package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class WorkflowVariablesTest {
    private NodeDefinition node(String id, Map<String, Object> config) {
        return new NodeDefinition(id, NodeType.AI_ANALYSIS, "任意名称" + id, 0, 0, config);
    }
    private WorkflowDocument document(NodeDefinition target) {
        return new WorkflowDocument("变量", "", List.of(node("source", Map.of()), node("middle", Map.of()), target),
                List.of(new EdgeDefinition("a", "source", "middle"), new EdgeDefinition("b", "middle", target.id())));
    }
    @Test void supportsAncestorReferencesNestedPathsAndLiteralModelContent() {
        var target = node("target", Map.of("prompt", "资料 {{#source.output#}} / {{#source.sources.0.sourceName#}}"));
        WorkflowVariables.validate(document(target), target);
        var value = WorkflowVariables.resolve(target.config(), Map.of("source", Map.of(
                "output", "保留 $1 和 {{#other.output#}}", "sources", List.of(Map.of("sourceName", "年报")))));
        assertThat(value).containsEntry("prompt", "资料 保留 $1 和 {{#other.output#}} / 年报");
    }
    @Test void rejectsUnconnectedSelfDownstreamMissingAndMalformedReferences() {
        for (var reference : List.of("target.output", "unknown.output", "source.missing", "source.output.child")) {
            var target = node("target", Map.of("prompt", "{{#" + reference + "#}}"));
            assertThatThrownBy(() -> WorkflowVariables.validate(document(target), target)).isInstanceOf(IllegalArgumentException.class);
        }
        var target = node("target", Map.of("prompt", "{{#source.output"));
        assertThatThrownBy(() -> WorkflowVariables.validate(document(target), target)).hasMessageContaining("未闭合");
        assertThatThrownBy(() -> WorkflowVariables.read(new WorkflowVariables.Selector("source", List.of("sources", "99")),
                Map.of("source", Map.of("sources", List.of())))).hasMessageContaining("没有产生变量");
    }
    @Test void keepsFalsyAndStructuredValuesWithoutExecutingCode() {
        var config = Map.<String, Object>of("prompt", "{{#source.output#}}", "script", "{{#source.output#}}");
        assertThat(WorkflowVariables.resolve(config, Map.of("source", Map.of("output", false))))
                .containsEntry("prompt", "false").containsEntry("script", "{{#source.output#}}");
        assertThat(WorkflowVariables.read(new WorkflowVariables.Selector("source", List.of("output")),
                Map.of("source", Map.of("output", Map.of("amount", 0))))).isEqualTo(Map.of("amount", 0));
    }
    @Test void publishesAliasesAndPreservesEvidenceWithoutDuplicatingSourceText() {
        var source = Map.<String, Object>of("id", "ref", "text", "原始证据", "sourceName", "年报");
        var output = WorkflowVariables.publish(NodeType.AI_ANALYSIS,
                Map.of("analysis", "分析结论", "refs", List.of(source), "refIds", List.of("ref")));
        assertThat(output).containsEntry("output", "分析结论").containsEntry("sources", List.of(source));
        var contexts = new WorkflowContextAssembler(null, null);
        assertThat(contexts.collectText(Map.of("analysis", output))).isEqualTo("分析结论");
        assertThat(contexts.sourceRefs(Map.of("analysis", output))).containsExactly(source);
        assertThat(contexts.referenceCatalog(Map.of("analysis", output))).contains("年报", "原始证据");
    }
    @Test void validatesExplicitBindingsAndIgnoresNames() {
        var target = node("target", Map.of("inputSource", Map.of("nodeId", "source", "path", List.of("output"))));
        WorkflowVariables.validate(document(target), target);
        assertThat(WorkflowVariables.inputSource(target.config()).nodeId()).isEqualTo("source");
        assertThatThrownBy(() -> WorkflowVariables.inputSource(Map.of("inputSource", Map.of("nodeId", "source", "path", "output"))))
                .hasMessageContaining("输入变量配置无效");
    }
}
