from io import BytesIO
from pathlib import Path

import pytest
from PIL import Image
from pptx import Presentation

from app.artifacts import presentation_review as review
from app.artifacts.models import ArtifactQualityReport
from app.artifacts.planning import prepare_for_format
from app.models import DeliverableRequest, DeliverableChart
from app.ppt_skills import render, _shorten_point, reflow_for_readability


def request(**kwargs):
    return DeliverableRequest(title="测试报告", include_citations=False, sections=[{
        "heading": "验证工序保持不变", "core_message": "数据变化不改变执行逻辑",
        "chapter": "执行概览", "layout": "statement", "bullets": ["基于最新数据重新生成"]
    }], **kwargs)


@pytest.mark.asyncio
@pytest.mark.parametrize("format", ["pptx", "docx", "pdf", "html_slides"])
async def test_fixed_render_never_calls_model(format):
    async def forbidden(*args):
        raise AssertionError("fixed rendering must not call the model")
    item = request(plan_mode="fixed")
    prepared, _ = await prepare_for_format(format, item, forbidden)
    assert prepared is item


@pytest.mark.asyncio
async def test_explicit_page_plan_never_replanned():
    async def forbidden(*args):
        raise AssertionError("explicit pages must remain unchanged")
    item = request()
    prepared, _ = await prepare_for_format("pptx", item, forbidden)
    assert prepared is item


def test_no_silent_truncation_of_long_points():
    text = "完整保留输入内容" * 80
    assert "".join(_shorten_point(text)) == text


def test_chapter_does_not_overlap_title_and_core_is_rendered():
    deck = Presentation(BytesIO(render(request())))
    slide = deck.slides[1]
    title = next(s for s in slide.shapes if getattr(s, "text", "") == "验证工序保持不变")
    chapter = next(s for s in slide.shapes if getattr(s, "text", "") == "执行概览")
    assert chapter.top + chapter.height <= title.top
    assert any("数据变化不改变执行逻辑" in getattr(s, "text", "") for s in slide.shapes)


def test_text_coverage_is_order_independent_and_detects_missing_chinese():
    assert review.text_coverage("中文报告 APEC", "APEC 中文 报告") == 1
    assert review.text_coverage("中文报告", "□□□□") == 0


def test_unavailable_renderer_never_claims_visual_pass(monkeypatch):
    def unavailable(*args):
        raise RuntimeError("missing converter")
    monkeypatch.setattr(review, "render_pages", unavailable)
    monkeypatch.setenv("PRESENTATION_VISUAL_REVIEW", "required")
    result = review.review_rendered(b"test", ArtifactQualityReport(format="pptx", passed=True))
    assert not result.passed
    assert not result.checks["rendered_visual_checked"]
    assert result.metrics["visual_review_status"] == "unavailable"


def test_chart_rejects_mismatched_and_nonfinite_values():
    for values in ([1], [1, float("nan")]):
        with pytest.raises(ValueError):
            DeliverableChart(categories=["A", "B"], series=[{"name": "值", "values": values}])


def test_repair_preserves_all_text_and_uses_fixed_rules():
    original = request(plan_mode="fixed")
    original.sections[0].bullets = [f"第{i}个证据完整保留" for i in range(15)]
    repaired = reflow_for_readability(original)
    assert repaired.plan_mode == "fixed"
    body = "".join(s.core_message + "".join(s.bullets) for s in repaired.sections)
    assert all(text in body for text in original.sections[0].bullets)
    assert original.sections[0].core_message in body


def test_mac_font_config_is_local_and_explicit(monkeypatch, tmp_path):
    from app import office_preview
    import xml.etree.ElementTree as ET
    monkeypatch.setattr(office_preview.shutil, "which", lambda _: "/usr/bin/soffice")
    monkeypatch.delenv("FONTCONFIG_FILE", raising=False)
    monkeypatch.setattr(office_preview, "_prepare_source", lambda *args: None)
    captured = {}
    def run(args, **kwargs):
        captured.update(kwargs["env"])
        (tmp_path / "source.pdf").write_bytes(b"%PDF-test")
        from types import SimpleNamespace
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(office_preview.subprocess, "run", run)
    office_preview.render_office_pdf(tmp_path / "input.pptx", "input.pptx", tmp_path)
    if Path("/Library/Fonts").exists():
        config = Path(captured["FONTCONFIG_FILE"])
        assert config.parent == tmp_path
        assert "/Library/Fonts" in [node.text for node in ET.parse(config).findall("dir")]


def test_blank_render_and_missing_pages_are_errors_even_in_auto_mode(monkeypatch, tmp_path):
    from types import SimpleNamespace
    image = tmp_path / "page.png"
    Image.new("RGB", (200, 100), "white").save(image)
    monkeypatch.setenv("PRESENTATION_VISUAL_REVIEW", "auto")
    monkeypatch.setattr(review, "render_pages", lambda *_: (tmp_path / "output.pdf", [image]))
    monkeypatch.setattr(review, "PdfReader", lambda _: SimpleNamespace(pages=[SimpleNamespace(extract_text=lambda: "")]))
    result = review.review_rendered(render(request()), ArtifactQualityReport(format="pptx", passed=True))
    assert not result.passed
    assert {i.code for i in result.issues} >= {"rendered_page_mismatch", "rendered_blank_page", "rendered_text_loss"}
