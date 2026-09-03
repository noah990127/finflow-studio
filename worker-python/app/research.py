import hashlib
import ipaddress
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
        self._ignored = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "title":
            self._in_title = True
        if tag in {"script", "style", "noscript", "svg"}:
            self._ignored += 1

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        if tag in {"script", "style", "noscript", "svg"} and self._ignored:
            self._ignored -= 1

    def handle_data(self, data: str) -> None:
        text = re.sub(r"\s+", " ", data).strip()
        if not text or self._ignored:
            return
        if self._in_title:
            self.title = text[:300]
        else:
            self.parts.append(text)


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
    if "text/html" not in content_type and "text/plain" not in content_type:
        raise ValueError("该地址不是可直接阅读的网页正文")
    parser = _ReadableText()
    parser.feed(body.decode(response.encoding or "utf-8", errors="replace"))
    text = "\n".join(parser.parts)
    text = re.sub(r"\n{3,}", "\n\n", text)[:120_000]
    return {
        "url": url,
        "final_url": str(response.url),
        "title": parser.title or url,
        "text": text,
        "content_hash": hashlib.sha256(body).hexdigest(),
        "content_type": content_type.split(";", 1)[0],
    }
