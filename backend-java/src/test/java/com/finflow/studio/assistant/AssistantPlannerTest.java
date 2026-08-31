package com.finflow.studio.assistant;

import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
class AssistantPlannerTest {

    @Test
    void createsAConfirmedProjectResearchAndWorkflowPlan() {
        var planner = new AssistantPlanner(new WorkerClient("http://127.0.0.1:9"));

        var work = planner.plan("新增一个英伟达五年财报分析项目", "project-workbench", null);

        assertThat(work.steps()).extracting(AssistantModels.PlanStep::tool).containsExactly(
                "project.get_summary",
                "project.create_analysis_workspace",
                "knowledge.discover_external_sources",
                "workflow.initialize_analysis"
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
}
