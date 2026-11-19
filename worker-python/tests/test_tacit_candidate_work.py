import asyncio
import json
from copy import deepcopy
from types import SimpleNamespace

import httpx
import pytest

from app.tacit import extraction, scenario_work
from app.tacit.candidate_work import handle_candidate_action
from app.tacit.derivations_graph import build_candidates, generate_flows, key
from app.tacit.engine import record
from app.tacit.workspace_state import new_state
from test_tacit_derivations import business
from test_tacit_lifecycle import FixtureModel, Session


@pytest.fixture
def work(monkeypatch):
    monkeypatch.setattr(scenario_work, "llm", SimpleNamespace(configured=False))
    spec = business()
    spec["completion"] = "complete"
    state = new_state()
    state["spec"] = spec
    flows = generate_flows(spec)
    record(state, "flows", flows, "approved")
    record(state, "candidates", build_candidates(spec, flows), "pending")
    state["artifacts"]["candidates"]["flow_digest"] = state["artifacts"]["flows"]["digest"]
    sources = {"src": {"text": spec["sources"][0]["clauses"][0]["quote"]}}

    def command(name, **payload):
        return asyncio.run(handle_candidate_action(state, name, payload, sources, "expert"))
    return state, command


def test_edit_extend_stage_checks_and_replay_signed_items(work):
    state, command = work
    candidates = state["artifacts"]["candidates"]["content"]
    node = next(i for i in candidates if i["key"] == "d")
    extension = {"kind": "Object", "name": "申请", "process_id": "p", "key": "application",
                 "definition": {"description": "教学申请"}, "evidence": node["evidence"]}
    oid = key("cand", "Object", "p", "application")
    command("edit_candidates", extensions=[extension], patches=[{"candidate_id": node["candidate_id"],
            "name": "校核教学容量", "definition": {**node["definition"], "references": [oid]}}])
    assert command("check_candidates")["valid"]
    with pytest.raises(ValueError, match="审核"):
        command("review_candidate_item", candidate_id=node["candidate_id"], decision="approved")
    command("submit_candidates")
    for row in state["artifacts"]["candidates"]["content"]:
        command("review_candidate_item", candidate_id=row["candidate_id"], decision="approved")
    command("validate_ontology")
    scenario = state["artifacts"]["walkthrough"]["content"]["processes"][0]["scenarios"][0]
    trace = next(s for s in scenario["segments"][0] if s["step_id"] == "d")
    assert trace["candidate_name"] == "校核教学容量"
    assert trace["references"][0]["candidate_id"] == oid
    assert trace["references"][0]["evidence"] == node["evidence"]
    command("edit_candidates", remove_ids=[oid])
    assert state["artifacts"]["walkthrough"]["status"] == "stale"
    assert command("check_candidates")["issues"][0]["code"] == "invalid_reference"
    with pytest.raises(ValueError, match="校验"):
        command("submit_candidates")


@pytest.mark.parametrize("evidence", [[], [{"source_id": "another-project", "clause_id": "c1", "quote": "教学"}],
                                     [{"source_id": "src", "clause_id": "c1", "quote": "偽造的原文"}]])
def test_review_requires_original_evidence(work, evidence):
    state, command = work
    cid = state["artifacts"]["candidates"]["content"][0]["candidate_id"]
    command("edit_candidates", patches=[{"candidate_id": cid, "evidence": evidence}])
    assert command("check_candidates")["issues"][0]["code"] == "invalid_evidence"
    with pytest.raises(ValueError, match="校验"):
        command("submit_candidates")


def test_candidate_fact_divergence_remains_visible(work):
    state, command = work
    node = next(r for r in state["artifacts"]["candidates"]["content"] if r["key"] == "d")
    changed = deepcopy(node["definition"])
    changed["step"]["kind"] = "end"
    command("edit_candidates", patches=[{"candidate_id": node["candidate_id"], "definition": changed}])
    command("submit_candidates")
    command("review_candidate", decision="approved")
    command("validate_ontology")
    assert "candidate_mismatch" in {f["code"] for f in state["artifacts"]["walkthrough"]["content"]["findings"]}


