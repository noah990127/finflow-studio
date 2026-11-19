"""HTTP contract from a project-scoped source snapshot to a reviewed ledger and ontology.

The fixture supplies only the model's JSON response. All other stages run through
the real FastAPI command handler, extraction, deterministic validation and derivation.
"""
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.tacit import extraction
from app.tacit.api import router

POLICY = "本项目的每张采购单以采购单号识别。收到采购单后，登记员录入单号并出具回执。"


def model_response():
    quote = POLICY.split("。")[1] + "。"
    def fact(data, excerpt=quote):
        return {**data, "status": "source_supported", "evidence": [{"unit": "u1", "quote": excerpt}]}

    return {"items": {
        "data_objects": [fact({"object_id": "order", "name": "采购单", "identity_basis": "采购单号",
                               "fields": [{"name": "采购单号", "business_name": "采购单号", "data_type": "文本"}]},
                              POLICY.split("。")[0] + "。")],
        "rules": [fact({"rule_id": "record", "name": "采购单登记", "scope": "收到采购单后",
                        "inputs": ["采购单号"], "outputs": ["回执"], "results": ["出具回执"],
                        "condition": {"operator": "predicate", "field": "采购单号", "comparator": "exists"},
                        "process_ids": ["issue"]})],
        "processes": [fact({"process_id": "issue", "name": "收单登记", "scope": "采购单登记",
                            "trigger": "收到采购单", "roles": ["登记员"], "inputs": ["采购单号"],
                            "outputs": ["回执"], "rule_ids": ["record"],
                            "steps": [{"step_id": "s", "name": "收到采购单", "kind": "start", "inputs": ["采购单号"]},
                                      {"step_id": "m", "name": "登记", "kind": "step", "rule_ids": ["record"],
                                       "actor": "登记员"},
                                      {"step_id": "e", "name": "出具回执", "kind": "end", "outputs": ["回执"]}],
                            "branches": [{"branch_id": k, "from_step_id": a, "to_step_id": b,
                                          "condition": {"operator": "always", "expression": "按登记顺序办理"}}
                                         for k, a, b in [("sm", "s", "m"), ("me", "m", "e")]]})],
        "acceptance_cases": [{"case_id": "issued", "name": "有单号的采购单", "kind": "normal",
                              "status": "defined", "process_ids": ["issue"], "rule_ids": ["record"],
                              "inputs": {"采购单号": "PO-1"}, "expected_outputs": {"回执": "已出具"},
                              "expected_path": ["s", "m", "e"]}],
    }, "excluded": []}


class FixtureModel:
    configured = True

    def __init__(self):
        self.prompts = []

    async def complete(self, system, user):
        prompt = json.loads(user)
        self.prompts.append(prompt)
        output = model_response()
        for rows in output["items"].values():
            for row in rows:
                for evidence in row.get("evidence", []):
                    evidence["unit"] = next(k for k, text in prompt["passages"].items() if evidence["quote"] in text)
        return json.dumps(output, ensure_ascii=False)


class Session:
    def __init__(self):
        app = FastAPI()
        app.include_router(router)
        self.client = TestClient(app)
        self.state = {}
        self.catalog = [{"source_id": "upload", "title": "项目登记规范", "source_type": "asset", "version": "1",
                         "text": POLICY}]

    def post(self, action, payload=None, expected=200):
        response = self.client.post("/tacit/command", json={"state": self.state, "action": action,
                                    "payload": payload or {}, "catalog": self.catalog, "actor": "expert-42"})
        assert response.status_code == expected, response.text
        if expected != 200:
            return response.json()["detail"]
        result = response.json()
        self.state = result["state"]
        return result["result"]

    def set_scope(self):
        self.post("save_batch", {"section": "scope", "scope": {"goal": "核对采购登记与回执", "in_scope": ["采购单"]}})


@pytest.fixture
def session(monkeypatch):
    model = FixtureModel()
    monkeypatch.setattr(extraction, "llm", model)
    return Session(), model


def test_command_lifecycle_approvals_and_replacement(session):
    client, model = session
    client.set_scope()
    page = client.post("extract", {"source_id": "upload", "offset": 0})
    assert page["next_offset"] is None
    assert "".join(model.prompts[0]["passages"].values()) == POLICY
    assert client.state["coverage"]["upload"] == [[0, len(POLICY)]]
    assert client.state["issues"] == []
    rule = client.state["spec"]["rules"][0]
    client.post("confirm_item", {"section": "rules", "item_id": rule["rule_id"],
                                 "text": "我确认这条采购登记规则适用于本项目。"})
    assert client.state["spec"]["rules"][0]["status"] == "user_confirmed"
    assert client.state["issues"] == []
    client.post("finalize", {"completion": "complete"})
    artifacts = client.state["artifacts"]
    assert "采购单登记" in artifacts["business_report"]["content"] and artifacts["gap_report"]["status"] == "ready"
    client.post("generate_flow")
    assert "flowchart TD" in client.state["artifacts"]["flows"]["content"][0]["content"]
    assert client.post("build_candidate", expected=422)
    client.post("review_flow", {"decision": "approved", "comment": "已按三步逐项复核"})
    client.post("generate_cqs")
    q = client.state["artifacts"]["cqs"]["content"][0]
    assert q["expected_business_answer"] and q["modeling_requirement"] and q["source"]["evidence"]
    client.post("review_cq", {"cq_id": q["cq_id"], "decision": "accepted"})
    client.post("build_candidate")
    assert all(row["review_status"] == "pending" for row in client.state["artifacts"]["candidates"]["content"])
    assert client.post("validate_ontology", expected=422)
    client.post("review_candidate", {"decision": "approved"})
    client.post("validate_ontology")
    report = client.state["artifacts"]["walkthrough"]["content"]
    assert report["processes"][0]["scenarios"][0]["status"] == "path_verified"
    # A technically valid path still requires expert review of the business result.
    assert report["status"] == "needs_review"
    client.catalog[0].update(version="2", text=POLICY + "新版本另有一项未明确的例外。")
    client.post("sync_sources")
    assert "upload" not in client.state["coverage"]
    assert client.state["spec"]["rules"][0]["status"] == "proposed"
    assert all(artifact["status"] == "stale" for artifact in client.state["artifacts"].values())
    assert client.post("build_candidate", expected=422)


