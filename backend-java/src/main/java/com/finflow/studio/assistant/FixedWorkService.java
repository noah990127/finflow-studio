package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.data.DataTransformService;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;

@Service
public class FixedWorkService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final WorkspaceResourceService workspace;
    private final KnowledgeService knowledge;
    private final DeliverableService deliverables;
    private final DataTransformService transforms;
    private final WorkerClient worker;
    private final AssistantModelSettings models;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private FixedToolExecutor tools;
    @org.springframework.beans.factory.annotation.Autowired
    private TechnicalArtifactService artifacts;
    @org.springframework.beans.factory.annotation.Autowired
    private TechnicalExecutionJournal journal;

    public FixedWorkService(JdbcClient jdbc, ObjectMapper json, ProjectService projects,
            WorkspaceResourceService workspace, KnowledgeService knowledge, DeliverableService deliverables,
            DataTransformService transforms, WorkerClient worker, AssistantModelSettings models,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor, TransactionTemplate tx) {
        this.jdbc = jdbc; this.json = json; this.projects = projects; this.workspace = workspace;
        this.knowledge = knowledge; this.deliverables = deliverables; this.transforms = transforms;
        this.worker = worker; this.models = models; this.executor = executor; this.tx = tx;
    }
    public record Definition(String id, int version, String hash, boolean verified, FixedWorkDefinition implementation, Instant createdAt) { }
    public record Bound(String resourceId, int version, String name, String type) { }
    public record StageResult(String id, String title, String status, List<Bound> inputs, Bound output, String error) { }
    @org.springframework.beans.factory.annotation.Autowired
    private FixedInteractionService interactions;
    public record Run(String id, String definitionId, String status, Map<String, Bound> snapshot,
                      List<StageResult> stages, String error, Instant createdAt, List<AgentWorkService.Stage> businessStages,
                      FixedInteractionService.Interaction interaction) { }
    public record State(Definition definition, List<Run> runs, List<TechnicalArtifactService.Artifact> artifacts) { }
    public record Start(String definitionId, String requestId) { }

    private void authorize(String project, String session) {
        projects.get(project);
        if (jdbc.sql("""
                select count(*) from assistant_session s where s.id=:session and s.default_user_id=:actor
                and (s.project_id=:project or exists (select 1 from assistant_plan p join assistant_context_snapshot c on c.id=p.context_snapshot_id
                            where p.session_id=s.id and c.project_id=:project)
                     or exists (select 1 from fixed_work_definition d where d.session_id=s.id and d.project_id=:project))
                """)
                .param("session", session).param("project", project).param("actor", ActorContext.current()).query(Integer.class).single() != 1)
            throw new IllegalArgumentException("工作不属于当前项目或用户");
    }
    private void lock(String session) {
        jdbc.sql("select id from assistant_session where id=:id for update").param("id", session).query(String.class).single();
    }
    public State state(String project, String session) {
        authorize(project, session);
        var definition = latestDefinition(project, session);
        return new State(definition, runs(project, session), definition == null ? List.of() : artifacts.list("DEFINITION", definition.id()));
    }

    public Map<String, Object> executionDetails(String project, String session, String runId) {
        authorize(project, session);
        var run = getRun(project, session, runId);
        return Map.of("steps", journal.entries(runId), "artifacts", artifacts.list("EXECUTION", runId),
                "implementationArtifacts", artifacts.list("DEFINITION", run.definitionId()));
    }

    /** Persist the already executed browser implementation without replaying its side effects. */
    public Definition retainBrowser(String sourceRun, String taskId, String hash, String parametersId) {
        var project = projectForRun(sourceRun);
        var session = jdbc.sql("select session_id from assistant_run where id=:id").param("id", sourceRun).query(String.class).single();
        authorize(project, session);
        return tx.execute(status -> {
            lock(session);
            var current = latestDefinition(project, session);
            if (current != null) {
                if (current.implementation().steps().stream().anyMatch(s -> s.code().contains(taskId))) return current;
                throw new IllegalStateException("当前工作已有其他实现；浏览器脚本已保留，请通过Agent明确合并，不覆盖原工序");
            }
            var code = json.writeValueAsString(Map.of("tool", "browser.replay", "version", 1, "arguments",
                    Map.of("sourceTask", taskId, "hash", hash, "parameters", Map.of("$input", "parameters", "as", "json"))));
            var recorded = jdbc.sql("select stages_json from agent_work where run_id=:run").param("run",sourceRun).query(String.class).optional().orElse("[]");
            var stages = json.readValue(recorded, AgentWorkService.Stage[].class);
            if (stages.length > 1) throw new IllegalStateException("浏览器实现尚未映射到各业务工序，请明确技术步骤归属后发布；不会把全部操作挂到最后一道工序");
            var title = stages.length == 0 ? "执行浏览器操作" : stages[stages.length-1].title();
            var step = new FixedWorkDefinition.Step("browser", title, "TOOL", code, "浏览器执行结果.md",
                    List.of(new FixedWorkDefinition.Input("parameters", parametersId, null, null)));
            var implementation = new FixedWorkDefinition(title, List.of(step), Map.of(),
                    List.of(new FixedWorkDefinition.BusinessStage("browser", title, "按已固化操作处理本轮输入",
                            "核对本轮页面实际结果", List.of("browser"), List.of(new FixedWorkDefinition.Outcome("browser", "PRIMARY")))));
            implementation.validate(); FixedToolCall.parse(json, step);
            var id = UUID.randomUUID().toString(); var now = Instant.now(); var encoded = json.writeValueAsString(implementation);
            artifacts.archiveDefinition(project, id, encoded, HashSupport.sha256(encoded));
            jdbc.sql("insert into fixed_work_definition(id,project_id,session_id,source_run_id,version,definition_json,content_hash,verified,created_at) values(:id,:project,:session,:run,1,:definition,:hash,false,:now)")
                    .param("id", id).param("project", project).param("session", session).param("run", sourceRun)
                    .param("definition", encoded).param("hash", HashSupport.sha256(encoded)).param("now", now).update();
            return new Definition(id, 1, HashSupport.sha256(encoded), false, implementation, now);
        });
    }
    AgentWorkService.Work overlay(String project, AgentWorkService.Work work) {
        var latest = runs(project, work.sessionId(), 1).stream().findFirst().orElse(null);
        if (latest == null || latest.createdAt().isBefore(work.createdAt())) return work;
        var stages = latest.businessStages();
        return new AgentWorkService.Work(work.id(), work.sessionId(), work.goal(), latest.status(), latest.error(), work.title(),
                work.createdAt(), stages, Map.of("fixedRunId", latest.id(), "definitionId", latest.definitionId()));
    }
    public Definition definition(String project, String session, String id) {
        authorize(project, session);
        return jdbc.sql("select * from fixed_work_definition where id=:id and session_id=:session and project_id=:project")
                .param("id", id).param("session", session).param("project", project)
                .query((rs, i) -> new Definition(rs.getString("id"), rs.getInt("version"), rs.getString("content_hash"), rs.getBoolean("verified"),
                        json.readValue(rs.getString("definition_json"), FixedWorkDefinition.class), rs.getTimestamp("created_at").toInstant())).optional()
                .orElseThrow(() -> new IllegalArgumentException("实现版本不存在或不属于当前工作"));
    }
    private Definition latestDefinition(String project, String session) {
        return jdbc.sql("select * from fixed_work_definition where session_id=:id and project_id=:project order by version desc limit 1")
                .param("id", session).param("project", project).query((rs, i) -> new Definition(rs.getString("id"), rs.getInt("version"), rs.getString("content_hash"),
                        rs.getBoolean("verified"), json.readValue(rs.getString("definition_json"), FixedWorkDefinition.class), rs.getTimestamp("created_at").toInstant()))
                .optional().orElse(null);
    }
    private List<Run> runs(String project, String session) {
        return runs(project, session, 30);
    }
    private List<Run> runs(String project, String session, int limit) {
        return jdbc.sql("select r.* from fixed_work_run r join fixed_work_definition d on d.id=r.definition_id where r.session_id=:id and d.project_id=:project order by r.created_at desc limit :limit")
                .param("id", session).param("project", project).param("limit", limit).query(this::mapRun).list();
    }
    private Run mapRun(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var implementation = jdbc.sql("select definition_json from fixed_work_definition where id=:id")
                .param("id", rs.getString("definition_id")).query(String.class).single();
        var stages = Arrays.asList(json.readValue(rs.getString("stages_json"), StageResult[].class));
        var interaction = interactions.get(rs.getString("id"));
        var status = rs.getString("status");
        if ("RUNNING".equals(status) && interaction != null && "WAITING_INPUT".equals(interaction.status())) {
            status = "WAITING_INPUT";
            var waiting = interaction;
            stages = stages.stream().map(s -> s.id().equals(waiting.stepId()) && "RUNNING".equals(s.status())
                    ? new StageResult(s.id(), s.title(), "WAITING_INPUT", s.inputs(), s.output(), "") : s).toList();
        } else if (!"RUNNING".equals(status)) interaction = null;
        return new Run(rs.getString("id"), rs.getString("definition_id"), status,
                readSnapshot(rs.getString("snapshot_json")), stages,
                rs.getString("error"), rs.getTimestamp("created_at").toInstant(),
                BusinessWorkProjection.project(json.readValue(implementation, FixedWorkDefinition.class), stages), interaction);
    }
    public void controlInteraction(String project, String session, String runId, String taskId, int promptId, String action) {
        authorize(project, session);
        if (!"WAITING_INPUT".equals(getRun(project, session, runId).status()))
            throw new IllegalStateException("本轮没有待处理事项，请刷新状态");
        interactions.control(runId, taskId, promptId, action);
    }
    private Run getRun(String project, String session, String id) {
        return jdbc.sql("select r.* from fixed_work_run r join fixed_work_definition d on d.id=r.definition_id where r.id=:id and r.session_id=:session and d.project_id=:project")
                .param("id", id).param("session", session).param("project", project).query(this::mapRun).optional()
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在"));
    }
    private Map<String, Bound> readSnapshot(String value) {
        var raw = json.readValue(value, Map.class);
        var result = new LinkedHashMap<String, Bound>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), json.convertValue(item, Bound.class)));
        return result;
    }

    public Definition publish(String sourceRun, Map<String, Object> arguments) {
        var context = jdbc.sql("""
                select r.session_id, c.project_id from assistant_run r join assistant_plan p on p.id=r.plan_id
                join assistant_context_snapshot c on c.id=p.context_snapshot_id
                where r.id=:id and r.status='RUNNING'
                """).param("id", sourceRun).query((rs, i) -> List.of(rs.getString(1), rs.getString(2))).single();
        var session = context.get(0); var project = projectForRun(sourceRun);
        authorize(project, session);
        var proposal = json.convertValue(arguments.get("implementation"), FixedWorkDefinition.class);
        if (proposal == null) throw new IllegalArgumentException("缺少实现定义");
        proposal.validate();
        for (var step : proposal.steps()) if ("TOOL".equals(step.kind())) {
            try {
                var call = FixedToolCall.parse(json, step);
                var texts = new LinkedHashMap<String, String>();
                for (var input : step.inputs()) if (input.resourceId() != null && !input.resourceId().isBlank())
                    texts.put(input.alias(), readText(bind(project, input.resourceId())));
                @SuppressWarnings("unchecked")
                var args = (Map<String, Object>) FixedToolCall.bind(call.arguments(), texts, json, true);
                call.validateArguments(args);
            } catch (IllegalArgumentException | java.io.IOException error) {
                throw new IllegalArgumentException("工序「" + step.title() + "」参数校验失败：" + error.getMessage(), error);
            }
        }
        var model = proposal.steps().stream().anyMatch(s -> "PROMPT".equals(s.kind()))
                ? worker.fixedWorkModel("snapshot", modelRequest(session)) : Map.<String, Object>of();
        var definition = new FixedWorkDefinition(proposal.title(), proposal.steps(), model, proposal.businessStages());
        // Validate project ownership now and again when binding current versions for each run.
        for (var step : definition.steps()) for (var input : step.inputs())
            if (input.resourceId() != null && !input.resourceId().isBlank()) bind(project, input.resourceId());
        var encoded = json.writeValueAsString(definition); var hash = HashSupport.sha256(encoded);
        return tx.execute(status -> {
            lock(session);
            var stillRunning = jdbc.sql("select status from assistant_run where id=:id for update").param("id", sourceRun).query(String.class).single();
            if (!"RUNNING".equals(stillRunning)) throw new IllegalStateException("原任务已结束，不能发布实现");
            if (active(session)) throw new IllegalStateException("固定实现正在执行，请先中止再修改");
            var current = latestDefinition(project, session);
            if (current != null && current.hash().equals(hash)) return current;
            int expected = arguments.get("expected_version") instanceof Number n ? n.intValue() : 0;
            if (expected != (current == null ? 0 : current.version())) throw new IllegalStateException("实现版本已变化，请重新读取后修改");
            var id = UUID.randomUUID().toString(); var now = Instant.now(); int version = expected + 1;
            artifacts.archiveDefinition(project, id, encoded, hash);
            jdbc.sql("""
                    insert into fixed_work_definition(id, project_id, session_id, source_run_id, version, definition_json, content_hash, verified, created_at)
                    values (:id,:project,:session,:run,:version,:definition,:hash,false,:now)
                    """).param("id", id).param("project", project).param("session", session).param("run", sourceRun)
                    .param("version", version).param("definition", encoded).param("hash", hash).param("now", now).update();
            return new Definition(id, version, hash, false, definition, now);
        });
    }
    String projectForRun(String runId) {
        return jdbc.sql("""
                select r.effects_json,c.project_id from assistant_run r join assistant_plan p on p.id=r.plan_id
                join assistant_context_snapshot c on c.id=p.context_snapshot_id where r.id=:id
                """).param("id", runId).query((rs, row) -> {
                    var effects = json.readValue(rs.getString(1), Map.class);
                    return Objects.toString(effects.get("currentProjectId"), Objects.toString(effects.get("createdProjectId"), rs.getString(2)));
                }).single();
    }
    void requirePublished(String runId) {
        var recorded = json.readValue(jdbc.sql("select effects_json from assistant_run where id=:id").param("id", runId).query(String.class).single(), Map.class);
        var published = Objects.toString(recorded.get("fixedImplementationId"), "");
        if (!published.isBlank()) {
            var valid = jdbc.sql("""
                    select count(*) from fixed_work_definition d join assistant_run r on r.session_id=d.session_id
                    where r.id=:run and d.id=:definition and d.project_id=:project
                    """).param("run", runId).param("definition", published).param("project", projectForRun(runId))
                    .query(Integer.class).single();
            if (valid == 0) throw new IllegalStateException("本轮关联的固定实现不存在或不属于当前工作，请重新读取实现版本");
        }
        if (jdbc.sql("select count(*) from fixed_work_definition where source_run_id=:id")
                .param("id", runId).query(Integer.class).single() == 0 && published.isBlank())
            throw new IllegalStateException("产物已保存，但本轮尚未固化可重复执行的工序；请继续保存固定工具调用及输入绑定，不能报告工作全部完成");
    }
    private Map<String, Object> modelRequest(String session) {
        var config = models.resolve(session);
        return config.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(Map.of("configuration", config));
    }
    private boolean active(String session) {
        return jdbc.sql("select count(*) from fixed_work_run where session_id=:id and status in ('QUEUED','RUNNING','CANCEL_REQUESTED')")
                .param("id", session).query(Integer.class).single() > 0;
    }
    public Run start(String project, String session, Start request) {
        return start(project, session, request, "");
    }
    Run startForAgent(String sourceRun) {
        var session = jdbc.sql("select session_id from assistant_run where id=:id and status='RUNNING'")
                .param("id", sourceRun).query(String.class).single();
        var project = projectForRun(sourceRun);
        authorize(project, session);
        var definition = latestDefinition(project, session);
        if (definition == null) throw new IllegalStateException("请先发布固定实现");
        var requestId = UUID.nameUUIDFromBytes((sourceRun + ":" + definition.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return start(project, session, new Start(definition.id(), requestId), sourceRun);
    }
    private Run start(String project, String session, Start request, String sourceRun) {
        authorize(project, session);
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("执行请求编号无效");
        var actor = ActorContext.current();
        var started = tx.execute(status -> {
            lock(session);
            var existing = jdbc.sql("select id from fixed_work_run where session_id=:session and request_id=:request")
                    .param("session", session).param("request", request.requestId()).query(String.class).optional();
            if (existing.isPresent()) return existing.get();
            if (active(session)) throw new IllegalStateException("工作正在执行，请等待或中止");
            if (!sourceRun.isBlank() && jdbc.sql("select count(*) from assistant_run where id=:run and session_id=:session and status='RUNNING'")
                    .param("run", sourceRun).param("session", session).query(Integer.class).single() != 1)
                throw new IllegalStateException("原任务已结束，不能启动执行");
            if (jdbc.sql("select count(*) from assistant_run where session_id=:id and id<>:source and status in ('QUEUED','RUNNING','WAITING_CONFIRMATION')")
                    .param("id", session).param("source", sourceRun).query(Integer.class).single() > 0) throw new IllegalStateException("Agent正在修改工作，请等待结束");
            var definition = latestDefinition(project, session);
            if (definition == null || !definition.id().equals(request.definitionId())) throw new IllegalStateException("实现不存在或版本已变化，请刷新后重试");
            var encoded = jdbc.sql("select definition_json from fixed_work_definition where id=:id").param("id", definition.id()).query(String.class).single();
            // Explicit compatibility migration before dispatch; existing archives are never replaced.
            artifacts.archiveDefinition(project, definition.id(), encoded, definition.hash());
            var executable = artifacts.loadDefinition(definition.id(), definition.hash());
            var snapshot = new LinkedHashMap<String, Bound>();
            var previous = runs(project, session).stream().filter(r -> r.definitionId().equals(definition.id())).toList();
            var boundResources = new HashMap<String, Bound>();
            for (var step : executable.steps()) for (var input : step.inputs()) {
                if (input.resourceId() == null || input.resourceId().isBlank()) continue;
                var id = input.resourceId();
                if (input.previousOutput() != null && !input.previousOutput().isBlank())
                    id = previous.stream().flatMap(r -> r.stages().stream()).filter(s -> s.id().equals(input.previousOutput()) && s.output() != null)
                            .map(s -> s.output().resourceId()).findFirst().orElse(id);
                snapshot.put(step.id() + "/" + input.alias(), boundResources.computeIfAbsent(id, key -> bind(project, key)));
            }
            var stages = executable.steps().stream().map(s -> new StageResult(s.id(), s.title(), "PENDING", List.of(), null, "")).toList();
            var id = UUID.randomUUID().toString();
            jdbc.sql("""
                    insert into fixed_work_run(id, definition_id, session_id, request_id, status, snapshot_json, stages_json, error, created_at)
                    values (:id,:definition,:session,:request,'QUEUED',:snapshot,:stages,'',:now)
                    """).param("id", id).param("definition", definition.id()).param("session", session).param("request", request.requestId())
                    .param("snapshot", json.writeValueAsString(snapshot)).param("stages", json.writeValueAsString(stages)).param("now", Instant.now()).update();
            return id;
        });
        // The conditional QUEUED->RUNNING transition makes retries and duplicate scheduling harmless.
        executor.execute(() -> ActorContext.runAs(actor, () -> execute(project, session, started)));
        return getRun(project, session, started);
    }
    private Bound bind(String project, String id) {
        var resource = workspace.get(project).resources().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("输入资源不存在或不属于当前项目：" + id));
        // Snapshot only versioned files; live database connections are not immutable input snapshots.
        if (!Set.of("DATA_FILE", "KNOWLEDGE_FILE", "OFFICE_FILE", "DELIVERABLE").contains(resource.resourceType())) throw new IllegalArgumentException("请先将输入保存为版本化文件：" + resource.name());
        return new Bound(id, resource.currentVersion(), resource.name(), "DELIVERABLE".equals(resource.resourceType()) ? "DELIVERABLE" : "FILE");
    }
    public void cancel(String project, String session, String id) {
        authorize(project, session);
        tx.executeWithoutResult(status -> {
            lock(session);
            getRun(project, session, id);
            var changed = jdbc.sql("update fixed_work_run set status='CANCEL_REQUESTED' where id=:id and session_id=:session and status in ('QUEUED','RUNNING')")
                    .param("id", id).param("session", session).update();
        });
        var thread = threads.get(id);
        if (thread != null) thread.interrupt();
    }
    private void check(String id) {
        if (Thread.currentThread().isInterrupted() || !"RUNNING".equals(jdbc.sql("select status from fixed_work_run where id=:id")
                .param("id", id).query(String.class).single())) throw new CancellationException("执行已中止");
    }
    private void execute(String project, String session, String id) {
        if (threads.putIfAbsent(id, Thread.currentThread()) != null) return;
        boolean dispatched = false;
        try {
            int changed = jdbc.sql("update fixed_work_run set status='RUNNING' where id=:id and status='QUEUED'").param("id", id).update();
            if (changed == 0) {
                jdbc.sql("update fixed_work_run set status='CANCELED', finished_at=:now where id=:id and status='CANCEL_REQUESTED'")
                        .param("id", id).param("now", Instant.now()).update();
                return;
            }
            dispatched = true;
            var run = getRun(project, session, id);
            var hash = jdbc.sql("select content_hash from fixed_work_definition where id=:id").param("id", run.definitionId()).query(String.class).single();
            var implementation = artifacts.loadDefinition(run.definitionId(), hash);
            var stages = new ArrayList<>(run.stages());
            try {
                for (int index = 0; index < implementation.steps().size(); index++) {
                    check(id);
                    var step = implementation.steps().get(index);
                    var inputs = new LinkedHashMap<String, Bound>();
                    for (var input : step.inputs()) {
                        var bound = input.stepId() == null || input.stepId().isBlank() ? run.snapshot().get(step.id() + "/" + input.alias())
                                : stages.stream().filter(s -> s.id().equals(input.stepId())).map(StageResult::output).filter(Objects::nonNull).findFirst().orElseThrow();
                        inputs.put(input.alias(), bound);
                    }
                    stages.set(index, new StageResult(step.id(), step.title(), "RUNNING", List.copyOf(inputs.values()), null, ""));
                    saveStages(id, stages);
                    journal.begin(id, run.definitionId(), implementation, step, index, inputs);
                    Bound output;
                    if ("SQL".equals(step.kind())) {
                        var upstream = new LinkedHashMap<String, Map<String, Object>>();
                        var aliases = new LinkedHashMap<String, String>();
                        inputs.forEach((alias, input) -> {
                            if (!"FILE".equals(input.type())) throw new IllegalArgumentException("SQL输入需要数据文件：" + input.name());
                            upstream.put(alias, Map.of("fileId", input.resourceId(), "version", input.version())); aliases.put(alias, alias);
                        });
                        var result = transforms.execute(project, Map.of("script", step.code(), "inputAliases", aliases, "outputName", step.outputName(), "outputFormat", "csv"), upstream);
                        output = new Bound(result.resource().id(), result.resource().currentVersion(), result.resource().name(), "FILE");
                    } else if ("TOOL".equals(step.kind())) {
                        var texts = new LinkedHashMap<String, String>();
                        for (var entry : inputs.entrySet()) texts.put(entry.getKey(), readText(entry.getValue()));
                        output = tools.execute(project, step, texts, () -> check(id), session, id);
                    } else {
                        var texts = new LinkedHashMap<String, String>();
                        for (var entry : inputs.entrySet()) texts.put(entry.getKey(), readText(entry.getValue()));
                        var request = modelRequest(session);
                        request.put("snapshot", implementation.modelSnapshot()); request.put("prompt", step.code()); request.put("inputs", texts);
                        var content = Objects.toString(worker.fixedWorkModel("execute", request).get("content"), "");
                        check(id);
                        var file = knowledge.importBytes(project, step.outputName().replaceFirst("(?i)\\.md$", "") + ".md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
                        output = new Bound(file.id(), file.currentVersion(), file.name(), "FILE");
                    }
                    // Keep already-produced outputs visible even if cancellation arrived during a tool call.
                    stages.set(index, new StageResult(step.id(), step.title(), "SUCCEEDED", List.copyOf(inputs.values()), output, ""));
                    journal.complete(id, step.id(), output);
                    saveStages(id, stages); check(id);
                }
                if (BusinessWorkProjection.project(implementation, stages).stream().anyMatch(s -> !"SUCCEEDED".equals(s.status())))
                    throw new IllegalStateException("业务工序主要成果不完整，不能报告执行完成");
                tx.executeWithoutResult(status -> {
                    int done = jdbc.sql("update fixed_work_run set status='SUCCEEDED', finished_at=:now where id=:id and status='RUNNING'")
                            .param("id", id).param("now", Instant.now()).update();
                    if (done == 1) jdbc.sql("update fixed_work_definition set verified=true where id=:id").param("id", run.definitionId()).update();
                });
            } catch (Exception failure) {
                Thread.interrupted();
                var current = jdbc.sql("select status from fixed_work_run where id=:id").param("id", id).query(String.class).single();
                boolean canceled = "CANCEL_REQUESTED".equals(current) || failure instanceof CancellationException;
                String message = canceled ? "执行已中止；已完成的产物保留" : Objects.toString(failure.getMessage(), "执行失败");
                for (int i = 0; i < stages.size(); i++) {
                    var stage = stages.get(i);
                    if ("RUNNING".equals(stage.status())) stages.set(i, new StageResult(stage.id(), stage.title(), canceled ? "CANCELED" : "FAILED", stage.inputs(), stage.output(), message));
                    else if ("PENDING".equals(stage.status()) && canceled) stages.set(i, new StageResult(stage.id(), stage.title(), "CANCELED", stage.inputs(), null, ""));
                }
                saveStages(id, stages);
                jdbc.sql("update fixed_work_run set status=:status, error=:error, finished_at=:now where id=:id")
                        .param("status", canceled ? "CANCELED" : "FAILED").param("error", message).param("now", Instant.now()).param("id", id).update();
            }
        } catch (Exception failure) {
            Thread.interrupted();
            jdbc.sql("update fixed_work_run set status='FAILED', error=:error, finished_at=:now where id=:id and status in ('RUNNING','QUEUED')")
                    .param("error", Objects.toString(failure.getMessage(), "执行初始化失败")).param("now", Instant.now()).param("id", id).update();
        } finally {
            try {
                if (dispatched) {
                    journal.interrupted(id, "本轮中断或调用未返回明确结果；不得自动重复提交");
                    journal.archive(project, id);
                }
            }
            catch (RuntimeException archiveError) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Execution journal archive failed for {}", id, archiveError);
            }
            threads.remove(id, Thread.currentThread()); Thread.interrupted();
            jdbc.sql("update fixed_work_run set status='CANCELED', finished_at=:now where id=:id and status='CANCEL_REQUESTED'")
                    .param("now", Instant.now()).param("id", id).update();
        }
    }
    private void saveStages(String id, List<StageResult> stages) {
        jdbc.sql("update fixed_work_run set stages_json=:stages where id=:id").param("stages", json.writeValueAsString(stages)).param("id", id).update();
    }
    private String readText(Bound input) throws java.io.IOException {
        if (!input.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt|csv|json|jsonl|xml|html)$"))
            throw new IllegalArgumentException("模型输入请先转换为文本文件：" + input.name());
        var path = "DELIVERABLE".equals(input.type()) ? deliverables.path(input.resourceId(), input.version()) : knowledge.filePath(input.resourceId(), input.version());
        if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("模型输入超过2MB，不自动截断：" + input.name());
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        jdbc.sql("update fixed_work_run set status='FAILED', error='服务重启中断执行；未自动重跑，请检查已保存产物后重新开始', finished_at=:now where status in ('RUNNING','QUEUED','CANCEL_REQUESTED')")
                .param("now", Instant.now()).update();
    }
}
