import httpx
import pytest

from app.integrations.search_provider import SearxngSearchProvider, SearchProviderUnavailable


@pytest.mark.asyncio
async def test_searxng_provider_sends_json_query_and_normalizes_results() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/search"
        assert request.url.params["q"] == "深圳福田活动"
        assert request.url.params["format"] == "json"
        assert request.url.params["engines"] == "sogou"
        return httpx.Response(200, json={"results": [
            {"title": "活动一", "url": "https://example.com/one", "content": "今晚开放"},
            {"title": "活动二", "url": "https://example.com/two?a=1&amp;b=2", "content": "需要预约"},
        ], "unresponsive_engines": [["bing", "timeout"]]})

    provider = SearxngSearchProvider(
        "http://search.local", engines="sogou,bing", transport=httpx.MockTransport(handler))
    results = await provider.search("深圳福田活动", 2)

    assert len(results) == 2
    assert results[0].title == "活动一"
    assert results[0].snippet == "今晚开放"
    assert results[1].url == "https://example.com/two?a=1&b=2"


@pytest.mark.asyncio
async def test_searxng_provider_falls_back_to_next_engine() -> None:
    requested_engines: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        engine = request.url.params["engines"]
        requested_engines.append(engine)
        if engine == "sogou":
            return httpx.Response(200, json={
                "results": [], "unresponsive_engines": [["sogou", "timeout"]],
            })
        return httpx.Response(200, json={
            "results": [{"title": "业务资料", "url": "https://example.com/source"}],
            "unresponsive_engines": [],
        })

    provider = SearxngSearchProvider(
        "http://search.local", engines="sogou,bing", transport=httpx.MockTransport(handler))
    results = await provider.search("任意业务主题", 1)

    assert requested_engines == ["sogou", "bing"]
    assert [item.title for item in results] == ["业务资料"]


@pytest.mark.asyncio
async def test_searxng_provider_reports_all_engines_unavailable_instead_of_no_results() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "results": [],
            "unresponsive_engines": [["sogou", "timeout"], ["bing", "CAPTCHA"]],
        })

    provider = SearxngSearchProvider(
        "http://search.local", engines="sogou,bing", transport=httpx.MockTransport(handler))

    with pytest.raises(SearchProviderUnavailable, match="搜索引擎暂时不可用"):
        await provider.search("任意业务主题", 5)


@pytest.mark.asyncio
async def test_searxng_provider_distinguishes_unavailable_service() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="not ready")

    provider = SearxngSearchProvider("http://search.local", transport=httpx.MockTransport(handler))

    with pytest.raises(SearchProviderUnavailable, match="暂时无法访问"):
        await provider.search("测试", 5)


@pytest.mark.asyncio
async def test_searxng_provider_requires_endpoint() -> None:
    with pytest.raises(SearchProviderUnavailable, match="尚未配置"):
        await SearxngSearchProvider("").search("测试", 5)
