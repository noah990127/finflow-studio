from copy import deepcopy

import httpx
import pytest

from app.tacit import alignment, retrieval
from app.tacit.engine import execute
from app.tacit.spec import item_digest
from app.tacit.spec_schema import AcceptanceCase, Gap, Process, Rule, SourceClause
from app.tacit.workspace_state import new_state, stamp, sync_sources


def catalog():
    return [{"source_id": sid, "source_type": "asset", "title": title, "version": version, "text": quote}
            for sid, title, version, quote in [
                ("v-old", "审批办法", "1", "🧾办理超过10元的款项需复核。"),
                ("v-new", "审批细则", "2", "超过10元的款项应当复核。")]]


def fixture(threshold=10):
    docs = catalog()
    if threshold != 10:
        docs[1]["text"] = docs[1]["text"].replace("10", str(threshold))
    state = new_state()
    sync_sources(state, {s["source_id"]: s for s in docs})
    for s in docs:
        state["coverage"][s["source_id"]] = [[0, len(s["text"])]]
    sync_sources(state, {s["source_id"]: s for s in docs})
    for i, src in enumerate(state["spec"]["sources"]):
        text, rid = docs[i]["text"], str(i + 1)
        src["content_kind"] = "business_text"
        src["clauses"] = [SourceClause(clause_id="c", locator=f"字符[0,{len(text)})", quote=text,
                                       coverage="extracted", rule_ids=[rid]).model_dump()]
        state["spec"]["rules"].append(Rule(rule_id=rid, name="复核政策", status="source_supported",
                                          scope="付款", process_ids=["payment"],
                                          condition={"operator": "predicate", "field": "付款金额", "comparator": "gt",
                                                     "value": threshold if i else 10, "unit": "元"},
                                          results=["复核支付"], inputs=["付款金额"], outputs=["复核通知"],
                                          evidence=[{"source_id": src["source_id"], "clause_id": "c",
                                                     "quote": text}]).model_dump())
    state["spec"]["processes"] = [Process(process_id="payment", status="user_confirmed", rule_ids=["1", "2"],
        steps=[dict(step_id="a", rule_ids=["1"])], branches=[
            dict(branch_id="b", from_step_id="a", to_step_id="a", rule_ids=["1", "2"])]).model_dump()]
    state["spec"]["acceptance_cases"] = [AcceptanceCase(case_id="c", rule_ids=["1"], status="defined").model_dump()]
    state["spec"]["gaps"] = [Gap(gap_id="g", rule_ids=["1", "2"]).model_dump()]
    return state, docs


@pytest.fixture(autouse=True)
def local(monkeypatch):
    monkeypatch.delenv("FINFLOW_TACIT_LIGHTRAG_URL", raising=False)
    monkeypatch.setattr(type(alignment.llm), "configured", property(lambda self: False))


async def command(state, docs, action, **payload):
    res = await execute(state, action, payload, docs, "复核员")
    return res["state"], res["result"]


