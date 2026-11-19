package com.finflow.studio.workflow;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Service
public class MysteryNodeService {
    private static final List<String> TARGETS = List.of("AUTO", "ORGANIZE", "TASK", "WORKFLOW", "DATA", "ANALYZE");
    private static final List<String> OUTPUT_KINDS = List.of(
            "CLEAN_CONTENT", "TASK_BRIEF", "WORKFLOW_DRAFT", "STRUCTURED_DATA", "ANALYSIS");

    private final WorkerClient worker;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;

    public MysteryNodeService(WorkerClient worker, ObjectMapper objectMapper, ProjectService projects) {
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.projects = projects;
    }

    public Map<String, Object> preview(String projectId, String source, String hint, String target) {
        projects.get(projectId);
        var normalizedSource = requireSource(source);
        var result = worker.generateContent("MYSTERY", requirements(hint, target), normalizedSource);
        return parse(result);
    }

    public Map<String, Object> transformStreaming(String source, String hint, String target,
                                                  Consumer<Map<String, Object>> eventConsumer) {
        var normalizedSource = requireSource(source);
        var result = worker.generateContentStreaming("MYSTERY", requirements(hint, target), normalizedSource,
                eventConsumer);
        return parse(result);
    }

    private String requireSource(String source) {
        var value = Objects.toString(source, "").strip();
        if (value.isBlank()) throw new IllegalArgumentException("神秘节点没有收到内容");
        if (value.length() > 200_000) throw new IllegalArgumentException("神秘节点一次最多处理 20 万字符");
        return value;
    }

    private String requirements(String hint, String target) {
        var normalizedTarget = Objects.toString(target, "AUTO").strip().toUpperCase(Locale.ROOT);
        if (normalizedTarget.isBlank()) normalizedTarget = "AUTO";
        if (!TARGETS.contains(normalizedTarget)) throw new IllegalArgumentException("神秘节点处理方向无效");
        var normalizedHint = Objects.toString(hint, "").strip();
        return "处理方向：" + normalizedTarget + "\n用户补充：" +
                (normalizedHint.isBlank() ? "没有补充，请根据内容谨慎推测" : normalizedHint);
    }

    private Map<String, Object> parse(Map<String, Object> response) {
        var raw = Objects.toString(response.get("content"), "").strip();
        if (raw.startsWith("```")) {
            raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").strip();
        }
        final Map<String, Object> value;
        try { value = objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (JacksonException exception) {
            throw new IllegalStateException("神秘节点没有返回可识别的结果，请重试或明确告诉它想变成什么", exception);
        }
        var outputKind = text(value, "output_kind", "CLEAN_CONTENT").toUpperCase(Locale.ROOT);
        if (!OUTPUT_KINDS.contains(outputKind)) throw new IllegalStateException("神秘节点返回了未知的输出类型");
        var content = text(value, "content", "").strip();
        if (content.isBlank()) throw new IllegalStateException("神秘节点没有生成可继续使用的内容");

        var output = new LinkedHashMap<String, Object>();
        output.put("title", text(value, "title", "神秘节点结果"));
        output.put("detectedKind", text(value, "detected_kind", "mixed"));
        output.put("understanding", text(value, "understanding", "已读取用户提供的内容"));
        output.put("inferredIntent", text(value, "inferred_intent", "整理为可继续处理的内容"));
        output.put("outputKind", outputKind);
        output.put("content", content);
        output.put("workSteps", list(value.get("work_steps")));
        var assumptions = stringList(value.get("assumptions"));
        output.put("assumptions", assumptions);
        output.put("suggestedNext", text(value, "suggested_next", "连接下一个处理步骤"));
        output.put("confidence", confidence(value.get("confidence")));
        var modelNeedsConfirmation = value.get("needs_confirmation") instanceof Boolean item ? item : true;
        output.put("needsConfirmation", modelNeedsConfirmation || !assumptions.isEmpty());
        output.put("mode", Objects.toString(response.get("mode"), "unknown"));
        return Map.copyOf(output);
    }

    private String text(Map<String, Object> value, String key, String fallback) {
        var result = Objects.toString(value.get(key), "").strip();
        return result.isBlank() ? fallback : result;
    }

    private List<Object> list(Object value) {
        return value instanceof List<?> items ? new ArrayList<>(items) : List.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(Object::toString).map(String::strip).filter(item -> !item.isBlank()).limit(20).toList();
    }

    private double confidence(Object value) {
        var number = value instanceof Number item ? item.doubleValue() : 0.5;
        return Math.max(0, Math.min(1, number));
    }
}
