"""Exercise real conversion, reference binding and versioned projection with a model fixture."""
import json
from copy import deepcopy

import pytest

from app.tacit import extraction
from app.tacit.derivations_walkthrough import evaluate
from app.tacit.engine import execute, new_state
from app.tacit.spec import ITEM_IDS, empty_spec, validate_spec


class Model:
    configured = True

    def __init__(self, *responses):
        self.responses = responses
        self.calls = []

    async def complete(self, system, prompt):
        self.calls.append(json.loads(prompt))
        response = self.responses[min(len(self.calls) - 1, len(self.responses) - 1)]
        return response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)


def ledger():
    s = empty_spec()
    s["scope"] = {"goal": "梳理教学资料中的供电计量口径", "in_scope": ["计量方式"]}
    return s


def material(text, **updates):
    return {"source_id": "training", "source_type": "asset", "version": "1", "title": "教学案例",
            "text": text, "read_status": "read", **updates}


def item_with_evidence(item, quote, unit="u1"):
    return {**item, "evidence": [{"unit": unit, "quote": quote}]}


def response(section, item, quote):
    return {"items": {section: [item_with_evidence(item, quote)]}, "excluded": []}


def gap(question):
    return {"gap_id": "g1", "question": question, "impact": "影响计量方案审核", "blocking": True}


def apply(spec, batches):
    for batch in batches:
        section = batch["section"]
        key = ITEM_IDS[section]
        rows = {row[key]: row for row in spec[section]}
        rows.update((row[key], row) for row in batch["items"])
        spec[section] = list(rows.values())


@pytest.mark.asyncio
@pytest.mark.parametrize(("text", "question"), [
    ("案例仅知供电电压 10kV 和容量 200kVA。电源配置、现场困难和政策适用性尚未核对。", "电源配置、困难依据及条款适用性如何？"),
    ("现场勘察记录只写有安装不便，未描述空间、安全或其他具体限制。", "哪些具体限制及证据能支持确有困难？"),
    ("附件已提交，尚未完成技术复核。", "谁将复核该附件并记录结论？"),
    ("专家提出临时用电的情境，相关适用条款待补。", "临时用电适用哪一条规定？"),
    ("变更计量方式后需核对所属侧、电压等级、变损标志，具体映射待补。", "所属侧、电压等级与变损标志的映射依据是什么？"),
])
async def test_unknowns_remain_blocking_with_source_locator(monkeypatch, text, question):
    source, spec = material(text), ledger()
    segments = [s.strip() + "。" for s in text.split("。") if s.strip()]
    doc = response("gaps", gap(question), segments[0])
    doc["items"]["gaps"][0]["evidence"] = [
        {"unit": f"u{i}", "quote": quote} for i, quote in enumerate(segments, 1)
    ]
    model = Model(doc)
    monkeypatch.setattr(extraction, "llm", model)
    apply(spec, await extraction.extract_page(spec, source, 0, len(text)))
    issue_codes = {err["code"] for err in validate_spec(spec, [source])}
    assert "blocking_gap" in issue_codes
    assert not (issue_codes & {"invalid_evidence", "invalid_locator", "fabricated_quote"})
    assert spec["gaps"][0]["status"] == "open"
    assert spec["sources"][0]["clauses"][0]["gap_ids"] == [spec["gaps"][0]["gap_id"]]
    assert spec["sources"][0]["clauses"][0]["locator"] == f"字符[0,{len(segments[0])})"
    assert not spec["rules"]


