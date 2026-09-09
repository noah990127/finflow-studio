import asyncio
import json
import re
from typing import Awaitable, Callable, Optional

from ..llm import llm
from ..models import DeliverableRequest, DeliverableSection
from ..services.presentation import prepare_presentation
from .models import ArtifactFormat, EvidenceClaim, EvidencePack, EvidenceSource


Complete = Callable[[str, str], Awaitable[Optional[str]]]
PLANNER_TIMEOUT_SECONDS = 75


async def prepare_for_format(
    output_format: ArtifactFormat,
    request: DeliverableRequest,
    complete: Optional[Complete] = None,
) -> tuple[DeliverableRequest, EvidencePack]:
    evidence = build_evidence_pack(request)
    if output_format in {"pptx", "html_slides"}:
        return await prepare_presentation(request, complete=complete), evidence
    if output_format in {"docx", "pdf"}:
        return await _prepare_document(request, evidence, complete), evidence
    if output_format in {"mermaid", "excalidraw"}:
        return await _prepare_diagram(request, evidence, complete), evidence
    return _prepare_dashboard(request), evidence


def build_evidence_pack(request: DeliverableRequest) -> EvidencePack:
    sources: list[EvidenceSource] = []
    seen: set[str] = set()
    claims: list[EvidenceClaim] = []
    for section_index, section in enumerate(request.sections, start=1):
        ref_ids: list[str] = []
        for ref_index, ref in enumerate(section.refs, start=1):
            ref_id = ref.ref_id or f"source-{section_index}-{ref_index}"
            ref_ids.append(ref_id)
            if ref_id not in seen:
                seen.add(ref_id)
                sources.append(EvidenceSource(
                    ref_id=ref_id,
                    source_name=ref.source_name,
                    resource_id=ref.resource_id,
                    version=ref.version,
                    content_hash=ref.content_hash,
                    location=ref.location,
                    excerpt=ref.text[:6000],
                ))
        values = ([section.core_message] if section.core_message else []) + section.paragraphs + section.bullets
        for claim_index, value in enumerate(values, start=1):
            clean = re.sub(r"\s+", " ", value).strip()
            if clean:
                claims.append(EvidenceClaim(
                    claim_id=f"claim-{section_index}-{claim_index}",
                    kind=_claim_kind(clean),
                    text=clean[:4000],
                    ref_ids=ref_ids,
                    confidence=0.9 if ref_ids else 0.6,
                ))
    return EvidencePack(
        task_goal=request.title + ("：" + request.subtitle if request.subtitle else ""),
        audience_inference="根据成果格式与用户生成要求推断，未使用固定受众模板。",
        claims=claims[:500],
        sources=sources,
    )


async def _prepare_document(
    request: DeliverableRequest, evidence: EvidencePack, complete: Optional[Complete]
) -> DeliverableRequest:
    complete = complete or llm.complete
    if not complete:
        return enforce_document_rules(request)
    try:
        response = await asyncio.wait_for(
            complete(_document_system(), _document_prompt(request, evidence)),
            timeout=PLANNER_TIMEOUT_SECONDS,
        )
        candidate = _document_from_response(request, response)
        if candidate is not None:
            request = candidate
    except (asyncio.TimeoutError, RuntimeError, ValueError, json.JSONDecodeError):
        pass
    return enforce_document_rules(request)


async def _prepare_diagram(
    request: DeliverableRequest, evidence: EvidencePack, complete: Optional[Complete]
) -> DeliverableRequest:
    if any(_is_mermaid(value) for section in request.sections for value in section.paragraphs):
        return request
    complete = complete or llm.complete
    if not complete:
        return request
    try:
        response = await asyncio.wait_for(
            complete(_diagram_system(), _diagram_prompt(request, evidence)),
            timeout=PLANNER_TIMEOUT_SECONDS,
        )
        source = _mermaid_response(response)
        if source:
            refs = [ref for section in request.sections for ref in section.refs]
            section = DeliverableSection(heading=request.title, paragraphs=[source], refs=refs)
            return request.model_copy(update={"sections": [section]}, deep=True)
    except (asyncio.TimeoutError, RuntimeError, ValueError):
        pass
    return request


