from pathlib import Path

import pytest

from app.agent import OpenTaskRequest, load_skills, run_open_task_stream
from app.agent import runtime
from app.agent.runtime import AgentCapability, AgentDependencies, AgentPlanRequest, build_workbench_tools, plan_with_agent
from app.integrations.codex_cli_chat_model import CodexCliChatModel


def test_loads_reusable_skills_from_markdown() -> None:
    skills = load_skills(str(Path(__file__).parents[1] / "skills"))

    assert {skill.name for skill in skills} == {
        "financial-analysis",
        "annual-report-analysis",
        "document-data-extraction",
        "excel-analysis",
        "research-and-evidence",
        "deliverable-generation",
        "workflow-authoring",
        "workspace-operations",
    }
    assert all(skill.instructions for skill in skills)
    assert all("Human Confirmation Boundary" in skill.instructions for skill in skills)


@pytest.mark.asyncio
async def test_open_task_has_governed_local_fallback(monkeypatch) -> None:
    monkeypatch.setattr(runtime, "_model", lambda: None)
    monkeypatch.setattr(runtime.settings, "llm_provider", "none")
    request = OpenTaskRequest(task="总结当前资料", project_id="project-1", source_context="收入同比增长 12%。")

    events = [event async for event in run_open_task_stream(request)]

    assert events[0]["type"] == "thinking_summary"
    assert events[1]["type"] == "planning"
    assert events[-1]["type"] == "completed"
    assert events[-1]["mode"] == "local-governed"
    assert "收入同比增长" in events[-1]["content"]


@pytest.mark.asyncio
async def test_open_task_stream_separates_generation_from_final_result(monkeypatch) -> None:
    monkeypatch.setattr(runtime, "_model", lambda: None)
    monkeypatch.setattr(runtime, "llm", type("StubLlm", (), {"configured": False})())
    request = OpenTaskRequest(task="生成摘要", project_id="project-1", source_context="现金流改善。")

    events = [event async for event in run_open_task_stream(request)]
    event_types = [event["type"] for event in events]

    assert "generating" in event_types
    assert event_types[-1] == "completed"


@pytest.mark.asyncio
async def test_every_workspace_capability_becomes_a_named_deep_agent_tool() -> None:
    request = AgentPlanRequest(
        session_id="session-1",
        goal="重命名项目并打开工作流",
        page="project-home",
        capabilities=[
            AgentCapability(id="project.rename", title="重命名项目", description="修改项目名称",
                            mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "new_name"]),
            AgentCapability(id="workflow.open", title="打开工作流", description="打开指定工作流",
                            mode="READ", risk="READ_ONLY", arguments=["workflow_id"]),
        ],
    )
    dependencies = AgentDependencies(request=request, skills=[])
    tools = build_workbench_tools(dependencies)

    assert [item.name for item in tools] == ["project_rename", "workflow_open"]
    await tools[0].ainvoke({"project_id": "p1", "new_name": "新名称"})
    assert dependencies.staged_actions[0].tool == "project.rename"
    assert dependencies.staged_actions[0].arguments["new_name"] == "新名称"


def test_codex_cli_adapter_returns_native_tool_calls() -> None:
    result = CodexCliChatModel()._result(
        '{"tool_calls":[{"name":"resource_read","arguments":{"resource_id":"r1"}}]}'
    )

    call = result.generations[0].message.tool_calls[0]
    assert call["name"] == "resource_read"
    assert call["args"] == {"resource_id": "r1"}


@pytest.mark.asyncio
async def test_deep_agent_selects_workspace_tools_in_a_session(monkeypatch) -> None:
    responses = iter([
        '{"tool_calls":[{"name":"inspect_workspace","arguments":{}}]}',
        '{"tool_calls":[{"name":"project_rename","arguments":{"project_id":"p1","new_name":"新项目"}}]}',
        '{"final":{"summary":"将重命名项目","intent":"rename","selected_skills":["workspace-operations"]}}',
    ])

    async def complete(_system: str, _user: str) -> str:
        return next(responses)

    monkeypatch.setattr(runtime.llm, "complete", complete)
    request = AgentPlanRequest(
        session_id="tool-loop-test",
        goal="把项目改名为新项目",
        page="project-home",
        project_id="p1",
        capabilities=[AgentCapability(id="project.rename", title="重命名项目", description="修改项目名称",
                                      mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "new_name"])],
    )

    result = await plan_with_agent(request, model_override=CodexCliChatModel())

    assert result is not None
    assert result.mode == "deep-agents"
    assert result.steps[0].tool == "project.rename"
    assert result.steps[0].arguments["new_name"] == "新项目"
