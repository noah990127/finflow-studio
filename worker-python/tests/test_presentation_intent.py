import json
from io import BytesIO

import pytest
from pptx import Presentation

from app.artifacts.quality import evaluate_artifact
from app.deliverables import create_pptx
from app.models import DeliverableRequest
from app.services.presentation import prepare_presentation, evaluate_presentation, _planner_system, _reviewer_system
from app.presentation_templates import EXECUTIVE


def request(layout="list", **kwargs):
    kwargs.setdefault("presentation_brief", {"include_cover": True})
    return DeliverableRequest(title="新员工入职计划", ppt_skill=EXECUTIVE, sections=[{
        "heading": "第一周安排", "core_message": "先熟悉环境，再完成岗位实践",
        "layout": layout, "bullets": ["周一：领取设备与开通账号", "周二：学习岗位流程", "周三至周五：完成练习与反馈"],
    }], **kwargs)


@pytest.mark.parametrize("layout", ["list", "statement", "decision", "timeline", "process", "comparison"])
def test_offline_template_native_and_no_invented_evidence(layout):
    item = request(layout, plan_mode="fixed")
    content = create_pptx(item)
    deck = Presentation(BytesIO(content))
    text = "\n".join(s.text for p in deck.slides for s in p.shapes if s.has_text_frame)
    assert len(deck.slides) == 2
    assert all(b in text for b in item.sections[0].bullets)
    assert "先熟悉环境，再完成岗位实践" in text
    assert not any(word in text for word in ["佐证", "核心判断", "需关注", "SMART TASK", "参考资料"])
    report = evaluate_artifact("pptx", item, content)
    assert report.passed, report.model_dump()
    assert "missing_citations" not in [i.code for i in report.issues]


def test_topic_headings_and_no_action_ending_are_valid():
    item = request()
    item.sections *= 1
    assert evaluate_presentation(item)["issues"] == []
    assert "不强制" in _reviewer_system()
    assert "禁止给所有文稿套" in _planner_system()


@pytest.mark.asyncio
async def test_inferred_brief_is_preserved_and_explicit_density_wins():
    item = request("auto", presentation_brief={"density": "reading"})
    async def complete(system, user):
        assert "presentation_brief" in user
        return json.dumps({"presentation_brief": {"purpose": "培训", "density": "speaking"},
                           "slides": [{"title": "岗位概览", "core_message": "了解岗位分工和协作方式",
                                       "layout": "list", "bullets": ["认识团队协作伙伴"]}]}, ensure_ascii=False)
    result = await prepare_presentation(item, complete)
    assert result.presentation_brief.purpose == "培训"
    assert result.presentation_brief.density == "reading"
    assert result.sections[0].heading == "岗位概览"


def test_research_still_warns_when_no_sources():
    item = request(presentation_brief={"evidence_policy": "research"})
    report = evaluate_artifact("pptx", item, create_pptx(item))
    assert "missing_citations" in [i.code for i in report.issues]


def test_long_content_split_without_dropping_or_duplicate_core():
    item = request(plan_mode="fixed")
    item.sections[0].bullets = [f"事项{i}：保留这条完整的工作安排" for i in range(10)]
    deck = Presentation(BytesIO(create_pptx(item)))
    text = "\n".join(s.text for p in deck.slides for s in p.shapes if s.has_text_frame)
    assert all(b in text for b in item.sections[0].bullets)


def test_chart_remains_native():
    item = request("chart")
    from app.models import DeliverableChart
    item.sections[0].chart = DeliverableChart(categories=["甲", "乙"], series=[{"name": "记录值", "values": [1, 2]}])
    content = create_pptx(item)
    assert any(s.has_chart for p in Presentation(BytesIO(content)).slides for s in p.shapes)
    assert evaluate_artifact("pptx", item, content).passed


def test_explicit_six_page_plan_is_not_forced_into_thirteen_pages():
    item = request("timeline", presentation_brief={"include_cover": False})
    item.sections = [item.sections[0].model_copy(update={
        "heading": f"第{i}周安排", "core_message": f"第{i}周计划内容",
        "bullets": [f"职责{j}：这是需要保留的工作安排" for j in range(5)]
    }, deep=True) for i in range(6)]
    content = create_pptx(item)
    assert len(Presentation(BytesIO(content)).slides) == 6
    assert evaluate_artifact("pptx", item, content).passed
