from app.evals.scenarios import (
    AgentTrace,
    NaturalLanguageScenario,
    NaturalLanguageScenarioGenerator,
    TraceEvent,
    category_coverage,
    evaluate_trace,
)
from app.tools.contracts import ToolDefinition


def tool(tool_id: str, category: str, risk: str = "read") -> ToolDefinition:
    return ToolDefinition(
        id=tool_id,
        category=category,
        title=f"处理{category}",
        description=f"对当前{category}对象执行{tool_id.split('.')[-1]}操作",
        risk=risk,
        requires_confirmation=risk != "read",
    )


def catalog() -> list[ToolDefinition]:
    return [
        tool("project.list", "project"),
        tool("folder.create", "folder", "write"),
        tool("resource.read", "resource"),
        tool("knowledge.search", "knowledge"),
        tool("dataset.transform", "dataset", "write"),
        tool("workflow.run", "workflow", "write"),
        tool("deliverable.create", "deliverable", "write"),
        tool("workspace.navigate", "workspace"),
    ]


def test_generated_scenarios_cover_manifest_categories_and_language_styles() -> None:
    generator = NaturalLanguageScenarioGenerator(catalog(), seed=20260904)

    scenarios = generator.generate(80)

    assert category_coverage(scenarios) == {item.category for item in catalog()}
    assert {scenario.style for scenario in scenarios} == {
        "direct", "goal", "contextual", "terse", "constrained"
    }
    assert {scenario.execution_mode for scenario in scenarios} == {"AUTO", "APPROVAL"}
    assert any(len(scenario.turns) > 1 for scenario in scenarios)
    assert any(len(scenario.expected_tools) > 1 for scenario in scenarios)
    assert len({scenario.turns for scenario in scenarios}) > 60


def test_scenario_generation_is_reproducible_without_named_business_cases() -> None:
    first = NaturalLanguageScenarioGenerator(catalog(), seed=17).generate(25)
    second = NaturalLanguageScenarioGenerator(catalog(), seed=17).generate(25)

    assert first == second
    text = " ".join(turn for scenario in first for turn in scenario.turns)
    assert "汇率" not in text
    assert "头部科技" not in text
    assert "主工作流" not in text


def test_trace_evaluation_uses_execution_and_state_instead_of_final_wording() -> None:
    scenario = NaturalLanguageScenario(
        id="state-check",
        seed=1,
        persona="业务人员",
        style="goal",
        turns=("把当前目录整理好",),
        expected_tools=frozenset({"folder.create"}),
        expected_categories=frozenset({"folder"}),
        execution_mode="AUTO",
        mutates_workspace=True,
    )
    trace = AgentTrace(
        events=(
            TraceEvent("tool_call", "running", "folder.create"),
            TraceEvent("observation", "completed", "folder.create", "已完成"),
            TraceEvent("completed", "completed", message="已经处理好了"),
        ),
        assistant_messages=("已经处理好了",),
        workspace_changed=False,
    )

    result = evaluate_trace(scenario, trace)

    assert result.passed is False
    assert {finding.code for finding in result.findings} == {"no-state-change"}


def test_trace_evaluation_rejects_internal_ids_and_false_completion() -> None:
    scenario = NaturalLanguageScenario(
        id="recovery-check",
        seed=2,
        persona="项目负责人",
        style="contextual",
        turns=("继续处理刚才的文件",),
        expected_tools=frozenset({"resource.read"}),
        expected_categories=frozenset({"resource"}),
        execution_mode="AUTO",
        mutates_workspace=False,
        requires_recovery=True,
    )
    trace = AgentTrace(
        events=(
            TraceEvent("tool_call", "running", "resource.read"),
            TraceEvent("observation", "failed", "resource.read", error_code="NOT_FOUND"),
            TraceEvent("completed", "completed"),
        ),
        assistant_messages=("请提供 resource_id，我就能继续。",),
    )

    result = evaluate_trace(scenario, trace)

    assert result.passed is False
    assert {finding.code for finding in result.findings} == {
        "false-completion", "leaked-internal-id", "missing-recovery"
    }


def test_approval_allows_read_before_confirmation_but_not_write() -> None:
    scenario = NaturalLanguageScenario(
        id="approval-order",
        seed=3,
        persona="分析师",
        style="direct",
        turns=("先检查再修改",),
        expected_tools=frozenset({"resource.read", "resource.edit"}),
        expected_categories=frozenset({"resource"}),
        execution_mode="APPROVAL",
        mutates_workspace=True,
        write_tools=frozenset({"resource.edit"}),
    )
    trace = AgentTrace(
        events=(
            TraceEvent("tool_call", "running", "resource.read"),
            TraceEvent("observation", "completed", "resource.read"),
            TraceEvent("waiting_confirmation", "waiting", "resource.edit"),
            TraceEvent("tool_call", "running", "resource.edit"),
            TraceEvent("observation", "completed", "resource.edit"),
            TraceEvent("completed", "completed"),
        ),
        workspace_changed=True,
    )

    assert evaluate_trace(scenario, trace).passed is True


def test_trace_evaluation_requires_an_observation_for_each_tool_call() -> None:
    scenario = NaturalLanguageScenario(
        id="observation-check",
        seed=4,
        persona="业务人员",
        style="terse",
        turns=("打开项目说明",),
        expected_tools=frozenset({"resource.open"}),
        expected_categories=frozenset({"resource"}),
        execution_mode="AUTO",
        mutates_workspace=False,
    )

    result = evaluate_trace(scenario, AgentTrace(events=(
        TraceEvent("tool_call", "running", "resource.open"),
        TraceEvent("completed", "completed"),
    )))

    assert {finding.code for finding in result.findings} == {"missing-observation"}
