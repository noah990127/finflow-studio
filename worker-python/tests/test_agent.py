from pathlib import Path
import json

import pytest

from app.agent import ContinuousAgentRequest, OpenTaskRequest, load_skills, run_continuous_agent_stream, run_open_task_stream
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
        "presentation-authoring",
        "document-authoring",
        "pdf-publishing",
        "dashboard-authoring",
        "diagram-authoring",
        "whiteboard-authoring",
    }
    assert all(skill.instructions for skill in skills)
    assert all("Human Confirmation Boundary" in skill.instructions for skill in skills)


def test_continuous_agent_defaults_unspecified_analysis_outputs_to_ppt() -> None:
    instructions = runtime._continuous_instructions(None)

    assert "默认生成 PPTX" in instructions
    assert "明确要求交互报告" in instructions
    assert "已有可读取的 CSV 或数据采集结果" in instructions


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


@pytest.mark.asyncio
async def test_workspace_tool_contract_rejects_missing_unknown_and_invalid_enum_arguments() -> None:
    request = AgentPlanRequest(
        goal="添加分析节点", page="workflow",
        capabilities=[AgentCapability(
            id="workflow.add_node", title="添加节点", description="添加工作流节点", mode="WRITE",
            risk="CREATE_VERSION", arguments=["workflow_id", "node_type", "config"],
            inputSchema={
                "type": "object", "additionalProperties": False,
                "required": ["workflow_id", "node_type", "config"],
                "properties": {
                    "workflow_id": {"type": "string", "minLength": 1},
                    "node_type": {"type": "string", "enum": ["AI_ANALYSIS", "DELIVERABLE"]},
                    "config": {"type": "object"},
                },
            },
        )],
    )
    tool = build_workbench_tools(AgentDependencies(request=request, skills=[]))[0]

    with pytest.raises(Exception, match="workflow_id"):
        await tool.ainvoke({"node_type": "AI_ANALYSIS", "config": {"prompt": "分析"}})
    with pytest.raises(Exception, match="AI_ANALYSIS|DELIVERABLE"):
        await tool.ainvoke({"workflow_id": "w1", "node_type": "UNKNOWN", "config": {}})
    with pytest.raises(Exception, match="extra|Extra"):
        await tool.ainvoke({"workflow_id": "w1", "node_type": "AI_ANALYSIS", "config": {}, "unexpected": True})


@pytest.mark.asyncio
async def test_continuous_agent_runs_parallel_reads_then_a_write(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    responses = iter([
        '{"tool_calls":['
        '{"name":"project_list","arguments":{"query":"资金"}},'
        '{"name":"workflow_open","arguments":{"workflow_id":"w1"}}]}',
        '{"tool_calls":[{"name":"workflow_edit","arguments":{"workflow_id":"w1","patch":{"nodes":[]},"expected_version":1}}]}',
        '{"final":{"summary":"已检查并保存工作流"}}',
    ])
    active_reads = 0
    max_active_reads = 0
    calls: list[str] = []

    async def complete(_system: str, _user: str) -> str:
        return next(responses)

    async def gateway(_request, _call_id, capability, _arguments):
        nonlocal active_reads, max_active_reads
        calls.append(capability.id)
        if capability.mode == "READ":
            active_reads += 1
            max_active_reads = max(max_active_reads, active_reads)
            await __import__("asyncio").sleep(0.02)
            active_reads -= 1
        return {"success": True, "result": f"{capability.id} ok", "output": {}}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="continuous-auto", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s1", goal="检查工作流后保存", page="workflow",
        capabilities=[
            AgentCapability(id="project.list", title="查看项目", description="列出项目", mode="READ", risk="READ_ONLY", arguments=["query"]),
            AgentCapability(id="workflow.open", title="打开工作流", description="读取工作流", mode="READ", risk="READ_ONLY", arguments=["workflow_id"]),
            AgentCapability(id="workflow.edit", title="编辑工作流", description="批量保存", mode="WRITE", risk="CREATE_VERSION",
                            arguments=["workflow_id", "patch", "expected_version"], inputSchema={
                                "type": "object", "additionalProperties": False,
                                "required": ["workflow_id", "patch", "expected_version"],
                                "properties": {"workflow_id": {"type": "string"}, "patch": {"type": "object"},
                                               "expected_version": {"type": "integer"}},
                            }),
        ],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=CodexCliChatModel())]

    assert calls == ["project.list", "workflow.open", "workflow.edit"]
    assert max_active_reads == 2
    assert events[-1]["type"] == "completed"
    assert events[-1]["content"] == "已检查并保存工作流"


