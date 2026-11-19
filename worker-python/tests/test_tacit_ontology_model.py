from copy import deepcopy

import pytest

from app.tacit import extraction
from app.tacit.ontology_model import build_ontology_model
from app.tacit.workspace_state import digest
from test_tacit_lifecycle import FixtureModel, Session


def reviewed_procurement(monkeypatch, tables=False):
    monkeypatch.setattr(extraction, "llm", FixtureModel())
    session = Session()
    session.set_scope()
    session.post("extract", {"source_id": "upload"})
    if tables:
        ddl = ("PO header table: order_id TEXT PRIMARY KEY. "
               "PO_LINES: order_id TEXT REFERENCES PO.order_id, line_id INT, old_no TEXT REFERENCES ARCHIVE.no.")
        session.catalog.append({"source_id": "ddl", "source_type": "asset", "title": "采购库结构", "version": "1", "text": ddl})
        session.post("read_source", {"source_id": "ddl"})
        ev = [{"source_id": "ddl", "clause_id": "design", "quote": ddl}]
        tables = [{"table_id": "head", "name": "PO", "columns": [
            {"name": "order_id", "business_name": "采购单号", "data_type": "TEXT", "primary_key": True}]},
            {"table_id": "line", "name": "PO_LINES", "columns": [
                {"name": "order_id", "foreign_key": "PO.order_id", "data_type": "TEXT", "primary_key": True},
                {"name": "line_id", "data_type": "INT", "primary_key": True},
                {"name": "old_no", "foreign_key": "ARCHIVE.no", "data_type": "TEXT"}]}]
        src = next(s for s in session.state["spec"]["sources"] if s["source_id"] == "ddl")
        session.post("save_batch", {"section": "sources", "items": [dict(src, content_kind="table_structure", clauses=[
            {"clause_id": "design", "quote": ddl, "locator": f"字符[0,{len(ddl)})", "coverage": "extracted",
             "data_table_ids": ["head", "line"]}])]})
        session.post("save_batch", {"section": "data_tables", "items": [
            dict(t, evidence=ev, status="source_supported", locator="采购库结构") for t in tables]})
    session.post("finalize", {"completion": "complete"})
    session.post("generate_flow")
    session.post("review_flow", {"decision": "approved"})
    session.post("build_candidate")
    session.post("review_candidate", {"decision": "approved"})
    return session


