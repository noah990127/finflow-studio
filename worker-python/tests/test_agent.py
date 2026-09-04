from pathlib import Path

import pytest

from app.agent import OpenTaskRequest, load_skills, run_open_task_stream
from app.agent import runtime
from app.agent.runtime import AgentCapability, AgentDependencies, AgentPlanRequest, build_workbench_tools, plan_with_agent
from app.integrations.codex_cli_chat_model import CodexCliChatModel
from app.llm import LlmGateway


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


def test_research_cooldown_does_not_disable_agent_planning(monkeypatch) -> None:
    gateway = LlmGateway()
    monkeypatch.setattr(runtime.settings, "llm_provider", "codex-cli")
    monkeypatch.setattr(gateway, "_codex_cli_path", lambda: "/tmp/codex")

    gateway._mark_codex_cli_research_unavailable()

    assert gateway.configured is True
    assert gateway._codex_cli_research_available() is False


def test_codex_cli_gateway_allows_virtual_agent_tool_selection() -> None:
    prompt = LlmGateway()._codex_cli_prompt(
        "Return a FinFlow tool call as JSON.",
        "Open the selected workspace item.",
    )

    assert "virtual FinFlow or DeepAgents tool-call JSON" in prompt
    assert "emitting a tool-call object is allowed" in prompt
    assert "Do not use tools" not in prompt


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


@pytest.mark.asyncio
async def test_deep_agent_replans_after_a_real_observation(monkeypatch) -> None:
    runtime._ACTIVE_AGENT_THREADS.clear()
    runtime._AGENT_CHECKPOINTER = None
    responses = iter([
        '{"tool_calls":[{"name":"inspect_workspace","arguments":{}}]}',
        '{"tool_calls":[{"name":"folder_create","arguments":{"project_id":"p1","name":"临时目录","group":"knowledge"}}]}',
        '{"final":{"summary":"先创建目录","intent":"organize","completed":false}}',
        '{"tool_calls":[{"name":"folder_rename","arguments":{"project_id":"p1","folder_id":"f1","name":"最终目录"}}]}',
        '{"final":{"summary":"继续重命名目录","intent":"organize","completed":false}}',
        '{"final":{"summary":"目录已经创建并重命名","intent":"organize","completed":true}}',
    ])

    async def complete(_system: str, _user: str) -> str:
        return next(responses)

    monkeypatch.setattr(runtime.llm, "complete", complete)
    capabilities = [
        AgentCapability(id="folder.create", title="创建目录", description="创建工作区目录",
                        mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "name", "group"]),
        AgentCapability(id="folder.rename", title="重命名目录", description="修改目录名称",
                        mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "folder_id", "name"]),
    ]
    first = await plan_with_agent(AgentPlanRequest(
        session_id="dynamic-observation-test", goal="创建目录后改名", page="project-home",
        project_id="p1", capabilities=capabilities,
    ), model_override=CodexCliChatModel())
    second = await plan_with_agent(AgentPlanRequest(
        session_id="dynamic-observation-test", goal="创建目录后改名", page="project-home",
        project_id="p1", continuation=True, completed_actions=1,
        observation={"tool": "folder.create", "success": True, "result": "已创建", "folder_id": "f1"},
        resources=[{"id": "f1", "name": "临时目录", "type": "FOLDER", "group": "KNOWLEDGE"}],
        capabilities=capabilities,
    ), model_override=CodexCliChatModel())
    final = await plan_with_agent(AgentPlanRequest(
        session_id="dynamic-observation-test", goal="创建目录后改名", page="project-home",
        project_id="p1", continuation=True, completed_actions=2,
        observation={"tool": "folder.rename", "success": True, "result": "已重命名"},
        resources=[{"id": "f1", "name": "最终目录", "type": "FOLDER", "group": "KNOWLEDGE"}],
        capabilities=capabilities,
    ), model_override=CodexCliChatModel())

    assert first and [step.tool for step in first.steps] == ["folder.create"]
    assert second and [step.tool for step in second.steps] == ["folder.rename"]
    assert second.steps[0].arguments["folder_id"] == "f1"
    assert final and final.completed is True and final.steps == []
