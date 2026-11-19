import asyncio
from pathlib import Path
import json

import pytest

from app.agent import ContinuousAgentRequest, OpenTaskRequest, load_skills, run_continuous_agent_stream, run_open_task_stream
from app.agent import runtime
from app.agent.runtime import (
    AgentCapability, AgentDependencies, AgentPlanRequest, build_continuous_workbench_tools,
    build_workbench_tools, plan_with_agent,
)
from app.agent.runtime import _needs_action_retry
from app.integrations.codex_cli_chat_model import CodexCliChatModel
from app.integrations.search_provider import SearchProviderUnavailable
from app.llm import LlmGateway


def test_fallback_planner_retries_false_write_completion_claims() -> None:
    assert _needs_action_retry("删除阶段成果", "已提交删除操作，等待审批", []) is True
    assert _needs_action_retry("删除阶段成果", "还缺少要删除的对象名称", []) is False
    assert _needs_action_retry("列出阶段成果", "已经整理好结果", []) is False


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

    assert "必须通过可保存的工作流完成" in instructions
    assert "workflow.prepare" in instructions
    assert "workflow.run" in instructions
    assert "不得跳过工作流直接调用 deliverable.create" in instructions
    assert "默认包含 PPTX" in instructions
    assert "明确要求交互报告" in instructions
    assert "已有可读取的 CSV 或数据采集结果" in instructions


def test_continuous_task_contract_honors_explicit_negative_constraints() -> None:
    source_only = runtime._continuous_task_contract("先只找资料，不要做 PPT")
    assert source_only == {
        "source_only": True,
        "no_delivery": True,
        "no_run": False,
        "read_only": False,
        "run_existing": False,
        "requires_run": False,
    }
    workflow_only = runtime._continuous_task_contract("只创建工作流，先不要运行")
    assert workflow_only["no_run"] is True
    assert workflow_only["no_delivery"] is False
    assert runtime._continuous_task_contract("现在运行刚才那条流程")["run_existing"] is True
    assert runtime._continuous_task_contract("分析这些资料并生成PPT")["requires_run"] is True
    assert runtime._continuous_task_contract("分析一下近期销量下滑的原因")["requires_run"] is True
    assert runtime._continuous_task_contract("加一个HTML输出，这次先别运行")["requires_run"] is False
    assert runtime._continuous_task_contract("不要再跑一遍，根据已经算出的结果告诉我")["read_only"] is True
    assert runtime._continuous_task_contract("按来源解释，不要又生成一份PPT")["read_only"] is True
    assert runtime._continuous_task_contract("说明资料变化，不要再运行")["no_run"] is True
    assert runtime._continuous_task_contract("说明资料变化，不要再运行")["run_existing"] is False
    create_then_run = runtime._continuous_task_contract("先建可复用流程再运行，生成管理层PPT")
    assert create_then_run["run_existing"] is False
    assert create_then_run["requires_run"] is True
    assert runtime._continuous_task_contract("复制一份叫‘月度复盘’，不要立即运行")["no_run"] is True


def test_unicode_safe_replaces_broken_web_surrogates_before_streaming() -> None:
    value = runtime._unicode_safe({"text": "可读正文\ud83d", "items": ["正常", "\udc00"]})

    assert value == {"text": "可读正文?", "items": ["正常", "?"]}
    assert runtime.json.dumps(value, ensure_ascii=False).encode("utf-8")


def test_extracts_business_name_for_workflow_copy() -> None:
    assert runtime._requested_workflow_copy_name("复制一份叫‘月度产品经营复盘’，不要立即运行") == "月度产品经营复盘"
    assert runtime._requested_workflow_copy_name("运行当前流程") is None


