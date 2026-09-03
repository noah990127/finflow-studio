import json
import asyncio
import re
from dataclasses import dataclass, field
from typing import Any, Optional

from pydantic import BaseModel, Field

from ..config import settings
from ..llm import llm
from ..research import fetch_web, search_web
from ..skills.loader import Skill, load_skills


class AgentCapability(BaseModel):
    id: str
    title: str
    description: str
    mode: str
    risk: str
    arguments: list[str] = Field(default_factory=list)


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
    goal: str
    page: str
    project_id: Optional[str] = None
    project_name: str = "当前项目"
    selection: dict[str, Any] = Field(default_factory=dict)
    resources: list[AgentResource] = Field(default_factory=list)
    recent_messages: list[AgentMessage] = Field(default_factory=list)
    capabilities: list[AgentCapability]


class AgentAction(BaseModel):
    tool: str
    title: str
    description: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class AgentDecision(BaseModel):
    summary: str
    intent: str
    selected_skills: list[str] = Field(default_factory=list)


class AgentPlanResponse(BaseModel):
    summary: str
    intent: str
    selected_skills: list[str] = Field(default_factory=list)
    steps: list[AgentAction]
    mode: str


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


@dataclass
class AgentDependencies:
    request: AgentPlanRequest
    skills: list[Skill]
    staged_actions: list[AgentAction] = field(default_factory=list)

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
    return None


async def plan_with_agent(request: AgentPlanRequest, model_override: Any = None) -> Optional[AgentPlanResponse]:
    if not settings.agent_enabled or (not llm.configured and model_override is None):
        return None
    model = model_override or _model()
    if model is None:
        return await _plan_with_gateway(request)

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
            "selection": item.selection,
            "resources": [resource.model_dump() for resource in item.resources],
            "recent_messages": [message.model_dump() for message in item.recent_messages],
        }

    @tool
    def find_skills(query: str) -> list[dict[str, str]]:
        """Find reusable work instructions relevant to the user's goal before choosing actions."""
        terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
        ranked = sorted(deps.skills, key=lambda skill: sum(term in (skill.name + skill.description + skill.instructions).lower() for term in terms), reverse=True)
        return [skill.model_dump() for skill in ranked[:4]]

    @tool
    def inspect_capabilities() -> list[dict[str, Any]]:
        """List every workbench operation available for the current run."""
        return [capability.model_dump() for capability in deps.request.capabilities]

    @tool
    def stage_workbench_action(tool: str, title: str,
                               description: str, arguments: dict[str, Any]) -> str:
        """Add one workbench action to the proposed plan. This stages but never executes the action."""
        allowed = {capability.id for capability in deps.request.capabilities}
        if tool not in allowed:
            return "不可用的能力，请重新查看能力目录"
        if len(deps.staged_actions) >= settings.agent_max_steps:
            return "计划步骤已达到上限"
        deps.staged_actions.append(AgentAction(tool=tool, title=title, description=description, arguments=arguments))
        return "已加入计划"

    instructions = """你是通用的个人工作台 Agent，不是固定的财经工作流模板。
先理解用户真正想完成的事情和当前上下文，再自主选择 Skill、MCP 工具与工作台能力。
必须先调用 inspect_workspace、find_skills 和 inspect_capabilities；需要外部只读信息时可调用管理员配置的 MCP。
每个准备在工作台执行的动作都必须调用 stage_workbench_action，禁止捏造能力名称。
不要因为用户提到分析就自动创建财务报告，也不要在缺少结构化数据时创建数据加工或图表报告。
读取、回答和导航可直接执行；创建、修改、删除、导出和外部写入只能形成待确认计划。
用业务用户看得懂的中文描述计划，不暴露内部技术名词。"""
    agent = create_deep_agent(
        model=model,
        tools=[inspect_workspace, find_skills, inspect_capabilities, stage_workbench_action],
        system_prompt=instructions,
        response_format=AgentDecision,
        backend=StateBackend(),
        checkpointer=True,
        name="finbtp-workbench-agent",
    )
    result = await agent.ainvoke(
        {"messages": [{"role": "user", "content": request.goal}]},
        config={"configurable": {"thread_id": f"plan:{request.project_id or 'personal'}"}},
    )
    decision = result.get("structured_response")
    if not isinstance(decision, AgentDecision):
        decision = AgentDecision.model_validate(decision or {
            "summary": "已根据当前工作台准备处理计划",
            "intent": "workbench-task",
            "selected_skills": [],
        })
    if not deps.staged_actions:
        deps.staged_actions.append(AgentAction(tool="assistant.respond", title="回答你的问题",
                                               description="结合当前项目和内容给出直接回答", arguments={"goal": request.goal}))
    return AgentPlanResponse(summary=decision.summary, intent=decision.intent,
                             selected_skills=decision.selected_skills,
                             steps=deps.staged_actions, mode="deep-agents")


async def _plan_with_gateway(request: AgentPlanRequest) -> Optional[AgentPlanResponse]:
    """Keep Codex CLI usable locally; API deployments use the full tool-calling agent above."""
    skills = load_skills(settings.agent_skills_dir)
    system = """你是个人工作台 Agent。根据上下文和能力目录制定计划，不使用关键词模板。
只选择能力目录中存在的 tool。缺少输入时先回答或检查，不得臆造报表。输出严格 JSON：
{"summary":"...","intent":"...","selected_skills":["..."],"steps":[{"tool":"...","title":"...","description":"...","arguments":{}}]}"""
    payload = request.model_dump()
    payload["skills"] = [{"name": item.name, "description": item.description, "instructions": item.instructions} for item in skills]
    raw = await llm.complete(system, json.dumps(payload, ensure_ascii=False))
    if not raw:
        return None
    try:
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        value = json.loads(match.group(0) if match else raw)
        return AgentPlanResponse.model_validate({**value, "mode": "model-planner"})
    except (ValueError, TypeError, json.JSONDecodeError):
        return None


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
        result = await search_web(query, limit)
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
