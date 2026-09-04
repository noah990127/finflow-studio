package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RunResponse;
import com.finflow.studio.deliverable.DeliverableModels.CitationRequest;
import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.SectionRequest;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowRunService;
import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.ExecutionMode;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
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
    private final WorkspaceResourceService workspace;
    private final KnowledgeService knowledge;
    private final DeliverableService deliverables;
    private final WorkflowRunService workflowRuns;
    private final AssistantWorkspaceToolGateway workspaceTools;

    public AssistantExecutionService(JdbcClient jdbc, AssistantEventService events,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                     ProjectService projects, WorkflowDefinitionService workflows,
                                     WorkerClient worker, ObjectMapper objectMapper,
                                     WorkspaceResourceService workspace, KnowledgeService knowledge,
                                     DeliverableService deliverables, WorkflowRunService workflowRuns,
                                     AssistantWorkspaceToolGateway workspaceTools) {
        this.jdbc = jdbc;
        this.events = events;
        this.taskExecutor = taskExecutor;
        this.projects = projects;
        this.workflows = workflows;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.workspace = workspace;
        this.knowledge = knowledge;
        this.deliverables = deliverables;
        this.workflowRuns = workflowRuns;
        this.workspaceTools = workspaceTools;
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
        var started = jdbc.sql("update assistant_run set status = 'RUNNING', started_at = :startedAt where id = :id and status = 'QUEUED'")
                .param("startedAt", startedAt)
                .param("id", runId)
                .update();
        if (started == 0) return;
        var steps = loadSteps(run.planId());
        events.publish(run.sessionId(), runId, "assistant.run.started", Map.of(
                "runId", runId, "progress", 25, "message", "开始执行，共 " + steps.size() + " 个步骤",
                "totalSteps", steps.size()));
        var effects = new LinkedHashMap<String, Object>();
        try {
            for (var step : steps) {
                if (isCanceled(runId)) return;
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
                events.publish(run.sessionId(), runId, "agent.tool_call", Map.of(
                        "status", "running", "step", step.order(), "totalSteps", steps.size(),
                        "toolName", step.tool(), "argumentSummary", summarizeArguments(step.arguments()),
                        "message", step.title(), "progress", startedProgress));
                events.publish(run.sessionId(), runId, "agent.executing", Map.of(
                        "status", "running", "step", step.order(), "toolName", step.tool(),
                        "message", step.description(), "progress", startedProgress));

                var result = executeStep(step, effects);
                if (isCanceled(runId)) {
                    jdbc.sql("update assistant_plan_step set status = 'CANCELED' where id = :id")
                            .param("id", step.id()).update();
                    return;
                }
                jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                        .param("effects", writeJson(effects)).param("id", runId).update();
                jdbc.sql("update assistant_plan_step set status = 'SUCCEEDED' where id = :id")
                        .param("id", step.id())
                        .update();
                var completedProgress = 25 + step.order() * 70 / Math.max(1, steps.size());
                var completedPayload = new LinkedHashMap<String, Object>();
                completedPayload.put("step", step.order());
                completedPayload.put("totalSteps", steps.size());
                completedPayload.put("title", step.title());
                completedPayload.put("message", result);
                completedPayload.put("progress", completedProgress);
                completedPayload.put("result", result);
                completedPayload.put("tool", step.tool());
                completedPayload.put("provenance", provenance(step, effects));
                if (effects.get("uiAction") instanceof Map<?, ?> action) {
                    completedPayload.put("uiAction", action);
                }
                events.publish(run.sessionId(), runId, "assistant.step.completed", completedPayload);
                events.publish(run.sessionId(), runId, "agent.observation", Map.of(
                        "status", "completed", "step", step.order(), "toolName", step.tool(),
                        "resultSummary", result, "message", result, "progress", completedProgress,
                        "provenance", provenance(step, effects)));
            }
            if (isCanceled(runId)) return;
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
                    "canRollback", effects.containsKey("createdProjectId")));
            events.publish(run.sessionId(), runId, "agent.generating", Map.of(
                    "status", "completed", "message", "正在整理最终结果和执行轨迹", "progress", 98));
            events.publish(run.sessionId(), runId, "agent.completed", Map.of(
                    "status", "completed", "summary", summary, "message", summary, "progress", 100,
                    "provenance", Map.of("traceId", runId, "toolCount", steps.size())));
        } catch (RuntimeException ex) {
            jdbc.sql("update assistant_run set status = 'FAILED', result_summary = :message, finished_at = :finishedAt where id = :id")
                    .param("message", ex.getMessage() == null ? "执行失败" : ex.getMessage())
                    .param("finishedAt", Instant.now())
                    .param("id", runId)
                    .update();
            events.publish(run.sessionId(), runId, "assistant.run.failed", Map.of(
                    "runId", runId, "progress", 100,
                    "message", "当前步骤没有完成，可以从这里重试",
                    "error", ex.getMessage() == null ? "" : ex.getMessage()));
            events.publish(run.sessionId(), runId, "agent.failed", Map.of(
                    "status", "failed", "progress", 100,
                    "message", "当前步骤没有完成，可以展开查看错误",
                    "error", ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    private boolean isCanceled(String runId) {
        return "CANCELED".equals(jdbc.sql("select status from assistant_run where id = :id")
                .param("id", runId).query(String.class).single());
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
        events.publish(run.sessionId(), id, "agent.cancelled", Map.of(
                "status", "cancelled", "runId", id, "message", "任务已停止"));
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

    String executeStep(PlanStep step, Map<String, Object> effects) {
        return switch (step.tool()) {
            case "workspace.inspect" -> inspectWorkspace(step, effects);
            case "workspace.navigate" -> navigate(step, effects);
            case "assistant.respond" -> respond(step, effects);
            case "assistant.analyze_context" -> analyzeContext(step, effects);
            case "project.create_workspace", "project.create_analysis_workspace" -> createAnalysisProject(step, effects);
            case "knowledge.discover_external_sources" -> discoverSources(step, effects);
            case "knowledge.search" -> searchKnowledge(step, effects);
            case "knowledge.add" -> addKnowledge(step, effects);
            case "workflow.initialize", "workflow.initialize_analysis" -> initializeWorkflow(step, effects);
            case "workflow.prepare" -> prepareWorkflow(step, effects);
            case "workflow.add_selected_resource" -> addSelectedResource(step, effects);
            case "workflow.add_data_transform" -> addDataTransform(step, effects);
            case "workflow.add_outputs" -> addOutputs(step, effects);
            case "workflow.run" -> runWorkflow(step, effects);
            case "deliverable.create" -> createDeliverable(step, effects);
            default -> workspaceTools.execute(step, effects);
        };
    }

    static Set<String> supportedTools() {
        var tools = new java.util.LinkedHashSet<>(AssistantWorkspaceToolGateway.supportedTools());
        tools.addAll(Set.of(
                "workspace.inspect", "workspace.navigate", "assistant.respond", "assistant.analyze_context",
                "project.create_workspace", "knowledge.discover_external_sources", "knowledge.search", "knowledge.add",
                "workflow.initialize", "workflow.prepare", "workflow.add_selected_resource",
                "workflow.add_data_transform", "workflow.add_outputs", "workflow.run", "deliverable.create"));
        return Set.copyOf(tools);
    }

    private Map<String, Object> provenance(PlanStep step, Map<String, Object> effects) {
        var value = new LinkedHashMap<String, Object>();
        value.put("toolName", step.tool());
        value.put("mode", step.mode());
        value.put("risk", step.risk().name());
        value.put("requiresConfirmation", step.requiresConfirmation());
        if (effects.containsKey("sourceProjectId")) value.put("sourceProjectId", effects.get("sourceProjectId"));
        if (effects.containsKey("workflowId")) value.put("workflowId", effects.get("workflowId"));
        if (effects.containsKey("createdProjectId")) value.put("createdProjectId", effects.get("createdProjectId"));
        if (effects.containsKey("knowledgeCitations")) value.put("citations", effects.get("knowledgeCitations"));
        if (effects.containsKey("deliverables")) value.put("deliverables", effects.get("deliverables"));
        return value;
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return "无参数";
        return arguments.entrySet().stream()
                .filter(entry -> !List.of("content", "text", "patch", "credentials_ref").contains(entry.getKey()))
                .limit(5)
                .map(entry -> entry.getKey() + "=" + Objects.toString(entry.getValue(), ""))
                .toList()
                .toString();
    }

    private String inspectWorkspace(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        if (projectId.isBlank()) return "已读取当前工作环境";
        var snapshot = workspace.get(projectId);
        var data = snapshot.resources().stream().filter(item -> "DATA".equals(item.group())).count();
        var knowledge = snapshot.resources().stream().filter(item -> "KNOWLEDGE".equals(item.group())).count();
        var outputs = snapshot.resources().stream().filter(item -> "OUTPUT".equals(item.group())).count();
        effects.put("workspaceCounts", Map.of("data", data, "knowledge", knowledge, "outputs", outputs));
        effects.put("sourceProjectId", projectId);
        return "已读取当前项目：数据 " + data + " 项、资料 " + knowledge + " 项、输出 " + outputs + " 项";
    }

    private String navigate(PlanStep step, Map<String, Object> effects) {
        var target = argument(step, "target", "HOME");
        var action = new LinkedHashMap<String, Object>();
        action.put("type", "OPEN_" + target);
        var projectId = argument(step, "project_id", "");
        if (!projectId.isBlank()) action.put("projectId", projectId);
        var resourceId = argument(step, "resource_id", "");
        if (!resourceId.isBlank()) action.put("resourceId", resourceId);
        var navigationGoal = argument(step, "goal", "");
        if (!navigationGoal.isBlank()) action.put("goal", navigationGoal);
        if ("WORKFLOW".equals(target) && !projectId.isBlank()) {
            var workflowId = argument(step, "workflow_id", "");
            var requestedName = argument(step, "workflow_name", "");
            var goal = argument(step, "goal", "");
            if (workflowId.isBlank()) {
                var available = workflows.list(projectId);
                var selected = available.stream()
                        .filter(item -> (!requestedName.isBlank() && (item.name().equalsIgnoreCase(requestedName) || item.name().contains(requestedName)))
                                || (!goal.isBlank() && goal.contains(item.name())))
                        .findFirst()
                        .orElseGet(() -> available.stream().findFirst().orElse(null));
                if (selected != null) workflowId = selected.id();
            }
            if (!workflowId.isBlank()) action.put("workflowId", workflowId);
        }
        effects.put("uiAction", action);
        return switch (target) {
            case "WORKFLOW" -> "已打开工作流";
            case "DATA" -> "已打开数据采集";
            case "RESOURCE" -> "已打开当前内容";
            default -> "已返回项目概览";
        };
    }

    private String respond(PlanStep step, Map<String, Object> effects) {
        var goal = argument(step, "goal", "请介绍当前项目");
        if ("NO_STRUCTURED_DATA".equals(argument(step, "reason", ""))) {
            var message = "当前项目中没有可用于这项任务的结构化数据。请先在左侧“数据”中上传表格，或连接数据库/数据服务，再选择内容让我继续。";
            effects.put("assistantResponse", message);
            return message;
        }
        try {
            var result = worker.summarize("""
                    你是通用个人工作台助手。根据用户目标和工作台摘要给出简洁、可执行的中文回答。
                    不得声称读取了未提供的文件内容，不得自行创建财务报表或其他成果。
                    用户目标：%s
                    工作台摘要：%s
                    """.formatted(goal, effects.getOrDefault("workspaceCounts", Map.of())), "工作台助手回答", 5);
            var message = Objects.toString(result.get("summary"), "").trim();
            if (!message.isBlank()) {
                effects.put("assistantResponse", message);
                return message;
            }
        } catch (RuntimeException ignored) { }
        var message = "我已结合当前工作台理解了你的需求。请选择要处理的内容，或明确告诉我需要打开、创建、编排、分析还是输出什么。";
        effects.put("assistantResponse", message);
        return message;
    }

    private String analyzeContext(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        var resourceId = argument(step, "resource_id", "");
        var query = resourceId.isBlank()
                ? jdbc.sql("select source_name, text_content from knowledge_ref where project_id = :projectId order by created_at desc limit 24")
                    .param("projectId", projectId)
                : jdbc.sql("select source_name, text_content from knowledge_ref where project_id = :projectId and resource_id = :resourceId order by chunk_index limit 24")
                    .param("projectId", projectId).param("resourceId", resourceId);
        var chunks = query.query((rs, rowNum) -> "[" + rs.getString("source_name") + "]\n" + rs.getString("text_content")).list();
        if (chunks.isEmpty()) {
            var message = resourceId.isBlank()
                    ? "当前项目还没有可读取的资料内容。请先选择或上传文件，也可以先采集数据库/API 数据。"
                    : "当前内容尚未解析出可分析的文本；如果它是数据库或 API，请先预览并采集数据。";
            effects.put("assistantResponse", message);
            return message;
        }
        var sourceText = String.join("\n\n", chunks);
        if (sourceText.length() > 60_000) sourceText = sourceText.substring(0, 60_000);
        var result = worker.summarize("用户问题：" + argument(step, "goal", "请分析这些内容") + "\n\n可用内容：\n" + sourceText,
                argument(step, "resource_name", "项目资料"), 8);
        var message = Objects.toString(result.get("summary"), "").trim();
        if (message.isBlank()) message = "没有从当前内容中提取到足够的信息。";
        effects.put("assistantResponse", message);
        effects.put("analyzedChunkCount", chunks.size());
        return message;
    }

    private String createAnalysisProject(PlanStep step, Map<String, Object> effects) {
        var name = argument(step, "project_name", "新的分析项目");
        var description = argument(step, "description", "由 AI 助手创建的个人分析项目");
        var project = projects.create(name, description);
        effects.put("createdProjectId", project.id());
        effects.put("createdProjectName", project.name());
        effects.put("topic", argument(step, "topic", name));
        effects.put("uiAction", Map.of("type", "OPEN_PROJECT", "projectId", project.id(), "refreshWorkspace", true));
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

    private String searchKnowledge(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", Objects.toString(effects.get("sourceProjectId"), ""));
        if (projectId.isBlank()) throw new IllegalStateException("当前项目不可用，无法搜索知识库");
        var query = argument(step, "query", argument(step, "goal", ""));
        if (query.isBlank()) throw new IllegalArgumentException("知识检索需要 query 参数");
        var limit = step.arguments().get("limit") instanceof Number number ? number.intValue() : 10;
        var refs = knowledge.search(projectId, query, Math.max(1, Math.min(limit, 20)));
        var citations = new ArrayList<Map<String, Object>>();
        if (effects.get("researchCitations") instanceof List<?> existing) {
            existing.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value)
                    .forEach(citations::add);
        }
        refs.stream().map(ref -> {
            var citation = new LinkedHashMap<String, Object>();
            citation.put("citationId", ref.id());
            citation.put("resourceId", ref.resourceId());
            citation.put("version", ref.version());
            citation.put("sourceName", ref.sourceName());
            citation.put("excerpt", ref.text());
            citation.put("location", ref.location());
            citation.put("contentHash", ref.contentHash());
            citation.put("score", ref.score());
            return Map.<String, Object>copyOf(citation);
        }).forEach(citations::add);
        effects.put("knowledgeSearchQuery", query);
        effects.put("knowledgeCitations", citations);
        return citations.isEmpty()
                ? "当前项目知识库未命中相关证据，将继续通过后续资料检索步骤补充"
                : "已从项目知识库找到 " + citations.size() + " 条可引用证据";
    }

    private String addKnowledge(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", Objects.toString(effects.get("sourceProjectId"), ""));
        if (projectId.isBlank()) throw new IllegalStateException("当前项目不可用，无法登记资料");
        var material = argument(step, "text", "");
        if (material.isBlank()) material = researchMaterial(effects);
        var source = argument(step, "source", "");
        if (material.isBlank() && !source.isBlank()) material = source;
        if (material.isBlank()) throw new IllegalStateException("没有可登记的资料内容或来源");
        var name = argument(step, "name", "Agent 研究资料索引.md");
        if (!name.toLowerCase().endsWith(".md")) name += ".md";
        var resource = knowledge.importBytes(projectId, name, "text/markdown",
                material.getBytes(StandardCharsets.UTF_8));
        effects.put("knowledgeResourceId", resource.id());
        var researchCitations = researchCitations(effects, resource.id(), resource.currentVersion());
        effects.put("researchCitations", researchCitations);
        effects.put("knowledgeCitations", researchCitations);
        effects.put("uiAction", Map.of("type", "OPEN_RESOURCE", "projectId", projectId,
                "resourceId", resource.id(), "refreshWorkspace", true));
        return "已将筛选后的资料索引加入项目知识库";
    }

    private List<Map<String, Object>> researchCitations(Map<String, Object> effects, String resourceId, int version) {
        if (!(effects.get("researchSources") instanceof List<?> sources)) return List.of();
        var citations = new ArrayList<Map<String, Object>>();
        for (var value : sources) {
            if (!(value instanceof Map<?, ?> source)) continue;
            var title = Objects.toString(source.get("title"), "公开资料");
            var url = Objects.toString(source.get("url"), "");
            var excerpt = Objects.toString(source.get("snippet"),
                    Objects.toString(source.get("why_relevant"), "公开资料入口"));
            citations.add(Map.of(
                    "citationId", "external-" + (citations.size() + 1),
                    "resourceId", resourceId,
                    "version", version,
                    "sourceName", title,
                    "excerpt", excerpt,
                    "location", url.isBlank() ? Map.of() : Map.of("url", url),
                    "contentHash", HashSupport.sha256(title + "\n" + url + "\n" + excerpt),
                    "score", 1.0));
        }
        return List.copyOf(citations);
    }

    @SuppressWarnings("unchecked")
    private String createDeliverable(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", Objects.toString(effects.get("sourceProjectId"), ""));
        if (projectId.isBlank()) throw new IllegalStateException("当前项目不可用，无法生成交付件");
        var format = normalizeDeliverableFormat(argument(step, "format", "PPTX"));
        var title = argument(step, "title", "Agent 分析成果");
        var goal = argument(step, "goal", step.description() == null || step.description().isBlank()
                ? "基于已核验资料总结战略规划、经营状况、风险与结论" : step.description());
        var sourceText = argument(step, "content", "");
        if (sourceText.isBlank()) sourceText = researchMaterial(effects);
        if (sourceText.isBlank()) throw new IllegalStateException("没有可用于生成交付件的研究证据");
        var generation = worker.generateContent(format, goal + "。所有事实和判断必须标注来源，不得编造。", sourceText);
        var generatedBody = Objects.toString(generation.get("content"), "").trim();
        if (generatedBody.isBlank()) throw new IllegalStateException("成果生成服务没有返回可用内容");

        var citations = new ArrayList<CitationRequest>();
        if (effects.get("knowledgeCitations") instanceof List<?> values) {
            for (var value : values) {
                if (!(value instanceof Map<?, ?> citation)) continue;
                citations.add(new CitationRequest(
                        Objects.toString(citation.get("citationId"), ""),
                        Objects.toString(citation.get("resourceId"), ""),
                        citation.get("version") instanceof Number number ? number.intValue() : 0,
                        Objects.toString(citation.get("sourceName"), "公开资料"),
                        Objects.toString(citation.get("excerpt"), ""),
                        citation.get("location") instanceof Map<?, ?> location
                                ? (Map<String, Object>) location : Map.of(),
                        Objects.toString(citation.get("contentHash"), "")));
            }
        }
        var section = new SectionRequest("分析结果", List.of(generatedBody), List.of(),
                citations.stream().map(CitationRequest::id).filter(id -> !id.isBlank()).toList(), citations);
        var pptSkill = "PPTX".equals(format) ? "guizang-huawei-style-c"
                : "HTML_SLIDES".equals(format) ? "frontend-slides" : null;
        var created = deliverables.create(new CreateRequest(projectId, null, title, "由 Agent 基于可追溯资料生成",
                format, pptSkill, true, "IEEE", List.of(section)));
        var outputs = effects.get("deliverables") instanceof List<?> values
                ? new ArrayList<>(values.stream().filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value).toList())
                : new ArrayList<Map<String, Object>>();
        outputs.add(Map.of("id", created.id(), "name", created.name(), "format", created.format(),
                "version", created.currentVersion(), "downloadUrl", "/api/deliverables/" + created.id() + "/download"));
        effects.put("deliverables", outputs);
        effects.put("uiAction", Map.of("type", "OPEN_DELIVERABLE", "projectId", projectId,
                "resourceId", created.id(), "refreshWorkspace", true));
        return "已生成“" + created.name() + "”（" + created.format().toUpperCase() + "）";
    }

    private String normalizeDeliverableFormat(String value) {
        return switch (value.trim().toUpperCase()) {
            case "PPT", "POWERPOINT" -> "PPTX";
            case "HTML", "WEB", "WEBPAGE" -> "HTML_SLIDES";
            default -> value.trim().toUpperCase();
        };
    }

    @SuppressWarnings("unchecked")
    private String researchMaterial(Map<String, Object> effects) {
        var result = new StringBuilder();
        var summary = Objects.toString(effects.get("researchSummary"), "").trim();
        if (!summary.isBlank()) result.append("研究摘要：").append(summary).append("\n\n");
        if (effects.get("researchSources") instanceof List<?> sources) {
            var index = 1;
            for (var value : sources) {
                if (!(value instanceof Map<?, ?> source)) continue;
                result.append("[来源 ").append(index++).append("] ")
                        .append(Objects.toString(source.get("title"), "未命名来源")).append('\n');
                for (var key : List.of("url", "source_type", "why_relevant", "snippet", "published_at")) {
                    var item = Objects.toString(source.get(key), "").trim();
                    if (!item.isBlank()) result.append(key).append(": ").append(item).append('\n');
                }
                result.append('\n');
            }
        }
        if (effects.get("knowledgeCitations") instanceof List<?> citations) {
            for (var value : citations) {
                if (!(value instanceof Map<?, ?> citation)) continue;
                result.append("[项目证据] ").append(Objects.toString(citation.get("sourceName"), "项目资料"))
                        .append("：").append(Objects.toString(citation.get("excerpt"), "")).append('\n');
            }
        }
        return result.toString().trim();
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
        var includeAnalysis = Boolean.TRUE.equals(step.arguments().get("include_analysis"));
        if (includeAnalysis) {
            var refId = "refs_" + shortId();
            nodes.add(new NodeDefinition(refId, NodeType.REF_SEARCH, "补充参考资料", 360, 80,
                    Map.of("query", topic)));
            nodes.add(new NodeDefinition(analysisId, NodeType.AI_ANALYSIS, "智能分析", 650, 210,
                    Map.of("prompt", argument(step, "goal", "围绕" + topic + "进行分析，区分事实、推断和待核实信息。"))));
            edges.add(new EdgeDefinition("edge_" + shortId(), refId, analysisId));
        }
        var formats = requestedFormats(step);
        if (!formats.isEmpty()) addOutputNodes(nodes, edges, formats, includeAnalysis ? analysisId : null, topic, argument(step, "goal", topic));
        var created = workflows.create(projectId, new SaveRequest("主工作流", "围绕" + topic + "组织和处理项目内容",
                nodes, edges, ExecutionMode.MANUAL, null, null));
        effects.put("workflowId", created.id());
        effects.put("sourceCount", nodes.stream().filter(node -> node.type() == NodeType.LINK_INPUT).count());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", created.id(), "refreshWorkspace", true));
        return formats.isEmpty() ? "已建立工作流，未添加用户没有要求的输出件"
                : "已建立工作流，并加入 " + String.join("、", formats) + " 输出";
    }

    private String prepareWorkflow(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        if (projectId.isBlank()) throw new IllegalStateException("当前项目不可用");
        var nodes = new ArrayList<NodeDefinition>();
        var edges = new ArrayList<EdgeDefinition>();
        var input = selectedInputNode(step, 80, 180);
        if (input != null) nodes.add(input);
        if (input == null) {
            var available = workspace.get(projectId).resources().stream()
                    .filter(resource -> !"OUTPUT".equals(resource.group()))
                    .limit(8)
                    .toList();
            for (var index = 0; index < available.size(); index++) {
                var resourceInput = workspaceInputNode(available.get(index), 70, 70 + index * 105);
                if (resourceInput != null) nodes.add(resourceInput);
            }
        }
        if (effects.get("researchSources") instanceof List<?> sources) {
            var sourceIndex = 0;
            for (var value : sources) {
                if (!(value instanceof Map<?, ?> source)) continue;
                var url = Objects.toString(source.get("url"), "");
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                var title = Objects.toString(source.get("title"), "公开资料 " + (sourceIndex + 1));
                nodes.add(new NodeDefinition("source_" + shortId(), NodeType.LINK_INPUT, title,
                        70, 70 + (++sourceIndex) * 105,
                        Map.of("title", title, "url", url,
                                "sourceType", Objects.toString(source.get("source_type"), "网页资料"),
                                "whyRelevant", Objects.toString(source.get("why_relevant"), ""))));
            }
        }
        var goal = argument(step, "goal", "整理并分析当前项目内容");
        var analysisId = "analysis_" + shortId();
        if (nodes.isEmpty()) {
            var researchId = "refs_" + shortId();
            nodes.add(new NodeDefinition(researchId, NodeType.REF_SEARCH, "查找相关资料", 80, 170,
                    Map.of("query", goal, "maxSources", 12)));
        }
        nodes.add(new NodeDefinition(analysisId, NodeType.AI_ANALYSIS, "分析与整理", 430, 180,
                Map.of("prompt", goal, "externalResearch", "ON", "transparent", true)));
        nodes.stream().filter(node -> !analysisId.equals(node.id())).forEach(node ->
                edges.add(new EdgeDefinition("edge_" + shortId(), node.id(), analysisId)));
        var formats = requestedFormats(step);
        addOutputNodes(nodes, edges, formats, analysisId, "工作成果", goal);
        var created = workflows.create(projectId, new SaveRequest(workflowName(step), "由 AI 助手按当前目标创建，可继续在画布中编排",
                nodes, edges, ExecutionMode.MANUAL, null, null));
        if (created.nodes().isEmpty()) throw new IllegalStateException("工作流没有生成可执行步骤，请重新描述目标");
        effects.put("workflowId", created.id());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", created.id(), "refreshWorkspace", true));
        return "已创建工作流“" + created.name() + "”，包含 " + created.nodes().size() + " 个可见步骤";
    }

    private NodeDefinition workspaceInputNode(com.finflow.studio.workspace.WorkspaceModels.Resource resource, double x, double y) {
        var id = "input_" + shortId();
        return switch (resource.resourceType()) {
            case "DATABASE_CONNECTION", "API_CONNECTION" -> new NodeDefinition(id, NodeType.DATA_EXTRACT, resource.name(), x, y,
                    Map.of("connectionId", resource.id(), "sql", "API_CONNECTION".equals(resource.resourceType()) ? "GET /" : "select * from your_table",
                            "outputName", resource.name() + ".csv", "fetchSize", 5000));
            case "DATASET" -> new NodeDefinition(id, NodeType.DATASET_INPUT, resource.name(), x, y,
                    Map.of("extractJobId", resource.id()));
            case "WEB_URL" -> new NodeDefinition(id, NodeType.LINK_INPUT, resource.name(), x, y,
                    Map.of("title", resource.name(), "url", Objects.toString(resource.url(), "")));
            case "DATA_FILE", "OFFICE_FILE", "KNOWLEDGE_FILE" -> new NodeDefinition(id, NodeType.FILE_INPUT, resource.name(), x, y,
                    Map.of("resourceId", resource.id()));
            default -> null;
        };
    }

    private String addSelectedResource(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        var current = workflows.getProjectWorkflow(projectId);
        var resourceId = argument(step, "resource_id", "");
        if (current.nodes().stream().anyMatch(node -> node.config() != null && node.config().containsValue(resourceId))) {
            effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                    "workflowId", current.id(), "refreshWorkspace", true));
            return "当前内容已经在工作流中";
        }
        var input = selectedInputNode(step, 80 + (current.nodes().size() % 3) * 260, 100 + (current.nodes().size() / 3) * 150);
        if (input == null) throw new IllegalStateException("请先在左侧选择要加入工作流的内容");
        workflows.update(current.id(), new SaveRequest(current.name(), current.description(), append(current.nodes(), input), current.edges(),
                current.executionMode(), current.schedule(), current.currentVersion()));
        effects.put("workflowId", current.id());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", current.id(), "refreshWorkspace", true));
        return "已把“" + input.name() + "”加入工作流";
    }

    private String addOutputs(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        var current = workflows.getProjectWorkflow(projectId);
        var nodes = new ArrayList<>(current.nodes());
        var edges = new ArrayList<>(current.edges());
        var existingFormats = nodes.stream()
                .filter(node -> node.type() == NodeType.DELIVERABLE || node.type() == NodeType.OUTPUT)
                .map(NodeDefinition::config)
                .filter(Objects::nonNull)
                .map(config -> Objects.toString(config.get("format"), ""))
                .filter(format -> !format.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        var formats = requestedFormats(step).stream()
                .filter(format -> !existingFormats.contains(format))
                .toList();
        if (formats.isEmpty()) {
            effects.put("workflowId", current.id());
            effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                    "workflowId", current.id(), "refreshWorkspace", true));
            return "所需输出节点已经在工作流中";
        }
        var upstream = nodes.stream().filter(node -> node.type() != NodeType.DELIVERABLE).reduce((left, right) -> right).map(NodeDefinition::id).orElse(null);
        addOutputNodes(nodes, edges, formats, upstream, "工作成果", argument(step, "goal", "根据当前项目内容生成成果"));
        workflows.update(current.id(), new SaveRequest(current.name(), current.description(), nodes, edges,
                current.executionMode(), current.schedule(), current.currentVersion()));
        effects.put("workflowId", current.id());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", current.id(), "refreshWorkspace", true));
        return "已加入 " + String.join("、", formats) + " 输出节点，可在画布中继续调整要求";
    }

    @SuppressWarnings("unchecked")
    private String runWorkflow(PlanStep step, Map<String, Object> effects) {
        var workflowId = argument(step, "workflow_id", Objects.toString(effects.get("workflowId"), ""));
        if (workflowId.contains("${")) workflowId = Objects.toString(effects.get("workflowId"), "");
        if (workflowId.isBlank()) throw new IllegalStateException("没有可运行的工作流");
        ensureWorkflowOutputs(workflowId, step);
        var run = workflowRuns.start(workflowId);
        effects.put("workflowId", workflowId);
        effects.put("workflowRunId", run.id());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "workflowId", workflowId,
                "runId", run.id(), "refreshWorkspace", true));

        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
        while (List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(run.status())
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待工作流完成时任务被中断", exception);
            }
            run = workflowRuns.get(run.id());
        }
        effects.put("workflowRunStatus", run.status());
        effects.put("workflowRunOutput", run.output());
        if ("FAILED".equals(run.status())) {
            throw new IllegalStateException("工作流执行失败：" + Objects.toString(run.errorMessage(), "未知错误"));
        }
        if ("CANCELED".equals(run.status()) || "REJECTED".equals(run.status())) {
            throw new IllegalStateException("工作流未完成：" + run.status());
        }
        if ("WAITING_REVIEW".equals(run.status())) {
            effects.put("waitingReview", true);
            return "工作流已运行到人工复核节点，正在等待确认";
        }
        if (!"SUCCEEDED".equals(run.status())) {
            return "工作流已启动并继续在后台执行，运行编号 " + run.id();
        }

        var outputs = effects.get("deliverables") instanceof List<?> values
                ? new ArrayList<>(values.stream().filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value).toList())
                : new ArrayList<Map<String, Object>>();
        for (var node : run.nodes()) {
            var deliverableId = Objects.toString(node.output().get("deliverableId"), "");
            if (deliverableId.isBlank()) continue;
            outputs.add(new LinkedHashMap<>(node.output()));
        }
        if (!outputs.isEmpty()) effects.put("deliverables", outputs);
        return outputs.isEmpty() ? "工作流已执行完成" : "工作流已执行完成并生成 " + outputs.size() + " 个交付件";
    }

    private void ensureWorkflowOutputs(String workflowId, PlanStep step) {
        var formats = requestedFormats(step);
        if (formats.isEmpty()) return;
        var current = workflows.get(workflowId);
        var existing = current.nodes().stream()
                .filter(node -> node.type() == NodeType.DELIVERABLE || node.type() == NodeType.OUTPUT)
                .map(NodeDefinition::config)
                .filter(Objects::nonNull)
                .map(config -> Objects.toString(config.get("format"), ""))
                .collect(java.util.stream.Collectors.toSet());
        var missing = formats.stream().filter(format -> !existing.contains(format)).toList();
        if (missing.isEmpty()) return;
        var nodes = new ArrayList<>(current.nodes());
        var edges = new ArrayList<>(current.edges());
        var upstream = nodes.stream()
                .filter(node -> node.type() != NodeType.DELIVERABLE && node.type() != NodeType.OUTPUT)
                .reduce((left, right) -> right).map(NodeDefinition::id).orElse(null);
        addOutputNodes(nodes, edges, missing, upstream, "工作成果", argument(step, "goal", current.description()));
        workflows.update(workflowId, new SaveRequest(current.name(), current.description(), nodes, edges,
                current.executionMode(), current.schedule(), current.currentVersion()));
    }

    private String addDataTransform(PlanStep step, Map<String, Object> effects) {
        var projectId = argument(step, "project_id", "");
        var current = workflows.getProjectWorkflow(projectId);
        var nodes = new ArrayList<>(current.nodes());
        var edges = new ArrayList<>(current.edges());
        var resourceId = argument(step, "resource_id", "");
        var upstream = nodes.stream()
                .filter(node -> node.config() != null && node.config().containsValue(resourceId))
                .findFirst().orElse(null);
        if (upstream == null) {
            upstream = selectedInputNode(step, 80, 180);
            if (upstream == null) throw new IllegalStateException("请先选择需要加工的数据");
            nodes.add(upstream);
        }
        var transformId = "transform_" + shortId();
        var goal = argument(step, "goal", "整理当前数据");
        nodes.add(new NodeDefinition(transformId, NodeType.DATA_TRANSFORM, "数据加工", upstream.x() + 300, upstream.y(),
                Map.of("requirements", goal, "script", "select * from input_1", "outputName", "processed_data.csv",
                        "scriptMode", "DRAFT", "transparent", true)));
        edges.add(new EdgeDefinition("edge_" + shortId(), upstream.id(), transformId));
        workflows.update(current.id(), new SaveRequest(current.name(), current.description(), nodes, edges,
                current.executionMode(), current.schedule(), current.currentVersion()));
        effects.put("workflowId", current.id());
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", current.id(), "refreshWorkspace", true));
        return "已在工作流中创建数据加工草稿，要求和脚本均可查看、修改和复核";
    }

    private NodeDefinition selectedInputNode(PlanStep step, double x, double y) {
        var resourceId = argument(step, "resource_id", "");
        if (resourceId.isBlank()) return null;
        var type = argument(step, "resource_type", "");
        var name = argument(step, "resource_name", "当前内容");
        var id = "input_" + shortId();
        return switch (type) {
            case "DATABASE_CONNECTION", "API_CONNECTION" -> new NodeDefinition(id, NodeType.DATA_EXTRACT, name, x, y,
                    Map.of("connectionId", resourceId, "sql", "API_CONNECTION".equals(type) ? "GET /" : "select * from your_table",
                            "outputName", name + ".csv", "fetchSize", 5000));
            case "DATASET" -> new NodeDefinition(id, NodeType.DATASET_INPUT, name, x, y, Map.of("extractJobId", resourceId));
            case "WEB_URL" -> new NodeDefinition(id, NodeType.LINK_INPUT, name, x, y,
                    Map.of("title", name, "url", Objects.toString(step.arguments().get("url"), "https://example.com")));
            default -> new NodeDefinition(id, NodeType.FILE_INPUT, name, x, y, Map.of("resourceId", resourceId));
        };
    }

    private void addOutputNodes(List<NodeDefinition> nodes, List<EdgeDefinition> edges, List<String> formats,
                                String upstreamId, String topic, String goal) {
        for (var index = 0; index < formats.size(); index++) {
            var format = formats.get(index);
            var id = "output_" + shortId();
            var title = topic + " - " + outputLabel(format);
            var config = new LinkedHashMap<String, Object>();
            config.put("title", title);
            config.put("format", format);
            config.put("heading", "工作结果");
            config.put("targetAudience", "使用者");
            config.put("lengthHint", "适中");
            config.put("includeCitations", false);
            config.put("citationStyle", "IEEE");
            config.put("generationPrompt", goal.isBlank() ? "根据上游内容生成结构清晰、可编辑的成果。" : goal);
            if ("PPTX".equals(format)) config.put("pptSkill", "guizang-huawei-style-c");
            if ("HTML_SLIDES".equals(format)) config.put("pptSkill", "frontend-slides");
            nodes.add(new NodeDefinition(id, NodeType.DELIVERABLE, outputLabel(format), 900, 100 + index * 180, config));
            if (upstreamId != null) edges.add(new EdgeDefinition("edge_" + shortId(), upstreamId, id));
        }
    }

    private String outputLabel(String format) {
        return switch (format) {
            case "PPTX" -> "演示文稿";
            case "DOCX" -> "Word 文档";
            case "PDF" -> "PDF 文档";
            case "MERMAID" -> "Mermaid 图";
            case "EXCALIDRAW" -> "Excalidraw 图";
            case "HTML_SLIDES" -> "网页幻灯片";
            case "FINANCIAL_REPORT" -> "交互报告";
            default -> format + " 成果";
        };
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Object::toString).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<String> requestedFormats(PlanStep step) {
        var requested = new ArrayList<>(stringList(step.arguments().get("output_formats")));
        if (step.arguments().get("parameters") instanceof Map<?, ?> parameters) {
            requested.addAll(stringList(parameters.get("output_formats")));
        }
        var goal = argument(step, "goal", "").toLowerCase(Locale.ROOT);
        if (goal.contains("ppt") || goal.contains("演示文稿")) requested.add("PPTX");
        if (goal.contains("html") || goal.contains("网页报告") || goal.contains("网页幻灯")) requested.add("HTML_SLIDES");
        return requested.stream().map(this::normalizeDeliverableFormat).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<NodeDefinition> append(List<NodeDefinition> source, NodeDefinition item) {
        var result = new ArrayList<>(source);
        result.add(item);
        return result;
    }

    private String workflowName(PlanStep step) {
        var goal = argument(step, "goal", "新工作流")
                .replaceAll("^(请|帮我|给我|新建|新增|创建|搭建|建立)+", "")
                .replaceAll("[，,。！？].*$", "").trim();
        if (goal.isBlank()) return "新工作流";
        if (!goal.contains("工作流")) goal += "工作流";
        return goal.substring(0, Math.min(goal.length(), 40));
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
        if (effects.get("deliverables") instanceof List<?> outputs && !outputs.isEmpty()) {
            if ("search-plan-fallback".equals(effects.get("researchMode"))) {
                return "已将待核实资料索引写入项目，建立并运行可见工作流，生成 " + outputs.size()
                        + " 个研究草稿。联网检索当前不可用，未读取成功的来源已明确标注，不应视为已核验结论。";
            }
            return "已完成研究分析并生成 " + outputs.size() + " 个交付件。";
        }
        var response = Objects.toString(effects.get("assistantResponse"), "");
        if (!response.isBlank()) return response;
        var projectName = Objects.toString(effects.get("createdProjectName"), "");
        if (!projectName.isBlank()) {
            var count = effects.get("sourceCount") instanceof Number number ? number.intValue() : 0;
            var suffix = "search-plan-fallback".equals(effects.get("researchMode")) ? "，其中资料入口需要继续核实" : "";
            return "已创建“" + projectName + "”，整理 " + count + " 个资料入口并建立主工作流" + suffix + "。";
        }
        if (effects.containsKey("uiAction")) return "已完成工作台操作。";
        return "已完成 " + steps.size() + " 个步骤。";
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
