import asyncio
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import re
import shutil
import socket
import subprocess
import tempfile
import urllib.request
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
import jieba

from .config import settings
from .integrations.search_provider import SearchProviderUnavailable, build_search_provider


_SEARCH_PREFIX = re.compile(
    r"^(?:(?:新建|创建)(?:一个|个)?项目[，,、：:\s]*)?"
    r"(?:请|麻烦|可以|能否|能不能)?(?:帮我|帮忙)?"
    r"(?:查找|搜索|检索|调研|研究|分析|了解|看看|总结|梳理)(?:一下|下)?[，,、：:\s]*"
)
_SEARCH_STOP_WORDS = {
    "一下", "一个", "一些", "以及", "相关", "资料", "公开", "来源", "内容", "信息",
    "分析", "调研", "研究", "查找", "搜索", "检索", "总结", "梳理", "事件", "情况",
    "当前", "最近", "近期", "发生", "进行", "基于", "需要", "要求", "生成", "输出",
    "时间线", "舆论", "形势", "报告", "中文", "工作流", "项目", "结果", "成果",
}


def prepare_search_query(value: str) -> str:
    """Turn a business request into a short, search-engine-friendly query."""
    normalized = re.sub(r"\s+", " ", value).strip()
    if not normalized:
        return normalized
    first_clause = re.split(r"[。！？!?；;：:\n]", normalized, maxsplit=1)[0].strip()
    candidate = _SEARCH_PREFIX.sub("", first_clause).strip(" ，,、：:") or first_clause
    candidate = re.sub(r"^(?:最近|近期|当前)(?:发生的|的)?", "", candidate).strip()
    candidate = re.split(
        r"(?:的时间线|当前舆论|舆论形势|并(?:且)?(?:生成|输出|做成)|做成|生成一份|输出一份)",
        candidate,
        maxsplit=1,
    )[0].strip() or candidate
    tokens: list[str] = []
    for token in jieba.lcut(candidate, cut_all=False):
        cleaned = token.strip().lower()
        if not cleaned or cleaned in _SEARCH_STOP_WORDS:
            continue
        if re.fullmatch(r"[\W_]+", cleaned):
            continue
        if len(cleaned) == 1 and not re.fullmatch(r"[a-z0-9]", cleaned):
            continue
        tokens.append(cleaned)
        if len(tokens) >= 12:
            break
    return " ".join(tokens) if tokens else candidate[:120]


def prepare_search_queries(value: str) -> list[str]:
    """Build a small generic fallback set without adding domain-specific terms."""
    primary = prepare_search_query(value)
    variants = [primary]
    if re.search(r"[\u4e00-\u9fff]", primary):
        compact = re.sub(r"\s+", "", primary)
        if compact and compact != primary:
            variants.append(compact)
    return list(dict.fromkeys(item for item in variants if item))[:2]


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
    if ((host in {"bing.com", "www.bing.com"} and parsed.path.startswith("/search"))
            or (host in {"google.com", "www.google.com"} and parsed.path.startswith("/search"))
            or (host in {"baidu.com", "www.baidu.com"} and parsed.path.startswith("/s"))):
        raise ValueError("搜索结果页不能作为正式来源，请选择并验证具体内容页面")
    allowed = {item.lower().rstrip(".") for item in (domain_allowlist or [])} | _allowed_domains()
    if allowed and not any(host == domain or host.endswith("." + domain) for domain in allowed):
        raise ValueError("该网站不在本次任务允许的范围内")
    for info in socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM):
        address = ipaddress.ip_address(info[4][0])
        if not address.is_global:
            raise ValueError("不允许通过联网研究访问本机或内部网络")
    return url


class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, domain_allowlist: list[str] | None) -> None:
        self.domain_allowlist = domain_allowlist
        super().__init__()

    def redirect_request(self, request, fp, code, message, headers, new_url):
        validate_public_url(urljoin(request.full_url, new_url), self.domain_allowlist)
        return super().redirect_request(request, fp, code, message, headers, new_url)


def _fetch_with_urllib(url: str, domain_allowlist: list[str] | None,
                       headers: dict[str, str]) -> tuple[str, str, bytes, str]:
    opener = urllib.request.build_opener(_SafeRedirectHandler(domain_allowlist))
    request = urllib.request.Request(url, headers=headers)
    with opener.open(request, timeout=30) as response:
        final_url = response.geturl()
        validate_public_url(final_url, domain_allowlist)
        content_type = response.headers.get("content-type", "").lower()
        encoding = response.headers.get_content_charset() or "utf-8"
        body = response.read(settings.research_max_download_bytes + 1)
    return final_url, content_type, body, encoding


