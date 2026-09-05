package com.finflow.studio.assistant;

import com.finflow.studio.workflow.WorkflowModels.*;
import java.util.*;

/** Explicit, atomic graph edits. Unspecified nodes and config fields are preserved. */
final class WorkflowAgentPatch {
    static SaveRequest apply(WorkflowResponse current, Object raw, int version) {
        var patch = object(raw);
        allowed(patch, Set.of("name", "description", "nodes", "edges", "node_id", "config"));
        var nodes = new LinkedHashMap<String, NodeDefinition>();
        current.nodes().forEach(node -> nodes.put(node.id(), node));
        String name = current.name();
        String description = current.description();
        if (patch.containsKey("node_id")) {
            if (patch.containsKey("nodes") || patch.containsKey("edges") || patch.containsKey("description"))
                throw new IllegalArgumentException("单节点编辑仅支持 node_id、name、config");
            var id = text(patch.get("node_id"));
            if (!nodes.containsKey(id)) throw new IllegalArgumentException("工作流节点不存在：" + id);
            var nodePatch = new LinkedHashMap<>(patch);
            nodePatch.remove("node_id"); nodePatch.put("id", id);
            nodes.put(id, node(nodes.get(id), nodePatch));
        } else {
            if (patch.containsKey("config")) throw new IllegalArgumentException("节点配置必须放在 patch.nodes[].config 中");
            if (patch.containsKey("name")) name = text(patch.get("name"));
            if (patch.containsKey("description")) description = Objects.toString(patch.get("description"), "");
            if (patch.containsKey("nodes")) {
                var seen = new HashSet<String>();
                for (var rawNode : array(patch.get("nodes"))) {
                    var item = object(rawNode);
                    var id = text(item.get("id"));
                    if (!seen.add(id)) throw new IllegalArgumentException("节点 ID 重复：" + id);
                    nodes.put(id, node(nodes.get(id), item));
                }
            }
        }
        var edges = current.edges();
        if (patch.containsKey("edges")) {
            var updatedEdges = new ArrayList<EdgeDefinition>();
            var seen = new HashSet<String>();
            var ids = new HashSet<String>();
            for (var rawEdge : array(patch.get("edges"))) {
                var item = object(rawEdge);
                allowed(item, Set.of("id", "source", "target"));
                var source = text(item.get("source")); var target = text(item.get("target"));
                if (!nodes.containsKey(source) || !nodes.containsKey(target) || source.equals(target))
                    throw new IllegalArgumentException("连线必须指向两个不同的已有节点");
                if (!seen.add(source + "\n" + target)) throw new IllegalArgumentException("不能重复添加同一条连线");
                var id = item.containsKey("id") ? text(item.get("id")) : current.edges().stream()
                        .filter(edge -> edge.source().equals(source) && edge.target().equals(target))
                        .map(EdgeDefinition::id).findFirst().orElse("edge_" + UUID.nameUUIDFromBytes((source + "\n" + target).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (!ids.add(id)) throw new IllegalArgumentException("连线 ID 重复");
                updatedEdges.add(new EdgeDefinition(id, source, target));
            }
            edges = updatedEdges;
        }
        return new SaveRequest(name, description, List.copyOf(nodes.values()), edges, current.executionMode(), current.schedule(), version);
    }

    static NodeDefinition node(NodeDefinition old, Map<String, Object> patch) {
        allowed(patch, Set.of("id", "type", "name", "config", "position", "x", "y"));
        var type = patch.containsKey("type") ? NodeType.valueOf(text(patch.get("type")).toUpperCase(Locale.ROOT)) : old == null ? null : old.type();
        if (type == null) throw new IllegalArgumentException("新增节点必须提供 type");
        var config = new LinkedHashMap<String, Object>(old == null || old.config() == null ? Map.of() : old.config());
        if (patch.containsKey("config")) config.putAll(object(patch.get("config")));
        var promptField = switch (type) { case AI_ANALYSIS -> "prompt"; case AGENT_TASK -> "instruction"; case DELIVERABLE -> "generationPrompt"; default -> ""; };
        if (!promptField.isEmpty() && Objects.toString(config.get(promptField), "").isBlank())
            throw new IllegalArgumentException("节点必须提供完整的 config." + promptField + "，不能只填写名称或省略用户要求");
        var position = patch.containsKey("position") ? object(patch.get("position")) : Map.<String, Object>of();
        allowed(position, Set.of("x", "y"));
        return new NodeDefinition(text(patch.get("id")), type,
                patch.containsKey("name") ? text(patch.get("name")) : old == null ? type.name() : old.name(),
                coordinate(patch.getOrDefault("x", position.get("x")), old == null ? 0 : old.x()),
                coordinate(patch.getOrDefault("y", position.get("y")), old == null ? 0 : old.y()), config);
    }

    private static double coordinate(Object value, double fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("节点坐标必须是有限数字");
        return number.doubleValue();
    }
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("缺少必要的字符串字段");
        return text;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("nodes 和 edges 必须是数组");
        return list;
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("patch 必须是结构化对象：{nodes:[{id,type,name,config}],edges:[{source,target}]}，不接受文字或 JSON Patch 数组");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private static void allowed(Map<String, Object> value, Set<String> keys) {
        for (var key : value.keySet()) if (!keys.contains(key)) throw new IllegalArgumentException("不支持的编辑字段：" + key);
    }
}