def test_extracts_new_project_business_task_without_rewriting_the_subject() -> None:
    goal = "新建一个项目 分析一下最近的尊界制动踏板断裂事件的时间线 当前舆论形势"

    assert runtime._requests_project_creation(goal) is True
    assert runtime._is_workflow_business_task(goal) is True
    assert runtime._project_task_goal(goal) == "分析一下最近的尊界制动踏板断裂事件的时间线 当前舆论形势"
    assert runtime._project_name_from_goal(goal) == "尊界制动踏板断裂事件分析"


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
async def test_continuous_workflow_tool_waits_for_async_run_and_emits_public_decision(monkeypatch) -> None:
    queue: asyncio.Queue[dict[str, object]] = asyncio.Queue()
    request = ContinuousAgentRequest(
        run_id="async-workflow", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-async", goal="运行月度分析工作流", page="workflow",
        capabilities=[AgentCapability(
            id="workflow.run", title="运行工作流", description="运行已保存工作流",
            mode="WRITE", risk="CREATE_VERSION", arguments=["workflow_id"],
            inputSchema={"type": "object", "additionalProperties": False,
                         "required": ["workflow_id"],
                         "properties": {"workflow_id": {"type": "string"}}},
        )],
    )

    async def gateway(_request, _call_id, _capability, _arguments):
        return {"success": True, "result": "工作流已启动",
                "output": {"workflowRunId": "wr-1", "workflowRunStatus": "QUEUED"}}

    async def wait_for_run(_request, _run_id, call_id, target_queue, _client):
        await target_queue.put({"type": "executing", "activity_id": f"{call_id}:progress",
                                "status": "running", "message": "正在分析收入偏差", "progress": 62})
        return {"success": True, "terminal": True, "taskComplete": True,
                "completionSummary": "工作流已完成并生成报告", "result": "工作流已完成"}

    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    monkeypatch.setattr(runtime, "_wait_for_workflow_run", wait_for_run)
    tool = build_continuous_workbench_tools(request, queue)[0]
    result = await tool.ainvoke({"workflow_id": "w1", "public_summary": "资料已经就绪，现在运行工作流生成报告。"})
    events = []
    while not queue.empty():
        events.append(queue.get_nowait())

    assert result["taskComplete"] is True
    assert any(event.get("type") == "thinking_summary" for event in events)
    assert any(event.get("type") == "executing" and event.get("progress") == 62 for event in events)


@pytest.mark.asyncio
async def test_run_existing_uses_single_ready_workflow_without_model_round_trips(monkeypatch) -> None:
    calls: list[tuple[str, dict[str, object]]] = []

    async def gateway(_request, _call_id, capability, arguments):
        calls.append((capability.id, arguments))
        return {"success": True, "result": "工作流已启动",
                "output": {"workflowRunId": "wr-fast", "workflowRunStatus": "QUEUED"}}

    async def wait_for_run(_request, _run_id, call_id, target_queue, _client):
        await target_queue.put({"type": "executing", "activity_id": f"{call_id}:progress",
                                "status": "running", "message": "正在生成 PPT", "progress": 70})
        return {"success": True, "terminal": True, "taskComplete": True,
                "completionSummary": "已运行工作流并生成 PPT", "result": "工作流已完成"}

    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    monkeypatch.setattr(runtime, "_wait_for_workflow_run", wait_for_run)
    request = ContinuousAgentRequest(
        run_id="fast-existing-run", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-fast-existing", goal="运行刚才的工作流，给我一份PPT",
        page="workflow", project_id="p1",
        resources=[{"id": "w1", "name": "调研工作流", "type": "WORKFLOW", "group": "WORKFLOW", "status": "READY"}],
        capabilities=[AgentCapability(
            id="workflow.run", title="运行工作流", description="运行已保存工作流",
            mode="WRITE", risk="CREATE_VERSION", arguments=["workflow_id"],
        )],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=object())]

    assert calls == [("workflow.run", {
        "workflow_id": "w1", "goal": "运行刚才的工作流，给我一份PPT",
        "project_id": "p1", "output_formats": ["PPTX"],
    })]
    assert [event["type"] for event in events] == [
        "thinking_summary", "planning", "tool_call", "executing", "observation", "completed",
    ]
    assert events[-1]["content"] == "已运行工作流并生成 PPT"


