package com.finflow.studio.workflow;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        var plan = deliverables.configuredPlan(Map.of(
                "format", "PPTX", "title", "经营复盘", "heading", "核心结论",
                "pptSkill", "guizang-huawei-style-c", "includeCitations", true,
                "citationStyle", "GB_T_7714"));

        assertThat(plan).containsEntry("format", "PPTX")
                .containsEntry("title", "经营复盘")
                .containsEntry("ppt_skill", "guizang-huawei-style-c")
                .containsEntry("include_citations", true)
                .containsEntry("citation_style", "GB_T_7714");
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
