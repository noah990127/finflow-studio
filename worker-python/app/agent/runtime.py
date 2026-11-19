import json
import asyncio
import re
import httpx
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, create_model

from ..config import settings
from ..integrations.search_provider import SearchProviderError
from ..llm import llm
from ..research import fetch_web, search_web
from ..skills.loader import Skill, load_skills
from .model_config import AgentModelConfig, custom_model


_AGENT_CHECKPOINTER = None
_ACTIVE_AGENT_THREADS: set[str] = set()


def _agent_checkpointer():
    global _AGENT_CHECKPOINTER
    if _AGENT_CHECKPOINTER is None:
        from langgraph.checkpoint.memory import InMemorySaver
        _AGENT_CHECKPOINTER = InMemorySaver()
    return _AGENT_CHECKPOINTER


class AgentCapability(BaseModel):
    id: str
    title: str
    description: str
    mode: str
    risk: str
    arguments: list[str] = Field(default_factory=list)
    input_schema: dict[str, Any] = Field(default_factory=dict, alias="inputSchema")
    output_schema: dict[str, Any] = Field(default_factory=dict, alias="outputSchema")


class AgentResource(BaseModel):
    id: str
    name: str
    type: str
    group: str
    status: str = ""


class AgentMessage(BaseModel):
    role: str
    content: str


class AgentPlanRequest(BaseModel):
    model_config_override: Optional[AgentModelConfig] = Field(default=None, alias="model_config", repr=False)
    session_id: str = ""
    execution_mode: str = "APPROVAL"
    continuation: bool = False
    observation: dict[str, Any] = Field(default_factory=dict)
    completed_actions: int = 0
    goal: str
    page: str
    project_id: Optional[str] = None
    project_name: str = "当前项目"
    selection: dict[str, Any] = Field(default_factory=dict)
    resources: list[AgentResource] = Field(default_factory=list)
    recent_messages: list[AgentMessage] = Field(default_factory=list)
    capabilities: list[AgentCapability]


class ContinuousAgentRequest(AgentPlanRequest):
    run_id: str
    gateway_url: str
    gateway_token: str = Field(repr=False)
    resume: bool = False
    approval_count: int = 0
    max_tool_calls: int = 16
    timeout_seconds: int = 300
    idle_timeout_seconds: int = Field(default=180, ge=1, le=3600)
    tool_timeout_seconds: int = Field(default=600, ge=1, le=3600)


class AgentAction(BaseModel):
    tool: str
    title: str
    description: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class AgentDecision(BaseModel):
    summary: str
    intent: str
    public_summary: str = ""
    selected_skills: list[str] = Field(default_factory=list)
    completed: bool = False


class AgentPlanResponse(BaseModel):
    summary: str
    intent: str
    public_summary: str = ""
    selected_skills: list[str] = Field(default_factory=list)
    steps: list[AgentAction]
    mode: str
    completed: bool = False


class OpenTaskPolicy(BaseModel):
    external_research: str = "OFF"
    domain_allowlist: list[str] = Field(default_factory=list)
    max_tool_calls: int = 80
    timeout_seconds: int = 900


class OpenTaskRequest(BaseModel):
    task: str
    project_id: str
    source_context: str = ""
    resources: list[AgentResource] = Field(default_factory=list)
    skills: list[str] = Field(default_factory=list)
    policy: OpenTaskPolicy = Field(default_factory=OpenTaskPolicy)


class ToolSearchInput(BaseModel):
    query: str = Field(description="用业务语言描述要完成的动作")


class ToolDescribeInput(BaseModel):
    tool_id: str = Field(description="search_tools 返回的工具 id")


class DiscoveredToolInput(BaseModel):
    tool_id: str = Field(description="已通过 search_tools 发现的工具 id")
    arguments: dict[str, Any] = Field(default_factory=dict, description="严格按 describe_tool 契约填写的参数")
    public_summary: str = Field(default="", description="向用户简要说明为什么执行这个动作")


@dataclass
class AgentDependencies:
    request: AgentPlanRequest
    skills: list[Skill]
    staged_actions: list[AgentAction] = field(default_factory=list)
    selected_skills: set[str] = field(default_factory=set)
    public_summary: str = ""

def _model():
    from langchain_openai import ChatOpenAI

    if llm.provider == "codex" and settings.openai_api_key:
        return ChatOpenAI(
            model=settings.openai_model,
            base_url=settings.openai_base_url,
            api_key=settings.openai_api_key,
            reasoning_effort=settings.openai_reasoning_effort,
            max_tokens=settings.openai_max_output_tokens,
        )
    if llm.provider == "deepseek" and settings.deepseek_api_key:
        return ChatOpenAI(
            model=settings.deepseek_chat_model,
            base_url=settings.deepseek_base_url,
            api_key=settings.deepseek_api_key,
            max_tokens=settings.openai_max_output_tokens,
        )
    if llm.provider == "codex-cli" and llm.configured:
        from ..integrations.codex_cli_chat_model import CodexCliChatModel
        return CodexCliChatModel()
    return None


def build_workbench_tools(deps: AgentDependencies):
    from langchain_core.tools import StructuredTool

    tools = []
    for capability in deps.request.capabilities:
        if capability.id == "assistant.respond":
            continue
        safe_name = capability.id.replace(".", "_").replace("-", "_")
        schema = capability.input_schema or {"type": "object", "properties": {
            name: {"type": "string"} for name in capability.arguments}, "required": capability.arguments}
        required = set(schema.get("required", []))
        if capability.id == "workflow.prepare" and "workflow_spec" in schema.get("properties", {}):
            required.add("workflow_spec")
        properties = schema.get("properties", {})
        fields = {}
        for name, property_schema in properties.items():
            annotation = _schema_type(property_schema, f"{safe_name}_{name}")
            default = ... if name in required else None
            fields[name] = (annotation if name in required else Optional[annotation], _schema_field(property_schema, default))
        fields["action_summary"] = (str, Field(default="", description="向用户简短说明目标理解和选择此动作的依据；不要内部推理"))
        args_schema = create_model(f"{safe_name.title().replace('_', '')}Input",
                                   __config__=ConfigDict(extra="forbid"), **fields)

        def make_invoke(item: AgentCapability):
            async def invoke(**arguments: Any) -> str:
                if deps.staged_actions:
                    return "本轮已经选择了一个真实工作台动作。请等待执行结果，不要继续调用其他工作台工具"
                deps.public_summary = str(arguments.pop("action_summary", ""))[:1200]
                clean_arguments = {key: _plain_value(value) for key, value in arguments.items() if value is not None}
                deps.staged_actions.append(AgentAction(
                    tool=item.id,
                    title=item.title,
                    description=item.description,
                    arguments=clean_arguments,
                ))
                return f"已选择 {item.id}，后端将在策略校验后执行"
            return invoke

        tools.append(StructuredTool.from_function(
            coroutine=make_invoke(capability),
            name=safe_name,
            description=(f"{capability.description}。风险：{capability.risk}；"
                         f"参数字段：{', '.join(capability.arguments) or '无'}。调用即把该操作加入本次执行序列。"),
            args_schema=args_schema,
            return_direct=True,
        ))
    return tools


def _capability_input_model(capability: AgentCapability, include_public_summary: bool = False):
    safe_name = capability.id.replace(".", "_").replace("-", "_")
    schema = capability.input_schema or {
        "type": "object",
        "properties": {name: {"type": "string"} for name in capability.arguments},
        "required": capability.arguments,
    }
    required = set(schema.get("required", []))
    if capability.id == "workflow.prepare" and "workflow_spec" in schema.get("properties", {}):
        required.add("workflow_spec")
    fields = {}
    for name, property_schema in schema.get("properties", {}).items():
        annotation = _schema_type(property_schema, f"{safe_name}_{name}")
        default = ... if name in required else None
        fields[name] = (
            annotation if name in required else Optional[annotation],
            _schema_field(property_schema, default),
        )
    if include_public_summary:
        fields["public_summary"] = (Optional[str], Field(
            default=None,
            description="用一两句业务语言说明当前结果意味着什么，以及为什么选择这个动作；不要写隐藏推理",
        ))
    return create_model(
        f"{safe_name.title().replace('_', '')}ContinuousInput",
        __config__=ConfigDict(extra="forbid"),
        **fields,
    )


async def _call_java_gateway(request: ContinuousAgentRequest, call_id: str,
                             capability: AgentCapability, arguments: dict[str, Any],
                             client: httpx.AsyncClient | None = None) -> dict[str, Any]:
    async def call(active_client: httpx.AsyncClient) -> dict[str, Any]:
        response = await active_client.post(
            f"{request.gateway_url.rstrip('/')}/internal/assistant/runs/{request.run_id}/tools/call",
            headers={"X-Agent-Gateway-Token": request.gateway_token},
            json={"callId": call_id, "tool": capability.id, "arguments": arguments},
        )
        response.raise_for_status()
        return _unicode_safe(response.json())

    if client is not None:
        return await call(client)
    async with httpx.AsyncClient(timeout=180) as temporary_client:
        return await call(temporary_client)