@pytest.mark.asyncio
async def test_auto_mode_can_discover_tools_without_loading_every_contract() -> None:
    request = ContinuousAgentRequest(
        run_id="tool-discovery", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-discovery", goal="整理当前工作", page="project-home",
        capabilities=[
            AgentCapability(id="workspace.inspect", title="查看当前工作", description="读取工作区",
                            mode="READ", risk="READ_ONLY", arguments=[]),
            AgentCapability(id="folder.delete", title="删除文件夹", description="删除指定文件夹",
                            mode="WRITE", risk="DESTRUCTIVE_OR_EXTERNAL", arguments=["project_id", "folder_id"],
                            inputSchema={"type": "object", "additionalProperties": False,
                                         "required": ["project_id", "folder_id"],
                                         "properties": {"project_id": {"type": "string"},
                                                        "folder_id": {"type": "string"}}}),
        ],
    )
    tools = build_continuous_workbench_tools(
        request, asyncio.Queue(), direct_tool_ids={"workspace.inspect"}, include_discovery=True)
    by_name = {tool.name: tool for tool in tools}

    assert set(by_name) == {"workspace_inspect", "search_tools", "describe_tool", "execute_discovered_tool"}
    matches = await by_name["search_tools"].ainvoke({"query": "删除文件夹"})
    assert matches[0]["id"] == "folder.delete"
    contract = await by_name["describe_tool"].ainvoke({"tool_id": "folder.delete"})
    assert contract["inputSchema"]["required"] == ["project_id", "folder_id"]
    invalid = await by_name["execute_discovered_tool"].ainvoke({
        "tool_id": "folder.delete", "arguments": {"project_id": "p1"},
    })
    assert invalid["failureType"] == "PARAMETER"


@pytest.mark.asyncio
async def test_continuous_agent_plans_spec_then_prepares_and_runs_clear_output_task(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    model_calls = 0

    async def complete(_system: str, _user: str) -> str:
        nonlocal model_calls
        model_calls += 1
        if model_calls == 1:
            return json.dumps({"tool_calls": [{"name": "workflow_prepare", "arguments": {
                "project_id": "p1", "goal": "总结项目资料并输出 PDF", "output_formats": ["PDF"],
                "workflow_spec": {
                    "version": "1.0", "name": "项目资料总结", "description": "提炼资料并生成可追溯 PDF",
                    "nodes": [
                        {"key": "analysis", "type": "AI_ANALYSIS", "name": "提炼关键结论",
                         "purpose": "归纳证据并输出结构化结论", "config": {"prompt": "基于项目资料总结关键结论"},
                         "depends_on": ["$inputs"]},
                        {"key": "pdf", "type": "DELIVERABLE", "name": "生成PDF",
                         "purpose": "把结论整理为可阅读报告", "config": {"generationPrompt": "生成专业PDF报告",
                         "format": "PDF", "includeCitations": True, "citationStyle": "IEEE"},
                         "depends_on": ["analysis"]},
                    ],
                },
            }}]}, ensure_ascii=False)
        if model_calls == 2:
            return '{"tool_calls":[{"name":"workflow_run","arguments":{"project_id":"p1","workflow_id":"w1","goal":"总结项目资料并输出 PDF","output_formats":["PDF"]}}]}'
        raise AssertionError("verified workflow run must use the deterministic completion path")

    calls: list[str] = []

    async def gateway(_request, _call_id, capability, _arguments):
        calls.append(capability.id)
        if capability.id == "workflow.prepare":
            return {"success": True, "result": "已创建工作流", "output": {"workflowId": "w1"}}
        return {"success": True, "result": "工作流已执行", "output": {"deliverableId": "d1"},
                "taskComplete": True, "completionSummary": "已运行工作流并生成 PDF"}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="deterministic-finish", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-fast", project_id="p1",
        goal="总结项目资料并输出 PDF", page="project-home",
        capabilities=[
            AgentCapability(id="workflow.prepare", title="创建工作流", description="创建工作流",
                            mode="WRITE", risk="CREATE_VERSION",
                            arguments=["project_id", "goal", "output_formats", "workflow_spec"],
                            inputSchema={"type": "object", "additionalProperties": False,
                                         "required": ["project_id", "goal", "output_formats", "workflow_spec"],
                                         "properties": {"project_id": {"type": "string"},
                                                        "goal": {"type": "string"},
                                                        "output_formats": {"type": "array", "items": {"type": "string"}},
                                                        "workflow_spec": {"type": "object"}}}),
            AgentCapability(id="workflow.run", title="运行工作流", description="运行工作流",
                            mode="WRITE", risk="CREATE_VERSION",
                            arguments=["project_id", "workflow_id", "goal", "output_formats"],
                            inputSchema={"type": "object", "additionalProperties": False,
                                         "required": ["project_id", "workflow_id", "goal", "output_formats"],
                                         "properties": {"project_id": {"type": "string"},
                                                        "workflow_id": {"type": "string"},
                                                        "goal": {"type": "string"},
                                                        "output_formats": {"type": "array", "items": {"type": "string"}}}}),
        ],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=CodexCliChatModel())]

    assert calls == ["workflow.prepare", "workflow.run"]
    assert model_calls == 2
    assert events[-1]["type"] == "completed"
    assert events[-1]["content"] == "已运行工作流并生成 PDF"


