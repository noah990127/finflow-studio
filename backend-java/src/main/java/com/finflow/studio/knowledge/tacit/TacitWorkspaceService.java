package com.finflow.studio.knowledge.tacit;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TacitWorkspaceService {
    private static final Set<String> READ = Set.of("workspace", "preview_source", "read_section", "read_fields", "read_clauses", "search_context");
    private static final Set<String> LEDGER_READ = Set.of("preview_source", "read_section", "read_fields", "read_clauses");
    private static final Set<String> HUMAN_DECISION = Set.of("review_flow", "review_candidate", "review_candidate_item",
            "review_scenario", "review_alignment", "review_cq", "confirm_item", "answer_gap");
    private static final Map<String, Set<String>> TASKS = Map.of(
            "organize_business", Set.of("read_source", "extract", "save_batch", "finalize", "align_policies"),
            "clarify_gaps", Set.of("read_source", "extract", "save_batch", "finalize", "align_policies"),
            "generate_flow", Set.of("read_source", "generate_flow"),
            "build_ontology", Set.of("read_source", "build_candidate", "edit_candidates", "check_candidates", "submit_candidates"),
            "validate_ontology", Set.of("read_source", "validate_ontology", "propose_scenarios"),
            "generate_cqs", Set.of("read_source", "generate_cqs"), "discuss", Set.of());
    private static final Set<String> PUBLIC_STATE = Set.of("spec", "artifacts", "issues", "coverage", "extraction", "interviews");
    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final WorkerClient worker;
    private final TacitCatalog catalog;
    private final TransactionTemplate tx;

    public TacitWorkspaceService(JdbcClient jdbc, ProjectService projects, WorkerClient worker,
                                 TacitCatalog catalog, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.worker = worker;
        this.catalog = catalog;
        this.tx = tx;
    }

    public Map<String, Object> workspace(String projectId) {
        // A parser can finish between reading the version stamp and saving its invalidation.
        // Refreshing a view may safely repeat that synchronization. User commands keep their original CAS.
        for (int attempt = 1; ; attempt++) {
            try {
                return project(current(projectId));
            } catch (WorkspaceConflict race) {
                if (attempt >= 3) throw race;
            }
        }
    }

    public Map<String, Object> executeAgent(String projectId, String task, String action, long revision,
                                           String requestId, Map<String, Object> payload) {
        var allowed = TASKS.get(task);
        if (allowed == null || !(READ.contains(action) || allowed.contains(action))) {
            throw new IllegalArgumentException("本轮任务无权执行该知识操作");
        }
        return execute(projectId, action, revision, requestId, payload, "agent:" + task, false);
    }

    public Map<String, Object> executeHuman(String projectId, String action, long revision,
                                           String requestId, Map<String, Object> payload) {
        return executeHuman(projectId, action, revision, requestId, payload, false);
    }

    public Map<String, Object> executeHuman(String projectId, String action, long revision,
                                           String requestId, Map<String, Object> payload, boolean compact) {
        if (action == null || !(Set.of("select_sources", "add_note", "remove_item").contains(action)
                || READ.contains(action) || HUMAN_DECISION.contains(action)
                || TASKS.values().stream().anyMatch(actions -> actions.contains(action)))) {
            throw new IllegalArgumentException("未知的知识操作");
        }
        if (HUMAN_DECISION.contains(action) && !ActorContext.isBound()) {
            throw new SecurityException("请使用项目所有者账户登录后审核");
        }
        return execute(projectId, action, revision, requestId, payload, "human", compact);
    }

    private Map<String, Object> execute(String id, String action, long revision, String requestId,
                                        Map<String, Object> payload, String origin, boolean compact) {
        if (revision < 0 || requestId == null || !requestId.matches("[a-zA-Z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("请提供版本号与稳定的请求标识");
        var args = payload == null ? Map.<String, Object>of() : payload;
        var hash = TacitJson.hash(TacitJson.write(List.of(origin, ActorContext.current(), revision, action, args)));
        var before = current(id);
        var existing = receipt(id, requestId, hash, before, compact);
        if (existing != null) return existing;
        if (before.revision != revision) throw conflict();
        if (action.equals("workspace")) return reply(before, Map.of(), compact);
        var selecting = action.equals("select_sources");
        var selected = selecting ? selection(args) : before.selected;
        var files = catalog.files(id, selected, false);
        if (selecting && files.stream().anyMatch(f -> "MISSING".equals(f.status()))) {
            throw new IllegalArgumentException("所选资料不属于当前项目或已删除");
        }
        if (!selecting && !before.stamp.equals(catalog.stamp(files))) throw conflict();
        var sources = catalog.load(id, files);
        var all = withNotes(sources, before.state);
        var input = new LinkedHashMap<>(before.state);
        input.remove("_file_catalog");
        if (LEDGER_READ.contains(action)) input.remove("artifacts");
        input.put("source_ids", selected);
        String transitionAction = selecting ? "sync_sources" : action;
        if (action.equals("save_batch") && origin.startsWith("agent:")) transitionAction = "save_agent_batch";
        var response = transition(input, transitionAction, selecting ? Map.of() : args, all);
        var result = TacitJson.map(response.get("result"));
        if (READ.contains(action) || action.equals("check_candidates")) {
            if (current(id).revision != before.revision) throw conflict();
            return reply(before, result, compact);
        }
        var state = new LinkedHashMap<>(TacitJson.map(response.get("state")));
        state.put("_file_catalog", summaries(sources));
        var after = new Record(before.revision + 1, selected, catalog.stamp(files), state);
        return tx.execute(ignored -> {
            lock(id);
            var actual = read(id);
            var replay = receipt(id, requestId, hash, actual, compact);
            if (replay != null) return replay;
            store(id, before, after);
            jdbc.sql("""
                    insert into tacit_command_receipt(project_id,request_id,request_hash,committed_revision,
                        result_json,actor_id,action,created_at)
                    values (:p,:id,:hash,:rev,:result,:actor,:action,:now)
                    """).param("p", id).param("id", requestId).param("hash", hash).param("rev", after.revision)
                    .param("result", TacitJson.write(result)).param("actor", ActorContext.current())
                    .param("action", action).param("now", Instant.now()).update();
            if (HUMAN_DECISION.contains(action)) appendReview(id, action, args, result, before, after);
            return reply(after, result, compact);
        });
    }

    // Detect upstream edits on the first read as well as before every mutation.
    // Long model calls run outside DB transactions; both the source stamp and CAS are checked at commit.
    private Record current(String id) {
        projects.get(id);
        var before = read(id);
        var files = catalog.files(id, before.selected, false);
        var stamp = catalog.stamp(files);
        if (stamp.equals(before.stamp)) return before;
        var sources = catalog.load(id, files);
        var input = new LinkedHashMap<>(before.state);
        input.remove("_file_catalog");
        input.put("source_ids", before.selected);
        var synced = transition(input, "sync_sources", Map.of(), withNotes(sources, before.state));
        var state = new LinkedHashMap<>(TacitJson.map(synced.get("state")));
        state.put("_file_catalog", summaries(sources));
        var after = new Record(before.revision + 1, before.selected, stamp, state);
        return tx.execute(ignored -> {
            lock(id);
            var actual = read(id);
            if (actual.revision != before.revision && actual.stamp.equals(stamp)) return actual;
            store(id, before, after);
            return after;
        });
    }

    private Map<String, Object> transition(Map<String, Object> state, String action, Map<String, Object> args,
                                          List<Map<String, Object>> sources) {
        return worker.tacitCommand(Map.of("state", state, "action", action, "payload", args, "catalog", sources,
                "actor", ActorContext.current(), "result_only", READ.contains(action) || action.equals("check_candidates")));
    }

    private Record read(String project) {
        return jdbc.sql("select * from tacit_workspace where project_id=:p").param("p", project)
                .query((rs, n) -> new Record(rs.getLong("revision"), TacitJson.strings(rs.getString("selected_json")),
                        rs.getString("source_stamp"), TacitJson.object(rs.getString("state_json")))).optional()
                .orElseGet(() -> new Record(0L, List.of(), catalog.stamp(List.of()), Map.of()));
    }

    private void lock(String project) {
        jdbc.sql("select id from project where id=:p and owner_id=:owner and deleted=false for update")
                .param("p", project).param("owner", ActorContext.current()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
    }

    private void store(String project, Record before, Record after) {
        if (!after.stamp.equals(catalog.stamp(catalog.files(project, after.selected, true)))) throw conflict();
        if (before.revision == 0) {
            if (read(project).revision != 0) throw conflict();
            jdbc.sql("""
                    insert into tacit_workspace(project_id,revision,selected_json,source_stamp,state_json)
                    values (:p,:r,:s,:stamp,:state)
                    """).param("p", project).param("r", after.revision).param("s", TacitJson.write(after.selected))
                    .param("stamp", after.stamp).param("state", TacitJson.write(after.state)).update();
        } else {
            int changed = jdbc.sql("""
                    update tacit_workspace set revision=:r, selected_json=:s, source_stamp=:stamp,state_json=:state
                    where project_id=:p and revision=:expected
                    """).param("p", project).param("r", after.revision).param("expected", before.revision)
                    .param("s", TacitJson.write(after.selected)).param("stamp", after.stamp)
                    .param("state", TacitJson.write(after.state)).update();
            if (changed != 1) throw conflict();
        }
    }

    private Map<String, Object> receipt(String id, String key, String hash, Record current, boolean compact) {
        record Receipt(String hash, long revision, String result) {}
        var saved = jdbc.sql("""
                select request_hash, committed_revision, result_json from tacit_command_receipt
                where project_id=:id and request_id=:key
                """).param("id", id).param("key", key)
                .query((rs, n) -> new Receipt(rs.getString("request_hash"), rs.getLong("committed_revision"),
                        rs.getString("result_json"))).optional().orElse(null);
        if (saved == null) return null;
        if (!hash.equals(saved.hash())) throw new IllegalStateException("请求标识已用于其他知识操作");
        // A competing request may commit between loading the workspace and looking up the receipt.
        var latest = current.revision < saved.revision() ? current(id) : current;
        var result = saved.revision() == latest.revision ? TacitJson.object(saved.result())
                : Map.<String, Object>of("already_applied", true, "committed_revision", saved.revision());
        return reply(latest, result, compact);
    }

    private void appendReview(String project, String action, Map<String, Object> args, Map<String, Object> result,
                              Record before, Record after) {
        String target;
        String digest;
        String decision;
        Object snapshot;
        String comment = args.get("comment") instanceof String remark ? remark : "";
        if (action.equals("confirm_item")) {
            var note = userSources(after.state).getLast();
            var id = (String) args.get("item_id");
            var section = (String) args.get("section");
            var idKey = Map.of("rules", "rule_id", "processes", "process_id", "data_tables", "table_id",
                    "data_objects", "object_id").get(section);
            target = section + "/" + id;
            snapshot = findItem(TacitJson.map(after.state.get("spec")).get(section), idKey, id);
            digest = (String) TacitJson.map(note.get("confirmed_items")).get(id);
            comment = (String) note.get("text");
            decision = "confirmed";
        } else if (action.equals("answer_gap")) {
            var id = (String) args.get("gap_id");
            target = "gaps/" + id;
            snapshot = findItem(TacitJson.map(after.state.get("spec")).get("gaps"), "gap_id", id);
            digest = TacitJson.hash(TacitJson.write(snapshot));
            comment = (String) args.get("text");
            decision = (String) result.get("decision");
        } else if (action.equals("review_cq")) {
            target = (String) args.get("cq_id");
            var artifact = TacitJson.map(TacitJson.map(before.state.get("artifacts")).get("cqs"));
            snapshot = findItem(artifact.get("content"), "cq_id", target);
            digest = TacitJson.hash(TacitJson.write(snapshot));
            decision = (String) args.get("decision");
        } else if (Set.of("review_candidate_item", "review_scenario", "review_alignment").contains(action)) {
            var key = switch (action) {
                case "review_candidate_item" -> "candidate_id";
                case "review_scenario" -> "scenario_id";
                default -> "alignment_id";
            };
            var name = switch (action) {
                case "review_candidate_item" -> "candidates";
                case "review_scenario" -> "scenarios";
                default -> "alignments";
            };
            var itemId = (String) args.get(key);
            target = name + "/" + itemId;
            var original = artifactItem(before, name, key, itemId);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("before", original);
            evidence.put("after", artifactItem(after, name, key, itemId));
            if (action.equals("review_alignment")) {
                var from = (String) original.get("from_id");
                var to = (String) original.get("to_id");
                var oldRules = TacitJson.map(before.state.get("spec")).get("rules");
                evidence.put("from_rule", findItem(oldRules, "rule_id", from));
                evidence.put("to_rule", findItem(oldRules, "rule_id", to));
                if ("merge".equals(args.get("decision"))) {
                    var newRules = TacitJson.map(after.state.get("spec")).get("rules");
                    evidence.put("merged_rule", findItem(newRules, "rule_id", to));
                }
            }
            snapshot = evidence;
            digest = TacitJson.hash(TacitJson.write(snapshot));
            decision = (String) args.get("decision");
        } else {
            target = action.equals("review_flow") ? "flows" : "candidates";
            var original = TacitJson.map(TacitJson.map(before.state.get("artifacts")).get(target));
            snapshot = original.get("content");
            var artifact = TacitJson.map(TacitJson.map(after.state.get("artifacts")).get(target));
            var review = TacitJson.map(artifact.get("review"));
            digest = action.equals("review_candidate") ? TacitJson.hash(TacitJson.write(snapshot))
                    : (String) review.get("digest");
            decision = (String) review.get("decision");
        }
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalStateException("审核缺少目标版本摘要");
        jdbc.sql("""
                insert into tacit_review_event(project_id,revision,action,target_id,target_digest,target_json,sources_json,
                    decision,actor_id,comment_text,created_at)
                values(:project,:revision,:action,:target,:digest,:snapshot,:sources,:decision,:actor,:comment,:now)
                """).param("project", project).param("revision", after.revision).param("action", action)
                .param("target", target).param("digest", digest).param("snapshot", TacitJson.write(snapshot))
                .param("sources", TacitJson.write(sourceSummaries(after))).param("decision", decision)
                .param("actor", ActorContext.current()).param("comment", comment).param("now", Instant.now()).update();
    }

    private static Map<String, Object> artifactItem(Record record, String name, String key, String id) {
        var artifact = TacitJson.map(TacitJson.map(record.state.get("artifacts")).get(name));
        return findItem(artifact.get("content"), key, id);
    }

    private static Map<String, Object> findItem(Object rows, String idKey, String id) {
        if (!(rows instanceof List<?> items)) throw new IllegalStateException("缺少审核时的台账条目");
        return items.stream().map(TacitJson::map).filter(item -> id.equals(item.get(idKey))).findFirst()
                .orElseThrow(() -> new IllegalStateException("无法定位审核条目"));
    }

    public Map<String, Object> reviewSnapshot(String id, long revision) {
        projects.get(id);
        return jdbc.sql("""
                select target_json,sources_json from tacit_review_event where project_id=:id and revision=:revision
                """).param("id", id).param("revision", revision).query((rs, n) ->
                Map.<String, Object>of("target", TacitJson.value(rs.getString("target_json")),
                        "sources", TacitJson.value(rs.getString("sources_json")))).optional()
                .orElseThrow(() -> new IllegalArgumentException("审核记录不存在"));
    }

    public Map<String, Object> reviews(String id, Long beforeRevision, int limit) {
        projects.get(id);
        if (limit < 1 || limit > 100 || (beforeRevision != null && beforeRevision <= 0))
            throw new IllegalArgumentException("审核记录分页参数无效");
        var rows = jdbc.sql("""
                select revision, action, target_id, target_digest, decision, actor_id,comment_text,created_at
                from tacit_review_event where project_id=:id and revision<:before order by revision desc limit :limit
                """).param("id", id).param("before", beforeRevision == null ? Long.MAX_VALUE : beforeRevision)
                .param("limit", limit + 1).query((rs, n) -> Map.<String, Object>of(
                        "revision", rs.getLong("revision"), "action", rs.getString("action"),
                        "target_id", rs.getString("target_id"), "target_digest", rs.getString("target_digest"),
                        "decision", rs.getString("decision"), "actor", rs.getString("actor_id"),
                        "comment", rs.getString("comment_text"), "created_at", rs.getTimestamp("created_at").toInstant().toString()))
                .list();
        var response = new LinkedHashMap<String, Object>();
        response.put("items", rows.stream().limit(limit).toList());
        response.put("next_before_revision", rows.size() > limit ? rows.get(limit - 1).get("revision") : null);
        return response;
    }

    private List<String> selection(Map<String, Object> payload) {
        if (!(payload.get("source_ids") instanceof List<?> ids)) throw new IllegalArgumentException("请选择项目资料");
        var selected = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var raw : ids) {
            if (!(raw instanceof String id) || id.isBlank() || id.length() > 64 || !seen.add(id))
                throw new IllegalArgumentException("资料标识为空、重复或过长");
            selected.add(id);
        }
        return List.copyOf(selected);
    }

    private static List<Map<String, Object>> userSources(Map<String, Object> state) {
        if (state.get("user_sources") instanceof List<?> notes) return notes.stream().map(TacitJson::map).toList();
        return List.of();
    }

    private static List<Map<String, Object>> withNotes(List<Map<String, Object>> files, Map<String, Object> state) {
        var all = new ArrayList<>(files);
        all.addAll(userSources(state));
        return all;
    }

    private static List<Map<String, Object>> summaries(List<Map<String, Object>> sources) {
        return sources.stream().map(source -> {
            var result = new LinkedHashMap<String, Object>();
            for (var key : List.of("source_id", "title", "source_type", "version", "total_characters",
                    "digest", "incomplete", "reason", "author", "created_at")) {
                if (source.containsKey(key)) result.put(key, source.get(key));
            }
            if (!result.containsKey("total_characters") && source.get("text") instanceof String text)
                result.put("total_characters", text.codePointCount(0, text.length()));
            return (Map<String, Object>) result;
        }).toList();
    }

    private static List<Map<String, Object>> sourceSummaries(Record record) {
        var sources = new ArrayList<Map<String, Object>>();
        if (record.state.get("_file_catalog") instanceof List<?> saved) saved.forEach(f -> sources.add(TacitJson.map(f)));
        sources.addAll(summaries(userSources(record.state)));
        return sources;
    }

    private static Map<String, Object> project(Record record) {
        var out = new LinkedHashMap<String, Object>();
        out.put("revision", record.revision);
        out.put("source_ids", record.selected);
        out.put("catalog", sourceSummaries(record));
        for (var key : PUBLIC_STATE)
            out.put(key, record.state.getOrDefault(key, key.equals("issues") ? List.of() : Map.of()));
        return out;
    }

    private static Map<String, Object> reply(Record record, Map<String, Object> result, boolean compact) {
        return Map.of("workspace", compact ? Map.of("revision", record.revision) : project(record), "result", result);
    }

    private static final class WorkspaceConflict extends IllegalStateException {
        private WorkspaceConflict() { super("工作台版本或资料已变化，请刷新并核对后重试"); }
    }

    private static WorkspaceConflict conflict() { return new WorkspaceConflict(); }

    private record Record(long revision, List<String> selected, String stamp, Map<String, Object> state) { }
}
