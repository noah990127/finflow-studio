package com.finflow.studio.assistant;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.finflow.studio.assistant.AssistantModels.*;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AssistantService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;
    private final AssistantPlanner planner;
    private final AssistantExecutionService execution;
    private final AssistantEventService events;
    private final WorkspaceResourceService workspace;
    private final AgentMemoryService memory;

    public AssistantService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                            AssistantPlanner planner, AssistantExecutionService execution,
                            AssistantEventService events, WorkspaceResourceService workspace,
                            AgentMemoryService memory) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.planner = planner;
        this.execution = execution;
        this.events = events;
        this.workspace = workspace;
        this.memory = memory;
    }

    public SessionResponse createSession(String projectId, String title) {
        projects.get(projectId);
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        var safeTitle = title == null || title.isBlank() ? "新的专注任务" : title.trim();
        jdbc.sql("""
                insert into assistant_session(id, project_id, default_user_id, title, summary, status,
                                              last_context_version, created_at, updated_at)
                values (:id, :projectId, 'default_user', :title, '', 'ACTIVE', 0, :createdAt, :updatedAt)
                """)
                .param("id", id)
                .param("projectId", projectId)
                .param("title", safeTitle)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return getSession(id);
    }

    public SessionResponse getSession(String id) {
        return jdbc.sql("select * from assistant_session where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new SessionResponse(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("助手会话不存在"));
    }

    public List<SessionResponse> listSessions(String projectId) {
        projects.get(projectId);
        return jdbc.sql("""
                        select * from assistant_session
                        where project_id = :projectId
                        order by updated_at desc
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new SessionResponse(
                        rs.getString("id"), rs.getString("project_id"), rs.getString("title"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public List<MessageHistoryItem> listMessages(String sessionId) {
        getSession(sessionId);
        return jdbc.sql("""
                        select id, role, content, model_name, trace_id, created_at
                        from assistant_message where session_id = :sessionId
                        order by created_at asc
                        """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new MessageHistoryItem(
                        rs.getString("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("model_name"), rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public MessageResponse sendMessage(String sessionId, MessageRequest request) {
        var session = getSession(sessionId);
        var executionPolicy = ExecutionPolicy.from(request.executionMode());
        var traceId = UUID.randomUUID().toString();
        saveMessage(sessionId, "USER", request.text(), null, traceId);
        events.publish(sessionId, null, "assistant.request.received", Map.of(
                "progress", 3, "message", "已收到需求，正在理解你想完成的工作"));
        events.publish(sessionId, null, "agent.thinking_summary", Map.of(
                "status", "running", "progress", 4, "message", "正在判断目标、上下文和可能需要的工作区能力"));

        events.publish(sessionId, null, "assistant.context.started", Map.of(
                "progress", 7, "message", "正在读取当前项目和已选择的内容"));
        events.publish(sessionId, null, "agent.tool_search", Map.of(
                "status", "running", "toolName", "workspace.inspect", "progress", 8,
                "message", "正在查看当前项目、选择内容和资源目录"));
        var context = createContext(session, request);
        events.publish(sessionId, null, "assistant.planning.started", Map.of(
                "progress", 12, "message", "正在识别意图并匹配可用能力与技能"));
        events.publish(sessionId, null, "agent.skill_loading", Map.of(
                "status", "running", "progress", 13, "message", "正在按任务类型加载可复用 Skill"));
        var workspaceResponse = workspace.get(session.projectId());
        var selected = request.selection() == null ? null : workspaceResponse.resources().stream()
                .filter(resource -> resource.id().equals(request.selection().resourceId()))
                .findFirst().orElse(null);
        var workspaceItems = new ArrayList<Map<String, Object>>();
        workspaceResponse.folders().stream().limit(120).forEach(folder -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", folder.id());
            item.put("name", folder.name());
            item.put("type", "FOLDER");
            item.put("group", folder.rootKind());
            item.put("status", "READY");
            if (folder.parentId() != null) item.put("parent_id", folder.parentId());
            workspaceItems.add(item);
        });
        workspaceResponse.workflows().stream().limit(Math.max(0, 120 - workspaceItems.size())).forEach(workflow ->
                workspaceItems.add(Map.<String, Object>of(
                        "id", workflow.id(), "name", workflow.name(), "type", "WORKFLOW",
                        "group", "WORKFLOW", "status", workflow.status())));
        workspaceResponse.resources().stream().limit(Math.max(0, 120 - workspaceItems.size())).forEach(resource ->
                workspaceItems.add(Map.<String, Object>of(
                        "id", resource.id(), "name", resource.name(), "type", resource.resourceType(),
                        "group", resource.group(), "status", resource.status())));
        var workspaceContext = new AssistantPlanner.WorkspaceContext(
                session.projectId(), workspaceResponse.project().name(),
                (int) workspaceResponse.resources().stream().filter(resource -> "DATA".equals(resource.group())).count(),
                (int) workspaceResponse.resources().stream().filter(resource -> "KNOWLEDGE".equals(resource.group())).count(),
                (int) workspaceResponse.resources().stream().filter(resource -> "OUTPUT".equals(resource.group())).count(),
                workspaceResponse.resources().stream().anyMatch(resource -> "DATA".equals(resource.group())),
                selected == null ? null : selected.id(), selected == null ? null : selected.resourceType(),
                selected == null ? null : selected.name(),
                List.copyOf(workspaceItems),
                recentMessages(sessionId, session.projectId()));
        var plannedWork = planner.plan(request.text(), request.page(), request.selection(), workspaceContext,
                sessionId, executionPolicy.name());
        if (executionPolicy == ExecutionPolicy.AUTO) {
            var automaticSteps = plannedWork.steps().stream().map(step -> new PlanStep(
                    step.id(), step.order(), step.tool(), step.mode(), step.title(), step.description(),
                    step.arguments(), step.risk(), false, step.status())).toList();
            plannedWork = new AssistantPlanner.PlannedWork(plannedWork.summary(), automaticSteps);
        }
        events.publish(sessionId, null, "agent.planning", Map.of(
                "status", "completed", "progress", 18, "message", plannedWork.summary()));
        var plan = savePlan(sessionId, context, request.text(), plannedWork);
        saveMessage(sessionId, "ASSISTANT", plannedWork.summary(), modelName(), traceId);

        events.publish(sessionId, null, "assistant.plan.ready", Map.of(
                "planId", plan.id(),
                "summary", plan.summary(),
                "progress", 20,
                "message", "处理计划已经准备好",
                "requiresConfirmation", plan.steps().stream().anyMatch(PlanStep::requiresConfirmation),
                "executionMode", executionPolicy.name()));
        events.publish(sessionId, null, "agent.plan_updated", Map.of(
                "status", "completed", "planId", plan.id(), "version", plan.version(),
                "progress", 20, "message", "计划已更新，共 " + plan.steps().size() + " 个步骤"));

        RunResponse run = null;
        if (plan.steps().stream().anyMatch(PlanStep::requiresConfirmation)) {
            events.publish(sessionId, null, "assistant.confirmation.required", Map.of(
                    "planId", plan.id(),
                    "planHash", plan.planHash(),
                    "progress", 20,
                    "message", "等待你确认后开始执行",
                    "affectedResources", plan.affectedResources()));
            events.publish(sessionId, null, "agent.waiting_confirmation", Map.of(
                    "status", "waiting", "planId", plan.id(), "planHash", plan.planHash(),
                    "progress", 20, "message", "有写入、导出或高风险操作，需要确认后执行"));
        } else {
            if (executionPolicy == ExecutionPolicy.AUTO) {
                events.publish(sessionId, null, "agent.executing", Map.of(
                        "status", "running", "progress", 21,
                        "message", "Auto 模式已启用，正在直接执行 LLM 选择的工具"));
            }
            run = execution.start(sessionId, plan.id(), "auto-" + plan.id());
        }
        return new MessageResponse(sessionId, plannedWork.summary(), context, plan, run);
    }

    private List<Map<String, String>> recentMessages(String sessionId, String projectId) {
        var messages = new ArrayList<Map<String, String>>();
        memory.list(projectId).stream().limit(20).forEach(item -> messages.add(Map.of(
                "role", "system",
                "content", "工作记忆[" + item.scope() + "/" + item.key() + "]：" + writeJson(item.value()))));
        var history = jdbc.sql("""
                select role, content from assistant_message where session_id = :sessionId
                order by created_at desc limit 30
                """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> Map.of("role", rs.getString("role").toLowerCase(Locale.ROOT),
                        "content", rs.getString("content")))
                .list();
        Collections.reverse(history);
        messages.addAll(history);
        return messages;
    }

    public PlanResponse getPlan(String id) {
        var row = jdbc.sql("select * from assistant_plan where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new PlanRow(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        rs.getInt("version"),
                        rs.getString("plan_hash"),
                        RiskLevel.valueOf(rs.getString("risk_level")),
                        rs.getString("status"),
                        readList(rs.getString("affected_resources_json")),
                        rs.getTimestamp("expires_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("助手计划不存在"));
        return new PlanResponse(row.id(), row.sessionId(), row.goal(), row.summary(), row.version(),
                row.planHash(), row.risk(), row.status(), row.affectedResources(), loadSteps(id), row.expiresAt());
    }

    @Transactional
    public RunResponse confirm(String planId, ConfirmPlanRequest request) {
        var plan = getPlan(planId);
        if (plan.expiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("计划已经过期，请重新生成预览");
        }
        if (plan.version() != request.planVersion() || !plan.planHash().equals(request.planHash())) {
            throw new IllegalStateException("PLAN_STALE：计划已经变化，请重新查看后确认");
        }
        if (!List.of("WAITING_CONFIRMATION", "PLAN_READY").contains(plan.status())) {
            var existing = jdbc.sql("select * from assistant_run where plan_id = :planId order by created_at desc limit 1")
                    .param("planId", planId)
                    .query((rs, rowNum) -> execution.get(rs.getString("id")))
                    .optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            throw new IllegalStateException("当前计划不能再次确认");
        }
        verifyResourceVersions(planId, request.expectedResourceVersions());
        jdbc.sql("update assistant_plan set status = 'CONFIRMED', confirmed_at = :now where id = :id")
                .param("now", Instant.now())
                .param("id", planId)
                .update();
        return execution.start(plan.sessionId(), planId, request.idempotencyKey());
    }

    private ContextSnapshot createContext(SessionResponse session, MessageRequest request) {
        var id = UUID.randomUUID().toString();
        var resourceIds = request.selection() == null || request.selection().resourceId() == null
                ? List.<String>of()
                : List.of(request.selection().resourceId());
        var version = request.clientContextVersion() == null ? 1 : request.clientContextVersion();
        var versions = new LinkedHashMap<String, Integer>();
        resourceIds.forEach(resourceId -> versions.put(resourceId, version));
        var expiresAt = Instant.now().plus(60, ChronoUnit.MINUTES);
        var selectionJson = writeJson(request.selection() == null ? Map.of() : request.selection());
        var hash = HashSupport.sha256(session.projectId() + request.page() + selectionJson + versions);
        jdbc.sql("""
                insert into assistant_context_snapshot(id, session_id, project_id, page, selection_json,
                    allowed_resource_ids_json, resource_versions_json, context_hash, expires_at, created_at)
                values (:id, :sessionId, :projectId, :page, :selection, :resources, :versions,
                    :contextHash, :expiresAt, :createdAt)
                """)
                .param("id", id)
                .param("sessionId", session.id())
                .param("projectId", session.projectId())
                .param("page", request.page())
                .param("selection", selectionJson)
                .param("resources", writeJson(resourceIds))
                .param("versions", writeJson(versions))
                .param("contextHash", hash)
                .param("expiresAt", expiresAt)
                .param("createdAt", Instant.now())
                .update();
        jdbc.sql("update assistant_session set last_context_version = last_context_version + 1, updated_at = :now where id = :id")
                .param("now", Instant.now())
                .param("id", session.id())
                .update();
        return new ContextSnapshot(id, session.projectId(), request.page(), request.selection(),
                resourceIds, versions, hash, expiresAt);
    }

    private PlanResponse savePlan(String sessionId, ContextSnapshot context, String goal,
                                  AssistantPlanner.PlannedWork plannedWork) {
        var id = UUID.randomUUID().toString();
        var steps = plannedWork.steps();
        var risk = steps.stream().map(PlanStep::risk).max(Comparator.comparing(Enum::ordinal))
                .orElse(RiskLevel.READ_ONLY);
        var status = steps.stream().anyMatch(PlanStep::requiresConfirmation)
                ? "WAITING_CONFIRMATION" : "PLAN_READY";
        var affected = context.allowedResourceIds();
        var expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        var planHash = HashSupport.sha256(writeJson(Map.of(
                "goal", goal,
                "contextHash", context.contextHash(),
                "steps", steps,
                "version", 1
        )));
        jdbc.sql("""
                insert into assistant_plan(id, session_id, context_snapshot_id, goal, summary, version,
                    plan_hash, risk_level, status, affected_resources_json, expires_at, created_at)
                values (:id, :sessionId, :contextId, :goal, :summary, 1, :planHash, :riskLevel,
                    :status, :affected, :expiresAt, :createdAt)
                """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("contextId", context.id())
                .param("goal", goal)
                .param("summary", plannedWork.summary())
                .param("planHash", planHash)
                .param("riskLevel", risk.name())
                .param("status", status)
                .param("affected", writeJson(affected))
                .param("expiresAt", expiresAt)
                .param("createdAt", Instant.now())
                .update();
        for (var step : steps) {
            jdbc.sql("""
                    insert into assistant_plan_step(id, plan_id, step_order, tool_name, tool_mode, title,
                        description, arguments_json, risk_level, requires_confirmation, status)
                    values (:id, :planId, :stepOrder, :toolName, :toolMode, :title, :description,
                        :arguments, :riskLevel, :requiresConfirmation, 'PENDING')
                    """)
                    .param("id", step.id())
                    .param("planId", id)
                    .param("stepOrder", step.order())
                    .param("toolName", step.tool())
                    .param("toolMode", step.mode())
                    .param("title", step.title())
                    .param("description", step.description())
                    .param("arguments", writeJson(step.arguments()))
                    .param("riskLevel", step.risk().name())
                    .param("requiresConfirmation", step.requiresConfirmation())
                    .update();
        }
        return new PlanResponse(id, sessionId, goal, plannedWork.summary(), 1, planHash, risk,
                status, affected, steps, expiresAt);
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
                        RiskLevel.valueOf(rs.getString("risk_level")),
                        rs.getBoolean("requires_confirmation"),
                        rs.getString("status")
                ))
                .list();
    }

    private void verifyResourceVersions(String planId, Map<String, Integer> expected) {
        var stored = jdbc.sql("""
                        select c.resource_versions_json
                        from assistant_plan p
                        join assistant_context_snapshot c on c.id = p.context_snapshot_id
                        where p.id = :planId
                        """)
                .param("planId", planId)
                .query(String.class)
                .single();
        var versions = readIntegerMap(stored);
        if (!versions.equals(expected)) {
            throw new IllegalStateException("PLAN_STALE：数据或文件版本已经变化，请重新预览");
        }
    }

    private void saveMessage(String sessionId, String role, String content, String modelName, String traceId) {
        jdbc.sql("""
                insert into assistant_message(id, session_id, role, content, model_name, trace_id, created_at)
                values (:id, :sessionId, :role, :content, :modelName, :traceId, :createdAt)
                """)
                .param("id", UUID.randomUUID().toString())
                .param("sessionId", sessionId)
                .param("role", role)
                .param("content", content)
                .param("modelName", modelName)
                .param("traceId", traceId)
                .param("createdAt", Instant.now())
                .update();
        jdbc.sql("update assistant_session set updated_at = :now where id = :id")
                .param("now", Instant.now()).param("id", sessionId).update();
    }

    private String modelName() {
        var configured = System.getenv("OPENAI_MODEL");
        if (configured == null || configured.isBlank()) configured = System.getenv("DEEPSEEK_CHAT_MODEL");
        return "deep-agents:" + (configured == null || configured.isBlank() ? "local" : configured);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("无法序列化助手任务", ex);
        }
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("无法读取计划资源", ex);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException ex) {
            return Map.of();
        }
    }

    private Map<String, Integer> readIntegerMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("无法读取资源版本", ex);
        }
    }

    private record PlanRow(String id, String sessionId, String goal, String summary, int version,
                           String planHash, RiskLevel risk, String status, List<String> affectedResources,
                           Instant expiresAt) {
    }
}