def enforce_document_rules(request: DeliverableRequest) -> DeliverableRequest:
    sections: list[DeliverableSection] = []
    for source in request.sections[:30]:
        heading = _compact(source.heading, 80) or "分析结果"
        paragraphs: list[str] = []
        bullets: list[str] = []
        for value in source.paragraphs:
            for part in re.split(r"\n{2,}", value):
                clean = re.sub(r"\s+", " ", part).strip()
                if not clean:
                    continue
                if re.match(r"^(?:[-*•]|\d+[.)、])\s*", clean):
                    bullets.append(re.sub(r"^(?:[-*•]|\d+[.)、])\s*", "", clean)[:500])
                else:
                    paragraphs.extend(_split_long_paragraph(clean, 900))
        bullets.extend(re.sub(r"\s+", " ", value).strip()[:500] for value in source.bullets if value.strip())
        if not paragraphs and source.core_message:
            paragraphs = [source.core_message]
        if not paragraphs and not bullets:
            continue
        sections.append(source.model_copy(update={
            "heading": heading,
            "paragraphs": paragraphs[:16],
            "bullets": _deduplicate(bullets)[:12],
        }, deep=True))
    return request.model_copy(update={"sections": sections or request.sections[:1]}, deep=True)


def _prepare_dashboard(request: DeliverableRequest) -> DeliverableRequest:
    sections: list[DeliverableSection] = []
    for section in request.sections[:20]:
        heading = _compact(section.heading, 80)
        core = section.core_message or next(iter(section.paragraphs), "")
        sections.append(section.model_copy(update={
            "heading": heading,
            "core_message": _compact(core, 300),
            "paragraphs": [_compact(value, 1200) for value in section.paragraphs[:4]],
            "bullets": [_compact(value, 300) for value in section.bullets[:6]],
        }, deep=True))
    return request.model_copy(update={"sections": sections}, deep=True)


def _document_system() -> str:
    return """你是资深商业文档编辑。把材料组织成可连续阅读、可审阅的正式文档，而不是演示文稿。
严格返回 JSON，不要 Markdown。结构：{"document_type":"report|proposal|memo|sop|minutes","executive_summary":"摘要","sections":[{"heading":"章节标题","purpose":"本节目的","paragraphs":["完整段落"],"bullets":["必要清单"],"ref_indexes":[1]}]}。
要求：先回答用户问题，再按证据、分析、影响、建议组织；章节之间有承接；段落表达完整；仅在并列事项时使用列表；不虚构材料中没有的事实和数字；引用使用 [Ref N]；不要输出 CSS、HTML 或排版说明。"""


def _diagram_system() -> str:
    return """你是信息架构与业务流程建模专家。将材料转换成最合适且可读的 Mermaid 图。
只返回 Mermaid 源码，不要代码围栏和解释。根据任务选择 flowchart、sequenceDiagram、stateDiagram-v2、erDiagram、timeline 或 gantt。控制在 24 个核心节点内；节点名称简短；决策必须有条件标签；不得虚构流程；加入 accTitle 和 accDescr。"""


def _document_prompt(request: DeliverableRequest, evidence: EvidencePack) -> str:
    payload = {
        "title": request.title,
        "subtitle": request.subtitle,
        "sections": [{"heading": s.heading, "paragraphs": s.paragraphs, "bullets": s.bullets}
                     for s in request.sections],
        "sources": [source.model_dump() for source in evidence.sources],
    }
    return "请编辑为专业正式文档：\n" + json.dumps(payload, ensure_ascii=False)[:45_000]


def _diagram_prompt(request: DeliverableRequest, evidence: EvidencePack) -> str:
    payload = {"title": request.title, "claims": [claim.model_dump() for claim in evidence.claims[:80]]}
    return "请生成最适合表达这些内容的图：\n" + json.dumps(payload, ensure_ascii=False)[:30_000]


