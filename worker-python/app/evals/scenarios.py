from __future__ import annotations

import random
import re
from collections import Counter
from dataclasses import dataclass, field
from typing import Iterable, Sequence

from ..tools.contracts import ToolDefinition


_PERSONAS = (
    "第一次使用平台的业务人员",
    "熟悉项目但不关心技术细节的分析师",
    "正在整理多人交接材料的项目负责人",
    "只用简短口语下指令的管理者",
    "会在执行中不断补充条件的研究人员",
)

_STYLES = ("direct", "goal", "contextual", "terse", "constrained")

_OPENERS = ("麻烦", "现在", "请", "帮我", "我需要你")

_BOUNDARIES = (
    "其他内容先不要改。",
    "做完告诉我实际发生了什么。",
    "不要假设不存在的数据。",
    "沿用当前会话里的要求。",
    "有重名时先根据上下文判断。",
)

_OBJECT_WORDS = {
    "project": ("项目", "工作空间", "刚才那个项目"),
    "folder": ("文件夹", "目录", "左边那个分类"),
    "resource": ("资料", "文件", "刚刚打开的内容"),
    "knowledge": ("知识库内容", "参考材料", "已有证据"),
    "dataset": ("数据", "数据集", "当前这份表"),
    "workflow": ("流程", "工作流", "项目里的处理流程"),
    "deliverable": ("成果", "交付文件", "刚生成的输出"),
    "workspace": ("工作区", "当前页面", "左侧内容"),
    "assistant": ("当前内容", "这件事", "我的问题"),
}

_MULTI_STEP_TOOLS = {
    "project.create_workspace": ("folder.create", "project.open"),
    "folder.create": ("folder.rename",),
    "resource.add": ("resource.open", "knowledge.add"),
    "resource.upload": ("resource.read", "knowledge.parse"),
    "resource.read": ("knowledge.add", "deliverable.create"),
    "knowledge.search": ("knowledge.read",),
    "knowledge.extract_table": ("dataset.open", "dataset.transform"),
    "dataset.create": ("dataset.query", "dataset.open", "dataset.transform"),
    "dataset.import": ("dataset.query", "dataset.open"),
    "workflow.prepare": ("workflow.add_node", "workflow.open"),
    "workflow.add_node": ("workflow.connect", "workflow.save_version"),
    "workflow.run": ("deliverable.open",),
    "deliverable.create": ("deliverable.open", "deliverable.export"),
    "workspace.inspect": ("workspace.navigate", "workspace.select"),
}

_TARGET_NAMES = {
    "project": "客户交接",
    "folder": "待复核",
    "resource": "项目说明",
    "knowledge": "合同条款",
    "dataset": "业务明细",
    "workflow": "项目处理流程",
    "deliverable": "阶段成果",
}