@pytest.mark.asyncio
async def test_continuous_agent_creates_project_then_runs_original_business_goal(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    calls: list[tuple[str, dict]] = []
    model_calls = 0

    async def complete(_system: str, _user: str) -> str:
        nonlocal model_calls
        model_calls += 1
        if model_calls == 1:
            return json.dumps({"tool_calls": [{"name": "workflow_prepare", "arguments": {
                "project_id": "new-project", "name": "尊界制动踏板事件分析",
                "goal": "分析一下最近的尊界制动踏板断裂事件的时间线 当前舆论形势",
                "output_formats": ["PPTX"], "workflow_spec": {
                    "version": "1.0", "name": "尊界制动踏板事件分析", "description": "检索、交叉核验并分析舆情",
                    "nodes": [
                        {"key": "official", "type": "REF_SEARCH", "name": "查找权威信息",
                         "purpose": "获取官方与主流媒体时间线", "config": {"query": "尊界 制动踏板 断裂 官方回应"},
                         "depends_on": []},
                        {"key": "analysis", "type": "AI_ANALYSIS", "name": "分析时间线与舆情",
                         "purpose": "区分事实、争议和舆论变化", "config": {"prompt": "形成时间线并分析当前舆论"},
                         "depends_on": ["official"]},
                        {"key": "deck", "type": "DELIVERABLE", "name": "生成分析简报",
                         "purpose": "输出管理层可读的结论", "config": {"generationPrompt": "生成带来源的专业PPT",
                         "format": "PPTX", "includeCitations": True, "citationStyle": "IEEE",
                         "pptSkill": "studio-professional-native"}, "depends_on": ["analysis"]},
                    ],
                },
            }}]}, ensure_ascii=False)
        if model_calls == 2:
            return json.dumps({"tool_calls": [{"name": "workflow_run", "arguments": {
                "project_id": "new-project", "workflow_id": "new-workflow", "goal": "分析事件时间线和舆论形势",
                "output_formats": ["PPTX"],
            }}]}, ensure_ascii=False)
        raise AssertionError("工作流完成后不应继续调用模型")

    async def gateway(_request, _call_id, capability, arguments):
        calls.append((capability.id, arguments))
        if capability.id == "project.create_workspace":
            return {"success": True, "result": "已创建项目", "output": {"createdProjectId": "new-project"}}
        if capability.id == "workflow.prepare":
            return {"success": True, "result": "已创建工作流", "output": {"workflowId": "new-workflow"}}
        return {"success": True, "result": "工作流已执行", "output": {"deliverableId": "d1"},
                "taskComplete": True, "completionSummary": "已完成事件分析"}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="new-project-business-task", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-new-project", project_id="old-project",
        goal="新建一个项目 分析一下最近的尊界制动踏板断裂事件的时间线 当前舆论形势",
        page="project-home",
        capabilities=[
            AgentCapability(id="project.create_workspace", title="创建项目", description="创建项目",
                            mode="WRITE", risk="CREATE_VERSION",
                            arguments=["project_name", "topic", "description"]),
            AgentCapability(id="workflow.prepare", title="创建工作流", description="创建工作流",
                            mode="WRITE", risk="CREATE_VERSION",
                            arguments=["project_id", "name", "goal", "output_formats", "workflow_spec"],
                            inputSchema={"type": "object", "additionalProperties": False,
                                         "required": ["project_id", "goal", "workflow_spec"],
                                         "properties": {"project_id": {"type": "string"},
                                                        "name": {"type": "string"},
                                                        "goal": {"type": "string"},
                                                        "output_formats": {"type": "array", "items": {"type": "string"}},
                                                        "workflow_spec": {"type": "object"}}}),
            AgentCapability(id="workflow.run", title="运行工作流", description="运行工作流",
                            mode="WRITE", risk="CREATE_VERSION",
                            arguments=["project_id", "workflow_id", "goal", "output_formats"],
                            inputSchema={"type": "object", "additionalProperties": False,
                                         "required": ["workflow_id"],
                                         "properties": {"project_id": {"type": "string"},
                                                        "workflow_id": {"type": "string"},
                                                        "goal": {"type": "string"},
                                                        "output_formats": {"type": "array", "items": {"type": "string"}}}}),
        ],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=CodexCliChatModel())]

    assert [item[0] for item in calls] == [
        "project.create_workspace", "workflow.prepare", "workflow.run",
    ]
    assert calls[0][1]["project_name"] == "尊界制动踏板断裂事件分析"
    assert calls[1][1]["project_id"] == "new-project"
    assert calls[1][1]["name"] == "尊界制动踏板事件分析"
    assert calls[1][1]["goal"] == "分析一下最近的尊界制动踏板断裂事件的时间线 当前舆论形势"
    assert calls[1][1]["workflow_spec"]["nodes"][0]["type"] == "REF_SEARCH"
    assert model_calls == 2
    assert "s800" not in calls[1][1]["goal"].lower()
    assert events[-1]["type"] == "completed"


