import asyncio
import json
import re
from typing import Awaitable, Callable, Optional

from ..llm import llm
from ..models import DeliverableChart, DeliverableRequest, DeliverableSection


PLANNER_TIMEOUT_SECONDS = 90
REVIEWER_TIMEOUT_SECONDS = 60
LAYOUTS = {"statement", "metric", "chart", "comparison", "timeline", "process", "matrix", "list"}
Complete = Callable[[str, str], Awaitable[Optional[str]]]


async def prepare_presentation(request: DeliverableRequest, complete: Optional[Complete] = None) -> DeliverableRequest:
    """Turn source material into a reviewed, page-level presentation plan."""
    complete = complete or llm.complete
    planned = request
    try:
        response = await asyncio.wait_for(
            complete(_planner_system(), _source_prompt(request)), timeout=PLANNER_TIMEOUT_SECONDS
        )
        candidate = _request_from_response(request, response)
        if candidate is not None:
            planned = candidate
    except (asyncio.TimeoutError, RuntimeError, ValueError, json.JSONDecodeError):
        planned = request

    max_slides = _recommended_slide_count(request)
    planned = enforce_presentation_rules(planned, max_slides=max_slides)
    try:
        review = await asyncio.wait_for(
            complete(_reviewer_system(), _review_prompt(planned)), timeout=REVIEWER_TIMEOUT_SECONDS
        )
        revised = _request_from_response(planned, review)
        if revised is not None:
            planned = revised
    except (asyncio.TimeoutError, RuntimeError, ValueError, json.JSONDecodeError):
        pass
    return enforce_presentation_rules(planned, max_slides=max_slides)


def evaluate_presentation(request: DeliverableRequest) -> dict[str, object]:
    issues: list[str] = []
    scores: list[int] = []
    for index, section in enumerate(request.sections, start=1):
        score = 100
        points = _section_points(section)
        if not section.core_message:
            issues.append(f"第{index}页缺少核心观点")
            score -= 20
        if len(section.heading) > 28:
            issues.append(f"第{index}页标题过长")
            score -= 10
        if len(points) > 4:
            issues.append(f"第{index}页要点超过4条")
            score -= 15
        if any(len(point) > 64 for point in points):
            issues.append(f"第{index}页存在过长要点")
            score -= 15
        if sum(map(len, points)) + len(section.core_message) > 280:
            issues.append(f"第{index}页信息密度过高")
            score -= 20
        if section.layout == "chart" and section.chart is None:
            issues.append(f"第{index}页图表布局缺少图表数据")
            score -= 25
        if re.search(r"\[Ref\s*\d+\]", section.core_message + " " + " ".join(points), re.IGNORECASE) and not section.refs:
            issues.append(f"第{index}页包含无效引用")
            score -= 25
        earlier = request.sections[:index - 1]
        if any(_similar(section.core_message, item.core_message) for item in earlier):
            issues.append(f"第{index}页与前文核心观点重复")
            score -= 25
        scores.append(max(0, score))
    return {
        "score": round(sum(scores) / len(scores)) if scores else 0,
        "slide_count": len(request.sections),
        "issues": issues,
        "passed": bool(scores) and min(scores) >= 70,
    }


def enforce_presentation_rules(request: DeliverableRequest, max_slides: int = 16) -> DeliverableRequest:
    sections: list[DeliverableSection] = []
    source_count = len(request.sections)
    for source_index, source in enumerate(request.sections, start=1):
        points = _section_points(source)
        seed_core = source.core_message or (points.pop(0) if points else source.heading)
        pages = [points[index:index + 3] for index in range(0, len(points), 3)] or [[]]
        for page_index, page_points in enumerate(pages):
            if len(sections) >= max_slides:
                break
            core = _compact(seed_core if page_index == 0 else page_points.pop(0), 88)
            bullets = [_compact(point, 60) for point in page_points if len(_clean(point)) >= 4][:3]
            core = _remove_orphan_citations(core, source.refs)
            bullets = [_remove_orphan_citations(point, source.refs) for point in bullets]
            provisional = source.model_copy(update={"core_message": core, "bullets": bullets}, deep=True)
            layout = resolve_layout(provisional, len(sections) + 1)
            chart = source.chart if page_index == 0 else None
            if layout == "chart" and chart is None:
                layout = "metric" if _metric(core + " " + " ".join(bullets)) else "statement"
            if layout == "metric" and not _metric(core):
                layout = "statement"
            if any(_similar(core, existing.core_message) for existing in sections):
                continue
            heading = source.heading if page_index == 0 else core
            sections.append(source.model_copy(update={
                "heading": _compact(heading, 28),
                "chapter": _compact(source.chapter or _chapter_for(source_index, source_count), 40),
                "core_message": core,
                "layout": layout,
                "paragraphs": [core],
                "bullets": bullets,
                "chart": chart,
            }, deep=True))
        if len(sections) >= max_slides:
            break
    return request.model_copy(update={"sections": sections}, deep=True)


