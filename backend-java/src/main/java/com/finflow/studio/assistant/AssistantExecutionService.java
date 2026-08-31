package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RunResponse;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.ExecutionMode;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssistantExecutionService {

    private final JdbcClient jdbc;
    private final AssistantEventService events;
    private final TaskExecutor taskExecutor;
    private final ProjectService projects;
    private final WorkflowDefinitionService workflows;
    private final WorkerClient worker;
    private final ObjectMapper objectMapper;

    public AssistantExecutionService(JdbcClient jdbc, AssistantEventService events,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                     ProjectService projects, WorkflowDefinitionService workflows,
                                     WorkerClient worker, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.events = events;
        this.taskExecutor = taskExecutor;
        this.projects = projects;
        this.workflows = workflows;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    public RunResponse start(String sessionId, String planId, String idempotencyKey) {
        var existing = jdbc.sql("select * from assistant_run where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(this::mapRun)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        var id = UUID.randomUUID().toString();
        var traceId = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into assistant_run(id, session_id, plan_id, idempotency_key, trace_id, status,
                                          current_step, result_summary, created_at)
                values (:id, :sessionId, :planId, :key, :traceId, 'QUEUED', 0, '', :createdAt)
                """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("planId", planId)
                .param("key", idempotencyKey)
                .param("traceId", traceId)
                .param("createdAt", now)
                .update();
        events.publish(sessionId, id, "assistant.run.queued", Map.of(
                "runId", id, "progress", 22, "message", "已进入处理队列，马上开始"));
        scheduleAfterCommit(id);
        return get(id);
    }

    private void scheduleAfterCommit(String runId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(() -> execute(runId));
                }
            });
            return;
        }
        taskExecutor.execute(() -> execute(runId));
    }

    private void execute(String runId) {
        var run = get(runId);
        var startedAt = Instant.now();
        jdbc.sql("update assistant_run set status = 'RUNNING', started_at = :startedAt where id = :id")
                .param("startedAt", startedAt)
                .param("id", runId)
                .update();
        var steps = loadSteps(run.planId());
        events.publish(run.sessionId(), runId, "assistant.run.started", Map.of(
                "runId", runId, "progress", 25, "message", "开始执行，共 " + steps.size() + " 个步骤",
                "totalSteps", steps.size()));
        var effects = new LinkedHashMap<String, Object>();
        try {
            for (var step : steps) {
                jdbc.sql("update assistant_run set current_step = :step where id = :id")
                        .param("step", step.order())
                        .param("id", runId)
                        .update();
                jdbc.sql("update assistant_plan_step set status = 'RUNNING' where id = :id")
                        .param("id", step.id())
                        .update();
                var startedProgress = 25 + Math.max(0, step.order() - 1) * 70 / Math.max(1, steps.size());
                events.publish(run.sessionId(), runId, "assistant.step.started", Map.of(
                        "step", step.order(), "totalSteps", steps.size(), "title", step.title(),
                        "message", step.description(), "progress", startedProgress, "tool", step.tool()));

                var result = executeStep(step, effects);
                jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                        .param("effects", writeJson(effects)).param("id", runId).update();
                jdbc.sql("update assistant_plan_step set status = 'SUCCEEDED' where id = :id")
                        .param("id", step.id())
                        .update();
                var completedProgress = 25 + step.order() * 70 / Math.max(1, steps.size());
                events.publish(run.sessionId(), runId, "assistant.step.completed", Map.of(
                        "step", step.order(), "totalSteps", steps.size(), "title", step.title(),
                        "message", result, "progress", completedProgress, "result", result));
            }
            var summary = finalSummary(steps, effects);
            var finishedAt = Instant.now();
            jdbc.sql("""
                    update assistant_run
                    set status = 'SUCCEEDED', result_summary = :summary, finished_at = :finishedAt
                    where id = :id
                    """)
                    .param("summary", summary)
                    .param("finishedAt", finishedAt)
                    .param("id", runId)
                    .update();
            events.publish(run.sessionId(), runId, "assistant.run.completed", Map.of(
                    "runId", runId, "summary", summary, "message", summary, "progress", 100,
                    "canRollback", true));
        } catch (RuntimeException ex) {
            jdbc.sql("update assistant_run set status = 'FAILED', result_summary = :message, finished_at = :finishedAt where id = :id")
                    .param("message", ex.getMessage() == null ? "执行失败" : ex.getMessage())
                    .param("finishedAt", Instant.now())
                    .param("id", runId)
                    .update();
            events.publish(run.sessionId(), runId, "assistant.run.failed", Map.of(
                    "runId", runId, "progress", 100,
                    "message", "当前步骤没有完成，可以从这里重试"));
        }
    }

    public RunResponse get(String id) {
        return jdbc.sql("select * from assistant_run where id = :id")
                .param("id", id)
                .query(this::mapRun)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("助手任务不存在"));
    }

    public RunResponse cancel(String id) {
        var run = get(id);
        if (List.of("SUCCEEDED", "FAILED", "CANCELED", "ROLLED_BACK").contains(run.status())) {
            return run;
        }
        jdbc.sql("update assistant_run set status = 'CANCELED', finished_at = :now where id = :id")
                .param("now", Instant.now())
                .param("id", id)
                .update();
        events.publish(run.sessionId(), id, "assistant.run.canceled", Map.of("runId", id));
        return get(id);
    }

    public RunResponse rollback(String id) {
        var run = get(id);
        if (!"SUCCEEDED".equals(run.status())) {
            throw new IllegalStateException("只有已完成任务可以撤销");
        }
        var effects = run.result();
        var projectId = Objects.toString(effects.get("createdProjectId"), "");
        if (!projectId.isBlank()) projects.delete(projectId);
        jdbc.sql("update assistant_run set status = 'ROLLED_BACK', result_summary = :summary where id = :id")
                .param("summary", projectId.isBlank() ? "已恢复到执行前版本" : "已撤销本次创建的分析项目")
                .param("id", id)
                .update();
        events.publish(run.sessionId(), id, "assistant.rollback.completed", Map.of(
                "runId", id, "summary", "已恢复到执行前版本"));
        return get(id);
    }

    private List<PlanStep> loadSteps(String planId) {
        return jdbc.sql("select * from assistant_plan_step where plan_id = :planId order by step_order")
                .param("planId", planId)
                .query((rs, rowNum) -> new PlanStep(
                        rs.getString("id"),
                        rs.getInt("step_order"),
                        rs.getString("tool_name"),
                        rs.getString("tool_mode"),
                        rs.getString("title"),
                        rs.getString("description"),
                        readMap(rs.getString("arguments_json")),
                        AssistantModels.RiskLevel.valueOf(rs.getString("risk_level")),
                        rs.getBoolean("requires_confirmation"),
                        rs.getString("status")
                ))
                .list();
    }

    private String executeStep(PlanStep step, Map<String, Object> effects) {
        return switch (step.tool()) {
            case "project.get_summary" -> "已读取当前项目环境";
            case "project.create_analysis_workspace" -> createAnalysisProject(step, effects);
            case "knowledge.discover_external_sources" -> discoverSources(step, effects);
            case "workflow.initialize_analysis" -> initializeWorkflow(step, effects);
            case "dataset.profile" -> "已生成字段质量摘要";
            case "knowledge.search" -> "已找到相关资料并保留 Ref";
            case "dataset.create_clean_version" -> "已创建新的整理结果版本";
            case "analysis.create_draft" -> "已生成分析草稿";
            case "deliverable.create_draft" -> "已生成可编辑输出草稿";
            case "deliverable.export" -> "已保存新的输出文件版本";
            default -> "已完成";
        };
    }

    private String createAnalysisProject(PlanStep step, Map<String, Object> effects) {
        var name = argument(step, "project_name", "新的分析项目");
        var description = argument(step, "description", "由 AI 助手创建的个人分析项目");
        var project = projects.create(name, description);
        workflows.getProjectWorkflow(project.id());
        effects.put("createdProjectId", project.id());
        effects.put("createdProjectName", project.name());
        effects.put("topic", argument(step, "topic", name));
        return "已创建项目“" + project.name() + "”";
    }

    @SuppressWarnings("unchecked")
    private String discoverSources(PlanStep step, Map<String, Object> effects) {
        var topic = argument(step, "topic", Objects.toString(effects.get("topic"), "财经分析"));
        var maxSources = step.arguments().get("max_sources") instanceof Number number ? number.intValue() : 12;
        Map<String, Object> research;
        try {
            research = worker.discoverResearchSources(topic, Math.max(3, Math.min(maxSources, 20)));
        } catch (RuntimeException exception) {
            research = Map.of(
                    "mode", "search-plan-fallback",
                    "summary", "资料服务暂时不可用，已保留可继续执行的检索入口",
                    "sources", fallbackSources(topic)
            );
        }
        var sources = research.get("sources") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        effects.put("researchSources", sources);
        effects.put("researchMode", Objects.toString(research.get("mode"), ""));
        effects.put("researchSummary", Objects.toString(research.get("summary"), ""));
        return "已整理 " + sources.size() + " 个资料入口" +
                ("search-plan-fallback".equals(effects.get("researchMode")) ? "（待进一步核实）" : "");
    }

    @SuppressWarnings("unchecked")
    private String initializeWorkflow(PlanStep step, Map<String, Object> effects) {
        var projectId = Objects.toString(effects.get("createdProjectId"), "");
        if (projectId.isBlank()) throw new IllegalStateException("分析项目尚未创建");
        var topic = argument(step, "topic", Objects.toString(effects.get("topic"), "财经分析"));
        var sources = effects.get("researchSources") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        var nodes = new ArrayList<NodeDefinition>();
        var edges = new ArrayList<EdgeDefinition>();
        var analysisId = "analysis_" + shortId();
        for (var index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            var url = Objects.toString(source.get("url"), "");
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            var id = "source_" + shortId();
            var title = Objects.toString(source.get("title"), "资料入口 " + (index + 1));
            nodes.add(new NodeDefinition(id, NodeType.LINK_INPUT, title, 60, 60 + index * 105,
                    Map.of("title", title, "url", url, "sourceType", Objects.toString(source.get("source_type"), "网页资料"),
                            "whyRelevant", Objects.toString(source.get("why_relevant"), ""))));
            edges.add(new EdgeDefinition("edge_" + shortId(), id, analysisId));
        }
        var refId = "refs_" + shortId();
        nodes.add(new NodeDefinition(refId, NodeType.REF_SEARCH, "补充资料与 Ref", 360, 80,
                Map.of("query", topic + " 财报 经营数据 行业 政策 重大事件 风险")));
        nodes.add(new NodeDefinition(analysisId, NodeType.AI_ANALYSIS, "综合分析", 650, 210,
                Map.of("prompt", "围绕" + topic + "，结合所有数据和资料分析增长质量、盈利能力、现金流、行业环境、重大事件与风险。区分事实、推断和待核实信息，并为每项结论保留 Ref。")));
        edges.add(new EdgeDefinition("edge_" + shortId(), refId, analysisId));
        var reportId = "report_" + shortId();
        var pptId = "ppt_" + shortId();
        nodes.add(new NodeDefinition(reportId, NodeType.DELIVERABLE, "财经分析报告", 970, 120,
                Map.of("title", topic + "财经分析报告", "format", "FINANCIAL_REPORT", "heading", "核心结论",
                        "targetAudience", "业务负责人", "lengthHint", "完整", "includeCitations", true,
                        "citationStyle", "IEEE", "generationPrompt", "生成包含关键指标、趋势图表、结论、风险和规范来源标注的可交互财经报告。")));
        nodes.add(new NodeDefinition(pptId, NodeType.DELIVERABLE, "管理层汇报", 970, 340,
                Map.of("title", topic + "管理层汇报", "format", "PPTX", "heading", "核心结论",
                        "targetAudience", "管理层", "lengthHint", "8-12页", "pptSkill", "guizang-huawei-style-c",
                        "includeCitations", true, "citationStyle", "IEEE",
                        "generationPrompt", "生成结论先行、数据可视化充分、适合管理层决策的演示文稿，并规范标注资料来源。")));
        edges.add(new EdgeDefinition("edge_" + shortId(), analysisId, reportId));
        edges.add(new EdgeDefinition("edge_" + shortId(), analysisId, pptId));
        var current = workflows.getProjectWorkflow(projectId);
        workflows.saveProjectWorkflow(projectId, new SaveRequest("主工作流", "围绕" + topic + "自动搜集资料、形成分析并生成报告与汇报",
                nodes, edges, ExecutionMode.MANUAL, null, current.currentVersion()));
        effects.put("workflowId", current.id());
        effects.put("sourceCount", nodes.stream().filter(node -> node.type() == NodeType.LINK_INPUT).count());
        return "已建立包含资料、分析、财经报告和 PPT 的主工作流";
    }

    private List<Map<String, Object>> fallbackSources(String topic) {
        var encoded = java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8);
        return List.of(
                Map.of("title", topic + " 官方与监管资料检索", "url", "https://www.bing.com/search?q=" + encoded + "+官网+监管+财报", "source_type", "待核实检索", "why_relevant", "优先定位官方披露和监管文件"),
                Map.of("title", topic + " 行业与政策资料检索", "url", "https://www.bing.com/search?q=" + encoded + "+行业+政策+研究报告", "source_type", "待核实检索", "why_relevant", "补充行业环境、政策和研究资料"),
                Map.of("title", topic + " 财经媒体与重大事件检索", "url", "https://www.bing.com/search?q=" + encoded + "+财经+重大事件+风险", "source_type", "待核实检索", "why_relevant", "补充事件脉络和风险线索")
        );
    }

    private String finalSummary(List<PlanStep> steps, Map<String, Object> effects) {
        var projectName = Objects.toString(effects.get("createdProjectName"), "");
        if (!projectName.isBlank()) {
            var count = effects.get("sourceCount") instanceof Number number ? number.intValue() : 0;
            var suffix = "search-plan-fallback".equals(effects.get("researchMode")) ? "，其中资料入口需要继续核实" : "";
            return "已创建“" + projectName + "”，整理 " + count + " 个资料入口并建立主工作流" + suffix + "。";
        }
        return "已完成 " + steps.size() + " 个步骤，结果均保存为新的草稿或版本。";
    }

    private String argument(PlanStep step, String key, String fallback) {
        var value = Objects.toString(step.arguments().get(key), "").trim();
        return value.isBlank() ? fallback : value;
    }

    private String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

    private RunResponse mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RunResponse(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("plan_id"),
                rs.getString("status"),
                rs.getInt("current_step"),
                rs.getString("result_summary"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                readMap(rs.getString("effects_json"))
        );
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("助手执行结果无法保存", exception); }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { return Map.of(); }
    }
}
