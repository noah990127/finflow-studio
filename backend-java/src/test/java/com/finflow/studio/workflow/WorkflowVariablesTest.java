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
    @Test void publishesMysteryResultAsTextAndKeepsItsStructuredFields() {
        var output = WorkflowVariables.publish(NodeType.MYSTERY, Map.of(
                "content", "1. 确认输入\n2. 分析偏差\n3. 生成报告",
                "detectedKind", "notes",
                "understanding", "这是一项每月重复的偏差分析工作",
                "inferredIntent", "把记录变成可复用流程",
                "outputKind", "WORKFLOW_DRAFT",
                "confidence", 0.86,
                "assumptions", List.of("运行前需确认数据口径"),
                "workSteps", List.of(Map.of("title", "确认输入")),
                "suggestedNext", "连接数据提取节点"));

        assertThat(output).containsEntry("output", "1. 确认输入\n2. 分析偏差\n3. 生成报告")
                .containsEntry("outputKind", "WORKFLOW_DRAFT")
                .containsEntry("confidence", 0.86)
                .containsEntry("sources", List.of());
        assertThat(WorkflowVariables.outputs(NodeType.MYSTERY)).extracting(WorkflowVariables.Field::name)
                .contains("content", "understanding", "inferredIntent", "workSteps", "suggestedNext");
    }
    @Test void validatesExplicitBindingsAndIgnoresNames() {
        var target = node("target", Map.of("inputSource", Map.of("nodeId", "source", "path", List.of("output"))));
        WorkflowVariables.validate(document(target), target);
        assertThat(WorkflowVariables.inputSource(target.config()).nodeId()).isEqualTo("source");
        assertThatThrownBy(() -> WorkflowVariables.inputSource(Map.of("inputSource", Map.of("nodeId", "source", "path", "output"))))
                .hasMessageContaining("输入变量配置无效");
    }
    @Test void compactsLongResearchTopicsToTheBusinessSubject() {
        var query = "请查找深圳福田今天下班后的文化活动。" + "补充背景".repeat(200)
                + "重点保留活动时间、地点和报名方式。";

        var compacted = WorkflowRunService.compactSearchTopic(query);

        assertThat(compacted).isEqualTo("深圳福田今天下班后的文化活动");
    }
    @Test void extractsTheSubjectBeforeExpandedResearchInstructions() {
        var query = "分析最近发生的尊界制动踏板断裂事件：基于可核验且成功读取的公开证据，"
                + "按发布时间梳理事件时间线，并生成带引用的中文PPTX。";

        assertThat(WorkflowRunService.compactSearchTopic(query)).isEqualTo("尊界制动踏板断裂事件");
    }
}