def resolve_layout(section: DeliverableSection, index: int) -> str:
    if section.chart is not None:
        return "chart"
    if section.layout in LAYOUTS and section.layout != "chart":
        return section.layout
    text = (section.heading + " " + section.core_message + " " + " ".join(section.bullets)).lower()
    if index == 1 or any(word in text for word in ("摘要", "结论", "判断")):
        return "statement"
    if any(word in text for word in ("时间", "阶段", "里程碑", "演进", "路线")):
        return "timeline"
    if any(word in text for word in ("流程", "步骤", "行动", "实施", "报名")):
        return "process"
    if any(word in text for word in ("对比", "比较", "结构", "组合", "差异")):
        return "comparison"
    if any(word in text for word in ("风险", "优先级", "矩阵")):
        return "matrix"
    if _metric(text):
        return "metric"
    return "list"


def _planner_system() -> str:
    return """你是资深演示文稿内容架构师。你的任务是把有依据的材料转成可演示的页面级叙事，不负责绘制版面。
严格输出 JSON，不要 Markdown。结构：{"slides":[{"chapter":"章节","title":"结论式标题","core_message":"本页唯一核心观点","layout":"statement|metric|chart|comparison|timeline|process|matrix|list","bullets":["证据或支撑点"],"ref_indexes":[1],"chart":null或{"type":"bar|line|pie","title":"图表标题","categories":["类别"],"series":[{"name":"系列","values":[1]}],"source_ref":"[Ref 1]"}}]}。
要求：大纲层级与页面顺序一致；每页只表达一个核心观点；标题必须是结论而非主题名；每页最多4条支撑点、每条尽量不超过45字；摘要先给结论，再按证据、比较/原因、影响、行动展开；存在可信数值序列时优先图表，否则使用结构图式布局；不得创造原材料没有的事实、数字或来源；引用使用 [Ref N]。"""


def _reviewer_system() -> str:
    return """你是演示文稿质量评估编辑。检查叙事完整性、单页单观点、标题是否为结论、页面层级、信息密度、布局与内容匹配、数字和引用可追溯性。直接返回修订后的完整 JSON，格式与输入 slides 相同；不要解释、不要 Markdown。不得新增输入中没有的事实或数字。"""


def _source_prompt(request: DeliverableRequest) -> str:
    refs = _all_refs(request)
    material = []
    for section in request.sections:
        material.append({
            "heading": section.heading,
            "paragraphs": section.paragraphs,
            "bullets": section.bullets,
        })
    evidence = [{
        "index": index,
        "source_name": ref.source_name,
        "text": ref.text[:1800],
        "location": ref.location,
    } for index, ref in enumerate(refs, start=1)]
    payload = {"title": request.title, "subtitle": request.subtitle, "material": material, "evidence": evidence}
    return "请规划一套内容完整、便于讲述的演示文稿：\n" + json.dumps(payload, ensure_ascii=False)[:45_000]


def _review_prompt(request: DeliverableRequest) -> str:
    report = evaluate_presentation(request)
    slides = [_section_payload(section, request) for section in request.sections]
    return "规则初评：" + json.dumps(report, ensure_ascii=False) + "\n待审稿页面：" + json.dumps({"slides": slides}, ensure_ascii=False)


def _request_from_response(base: DeliverableRequest, value: Optional[str]) -> Optional[DeliverableRequest]:
    if not value:
        return None
    payload = _json_object(value)
    slides = payload.get("slides")
    if not isinstance(slides, list) or not slides:
        return None
    refs = _all_refs(base)
    used_indexes: list[int] = []
    for raw in slides:
        if not isinstance(raw, dict):
            continue
        for index in _ref_indexes(raw):
            if 1 <= index <= len(refs) and index not in used_indexes:
                used_indexes.append(index)
    marker_map = {old: new for new, old in enumerate(used_indexes, start=1)}
    sections: list[DeliverableSection] = []
    for raw in slides[:16]:
        if not isinstance(raw, dict):
            continue
        title = _clean(_remap_markers(str(raw.get("title") or raw.get("heading") or ""), marker_map))
        core = _clean(_remap_markers(str(raw.get("core_message") or raw.get("summary") or ""), marker_map))
        bullets = raw.get("bullets") if isinstance(raw.get("bullets"), list) else []
        selected_refs = _select_refs(_ref_indexes(raw), refs)
        chart = _chart(raw.get("chart"))
        if chart:
            chart = chart.model_copy(update={"source_ref": _remap_markers(chart.source_ref, marker_map)})
        layout = str(raw.get("layout") or "auto").lower()
        if layout not in LAYOUTS:
            layout = "auto"
        if title and (core or bullets or chart):
            sections.append(DeliverableSection(
                heading=title,
                chapter=_clean(str(raw.get("chapter") or "")),
                core_message=core,
                layout=layout,
                paragraphs=[core] if core else [],
                bullets=[_clean(_remap_markers(str(item), marker_map)) for item in bullets],
                refs=selected_refs,
                chart=chart,
            ))
    return base.model_copy(update={"sections": sections}, deep=True) if sections else None


