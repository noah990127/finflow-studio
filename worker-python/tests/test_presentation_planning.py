import json
from io import BytesIO

import pytest
from pptx import Presentation

from app.deliverables import create_pptx
from app.models import DeliverableRequest
from app.ppt_skills import HUAWEI_STYLE_C
from app.services.presentation import evaluate_presentation, prepare_presentation


def source_request() -> DeliverableRequest:
    return DeliverableRequest.model_validate({
        "title": "经营质量分析",
        "sections": [{
            "heading": "原始分析",
            "paragraphs": ["收入同比增长12%，但现金转化率下降。管理层应优先改善回款。"],
            "bullets": ["数据中心业务贡献主要增量", "应收账款周转天数增加", "下一季度建立回款专项"],
            "refs": [{"ref_id": "r1", "source_name": "年度报告", "text": "收入同比增长12%"}],
        }],
    })


@pytest.mark.asyncio
async def test_model_plans_and_reviews_a_page_level_narrative() -> None:
    responses = [
        {"slides": [{
            "chapter": "结论摘要", "title": "增长延续，回款质量承压",
            "core_message": "收入增长没有同步转化为同等质量的现金回报 [Ref 1]",
            "layout": "metric", "bullets": ["收入同比增长12% [Ref 1]", "现金转化率下降", "下一季度建立回款专项"],
            "ref_indexes": [1], "chart": None,
        }]},
    ]

    async def complete(_system: str, _user: str) -> str:
        return json.dumps(responses.pop(0), ensure_ascii=False)

    planned = await prepare_presentation(source_request(), complete)

    assert not responses
    assert len(planned.sections) == 1
    assert planned.sections[0].heading == "增长延续，回款质量承压"
    assert planned.sections[0].layout == "statement"
    assert len(planned.sections[0].bullets) == 3
    assert planned.sections[0].refs[0].ref_id == "r1"
    assert evaluate_presentation(planned)["passed"] is True


@pytest.mark.asyncio
async def test_rule_fallback_splits_dense_material_without_failing_generation() -> None:
    async def unavailable(_system: str, _user: str) -> str:
        raise RuntimeError("model unavailable")

    item = source_request()
    item.sections[0].paragraphs = ["。".join(f"第{index}项经营判断需要解释" for index in range(10))]

    planned = await prepare_presentation(item, unavailable)

    assert len(planned.sections) >= 3
    assert all(section.core_message for section in planned.sections)
    assert all(len(section.bullets) <= 3 for section in planned.sections)
    assert evaluate_presentation(planned)["passed"] is True


@pytest.mark.asyncio
async def test_planner_caps_repetition_and_removes_citations_without_sources() -> None:
    slides = [{
        "chapter": "分析", "title": f"第{index}项判断",
        "core_message": "收入增长18%但现金转化承压 [Ref 1]" if index < 4 else f"行动建议{index}",
        "layout": "metric", "bullets": ["经营现金流仅增长4% [Ref 1]"],
        "ref_indexes": [], "chart": None,
    } for index in range(9)]

    async def complete(_system: str, _user: str) -> str:
        return json.dumps({"slides": slides}, ensure_ascii=False)

    item = source_request().model_copy(update={"sections": [
        source_request().sections[0].model_copy(update={"refs": []}, deep=True)
    ]}, deep=True)
    planned = await prepare_presentation(item, complete)

    assert len(planned.sections) <= 4
    assert "[Ref" not in " ".join(
        section.core_message + " " + " ".join(section.bullets) for section in planned.sections
    )


@pytest.mark.asyncio
async def test_citations_are_renumbered_in_first_appearance_order() -> None:
    item = source_request()
    item.sections[0].refs.append(item.sections[0].refs[0].model_copy(
        update={"ref_id": "r2", "source_name": "现金流报告", "text": "现金流增长4%"}
    ))
    first = {"slides": [
        {"chapter": "经营", "title": "现金流承压", "core_message": "现金流增长4% [Ref 2]",
         "layout": "metric", "bullets": [], "ref_indexes": [2], "chart": None},
        {"chapter": "经营", "title": "收入保持增长", "core_message": "收入增长12% [Ref 1]",
         "layout": "metric", "bullets": [], "ref_indexes": [1], "chart": None},
    ]}
    reviewed = {"slides": [
        {"chapter": "经营", "title": "现金流承压", "core_message": "现金流增长4% [Ref 1]",
         "layout": "metric", "bullets": [], "ref_indexes": [1], "chart": None},
        {"chapter": "经营", "title": "收入保持增长", "core_message": "收入增长12% [Ref 2]",
         "layout": "metric", "bullets": [], "ref_indexes": [2], "chart": None},
    ]}
    responses = [first, reviewed]

    async def complete(_system: str, _user: str) -> str:
        return json.dumps(responses.pop(0), ensure_ascii=False)

    planned = await prepare_presentation(item, complete)

    assert planned.sections[0].refs[0].ref_id == "r2"
    assert planned.sections[0].core_message.endswith("[Ref 1]")
    assert planned.sections[1].refs[0].ref_id == "r1"
    assert planned.sections[1].core_message.endswith("[Ref 2]")


@pytest.mark.asyncio
async def test_reuses_structured_workflow_output_without_another_model_round() -> None:
    item = source_request()
    item.sections[0].paragraphs = [json.dumps({"slides": [{
        "title": "福田夜间活动选择丰富",
        "summary": "今晚可按演出、展览与休闲三类安排 [Ref 1]",
        "bullets": ["先核对官方开放时间 [Ref 1]", "优先选择地铁可达地点", "出发前确认预约要求"],
        "chart": None,
    }]}, ensure_ascii=False)]

    async def must_not_run(_system: str, _user: str) -> str:
        raise AssertionError("structured workflow output must not be planned by the model again")

    planned = await prepare_presentation(item, must_not_run)

    assert len(planned.sections) == 1
    assert planned.sections[0].heading == "福田夜间活动选择丰富"
    assert planned.sections[0].refs[0].ref_id == "r1"


@pytest.mark.asyncio
async def test_structured_slides_keep_page_boundaries_and_numeric_citations() -> None:
    item = source_request().model_copy(update={"ppt_skill": HUAWEI_STYLE_C}, deep=True)
    slides = [{
        "chapter": "晚间决策",
        "title": f"第{index}个核心观点",
        "summary": f"第{index}页只表达一个结论 [1]",
        "bullets": [
            f"证据{index}需要核对开放时间；出发前以官方页面为准 [1]",
            f"路线{index}优先选择地铁可达地点",
            f"备选{index}用于临时闭馆或天气变化",
        ],
        "chart": None,
    } for index in range(1, 12)]
    item.sections[0].paragraphs = [json.dumps({"slides": slides}, ensure_ascii=False)]

    async def must_not_run(_system: str, _user: str) -> str:
        raise AssertionError("structured workflow output must not call the model again")

    planned = await prepare_presentation(item, must_not_run)
    deck = Presentation(BytesIO(create_pptx(planned)))
    texts = [shape.text for slide in deck.slides for shape in slide.shapes if hasattr(shape, "text_frame")]

    assert len(planned.sections) == 11
    assert all(len(section.bullets) == 3 for section in planned.sections)
    assert all(section.refs and section.refs[0].ref_id == "r1" for section in planned.sections)
    assert len(deck.slides) == 13
    assert not any("（续）" in value for value in texts)
    assert all(any(f"第{index}个核心观点" in value for value in texts) for index in range(1, 12))