def test_human_scenarios_grounded_in_real_paths_and_stale_invalidation(work):
    state, command = work
    command("review_candidate", decision="approved")
    spec = state["spec"]
    spec["acceptance_cases"] = []
    command("propose_scenarios")
    rows = state["artifacts"]["scenarios"]["content"]
    assert {r["kind"] for r in rows} == {"normal", "exception", "boundary"}
    assert all(r["evidence"] and not r["expected_outputs"] and r["origin"] == "graph" for r in rows)
    command("validate_ontology")
    assert "missing_case" in {f["code"] for f in state["artifacts"]["walkthrough"]["content"]["findings"]}
    boundary = next(r for r in rows if r["kind"] == "boundary")
    assert boundary["inputs"]["容量"] == {"value": 315, "unit": "kVA"}
    with pytest.raises(ValueError, match="输入无法验证"):
        command("review_scenario", scenario_id=boundary["scenario_id"], decision="approved",
                inputs={"容量": {"value": 313, "unit": "kVA"}}, expected_outputs={"复核路径": "常规"})
    with pytest.raises(ValueError, match="预期输出"):
        command("review_scenario", scenario_id=boundary["scenario_id"], decision="approved")
    for row in rows:
        if row["kind"] == "exception":
            command("review_scenario", scenario_id=row["scenario_id"], decision="rejected", comment="退回待另补")
        else:
            command("review_scenario", scenario_id=row["scenario_id"], decision="approved", expected_outputs={"复核路径": "常规"})
    assert state["artifacts"]["scenarios"]["status"] == "ready"
    command("validate_ontology")
    report = state["artifacts"]["walkthrough"]["content"]
    assert all(s["status"] == "path_verified" for s in report["processes"][0]["scenarios"])
    assert {s["kind"] for s in report["processes"][0]["scenarios"]} == {"normal", "boundary"}
    assert not spec["acceptance_cases"]
    assert command("propose_scenarios")["count"] == 3
    spec["rules"][0]["results"] = ["修订"]
    with pytest.raises(ValueError, match="已变化"):
        command("review_scenario", scenario_id=boundary["scenario_id"], decision="rejected")


def test_draft_scenario_can_close_case_gaps_via_real_http(monkeypatch):
    monkeypatch.setattr(extraction, "llm", FixtureModel())
    monkeypatch.setattr(scenario_work, "llm", SimpleNamespace(configured=False))
    client = Session()
    client.set_scope()
    client.post("extract", {"source_id": "upload", "offset": 0})
    original = client.state["spec"]["acceptance_cases"][0]
    client.post("remove_item", {"section": "acceptance_cases", "item_id": original["case_id"]})
    assert "rule_without_case" in {i["code"] for i in client.state["issues"]}
    client.post("finalize", {"completion": "draft"})
    client.post("generate_flow")
    client.post("review_flow", {"decision": "approved"})
    client.post("propose_scenarios")
    row, = client.state["artifacts"]["scenarios"]["content"]
    result = client.post("review_scenario", {"scenario_id": row["scenario_id"], "decision": "approved",
                         "inputs": {"采购单号": "PO-2"}, "expected_outputs": {"回执": "已出具"}, "comment": "核对登记依据"})
    assert result["adopted"]
    assert client.state["issues"] == []
    assert client.state["spec"]["acceptance_cases"][0]["rule_ids"]
    client.post("finalize", {"completion": "complete"})
    client.post("generate_flow")
    client.post("review_flow", {"decision": "approved"})
    client.post("build_candidate")
    client.post("review_candidate", {"decision": "approved"})
    client.post("validate_ontology")
    assert client.state["artifacts"]["walkthrough"]["content"]["processes"][0]["scenarios"][0]["status"] == "path_verified"


def test_model_proposals_cannot_introduce_a_path_or_cross_units(work, monkeypatch):
    state, command = work
    state["spec"]["acceptance_cases"] = []

    class Model:
        configured = True

        async def complete(self, system, prompt):
            result = []
            for group in json.loads(prompt):
                path_id = group["paths"][0]["path_id"]
                if group["kind"] == "normal":
                    path_id = "invented-path"
                unit = "V" if group["kind"] == "boundary" else "kVA"
                result.append({"process_id": group["process_id"], "kind": group["kind"], "path_id": path_id,
                               "inputs": {"容量": {"value": 316, "unit": unit}}})
            return json.dumps(result)
    monkeypatch.setattr(scenario_work, "llm", Model())
    result = command("propose_scenarios")
    rows = {r["kind"]: r for r in state["artifacts"]["scenarios"]["content"]}
    assert all(r["path_id"] != "invented-path" for r in rows.values())
    assert rows["normal"]["origin"] == rows["boundary"]["origin"] == "graph"
    assert rows["exception"]["origin"] == "model"
    assert result["diagnostics"]


