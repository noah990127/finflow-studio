package com.finflow.studio.workflow;

import com.finflow.studio.deliverable.DeliverableModels;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-variable-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=${java.io.tmpdir}/finflow-variable-test"
})
class WorkflowVariableFlowTest {
    @Autowired ProjectService projects;
    @Autowired WorkflowDefinitionService definitions;
    @Autowired WorkflowRunService runs;
    @MockitoBean WorkerClient worker;
    @MockitoBean DeliverableService deliverables;

    @Test void bindsAnalysisOutputToGenerationAcrossSaveRunAndEvidence() throws Exception {
        when(worker.fetchResearchSource(anyString())).thenReturn(Map.of("text", "收入增长18%", "title", "经营年报", "content_hash", "sha-18"));
        when(worker.generateContentStreaming(eq("ANALYSIS"), anyString(), anyString(), any()))
                .thenReturn(Map.of("mode", "llm", "content", "收入增长18%，建议核实回款质量。"));
        when(worker.generateContent(eq("DELIVERABLE_PLAN"), anyString(), anyString())).thenReturn(Map.of("mode", "llm", "content",
                "{\"format\":\"PPTX\",\"title\":\"经营复盘\",\"heading\":\"结论\"}"));
        when(worker.generateContentStreaming(eq("PPTX"), anyString(), anyString(), any()))
                .thenReturn(Map.of("mode", "llm", "content", "已生成成果"));
        when(deliverables.create(any())).thenReturn(new DeliverableModels.Response("artifact", "project", "经营复盘", "PPTX", 1, "READY", 100, "hash", null, null));
        var project = projects.create("变量回归", "隔离测试");
        var definition = new SaveRequest("分析到成果", "", List.of(
                new NodeDefinition("source", NodeType.LINK_INPUT, "年报", 0, 0, Map.of("url", "https://example.com/report")),
                new NodeDefinition("analysis", NodeType.AI_ANALYSIS, "智能分析", 200, 0, Map.of("prompt", "分析 {{#source.output#}}")),
                new NodeDefinition("result", NodeType.DELIVERABLE, "生成成果", 400, 0, Map.of(
                        "generationPrompt", "依据 {{#analysis.output#}} 生成汇报", "format", "PPTX", "includeCitations", true,
                        "citationStyle", "IEEE", "inputSource", Map.of("nodeId", "analysis", "path", List.of("output"))))),
                List.of(new EdgeDefinition("s-a", "source", "analysis"), new EdgeDefinition("a-r", "analysis", "result")));
        var saved = definitions.create(project.id(), definition);
        assertThat(saved.status()).isEqualTo("READY");
        assertThat(definitions.get(saved.id()).nodes().getLast().config()).containsKey("inputSource");
        var run = runs.start(saved.id());
        var deadline = Instant.now().plusSeconds(15);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }
        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        var analysis = run.nodes().stream().filter(node -> node.nodeId().equals("analysis")).findFirst().orElseThrow();
        assertThat(analysis.output()).containsEntry("output", "收入增长18%，建议核实回款质量。");
        assertThat((List<?>) analysis.output().get("sources")).hasSize(1);
        var output = run.nodes().stream().filter(node -> node.nodeId().equals("result")).findFirst().orElseThrow();
        assertThat(output.input().get("config").toString()).contains("收入增长18%").doesNotContain("{{#analysis.output#}}");
        assertThat((List<?>) output.output().get("sources")).hasSize(1);
        verify(worker).generateContentStreaming(eq("PPTX"), contains("依据 收入增长18%"),
                argThat(value -> value.startsWith("收入增长18%，建议核实回款质量。") && value.contains("经营年报")), any());
        verify(deliverables).create(argThat(request -> request.sections().getFirst().citations().size() == 1));
    }
}
