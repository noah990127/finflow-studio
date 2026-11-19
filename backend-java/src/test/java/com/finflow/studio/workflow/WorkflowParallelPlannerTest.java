package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.WorkflowDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowParallelPlannerTest {
    @Test
    void runsIndependentNodesTogetherThenReleasesTheirDependentNode() {
        var document = document(
                List.of(node("left", NodeType.REF_SEARCH), node("right", NodeType.FILE_INPUT),
                        node("join", NodeType.AI_ANALYSIS)),
                List.of(edge("left", "join"), edge("right", "join")));
        var order = List.of("left", "right", "join");

        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of(), 4))
                .containsExactly("left", "right");
        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of("left", "right"), 4))
                .containsExactly("join");
    }

    @Test
    void honorsParallelLimitAndVariableDependencies() {
        var variableConsumer = new NodeDefinition("consumer", NodeType.DELIVERABLE, "consumer", 0, 0,
                Map.of("generationPrompt", "使用 {{source.output}} 生成成果", "format", "PPTX"));
        var document = document(List.of(node("source", NodeType.REF_SEARCH), node("free", NodeType.REF_SEARCH),
                variableConsumer), List.of());
        var order = List.of("source", "free", "consumer");

        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of(), 1)).containsExactly("source");
        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of("source", "free"), 4))
                .containsExactly("consumer");
    }

    @Test
    void treatsReviewAsABarrierAfterOtherReadyWork() {
        var document = document(List.of(node("review", NodeType.REVIEW), node("background", NodeType.REF_SEARCH)), List.of());
        var order = List.of("review", "background");

        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of(), 4)).containsExactly("background");
        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of("background"), 4)).containsExactly("review");
    }

    @Test
    void serializesInPlaceSpreadsheetWrites() {
        var document = document(List.of(node("write", NodeType.SPREADSHEET_TRANSFORM),
                node("read", NodeType.REF_SEARCH)), List.of());
        var order = List.of("write", "read");

        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of(), 4)).containsExactly("read");
        assertThat(WorkflowParallelPlanner.readyNodes(document, order, Set.of("read"), 4)).containsExactly("write");
    }

    private WorkflowDocument document(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDocument("parallel", "", nodes, edges);
    }

    private NodeDefinition node(String id, NodeType type) {
        var config = type == NodeType.REVIEW ? Map.<String, Object>of("instructions", "核对结果") : Map.<String, Object>of();
        return new NodeDefinition(id, type, id, 0, 0, config);
    }

    private EdgeDefinition edge(String source, String target) {
        return new EdgeDefinition(source + "-" + target, source, target);
    }
}
