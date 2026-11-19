import io
import math
import os
import re
import sys
from typing import Iterable, List, Optional

from lxml.etree import SubElement
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

from .charting import add_native_ppt_chart, normalize_ppt_chart_ids, valid_chart
from .citations import inline_sources, reference_entries
from .models import DeliverableRequest, DeliverableSection


HUAWEI_STYLE_C = "guizang-huawei-style-c"
STUDIO_PROFESSIONAL = "studio-professional-native"
FRONTEND_SLIDES = "frontend-slides"
RED = RGBColor(199, 0, 11)
RED_DARK = RGBColor(153, 0, 8)
RED_SOFT = RGBColor(245, 229, 231)
PAPER = RGBColor(247, 248, 250)
CARD = RGBColor(255, 255, 255)
INK = RGBColor(34, 37, 43)
MUTED = RGBColor(111, 118, 128)
LINE = RGBColor(223, 227, 232)
GREY = RGBColor(240, 242, 245)
WHITE = RGBColor(255, 255, 255)
FONT_FAMILY = os.getenv("PRESENTATION_FONT_FAMILY", "Arial Unicode MS" if sys.platform == "darwin" else "Noto Sans CJK SC")
SLIDE_LEFT = 0.95
SLIDE_RIGHT = 12.38
CONTENT_TOP = 1.68
CONTENT_BOTTOM = 6.56


def catalog() -> list[dict[str, object]]:
    return [{
        "id": "blue-professional-v2",
        "name": "Blue Professional · 结构化企业汇报",
        "description": "复用开源HTML模板，先设计路线图、职责矩阵或对比结构，再导出可编辑PPT。",
        "formats": ["pptx"],
        "theme": "Blue Professional",
        "source": "https://github.com/zarazhangrui/beautiful-html-templates/tree/main/templates/blue-professional",
        "license": "MIT",
    }, {
        "id": "presenton-executive-v1",
        "name": "Executive · 企业汇报",
        "description": "Presenton 开源版式的离线原生适配，根据汇报用途组织内容，支持可编辑文字与图表。",
        "formats": ["pptx"],
        "theme": "Executive",
        "source": "https://github.com/presenton/presenton/tree/main/templates/executive",
        "license": "Apache-2.0",
    }, {
        "id": STUDIO_PROFESSIONAL,
        "name": "Studio Professional · 原生可编辑",
        "description": "兼容历史工序的原生可编辑版式。",
        "formats": ["pptx"],
        "theme": "Studio Professional",
        "source": "https://github.com/hugohe3/ppt-master",
        "method_reference": "https://github.com/Gabberflast/academic-pptx-skill",
    }, {
        "id": HUAWEI_STYLE_C,
        "name": "华为企业汇报 · Style C",
        "description": "白灰底、企业红、清晰层级，适合管理层汇报、经营复盘和工作计划。",
        "formats": ["pptx"],
        "theme": "Classic Red",
        "source": "https://github.com/SeanDongX/guizang-ppt-skill",
    }, {
        "id": FRONTEND_SLIDES,
        "name": "Frontend Slides · 网页演示",
        "description": "单文件 HTML + JavaScript，支持动效、键盘翻页和浏览器内播放；不是 PowerPoint 文件。",
        "formats": ["html_slides"],
        "theme": "Editorial Data",
        "source": "https://github.com/zarazhangrui/frontend-slides",
    }]