async def _wait_for_workflow_run(request: ContinuousAgentRequest, workflow_run_id: str,
                                 call_id: str, queue: asyncio.Queue[dict[str, Any]],
                                 client: httpx.AsyncClient) -> dict[str, Any]:
    """Wait through short status reads so a long workflow never occupies one gateway request."""
    url = (f"{request.gateway_url.rstrip('/')}/internal/assistant/runs/{request.run_id}"
           f"/workflow-runs/{workflow_run_id}")
    last_signature = ""
    while True:
        response = await client.get(url, headers={"X-Agent-Gateway-Token": request.gateway_token})
        response.raise_for_status()
        status = _unicode_safe(response.json())
        if status.get("terminal"):
            return status
        signature = f"{status.get('status')}:{status.get('progress')}:{status.get('message')}"
        if signature != last_signature:
            await queue.put({
                "type": "executing", "status": "running", "activity_id": f"{call_id}:progress",
                "toolName": "workflow.run", "message": status.get("message") or "工作流正在执行",
                "resultSummary": (f"已完成 {status.get('completedNodes', 0)}/"
                                  f"{status.get('totalNodes', 0)} 个节点"),
                "progress": min(96, max(24, int(status.get("progress", 24)))),
            })
            last_signature = signature
        await asyncio.sleep(0.8)


def build_continuous_workbench_tools(request: ContinuousAgentRequest,
                                     queue: asyncio.Queue[dict[str, Any]],
                                     client: httpx.AsyncClient | None = None,
                                     direct_tool_ids: set[str] | None = None,
                                     include_discovery: bool = False):
    """Expose the Java workbench through strict tools; reads may overlap, writes are serialized."""
    from langchain_core.tools import StructuredTool

    tools = []
    write_lock = asyncio.Lock()
    sequence = 0
    source_failure_streak = 0
    invokers: dict[str, Any] = {}
    capabilities = {item.id: item for item in request.capabilities if item.id != "assistant.respond"}
    contract = _continuous_task_contract(request.goal)

    for capability in request.capabilities:
        if capability.id == "assistant.respond":
            continue
        safe_name = capability.id.replace(".", "_").replace("-", "_")

        def make_invoke(item: AgentCapability):
            async def invoke(**arguments: Any) -> dict[str, Any]:
                nonlocal sequence, source_failure_streak
                blocked_reason = _tool_constraint_violation(item, contract)
                if blocked_reason:
                    return {
                        "success": False, "failureType": "TASK_CONSTRAINT", "retryable": False,
                        "error": blocked_reason,
                        "recoveryHint": "遵守用户明确边界，停止该操作并完成允许范围内的工作",
                    }
                sequence += 1
                if sequence > request.max_tool_calls:
                    return {
                        "success": False,
                        "failureType": "BUDGET_EXHAUSTED",
                        "retryable": False,
                        "recoveryHint": "停止继续调用工具，向用户说明已完成内容和剩余工作",
                    }
                public_summary = str(arguments.pop("public_summary", "")).strip()[:1200]
                clean_arguments = {key: _plain_value(value) for key, value in arguments.items() if value is not None}
                call_id = f"{request.run_id}:{sequence}"
                if public_summary:
                    await queue.put({
                        "type": "thinking_summary", "status": "completed",
                        "activity_id": f"{call_id}:decision", "phase": "assessment",
                        "message": public_summary, "progress": min(90, 18 + sequence * 2),
                    })
                await queue.put({
                    "type": "tool_call", "status": "running", "activity_id": call_id,
                    "toolName": item.id, "argumentSummary": _summarize_arguments(clean_arguments),
                    "message": item.title, "progress": min(92, 20 + sequence * 2),
                })
                if item.id == "workflow.prepare" and not isinstance(clean_arguments.get("workflow_spec"), dict):
                    result = {
                        "success": False,
                        "error": "自主 Agent 创建工作流时必须提交完整 workflow_spec，不能使用固定流程骨架",
                        "failureType": "PARAMETER",
                        "retryable": True,
                        "recoveryHint": "根据任务设计节点、完整配置和 depends_on 后重新调用 workflow.prepare",
                    }
                    await queue.put({
                        "type": "observation", "status": "failed", "activity_id": call_id,
                        "toolName": item.id, "resultSummary": result["error"], "message": result["error"],
                        "failureType": result["failureType"], "retryable": True,
                        "recoveryHint": result["recoveryHint"], "progress": min(94, 22 + sequence * 2),
                    })
                    return result
                if item.id in {"source.verify", "source.add_verified"} and source_failure_streak >= 3:
                    result = {
                        "success": False,
                        "error": "本次任务已有 3 个来源连续无法读取，已停止继续穷举网址",
                        "failureType": "NO_PROGRESS",
                        "retryable": False,
                        "recoveryHint": "复用已验证来源并明确资料限制；若没有有效来源，说明联网搜索不可用并结束任务",
                    }
                    await queue.put({
                        "type": "observation", "status": "failed", "activity_id": call_id,
                        "toolName": item.id, "resultSummary": result["error"], "message": result["error"],
                        "failureType": result["failureType"], "retryable": False,
                        "recoveryHint": result["recoveryHint"], "progress": min(94, 22 + sequence * 2),
                    })
                    return result
                try:
                    if item.mode == "READ":
                        result = await _call_java_gateway(request, call_id, item, clean_arguments) if client is None \
                            else await _call_java_gateway(request, call_id, item, clean_arguments, client)
                    else:
                        async with write_lock:
                            result = await _call_java_gateway(request, call_id, item, clean_arguments) if client is None \
                                else await _call_java_gateway(request, call_id, item, clean_arguments, client)
                    workflow_run_id = str((result.get("output") or {}).get("workflowRunId") or "")
                    workflow_status = str((result.get("output") or {}).get("workflowRunStatus") or "")
                    if (item.id == "workflow.run" and result.get("success") and workflow_run_id
                            and workflow_status in {"QUEUED", "RUNNING", "CANCEL_REQUESTED"}):
                        active_client = client or httpx.AsyncClient(timeout=30)
                        try:
                            result = await _wait_for_workflow_run(
                                request, workflow_run_id, call_id, queue, active_client)
                        finally:
                            if client is None:
                                await active_client.aclose()
                except (httpx.HTTPError, OSError) as exception:
                    result = {
                        "success": False, "error": str(exception), "failureType": "NETWORK",
                        "retryable": True, "recoveryHint": "Java 工具网关暂时不可用，可以有限重试",
                    }
                if item.id in {"source.verify", "source.add_verified"}:
                    source_failure_streak = 0 if result.get("success") else source_failure_streak + 1
                await queue.put({
                    "type": "observation", "status": "completed" if result.get("success") else "failed",
                    "activity_id": call_id, "toolName": item.id,
                    "resultSummary": result.get("result") or result.get("error") or "工具已返回",
                    "message": result.get("result") or result.get("error") or "工具已返回",
                    "output": result.get("output", {}), "provenance": result.get("provenance", {}),
                    "failureType": result.get("failureType"), "retryable": result.get("retryable"),
                    "recoveryHint": result.get("recoveryHint"),
                    "progress": min(94, 22 + sequence * 2),
                })
                if result.get("taskComplete"):
                    await queue.put({
                        "type": "_task_complete",
                        "content": result.get("completionSummary") or result.get("result") or "任务已完成",
                    })
                return result
            return invoke

        invoke = make_invoke(capability)
        invokers[capability.id] = invoke
        if direct_tool_ids is None or capability.id in direct_tool_ids:
            tools.append(StructuredTool.from_function(
                coroutine=invoke,
                name=safe_name,
                description=(f"{capability.description}。模式：{capability.mode}；风险：{capability.risk}。"
                             "参数必须符合 schema；返回的 Observation 是真实执行结果。"
                             "每次调用都填写 public_summary，用业务语言简要说明为何现在执行这个动作。"),
                args_schema=_capability_input_model(capability, include_public_summary=True),
            ))

    if include_discovery:
        async def search_tools(query: str) -> list[dict[str, Any]]:
            normalized = query.strip().lower()
            terms = set(re.findall(r"[a-z0-9_.-]+|[\u4e00-\u9fff]{1,4}", normalized))
            ranked: list[tuple[int, AgentCapability]] = []
            for item in capabilities.values():
                text = " ".join([item.id, item.title, item.description, *item.arguments]).lower()
                score = (6 if normalized and normalized in text else 0) + sum(term in text for term in terms)
                if score or not terms:
                    ranked.append((score, item))
            ranked.sort(key=lambda value: (-value[0], value[1].id))
            return [{
                "id": item.id, "title": item.title, "description": item.description,
                "mode": item.mode, "risk": item.risk,
            } for _, item in ranked[:12]]

        async def describe_tool(tool_id: str) -> dict[str, Any]:
            item = capabilities.get(tool_id)
            return item.model_dump(by_alias=True) if item else {
                "error": f"工具不存在：{tool_id}", "recoveryHint": "先使用 search_tools 查找正确工具",
            }

        async def execute_discovered_tool(tool_id: str, arguments: dict[str, Any],
                                          public_summary: str = "") -> dict[str, Any]:
            item = capabilities.get(tool_id)
            if item is None:
                return {"success": False, "failureType": "TOOL_NOT_FOUND", "retryable": False,
                        "error": f"工具不存在：{tool_id}", "recoveryHint": "先使用 search_tools 查找正确工具"}
            try:
                validated = _capability_input_model(item, include_public_summary=True).model_validate({
                    **arguments, "public_summary": public_summary,
                }).model_dump(exclude_none=True)
            except Exception as exception:
                return {"success": False, "failureType": "PARAMETER", "retryable": False,
                        "error": f"参数不符合 {tool_id} 契约：{exception}",
                        "recoveryHint": "调用 describe_tool 查看必填字段、类型和枚举值后修正参数"}
            return await invokers[tool_id](**validated)

        tools.extend([
            StructuredTool.from_function(
                coroutine=search_tools, name="search_tools",
                description="当当前显式工具不够时，按业务目标搜索工作台能力；不会修改工作区。",
                args_schema=ToolSearchInput,
            ),
            StructuredTool.from_function(
                coroutine=describe_tool, name="describe_tool",
                description="读取一个已发现工具的完整参数、输出和风险契约。",
                args_schema=ToolDescribeInput,
            ),
            StructuredTool.from_function(
                coroutine=execute_discovered_tool, name="execute_discovered_tool",
                description=("执行一个已通过 search_tools/describe_tool 发现的工作台工具。"
                             "参数在本地严格验证后才会送往 Java 权限网关。"),
                args_schema=DiscoveredToolInput,
            ),
        ])
    return tools


