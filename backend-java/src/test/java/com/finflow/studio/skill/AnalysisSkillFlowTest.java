package com.finflow.studio.skill;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.workflow.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-analysis-skill-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=false",
        "finflow.storage.root=${java.io.tmpdir}/finflow-analysis-skill-test"
})
class AnalysisSkillFlowTest {
    @Autowired ProjectService projects;
    @Autowired AnalysisSkillService skills;
    @Autowired WorkflowDefinitionService definitions;
    @Autowired WorkflowRunService runs;
    @MockitoBean WorkerClient worker;

    @Test
    void uploadsProjectSkillAndUsesVerifiedContentDuringAnalysis() throws Exception {
        var project = projects.create("Skill 分析", "项目隔离");
        var file = new MockMultipartFile("file", "cash-flow.md", "text/markdown", """
                ---
                name: 现金流诊断
                description: 用现金转换与偿债能力检查经营质量
                ---
                # 现金流诊断
                先核对经营现金流与利润方向，再检查短期偿债风险。每个判断必须引用输入资料。
                """.getBytes());
        var skill = skills.upload(project.id(), file);

        when(worker.fetchResearchSource(anyString())).thenReturn(Map.of(
                "text", "经营现金流为100万元", "title", "经营数据", "content_hash", "source-hash"));
        when(worker.generateContentStreaming(eq("ANALYSIS"), anyString(), anyString(), eq("现金流诊断"),
                contains("短期偿债风险"), any())).thenReturn(Map.of("mode", "llm", "content", "现金流表现稳定。"));
        var saved = definitions.create(project.id(), new SaveRequest("Skill 工作流", "", List.of(
                new NodeDefinition("source", NodeType.LINK_INPUT, "经营数据", 0, 0,
                        Map.of("url", "https://example.com/cash-flow")),
                new NodeDefinition("analysis", NodeType.AI_ANALYSIS, "智能分析", 200, 0,
                        Map.of("prompt", "判断经营质量", "analysisSkillId", skill.id()))),
                List.of(new EdgeDefinition("source-analysis", "source", "analysis"))));

        var run = runs.start(saved.id());
        var deadline = Instant.now().plusSeconds(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }

        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(run.nodes().getLast().output()).extractingByKey("skill")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("id", skill.id()).containsEntry("contentHash", skill.contentHash());
        verify(worker).generateContentStreaming(eq("ANALYSIS"), eq("判断经营质量"), anyString(),
                eq("现金流诊断"), contains("每个判断必须引用输入资料"), any());
    }

    @Test
    void rejectsUnsupportedFilesAndCrossAccountAccess() {
        var firstProject = projects.create("第一项目", "");
        var skill = skills.upload(firstProject.id(), new MockMultipartFile("file", "method.md", "text/markdown",
                "# 方法\n核对输入证据，区分事实、推断和不确定信息。".getBytes()));
        assertThatThrownBy(() -> skills.upload(firstProject.id(), new MockMultipartFile("file", "code.py",
                "text/x-python", "print('unsafe') and more text".getBytes())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仅支持");

        try (var ignored = ActorContext.bind("another-user")) {
            assertThatThrownBy(() -> skills.get(skill.id()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("项目不存在");
        }
    }
}
