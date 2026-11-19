package com.finflow.studio.workflow;

import com.finflow.studio.deliverable.DeliverableModels;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
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
    @Autowired KnowledgeService knowledge;
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

    @Test void searchesValidatesAndPersistsFreshWebSourcesForEveryRun() throws Exception {
        var longQuery = "请查找A股科技股最新变化，重点关注走势、资金与盈利预期。"
                + "背景资料".repeat(180)
                + "最后请确保检索结果覆盖最新公开信息与主要影响因素。";
        when(worker.discoverResearchSources("请查找A股科技股最新变化，重点关注走势、资金与盈利预期。", 5)).thenReturn(Map.of(
                "mode", "search-provider", "status", "ready", "provider", "searxng",
                "summary", "找到候选资料", "sources", List.of(
                        Map.of("title", "科技板块可访问资料", "url", "https://example.com/good"),
                        Map.of("title", "科技板块失效资料", "url", "https://example.com/bad"))));
        when(worker.fetchResearchSource("https://example.com/good")).thenReturn(Map.of(
                "title", "科技板块最新观察", "url", "https://example.com/good",
                "final_url", "https://example.com/good", "text", "科技板块成交与盈利预期出现变化。",
                "content_hash", "hash-good", "content_type", "text/html"));
        when(worker.fetchResearchSource("https://example.com/bad"))
                .thenThrow(new IllegalStateException("网页返回 404"));

        var project = projects.create("联网查找回归", "验证动态资料节点");
        var workflow = definitions.create(project.id(), new SaveRequest("动态研究", "", List.of(
                new NodeDefinition("research", NodeType.REF_SEARCH, "查找资料", 0, 0,
                        Map.of("query", longQuery, "limit", 5, "includeWeb", true))), List.of()));
        var run = runs.start(workflow.id());
        var deadline = Instant.now().plusSeconds(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }

        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        var output = run.nodes().getFirst().output();
        assertThat(output).containsEntry("count", 1).containsEntry("webSearch", true)
                .containsEntry("queryCompacted", true);
        assertThat(output.get("query").toString())
                .isEqualTo("请查找A股科技股最新变化，重点关注走势、资金与盈利预期。");
        assertThat((List<?>) output.get("links")).singleElement().asString().contains("https://example.com/good");
        assertThat((List<?>) output.get("refs")).singleElement().asString().contains("科技板块成交与盈利预期出现变化");
        assertThat((List<?>) output.get("sourceSnapshots")).hasSize(1);
        assertThat((List<?>) output.get("rejectedCandidates")).singleElement().asString().contains("404");
        assertThat(knowledge.list(project.id())).anySatisfy(resource ->
                assertThat(resource.name()).contains("科技板块最新观察", "网页快照"));
    }

    @Test void reportsSearchServiceUnavailableInsteadOfPretendingThereWereNoResults() throws Exception {
        when(worker.discoverResearchSources(anyString(), eq(5))).thenReturn(Map.of(
                "mode", "search-unavailable", "status", "unavailable", "provider", "searxng",
                "summary", "联网搜索服务暂时无法访问", "sources", List.of()));
        var project = projects.create("搜索故障回归", "区分服务故障和空结果");
        var workflow = definitions.create(project.id(), new SaveRequest("搜索故障", "", List.of(
                new NodeDefinition("research", NodeType.REF_SEARCH, "查找资料", 0, 0,
                        Map.of("query", "深圳福田今天活动", "limit", 5, "includeWeb", true))), List.of()));

        var run = runs.start(workflow.id());
        var deadline = Instant.now().plusSeconds(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorMessage()).contains("联网搜索服务当前不可用", "暂时无法访问")
                .doesNotContain("没有返回候选资料");
    }

    @Test void keepsEarlierAgentResearchWorkflowsExecutable() throws Exception {
        when(worker.discoverResearchSources("尊界踏板事件", 4)).thenReturn(Map.of(
                "status", "ready", "sources", List.of(
                        Map.of("title", "尊界踏板事件通报", "url", "https://example.com/notice"))));
        when(worker.fetchResearchSource("https://example.com/notice")).thenReturn(Map.of(
                "title", "事件通报", "final_url", "https://example.com/notice",
                "text", "公开通报正文", "content_hash", "hash-notice"));
        var project = projects.create("旧工作流兼容", "验证 maxSources 旧契约");
        var workflow = definitions.create(project.id(), new SaveRequest("旧研究流程", "", List.of(
                new NodeDefinition("research", NodeType.REF_SEARCH, "查找资料", 0, 0,
                        Map.of("query", "尊界踏板事件", "maxSources", 4))), List.of()));

        var run = runs.start(workflow.id());
        var deadline = Instant.now().plusSeconds(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }

        assertThat(run.status()).as(run.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(run.nodes().getFirst().output()).containsEntry("webSearch", true).containsEntry("count", 1);
        verify(worker).discoverResearchSources("尊界踏板事件", 4);
    }

    @Test void retryRunsResearchAgainInsteadOfReusingAnEarlierSearchSnapshot() throws Exception {
        when(worker.discoverResearchSources("月度市场跟踪", 3)).thenReturn(Map.of(
                "status", "ready", "sources", List.of(
                        Map.of("title", "市场日报", "url", "https://example.com/market"))));
        when(worker.fetchResearchSource("https://example.com/market")).thenReturn(Map.of(
                "title", "市场日报", "final_url", "https://example.com/market",
                "text", "市场成交与情绪数据", "content_hash", "hash-market"));
        when(worker.generateContentStreaming(eq("ANALYSIS"), anyString(), anyString(), any()))
                .thenReturn(Map.of("mode", "fallback", "content", ""))
                .thenReturn(Map.of("mode", "llm", "content", "市场情绪有所修复。"));
        var project = projects.create("重试搜索", "验证动态资料不复用");
        var workflow = definitions.create(project.id(), new SaveRequest("市场跟踪", "", List.of(
                new NodeDefinition("research", NodeType.REF_SEARCH, "查找资料", 0, 0,
                        Map.of("query", "月度市场跟踪", "limit", 3, "includeWeb", true)),
                new NodeDefinition("analysis", NodeType.AI_ANALYSIS, "分析", 200, 0,
                        Map.of("prompt", "分析变化"))),
                List.of(new EdgeDefinition("r-a", "research", "analysis"))));

        var first = waitForRun(runs.start(workflow.id()));
        assertThat(first.status()).isEqualTo("FAILED");
        var retried = waitForRun(runs.retry(first.id()));

        assertThat(retried.status()).as(retried.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(retried.nodes().stream().filter(node -> node.nodeId().equals("research")).findFirst().orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        verify(worker, times(2)).discoverResearchSources("月度市场跟踪", 3);
    }

    private RunResponse waitForRun(RunResponse run) throws InterruptedException {
        var deadline = Instant.now().plusSeconds(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(30);
            run = runs.get(run.id());
        }
        return run;
    }
}