@pytest.mark.asyncio
async def test_literal_source_alias_and_external_scope(monkeypatch):
    state, docs = fixture()
    state["spec"]["sources"][1]["disposition"] = "excluded"
    ev = state["spec"]["rules"][0]["evidence"][0]
    state["artifacts"]["cqs"] = {"status": "draft", "content": [
        {"cq_id": "q", "cq_text": "谁负责双签？", "expected_business_answer": "风控复核",
         "source": {"evidence": [dict(ev, start=0, end=len(ev["quote"]), version="1", source_stamp=stamp(docs[0]))]}},
        {"cq_id": "out", "cq_text": "保密双签", "source": {"evidence": [dict(ev, quote="别处文字")]}},
        {"cq_id": "mixed", "cq_text": "双签审批", "source": {"evidence": [ev, state["spec"]["rules"][1]["evidence"][0]]}},
        {"cq_id": "stale", "cq_text": "双签", "source": {"evidence": [dict(ev, start=0, end=len(ev["quote"]), version="0")]}}]}
    async def remote(query):
        return [{"file_path": "v-old", "content": "🧾办理"}, {"file_path": "not-selected", "content": "其他秘密"},
                {"file_path": "v-new", "content": docs[1]["text"]}, {"file_path": "v-old", "content": "虚假内容"},
                {"file_path": "v-old", "content": "办理", "version": "0"}], "available"
    monkeypatch.setattr(retrieval, "_light_chunks", remote)
    _, result = await command(state, docs, "search_context", query="双签", limit=1)
    assert result["total"] == 2 and result["next_offset"] == 1
    _, second = await command(state, docs, "search_context", query="双签", limit=1, offset=1)
    hits = result["items"] + second["items"]
    assert {h["id"] for h in hits} == {"q", "v-old"}
    assert all(docs[0]["text"][h["start"]:h["end"]] == h["text"] for h in hits)
    _, concepts = await command(state, docs, "search_context", query="付款金额", section="contract")
    assert {row["id"] for row in concepts["items"]} == {"1"}


@pytest.mark.asyncio
async def test_scoped_search_avoids_excluded_span_and_browses_fields():
    state, docs = fixture()
    state["spec"]["sources"][0]["clauses"].append(SourceClause(clause_id="x", locator="字符[1,3)", quote="办理",
                                                                coverage="out_of_scope", reason="排除").model_dump())
    _, view = await command(state, docs, "search_context", query="办理", section="assets")
    assert not view["items"]
    fields = [{"name": "F" + str(i), "business_name": "领款单位" if i == 177 else ""} for i in range(180)]
    state["spec"]["data_objects"] = [{"object_id": "o", "name": "领款记录", "fields": fields,
                                      "evidence": state["spec"]["rules"][1]["evidence"]}]
    _, view = await command(state, docs, "search_context", query="领款单位", section="terminology")
    assert view["items"][0]["id"] == "o:field:177"
    assert view["items"][0]["source_id"] == "v-new"


@pytest.mark.asyncio
async def test_real_lightrag_protocol_uses_only_server_configuration(monkeypatch):
    monkeypatch.setenv("FINFLOW_TACIT_LIGHTRAG_URL", "https://rag.example/project")
    monkeypatch.setenv("FINFLOW_TACIT_LIGHTRAG_API_KEY", "project-key")
    real = httpx.AsyncClient
    def serve(req):
        assert str(req.url) == "https://rag.example/project/query/data"
        assert req.headers.get("X-API-Key") == "project-key" and b'"mode":"mix"' in req.content
        return httpx.Response(200, json={"data": {"chunks": [{"file_path": "v-new", "content": "应当复核"}]}})
    monkeypatch.setattr(retrieval.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(serve), **kw))
    state, docs = fixture()
    _, view = await command(state, docs, "search_context", query="审批分工", url="https://irrelevant.invalid")
    assert view["providers"]["lightrag"] == "available"
    assert any(h["text"] == "应当复核" for h in view["items"])


