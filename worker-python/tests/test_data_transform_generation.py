import json

import pytest

from app import data_transform
from app.models import TransformGenerateRequest


def request() -> TransformGenerateRequest:
    return TransformGenerateRequest(
        requirements="排除取消记录，按区域汇总金额。",
        inputs=[{
            "alias": "orders",
            "name": "模拟订单",
            "columns": [
                {"name": "region", "data_type": "VARCHAR", "nullable": False},
                {"name": "status", "data_type": "VARCHAR", "nullable": True},
                {"name": "amount", "data_type": "DOUBLE", "nullable": True},
            ],
        }],
    )


def valid_response() -> str:
    return json.dumps({
        "plan": {
            "version": "1.0",
            "goal": "按区域汇总有效金额",
            "steps": [
                {"id": "active", "type": "filter", "title": "排除取消记录", "input": "orders",
                 "condition": {"column": "status", "operator": "not_equal", "value": "取消"}},
                {"id": "result", "type": "aggregate", "title": "按区域汇总", "input": "active",
                 "group_by": ["region"], "metrics": [{"name": "total_amount", "function": "sum", "column": "amount"}]},
            ],
            "validations": [{"type": "row_count", "input": "result", "minimum": 0}],
            "output": {"format": "parquet", "partition_by": []},
        },
        "summary": "已生成两步加工方案",
        "assumptions": [],
        "quality_rules": ["检查输出行数"],
        "warnings": [],
    }, ensure_ascii=False)


@pytest.mark.asyncio
async def test_generation_prompt_contains_the_strict_plan_contract(monkeypatch):
    prompts: list[str] = []

    async def complete(system: str, user: str) -> str:
        prompts.append(system)
        return valid_response()

    monkeypatch.setattr(type(data_transform.llm), "configured", property(lambda self: True))
    monkeypatch.setattr(data_transform.llm, "complete", complete)

    result = await data_transform.generate_transform(request())

    assert result.plan.goal == "按区域汇总有效金额"
    assert '"goal"' in prompts[0]
    assert "condition 使用 column" in prompts[0]
    assert "metrics 每项使用 name/function/column" in prompts[0]


@pytest.mark.asyncio
async def test_invalid_model_plan_is_repaired_once(monkeypatch):
    responses = iter([
        json.dumps({
            "plan": {"version": "1.0", "steps": [{
                "id": "active", "type": "filter", "input": "orders",
                "condition": {"field": "status", "operator": "not_equal", "value": "取消"},
            }], "output": {"format": "parquet"}},
            "summary": "错误字段名",
        }, ensure_ascii=False),
        valid_response(),
    ])
    prompts: list[tuple[str, str]] = []

    async def complete(system: str, user: str) -> str:
        prompts.append((system, user))
        return next(responses)

    monkeypatch.setattr(type(data_transform.llm), "configured", property(lambda self: True))
    monkeypatch.setattr(data_transform.llm, "complete", complete)

    result = await data_transform.generate_transform(request())

    assert len(prompts) == 2
    assert "validation_error" in prompts[1][1]
    assert result.plan.steps[-1].id == "result"
    assert result.script.startswith("WITH")
