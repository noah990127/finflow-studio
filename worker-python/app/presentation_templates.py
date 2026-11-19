"""Versioned offline adapter for licensed Presenton layout data.

This is not the full Presenton engine. Upstream component anchors define the
compositions; native PowerPoint objects and CJK-safe typography are supplied by
the existing renderer. No template example text or fabricated labels are used.
"""
import io
import json
from functools import lru_cache
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches

from .charting import add_native_ppt_chart, normalize_ppt_chart_ids
from .citations import reference_entries
from .ppt_skills import _text, _rect, _content_points, _speaker_notes, _chunks

EXECUTIVE = "presenton-executive-v1"
ROOT = Path(__file__).parent / "templates" / EXECUTIVE
INK = RGBColor.from_string("202126")
ACCENT = RGBColor.from_string("5F4F9B")
MUTED = RGBColor.from_string("666975")
LINE = RGBColor.from_string("DFDFE7")


@lru_cache(maxsize=1)
def layouts():
    return json.loads((ROOT / "layouts.json").read_text())


def component(layout, component_id):
    return next(item for item in layouts()[layout]["components"] if item["id"] == component_id)


def anchor(layout, component_id):
    p = component(layout, component_id)["position"]
    return p["x"] / 96, p["y"] / 96


def text_slot(slide, layout, component_id, element_name, text, *, size=30, height=None):
    c = component(layout, component_id)
    e = next(item for item in c["elements"] if item.get("name") == element_name)
    x = (c["position"]["x"] + e.get("position", {}).get("x", 0)) / 96
    y = (c["position"]["y"] + e.get("position", {}).get("y", 0)) / 96
    align = PP_ALIGN.CENTER if e.get("alignment", {}).get("horizontal") == "center" else PP_ALIGN.LEFT
    _text(slide, text, x, y, e["size"]["width"] / 96, height or e["size"]["height"] / 96,
          size, INK, bold=size >= 28, min_size=18 if size < 28 else 26, align=align)


def render(request):
    deck = Presentation()
    deck.slide_width, deck.slide_height = Inches(1280 / 96), Inches(7.5)
    deck.core_properties.title = request.title
    deck.core_properties.subject = EXECUTIVE
    if request.presentation_brief.include_cover:
        cover = deck.slides.add_slide(deck.slide_layouts[6])
        key = "centered_title_metadata_6418"
        text_slot(cover, key, "center_title_block", "main_headline", request.title, size=44)
        if request.subtitle:
            text_slot(cover, key, "center_title_block", "supporting_tagline", request.subtitle, size=20, height=1.25)
        cover.notes_slide.notes_text_frame.text = request.presentation_brief.model_dump_json()
    for section in request.sections:
        # Capacity is a layout constraint, not a forced three-point narrative.
        points = _content_points(section)
        core = section.core_message
        if core and points and points[0] == core.strip("。；; "):
            points = points[1:]
        groups = list(_chunks(points, 6)) or [[]]
        for index, group in enumerate(groups):
            slide = deck.slides.add_slide(deck.slide_layouts[6])
            title = section.heading if index == 0 else section.heading + "（续）"
            if section.chart is not None and index == 0:
                _chart(slide, title, core, group, section.chart)
            elif section.layout in {"timeline", "process"} and 0 < len(group) <= 4:
                _timeline(slide, title, core, group)
            elif section.layout in {"statement", "decision", "comparison", "matrix", "metric"} and 0 < len(group) <= 4:
                _split(slide, title, core, group)
            else:
                _list(slide, title, core, group)
            _text(slide, str(len(deck.slides)), 12.15, 7.0, .6, .2, 10, MUTED, min_size=10)
            _speaker_notes(slide, section, group, "\n".join(
                ref.source_name + " " + str(ref.location) + "\n" + ref.text for ref in section.refs))
    if request.include_citations:
        for entries in _chunks(reference_entries(request), 6):
            slide = deck.slides.add_slide(deck.slide_layouts[6])
            _text(slide, "参考资料", .56, .55, 12, .65, 32, INK, bold=True)
            for i, entry in enumerate(entries):
                _text(slide, entry, .65, 1.8 + i * .75, 12, .65, 14, MUTED, min_size=12)
    stream = io.BytesIO()
    deck.save(stream)
    return normalize_ppt_chart_ids(stream.getvalue())


def _list(slide, title, core, points):
    key = "large_title_list_footer_2424"
    text_slot(slide, key, "header_title", "section_title", title, size=32, height=.95)
    x, y = anchor(key, "stacked_text_items")
    if core:
        _text(slide, core, x, y, 11.6, .9, 24, ACCENT, bold=True)
    start = y + (.95 if core else 0)
    stride = min(.95, (6.8 - start) / max(1, len(points)))
    for i, point in enumerate(points):
        _text(slide, point, x, start + i * stride, 11.6, stride - .05, 20, INK, min_size=18)


def _split(slide, title, core, points):
    key = "title_with_stacked_cards_2967"
    text_slot(slide, key, "left_intro_text_part_1", "intro_headline", title, size=34, height=1.35)
    x, y = anchor(key, "left_intro_text_part_2")
    if core:
        _text(slide, core, x, y + .3, 5.4, 2.8, 26, ACCENT, min_size=22)
    x, y = anchor(key, "right_information_cards")
    stride = min(1.75, 5.8 / max(1, len(points)))
    for i, point in enumerate(points):
        _text(slide, f"{i + 1:02d}", x, y + i * stride + .16, .5, .35, 14, ACCENT, bold=True)
        _text(slide, point, x + .65, y + i * stride + .12, 5.15, stride - .35, 22, INK, min_size=18)
        _rect(slide, x, y + (i + 1) * stride - .1, 5.9, .012, LINE)


def _timeline(slide, title, core, points):
    key = "title_timeline_sections_footer_6488"
    text_slot(slide, key, "header_area", "main_headline", title, size=32, height=.85)
    text_slot(slide, key, "header_area", "supporting_subtitle", core, size=20, height=.9)
    x, y = anchor(key, "timeline_sections")
    width = 12.1 / len(points)
    for i, point in enumerate(points):
        left = x + i * width
        _text(slide, f"{i + 1:02d}", left, y + .35, width - .3, .6, 28, ACCENT, bold=True)
        _rect(slide, left, y + 1.1, width - .25, .025, LINE)
        _text(slide, point, left, y + 1.45, width - .35, 2.1, 21, INK, min_size=18)


def _chart(slide, title, core, points, chart):
    key = "title_cards_chart_footer_2861"
    text_slot(slide, key, "title_and_summary", "main_heading", title, size=32, height=.9)
    text_slot(slide, key, "title_and_summary", "summary_text", core, size=20, height=.9)
    x, y = anchor(key, "right_chart_panel")
    add_native_ppt_chart(slide, chart, x, y, 5.15, 3.65, accent="5F4F9B")
    x, y = anchor(key, "stacked_info_cards")
    for i, point in enumerate(points[:3]):
        _text(slide, point, x, y + i * 1.1, 6.4, .95, 20, INK, min_size=18)