def _continuous_direct_tool_ids(request: ContinuousAgentRequest) -> set[str]:
    """Keep the common path small; the remaining catalog stays available through discovery."""
    selected = {
        "workspace.inspect", "workspace.navigate", "project.list", "project.open",
        "workflow.open", "workflow.prepare", "workflow.run", "workflow.edit", "workflow.duplicate",
        "resource.open", "resource.read", "knowledge.search", "knowledge.read",
        "source.verify", "source.add_verified", "deliverable.open",
    }
    goal = request.goal.lower()
    category_hints = {
        "project.": ("项目",),
        "folder.": ("文件夹", "目录"),
        "resource.": ("文件", "资源", "来源", "上传"),
        "knowledge.": ("资料", "知识", "调研", "搜索", "研究", "引用"),
        "source.": ("网址", "联网", "资料", "调研", "研究"),
        "dataset.": ("数据", "表格", "excel", "csv", "api", "数据库", "加工", "查询"),
        "workflow.": ("工作流", "分析", "研究", "调研", "报告", "ppt", "html"),
        "deliverable.": ("输出", "成果", "报告", "ppt", "html", "word", "pdf", "导出"),
    }
    for prefix, hints in category_hints.items():
        if any(hint in goal for hint in hints):
            selected.update(item.id for item in request.capabilities if item.id.startswith(prefix))
    contract = _continuous_task_contract(request.goal)
    if contract["source_only"]:
        selected.discard("source.verify")
    selected = {
        tool_id for tool_id in selected
        if (capability := next((item for item in request.capabilities if item.id == tool_id), None)) is None
        or _tool_constraint_violation(capability, contract) is None
    }
    return selected


def _continuous_task_contract(goal: str) -> dict[str, bool]:
    text = goal.strip().lower()
    source_only = any(value in text for value in (
        "只找资料", "仅找资料", "只搜索资料", "仅搜索资料", "先找资料", "先搜资料",
        "only find sources", "sources only",
    ))
    no_delivery = source_only or any(value in text for value in (
        "不要生成", "先不要生成", "不要又生成", "别再生成", "不用再生成", "不要另生成",
        "暂时不要做", "不要做ppt", "不做ppt", "不要输出件",
        "do not generate", "no deliverable",
    ))
    no_run = any(value in text for value in (
        "不要运行", "先不要运行", "不要再运行", "别再运行", "不运行", "不用运行", "无需运行", "暂不运行", "先别运行",
        "不要执行", "先别执行",
        "不要重跑", "不要重新运行", "不要再跑", "别再跑", "不要跑", "先别跑",
        "只创建工作流", "只建工作流", "只保存工作流",
        "do not run", "don't run",
    )) or bool(re.search(r"(?:不要|别|先别|暂不|无需|不用).{0,4}(?:运行|执行|重跑|跑一遍)", text))
    read_only = any(value in text for value in (
        "只告诉我", "只回答", "不要修改", "别修改", "不要改", "只读", "先别加工", "不要加工",
        "不要再生成", "不要又生成", "别再生成", "不用再生成", "不要另生成", "不要重新生成",
        "不要再运行", "别再运行", "不要重跑", "不要重新运行", "不要继续执行", "read only",
        "不要再跑", "别再跑", "不要跑", "先别跑",
    ))
    creates_workflow = any(value in text for value in (
        "创建工作流", "新建工作流", "先建工作流", "先建可复用流程", "做成可复用流程", "沉淀为工作流",
    ))
    run_existing = not no_run and not creates_workflow and any(value in text for value in (
        "运行刚才", "执行刚才", "运行上一个", "执行上一个", "现在运行", "立即运行",
        "重新运行", "再运行", "重跑", "运行修改后", "运行复制后",
        "run the workflow", "run it now",
    ))
    requires_run = not (source_only or no_run or read_only) and (
        run_existing
        or _is_workflow_business_task(goal)
        or bool(_requested_output_formats(goal))
        or any(value in text for value in ("成果出来", "生成成果", "产出成果", "最后生成", "最后输出"))
    )
    return {
        "source_only": source_only,
        "no_delivery": no_delivery,
        "no_run": no_run,
        "read_only": read_only,
        "run_existing": run_existing,
        "requires_run": requires_run,
    }


def _single_ready_workflow_id(request: ContinuousAgentRequest) -> str | None:
    candidates = [
        item.id for item in request.resources
        if item.type.upper() == "WORKFLOW" and item.status.upper() == "READY"
    ]
    return candidates[0] if len(candidates) == 1 else None


def _requested_output_formats(goal: str) -> list[str]:
    text = goal.lower()
    formats = []
    if "ppt" in text or "powerpoint" in text or "演示文稿" in text:
        formats.append("PPTX")
    if "html" in text or "网页" in text:
        formats.append("HTML_SLIDES")
    if "pdf" in text:
        formats.append("PDF")
    if "word" in text or "docx" in text:
        formats.append("DOCX")
    return formats


def _requested_workflow_copy_name(goal: str) -> str | None:
    if not any(value in goal for value in ("复制", "另存")):
        return None
    quoted = re.search(r"(?:叫|命名为|名为|另存为)\s*[“‘\"']([^”’\"']{2,40})[”’\"']", goal)
    if quoted:
        return quoted.group(1).strip()
    plain = re.search(r"(?:叫|命名为|名为|另存为)\s*([一-鿿A-Za-z0-9 _-]{2,30})", goal)
    return plain.group(1).strip() if plain else None


def _requests_project_creation(goal: str) -> bool:
    text = goal.strip().lower()
    return "项目" in text and any(value in text for value in ("新建", "创建", "建一个", "建个"))


def _is_workflow_business_task(goal: str) -> bool:
    text = goal.strip().lower()
    return any(value in text for value in (
        "分析", "调研", "研究", "复核", "对比", "比较", "归因", "洞察", "总结", "报告",
        "analyze", "research", "compare", "review",
    ))


def _project_task_goal(goal: str) -> str:
    task = re.sub(
        r"^(?:(?:请|麻烦|可以|能不能)?(?:帮我|帮忙)?)?"
        r"(?:(?:新建|创建|建)(?:一个|个)?项目)[，,、：:\s]*",
        "",
        goal.strip(),
    ).strip()
    return task or goal.strip()


def _project_name_from_goal(goal: str) -> str:
    task = _project_task_goal(goal)
    name = re.sub(r"^(?:请|帮我|帮忙)?(?:分析|调研|研究|复核|对比|比较|总结)(?:一下|下)?", "", task).strip()
    name = re.split(r"[。！？!?；;\n]|(?:帮我)?(?:生成|输出|做成|制作)", name, maxsplit=1)[0].strip()
    name = re.split(r"(?:的时间线|当前舆论|舆论形势)", name, maxsplit=1)[0].strip()
    name = re.sub(r"^(?:最近|近期|当前)(?:发生的|的)?", "", name).strip(" ，,、：:")
    if not name:
        return "新的业务分析"
    if not any(name.endswith(value) for value in ("分析", "研究", "复核", "报告")):
        name += "分析"
    return name[:36]


