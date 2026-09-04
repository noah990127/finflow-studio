package com.finflow.studio.assistant;

import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
class AssistantPlannerTest {

    @Test
    void passesAutoModeAndNamedWorkspaceObjectsToTheAgent() {
        var captured = new AtomicReference<Map<String, Object>>();
        var worker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> planAgent(Object request) {
                captured.set((Map<String, Object>) request);
                return Map.of("summary", "将删除主工作流", "steps", List.of(Map.of(
                        "tool", "workflow.delete", "title", "删除工作流", "description", "删除主工作流",
                        "arguments", Map.of("workflow_id", "workflow-main"))));
            }
        };
        var context = new AssistantPlanner.WorkspaceContext("project-1", "项目", 0, 0, 0,
                false, null, null, null,
                List.of(Map.of("id", "workflow-main", "name", "主工作流", "type", "WORKFLOW",
                        "group", "WORKFLOW", "status", "DRAFT")), List.of());

        var work = new AssistantPlanner(worker).plan("删除掉主工作流", "project-home", null,
                context, "session-1", "AUTO");

        assertThat(captured.get()).containsEntry("execution_mode", "AUTO");
        assertThat(captured.get().get("resources")).isEqualTo(context.resources());
        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool)
                .containsExactly("workspace.inspect", "workflow.delete");
    }

    @Test
    void rejectsUnknownAgentToolsAndFallsBackToSafePlanner() {
        var worker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            public Map<String, Object> planAgent(Object request) {
                return Map.of("summary", "执行任意命令", "steps", List.of(Map.of(
                        "tool", "system.shell", "title", "执行命令", "description", "绕过工作台",
                        "arguments", Map.of("command", "anything"))));
            }
        };
        var planner = new AssistantPlanner(worker);

        var work = planner.plan("介绍当前项目", "project-home", null);

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool)
                .containsExactly("workspace.inspect", "assistant.respond");
    }

    @Test
    void createsAConfirmedProjectResearchAndWorkflowPlan() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("新增一个英伟达五年财报分析项目", "project-workbench", null);

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool).containsExactly(
                "workspace.inspect",
                "project.create_workspace",
                "knowledge.discover_external_sources",
                "workflow.initialize"
        );
        assertThat(work.steps().get(1).arguments().get("project_name")).isEqualTo("英伟达五年财报分析");
        assertThat(work.steps().get(1).requiresConfirmation()).isTrue();
        assertThat(work.steps().get(3).requiresConfirmation()).isTrue();
    }

    @Test
    void summarizesAConversationalRequestIntoAConciseProjectName() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("请新增一个项目，分析英伟达近五年财报，然后生成管理层PPT", "project-workbench", null);

        assertThat(work.steps().get(1).arguments().get("project_name"))
                .isEqualTo("英伟达近五年财报分析");
        assertThat(work.steps().get(1).arguments().get("topic"))
                .isEqualTo("英伟达近五年财报");
    }

    @Test
    void removesInstructionsAfterTheProjectTopic() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("帮我创建一个库存清点分析项目，然后整理数据并输出汇报", "project-workbench", null);

        assertThat(work.steps().get(1).arguments().get("project_name"))
                .isEqualTo("库存清点分析");
    }

    @Test
    void prioritizesTheAnalysisSubjectOverPreliminaryResearchInstructions() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("新建一个项目，搜集一些网上的资料，分析海力士近五年的经营情况、战略规划等。", "project-workbench", null);

        assertThat(work.steps().get(1).arguments().get("project_name"))
                .isEqualTo("海力士近五年经营与战略分析");
    }

    @Test
    void fallbackRunsResearchAnalysisAndAllRequestedOutputsEndToEnd() {
        var unavailableWorker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            public Map<String, Object> planAgent(Object request) {
                throw new IllegalStateException("model unavailable");
            }
        };
        var planner = new AssistantPlanner(unavailableWorker);

        var work = planner.plan("搜索2026年头部科技公司战略和经营状况，分析后输出PPT和HTML",
                "project-home", null,
                new AssistantPlanner.WorkspaceContext("project-1", "研究项目", 0, 0, 0,
                        false, null, null, null, List.of(), List.of()));

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool).containsExactly(
                "workspace.inspect",
                "knowledge.discover_external_sources",
                "knowledge.add",
                "workflow.prepare",
                "workflow.run"
        );
        assertThat(work.steps().get(3).arguments().get("output_formats"))
                .isEqualTo(List.of("PPTX", "HTML_SLIDES"));
    }

    @Test
    void doesNotInventAReportForAGenericRequestWithoutData() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("帮我看看这个项目下一步该做什么", "project-home", null);

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool)
                .containsExactly("workspace.inspect", "assistant.respond");
        assertThat(work.steps()).noneMatch(step -> step.tool().contains("output") || step.tool().contains("deliverable"));
    }

    @Test
    void stagesOnlyTheFirstRealToolSelectedByTheDeepAgent() {
        var worker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            public Map<String, Object> planAgent(Object request) {
                return Map.of("summary", "research", "steps", List.of(
                        Map.of("tool", "knowledge.discover_external_sources", "title", "search",
                                "description", "search", "arguments", Map.of("topic", "tech")),
                        Map.of("tool", "assistant.analyze_context", "title", "analyze",
                                "description", "analyze", "arguments", Map.of()),
                        Map.of("tool", "deliverable.create", "title", "ppt",
                                "description", "12-slide PPTX", "arguments", Map.of("format", "PPTX")),
                        Map.of("tool", "deliverable.create", "title", "html",
                                "description", "HTML report", "arguments", Map.of("format", "HTML"))));
            }
        };
        var planner = new AssistantPlanner(worker);

        var work = planner.plan("Research latest results, analyze them and create a 12-slide PPTX and HTML report",
                "project-home", null,
                new AssistantPlanner.WorkspaceContext("project-1", "Research", 0, 0, 0,
                        false, null, null, null, List.of(), List.of()));

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool).containsExactly(
                "workspace.inspect", "knowledge.discover_external_sources");
        assertThat(work.dynamic()).isTrue();
    }

    @Test
    void followsAWorkspaceInspectionWithAUserVisibleAnswer() {
        var worker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            public Map<String, Object> planAgent(Object request) {
                return Map.of("summary", "先查看当前项目", "steps", List.of(Map.of(
                        "tool", "workspace.inspect", "title", "检查项目", "description", "读取工作区摘要",
                        "arguments", Map.of())));
            }
        };

        var work = new AssistantPlanner(worker).plan("梳理当前项目并给出一项建议", "project-home", null,
                new AssistantPlanner.WorkspaceContext("project-1", "项目", 0, 1, 2,
                        false, null, null, null, List.of(), List.of()));

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool)
                .containsExactly("workspace.inspect", "assistant.respond");
        assertThat(work.dynamic()).isTrue();
    }

    @Test
    void sendsARealObservationBackForTheNextDynamicDecision() {
        var captured = new AtomicReference<Map<String, Object>>();
        var worker = new WorkerClient("http://127.0.0.1:9") {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> planAgent(Object request) {
                captured.set((Map<String, Object>) request);
                return Map.of("summary", "继续重命名目录", "completed", false, "steps", List.of(Map.of(
                        "tool", "folder.rename", "title", "重命名目录", "description", "使用刚创建的目录继续处理",
                        "arguments", Map.of("folder_id", "folder-1", "name", "最终名称"))));
            }
        };
        var context = new AssistantPlanner.WorkspaceContext("project-1", "项目", 0, 1, 0,
                false, null, null, null,
                List.of(Map.of("id", "folder-1", "name", "临时名称", "type", "FOLDER",
                        "group", "KNOWLEDGE", "status", "READY")), List.of());
        var observation = Map.<String, Object>of(
                "tool", "folder.create", "success", true, "result", "已创建目录“临时名称”");

        var turn = new AssistantPlanner(worker).continueAfterObservation(
                "创建目录后重命名", "project-home", context, "session-1", "AUTO", observation, 1);

        assertThat(captured.get()).containsEntry("continuation", true).containsEntry("completed_actions", 1);
        assertThat(captured.get().get("observation")).isEqualTo(observation);
        assertThat(turn.completed()).isFalse();
        assertThat(turn.steps()).extracting(AssistantModels.PlanStep::tool).containsExactly("folder.rename");
    }

    @Test
    void asksForDataBeforePlanningDataWork() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("清理数据并检查异常", "project-home", null);

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool)
                .containsExactly("workspace.inspect", "assistant.respond");
        assertThat(work.steps().get(1).arguments()).containsEntry("reason", "NO_STRUCTURED_DATA");
    }
}