def _section_payload(section: DeliverableSection, request: DeliverableRequest) -> dict[str, object]:
    refs = _all_refs(request)
    indexes = [index for index, ref in enumerate(refs, start=1) if ref in section.refs]
    return {
        "chapter": section.chapter,
        "title": section.heading,
        "core_message": section.core_message,
        "layout": section.layout,
        "bullets": section.bullets,
        "ref_indexes": indexes,
        "chart": section.chart.model_dump() if section.chart else None,
    }


def _all_refs(request: DeliverableRequest) -> list:
    result = []
    seen = set()
    for section in request.sections:
        for ref in section.refs:
            key = ref.ref_id or (ref.source_name, ref.content_hash)
            if key not in seen:
                seen.add(key)
                result.append(ref)
    return result


def _ref_indexes(raw: dict) -> list[int]:
    values = raw.get("ref_indexes") if isinstance(raw.get("ref_indexes"), list) else []
    values = list(values) + re.findall(
        r"\[Ref\s*(\d+)\]", json.dumps(raw, ensure_ascii=False), flags=re.IGNORECASE
    )
    result: list[int] = []
    for value in values:
        try:
            index = int(value)
        except (TypeError, ValueError):
            continue
        if index not in result:
            result.append(index)
    return result


def _select_refs(indexes: list[int], refs: list) -> list:
    return [refs[index - 1] for index in indexes if 1 <= index <= len(refs)]


def _remap_markers(value: str, marker_map: dict[int, int]) -> str:
    def replace(match: re.Match[str]) -> str:
        mapped = marker_map.get(int(match.group(1)))
        return f"[Ref {mapped}]" if mapped is not None else ""
    return re.sub(r"\s*\[Ref\s*(\d+)\]", replace, value, flags=re.IGNORECASE).strip()


def _chart(raw: object) -> Optional[DeliverableChart]:
    if not isinstance(raw, dict):
        return None
    try:
        chart = DeliverableChart.model_validate(raw)
    except ValueError:
        return None
    count = len(chart.categories)
    return chart if count >= 2 and chart.series and all(len(series.values) == count for series in chart.series) else None


def _json_object(value: str) -> dict:
    candidate = re.sub(r"^```(?:json)?\s*", "", value.strip(), flags=re.IGNORECASE)
    candidate = re.sub(r"\s*```$", "", candidate)
    start, end = candidate.find("{"), candidate.rfind("}")
    if start < 0 or end <= start:
        raise json.JSONDecodeError("missing JSON object", candidate, 0)
    result = json.loads(candidate[start:end + 1])
    if not isinstance(result, dict):
        raise ValueError("presentation plan must be an object")
    return result


def _section_points(section: DeliverableSection) -> list[str]:
    values = list(section.paragraphs) + list(section.bullets)
    result: list[str] = []
    seen = set()
    for value in values:
        for part in re.split(r"\n+|(?<=[。！？；])", value):
            clean = _clean(part)
            key = clean.lower()
            if len(clean) >= 4 and key not in seen and clean != section.core_message:
                seen.add(key)
                result.append(clean)
    return result


def _clean(value: str) -> str:
    value = value.replace("**", "").replace("`", "")
    value = re.sub(r"^\s*(?:[#>*•\-]+\s*|\d+[.、]\s+)+", "", value)
    return re.sub(r"\s+", " ", value).strip(" ；;。")


def _compact(value: str, limit: int) -> str:
    clean = _clean(value)
    if len(clean) <= limit:
        return clean
    clauses = [part.strip() for part in re.split(r"[；;。]", clean) if part.strip()]
    return next((part for part in clauses if len(part) <= limit), clean[:limit].rstrip("，,；;。"))


def _metric(value: str) -> bool:
    return bool(re.search(r"[-+]?\d[\d,.]*(?:%|亿元|万元|万|亿美元|天|项|个)", value))


def _remove_orphan_citations(value: str, refs: list) -> str:
    if refs:
        return value
    return re.sub(r"\s*\[Ref\s*\d+\]", "", value, flags=re.IGNORECASE).strip()


def _similar(left: str, right: str) -> bool:
    left, right = _clean(left), _clean(right)
    if not left or not right:
        return False
    left_numbers = set(re.findall(r"\d+(?:\.\d+)?%?", left))
    right_numbers = set(re.findall(r"\d+(?:\.\d+)?%?", right))
    if left_numbers and right_numbers:
        return left_numbers == right_numbers
    left_chars, right_chars = set(left), set(right)
    return len(left_chars & right_chars) / max(1, min(len(left_chars), len(right_chars))) >= 0.82


def _recommended_slide_count(request: DeliverableRequest) -> int:
    structured = 0
    facts = 0
    for section in request.sections:
        for paragraph in section.paragraphs:
            try:
                payload = _json_object(paragraph)
                slides = payload.get("slides")
                if isinstance(slides, list):
                    structured = max(structured, len(slides))
            except (json.JSONDecodeError, ValueError):
                facts += len([part for part in re.split(r"\n+|(?<=[。！？；])", paragraph) if _clean(part)])
        facts += len(section.bullets)
    if structured:
        return max(2, min(16, structured))
    return max(3, min(12, (facts + 1) // 2 + 1))


def _chapter_for(index: int, total: int) -> str:
    if index == 1:
        return "结论摘要"
    if index == total:
        return "行动与展望"
    return "分析与证据"