@pytest.mark.asyncio
async def test_policy_preserves_boundaries_configuration_and_case_links(monkeypatch):
    quote_a = "两路及以上电源或单电源两台及以上变压器采用高供高计。"
    quote_b = "315kVA及以下属于容量审查范围，容量条件不能单独决定方案。"
    source = material(quote_a + quote_b)
    rule = {"rule_id": "r1", "name": "核对电源配置", "scope": "教学案例", "status": "source_supported",
            "condition": {"operator": "any", "clauses": [
                {"operator": "predicate", "field": "电源数", "comparator": "gte", "value": 2, "unit": "路"},
                {"operator": "all", "clauses": [
                    {"operator": "predicate", "field": "电源数", "comparator": "eq", "value": 1, "unit": "路"},
                    {"operator": "predicate", "field": "变压器数", "comparator": "gte", "value": 2, "unit": "台"}]}]},
            "results": ["采用高供高计"], "inputs": ["电源数", "变压器数"], "outputs": ["计量方式"],
            "process_disposition": "independent", "process_reason": "单独审核供电配置"}
    capacity = {**rule, "rule_id": "r2", "name": "容量审查范围", "inputs": ["容量"], "outputs": ["是否纳入核对"],
                "condition": {"operator": "predicate", "field": "容量", "comparator": "lte", "value": 315, "unit": "kVA"},
                "results": ["纳入容量审查"], "exceptions": ["容量不能单独决定方案"]}
    cases = [{"case_id": "t" + str(value), "name": str(value) + "kVA 边界", "kind": "boundary", "rule_ids": ["r2"],
              "inputs": {"容量": {"value": value, "unit": "kVA"}}, "expected_outputs": {"纳入容量审查": result}, "status": "defined"}
             for value, result in [(315, True), (316, False)]]
    model = Model({"items": {"rules": [item_with_evidence(rule, quote_a), item_with_evidence(capacity, quote_b, "u2")],
                             "acceptance_cases": cases}, "excluded": []})
    monkeypatch.setattr(extraction, "llm", model)
    spec = ledger()
    apply(spec, await extraction.extract_page(spec, source, 0, len(source["text"])))
    by_name = {r["name"]: r for r in spec["rules"]}
    cond = by_name["核对电源配置"]["condition"]
    assert cond["operator"] == "any" and cond["clauses"][1]["operator"] == "all"
    assert evaluate(cond, {"电源数": {"value": 1, "unit": "路"}, "变压器数": {"value": 2, "unit": "台"}}) is True
    assert evaluate(cond, {"容量": 200, "电压": 10}) is None
    limit = by_name["容量审查范围"]
    assert (limit["condition"]["comparator"], limit["condition"]["unit"]) == ("lte", "kVA")
    for c in spec["acceptance_cases"]:
        assert c["rule_ids"] == [limit["rule_id"]]
        assert evaluate(limit["condition"], c["inputs"]) == c["expected_outputs"]["纳入容量审查"]
    assert limit["results"] == ["纳入容量审查"]
    issues = validate_spec(spec, [source])
    assert not any(err["code"] in {"invalid_evidence", "dangling_reference", "numeric_unit", "unclassified_clause",
                                   "condition_operands", "fabricated_quote"} for err in issues)


@pytest.mark.asyncio
async def test_retry_and_pages_preserve_earlier_source_evidence(monkeypatch):
    text = "A📄 缺主管签字。\n" + "B缺汇率。"
    boundary = text.index("B")
    source, spec = material(text), ledger()
    doc1 = response("gaps", gap("由谁签字？"), "缺主管签字")
    doc2 = response("gaps", gap("汇率如何确定？"), "缺汇率")
    model = Model("not JSON", doc1, doc2, doc2)
    monkeypatch.setattr(extraction, "llm", model)
    apply(spec, await extraction.extract_page(spec, source, 0, boundary))
    assert "validation_feedback" in model.calls[1]
    apply(spec, await extraction.extract_page(spec, source, boundary, len(text)))
    previous = deepcopy(spec)
    apply(spec, await extraction.extract_page(spec, source, boundary, len(text)))
    assert spec == previous
    assert len(spec["gaps"]) == 2 and len(spec["sources"][0]["clauses"]) == 2
    assert spec["sources"][0]["clauses"][1]["locator"] == f"字符[{boundary},{len(text)})"