def test_reviewed_objects_rules_and_cq_round_trip_in_model_and_manifest(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    s.post("generate_cqs")
    q = s.state["artifacts"]["cqs"]["content"][0]
    s.post("review_cq", {"cq_id": q["cq_id"], "decision": "accepted"})
    before = deepcopy(s.state)
    built = build_ontology_model(s.state, "A")
    assert built == build_ontology_model(deepcopy(s.state), "A")
    native, manifest = built["model"], built["manifest"]
    db, = native["data_layer"]["databases"]
    schema, = db["schemas"]
    table, = schema["tables"]
    column, = table["columns"]
    term, = native["semantic_layer"]["terms"]
    assert schema["name"] == "finflow_objects" and schema["database_name"] == built["database_name"]
    assert table["name"] == "采购单" and table["type"] == "LOGICAL OBJECT" and table["pk"] == []
    assert column["name"] == "采购单号" and column["type"] == "文本"
    assert term["represents"] == [table["id"]] and term["columns_attributes"][0]["column_id"] == column["id"]
    assert all(not entries for entries in native["semantic_layer"]["sql_attributes"].values())
    assert native["semantic_layer"]["custom_analyses"] == native["data_layer"]["foreign_keys"] == []
    assert built["digest"] == digest(native) == manifest["model_digest"]
    assert [q["cq_id"] for q in manifest["competency_questions"]] == [q["cq_id"]]
    assert manifest["sources"][0]["clauses"][0]["locator"]
    assert manifest["review"]["mode"] == "item_and_set"
    for row in manifest["candidates"]:
        assert row["review"]["by"] == "expert-42" and row["evidence"]
        assert row["mapping"]["kind"] == ("logical_object" if row["kind"] == "Object" else "manifest_only")
    assert s.state == before
    other = build_ontology_model(before, "B")
    assert other["database_name"] != built["database_name"]
    assert other["model"]["semantic_layer"]["terms"][0]["name"] != term["name"]
    # Readers may annotate the returned manifest without mutating signed workspace data.
    manifest["candidates"][0]["definition"].clear()
    assert s.state == before


def test_physical_tables_only_link_explicit_resolved_foreign_keys(monkeypatch):
    s = reviewed_procurement(monkeypatch, tables=True)
    r = build_ontology_model(s.state, "procurement")
    db, = r["model"]["data_layer"]["databases"]
    schema = next(s for s in db["schemas"] if s["name"] == "finflow_tables")
    tables = {t["name"]: t for t in schema["tables"]}
    head, line = tables["PO"], tables["PO_LINES"]
    assert head["pk"] == ["order_id"] and line["pk"] == ["order_id", "line_id"]
    assert head["columns"][0]["is_unique"] and not any(c["is_unique"] for c in line["columns"])
    assert not line["columns"][0]["is_nullable"] and line["columns"][2]["is_nullable"]
    assert r["model"]["data_layer"]["foreign_keys"] == [{
        "source_column_id": line["columns"][0]["id"], "target_column_id": head["columns"][0]["id"]}]
    warning, = r["manifest"]["warnings"]
    assert warning["code"] == "unresolved_foreign_key" and warning["declaration"] == "ARCHIVE.no"
    assert r["counts"]["physical_tables"] == 2 and r["counts"]["column_attributes"] == 5


def test_ambiguous_foreign_key_kept_as_review_evidence(monkeypatch):
    s = reviewed_procurement(monkeypatch, tables=True)
    table = next(r for r in s.state["artifacts"]["candidates"]["content"] if r["key"] == "head")
    s.post("edit_candidates", {"extensions": [{"kind": "Table", "name": "另一份采购表", "key": "head-copy",
                                               "process_id": "", "definition": table["definition"],
                                               "evidence": table["evidence"]}]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    r = build_ontology_model(s.state, "P")
    schemas = r["model"]["data_layer"]["databases"][0]["schemas"]
    native = next(v for v in schemas if v["name"] == "finflow_tables")["tables"]
    assert len(set(t["name"] for t in native)) == 3
    assert r["model"]["data_layer"]["foreign_keys"] == []
    assert {w["declaration"] for w in r["manifest"]["warnings"]} == {"PO.order_id", "ARCHIVE.no"}


@pytest.mark.parametrize("change", ["candidate_status", "set_digest", "item_digest", "flow_digest",
                                    "gap", "scope", "versions", "source", "row", "mixed"])
def test_incomplete_stale_or_damaged_approval_fails_closed(monkeypatch, change):
    s = reviewed_procurement(monkeypatch)
    state, arts = s.state, s.state["artifacts"]
    candidates = arts["candidates"]
    if change == "candidate_status":
        candidates["status"] = "pending"
    elif change == "set_digest":
        candidates["review"]["digest"] = "unsigned"
    elif change == "item_digest":
        candidates["content"][0]["review"]["digest"] = "old"
        candidates["review"]["digest"] = candidates["digest"] = digest(candidates["content"])
    elif change == "flow_digest":
        candidates["flow_digest"] = "old"
    elif change == "gap":
        state["spec"]["gaps"].append({"gap_id": "missing", "status": "open", "blocking": True})
    elif change == "scope":
        state["spec"]["scope"]["in_scope"] = []
    elif change == "versions":
        s.catalog[0]["version"] = "2"
        s.post("sync_sources")
        state = s.state
    elif change == "source":
        state["spec"]["sources"][0]["disposition"] = "excluded"
    elif change == "row":
        candidates["content"][0]["name"] += "更改"
    elif change == "mixed":
        del candidates["content"][0]["review"]
        candidates["review"]["digest"] = candidates["digest"] = digest(candidates["content"])
    with pytest.raises(ValueError):
        build_ontology_model(state, "P")


def test_legacy_whole_set_signature_matches_exact_preprojection_payload(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    art = s.state["artifacts"]["candidates"]
    for row in art["content"]:
        del row["review"]
    art["digest"] = digest(art["content"])
    art["review"]["digest"] = digest([dict(r, review_status="pending") for r in art["content"]])
    assert build_ontology_model(s.state, "P")["manifest"]["review"]["mode"] == "legacy_set"
    art["content"][-1]["name"] += "已改变"
    art["digest"] = digest(art["content"])
    with pytest.raises(ValueError, match="签名"):
        build_ontology_model(s.state, "P")


def test_signed_edits_change_namespace_and_non_native_only_explains_missing_inputs(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    first = build_ontology_model(s.state, "same-project")
    obj = next(r for r in s.state["artifacts"]["candidates"]["content"] if r["kind"] == "Object")
    s.post("edit_candidates", {"patches": [{"candidate_id": obj["candidate_id"], "name": "受理采购单"}]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    changed = build_ontology_model(s.state, "same-project")
    assert first["database_name"] != changed["database_name"]
    assert changed["model"]["semantic_layer"]["terms"][0]["name"].startswith("受理采购单 ")
    s.post("edit_candidates", {"remove_ids": [obj["candidate_id"]]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    with pytest.raises(ValueError, match="业务对象或已读取的表结构"):
        build_ontology_model(s.state, "same-project")


def test_offline_bundle_distinguishes_review_from_current_path_validation(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    offline = build_ontology_model(s.state, "P")
    assert offline["quality"]["scenario_validation"] == "not_run"
    assert not offline["quality"]["publication_ready"] and offline["quality"]["reasons"]
    s.post("validate_ontology")
    live = build_ontology_model(s.state, "P")
    assert live["quality"] == {"publication_ready": True, "reasons": [], "scenario_validation": "path_verified",
                               "business_outcome": "requires_independent_verification"}
    manifest = live["manifest"]
    assert live["digest"] != offline["digest"]
    assert manifest["acceptance_cases"] == s.state["spec"]["acceptance_cases"]
    proof = manifest["walkthrough"]["result"]["processes"][0]["scenarios"][0]
    assert proof["status"] == "path_verified" and proof["case_id"] == manifest["acceptance_cases"][0]["case_id"]
    assert manifest["quality"] == live["quality"]
    replay = s.state["artifacts"]["walkthrough"]
    replay["content"]["processes"][0]["scenarios"] = []
    replay["digest"] = digest(replay["content"])
    quality = build_ontology_model(s.state, "P")["quality"]
    assert not quality["publication_ready"] and quality["scenario_validation"] == "incomplete"
    s.state["artifacts"]["scenarios"]["baseline"] = "obsolete"
    assert build_ontology_model(s.state, "P")["quality"]["scenario_validation"] == "stale"


def test_oversize_offline_bundle_keeps_replay_evidence_and_gives_publish_reasons(monkeypatch):
    from app.tacit import ontology_model
    s = reviewed_procurement(monkeypatch)
    s.post("validate_ontology")
    monkeypatch.setattr(ontology_model, "TERM_LIMIT", 0)
    monkeypatch.setattr(ontology_model, "MODEL_LIMIT", 1)
    result = build_ontology_model(s.state, "P")
    assert result["quality"]["scenario_validation"] == "path_verified"
    assert result["quality"]["publication_ready"] is False
    assert len(result["quality"]["reasons"]) == 2 and result["model"]["semantic_layer"]["terms"]


def test_signed_proposed_scenario_is_preserved_and_failing_ledger_case_blocks_publication(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    original = s.state["spec"]["acceptance_cases"][0]
    broken = dict(original, inputs={"采购单号": None}, expected_outputs={"其他": "观察值"})
    s.post("save_batch", {"section": "acceptance_cases", "items": [broken]})
    s.post("finalize", {"completion": "complete"})
    s.post("generate_flow")
    s.post("review_flow", {"decision": "approved"})
    s.post("build_candidate")
    s.post("review_candidate", {"decision": "approved"})
    s.post("validate_ontology")
    proposal, = s.state["artifacts"]["scenarios"]["content"]
    pending = build_ontology_model(s.state, "P")
    assert any("待专家审核" in reason for reason in pending["quality"]["reasons"])
    s.post("review_scenario", {"scenario_id": proposal["scenario_id"], "decision": "approved",
                               "inputs": {"采购单号": "PO-42"}, "expected_outputs": {"回执": "已出具"}})
    s.post("validate_ontology")
    built = build_ontology_model(s.state, "P")
    signed, = built["manifest"]["scenarios"]
    assert signed["review"]["by"] == "expert-42" and signed["scenario_id"] == proposal["scenario_id"]
    assert signed["evidence"] and built["manifest"]["acceptance_cases"][0]["inputs"] == broken["inputs"]
    outcomes = built["manifest"]["walkthrough"]["result"]["processes"][0]["scenarios"]
    assert {r["status"] for r in outcomes} == {"path_verified", "incomplete"}
    assert built["quality"]["scenario_validation"] == "incomplete" and not built["quality"]["publication_ready"]