@pytest.mark.asyncio
async def test_run_existing_retries_when_model_prints_a_tool_call_as_text(monkeypatch) -> None:
    runtime._AGENT_CHECKPOINTER = None
    model_calls = 0

    async def complete(_system: str, _user: str) -> str:
        nonlocal model_calls
        model_calls += 1
        if model_calls == 1:
            return '{"tool_calls":[{"name":"project_list","arguments":{}}]}'
        if model_calls == 2:
            return ('{"tool_calls":[{"name":"workflow_run","arguments":'
                    '{"project_id":"p1","workflow_id":"w1","goal":"生成PPT",'
                    '"output_formats":["PPTX"]}}]}')
        raise AssertionError("纠正后的真实运行应直接完成任务")

    calls: list[str] = []

    async def gateway(_request, _call_id, capability, _arguments):
        calls.append(capability.id)
        return {"success": True, "result": "工作流已执行", "output": {"deliverableId": "d1"},
                "taskComplete": True, "completionSummary": "已运行工作流并生成 PPT"}

    monkeypatch.setattr(runtime.llm, "complete", complete)
    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="required-existing-run", gateway_url="http://java", gateway_token="secret",
        execution_mode="AUTO", session_id="s-existing", goal="运行刚才的工作流，给我一份PPT",
        page="workflow",
        capabilities=[AgentCapability(
            id="workflow.run", title="运行工作流", description="运行已保存工作流",
            mode="WRITE", risk="CREATE_VERSION",
            arguments=["project_id", "workflow_id", "goal", "output_formats"],
            inputSchema={"type": "object", "additionalProperties": False,
                         "required": ["project_id", "workflow_id", "goal", "output_formats"],
                         "properties": {"project_id": {"type": "string"},
                                        "workflow_id": {"type": "string"},
                                        "goal": {"type": "string"},
                                        "output_formats": {"type": "array", "items": {"type": "string"}}}},
        )],
    )

    events = [event async for event in run_continuous_agent_stream(request, model_override=CodexCliChatModel())]

    assert calls == ["workflow.run"]
    assert model_calls == 2
    assert events[-1]["type"] == "completed"
    assert events[-1]["content"] == "已运行工作流并生成 PPT"


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


def test_codex_cli_uses_ephemeral_writable_home_with_auth_only(tmp_path, monkeypatch) -> None:
    source_home = tmp_path / "source-home"
    source_home.mkdir()
    (source_home / "auth.json").write_text('{"token":"test"}', encoding="utf-8")
    (source_home / "config.toml").write_text('model="should-not-be-copied"', encoding="utf-8")
    runtime_dir = tmp_path / "runtime"
    runtime_dir.mkdir()
    monkeypatch.setenv("CODEX_HOME", str(source_home))

    environment = LlmGateway()._codex_cli_environment(runtime_dir)
    runtime_home = Path(environment["CODEX_HOME"])

    assert runtime_home != source_home
    assert (runtime_home / "auth.json").read_text(encoding="utf-8") == '{"token":"test"}'
    assert not (runtime_home / "config.toml").exists()


