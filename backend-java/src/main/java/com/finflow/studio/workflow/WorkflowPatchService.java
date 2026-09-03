package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class WorkflowPatchService {
    private final WorkflowDefinitionService definitions;

    public WorkflowPatchService(WorkflowDefinitionService definitions) {
        this.definitions = definitions;
    }

    public PatchPreview preview(String workflowId, WorkflowPatch patch) {
        var workflow = definitions.get(workflowId);
        if (workflow.currentVersion() != patch.baseRevision()) {
            throw new IllegalStateException("工作流已被更新，请基于最新版本重新生成建议");
        }
        var document = applyOperations(new WorkflowDocument(workflow.name(), workflow.description(), workflow.nodes(),
                workflow.edges(), workflow.executionMode(), workflow.schedule()), patch.operations());
        var validation = definitions.validate(workflow.projectId(), document);
        var changes = patch.operations().stream().map(this::describe).toList();
        return new PatchPreview(document, validation, changes, patch.baseRevision());
    }

    public WorkflowResponse apply(String workflowId, WorkflowPatch patch) {
        var preview = preview(workflowId, patch);
        if (!preview.validation().valid()) {
            throw new IllegalArgumentException("工作流建议未通过检查：" + preview.validation().issues().getFirst().message());
        }
        var document = preview.document();
        return definitions.update(workflowId, new SaveRequest(document.name(), document.description(),
                document.nodes(), document.edges(), document.executionMode(), document.schedule(), patch.baseRevision()));
    }

    private WorkflowDocument applyOperations(WorkflowDocument current, List<PatchOperation> operations) {
        var nodes = new LinkedHashMap<String, NodeDefinition>();
        current.nodes().forEach(node -> nodes.put(node.id(), node));
        var edges = new LinkedHashMap<String, EdgeDefinition>();
        current.edges().forEach(edge -> edges.put(edge.id(), edge));
        for (var operation : operations) {
            switch (operation.op()) {
                case "add_node" -> {
                    if (operation.node() == null) throw new IllegalArgumentException("新增步骤缺少节点内容");
                    if (nodes.putIfAbsent(operation.node().id(), operation.node()) != null) throw new IllegalArgumentException("步骤标识已存在");
                }
                case "remove_node" -> {
                    nodes.remove(operation.nodeId());
                    edges.values().removeIf(edge -> edge.source().equals(operation.nodeId()) || edge.target().equals(operation.nodeId()));
                }
                case "add_edge" -> {
                    if (operation.edge() == null) throw new IllegalArgumentException("新增连线缺少内容");
                    if (edges.putIfAbsent(operation.edge().id(), operation.edge()) != null) throw new IllegalArgumentException("连线标识已存在");
                }
                case "remove_edge" -> edges.remove(operation.edgeId());
                case "update_config" -> {
                    var node = requireNode(nodes, operation.nodeId());
                    var config = new LinkedHashMap<>(node.config() == null ? java.util.Map.of() : node.config());
                    if (operation.patch() != null) config.putAll(operation.patch());
                    nodes.put(node.id(), new NodeDefinition(node.id(), node.type(), node.name(), node.x(), node.y(), config));
                }
                case "move_node" -> {
                    var node = requireNode(nodes, operation.nodeId());
                    var x = number(operation.patch(), "x", node.x());
                    var y = number(operation.patch(), "y", node.y());
                    nodes.put(node.id(), new NodeDefinition(node.id(), node.type(), node.name(), x, y, node.config()));
                }
                case "rename_node" -> {
                    var node = requireNode(nodes, operation.nodeId());
                    var name = operation.patch() == null ? "" : String.valueOf(operation.patch().getOrDefault("name", "")).trim();
                    if (name.isBlank()) throw new IllegalArgumentException("步骤名称不能为空");
                    nodes.put(node.id(), new NodeDefinition(node.id(), node.type(), name, node.x(), node.y(), node.config()));
                }
                default -> throw new IllegalArgumentException("不支持的工作流修改：" + operation.op());
            }
        }
        return new WorkflowDocument(current.name(), current.description(), List.copyOf(nodes.values()),
                List.copyOf(edges.values()), current.executionMode(), current.schedule());
    }

    private NodeDefinition requireNode(LinkedHashMap<String, NodeDefinition> nodes, String id) {
        var node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("要修改的步骤不存在");
        return node;
    }

    private double number(java.util.Map<String, Object> values, String key, double fallback) {
        if (values == null) return fallback;
        return values.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private String describe(PatchOperation operation) {
        return switch (operation.op()) {
            case "add_node" -> "新增步骤：" + (operation.node() == null ? "" : operation.node().name());
            case "remove_node" -> "删除步骤：" + operation.nodeId();
            case "add_edge" -> "新增连接";
            case "remove_edge" -> "删除连接";
            case "update_config" -> "调整步骤设置：" + operation.nodeId();
            case "move_node" -> "移动步骤：" + operation.nodeId();
            case "rename_node" -> "重命名步骤：" + operation.nodeId();
            default -> operation.op();
        };
    }
}
