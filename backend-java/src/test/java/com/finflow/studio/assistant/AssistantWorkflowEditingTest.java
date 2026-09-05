package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.*;
import com.finflow.studio.assistant.AssistantModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:workflow-agent-edit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AssistantWorkflowEditingTest {
    @Autowired ProjectService projects;
    @Autowired WorkflowDefinitionService workflows;
    @Autowired AssistantWorkspaceToolGateway gateway;

    WorkflowResponse create() {
        return workflows.create(projects.create("批量编辑回归", "").id(), new SaveRequest("原名称", "原描述",
                List.of(new NodeDefinition("input", NodeType.FILE_INPUT, "资料", 0, 0, Map.of("resourceId", "test"))), List.of()));
    }
    PlanStep edit(String id, Object patch, int version) {
        return new PlanStep(UUID.randomUUID().toString(), 1, "workflow.edit", "WRITE", "编辑", "编辑", Map.of("workflow_id", id, "patch", patch, "expected_version", version), RiskLevel.CREATE_VERSION, false, "PENDING");
    }

    @Test void savesFourBranchesMergeAndOutputInOneVersionWithFullPrompts() {
        var current = create();
        var nodes = new ArrayList<Map<String, Object>>();
        var edges = new ArrayList<Map<String, String>>();
        var longPrompt = "完整要求、原句出处和输出契约。".repeat(800);
        for (int i = 0; i < 4; i++) {
            nodes.add(Map.of("id", "branch" + i, "type", "AI_ANALYSIS", "name", "独立分析" + i, "config", Map.of("prompt", longPrompt + i)));
            edges.add(Map.of("source", "input", "target", "branch" + i));
            edges.add(Map.of("source", "branch" + i, "target", "merge"));
        }
        nodes.add(Map.of("id", "merge", "type", "AI_ANALYSIS", "name", "汇总评分", "config", Map.of("prompt", "按指定权重评分，保留全部出处")));
        nodes.add(Map.of("id", "output", "type", "DELIVERABLE", "name", "报告", "config", Map.of("generationPrompt", "生成带出处的报告", "format", "PDF", "includeCitations", true)));
        edges.add(Map.of("source", "merge", "target", "output"));
        var patch = Map.of("nodes", nodes, "edges", edges);
        var effects = new LinkedHashMap<String, Object>();
        gateway.execute(edit(current.id(), patch, 1), effects);
        var saved = workflows.get(current.id());
        assertThat(saved.currentVersion()).isEqualTo(2);
        assertThat(saved.nodes()).hasSize(7);
        assertThat(saved.edges()).hasSize(9);
        assertThat(saved.nodes().get(1).config().get("prompt")).isEqualTo(longPrompt + "0");
        assertThat(effects.get("workflow")).isEqualTo(saved);
        gateway.execute(edit(current.id(), patch, 2), effects);
        assertThat(workflows.get(current.id()).currentVersion()).isEqualTo(2);
        assertThat(effects.get("changed")).isEqualTo(false);
        gateway.execute(edit(current.id(), Map.of("node_id", "branch0", "name", "新的节点名", "config", Map.of("prompt", "完整的新要求")), 2), effects);
        assertThat(workflows.get(current.id()).name()).isEqualTo("原名称");
        assertThat(workflows.get(current.id()).nodes().get(1).name()).isEqualTo("新的节点名");
        assertThatThrownBy(() -> gateway.execute(edit(current.id(), patch, 1), effects)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsUnsupportedPatchesAndIncompleteAnalysisWithoutCreatingVersions() {
        var current = create();
        for (var patch : List.of("请修改节点", List.of(Map.of("op", "replace", "path", "/nodes/1")),
                Map.of("unexpected", true), Map.of("nodes", List.of(Map.of("id", "new", "type", "AI_ANALYSIS", "name", "只有名称"))))) {
            assertThatThrownBy(() -> gateway.execute(edit(current.id(), patch, 1), new HashMap<>())).isInstanceOf(IllegalArgumentException.class);
            assertThat(workflows.get(current.id()).currentVersion()).isEqualTo(1);
        }
    }
}