def _tool_constraint_violation(capability: AgentCapability, contract: dict[str, bool]) -> str | None:
    tool_id = capability.id
    if contract["read_only"] and capability.mode != "READ":
        return "用户要求只读回答，本次任务不允许修改工作区"
    if contract["source_only"] and (tool_id.startswith("workflow.") or tool_id.startswith("deliverable.")):
        return "用户要求本次只收集资料，不允许创建、编辑或运行工作流，也不允许生成成果"
    if contract["no_delivery"] and tool_id.startswith("deliverable."):
        return "用户明确要求本次不要生成成果"
    if contract["no_run"] and tool_id == "workflow.run":
        return "用户明确要求本次不要运行工作流"
    if contract["run_existing"] and tool_id in {
        "workflow.prepare", "workflow.initialize", "workflow.edit", "workflow.add_node",
        "workflow.duplicate", "workflow.remove_node", "workflow.connect", "workflow.save_version",
        "workflow.add_selected_resource", "workflow.add_data_transform", "workflow.add_outputs",
    }:
        return "用户要求运行已有工作流，本次不允许在运行前临时创建、编辑或另存流程"
    return None


def _summarize_arguments(arguments: dict[str, Any]) -> str:
    value = json.dumps(_unicode_safe(arguments), ensure_ascii=False, default=str)
    return value if len(value) <= 500 else value[:497] + "..."


