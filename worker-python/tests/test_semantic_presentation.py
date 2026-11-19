import io
import json

import pytest
from lxml import html
from pptx import Presentation
from pydantic import ValidationError

from app.models import DeliverableRequest, PresentationVisual
from app.semantic_presentation import TEMPLATE_ID, render_html, validate_design, render, prepare
from app.artifacts.quality import evaluate_artifact


def example():
    return DeliverableRequest.model_validate({
        "title": "新员工四周培养计划", "ppt_skill": TEMPLATE_ID, "plan_mode": "fixed",
        "include_citations": False,
        "sections": [
            {"heading": "从入职准备到独立实践", "core_message": "四周模拟计划：逐步增加自主性，尚非完成事实", "layout": "timeline",
             "visual": {"kind": "timeline", "rationale": "阶段有先后关系，按时间排列任务与责任人", "items": [
                 {"label": "第一周 · 准备", "detail": "办理入职、配置账号、认识岗位", "owner": "人事 / IT / 导师"},
                 {"label": "第二周 · 带领", "detail": "在导师带领下完成一次小任务", "owner": "导师与新人"},
                 {"label": "第三周 · 实践", "detail": "独立完成模拟任务，由导师反馈", "owner": "新人与导师"},
                 {"label": "第四周 · 评估", "detail": "共同评估新人适应情况", "owner": "主管与导师"}]}},
            {"heading": "责任分工一览", "core_message": "按角色查看各阶段需要承担的工作", "layout": "matrix",
             "visual": {"kind": "matrix", "rationale": "角色和阶段是交叉维度", "columns": ["第一周", "第二周", "第三周", "第四周"],
                        "rows": [{"label": "人事 / IT", "cells": ["手续与设备账号", "—", "—", "—"]},
                                 {"label": "导师", "cells": ["岗位介绍", "带领任务", "提供反馈", "参与评估"]},
                                 {"label": "新人", "cells": ["熟悉岗位", "参与任务", "独立实践", "接受评估"]},
                                 {"label": "主管", "cells": ["—", "—", "—", "评估适应情况"]}]}},
            {"heading": "执行前还需确认什么", "core_message": "未明确的事项保留为待确认，不补造任务和标准", "layout": "list",
             "visual": {"kind": "list", "rationale": "彼此独立的待确认事项", "items": [
                 {"label": "小任务内容", "detail": "第二周的小任务及第三周模拟任务尚未明确"},
                 {"label": "评估标准", "detail": "第四周采用哪些标准判断适应情况，仍需确认"}]}}
        ]})


def test_semantic_relationships_survive_template_hydration():
    document = html.fromstring(render_html(example()))
    assert len(document.find_class("timeline-step")) == 4
    assert len(document.xpath("//table/tr")) == 5
    assert "导师" in document.xpath("//table/tr[3]/th")[0].text
    assert not document.xpath("//script|//link|//img")
    assert "Market Outlook" not in document.text_content()
    assert "score" not in evaluate_artifact("pptx", example(), b"").model_dump()


def test_bad_dimensions_rejected_not_flattened():
    with pytest.raises(ValidationError):
        PresentationVisual(kind="matrix", rationale="test", columns=["a", "b"], rows=[{"label": "x", "cells": ["a"]}])
    request = example()
    request.sections[0].visual = None
    with pytest.raises(ValueError, match="不会降级"):
        validate_design(request)


def test_untrusted_input_is_text_not_html():
    request = example()
    request.sections[0].visual.items[0].label = '<script>alert(1)</script>'
    document = html.fromstring(render_html(request))
    assert not document.xpath("//script")
    assert '<script>alert(1)</script>' in document.text_content()


@pytest.mark.asyncio
async def test_fixed_structure_does_not_replan():
    async def forbidden(*_):
        raise AssertionError("Must not call model")
    source = example()
    assert await prepare(source, forbidden) == source
    from app.artifacts.planning import prepare_for_format
    planned, _ = await prepare_for_format("pptx", source, forbidden)
    assert planned is source


def test_ratings_are_not_part_of_validation_contract(monkeypatch):
    from app.artifacts.models import ArtifactQualityReport, QualityIssue
    from app.artifacts import quality
    with pytest.raises(ValidationError):
        ArtifactQualityReport(format="pptx", passed=True, score=100)
    def warnings_only(request, content, issues, checks, metrics):
        issues.extend(QualityIssue(code="advisory", severity="warning", message="提示") for _ in range(10))
    monkeypatch.setattr(quality, "_evaluate_pptx", warnings_only)
    report = quality.evaluate_artifact("pptx", example(), b"valid")
    assert report.passed
    assert "score" not in report.model_dump()


@pytest.mark.asyncio
async def test_auto_planner_builds_semantics():
    request = example()
    request.sections[0].visual = None
    async def complete(system, material):
        assert "rationale" in system
        return json.dumps({"sections": [{"heading": s.heading, "coreMessage": s.core_message,
                                         "visual": s.visual.model_dump()} for s in example().sections]})
    planned = await prepare(request, complete)
    assert planned.sections[1].visual.kind == "matrix"


@pytest.mark.integration
def test_real_html_export_is_editable(tmp_path):
    request = example()
    request.sections[0].visual.items[1].owner = "导师"
    content = render(request)
    deck = Presentation(io.BytesIO(content))
    assert len(deck.slides) == 3
    assert deck.core_properties.subject == TEMPLATE_ID
    from pptx.util import Inches
    owner = next(s for s in deck.slides[0].shapes if getattr(s, "text", "") == "导师")
    assert owner.width > Inches(1)
    assert "导师" in "".join(s.text for s in deck.slides[1].shapes if s.has_text_frame)
    assert evaluate_artifact("pptx", example(), content).passed
    (tmp_path / "semantic.pptx").write_bytes(content)
