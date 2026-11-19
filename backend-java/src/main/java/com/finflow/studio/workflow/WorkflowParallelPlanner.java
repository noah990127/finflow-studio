package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.WorkflowDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkflowParallelPlanner {
    private WorkflowParallelPlanner() { }

    static List<String> readyNodes(WorkflowDocument document, List<String> topologicalOrder,
                                   Set<String> completed, int limit) {
        var ready = topologicalOrder.stream()
                .filter(nodeId -> !completed.contains(nodeId))
                .filter(nodeId -> completed.containsAll(dependencies(document, nodeId)))
                .toList();
        var nonReview = ready.stream()
                .filter(nodeId -> parallelSafe(document, nodeId))
                .limit(Math.max(1, limit))
                .toList();
        if (!nonReview.isEmpty()) return nonReview;
        return ready.stream().limit(1).toList();
    }

    private static Set<String> dependencies(WorkflowDocument document, String nodeId) {
        var result = new LinkedHashSet<String>();
        if (document.edges() != null) {
            document.edges().stream().filter(edge -> edge.target().equals(nodeId))
                    .forEach(edge -> result.add(edge.source()));
        }
        document.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst().ifPresent(node -> {
            var config = node.config() == null ? java.util.Map.<String, Object>of() : node.config();
            WorkflowVariables.references(config).forEach(reference -> result.add(reference.nodeId()));
            var input = WorkflowVariables.inputSource(config);
            if (input != null) result.add(input.nodeId());
        });
        return result;
    }

    private static boolean parallelSafe(WorkflowDocument document, String nodeId) {
        var node = document.nodes().stream().filter(value -> value.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工作流节点不存在：" + nodeId));
        if (node.type() == NodeType.REVIEW || node.type() == NodeType.SPREADSHEET_TRANSFORM) return false;
        return node.config() == null || !Boolean.FALSE.equals(node.config().get("parallelSafe"));
    }
}