@pytest.mark.asyncio
async def test_review_merge_rewrites_every_link_and_revokes_old_endorsements():
    state, docs = fixture()
    state["spec"]["rules"][0]["name"] = "旧制复查"
    signed = {"1": "old1", "2": "old2", "payment": "old-proc"}
    state["user_sources"] = [{"source_id": "note-old", "version": "1", "source_type": "user",
                              "text": "历史确认", "title": "早期审核", "confirmed_items": signed}]
    state, scanned = await command(state, docs, "align_policies")
    item = scanned["items"][0]
    assert item["kind"] == "equivalent" and not item["requires_reconciliation"]
    state, result = await command(state, docs, "review_alignment", decision="merge", comment="人工核对两版规定一致。",
                                  alignment_id=item["alignment_id"])
    spec = state["spec"]
    assert result["target"] == "2" and len(spec["rules"]) == 1
    for row in [*spec["acceptance_cases"], *spec["gaps"], *spec["processes"], *spec["processes"][0]["steps"],
                *spec["processes"][0]["branches"], *[s["clauses"][0] for s in spec["sources"] if s["clauses"]]]:
        assert row["rule_ids"] == ["2"]
    rule = spec["rules"][0]
    assert len(rule["evidence"]) == 3 and rule["status"] == "user_confirmed"
    assert state["user_sources"][0]["confirmed_items"] == {}
    assert state["user_sources"][-1]["confirmed_items"] == {"2": item_digest(rule)}
    assert spec["processes"][0]["status"] == "proposed"
    assert state["artifacts"]["alignments"]["content"][0]["status"] == "merge"
    _, aliases = await command(state, docs, "search_context", query="旧制复查", section="terminology")
    assert all("复核政策" in h["summary"] for h in aliases["items"]) and aliases["items"]


@pytest.mark.asyncio
async def test_different_amount_needs_real_resolution_or_linked_conflict_gap():
    state, docs = fixture(20)
    state, scanned = await command(state, docs, "align_policies")
    row = scanned["items"][0]
    assert row["kind"] == "possible_conflict" and row["requires_reconciliation"]
    vote = {"decision": "merge", "alignment_id": row["alignment_id"], "comment": "按最新发布的细则执行。"}
    with pytest.raises(ValueError, match="merged_rule"):
        await command(state, docs, "review_alignment", **vote)
    rule = deepcopy(state["spec"]["rules"][1])
    rule["condition"]["value"] = 20
    merged, _ = await command(state, docs, "review_alignment", **vote, merged_rule=rule)
    assert merged["spec"]["rules"][0]["condition"]["value"] == 20
    assert merged["spec"]["acceptance_cases"][0]["status"] == "needs_confirmation"
    conflict, res = await command(state, docs, "review_alignment", **dict(vote, decision="conflict"))
    assert [r["status"] for r in conflict["spec"]["rules"]] == ["conflicted", "conflicted"]
    gap = conflict["spec"]["gaps"][-1]
    assert gap["blocking"] and gap["gap_id"] == res["gap_id"] and len(gap["evidence"]) == 3
    assert all(gap["gap_id"] in s["clauses"][0]["gap_ids"] for s in conflict["spec"]["sources"])
    _, res = await command(conflict, docs, "align_policies")
    assert not res["items"]


@pytest.mark.asyncio
async def test_stale_review_rejected_semantic_and_paging(monkeypatch):
    state, docs = fixture(20)
    docs[0]["text"] = "宾馆旅客夜间住宿登记。"
    sync_sources(state, {s["source_id"]: s for s in docs})
    rule = state["spec"]["rules"][0]
    rule["evidence"][0]["quote"] = docs[0]["text"]
    old = next(s for s in state["spec"]["sources"] if s["source_id"] == docs[0]["source_id"])
    old["clauses"] = [SourceClause(clause_id="c", locator=f"字符[0,{len(docs[0]['text'])})",
                                   quote=docs[0]["text"], coverage="extracted", rule_ids=["1"]).model_dump()]
    state["spec"]["rules"][0].update(name="访客留宿登记", scope="宾馆")
    state["spec"]["rules"][0]["condition"]["field"] = "guest"
    async def semantic(prompt, payload):
        return '{"matches":[{"from_id":"1","to_id":"2","kind":"related","reason":"人工核对别称"},' + (
            '{"from_id":"outside","to_id":"2","kind":"related","reason":"未授权"}]}')
    monkeypatch.setattr(type(alignment.llm), "configured", property(lambda self: True))
    monkeypatch.setattr(alignment.llm, "complete", semantic)
    state, scanned = await command(state, docs, "align_policies")
    assert len(scanned["items"]) == 1 and scanned["semantic_provider"] == "available"
    assert scanned["items"][0]["reason"] == "人工核对别称"
    identity = scanned["items"][0]["alignment_id"]
    state["spec"]["rules"][0]["exceptions"] = ["新例外"]
    with pytest.raises(ValueError, match="变化"):
        await command(state, docs, "review_alignment", decision="distinct", comment="分别保留", alignment_id=identity)
    with pytest.raises(ValueError, match="分页"):
        await command(state, docs, "align_policies", offset=1)


