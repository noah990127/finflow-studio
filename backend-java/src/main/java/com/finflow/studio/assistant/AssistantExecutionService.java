package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RunResponse;
import com.finflow.studio.deliverable.DeliverableModels.CitationRequest;
import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.SectionRequest;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.data.DataTransformService;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
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
    private final DataTransformService dataTransforms;
    private final AssistantWorkspaceToolGateway workspaceTools;
    private final AssistantPlanner planner;
    private final AgentWorkService agentWorks;
    private final TacitAgentTools tacitTools;
    @org.springframework.beans.factory.annotation.Autowired
    private FixedWorkService fixedWorks;
    @org.springframework.beans.factory.annotation.Autowired
    private BrowserAuthorizationService browserAuthorizations;
    @org.springframework.beans.factory.annotation.Autowired
    private BrowserAgentService browserAgent;
    private final int maxDynamicActions;
    private final AssistantInterruptions interruptions;
    private final String gatewayBaseUrl;
    private final Map<String, ReentrantReadWriteLock> runLocks = new ConcurrentHashMap<>();
    private volatile boolean continuousAgentHealthy;
    private volatile String continuousRuntimeMode = "deep-agents";
    private volatile long continuousAgentHealthExpiresAt;

    public AssistantExecutionService(JdbcClient jdbc, AssistantEventService events,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                     ProjectService projects, WorkflowDefinitionService workflows,
                                     WorkerClient worker, ObjectMapper objectMapper,
                                     WorkspaceResourceService workspace, KnowledgeService knowledge,
                                     DeliverableService deliverables, WorkflowRunService workflowRuns,
                                     DataTransformService dataTransforms,
                                     AssistantWorkspaceToolGateway workspaceTools, AssistantPlanner planner,
                                     @Value("${finflow.agent.max-dynamic-actions:80}") int maxDynamicActions,
                                     @Value("${finflow.agent.gateway-base-url:http://127.0.0.1:8080}") String gatewayBaseUrl,
                                     AssistantInterruptions interruptions, AgentWorkService agentWorks, TacitAgentTools tacitTools) {
        this.tacitTools = tacitTools;
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
        this.dataTransforms = dataTransforms;
        this.workspaceTools = workspaceTools;
        this.planner = planner;
        this.maxDynamicActions = Math.max(12, maxDynamicActions);
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.interruptions = interruptions;
        this.agentWorks = agentWorks;
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
        var actor = ActorContext.current();
        var task = (Runnable) () -> ActorContext.runAs(actor, () -> execute(runId));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(task);
                }
            });
            return;
        }
        taskExecutor.execute(task);
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
                            observation, completedActions, runtime.stage()));
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

    private static boolean isCliRuntime(Object mode) {
        return "opencode".equals(mode) || "codex-cli".equals(mode);
    }

    public boolean continuousAgentAvailable() {
        var now = System.nanoTime();
        if (now < continuousAgentHealthExpiresAt) return continuousAgentHealthy;
        synchronized (this) {
            if (now < continuousAgentHealthExpiresAt) return continuousAgentHealthy;
            try {
                var health = worker.health();
                continuousRuntimeMode = Objects.toString(health.get("agentRuntimeMode"), "deep-agents");
                continuousAgentHealthy = "online".equals(health.get("status"))
                        && Set.of("deep-agents", "opencode", "codex-cli").contains(Objects.toString(health.get("agentRuntimeMode"), ""))
                        && Boolean.TRUE.equals(health.get("continuousAgent"));
                continuousAgentHealthExpiresAt = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            } catch (RuntimeException exception) {
                // Keep the last known mode briefly so one transient health probe does not switch runtimes mid-session.
                continuousAgentHealthExpiresAt = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            }
            return continuousAgentHealthy;
        }
    }

    @SuppressWarnings("unchecked")
    private void executeContinuous(RunResponse run, PlanRuntime runtime, Map<String, Object> effects) {
        var row = jdbc.sql("select gateway_token, approval_count from assistant_run where id = :id")
                .param("id", run.id()).query((rs, index) -> Map.<String, Object>of(
                        "token", rs.getString("gateway_token"),
                        "approvalCount", rs.getInt("approval_count"))).single();
        var approvalCount = ((Number) row.get("approvalCount")).intValue();
        var resume = approvalCount > 0;
        if (resume && isCliRuntime(continuousRuntimeMode)) {
            // A human approved these exact arguments. Execute them once before asking the CLI to continue.
            for (var approved : loadSteps(run.planId()).stream()
                    .filter(step -> "PENDING".equals(step.status()) && step.requiresConfirmation()).toList()) {
                var result = executeAndObserve(run, approved, new LinkedHashMap<>(readEffects(run.id())));
                if (!Boolean.TRUE.equals(result.get("success")))
                    throw new IllegalStateException("已确认操作执行失败：" + result.get("error"));
            }
            effects.putAll(readEffects(run.id()));
            jdbc.sql("update assistant_run set approval_count = 0 where id = :id").param("id", run.id()).update();
        }
        var context = withRecentMessages(workspaceContext(runtime.projectId()), run.sessionId());
        effects.put("agentRuntime", continuousRuntimeMode);
        jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                .param("effects", writeJson(effects)).param("id", run.id()).update();
        var request = planner.continuousRequest(runtime.goal(), runtime.page(), context, run.sessionId(),
                runtime.executionMode(), run.id(), gatewayBaseUrl, String.valueOf(row.get("token")),
                resume, approvalCount, runtime.stage());
        if (Set.of(TacitAgentTask.ORGANIZE, TacitAgentTask.CQ, TacitAgentTask.ALIGN).contains(runtime.stage()))
            request.put("max_tool_calls", maxDynamicActions);
        if (isCliRuntime(continuousRuntimeMode)) {
            request.put("capabilities", AssistantCapabilityRegistry.catalog().stream()
                    .filter(cap -> runtime.stage().permits(cap.get("id").toString()))
                    .filter(item -> !item.get("id").toString().startsWith("browser.")
                            || Set.of("browser.delegate", "browser.task_status").contains(item.get("id")))
                    .toList());
            request.put("max_tool_calls", maxDynamicActions);
        }
        request.put("observation", Map.of("previousEffects", readEffects(run.id()),
                "browserTasks", browserAgent == null ? List.of() : browserAgent.conversationTasks(run.sessionId()),
                "browserTaskGuidance", "browserTasks 是当前对话已有任务。继续、核对或固化时先用 browser.task_status 查询原 task_id；项目资源为空不代表浏览器未执行。不得为固化而重发或重新委派。执行成功与固化成功分别判断，NEEDS_REVIEW 应报告具体绑定问题，不得声称数据已变化。",
                "work", runtime.projectId() == null ? List.of() : agentWorks.list(runtime.projectId()).stream()
                        .filter(work -> work.sessionId().equals(run.sessionId())).toList(),
                "approvedActions", loadSteps(run.planId()).stream().filter(step -> "PENDING".equals(step.status()) && !"agent.execute".equals(step.tool()))
                        .map(step -> Map.of("tool", step.tool(), "arguments", step.arguments())).toList()));
        jdbc.sql("update assistant_plan_step set status = 'RUNNING' where plan_id = :id and tool_name = 'agent.execute' and status = 'PENDING'")
                .param("id", run.planId()).update();
        var result = interruptions.token("run", run.id()).await(() -> worker.runContinuousAgentStreaming(request, event -> {
            var type = Objects.toString(event.get("type"), "");
            if (Set.of("tool_call", "completed", "heartbeat").contains(type)) return;
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
                    if (capability == null || "READ".equals(capability.mode()) || !runtime.stage().permits(tool)) continue;
                    var arguments = new LinkedHashMap<String, Object>();
                    if (raw.get("arguments") instanceof Map<?, ?> values) {
                        values.forEach((key, item) -> arguments.put(String.valueOf(key), item));
                    }
                    var description = "browser.delegate".equals(tool)
                            ? browserAuthorizations.domainApprovalDescription(arguments)
                            : "resource.delete_batch".equals(tool)
                            ? workspaceTools.deletionDescription(arguments) : "browser.commit".equals(tool)
                            ? BrowserAuthorizationService.approvalDescription(browserSession("preview", arguments)) : "browser.open".equals(tool)
                            ? browserAuthorizations.scope(tool, arguments).label()
                              + (Boolean.FALSE.equals(arguments.get("human_login")) ? "。本次由Agent浏览公开页面。" : "。本次先交给你操作，再打开网站；登录后由你交还Agent。")
                              + "网站可正常加载数据和维持连接；填写可能触发自动保存。发送、发布、删除等操作另行确认。不允许访问未授权的本机私有服务。记住授权有效期为30天。" : capability.description();
                    var proposed = new PlanStep(UUID.randomUUID().toString(), 0, capability.id(), capability.mode(),
                            "browser.delegate".equals(tool) ? "授权网站访问" : capability.title(), description, arguments, capability.risk(), true, "PENDING");
                    staged.add(appendDynamicStep(run.planId(), proposed, "APPROVAL",
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
        var actor = jdbc.sql("""
                select s.default_user_id from assistant_run r
                join assistant_session s on s.id = r.session_id
                where r.id = :id
                """).param("id", runId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("助手任务不存在"));
        try (var ignored = ActorContext.bind(actor)) {
            return callContinuousToolAsActor(runId, gatewayToken, request);
        }
    }

    public Map<String, Object> workflowRunStatus(String runId, String workflowRunId, String gatewayToken) {
        var actor = jdbc.sql("""
                select s.default_user_id from assistant_run r
                join assistant_session s on s.id = r.session_id
                where r.id = :id
                """).param("id", runId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("助手任务不存在"));
        try (var ignored = ActorContext.bind(actor)) {
            var stored = jdbc.sql("select gateway_token from assistant_run where id = :id")
                    .param("id", runId).query(String.class).single();
            if (stored.isBlank() || !stored.equals(gatewayToken)) throw new SecurityException("Agent 工具网关凭证无效");
            if (!"RUNNING".equals(get(runId).status())) throw new IllegalStateException("Agent 任务当前不允许读取工作流状态");
            var effects = new LinkedHashMap<>(readEffects(runId));
            if (!workflowRunId.equals(Objects.toString(effects.get("workflowRunId"), ""))) {
                throw new SecurityException("工作流运行不属于当前 Agent 任务");
            }
            var workflowRun = workflowRuns.get(workflowRunId);
            var completedNodes = workflowRun.nodes().stream()
                    .filter(node -> Set.of("SUCCEEDED", "FAILED", "CANCELED", "REUSED").contains(node.status())).count();
            var totalNodes = Math.max(1, workflowRun.nodes().size());
            var progress = Math.min(99, 10 + (int) (completedNodes * 85 / totalNodes));
            var currentNode = workflowRun.nodes().stream()
                    .filter(node -> Objects.equals(node.nodeId(), workflowRun.currentNodeId())).findFirst()
                    .map(node -> node.nodeName()).orElse("正在准备下一步");
            var status = workflowRun.status();
            if (Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(status)) {
                return Map.of("success", true, "terminal", false, "status", status,
                        "progress", progress, "completedNodes", completedNodes, "totalNodes", totalNodes,
                        "message", currentNode);
            }
            effects.put("workflowRunStatus", status);
            effects.put("workflowRunOutput", Map.of("status", status, "nodeCount", workflowRun.nodes().size(),
                    "completedNodeCount", completedNodes));
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "REJECTED".equals(status)) {
                jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                        .param("effects", writeJson(effects)).param("id", runId).update();
                var error = Objects.toString(workflowRun.errorMessage(), "工作流未完成：" + status);
                return Map.of("success", false, "terminal", true, "status", status, "progress", 100,
                        "error", error, "failureType", "WORKFLOW_FAILED", "retryable", false,
                        "recoveryHint", "检查失败节点和输入资料后再运行工作流");
            }
            if ("WAITING_REVIEW".equals(status)) {
                jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                        .param("effects", writeJson(effects)).param("id", runId).update();
                return Map.of("success", false, "terminal", true, "status", status, "progress", progress,
                        "error", "工作流停在人工复核节点；Studio 不会把等待复核当作任务完成",
                        "failureType", "WAITING_REVIEW", "retryable", false,
                        "recoveryHint", "移除人工复核节点后重新运行，或在工作流页面手动处理该节点",
                        "taskComplete", false);
            }
            collectWorkflowDeliverables(workflowRun, effects);
            persistCompactCompletedEffects(runId, effects);
            var arguments = effects.get("workflowRunArguments") instanceof Map<?, ?> raw
                    ? raw.entrySet().stream().collect(LinkedHashMap<String, Object>::new,
                    (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), Map::putAll)
                    : new LinkedHashMap<String, Object>();
            var step = new PlanStep("workflow-result", 0, "workflow.run", "WRITE", "运行工作流", "运行工作流",
                    Map.copyOf(arguments), RiskLevel.CREATE_VERSION, false, "SUCCEEDED");
            var completion = deterministicWorkflowRunCompletion(step, loadRuntime(get(runId).planId()).goal(), effects);
            if (completion == null) completion = "工作流已经执行完成。";
            return Map.of("success", true, "terminal", true, "status", status, "progress", 100,
                    "result", completion, "output", observationOutput(Map.of(), effects),
                    "taskComplete", true, "completionSummary", completion);
        }
    }

    private Map<String, Object> callContinuousToolAsActor(String runId, String gatewayToken, Map<String, Object> request) {
        var run = get(runId);
        var stored = jdbc.sql("select gateway_token from assistant_run where id = :id")
                .param("id", runId).query(String.class).single();
        if (stored.isBlank() || !stored.equals(gatewayToken)) throw new SecurityException("Agent 工具网关凭证无效");
        if (!"RUNNING".equals(run.status())) throw new IllegalStateException("Agent 任务当前不允许调用工具");
        var tool = resolveToolName(Objects.toString(request.get("tool"), ""));
        var capability = AssistantCapabilityRegistry.find(tool)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在：" + tool));
        var runtime = loadRuntime(run.planId());
        if (!runtime.stage().permits(tool))
            return Map.of("success", false, "failureType", "TASK_SCOPE", "retryable", false,
                    "error", "本轮用户指令未选择该执行阶段，原文和历史对话不能扩大权限",
                    "recoveryHint", "可读取台账并回答；执行其他阶段需要用户明确指定");
        var arguments = new LinkedHashMap<String, Object>();
        if (request.containsKey("arguments") && !(request.get("arguments") instanceof Map<?, ?>))
            throw new IllegalArgumentException("工具arguments必须为JSON对象，不能是字符串、数组或null");
        if (request.get("arguments") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        }
        if ("work.update".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            var stages = agentWorks.update(runId, arguments);
            events.publish(run.sessionId(), runId, "agent.plan_updated", Map.of("message", "业务工序已更新", "stages", stages));
            return Map.of("success", true, "result", "业务工序已保存", "output", Map.of("stages", stages));
        }
        if ("work.publish_implementation".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            var definition = fixedWorks.publish(runId, arguments);
            var savedEffects = new LinkedHashMap<>(readEffects(runId));
            savedEffects.put("fixedImplementationId", definition.id());
            jdbc.sql("update assistant_run set effects_json=:effects where id=:id")
                    .param("effects", writeJson(savedEffects)).param("id", runId).update();
            events.publish(run.sessionId(), runId, "work.implementation_published", Map.of("version", definition.version(), "hash", definition.hash()));
            return Map.of("success", true, "output", definition, "result", "固定实现已保存；首次固定执行成功后才标记为验证通过");
        }
        if ("work.implementation_status".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            return Map.of("success", true, "output", fixedWorks.state(fixedWorks.projectForRun(runId), run.sessionId()));
        }
        if ("browser.task_status".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            return Map.of("success", true, "output", browserAgent.status(arguments.get("task_id").toString()));
        }
        if ("browser.inspect".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            return Map.of("success", true, "output", worker.browser("inspect", arguments));
        }
        if (Set.of("browser.observe", "browser.act", "browser.close").contains(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            var output = browserSession(tool.substring("browser.".length()), arguments);
            events.publish(run.sessionId(), runId, "agent.observation", Map.of("toolName", tool,
                    "message", capability.title(), "status", "completed", "output", output));
            return Map.of("success", true, "output", output);
        }
        if ("browser.sites".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            return Map.of("success", true, "output", worker.browser("sites", Map.of()));
        }
        if ("browser.explore".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            return Map.of("success", true, "output", worker.browser("explore", arguments));
        }
        if ("work.execute_implementation".equals(tool)) {
            AssistantToolContracts.validate(tool, arguments);
            var execution = fixedWorks.startForAgent(runId);
            var savedEffects = new LinkedHashMap<>(readEffects(runId));
            savedEffects.put("fixedImplementationId", execution.definitionId());
            savedEffects.put("fixedRunId", execution.id());
            jdbc.sql("update assistant_run set effects_json=:effects where id=:id")
                    .param("effects", writeJson(savedEffects)).param("id", runId).update();
            events.publish(run.sessionId(), runId, "work.execution_started", Map.of("fixedRunId", execution.id()));
            return Map.of("success", true, "output", execution, "result", "已启动固定执行，请检查状态并登记实际产物后再报告完成");
        }
        if ("resource.delete_batch".equals(tool)) {
            var done = loadSteps(run.planId()).stream().anyMatch(step -> "SUCCEEDED".equals(step.status())
                    && tool.equals(step.tool()) && step.arguments().equals(arguments));
            if (done) return Map.of("success", true, "result", "该冻结清单已执行成功，不需要再次审批或执行");
            try {
                workspaceTools.deletionDescription(arguments);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                return Map.of("success", false, "failureType", "CONFLICT", "retryable", false,
                        "error", invalid.getMessage(), "recoveryHint", "调用 resource.prepare_delete 重新读取清单；不要重复原样提交");
            }
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
            var existingEffects = readEffects(runId);
            if ("browser.delegate".equals(tool) && !browserAuthorizations.canReuse(tool, arguments)) {
                return Map.of("success", false, "error", browserAuthorizations.domainApprovalDescription(arguments),
                        "failureType", "AUTHORIZATION", "retryable", false,
                        "recoveryHint", "等待用户授权网站访问；自动执行模式不能跳过网站安全授权");
            }
            var completedRun = completedWorkflowRunObservation(tool, arguments, runtime.goal(), existingEffects);
            if (completedRun != null) return completedRun;
            PlanStep step;
            synchronized (lock) {
                step = loadSteps(run.planId()).stream()
                        .filter(item -> "PENDING".equals(item.status()) && item.tool().equals(tool) && item.arguments().equals(arguments))
                        .findFirst().orElse(null);
                if (step == null) {
                    if (!"browser.delegate".equals(tool) && !"READ".equals(capability.mode()) && (!"AUTO".equalsIgnoreCase(runtime.executionMode())
                            || capability.risk() == RiskLevel.DESTRUCTIVE_OR_EXTERNAL)
                            && !browserAuthorizations.canReuse(tool, arguments)) {
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
                var completion = "workflow.run".equals(tool)
                        ? deterministicWorkflowRunCompletion(step, goal, currentEffects)
                        : deterministicWorkflowCompletion(tool, goal, currentEffects);
                if (completion == null) completion = deterministicSimpleCompletion(tool, goal, currentEffects);
                if (completion != null) {
                    observation = new LinkedHashMap<>(observation);
                    observation.put("taskComplete", true);
                    observation.put("completionSummary", completion);
                    persistCompactCompletedEffects(runId, currentEffects);
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
                        order by created_at desc limit 12
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
        step = withWorkspaceContext(step, runtime, effects);
        if (!isCliRuntime(effects.get("agentRuntime"))) step = applyDeliverablePolicy(step, runtime.goal());
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
        effects.remove("tacit");
        var effectsBefore = new LinkedHashMap<String, Object>(effects);
        var observation = new LinkedHashMap<String, Object>();
        observation.put("tool", step.tool());
        observation.put("arguments", step.arguments());
        try {
            var stage = runtime.stage();
            if (!stage.permits(step.tool())) throw new IllegalArgumentException("当前消息未授权这项知识整理操作");
            String result;
            if (TacitAgentTools.TOOLS.contains(step.tool())) {
                var output = tacitTools.execute(step, runtime.projectId(), stage);
                effects.put("tacit", output);
                if (output.get("artifact_receipt") instanceof Map<?,?> receipt) effects.put("tacitArtifactReceipt", receipt);
                result = step.title() + "，台账版本 " + output.get("revision");
            } else {
                result = executeStep(step, effects);
            }
            verifyStepOutcome(step, effectsBefore, effects);
            syncSessionProject(run.sessionId(), activeProjectId(runtime, step, effects));
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
            var publicObservation = new LinkedHashMap<String, Object>();
            publicObservation.put("status", "completed"); publicObservation.put("step", step.order());
            publicObservation.put("toolName", step.tool()); publicObservation.put("resultSummary", result);
            publicObservation.put("message", result); publicObservation.put("progress", progress);
            publicObservation.put("output", observationOutput(effectsBefore, effects));
            publicObservation.put("provenance", provenance(step, effects));
            events.publish(run.sessionId(), run.id(), "agent.observation", publicObservation);
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
            var publicFailure = new LinkedHashMap<String, Object>();
            publicFailure.put("status", "failed"); publicFailure.put("step", step.order());
            publicFailure.put("toolName", step.tool()); publicFailure.put("resultSummary", error);
            publicFailure.put("message", "工具没有完成，Agent 正在调整方案");
            publicFailure.put("error", error); publicFailure.put("progress", progress);
            publicFailure.put("failureType", failure.category().name());
            publicFailure.put("retryable", failure.retryable()); publicFailure.put("recoveryHint", failure.recoveryHint());
            publicFailure.put("provenance", provenance(step, effects));
            events.publish(run.sessionId(), run.id(), "agent.observation", publicFailure);
        }
        jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                .param("effects", writeJson(effects)).param("id", run.id()).update();
        return observation;
    }

    @SuppressWarnings("unchecked")
    private PlanStep withWorkspaceContext(PlanStep step, PlanRuntime runtime, Map<String, Object> effects) {
        var capability = AssistantCapabilityRegistry.find(step.tool()).orElse(null);
        if (capability == null) return step;
        var properties = capability.inputSchema().get("properties") instanceof Map<?, ?> values
                ? (Map<String, Object>) values : Map.<String, Object>of();
        var arguments = new LinkedHashMap<>(step.arguments());
        Set.of("page", "goal", "project_id").stream()
                .filter(key -> !properties.containsKey(key)).forEach(arguments::remove);
        var activeProjectId = Objects.toString(effects.get("currentProjectId"),
                Objects.toString(effects.get("createdProjectId"), runtime.projectId()));
        if (properties.containsKey("project_id") && !arguments.containsKey("project_id")
                && !activeProjectId.isBlank()) {
            arguments.put("project_id", activeProjectId);
        }
        if (arguments.equals(step.arguments())) return step;
        return new PlanStep(step.id(), step.order(), step.tool(), step.mode(), step.title(), step.description(),
                Map.copyOf(arguments), step.risk(), step.requiresConfirmation(), step.status());
    }

    private PlanStep appendDynamicStep(String planId, PlanStep proposed, String executionMode, String summary) {
        var current = loadSteps(planId);
        var order = current.stream().mapToInt(PlanStep::order).max().orElse(0) + 1;
        var requiresConfirmation = !"AUTO".equalsIgnoreCase(executionMode)
                && (proposed.requiresConfirmation() || proposed.risk().requiresConfirmation());
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

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.transaction.annotation.Transactional
    public void repairWaitingDomainApprovals() {
        // Repair only the inconsistent waiting records created before explicit consent was preserved.
        var plans = jdbc.sql("""
                select distinct s.plan_id from assistant_plan_step s join assistant_run r on r.plan_id=s.plan_id
                where r.status='WAITING_CONFIRMATION' and s.status='PENDING'
                  and s.tool_name='browser.delegate' and s.title='授权网站访问' and s.requires_confirmation=false
                """).query(String.class).list();
        for (var planId : plans) {
            jdbc.sql("""
                    update assistant_plan_step set requires_confirmation=true where plan_id=:id
                    and status='PENDING' and tool_name='browser.delegate' and title='授权网站访问'
                    """).param("id", planId).update();
            var version = jdbc.sql("select version from assistant_plan where id=:id").param("id", planId).query(Integer.class).single() + 1;
            var hash = HashSupport.sha256(writeJson(Map.of("planId", planId, "version", version, "steps", loadSteps(planId))));
            jdbc.sql("""
                    update assistant_plan set status='WAITING_CONFIRMATION',version=:version,plan_hash=:hash,expires_at=:expires
                    where id=:id and status <> 'CANCELED'
                    """).param("id", planId).param("version", version).param("hash", hash)
                    .param("expires", Instant.now().plusSeconds(1800)).update();
        }
    }

    static java.util.Optional<AgentWorkService.Stage> completionBlocker(List<AgentWorkService.Stage> stages) {
        if (stages.stream().anyMatch(s -> "WAITING_INPUT".equals(s.status()))) return java.util.Optional.empty();
        return stages.stream().filter(s -> Set.of("FAILED", "STALE").contains(s.status())).findFirst();
    }

    private void finishRun(RunResponse run, String summary, Map<String, Object> effects) {
        boolean delegated = effects.get("browserAgentTask") instanceof Map<?, ?> task
                && task.get("id") instanceof String;
        if (effects.get("fixedRunId") != null) {
            var state = fixedWorks.state(fixedWorks.projectForRun(run.id()), run.sessionId());
            var execution = state.runs().stream().filter(r -> r.id().equals(effects.get("fixedRunId"))).findFirst().orElseThrow();
            if (!"SUCCEEDED".equals(execution.status()))
                throw new IllegalStateException("固定工序尚未执行成功：" + execution.status() + " " + execution.error());
        }
        var planSteps = loadSteps(run.planId());
        var runtime = loadRuntime(run.planId());
        var goal = runtime.goal();
        var knowledgeTask = runtime.stage();
        if (!delegated) verifyTaskCompletion(planSteps, effects, goal, knowledgeTask);
        var safeSummary = summary == null || summary.isBlank() ? "已完成工作台操作。" : summary;
        if (delegated) {
            // The coordinator turn is finished, not the external task. Its A2A card
            // owns the outcome; do not manufacture completed business stages.
            safeSummary = "正在处理网页操作，进展与需要你确认的事项会显示在当前对话中。";
            effects.put("workStatus", "DELEGATED");
            jdbc.sql("update assistant_run set effects_json=:effects where id=:id")
                    .param("effects", writeJson(effects)).param("id", run.id()).update();
        } else if (knowledgeTask != TacitAgentTask.OTHER) {
            safeSummary = knowledgeTask == TacitAgentTask.DISCUSS ? TacitAgentTools.discussionOutcome(safeSummary)
                    : tacitTools.verifiedOutcome(knowledgeTask, runtime.projectId(), planSteps, effects.get("tacitArtifactReceipt"));
        } else if (isCliRuntime(effects.get("agentRuntime"))) {
            var stages = agentWorks.stages(run.id());
            var waiting = stages.stream().filter(s -> "WAITING_INPUT".equals(s.status())).findFirst();
            var blocked = completionBlocker(stages);
            if (blocked.isPresent()) {
                var stage = blocked.get();
                var reason = "STALE".equals(stage.status()) ? "工序结果待重新核验：" + (stage.summary() == null ? "请检查引用版本或原执行记录" : stage.summary())
                        : stage.summary() == null || stage.summary().isBlank() ? "执行失败，请查看技术详情" : stage.summary();
                throw new IllegalStateException("工序“" + stage.title() + "”未完成：" + reason);
            }
            if (waiting.isPresent()) {
                safeSummary = "需要你的输入：" + waiting.get().question();
                effects.put("workStatus", "WAITING_INPUT");
            } else if (stages.stream().anyMatch(s -> !"SUCCEEDED".equals(s.status()))) {
                throw new IllegalStateException("业务工序尚未完成，请查看已保存的中间产物");
            } else if (stages.isEmpty() && loadSteps(run.planId()).stream().anyMatch(s -> !"READ".equals(s.mode()))) {
                throw new IllegalStateException("Agent 未登记业务工序及产物，不能报告完成");
            }
            if (waiting.isEmpty() && isReadOnlyReview(loadSteps(run.planId()), effects)) {
                effects.put("workStatus", "REVIEW_ONLY");
                safeSummary = "本轮仅核对已有资料，未执行新的操作。\n\n" + safeSummary;
            } else if (waiting.isEmpty() && stages.stream().anyMatch(s -> !s.outputs().isEmpty())) {
                fixedWorks.requirePublished(run.id());
            }
            jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                    .param("effects", writeJson(effects)).param("id", run.id()).update();
        }
        var changed = jdbc.sql("update assistant_run set status = 'SUCCEEDED', result_summary = :summary, finished_at = :now where id = :id and status = 'RUNNING'")
                .param("summary", safeSummary).param("now", Instant.now()).param("id", run.id()).update();
        if (changed == 0) return;
        jdbc.sql("update assistant_plan set status = 'COMPLETED', summary = :summary where id = :id")
                .param("summary", safeSummary).param("id", run.planId()).update();
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
        return Objects.toString(effects.get("currentProjectId"),
                Objects.toString(effects.get("createdProjectId"), runtime.projectId()));
    }

    private void syncSessionProject(String sessionId, String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        projects.get(projectId);
        jdbc.sql("""
                update assistant_session set project_id = :projectId, updated_at = :now
                where id = :sessionId and project_id <> :projectId
                """).param("projectId", projectId).param("now", Instant.now())
                .param("sessionId", sessionId).update();
    }

    Map<String, Object> observationOutput(Map<String, Object> before, Map<String, Object> after) {
        var output = new LinkedHashMap<String, Object>();
        after.forEach((key, value) -> {
            if (!"uiAction".equals(key) && (Set.of("workflow", "changed", "tacit").contains(key) || !Objects.equals(before.get(key), value))) {
                // Approval scopes must never be truncated into an incomplete list of IDs/versions.
                output.put(key, Set.of("deletionPreview", "tacit").contains(key) ? value : compactObservationValue(value, 0));
            }
        });
        return output;
    }

    private Object compactObservationValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) {
            return text.length() <= 1200 ? text : text.substring(0, 1200) + "…（已省略，完整内容保留在工作区）";
        }
        if (depth >= 4) return "已保留完整详情";
        if (value instanceof Map<?, ?> raw) {
            var result = new LinkedHashMap<String, Object>();
            int count = 0;
            for (var entry : raw.entrySet()) {
                var key = Objects.toString(entry.getKey(), "");
                if (Set.of("text", "excerpt", "content", "sourceText", "source_text").contains(key)) {
                    var text = Objects.toString(entry.getValue(), "");
                    result.put(key + "Summary", compactObservationValue(text, depth + 1));
                } else {
                    result.put(key, compactObservationValue(entry.getValue(), depth + 1));
                }
                if (++count >= 30) {
                    result.put("truncated", true);
                    break;
                }
            }
            return result;
        }
        if (value instanceof Collection<?> values) {
            var result = new ArrayList<>();
            values.stream().limit(12).forEach(item -> result.add(compactObservationValue(item, depth + 1)));
            if (values.size() > 12) result.add(Map.of("truncated", true, "total", values.size()));
            return result;
        }
        try {
            return compactObservationValue(objectMapper.convertValue(value, Object.class), depth + 1);
        } catch (RuntimeException ignored) {
            return compactObservationValue(Objects.toString(value, ""), depth + 1);
        }
    }

    private void persistCompactCompletedEffects(String runId, Map<String, Object> effects) {
        var compact = new LinkedHashMap<String, Object>();
        effects.forEach((key, value) -> compact.put(key, compactObservationValue(value, 0)));
        jdbc.sql("update assistant_run set effects_json = :effects where id = :id")
                .param("effects", writeJson(compact)).param("id", runId).update();
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
            verifyRequestedWorkflowFormats(step, effects);
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

    @SuppressWarnings("unchecked")
    private void verifyRequestedWorkflowFormats(PlanStep step, Map<String, Object> effects) {
        var required = new LinkedHashSet<>(requestedFormats(step));
        if (required.isEmpty()) return;
        var actual = effects.get("deliverables") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item)
                .map(item -> normalizeRequestedFormat(Objects.toString(item.get("format"), "")))
                .collect(java.util.stream.Collectors.toSet())
                : Set.<String>of();
        var missing = required.stream().filter(format -> !actual.contains(format)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("结果验证失败：工作流未生成要求的成果格式 " + String.join("、", missing));
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

    static boolean isReadOnlyReview(List<PlanStep> steps, Map<String, Object> effects) {
        if (effects.containsKey("fixedImplementationId") || effects.containsKey("fixedRunId")
                || effects.containsKey("browserAgentTask")) return false;
        var actual = steps.stream().filter(step -> !"agent.execute".equals(step.tool())).toList();
        return !actual.isEmpty() && actual.stream().allMatch(step -> "READ".equals(step.mode())
                && "SUCCEEDED".equals(step.status()));
    }

    private void verifyTaskCompletion(List<PlanStep> steps, Map<String, Object> effects, String goal, TacitAgentTask stage) {
        if (steps.stream().anyMatch(step -> Set.of("PENDING", "RUNNING").contains(step.status())))
            throw new IllegalStateException("任务尚未完成：仍有工具操作未结束");
        var lastRealStep = steps.stream().filter(step -> !"agent.execute".equals(step.tool())).reduce((left, right) -> right);
        if (lastRealStep.isPresent() && "FAILED".equals(lastRealStep.get().status()))
            throw new IllegalStateException("任务尚未完成：最后一个工具操作失败，且没有经过替代方式恢复");
        if (steps.stream().anyMatch(step -> "deliverable.create".equals(step.tool())))
            verifyNewDeliverables(Map.of(), effects, true);
        if (steps.stream().anyMatch(step -> "workflow.run".equals(step.tool()))
                && !"SUCCEEDED".equals(Objects.toString(effects.get("workflowRunStatus"), ""))) {
            var status = Objects.toString(effects.get("workflowRunStatus"), "UNKNOWN");
            throw new IllegalStateException("任务尚未完成：工作流状态为 " + status + "，不能报告已完成");
        }
        if (stage == TacitAgentTask.OTHER && !isCliRuntime(effects.get("agentRuntime")) && requestsWorkflowRun(goal)
                && steps.stream().noneMatch(step -> "workflow.run".equals(step.tool()) && "SUCCEEDED".equals(step.status()))) {
            throw new IllegalStateException("任务尚未完成：用户要求产出成果，但工作流尚未真正运行");
        }
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
    String deterministicWorkflowRunCompletion(PlanStep step, String goal, Map<String, Object> effects) {
        if (!"SUCCEEDED".equals(Objects.toString(effects.get("workflowRunStatus"), ""))) return null;
        var required = new LinkedHashSet<>(requestedFormats(step));
        if (required.isEmpty()) required.addAll(requestedFormats(goal));
        var outputs = effects.get("deliverables") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        var actual = outputs.stream().map(item -> normalizeRequestedFormat(Objects.toString(item.get("format"), "")))
                .collect(java.util.stream.Collectors.toSet());
        if (!actual.containsAll(required)) return null;
        var workflowId = Objects.toString(effects.get("workflowId"), "");
        var workflowName = workflowId.isBlank() ? "工作流" : workflows.get(workflowId).name();
        if (outputs.isEmpty()) return "已运行并验证工作流“" + workflowName + "”。";
        var names = outputs.stream().map(item -> Objects.toString(item.get("name"), "输出件")).distinct().toList();
        return "已运行并验证工作流“" + workflowName + "”，生成"
                + String.join("、", names) + "。";
    }

    Map<String, Object> completedWorkflowRunObservation(String tool, Map<String, Object> arguments,
                                                         String goal, Map<String, Object> effects) {
        if (!"workflow.run".equals(tool)) return null;
        var requestedWorkflowId = Objects.toString(arguments.get("workflow_id"), "");
        var completedWorkflowId = Objects.toString(effects.get("workflowId"), "");
        if (!requestedWorkflowId.isBlank() && !requestedWorkflowId.contains("${")
                && !requestedWorkflowId.equals(completedWorkflowId)) return null;
        var step = new PlanStep("completed-workflow-run", 0, tool, "WRITE", "运行工作流", "运行工作流",
                Map.copyOf(arguments), RiskLevel.CREATE_VERSION, false, "SUCCEEDED");
        var completion = deterministicWorkflowRunCompletion(step, goal, effects);
        if (completion == null) return null;
        return Map.of("success", true,
                "result", "相同工作流已经成功完成，已跳过重复运行",
                "output", observationOutput(Map.of(), effects),
                "taskComplete", true,
                "completionSummary", completion);
    }

    @SuppressWarnings("unchecked")
    String deterministicWorkflowCompletion(String tool, String goal, Map<String, Object> effects) {
        if (!Set.of("workflow.edit", "workflow.duplicate", "workflow.prepare", "workflow.initialize", "workflow.initialize_analysis").contains(tool))
            return null;
        var normalizedGoal = Objects.toString(goal, "").toLowerCase(Locale.ROOT);
        if (!containsAny(normalizedGoal, "工作流", "流程", "workflow")) return null;
        if (requestsWorkflowRun(normalizedGoal)) return null;
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

    private String deterministicSimpleCompletion(String tool, String goal, Map<String, Object> effects) {
        var text = Objects.toString(goal, "").toLowerCase(Locale.ROOT);
        return switch (tool) {
            case "project.open" -> containsAny(text, "打开项目", "切换到", "进入项目")
                    && !containsAny(text, "分析", "调研", "研究", "工作流", "报告", "ppt", "html")
                    ? "已打开项目“" + Objects.toString(effects.get("currentProjectName"), "目标项目") + "”。" : null;
            case "project.create_workspace" -> containsAny(text, "新建项目", "创建项目", "建一个项目")
                    && !containsAny(text, "分析", "调研", "研究", "工作流", "报告", "ppt", "html", "然后", "并且")
                    ? "已创建并打开项目“" + Objects.toString(effects.get("createdProjectName"), "新项目") + "”。" : null;
            case "project.rename" -> containsAny(text, "重命名项目", "项目改名", "项目改成") ? "项目已重命名并刷新工作区。" : null;
            case "project.delete" -> containsAny(text, "删除项目", "删掉项目") ? "项目已删除，工作区已刷新。" : null;
            case "workflow.delete" -> containsAny(text, "删除", "删掉") && containsAny(text, "工作流", "workflow")
                    ? "工作流已删除，工作区已刷新。" : null;
            case "folder.rename" -> containsAny(text, "重命名", "改名", "改成") && containsAny(text, "目录", "文件夹")
                    ? "目录修改已经完成，工作区已刷新。" : null;
            case "folder.delete" -> containsAny(text, "删除", "删掉") && containsAny(text, "目录", "文件夹")
                    ? "目录已删除，工作区已刷新。" : null;
            case "dataset.profile" -> containsAny(text, "概况", "画像", "字段", "数据质量", "profile")
                    ? "已完成数据概况检查，结果可在当前任务记录中查看。" : null;
            case "source.add_verified" -> containsAny(text, "只找资料", "仅找资料", "只搜索资料", "仅搜索资料", "先找资料", "先搜资料")
                    && effects.get("verifiedSources") instanceof List<?> values && !values.isEmpty()
                    ? "已验证并保存 " + values.size() + " 份可访问来源及正文快照，未创建工作流或输出件。" : null;
            case "workspace.inspect" -> workspaceInspectCompletion(text, effects);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private String workspaceInspectCompletion(String goal, Map<String, Object> effects) {
        if (containsAny(goal, "删除", "删掉") && containsAny(goal, "工作流", "流程", "workflow")) {
            var items = effects.get("workspaceItems") instanceof List<?> values ? values : List.of();
            var hasWorkflow = items.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(item -> "WORKFLOW".equals(Objects.toString(item.get("type"), "")));
            if (!hasWorkflow) return "当前项目没有可删除的工作流，未做任何修改。";
        }
        if (!containsAny(goal, "总行数", "多少行", "匹配上的", "没匹配", "未匹配")) return null;
        var results = effects.get("latestDataResults") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        if (results.isEmpty()) return null;
        var latest = results.getFirst();
        var quality = latest.get("qualityReport") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(LinkedHashMap<String, Object>::new,
                (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), Map::putAll)
                : Map.<String, Object>of();
        var rowCount = quality.get("rowCount") instanceof Number number ? number.longValue() : null;
        Map<String, Object> join = null;
        if (latest.get("sampleReport") instanceof Map<?, ?> sampleRaw
                && sampleRaw.get("stepReports") instanceof List<?> steps) {
            join = steps.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item)
                    .map(item -> item.get("join"))
                    .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item)
                    .reduce((left, right) -> right).orElse(null);
        }
        var name = Objects.toString(latest.get("name"), "最新数据加工结果");
        if (join != null && join.get("leftRowCount") instanceof Number left
                && join.get("matchRate") instanceof Number rate) {
            var matched = Math.round(left.longValue() * rate.doubleValue());
            var unmatched = Math.max(0, left.longValue() - matched);
            return "已读取“" + name + "”的真实加工质量结果：总行数 "
                    + (rowCount == null ? left.longValue() : rowCount) + "，匹配 " + matched
                    + "，未匹配 " + unmatched + "。";
        }
        return rowCount == null ? "已找到最新数据加工结果“" + name + "”，质量详情已载入当前任务。"
                : "已读取最新数据加工结果“" + name + "”，总行数 " + rowCount + "；当前质量报告没有单独记录匹配/未匹配数量。";
    }

    private boolean requestsWorkflowRun(String goal) {
        var text = Objects.toString(goal, "").toLowerCase(Locale.ROOT);
        if (text.matches(".*(?:不要|别|先别|暂不|无需|不用).{0,4}(?:运行|执行|重跑|跑一遍).*") ) return false;
        if (containsAny(text, "不运行", "不要运行", "先不要运行", "不要再运行", "别再运行", "不用运行", "无需运行", "暂不运行", "先别运行",
                "不执行", "不要执行", "先别执行", "无需执行", "只创建", "仅创建", "只保存", "仅保存",
                "不要再跑", "别再跑", "不要跑", "先别跑", "不要重跑",
                "do not run", "don't run")) return false;
        if (containsAny(text, "运行", "重跑", "跑一下", "跑一遍", "试运行", "run")) return true;
        if (text.contains("执行") && (containsAny(text, "工作流", "流程", "workflow")
                || text.strip().matches("(?:请|现在|开始|直接|立即){0,3}执行(?:一下|一遍|它)?[。！!]?"))) return true;
        return explicitlyRequestsDeliverable(text);
    }

    private boolean explicitlyRequestsDeliverable(String goal) {
        if (containsAny(goal, "不要生成", "先不要生成", "不要又生成", "别再生成", "不用再生成", "不生成", "不要输出件", "不要生成新文件")) return false;
        var namesFormat = containsAny(goal, "ppt", "powerpoint", "html", "pdf", "word", "docx", "演示文稿", "网页报告", "交互报告");
        var asksForOutput = containsAny(goal, "生成", "制作", "做成", "输出", "产出", "给我", "成果", "汇报", "报告", "总结");
        return namesFormat && asksForOutput;
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
                        select p.goal, p.execution_mode, p.dynamic_agent, p.tacit_task, c.page, c.project_id
                        from assistant_plan p join assistant_context_snapshot c on c.id = p.context_snapshot_id
                        where p.id = :id
                        """).param("id", planId).query((rs, row) -> new PlanRuntime(
                        rs.getString("goal"), rs.getString("page"), rs.getString("project_id"),
                        rs.getString("execution_mode"), rs.getBoolean("dynamic_agent"),
                        TacitAgentTask.stored(rs.getString("tacit_task"), rs.getString("goal")))).single();
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

    private record PlanRuntime(String goal, String page, String projectId, String executionMode, boolean dynamic, TacitAgentTask stage) { }

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
        return jdbc.sql("""
                select r.* from assistant_run r
                join assistant_session s on s.id = r.session_id
                where r.id = :id and s.default_user_id = :actor
                """)
                .param("id", id)
                .param("actor", ActorContext.current())
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
        var effects = readEffects(id);
        var workflowRunId = Objects.toString(effects.get("workflowRunId"), "");
        if (!workflowRunId.isBlank()) {
            try {
                workflowRuns.cancel(workflowRunId);
            } catch (RuntimeException ignored) {
                // The workflow may have reached a terminal state just before cancellation.
            }
        }
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
        var sessionId = jdbc.sql("""
                select p.session_id from assistant_plan p
                join assistant_session s on s.id = p.session_id
                where p.id = :id and s.default_user_id = :actor
                for update
                """).param("id", planId).param("actor", ActorContext.current())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("助手计划不存在"));
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
            case "browser.delegate" -> {
                effects.put("browserAgentTask", browserAuthorizations.execute(step.tool(), step.arguments(), step.id(),
                        () -> browserAgent.start(step.id(), step.arguments())));
                yield "已委派独立浏览器 Agent；请查看任务状态，不能把已启动当作完成";
            }
            case "browser.open" -> {
                var result = browserAuthorizations.execute(step.tool(), step.arguments(), step.id(), () -> {
                    var args = new LinkedHashMap<String, Object>(step.arguments());
                    args.put("url", browserAuthorizations.entryUrl(step.arguments()));
                    args.put("user_authorized", true);
                    args.putIfAbsent("human_login", true);
                    return browserSession("open", args);
                });
                effects.put("browserResult", result);
                yield "网站已连接，浏览器会话已建立";
            }
            case "browser.login", "browser.resume" -> {
                effects.put("browserResult", browserSession(step.tool().substring("browser.".length()), step.arguments()));
                yield "浏览器控制权已更新，请查看当前状态";
            }
            case "browser.commit" -> {
                var result = browserAuthorizations.execute(step.tool(), step.arguments(), step.id(), () -> browserSession("commit", step.arguments()));
                effects.put("browserResult", result);
                yield "已执行批准的浏览器动作；请查看并校验业务结果";
            }
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
        tools.addAll(TacitAgentTools.TOOLS);
        // These tools are dispatched by callContinuousToolAsActor, not executeStep.
        tools.addAll(Set.of("work.update", "work.publish_implementation", "work.implementation_status",
                "work.execute_implementation", "browser.sites", "browser.inspect", "browser.explore"));
        tools.addAll(Set.of("browser.open", "browser.observe", "browser.act", "browser.commit", "browser.close", "browser.login", "browser.resume"));
        tools.addAll(Set.of("browser.delegate", "browser.task_status"));
        tools.addAll(Set.of(
                "workspace.inspect", "workspace.navigate", "assistant.respond", "assistant.analyze_context",
                "project.create_workspace", "knowledge.discover_external_sources", "knowledge.search", "knowledge.add",
                "workflow.initialize", "workflow.prepare", "workflow.add_selected_resource",
                "workflow.add_data_transform", "workflow.add_outputs", "workflow.run", "deliverable.create"));
        return Set.copyOf(tools);
    }

    private Map<String, Object> browserSession(String operation, Map<String, Object> arguments) {
        if (arguments.containsKey("session") && !"close".equals(operation))
            browserAuthorizations.checkSession(arguments.get("session").toString());
        var body = new LinkedHashMap<String, Object>(arguments);
        body.put("owner", ActorContext.current());
        try {
            var result = new LinkedHashMap<String, Object>(worker.browser("session/" + operation, body));
            if (arguments.containsKey("session")) result.put("session", arguments.get("session"));
            if ("open".equals(operation)) browserAuthorizations.registerSession(arguments, Objects.toString(result.get("session"), ""));
            browserAuthorizations.recordOperation(operation, Objects.toString(result.get("session"), ""), true);
            return result;
        } catch (RuntimeException ex) {
            browserAuthorizations.recordOperation(operation, Objects.toString(arguments.get("session"), ""), false);
            throw ex;
        }
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
        var latestDataResults = jdbc.sql("""
                        select n.node_name, n.output_json
                        from workflow_node_run n join workflow_run r on r.id = n.run_id
                        where r.project_id = :projectId and r.status = 'SUCCEEDED'
                          and n.status in ('SUCCEEDED', 'REUSED') and n.node_type = 'DATA_TRANSFORM'
                        order by r.finished_at desc, n.step_order desc limit 3
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> {
                    var value = new LinkedHashMap<String, Object>(readMap(rs.getString("output_json")));
                    value.put("nodeName", rs.getString("node_name"));
                    return value;
                }).list();
        if (!latestDataResults.isEmpty()) effects.put("latestDataResults", latestDataResults);
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
            var detail = Objects.toString(exception.getMessage(), "搜索服务没有返回错误详情").strip();
            throw new IllegalStateException("联网搜索服务当前不可用："
                    + detail.substring(0, Math.min(detail.length(), 500)), exception);
        }
        var sources = research.get("sources") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.<Map<String, Object>>of();
        effects.put("researchSources", sources);
        effects.put("researchMode", Objects.toString(research.get("mode"), ""));
        effects.put("researchSummary", Objects.toString(research.get("summary"), ""));
        effects.put("researchProvider", Objects.toString(research.get("provider"), ""));
        var status = Objects.toString(research.get("status"), sources.isEmpty() ? "empty" : "ready");
        if ("unavailable".equals(status) || "failed".equals(status)) {
            throw new IllegalStateException(("unavailable".equals(status)
                    ? "联网搜索服务当前不可用：" : "联网搜索执行失败：")
                    + Objects.toString(research.get("summary"), "请稍后重试"));
        }
        return sources.isEmpty()
                ? "联网搜索已完成，但没有找到匹配的资料入口"
                : "已找到 " + sources.size() + " 个候选资料入口，下一步将逐项读取核验";
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
        if (step.arguments().get("workflow_spec") instanceof Map<?, ?> rawSpec && !rawSpec.isEmpty()) {
            return preparePlannedWorkflow(projectId, step, effects, nodes, goal, stringObjectMap(rawSpec));
        }
        if (isDataPreparationGoal(goal)) {
            var dataInputs = nodes.stream().filter(this::isStructuredDataInput).toList();
            if (!dataInputs.isEmpty()) {
                var transformId = "transform_" + shortId();
                var config = generatedTransformConfig(projectId, goal, dataInputs);
                var x = dataInputs.stream().mapToDouble(NodeDefinition::x).max().orElse(80) + 340;
                var transform = new NodeDefinition(transformId, NodeType.DATA_TRANSFORM, "数据加工", x, 180, config);
                nodes.add(transform);
                dataInputs.forEach(node -> edges.add(new EdgeDefinition("edge_" + shortId(), node.id(), transformId)));
                var formats = requestedFormats(step);
                addOutputNodes(nodes, edges, formats, transformId, "工作成果", goal);
                var created = workflows.create(projectId, new SaveRequest(workflowName(step), goal,
                        nodes, edges, ExecutionMode.MANUAL, null, null));
                effects.put("workflowId", created.id());
                effects.put("workflow", created);
                effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                        "workflowId", created.id(), "refreshWorkspace", true));
                return "已创建可运行的数据准备工作流“" + created.name() + "”，加工方案、输入和质量规则均可在画布中查看";
            }
        }
        var analysisId = "analysis_" + shortId();
        if (nodes.isEmpty()) {
            var researchId = "refs_" + shortId();
            nodes.add(new NodeDefinition(researchId, NodeType.REF_SEARCH, "查找相关资料", 80, 170,
                    Map.of("query", goal, "limit", 5, "includeWeb", true)));
        }
        nodes.add(new NodeDefinition(analysisId, NodeType.AI_ANALYSIS, "分析与整理", 430, 180,
                Map.of("prompt", goal, "externalResearch", "ON", "transparent", true)));
        nodes.stream().filter(node -> !analysisId.equals(node.id())).forEach(node ->
                edges.add(new EdgeDefinition("edge_" + shortId(), node.id(), analysisId)));
        var formats = requestedFormats(step);
        addOutputNodes(nodes, edges, formats, analysisId, "工作成果", goal);
        var created = workflows.create(projectId, new SaveRequest(workflowName(step), goal,
                nodes, edges, ExecutionMode.MANUAL, null, null));
        if (created.nodes().isEmpty()) throw new IllegalStateException("工作流没有生成可执行步骤，请重新描述目标");
        effects.put("workflowId", created.id());
        effects.put("workflow", created);
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", created.id(), "refreshWorkspace", true));
        return "已创建工作流“" + created.name() + "”，包含 " + created.nodes().size() + " 个可见步骤";
    }

    private String preparePlannedWorkflow(String projectId, PlanStep step, Map<String, Object> effects,
                                          List<NodeDefinition> inputNodes, String goal,
                                          Map<String, Object> spec) {
        var version = Objects.toString(spec.get("version"), "");
        if (!"1.0".equals(version)) throw new IllegalArgumentException("工作流规划协议只支持 version=1.0");
        if (!(spec.get("nodes") instanceof List<?> rawNodes) || rawNodes.isEmpty()) {
            throw new IllegalArgumentException("工作流规划至少需要一个业务节点");
        }
        if (rawNodes.size() > 12) throw new IllegalArgumentException("工作流规划最多包含 12 个业务节点，请合并重复步骤");

        var referencesProjectInputs = rawNodes.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(item -> item.get("depends_on")).filter(List.class::isInstance).map(List.class::cast)
                .anyMatch(values -> values.stream().anyMatch("$inputs"::equals));
        var availableInputs = referencesProjectInputs ? List.copyOf(inputNodes) : List.<NodeDefinition>of();
        var allowedTypes = Set.of(NodeType.REF_SEARCH, NodeType.DATA_TRANSFORM, NodeType.AI_ANALYSIS,
                NodeType.AGENT_TASK, NodeType.REVIEW, NodeType.DELIVERABLE);
        var nodes = new ArrayList<>(availableInputs);
        var edges = new ArrayList<EdgeDefinition>();
        var idByKey = new LinkedHashMap<String, String>();
        var plannedNodes = new ArrayList<Map<String, Object>>();
        var structuredInputs = availableInputs.stream().filter(this::isStructuredDataInput).toList();

        for (var rawNode : rawNodes) {
            if (!(rawNode instanceof Map<?, ?> rawMap)) throw new IllegalArgumentException("工作流节点必须是对象");
            var planned = stringObjectMap(rawMap);
            var key = Objects.toString(planned.get("key"), "").trim();
            if (!key.matches("[a-zA-Z][a-zA-Z0-9_-]{0,49}")) {
                throw new IllegalArgumentException("工作流节点 key 必须以字母开头，仅包含字母、数字、下划线或短横线：" + key);
            }
            if (idByKey.containsKey(key)) throw new IllegalArgumentException("工作流节点 key 重复：" + key);
            NodeType type;
            try { type = NodeType.valueOf(Objects.toString(planned.get("type"), "")); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("不支持的工作流节点类型：" + planned.get("type")); }
            if (!allowedTypes.contains(type)) throw new IllegalArgumentException("动态工作流不允许使用节点类型：" + type);
            var name = Objects.toString(planned.get("name"), "").trim();
            var purpose = Objects.toString(planned.get("purpose"), "").trim();
            if (name.isBlank() || purpose.isBlank()) throw new IllegalArgumentException("节点 " + key + " 缺少业务名称或职责说明");
            var config = planned.get("config") instanceof Map<?, ?> rawConfig
                    ? new LinkedHashMap<>(stringObjectMap(rawConfig)) : new LinkedHashMap<String, Object>();
            completePlannedNodeConfig(projectId, goal, type, purpose, config, structuredInputs);
            var id = "planned_" + key + "_" + shortId();
            idByKey.put(key, id);
            nodes.add(new NodeDefinition(id, type, name, 0, 0, Map.copyOf(config)));
            plannedNodes.add(planned);
        }

        for (var index = 0; index < plannedNodes.size(); index++) {
            var planned = plannedNodes.get(index);
            var key = Objects.toString(planned.get("key"), "");
            var target = idByKey.get(key);
            var type = nodes.stream().filter(node -> node.id().equals(target)).findFirst().orElseThrow().type();
            var dependencies = stringList(planned.get("depends_on"));
            if (dependencies.isEmpty() && requiresPlannedInput(type)) {
                throw new IllegalArgumentException("节点 " + key + " 必须通过 depends_on 声明上游；项目输入请使用 $inputs");
            }
            for (var dependency : dependencies) {
                if ("$inputs".equals(dependency)) {
                    if (availableInputs.isEmpty()) throw new IllegalArgumentException("节点 " + key + " 引用了 $inputs，但当前项目没有可用输入");
                    availableInputs.forEach(input -> edges.add(new EdgeDefinition(
                            "edge_" + shortId(), input.id(), target)));
                    continue;
                }
                var source = idByKey.get(dependency);
                if (source == null) throw new IllegalArgumentException("节点 " + key + " 引用了不存在的上游：" + dependency);
                edges.add(new EdgeDefinition("edge_" + shortId(), source, target));
            }
        }

        var formats = requestedFormats(step);
        var existingFormats = nodes.stream().filter(node -> node.type() == NodeType.DELIVERABLE)
                .map(NodeDefinition::config).map(this::configuredOutputFormat).collect(java.util.stream.Collectors.toSet());
        var missingFormats = formats.stream().filter(format -> !existingFormats.contains(format)).toList();
        if (!missingFormats.isEmpty()) {
            var upstream = nodes.stream().filter(node -> node.type() != NodeType.DELIVERABLE)
                    .reduce((left, right) -> right).map(NodeDefinition::id).orElse(null);
            addOutputNodes(nodes, edges, missingFormats, upstream, "工作成果", goal);
        }
        var laidOut = layoutPlannedWorkflow(nodes, edges);
        var specName = Objects.toString(spec.get("name"), "").trim();
        var name = argument(step, "name", specName);
        if (name.isBlank()) name = workflowName(step);
        var description = Objects.toString(spec.get("description"), "").trim();
        if (description.isBlank()) description = goal;
        var created = workflows.create(projectId, new SaveRequest(name, description,
                laidOut, edges, ExecutionMode.MANUAL, null, null));
        effects.put("workflowId", created.id());
        effects.put("workflow", created);
        effects.put("workflowPlanningMode", "deep-agent-spec");
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", created.id(), "refreshWorkspace", true));
        return "已按任务动态规划并创建工作流“" + created.name() + "”，包含 "
                + created.nodes().size() + " 个可见步骤";
    }

    private void completePlannedNodeConfig(String projectId, String goal, NodeType type, String purpose,
                                           LinkedHashMap<String, Object> config,
                                           List<NodeDefinition> structuredInputs) {
        config.put("purpose", purpose);
        switch (type) {
            case REF_SEARCH -> {
                config.putIfAbsent("query", goal);
                config.putIfAbsent("limit", 5);
                config.putIfAbsent("includeWeb", true);
            }
            case DATA_TRANSFORM -> {
                if (structuredInputs.isEmpty()) throw new IllegalArgumentException("数据加工节点需要项目中的结构化数据输入");
                var generated = new LinkedHashMap<>(generatedTransformConfig(projectId, goal, structuredInputs));
                generated.putAll(config);
                config.clear();
                config.putAll(generated);
                config.putIfAbsent("requirements", goal);
                config.putIfAbsent("outputName", "processed_data.parquet");
            }
            case AI_ANALYSIS -> {
                config.putIfAbsent("prompt", goal);
                config.putIfAbsent("externalResearch", "ON");
                config.putIfAbsent("transparent", true);
            }
            case AGENT_TASK -> config.putIfAbsent("instruction", goal);
            case REVIEW -> config.putIfAbsent("instructions", "检查事实依据、计算口径、遗漏项和结论边界，输出需要修正的问题");
            case DELIVERABLE -> {
                config.putIfAbsent("generationPrompt", goal);
                config.putIfAbsent("format", "PPTX");
                config.putIfAbsent("includeCitations", true);
                config.putIfAbsent("citationStyle", "IEEE");
                if ("PPTX".equalsIgnoreCase(Objects.toString(config.get("format"), ""))) {
                    config.putIfAbsent("pptSkill", "studio-professional-native");
                }
            }
            default -> throw new IllegalArgumentException("不支持的动态节点类型：" + type);
        }
    }

    private boolean requiresPlannedInput(NodeType type) {
        return Set.of(NodeType.DATA_TRANSFORM, NodeType.AI_ANALYSIS, NodeType.REVIEW, NodeType.DELIVERABLE).contains(type);
    }

    private List<NodeDefinition> layoutPlannedWorkflow(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        var indegree = new LinkedHashMap<String, Integer>();
        var outgoing = new LinkedHashMap<String, List<String>>();
        var layer = new LinkedHashMap<String, Integer>();
        nodes.forEach(node -> { indegree.put(node.id(), 0); layer.put(node.id(), 0); });
        for (var edge : edges) {
            if (!indegree.containsKey(edge.source()) || !indegree.containsKey(edge.target())) continue;
            indegree.put(edge.target(), indegree.get(edge.target()) + 1);
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
        }
        var queue = new java.util.ArrayDeque<String>();
        indegree.forEach((id, count) -> { if (count == 0) queue.add(id); });
        var visited = 0;
        while (!queue.isEmpty()) {
            var source = queue.removeFirst();
            visited++;
            for (var target : outgoing.getOrDefault(source, List.of())) {
                layer.put(target, Math.max(layer.get(target), layer.get(source) + 1));
                var remaining = indegree.get(target) - 1;
                indegree.put(target, remaining);
                if (remaining == 0) queue.addLast(target);
            }
        }
        if (visited != nodes.size()) throw new IllegalArgumentException("工作流规划中存在循环依赖");
        var rowByLayer = new LinkedHashMap<Integer, Integer>();
        return nodes.stream().map(node -> {
            var column = layer.get(node.id());
            var row = rowByLayer.merge(column, 1, Integer::sum) - 1;
            return new NodeDefinition(node.id(), node.type(), node.name(), 80 + column * 350.0,
                    90 + row * 160.0, node.config());
        }).toList();
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private boolean isDataPreparationGoal(String goal) {
        var value = Objects.toString(goal, "").toLowerCase(Locale.ROOT);
        return containsAny(value, "数据准备", "数据加工", "扩维", "关联", "合并", "补上", "补录", "补充字段", "清洗", "转换",
                "偏差", "对账", "匹配", "比较", "毛利", "销毛");
    }

    private boolean isStructuredDataInput(NodeDefinition node) {
        if (Set.of(NodeType.DATASET_INPUT, NodeType.DATA_EXTRACT).contains(node.type())) return true;
        if (node.type() != NodeType.FILE_INPUT || node.config() == null) return false;
        var resourceId = Objects.toString(node.config().get("resourceId"), "");
        if (resourceId.isBlank()) return false;
        try {
            var file = knowledge.get(resourceId);
            var name = file.name().toLowerCase(Locale.ROOT);
            var mediaType = Objects.toString(file.mediaType(), "").toLowerCase(Locale.ROOT);
            return List.of(".csv", ".tsv", ".parquet", ".xlsx", ".xls", ".xlsm", ".json")
                    .stream().anyMatch(name::endsWith)
                    || Set.of("text/csv", "text/tab-separated-values", "application/json",
                    "application/vnd.apache.parquet").contains(mediaType);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Map<String, Object> generatedTransformConfig(String projectId, String goal, List<NodeDefinition> inputs) {
        var sourceRequests = new ArrayList<DataTransformService.SourceRequest>();
        var aliases = new LinkedHashMap<String, String>();
        for (var index = 0; index < inputs.size(); index++) {
            var node = inputs.get(index);
            var config = node.config() == null ? Map.<String, Object>of() : node.config();
            var alias = "data_" + (index + 1);
            aliases.put(node.id(), alias);
            switch (node.type()) {
                case FILE_INPUT -> sourceRequests.add(new DataTransformService.SourceRequest("FILE",
                        Objects.toString(config.get("resourceId"), ""), alias, node.name(), null,
                        Objects.toString(config.get("sheetName"), "")));
                case DATASET_INPUT -> sourceRequests.add(new DataTransformService.SourceRequest("EXTRACT",
                        Objects.toString(config.get("extractJobId"), ""), alias, node.name(), null, null));
                case DATA_EXTRACT -> sourceRequests.add(new DataTransformService.SourceRequest("CONNECTION",
                        Objects.toString(config.get("connectionId"), ""), alias, node.name(),
                        Objects.toString(config.get("sql"), ""), null));
                default -> { }
            }
        }
        var generated = dataTransforms.generate(projectId, new DataTransformService.GenerateRequest(goal, sourceRequests));
        var config = new LinkedHashMap<String, Object>();
        config.put("requirements", goal);
        config.put("transformPlan", generated.get("plan"));
        config.put("script", Objects.toString(generated.get("script"), ""));
        config.put("scriptSummary", Objects.toString(generated.get("summary"), "已根据输入字段生成可复核加工方案"));
        config.put("scriptMode", "GENERATED");
        config.put("inputAliases", aliases);
        config.put("outputFormat", "parquet");
        config.put("outputName", "数据加工结果.parquet");
        config.put("transparent", true);
        if (generated.get("assumptions") != null) config.put("assumptions", generated.get("assumptions"));
        if (generated.get("quality_rules") != null) config.put("qualityRules", generated.get("quality_rules"));
        if (generated.get("warnings") != null) config.put("warnings", generated.get("warnings"));
        return Map.copyOf(config);
    }

    private NodeDefinition workspaceInputNode(com.finflow.studio.workspace.WorkspaceModels.Resource resource, double x, double y) {
        var id = "input_" + shortId();
        return switch (resource.resourceType()) {
            case "DATABASE_CONNECTION", "API_CONNECTION" -> new NodeDefinition(id, NodeType.DATA_EXTRACT, resource.name(), x, y,
                    Map.of("connectionId", resource.id(), "tableCatalog", "", "tableSchema", "", "tableName", "",
                            "sql", "API_CONNECTION".equals(resource.resourceType()) ? "GET /" : "",
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
        var current = workflows.ensureProjectWorkflow(projectId);
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
        var current = workflows.ensureProjectWorkflow(projectId);
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
        effects.put("workflowRunStatus", run.status());
        effects.put("workflowRunArguments", new LinkedHashMap<>(step.arguments()));
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "workflowId", workflowId,
                "runId", run.id(), "refreshWorkspace", true));
        return "工作流已启动，正在按节点执行";
    }

    @SuppressWarnings("unchecked")
    private int collectWorkflowDeliverables(com.finflow.studio.workflow.WorkflowModels.RunResponse run,
                                            Map<String, Object> effects) {
        var outputsById = new LinkedHashMap<String, Map<String, Object>>();
        if (effects.get("deliverables") instanceof List<?> values) {
            values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).forEach(output -> {
                var id = Objects.toString(output.get("deliverableId"), Objects.toString(output.get("id"), ""));
                if (!id.isBlank()) outputsById.put(id, output);
            });
        }
        int generatedCount = 0;
        for (var node : run.nodes()) {
            var deliverableId = Objects.toString(node.output().get("deliverableId"), "");
            if (deliverableId.isBlank()) continue;
            outputsById.put(deliverableId, new LinkedHashMap<>(node.output()));
            generatedCount++;
        }
        if (!outputsById.isEmpty()) effects.put("deliverables", new ArrayList<>(outputsById.values()));
        return generatedCount;
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
        var current = workflows.ensureProjectWorkflow(projectId);
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
                    Map.of("connectionId", resourceId, "tableCatalog", "", "tableSchema", "", "tableName", "",
                            "sql", "API_CONNECTION".equals(type) ? "GET /" : "",
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
            config.put("fastPlanning", true);
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
        if (requested.isEmpty()) requested.addAll(requestedFormats(goal));
        return requested.stream().map(this::normalizeDeliverableFormat).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<NodeDefinition> append(List<NodeDefinition> source, NodeDefinition item) {
        var result = new ArrayList<>(source);
        result.add(item);
        return result;
    }

    private String workflowName(PlanStep step) {
        var name = argument(step, "name", "");
        if (!name.isBlank()) return name.trim();
        if (name.isBlank()) {
            name = argument(step, "goal", "新工作流")
                    .replaceAll("^(请|帮我|给我|把|将|一个|新建|新增|创建|搭建|建立)+", "")
                    .replaceAll("(可复用|本次不运行|这次不运行|先不运行)", "")
                    .replaceAll("[:：，,。！？；;].*$", "").trim();
        }
        name = name.replaceAll("[《》“”\"']", "")
                .replaceAll("(请|帮我|给我)$", "")
                .trim();
        if (name.isBlank() || "的工作流".equals(name)) return "新工作流";
        if (!name.endsWith("工作流")) name += "工作流";
        return name.substring(0, Math.min(name.length(), 24));
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
