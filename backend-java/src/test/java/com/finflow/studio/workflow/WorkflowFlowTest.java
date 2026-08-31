package com.finflow.studio.workflow;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.ReviewRequest;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.workflow.WorkflowModels.WorkflowDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-workflow-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=false",
        "finflow.storage.root=${java.io.tmpdir}/finflow-workflow-test"
})
@AutoConfigureMockMvc
class WorkflowFlowTest {
    @Autowired ProjectService projects;
    @Autowired WorkflowDefinitionService definitions;
    @Autowired WorkflowRunService runs;
    @Autowired MockMvc mockMvc;

    @Test
    void allowsBrowserToSaveWorkflowVersionsWithPut() throws Exception {
        mockMvc.perform(options("/api/workflows/example")
                        .header("Origin", "http://127.0.0.1:5174")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void savesDraftRejectsCycleAndRunsFrozenVersion() throws Exception {
        var project = projects.create("工作流测试", "个人自动化编排");
        var draftNode = new NodeDefinition("ref", NodeType.REF_SEARCH, "查找参考", 100, 100, Map.of());
        var draft = definitions.create(project.id(), new SaveRequest("月度分析", "测试草稿",
                List.of(draftNode), List.of()));
        assertThat(draft.status()).isEqualTo("DRAFT");

        var left = new NodeDefinition("left", NodeType.REF_SEARCH, "查找一", 100, 100,
                Map.of("query", "收入"));
        var right = new NodeDefinition("right", NodeType.REF_SEARCH, "查找二", 350, 100,
                Map.of("query", "成本"));
        var cycle = definitions.validate(project.id(), new WorkflowDocument("循环", "",
                List.of(left, right), List.of(new EdgeDefinition("e1", "left", "right"),
                        new EdgeDefinition("e2", "right", "left"))));
        assertThat(cycle.valid()).isFalse();
        assertThat(cycle.issues()).extracting(WorkflowModels.ValidationIssue::message)
                .contains("步骤之间形成了循环，请调整连线");

        var ready = definitions.update(draft.id(), new SaveRequest("月度分析", "可运行版本",
                List.of(new NodeDefinition("ref", NodeType.REF_SEARCH, "查找参考", 100, 100,
                        Map.of("query", "收入变化", "limit", 5))), List.of()));
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.currentVersion()).isEqualTo(2);

        var run = runs.start(ready.id());
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            run = runs.get(run.id());
            if (List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status())) break;
            Thread.sleep(50);
        }
        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(run.workflowVersion()).isEqualTo(2);
        assertThat(run.nodes()).singleElement().satisfies(node -> {
            assertThat(node.status()).isEqualTo("SUCCEEDED");
            assertThat(node.output()).containsEntry("count", 0);
        });
    }

    @Test
    void keepsOneProjectWorkflowAndRejectsStaleSave() {
        var project = projects.create("唯一工作流测试", "项目级主工作流");
        var first = definitions.getProjectWorkflow(project.id());
        var same = definitions.getProjectWorkflow(project.id());
        assertThat(same.id()).isEqualTo(first.id());
        assertThat(same.status()).isEqualTo("DRAFT");

        var node = new NodeDefinition("analysis", NodeType.AI_ANALYSIS, "分析", 100, 100,
                Map.of("prompt", "分析数据变化"));
        var saved = definitions.saveProjectWorkflow(project.id(), new SaveRequest("主工作流", "",
                List.of(node), List.of(), first.currentVersion()));
        assertThat(saved.currentVersion()).isEqualTo(2);

        assertThatThrownBy(() -> definitions.saveProjectWorkflow(project.id(), new SaveRequest("旧版本", "",
                List.of(node), List.of(), first.currentVersion())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("刷新");
        assertThatThrownBy(() -> definitions.create(project.id(), new SaveRequest("另一个工作流", "",
                List.of(node), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能有一个");
    }

    @Test
    void pausesForReviewPersistsAdjustmentAndResumes() throws Exception {
        var project = projects.create("人工复核测试", "严谨财经作业");
        var source = new NodeDefinition("source", NodeType.LINK_INPUT, "中间分析", 80, 100,
                Map.of("url", "https://example.com/report", "title", "模拟报告"));
        var review = new NodeDefinition("review", NodeType.REVIEW, "复核分析结论", 340, 100,
                Map.of("instructions", "核对数字和口径", "editable", true, "requireComment", true));
        var after = new NodeDefinition("after", NodeType.REF_SEARCH, "后续步骤", 600, 100,
                Map.of("query", "经确认的结论", "limit", 3));
        var workflow = definitions.create(project.id(), new SaveRequest("带复核的工作流", "",
                List.of(source, review, after), List.of(new EdgeDefinition("e1", "source", "review"),
                        new EdgeDefinition("e2", "review", "after"))));

        var run = runs.start(workflow.id());
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            run = runs.get(run.id());
            if ("WAITING_REVIEW".equals(run.status())) break;
            Thread.sleep(50);
        }
        assertThat(run.status()).isEqualTo("WAITING_REVIEW");
        assertThat(run.nodes()).filteredOn(node -> node.nodeId().equals("review")).singleElement()
                .satisfies(node -> assertThat(node.output()).containsEntry("reviewStatus", "WAITING_REVIEW"));

        run = runs.confirmReview(run.id(), new ReviewRequest("已核对口径", "调整后的正式分析结论"));
        deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            run = runs.get(run.id());
            if (List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status())) break;
            Thread.sleep(50);
        }
        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(run.nodes()).filteredOn(node -> node.nodeId().equals("review")).singleElement().satisfies(node -> {
            assertThat(node.status()).isEqualTo("SUCCEEDED");
            assertThat(node.output()).containsEntry("analysis", "调整后的正式分析结论")
                    .containsEntry("reviewComment", "已核对口径")
                    .containsEntry("reviewStatus", "CONFIRMED");
        });
        assertThat(run.nodes()).filteredOn(node -> node.nodeId().equals("after")).singleElement()
                .satisfies(node -> assertThat(node.status()).isEqualTo("SUCCEEDED"));
    }
}