def test_preview_partial_read_and_untrusted_confirmation(session):
    client, _ = session
    client.catalog[0]["text"] = POLICY + "待核对。"*2200
    client.set_scope()
    client.post("preview_source", {"source_id": "upload"})
    assert not client.state["coverage"]
    page = client.post("read_source", {"source_id": "upload"})
    assert page["end"] == 8000 and page["next_offset"] == 8000
    assert "source_unread" in {i["code"] for i in client.state["issues"]}
    assert client.post("finalize", {"completion": "complete"}, expected=422)
    client.post("finalize", {"completion": "draft"})
    assert client.state["artifacts"]["business_report"]["status"] == "draft"

    client.catalog[0].update(text=POLICY, version="2")
    client.post("extract", {"source_id": "upload"})
    rule = dict(client.state["spec"]["rules"][0], status="user_confirmed")
    detail = client.post("save_batch", {"section": "rules", "items": [rule]}, expected=422)
    assert "invalid_confirmation" in detail
    assert client.state["spec"]["rules"][0]["status"] == "source_supported"
    assert not client.state["user_sources"]


@pytest.mark.parametrize("padding", [0, 1900])
def test_table_read_status_follows_final_source_coverage(session, monkeypatch, padding):
    client, _ = session
    ddl = "登记表 PAY_LOG 的单号列为 req_no，类型为 TEXT。"
    client.catalog[0]["text"] = ddl + "\n" + ("资料封面。\n" * padding)

    class TableModel:
        configured = True

        async def complete(self, system, user):
            passages = json.loads(user)["passages"]
            table = next((unit for unit, text in passages.items() if ddl in text), None)
            items = {}
            if table:
                items["data_tables"] = [{"table_id": "t", "name": "PAY_LOG", "locator": "资料首行",
                                         "columns": [{"name": "req_no", "data_type": "TEXT", "business_name": "采购单号"}],
                                         "evidence": [{"unit": table, "quote": ddl}], "status": "source_supported"}]
            return json.dumps({"items": items, "excluded": [{"unit": unit, "reason": "无业务内容的封面"}
                              for unit in passages if unit != table]}, ensure_ascii=False)

    monkeypatch.setattr(extraction, "llm", TableModel())
    client.set_scope()
    cursor = client.post("extract", {"source_id": "upload"})["next_offset"]
    if cursor is not None:
        assert "table_unread" in {i["code"] for i in client.state["issues"]}
    while cursor is not None:
        cursor = client.post("extract", {"source_id": "upload", "offset": cursor})["next_offset"]
    assert client.state["spec"]["data_tables"][0]["read_status"] == "read"
    assert "table_unread" not in {i["code"] for i in client.state["issues"]}


def test_old_confirmation_cannot_follow_edited_or_recreated_item(session):
    client, _ = session
    client.set_scope()
    client.post("extract", {"source_id": "upload"})
    first = client.state["spec"]["rules"][0]
    confirmation = {"section": "rules", "item_id": first["rule_id"], "text": "我确认当前版本的采购单登记口径。"}
    client.post("confirm_item", confirmation)
    signed = client.state["spec"]["rules"][0]
    client.post("save_batch", {"section": "rules", "items": [{**signed, "scope": "修改后的范围"}]})
    assert client.state["spec"]["rules"][0]["status"] == "proposed"
    assert "invalid_confirmation" in client.post("save_batch", {"section": "rules", "items": [signed]}, expected=422)
    client.post("confirm_item", confirmation)
    signed = client.state["spec"]["rules"][0]
    client.post("remove_item", {"section": "rules", "item_id": first["rule_id"]})
    assert "invalid_confirmation" in client.post("save_batch", {"section": "rules", "items": [signed]}, expected=422)


def test_draft_does_not_present_fabricated_rule_evidence_as_supported(session):
    client, _ = session
    client.set_scope()
    client.post("extract", {"source_id": "upload"})
    source = client.state["spec"]["sources"][0]
    false_text = "所有采购单均可免予复核。"
    rule = client.state["spec"]["rules"][0]
    cited = rule["evidence"][0]["clause_id"]
    clauses = [{**c, "quote": false_text} if c["clause_id"] == cited else c for c in source["clauses"]]
    client.post("save_batch", {"section": "sources", "items": [{**source, "clauses": clauses}]})
    rule = {**rule, "evidence": [{**rule["evidence"][0], "quote": false_text}], "results": ["免予复核"]}
    client.post("save_batch", {"section": "rules", "items": [rule]})
    assert "fabricated_quote" in {issue["code"] for issue in client.state["issues"]}
    client.post("finalize", {"completion": "draft"})
    draft = client.state["artifacts"]["business_report"]["content"]
    rules = draft.split("## 业务规则", 1)[1].split("## 业务流程", 1)[0]
    assert "事实状态：材料支持" not in rules
    assert client.post("finalize", {"completion": "complete"}, expected=422)
