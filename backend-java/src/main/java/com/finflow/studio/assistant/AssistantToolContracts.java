package com.finflow.studio.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AssistantToolContracts {
    private static final Set<String> IDENTIFIERS = Set.of(
            "project_id", "resource_id", "folder_id", "parent_id", "target_parent_id", "target_folder_id",
            "target_id", "workflow_id", "node_id", "source_node_id", "target_node_id", "deliverable_id",
            "dataset_id", "source_id", "citation_id", "connection_id");
    private static final Set<String> ARRAYS = Set.of("resource_ids", "pages", "rows", "citations", "output_formats", "range");
    private static final Set<String> OBJECTS = Set.of("patch", "config", "position", "parameters", "schema",
            "connection", "parser_options");
    private static final Set<String> INTEGERS = Set.of("limit", "max_sources", "expected_version");
    private static final Set<String> BOOLEANS = Set.of("include_analysis", "include_citations", "hand_drawn");

    private AssistantToolContracts() { }

    static Map<String, Object> inputSchema(String tool, List<String> arguments) {
        var properties = new LinkedHashMap<String, Object>();
        for (var argument : arguments) properties.put(argument, property(tool, argument));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required(tool));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    static Map<String, Object> outputSchema(String tool) {
        if (tool.startsWith("workflow.")) return object(Map.of(
                "workflowId", string("真实工作流 ID"),
                "workflow", object(Map.of()),
                "changed", bool("是否发生真实修改")));
        if (tool.startsWith("deliverable.")) return object(Map.of(
                "deliverableId", string("真实交付件 ID"),
                "version", integer("已保存版本", 1),
                "downloadUrl", string("可访问的下载地址"),
                "refIds", array(string("引用 ID"))));
        if (tool.startsWith("dataset.")) return object(Map.of(
                "datasetId", string("真实数据集 ID"), "provenance", object(Map.of())));
        return object(Map.of());
    }

    static void validate(String tool, Map<String, Object> arguments) {
        var capability = AssistantCapabilityRegistry.find(tool)
                .orElseThrow(() -> new IllegalArgumentException("参数错误：工具 " + tool + " 未注册，请重新搜索工具"));
        validateObject(tool, arguments == null ? Map.of() : arguments, capability.inputSchema(), "参数");
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(String tool, Map<String, Object> value, Map<String, Object> schema, String path) {
        var properties = schema.get("properties") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.<String, Object>of();
        var required = schema.get("required") instanceof List<?> raw ? raw.stream().map(String::valueOf).toList() : List.<String>of();
        var missing = required.stream().filter(key -> !value.containsKey(key) || blank(value.get(key))).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 缺少必填字段 " + missing
                + "；请按 describe_tool 返回的 inputSchema 补全后重试");
        var allowAdditional = Boolean.TRUE.equals(schema.get("additionalProperties"));
        var unknown = allowAdditional ? List.<String>of()
                : value.keySet().stream().filter(key -> !properties.containsKey(key)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不支持字段 " + unknown
                + "；允许字段为 " + properties.keySet());
        for (var entry : value.entrySet()) {
            if (entry.getValue() == null) continue;
            var property = properties.get(entry.getKey());
            if (property instanceof Map<?, ?> raw) validateValue(tool, entry.getValue(), (Map<String, Object>) raw,
                    path + "." + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateValue(String tool, Object value, Map<String, Object> schema, String path) {
        var type = Objects.toString(schema.get("type"), "string");
        boolean valid = switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 必须是 " + type
                + "，当前收到 " + value.getClass().getSimpleName());
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number minimum && text.trim().length() < minimum.intValue())
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能为空");
            if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(item -> String.valueOf(item).equalsIgnoreCase(text)))
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 只支持 " + values + "，当前为 " + text);
        }
        if (value instanceof Number number && schema.get("minimum") instanceof Number minimum
                && number.doubleValue() < minimum.doubleValue())
            throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能小于 " + minimum);
        if (value instanceof List<?> values && schema.get("items") instanceof Map<?, ?> itemSchema)
            for (int index = 0; index < values.size(); index++)
                validateValue(tool, values.get(index), (Map<String, Object>) itemSchema, path + "[" + index + "]");
        if (value instanceof Map<?, ?> values && schema.containsKey("properties"))
            validateObject(tool, values.entrySet().stream().collect(LinkedHashMap::new,
                    (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), Map::putAll), schema, path);
    }

    private static boolean blank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static List<String> required(String tool) {
        return switch (tool) {
            case "workspace.select" -> List.of("target_id");
            case "project.open", "project.delete" -> List.of("project_id");
            case "project.rename" -> List.of("project_id", "new_name");
            case "folder.create" -> List.of("project_id", "name");
            case "folder.rename" -> List.of("project_id", "folder_id", "new_name");
            case "folder.move", "folder.delete" -> List.of("project_id", "folder_id");
            case "resource.upload" -> List.of("project_id", "file_name", "content");
            case "resource.add" -> List.of("project_id", "url");
            case "source.verify" -> List.of("url");
            case "source.add_verified" -> List.of("project_id", "url");
            case "resource.open", "resource.read", "resource.delete" -> List.of("project_id", "resource_id");
            case "resource.edit" -> List.of("project_id", "resource_id", "patch");
            case "resource.rename" -> List.of("project_id", "resource_id", "new_name");
            case "resource.move" -> List.of("project_id", "resource_id");
            case "knowledge.discover_external_sources" -> List.of("topic");
            case "knowledge.search" -> List.of("project_id", "query");
            case "knowledge.parse" -> List.of("resource_id");
            case "knowledge.extract_table" -> List.of("project_id", "resource_id");
            case "dataset.add_source" -> List.of("project_id", "name", "connection");
            case "dataset.connect" -> List.of("source_id");
            case "dataset.import" -> List.of("project_id", "source_id");
            case "dataset.extract" -> List.of("project_id", "resource_id");
            case "dataset.query" -> List.of("project_id", "dataset_id");
            case "dataset.create" -> List.of("project_id", "name", "rows");
            case "dataset.transform" -> List.of("project_id", "dataset_id", "requirements");
            case "dataset.open", "dataset.delete" -> List.of("project_id", "dataset_id");
            case "dataset.profile" -> List.of("project_id", "resource_id");
            case "workflow.prepare" -> List.of("project_id", "goal");
            case "workflow.open" -> List.of("project_id");
            case "workflow.edit" -> List.of("workflow_id", "patch");
            case "workflow.add_node" -> List.of("workflow_id", "node_type", "config");
            case "workflow.remove_node" -> List.of("workflow_id", "node_id");
            case "workflow.connect" -> List.of("workflow_id", "source_node_id", "target_node_id");
            case "workflow.run", "workflow.save_version" -> List.of("workflow_id");
            case "workflow.delete" -> List.of("project_id", "workflow_id");
            case "workflow.add_selected_resource", "workflow.add_data_transform" -> List.of("project_id", "resource_id");
            case "workflow.add_outputs" -> List.of("project_id", "output_formats");
            case "deliverable.create" -> List.of("project_id", "format");
            case "deliverable.open", "deliverable.export", "deliverable.delete" -> List.of("deliverable_id");
            case "deliverable.edit" -> List.of("deliverable_id", "patch");
            default -> List.of();
        };
    }

    private static Map<String, Object> property(String tool, String name) {
        if (IDENTIFIERS.contains(name)) return string("平台返回的真实内部 ID，不要使用名称或自行编造");
        if (INTEGERS.contains(name)) return integer(name.equals("expected_version") ? "最近读取到的版本号" : "正整数", name.equals("expected_version") ? 1 : 1);
        if (BOOLEANS.contains(name)) return bool("布尔开关");
        if (ARRAYS.contains(name)) {
            var item = switch (name) {
                case "pages" -> integer("页码", 1);
                case "rows", "citations" -> object(Map.of());
                case "output_formats" -> enumeration("输出格式", List.of("PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW"));
                default -> string("列表项");
            };
            return array(item);
        }
        if (OBJECTS.contains(name)) {
            if ("position".equals(name)) return object(Map.of("x", number("横坐标"), "y", number("纵坐标")));
            if ("patch".equals(name)) return "workflow.edit".equals(tool) ? workflowPatch()
                    : string("完整的新内容；长文本不会被截断");
            if ("config".equals(name) && "workflow.add_node".equals(tool)) return workflowConfig();
            return object(Map.of());
        }
        return switch (name) {
            case "format" -> enumeration("输出格式", List.of("PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW"));
            case "node_type" -> enumeration("工作流节点类型", List.of("FILE_INPUT", "LINK_INPUT", "DATABASE_INPUT", "DATA_PROCESS", "AI_ANALYSIS", "AGENT_TASK", "HUMAN_REVIEW", "DELIVERABLE", "OUTPUT"));
            case "group", "target_group" -> enumeration("工作区分组", List.of("FILES", "DATA", "KNOWLEDGE", "OUTPUT", "WORKFLOW"));
            case "source_type" -> enumeration("数据源类型", List.of("POSTGRESQL", "MYSQL", "OPENGAUSS", "GAUSS_DWS", "DUCKDB", "HTTP_API"));
            case "target" -> enumeration("导航目标", List.of("HOME", "PROJECT", "DATA", "WORKFLOW", "RESOURCE"));
            case "target_type" -> enumeration("选择对象类型", List.of("PROJECT", "FOLDER", "RESOURCE", "WORKFLOW", "DATASET", "DELIVERABLE"));
            case "url" -> Map.of("type", "string", "format", "uri", "minLength", 1, "description", "HTTP 或 HTTPS 地址");
            default -> string("工具参数 " + name);
        };
    }

    private static Map<String, Object> workflowPatch() {
        var node = object(Map.of(
                "id", string("稳定节点 ID"),
                "type", property("workflow.add_node", "node_type"),
                "name", string("节点显示名称"),
                "config", workflowConfig(),
                "position", property("workflow.add_node", "position")));
        var edge = object(Map.of("id", string("稳定连线 ID"), "source", string("起点节点 ID"), "target", string("终点节点 ID")));
        return object(Map.of(
                "name", string("工作流名称"), "description", string("工作流说明"),
                "nodes", array(node), "edges", array(edge),
                "node_id", string("单节点编辑时的节点 ID"), "config", workflowConfig()));
    }

    private static Map<String, Object> workflowConfig() {
        var properties = new LinkedHashMap<String, Object>();
        for (var key : List.of("resourceId", "url", "extractJobId", "connectionId", "sql", "requirements",
                "script", "outputName", "query", "prompt", "instruction", "instructions", "generationPrompt",
                "title", "body", "capability", "workflowId", "outputResourceId", "pptSkill")) {
            properties.put(key, string("节点配置 " + key));
        }
        properties.put("format", property("deliverable.create", "format"));
        properties.put("includeCitations", bool("是否保留引用"));
        properties.put("handDrawn", bool("是否使用手绘图表风格"));
        properties.put("citationStyle", enumeration("引用格式", List.of("IEEE", "APA_7", "GB_T_7714")));
        properties.put("maxPoints", integer("最大要点数", 1));
        return object(properties);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        var value = new LinkedHashMap<String, Object>();
        value.put("type", "object");
        value.put("properties", properties);
        value.put("additionalProperties", properties.isEmpty());
        return Map.copyOf(value);
    }

    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> string(String description) { return Map.of("type", "string", "minLength", 1, "description", description); }
    private static Map<String, Object> integer(String description, int minimum) { return Map.of("type", "integer", "minimum", minimum, "description", description); }
    private static Map<String, Object> number(String description) { return Map.of("type", "number", "description", description); }
    private static Map<String, Object> bool(String description) { return Map.of("type", "boolean", "description", description); }
    private static Map<String, Object> enumeration(String description, List<String> values) {
        return Map.of("type", "string", "enum", values, "description", description);
    }
}
