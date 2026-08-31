package com.finflow.studio.assistant;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.finflow.studio.assistant.AssistantModels.AssistantEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AssistantEventService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public AssistantEventService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AssistantEvent publish(String sessionId, String runId, String type, Map<String, Object> payload) {
        var sequence = sequences.computeIfAbsent(sessionId, this::loadSequence).incrementAndGet();
        var event = new AssistantEvent(UUID.randomUUID().toString(), sequence, sessionId, runId,
                type, payload, Instant.now());
        jdbc.sql("""
                insert into assistant_event(id, session_id, run_id, event_seq, event_type, payload_json, created_at)
                values (:id, :sessionId, :runId, :eventSeq, :eventType, :payload, :createdAt)
                """)
                .param("id", event.eventId())
                .param("sessionId", sessionId)
                .param("runId", runId)
                .param("eventSeq", sequence)
                .param("eventType", type)
                .param("payload", writeJson(payload))
                .param("createdAt", event.createdAt())
                .update();
        emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>()).removeIf(emitter -> !send(emitter, event));
        return event;
    }

    public SseEmitter subscribe(String sessionId, long afterSequence) {
        var emitter = new SseEmitter(30 * 60 * 1000L);
        list(sessionId, afterSequence).forEach(event -> send(emitter, event));
        emitters.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(error -> remove(sessionId, emitter));
        return emitter;
    }

    public List<AssistantEvent> list(String sessionId, long afterSequence) {
        return jdbc.sql("""
                        select * from assistant_event
                        where session_id = :sessionId and event_seq > :afterSequence
                        order by event_seq
                        """)
                .param("sessionId", sessionId)
                .param("afterSequence", afterSequence)
                .query((rs, rowNum) -> new AssistantEvent(
                        rs.getString("id"),
                        rs.getLong("event_seq"),
                        rs.getString("session_id"),
                        rs.getString("run_id"),
                        rs.getString("event_type"),
                        readJson(rs.getString("payload_json")),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    private AtomicLong loadSequence(String sessionId) {
        var current = jdbc.sql("select coalesce(max(event_seq), 0) from assistant_event where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        return new AtomicLong(current);
    }

    private boolean send(SseEmitter emitter, AssistantEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.eventSeq()))
                    .name(event.type())
                    .data(event));
            return true;
        } catch (IOException | IllegalStateException ex) {
            return false;
        }
    }

    private void remove(String sessionId, SseEmitter emitter) {
        emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>()).remove(emitter);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("无法保存助手事件", ex);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException ex) {
            return Map.of("message", "事件内容无法解析");
        }
    }
}
