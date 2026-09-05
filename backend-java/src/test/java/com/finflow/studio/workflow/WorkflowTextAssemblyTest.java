package com.finflow.studio.workflow;

import com.finflow.studio.deliverable.DeliverableModels;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowTextAssemblyTest {

    private final WorkflowContextAssembler contexts = new WorkflowContextAssembler(null, null);
    private final WorkflowDeliverableService deliverables = new WorkflowDeliverableService(
            null, null, null, new ObjectMapper(), contexts);

    @Test
    void parsedReferencesPreventRawFileBytesFromBeingReadAgain() {
        var output = Map.<String, Object>of(
                "fileId", "parsed-pdf",
                "version", 1,
                "refs", List.of(
                        Map.of("text", "第一页的已解析内容"),
                        Map.of("text", "第二页的已解析内容")));

        assertThat(contexts.collectText(Map.of("input", output)))
                .isEqualTo("第一页的已解析内容\n第二页的已解析内容");
    }

    @Test
    void generatedContextNeverExceedsWorkerRequestLimit() {
        var oversized = "数据".repeat(WorkflowContextAssembler.MAX_CONTEXT_CHARS);

        var result = contexts.collectText(Map.of("analysis", Map.of("analysis", oversized)));

        assertThat(result).hasSizeLessThan(200_000)
                .endsWith("[工作流上下文较长，已按安全上限截断]");
    }

    @Test
    void parsesModelSelectedDeliverablePlanWithoutMarkdownWrapper() {
        var plan = deliverables.parsePlan("""
                ```json
                {"format":"PPTX","title":"经营复盘","subtitle":"2026年","heading":"核心结论",
                 "include_citations":true,"citation_style":"IEEE","ppt_skill":"guizang-huawei-style-c"}
                ```
                """);

        assertThat(plan).containsEntry("format", "PPTX")
                .containsEntry("title", "经营复盘")
                .containsEntry("include_citations", true);
    }

    @Test
    void preservesExplicitDeliverableTypeCitationAndSkillChoices() {
        var plan = deliverables.applyOutputPreferences(Map.of(
                "format", "HTML_SLIDES", "title", "经营复盘", "heading", "核心结论",
                "include_citations", false, "citation_style", "IEEE", "ppt_skill", "frontend-slides"), Map.of(
                "format", "PPTX", "title", "旧标题", "heading", "旧章节",
                "pptSkill", "guizang-huawei-style-c", "includeCitations", true,
                "citationStyle", "GB_T_7714"));

        assertThat(plan).containsEntry("format", "PPTX")
                .containsEntry("title", "经营复盘")
                .containsEntry("ppt_skill", "guizang-huawei-style-c")
                .containsEntry("heading", "核心结论")
                .containsEntry("include_citations", true)
                .containsEntry("citation_style", "GB_T_7714");
    }

    @Test
    void explicitDisabledOptionsOverrideModelAndPromptOnlyPlansStayIntact() {
        var modelPlan = Map.<String, Object>of("format", "PPTX", "title", "自动标题",
                "include_citations", true, "ppt_skill", "guizang-huawei-style-c");
        assertThat(deliverables.applyOutputPreferences(modelPlan,
                Map.of("includeCitations", false, "pptSkill", "")))
                .containsEntry("include_citations", false).containsEntry("ppt_skill", "")
                .containsEntry("title", "自动标题");
        assertThat(deliverables.applyOutputPreferences(modelPlan, Map.of("generationPrompt", "总结资料")))
                .isEqualTo(modelPlan);
        assertThat(deliverables.applyOutputPreferences(modelPlan, Map.of("format", "MERMAID", "handDrawn", true)))
                .containsEntry("format", "EXCALIDRAW");
    }

    @Test
    void configuredOutputsStillUseModelPlanningAndIgnoreRetiredContentFields() {
        var worker = mock(WorkerClient.class);
        var artifacts = mock(DeliverableService.class);
        var events = mock(WorkflowRunEventService.class);
        var service = new WorkflowDeliverableService(worker, artifacts, events, new ObjectMapper(), contexts);
        when(worker.generateContent(eq("DELIVERABLE_PLAN"), anyString(), anyString())).thenReturn(Map.of(
                "mode", "llm", "content", """
                {"format":"HTML_SLIDES","title":"模型标题","subtitle":"模型副标题","heading":"模型章节",
                 "include_citations":true,"citation_style":"IEEE","ppt_skill":"frontend-slides"}
                """));
        when(worker.generateContentStreaming(eq("PPTX"), anyString(), anyString(), any()))
                .thenReturn(Map.of("mode", "llm", "content", "模型生成正文"));
        when(artifacts.create(any())).thenReturn(new DeliverableModels.Response(
                "artifact", "project", "模型标题", "PPTX", 1, "READY", 100, "hash", null, null));
        var config = Map.<String, Object>of("generationPrompt", "给出变化原因和行动建议",
                "format", "PPTX", "title", "旧标题", "subtitle", "旧副标题", "heading", "旧章节",
                "targetAudience", "旧受众配置", "lengthHint", "旧篇幅配置", "includeCitations", false, "pptSkill", "");
        var node = new WorkflowModels.NodeDefinition("output", WorkflowModels.NodeType.DELIVERABLE,
                "生成成果", 0, 0, config);

        var result = service.create("project", "run", node, config,
                Map.of("analysis", Map.of("analysis", "已核验的上游资料")), 10, 90);

        verify(worker).generateContent(eq("DELIVERABLE_PLAN"), contains("给出变化原因和行动建议"), eq("已核验的上游资料"));
        var requirements = ArgumentCaptor.forClass(String.class);
        verify(worker).generateContentStreaming(eq("PPTX"), requirements.capture(), anyString(), any());
        assertThat(requirements.getValue()).contains("模型标题", "模型章节", "给出变化原因和行动建议")
                .doesNotContain("旧标题", "旧副标题", "旧章节", "旧受众配置", "旧篇幅配置");
        var request = ArgumentCaptor.forClass(DeliverableModels.CreateRequest.class);
        verify(artifacts).create(request.capture());
        assertThat(request.getValue().title()).isEqualTo("模型标题");
        assertThat(request.getValue().format()).isEqualTo("PPTX");
        assertThat(request.getValue().includeCitations()).isFalse();
        assertThat(request.getValue().pptSkill()).isEmpty();
        assertThat(result).containsEntry("planningMode", "llm");
    }

    @Test
    void deduplicatesReferencesAcrossConnectedNodes() {
        var reference = Map.<String, Object>of(
                "id", "ref-1", "resourceId", "file-1", "version", 2,
                "sourceName", "年度报告", "text", "收入同比增长 18%",
                "location", Map.of("page", 8), "contentHash", "sha-1");
        var context = Map.of(
                "source-a", Map.<String, Object>of("refs", List.of(reference), "refIds", List.of("ref-1")),
                "source-b", Map.<String, Object>of("refs", List.of(reference), "refIds", List.of("ref-1")));

        assertThat(contexts.refIds(context)).containsExactly("ref-1");
        assertThat(contexts.citations(context)).singleElement().satisfies(citation -> {
            assertThat(citation.sourceName()).isEqualTo("年度报告");
            assertThat(citation.location()).containsEntry("page", 8);
        });
        assertThat(contexts.referenceCatalog(context))
                .contains("[Ref 1] 年度报告")
                .contains("收入同比增长 18%");
    }

    @Test
    void rejectsInvalidDeliverablePlanWithDomainError() {
        assertThatThrownBy(() -> deliverables.parsePlan("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("大模型没有返回有效的成果规格");
    }
}
