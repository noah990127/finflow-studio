package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Service
public class AgentWorkService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final WorkspaceResourceService workspace;
    private final FixedWorkService fixedWorks;

    public AgentWorkService(JdbcClient jdbc, ObjectMapper json, ProjectService projects, WorkspaceResourceService workspace, FixedWorkService fixedWorks) {
        this.jdbc = jdbc; this.json = json; this.projects = projects; this.workspace = workspace; this.fixedWorks = fixedWorks;
    }

    public record ResourceRef(String resourceId, int version, String role) {
        public ResourceRef(String resourceId, int version) { this(resourceId, version, "PRIMARY"); }
        public ResourceRef { role = role == null ? "PRIMARY" : role; }
    }
    public record Stage(String id, String title, String status, String summary, String question,
                        List<ResourceRef> inputs, List<ResourceRef> outputs) { }
    public record Work(String id, String sessionId, String goal, String status, String resultSummary,
                       String title,
                       Instant createdAt, List<Stage> stages, Map<String, Object> result) { }
    public record Review(String resourceId, int version, String reviewedBy, Instant reviewedAt) { }

    public List<Stage> stages(String runId) {
        var stages = jdbc.sql("select stages_json from agent_work where run_id = :run").param("run", runId)
                .query(String.class).optional().map(value -> Arrays.asList(json.readValue(value, Stage[].class))).orElse(List.of());
        var projectId = jdbc.sql("""
                select c.project_id from assistant_run r join assistant_plan p on p.id = r.plan_id
                join assistant_context_snapshot c on c.id = p.context_snapshot_id where r.id = :run
                """).param("run", runId).query(String.class).single();
        return projectStages(stages, workspace.get(fixedWorks.projectForRun(runId)).resources());
    }

    static List<Stage> projectStages(List<Stage> stages, List<com.finflow.studio.workspace.WorkspaceModels.Resource> resources) {
        var versions = new HashMap<String, Integer>();
        resources.forEach(r -> versions.put(r.id(), r.currentVersion()));
        var invalid = new HashSet<String>();
        var staleStages = new HashSet<String>();
        boolean changed;
        do {
            changed = false;
            for (var stage : stages) {
                boolean stale = "STALE".equals(stage.status()) || java.util.stream.Stream.concat(stage.inputs().stream(), stage.outputs().stream())
                        .anyMatch(ref -> !Objects.equals(versions.get(ref.resourceId()), ref.version()) || invalid.contains(ref.resourceId()));
                if (stale && staleStages.add(stage.id())) {
                    stage.outputs().forEach(ref -> invalid.add(ref.resourceId())); changed = true;
                }
            }
        } while (changed);
        return stages.stream().map(s -> staleStages.contains(s.id())
                ? new Stage(s.id(), s.title(), "STALE", s.summary(), s.question(), s.inputs(), s.outputs()) : s).toList();
    }

    @SuppressWarnings("unchecked")
    public List<Work> list(String projectId) {
        projects.get(projectId);
        var resources = workspace.get(projectId).resources();
        var runs = jdbc.sql("""
                select r.*, coalesce((select p0.goal from assistant_plan p0
                    join assistant_context_snapshot c0 on c0.id = p0.context_snapshot_id
                    where p0.session_id = r.session_id and c0.project_id = :project
                    order by p0.created_at asc limit 1), p.goal) as goal, t.title as work_title, w.stages_json from assistant_run r
                join assistant_plan p on p.id = r.plan_id
                join assistant_context_snapshot c on c.id = p.context_snapshot_id
                join assistant_session s on s.id = r.session_id
                left join agent_work w on w.run_id = r.id
                left join agent_work_title t on t.project_id = :project and t.session_id = r.session_id
                where (c.project_id = :project or s.project_id = :project) and s.default_user_id = :actor
                order by r.created_at desc
                """).param("project", projectId).param("actor", ActorContext.current())
                .query((rs, row) -> new Work(rs.getString("id"), rs.getString("session_id"), rs.getString("goal"),
                        rs.getString("status"), rs.getString("result_summary"), rs.getString("work_title"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("stages_json") == null ? List.of() : projectStages(Arrays.asList(json.readValue(rs.getString("stages_json"), Stage[].class)), resources),
                        json.readValue(rs.getString("effects_json"), Map.class))).list();
        var latest = new LinkedHashMap<String, Work>();
        runs = runs.stream().filter(r -> projectId.equals(fixedWorks.projectForRun(r.id()))).toList();
        for (var run : runs) latest.putIfAbsent(run.sessionId(), run);
        var projectRuns = runs;
        return latest.values().stream().map(current -> {
            var history = projectRuns.stream().filter(r -> r.sessionId().equals(current.sessionId())).toList();
            var goal = history.getLast().goal();
            var stages = current.stages();
            if (stages.isEmpty()) {
                stages = history.stream().filter(r -> !r.stages().isEmpty()).findFirst().map(r -> r.stages().stream()
                        .map(s -> new Stage(s.id(), s.title(), "PENDING", "沿用历史工序，待核对原执行结果", "", s.inputs(), s.outputs())).toList()).orElse(List.of());
            }
            var title = current.title();
            if (title == null || title.startsWith("新对话") || title.length() > 40) title = stages.isEmpty() ? "未命名工作" : stages.getLast().title();
            return fixedWorks.overlay(projectId, browserOutcome(new Work(current.id(), current.sessionId(), goal, current.status(), current.resultSummary(), title, current.createdAt(), stages, current.result())));
        }).toList();
    }

    private Work browserOutcome(Work work) {
        if (!"DELEGATED".equals(work.result().get("workStatus"))
                || !(work.result().get("browserAgentTask") instanceof Map<?, ?> reference)) return work;
        var cached = jdbc.sql("select last_result from browser_agent_task where task_id=:id and session_id=:session and owner_id=:owner")
                .param("id", reference.get("id")).param("session", work.sessionId()).param("owner", ActorContext.current())
                .query(String.class).optional();
        if (cached.isEmpty()) return work;
        var outputs = jdbc.sql("select resource_id,resource_version from browser_task_artifact where task_id=:id order by part_key")
                .param("id", reference.get("id")).query((rs, row) -> new ResourceRef(rs.getString(1), rs.getInt(2), "SUPPORTING")).list();
        return projectBrowserOutcome(work, json.readValue(cached.get(), Map.class), outputs);
    }

    static Work projectBrowserOutcome(Work work, Map<String, Object> task, List<ResourceRef> outputs) {
        if (!(task.get("status") instanceof Map<?, ?> status)) return work;
        var state = Objects.toString(status.get("state"), "");
        if (!Set.of("completed", "failed", "canceled", "rejected").contains(state)) return work;
        var meta = task.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
        var succeeded = "completed".equals(state);
        var outcome = succeeded ? "SUCCEEDED" : "canceled".equals(state) ? "CANCELED" : "FAILED";
        var issue = Objects.toString(meta.get("procedureError"), "");
        var summary = succeeded ? issue.isBlank() ? "本次网页操作已完成；固定实现状态请查看技术详情"
                : "本次网页操作已完成；工序尚未固化：" + issue : "网页操作已结束但未完成，请查看对话中的任务结果";
        var stages = work.stages();
        // One delegated business stage represents the task. Do not infer success of unrelated stages.
        if (stages.size() == 1) {
            var stage = stages.getFirst();
            var merged = new ArrayList<>(stage.outputs());
            for (var ref : outputs) if (merged.stream().noneMatch(r -> r.resourceId().equals(ref.resourceId()))) merged.add(ref);
            stages = List.of(new Stage(stage.id(), stage.title(), outcome, summary, "", stage.inputs(), merged));
        }
        var result = new LinkedHashMap<>(work.result());
        var taskSummary = new LinkedHashMap<>(task);
        taskSummary.remove("artifacts");
        taskSummary.remove("history");
        result.put("browserAgentTask", taskSummary);
        result.put("workStatus", succeeded ? "BROWSER_COMPLETED" : "BROWSER_FAILED");
        result.put("procedureState", Objects.toString(meta.get("procedureState"), "NOT_GENERATED"));
        return new Work(work.id(), work.sessionId(), work.goal(), outcome, summary, work.title(), work.createdAt(), stages, result);
    }

    @Transactional
    public List<Stage> update(String runId, Map<String, Object> arguments) {
        // Lock the run so cancel/completion cannot race a late plan update.
        var projectId = jdbc.sql("""
                select c.project_id from assistant_run r join assistant_plan p on p.id = r.plan_id
                join assistant_context_snapshot c on c.id = p.context_snapshot_id
                join assistant_session s on s.id = r.session_id
                where r.id = :run and r.status = 'RUNNING' and s.default_user_id = :actor for update
                """).param("run", runId).param("actor", ActorContext.current()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("工作不存在或已结束"));
        projectId = fixedWorks.projectForRun(runId);
        var decoded = json.convertValue(arguments.get("stages"), Stage[].class);
        if (decoded == null) throw new IllegalArgumentException("工序列表不能为空");
        var stages = Arrays.asList(decoded);
        validateStages(stages);
        if (arguments.get("title") instanceof String title && !title.isBlank()) {
            var session = jdbc.sql("select session_id from assistant_run where id = :id").param("id", runId).query(String.class).single();
            rename(projectId, session, title);
        }
        var resources = workspace.get(projectId).resources();
        for (var stage : stages) {
            var refs = new ArrayList<>(stage.inputs()); refs.addAll(stage.outputs());
            for (var ref : refs) {
                if (resources.stream().noneMatch(r -> r.id().equals(ref.resourceId()) && r.currentVersion() == ref.version()))
                    throw new IllegalArgumentException("工序引用的资料不在当前项目或版本已变化");
            }
        }
        var value = json.writeValueAsString(stages);
        var changed = jdbc.sql("update agent_work set stages_json = :stages where run_id = :run")
                .param("stages", value).param("run", runId).update();
        if (changed == 0) jdbc.sql("insert into agent_work(run_id, stages_json) values (:run, :stages)")
                .param("stages", value).param("run", runId).update();
        return stages;
    }

    @Transactional
    public void rename(String projectId, String sessionId, String title) {
        projects.get(projectId);
        if (title == null || title.isBlank() || title.length() > 40) throw new IllegalArgumentException("工作名称需为1到40个字符");
        if (list(projectId).stream().noneMatch(w -> w.sessionId().equals(sessionId))) throw new IllegalArgumentException("工作不在当前项目");
        jdbc.sql("select id from assistant_session where id = :id and default_user_id = :actor for update")
                .param("id", sessionId).param("actor", ActorContext.current()).query(String.class).single();
        var changed = jdbc.sql("update agent_work_title set title = :title where project_id = :project and session_id = :session")
                .param("title", title.trim()).param("project", projectId).param("session", sessionId).update();
        if (changed == 0) jdbc.sql("insert into agent_work_title(project_id, session_id, title) values (:project, :session, :title)")
                .param("project", projectId).param("session", sessionId).param("title", title.trim()).update();
    }

    static void validateStages(List<Stage> stages) {
        if (stages == null || stages.isEmpty() || stages.size() > 30) throw new IllegalArgumentException("工序数量应为1到30");
        var ids = new HashSet<String>();
        for (var s : stages) {
            if (s == null || s.id() == null || s.id().isBlank() || s.id().length() > 80 || !ids.add(s.id())
                    || s.title() == null || s.title().isBlank() || s.title().length() > 120
                    || !Set.of("PENDING", "RUNNING", "WAITING_INPUT", "SUCCEEDED", "FAILED", "STALE").contains(Objects.toString(s.status(), ""))
                    || s.inputs() == null || s.outputs() == null || s.inputs().size() + s.outputs().size() > 200)
                throw new IllegalArgumentException("工序名称、状态、唯一标识和资源列表必须有效");
            if ("WAITING_INPUT".equals(s.status()) && (s.question() == null || s.question().isBlank()))
                throw new IllegalArgumentException("等待输入的工序需要具体问题");
            if ("SUCCEEDED".equals(s.status()) && s.outputs().isEmpty() && (s.summary() == null || s.summary().isBlank()))
                throw new IllegalArgumentException("完成的工序必须有产物或可检查的文字结果");
            for (var ref : java.util.stream.Stream.concat(s.inputs().stream(), s.outputs().stream()).toList())
                if (ref == null || ref.resourceId() == null || ref.resourceId().isBlank() || ref.version() < 1
                        || !Set.of("PRIMARY", "SUPPORTING", "INTERNAL").contains(ref.role()))
                    throw new IllegalArgumentException("产物引用必须包含真实ID和版本");
        }
    }

    public List<Review> reviews(String projectId) {
        projects.get(projectId);
        return jdbc.sql("select * from artifact_review where project_id = :project and reviewed_by = :actor")
                .param("project", projectId).param("actor", ActorContext.current())
                .query((rs, i) -> new Review(rs.getString("resource_id"), rs.getInt("resource_version"),
                        rs.getString("reviewed_by"), rs.getTimestamp("reviewed_at").toInstant())).list();
    }

    @Transactional
    public Review review(String projectId, ResourceRef ref) {
        projects.get(projectId);
        var resource = workspace.get(projectId).resources().stream().filter(r -> r.id().equals(ref.resourceId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("产物不在当前项目"));
        if (resource.currentVersion() != ref.version()) throw new IllegalArgumentException("产物已更新，请检查最新版本");
        var now = Instant.now();
        jdbc.sql("""
                insert into artifact_review(id, project_id, resource_id, resource_version, reviewed_by, reviewed_at)
                values (:id, :project, :resource, :version, :actor, :now)
                """).param("id", UUID.randomUUID().toString()).param("project", projectId)
                .param("resource", ref.resourceId()).param("version", ref.version()).param("actor", ActorContext.current()).param("now", now).update();
        return new Review(ref.resourceId(), ref.version(), ActorContext.current(), now);
    }
}