def render(request: DeliverableRequest) -> bytes:
    deck = Presentation()
    deck.core_properties.title = request.title
    deck.core_properties.subject = "presentation-content-v1 / studio-native-v2"
    deck.slide_width = Inches(13.333)
    deck.slide_height = Inches(7.5)
    _cover(deck, request)
    slide_number = 2
    for section_index, section in enumerate(request.sections, start=1):
        points = _content_points(section)
        layout = section.layout
        if layout == "auto" and (section_index == 1 or any(
                word in section.heading for word in ("摘要", "总体", "核心结论"))):
            layout = "statement"
        capacity = 4
        if valid_chart(section.chart):
            capacity = 3
        elif layout == "statement" and all(len(point) <= 36 for point in points):
            # Four short supporting points fit beside the summary's lead.
            capacity = 5
        for page_index, group in enumerate(_chunks(points, capacity), start=1):
            slide = deck.slides.add_slide(deck.slide_layouts[6])
            _background(slide, PAPER)
            heading = section.heading if page_index == 1 else f"{section.heading}（续）"
            _chrome(slide, slide_number, section_index, section.chapter)
            _text(slide, heading, 1.82, 0.62, 10.45, 0.58, 27, INK, bold=True,
                  min_size=22, single_line=True)
            if valid_chart(section.chart):
                _chart_layout(slide, section.chart, group)
            elif layout in {"process", "timeline"} and section.core_message:
                _text(slide, section.core_message, 0.98, 1.66, 11.2, 0.62, 18, INK, min_size=15)
                steps = [point for point in group if _semantic_key(point) != _semantic_key(section.core_message)]
                _render_content(slide, heading, steps, section_index, layout)
            else:
                _render_content(slide, heading, group, section_index, layout)
            refs = inline_sources(request, section.refs)
            if refs and not (valid_chart(section.chart) and section.chart.source_ref):
                _text(slide, "参考：" + refs[:180], 0.95, 6.93, 10.55, 0.2, 8, MUTED,
                      min_size=8, single_line=True)
            _speaker_notes(slide, section, group, refs)
            slide_number += 1
    _reference_slides(deck, request, slide_number)
    stream = io.BytesIO()
    deck.save(stream)
    return normalize_ppt_chart_ids(stream.getvalue())


def _cover(deck: Presentation, request: DeliverableRequest) -> None:
    slide = deck.slides.add_slide(deck.slide_layouts[6])
    _background(slide, PAPER)
    _rect(slide, 0, 0, 0.34, 7.5, RED)
    _rect(slide, 0.92, 1.18, 0.18, 0.18, RED)
    label = "MANAGEMENT REPORT" if request.ppt_skill == HUAWEI_STYLE_C else "STUDIO PROFESSIONAL"
    _text(slide, label, 1.28, 1.12, 4.5, 0.3, 10, RED, bold=True)
    _text(slide, request.title, 0.95, 2.03, 10.75, 1.48, 42, INK, bold=True, min_size=32)
    _text(slide, request.subtitle or "由 FinBTP Studio 工作流生成", 0.98, 3.7, 10.2, 0.5, 17, MUTED,
          min_size=14, single_line=True)
    _rect(slide, 0.98, 4.46, 1.36, 0.07, RED)
    _text(slide, "FINBTP STUDIO", 10.75, 0.58, 1.65, 0.25, 10, INK, bold=True, align=PP_ALIGN.RIGHT)
    style_label = "企业汇报 · Classic Red" if request.ppt_skill == HUAWEI_STYLE_C else "结论先行 · 证据驱动"
    _text(slide, style_label, 0.98, 6.72, 4.2, 0.25, 10, MUTED)
    _text(slide, "01", 11.65, 5.64, 0.65, 0.5, 18, WHITE, bold=True, align=PP_ALIGN.CENTER,
          fill=RED, vertical=MSO_ANCHOR.MIDDLE)


def reflow_for_readability(request: DeliverableRequest) -> DeliverableRequest:
    """One bounded, deterministic repair: split text, never rewrite facts."""
    sections = []
    for section in request.sections:
        if section.chart is not None:
            sections.append(section)
            continue
        for index, points in enumerate(_chunks(_content_points(section), 2)):
            sections.append(section.model_copy(update={
                "heading": section.heading if index == 0 else section.heading + "（续）",
                "core_message": points[0], "paragraphs": [], "bullets": points[1:], "layout": "statement",
            }, deep=True))
    if len(sections) > 100:
        raise ValueError("内容超过100页，请拆分文稿；系统不会删减正文")
    return request.model_copy(update={"sections": sections}, deep=True)


