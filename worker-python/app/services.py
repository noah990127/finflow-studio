import asyncio
import logging
import math
import re
import time
from collections import Counter
from typing import AsyncIterator, Dict, Iterable, List

from .llm import llm
from .models import (
    ColumnProfile,
    DatasetProfileRequest,
    DatasetProfileResponse,
    GenerateContentRequest,
    GenerateContentResponse,
    KnowledgeChunk,
    SearchHit,
    SearchRequest,
    SummarizeRequest,
    SummarizeResponse,
)


TOKEN_PATTERN = re.compile(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]+", re.UNICODE)
SENTENCE_PATTERN = re.compile(r"(?<=[。！？.!?])\s*")
logger = logging.getLogger(__name__)
MAX_GENERATION_SOURCE_CHARS = 24_000
MAX_GENERATION_REQUIREMENTS_CHARS = 6_000


def _tokens(text: str) -> List[str]:
    tokens: List[str] = []
    for token in TOKEN_PATTERN.findall(text):
        normalized = token.lower().strip()
        if not normalized:
            continue
        if re.fullmatch(r"[\u4e00-\u9fff]+", normalized):
            tokens.extend(normalized[index : index + 2] for index in range(max(1, len(normalized) - 1)))
        elif len(normalized) > 1:
            tokens.append(normalized)
    return tokens


async def summarize(request: SummarizeRequest) -> SummarizeResponse:
    try:
        ai_result = await llm.complete(
            "你是 FinFlow Studio 的通用个人工作台助手。只分析用户提供的数据和参考内容，不补充外部事实。"
            "根据内容类型选择合适的分析方式，明确区分已知内容与推断，使用简洁中文。",
            "请先给出一段综合分析，再列出不超过%d个关键要点。\n\n%s"
            % (request.max_points, request.text),
        )
    except Exception as exception:
        logger.warning(
            "LLM analysis failed; using local fallback (%s)",
            type(exception).__name__,
        )
        ai_result = None
    sentences = [part.strip() for part in SENTENCE_PATTERN.split(request.text) if part.strip()]
    points = sentences[: request.max_points]
    if not points:
        points = [request.text[:500]]
    summary = ai_result.strip() if ai_result else "；".join(points)
    ref = KnowledgeChunk(
        ref_id="inline-ref-1",
        source_name=request.source_name,
        text=request.text[:500],
        location={"type": "inline", "start": 0},
    )
    return SummarizeResponse(
        summary=summary,
        points=points,
        mode=llm.provider if ai_result else "local-extractive",
        refs=[ref],
    )


def _generation_prompt(request: GenerateContentRequest) -> tuple[str, str]:
    output_format = request.format.strip().upper()
    if "来源标注：关闭" in request.requirements:
        citation_rules = "正文、图表和参考文献中不得输出 [Ref N]、来源编号或引用信息。"
    elif "APA 第 7 版" in request.requirements:
        citation_rules = ("每个事实、数字、判断和图表必须使用 APA 第 7 版文内引用；"
                          "缺少年份时使用（机构或作者, n.d.），chart.source_ref 使用相同格式。")
    else:
        citation_rules = ("每个事实、数字、判断和图表必须使用顺序编码引用 [1]、[2]；"
                          "chart.source_ref 使用相同编号，禁止输出 [Ref N] 字样。")
    if output_format in {"MERMAID", "EXCALIDRAW"}:
        system = (
            "你是 FinFlow Studio 的业务图表生成助手。只返回可执行的 Mermaid flowchart 源码，"
            "不要使用 Markdown 代码块，不要附加解释。只使用输入中的事实。"
        )
    elif output_format in {"PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT"}:
        is_slides = output_format in {"PPTX", "HTML_SLIDES"}
        container = "slides" if is_slides else "sections"
        item_title = "title" if is_slides else "heading"
        system = (
            "你是 FinFlow Studio 的通用业务成果设计助手。只返回一个合法 JSON 对象，不要使用 Markdown 代码块或附加解释。"
            f"JSON 顶层字段必须为 {container}，每项包含 {item_title}、summary、bullets、chart。"
            "chart 可以为 null；有连续期间、分类对比或构成数据时必须生成图表，格式为："
            '{"type":"bar|line|pie","title":"图表标题","categories":["分类"],'
            '"series":[{"name":"指标及单位","values":[1.0]}],"source_ref":"[Ref 1]"}。'
            "趋势用 line、分类比较用 bar、同一总体的构成用 pie；categories 与每个 series.values 长度必须相同。"
            "图表数值只能逐项复制自原始内容，不得估算、补齐、换算或编造；无法确认口径时 chart 必须为 null。"
            + citation_rules +
            "标题必须直接表达业务结论，禁止出现“第一页”“第4页”“Slide 2”“本页”等编排文字。"
            "标题必须是一行、最多22个汉字，不使用手工换行；summary 最多48个汉字，表达本页唯一核心判断；"
            "每项提供2至3个可独立阅读的 bullets，每条最多42个汉字，不重复summary，不写大段正文，"
            "保留金额、比例、期间和分类名称。相邻页面要承担不同叙事任务，按背景、关键发现、原因或风险、行动建议推进，"
            "不要把同一种三段式内容机械复制到每一页。可确认的数值达到3个以上时优先使用chart承载证据，"
            "正文只解释图表结论，不重复罗列全部数值。"
            + ("slides 只包含正文页，不包含封面；生成 4 至 8 页。" if is_slides else
               "sections 应形成 6 至 10 个完整报告章节，包括执行摘要、核心指标、趋势与结构、"
               "原因与风险、情景或对比、行动建议；至少 3 个章节应在有可靠数值时配置 chart。")
        )
    else:
        system = (
            "你是 FinFlow Studio 的财经业务成果生成助手。根据用户要求重新组织输入内容，"
            "先给结论，再展开支撑信息与行动建议；不得编造数据或外部事实。"
        )
    user = "输出格式：%s\n\n生成要求：\n%s\n\n原始内容：\n%s" % (
        output_format, request.requirements[:MAX_GENERATION_REQUIREMENTS_CHARS],
        request.source_text[:MAX_GENERATION_SOURCE_CHARS]
    )
    return system, user