def _document_from_response(base: DeliverableRequest, value: Optional[str]) -> Optional[DeliverableRequest]:
    if not value:
        return None
    payload = _json_object(value)
    items = payload.get("sections")
    if not isinstance(items, list) or not items:
        return None
    all_refs = [ref for section in base.sections for ref in section.refs]
    sections: list[DeliverableSection] = []
    summary = re.sub(r"\s+", " ", str(payload.get("executive_summary") or "")).strip()
    if summary:
        sections.append(DeliverableSection(heading="执行摘要", paragraphs=[summary[:6000]], refs=all_refs))
    for raw in items[:30]:
        if not isinstance(raw, dict):
            continue
        heading = _compact(str(raw.get("heading") or ""), 80)
        paragraphs = raw.get("paragraphs") if isinstance(raw.get("paragraphs"), list) else []
        bullets = raw.get("bullets") if isinstance(raw.get("bullets"), list) else []
        indexes = raw.get("ref_indexes") if isinstance(raw.get("ref_indexes"), list) else []
        refs = [all_refs[index - 1] for index in indexes if isinstance(index, int) and 1 <= index <= len(all_refs)]
        if heading and (paragraphs or bullets):
            sections.append(DeliverableSection(
                heading=heading,
                paragraphs=[str(item) for item in paragraphs],
                bullets=[str(item) for item in bullets],
                refs=refs,
            ))
    return base.model_copy(update={"sections": sections}, deep=True) if sections else None


def _json_object(value: str) -> dict:
    clean = re.sub(r"^```(?:json)?\s*|\s*```$", "", value.strip(), flags=re.IGNORECASE)
    start, end = clean.find("{"), clean.rfind("}")
    if start < 0 or end <= start:
        raise json.JSONDecodeError("missing JSON object", clean, 0)
    payload = json.loads(clean[start:end + 1])
    if not isinstance(payload, dict):
        raise ValueError("document plan must be an object")
    return payload


def _mermaid_response(value: Optional[str]) -> str:
    if not value:
        return ""
    clean = re.sub(r"^```(?:mermaid)?\s*|\s*```$", "", value.strip(), flags=re.IGNORECASE)
    return clean if _is_mermaid(clean) else ""


def _is_mermaid(value: str) -> bool:
    return bool(re.match(r"^(?:---[\s\S]*?---\s*)?(?:flowchart|graph|sequenceDiagram|stateDiagram-v2|erDiagram|timeline|gantt)\b", value.strip(), re.IGNORECASE))


def _claim_kind(value: str) -> str:
    if any(word in value for word in ("建议", "应当", "需要", "优先")):
        return "recommendation"
    if any(word in value for word in ("可能", "预计", "推断", "表明")):
        return "inference"
    if re.search(r"(?:同比|环比|增长|下降|占比).{0,12}\d", value):
        return "calculation"
    if any(word in value for word in ("限制", "不足", "暂缺", "无法")):
        return "limitation"
    return "fact"


def _split_long_paragraph(value: str, limit: int) -> list[str]:
    if len(value) <= limit:
        return [value]
    sentences = [item.strip() for item in re.split(r"(?<=[。！？])", value) if item.strip()]
    result: list[str] = []
    current = ""
    for sentence in sentences:
        if current and len(current) + len(sentence) > limit:
            result.append(current)
            current = sentence
        else:
            current += sentence
    if current:
        result.append(current)
    return result or [value[index:index + limit] for index in range(0, len(value), limit)]


def _deduplicate(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        key = re.sub(r"\W+", "", value).lower()
        if value and key not in seen:
            result.append(value)
            seen.add(key)
    return result


def _compact(value: str, limit: int) -> str:
    clean = re.sub(r"\s+", " ", value).strip(" ：:，,；;")
    return clean if len(clean) <= limit else clean[:limit - 1].rstrip("，,；;") + "…"
