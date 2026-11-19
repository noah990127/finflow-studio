from copy import deepcopy

import pytest

from app.tacit.derivations import build_candidates, generate_cqs, generate_flows, render_reports, walkthrough
from app.tacit.derivations_walkthrough import evaluate, path_catalog


def unconditional():
    return {"operator": "always", "expression": "资料登记后继续"}


def pred(op, field="容量", value=315, unit="kVA"):
    return {"operator": "predicate", "field": field, "comparator": op, "value": value, "unit": unit}


def business():
    quote = "本教学案例仅按315kVA及以下划分复核路径。计量方式另行核实。"
    evidence = [{"source_id": "src", "clause_id": "c1", "quote": quote}]
    steps = [
        {"step_id": "s", "name": "收到申请", "kind": "start", "inputs": ["容量"]},
        {"step_id": "d", "name": "核对容量", "kind": "decision"},
        {"step_id": "e", "name": "常规复核", "kind": "end", "outputs": ["复核路径"]},
        {"step_id": "x", "name": "专项复核", "kind": "end", "outputs": ["复核路径"]},
    ]
    edges = [
        {"branch_id": "sd", "from_step_id": "s", "to_step_id": "d", "condition": unconditional()},
        {"branch_id": "de", "from_step_id": "d", "to_step_id": "e", "condition": pred("lte"), "rule_ids": ["r"]},
        {"branch_id": "dx", "from_step_id": "d", "to_step_id": "x", "condition": pred("gt"), "is_exception": True},
    ]
    process = {"process_id": "p", "name": "容量复核", "scope": "教学", "trigger": "申请", "roles": ["经办人"],
               "inputs": ["容量"], "outputs": ["复核路径"], "steps": steps, "branches": edges,
               "rule_ids": ["r"], "status": "source_supported", "evidence": evidence}
    cases = []
    for kind, value, end, answer in [("normal", 200, "e", "常规复核"), ("boundary", 315, "e", "常规复核"),
                                     ("exception", 316, "x", "专项复核")]:
        cases.append({"case_id": kind, "kind": kind, "name": str(value), "process_ids": ["p"],
                      "rule_ids": ["r"] if end == "e" else [], "expected_path": ["s", "d", end],
                      "expected_outputs": {"复核路径": answer}, "inputs": {"容量": {"unit": "kVA", "value": value}}, "status": "defined"})
    return {"scope": {"goal": "核实复核路径", "in_scope": ["教学"]},
            "sources": [{"source_id": "src", "title": "教学材料", "read_status": "read",
                         "clauses": [{"clause_id": "c1", "locator": "第一段", "quote": quote}]}],
            "rules": [{"rule_id": "r", "name": "容量分档", "scope": "教学", "results": ["常规复核"],
                       "inputs": ["容量"], "outputs": ["复核路径"], "condition": pred("lte"),
                       "status": "source_supported", "evidence": evidence, "process_ids": ["p"]}],
            "processes": [process], "gaps": [], "acceptance_cases": cases}


def run(spec):
    flows = generate_flows(spec)
    return walkthrough(spec, flows, build_candidates(spec, flows))


def test_reports_render_unresolved_conditions_and_evidence_reproducibly():
    spec = business()
    spec["rules"][0]["condition"] = {"operator": "all", "clauses": [pred("lte"), {"operator": "unresolved"}]}
    spec["gaps"].append({"question": "是否确有安装困难？", "gap_id": "g", "impact": "计量方式待定",
                         "rule_ids": ["r"], "blocking": True, "status": "open", "evidence": []})
    reports = render_reports(spec)
    assert render_reports(deepcopy(spec)) == reports
    for text in ("业务范围", "业务目标", "业务规则", "业务流程", "教学材料", "第一段", "315 kVA", "且", "待确认条件", "安装困难"):
        assert text in reports["business_report"]
    assert "计量方式待定" in reports["gap_report"]
    diagram = generate_flows(spec)[0]
    assert diagram["gap_ids"] == ["g"] and "待确认" in diagram["content"]


