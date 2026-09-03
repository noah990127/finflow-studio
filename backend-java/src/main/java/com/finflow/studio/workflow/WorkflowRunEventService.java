package com.finflow.studio.workflow;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class WorkflowRunEventService {
    public record RunProgressEvent(long sequence, String runId, String type, String nodeId,
                                   String nodeName, String status, int progress, String message,
                                   String content, Instant createdAt) { }

    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;

    public WorkflowRunEventService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RunProgressEvent publish(String runId, String type, String nodeId, String nodeName,
                                    String status, int progress, String message, String content) {
        var sequence = sequences.computeIfAbsent(runId, this::loadSequence).incrementAndGet();
        var event = new RunProgressEvent(sequence, runId, type, nodeId, nodeName, status,
                Math.max(0, Math.min(100, progress)), message == null ? "" : message,
                content == null ? "" : content, Instant.now());
        jdbc.sql("""
                insert into workflow_run_event(id, run_id, event_seq, event_type, node_id, node_name,
                    status, progress, message, content, created_at)
                values (:id, :runId, :sequence, :type, :nodeId, :nodeName, :status,
                    :progress, :message, :content, :createdAt)
                """).param("id", java.util.UUID.randomUUID().toString()).param("runId", runId)
                .param("sequence", event.sequence()).param("type", event.type()).param("nodeId", event.nodeId())
                .param("nodeName", event.nodeName()).param("status", event.status()).param("progress", event.progress())
                .param("message", event.message()).param("content", event.content()).param("createdAt", event.createdAt()).update();
        emitters.getOrDefault(runId, new CopyOnWriteArrayList<>()).removeIf(emitter -> !send(emitter, event));
        return event;
    }

    public SseEmitter subscribe(String runId, long afterSequence) {
        var emitter = new SseEmitter(30 * 60 * 1000L);
        list(runId, afterSequence).forEach(event -> send(emitter, event));
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        return emitter;
    }

    public List<RunProgressEvent> list(String runId, long afterSequence) {
        return jdbc.sql("""
                select * from workflow_run_event where run_id = :runId and event_seq > :after
                order by event_seq
                """).param("runId", runId).param("after", afterSequence)
                .query((rs, rowNum) -> new RunProgressEvent(rs.getLong("event_seq"), rs.getString("run_id"),
                        rs.getString("event_type"), rs.getString("node_id"), rs.getString("node_name"),
                        rs.getString("status"), rs.getInt("progress"), rs.getString("message"),
                        rs.getString("content"), rs.getTimestamp("created_at").toInstant())).list();
    }

    private AtomicLong loadSequence(String runId) {
        var current = jdbc.sql("select coalesce(max(event_seq), 0) from workflow_run_event where run_id = :runId")
                .param("runId", runId).query(Long.class).single();
        return new AtomicLong(current);
    }

    private boolean send(SseEmitter emitter, RunProgressEvent event) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("progress").data(event));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        emitters.getOrDefault(runId, new CopyOnWriteArrayList<>()).remove(emitter);
    }
}