def _chrome(slide, page_number: int, section_number: int, chapter: str = "") -> None:
    _rect(slide, 0.95, 0.78, 0.18, 0.18, RED)
    _text(slide, f"{section_number:02d}", 1.22, 0.7, 0.45, 0.3, 10, RED, bold=True,
          min_size=10, single_line=True)
    if chapter:
        _text(slide, chapter, 0.95, 0.26, 8.8, 0.22, 10, MUTED, bold=True,
              min_size=9, single_line=True)
    _text(slide, "FINBTP STUDIO", 10.72, 0.26, 1.65, 0.22, 9, INK, bold=True,
          align=PP_ALIGN.RIGHT, min_size=9, single_line=True)
    _rect(slide, 0.95, 1.38, 11.42, 0.018, LINE)
    _text(slide, str(page_number), 11.98, 6.96, 0.35, 0.18, 8, MUTED,
          align=PP_ALIGN.RIGHT, min_size=8, single_line=True)


def _cards(slide, points: List[str]) -> None:
    positions = [(0.95, 1.8), (6.72, 1.8), (0.95, 4.28), (6.72, 4.28)]
    for index, point in enumerate(points):
        left, top = positions[index]
        _rect(slide, left, top, 5.25, 2.05, CARD, line=LINE)
        _rect(slide, left, top, 0.08, 2.05, RED)
        _text(slide, f"{index + 1:02d}", left + 0.36, top + 0.28, 0.56, 0.34, 13, RED, bold=True)
        _text(slide, point, left + 0.36, top + 0.79, 4.45, 0.98, 16, INK, bold=False)


def _render_content(slide, heading: str, points: List[str], section_index: int,
                    requested_layout: str = "auto") -> None:
    layout = requested_layout
    normalized = heading.lower()
    if layout == "statement":
        _summary_layout(slide, points)
    elif layout == "process" or (layout == "auto" and
            any(keyword in normalized for keyword in ("行动", "责任", "闭环", "计划", "要求"))):
        _action_layout(slide, points)
    elif layout == "timeline" or (layout == "auto" and
            any(keyword in normalized for keyword in ("时间", "时点", "阶段", "路线", "里程碑", "演进"))):
        _timeline_layout(slide, points)
    elif layout == "matrix" or (layout == "auto" and
            any(keyword in normalized for keyword in ("风险", "准备度", "优先级", "矩阵", "暴露"))):
        _priority_layout(slide, points)
    elif layout == "decision":
        _decision_layout(slide, points)
    elif layout == "table":
        _table_layout(slide, points)
    elif layout == "comparison" or (layout == "auto" and
            any(keyword in normalized for keyword in ("对比", "结构", "组合", "场景", "区域", "平台"))):
        _comparison_layout(slide, points)
    elif layout == "list" or (layout == "auto" and
            any(keyword in normalized for keyword in ("top", "物料", "清单", "事项"))):
        _ranked_layout(slide, points)
    elif layout == "auto" and any(keyword in normalized for keyword in ("仓", "品类", "成因", "原因")):
        _split_layout(slide, points)
    elif layout == "metric" or (layout == "auto" and
            any(keyword in normalized for keyword in ("差异", "库龄", "减值", "金额", "占比"))):
        _metric_layout(slide, points)
    else:
        (_metric_layout if section_index % 3 == 0 else _split_layout)(slide, points)


def _timeline_layout(slide, points: List[str]) -> None:
    items = points[:4] or ["完成当前阶段评估"]
    _rect(slide, 1.42, 3.05, 10.0, 0.04, LINE)
    width = 10.45 / len(items)
    for index, point in enumerate(items):
        left = 1.0 + index * width
        _text(slide, f"0{index + 1}", left + 0.12, 2.72, 0.62, 0.62, 16, WHITE, bold=True,
              align=PP_ALIGN.CENTER, fill=RED if index == 0 else RED_DARK, vertical=MSO_ANCHOR.MIDDLE)
        _text(slide, "关键节点", left + 0.12, 3.65, width - 0.28, 0.28, 11, RED, bold=True)
        _text(slide, point, left + 0.12, 4.08, width - 0.3, 1.68, 15, INK, bold=index == 0,
              min_size=13)