@pytest.mark.asyncio
async def test_valid_sparse_ledger_and_distinct_decision_remain_searchable():
    state, docs = fixture()
    spec = state["spec"]
    spec["processes"].append({"process_id": "unfinished"})
    spec["processes"][0].pop("rule_ids")
    spec["sources"].append({"source_id": "draft", "source_type": "asset"})
    docs.append({"source_id": "draft", "version": "1", "source_type": "asset", "text": "草稿", "title": "待阅读"})
    state, scanned = await command(state, docs, "align_policies")
    vote = {"decision": "distinct", "alignment_id": scanned["items"][0]["alignment_id"], "comment": "适用对象不同，分别保留。"}
    distinct, _ = await command(state, docs, "review_alignment", **vote)
    _, result = await command(distinct, docs, "search_context", section="decisions", query="适用对象")
    assert {r["id"] for r in result["items"]} == {vote["alignment_id"]}
    merged, res = await command(state, docs, "review_alignment", **dict(vote, decision="merge"))
    assert res["target"] == "2"
    assert merged["spec"]["processes"][0]["rule_ids"] == ["2"]
    assert merged["spec"]["processes"][1] == {"process_id": "unfinished"}


@pytest.mark.asyncio
async def test_pair_pagination_resumes_without_skipping_and_provider_failure_keeps_local_suggestions(monkeypatch):
    monkeypatch.setattr(alignment, "PAIR_PAGE", 2)
    monkeypatch.setattr(type(alignment.llm), "configured", property(lambda self: True))
    async def malformed(*args):
        return "unusable response"
    monkeypatch.setattr(alignment.llm, "complete", malformed)
    state, docs = fixture()
    for i in range(3, 6):
        base = deepcopy(state["spec"]["rules"][0])
        base["rule_id"] = str(i)
        state["spec"]["rules"].append(base)
        state["spec"]["sources"][0]["clauses"][0]["rule_ids"].append(str(i))
    seen, counts = [], []
    offset = 0
    while offset is not None:
        state, result = await command(state, docs, "align_policies", offset=offset)
        assert result["total_pairs"] == 10
        seen.extend((r["from_id"], r["to_id"]) for r in result["items"])
        counts.append(len(result["items"]))
        offset = result["next_offset"]
    assert len(counts) == 5 and set(seen) == {("1", "2"), ("2", "3"), ("2", "4"), ("2", "5")}
    assert len(seen) == 4 and state["artifacts"]["alignments"]["status"] == "ready"
    assert len(state["artifacts"]["alignments"]["content"]) == 4


@pytest.mark.asyncio
async def test_material_change_demotes_rule_dependent_approval_and_unread_prevents_signing():
    state, docs = fixture(80)
    state["spec"]["processes"][0]["rule_ids"] = ["2"]
    state["spec"]["processes"][0]["steps"][0]["rule_ids"] = ["2"]
    state["spec"]["processes"][0]["branches"][0]["rule_ids"] = ["2"]
    state, scan = await command(state, docs, "align_policies")
    vote = {"decision": "merge", "alignment_id": scan["items"][0]["alignment_id"], "comment": "新审批细则优先。",
            "merged_rule": state["spec"]["rules"][1]}
    resolved, _ = await command(state, docs, "review_alignment", **vote)
    assert resolved["spec"]["processes"][0]["status"] == "proposed"
    state["coverage"].pop("v-old")
    with pytest.raises(ValueError, match="完整读取"):
        await command(state, docs, "review_alignment", **vote)
