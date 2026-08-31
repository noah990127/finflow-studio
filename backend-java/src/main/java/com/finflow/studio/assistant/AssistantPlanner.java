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

    public AssistantPlanner(WorkerClient worker) {
        this.worker = worker;
    }

    public PlannedWork plan(String goal, String page, Selection selection) {
        var normalized = goal.toLowerCase(Locale.ROOT);
        var steps = new ArrayList<PlanStep>();
        var resourceId = selection == null ? null : selection.resourceId();

        steps.add(step(1, "project.get_summary", "READ", "了解当前工作",
                "读取当前项目、页面和进行中的任务", RiskLevel.READ_ONLY,
                mapOf("page", page)));

        if (isCreateProjectIntent(normalized)) {
            var topic = inferProjectTopic(goal);
            var projectName = projectName(topic);
            steps.add(step(2, "project.create_analysis_workspace", "WRITE", "创建分析项目",
                    "新建“" + projectName + "”个人项目空间", RiskLevel.CREATE_VERSION,
                    Map.of("project_name", projectName, "topic", topic,
                            "description", "围绕" + topic + "汇集数据与资料，形成可复用的分析工作流和管理层成果。")));
            steps.add(step(3, "knowledge.discover_external_sources", "READ", "搜集相关资料",
                    "联网查找官网、监管披露、行业、政策和财经媒体资料", RiskLevel.READ_ONLY,
                    Map.of("topic", topic, "max_sources", 12)));
            steps.add(step(4, "workflow.initialize_analysis", "WRITE", "建立主工作流",
                    "把资料检索、智能分析、财经报告和演示汇报编排起来", RiskLevel.CREATE_VERSION,
                    Map.of("topic", topic)));
            return new PlannedWork("我会先创建“" + projectName + "”，再联网整理可信资料并建立可直接继续编辑的主工作流。执行前需要你确认。", List.copyOf(steps));
        }

        if (containsAny(normalized, "数据", "字段", "清理", "整理", "异常", "表格", "excel")) {
            steps.add(step(steps.size() + 1, "dataset.profile", "READ", "检查数据质量",
                    "查看字段、空值、重复和异常分布", RiskLevel.READ_ONLY,
                    mapOf("resource_id", resourceId)));
        }
        if (containsAny(normalized, "资料", "文档", "ref", "来源", "报告", "汇报", "分析")) {
            steps.add(step(steps.size() + 1, "knowledge.search", "READ", "查找相关资料",
                    "检索项目资料并保留可回看的 Ref", RiskLevel.READ_ONLY,
                    mapOf("query", goal)));
        }
        if (containsAny(normalized, "清理", "整理", "加工", "写入", "生成新表")) {
            steps.add(step(steps.size() + 1, "dataset.create_clean_version", "WRITE", "生成整理结果",
                    "创建新的数据版本，不覆盖原始内容", RiskLevel.CREATE_VERSION,
                    mapOf("resource_id", resourceId)));
        }
        if (containsAny(normalized, "分析", "解释", "总结", "归因")) {
            steps.add(step(steps.size() + 1, "analysis.create_draft", "DRAFT", "形成分析草稿",
                    "区分事实、推断和待确认内容", RiskLevel.DRAFT_ONLY,
                    mapOf("goal", goal)));
        }
        if (containsAny(normalized, "汇报", "ppt", "word", "输出", "报告", "mermaid")) {
            steps.add(step(steps.size() + 1, "deliverable.create_draft", "DRAFT", "生成输出草稿",
                    "创建可编辑的输出草稿并检查 Ref", RiskLevel.DRAFT_ONLY,
                    mapOf("goal", goal)));
            steps.add(step(steps.size() + 1, "deliverable.export", "WRITE", "保存输出版本",
                    "生成新的交付文件版本", RiskLevel.CREATE_VERSION,
                    mapOf("format", inferFormat(normalized))));
        }

        if (steps.size() == 1) {
            steps.add(step(2, "analysis.create_draft", "DRAFT", "整理任务建议",
                    "根据当前项目形成一份可继续修改的建议", RiskLevel.DRAFT_ONLY,
                    mapOf("goal", goal)));
        }

        var summary = modelSummary(goal, steps);
        return new PlannedWork(summary, List.copyOf(steps));
    }

    private boolean isCreateProjectIntent(String text) {
        return containsAny(text, "新建", "新增", "创建") && text.contains("项目");
    }

    String inferProjectTopic(String goal) {
        var text = goal == null ? "" : goal
                .replaceFirst("(?s)\\n\\n本次重点分析文件：.*$", "")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        var afterProject = Pattern.compile(
                "(?:新建|新增|创建)(?:一个|个)?项目[，,：: ]*(?:用来|用于|做|分析|研究)?(.{2,60})")
                .matcher(text);
        if (afterProject.find()) return cleanTopic(afterProject.group(1));

        var beforeProject = Pattern.compile(
                "(?:新建|新增|创建)(?:一个|个)?(?:关于|针对)?(.{2,48}?)(?:的)?(?:分析|研究|盘点|复盘|汇报)?项目")
                .matcher(text);
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
                .replaceAll("等$", "")
                .replaceAll("[，,。！？；;：:]+$", "")
                .trim();
        if (topic.isBlank()) return "财经专题";
        return topic.substring(0, Math.min(topic.length(), 24));
    }

    private String projectName(String topic) {
        var name = topic.matches(".*(?:分析|研究|盘点|复盘|报告|汇报)$") ? topic : topic + "分析";
        return name.substring(0, Math.min(name.length(), 30));
    }

    private String modelSummary(String goal, List<PlanStep> steps) {
        try {
            var result = worker.summarize("""
                    你是 FinFlow Studio 的个人工作助手。请用不超过80字的中文概括下面任务计划，避免技术术语，说明修改前会确认。
                    用户目标：%s
                    步骤：%s
                    """.formatted(goal, steps.stream().map(PlanStep::title).toList()), "AI 助手任务计划", 3);
            return java.util.Objects.toString(result.get("summary"), "");
        } catch (RuntimeException ex) {
            return "我已结合当前页面整理出 " + steps.size() + " 个步骤。读取和草稿步骤可直接进行，修改内容前会请你确认。";
        }
    }

    private PlanStep step(int order, String tool, String mode, String title, String description,
                          RiskLevel risk, Map<String, Object> arguments) {
        return new PlanStep(UUID.randomUUID().toString(), order, tool, mode, title, description,
                arguments, risk, risk.requiresConfirmation(), "PENDING");
    }

    private Map<String, Object> mapOf(String key, Object value) {
        var values = new LinkedHashMap<String, Object>();
        if (value != null) {
            values.put(key, value);
        }
        return values;
    }

    private boolean containsAny(String text, String... values) {
        for (var value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String inferFormat(String text) {
        if (text.contains("word")) return "docx";
        if (text.contains("mermaid")) return "mermaid";
        if (text.contains("excel")) return "xlsx";
        return "pptx";
    }

    public record PlannedWork(String summary, List<PlanStep> steps) {
    }
}
