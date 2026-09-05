package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

/** Resolves run-scoped values only; variable contents are never evaluated as templates. */
public final class WorkflowVariables {
    private WorkflowVariables() { }
    private static final Pattern TOKEN = Pattern.compile("\\{\\{#([^#{}]+)#}}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> TEXT_FIELDS = Set.of("prompt", "generationPrompt", "instruction", "requirements", "query", "body", "instructions");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Field(String name, String type, String label) { }
    public record Selector(String nodeId, List<String> path) { }

    public static List<Field> outputs(NodeType type) {
        var fields = new ArrayList<Field>();
        var textual = Set.of(NodeType.AI_ANALYSIS, NodeType.AGENT_TASK, NodeType.FILE_INPUT,
                NodeType.LINK_INPUT, NodeType.REF_SEARCH).contains(type);
        fields.add(new Field("output", textual ? "string" : "object", textual ? "正文内容" : "输出结果"));
        fields.add(new Field("sources", "array", "参考来源"));
        fields.add(new Field("refIds", "array", "来源编号"));
        switch (type) {
            case AI_ANALYSIS, AGENT_TASK -> fields.add(new Field("analysis", "string", "分析正文"));
            case FILE_INPUT -> { fields.add(new Field("fileId", "string", "文件 ID")); fields.add(new Field("version", "number", "资料版本")); }
            case LINK_INPUT -> fields.add(new Field("url", "string", "网站地址"));
            case REF_SEARCH -> fields.add(new Field("count", "number", "匹配数量"));
            case DELIVERABLE, OUTPUT -> { fields.add(new Field("deliverableId", "string", "成果 ID")); fields.add(new Field("name", "string", "成果名称")); fields.add(new Field("format", "string", "成果格式")); fields.add(new Field("text", "string", "生成正文")); }
            case DATASET_INPUT, DATA_EXTRACT -> {
                fields.add(new Field("extractJobId", "string", "数据集 ID"));
                fields.add(new Field("rowCount", "number", "数据行数"));
            }
            case DATA_TRANSFORM, PROCESS, SPREADSHEET_TRANSFORM -> {
                fields.add(new Field("fileId", "string", "结果文件 ID"));
                fields.add(new Field("name", "string", "文件名称"));
            }
            default -> { }
        }
        if (Set.of(NodeType.FILE_INPUT, NodeType.DATASET_INPUT, NodeType.DATA_EXTRACT, NodeType.DATA_TRANSFORM,
                NodeType.PROCESS, NodeType.DELIVERABLE, NodeType.OUTPUT).contains(type))
            fields.add(new Field("downloadUrl", "string", "下载地址"));
        return List.copyOf(fields);
    }

    public static Set<String> ancestors(WorkflowDocument document, String nodeId) {
        var found = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            var target = queue.removeFirst();
            for (var edge : document.edges()) if (edge.target().equals(target) && found.add(edge.source())) queue.add(edge.source());
        }
        found.remove(nodeId);
        return found;
    }

    private static Selector token(String value) {
        var split = value.split("\\.", -1);
        if (split.length < 2 || Arrays.stream(split).anyMatch(String::isBlank))
            throw new IllegalArgumentException("变量格式无效：" + value);
        return new Selector(split[0], List.of(Arrays.copyOfRange(split, 1, split.length)));
    }

