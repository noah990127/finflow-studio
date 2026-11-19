"""Versioned semantic slides using a licensed HTML template and an OSS exporter."""
import asyncio
import io
import json
import tempfile
from copy import deepcopy
from pathlib import Path

from lxml import html, etree

TEMPLATE_ID = "blue-professional-v2"
ROOT = Path(__file__).parent / "templates" / TEMPLATE_ID

DESIGN_INSTRUCTIONS = """
先根据受众和用途设计表达结构，再写页面；不要逐段搬运，不要机械一周一页。
每页 visual 必须说明 rationale（为什么这种结构能帮助读者），不显示在正文。
visual.kind 可为 timeline/process/matrix/comparison/statement/list。
timeline/process: items=[{label,detail,owner,outcome}]，2至5项，分别是阶段、任务、负责人、交付物；未知可留空，不能虚构。
matrix/comparison: columns=[列名]，rows=[{label:行名,cells:[逐列内容]}]，最多5列6行，各行长度等于列数。
例如职责分配用角色×阶段矩阵，方案比较用共同评价维度，不得退化成两栏无关联文字。
statement/list: items=[{label,detail}]，仅用于没有更强关系的内容，最多6项。
每页必须有 heading、coreMessage、visual；paragraphs/bullets为空，避免重复正文。不得同时提交chart。
研究需要真实来源；计划只需忠实表达安排，不强制论证、三点、图表、结论标题或行动收尾。
每一页必须有独立用途，信息少就合并；除用户明确要求，不固定页数或一阶段一页。
模板ID blue-professional-v2，版本固定；任何首次设计缺陷必须先修正并检查真实导出再交付。
"""


def validate_design(request):
    if request.presentation_brief.include_cover:
        raise ValueError("本版本请将开场设计为显式section，不自动添加封面")
    for index, section in enumerate(request.sections, 1):
        if section.visual is None:
            raise ValueError(f"第{index}页缺少visual结构，请先设计表达结构；不会降级为文字列表")
        if section.bullets or section.paragraphs or section.chart:
            raise ValueError(f"第{index}页请将正文放入visual结构，禁止混用旧正文协议")


async def prepare(request, complete):
    if all(section.visual is not None for section in request.sections):
        validate_design(request)
        return request
    from .models import DeliverableSection
    material = request.model_dump_json()
    instruction = DESIGN_INSTRUCTIONS + '\n只返回JSON {"sections":[{heading,coreMessage,visual}]}。'
    result = await asyncio.wait_for(complete(instruction, material), 90)
    if not result:
        raise ValueError("演示结构设计未返回内容，请重试；不会退回旧版文字排版")
    payload = json.loads(result.strip().removeprefix("```json").removesuffix("```").strip())
    sections = [DeliverableSection(heading=p["heading"], core_message=p.get("coreMessage", ""),
                                  visual=p["visual"], layout=p["visual"]["kind"])
                for p in payload["sections"]]
    if not 1 <= len(sections) <= 18:
        raise ValueError("演示设计需要1至18页")
    # Preserve verified provenance; the model cannot invent source records.
    refs = [ref for s in request.sections for ref in s.refs]
    for section in sections:
        section.refs = refs
    planned = request.model_copy(update={"sections": sections}, deep=True)
    validate_design(planned)
    return planned


def _element(tag, text=None, cls=None):
    node = etree.Element(tag)
    if cls:
        node.set("class", cls)
    node.text = text
    return node


def _first(node, cls):
    return node.find_class(cls)[0]


def _replace_text(node, text):
    for child in list(node):
        node.remove(child)
    node.text = text


