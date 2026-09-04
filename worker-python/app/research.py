import hashlib
import ipaddress
import json
import re
import socket
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urlparse

import httpx

from .config import settings


class _ReadableText(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.title = ""
        self._in_title = False
        self.parts: list[str] = []
        self.tables: list[dict[str, Any]] = []
        self._ignored = 0
        self._table: list[list[str]] | None = None
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "title":
            self._in_title = True
        if tag in {"script", "style", "noscript", "svg"}:
            self._ignored += 1
        if self._ignored:
            return
        if tag == "table" and self._table is None:
            self._table = []
        elif tag == "tr" and self._table is not None:
            self._row = []
        elif tag in {"th", "td"} and self._row is not None:
            self._cell = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        if tag in {"script", "style", "noscript", "svg"} and self._ignored:
            self._ignored -= 1
            return
        if tag in {"th", "td"} and self._cell is not None and self._row is not None:
            self._row.append(" ".join(self._cell).strip()[:2_000])
            self._cell = None
        elif tag == "tr" and self._row is not None and self._table is not None:
            if any(self._row):
                self._table.append(self._row)
            self._row = None
        elif tag == "table" and self._table is not None:
            if self._table:
                self.tables.append({"title": f"网页表格 {len(self.tables) + 1}", "rows": self._table[:500]})
            self._table = None

    def handle_data(self, data: str) -> None:
        text = re.sub(r"\s+", " ", data).strip()
        if not text or self._ignored:
            return
        if self._in_title:
            self.title = text[:300]
        else:
            self.parts.append(text)
            if self._cell is not None:
                self._cell.append(text)


def _json_tables(value: Any, path: str = "root") -> list[dict[str, Any]]:
    tables: list[dict[str, Any]] = []
    if isinstance(value, dict):
        scalar_items = [(str(key), item) for key, item in value.items()
                        if item is None or isinstance(item, (str, int, float, bool))]
        if len(scalar_items) >= 2:
            tables.append({"title": path, "rows": [["field", "value"], *[[key, str(item)] for key, item in scalar_items]]})
        for key, item in value.items():
            child_path = str(key) if path == "root" else f"{path}.{key}"
            if isinstance(item, dict) and item and all(
                    nested is None or isinstance(nested, (str, int, float, bool)) for nested in item.values()):
                tables.append({"title": child_path, "rows": [["key", "value"], *[
                    [str(nested_key), str(nested_value)] for nested_key, nested_value in item.items()
                ]]})
            elif isinstance(item, (dict, list)):
                tables.extend(_json_tables(item, child_path))
    elif isinstance(value, list) and value:
        if all(isinstance(item, dict) for item in value):
            columns = list(dict.fromkeys(str(key) for item in value for key in item.keys()))[:100]
            rows = [columns]
            rows.extend([[str(item.get(column, "")) for column in columns] for item in value[:500]])
            tables.append({"title": path, "rows": rows})
        else:
            tables.append({"title": path, "rows": [["index", "value"], *[
                [str(index), str(item)] for index, item in enumerate(value[:500])
            ]]})
    return tables[:50]


def _allowed_domains() -> set[str]:
    return {item.strip().lower() for item in settings.research_allowed_domains.split(",") if item.strip()}


def validate_public_url(url: str, domain_allowlist: list[str] | None = None) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("只允许访问公开的 HTTP/HTTPS 网页")
    host = parsed.hostname.lower().rstrip(".")
    allowed = {item.lower().rstrip(".") for item in (domain_allowlist or [])} | _allowed_domains()
    if allowed and not any(host == domain or host.endswith("." + domain) for domain in allowed):
        raise ValueError("该网站不在本次任务允许的范围内")
    for info in socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM):
        address = ipaddress.ip_address(info[4][0])
        if not address.is_global:
            raise ValueError("不允许通过联网研究访问本机或内部网络")
    return url


async def search_web(query: str, limit: int = 8) -> list[dict[str, str]]:
    if not settings.research_searxng_url.strip():
        return []
    endpoint = settings.research_searxng_url.rstrip("/") + "/search"
    async with httpx.AsyncClient(timeout=20, follow_redirects=False) as client:
        response = await client.get(endpoint, params={"q": query, "format": "json", "categories": "general"})
        response.raise_for_status()
    items: list[dict[str, str]] = []
    for item in response.json().get("results", []):
        url = str(item.get("url", ""))
        try:
            validate_public_url(url)
        except (ValueError, OSError):
            continue
        items.append({"title": str(item.get("title", url))[:300], "url": url,
                      "snippet": str(item.get("content", ""))[:1200]})
        if len(items) >= max(1, min(limit, 20)):
            break
    return items


async def fetch_web(url: str, domain_allowlist: list[str] | None = None) -> dict[str, Any]:
    validate_public_url(url, domain_allowlist)
    headers = {"User-Agent": "FinBTP-Studio-Research/1.0"}
    async with httpx.AsyncClient(timeout=30, follow_redirects=False, headers=headers) as client:
        response = await client.get(url)
        if response.is_redirect:
            target = str(response.headers.get("location", ""))
            if not target.startswith("http"):
                target = str(response.url.join(target))
            validate_public_url(target, domain_allowlist)
            response = await client.get(target)
        response.raise_for_status()
        content_type = response.headers.get("content-type", "").lower()
        body = response.content
    if len(body) > settings.research_max_download_bytes:
        raise ValueError("网页内容超过本次研究任务的下载上限")
    decoded = body.decode(response.encoding or "utf-8", errors="replace")
    tables: list[dict[str, Any]] = []
    title = url
    if "application/json" in content_type or "+json" in content_type:
        payload = json.loads(decoded)
        text = json.dumps(payload, ensure_ascii=False, indent=2)[:120_000]
        tables = _json_tables(payload)
    elif "text/html" in content_type:
        parser = _ReadableText()
        parser.feed(decoded)
        text = re.sub(r"\n{3,}", "\n\n", "\n".join(parser.parts))[:120_000]
        title = parser.title or url
        tables = parser.tables[:50]
    elif "text/plain" in content_type:
        text = decoded[:120_000]
    else:
        raise ValueError("该地址不是可直接阅读的网页、文本或 JSON 数据")
    return {
        "url": url,
        "final_url": str(response.url),
        "title": title,
        "text": text,
        "tables": tables,
        "content_hash": hashlib.sha256(body).hexdigest(),
        "content_type": content_type.split(";", 1)[0],
    }
