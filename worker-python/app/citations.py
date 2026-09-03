import re
from typing import Iterable

from .models import DeliverableRef, DeliverableRequest


SUPPORTED_STYLES = {"IEEE", "APA_7", "GB_T_7714"}


def style(request: DeliverableRequest) -> str:
    value = (request.citation_style or "IEEE").upper()
    return value if value in SUPPORTED_STYLES else "IEEE"


def unique_refs(request: DeliverableRequest) -> list[DeliverableRef]:
    if not request.include_citations:
        return []
    result: list[DeliverableRef] = []
    seen: set[str] = set()
    for section in request.sections:
        for ref in section.refs:
            key = ref.ref_id or ref.source_name + repr(sorted(ref.location.items()))
            if key not in seen:
                seen.add(key)
                result.append(ref)
    return result


def format_reference(ref: DeliverableRef, index: int, citation_style: str) -> str:
    source = ref.source_name.strip() or "未命名资料"
    location = _location(ref)
    suffix = f" {location}" if location else ""
    if citation_style == "APA_7":
        return f"{source}. (n.d.).{suffix}".strip()
    if citation_style == "GB_T_7714":
        return f"[{index}] {source}[EB/OL].{suffix}".strip()
    return f"[{index}] {source}.{suffix}".strip()


def reference_entries(request: DeliverableRequest) -> list[str]:
    citation_style = style(request)
    return [format_reference(ref, index, citation_style)
            for index, ref in enumerate(unique_refs(request), start=1)]


def reference_records(request: DeliverableRequest) -> list[dict[str, object]]:
    citation_style = style(request)
    return [{
        "id": ref.ref_id or f"citation-{index}",
        "resource_id": ref.resource_id,
        "version": ref.version,
        "source_name": ref.source_name,
        "text": ref.text,
        "location": ref.location,
        "content_hash": ref.content_hash,
        "formatted": format_reference(ref, index, citation_style),
    } for index, ref in enumerate(unique_refs(request), start=1)]


def inline_sources(request: DeliverableRequest, refs: Iterable[DeliverableRef]) -> str:
    if not request.include_citations:
        return ""
    catalog = unique_refs(request)
    indexes = [catalog.index(ref) + 1 for ref in refs if ref in catalog]
    if not indexes:
        return ""
    if style(request) == "APA_7":
        names = [_short_source_name(catalog[index - 1].source_name) for index in indexes]
        return "(" + "; ".join(f"{name}, n.d." for name in names) + ")"
    return "".join(f"[{index}]" for index in indexes)


def normalize_markers(value: str, request: DeliverableRequest) -> str:
    catalog = unique_refs(request)

    def replace(match: re.Match[str]) -> str:
        index = int(match.group(1))
        if not request.include_citations:
            return ""
        if style(request) == "APA_7" and 0 < index <= len(catalog):
            return f"({catalog[index - 1].source_name}, n.d.)"
        return f"[{index}]"

    normalized = re.sub(r"\[\s*Ref\s+(\d+)\s*\]", replace, value, flags=re.IGNORECASE)
    if not request.include_citations:
        normalized = re.sub(r"\s+(?=[，,。；;])", "", normalized)
    return normalized


def compact_inline_citations(value: str, request: DeliverableRequest) -> str:
    if style(request) != "APA_7":
        return value
    result = value
    for ref in unique_refs(request):
        source = ref.source_name.strip()
        if not source:
            continue
        short = _short_source_name(source)
        result = re.sub(
            r"[（(]" + re.escape(source) + r"\s*,?\s*(n\.d\.|\d{4})[）)]",
            lambda match: f"({short}, {match.group(1)})",
            result,
            flags=re.IGNORECASE,
        )
    result = re.sub(
        r"[（(]([^()（）]{1,80}?\.(?:csv|tsv|xlsx?|docx?|pdf|pptx?|md|txt))\s*,?\s*(n\.d\.|\d{4})[）)]",
        lambda match: f"({_short_source_name(match.group(1))}, {match.group(2)})",
        result,
        flags=re.IGNORECASE,
    )
    return result


def _short_source_name(source: str) -> str:
    clean = re.sub(r"\.(?:csv|tsv|xlsx?|docx?|pdf|pptx?|md|txt)$", "", source.strip(), flags=re.IGNORECASE)
    clean = re.sub(r"[_\-]+", " ", clean)
    clean = re.sub(r"\s+", " ", clean).strip()
    if len(clean) <= 18:
        return clean
    words = clean.split()
    if len(words) > 1:
        concise = " ".join(words[:2])
        if len(concise) <= 18:
            return concise
    return clean[:18].rstrip()


def _location(ref: DeliverableRef) -> str:
    preferred = []
    labels = {"page": "p.", "slide": "slide", "sheet": "sheet", "row": "row",
              "paragraph": "para.", "url": ""}
    for key, value in ref.location.items():
        if value in (None, ""):
            continue
        label = labels.get(str(key), str(key))
        preferred.append(f"{label} {value}".strip())
    return ", ".join(preferred)