def test_model_outage_recovers_and_existing_mislabeled_case_is_detected(work, monkeypatch):
    state, command = work
    spec = state["spec"]
    next(c for c in spec["acceptance_cases"] if c["kind"] == "boundary")["inputs"]["容量"]["value"] = 314

    class Offline:
        configured = True

        async def complete(self, *_):
            raise httpx.ConnectError("unavailable")

    monkeypatch.setattr(scenario_work, "llm", Offline())
    command("review_candidate", decision="approved")
    result = command("propose_scenarios")
    assert result["count"] == 1 and result["diagnostics"]
    row, = state["artifacts"]["scenarios"]["content"]
    assert row["kind"] == "boundary" and row["origin"] == "graph"
    command("validate_ontology")
    assert "untested_boundary" in {f["code"] for f in state["artifacts"]["walkthrough"]["content"]["findings"]}


def test_additional_case_for_uncovered_rule_with_same_normal_path(work):
    state, command = work
    spec = state["spec"]
    proc = spec["processes"][0]
    second = {**deepcopy(spec["rules"][0]), "rule_id": "r2", "name": "临界专项核验"}
    second["condition"] = {"operator": "predicate", "field": "模式", "comparator": "eq", "value": "需核验"}
    spec["rules"].append(second)
    proc["rule_ids"].append("r2")
    proc["branches"][1]["rule_ids"].append("r2")
    for case in spec["acceptance_cases"]:
        case["inputs"]["模式"] = "常规"
    record(state, "flows", generate_flows(spec), "approved")
    command("propose_scenarios")
    rows = state["artifacts"]["scenarios"]["content"]
    assert any(r["inputs"].get("模式") == "需核验" and "临界" in r["name"] for r in rows)


def test_partial_expected_outputs_rejected_by_review(work):
    state, command = work
    spec = state["spec"]
    proc = spec["processes"][0]
    proc["outputs"].append("复核说明")
    proc["steps"][2]["outputs"].append("复核说明")
    spec["acceptance_cases"].clear()
    record(state, "flows", generate_flows(spec), "approved")
    command("propose_scenarios")
    row = next(r for r in state["artifacts"]["scenarios"]["content"] if r["kind"] == "normal")
    assert set(row["output_fields"]) == {"复核路径", "复核说明"}
    with pytest.raises(ValueError, match="所有预期输出"):
        command("review_scenario", scenario_id=row["scenario_id"], decision="approved",
                expected_outputs={"复核路径": "常规"})
    command("review_scenario", scenario_id=row["scenario_id"], decision="approved",
            expected_outputs={"复核路径": "常规", "复核说明": "依据教学口径"})


def test_removal_of_step_in_approved_flow_blocks_candidate_submission(work):
    state, command = work
    node = next(r for r in state["artifacts"]["candidates"]["content"] if r["key"] == "d")
    command("edit_candidates", remove_ids=[node["candidate_id"]])
    assert {"missing_step_candidate"} == {i["code"] for i in command("check_candidates")["issues"]}
    with pytest.raises(ValueError, match="流程步骤"):
        command("submit_candidates")


def test_direct_validation_proposes_missing_cases_once_after_candidate_approval(work):
    state, command = work
    state["spec"]["acceptance_cases"] = []
    art = state["artifacts"]
    with pytest.raises(ValueError, match="candidates"):
        command("validate_ontology")
    assert "scenarios" not in art
    command("review_candidate", decision="approved")
    result = command("validate_ontology")
    assert result["status"] == "incomplete" and result["pending_scenario_count"] == 3
    assert art["walkthrough"]["content"]["pending_scenario_count"] == 3
    assert not art["walkthrough"]["content"]["processes"][0]["scenarios"]
    original = deepcopy(art["scenarios"]["content"])
    command("validate_ontology")
    assert art["scenarios"]["content"] == original
    normal = next(r for r in original if r["kind"] == "normal")
    command("review_scenario", scenario_id=normal["scenario_id"], decision="approved", expected_outputs={"复核路径": "常规"})
    assert command("validate_ontology")["pending_scenario_count"] == 2
    verified, = art["walkthrough"]["content"]["processes"][0]["scenarios"]
    assert verified["case_id"] == normal["scenario_id"] and verified["status"] == "path_verified"
    art["scenarios"]["status"] = "stale"
    assert command("validate_ontology")["pending_scenario_count"] == 3
    assert not art["walkthrough"]["content"]["processes"][0]["scenarios"]
