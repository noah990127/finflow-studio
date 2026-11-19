"""Replaceable public-web search provider adapters."""

from dataclasses import dataclass
from html import unescape
from typing import Any, Protocol

import httpx


class SearchProviderError(RuntimeError):
    """Base error for a configured search provider."""


class SearchProviderUnavailable(SearchProviderError):
    """Raised when no provider is configured or the provider cannot be reached."""


@dataclass(frozen=True)
class SearchCandidate:
    title: str
    url: str
    snippet: str = ""


class SearchProvider(Protocol):
    name: str

    async def search(self, query: str, limit: int) -> list[SearchCandidate]: ...


class SearxngSearchProvider:
    name = "searxng"

    def __init__(self, endpoint: str, *, engines: str = "", connect_timeout: float = 5.0,
                 request_timeout: float = 15.0, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.endpoint = endpoint.strip().rstrip("/")
        self.engines = ",".join(dict.fromkeys(
            item.strip().lower() for item in engines.split(",") if item.strip()
        ))
        self.timeout = httpx.Timeout(request_timeout, connect=connect_timeout)
        self.transport = transport

    async def search(self, query: str, limit: int) -> list[SearchCandidate]:
        if not self.endpoint:
            raise SearchProviderUnavailable("尚未配置联网搜索服务")
        requested_limit = max(1, min(limit, 20))
        engine_order = [item for item in self.engines.split(",") if item] or [""]
        candidates: list[SearchCandidate] = []
        seen_urls: set[str] = set()
        unavailable_engines: set[str] = set()
        request_failures = 0
        async with httpx.AsyncClient(timeout=self.timeout, transport=self.transport,
                                     follow_redirects=False) as client:
            for engine in engine_order:
                params = {
                    "q": query,
                    "format": "json",
                    "categories": "general",
                    "language": "zh-CN",
                    "safesearch": 1,
                }
                if engine:
                    params["engines"] = engine
                try:
                    response = await client.get(self.endpoint + "/search", params=params)
                    response.raise_for_status()
                    payload = response.json()
                except (httpx.HTTPError, OSError, ValueError, TypeError):
                    unavailable_engines.add(engine or "default")
                    request_failures += 1
                    continue
                if not isinstance(payload, dict) or not isinstance(payload.get("results", []), list):
                    raise SearchProviderError("联网搜索服务返回了无法识别的结果")
                unresponsive = payload.get("unresponsive_engines", [])
                unavailable_engines.update(
                    str(item[0]).strip().lower()
                    for item in unresponsive
                    if isinstance(item, (list, tuple)) and item
                )
                for item in payload.get("results", []):
                    if not isinstance(item, dict):
                        continue
                    url = unescape(str(item.get("url", "")).strip())
                    if not url or url in seen_urls:
                        continue
                    seen_urls.add(url)
                    candidates.append(SearchCandidate(
                        title=str(item.get("title") or url)[:300],
                        url=url,
                        snippet=str(item.get("content") or "")[:1200],
                    ))
                    if len(candidates) >= requested_limit:
                        break
                if len(candidates) >= requested_limit:
                    break
        if not candidates and request_failures == len(engine_order):
            raise SearchProviderUnavailable("联网搜索服务暂时无法访问")
        if not candidates and unavailable_engines and all(
                (engine or "default") in unavailable_engines for engine in engine_order):
            names = "、".join(sorted(unavailable_engines))
            raise SearchProviderUnavailable(f"联网搜索引擎暂时不可用（{names}）")
        return candidates


def build_search_provider(provider: str, searxng_url: str, *, searxng_engines: str = "",
                          connect_timeout: float = 5.0,
                          request_timeout: float = 15.0) -> SearchProvider:
    normalized = provider.strip().lower() or "searxng"
    if normalized == "searxng":
        return SearxngSearchProvider(searxng_url, engines=searxng_engines,
                                     connect_timeout=connect_timeout,
                                     request_timeout=request_timeout)
    raise SearchProviderUnavailable(f"未支持的联网搜索服务：{normalized}")


def provider_info(provider: str, searxng_url: str) -> dict[str, Any]:
    normalized = provider.strip().lower() or "searxng"
    return {
        "name": normalized,
        "configured": normalized == "searxng" and bool(searxng_url.strip()),
    }
