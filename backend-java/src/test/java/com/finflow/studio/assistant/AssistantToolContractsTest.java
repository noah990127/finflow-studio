package com.finflow.studio.assistant;

import com.finflow.studio.workflow.WorkflowModels.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantToolContractsTest {
    @Test
    void exposesRequiredTypedAndClosedToolSchemas() {
        var schema = AssistantCapabilityRegistry.find("workflow.add_node").orElseThrow().inputSchema();
        assertThat(schema.get("required")).isEqualTo(List.of("workflow_id", "node_type", "config"));
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        var properties = (Map<?, ?>) schema.get("properties");
        assertThat(((Map<?, ?>) properties.get("node_type")).get("enum")).isNotNull();
        assertThat(((Map<?, ?>) properties.get("config")).get("type")).isEqualTo("object");
    }

    @Test
    void workflowNodeEnumAlwaysMatchesTheExecutableNodeTypes() {
        var schema = AssistantCapabilityRegistry.find("workflow.add_node").orElseThrow().inputSchema();
        var properties = (Map<?, ?>) schema.get("properties");
        var nodeTypes = ((List<?>) ((Map<?, ?>) properties.get("node_type")).get("enum"))
                .stream().map(Object::toString).toList();

        assertThat(nodeTypes).containsExactlyInAnyOrder(
                java.util.Arrays.stream(NodeType.values()).map(Enum::name).toArray(String[]::new));
        assertThat(nodeTypes).contains("REVIEW", "DATASET_INPUT", "DATA_TRANSFORM")
                .doesNotContain("HUMAN_REVIEW", "DATABASE_INPUT", "DATA_PROCESS");
    }

    @Test
    void rejectsMissingUnknownAndWronglyTypedArgumentsWithRepairAdvice() {
        assertThatThrownBy(() -> AssistantToolContracts.validate("project.rename", Map.of("project_id", "p-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填字段")
                .hasMessageContaining("new_name")
                .hasMessageContaining("describe_tool");
        assertThatThrownBy(() -> AssistantToolContracts.validate("project.rename", Map.of(
                "project_id", "p-1", "new_name", "新名称", "invented", true)))
                .hasMessageContaining("不支持字段")
                .hasMessageContaining("invented");
        assertThatThrownBy(() -> AssistantToolContracts.validate("knowledge.search", Map.of(
                "project_id", "p-1", "query", "收入", "limit", "10")))
                .hasMessageContaining("必须是 integer")
                .hasMessageContaining("String");
    }

    @Test
    void rejectsUnsupportedEnumValuesAndMalformedWorkflowPatch() {
        assertThatThrownBy(() -> AssistantToolContracts.validate("deliverable.create", Map.of(
                "project_id", "p-1", "format", "HTML")))
                .hasMessageContaining("只支持")
                .hasMessageContaining("HTML_SLIDES");
        assertThatThrownBy(() -> AssistantToolContracts.validate("workflow.edit", Map.of(
                "workflow_id", "w-1", "patch", Map.of("unknown", "value"))))
                .hasMessageContaining("参数.patch")
                .hasMessageContaining("不支持字段");
    }

    @Test
    void distinguishesTextPatchesFromStructuredWorkflowPatches() {
        AssistantToolContracts.validate("resource.edit", Map.of(
                "project_id", "p-1", "resource_id", "r-1", "patch", "完整的长文本内容"));
        assertThatThrownBy(() -> AssistantToolContracts.validate("deliverable.edit", Map.of(
                "deliverable_id", "d-1", "patch", Map.of("content", "错误类型"))))
                .hasMessageContaining("参数.patch 必须是 string");
    }
}