@pytest.mark.asyncio
@pytest.mark.parametrize(("doc", "error"), [
    (response("gaps", gap("待核对？"), "不存在的引文"), "fabricated_quote"),
    (response("gaps", {**gap("待核对？"), "status": "resolved"}, "附件已提交"), "不能解决"),
    (response("rules", {"rule_id": "r1", "name": "确认规则", "status": "user_confirmed"}, "附件已提交"), "不能代替专家"),
    ({"items": {}, "excluded": []}, "缺少抽取依据"),
    ({"items": {"sources": []}, "excluded": []}, "只能包含"),
])
async def test_invalid_model_cannot_change_ledger(monkeypatch, doc, error):
    source, spec = material("附件已提交"), ledger()
    previous = deepcopy(spec)
    model = Model(doc)
    monkeypatch.setattr(extraction, "llm", model)
    with pytest.raises(ValueError, match=error):
        await extraction.extract_page(spec, source, 0, len(source["text"]))
    assert len(model.calls) == 2 and spec == previous


@pytest.mark.asyncio
async def test_conflicting_human_record_never_overwritten(monkeypatch):
    src, spec = material("审批依据待查。"), ledger()
    doc = response("gaps", gap("审批依据？"), "审批依据")
    monkeypatch.setattr(extraction, "llm", Model(doc))
    apply(spec, await extraction.extract_page(spec, src, 0, len(src["text"])))
    spec["gaps"][0]["impact"] = "人工修改：涉外合同和内贸口径不同"
    previous = deepcopy(spec)
    with pytest.raises(ValueError, match="既有同名条目"):
        await extraction.extract_page(spec, src, 0, len(src["text"]))
    assert spec == previous



@pytest.mark.asyncio
async def test_command_only_credits_page_after_model_and_evidence_succeed(monkeypatch):
    src = material("政策中所述具体限制待核实。")
    before = new_state()
    before["spec"]["scope"] = ledger()["scope"]
    payload = {"source_id": src["source_id"], "offset": 0}
    monkeypatch.setattr(extraction, "llm", Model(response("gaps", gap("限制是什么？"), "不存在的文字")))
    with pytest.raises(ValueError, match="fabricated_quote"):
        await execute(before, "extract", payload, [src], "业务员")
    assert before["coverage"] == {} and before["extraction"] == {}
    monkeypatch.setattr(extraction, "llm", Model(response("gaps", gap("限制是什么？"), "具体限制待核实")))
    after = await execute(before, "extract", payload, [src], "业务员")
    assert after["state"]["coverage"][src["source_id"]] == [[0, len(src["text"])]]
    assert after["state"]["spec"]["sources"][0]["read_status"] == "read"
    assert after["result"]["next_offset"] is None
    assert any(issue["code"] == "blocking_gap" for issue in after["state"]["issues"])


@pytest.mark.asyncio
async def test_model_unavailable_surfaces_technical_failure(monkeypatch):
    model = Model()
    model.configured = False
    monkeypatch.setattr(extraction, "llm", model)
    with pytest.raises(RuntimeError, match="未配置"):
        await extraction.extract_page(ledger(), material("明细待查"), 0, 4)
    assert not model.calls


@pytest.mark.asyncio
async def test_truncated_response_reduces_interval_before_writing(monkeypatch):
    source, spec = material("政策适用范围待定" * 120), ledger()
    good = response("gaps", gap("适用范围？"), "范围待定")
    monkeypatch.setattr(extraction, "llm", Model('{"items":{"gaps":[{"question":"incomplete', good))
    end, batches = await extraction.extract_next_page(spec, source, 0)
    assert 0 < end < len(source["text"])
    apply(spec, batches)
    assert len(spec["gaps"]) == 1
    assert spec["sources"][0]["clauses"][0]["locator"] == f"字符[0,{end})"



@pytest.mark.asyncio
async def test_one_quote_does_not_cover_a_second_business_rule(monkeypatch):
    source = material("合同超限额须审批。因公差旅免此审批。")
    doc = response("gaps", gap("谁有审批权？"), "须审批")
    monkeypatch.setattr(extraction, "llm", Model(doc))
    with pytest.raises(ValueError, match="u2"):
        await extraction.extract_page(ledger(), source, 0, len(source["text"]))