def _priority_layout(slide, points: List[str]) -> None:
    items = points[:4] or ["建立风险跟踪与处置机制"]
    positions = [(0.95, 1.77), (6.72, 1.77), (0.95, 4.18), (6.72, 4.18)]
    labels = ["立即处理", "重点跟踪", "计划改善", "持续监测"]
    for index, point in enumerate(items):
        left, top = positions[index]
        _rect(slide, left, top, 5.25, 1.98, CARD if index != 0 else RED_SOFT, line=LINE)
        _text(slide, labels[index], left + 0.28, top + 0.22, 1.45, 0.3, 11, RED_DARK, bold=True)
        _text(slide, point, left + 0.28, top + 0.75, 4.65, 0.92, 16, INK, bold=index == 0,
              min_size=14)


def _comparison_layout(slide, points: List[str]) -> None:
    items = points[:4] or ["暂无可对比内容"]
    for index, point in enumerate(items):
        top = 1.72 + index * 1.18
        _text(slide, f"0{index + 1}", 1.02, top + 0.17, 0.58, 0.58, 17, WHITE, bold=True,
              align=PP_ALIGN.CENTER, fill=RED if index == 0 else INK, vertical=MSO_ANCHOR.MIDDLE)
        _text(slide, point, 2.05, top + 0.08, 9.9, 0.86, 18, INK, bold=index == 0,
              min_size=14, vertical=MSO_ANCHOR.MIDDLE)
        if index < len(items) - 1:
            _rect(slide, 2.05, top + 1.02, 9.9, 0.012, LINE)


def _summary_layout(slide, points: List[str]) -> None:
    lead = points[0] if points else "工作流已完成本次分析。"
    _rect(slide, 0.95, 1.76, 0.1, 4.72, RED)
    _text(slide, "核心判断", 1.34, 1.9, 1.25, 0.3, 11, RED, bold=True,
          min_size=11, single_line=True)
    _text(slide, lead, 1.32, 2.48, 5.6, 2.2, 25, INK, bold=True, min_size=19,
          vertical=MSO_ANCHOR.MIDDLE)
    _rect(slide, 1.34, 5.24, 1.34, 0.07, RED)
    supporting = points[1:] or [lead]
    row_spacing = min(1.52, 4.6 / len(supporting))
    for index, point in enumerate(supporting):
        top = 1.83 + index * row_spacing
        _text(slide, f"0{index + 1}", 7.38, top + 0.04, 0.42, 0.28, 11, RED, bold=True,
              min_size=11, single_line=True)
        _text(slide, point, 8.05, top, 4.0, 0.92, 16, INK, min_size=14)
        if index < len(supporting) - 1:
            _rect(slide, 7.38, top + min(1.12, row_spacing - 0.12), 4.68, 0.015, LINE)


def _metric_layout(slide, points: List[str]) -> None:
    lead = points[0] if points else "暂无指标"
    metric = _first_metric(lead)
    _rect(slide, 0.95, 1.78, 4.05, 4.82, CARD, line=LINE)
    _rect(slide, 0.95, 1.78, 0.1, 4.82, RED)
    _text(slide, "关键指标", 1.38, 2.17, 1.5, 0.28, 11, MUTED, bold=True,
          min_size=11, single_line=True)
    _text(slide, metric or "重点关注", 1.35, 2.78, 3.2, 0.72, 30 if metric else 23, RED_DARK,
          bold=True, min_size=22, single_line=True)
    _text(slide, lead, 1.38, 3.78, 3.12, 1.55, 17, INK, bold=True, min_size=15)
    _text(slide, "数据口径以工作流上游结果为准", 1.38, 5.85, 3.1, 0.28, 10, MUTED)
    supporting = points[1:4]
    for index, point in enumerate(supporting):
        top = 1.88 + index * 1.53
        _text(slide, f"0{index + 1}", 5.55, top + 0.08, 0.55, 0.3, 12, RED, bold=True)
        _text(slide, point, 6.25, top, 5.72, 0.92, 16, INK, min_size=14)
        if index < len(supporting) - 1:
            _rect(slide, 5.55, top + 1.16, 6.42, 0.015, LINE)