def test_codex_cli_inherits_only_desktop_default_model(tmp_path, monkeypatch) -> None:
    from app.config import settings

    source_home = tmp_path / "source-home"
    source_home.mkdir()
    (source_home / "config.toml").write_text(
        'model = "gpt-current"\nmodel_reasoning_effort = "medium"\n[plugins.test]\nenabled = true\n',
        encoding="utf-8",
    )
    monkeypatch.setenv("CODEX_HOME", str(source_home))
    monkeypatch.setattr(settings, "codex_cli_model", "")

    assert LlmGateway()._codex_cli_model() == "gpt-current"


def test_codex_cli_explicit_model_overrides_desktop_default(tmp_path, monkeypatch) -> None:
    from app.config import settings

    source_home = tmp_path / "source-home"
    source_home.mkdir()
    (source_home / "config.toml").write_text('model = "gpt-current"\n', encoding="utf-8")
    monkeypatch.setenv("CODEX_HOME", str(source_home))
    monkeypatch.setattr(settings, "codex_cli_model", "gpt-explicit")

    assert LlmGateway()._codex_cli_model() == "gpt-explicit"


def test_codex_cli_error_prefers_actual_error_over_trailing_warning() -> None:
    stderr = b"WARN plugin catalog unavailable\nError: model request failed\nWARN analytics unavailable\n"

    assert LlmGateway._codex_cli_error(stderr) == "Error: model request failed"


@pytest.mark.asyncio
async def test_research_uses_configured_search_provider(monkeypatch) -> None:
    import app.llm as llm_module

    async def search(_topic: str, _limit: int):
        return [{"title": "福田活动", "url": "https://example.com/event", "snippet": "今晚开放"}]

    gateway = LlmGateway()
    monkeypatch.setattr(llm_module, "search_web", search)
    result = await gateway.discover_sources("深圳福田今天下班去哪玩", 6)

    assert result["mode"] == "search-provider"
    assert result["status"] == "ready"
    assert result["provider"] == "searxng"
    assert result["sources"][0]["url"] == "https://example.com/event"


@pytest.mark.asyncio
async def test_research_reports_provider_unavailable_without_fake_fallback(monkeypatch) -> None:
    import app.llm as llm_module

    async def search(_topic: str, _limit: int):
        raise SearchProviderUnavailable("联网搜索服务暂时无法访问")

    monkeypatch.setattr(llm_module, "search_web", search)
    result = await LlmGateway().discover_sources("深圳福田今天下班去哪玩", 6)

    assert result["mode"] == "search-unavailable"
    assert result["status"] == "unavailable"
    assert result["sources"] == []
    assert "暂时无法访问" in result["summary"]


@pytest.mark.asyncio
async def test_research_reports_real_empty_result(monkeypatch) -> None:
    import app.llm as llm_module

    async def search(_topic: str, _limit: int):
        return []

    monkeypatch.setattr(llm_module, "search_web", search)
    result = await LlmGateway().discover_sources("极少见的查找内容", 6)

    assert result["mode"] == "search-empty"
    assert result["status"] == "empty"
    assert result["sources"] == []
    assert "没有找到" in result["summary"]


@pytest.mark.asyncio
async def test_continuous_agent_stops_after_three_source_failures(monkeypatch) -> None:
    calls = 0

    async def gateway(*_args):
        nonlocal calls
        calls += 1
        return {"success": False, "error": "来源不可访问", "failureType": "NOT_FOUND", "retryable": False}

    monkeypatch.setattr(runtime, "_call_java_gateway", gateway)
    request = ContinuousAgentRequest(
        run_id="source-budget", gateway_url="http://java", gateway_token="secret",
        session_id="source-session", goal="调研今天福田活动", page="project-home",
        capabilities=[AgentCapability(
            id="source.verify", title="验证来源", description="读取并验证公开网页",
            mode="READ", risk="READ_ONLY", arguments=["url"],
            inputSchema={"type": "object", "properties": {"url": {"type": "string"}},
                         "required": ["url"], "additionalProperties": False},
        )],
    )
    tool = build_continuous_workbench_tools(request, asyncio.Queue())[0]

    results = [await tool.ainvoke({"url": f"https://example.com/{index}"}) for index in range(4)]

    assert calls == 3
    assert results[-1]["failureType"] == "NO_PROGRESS"
    assert results[-1]["retryable"] is False


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
