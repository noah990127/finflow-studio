import json
from collections import Counter
from types import SimpleNamespace

import pytest

from app.tacit import cq_pipeline as cq
from app.tacit.cq_pipeline_units import ALLOWED_TYPES, UNITS, is_business
from app.tacit.workspace_state import new_state, sync_sources


def fixture(text, sid="material", title="审核要求"):
    return dict(source_id=sid, title=title, version="1", source_type="asset", text=text)


def workspace(*rows):
    sources = {row["source_id"]: row for row in rows}
    state = new_state()
    sync_sources(state, sources)
    return state, sources


def fake_model(monkeypatch, result=None):
    calls = []

    async def complete(system, user):
        calls.append(json.loads(user))
        return result

    monkeypatch.setattr(cq, "llm", SimpleNamespace(configured=result is not None, complete=complete))
    return calls


def response(quote, text="何时必须复核？", answer="待专家说明复核条件", start=0, kind="判断类"):
    question = dict(cq_text=text, cq_type=kind, business_context="办理复核", expected_business_answer=answer,
                    modeling_requirement="建模适用条件", technical_validation_question="测试条件边界",
                    related_entities=[], related_attributes=[], related_relations=[])
    return json.dumps({"units": [{"type": "rule", "quote": quote, "start": start, "questions": [question]}]})


@pytest.mark.asyncio
async def test_semantic_units_and_fallback_limits(monkeypatch):
    raw = ("规则要求超过阈值时必须执行。客户与账户一对多。统计余额。例外应当豁免。三个工作日内办结。办理人提交申请。"
           "状态转为已完成。回调事件发布报文。字段需列明。流程环节交接。经办人负责核对。必填信息不能缺失。"
           "输入与输出分别核对。术语分为两类。例如常规账户。")
    state, sources = workspace(fixture(raw))
    fake_model(monkeypatch, "invalid json")
    result = await cq.run_cq_pipeline(state, sources, {}, "author")
    artifact = state["artifacts"]["cqs"]
    assert result["done"] and result["fallback_reasons"] == {"invalid_model_output": 1}
    assert {u["type"] for u in artifact["semantic_units"]} == set(UNITS)
    assert 2 <= len(artifact["content"]) <= 12
    assert {q["semantic_unit"] for q in artifact["content"]} >= cq.HIGH_VALUE
    for q in artifact["content"]:
        assert q["origin"] == "source_rule_hypothesis" and q["review_status"] == "pending"
        assert q["cq_type"] in ALLOWED_TYPES[q["strategy"]]
        for ev in q["source"]["evidence"]:
            assert ev["quote"] == raw[ev["start"]:ev["end"]]
            assert ev["locator"] == f"字符[{ev['start']},{ev['end']})"


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["valid", "invented", "tech", "offset", "answer", "strategy", "physical", "raw_physical"])
async def test_model_evidence_contract(monkeypatch, mode):
    raw = "必须复核。字段名：paid。"
    state, sources = workspace(fixture(raw))
    state["spec"]["data_tables"] = [{"name": "payments", "columns": [{"name": "amount"}]}]
    args = {"invented": dict(quote="必须免审"), "tech": dict(text="payState 返回 HTTP 404？"),
            "offset": dict(start=1), "answer": dict(answer="所有支付均自动审批"), "strategy": dict(kind="执行类"),
            "physical": dict(text="怎样查询 payments？"), "raw_physical": dict(text="如何校验 paid？")}
    calls = fake_model(monkeypatch, response(**{"quote": "必须复核。", **args.get(mode, {})}))
    result = await cq.run_cq_pipeline(state, sources, {}, "author")
    rows = state["artifacts"]["cqs"]["content"]
    assert len(calls) == 1 and len(rows) >= 2
    assert all(is_business(q["cq_text"], ["payments", "amount", "paid"]) for q in rows)
    if mode == "valid":
        assert result["fallback_chunks"] == 0
        q = next(q for q in rows if q["origin"] == "source_model_hypothesis")
        assert q["cq_text"] == "何时必须复核？" and q["source"]["evidence"][0]["quote"] == "必须复核。"
    else:
        assert result["fallback_reasons"] == {"invalid_model_output": 1}
        assert all(q["origin"] == "source_rule_hypothesis" for q in rows)