async def generate_content(request: GenerateContentRequest) -> GenerateContentResponse:
    system, user = _generation_prompt(request)
    if not llm.configured:
        raise RuntimeError("大模型尚未配置，无法按生成要求制作成果")
    try:
        content = await llm.complete(system, user)
    except Exception as exception:
        logger.exception("LLM deliverable generation failed")
        raise RuntimeError("大模型生成失败：%s" % str(exception)) from exception
    if not content or not content.strip():
        raise RuntimeError("大模型没有返回可用内容")
    return GenerateContentResponse(content=content.strip(), mode=llm.provider)


async def generate_content_stream(request: GenerateContentRequest) -> AsyncIterator[dict[str, object]]:
    if not llm.configured:
        raise RuntimeError("大模型尚未配置，无法按生成要求制作成果")
    system, user = _generation_prompt(request)
    yield {"type": "status", "message": "正在连接大模型", "progress": 42}
    task = asyncio.create_task(llm.complete(system, user))
    started_at = time.monotonic()
    try:
        while not task.done():
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=1.5)
            except asyncio.TimeoutError:
                elapsed = max(1, round(time.monotonic() - started_at))
                progress = min(74, 48 + elapsed)
                yield {
                    "type": "status",
                    "message": "正在理解资料并组织内容（%d 秒）" % elapsed,
                    "progress": progress,
                }
        content = await task
        if not content or not content.strip():
            raise RuntimeError("大模型没有返回可用内容")
        clean = content.strip()
        yield {"type": "status", "message": "内容已生成，正在整理输出", "progress": 78}
        chunk_size = 90
        for index in range(0, len(clean), chunk_size):
            yield {
                "type": "content",
                "content": clean[index:index + chunk_size],
                "progress": min(92, 80 + int(12 * (index + chunk_size) / max(1, len(clean)))),
            }
            await asyncio.sleep(0.025)
        yield {"type": "complete", "content": clean, "mode": llm.provider, "progress": 94}
    except Exception as exception:
        logger.exception("Streaming LLM deliverable generation failed")
        yield {"type": "error", "message": "大模型生成失败：%s" % str(exception), "progress": 0}


def search(request: SearchRequest) -> List[SearchHit]:
    query_terms = Counter(_tokens(request.query))
    hits: List[SearchHit] = []
    for chunk in request.chunks:
        document_terms = Counter(_tokens(chunk.text))
        overlap = sum(min(count, document_terms[term]) for term, count in query_terms.items())
        denominator = math.sqrt(max(1, sum(query_terms.values())) * max(1, sum(document_terms.values())))
        score = overlap / denominator
        if score > 0:
            hits.append(
                SearchHit(
                    ref_id=chunk.ref_id,
                    source_name=chunk.source_name,
                    text=chunk.text,
                    location=chunk.location,
                    score=round(score, 4),
                )
            )
    return sorted(hits, key=lambda item: item.score, reverse=True)[: request.limit]


def profile_dataset(request: DatasetProfileRequest) -> DatasetProfileResponse:
    profiles: List[ColumnProfile] = []
    suggestions: List[str] = []
    row_count = len(request.rows)
    for column in request.columns:
        values = [row.get(column) for row in request.rows]
        non_null = [value for value in values if value is not None and value != ""]
        distinct = {_hashable(value) for value in non_null}
        null_count = row_count - len(non_null)
        profiles.append(
            ColumnProfile(
                name=column,
                non_null_count=len(non_null),
                null_count=null_count,
                distinct_count=len(distinct),
                sample_values=non_null[:5],
            )
        )
        if null_count:
            suggestions.append("字段 %s 有 %d 个空值，建议先确认是否需要补全" % (column, null_count))
        if non_null and len(distinct) == 1:
            suggestions.append("字段 %s 的样本值完全相同，可检查是否有分析价值" % column)
    if not suggestions:
        suggestions.append("当前样本未发现明显的空值或单一值字段")
    return DatasetProfileResponse(row_count=row_count, columns=profiles, suggestions=suggestions)


def _hashable(value: object) -> str:
    if isinstance(value, (dict, list)):
        return repr(value)
    return str(value)
