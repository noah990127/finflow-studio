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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private final AssistantPlanner planner;
    private final int maxDynamicActions;
    private final AssistantInterruptions interruptions;
    private final String gatewayBaseUrl;
    private final Map<String, ReentrantReadWriteLock> runLocks = new ConcurrentHashMap<>();

    public AssistantExecutionService(JdbcClient jdbc, AssistantEventService events,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                     ProjectService projects, WorkflowDefinitionService workflows,
                                     WorkerClient worker, ObjectMapper objectMapper,
                                     WorkspaceResourceService workspace, KnowledgeService knowledge,
                                     DeliverableService deliverables, WorkflowRunService workflowRuns,
                                     AssistantWorkspaceToolGateway workspaceTools, AssistantPlanner planner,
                                     @Value("${finflow.agent.max-dynamic-actions:80}") int maxDynamicActions,
                                     @Value("${finflow.agent.gateway-base-url:http://127.0.0.1:8080}") String gatewayBaseUrl,
                                     AssistantInterruptions interruptions) {
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
        this.planner = planner;
        this.maxDynamicActions = Math.max(12, maxDynamicActions);
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.interruptions = interruptions;
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
        var gatewayToken = UUID.randomUUID() + "." + UUID.randomUUID();
        var continuous = jdbc.sql("select count(*) from assistant_plan_step where plan_id = :id and tool_name = 'agent.execute'")
                .param("id", planId).query(Integer.class).single() > 0;
        var now = Instant.now();
        jdbc.sql("""
                insert into assistant_run(id, session_id, plan_id, idempotency_key, trace_id, status,
                                          current_step, result_summary, created_at, gateway_token, continuous_agent)
                values (:id, :sessionId, :planId, :key, :traceId, 'QUEUED', 0, '', :createdAt, :gatewayToken, :continuous)
                """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("planId", planId)
                .param("key", idempotencyKey)
                .param("traceId", traceId)
                .param("gatewayToken", gatewayToken)
                .param("continuous", continuous)
                .param("createdAt", now)
                .update();
        events.publish(sessionId, id, "assistant.run.queued", Map.of(
                "runId", id, "progress", 22, "message", "已进入处理队列，马上开始"));
        scheduleAfterCommit(id);
        return get(id);
    }

    public RunResponse resume(String runId) {
        var run = get(runId);
        var updated = jdbc.sql("update assistant_run set status = 'QUEUED' where id = :id and status = 'WAITING_CONFIRMATION'")
                .param("id", runId).update();
        if (updated == 0) return run;
        events.publish(run.sessionId(), runId, "assistant.run.queued", Map.of(
                "runId", runId, "progress", 22, "message", "确认已收到，继续执行动态计划"));
        scheduleAfterCommit(runId);
        return get(runId);
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
        var started = jdbc.sql("""
                        update assistant_run set status = 'RUNNING', started_at = coalesce(started_at, :startedAt)
                        where id = :id and status = 'QUEUED'
                        """)
                .param("startedAt", Instant.now()).param("id", runId).update();
        if (started == 0) return;
        var runtime = loadRuntime(run.planId());
        var effects = new LinkedHashMap<String, Object>(run.result());
        events.publish(run.sessionId(), runId, "assistant.run.started", Map.of(
                "runId", runId, "progress", 25,
                "message", runtime.dynamic() ? "开始动态执行，Agent 会根据每一步结果继续决策" : "开始执行计划"));
        try {
            if (isContinuousRun(runId)) {
                executeContinuous(run, runtime, effects);
                return;
            }
            var failureAttempts = new LinkedHashMap<String, Integer>();
            while (true) {
                if (isCanceled(runId)) return;
                var pending = loadSteps(run.planId()).stream().filter(step -> "PENDING".equals(step.status())).toList();
                if (pending.isEmpty()) {
                    finishRun(run, finalSummary(loadSteps(run.planId()), effects), effects);
                    return;
                }
                var extended = false;
                for (var step : pending) {
                    if (isCanceled(runId)) return;
                    var observation = executeAndObserve(run, step, effects);
                    if (isCanceled(runId)) return;
                    if (!runtime.dynamic() && !Boolean.TRUE.equals(observation.get("success"))) {
                        throw new IllegalStateException(Objects.toString(observation.get("error"), "工具执行失败"));
                    }
                    if (!runtime.dynamic()) continue;
                    var recovery = AssistantFailurePolicy.assess(step, observation, failureAttempts);
                    if (recovery != null) {
                        observation.put("failureType", recovery.failure().category().name());
                        observation.put("retryable", recovery.failure().retryable());
                        observation.put("recoveryHint", recovery.failure().recoveryHint());
                        observation.put("attempt", recovery.attempt());
                        events.publish(run.sessionId(), run.id(), "agent.retrying", Map.of(
                                "status", recovery.failure().retryable() ? "running" : "adjusting",
                                "toolName", step.tool(), "failureType", recovery.failure().category().name(),
                                "retryable", recovery.failure().retryable(), "attempt", recovery.attempt(),
                                "message", recovery.failure().recoveryHint(),
                                "progress", dynamicProgress(completedActionCount(run.planId()))));
                        if (recovery.stop()) throw new IllegalStateException(recovery.stopMessage());
                    }
                    var completedActions = completedActionCount(run.planId());
                    events.publish(run.sessionId(), runId, "agent.thinking_summary", Map.of(
                            "status", "running", "progress", dynamicProgress(completedActions),
                            "message", "正在根据刚才的真实执行结果判断下一步"));
                    events.publish(run.sessionId(), runId, "agent.planning", Map.of(
                            "status", "running", "progress", dynamicProgress(completedActions),
                            "message", "正在动态更新计划"));
                    var turn = interruptions.token("run", runId).await(() -> planner.continueAfterObservation(runtime.goal(), runtime.page(),
                            workspaceContext(activeProjectId(runtime, step, effects)), run.sessionId(), runtime.executionMode(),
                            observation, completedActions));
                    if (isCanceled(runId)) return;
                    if (!turn.publicSummary().isBlank()) {
                        events.publish(run.sessionId(), runId, "agent.thinking_summary", Map.of(
                                "status", "completed", "phase", "assessment",
                                "progress", dynamicProgress(completedActions), "message", turn.publicSummary()));
                    }
                    if (turn.completed() || turn.steps().isEmpty()) {
                        if (!Boolean.TRUE.equals(observation.get("success"))) {
                            throw new IllegalStateException(turn.summary());
                        }
                        finishRun(run, turn.summary(), effects);
                        return;
                    }
                    if (completedActions >= maxDynamicActions) {
                        throw new IllegalStateException("任务在完成前已用完本次 " + maxDynamicActions
                                + " 次执行预算；中间结果已经保留，请继续当前对话以恢复执行");
                    }
                    var next = appendDynamicStep(run.planId(), turn.steps().getFirst(), runtime.executionMode(), turn.summary());
                    if (isCanceled(runId)) {
                        jdbc.sql("update assistant_plan_step set status = 'CANCELED' where id = :id and status = 'PENDING'")
                                .param("id", next.id()).update();
                        return;
                    }
                    events.publish(run.sessionId(), runId, "agent.plan_updated", Map.of(
                            "status", "completed", "planId", run.planId(), "step", next.order(),
                            "toolName", next.tool(), "message", "已根据 Observation 增加下一步：“" + next.title() + "”",
                            "progress", dynamicProgress(completedActions)));
                    if (next.requiresConfirmation()) {
                        pauseForConfirmation(run, next);
                        return;
                    }
                    extended = true;
                    break;
                }
                if (!runtime.dynamic()) {
                    finishRun(run, finalSummary(loadSteps(run.planId()), effects), effects);
                    return;
                }
                if (!extended && loadSteps(run.planId()).stream().noneMatch(step -> "PENDING".equals(step.status()))) {
                    finishRun(run, finalSummary(loadSteps(run.planId()), effects), effects);
                    return;
                }
            }
        } catch (RuntimeException ex) {
            failRun(run, ex);
        }
    }

    public boolean continuousAgentAvailable() {
        var health = worker.health();
        return "online".equals(health.get("status"))
                && "deep-agents".equals(health.get("agentRuntimeMode"))
                && Boolean.TRUE.equals(health.get("continuousAgent"));
    }

    @SuppressWarnings("unchecked")
    private void executeContinuous(RunResponse run, PlanRuntime runtime, Map<String, Object> effects) {
        var row = jdbc.sql("select gateway_token, approval_count from assistant_run where id = :id")
                .param("id", run.id()).query((rs, index) -> Map.<String, Object>of(
                        "token", rs.getString("gateway_token"),
                        "approvalCount", rs.getInt("approval_count"))).single();
        var approvalCount = ((Number) row.get("approvalCount")).intValue();
        var resume = approvalCount > 0;
        var context = withRecentMessages(workspaceContext(runtime.projectId()), run.sessionId());
        var request = planner.continuousRequest(runtime.goal(), runtime.page(), context, run.sessionId(),
                runtime.executionMode(), run.id(), gatewayBaseUrl, String.valueOf(row.get("token")),
                resume, approvalCount);
        jdbc.sql("update assistant_plan_step set status = 'RUNNING' where plan_id = :id and tool_name = 'agent.execute' and status = 'PENDING'")
                .param("id", run.planId()).update();
        var result = interruptions.token("run", run.id()).await(() -> worker.runContinuousAgentStreaming(request, event -> {
            var type = Objects.toString(event.get("type"), "");
            if (Set.of("tool_call", "observation", "waiting_confirmation", "completed", "error").contains(type)) return;
            var payload = new LinkedHashMap<String, Object>();
            event.forEach((key, value) -> { if (value != null) payload.put(key, value); });
            events.publish(run.sessionId(), run.id(), "agent." + type, payload);
        }));
        if (isCanceled(run.id())) return;
        if ("waiting_confirmation".equals(result.get("type"))) {
            var actions = result.get("actions") instanceof List<?> values ? values : List.of();
            var staged = new ArrayList<PlanStep>();
            synchronized (runLock(run.id())) {
                for (var value : actions) {
                    if (!(value instanceof Map<?, ?> raw)) continue;
                    var tool = resolveToolName(Objects.toString(raw.get("toolName"), Objects.toString(raw.get("tool"), "")));
                    var capability = AssistantCapabilityRegistry.find(tool).orElse(null);
                    if (capability == null || "READ".equals(capability.mode())) continue;
                    var arguments = new LinkedHashMap<String, Object>();
                    if (raw.get("arguments") instanceof Map<?, ?> values) {
                        values.forEach((key, item) -> arguments.put(String.valueOf(key), item));
                    }
                    var proposed = new PlanStep(UUID.randomUUID().toString(), 0, capability.id(), capability.mode(),
                            capability.title(), capability.description(), arguments, capability.risk(), true, "PENDING");
                    staged.add(appendDynamicStep(run.planId(), proposed, runtime.executionMode(),
                            "Agent 已停在执行前，等待确认写入操作"));
                }
            }
            if (staged.isEmpty()) throw new IllegalStateException("Agent 请求确认，但没有返回有效的写入操作");
            jdbc.sql("update assistant_run set approval_count = :count where id = :id")
                    .param("count", staged.size()).param("id", run.id()).update();
            pauseForConfirmation(run, staged.getFirst());
            return;
        }
        var summary = Objects.toString(result.get("content"), Objects.toString(result.get("message"), "任务已完成"));
        jdbc.sql("update assistant_plan_step set status = 'SUCCEEDED' where plan_id = :id and tool_name = 'agent.execute' and status = 'RUNNING'")
                .param("id", run.planId()).update();
        jdbc.sql("update assistant_run set approval_count = 0 where id = :id").param("id", run.id()).update();
        finishRun(run, summary, readEffects(run.id()));
    }

    public Map<String, Object> callContinuousTool(String runId, String gatewayToken, Map<String, Object> request) {
        var run = get(runId);
        var stored = jdbc.sql("select gateway_token from assistant_run where id = :id")
                .param("id", runId).query(String.class).single();
        if (stored.isBlank() || !stored.equals(gatewayToken)) throw new SecurityException("Agent 工具网关凭证无效");
        if (!"RUNNING".equals(run.status())) throw new IllegalStateException("Agent 任务当前不允许调用工具");
        var tool = resolveToolName(Objects.toString(request.get("tool"), ""));
        var capability = AssistantCapabilityRegistry.find(tool)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在：" + tool));
        var arguments = new LinkedHashMap<String, Object>();
        if (request.get("arguments") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        }
        if (completedActionCount(run.planId()) >= maxDynamicActions) {
            return Map.of("success", false, "error", "本次任务已用完工具调用预算",
                    "failureType", "BUDGET_EXHAUSTED", "retryable", false,
                    "recoveryHint", "停止调用工具，总结已完成内容与剩余工作");
        }
        var lock = runLock(runId);
        var selectedLock = "READ".equals(capability.mode()) ? lock.readLock() : lock.writeLock();
        selectedLock.lock();
        try {
            var runtime = loadRuntime(run.planId());
            PlanStep step;
            synchronized (lock) {
                step = loadSteps(run.planId()).stream()
                        .filter(item -> "PENDING".equals(item.status()) && item.tool().equals(tool))
                        .findFirst().orElse(null);
                if (step == null) {
                    if (!"READ".equals(capability.mode()) && !"AUTO".equalsIgnoreCase(runtime.executionMode())) {
                        return Map.of("success", false, "error", "该写入操作尚未获得用户确认",
                                "failureType", "AUTHORIZATION", "retryable", false,
                                "recoveryHint", "暂停并等待用户在界面中确认");
                    }
                    var proposed = new PlanStep(UUID.randomUUID().toString(), 0, tool, capability.mode(),
                            capability.title(), capability.description(), arguments, capability.risk(), false, "PENDING");
                    step = appendDynamicStep(run.planId(), proposed, "AUTO", "Agent 正在持续执行任务");
                }
            }
            var observation = executeAndObserve(run, step, new LinkedHashMap<>(readEffects(runId)));
            if (Boolean.TRUE.equals(observation.get("success"))) {
                var currentEffects = readEffects(runId);
                var goal = loadRuntime(run.planId()).goal();
                var completion = "deliverable.create".equals(tool)
                        ? deterministicDeliverableCompletion(goal, currentEffects)
                        : deterministicWorkflowCompletion(tool, goal, currentEffects);
                if (completion != null) {
                    observation = new LinkedHashMap<>(observation);
                    observation.put("taskComplete", true);
                    observation.put("completionSummary", completion);
                }
            }
            return observation;
        } finally {
            selectedLock.unlock();
        }
    }

    private boolean isContinuousRun(String runId) {
        return jdbc.sql("select continuous_agent from assistant_run where id = :id")
                .param("id", runId).query(Boolean.class).single();
    }

    private ReentrantReadWriteLock runLock(String runId) {
        return runLocks.computeIfAbsent(runId, ignored -> new ReentrantReadWriteLock(true));
    }

    private String resolveToolName(String value) {
        if (AssistantCapabilityRegistry.find(value).isPresent()) return value;
        return AssistantCapabilityRegistry.ids().stream()
                .filter(id -> id.replace(".", "_").replace("-", "_").equals(value))
                .findFirst().orElse(value);
    }

    private Map<String, Object> readEffects(String runId) {
        return jdbc.sql("select effects_json from assistant_run where id = :id")
                .param("id", runId).query(String.class).optional().map(this::readMap).orElse(Map.of());
    }

    private AssistantPlanner.WorkspaceContext withRecentMessages(AssistantPlanner.WorkspaceContext context,
                                                                  String sessionId) {
        var messages = jdbc.sql("""
                        select role, content from assistant_message where session_id = :sessionId
                        order by created_at desc limit 30
                        """).param("sessionId", sessionId)
                .query((rs, row) -> Map.of("role", rs.getString("role").toLowerCase(Locale.ROOT),
                        "content", rs.getString("content"))).list();
        java.util.Collections.reverse(messages);
        return new AssistantPlanner.WorkspaceContext(context.projectId(), context.projectName(), context.dataCount(),
                context.knowledgeCount(), context.outputCount(), context.hasStructuredData(),
                context.selectedResourceId(), context.selectedResourceType(), context.selectedResourceName(),
                context.resources(), messages);
    }

    private Map<String, Object> executeAndObserve(RunResponse run, PlanStep step, Map<String, Object> effects) {
        if (isCanceled(run.id())) return Map.of("success", false, "error", "任务已停止");
        var runtime = loadRuntime(run.planId());
        step = applyDeliverablePolicy(withWorkspaceContext(step, runtime), runtime.goal());
        var total = loadSteps(run.planId()).size();
        var progress = dynamicProgress(completedActionCount(run.planId()));
        jdbc.sql("update assistant_run set current_step = :step where id = :id")
                .param("step", step.order()).param("id", run.id()).update();
        var claimed = jdbc.sql("""
                update assistant_plan_step set status = 'RUNNING' where id = :id and status = 'PENDING'
                and exists (select 1 from assistant_run where id = :runId and status = 'RUNNING')
                """).param("id", step.id()).param("runId", run.id()).update();
        if (claimed == 0) return Map.of("success", false, "error", "任务已停止");
        events.publish(run.sessionId(), run.id(), "assistant.step.started", Map.of(
                "step", step.order(), "totalSteps", total, "title", step.title(),
                "message", step.description(), "progress", progress, "tool", step.tool()));
        events.publish(run.sessionId(), run.id(), "agent.tool_call", Map.of(
                "status", "running", "step", step.order(), "totalSteps", total,
                "toolName", step.tool(), "argumentSummary", summarizeArguments(step.arguments()),
                "message", step.title(), "progress", progress));
        events.publish(run.sessionId(), run.id(), "agent.executing", Map.of(
                "status", "running", "step", step.order(), "toolName", step.tool(),
                "message", step.description(), "progress", progress));
        effects.remove("uiAction");
        effects.remove("changed");
        var effectsBefore = new LinkedHashMap<String, Object>(effects);
        var observation = new LinkedHashMap<String, Object>();
        observation.put("tool", step.tool());
        observation.put("arguments", step.arguments());
        try {
            var result = executeStep(step, effects);
            verifyStepOutcome(step, effectsBefore, effects);
            if (!"READ".equals(step.mode())) {
                var action = new LinkedHashMap<String, Object>();
                if (effects.get("uiAction") instanceof Map<?, ?> existing) existing.forEach((key, value) -> action.put(String.valueOf(key), value));
                action.putIfAbsent("type", "REFRESH_WORKSPACE");
                var projectId = activeProjectId(loadRuntime(run.planId()), step, effects);
                if (projectId != null) action.putIfAbsent("projectId", projectId);
                action.put("refreshWorkspace", true);
                effects.put("uiAction", action);
            }
            jdbc.sql("update assistant_plan_step set status = 'SUCCEEDED' where id = :id")
                    .param("id", step.id()).update();
            observation.put("success", true);
            observation.put("result", result);
            observation.put("output", observationOutput(effectsBefore, effects));
            observation.put("provenance", provenance(step, effects));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("step", step.order()); payload.put("totalSteps", total); payload.put("title", step.title());
            payload.put("message", result); payload.put("result", result); payload.put("progress", progress);
            payload.put("tool", step.tool()); payload.put("output", observationOutput(effectsBefore, effects));
            payload.put("provenance", provenance(step, effects));
            if (effects.get("uiAction") instanceof Map<?, ?> action) payload.put("uiAction", action);
            events.publish(run.sessionId(), run.id(), "assistant.step.completed", payload);
            events.publish(run.sessionId(), run.id(), "agent.observation", Map.of(
                    "status", "completed", "step", step.order(), "toolName", step.tool(),
                    "resultSummary", result, "message", result, "progress", progress,
                    "provenance", provenance(step, effects)));
        } catch (RuntimeException exception) {
            var error = exception.getMessage() == null ? "工具执行失败" : exception.getMessage();
            var failure = AssistantFailurePolicy.classify(exception);
            jdbc.sql("update assistant_plan_step set status = 'FAILED' where id = :id")
                    .param("id", step.id()).update();
            observation.put("success", false);
            observation.put("error", error);
            observation.put("failureType", failure.category().name());
            observation.put("retryable", failure.retryable());
            observation.put("recoveryHint", failure.recoveryHint());
            observation.put("provenance", provenance(step, effects));
            events.publish(run.sessionId(), run.id(), "agent.observation", Map.of(
                    "status", "failed", "step", step.order(), "toolName", step.tool(),
                    "resultSummary", error, "message", "工具没有完成，Agent 正在调整方案",
                    "error", error, "progress", progress, "provenance", provenance(step, effects)));
        }
        jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                .param("effects", writeJson(effects)).param("id", run.id()).update();
        return observation;
    }

    @SuppressWarnings("unchecked")
    private PlanStep withWorkspaceContext(PlanStep step, PlanRuntime runtime) {
        var capability = AssistantCapabilityRegistry.find(step.tool()).orElse(null);
        if (capability == null) return step;
        var properties = capability.inputSchema().get("properties") instanceof Map<?, ?> values
                ? (Map<String, Object>) values : Map.<String, Object>of();
        var arguments = new LinkedHashMap<>(step.arguments());
        Set.of("page", "goal", "project_id").stream()
                .filter(key -> !properties.containsKey(key)).forEach(arguments::remove);
        if (properties.containsKey("project_id") && !arguments.containsKey("project_id")
                && runtime.projectId() != null && !runtime.projectId().isBlank()) {
            arguments.put("project_id", runtime.projectId());
        }
        if (arguments.equals(step.arguments())) return step;
        return new PlanStep(step.id(), step.order(), step.tool(), step.mode(), step.title(), step.description(),
                Map.copyOf(arguments), step.risk(), step.requiresConfirmation(), step.status());
    }

    private PlanStep appendDynamicStep(String planId, PlanStep proposed, String executionMode, String summary) {
        var current = loadSteps(planId);
        var order = current.stream().mapToInt(PlanStep::order).max().orElse(0) + 1;
        var requiresConfirmation = !"AUTO".equalsIgnoreCase(executionMode) && proposed.risk().requiresConfirmation();
        var step = new PlanStep(UUID.randomUUID().toString(), order, proposed.tool(), proposed.mode(),
                proposed.title(), proposed.description(), proposed.arguments(), proposed.risk(), requiresConfirmation, "PENDING");
        var risk = java.util.stream.Stream.concat(loadSteps(planId).stream().map(PlanStep::risk),
                        java.util.stream.Stream.of(proposed.risk()))
                .max(java.util.Comparator.comparing(Enum::ordinal)).orElse(proposed.risk());
        jdbc.sql("""
                        insert into assistant_plan_step(id, plan_id, step_order, tool_name, tool_mode, title,
                            description, arguments_json, risk_level, requires_confirmation, status)
                        values (:id, :planId, :stepOrder, :toolName, :toolMode, :title, :description,
                            :arguments, :riskLevel, :requiresConfirmation, 'PENDING')
                        """)
                .param("id", step.id()).param("planId", planId).param("stepOrder", order)
                .param("toolName", step.tool()).param("toolMode", step.mode()).param("title", step.title())
                .param("description", step.description()).param("arguments", writeJson(step.arguments()))
                .param("riskLevel", step.risk().name()).param("requiresConfirmation", requiresConfirmation).update();
        var version = jdbc.sql("select version from assistant_plan where id = :id")
                .param("id", planId).query(Integer.class).single() + 1;
        var hash = HashSupport.sha256(writeJson(Map.of("planId", planId, "version", version,
                "steps", loadSteps(planId), "summary", summary)));
        jdbc.sql("""
                        update assistant_plan set version = :version, plan_hash = :hash, summary = :summary,
                            risk_level = :risk, status = :status, expires_at = :expiresAt
                        where id = :id and status <> 'CANCELED'
                        """)
                .param("version", version).param("hash", hash).param("summary", summary)
                .param("risk", risk.name())
                .param("status", requiresConfirmation ? "WAITING_CONFIRMATION" : "RUNNING")
                .param("expiresAt", Instant.now().plusSeconds(1800)).param("id", planId).update();
        return step;
    }

    private void pauseForConfirmation(RunResponse run, PlanStep next) {
        var changed = jdbc.sql("update assistant_run set status = 'WAITING_CONFIRMATION', result_summary = :summary where id = :id and status = 'RUNNING'")
                .param("summary", "等待确认下一步：“" + next.title() + "”").param("id", run.id()).update();
        if (changed == 0) return;
        var plan = jdbc.sql("select version, plan_hash from assistant_plan where id = :id")
                .param("id", run.planId()).query((rs, row) -> Map.of(
                        "version", rs.getInt("version"), "hash", rs.getString("plan_hash"))).single();
        events.publish(run.sessionId(), run.id(), "assistant.confirmation.required", Map.of(
                "planId", run.planId(), "planVersion", plan.get("version"), "planHash", plan.get("hash"),
                "progress", dynamicProgress(completedActionCount(run.planId())),
                "message", "Agent 根据执行结果提出了新的修改，需要确认后继续"));
        events.publish(run.sessionId(), run.id(), "agent.waiting_confirmation", Map.of(
                "status", "waiting", "planId", run.planId(), "toolName", next.tool(),
                "message", "等待确认动态追加的步骤：“" + next.title() + "”"));
    }

    private void finishRun(RunResponse run, String summary, Map<String, Object> effects) {
        verifyTaskCompletion(loadSteps(run.planId()), effects);
        var safeSummary = summary == null || summary.isBlank() ? "已完成工作台操作。" : summary;
        var changed = jdbc.sql("update assistant_run set status = 'SUCCEEDED', result_summary = :summary, finished_at = :now where id = :id and status = 'RUNNING'")
                .param("summary", safeSummary).param("now", Instant.now()).param("id", run.id()).update();
        if (changed == 0) return;
        jdbc.sql("update assistant_plan set status = 'COMPLETED', summary = :summary where id = :id")
                .param("summary", safeSummary).param("id", run.planId()).update();
        moveSessionToCreatedProject(run, effects);
        saveAssistantMessage(run.sessionId(), safeSummary, run.id());
        events.publish(run.sessionId(), run.id(), "agent.generating", Map.of(
                "status", "completed", "message", "正在整理最终结果和执行轨迹", "progress", 98));
        events.publish(run.sessionId(), run.id(), "assistant.run.completed", Map.of(
                "runId", run.id(), "summary", safeSummary, "message", safeSummary, "progress", 100,
                "canRollback", effects.containsKey("createdProjectId")));
        events.publish(run.sessionId(), run.id(), "agent.completed", Map.of(
                "status", "completed", "summary", safeSummary, "message", safeSummary, "progress", 100,
                "provenance", Map.of("traceId", run.id(), "toolCount", completedActionCount(run.planId()))));
        runLocks.remove(run.id());
    }

    private void moveSessionToCreatedProject(RunResponse run, Map<String, Object> effects) {
        var projectId = Objects.toString(effects.get("createdProjectId"), "");
        if (projectId.isBlank()) return;
        jdbc.sql("update assistant_session set project_id = :projectId, updated_at = :now where id = :id")
                .param("projectId", projectId).param("now", Instant.now()).param("id", run.sessionId()).update();
    }

    private void failRun(RunResponse run, RuntimeException exception) {
        var failure = exception.getMessage() == null ? "执行失败" : exception.getMessage();
        var changed = jdbc.sql("update assistant_run set status = 'FAILED', result_summary = :message, finished_at = :now where id = :id and status in ('QUEUED', 'RUNNING')")
                .param("message", failure).param("now", Instant.now()).param("id", run.id()).update();
        if (changed == 0) return;
        jdbc.sql("update assistant_plan set status = 'FAILED' where id = :id")
                .param("id", run.planId()).update();
        saveAssistantMessage(run.sessionId(), "本次任务未完成：" + failure, run.id());
        events.publish(run.sessionId(), run.id(), "assistant.run.failed", Map.of(
                "runId", run.id(), "progress", 100, "message", "当前步骤没有完成，可以从这里重试", "error", failure));
        events.publish(run.sessionId(), run.id(), "agent.failed", Map.of(
                "status", "failed", "progress", 100, "message", "当前步骤没有完成，可以展开查看错误", "error", failure));
        runLocks.remove(run.id());
    }

    private int completedActionCount(String planId) {
        return jdbc.sql("""
                        select count(*) from assistant_plan_step
                        where plan_id = :planId and status in ('SUCCEEDED', 'FAILED')
                        """).param("planId", planId).query(Integer.class).single();
    }

    private int dynamicProgress(int completedActions) {
        return Math.min(94, 25 + completedActions * 6);
    }

    private String activeProjectId(PlanRuntime runtime, PlanStep completedStep, Map<String, Object> effects) {
        if ("project.delete".equals(completedStep.tool())) return null;
        return Objects.toString(effects.get("createdProjectId"), runtime.projectId());
    }

    private Map<String, Object> observationOutput(Map<String, Object> before, Map<String, Object> after) {
        var output = new LinkedHashMap<String, Object>();
        after.forEach((key, value) -> {
            if (!"uiAction".equals(key) && (Set.of("workflow", "changed").contains(key) || !Objects.equals(before.get(key), value))) output.put(key, value);
        });
        return output;
    }

    private void verifyStepOutcome(PlanStep step, Map<String, Object> before, Map<String, Object> effects) {
        if (Set.of("workflow.edit", "workflow.add_node", "workflow.remove_node", "workflow.connect").contains(step.tool())) {
            if (!(effects.get("workflow") instanceof com.finflow.studio.workflow.WorkflowModels.WorkflowResponse workflow))
                throw new IllegalStateException("结果验证失败：工作流修改没有返回已保存的工作流和版本");
            if (workflow.currentVersion() < 1) throw new IllegalStateException("结果验证失败：工作流版本无效");
            if ("workflow.add_node".equals(step.tool())) verifyWorkflowNode(workflow,
                    Objects.toString(effects.get("workflowNodeId"), ""));
        }
        if ("deliverable.create".equals(step.tool())) verifyNewDeliverables(before, effects, true);
        if ("workflow.run".equals(step.tool()) && "SUCCEEDED".equals(effects.get("workflowRunStatus"))) {
            var requested = step.arguments().get("output_formats") instanceof List<?> values && !values.isEmpty();
            verifyNewDeliverables(before, effects, requested);
        }
        if ("deliverable.edit".equals(step.tool())) {
            var item = deliverables.get(Objects.toString(step.arguments().get("deliverable_id"), ""));
            verifyArtifact(item.id(), item.format());
        }
        if ("deliverable.export".equals(step.tool())) {
            var export = effects.get("export") instanceof Map<?, ?> value ? value : Map.of();
            var id = Objects.toString(export.get("deliverableId"), "");
            if (id.isBlank()) throw new IllegalStateException("结果验证失败：导出操作没有返回交付件 ID");
            verifyArtifact(id, Objects.toString(export.get("format"), ""));
        }
    }

    private void verifyWorkflowNode(com.finflow.studio.workflow.WorkflowModels.WorkflowResponse workflow, String nodeId) {
        var node = workflow.nodes().stream().filter(item -> item.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("结果验证失败：新建节点没有写入工作流"));
        var field = switch (node.type()) {
            case AI_ANALYSIS -> "prompt";
            case AGENT_TASK -> "instruction";
            case DELIVERABLE, OUTPUT -> "generationPrompt";
            default -> "";
        };
        if (!field.isBlank() && Objects.toString(node.config().get(field), "").isBlank())
            throw new IllegalStateException("结果验证失败：节点“" + node.name() + "”缺少完整的 " + field + " 要求");
    }

    @SuppressWarnings("unchecked")
    private void verifyNewDeliverables(Map<String, Object> before, Map<String, Object> effects, boolean required) {
        var previousIds = before.get("deliverables") instanceof List<?> items
                ? items.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(item -> Objects.toString(item.get("id"), Objects.toString(item.get("deliverableId"), "")))
                .collect(java.util.stream.Collectors.toSet()) : Set.<String>of();
        var outputs = effects.get("deliverables") instanceof List<?> items
                ? items.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item)
                .filter(item -> !previousIds.contains(Objects.toString(item.get("id"), Objects.toString(item.get("deliverableId"), "")))).toList()
                : List.<Map<String, Object>>of();
        if (required && outputs.isEmpty()) throw new IllegalStateException("结果验证失败：任务要求生成成果，但没有创建任何输出文件");
        var expectedProvenance = hasResearchEvidence(effects);
        var verified = new ArrayList<Map<String, Object>>();
        for (var output : outputs) {
            var id = Objects.toString(output.get("id"), Objects.toString(output.get("deliverableId"), ""));
            var format = Objects.toString(output.get("format"), "");
            if (id.isBlank() || format.isBlank()) throw new IllegalStateException("结果验证失败：输出件缺少 ID 或格式");
            verifyArtifact(id, format);
            if (expectedProvenance && (!(output.get("refIds") instanceof List<?> refs) || refs.isEmpty()))
                throw new IllegalStateException("结果验证失败：输出件没有保留已使用资料的引用链路");
            var item = deliverables.get(id);
            verified.add(Map.of("id", id, "format", item.format(), "version", item.currentVersion(),
                    "sizeBytes", item.sizeBytes(), "checksum", item.checksum()));
        }
        if (!verified.isEmpty()) effects.put("verifiedArtifacts", verified);
    }

    private void verifyArtifact(String id, String expectedFormat) {
        var item = deliverables.get(id);
        if (item.currentVersion() < 1 || item.sizeBytes() < 16 || item.checksum() == null || item.checksum().isBlank())
            throw new IllegalStateException("结果验证失败：输出文件为空、版本无效或缺少校验值");
        if (!expectedFormat.isBlank() && !item.format().equalsIgnoreCase(expectedFormat))
            throw new IllegalStateException("结果验证失败：输出格式与请求不一致，预期 " + expectedFormat + "，实际 " + item.format());
        Path path = deliverables.path(id, null);
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != item.sizeBytes())
                throw new IllegalStateException("结果验证失败：输出文件不存在或大小与版本记录不一致");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(8192); }
            var prefix = new String(bytes, StandardCharsets.UTF_8);
            var format = item.format().toLowerCase(Locale.ROOT);
            if ("pdf".equals(format) && !prefix.startsWith("%PDF")) throw new IllegalStateException("结果验证失败：PDF 文件内容无效");
            if (Set.of("pptx", "docx").contains(format) && !prefix.startsWith("PK")) throw new IllegalStateException("结果验证失败：Office 文件内容无效");
            if ("html_slides".equals(format)) {
                var normalized = prefix.stripLeading().toLowerCase(Locale.ROOT);
                if (!normalized.startsWith("<!doctype html") && !normalized.startsWith("<html"))
                    throw new IllegalStateException("结果验证失败：HTML 文件结构无效");
                if (normalized.contains("&lt;style") || normalized.contains("```html") || normalized.contains("&lt;!doctype html"))
                    throw new IllegalStateException("结果验证失败：模型把 HTML/CSS 源码作为正文返回，未生成有效业务内容");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("结果验证失败：无法读取输出文件", exception);
        }
    }

    private void verifyTaskCompletion(List<PlanStep> steps, Map<String, Object> effects) {
        if (steps.stream().anyMatch(step -> Set.of("PENDING", "RUNNING").contains(step.status())))
            throw new IllegalStateException("任务尚未完成：仍有工具操作未结束");
        var lastRealStep = steps.stream().filter(step -> !"agent.execute".equals(step.tool())).reduce((left, right) -> right);
        if (lastRealStep.isPresent() && "FAILED".equals(lastRealStep.get().status()))
            throw new IllegalStateException("任务尚未完成：最后一个工具操作失败，且没有经过替代方式恢复");
        if (steps.stream().anyMatch(step -> "deliverable.create".equals(step.tool())))
            verifyNewDeliverables(Map.of(), effects, true);
        var verifiedWrites = steps.stream().filter(step -> "SUCCEEDED".equals(step.status()) && !"READ".equals(step.mode())).count();
        if (verifiedWrites > 0 && Boolean.FALSE.equals(effects.get("changed")) && !effects.containsKey("verifiedArtifacts"))
            throw new IllegalStateException("任务尚未完成：最后一次修改没有产生变化，不能报告已完成");
    }

    @SuppressWarnings("unchecked")
    private String deterministicDeliverableCompletion(String goal, Map<String, Object> effects) {
        var required = requestedFormats(goal);
        if (required.isEmpty()) return null;
        var outputs = effects.get("deliverables") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        var actual = outputs.stream().map(item -> normalizeRequestedFormat(Objects.toString(item.get("format"), "")))
                .collect(java.util.stream.Collectors.toSet());
        if (!actual.containsAll(required)) return null;
        var citationsRequired = hasResearchEvidence(effects);
        if (citationsRequired && outputs.stream().anyMatch(item -> !(item.get("refIds") instanceof List<?> refs) || refs.isEmpty()))
            return null;
        var names = outputs.stream().map(item -> Objects.toString(item.get("name"), "输出件")).distinct().toList();
        return "已生成并验证" + String.join("、", names) + "，成果已在输出件中打开。";
    }

    @SuppressWarnings("unchecked")
    String deterministicWorkflowCompletion(String tool, String goal, Map<String, Object> effects) {
        if (!Set.of("workflow.edit", "workflow.prepare", "workflow.initialize", "workflow.initialize_analysis").contains(tool))
            return null;
        var normalizedGoal = Objects.toString(goal, "").toLowerCase(Locale.ROOT);
        if (!containsAny(normalizedGoal, "工作流", "workflow")) return null;
        if (containsAny(normalizedGoal, "运行工作流", "执行工作流", "并运行", "并执行", "跑一下", "试运行", "run workflow"))
            return null;
        if (!(effects.get("workflow") instanceof Map<?, ?> rawWorkflow)) return null;
        var workflow = (Map<String, Object>) rawWorkflow;
        if (!"READY".equals(Objects.toString(workflow.get("status"), ""))) return null;
        var nodes = workflow.get("nodes") instanceof List<?> values ? values : List.of();
        var edges = workflow.get("edges") instanceof List<?> values ? values : List.of();
        if (nodes.isEmpty()) return null;
        var name = Objects.toString(workflow.get("name"), "工作流");
        var version = workflow.get("currentVersion") instanceof Number number ? number.intValue() : 1;
        return "已创建并验证可复用工作流“" + name + "”（v" + version + "），包含 "
                + nodes.size() + " 个步骤和 " + edges.size() + " 条连线，已在工作流画布中打开。";
    }

    private Set<String> requestedFormats(String goal) {
        var text = Objects.toString(goal, "").toUpperCase(Locale.ROOT);
        var formats = new java.util.LinkedHashSet<String>();
        if (text.contains("PDF")) formats.add("PDF");
        if (text.contains("PPT") || text.contains("POWERPOINT")) formats.add("PPTX");
        if (text.contains("HTML") || containsAny(text, "网页报告", "网页幻灯", "网页演示")) formats.add("HTML_SLIDES");
        if (text.contains("WORD") || text.contains("DOCX")) formats.add("DOCX");
        if (containsAny(text, "交互报告", "可交互报告", "自助分析", "数据看板", "图表报告"))
            formats.add("FINANCIAL_REPORT");
        if (text.contains("MERMAID")) formats.add("MERMAID");
        if (text.contains("EXCALIDRAW") || text.contains("手绘图")) formats.add("EXCALIDRAW");
        if (formats.isEmpty() && containsAny(text, "分析", "总结", "归纳", "洞察", "研究", "对比", "复盘",
                "ANALYZE", "ANALYSIS", "RESEARCH", "SUMMARY", "COMPARE")) formats.add("PPTX");
        return java.util.Collections.unmodifiableSet(formats);
    }

    PlanStep applyDeliverablePolicy(PlanStep step, String userGoal) {
        if (!"deliverable.create".equals(step.tool())) return step;
        var requested = requestedFormats(userGoal);
        var arguments = new LinkedHashMap<>(step.arguments());
        var selected = normalizeDeliverableFormat(Objects.toString(arguments.get("format"), "PPTX"));
        if (requested.isEmpty()) {
            selected = "PPTX";
        } else if (!requested.contains(selected)) {
            selected = requested.iterator().next();
        }
        arguments.put("format", selected);
        return new PlanStep(step.id(), step.order(), step.tool(), step.mode(), step.title(), step.description(),
                Map.copyOf(arguments), step.risk(), step.requiresConfirmation(), step.status());
    }

    private String normalizeRequestedFormat(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "PPT", "POWERPOINT" -> "PPTX";
            case "HTML", "WEB", "WEBPAGE" -> "HTML_SLIDES";
            default -> value.toUpperCase(Locale.ROOT);
        };
    }

    private PlanRuntime loadRuntime(String planId) {
        return jdbc.sql("""
                        select p.goal, p.execution_mode, p.dynamic_agent, c.page, c.project_id
                        from assistant_plan p join assistant_context_snapshot c on c.id = p.context_snapshot_id
                        where p.id = :id
                        """).param("id", planId).query((rs, row) -> new PlanRuntime(
                        rs.getString("goal"), rs.getString("page"), rs.getString("project_id"),
                        rs.getString("execution_mode"), rs.getBoolean("dynamic_agent"))).single();
    }

    private AssistantPlanner.WorkspaceContext workspaceContext(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            var items = projects.list().stream().map(project -> Map.<String, Object>of(
                    "id", project.id(), "name", project.name(), "type", "PROJECT",
                    "group", "PROJECT", "status", project.status())).toList();
            return new AssistantPlanner.WorkspaceContext(null, "个人工作台", 0, 0, 0,
                    false, null, null, null, items, List.of());
        }
        var snapshot = workspace.get(projectId);
        var items = new ArrayList<Map<String, Object>>();
        snapshot.folders().forEach(folder -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", folder.id()); item.put("name", folder.name()); item.put("type", "FOLDER");
            item.put("group", folder.rootKind()); item.put("status", "READY");
            if (folder.parentId() != null) item.put("parent_id", folder.parentId());
            items.add(item);
        });
        snapshot.workflows().forEach(workflow -> items.add(Map.of(
                "id", workflow.id(), "name", workflow.name(), "type", "WORKFLOW",
                "group", "WORKFLOW", "status", workflow.status())));
        snapshot.resources().forEach(resource -> items.add(Map.of(
                "id", resource.id(), "name", resource.name(), "type", resource.resourceType(),
                "group", resource.group(), "status", resource.status())));
        return new AssistantPlanner.WorkspaceContext(projectId, snapshot.project().name(),
                (int) snapshot.resources().stream().filter(item -> "DATA".equals(item.group())).count(),
                (int) snapshot.resources().stream().filter(item -> "KNOWLEDGE".equals(item.group())).count(),
                (int) snapshot.resources().stream().filter(item -> "OUTPUT".equals(item.group())).count(),
                snapshot.resources().stream().anyMatch(item -> "DATA".equals(item.group())),
                null, null, null, List.copyOf(items), List.of());
    }

    private record PlanRuntime(String goal, String page, String projectId, String executionMode, boolean dynamic) { }

    private void saveAssistantMessage(String sessionId, String content, String traceId) {
        jdbc.sql("""
                        insert into assistant_message(id, session_id, role, content, model_name, trace_id, created_at)
                        values (:id, :sessionId, 'ASSISTANT', :content, 'deep-agents', :traceId, :createdAt)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("sessionId", sessionId)
                .param("content", content)
                .param("traceId", traceId)
                .param("createdAt", Instant.now())
                .update();
        jdbc.sql("update assistant_session set updated_at = :now where id = :id")
                .param("now", Instant.now()).param("id", sessionId).update();
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunResponse cancel(String id) {
        var run = get(id);
        jdbc.sql("select id from assistant_plan where id = :id for update")
                .param("id", run.planId()).query(String.class).single();
        if (List.of("SUCCEEDED", "FAILED", "CANCELED", "ROLLED_BACK").contains(run.status())) {
            return run;
        }
        var message = "已中断，后续步骤不会继续。已完成的修改会保留，正在提交的操作可能仍会完成。";
        var changed = jdbc.sql("update assistant_run set status = 'CANCELED', result_summary = :message, finished_at = :now where id = :id and status in ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION')")
                .param("message", message)
                .param("now", Instant.now())
                .param("id", id)
                .update();
        if (changed == 0) return get(id);
        jdbc.sql("update assistant_plan set status = 'CANCELED' where id = :id").param("id", run.planId()).update();
        jdbc.sql("update assistant_plan_step set status = 'CANCELED' where plan_id = :id and status = 'PENDING'")
                .param("id", run.planId()).update();
        // Interrupt model decisions only; a tool already committing a write must settle normally.
        interruptions.token("run", id).cancel();
        saveAssistantMessage(run.sessionId(), message, id);
        events.publish(run.sessionId(), id, "assistant.run.canceled", Map.of("runId", id));
        events.publish(run.sessionId(), id, "agent.cancelled", Map.of(
                "status", "cancelled", "runId", id, "planId", run.planId(), "message", message));
        runLocks.remove(id);
        return get(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPlan(String planId) {
        var sessionId = jdbc.sql("select session_id from assistant_plan where id = :id for update")
                .param("id", planId).query(String.class).single();
        var runId = jdbc.sql("select id from assistant_run where plan_id = :id order by created_at desc limit 1")
                .param("id", planId).query(String.class).optional();
        if (runId.isPresent()) {
            cancel(runId.get());
            return;
        }
        var changed = jdbc.sql("update assistant_plan set status = 'CANCELED' where id = :id and status in ('WAITING_CONFIRMATION', 'PLAN_READY')")
                .param("id", planId).update();
        if (changed == 0) return;
        jdbc.sql("update assistant_plan_step set status = 'CANCELED' where plan_id = :id and status = 'PENDING'")
                .param("id", planId).update();
        var message = "已取消本次待确认操作，没有执行修改。";
        saveAssistantMessage(sessionId, message, planId);
        events.publish(sessionId, null, "agent.cancelled", Map.of("status", "cancelled", "planId", planId, "message", message));
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
        AssistantToolContracts.validate(step.tool(), step.arguments());
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
        if (effects.containsKey("datasetProvenance")) value.put("dataset", effects.get("datasetProvenance"));
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
        if (projectId.isBlank()) {
            effects.put("projects", projects.list());
            return "已读取当前工作环境和可用项目";
        }
        var snapshot = workspace.get(projectId);
        var data = snapshot.resources().stream().filter(item -> "DATA".equals(item.group())).count();
        var knowledge = snapshot.resources().stream().filter(item -> "KNOWLEDGE".equals(item.group())).count();
        var outputs = snapshot.resources().stream().filter(item -> "OUTPUT".equals(item.group())).count();
        effects.put("workspaceCounts", Map.of("data", data, "knowledge", knowledge, "outputs", outputs));
        effects.put("workspaceSummary", Map.of(
                "projectId", projectId,
                "projectName", snapshot.project().name(),
                "data", data,
                "knowledge", knowledge,
                "outputs", outputs));
        var items = new ArrayList<Map<String, Object>>();
        snapshot.folders().forEach(folder -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", folder.id()); item.put("name", folder.name()); item.put("type", "FOLDER");
            item.put("group", folder.rootKind());
            if (folder.parentId() != null) item.put("parentId", folder.parentId());
            items.add(item);
        });
        snapshot.workflows().forEach(workflow -> items.add(Map.of(
                "id", workflow.id(), "name", workflow.name(), "type", "WORKFLOW",
                "status", workflow.status(), "version", workflow.currentVersion())));
        snapshot.resources().forEach(resource -> items.add(Map.of(
                "id", resource.id(), "name", resource.name(), "type", resource.resourceType(),
                "group", resource.group(), "status", resource.status())));
        effects.put("workspaceItems", items);
        effects.put("sourceProjectId", projectId);
        return "已读取当前项目“" + snapshot.project().name() + "”：数据 " + data
                + " 项、资料 " + knowledge + " 项、输出 " + outputs + " 项";
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
        var preparedAnswer = argument(step, "prepared_answer", "");
        if (!preparedAnswer.isBlank()) {
            effects.put("assistantResponse", preparedAnswer);
            return preparedAnswer;
        }
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
                    """.formatted(goal, effects.getOrDefault("workspaceSummary",
                            effects.getOrDefault("workspaceCounts", Map.of()))), "工作台助手回答", 5);
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
        if ("FINANCIAL_REPORT".equals(format) && !hasInteractiveReportData(projectId)) {
            throw new IllegalStateException("交互报告需要项目中已有可读取的 CSV、TSV 或数据采集结果；当前项目没有符合条件的数据。若用户未明确要求交互报告，请改为生成 PPTX");
        }
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
        mergeRequestedCitations(step, effects, citations);
        if (hasResearchEvidence(effects) && citations.isEmpty())
            throw new IllegalStateException("结果验证失败：已读取研究资料，但交付件没有绑定引用");
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
                "version", created.currentVersion(), "downloadUrl", "/api/deliverables/" + created.id() + "/download",
                "refIds", citations.stream().map(CitationRequest::id).filter(id -> !id.isBlank()).toList()));
        effects.put("deliverables", outputs);
        effects.put("uiAction", Map.of("type", "OPEN_DELIVERABLE", "projectId", projectId,
                "resourceId", created.id(), "refreshWorkspace", true));
        return "已生成“" + created.name() + "”（" + created.format().toUpperCase() + "）";
    }

    private boolean hasInteractiveReportData(String projectId) {
        return workspace.get(projectId).resources().stream().anyMatch(resource -> {
            if ("DATASET".equals(resource.resourceType())) return true;
            if (!Set.of("DATA_FILE", "KNOWLEDGE_FILE", "OFFICE_FILE").contains(resource.resourceType())) return false;
            var name = Objects.toString(resource.name(), "").toLowerCase(Locale.ROOT);
            return name.endsWith(".csv") || name.endsWith(".tsv");
        });
    }

    @SuppressWarnings("unchecked")
    private void mergeRequestedCitations(PlanStep step, Map<String, Object> effects, List<CitationRequest> citations) {
        if (!(step.arguments().get("citations") instanceof List<?> requested) || requested.isEmpty()) return;
        var verified = new ArrayList<Map<String, Object>>();
        if (effects.get("verifiedSources") instanceof List<?> values)
            values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).forEach(verified::add);
        if (effects.get("knowledgeRefs") instanceof List<?> values) {
            values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).forEach(ref -> {
                var normalized = new LinkedHashMap<String, Object>();
                normalized.put("citationId", firstNonBlank(ref.get("citationId"), ref.get("id")));
                normalized.put("resourceId", ref.get("resourceId"));
                normalized.put("version", ref.get("version"));
                normalized.put("sourceName", ref.get("sourceName"));
                normalized.put("excerpt", firstNonBlank(ref.get("excerpt"), ref.get("text")));
                normalized.put("contentHash", ref.get("contentHash"));
                normalized.put("location", ref.get("location"));
                verified.add(normalized);
            });
        }
        for (var value : requested) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            var resourceId = firstNonBlank(raw.get("resource_id"), raw.get("resourceId"));
            var url = Objects.toString(raw.get("url"), "");
            var requestedCitationId = firstNonBlank(raw.get("citation_id"), raw.get("citationId"));
            var source = verified.stream().filter(item -> (!resourceId.isBlank() && resourceId.equals(item.get("resourceId")))
                            || (!url.isBlank() && url.equals(item.get("url")))
                            || (!requestedCitationId.isBlank() && requestedCitationId.equals(item.get("citationId"))))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "结果验证失败：交付件引用了尚未成功读取的来源 " + firstNonBlank(resourceId, url)));
            var citationId = requestedCitationId;
            if (citationId.isBlank()) citationId = Objects.toString(source.get("citationId"), "");
            if (citationId.isBlank()) citationId = "web:" + UUID.nameUUIDFromBytes(
                    Objects.toString(source.get("url"), "").getBytes(StandardCharsets.UTF_8));
            var finalCitationId = citationId;
            citations.removeIf(item -> finalCitationId.equals(item.id()));
            var location = source.get("location") instanceof Map<?, ?> valueLocation
                    ? new LinkedHashMap<String, Object>((Map<String, Object>) valueLocation)
                    : new LinkedHashMap<String, Object>();
            if (source.containsKey("url")) location.put("url", Objects.toString(source.get("url"), ""));
            if (source.containsKey("finalUrl")) location.put("finalUrl", Objects.toString(source.get("finalUrl"), ""));
            if (source.containsKey("verifiedAt")) location.put("verifiedAt", Objects.toString(source.get("verifiedAt"), ""));
            citations.add(new CitationRequest(citationId,
                    Objects.toString(source.get("resourceId"), resourceId),
                    source.get("version") instanceof Number number ? Math.max(1, number.intValue()) : 1,
                    firstNonBlank(raw.get("source_title"), raw.get("sourceName"), source.get("sourceName")),
                    firstNonBlank(raw.get("usage"), raw.get("excerpt"), source.get("excerpt")),
                    Map.copyOf(location), Objects.toString(source.get("contentHash"), "")));
        }
    }

    private boolean hasResearchEvidence(Map<String, Object> effects) {
        return effects.get("verifiedSources") instanceof List<?> sources && !sources.isEmpty()
                || effects.get("knowledgeCitations") instanceof List<?> citations && !citations.isEmpty()
                || effects.get("knowledgeRefs") instanceof List<?> refs && !refs.isEmpty();
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
        if (effects.get("knowledgeRefs") instanceof List<?> refs) {
            for (var value : refs) {
                if (!(value instanceof Map<?, ?> ref)) continue;
                result.append("[已读取资料] ").append(Objects.toString(ref.get("sourceName"), "项目资料"))
                        .append("：").append(firstNonBlank(ref.get("excerpt"), ref.get("text"))).append('\n');
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
        effects.put("workflow", created);
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
        effects.put("workflow", created);
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
                .map(this::configuredOutputFormat)
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
                .map(this::configuredOutputFormat)
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
            var config = new LinkedHashMap<String, Object>();
            var requirement = goal.isBlank() ? "根据上游内容生成结构清晰、可编辑的成果。" : goal;
            config.put("generationPrompt", requirement);
            config.put("format", format);
            config.put("includeCitations", true);
            config.put("citationStyle", "IEEE");
            config.put("pptSkill", "PPTX".equals(format) ? "guizang-huawei-style-c"
                    : "HTML_SLIDES".equals(format) ? "frontend-slides" : "");
            nodes.add(new NodeDefinition(id, NodeType.DELIVERABLE, outputLabel(format), 900, 100 + index * 180, config));
            if (upstreamId != null) edges.add(new EdgeDefinition("edge_" + shortId(), upstreamId, id));
        }
    }

    private String configuredOutputFormat(Map<String, Object> config) {
        var legacy = Objects.toString(config.get("format"), "").toUpperCase(Locale.ROOT);
        if (!legacy.isBlank()) return legacy;
        var prompt = Objects.toString(config.get("generationPrompt"), "").toUpperCase(Locale.ROOT);
        for (var format : List.of("HTML_SLIDES", "FINANCIAL_REPORT", "EXCALIDRAW", "MERMAID", "PPTX", "DOCX", "PDF")) {
            if (prompt.contains("输出形式：" + format)) return format;
        }
        return "";
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

    private String firstNonBlank(Object... values) {
        for (var value : values) {
            var text = Objects.toString(value, "").trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private boolean containsAny(String text, String... values) {
        for (var value : values) {
            if (text.contains(value)) return true;
        }
        return false;
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
