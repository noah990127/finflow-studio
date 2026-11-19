package com.finflow.studio.knowledge.tacit;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class OntologyPublications {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String COLUMNS = "id,request_id,source_revision,model_digest,target_id,actor_id,status,attempt,result_json,created_at,updated_at";
    private final ProjectService projects;
    private final TacitWorkspaceService workspace;
    private final OntologyGateway gateway;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    OntologyPublications(ProjectService projects, TacitWorkspaceService workspace, OntologyGateway gateway,
                         JdbcClient jdbc, TransactionTemplate tx) {
        this.projects = projects;
        this.workspace = workspace;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private void owner(String project) {
        if (!ActorContext.isBound()) throw new SecurityException("请登录项目所有者账户");
        projects.get(project);
    }

    private void lock(String project) {
        jdbc.sql("select id from project where id=:p and owner_id=:actor and deleted=false for update")
                .param("p", project).param("actor", ActorContext.current()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
    }

    private record Row(String id, String request, long revision, String digest, String target, String actor,
                       String status, int attempt, Map<String,Object> result, Instant created, Instant updated) {
        Map<String,Object> view() {
            var row = new LinkedHashMap<String,Object>();
            row.put("id", id);
            row.put("source_revision", revision);
            row.put("model_digest", digest);
            row.put("target_id", target);
            row.put("actor_id", actor);
            row.put("status", status);
            row.put("attempt", attempt);
            row.put("created_at", created.toString());
            row.put("updated_at", updated.toString());
            row.put("message", result.getOrDefault("message", ""));
            row.put("receipt", result.getOrDefault("receipt", Map.of()));
            return row;
        }
    }
    private record Reservation(Row row, Map<String,Object> snapshot, boolean verifyOnly) {
        static Reservation existing(Row row) { return new Reservation(row, null, true); }
    }

    private static Row row(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("request_id"), rs.getLong("source_revision"),
                rs.getString("model_digest"), rs.getString("target_id"), rs.getString("actor_id"), rs.getString("status"),
                rs.getInt("attempt"), TacitJson.object(rs.getString("result_json")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private Row find(String project, String id) {
        return jdbc.sql("select " + COLUMNS + " from tacit_ontology_publication where project_id=:p and id=:id")
                .param("p", project).param("id", id).query(OntologyPublications::row).optional()
                .orElseThrow(() -> new IllegalArgumentException("发布记录不存在"));
    }

    Map<String,Object> list(String project, String before) {
        owner(project);
        String query = "select " + COLUMNS + " from tacit_ontology_publication where project_id=:p";
        if (before != null) query += " and (created_at < :time or (created_at = :time and id < :id))";
        var sql = jdbc.sql(query + " order by created_at desc,id desc limit 31").param("p", project);
        if (before != null) {
            Row cursor = find(project, before);
            sql = sql.param("time", cursor.created).param("id", cursor.id);
        }
        var rows = sql.query(OntologyPublications::row).list();
        var response = new LinkedHashMap<String,Object>();
        response.put("items", rows.stream().limit(30).map(Row::view).toList());
        response.put("next_before", rows.size() > 30 ? rows.get(29).id : null);
        response.put("target", gateway.target(project));
        return response;
    }

    Map<String,Object> snapshot(String project, String id) {
        owner(project);
        return savedSnapshot(project, id);
    }

    private Map<String,Object> savedSnapshot(String project, String id) {
        return jdbc.sql("select snapshot_json from tacit_ontology_publication where project_id=:p and id=:id")
                .param("p", project).param("id", id).query(String.class).optional().map(TacitJson::object)
                .orElseThrow(() -> new IllegalArgumentException("发布快照不存在"));
    }

    private void requireTarget(String target, Map<String,Object> config) {
        if (!Boolean.TRUE.equals(config.get("configured")) || !target.equals(config.get("target_id")))
            throw new IllegalStateException("项目的本体目标未配置或已变更，请刷新后核对");
    }

    private void ensureNoPending(String project, String target, String excluded) {
        var count = jdbc.sql("""
                select count(*) from tacit_ontology_publication where project_id=:p and target_id=:t
                  and id<>:excluded and status in ('UNCERTAIN','PUBLISHING')
                """).param("p", project).param("t", target).param("excluded", excluded).query(Long.class).single();
        if (count > 0) throw new IllegalStateException("先核对上次发布结果，再发布新版本");
    }

    Map<String,Object> publish(String project, OntologyPublicationController.Command cmd) {
        owner(project);
        var currentTarget = gateway.target(project);
        var reservation = tx.execute(ignored -> {
            lock(project);
            var existing = jdbc.sql("select " + COLUMNS + " from tacit_ontology_publication where project_id=:p and request_id=:request")
                    .param("p", project).param("request", cmd.requestId()).query(OntologyPublications::row).optional();
            if (existing.isPresent()) {
                var row = existing.get();
                if (!row.target.equals(cmd.target()) || !row.digest.equals(cmd.digest()) || row.revision != cmd.revision())
                    throw new IllegalStateException("该请求标识已用于其他发布内容");
                return Reservation.existing(row);
            }
            requireTarget(cmd.target(), currentTarget);
            var snapshot = workspace.modelForPublication(project, cmd.revision(), cmd.digest());
            var duplicate = jdbc.sql("select " + COLUMNS + " from tacit_ontology_publication where project_id=:p and model_digest=:d and target_id=:t")
                    .param("p", project).param("d", cmd.digest()).param("t", cmd.target()).query(OntologyPublications::row).optional();
            if (duplicate.isPresent()) return Reservation.existing(duplicate.get());
            String id = UUID.randomUUID().toString();
            ensureNoPending(project, cmd.target(), id);
            Instant now = Instant.now();
            jdbc.sql("""
                    insert into tacit_ontology_publication
                        (id,project_id,request_id,source_revision,model_digest,target_id,actor_id,snapshot_json,status,
                         attempt,result_json,created_at,updated_at)
                    values (:id,:p,:r,:v,:d,:t,:a,:snap,'PUBLISHING',1,'{}',:now,:now)
                    """).param("id", id).param("p", project).param("r", cmd.requestId()).param("v", cmd.revision())
                    .param("d", cmd.digest()).param("t", cmd.target()).param("a", ActorContext.current())
                    .param("snap", TacitJson.write(snapshot)).param("now", now).update();
            return new Reservation(find(project, id), snapshot, false);
        });
        return send(project, reservation);
    }

    Map<String,Object> retry(String project, String id, long revision) {
        owner(project);
        var currentTarget = gateway.target(project);
        var work = tx.execute(ignored -> {
            lock(project);
            var row = find(project, id);
            if (row.status.equals("VERIFIED") || (row.status.equals("PUBLISHING") && row.updated.plus(LEASE).isAfter(Instant.now())))
                return Reservation.existing(row);
            requireTarget(row.target, currentTarget);
            boolean verifyOnly = List.of("PUBLISHING", "UNCERTAIN").contains(row.status);
            if (!verifyOnly) {
                ensureNoPending(project, row.target, id);
                workspace.modelForPublication(project, revision, row.digest);
            }
            jdbc.sql("""
                    update tacit_ontology_publication set attempt=attempt+1,status='PUBLISHING',updated_at=:now,result_json='{}'
                    where id=:id and project_id=:p
                    """).param("id", id).param("p", project).param("now", Instant.now()).update();
            return new Reservation(find(project, id), savedSnapshot(project, id), verifyOnly);
        });
        return send(project, work);
    }

    private Map<String,Object> send(String project, Reservation work) {
        var row = work.row;
        if (work.snapshot == null) return row.view();
        String status = "UNCERTAIN";
        Map<String,Object> evidence = Map.of("message", "尚未确认远端结果，请核对本次发布");
        try {
            var result = gateway.publish(Map.of("project_id", project, "target_id", row.target, "digest", row.digest,
                    "model", work.snapshot.get("model"), "verify_only", work.verifyOnly));
            var outcome = String.valueOf(result.get("status"));
            var receipt = TacitJson.map(result.getOrDefault("receipt", Map.of()));
            boolean verified = Boolean.TRUE.equals(receipt.get("verified")) && row.digest.equals(receipt.get("digest"))
                    && row.target.equals(receipt.get("target_id"));
            if ((outcome.equals("VERIFIED") && verified) || outcome.equals("UNCERTAIN") ||
                    (outcome.equals("ABSENT") && work.verifyOnly) || (outcome.equals("FAILED") && !work.verifyOnly)) {
                status = outcome;
                evidence = Map.of("message", String.valueOf(result.getOrDefault("message", "")), "receipt", receipt);
            }
        } catch (RuntimeException failure) {
            // HTTP failure can occur after the remote transaction committed. The subsequent probe only reads.
        }
        jdbc.sql("""
                update tacit_ontology_publication set status=:s,result_json=:result,updated_at=:now
                where project_id=:p and id=:id and status='PUBLISHING' and attempt=:attempt
                """).param("s", status).param("result", TacitJson.write(evidence)).param("now", Instant.now())
                .param("p", project).param("id", row.id).param("attempt", row.attempt).update();
        return find(project, row.id).view();
    }
}