    public static Selector inputSource(Map<String, Object> config) {
        if (!config.containsKey("inputSource") || config.get("inputSource") == null) return null;
        if (!(config.get("inputSource") instanceof Map<?, ?> value)
                || !(value.get("nodeId") instanceof String nodeId) || nodeId.isBlank()
                || !(value.get("path") instanceof List<?> path) || path.isEmpty()
                || path.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException("输入变量配置无效，请重新选择上游字段");
        return new Selector(nodeId, path.stream().map(Object::toString).toList());
    }

    public static List<Selector> references(Map<String, Object> config) {
        var result = new ArrayList<Selector>();
        var source = inputSource(config);
        if (source != null) result.add(source);
        for (var key : TEXT_FIELDS) if (config.get(key) instanceof String text) {
            var matcher = TOKEN.matcher(text);
            while (matcher.find()) result.add(token(matcher.group(1)));
            if (matcher.replaceAll("").contains("{{#")) throw new IllegalArgumentException("变量引用未闭合，请重新插入变量");
        }
        return result;
    }

    public static void validate(WorkflowDocument document, NodeDefinition node) {
        var allowed = ancestors(document, node.id());
        for (var selector : references(node.config() == null ? Map.of() : node.config())) {
            if (!allowed.contains(selector.nodeId())) throw new IllegalArgumentException("引用的节点不是当前步骤的上游：" + selector.nodeId());
            var source = document.nodes().stream().filter(item -> item.id().equals(selector.nodeId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("引用的节点不存在：" + selector.nodeId()));
            var field = outputs(source.type()).stream().filter(item -> item.name().equals(selector.path().getFirst())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("上游输出字段不存在：" + source.name() + "." + selector.path().getFirst()));
            if (selector.path().size() > 1 && !Set.of("object", "array").contains(field.type()))
                throw new IllegalArgumentException("不能读取基础类型的子字段：" + source.name() + "." + field.name());
        }
    }

    public static Object read(Selector selector, Map<String, Map<String, Object>> context) {
        Object value = context.get(selector.nodeId());
        for (var part : selector.path()) {
            if (value instanceof Map<?, ?> map) value = map.get(part);
            else if (value instanceof List<?> list && part.matches("[0-9]+")) {
                try { value = list.get(Integer.parseInt(part)); }
                catch (IndexOutOfBoundsException | NumberFormatException exception) { value = null; }
            } else value = null;
            if (value == null) throw new IllegalArgumentException("本次运行没有产生变量：" + selector.nodeId() + "." + String.join(".", selector.path()));
        }
        return value;
    }

    public static String asText(Object value) { return value instanceof String text ? text : JSON.writeValueAsString(value); }

    public static Map<String, Object> resolve(Map<String, Object> config, Map<String, Map<String, Object>> context) {
        var result = new LinkedHashMap<>(config);
        for (var key : TEXT_FIELDS) if (config.get(key) instanceof String text) {
            var matcher = TOKEN.matcher(text);
            result.put(key, matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(asText(read(token(match.group(1)), context)))));
        }
        return result;
    }

    public static Map<String, Object> publish(NodeType type, Map<String, Object> raw) {
        var result = new LinkedHashMap<>(raw);
        if ("object".equals(outputs(type).getFirst().type()) && !(result.get("output") instanceof Map<?, ?>)) {
            var value = new LinkedHashMap<>(raw);
            value.remove("output");
            result.put("output", value);
        }
        if (!result.containsKey("output")) {
            if (Set.of(NodeType.AI_ANALYSIS, NodeType.AGENT_TASK).contains(type)) result.put("output", raw.getOrDefault("analysis", ""));
            else if (Set.of(NodeType.FILE_INPUT, NodeType.LINK_INPUT, NodeType.REF_SEARCH).contains(type)) {
                var parts = new ArrayList<String>();
                if (raw.get("text") instanceof String text) parts.add(text);
                else if (raw.get("refs") instanceof List<?> refs) for (var ref : refs)
                    if (ref instanceof Map<?, ?> map && map.get("text") instanceof String text) parts.add(text);
                result.put("output", String.join("\n\n", parts));
            } else result.put("output", new LinkedHashMap<>(raw));
        }
        result.putIfAbsent("sources", raw.getOrDefault("refs", List.of()));
        result.putIfAbsent("refIds", List.of());
        for (var field : outputs(type)) if (result.containsKey(field.name())) {
            var value = result.get(field.name());
            var valid = switch (field.type()) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "array" -> value instanceof List<?>;
                case "object" -> value instanceof Map<?, ?>;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("节点输出类型不匹配：" + field.name() + " 应为 " + field.type());
        }
        return result;
    }
}
