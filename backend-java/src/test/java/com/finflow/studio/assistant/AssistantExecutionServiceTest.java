package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import com.finflow.studio.knowledge.KnowledgeModels.RefResponse;
import com.finflow.studio.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantExecutionServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void executesKnowledgeSearchAndKeepsCitationProvenance() {
        var knowledge = mock(KnowledgeService.class);
        when(knowledge.search("project-1", "2026 科技公司战略", 8)).thenReturn(List.of(
                new RefResponse("citation-1", "project-1", "resource-1", 3, "公司年报.pdf",
                        "战略投入继续聚焦人工智能基础设施。", Map.of("page", 12), "sha256:abc", 0.91)
        ));
        var execution = new AssistantExecutionService(null, null, null, null, null, null, null, null, knowledge, null);
        var step = new PlanStep("step-1", 2, "knowledge.search", "READ", "搜索知识", "检索已有证据",
                Map.of("project_id", "project-1", "query", "2026 科技公司战略", "limit", 8),
                RiskLevel.READ_ONLY, false, "PENDING");
        var effects = new LinkedHashMap<String, Object>();

        var result = execution.executeStep(step, effects);

        assertThat(result).isEqualTo("已从项目知识库找到 1 条可引用证据");
        var citations = (List<Map<String, Object>>) effects.get("knowledgeCitations");
        assertThat(citations).hasSize(1);
        assertThat(citations.getFirst())
                .containsEntry("citationId", "citation-1")
                .containsEntry("resourceId", "resource-1")
                .containsEntry("version", 3)
                .containsEntry("contentHash", "sha256:abc");
        verify(knowledge).search("project-1", "2026 科技公司战略", 8);
    }

    @Test
    void emptyKnowledgeSearchContinuesInsteadOfFailingTheRun() {
        var knowledge = mock(KnowledgeService.class);
        when(knowledge.search("project-1", "最新经营状况", 10)).thenReturn(List.of());
        var execution = new AssistantExecutionService(null, null, null, null, null, null, null, null, knowledge, null);
        var step = new PlanStep("step-1", 2, "knowledge.search", "READ", "搜索知识", "检索已有证据",
                Map.of("project_id", "project-1", "query", "最新经营状况"),
                RiskLevel.READ_ONLY, false, "PENDING");

        var result = execution.executeStep(step, new LinkedHashMap<>());

        assertThat(result).contains("未命中相关证据").contains("继续");
    }
}