@pytest.mark.asyncio
async def test_continuous_agent_stops_without_another_model_turn_after_verified_deliverable(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    model_calls = 0

    async def complete(_system: str, _user: str) -> str:
        nonlocal model_calls
        model_calls += 1
        if model_calls > 1:
            raise AssertionError("verified deliverable must use the deterministic completion path")
        return '{"tool_calls":[{"name":"deliverable_create","arguments":{"project_id":"p1","format":"PDF"}}]}'

    async def gateway(_request, _call_id, _capability, _arguments):
        return {"success": True, "result": "PDF 已生成", "output": {"deliverableId": "d1"},
                "taskComplete": True, "completionSummary": "PDF 已生成并验证"}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="deterministic-finish", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-fast", goal="输出 PDF", page="project-home",
        capabilities=[AgentCapability(id="deliverable.create", title="创建交付件", description="创建 PDF",
                                      mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "format"],
                                      inputSchema={"type": "object", "additionalProperties": False,
                                                   "required": ["project_id", "format"],
                                                   "properties": {"project_id": {"type": "string"},
                                                                  "format": {"type": "string", "enum": ["PDF"]}}})],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=CodexCliChatModel())]

    assert model_calls == 1
    assert events[-1]["type"] == "completed"
    assert events[-1]["content"] == "PDF 已生成并验证"


@pytest.mark.asyncio
async def test_continuous_agent_resumes_same_checkpoint_after_approval(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    responses = iter([
        '{"tool_calls":[{"name":"folder_create","arguments":{"project_id":"p1","name":"资料","group":"KNOWLEDGE"}}]}',
        '{"final":{"summary":"资料文件夹已创建"}}',
    ])
    calls: list[str] = []

    async def complete(_system: str, _user: str) -> str:
        return next(responses)

    async def gateway(_request, _call_id, capability, _arguments):
        calls.append(capability.id)
        return {"success": True, "result": "已创建", "output": {"folderId": "f1"}}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    base = dict(
        run_id="continuous-approval", gateway_url="http://java", gateway_token="secret",
        execution_mode="APPROVAL", session_id="s2", goal="创建资料文件夹", page="project-home",
        capabilities=[AgentCapability(id="folder.create", title="创建文件夹", description="创建文件夹",
                                      mode="WRITE", risk="CREATE_VERSION", arguments=["project_id", "name", "group"])],
    )
    paused = [event async for event in run_continuous_agent_stream(ContinuousAgentRequest(**base), model_override=CodexCliChatModel())]
    resumed = [event async for event in run_continuous_agent_stream(
        ContinuousAgentRequest(**base, resume=True, approval_count=1), model_override=CodexCliChatModel())]

    assert paused[-1]["type"] == "waiting_confirmation"
    assert calls == ["folder.create"]
    assert resumed[-1]["type"] == "completed"


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
        '{"tool_calls":[{"name":"project_rename","arguments":{"project_id":"p1","new_name":"新项目","action_summary":"你希望只修改项目名称，保留原有资料。我会在当前项目上更新名称，不另建项目。"}}]}',
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
    assert result.public_summary == "你希望只修改项目名称，保留原有资料。我会在当前项目上更新名称，不另建项目。"


@pytest.mark.asyncio
async def test_deep_agent_replans_after_a_real_observation(monkeypatch) -> None:
    runtime._ACTIVE_AGENT_THREADS.clear()
    runtime._AGENT_CHECKPOINTER = None
    responses = iter([
        '{"tool_calls":[{"name":"inspect_workspace","arguments":{}}]}',
        '{"tool_calls":[{"name":"folder_create","arguments":{"project_id":"p1","name":"临时目录","group":"knowledge"}}]}',
        '{"tool_calls":[{"name":"folder_rename","arguments":{"project_id":"p1","folder_id":"f1","name":"最终目录","action_summary":"目录已创建，接下来只需修改名称，不必重复创建。"}}]}',
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
    assert second.public_summary == "目录已创建，接下来只需修改名称，不必重复创建。"
    assert first.public_summary == ""
    assert final and final.completed is True and final.steps == []


@pytest.mark.asyncio
async def test_direct_answer_does_not_invent_a_workflow_or_summary_tool(monkeypatch) -> None:
    answer = "当前项目包含主工作流和资料整理。未修改任何内容。"
    public_summary = "你需要的是名称清单，项目目录足够回答，不需要生成报告或运行流程。"
    responses = iter([
        '{"tool_calls":[{"name":"inspect_workspace","arguments":{}}]}',
        json.dumps({"final": {"summary": answer, "intent": "list", "public_summary": public_summary}}, ensure_ascii=False),
    ])

    async def complete(_system: str, _user: str) -> str:
        assert '"name": "assistant_respond"' not in _system
        return next(responses)

    monkeypatch.setattr(runtime.llm, "complete", complete)
    result = await plan_with_agent(AgentPlanRequest(
        session_id="direct-answer-test", goal="列出工作流名称，不要修改", page="project-home",
        resources=[{"id": "w1", "name": "主工作流", "type": "WORKFLOW", "group": "WORKFLOW"},
                   {"id": "w2", "name": "资料整理", "type": "WORKFLOW", "group": "WORKFLOW"}],
        capabilities=[AgentCapability(id="assistant.respond", title="回答", description="旧摘要接口",
                                      mode="READ", risk="READ_ONLY")],
    ), model_override=CodexCliChatModel())
    assert result and result.completed and result.steps == []
    assert result.summary == answer
    assert result.public_summary == public_summary
