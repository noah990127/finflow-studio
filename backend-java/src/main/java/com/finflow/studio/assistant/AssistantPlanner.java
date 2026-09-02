package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import com.finflow.studio.assistant.AssistantModels.Selection;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class AssistantPlanner {
    private final WorkerClient worker;

    public AssistantPlanner(WorkerClient worker) { this.worker = worker; }

    public PlannedWork plan(String goal, String page, Selection selection) {
        return plan(goal, page, selection, WorkspaceContext.empty());
    }

    public PlannedWork plan(String goal, String page, Selection selection, WorkspaceContext context) {
        var text = goal == null ? "" : goal.trim();
        var normalized = text.toLowerCase(Locale.ROOT);
        var steps = new ArrayList<PlanStep>();
        var resourceId = selection == null ? null : selection.resourceId();

        steps.add(step(1, "workspace.inspect", "READ", "了解当前工作",
                contextDescription(page, context), RiskLevel.READ_ONLY,
                mapOf("project_id", context.projectId(), "resource_id", resourceId, "page", page, "goal", text)));

        var navigationTarget = navigationTarget(normalized, context);
        if (navigationTarget != null) {
            steps.add(step(steps.size() + 1, "workspace.navigate", "READ", "打开工作区域",
                    navigationDescription(navigationTarget, context), RiskLevel.READ_ONLY,
                    mapOf("target", navigationTarget, "resource_id", resourceId)));
        }

        if (isCreateProjectIntent(normalized)) {
            var topic = inferProjectTopic(text);
            var projectName = projectName(topic);
            var discoverSources = asksForSources(normalized) || asksForAnalysis(normalized);
            var createWorkflow = asksForWorkflow(normalized) || asksForAnalysis(normalized) || discoverSources;
            var formats = requestedFormats(normalized);
            steps.add(step(steps.size() + 1, "project.create_workspace", "WRITE", "创建项目",
                    "新建“" + projectName + "”个人项目", RiskLevel.CREATE_VERSION,
                    Map.of("project_name", projectName, "topic", topic,
                            "description", "围绕" + topic + "组织数据、资料、工作流和成果。")));
            if (discoverSources) {
                steps.add(step(steps.size() + 1, "knowledge.discover_external_sources", "READ", "查找相关资料",
                        "查找与主题相关的公开资料入口，并标记待核实内容", RiskLevel.READ_ONLY,
                        Map.of("topic", topic, "max_sources", 12)));
            }
            if (createWorkflow) {
                steps.add(step(steps.size() + 1, "workflow.initialize", "WRITE", "建立工作流",
                        workflowDescription(formats), RiskLevel.CREATE_VERSION,
                        Map.of("topic", topic, "goal", text, "include_analysis", asksForAnalysis(normalized),
                                "output_formats", formats)));
            }
            return new PlannedWork(projectPlanSummary(projectName, discoverSources, createWorkflow, formats), List.copyOf(steps));
        }

        var hasStructuredInput = context.hasStructuredData() || isStructuredSelection(context, selection);
        if (asksForDataWork(normalized)) {
            if (hasStructuredInput) {
                steps.add(step(steps.size() + 1, "dataset.profile", "READ", "检查相关数据",
                        "查看可用字段、数据规模和质量情况", RiskLevel.READ_ONLY,
                        mapOf("resource_id", resourceId, "goal", text)));
                if (asksToModifyData(normalized)) {
                    steps.add(step(steps.size() + 1, "workflow.add_data_transform", "WRITE", "准备数据加工",
                            "在工作流中创建透明可见的数据加工草稿，不覆盖原始数据", RiskLevel.CREATE_VERSION,
                            mapOf("project_id", context.projectId(), "resource_id", resourceId,
                                    "resource_type", context.selectedResourceType(), "resource_name", context.selectedResourceName(),
                                    "goal", text)));
                }
            } else {
                steps.add(step(steps.size() + 1, "assistant.respond", "READ", "确认所需输入",
                        "说明当前缺少可处理的数据，并给出下一步选择", RiskLevel.READ_ONLY,
                        mapOf("goal", text, "reason", "NO_STRUCTURED_DATA")));
            }
        }

        if (asksForSources(normalized)) {
            steps.add(step(steps.size() + 1, "knowledge.discover_external_sources", "READ", "查找相关资料",
                    "按当前主题查找公开资料入口，并保留来源", RiskLevel.READ_ONLY,
                    Map.of("topic", inferProjectTopic(text), "max_sources", 12)));
        }

        if (asksForAnalysis(normalized) && !asksToModifyData(normalized)) {
            steps.add(step(steps.size() + 1, "assistant.analyze_context", "READ", "分析相关内容",
                    resourceId == null ? "从当前项目已有内容中提取与问题相关的信息" : "读取并分析当前选中的内容",
                    RiskLevel.READ_ONLY, mapOf("project_id", context.projectId(), "resource_id", resourceId,
                            "resource_name", context.selectedResourceName(), "goal", text)));
        }

        if (asksForWorkflow(normalized)) {
            steps.add(step(steps.size() + 1, "workflow.prepare", "WRITE", "创建工作流",
                    "在当前项目中新建一条可继续编排的工作流", RiskLevel.CREATE_VERSION,
                    mapOf("project_id", context.projectId(), "goal", text, "resource_id", resourceId,
                            "resource_type", context.selectedResourceType(), "resource_name", context.selectedResourceName(),
                            "output_formats", requestedFormats(normalized))));
        } else if (asksToAddSelectionToWorkflow(normalized) && resourceId != null) {
            steps.add(step(steps.size() + 1, "workflow.add_selected_resource", "WRITE", "加入工作流",
                    "把当前内容加入项目工作流，供后续步骤使用", RiskLevel.CREATE_VERSION,
                    mapOf("project_id", context.projectId(), "resource_id", resourceId,
                            "resource_type", context.selectedResourceType(), "resource_name", context.selectedResourceName())));
        }

        var formats = requestedFormats(normalized);
        if (!formats.isEmpty() && !asksForWorkflow(normalized)) {
            steps.add(step(steps.size() + 1, "workflow.add_outputs", "WRITE", "配置输出成果",
                    "在当前工作流中加入用户明确指定的输出类型", RiskLevel.CREATE_VERSION,
                    mapOf("project_id", context.projectId(), "goal", text, "output_formats", formats,
                            "resource_id", resourceId, "resource_type", context.selectedResourceType(),
                            "resource_name", context.selectedResourceName())));
        }

        if (steps.size() == 1) {
            steps.add(step(steps.size() + 1, "assistant.respond", "READ", "回答并给出下一步",
                    "结合当前项目和所选内容回答，不自行创建文件或报表", RiskLevel.READ_ONLY,
                    mapOf("goal", text)));
        }
        return new PlannedWork(modelSummary(text, steps, context), List.copyOf(steps));
    }

    private boolean isCreateProjectIntent(String text) { return containsAny(text, "新建", "新增", "创建") && text.contains("项目"); }
    private boolean asksForWorkflow(String text) { return text.contains("工作流") && containsAny(text, "新建", "新增", "创建", "搭建", "编排", "建立"); }
    private boolean asksToAddSelectionToWorkflow(String text) { return text.contains("工作流") && containsAny(text, "加入", "添加", "放进", "拖进"); }
    private boolean asksForAnalysis(String text) { return containsAny(text, "分析", "总结", "归因", "洞察", "复盘", "研究", "对比"); }
    private boolean asksForDataWork(String text) { return containsAny(text, "数据", "字段", "清理", "整理", "异常", "表格", "excel", "csv", "关联", "合并", "计算"); }
    private boolean asksToModifyData(String text) { return containsAny(text, "清理", "整理", "加工", "关联", "合并", "计算", "转换", "去重", "补全"); }
    private boolean asksForSources(String text) { return containsAny(text, "找资料", "查资料", "搜集资料", "收集资料", "搜索资料", "联网", "公开资料", "参考资料", "信息来源"); }

    private boolean isStructuredSelection(WorkspaceContext context, Selection selection) {
        return selection != null && (isStructuredType(context.selectedResourceType()) || isStructuredType(selection.type()));
    }

    private boolean isStructuredType(String type) {
        var normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return containsAny(normalized, "DATA_FILE", "DATASET", "DATABASE", "API_CONNECTION", "CSV", "EXCEL", "SPREADSHEET");
    }

    private String navigationTarget(String text, WorkspaceContext context) {
        if (!containsAny(text, "打开", "进入", "查看", "跳到", "带我去")) return null;
        if (text.contains("工作流")) return "WORKFLOW";
        if (containsAny(text, "数据采集", "数据库", "数据服务")) return "DATA";
        if (containsAny(text, "项目概览", "项目首页", "首页")) return "HOME";
        if (context.selectedResourceId() != null && containsAny(text, "这个", "当前", "文件", "资料", "输出件")) return "RESOURCE";
        return null;
    }

    private List<String> requestedFormats(String text) {
        var formats = new ArrayList<String>();
        if (containsAny(text, "ppt", "powerpoint", "演示文稿", "演示汇报")) formats.add("PPTX");
        if (text.contains("汇报") && containsAny(text, "生成", "输出", "制作", "形成")) formats.add("PPTX");
        if (containsAny(text, "word", "docx", "文档报告")) formats.add("DOCX");
        if (text.contains("pdf")) formats.add("PDF");
        if (text.contains("mermaid")) formats.add("MERMAID");
        if (containsAny(text, "excalidraw", "手绘图")) formats.add("EXCALIDRAW");
        if (containsAny(text, "html幻灯", "网页幻灯", "html slides")) formats.add("HTML_SLIDES");
        if (containsAny(text, "交互报告", "可交互报告", "图表报告", "财务报告")) formats.add("FINANCIAL_REPORT");
        return formats.stream().distinct().toList();
    }

    String inferProjectTopic(String goal) {
        var text = goal == null ? "" : goal.replaceFirst("(?s)\\n\\n本次重点分析文件：.*$", "")
                .replaceAll("[\\r\\n]+", " ").trim();
        var afterProject = Pattern.compile("(?:新建|新增|创建)(?:一个|个)?项目[，,：: ]*(?:用来|用于|做|分析|研究)?(.{2,60})").matcher(text);
        if (afterProject.find()) return cleanTopic(afterProject.group(1));
        var beforeProject = Pattern.compile("(?:新建|新增|创建)(?:一个|个)?(?:关于|针对)?(.{2,48}?)(?:的)?(?:分析|研究|盘点|复盘|汇报)?项目").matcher(text);
        if (beforeProject.find()) return cleanTopic(beforeProject.group(1));
        return cleanTopic(text);
    }

    private String cleanTopic(String value) {
        var topic = value == null ? "" : value.trim();
        topic = topic.replaceFirst("^(请|麻烦|帮我|给我|我想要?|需要|想要|做一个|做个)+", "");
        var focus = Pattern.compile("(?:分析|研究|盘点|复盘)(.{2,60})").matcher(topic);
        if (focus.find()) topic = focus.group(1);
        topic = topic.split("(?:然后|并且|同时|接着|之后|再|输出|生成|包括|支持|用来|用于|就可以)", 2)[0];
        topic = topic.replaceAll("^(一个|个|关于|针对)", "")
                .replaceAll("(?:的)?(?:分析|研究|盘点|复盘|汇报)?项目$", "")
                .replaceAll("近([一二三四五六七八九十0-9]+)年的", "近$1年")
                .replaceAll("经营情况[、和及与]?战略规划(?:等)?", "经营与战略")
                .replaceAll("等$", "").replaceAll("[，,。！？；;：:]+$", "").trim();
        if (topic.isBlank()) return "新的工作";
        return topic.substring(0, Math.min(topic.length(), 24));
    }

    private String projectName(String topic) {
        var name = topic.matches(".*(?:分析|研究|盘点|复盘|报告|汇报)$") ? topic : topic + "分析";
        return name.substring(0, Math.min(name.length(), 30));
    }

    private String contextDescription(String page, WorkspaceContext context) {
        if (context.selectedResourceName() != null) return "读取当前项目和“" + context.selectedResourceName() + "”的基本信息";
        return "读取当前项目、" + (page == null ? "工作台" : page) + "和可用内容";
    }

    private String workflowDescription(List<String> formats) {
        return formats.isEmpty() ? "按目标建立资料与分析步骤，不预设任何输出件"
                : "按目标建立处理步骤，并仅加入明确要求的 " + String.join("、", formats) + " 输出";
    }

    private String projectPlanSummary(String name, boolean sources, boolean workflow, List<String> formats) {
        var actions = new ArrayList<String>();
        actions.add("创建项目“" + name + "”");
        if (sources) actions.add("查找相关资料");
        if (workflow) actions.add("建立工作流");
        if (!formats.isEmpty()) actions.add("配置 " + String.join("、", formats) + " 输出");
        return "我会" + String.join("、", actions) + "。只会创建你明确要求的内容，执行前需要确认。";
    }

    private String navigationDescription(String target, WorkspaceContext context) {
        return switch (target) {
            case "WORKFLOW" -> "切换到项目工作流画布";
            case "DATA" -> "切换到数据采集区域";
            case "RESOURCE" -> "打开“" + (context.selectedResourceName() == null ? "当前内容" : context.selectedResourceName()) + "”";
            default -> "返回项目概览";
        };
    }

    private String modelSummary(String goal, List<PlanStep> steps, WorkspaceContext context) {
        var changesWorkspace = steps.stream().anyMatch(PlanStep::requiresConfirmation);
        var selected = context.selectedResourceName() == null ? "当前项目" : "“" + context.selectedResourceName() + "”";
        return "我会结合" + selected + "完成“" + shorten(goal, 36) + "”。"
                + (changesWorkspace ? "涉及工作台修改，开始前需要确认。" : "不会自行创建或修改内容。");
    }

    private String shorten(String value, int length) {
        var safe = value == null || value.isBlank() ? "当前任务" : value.trim();
        return safe.substring(0, Math.min(safe.length(), length));
    }

    private PlanStep step(int order, String tool, String mode, String title, String description, RiskLevel risk, Map<String, Object> arguments) {
        return new PlanStep(UUID.randomUUID().toString(), order, tool, mode, title, description, arguments, risk, risk.requiresConfirmation(), "PENDING");
    }

    private Map<String, Object> mapOf(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (var index = 0; index + 1 < values.length; index += 2) if (values[index + 1] != null) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private boolean containsAny(String text, String... values) {
        for (var value : values) if (text.contains(value)) return true;
        return false;
    }

    public record WorkspaceContext(String projectId, String projectName, int dataCount, int knowledgeCount,
                                   int outputCount, boolean hasStructuredData, String selectedResourceId,
                                   String selectedResourceType, String selectedResourceName) {
        public static WorkspaceContext empty() { return new WorkspaceContext(null, "当前项目", 0, 0, 0, false, null, null, null); }
    }

    public record PlannedWork(String summary, List<PlanStep> steps) { }
}
