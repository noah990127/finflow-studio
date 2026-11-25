package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;

@Service
public class BrowserAgentService implements FixedInteractionHandler {
    @Override public String interactionProvider() { return "browser"; }
    static String approvalDescription(Map<String, Object> goal) {
        var text = "请批准本次浏览器任务（含目标内的填写和对外提交，不授权其他任务）：\n" + goal.get("goal")
                + "\n网站：" + java.net.URI.create(goal.get("url").toString()).getHost()
                + "\n额外授权域名：" + Objects.toString(goal.get("allowed_domains"), "无");
        if (text.length() > 1000) throw new IllegalArgumentException("浏览器任务授权范围过长，请缩短目标或减少额外域名；不会截断授权内容");
        return text;
    }
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AssistantEventService events;
    private final WebClient client;
    private final String token;
    @org.springframework.beans.factory.annotation.Autowired
    private com.finflow.studio.knowledge.KnowledgeService knowledge;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private FixedWorkService fixedWorks;
    @org.springframework.beans.factory.annotation.Autowired
    private BrowserAuthorizationService authorizations;
    @org.springframework.beans.factory.annotation.Autowired
    private TechnicalArtifactService technicalArtifacts;

    public BrowserAgentService(JdbcClient jdbc, ObjectMapper json, AssistantEventService events,
            @Value("${BROWSER_AGENT_URL:http://127.0.0.1:8003}") String url,
            @Value("${BROWSER_AGENT_TOKEN:}") String token) {
        this.jdbc = jdbc; this.json = json; this.events = events;
        this.client = WebClient.builder().baseUrl(url).codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build(); this.token = token;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpc(String method, Map<String, Object> params) {
        if (token.length() < 32) throw new IllegalStateException("独立浏览器 Agent 未配置 BROWSER_AGENT_TOKEN；不会切回旧浏览器实现");
        Map<String, Object> response;
        try {
            response = client.post().uri("/").header("Authorization", "Bearer " + token)
                    .header("X-Finflow-Owner", ActorContext.current())
                    .bodyValue(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params))
                    .retrieve().bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(20)).block();
        } catch (RuntimeException e) {
            throw new IllegalStateException("独立浏览器 Agent 连接失败，请检查8003服务与模型配置；不会重新执行或切回旧实现", e);
        }
        if (response == null || response.containsKey("error") || !(response.get("result") instanceof Map<?, ?>))
            throw new IllegalStateException("浏览器 Agent 未接受本次请求，请查看独立服务日志；任务不会自动重跑");
        return (Map<String, Object>) response.get("result");
    }

    public Map<String, Object> start(String stepId, Map<String, Object> goal) {
        authorizations.requireDomains(goal);
        var existing = jdbc.sql("select task_id from browser_agent_task where step_id=:step and owner_id=:owner")
                .param("step", stepId).param("owner", ActorContext.current()).query(String.class).optional();
        if (existing.isPresent()) return status(existing.get());
        var mode = jdbc.sql("select p.execution_mode from assistant_plan p join assistant_plan_step s on s.plan_id=p.id where s.id=:id")
                .param("id", stepId).query(String.class).single();
        goal = new LinkedHashMap<>(goal);
        goal.put("allowed_domains", BrowserAuthorizationService.requestedDomains(goal));
        goal.put("approval_mode", "AUTO".equalsIgnoreCase(mode) ? "AUTO" : "CONFIRM");
        var session = jdbc.sql("select r.session_id from assistant_run r join assistant_plan_step s on s.plan_id=r.plan_id where s.id=:id order by r.created_at desc limit 1")
                .param("id", stepId).query(String.class).single();
        var task = rpc("message/send", Map.of("message", Map.of("kind", "message", "role", "user", "messageId", stepId,
                "parts", List.of(Map.of("kind", "text", "text", goal.get("goal"))),
                "metadata", Map.of("approved", true, "requestKey", stepId, "browserGoal", goal))));
        var id = Objects.toString(task.get("id"), "");
        if (id.isBlank()) throw new IllegalStateException("浏览器 Agent 没有返回任务编号");
        jdbc.sql("insert into browser_agent_task(task_id,step_id,owner_id,session_id,last_result) values(:task,:step,:owner,:session,:result)")
                .param("task", id).param("step", stepId).param("owner", ActorContext.current())
                .param("session", session).param("result", json.writeValueAsString(task)).update();
        events.publish(session, null, "browser.agent.started", Map.of("taskId", id, "message", "已委派 browser-use 浏览器 Agent"));
        return status(id);
    }

    private String session(String id) {
        return jdbc.sql("select session_id from browser_agent_task where task_id=:id and owner_id=:owner")
                .param("id", id).param("owner", ActorContext.current()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("浏览器任务不存在"));
    }

    public Map<String, Object> status(String id) {
        var result = new LinkedHashMap<>(rawStatus(id));
        result.put("stepId", jdbc.sql("select step_id from browser_agent_task where task_id=:id and owner_id=:owner")
                .param("id", id).param("owner", ActorContext.current()).query(String.class).single());
        result.put("conversationTraceIds", jdbc.sql("""
                select s.plan_id as trace_id from browser_agent_task b
                join assistant_plan_step s on s.id=b.step_id
                where b.task_id=:id and b.owner_id=:owner
                union
                select r.id as trace_id from browser_agent_task b
                join assistant_plan_step s on s.id=b.step_id
                join assistant_run r on r.plan_id=s.plan_id and r.session_id=b.session_id
                where b.task_id=:id and b.owner_id=:owner
                """).param("id", id).param("owner", ActorContext.current()).query(String.class).list());
        if (knowledge != null) adoptHistory(id, result);
        if (knowledge != null && result.get("metadata") instanceof Map<?, ?> meta && meta.get("procedure") instanceof Map<?, ?> procedure) {
            try { result.put("savedProcedure", retain(id, result, procedure)); }
            catch (RuntimeException error) { result.put("procedureError", error.getMessage()); }
        }
        if (knowledge != null) {
            try { result.put("archivedResources", archiveTaskFiles(id, result)); }
            catch (RuntimeException error) { result.put("archiveIssue", "附件仍可下载，但项目资源归档失败：" + error.getMessage()); }
        }
        var files = new ArrayList<Map<String, String>>();
        if (result.remove("artifacts") instanceof List<?> artifacts) {
            for (int i = 0; i < artifacts.size(); i++) {
                if (!(artifacts.get(i) instanceof Map<?, ?> artifact) || !(artifact.get("parts") instanceof List<?> parts)) continue;
                for (int j = 0; j < parts.size(); j++) {
                    if (parts.get(j) instanceof Map<?, ?> part && part.get("file") instanceof Map<?, ?> file && file.get("bytes") instanceof String)
                        files.add(Map.of("name", Objects.toString(file.get("name"), "下载文件"), "url", "/api/browser/agent-tasks/" + id + "/files/" + i + "/" + j));
                }
            }
        }
        result.put("files", files);
        projectProcedureStatus(result);
        return result;
    }

    private synchronized List<AgentWorkService.ResourceRef> archiveTaskFiles(String id, Map<String, Object> task) {
        if (!(task.get("status") instanceof Map<?, ?> state) || !"completed".equals(state.get("state"))) return List.of();
        var run = jdbc.sql("select r.id from browser_agent_task b join assistant_plan_step s on s.id=b.step_id join assistant_run r on r.plan_id=s.plan_id and r.session_id=b.session_id where b.task_id=:id and b.owner_id=:owner order by r.created_at desc limit 1")
                .param("id", id).param("owner", ActorContext.current()).query(String.class).optional();
        if (run.isEmpty()) return List.of();
        var refs = new ArrayList<AgentWorkService.ResourceRef>();
        if (!(task.get("artifacts") instanceof List<?> artifacts)) return refs;
        for (int i = 0; i < artifacts.size(); i++) {
            if (!(artifacts.get(i) instanceof Map<?, ?> artifact) || !(artifact.get("parts") instanceof List<?> parts)) continue;
            for (int j = 0; j < parts.size(); j++) {
                if (!(parts.get(j) instanceof Map<?, ?> part) || !(part.get("file") instanceof Map<?, ?> file)
                        || !(file.get("bytes") instanceof String bytes)) continue;
                var name = Objects.toString(file.get("name"), "浏览器附件.txt");
                // Published executable bundles have their own versioned archive; candidates stay technical downloads.
                if (name.startsWith("browser-procedure")) continue;
                var key = i + ":" + j;
                var saved = jdbc.sql("select resource_id,resource_version from browser_task_artifact where task_id=:id and part_key=:key")
                        .param("id", id).param("key", key).query((rs, row) -> new AgentWorkService.ResourceRef(rs.getString(1), rs.getInt(2), "SUPPORTING")).optional();
                if (saved.isPresent()) { refs.add(saved.get()); continue; }
                var resource = knowledge.importBytes(fixedWorks.projectForRun(run.get()), name,
                        Objects.toString(file.get("mimeType"), "application/octet-stream"), Base64.getDecoder().decode(bytes));
                jdbc.sql("insert into browser_task_artifact(task_id,part_key,resource_id,resource_version) values(:id,:key,:resource,:version)")
                        .param("id", id).param("key", key).param("resource", resource.id()).param("version", resource.currentVersion()).update();
                refs.add(new AgentWorkService.ResourceRef(resource.id(), resource.currentVersion(), "SUPPORTING"));
            }
        }
        return refs;
    }

    static void projectProcedureStatus(Map<String, Object> task) {
        var meta = task.get("metadata") instanceof Map<?, ?> value ? value : Map.of();
        var saved = task.get("savedProcedure") instanceof Map<?, ?> value ? value : Map.of();
        var issue = Objects.toString(task.getOrDefault("procedureError", meta.getOrDefault("procedureError", null)), "");
        var state = Objects.toString(meta.get("procedureState"), "");
        if (!issue.isBlank()) state = "NEEDS_REVIEW";
        else if (saved.containsKey("definitionId")) state = "PUBLISHED";
        else if (meta.containsKey("procedure")) state = "READY";
        else if (state.isBlank() && task.get("status") instanceof Map<?, ?> status && "completed".equals(status.get("state")))
            state = "NOT_GENERATED";
        if (!state.isBlank()) task.put("procedureState", state);
        if (!issue.isBlank()) task.put("procedureIssue", issue);
        else if (saved.get("notice") != null) task.put("procedureIssue", saved.get("notice"));
    }

    @SuppressWarnings("unchecked")
    private synchronized void adoptHistory(String id, Map<String,Object> task) {
        if (!(task.get("status") instanceof Map<?,?> state) || !"completed".equals(state.get("state"))
                || !(task.get("metadata") instanceof Map<?,?> metadata) || metadata.containsKey("procedure") || metadata.containsKey("procedureError")) return;
        var args = jdbc.sql("select s.arguments_json from browser_agent_task b join assistant_plan_step s on s.id=b.step_id where b.task_id=:id and b.owner_id=:owner")
                .param("id",id).param("owner",ActorContext.current()).query(String.class).optional();
        if (args.isEmpty()) return; // Replays do not create another implementation.
        var meta = new LinkedHashMap<String,Object>(); metadata.forEach((k,v)->meta.put(k.toString(),v));
        try {
            var bundle = client.post().uri("/procedures/export").header("Authorization", "Bearer " + token).header("X-Finflow-Owner",ActorContext.current())
                    .bodyValue(Map.of("taskId",id,"goal",json.readValue(args.get(),Map.class))).retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String,Object>>(){}).timeout(Duration.ofSeconds(20)).block();
            if (bundle == null) return;
            meta.put("procedure",Map.of("hash",bundle.get("hash"),"parameters",bundle.get("parameters"),"engine",bundle.get("engine"), "allowed_domains", bundle.getOrDefault("allowed_domains", List.of())));
            var artifacts = new ArrayList<Object>((List<?>)task.getOrDefault("artifacts",List.of()));
            artifacts.add(Map.of("artifactId","procedure","name","固定浏览器脚本","parts",List.of(Map.of("kind","file","file",Map.of("name","browser-procedure.json","mimeType","application/json","bytes",Base64.getEncoder().encodeToString(json.writeValueAsString(bundle).getBytes(java.nio.charset.StandardCharsets.UTF_8)))))));
            task.put("artifacts",artifacts);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.UnprocessableEntity error) {
            meta.put("procedureError","历史操作不能直接固化，请检查操作记录并整理实现");
        } catch (RuntimeException error) { task.put("procedureError","脚本暂未归档，独立浏览器服务恢复后重试"); return; }
        task.put("metadata",meta);
        jdbc.sql("update browser_agent_task set last_result=:result where task_id=:id and owner_id=:owner")
                .param("result",json.writeValueAsString(task)).param("id",id).param("owner",ActorContext.current()).update();
    }

    private synchronized Map<String, Object> retain(String id, Map<String, Object> task, Map<?, ?> procedure) {
        session(id);
        var sourceRun = jdbc.sql("select r.id from browser_agent_task b join assistant_plan_step s on s.id=b.step_id join assistant_run r on r.plan_id=s.plan_id where b.task_id=:id order by r.created_at desc limit 1")
                .param("id", id).query(String.class).single();
        var project = fixedWorks.projectForRun(sourceRun);
        var saved = jdbc.sql("select * from browser_procedure_artifact where task_id=:id").param("id", id)
                .query((rs, row) -> Map.<String,Object>of("scriptResourceId", rs.getString("script_resource_id"), "parametersResourceId", rs.getString("parameters_resource_id"), "resultResourceId", rs.getString("result_resource_id"))).optional();
        Map<String, Object> refs;
        if (saved.isPresent()) refs = saved.get();
        else {
            Map<?, ?> script = null;
            if (task.get("artifacts") instanceof List<?> artifacts) for (var value : artifacts)
                if (value instanceof Map<?, ?> artifact && artifact.get("parts") instanceof List<?> parts) for (var part : parts)
                    if (part instanceof Map<?, ?> p && p.get("file") instanceof Map<?, ?> f && "browser-procedure.json".equals(f.get("name"))) script = f;
            if (script == null) throw new IllegalStateException("浏览器任务缺少脚本产物");
            var code = knowledge.importBytes(project, "浏览器固定脚本-" + id.substring(0, 8) + ".json", "application/json", Base64.getDecoder().decode(script.get("bytes").toString()));
            var params = knowledge.importBytes(project, "浏览器执行输入-" + id.substring(0, 8) + ".json", "application/json", json.writeValueAsString(procedure.get("parameters")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var report = knowledge.importBytes(project, "浏览器操作结果-" + id.substring(0, 8) + ".md", "text/markdown", summary(task).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.sql("insert into browser_procedure_artifact(task_id,script_resource_id,parameters_resource_id,result_resource_id,content_hash) values(:id,:script,:parameters,:result,:hash)")
                    .param("id", id).param("script", code.id()).param("parameters", params.id()).param("result", report.id()).param("hash", procedure.get("hash")).update();
            refs = Map.of("scriptResourceId", code.id(), "parametersResourceId", params.id(), "resultResourceId", report.id());
            var raw = jdbc.sql("select stages_json from agent_work where run_id=:run").param("run", sourceRun).query(String.class).optional().orElse("[]");
            var stages = new ArrayList<>(Arrays.asList(json.readValue(raw, AgentWorkService.Stage[].class)));
            var outputs = List.of(new AgentWorkService.ResourceRef(code.id(), code.currentVersion(), "INTERNAL"), new AgentWorkService.ResourceRef(report.id(), report.currentVersion(), "PRIMARY"));
            if (stages.isEmpty()) stages.add(new AgentWorkService.Stage("browser-" + id.substring(0,8), "浏览器操作结果", "SUCCEEDED", "操作完成，固定脚本已保留；重放尚待验证", "",
                    List.of(new AgentWorkService.ResourceRef(params.id(), params.currentVersion())), outputs));
            else if (stages.size() == 1) {
                var previous = stages.getLast();
                var mergedOutputs = new ArrayList<>(previous.outputs()); mergedOutputs.addAll(outputs);
                var mergedInputs = new ArrayList<>(previous.inputs()); mergedInputs.add(new AgentWorkService.ResourceRef(params.id(), params.currentVersion()));
                stages.set(0, new AgentWorkService.Stage(previous.id(), previous.title(), "SUCCEEDED", "操作完成，固定脚本已保留；重放尚待验证", "", mergedInputs, mergedOutputs));
            }
            var updated = jdbc.sql("update agent_work set stages_json=:stages where run_id=:run")
                    .param("run", sourceRun).param("stages", json.writeValueAsString(stages)).update();
            if (updated == 0) jdbc.sql("insert into agent_work(run_id,stages_json) values(:run,:stages)")
                    .param("run", sourceRun).param("stages", json.writeValueAsString(stages)).update();
        }
        technicalArtifacts.pin(project, "BROWSER", id, "@script", refs.get("scriptResourceId").toString(), 1);
        var result = new LinkedHashMap<>(refs);
        try { result.put("definitionId", fixedWorks.retainBrowser(sourceRun, id, procedure.get("hash").toString(), refs.get("parametersResourceId").toString()).id()); }
        catch (IllegalStateException error) { result.put("notice", error.getMessage()); }
        return result;
    }

    private String summary(Map<String, Object> task) {
        if (task.get("status") instanceof Map<?, ?> status && status.get("message") instanceof Map<?, ?> message && message.get("parts") instanceof List<?> parts)
            return parts.stream().filter(Map.class::isInstance).map(p -> Objects.toString(((Map<?, ?>)p).get("text"), "")).collect(java.util.stream.Collectors.joining("\n"));
        return "浏览器任务结果待核验";
    }

    public String replay(String source, String hash, Map<String, Object> parameters, String sessionId, String requestId, Runnable check) {
        return replay(source, hash, parameters, sessionId, requestId, check, task -> { });
    }
    public String replay(String source, String hash, Map<String, Object> parameters, String sessionId, String requestId,
                         Runnable check, java.util.function.Consumer<Map<String, Object>> observer) {
        var original = rawStatus(source); // Owner check also applies to old artifacts.
        var sourceSession = session(source);
        if (!sourceSession.equals(sessionId)) throw new IllegalArgumentException("浏览器实现不属于当前工作");
        var meta = (Map<?, ?>) original.get("metadata");
        if (!(meta.get("procedure") instanceof Map<?, ?> procedure) || !hash.equals(procedure.get("hash"))) throw new IllegalArgumentException("浏览器实现版本已变化");
        var savedScript = technicalArtifacts.list("BROWSER", source);
        if (savedScript.isEmpty()) {
            // Migrate legacy resources explicitly, never execute a transient worker file.
            retain(source, original, procedure);
            savedScript = technicalArtifacts.list("BROWSER", source);
        }
        var script = savedScript.stream().filter(a -> a.part().equals("@script")).findFirst()
                .orElseThrow(() -> new IllegalStateException("浏览器脚本尚未归档，不能开始执行"));
        var bundle = json.readValue(technicalArtifacts.read(script), Map.class);
        if (!hash.equals(bundle.get("hash"))) throw new IllegalStateException("归档浏览器脚本版本不匹配");
        var access = new LinkedHashMap<String, Object>();
        access.put("url", bundle.get("url"));
        Object domains = bundle.get("allowed_domains");
        if (!(domains instanceof List<?>)) throw new IllegalStateException("工序缺少网站范围，不能绕过网站授权执行；请检查技术产物");
        access.put("allowed_domains", domains);
        authorizations.requireDomains(access);
        var goal = Map.of("url", bundle.get("url"), "goal", "执行已固化的浏览器操作", "replay_source", source, "replay_hash", hash, "parameters", parameters, "replay_bundle", bundle);
        var task = rpc("message/send", Map.of("message", Map.of("kind", "message", "role", "user", "messageId", requestId,
                "parts", List.of(Map.of("kind", "text", "text", "执行固定浏览器脚本")), "metadata", Map.of("approved", true, "requestKey", requestId, "browserGoal", goal))));
        var id = task.get("id").toString();
        if (jdbc.sql("select count(*) from browser_agent_task where task_id=:id").param("id", id).query(Integer.class).single() == 0)
            jdbc.sql("insert into browser_agent_task(task_id,step_id,owner_id,session_id,last_result) values(:id,:step,:owner,:session,:result)")
                    .param("id", id).param("step", requestId).param("owner", ActorContext.current()).param("session", sessionId).param("result", json.writeValueAsString(task)).update();
        try {
            while (true) {
                check.run(); task = rawStatus(id);
                observer.accept(task);
                var state = ((Map<?, ?>)task.get("status")).get("state");
                if ("completed".equals(state)) return summary(task);
                if (Set.of("failed", "canceled", "rejected").contains(state)) throw new IllegalStateException(summary(task));
                Thread.sleep(1500);
            }
        } catch (InterruptedException error) {
            Thread.interrupted();
            try { stopReplay(id, "工序执行线程被中断"); }
            finally { Thread.currentThread().interrupt(); }
            throw new java.util.concurrent.CancellationException("浏览器执行已中止");
        } catch (RuntimeException error) {
            var state = ((Map<?, ?>)task.get("status")).get("state");
            if (!Set.of("completed", "failed", "canceled", "rejected").contains(state)) {
                try { stopReplay(id, "执行协调异常：" + error.getClass().getSimpleName()); } catch (RuntimeException ignored) { }
            }
            throw error;
        }
    }

    private void stopReplay(String id, String reason) {
        rpc("tasks/cancel", Map.of("id", id));
        events.publish(session(id), null, "browser.agent.interrupted", Map.of("taskId", id, "message", reason));
    }

    private Map<String, Object> rawStatus(String id) {
        session(id);
        var saved = jdbc.sql("select last_result from browser_agent_task where task_id=:id and owner_id=:owner")
                .param("id", id).param("owner", ActorContext.current()).query(String.class).single();
        var cached = json.readValue(saved, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        if (cached.get("status") instanceof Map<?, ?> status
                && Set.of("completed", "failed", "canceled", "rejected").contains(status.get("state"))) return cached;
        var task = rpc("tasks/get", Map.of("id", id));
        jdbc.sql("update browser_agent_task set last_result=:result where task_id=:id and owner_id=:owner")
                .param("result", json.writeValueAsString(task)).param("id", id).param("owner", ActorContext.current()).update();
        return task;
    }

    public record Download(String name, byte[] bytes) { }
    public Download download(String id, int artifactIndex, int partIndex) {
        var task = rawStatus(id);
        if (!(task.get("artifacts") instanceof List<?> artifacts) || artifactIndex < 0 || artifactIndex >= artifacts.size())
            throw new IllegalArgumentException("文件不存在");
        var artifact = (Map<?, ?>) artifacts.get(artifactIndex);
        var parts = (List<?>) artifact.get("parts");
        if (partIndex < 0 || partIndex >= parts.size()) throw new IllegalArgumentException("文件不存在");
        var part = (Map<?, ?>) parts.get(partIndex);
        if (!(part.get("file") instanceof Map<?, ?> file) || !(file.get("bytes") instanceof String bytes))
            throw new IllegalArgumentException("文件不存在");
        return new Download(Objects.toString(file.get("name"), "download"), Base64.getDecoder().decode(bytes));
    }

    public List<Map<String, Object>> conversationTasks(String sessionId) {
        // Only supply persisted task references. The agent explicitly refreshes status when needed.
        return jdbc.sql("select task_id,last_result from browser_agent_task where session_id=:session and owner_id=:owner order by created_at desc limit 20")
                .param("session", sessionId).param("owner", ActorContext.current()).query((rs, row) -> {
                    var cached = json.readValue(rs.getString("last_result"), Map.class);
                    var metadata = cached.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
                    var value = new LinkedHashMap<String, Object>();
                    value.put("task_id", rs.getString("task_id"));
                    value.put("status", cached.getOrDefault("status", Map.of()));
                    value.put("procedureState", metadata.get("procedureState"));
                    value.put("procedureError", metadata.get("procedureError"));
                    value.put("cached", true);
                    return (Map<String, Object>) value;
                }).list();
    }

    public List<Map<String, Object>> list(String sessionId) {
        var ids = jdbc.sql("select task_id from browser_agent_task where session_id=:session and owner_id=:owner order by created_at desc")
                .param("session", sessionId).param("owner", ActorContext.current()).query(String.class).list();
        return ids.stream().map(this::status).toList();
    }

    public Map<String, Object> control(String id, String action, int promptId) {
        var session = session(id);
        Map<String, Object> result;
        String kind = "INPUT";
        if (Set.of("resume", "approve", "focus").contains(action)) {
            var current = rawStatus(id);
            var metadata = current.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
            if (metadata.get("interaction") instanceof Map<?, ?> pending) kind = Objects.toString(pending.get("kind"), "INPUT");
            if (!"focus".equals(action) && "APPROVAL".equals(kind) != "approve".equals(action))
                throw new IllegalStateException("请使用当前待办对应的确认按钮，普通继续不能代替提交审批");
            result = rpc("message/send", Map.of("message", Map.of("kind", "message", "role", "user",
                    "messageId", UUID.randomUUID().toString(), "taskId", id,
                    "parts", List.of(Map.of("kind", "text", "text", "人工操作已完成，继续原任务")),
                    "metadata", Map.of("promptId", promptId, "command", action))));
        } else if ("cancel".equals(action)) result = rpc("tasks/cancel", Map.of("id", id));
        else throw new IllegalArgumentException("不支持的浏览器任务操作");
        var message = "approve".equals(action) ? "用户已确认本次提交内容" : "resume".equals(action) ? "用户已完成当前人工操作" : "focus".equals(action) ? "用户已打开操作窗口" : "用户已中止浏览器任务";
        events.publish(session, null, "browser.agent." + action, Map.of("taskId", id, "promptId", promptId, "interactionKind", kind, "message", message));
        jdbc.sql("insert into browser_authorization_audit(id,owner_id,grant_id,step_id,event,label,created_at) values(:id,:owner,'',:task,:event,:label,:now)")
                .param("id", UUID.randomUUID().toString()).param("owner", ActorContext.current()).param("task", id)
                .param("event", "approve".equals(action) ? "SUBMISSION_APPROVED" : "BROWSER_HUMAN_CONTROL")
                .param("label", message + " · " + kind + " · 待办 " + promptId).param("now", java.time.Instant.now()).update();
        return result;
    }
}