def _unicode_safe(value: Any) -> Any:
    """Replace unpaired UTF-16 surrogates before SSE/model UTF-8 encoding."""
    if isinstance(value, str):
        return value.encode("utf-8", errors="replace").decode("utf-8")
    if isinstance(value, dict):
        return {str(_unicode_safe(key)): _unicode_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_unicode_safe(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_unicode_safe(item) for item in value)
    return value


def _schema_type(schema: dict[str, Any], name: str):
    values = schema.get("enum")
    if isinstance(values, list) and values:
        return Literal.__getitem__(tuple(values))
    kind = schema.get("type", "string")
    if kind == "integer":
        return int
    if kind == "number":
        return float
    if kind == "boolean":
        return bool
    if kind == "array":
        return list[_schema_type(schema.get("items", {}), name + "_item")]
    if kind == "object":
        properties = schema.get("properties")
        if not isinstance(properties, dict) or not properties:
            return dict[str, Any]
        required = set(schema.get("required", []))
        fields = {}
        for field_name, field_schema in properties.items():
            annotation = _schema_type(field_schema, name + "_" + field_name)
            default = ... if field_name in required else None
            fields[field_name] = (annotation if field_name in required else Optional[annotation],
                                  _schema_field(field_schema, default))
        return create_model(name.title().replace("_", ""),
                            __config__=ConfigDict(extra="forbid" if schema.get("additionalProperties") is False else "allow"),
                            **fields)
    return str


def _schema_field(schema: dict[str, Any], default: Any):
    options: dict[str, Any] = {"default": default, "description": schema.get("description", "")}
    if isinstance(schema.get("minimum"), (int, float)):
        options["ge"] = schema["minimum"]
    if isinstance(schema.get("minLength"), int):
        options["min_length"] = schema["minLength"]
    return Field(**options)


def _plain_value(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return {key: _plain_value(item) for key, item in value.model_dump(exclude_none=True).items()}
    if isinstance(value, list):
        return [_plain_value(item) for item in value]
    return value


def _needs_action_retry(goal: str, summary: str, staged_actions: list[AgentAction]) -> bool:
    """Catch the fallback planner claiming a write it never staged."""
    if staged_actions:
        return False
    mutation_words = ("新建", "创建", "修改", "更新", "编辑", "改名", "重命名", "移动", "删除", "删掉",
                      "上传", "导入", "连接", "运行", "执行", "保存", "生成", "导出", "加入", "添加")
    completion_claims = ("已提交", "已创建", "已新建", "已修改", "已更新", "已编辑", "已改名", "已移动",
                         "已删除", "已上传", "已导入", "已连接", "已运行", "已执行", "已保存", "已生成", "已导出")
    return any(word in goal for word in mutation_words) and any(claim in summary for claim in completion_claims)


async def plan_with_agent(request: AgentPlanRequest, model_override: Any = None) -> Optional[AgentPlanResponse]:
    if not settings.agent_enabled or (not llm.configured and model_override is None and request.model_config_override is None):
        return None
    model = model_override or (custom_model(request.model_config_override) if request.model_config_override else _model())
    if model is None:
        return None

    from deepagents import create_deep_agent
    from deepagents.backends import StateBackend
    from langchain_core.tools import tool

    deps = AgentDependencies(request=request, skills=load_skills(settings.agent_skills_dir))

    @tool
    def inspect_workspace() -> dict[str, Any]:
        """Read the active project, current selection, resources, and recent conversation."""
        item = deps.request
        return {
            "project": {"id": item.project_id, "name": item.project_name, "page": item.page},
            "execution_mode": item.execution_mode,
            "selection": item.selection,
            "resources": [resource.model_dump() for resource in item.resources],
            "recent_messages": [message.model_dump() for message in item.recent_messages],
        }

    @tool
    def find_skills(query: str) -> list[dict[str, str]]:
        """Find reusable work instructions relevant to the user's goal before choosing actions."""
        terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
        ranked = sorted(deps.skills, key=lambda skill: sum(term in (skill.name + skill.description + skill.instructions).lower() for term in terms), reverse=True)
        selected = ranked[:4]
        deps.selected_skills.update(skill.name for skill in selected)
        return [skill.model_dump() for skill in selected]

    @tool
    def search_tools(query: str) -> list[dict[str, Any]]:
        """Search the workbench tool catalog by id, title, description, or argument name."""
        terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
        matches = []
        for capability in deps.request.capabilities:
            if capability.id == "assistant.respond":
                continue
            text = " ".join([capability.id, capability.title, capability.description, *capability.arguments]).lower()
            score = sum(term in text for term in terms)
            if score or not terms:
                matches.append((score, capability))
        matches.sort(key=lambda item: item[0], reverse=True)
        return [{"id": item.id, "tool_name": item.id.replace(".", "_"), "title": item.title,
                 "description": item.description, "risk": item.risk} for _, item in matches[:12]]

    @tool
    def describe_tool(tool_id: str) -> dict[str, Any]:
        """Read the complete contract and risk metadata for one workbench tool."""
        item = next((value for value in deps.request.capabilities if value.id == tool_id and value.id != "assistant.respond"), None)
        return item.model_dump() if item else {"error": "工具不存在", "tool_id": tool_id}

    from .naming import RESOURCE_NAMING_POLICY
    instructions = RESOURCE_NAMING_POLICY + "\n" + """你是通用的个人工作台 Agent，也是用户编排和执行可复用工作流的助手。
先理解用户真正想完成的事情和当前上下文，再自主选择 Skill、MCP 工具与工作台能力。
涉及项目内容时按需调用 inspect_workspace；按需调用 find_skills、search_tools、describe_tool。所有左侧工作区操作都已作为独立工具提供。
普通问答可以直接回答；读取到足够上下文后直接在 summary 中回答，不再委派给另一个摘要接口。
普通问答可以直接回答；只要任务需要研究、分析、数据加工或生成成果，就必须通过可保存的工作流完成。
先在当前项目中查找已就绪且输入、处理目标、输出格式与本次需求一致的工作流；匹配时复用并运行，不匹配时先调用 workflow.prepare 创建完整工作流，再使用其返回的 workflowId 调用 workflow.run。
workflow.prepare 必须提交 workflow_spec，由你根据任务动态设计节点和依赖，不能套用固定流程。协议 version 固定为 1.0；节点包含唯一 key、type、业务名称、purpose、完整 config 和 depends_on。项目现有输入用 $inputs；可并行的检索或分析使用多个节点，最终再汇总。通常使用 3-8 个业务节点，最多 12 个。REF_SEARCH 填 query；AI_ANALYSIS 填完整 prompt；AGENT_TASK 填 instruction；REVIEW 填 instructions；DELIVERABLE 填 generationPrompt、format、includeCitations 和 citationStyle。name 必须是 6-20 个字左右的简短业务标题，不包含“不要运行”或步骤说明；goal 保留用户完整要求。
不得跳过工作流直接调用 deliverable.create；交付件应由工作流的输出节点生成，以便保留输入、分析、引用和运行记录。
采用动态单步执行：每轮最多调用一个真实工作台工具，然后立即结束本轮并等待 Java 返回真实 Observation。
同一个工作流的节点、分析要求和连线已经明确时，优先一次调用 workflow.edit 批量保存，不要逐节点逐连线各调用一次模型。
用户提供的节点 Prompt 必须完整写入相应配置，包含规则、权重、输出格式和出处要求；不能只写名称或截取开头。
workflow.edit 的 patch 必须是结构化对象；nodes 按 id 合并，edges 为完整连线列表。不要使用自然语言 patch 或 JSON Patch 数组。
Observation 已返回 workflow 时直接使用其节点和版本，不要重复打开核对同一内容。changed=false 表示没有修改，不得重复尝试同样操作。
工具失败时必须读取 failureType、retryable 和 recoveryHint：PARAMETER 要按契约修参，RESOURCE_MISSING 要重新查找真实对象或资料，
CONFLICT 要先读取最新版本，AUTHORIZATION 要说明需要用户处理的权限；只有 NETWORK 且 retryable=true 才能使用相同参数有限重试。
禁止提前编排整条固定步骤，也禁止只描述计划或捏造工具名称。工具结果由 Java 权限网关真实执行。
研究、分析、总结类任务如果用户没有明确指定成果格式，在工作流中默认配置一个 PPTX 输出节点，不要只在对话中返回长篇结论。
只有用户明确要求交互报告、自助分析、数据看板或图表报告时才能选择 FINANCIAL_REPORT，并且必须先确认项目中已有可读取的 CSV 或数据采集结果。
不要因为用户提到“分析”或“报告”就选择 FINANCIAL_REPORT，也不要为了满足前置条件而凭空创建数据集。
根据用户选择的 Auto 或审批策略决定是否等待确认；风险分类由后端最终强制执行。
当前执行模式可从 inspect_workspace 的 execution_mode 读取。AUTO 模式下不得要求用户再次确认；当工作区快照中能按名称唯一匹配对象时，
必须自行使用其 ID 调用工具，不得向用户索要 workflow_id、resource_id、folder_id 等内部标识。APPROVAL 模式下也应先形成工具计划，由界面统一请求确认。
若目标已经完成，不再调用工具，返回 completed=true；否则调用一个最合适的工具并返回 completed=false。
完成本轮后返回 JSON，包含 summary、intent、selected_skills、completed。summary 描述接下来将执行什么或基于 Observation 得出的最终结果，
另外返回 public_summary：专门向用户展示的 2 到 3 句简短说明，不是内部推理记录。
首次决策说明“我理解你希望达成的结果”以及关键约束，再说明处理方式的简要依据；不要只是复述步骤。
后续决策说明真实结果意味着什么、为何继续或调整方式；信息不足时明确不确定性，不要补造事实。
使用用户的业务语言，不包含工具名、内部 ID、代码、隐藏思维链或逐步推导。不得声称尚未执行的操作已完成。
不得把尚未执行的动作说成已经完成。工具失败时先根据错误选择修正参数、替代工具或重试；只有无法继续时才结束并解释原因。
dataset.extract 会把网页 JSON/HTML 真正落为数据文件；后续 dataset.query、dataset.transform 和 dataset.open 必须使用 Observation 返回的新 datasetId，
不得继续使用原网页 resource_id。长任务应依据已执行动作数持续收敛，优先复用已有结果，避免重复读取或重复创建同一资源。
dataset.transform 的 script 只能是单条只读 DuckDB SELECT/WITH SQL，输入表名固定为 source；不确定 SQL 时省略 script，只提供明确 requirements 让系统生成。
用业务用户看得懂的中文，不暴露隐藏推理。"""
    workbench_tools = build_workbench_tools(deps)
    agent = create_deep_agent(
        model=model,
        tools=[inspect_workspace, find_skills, search_tools, describe_tool, *workbench_tools],
        system_prompt=instructions,
        backend=StateBackend(),
        checkpointer=_agent_checkpointer(),
        name="finbtp-workbench-agent",
    )
    thread_id = f"session:{request.session_id or request.project_id or 'personal'}"
    messages = [] if thread_id in _ACTIVE_AGENT_THREADS else [
        message.model_dump() for message in request.recent_messages if message.role in {"user", "assistant"}
    ]
    turn_content = request.goal
    if request.continuation:
        observation = dict(request.observation)
        if isinstance(observation.get("arguments"), dict):
            observation["arguments"] = {key: value for key, value in observation["arguments"].items() if key != "goal"}
        turn_content = (("" if thread_id in _ACTIVE_AGENT_THREADS else "原始目标：" + request.goal) + "\n真实工具 Observation：" +
                        json.dumps(observation, ensure_ascii=False) +
                        f"\n已执行真实动作数：{request.completed_actions}。请根据最新工作区和结果决定下一步。")
    if not messages or messages[-1]["role"] != "user" or messages[-1]["content"] != turn_content:
        messages.append({"role": "user", "content": turn_content})
    result = await asyncio.wait_for(agent.ainvoke(
        {"messages": messages},
        config={"configurable": {"thread_id": thread_id}, "recursion_limit": 12},
    ), timeout=120)
    _ACTIVE_AGENT_THREADS.add(thread_id)
    raw_content = getattr(result.get("messages", [None])[-1], "content", "")
    try:
        raw_decision = json.loads(raw_content) if isinstance(raw_content, str) else {}
    except json.JSONDecodeError:
        raw_decision = {"summary": str(raw_content)}
    if _needs_action_retry(request.goal, str(raw_decision.get("summary", raw_content)), deps.staged_actions):
        result = await asyncio.wait_for(agent.ainvoke({"messages": [{
            "role": "user",
            "content": ("你刚才声称已经执行了写操作，但没有调用任何工作台工具。"
                        "现在必须调用一个真实工具提交动作；如果缺少必要信息，就明确说明缺少什么，"
                        "不得继续声称已提交、已修改或已删除。"),
        }]}, config=config), timeout=120)
        raw_content = getattr(result.get("messages", [None])[-1], "content", "")
        try:
            raw_decision = json.loads(raw_content) if isinstance(raw_content, str) else {}
        except json.JSONDecodeError:
            raw_decision = {"summary": str(raw_content)}
    decision = AgentDecision.model_validate({
        "summary": raw_decision.get("summary", "已根据当前工作台准备处理计划"),
        "intent": raw_decision.get("intent", "workbench-task"),
        "public_summary": raw_decision.get("public_summary") or "",
        "selected_skills": raw_decision.get("selected_skills", sorted(deps.selected_skills)),
        "completed": False if deps.staged_actions else raw_decision.get("completed", True),
    })
    return AgentPlanResponse(summary=decision.summary, intent=decision.intent,
                             public_summary=deps.public_summary or decision.public_summary,
                             selected_skills=decision.selected_skills or sorted(deps.selected_skills),
                             steps=deps.staged_actions, mode="deep-agents", completed=decision.completed)


def _continuous_instructions(request: ContinuousAgentRequest | None) -> str:
    contract = _continuous_task_contract(request.goal if request is not None else "")
    boundaries = []
    if contract["source_only"]:
        boundaries.append("本次只查找、验证并保存资料，不创建或运行工作流，不生成成果。")
    if contract["no_delivery"] and not contract["source_only"]:
        boundaries.append("本次禁止生成任何输出件。")
    if contract["no_run"]:
        boundaries.append("可以创建或编辑工作流，但禁止运行。")
    if contract["read_only"]:
        boundaries.append("本次只读，不得修改工作区。")
    if contract["run_existing"]:
        boundaries.append("本次只定位并运行已有工作流，必须真实调用 workflow.run；不得创建、编辑或另存工作流。")
    elif contract["requires_run"]:
        boundaries.append("本次必须在工作流准备完成后真实调用 workflow.run，不能把“已创建流程”当成任务完成。")
    contract_text = "\n".join(boundaries) or "没有额外的禁止操作；仍需遵守工具权限和任务完成验证。"
    from .naming import RESOURCE_NAMING_POLICY
    return RESOURCE_NAMING_POLICY + "\n" + f"""你是 FinFlow 工作台的自主 Agent。你负责从理解目标到验证结果的完整任务循环，不要在每个工具之后结束任务。
用户明确边界（优先级最高）：
{contract_text}
先读取必要的项目上下文，按需发现 Skill 和工具，再连续执行、检查真实 Observation、修正参数或更换策略，直到用户的任务真正完成。
Skill 说明“怎么做”，Tool 表示“能做什么”。仅在需要时加载 Skill，不要把 Skill 当成工具执行。
相互独立的读取可以在同一轮并行调用；有依赖的操作等待上游返回后再执行。
每次工具调用都填写 public_summary，用一两句业务语言说明刚获得的信息和当前动作的目的，不要输出隐藏思维链。
已经成功返回的读取结果应直接复用；工作区未发生相关变化时，不要使用相同或等价参数重复调用 workspace.inspect、project.list、source.search、knowledge.read 等读取工具。
公开资料搜索结果只是候选。加入网址时优先使用 source.add_verified；多个候选必须在一次调用中通过 sources 数组提交，平台会并行验证并保存成功来源的快照。只收集来源的任务不要先逐个调用 source.verify。禁止把未成功读取的 URL 当作正式来源。
来源验证连续失败 3 次后停止继续更换网址；已有有效来源就基于它给出范围受限的结论，没有有效来源就明确说明联网不可用并结束，不要穷举网站。
已明确的节点、配置和连线优先使用 workflow.edit 一次批量保存；会冲突的写操作必须顺序执行。
普通问答可以直接回答。只要任务需要研究、分析、数据加工或生成成果，就必须通过可保存的工作流完成。
先读取当前项目里的工作流，检查其输入资料、处理目标和输出格式是否覆盖本次需求。只有匹配的 READY 工作流才能直接复用；没有时调用 workflow.prepare 创建完整工作流，再使用 Observation 返回的 workflowId 调用 workflow.run。
调用 workflow.prepare 时必须提交 workflow_spec，由你根据当前任务和工作区动态设计，而不是套用固定三节点模板。workflow_spec.version 固定为 1.0；每个节点必须给出唯一 key、type、业务名称、purpose、完整 config 和 depends_on。项目已有输入用 $inputs 表示；需要时可安排多个相互独立的 REF_SEARCH 或分析节点并行执行，再汇总到下游。只保留完成任务真正需要的步骤，通常 3-8 个节点，最多 12 个。
REF_SEARCH.config 必须含具体 query；AI_ANALYSIS.config 必须含完整 prompt，说明分析维度、证据要求和输出结构；AGENT_TASK.config 必须含 instruction；REVIEW.config 必须含 instructions；DELIVERABLE.config 必须含 generationPrompt、format、includeCitations、citationStyle，PPTX 使用 studio-professional-native。DATA_TRANSFORM 仅在项目已有结构化数据且任务确实需要加工时使用，config 至少包含 requirements 和 outputName。name 使用简短业务标题，goal 保留用户完整要求；不要把“本次不运行”或第一步做什么写进名称。
不得跳过工作流直接调用 deliverable.create。交付件必须由工作流输出节点生成，这样用户才能看见、调整和复用处理过程。
研究、分析、总结类任务如果用户没有明确指定成果格式，workflow.prepare 要默认包含 PPTX 输出节点，不要只在对话中返回长篇结论。
只有用户明确要求交互报告、自助分析、数据看板或图表报告时才能选择 FINANCIAL_REPORT，并且必须先通过项目上下文确认已有可读取的 CSV 或数据采集结果。
不要因为用户提到“分析”或“报告”就选择 FINANCIAL_REPORT，也不要为了生成交互报告而凭空创建数据集。
工具参数必须严格遵守 schema。失败时根据 failureType、retryable 和 recoveryHint 恢复：参数错误修参数，资料缺失先查找，版本冲突先重读，只有可重试的网络错误才可用同参数有限重试。
执行成功不等于任务完成。创建或编辑后必须检查对象存在、内容有效、关键要求完整，涉及研究与交付件时检查 citation/provenance。
工作流运行成功后会打开新成果，不要再调用 deliverable.open。若工具返回 taskComplete，立即结束任务。
workflow.run 返回后由运行时持续跟踪节点进度；不要重复调用 workflow.run，也不要把仍在运行误判为失败。
不要为了理解当前任务打开无关的历史交付件。生成成果失败时先根据明确错误修正参数或引用，不要原样重复创建。
用户按名称描述对象时，自行读取工作区并解析内部 ID，不得要求用户提供 workflow_id 等内部标识。
最终回答使用业务用户能读懂的语言，明确已完成内容、产出位置、重要来源和仍存在的限制。不暴露隐藏思维链，不宣称未验证的操作已完成。
""".strip()


def _interrupt_payload(result: dict[str, Any]) -> list[dict[str, Any]]:
    payloads = result.get("__interrupt__", [])
    actions: list[dict[str, Any]] = []
    for interrupt in payloads:
        value = getattr(interrupt, "value", interrupt)
        if not isinstance(value, dict):
            continue
        for action in value.get("action_requests", []):
            if isinstance(action, dict):
                actions.append({
                    "tool": action.get("name", "").replace("_", "."),
                    "toolName": action.get("name", ""),
                    "arguments": action.get("args", {}),
                    "description": action.get("description", ""),
                })
    return actions


async def run_continuous_agent_stream(request: ContinuousAgentRequest, model_override: Any = None):
    """Run or resume one checkpointed DeepAgents task against the governed Java gateway."""
    model = model_override or (custom_model(request.model_config_override) if request.model_config_override else _model())
    if model is None:
        raise RuntimeError("Agent 模型尚未配置")

    from deepagents import create_deep_agent
    from deepagents.backends import CompositeBackend, FilesystemBackend, StateBackend
    from langgraph.types import Command

    queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
    auto_mode = request.execution_mode.upper() == "AUTO"
    contract = _continuous_task_contract(request.goal)
    run_capability = next((item for item in request.capabilities if item.id == "workflow.run"), None)
    prepare_capability = next((item for item in request.capabilities if item.id == "workflow.prepare"), None)
    duplicate_capability = next((item for item in request.capabilities if item.id == "workflow.duplicate"), None)
    create_project_capability = next(
        (item for item in request.capabilities if item.id == "project.create_workspace"), None)
    if (auto_mode and _requests_project_creation(request.goal) and _is_workflow_business_task(request.goal)
            and not contract["no_run"] and not contract["read_only"]
            and create_project_capability is not None and prepare_capability is not None
            and run_capability is not None):
        original_goal = request.goal
        task_goal = _project_task_goal(original_goal)
        create_call_id = f"{request.run_id}:direct-project-create"
        create_arguments = {
            "project_name": _project_name_from_goal(original_goal),
            "topic": task_goal,
            "description": task_goal,
        }
        yield {"type": "thinking_summary", "status": "completed", "activity_id": "understanding",
               "message": "已识别为需要在新项目中沉淀并执行的业务分析任务", "progress": 8}
        yield {"type": "tool_call", "status": "running", "activity_id": create_call_id,
               "toolName": "project.create_workspace", "argumentSummary": _summarize_arguments(create_arguments),
               "message": "创建独立项目并切换工作区", "progress": 14}
        created = await _call_java_gateway(request, create_call_id, create_project_capability, create_arguments)
        yield {"type": "observation", "status": "completed" if created.get("success") else "failed",
               "activity_id": create_call_id, "toolName": "project.create_workspace",
               "resultSummary": created.get("result") or created.get("error") or "项目创建已返回",
               "message": created.get("result") or created.get("error") or "项目创建已返回",
               "output": created.get("output", {}), "provenance": created.get("provenance", {}),
               "failureType": created.get("failureType"), "retryable": created.get("retryable"),
               "recoveryHint": created.get("recoveryHint"), "progress": 18}
        if not created.get("success"):
            raise RuntimeError(str(created.get("error") or "项目创建失败"))
        created_output = created.get("output") or {}
        project_id = str(created_output.get("createdProjectId") or created_output.get("projectId") or "")
        if not project_id:
            raise RuntimeError("项目已创建但没有返回可继续使用的项目标识")
        request = request.model_copy(update={"project_id": project_id, "goal": task_goal, "resources": []})
        contract = _continuous_task_contract(task_goal)
    single_ready_workflow_id = _single_ready_workflow_id(request)
    ready_workflow_id = single_ready_workflow_id if auto_mode and contract["run_existing"] else None
    copy_name = _requested_workflow_copy_name(request.goal)
    if (auto_mode and contract["no_run"] and not contract["read_only"] and copy_name
            and request.project_id and single_ready_workflow_id and duplicate_capability is not None):
        call_id = f"{request.run_id}:direct-workflow-duplicate"
        arguments = {"project_id": request.project_id, "workflow_id": single_ready_workflow_id, "name": copy_name}
        yield {"type": "thinking_summary", "status": "completed", "activity_id": "understanding",
               "message": "已定位当前项目中唯一的可复用工作流", "progress": 12}
        yield {"type": "tool_call", "status": "running", "activity_id": call_id,
               "toolName": "workflow.duplicate", "argumentSummary": _summarize_arguments(arguments),
               "message": "复制工作流，不携带历史运行结果", "progress": 30}
        result = await _call_java_gateway(request, call_id, duplicate_capability, arguments)
        yield {"type": "observation", "status": "completed" if result.get("success") else "failed",
               "activity_id": call_id, "toolName": "workflow.duplicate",
               "resultSummary": result.get("result") or result.get("error") or "复制操作已返回",
               "message": result.get("result") or result.get("error") or "复制操作已返回",
               "output": result.get("output", {}), "provenance": result.get("provenance", {}), "progress": 96}
        if not result.get("success"):
            raise RuntimeError(str(result.get("error") or "工作流复制失败"))
        summary = str(result.get("completionSummary") or result.get("result") or "工作流已复制")
        yield {"type": "completed", "status": "completed", "content": summary,
               "message": summary, "mode": "deep-agents-continuous", "progress": 100}
        return
    if ready_workflow_id and run_capability is not None:
        yield {"type": "thinking_summary", "status": "completed", "activity_id": "understanding",
               "message": "已定位到当前项目唯一可运行的工作流", "progress": 12}
        yield {"type": "planning", "status": "completed", "activity_id": "planning",
               "message": "将直接运行已有流程并跟踪真实产出", "progress": 18}
        call_id = f"{request.run_id}:direct-workflow-run"
        arguments: dict[str, Any] = {
            "workflow_id": ready_workflow_id,
            "goal": request.goal,
        }
        if request.project_id:
            arguments["project_id"] = request.project_id
        output_formats = _requested_output_formats(request.goal)
        if output_formats:
            arguments["output_formats"] = output_formats
        yield {"type": "tool_call", "status": "running", "activity_id": call_id,
               "toolName": "workflow.run", "argumentSummary": _summarize_arguments(arguments),
               "message": "运行已有工作流", "progress": 22}
        result = await _call_java_gateway(request, call_id, run_capability, arguments)
        workflow_run_id = str((result.get("output") or {}).get("workflowRunId") or "")
        workflow_status = str((result.get("output") or {}).get("workflowRunStatus") or "")
        if result.get("success") and workflow_run_id and workflow_status in {
            "QUEUED", "RUNNING", "CANCEL_REQUESTED",
        }:
            async with httpx.AsyncClient(timeout=30) as client:
                wait_task = asyncio.create_task(
                    _wait_for_workflow_run(request, workflow_run_id, call_id, queue, client)
                )
                while not wait_task.done() or not queue.empty():
                    try:
                        yield await asyncio.wait_for(queue.get(), timeout=0.25)
                    except asyncio.TimeoutError:
                        continue
                result = await wait_task
        yield {"type": "observation", "status": "completed" if result.get("success") else "failed",
               "activity_id": call_id, "toolName": "workflow.run",
               "resultSummary": result.get("result") or result.get("error") or "工作流已返回",
               "message": result.get("result") or result.get("error") or "工作流已返回",
               "output": result.get("output", {}), "provenance": result.get("provenance", {}),
               "failureType": result.get("failureType"), "retryable": result.get("retryable"),
               "recoveryHint": result.get("recoveryHint"), "progress": 96}
        if result.get("success") and result.get("taskComplete"):
            summary = str(result.get("completionSummary") or result.get("result") or "工作流已完成")
            yield {"type": "completed", "status": "completed", "content": summary,
                   "message": summary, "mode": "deep-agents-continuous", "progress": 100}
            return
        if not result.get("success"):
            raise RuntimeError(str(result.get("error") or "工作流运行失败"))
    tools = build_continuous_workbench_tools(
        request,
        queue,
        direct_tool_ids=_continuous_direct_tool_ids(request) if auto_mode else None,
        include_discovery=auto_mode,
    )
    capabilities_by_name = {
        item.id.replace(".", "_").replace("-", "_"): item
        for item in request.capabilities if item.id != "assistant.respond"
    }
    interrupt_on = None
    if request.execution_mode.upper() != "AUTO":
        interrupt_on = {
            name: {"allowed_decisions": ["approve", "reject"], "description": capability.description}
            for name, capability in capabilities_by_name.items() if capability.mode != "READ"
        }
    skills_path = Path(settings.agent_skills_dir).resolve()
    backend = CompositeBackend(
        default=StateBackend(),
        routes={"/skills/": FilesystemBackend(root_dir=skills_path, virtual_mode=True)},
    )
    agent = create_deep_agent(
        model=model,
        tools=tools,
        system_prompt=_continuous_instructions(request),
        skills=["/skills/"],
        backend=backend,
        checkpointer=_agent_checkpointer(),
        interrupt_on=interrupt_on,
        name="finflow-continuous-agent",
    )
    config = {"configurable": {"thread_id": f"continuous:{request.run_id}"},
              "recursion_limit": max(30, request.max_tool_calls * 3)}
    if request.resume:
        decisions = [{"type": "approve"} for _ in range(max(1, request.approval_count))]
        agent_input: Any = Command(resume={"decisions": decisions})
    else:
        history = [message.model_dump() for message in request.recent_messages
                   if message.role in {"user", "assistant", "system"}]
        if not history or history[-1].get("role") != "user" or history[-1].get("content") != request.goal:
            history.append({"role": "user", "content": request.goal})
        agent_input = {"messages": history}

    await queue.put({"type": "thinking_summary", "status": "running", "activity_id": "understanding",
                     "message": "正在理解目标、检查当前工作区并选择处理方式", "progress": 8})
    await queue.put({"type": "skill_loading", "status": "completed", "activity_id": "skills",
                     "message": f"已发现 {len(load_skills(settings.agent_skills_dir))} 个可按需加载的业务技能", "progress": 12})
    await queue.put({"type": "planning", "status": "running", "activity_id": "planning",
                     "message": "正在选择所需资料和工具，计划会随真实结果动态调整", "progress": 15})
    invocation = asyncio.create_task(agent.ainvoke(agent_input, config=config))
    deadline = asyncio.get_running_loop().time() + max(1, request.timeout_seconds)
    last_activity_at = asyncio.get_running_loop().time()
    completion_summary: str | None = None
    called_tool_ids: set[str] = set()
    result: dict[str, Any] = {}
    try:
        for attempt in range(2):
            while not invocation.done() or not queue.empty():
                if asyncio.get_running_loop().time() >= deadline:
                    raise asyncio.TimeoutError
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=0.25)
                    if event.get("type") == "_task_complete":
                        completion_summary = str(event.get("content") or "任务已完成")
                        invocation.cancel()
                        break
                    if event.get("type") == "tool_call" and event.get("toolName"):
                        called_tool_ids.add(str(event["toolName"]))
                    yield event
                    last_activity_at = asyncio.get_running_loop().time()
                except asyncio.TimeoutError:
                    now = asyncio.get_running_loop().time()
                    if now - last_activity_at >= 10:
                        yield {
                            "type": "heartbeat", "status": "running",
                            "activity_id": "agent-heartbeat",
                        }
                        last_activity_at = now
                    continue
            if completion_summary is not None:
                try:
                    await invocation
                except asyncio.CancelledError:
                    pass
                yield {"type": "generating", "status": "completed", "activity_id": "deterministic-finish",
                       "message": "成果已经验证，正在完成任务", "progress": 98}
                yield {"type": "completed", "status": "completed", "content": completion_summary,
                       "message": completion_summary, "mode": "deep-agents-continuous", "progress": 100}
                return
            result = await invocation
            contract = _continuous_task_contract(request.goal)
            missing_required_run = (
                auto_mode and contract["requires_run"] and "workflow.run" not in called_tool_ids
                and not _interrupt_payload(result)
            )
            if not missing_required_run or attempt == 1:
                break
            yield {
                "type": "retrying", "status": "running", "activity_id": "required-workflow-run",
                "message": "刚才没有真正运行流程，正在继续定位并执行", "progress": 24,
            }
            invocation = asyncio.create_task(agent.ainvoke({"messages": [{
                "role": "user",
                "content": (
                    "刚才没有任何 workflow.run 的真实调用，不能把“已创建或已编辑流程”当成任务完成。"
                    "如果前一步已返回 workflowId，直接用 workflow_run 执行；否则从当前工作区定位匹配流程。"
                    "必要时先调用 workspace_inspect，不得只输出计划或 JSON。"
                ),
            }]}, config=config))
    except asyncio.TimeoutError as exception:
        invocation.cancel()
        raise RuntimeError(f"Agent 超过 {request.timeout_seconds} 秒执行上限，已保留完成的操作") from exception

    if auto_mode and _continuous_task_contract(request.goal)["requires_run"] and "workflow.run" not in called_tool_ids:
        raise RuntimeError("未能真正运行本次任务所需的工作流，本次任务不会误报为完成")

    actions = _interrupt_payload(result)
    if actions:
        yield {"type": "waiting_confirmation", "status": "waiting", "actions": actions,
               "approvalCount": len(actions), "message": f"有 {len(actions)} 个写入操作等待确认", "progress": 50}
        return
    messages = result.get("messages", [])
    content = getattr(messages[-1], "content", "") if messages else ""
    if not isinstance(content, str):
        content = json.dumps(content, ensure_ascii=False, default=str)
    try:
        final_value = json.loads(content)
        if isinstance(final_value, dict):
            final_value = final_value.get("final", final_value)
            if isinstance(final_value, dict) and isinstance(final_value.get("summary"), str):
                content = final_value["summary"]
    except json.JSONDecodeError:
        pass
    yield {"type": "completed", "status": "completed", "content": content,
           "message": content, "mode": "deep-agents-continuous", "progress": 100}

async def run_open_task_stream(request: OpenTaskRequest, model_override: Any = None):
    """Run one governed open task and expose business-readable activity events."""
    model = model_override or _model()
    if model is None:
        yield {"type": "thinking_summary", "activity_id": "gateway-thinking", "status": "running",
               "message": "正在读取任务目标与上游内容", "progress": 8}
        yield {"type": "planning", "activity_id": "gateway-plan", "status": "completed",
               "message": "已准备本地受控执行计划", "progress": 10}
        used_sources: list[dict[str, Any]] = []
        source_context = request.source_context.strip()
        if llm.configured and request.policy.external_research in {"PUBLIC_READ", "DOMAIN_ALLOWLIST"}:
            yield {"type": "tool_search", "activity_id": "gateway-search", "status": "running",
                   "toolName": "web.search", "message": "正在查找可采用的公开资料", "progress": 20}
            discovery = await llm.discover_sources(request.task, min(6, request.policy.max_tool_calls))
            for index, source in enumerate(discovery.get("sources", [])):
                try:
                    snapshot = await fetch_web(str(source.get("url", "")), request.policy.domain_allowlist)
                    used_sources.append(snapshot)
                    source_context += "\n\n[公开资料 %d] %s\n%s" % (
                        index + 1, snapshot["url"], snapshot["text"][:20_000])
                    yield {"type": "observation", "activity_id": f"gateway-fetch-{index + 1}",
                           "status": "completed", "toolName": "web.snapshot", "message": f"已采用来源：{snapshot['title']}",
                           "resultSummary": snapshot["title"],
                           "output": {"url": snapshot["url"], "title": snapshot["title"]},
                           "provenance": {"url": snapshot["url"]}, "progress": 35 + index * 5}
                except (RuntimeError, ValueError):
                    continue
        system = """你是 FinBTP Studio 的个人工作 Agent。严格基于给定内容完成任务，区分事实、计算、推断和不确定项。
不得编造数据与来源；引用公开资料时标明对应 URL。输出结构清晰、可直接进入后续报告或工作流步骤。"""
        prompt = "任务：%s\n\n项目内容：\n%s" % (request.task.strip(), source_context[:120_000])
        result = await llm.complete(system, prompt) if llm.configured else None
        if not result:
            result = f"任务目标：{request.task.strip()}\n\n{(source_context or request.task.strip())[:12000]}"
        yield {"type": "generating", "activity_id": "gateway-generating", "status": "running",
               "message": "正在整理最终结果", "progress": 70}
        for offset in range(0, len(result), 240):
            yield {"type": "content", "content": result[offset:offset + 240], "progress": min(95, 20 + offset // 240)}
        yield {"type": "completed", "content": result,
               "mode": "model-gateway" if llm.configured else "local-governed",
               "used_sources": used_sources, "progress": 100}
        return

    from deepagents import create_deep_agent
    from deepagents.backends import StateBackend
    from langchain_core.tools import tool

    queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
    used_sources: list[dict[str, Any]] = []
    tool_calls = 0

    async def announce(event: dict[str, Any]) -> None:
        await queue.put(event)

    @tool
    def inspect_project_context(query: str = "") -> dict[str, Any]:
        """Read the task's project resources and supplied upstream content without changing them."""
        nonlocal tool_calls
        tool_calls += 1
        return {"query": query, "resources": [item.model_dump() for item in request.resources],
                "content": request.source_context[:120_000]}

    @tool
    def find_task_skills(query: str) -> list[dict[str, str]]:
        """Find optional reusable skills relevant to this task."""
        nonlocal tool_calls
        tool_calls += 1
        all_skills = load_skills(settings.agent_skills_dir)
        requested = set(request.skills)
        terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
        ranked = sorted(all_skills, key=lambda item: (item.name in requested,
                        sum(term in (item.name + item.description).lower() for term in terms)), reverse=True)
        return [{"name": item.name, "description": item.description,
                 "instructions": item.instructions[:8000]} for item in ranked[:4]]

    @tool
    async def search_public_web(query: str, limit: int = 8) -> list[dict[str, str]]:
        """Search public web pages when this task's network policy permits public research."""
        nonlocal tool_calls
        tool_calls += 1
        activity_id = f"web-search-{tool_calls}"
        if request.policy.external_research not in {"PUBLIC_READ", "DOMAIN_ALLOWLIST"}:
            return [{"status": "blocked", "message": "本次任务未允许公开联网研究"}]
        await announce({"type": "tool_call", "activity_id": activity_id, "status": "running",
                        "toolName": "web.search", "message": f"正在搜索：{query}", "progress": 25})
        try:
            result = await search_web(query, limit)
        except SearchProviderError as exception:
            await announce({"type": "observation", "activity_id": activity_id, "status": "failed",
                            "toolName": "web.search", "message": str(exception),
                            "resultSummary": "联网搜索服务不可用", "error": str(exception),
                            "progress": 35})
            return [{"status": "unavailable", "message": str(exception)}]
        await announce({"type": "observation", "activity_id": activity_id, "status": "completed",
                        "toolName": "web.search", "message": f"找到 {len(result)} 个候选来源",
                        "resultSummary": f"{len(result)} 个候选来源", "progress": 35})
        return result

    @tool
    async def read_public_web_page(url: str) -> dict[str, Any]:
        """Read and snapshot one public web page after a search result has been selected."""
        nonlocal tool_calls
        tool_calls += 1
        if request.policy.external_research not in {"PUBLIC_READ", "DOMAIN_ALLOWLIST"}:
            return {"status": "blocked", "message": "本次任务未允许公开联网研究"}
        activity_id = f"web-fetch-{tool_calls}"
        await announce({"type": "tool_call", "activity_id": activity_id, "status": "running",
                        "toolName": "web.fetch", "message": "正在读取公开网页", "progress": 40})
        result = await fetch_web(url, request.policy.domain_allowlist)
        used_sources.append(result)
        await announce({"type": "observation", "activity_id": activity_id, "status": "completed",
                        "toolName": "web.snapshot", "message": f"已采用来源：{result['title']}",
                        "resultSummary": result["title"],
                        "output": {"url": result["url"], "title": result["title"]},
                        "provenance": {"url": result["url"]}, "progress": 55})
        return {**result, "text": result["text"][:40_000]}

    tools = [inspect_project_context, find_task_skills, search_public_web, read_public_web_page]
    subagents = [
        {"name": "resource-researcher", "description": "搜索、筛选和阅读资料，保留采用来源", "tools": tools,
         "system_prompt": "只做只读资料研究，区分已采用来源和候选来源。"},
        {"name": "data-analyst", "description": "分析提供的数据和资料，检查口径与不确定项", "tools": tools[:2],
         "system_prompt": "基于实际提供的内容分析，不编造数据。"},
        {"name": "deliverable-designer", "description": "把分析整理成结构清晰的成果内容", "tools": tools[:2],
         "system_prompt": "保持结论、依据、限制和建议之间的结构。"},
    ]
    prompt = """你是 FinBTP Studio 的个人工作 Agent。任务目标可以开放，但所有能力调用必须遵守策略。
先检查项目上下文和可用 Skill，再决定是否委派子任务。只有策略允许时才能联网。
不得访问主机文件、执行 shell、读取密钥或写入外部系统。不得编造数据和来源。
最终使用中文输出，明确区分事实、计算、推断和不确定项；用到网页时必须先调用读取网页工具。
任务：%s""" % request.task
    agent = create_deep_agent(model=model, tools=tools, subagents=subagents, system_prompt=prompt,
                              backend=StateBackend(), checkpointer=False, name="finbtp-open-task")

    async def invoke() -> dict[str, Any]:
        return await asyncio.wait_for(agent.ainvoke({"messages": [{"role": "user", "content": request.task}]}),
                                      timeout=min(request.policy.timeout_seconds, settings.agent_task_timeout_seconds))

    task = asyncio.create_task(invoke())
    yield {"type": "thinking_summary", "activity_id": "agent-thinking", "status": "running",
           "message": "Agent 正在理解目标和可用上下文", "progress": 6}
    yield {"type": "planning", "activity_id": "agent-plan", "status": "running",
           "toolName": "agent.plan", "message": "Agent 正在规划处理步骤", "progress": 8}
    while not task.done():
        if tool_calls > min(request.policy.max_tool_calls, settings.agent_max_tool_calls):
            task.cancel()
            raise RuntimeError("Agent 工具调用超过本次任务预算")
        try:
            event = await asyncio.wait_for(queue.get(), timeout=0.5)
            yield event
        except asyncio.TimeoutError:
            continue
    result = await task
    while not queue.empty():
        yield queue.get_nowait()
    messages = result.get("messages", [])
    content = ""
    if messages:
        raw = getattr(messages[-1], "content", "")
        content = raw if isinstance(raw, str) else json.dumps(raw, ensure_ascii=False)
    for offset in range(0, len(content), 240):
        yield {"type": "content", "content": content[offset:offset + 240], "progress": min(96, 70 + offset // 240)}
    yield {"type": "generating", "activity_id": "agent-generating", "status": "running",
           "message": "正在整理最终结果", "progress": 96}
    yield {"type": "completed", "content": content, "mode": "deep-agents",
           "used_sources": used_sources, "tool_calls": tool_calls, "progress": 100}