def render_html(request):
    validate_design(request)
    source = html.parse(str(ROOT / "blue-professional.html")).getroot()
    doc = html.fromstring("<html><head><meta charset='utf-8'></head><body><div class='deck'></div></body></html>")
    head = doc.find("head")
    # Keep upstream composition/typography rules; omit network fonts and all upstream JS.
    for style in source.xpath("//head/style"):
        head.append(deepcopy(style))
    head.append(_element("style", (ROOT / "offline.css").read_text()))
    deck = _first(doc, "deck")
    for index, section in enumerate(request.sections, 1):
        visual = section.visual
        layout = "timeline" if visual.kind in {"timeline", "process"} else "detail"
        slide = deepcopy(_first(source, "layout-" + layout))
        slide.set("class", "slide layout-" + layout + (" active" if index == 1 else ""))
        _replace_text(slide.xpath(".//h4")[0], request.title)
        _replace_text(_first(slide, "tag"), f"{index:02d} / {len(request.sections):02d}")
        _replace_text(slide.xpath(".//h2")[0], section.heading)
        content = _first(slide, "slide-content")
        content.insert(1, _element("p", section.core_message, "page-message"))
        region = _first(slide, "timeline-track" if layout == "timeline" else "detail-body")
        region.clear()
        if layout == "timeline":
            region.set("class", "timeline-track")
            for i, item in enumerate(visual.items, 1):
                step = _element("div", cls="timeline-step")
                for cls, value in [("step-circle", str(i)), ("step-title", item.label),
                                   ("step-desc", item.detail), ("step-owner", item.owner),
                                   ("step-outcome", item.outcome)]:
                    if value:
                        step.append(_element("div", value, cls))
                region.append(step)
        elif visual.kind in {"matrix", "comparison"}:
            region.set("class", "relationship-table")
            table = _element("table")
            row = _element("tr")
            for value in [""] + visual.columns:
                row.append(_element("th", value))
            table.append(row)
            for data in visual.rows:
                row = _element("tr")
                row.append(_element("th", data.label))
                for value in data.cells:
                    row.append(_element("td", value))
                table.append(row)
            region.append(table)
        else:
            region.set("class", "detail-body")
            for item in visual.items:
                block = _element("div", cls="detail-block")
                block.append(_element("h3", item.label))
                for value in [item.detail, item.owner, item.outcome]:
                    if value:
                        block.append(_element("p", value))
                region.append(block)
        deck.append(slide)
    return etree.tostring(doc, encoding="unicode", method="html")


async def _export(request):
    from html_to_pptx import extract_measurements, render_pptx
    with tempfile.TemporaryDirectory(prefix="studio-deck-") as tmp:
        path = Path(tmp) / "slides.html"
        path.write_text(render_html(request))
        measurements = await asyncio.wait_for(extract_measurements(str(path)), 90)
        deck = render_pptx(measurements)
        deck.core_properties.title = request.title
        deck.core_properties.subject = TEMPLATE_ID
        for slide, section in zip(deck.slides, request.sections, strict=True):
            from .ppt_skills import _set_run_font
            for shape in slide.shapes:
                if not getattr(shape, "has_text_frame", False):
                    continue
                for paragraph in shape.text_frame.paragraphs:
                    for run in paragraph.runs:
                        # The exporter writes Latin font metadata only; Office also needs EA.
                        _set_run_font(run, run.font.name or "Noto Sans CJK SC")
                if shape.text == request.title or " / " in shape.text and len(shape.text) < 12:
                    shape.text_frame.word_wrap = False
            rendered = "".join(s.text for s in slide.shapes if getattr(s, "has_text_frame", False))
            values = [section.heading, section.core_message] + section.visual.columns
            values += [v for item in section.visual.items for v in (item.label, item.detail, item.owner, item.outcome)]
            values += [v for row in section.visual.rows for v in [row.label, *row.cells]]
            compact = lambda value: "".join(value.split())
            if any(compact(value) not in compact(rendered) for value in values if value):
                raise ValueError("导出丢失设计内容，请检查模板容量；不会交付缺字文件")
            slide.notes_slide.notes_text_frame.text = json.dumps({
                "design": section.visual.model_dump(), "brief": request.presentation_brief.model_dump(),
                "sources": [ref.model_dump() for ref in section.refs]}, ensure_ascii=False)
        stream = io.BytesIO()
        deck.save(stream)
        return stream.getvalue()


def render(request):
    return asyncio.run(_export(request))
