from app.research import prepare_search_queries, prepare_search_query
import pytest
from app import research
from app.integrations.search_provider import SearchCandidate


def test_business_request_becomes_segmented_search_query() -> None:
    query = prepare_search_query(
        "新建一个项目，分析一下最近的尊界制动踏板断裂事件的时间线和当前舆论形势"
    )

    assert "尊界" in query.split()
    assert "制动" in query.split()
    assert "踏板" in query.split()
    assert "断裂" in query.split()
    assert "项目" not in query
    assert "分析" not in query


def test_expanded_workflow_prompt_keeps_only_the_search_subject() -> None:
    query = prepare_search_query(
        "分析最近发生的尊界制动踏板断裂事件：基于可核验且成功读取的公开证据，"
        "按发布时间梳理事件时间线，并生成中文PPTX。"
    )

    assert query == "尊界 制动 踏板 断裂"


def test_chinese_query_has_compact_generic_fallback_without_adding_business_terms() -> None:
    queries = prepare_search_queries("调研深圳福田周末文化活动和报名方式")

    assert len(queries) == 2
    assert queries[1] == queries[0].replace(" ", "")
    assert all("尊界" not in query for query in queries)


@pytest.mark.asyncio
async def test_unrelated_results_do_not_prevent_query_fallback(monkeypatch) -> None:
    calls = []
    class Provider:
        async def search(self, query, limit):
            calls.append(query)
            if len(calls) == 1:
                return [SearchCandidate("CHINT POWER", "https://example.com/unrelated")]
            return [SearchCandidate("APEC民生影响", "https://example.com/news")]
    monkeypatch.setattr(research, "build_search_provider", lambda *a, **k: Provider())
    monkeypatch.setattr(research, "validate_public_url", lambda *a: None)
    results = await research.search_web("深圳 APEC 民生", 5)
    assert len(calls) == 2
    assert [item["url"] for item in results] == ["https://example.com/news"]


@pytest.mark.asyncio
async def test_unrelated_results_are_not_returned_as_sources(monkeypatch) -> None:
    class Provider:
        async def search(self, query, limit):
            return [SearchCandidate("CHINT POWER", "https://example.com/unrelated")]
    monkeypatch.setattr(research, "build_search_provider", lambda *a, **k: Provider())
    assert await research.search_web("深圳 APEC 民生", 5) == []