def _ranked_layout(slide, points: List[str]) -> None:
    for index, point in enumerate(points[:4]):
        top = 1.78 + index * 1.2
        fill = CARD if index % 2 == 0 else GREY
        _rect(slide, 0.95, top, 11.42, 0.98, fill, line=LINE if index % 2 == 0 else None)
        _text(slide, str(index + 1), 1.18, top + 0.17, 0.56, 0.56, 18, WHITE, bold=True,
              align=PP_ALIGN.CENTER, fill=RED if index == 0 else RED_DARK, vertical=MSO_ANCHOR.MIDDLE)
        _text(slide, point, 2.02, top + 0.16, 9.75, 0.62, 17, INK, bold=index == 0,
              min_size=15,
              vertical=MSO_ANCHOR.MIDDLE)


def _action_layout(slide, points: List[str]) -> None:
    items = points[:4]
    if not items:
        items = ["明确责任人与完成时间"]
    _rect(slide, 1.25, 3.08, 10.5, 0.04, LINE)
    width = 10.9 / len(items)
    for index, point in enumerate(items):
        left = 0.95 + index * width
        _text(slide, f"0{index + 1}", left + 0.18, 2.57, 0.62, 0.62, 16, WHITE, bold=True,
              align=PP_ALIGN.CENTER, fill=RED if index == 0 else RED_DARK, vertical=MSO_ANCHOR.MIDDLE)
        _text(slide, "责任动作", left + 0.18, 3.48, width - 0.36, 0.3, 11, RED, bold=True)
        _text(slide, point, left + 0.18, 4.0, width - 0.38, 1.7, 16, INK, bold=index == 0,
              min_size=14)


def _decision_layout(slide, points: List[str]) -> None:
    items = points[:4] or ["基于当前证据给出决策建议"]
    lead = items[0]
    _rect(slide, 0.95, 1.78, 7.12, 4.82, INK)
    _text(slide, "建议决策", 1.34, 2.15, 1.65, 0.3, 11, RED_SOFT, bold=True)
    _text(slide, lead, 1.32, 2.78, 6.15, 2.05, 25, WHITE, bold=True, min_size=19,
          vertical=MSO_ANCHOR.MIDDLE)
    _rect(slide, 1.34, 5.46, 1.38, 0.07, RED)
    labels = ["依据 01", "依据 02", "依据 03"]
    supporting = items[1:4] or ["依据已核验的上游结果"]
    for index, point in enumerate(supporting):
        top = 1.9 + index * 1.48
        _text(slide, labels[index], 8.52, top, 1.2, 0.28, 10, RED, bold=True)
        _text(slide, point, 8.52, top + 0.38, 3.62, 0.84, 15, INK, min_size=13)


def _table_layout(slide, points: List[str]) -> None:
    items = points[:4] or ["当前没有可展示的明细"]
    _rect(slide, 0.95, 1.75, 11.42, 0.55, INK)
    _text(slide, "#", 1.18, 1.9, 0.45, 0.22, 10, WHITE, bold=True)
    _text(slide, "关键事项", 1.85, 1.9, 7.1, 0.22, 10, WHITE, bold=True)
    _text(slide, "当前判断", 9.62, 1.9, 2.1, 0.22, 10, WHITE, bold=True)
    for index, point in enumerate(items):
        top = 2.3 + index * 1.0
        _rect(slide, 0.95, top, 11.42, 0.92, CARD if index % 2 == 0 else GREY, line=LINE)
        _text(slide, f"{index + 1:02d}", 1.18, top + 0.28, 0.45, 0.25, 11, RED, bold=True)
        _text(slide, point, 1.85, top + 0.2, 7.3, 0.48, 15, INK, min_size=13)
        _text(slide, "需关注" if index == 0 else "已记录", 9.62, top + 0.25, 1.6, 0.28,
              11, RED_DARK if index == 0 else MUTED, bold=index == 0)


