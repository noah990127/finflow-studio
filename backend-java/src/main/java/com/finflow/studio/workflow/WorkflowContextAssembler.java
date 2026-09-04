package com.finflow.studio.workflow;

import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.deliverable.DeliverableModels.CitationRequest;
import com.finflow.studio.knowledge.KnowledgeService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkflowContextAssembler {
    static final int MAX_CONTEXT_CHARS = 180_000;
    private static final int MAX_FILE_PREVIEW_BYTES = 300_000;
    private static final Set<String> TEXT_PREVIEW_EXTENSIONS = Set.of(
            "csv", "tsv", "txt", "md", "json", "jsonl", "xml", "html", "htm", "yaml", "yml", "sql");

    private final KnowledgeService knowledge;
    private final ExtractJobService extracts;

    public WorkflowContextAssembler(KnowledgeService knowledge, ExtractJobService extracts) {
        this.knowledge = knowledge;
        this.extracts = extracts;
    }

    public String collectText(Map<String, Map<String, Object>> values) {
        var result = new StringBuilder();
        for (var output : values.values()) {
            var hasParsedText = false;
            for (var key : List.of("analysis", "summary", "text")) {
                var value = Objects.toString(output.get(key), "").trim();
                if (!value.isBlank()) {
                    result.append(value).append('\n');
                    hasParsedText = true;
                }
            }
            for (var item : list(output.get("refs"))) {
                if (item instanceof Map<?, ?> map) {
                    var value = Objects.toString(map.get("text"), "").trim();
                    if (!value.isBlank()) {
                        result.append(value).append('\n');
                        hasParsedText = true;
                    }
                }
            }
            var url = Objects.toString(output.get("url"), "");
            if (!url.isBlank()) result.append("网站资料：").append(Objects.toString(output.get("title"), url))
                    .append(' ').append(url).append('\n');
            var fileId = Objects.toString(output.get("fileId"), "");
            if (!fileId.isBlank() && !hasParsedText && knowledge != null) {
                appendFilePreview(result, knowledge.filePath(fileId,
                        output.get("version") instanceof Number number ? number.intValue() : null));
            }
            var extractId = Objects.toString(output.get("extractJobId"), "");
            if (!extractId.isBlank() && extracts != null) appendFilePreview(result, extracts.outputPath(extractId));
        }
        var text = result.toString().trim();
        if (text.length() <= MAX_CONTEXT_CHARS) return text;
        return text.substring(0, MAX_CONTEXT_CHARS) + "\n[工作流上下文较长，已按安全上限截断]";
    }

    public String referenceCatalog(Map<String, Map<String, Object>> context) {
        var byId = new LinkedHashMap<String, Map<?, ?>>();
        for (var output : context.values()) {
            for (var item : list(output.get("refs"))) {
                if (item instanceof Map<?, ?> map) {
                    var id = Objects.toString(map.get("id"), "");
                    if (!id.isBlank()) byId.putIfAbsent(id, map);
                }
            }
        }
        var catalog = new StringBuilder();
        var ids = refIds(context);
        for (var index = 0; index < ids.size(); index++) {
            var ref = byId.get(ids.get(index));
            if (ref == null) continue;
            catalog.append("[Ref ").append(index + 1).append("] ")
                    .append(Objects.toString(ref.get("sourceName"), "项目资料"));
            var location = ref.get("location");
            if (location != null) catalog.append("，位置：").append(location);
            var text = Objects.toString(ref.get("text"), "").replaceAll("\\s+", " ").trim();
            if (!text.isBlank()) catalog.append("，摘录：").append(text, 0, Math.min(text.length(), 500));
            catalog.append('\n');
        }
        return catalog.toString().trim();
    }

    public List<String> refIds(Map<String, Map<String, Object>> context) {
        var ids = new LinkedHashSet<String>();
        context.values().forEach(output -> stringList(output.get("refIds")).forEach(ids::add));
        return List.copyOf(ids);
    }

    public List<CitationRequest> citations(Map<String, Map<String, Object>> context) {
        var result = new LinkedHashMap<String, CitationRequest>();
        for (var output : context.values()) {
            for (var item : list(output.get("refs"))) {
                if (!(item instanceof Map<?, ?> ref)) continue;
                var id = Objects.toString(ref.get("id"), "");
                if (id.isBlank()) continue;
                var location = new LinkedHashMap<String, Object>();
                if (ref.get("location") instanceof Map<?, ?> rawLocation) {
                    rawLocation.forEach((key, value) -> location.put(Objects.toString(key), value));
                }
                result.putIfAbsent(id, new CitationRequest(id, Objects.toString(ref.get("resourceId"), ""),
                        ref.get("version") instanceof Number number ? number.intValue() : 0,
                        Objects.toString(ref.get("sourceName"), "未命名资料"), Objects.toString(ref.get("text"), ""),
                        location, Objects.toString(ref.get("contentHash"), "")));
            }
        }
        return List.copyOf(result.values());
    }

    public String citationRequirement(boolean include, String style) {
        if (!include) return "来源标注：关闭。正文、图表和页脚中不要输出 [Ref N]、来源编号或参考文献。";
        return switch (style) {
            case "APA_7" -> "来源标注：开启，使用 APA 第 7 版。正文采用（机构或作者, 年份）格式；缺少年份时使用 n.d.；图表 source_ref 使用相同格式；末尾生成按作者排序的参考文献。";
            case "GB_T_7714" -> "来源标注：开启，使用 GB/T 7714-2015 顺序编码制。正文和图表使用 [1]、[2] 编号；末尾生成对应编号的参考文献。";
            default -> "来源标注：开启，使用 IEEE 顺序编码制。正文和图表使用 [1]、[2] 编号；末尾生成对应编号的参考文献。";
        };
    }

    private void appendFilePreview(StringBuilder result, Path path) {
        var name = path.getFileName() == null ? "" : path.getFileName().toString();
        var separator = name.lastIndexOf('.');
        var extension = separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!TEXT_PREVIEW_EXTENSIONS.contains(extension)) return;
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(MAX_FILE_PREVIEW_BYTES);
            if (bytes.length == 0) return;
            result.append("\n--- 结构化数据预览 ---\n").append(new String(bytes, StandardCharsets.UTF_8));
            if (input.read() >= 0) result.append("\n[数据较大，以上为前 300KB 预览]");
            result.append('\n');
        } catch (Exception ignored) {
            // Dedicated file-processing nodes remain responsible for non-text files.
        }
    }

    private List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private List<String> stringList(Object value) {
        return list(value).stream().map(item -> Objects.toString(item, "")).filter(item -> !item.isBlank()).toList();
    }
}