_INTERNAL_ID = re.compile(
    r"\b(?:project|folder|resource|dataset|workflow|deliverable|citation|node)_id\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class NaturalLanguageScenario:
    id: str
    seed: int
    persona: str
    style: str
    turns: tuple[str, ...]
    expected_tools: frozenset[str]
    expected_categories: frozenset[str]
    execution_mode: str
    mutates_workspace: bool
    write_tools: frozenset[str] = field(default_factory=frozenset)
    requires_recovery: bool = False


@dataclass(frozen=True)
class TraceEvent:
    type: str
    status: str = ""
    tool_name: str = ""
    message: str = ""
    error_code: str = ""


@dataclass(frozen=True)
class AgentTrace:
    events: tuple[TraceEvent, ...]
    assistant_messages: tuple[str, ...] = ()
    workspace_changed: bool = False


@dataclass(frozen=True)
class EvaluationFinding:
    code: str
    message: str


@dataclass(frozen=True)
class EvaluationResult:
    passed: bool
    score: float
    findings: tuple[EvaluationFinding, ...] = field(default_factory=tuple)


class NaturalLanguageScenarioGenerator:
    """Build held-out prompts from the tool manifest instead of named demo tasks."""

    def __init__(self, tools: Sequence[ToolDefinition], seed: int = 0) -> None:
        if not tools:
            raise ValueError("At least one tool is required")
        self.tools = tuple(tools)
        self.seed = seed

    def generate(self, count: int = 40) -> list[NaturalLanguageScenario]:
        if count < 1:
            return []
        rng = random.Random(self.seed)
        result: list[NaturalLanguageScenario] = []
        shuffled = list(self.tools)
        rng.shuffle(shuffled)

        # Put every capability into the pool before repeating. This prevents a
        # small set of attractive examples from dominating an evaluation run.
        for index in range(count):
            primary = shuffled[index % len(shuffled)]
            style = _STYLES[index % len(_STYLES)]
            scenario_seed = rng.randrange(1, 2**31)
            local = random.Random(scenario_seed)
            partner = self._partner(primary, local) if index % 4 == 3 else None
            if partner is None and index >= len(shuffled) and index % 4 == 3:
                composable = [tool for tool in self.tools if self._partner(tool, local) is not None]
                if composable:
                    primary = local.choice(composable)
                    partner = self._partner(primary, local)
            turns = [self._prompt(primary, style, local)]
            expected = {primary.id}
            categories = {primary.category}

            if partner is not None:
                turns[0] = self._combined_prompt(primary, partner, local)
                expected.add(partner.id)
                categories.add(partner.category)

            if index % 5 == 4:
                turns.append(local.choice((
                    "补充一下，只处理我刚才说的对象，其他内容保持不变。",
                    "继续，不过结果名称改成“复核版”，前面的要求都保留。",
                    "先别重复已经完成的步骤，接着刚才的进度处理。",
                )))

            result.append(NaturalLanguageScenario(
                id=f"generated-{self.seed}-{index + 1}",
                seed=scenario_seed,
                persona=_PERSONAS[index % len(_PERSONAS)],
                style=style,
                turns=tuple(turns),
                expected_tools=frozenset(expected),
                expected_categories=frozenset(categories),
                execution_mode="AUTO" if index % 2 == 0 else "APPROVAL",
                mutates_workspace=any(tool.risk != "read" for tool in self.tools if tool.id in expected),
                write_tools=frozenset(tool.id for tool in self.tools if tool.id in expected and tool.risk != "read"),
                requires_recovery=index % 7 == 6,
            ))
        return result

    def _prompt(self, tool: ToolDefinition, style: str, rng: random.Random) -> str:
        object_word = rng.choice(_OBJECT_WORDS.get(tool.category, ("内容", "对象")))
        action = tool.description.rstrip("。")
        target = _TARGET_NAMES.get(tool.category)
        target_clause = f"，对象叫“{target}”" if target else ""
        opener = rng.choice(_OPENERS)
        boundary = rng.choice(_BOUNDARIES)
        if style == "direct":
            return f"{opener}{action}{target_clause}，完成后把结果直接打开。{boundary}"
        if style == "goal":
            return f"我想把{object_word}处理好：{action}{target_clause}。你自己判断要用哪些功能。{boundary}"
        if style == "contextual":
            return f"就按当前页面和我选中的{object_word}继续，{action}，不用问我内部编号。{boundary}"
        if style == "terse":
            return f"{tool.title}一下{target_clause}。{boundary}"
        return f"{opener}{action}{target_clause}；先检查现有内容，避免重复，并保留可追溯的处理记录。{boundary}"

    def _partner(self, primary: ToolDefinition, rng: random.Random) -> ToolDefinition | None:
        partner_ids = set(_MULTI_STEP_TOOLS.get(primary.id, ()))
        candidates = [tool for tool in self.tools if tool.id in partner_ids]
        return rng.choice(candidates) if candidates else None

    def _combined_prompt(self, first: ToolDefinition, second: ToolDefinition, rng: random.Random) -> str:
        connector = rng.choice(("然后", "接着", "确认没问题后再", "处理完以后"))
        first_target = _TARGET_NAMES.get(first.category, "当前对象")
        second_target = _TARGET_NAMES.get(second.category, first_target)
        return (
            f"先{first.description.rstrip('。')}，对象叫“{first_target}”，{connector}"
            f"{second.description.rstrip('。')}“{second_target}”。系统对象由你根据名称和当前工作区解析，"
            "不要让我提供内部 ID。"
            f"{rng.choice(_BOUNDARIES)}"
        )


def evaluate_trace(scenario: NaturalLanguageScenario, trace: AgentTrace) -> EvaluationResult:
    findings: list[EvaluationFinding] = []
    tool_events = [event for event in trace.events if event.type == "tool_call"]
    called_tools = {event.tool_name for event in tool_events}
    successful_tools = {
        event.tool_name for event in trace.events
        if event.type == "observation" and event.status == "completed" and event.tool_name
    }
    failed_tools = {
        event.tool_name for event in trace.events
        if event.type == "observation" and event.status == "failed" and event.tool_name
    }
    call_counts = Counter(event.tool_name for event in tool_events if event.tool_name)
    observation_counts = Counter(
        event.tool_name for event in trace.events if event.type == "observation" and event.tool_name
    )

    missing = scenario.expected_tools - called_tools
    if missing:
        findings.append(EvaluationFinding("missing-capability", f"没有调用预期能力：{', '.join(sorted(missing))}"))

    if any(_INTERNAL_ID.search(message) for message in trace.assistant_messages):
        findings.append(EvaluationFinding("leaked-internal-id", "Agent 要求业务用户提供内部 ID"))

    if scenario.execution_mode == "AUTO" and any(event.type == "waiting_confirmation" for event in trace.events):
        findings.append(EvaluationFinding("unexpected-confirmation", "Auto 模式出现了人工确认"))

    if scenario.execution_mode == "APPROVAL" and scenario.mutates_workspace:
        write_tools = scenario.write_tools or scenario.expected_tools
        first_write = next((index for index, event in enumerate(trace.events)
                            if event.type == "tool_call" and event.tool_name in write_tools), None)
        confirmation = next((index for index, event in enumerate(trace.events)
                             if event.type == "waiting_confirmation"), None)
        if confirmation is None or (first_write is not None and confirmation > first_write):
            findings.append(EvaluationFinding("missing-confirmation", "审批模式没有在写操作前暂停"))

    if scenario.mutates_workspace and successful_tools.intersection(scenario.expected_tools) and not trace.workspace_changed:
        findings.append(EvaluationFinding("no-state-change", "工具声称成功，但工作区真实状态没有变化"))

    completed = any(event.type == "completed" for event in trace.events)
    unresolved_failures = failed_tools - successful_tools
    if completed and unresolved_failures:
        findings.append(EvaluationFinding("false-completion", "仍有失败工具时 Agent 宣布了完成"))

    if scenario.requires_recovery and failed_tools:
        recovered = any(event.type in {"retrying", "plan_updated"} for event in trace.events)
        if not recovered:
            findings.append(EvaluationFinding("missing-recovery", "收到结构化失败后没有重试或调整计划"))

    missing_observations = sorted(
        tool_name for tool_name, count in call_counts.items() if observation_counts[tool_name] < count
    )
    if missing_observations:
        findings.append(EvaluationFinding(
            "missing-observation",
            f"工具调用没有对应 Observation：{', '.join(missing_observations)}",
        ))

    checks = 7
    score = max(0.0, (checks - len(findings)) / checks)
    return EvaluationResult(passed=not findings, score=score, findings=tuple(findings))


def category_coverage(scenarios: Iterable[NaturalLanguageScenario]) -> set[str]:
    return {category for scenario in scenarios for category in scenario.expected_categories}
