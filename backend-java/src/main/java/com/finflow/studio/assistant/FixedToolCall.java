package com.finflow.studio.assistant;

import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Versioned, allowlisted functions; bindings replace values, never executable code. */
public record FixedToolCall(String tool, int version, Map<String, Object> arguments) {
    private record PendingInput(String alias) { }
    private static final Map<String, Set<String>> PARAMETERS = Map.of(
            "research.discover", Set.of("topic", "maxSources"),
            "research.fetch", Set.of("url"),
            "research.collect", Set.of("sources", "maxSources"),
            "browser.run", Set.of("script", "parameters"),
            "document.render", Set.of("format", "pptSkill", "document"));

    public static FixedToolCall parse(ObjectMapper json, FixedWorkDefinition.Step step) {
        FixedToolCall call;
        try { call = json.readValue(step.code(), FixedToolCall.class); }
        catch (tools.jackson.core.JacksonException error) { throw new IllegalArgumentException("固定工具code必须是有效的工具调用JSON", error); }
        if (call == null || call.version != 1 || call.tool == null || !PARAMETERS.containsKey(call.tool) || call.arguments == null
                || !PARAMETERS.get(call.tool).containsAll(call.arguments.keySet()))
            throw new IllegalArgumentException("固定工具名称、版本或参数不受支持");
        var required = switch (call.tool) {
            case "research.discover" -> "topic";
            case "research.fetch" -> "url";
            case "research.collect" -> "sources";
            case "browser.run" -> "script";
            default -> "document";
        };
        if (!call.arguments.containsKey(required)) throw new IllegalArgumentException("固定工具缺少参数：" + required);
        if ("browser.run".equals(call.tool)) {
            if (!(call.arguments.get("script") instanceof Map<?, ?> script) || containsBinding(script))
                throw new IllegalArgumentException("浏览器脚本必须固化，不允许用输入替换操作逻辑");
            if (!call.arguments.containsKey("parameters")) throw new IllegalArgumentException("浏览器执行缺少parameters");
        }
        if ("document.render".equals(call.tool) && !Set.of("pptx", "docx", "pdf", "html_slides").contains(Objects.toString(call.arguments.get("format"), "")))
            throw new IllegalArgumentException("文档格式必须固定为pptx、docx、pdf或html_slides");
        var aliases = new HashSet<String>();
        step.inputs().forEach(i -> aliases.add(i.alias()));
        validateBindings(call.arguments, aliases);
        @SuppressWarnings("unchecked")
        var pending = (Map<String, Object>) bind(call.arguments, Map.of(), json, true);
        call.validateArguments(pending);
        return call;
    }

