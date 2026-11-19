package com.finflow.studio.assistant;

import com.finflow.studio.deliverable.DeliverableModels.*;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class FixedToolExecutor {
    private final WorkerClient worker;
    private final KnowledgeService knowledge;
    private final DeliverableService deliverables;
    private final ObjectMapper json;
    public FixedToolExecutor(WorkerClient worker, KnowledgeService knowledge, DeliverableService deliverables, ObjectMapper json) {
        this.worker = worker; this.knowledge = knowledge; this.deliverables = deliverables; this.json = json;
    }

    @SuppressWarnings("unchecked")
    public FixedWorkService.Bound execute(String project, FixedWorkDefinition.Step step, Map<String, String> inputs, Runnable check) {
        var call = FixedToolCall.parse(json, step);
        var args = (Map<String, Object>) FixedToolCall.bind(call.arguments(), inputs, json);
        call.validateArguments(args);
        check.run();
        if ("browser.run".equals(call.tool())) {
            var result = worker.browser("run", args);
            check.run();
            if (!(result.get("audit") instanceof Map<?, ?> audit) || !"SUCCEEDED".equals(audit.get("status")))
                throw new IllegalStateException("浏览器执行未通过结果校验");
            var bytes = Base64.getDecoder().decode(Objects.toString(result.get("contentBase64"), ""));
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new IllegalStateException("浏览器下载文件为空或过大");
            var name = step.outputName().replaceFirst("(?i)\\.csv$", "");
            var output = knowledge.importBytes(project, name + ".csv", "text/csv", bytes);
            var evidence = new LinkedHashMap<String, Object>();
            audit.forEach((k, v) -> evidence.put(String.valueOf(k), v));
            evidence.put("outputResourceId", output.id());
            var suffix = "-" + output.id().substring(0, 8);
            knowledge.importBytes(project, name + "-执行记录" + suffix + ".json", "application/json", json.writeValueAsString(evidence).getBytes(StandardCharsets.UTF_8));
            if (result.get("screenshotBase64") instanceof String screenshot && !screenshot.isBlank())
                knowledge.importBytes(project, name + "-页面凭证" + suffix + ".png", "image/png", Base64.getDecoder().decode(screenshot));
            return new FixedWorkService.Bound(output.id(), output.currentVersion(), output.name(), "FILE");
        }
        if ("document.render".equals(call.tool())) {
            if (!(args.get("document") instanceof Map<?, ?> document)) throw new IllegalArgumentException("文档输入必须是包含sections的JSON对象");
            var sections = json.convertValue(normalizeSections(document.get("sections")), SectionRequest[].class);
            if (sections == null || sections.length == 0 || sections.length > 100) throw new IllegalArgumentException("文档需要1至100个内容章节");
            for (var section : sections) if (section == null || section.heading() == null || section.heading().isBlank())
                throw new IllegalArgumentException("文档章节缺少标题");
            // The renderer consumes supplied sections; it must never draft or rewrite them through an agent.
            var title = Objects.toString(document.get("title"), "").strip();
            if (title.isBlank()) title = step.outputName().replaceFirst("(?i)\\.(pptx|docx|pdf|html)$", "");
            var format = String.valueOf(args.get("format"));
            var pptSkill = Set.of("pptx", "html_slides").contains(format.toLowerCase(Locale.ROOT))
                    ? Objects.toString(args.get("pptSkill"), "") : "";
            var output = deliverables.create(new CreateRequest(project, null, title,
                    Objects.toString(document.get("subtitle"), ""), format,
                    pptSkill, !Boolean.FALSE.equals(document.get("includeCitations")),
                    "IEEE", Arrays.asList(sections), "fixed",
                    document.get("presentationBrief") instanceof Map<?, ?> brief
                            ? (Map<String, Object>) brief : Map.of()));
            return new FixedWorkService.Bound(output.id(), output.currentVersion(), output.name(), "DELIVERABLE");
        }
        Object result;
        switch (call.tool()) {
            case "research.discover" -> {
                var response = worker.discoverResearchSources(required(args, "topic"), limit(args));
                var status = Objects.toString(response.get("status"), "");
                if (Set.of("unavailable", "failed").contains(status))
                    throw new IllegalStateException("联网搜索失败：" + Objects.toString(response.get("summary"), "搜索服务暂不可用"));
                if ("empty".equals(status))
                    throw new IllegalStateException("联网搜索已完成，但没有匹配来源；请调整检索范围或关键词后重试");
                if (!(response.get("sources") instanceof List<?> sources) || sources.isEmpty())
                    throw new IllegalStateException("搜索未返回来源；请检查搜索服务，不会把空结果当作调研完成");
                result = response;
            }
            case "research.fetch" -> result = fetch(required(args, "url"));
            case "research.collect" -> {
                var raw = args.get("sources");
                if (raw instanceof Map<?, ?> map) raw = map.get("sources");
                if (!(raw instanceof List<?> sources) || sources.isEmpty()) throw new IllegalArgumentException("采集输入需要非空sources列表");
                var collected = new ArrayList<Map<String, Object>>();
                var failures = new ArrayList<Map<String, Object>>();
                for (var source : sources.stream().limit(limit(args)).toList()) {
                    check.run();
                    var url = source instanceof Map<?, ?> map ? Objects.toString(map.get("url"), "") : Objects.toString(source, "");
                    try {
                        collected.add(fetch(url));
                    } catch (RuntimeException exception) {
                        // Cancellation must stop the run, not become a failed source.
                        check.run();
                        if (Thread.currentThread().isInterrupted()) throw exception;
                        failures.add(Map.of("url", url, "error",
                                Objects.toString(exception.getMessage(), "网页读取失败")));
                    }
                }
                if (collected.isEmpty()) throw new IllegalStateException("网页采集全部失败（" + failures.size()
                        + "个来源）；首个失败地址：" + failures.getFirst().get("url")
                        + "；原因：" + failures.getFirst().get("error"));
                result = Map.of("sources", collected, "failedSources", failures,
                        "status", failures.isEmpty() ? "complete" : "partial",
                        "summary", failures.isEmpty() ? "全部候选来源已读取"
                                : "部分候选来源未能读取；报告只能引用sources中的正文，并须披露failedSources及证据局限，不能把采集成功视为事实已核验");
            }
            default -> throw new IllegalArgumentException("未注册的固定执行工具");
        }
        check.run();
        var file = knowledge.importBytes(project, step.outputName().replaceFirst("(?i)\\.json$", "") + ".json",
                "application/json", json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8));
        return new FixedWorkService.Bound(file.id(), file.currentVersion(), file.name(), "FILE");
    }
    private Map<String, Object> fetch(String url) {
        var response = worker.fetchResearchSource(url);
        if (Objects.toString(response.get("text"), "").isBlank()) throw new IllegalStateException("网页没有正文，不生成空快照");
        return response;
    }
    private Object normalizeSections(Object raw) {
        if (!(raw instanceof List<?> sections)) return raw;
        return sections.stream().map(item -> {
            if (!(item instanceof Map<?, ?> section)) return item;
            var normalized = new LinkedHashMap<String, Object>();
            section.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            if (section.get("citations") instanceof List<?> citations) {
                normalized.put("citations", citations.stream().map(value -> {
                    if (!(value instanceof Map<?, ?> citation)) return value;
                    var ref = new LinkedHashMap<String, Object>();
                    for (var key : List.of("id", "resourceId", "sourceName", "text", "contentHash"))
                        if (citation.get(key) != null) ref.put(key, String.valueOf(citation.get(key)));
                    // External web citations have no internal resource version.
                    ref.put("version", citation.get("version") == null ? 0 : citation.get("version"));
                    ref.putIfAbsent("sourceName", Objects.toString(citation.get("title"), "未命名资料"));
                    if (!ref.containsKey("id") && citation.get("url") != null)
                        ref.put("id", String.valueOf(citation.get("url")));
                    var location = new LinkedHashMap<String, Object>();
                    if (citation.get("location") instanceof Map<?, ?> existing)
                        existing.forEach((key, v) -> location.put(String.valueOf(key), v));
                    for (var key : List.of("url", "publisher", "publishedAt"))
                        if (citation.get(key) != null) location.put(key, citation.get(key));
                    ref.put("location", location);
                    return ref;
                }).toList());
            }
            return normalized;
        }).toList();
    }
    private static String required(Map<String, Object> args, String name) {
        if (!(args.get(name) instanceof String value) || value.isBlank() || value.length() > 20000)
            throw new IllegalArgumentException("工具参数无效：" + name);
        return value.strip();
    }
    private static int limit(Map<String, Object> args) {
        int n = args.get("maxSources") instanceof Number value ? value.intValue() : 5;
        if (n < 3 || n > 20) throw new IllegalArgumentException("来源数量必须为3至20");
        return n;
    }
}