def _split_layout(slide, points: List[str]) -> None:
    lead = points[0] if points else "工作流已完成本次分析。"
    _rect(slide, 0.95, 1.78, 4.62, 4.82, INK)
    _text(slide, "业务判断", 1.32, 2.18, 1.3, 0.28, 11, RED_SOFT, bold=True)
    _text(slide, lead, 1.32, 2.82, 3.85, 2.15, 23, WHITE, bold=True, min_size=18)
    _rect(slide, 1.32, 5.58, 1.15, 0.06, RED)
    for index, point in enumerate(points[1:4]):
        top = 1.93 + index * 1.48
        _text(slide, f"0{index + 1}", 6.12, top, 0.55, 0.3, 12, RED, bold=True)
        _text(slide, point, 6.82, top - 0.02, 5.0, 0.92, 16, INK, min_size=14)
        if index < min(2, len(points[1:4]) - 1):
            _rect(slide, 6.12, top + 1.12, 5.7, 0.015, LINE)


def _chart_layout(slide, chart, points: List[str]) -> None:
    add_native_ppt_chart(slide, chart, 0.95, 1.72, 7.35, 4.82, accent="C7000B")
    _text(slide, "关键判断", 8.72, 1.82, 2.2, 0.3, 11, RED, bold=True)
    for index, point in enumerate(points[:3]):
        top = 2.35 + index * 1.34
        _text(slide, f"0{index + 1}", 8.72, top + 0.02, 0.45, 0.3, 11, RED, bold=True)
        _text(slide, point, 9.28, top, 2.92, 1.02, 15, INK, bold=index == 0, min_size=13)
    if chart.source_ref:
        _text(slide, "图表来源：" + chart.source_ref, 0.98, 6.78, 7.2, 0.2, 8, MUTED,
              min_size=8, single_line=True)


def _first_metric(value: str) -> str:
    match = re.search(r"[-+]?\d[\d,.]*(?:%|亿元|万元|万|天)", value)
    return match.group(0) if match else ""


def _background(slide, color: RGBColor) -> None:
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = color


def _rect(slide, left: float, top: float, width: float, height: float, fill: RGBColor,
          line: Optional[RGBColor] = None):
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(left), Inches(top), Inches(width), Inches(height))
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    SubElement(shape._element.spPr, qn("a:effectLst"))
    if line:
        shape.line.color.rgb = line
    else:
        shape.line.fill.background()
    return shape


def _text(slide, text: str, left: float, top: float, width: float, height: float, size: int,
          color: RGBColor, bold: bool = False, align=PP_ALIGN.LEFT, fill: Optional[RGBColor] = None,
          vertical=MSO_ANCHOR.TOP, min_size: int = 12, single_line: bool = False) -> None:
    box = _rect(slide, left, top, width, height, fill) if fill else slide.shapes.add_textbox(
        Inches(left), Inches(top), Inches(width), Inches(height)
    )
    frame = box.text_frame
    frame.clear()
    frame.word_wrap = True
    frame.vertical_anchor = vertical
    frame.margin_left = frame.margin_right = Inches(0.05) if fill else 0
    frame.margin_top = frame.margin_bottom = 0
    normalized = re.sub(r"\s*\n+\s*", " " if single_line else "\n", str(text)).strip()
    fitted_size = _fit_font_size(normalized, width, height, size, min_size, single_line)
    for line_index, line in enumerate(normalized.splitlines() or [""]):
        paragraph = frame.paragraphs[0] if line_index == 0 else frame.add_paragraph()
        paragraph.alignment = align
        paragraph.line_spacing = 1.08
        paragraph.space_after = Pt(0)
        parts = re.split(r"(\[\d+(?:[-,]\d+)*\]|\([^)]*n\.d\.[^)]*\))", line)
        for part in parts:
            if not part:
                continue
            run = paragraph.add_run()
            run.text = part
            citation = bool(re.fullmatch(r"\[\d+(?:[-,]\d+)*\]|\([^)]*n\.d\.[^)]*\)", part))
            run.font.size = Pt(max(7, round(fitted_size * 0.58))) if citation else Pt(fitted_size)
            run.font.bold = bold
            run.font.color.rgb = color
            _set_run_font(run, FONT_FAMILY)


def _fit_font_size(text: str, width: float, height: float, preferred: int, minimum: int,
                   single_line: bool) -> int:
    visual_units = sum(1.0 if ord(char) > 255 else 0.56 for char in re.sub(r"\s+", " ", text))
    explicit_lines = max(1, text.count("\n") + 1)
    for candidate in range(preferred, minimum - 1, -1):
        units_per_line = max(1.0, width * 72 / (candidate * 0.94))
        estimated_lines = explicit_lines if single_line else max(explicit_lines, math.ceil(visual_units / units_per_line))
        if single_line and visual_units > units_per_line:
            continue
        if estimated_lines * candidate * 1.18 <= height * 72:
            return candidate
    return minimum