def test_rendered_mermaid_uses_only_generated_node_ids_and_literal_labels():
    spec = business()
    spec["processes"][0]["steps"][1]["name"] = 'x"]\nclick x "https://x" %%{init:<b>}'
    flow = generate_flows(spec)[0]
    assert len(flow["content"].splitlines()) == 8
    assert "\nclick" not in flow["content"] and "<" not in flow["content"] and "%%" not in flow["content"]
    assert "判" in generate_cqs(spec)[0]["cq_type"]
    assert generate_flows(deepcopy(spec)) == [flow]


@pytest.mark.parametrize("fault", ["dangling", "unreachable", "rule", "unbound_output", "decision"])
def test_invalid_graph_not_saved(fault):
    spec = business()
    p = spec["processes"][0]
    if fault == "dangling":
        p["branches"][0]["to_step_id"] = "none"
    elif fault == "unreachable":
        p["steps"].append({"step_id": "lost", "name": "无前驱", "kind": "step"})
    elif fault == "rule":
        p["steps"][0]["rule_ids"] = ["not-in-process"]
    elif fault == "unbound_output":
        p["outputs"].append("未绑定")
    else:
        p["branches"].pop()
        p["steps"].pop()
    with pytest.raises(ValueError):
        generate_flows(spec)


def test_candidates_require_same_review_snapshot_and_locatable_evidence():
    spec = business()
    flows = generate_flows(spec)
    entries = build_candidates(spec, flows)
    assert {c["kind"] for c in entries} == {"Logic", "Function"}
    assert all(c["review_status"] == "pending" and c["evidence"] for c in entries)
    flows[0]["content"] += "\nA --> B"
    with pytest.raises(ValueError):
        build_candidates(spec, flows)
    spec["rules"][0]["evidence"] = [{"source_id": "invented", "clause_id": "other", "quote": "假引文"}]
    with pytest.raises(ValueError):
        build_candidates(spec, generate_flows(spec))


def test_rule_and_case_projection_is_stable_and_distinguishes_semantic_origin():
    spec = business()
    cqs = generate_cqs(spec)
    assert len({c["cq_id"] for c in cqs}) == len(cqs) >= 4
    for key in ("source", "expected_business_answer", "modeling_requirement", "technical_validation_question", "review_status"):
        assert all(c[key] for c in cqs)
    spec["rules"][0]["name"] = "https://example.org/private"
    assert all("https://" not in c["cq_text"] for c in generate_cqs(spec))
    assert [c["cq_id"] for c in cqs] == [c["cq_id"] for c in generate_cqs(spec)]


def test_boundary_replay_proves_only_the_path_and_checks_all_supplied_cases():
    result = run(business())
    assert result["status"] == "needs_review"
    process = result["processes"][0]
    assert not process["truncated"] and not process["uncovered_paths"]
    assert all(s["status"] == "path_verified" for s in process["scenarios"])
    paths = {s["case_id"]: s["path_id"] for s in process["scenarios"]}
    assert paths["normal"] == paths["boundary"] != paths["exception"]
    assert "业务产出待独立核对" in result["modeling_gap_report"]


@pytest.mark.parametrize("value", [None, {"value": 315, "unit": "VA"}, True, "315"])
def test_missing_or_wrongly_typed_quantities_stay_unknown(value):
    assert evaluate(pred("lte"), {"容量": value}) is None
    assert evaluate(pred("lte"), {}) is None
    assert evaluate({"operator": "expression", "expression": "some business predicate"}, {"容量": 315}) is None


def test_ambiguous_branches_path_deviation_and_missing_facts_are_reported():
    spec = business()
    spec["acceptance_cases"][0]["expected_path"] = ["s", "d", "x"]
    result = run(spec)
    assert "branch_deviation" in {f["code"] for f in result["findings"]}
    spec["processes"][0]["branches"][-1]["condition"] = pred("gte", value=200)
    assert "ambiguous_branch" in {f["code"] for f in run(spec)["findings"]}
    spec["processes"][0]["branches"][-1]["condition"] = {"operator": "unresolved"}
    assert "unresolved_branch" in {f["code"] for f in run(spec)["findings"]}


