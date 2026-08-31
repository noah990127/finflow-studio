package com.finflow.studio.workflow;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private static final int MAX_REPLAY_EVENTS = 600;

    public record RunProgressEvent(long sequence, String runId, String type, String nodeId,
                                   String nodeName, String status, int progress, String message,
                                   String content, Instant createdAt) { }

    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, List<RunProgressEvent>> replay = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public RunProgressEvent publish(String runId, String type, String nodeId, String nodeName,
                                    String status, int progress, String message, String content) {
        var sequence = sequences.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
        var event = new RunProgressEvent(sequence, runId, type, nodeId, nodeName, status,
                Math.max(0, Math.min(100, progress)), message == null ? "" : message,
                content == null ? "" : content, Instant.now());
        var events = replay.computeIfAbsent(runId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (events) {
            events.add(event);
            if (events.size() > MAX_REPLAY_EVENTS) events.subList(0, events.size() - MAX_REPLAY_EVENTS).clear();
        }
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
        var events = replay.get(runId);
        if (events == null) return List.of();
        synchronized (events) {
            return events.stream().filter(event -> event.sequence() > afterSequence).toList();
        }
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
