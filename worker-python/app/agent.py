import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from pydantic import BaseModel, Field

from .config import settings
from .llm import llm


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


class Skill(BaseModel):
    name: str
    description: str
    instructions: str


@dataclass
class AgentDependencies:
    request: AgentPlanRequest
    skills: list[Skill]
    staged_actions: list[AgentAction] = field(default_factory=list)


def load_skills(directory: str) -> list[Skill]:
    root = Path(directory)
    if not root.is_absolute():
        root = Path(__file__).resolve().parent.parent / root
    if not root.is_dir():
        return []
    result: list[Skill] = []
    for path in sorted(root.glob("**/SKILL.md")):
        text = path.read_text(encoding="utf-8")
        name = path.parent.name
        description = ""
        body = text
        if text.startswith("---"):
            parts = text.split("---", 2)
            if len(parts) == 3:
                metadata, body = parts[1], parts[2]
                for line in metadata.splitlines():
                    key, _, value = line.partition(":")
                    if key.strip() == "name" and value.strip():
                        name = value.strip().strip('"\'')
                    if key.strip() == "description" and value.strip():
                        description = value.strip().strip('"\'')
        if not description:
            description = next((line.lstrip("# ").strip() for line in body.splitlines() if line.strip()), name)
        result.append(Skill(name=name, description=description, instructions=body.strip()[:8000]))
    return result


def _model():
    from pydantic_ai.models.openai import OpenAIChatModel, OpenAIResponsesModel
    from pydantic_ai.providers.openai import OpenAIProvider

    if llm.provider == "codex" and settings.openai_api_key:
        return OpenAIResponsesModel(
            settings.openai_model,
            provider=OpenAIProvider(base_url=settings.openai_base_url, api_key=settings.openai_api_key),
        )
    if llm.provider == "deepseek" and settings.deepseek_api_key:
        return OpenAIChatModel(
            settings.deepseek_chat_model,
            provider=OpenAIProvider(base_url=settings.deepseek_base_url, api_key=settings.deepseek_api_key),
        )
    return None


def _mcp_toolsets() -> list[Any]:
    if not settings.agent_mcp_config.strip():
        return []
    path = Path(settings.agent_mcp_config).expanduser().resolve()
    if not path.is_file():
        raise RuntimeError("Agent MCP 配置文件不存在")
    # This file is deployment-owned: MCP stdio definitions may start processes.
    allowed = {item.strip() for item in settings.agent_mcp_allowed_tools.split(",") if item.strip()}
    if not allowed:
        raise RuntimeError("已配置 MCP，但尚未设置只读工具白名单")
    from pydantic_ai.mcp import load_mcp_toolsets
    return [toolset.filtered(lambda ctx, tool_def: tool_def.name in allowed)
            for toolset in load_mcp_toolsets(path)]


async def plan_with_agent(request: AgentPlanRequest, model_override: Any = None) -> Optional[AgentPlanResponse]:
    if not settings.agent_enabled or (not llm.configured and model_override is None):
        return None
    model = model_override or _model()
    if model is None:
        return await _plan_with_gateway(request)

    from pydantic_ai import Agent, FunctionToolset, RunContext

    deps = AgentDependencies(request=request, skills=load_skills(settings.agent_skills_dir))
    tools = FunctionToolset[AgentDependencies]()

    @tools.tool
    def inspect_workspace(ctx: RunContext[AgentDependencies]) -> dict[str, Any]:
        """Read the active project, current selection, resources, and recent conversation."""
        item = ctx.deps.request
        return {
            "project": {"id": item.project_id, "name": item.project_name, "page": item.page},
            "selection": item.selection,
            "resources": [resource.model_dump() for resource in item.resources],
            "recent_messages": [message.model_dump() for message in item.recent_messages],
        }

    @tools.tool
    def find_skills(ctx: RunContext[AgentDependencies], query: str) -> list[dict[str, str]]:
        """Find reusable work instructions relevant to the user's goal before choosing actions."""
        terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
        ranked = sorted(ctx.deps.skills, key=lambda skill: sum(term in (skill.name + skill.description + skill.instructions).lower() for term in terms), reverse=True)
        return [skill.model_dump() for skill in ranked[:4]]

    @tools.tool
    def inspect_capabilities(ctx: RunContext[AgentDependencies]) -> list[dict[str, Any]]:
        """List every workbench operation available for the current run."""
        return [capability.model_dump() for capability in ctx.deps.request.capabilities]

    @tools.tool
    def stage_workbench_action(ctx: RunContext[AgentDependencies], tool: str, title: str,
                               description: str, arguments: dict[str, Any]) -> str:
        """Add one workbench action to the proposed plan. This stages but never executes the action."""
        allowed = {capability.id for capability in ctx.deps.request.capabilities}
        if tool not in allowed:
            return "不可用的能力，请重新查看能力目录"
        if len(ctx.deps.staged_actions) >= settings.agent_max_steps:
            return "计划步骤已达到上限"
        ctx.deps.staged_actions.append(AgentAction(tool=tool, title=title, description=description, arguments=arguments))
        return "已加入计划"

    instructions = """你是通用的个人工作台 Agent，不是固定的财经工作流模板。
先理解用户真正想完成的事情和当前上下文，再自主选择 Skill、MCP 工具与工作台能力。
必须先调用 inspect_workspace、find_skills 和 inspect_capabilities；需要外部只读信息时可调用管理员配置的 MCP。
每个准备在工作台执行的动作都必须调用 stage_workbench_action，禁止捏造能力名称。
不要因为用户提到分析就自动创建财务报告，也不要在缺少结构化数据时创建数据加工或图表报告。
读取、回答和导航可直接执行；创建、修改、删除、导出和外部写入只能形成待确认计划。
用业务用户看得懂的中文描述计划，不暴露内部技术名词。"""
    agent = Agent(model, deps_type=AgentDependencies, output_type=AgentDecision,
                  instructions=instructions, toolsets=[tools, *_mcp_toolsets()])
    result = await agent.run(request.goal, deps=deps)
    if not deps.staged_actions:
        deps.staged_actions.append(AgentAction(tool="assistant.respond", title="回答你的问题",
                                               description="结合当前项目和内容给出直接回答", arguments={"goal": request.goal}))
    return AgentPlanResponse(summary=result.output.summary, intent=result.output.intent,
                             selected_skills=result.output.selected_skills,
                             steps=deps.staged_actions, mode="pydantic-ai-agent")


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
