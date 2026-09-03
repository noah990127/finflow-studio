package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.ActivityRunResponse;
import com.finflow.studio.workflow.WorkflowModels.LineageEdgeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WorkflowFactService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public WorkflowFactService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String beginActivity(String runId, String nodeId, String type, String capability,
                                String title, Map<String, Object> input) {
        var nodeRunId = nodeRunId(runId, nodeId);
        var order = jdbc.sql("select coalesce(max(activity_order), 0) + 1 from workflow_activity_run where node_run_id = :id")
                .param("id", nodeRunId).query(Integer.class).single();
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                insert into workflow_activity_run(id, run_id, node_run_id, activity_order, activity_type,
                    capability, title, status, input_json, output_json, started_at)
                values (:id, :runId, :nodeRunId, :activityOrder, :activityType, :capability,
                    :title, 'RUNNING', :input, '{}', :startedAt)
                """).param("id", id).param("runId", runId).param("nodeRunId", nodeRunId)
                .param("activityOrder", order).param("activityType", type).param("capability", safe(capability))
                .param("title", title).param("input", writeJson(input)).param("startedAt", Instant.now()).update();
        return id;
    }

    public void completeActivity(String id, String status, Map<String, Object> output, String error) {
        jdbc.sql("""
                update workflow_activity_run set status = :status, output_json = :output,
                    error_message = :error, finished_at = :finishedAt where id = :id
                """).param("status", status).param("output", writeJson(output)).param("error", safe(error))
                .param("finishedAt", Instant.now()).param("id", id).update();
    }

    public void recordNodeLineage(String runId, String nodeId,
                                  Map<String, Map<String, Object>> upstream, Map<String, Object> output) {
        var nodeRunId = nodeRunId(runId, nodeId);
        var target = artifact(output, "NODE_RUN", nodeRunId, null);
        for (var entry : upstream.entrySet()) {
            var source = artifact(entry.getValue(), "NODE_RUN", entry.getKey(), null);
            insertLineage(runId, nodeRunId, source, target, "USED_BY", Map.of("upstreamNodeId", entry.getKey()));
        }
        var produced = artifact(output, null, null, null);
        if (produced != null && !"NODE_RUN".equals(produced.kind())) {
            insertLineage(runId, nodeRunId, target, produced, "PRODUCED", Map.of());
        }
        for (var refId : stringList(output.get("refIds"))) {
            insertLineage(runId, nodeRunId, new Artifact("REF", refId, null), target, "SUPPORTED", Map.of());
        }
    }

    public List<ActivityRunResponse> activitiesForNode(String nodeRunId) {
        return jdbc.sql("select * from workflow_activity_run where node_run_id = :id order by activity_order")
                .param("id", nodeRunId).query(this::mapActivity).list();
    }

    public List<ActivityRunResponse> activitiesForRun(String runId) {
        return jdbc.sql("select * from workflow_activity_run where run_id = :id order by started_at, activity_order")
                .param("id", runId).query(this::mapActivity).list();
    }

    public List<LineageEdgeResponse> lineageForRun(String runId) {
        return jdbc.sql("select * from workflow_lineage_edge where run_id = :id order by created_at")
                .param("id", runId).query(this::mapLineage).list();
    }

    public List<LineageEdgeResponse> provenance(String targetRef) {
        return jdbc.sql("select * from workflow_lineage_edge where target_ref = :ref or run_id in (select run_id from workflow_lineage_edge where target_ref = :ref) order by created_at")
                .param("ref", targetRef).query(this::mapLineage).list();
    }

    public void recordExternalSource(String runId, String nodeId, String resourceId,
                                     int version, String url, String contentHash) {
        var nodeRunId = nodeRunId(runId, nodeId);
        insertLineage(runId, nodeRunId, new Artifact("WEB_SNAPSHOT", resourceId, version),
                new Artifact("NODE_RUN", nodeRunId, null), "SUPPORTED",
                Map.of("url", safe(url), "contentHash", safe(contentHash)));
    }

    private void insertLineage(String runId, String nodeRunId, Artifact source, Artifact target,
                               String relation, Map<String, Object> details) {
        if (source == null || target == null || source.ref().isBlank() || target.ref().isBlank()) return;
        jdbc.sql("""
                insert into workflow_lineage_edge(id, run_id, node_run_id, source_kind, source_ref,
                    source_version, target_kind, target_ref, target_version, relation, details_json, created_at)
                values (:id, :runId, :nodeRunId, :sourceKind, :sourceRef, :sourceVersion,
                    :targetKind, :targetRef, :targetVersion, :relation, :details, :createdAt)
                """).param("id", UUID.randomUUID().toString()).param("runId", runId).param("nodeRunId", nodeRunId)
                .param("sourceKind", source.kind()).param("sourceRef", source.ref()).param("sourceVersion", source.version())
                .param("targetKind", target.kind()).param("targetRef", target.ref()).param("targetVersion", target.version())
                .param("relation", relation).param("details", writeJson(details)).param("createdAt", Instant.now()).update();
    }

    private Artifact artifact(Map<String, ?> value, String fallbackKind, String fallbackRef, Integer fallbackVersion) {
        if (value != null) {
            var file = Objects.toString(value.get("fileId"), "");
            if (!file.isBlank()) return new Artifact("RESOURCE_VERSION", file, integer(value.get("version")));
            var extract = Objects.toString(value.get("extractJobId"), "");
            if (!extract.isBlank()) return new Artifact("DATASET", extract, 1);
            var deliverable = Objects.toString(value.get("deliverableId"), "");
            if (!deliverable.isBlank()) return new Artifact("DELIVERABLE_VERSION", deliverable, integer(value.get("version")));
            var url = Objects.toString(value.get("snapshotResourceId"), "");
            if (!url.isBlank()) return new Artifact("WEB_SNAPSHOT", url, integer(value.get("version")));
        }
        return fallbackKind == null ? null : new Artifact(fallbackKind, safe(fallbackRef), fallbackVersion);
    }

    private String nodeRunId(String runId, String nodeId) {
        return jdbc.sql("select id from workflow_node_run where run_id = :runId and node_id = :nodeId")
                .param("runId", runId).param("nodeId", nodeId).query(String.class).single();
    }

    private ActivityRunResponse mapActivity(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityRunResponse(rs.getString("id"), rs.getString("run_id"), rs.getString("node_run_id"),
                rs.getInt("activity_order"), rs.getString("activity_type"), rs.getString("capability"),
                rs.getString("title"), rs.getString("status"), readMap(rs.getString("input_json")),
                readMap(rs.getString("output_json")), rs.getString("error_message"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private LineageEdgeResponse mapLineage(ResultSet rs, int rowNum) throws SQLException {
        return new LineageEdgeResponse(rs.getString("id"), rs.getString("run_id"), rs.getString("node_run_id"),
                rs.getString("source_kind"), rs.getString("source_ref"), nullableInteger(rs, "source_version"),
                rs.getString("target_kind"), rs.getString("target_ref"), nullableInteger(rs, "target_version"),
                rs.getString("relation"), readMap(rs.getString("details_json")), rs.getTimestamp("created_at").toInstant());
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private String safe(String value) { return value == null ? "" : value; }
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList();
    }
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("运行事实无法保存", exception); }
    }
    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException exception) { return Map.of(); }
    }
    private record Artifact(String kind, String ref, Integer version) { }
}