    private static void validateBindings(Object value, Set<String> aliases) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$input")) {
                if (!Set.of("$input", "as", "path").containsAll(map.keySet()) || !aliases.contains(map.get("$input"))
                        || !Set.of("text", "json").contains(Objects.toString(map.get("as"), "text")))
                    throw new IllegalArgumentException("无效输入绑定；仅允许声明的别名及text/json格式");
                if (map.containsKey("path") && (!(map.get("path") instanceof String path)
                        || !path.matches("(?:/(?:[^~/]|~[01])*)*") || !"json".equals(map.get("as"))))
                    throw new IllegalArgumentException("字段绑定path必须是JSON Pointer，且as必须为json，例如/topic");
            } else map.values().forEach(v -> validateBindings(v, aliases));
        } else if (value instanceof List<?> list) list.forEach(v -> validateBindings(v, aliases));
    }

    private static boolean containsBinding(Object value) {
        if (value instanceof Map<?, ?> map) return map.containsKey("$input") || map.values().stream().anyMatch(FixedToolCall::containsBinding);
        return value instanceof List<?> list && list.stream().anyMatch(FixedToolCall::containsBinding);
    }

    public static Object bind(Object value, Map<String, String> inputs, ObjectMapper json) {
        return bind(value, inputs, json, false);
    }

    public static Object bind(Object value, Map<String, String> inputs, ObjectMapper json, boolean partial) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$input")) {
                var text = inputs.get(String.valueOf(map.get("$input")));
                if (text == null && partial) return new PendingInput(String.valueOf(map.get("$input")));
                if (text == null) throw new IllegalArgumentException("缺少固定工具输入：" + map.get("$input"));
                try {
                    if (map.containsKey("path")) {
                        var selected = json.readTree(text).at(String.valueOf(map.get("path")));
                        if (selected.isMissingNode() || selected.isNull())
                            throw new IllegalArgumentException("输入 " + map.get("$input") + " 缺少字段：" + map.get("path"));
                        return json.convertValue(selected, Object.class);
                    }
                    return "json".equals(map.get("as")) ? json.readValue(text, Object.class) : text;
                } catch (tools.jackson.core.JacksonException error) {
                    throw new IllegalArgumentException("输入 " + map.get("$input") + " 不是有效JSON，请检查参数文件格式", error);
                }
            }
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(String.valueOf(k), bind(v, inputs, json, partial)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> bind(v, inputs, json, partial)).toList();
        return value;
    }

    public void validateArguments(Map<String, Object> args) {
        switch (tool) {
            case "research.discover" -> {
                var topic = args.get("topic");
                if (!binding(topic)) {
                    string(topic, "topic", 500);
                    if (((String) topic).strip().startsWith("{") || ((String) topic).strip().startsWith("["))
                        throw new IllegalArgumentException("topic不能使用整份JSON参数，请用path绑定具体检索字段，例如/topic");
                }
            }
            case "research.fetch" -> { if (!binding(args.get("url"))) url(args.get("url")); }
            case "research.collect" -> {
                var sources = args.get("sources");
                if (!binding(sources)) {
                    if (sources instanceof Map<?, ?> map) sources = map.get("sources");
                    if (!binding(sources)) {
                        if (!(sources instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("sources必须是非空来源数组或包含sources的对象");
                        for (var source : list) {
                            var value = source instanceof Map<?, ?> map && !binding(source) ? map.get("url") : source;
                            if (!binding(value)) url(value);
                        }
                    }
                }
            }
            case "document.render" -> {
                var format = Objects.toString(args.get("format"), "");
                var skill = args.get("pptSkill");
                if (skill != null && !binding(skill)) {
                    if (!(skill instanceof String)) throw new IllegalArgumentException("pptSkill必须为字符串");
                    if (!((String) skill).isBlank() && !com.finflow.studio.deliverable.DeliverableService.formatSupportsSkill(format, (String) skill))
                        throw new IllegalArgumentException("输出格式与模板不兼容：" + format + " / " + skill);
                }
                var doc = args.get("document");
                if (!binding(doc)) {
                    if (!(doc instanceof Map<?, ?> map)) throw new IllegalArgumentException("document必须为JSON对象");
                    for (var key : List.of("title", "subtitle")) optionalType(map, key, String.class);
                    optionalType(map, "includeCitations", Boolean.class);
                    optionalType(map, "presentationBrief", Map.class);
                    var sections = map.get("sections");
                    if (!binding(sections)) {
                        if (!(sections instanceof List<?> list) || list.isEmpty() || list.size() > 100)
                            throw new IllegalArgumentException("sections需要1至100个章节");
                        for (var section : list) if (!binding(section)) {
                            if (!(section instanceof Map<?, ?> s)) throw new IllegalArgumentException("章节必须为对象");
                            if (!binding(s.get("heading"))) string(s.get("heading"), "heading", Integer.MAX_VALUE);
                            for (var key : List.of("paragraphs", "bullets", "refIds")) {
                                optionalType(s, key, List.class);
                                if (s.get(key) instanceof List<?> values) for (var value : values)
                                    if (!binding(value) && !(value instanceof String)) throw new IllegalArgumentException(key + "的元素必须为字符串");
                            }
                            for (var key : List.of("chapter", "coreMessage", "layout")) optionalType(s,key,String.class);
                            for (var key : List.of("chart", "visual")) optionalType(s,key,Map.class);
                        }
                    }
                }
            }
            case "browser.run" -> {
                if (!binding(args.get("parameters")) && !(args.get("parameters") instanceof Map<?, ?>))
                    throw new IllegalArgumentException("浏览器parameters必须为JSON对象");
                if (args.get("parameters") instanceof Map<?, ?> parameters) {
                    if (parameters.size() > 30) throw new IllegalArgumentException("浏览器parameters最多30项");
                    for (var value : parameters.values()) if (!binding(value) && (!(value instanceof String text) || text.length() > 2000))
                        throw new IllegalArgumentException("浏览器参数值必须为不超过2000字符的字符串");
                }
            }
            default -> { }
        }
        if (args.containsKey("maxSources") && !binding(args.get("maxSources"))) {
            var n = args.get("maxSources");
            if (!(n instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 3 || number.intValue() > 20)
                throw new IllegalArgumentException("maxSources必须为3至20的整数，不能是字符串或小数");
        }
    }

    private static boolean binding(Object value) { return value instanceof PendingInput; }
    private static void optionalType(Map<?, ?> values, String key, Class<?> type) {
        var value=values.get(key);
        if (value != null && !binding(value) && !type.isInstance(value))
            throw new IllegalArgumentException(key + "类型必须为" + type.getSimpleName());
    }
    private static void string(Object value, String name, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.codePointCount(0, text.length()) > max)
            throw new IllegalArgumentException(name + "必须为非空字符串，最多" + max + "字符");
    }
    private static void url(Object value) {
        string(value, "url", 20000);
        try {
            var uri = java.net.URI.create((String) value);
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("url必须为有效的http/https网址"); }
    }
}