def test_path_id_selection_expands_trusted_nodes_tampered_candidate_halts_at_step():
    spec = business()
    flow = generate_flows(spec)
    entries = build_candidates(spec, flow)
    path = next(p for p in path_catalog(flow[0])[0] if p["steps"][-1] == "e")
    spec["acceptance_cases"][0]["expected_path"] = [path["path_id"]]
    happy = walkthrough(spec, flow, entries)["processes"][0]["scenarios"][0]
    assert happy["path_id"] == path["path_id"]
    next(c for c in entries if c["key"] == "d")["evidence"] = []
    assert "missing_candidate" in {f["code"] for f in walkthrough(spec, flow, entries)["findings"]}


def test_catalog_has_explicit_limits_for_cycles_deep_graphs_and_many_paths():
    p = deepcopy(business()["processes"][0])
    p["branches"].append({"branch_id": "loop", "from_step_id": "d", "to_step_id": "d", "condition": unconditional()})
    paths, reasons = path_catalog(p)
    assert len(paths) == 2 and "循环路径" in reasons
    deep = {"process_id": "p", "steps": [{"step_id": str(i), "kind": "start" if i == 0 else "end" if i == 110 else "step"}
                                         for i in range(111)],
            "branches": [{"branch_id": str(i), "from_step_id": str(i), "to_step_id": str(i + 1)} for i in range(110)]}
    assert path_catalog(deep) == ([], ["长度上限"])
    deep["steps"] = deep["steps"][:8]
    deep["steps"][-1]["kind"] = "end"
    deep["branches"] = [dict(deep["branches"][i], branch_id=f"{i}:{b}") for i in range(7) for b in (1, 2)]
    paths, limits = path_catalog(deep)
    assert len(paths) == 64 and "数量上限" in limits


def test_metering_fixture_demands_configuration_difficulty_and_applicability():
    """The teaching fixture's thresholds select a branch only with all required facts."""
    # These are the test document's conditions; there is no domain policy in the engine.
    under_cap = pred("lte")
    high_voltage = pred("eq", field="供电电压", value=10, unit="kV")
    ordinary = pred("eq", field="适用情形", value="常规用电", unit="")
    difficulty = pred("eq", field="勘察结论", value="确有困难且有复核依据", unit="")
    low_branch = {"operator": "all", "clauses": [under_cap, high_voltage, ordinary, difficulty]}
    partial = {"供电电压": {"value": 10, "unit": "kV"}, "容量": {"value": 200, "unit": "kVA"}}
    assert evaluate(low_branch, partial) is None
    assert evaluate(low_branch, dict(partial, 附件已提交=True, 勘察备注="安装不便")) is None
    assert evaluate(low_branch, dict(partial, 适用情形="临时用电")) is False
    assert evaluate(low_branch, dict(partial, 适用情形="常规用电", 勘察结论="确有困难且有复核依据")) is True

    sources = pred("gte", field="电源数", value=2, unit="路")
    multiple_transformers = {"operator": "all", "clauses": [
        pred("eq", field="电源数", value=1, unit="路"), pred("gte", field="变压器数", value=2, unit="台")]}
    high_branch = {"operator": "any", "clauses": [sources, multiple_transformers]}
    assert evaluate(high_branch, dict(partial, 电源数=1)) is None
    assert evaluate(high_branch, dict(partial, 电源数=1, 变压器数=2)) is True
    assert evaluate(high_branch, dict(partial, 电源数=2)) is True


def test_missing_field_mapping_remains_visible_without_synthesizing_a_default():
    spec = business()
    spec["gaps"] = [{"gap_id": "g", "question": "计量方式变更后，所属侧、电压等级、变损标志分别如何映射？",
                     "impact": "三项联动口径均待核实", "status": "open", "blocking": True, "process_ids": ["p"]}]
    report = render_reports(spec)["gap_report"]
    assert all(name in report for name in ("所属侧", "电压等级", "变损标志"))
    scenarios = run(spec)["processes"][0]["scenarios"]
    assert all(s["findings"][0]["code"] == "blocking_gap" and s["segments"] == [] for s in scenarios)