@pytest.mark.asyncio
async def test_exclusions_and_partial_sources(monkeypatch):
    one = fixture("必须审查。禁止进入模型。允许核对。")
    two = fixture("用户排除的整个文件", sid="excluded")
    one.update(incomplete=True, reason="解析有缺页", locations=[{"locator": "第 2 页", "start": 0, "end": 5}])
    state, sources = workspace(one, two)
    state["spec"]["sources"][0]["clauses"] = [
        dict(clause_id="kept", coverage="extracted", quote="必须审查。", locator="第 2 页"),
        dict(clause_id="removed", coverage="out_of_scope", quote="禁止进入模型。", locator="字符[5,12)")]
    state["spec"]["sources"][1]["disposition"] = "excluded"
    calls = fake_model(monkeypatch, "broken")
    progress = await cq.run_cq_pipeline(state, sources, {}, "author")
    assert progress["done"] and progress["source_warnings"][0]["reason"] == "解析有缺页"
    assert [page["text"] for page in calls] == ["必须审查。", "允许核对。"]
    ev = state["artifacts"]["cqs"]["content"][0]["source"]["evidence"][0]
    assert ev["clause_id"] == "kept" and ev["source_locator"] == "第 2 页" and state["coverage"] == {}


@pytest.mark.asyncio
async def test_approvals_bind_to_question_answer_scope_and_source_revisions(monkeypatch):
    raw = "必须核对。"
    state, sources = workspace(fixture(raw))
    calls = fake_model(monkeypatch, response(raw))
    await cq.run_cq_pipeline(state, sources, {}, "reviewer")

    def model_question():
        return next(q for q in state["artifacts"]["cqs"]["content"] if q["origin"] == "source_model_hypothesis")

    model_question().update(review_status="accepted", reviewed_by="human", reviewed_at="any")
    await cq.run_cq_pipeline(state, sources, {}, "reviewer")
    assert len(calls) == 1
    await cq.run_cq_pipeline(state, sources, {"restart": True}, "reviewer")
    assert model_question()["review_status"] == "accepted"
    fake_model(monkeypatch, response(raw, answer="待专家明确日期"))
    await cq.run_cq_pipeline(state, sources, {"restart": True}, "reviewer")
    assert model_question()["review_status"] == "pending"
    model_question().update(review_status="rejected", reviewed_by="human")
    sources["material"]["version"] = "2"
    await cq.run_cq_pipeline(state, sources, {}, "reviewer")
    assert model_question()["review_status"] == "pending"
    model_question().update(review_status="accepted", reviewed_by="human")
    state["spec"]["scope"]["region"] = "华东"
    await cq.run_cq_pipeline(state, sources, {}, "reviewer")
    assert model_question()["review_status"] == "pending"


@pytest.mark.asyncio
async def test_dedup_retains_sources_and_invalidates_partial_review(monkeypatch):
    state, sources = workspace(fixture("必须批准。", sid="a"), fixture("必须批准。", sid="b"))
    fake_model(monkeypatch)
    front = await cq.run_cq_pipeline(state, sources, {"page_size": 1}, "x")
    assert not front["done"] and front["remaining_chunks"] == 1
    first = state["artifacts"]["cqs"]["content"][0]
    first.update(review_status="accepted", reviewed_by="user")
    done = await cq.run_cq_pipeline(state, sources, {"page_size": 1}, "x")
    assert done["done"] and done["generated_questions"] == front["generated_questions"]
    assert {e["source_id"] for e in first["source"]["evidence"]} == {"a", "b"}
    assert first["review_status"] == "pending" and "reviewed_by" not in first


def test_sizing_and_logical_windows_cover_whole_asset():
    chars = "甲" * 3333
    spans = list(cq._chunk_spans(chars, 0, len(chars), False))
    assert all(cq.MIN_CHARS <= end - start <= cq.MAX_CHARS for start, end in spans)
    assert "".join(chars[start:end] for start, end in spans) == chars
    table = "编号,含义\n" * 1700
    state, sources = workspace(*(fixture(table, sid=k, title=k + ".csv") for k in "abcd"))
    plan, windows, limited, *_ = cq._plan(state["spec"], sources)
    assert set(limited) == set("abcd") and len(windows) >= 2
    for left, right in zip([0, *windows], windows):
        assert right - left <= 150
        assert max(Counter(sid for sid, *_ in plan[left:right]).values()) <= 50
    for sid in sources:
        chunks = [(start, end) for key, start, end, _ in plan if key == sid]
        assert "".join(table[start:end] for start, end in chunks) == table
        assert all(len(table[start:end].splitlines()) <= 30 for start, end in chunks)


@pytest.mark.asyncio
async def test_duplicate_text_with_different_answers_is_preserved(monkeypatch):
    raw = "必须核对。"
    state, sources = workspace(fixture(raw))
    result = json.loads(response(raw))
    questions = result["units"][0]["questions"]
    questions.append(dict(questions[0], expected_business_answer="待专家明确处理时点"))
    fake_model(monkeypatch, json.dumps(result))
    await cq.run_cq_pipeline(state, sources, {}, "user")
    matches = [q for q in state["artifacts"]["cqs"]["content"] if q["cq_text"] == "何时必须复核？"]
    assert len(matches) == 2 and len({q["cq_id"] for q in matches}) == 2