def _fetch_with_curl(url: str, domain_allowlist: list[str] | None,
                     headers: dict[str, str]) -> tuple[str, str, bytes, str]:
    executable = shutil.which("curl")
    if not executable:
        raise OSError("网页 TLS 握手失败，且当前环境没有可用的兼容读取器")
    current_url = url
    with tempfile.TemporaryDirectory(prefix="finflow-web-") as directory:
        root = Path(directory)
        for redirect_index in range(6):
            validate_public_url(current_url, domain_allowlist)
            body_path = root / f"body-{redirect_index}"
            header_path = root / f"headers-{redirect_index}"
            command = [
                executable, "--silent", "--show-error", "--output", str(body_path),
                "--dump-header", str(header_path), "--connect-timeout", "10", "--max-time", "30",
                "--max-filesize", str(settings.research_max_download_bytes),
                "--proto", "=http,https", "--request", "GET",
                "--write-out", "%{http_code}\n%{url_effective}\n%{content_type}",
            ]
            for name, value in headers.items():
                command.extend(["--header", f"{name}: {value}"])
            try:
                completed = subprocess.run(command + [current_url], capture_output=True, text=True,
                                           timeout=35, check=False)
            except subprocess.TimeoutExpired as exception:
                raise OSError("网页读取超时") from exception
            metadata = completed.stdout.splitlines()
            status = int(metadata[0]) if metadata and metadata[0].isdigit() else 0
            if 300 <= status < 400:
                header_text = header_path.read_text(encoding="iso-8859-1", errors="replace")
                match = re.search(r"(?im)^location:\s*(.+?)\s*$", header_text)
                if not match:
                    raise OSError("网页返回了重定向，但没有提供目标地址")
                current_url = urljoin(current_url, match.group(1).strip())
                continue
            if completed.returncode != 0 or status >= 400:
                message = completed.stderr.strip() or f"网页返回 HTTP {status or '错误'}"
                raise OSError(message[:500])
            final_url = metadata[1].strip() if len(metadata) > 1 else current_url
            validate_public_url(final_url, domain_allowlist)
            content_type = (metadata[2].strip() if len(metadata) > 2 else "").lower()
            body = body_path.read_bytes()
            if len(body) > settings.research_max_download_bytes:
                raise ValueError("网页内容超过本次研究任务的下载上限")
            charset = re.search(r"charset=([^;\s]+)", content_type)
            return final_url, content_type, body, charset.group(1).strip('"\'') if charset else "utf-8"
    raise OSError("网页重定向次数过多")


async def search_web(query: str, limit: int = 8) -> list[dict[str, str]]:
    provider = build_search_provider(
        settings.research_search_provider,
        settings.research_searxng_url,
        searxng_engines=settings.research_searxng_engines,
        connect_timeout=settings.research_search_connect_timeout_seconds,
        request_timeout=settings.research_search_timeout_seconds,
    )
    requested_limit = max(1, min(limit, 20))
    raw_items = []
    query_terms = [term.lower() for term in prepare_search_query(query).split() if len(term) > 1]
    seen_urls: set[str] = set()
    last_unavailable: Exception | None = None
    for search_query in prepare_search_queries(query):
        try:
            candidates = await provider.search(search_query, requested_limit)
        except SearchProviderUnavailable as exception:
            last_unavailable = exception
            continue
        for candidate in candidates:
            # Discard obvious off-topic engine fallbacks before accepting a query variant.
            searchable = (candidate.title + " " + candidate.snippet).lower()
            if query_terms and not any(term in searchable for term in query_terms):
                continue
            if candidate.url in seen_urls:
                continue
            seen_urls.add(candidate.url)
            raw_items.append(candidate)
            if len(raw_items) >= requested_limit:
                break
        if raw_items:
            break
    if not raw_items and last_unavailable is not None:
        raise last_unavailable
    raw_items.sort(key=lambda item: sum(
        term in (item.title + " " + item.snippet).lower() for term in query_terms
    ), reverse=True)
    items: list[dict[str, str]] = []
    for item in raw_items:
        url = item.url
        try:
            validate_public_url(url)
        except (ValueError, OSError):
            continue
        items.append({"title": item.title, "url": url, "snippet": item.snippet})
        if len(items) >= requested_limit:
            break
    return items


async def fetch_web(url: str, domain_allowlist: list[str] | None = None) -> dict[str, Any]:
    validate_public_url(url, domain_allowlist)
    embed_allowed = False
    headers = {
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.7",
    }
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=False, headers=headers) as client:
            response = await client.get(url)
            if response.is_redirect:
                target = urljoin(str(response.url), str(response.headers.get("location", "")))
                validate_public_url(target, domain_allowlist)
                response = await client.get(target)
            response.raise_for_status()
            # A restrictive or unknown embedding policy never disables the stored reader.
            embed_allowed = not response.headers.get("x-frame-options") and not response.headers.get("content-security-policy")
            final_url = str(response.url)
            content_type = response.headers.get("content-type", "").lower()
            body = response.content
            encoding = response.encoding or "utf-8"
    except (httpx.RemoteProtocolError, httpx.ConnectError):
        try:
            final_url, content_type, body, encoding = await asyncio.to_thread(
                _fetch_with_urllib, url, domain_allowlist, headers)
        except OSError:
            final_url, content_type, body, encoding = await asyncio.to_thread(
                _fetch_with_curl, url, domain_allowlist, headers)
    if len(body) > settings.research_max_download_bytes:
        raise ValueError("网页内容超过本次研究任务的下载上限")
    decoded = body.decode(encoding, errors="replace")
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
        "final_url": final_url,
        "title": title,
        "text": text,
        "tables": tables,
        "content_hash": hashlib.sha256(body).hexdigest(),
        "content_type": content_type.split(";", 1)[0],
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "embed_allowed": embed_allowed,
    }
