package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService assistant;
    private final AssistantExecutionService execution;
    private final AssistantEventService events;

    public AssistantController(AssistantService assistant, AssistantExecutionService execution,
                               AssistantEventService events) {
        this.assistant = assistant;
        this.execution = execution;
        this.events = events;
    }

    @PostMapping("/projects/{projectId}/assistant/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse createSession(@PathVariable String projectId,
                                  @RequestBody(required = false) CreateSessionRequest request) {
        return assistant.createSession(projectId, request == null ? null : request.title());
    }

    @GetMapping("/assistant/sessions/{sessionId}")
    SessionResponse getSession(@PathVariable String sessionId) {
        return assistant.getSession(sessionId);
    }

    @PostMapping("/assistant/sessions/{sessionId}/messages")
    MessageResponse sendMessage(@PathVariable String sessionId,
                                @Valid @RequestBody MessageRequest request) {
        return assistant.sendMessage(sessionId, request);
    }

    @GetMapping("/assistant/sessions/{sessionId}/events")
    SseEmitter streamEvents(@PathVariable String sessionId,
                            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
        assistant.getSession(sessionId);
        return events.subscribe(sessionId, lastEventId);
    }

    @GetMapping("/assistant/sessions/{sessionId}/event-history")
    List<AssistantEvent> eventHistory(@PathVariable String sessionId,
                                      @RequestParam(defaultValue = "0") long after) {
        assistant.getSession(sessionId);
        return events.list(sessionId, after);
    }

    @GetMapping("/assistant/plans/{planId}")
    PlanResponse getPlan(@PathVariable String planId) {
        return assistant.getPlan(planId);
    }

    @PostMapping("/assistant/plans/{planId}/confirm")
    RunResponse confirm(@PathVariable String planId,
                        @Valid @RequestBody ConfirmPlanRequest request) {
        return assistant.confirm(planId, request);
    }

    @GetMapping("/assistant/runs/{runId}")
    RunResponse getRun(@PathVariable String runId) {
        return execution.get(runId);
    }

    @PostMapping("/assistant/runs/{runId}/cancel")
    RunResponse cancel(@PathVariable String runId) {
        return execution.cancel(runId);
    }

    @PostMapping("/assistant/runs/{runId}/rollback")
    RunResponse rollback(@PathVariable String runId) {
        return execution.rollback(runId);
    }
}