def _set_run_font(run, family: str) -> None:
    run.font.name = family
    properties = run._r.get_or_add_rPr()
    for tag in ("a:latin", "a:ea", "a:cs"):
        element = properties.find(qn(tag))
        if element is None:
            element = SubElement(properties, qn(tag))
        element.set("typeface", family)


def _reference_slides(deck: Presentation, request: DeliverableRequest, page_number: int) -> None:
    entries = reference_entries(request)
    for page_index, group in enumerate(_chunks(entries, 8), start=1):
        slide = deck.slides.add_slide(deck.slide_layouts[6])
        _background(slide, PAPER)
        _chrome(slide, page_number, 99)
        heading = "参考文献" if page_index == 1 else "参考文献（续）"
        _text(slide, heading, 1.82, 0.62, 10.45, 0.58, 27, INK, bold=True,
              min_size=22, single_line=True)
        for index, entry in enumerate(group):
            _text(slide, entry, 1.1, 1.72 + index * 0.62, 10.9, 0.48, 11, INK, min_size=9)
        page_number += 1


def _content_points(section: DeliverableSection) -> List[str]:
    raw: List[str] = [section.core_message] if section.core_message else []
    for paragraph in section.paragraphs:
        if section.bullets and len(section.paragraphs) == 1 and len(paragraph) <= 72:
            raw.append(paragraph)
        else:
            raw.extend(re.split(r"\n+|(?<=[。！？；])", paragraph))
    raw.extend(section.bullets)
    result: List[str] = []
    seen = set()
    for value in raw:
        clean = value.replace("**", "").replace("`", "")
        clean = re.sub(r"^\s*(?:[#>*•\-]+\s*|\d+[.、]\s+)+", "", clean)
        clean = re.sub(r"\s+", " ", clean).strip(" ；;。")
        semantic = _semantic_key(clean)
        if len(semantic) >= 4 and semantic not in seen:
            for item in _shorten_point(clean):
                key = _semantic_key(item)
                if key not in seen:
                    result.append(item)
                    seen.add(key)
    if len(result) > 120:
        raise ValueError("单个章节内容过多，请拆分章节后生成，系统不会截断正文")
    return result or ["工作流已完成处理，但当前步骤没有生成可展示的文字内容。"]


def _semantic_key(value: str) -> str:
    value = re.sub(r"\[(?:Ref\s*)?\d+\]", "", value, flags=re.IGNORECASE)
    return re.sub(r"[\s，,；;。！？!?：:]", "", value).lower()


def _shorten_point(value: str, limit: int = 72) -> List[str]:
    if len(value) <= limit:
        return [value]
    clauses = [item.strip() for item in re.split(r"(?<=[，,；;。])", value) if item.strip()]
    result: List[str] = []
    current = ""
    for clause in clauses:
        if current and len(current) + len(clause) > limit:
            result.append(current.rstrip("，,；;。"))
            current = clause
        else:
            current += clause
    if current:
        result.append(current.rstrip("，,；;。"))
    return [item[start:start + limit] for item in result for start in range(0, len(item), limit)]


def _chunks(values: List[str], size: int) -> Iterable[List[str]]:
    for index in range(0, len(values), size):
        yield values[index:index + size]


def _format_refs(section: DeliverableSection) -> str:
    return "；".join(ref.source_name for ref in section.refs)


def _speaker_notes(slide, section: DeliverableSection, points: List[str], refs: str) -> None:
    frame = slide.notes_slide.notes_text_frame
    notes = ["核心观点：" + (section.core_message or section.heading)]
    if points:
        notes.append("讲解要点：" + "；".join(points))
    notes.append("原始正文：" + "\n".join(section.paragraphs + section.bullets))
    if refs:
        notes.append("来源：" + refs)
    frame.text = "\n".join(notes)
