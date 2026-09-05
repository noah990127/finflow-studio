package com.finflow.studio.workflow;

import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.SectionRequest;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkflowDeliverableService {
    private static final int MAX_PLANNING_SOURCE_CHARS = 30_000;
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW");
    private static final Set<String> CITATION_STYLES = Set.of("IEEE", "APA_7", "GB_T_7714");

    private final WorkerClient worker;
    private final DeliverableService deliverables;
    private final WorkflowRunEventService events;
    private final ObjectMapper objectMapper;
    private final WorkflowContextAssembler contextAssembler;

    public WorkflowDeliverableService(WorkerClient worker, DeliverableService deliverables,
                                      WorkflowRunEventService events, ObjectMapper objectMapper,
                                      WorkflowContextAssembler contextAssembler) {
        this.worker = worker;
        this.deliverables = deliverables;
        this.events = events;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
    }

    public Map<String, Object> create(String projectId, String runId, NodeDefinition node,
                                      Map<String, Object> config,
                                      Map<String, Map<String, Object>> upstream,
                                      int startProgress, int endProgress) {
        var requirements = required(config, "generationPrompt");
        var sourceText = contextAssembler.collectText(upstream);
        if (sourceText.isBlank()) throw new IllegalStateException("生成成果没有可读取的上游内容，请先连接资料、数据或分析节点");

        events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", startProgress,
                "正在理解生成要求，规划标题与内容结构", "");
        var planningRequirements = requirements
                + "\n\n根据用户意图与上游资料自主拟定标题、副标题和章节结构，判断合适的使用对象与篇幅。"
                + "用户已明确选择的成果类型、引用和 Skill 必须遵守："
                + objectMapper.writeValueAsString(outputPreferences(config));
        var planning = worker.generateContent("DELIVERABLE_PLAN", planningRequirements,
                sourceText.substring(0, Math.min(sourceText.length(), MAX_PLANNING_SOURCE_CHARS)));
        var planningMode = Objects.toString(planning.get("mode"), "");
        if (planningMode.contains("fallback")) throw new IllegalStateException("大模型当前不可用，无法规划生成成果");
        var plan = applyOutputPreferences(parsePlan(Objects.toString(planning.get("content"), "")), config);
        var format = plannedFormat(plan);
        var title = plannedText(plan, "title", "成果标题", 280);
        var subtitle = optional(plan, "subtitle", "");
        var heading = plannedText(plan, "heading", "主章节", 300);
        var includeCitations = bool(plan, "include_citations");
        var citationStyle = plannedCitationStyle(plan);
        var pptSkill = plannedPptSkill(plan, format);

        var completeRequirements = requirements
                + "\n根据用户意图与资料内容自主安排章节、使用对象与篇幅，不套用固定受众或长度。"
                + "\n\n已确定的成果规格：\n成果形式：" + format
                + "\n标题：" + title + "\n副标题：" + subtitle + "\n主章节：" + heading
                + presentationRequirement(pptSkill) + "\n"
                + contextAssembler.citationRequirement(includeCitations, citationStyle);
        var generationSource = sourceText;
        var referenceCatalog = contextAssembler.referenceCatalog(upstream);
        if (includeCitations && !referenceCatalog.isBlank()) {
            generationSource += "\n\n--- 可用参考来源（正文必须使用对应编号） ---\n" + referenceCatalog;
        }

        events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", startProgress + 1,
                "已选择 " + format + "，正在生成“" + title + "”", "");
        var span = Math.max(1, endProgress - startProgress);
        var generation = worker.generateContentStreaming(format, completeRequirements, generationSource, event -> {
            var type = Objects.toString(event.get("type"), "status");
            var localProgress = event.get("progress") instanceof Number number ? number.intValue() : 50;
            var progress = Math.min(endProgress - 1, startProgress + Math.max(1, localProgress * span / 100));
            if ("content".equals(type)) {
                events.publish(runId, "MODEL_OUTPUT", node.id(), node.name(), "RUNNING", progress,
                        "正在生成成果内容", Objects.toString(event.get("content"), ""));
            } else if (!"complete".equals(type)) {
                events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", progress,
                        Objects.toString(event.get("message"), "正在生成成果"), "");
            }
        });
        var generationMode = Objects.toString(generation.get("mode"), "");
        if (generationMode.contains("fallback")) throw new IllegalStateException("大模型当前不可用，成果内容未生成");

        var generatedBody = Objects.toString(generation.get("content"), sourceText);
        var refIds = includeCitations ? contextAssembler.refIds(upstream) : List.<String>of();
        var section = new SectionRequest(heading, List.of(generatedBody), stringList(findValue(upstream, "points")),
                refIds, includeCitations ? contextAssembler.citations(upstream) : List.of());
        events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING", Math.max(startProgress, endProgress - 1),
                "正在写入 " + format + " 文件", "");
        var item = deliverables.create(new CreateRequest(projectId, compatibleOutputResource(config, format), title,
                subtitle, format, pptSkill, includeCitations, citationStyle, List.of(section)));
        return Map.of("deliverableId", item.id(), "name", item.name(), "format", item.format(), "outputPlan", plan,
                "version", item.currentVersion(), "downloadUrl", "/api/deliverables/" + item.id() + "/download",
                "analysisMode", generationMode, "planningMode", planningMode);
    }

    Map<String, Object> applyOutputPreferences(Map<String, Object> modelPlan, Map<String, Object> config) {
        var plan = new LinkedHashMap<>(modelPlan);
        plan.putAll(outputPreferences(config));
        return Map.copyOf(plan);
    }

    private Map<String, Object> outputPreferences(Map<String, Object> config) {
        var plan = new LinkedHashMap<String, Object>();
        var format = optional(config, "format", "").toUpperCase(Locale.ROOT);
        if (!format.isBlank()) {
            if ("MERMAID".equals(format) && bool(config, "handDrawn")) format = "EXCALIDRAW";
            plan.put("format", format);
        }
        if (config.containsKey("includeCitations")) plan.put("include_citations", bool(config, "includeCitations"));
        if (config.containsKey("citationStyle")) plan.put("citation_style", optional(config, "citationStyle", "IEEE"));
        if (config.containsKey("pptSkill")) plan.put("ppt_skill", optional(config, "pptSkill", ""));
        return plan;
    }

    Map<String, Object> parsePlan(String value) {
        var clean = value == null ? "" : value.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        var objectStart = clean.indexOf('{');
        var objectEnd = clean.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) clean = clean.substring(objectStart, objectEnd + 1);
        try {
            Map<String, Object> plan = objectMapper.readValue(clean, new TypeReference<>() {});
            if (!plan.isEmpty()) return plan;
        } catch (JacksonException ignored) {
            // Report a stable domain error below instead of leaking parser details.
        }
        throw new IllegalStateException("大模型没有返回有效的成果规格");
    }

    private String plannedFormat(Map<String, Object> plan) {
        var format = optional(plan, "format", "").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(format)) throw new IllegalStateException("大模型返回了不支持的成果形式");
        return format;
    }

    private String plannedText(Map<String, Object> plan, String key, String label, int maxLength) {
        var value = optional(plan, key, "").trim();
        if (value.isBlank()) throw new IllegalStateException("大模型没有给出" + label);
        return value.substring(0, Math.min(value.length(), maxLength));
    }

    private String plannedCitationStyle(Map<String, Object> plan) {
        var style = optional(plan, "citation_style", "IEEE").toUpperCase(Locale.ROOT);
        return CITATION_STYLES.contains(style) ? style : "IEEE";
    }

    private String plannedPptSkill(Map<String, Object> plan, String format) {
        if (!List.of("PPTX", "HTML_SLIDES").contains(format)) return "";
        var skill = optional(plan, "ppt_skill", "");
        if ("PPTX".equals(format) && "guizang-huawei-style-c".equals(skill)) return skill;
        if ("HTML_SLIDES".equals(format) && "frontend-slides".equals(skill)) return skill;
        return "";
    }

    private String compatibleOutputResource(Map<String, Object> config, String format) {
        var resourceId = optional(config, "outputResourceId", "");
        if (resourceId.isBlank()) return null;
        return deliverables.get(resourceId).format().equalsIgnoreCase(format) ? resourceId : null;
    }

    private String presentationRequirement(String pptSkill) {
        if ("guizang-huawei-style-c".equals(pptSkill)) {
            return "\nPPT 技能：华为企业汇报 Style C。采用结论先行的管理层叙事，避免模板化堆框。"
                    + "每页只表达一个判断；标题必须单行且不超过22个汉字，摘要不超过64个汉字，"
                    + "每页3条要点、每条不超过56个汉字，分别说清量化依据、业务影响和决策或动作；"
                    + "禁止手工换行、长段落和重复措辞。有可靠连续数据、分类比较或构成数据时生成原生图表，"
                    + "正文中至少40%的页面应在有可靠数值时配置图表，并用一句话解释其业务含义。";
        }
        if ("frontend-slides".equals(pptSkill)) {
            return "\n演示技能：Frontend Slides 网页演示。产物是 HTML + JavaScript，不是 PowerPoint 文件。"
                    + "内容应适合浏览器全屏演示，强调视觉层级、页面节奏、图表证据与版式变化；"
                    + "每页只表达一个判断，标题不超过22个汉字，每页2至3条要点，有可靠数值时优先使用图表。";
        }
        return "";
    }

    private String required(Map<String, Object> config, String key) {
        var value = Objects.toString(config.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("步骤缺少配置：" + key);
        return value;
    }

    private String optional(Map<String, Object> value, String key, String fallback) {
        var text = Objects.toString(value.get(key), "").trim();
        return text.isBlank() ? fallback : text;
    }

    private boolean bool(Map<String, Object> value, String key) {
        return Boolean.TRUE.equals(value.get(key)) || "true".equalsIgnoreCase(Objects.toString(value.get(key), ""));
    }

    private Object findValue(Map<String, Map<String, Object>> values, String key) {
        return values.values().stream().map(value -> value.get(key)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private List<String> stringList(Object value) {
        return list(value).stream().map(item -> Objects.toString(item, "")).filter(item -> !item.isBlank()).toList();
    }
}
