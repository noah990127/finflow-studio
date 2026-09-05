package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService assistant;
    private final AssistantExecutionService execution;
    private final AssistantEventService events;
    private final AgentMemoryService memory;

    public AssistantController(AssistantService assistant, AssistantExecutionService execution,
                               AssistantEventService events, AgentMemoryService memory) {
        this.assistant = assistant;
        this.execution = execution;
        this.events = events;
        this.memory = memory;
    }

    @PostMapping("/projects/{projectId}/assistant/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse createSession(@PathVariable String projectId,
                                  @RequestBody(required = false) CreateSessionRequest request) {
        return assistant.createSession(projectId, request == null ? null : request.title());
    }

    @GetMapping("/projects/{projectId}/assistant/sessions")
    List<SessionResponse> listSessions(@PathVariable String projectId) {
        return assistant.listSessions(projectId);
    }

    @GetMapping("/assistant/sessions/{sessionId}")
    SessionResponse getSession(@PathVariable String sessionId) {
        return assistant.getSession(sessionId);
    }

    @GetMapping("/assistant/sessions/{sessionId}/messages")
    List<MessageHistoryItem> messageHistory(@PathVariable String sessionId) {
        return assistant.listMessages(sessionId);
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

    @GetMapping("/assistant/tools")
    List<Map<String, Object>> listTools() {
        return AssistantCapabilityRegistry.catalog();
    }

    @PostMapping("/assistant/tools/search")
    List<Map<String, Object>> searchTools(@RequestBody(required = false) ToolSearchRequest request) {
        return AssistantCapabilityRegistry.search(request == null ? "" : request.query());
    }

    @GetMapping("/assistant/tools/{toolName:.+}")
    Map<String, Object> describeTool(@PathVariable String toolName) {
        return AssistantCapabilityRegistry.find(toolName)
                .map(capability -> AssistantCapabilityRegistry.search(capability.id()).stream()
                        .filter(item -> toolName.equals(item.get("id")))
                        .findFirst()
                        .orElseThrow())
                .orElseThrow(() -> new IllegalArgumentException("工具不存在：" + toolName));
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

    @PostMapping("/assistant/sessions/{sessionId}/requests/{requestId}/cancel")
    Map<String, String> interruptRequest(@PathVariable String sessionId, @PathVariable String requestId) {
        if (requestId.length() > 64) throw new IllegalArgumentException("请求标识过长");
        assistant.interruptRequest(sessionId, requestId);
        return Map.of("status", "CANCELED");
    }

    @PostMapping("/assistant/plans/{planId}/cancel")
    Map<String, String> cancelPlan(@PathVariable String planId) {
        execution.cancelPlan(planId);
        return Map.of("status", assistant.getPlan(planId).status());
    }

    @PostMapping("/assistant/runs/{runId}/rollback")
    RunResponse rollback(@PathVariable String runId) {
        return execution.rollback(runId);
    }

    @GetMapping("/assistant/memory")
    List<MemoryResponse> listMemory(@RequestParam(required = false) String projectId) {
        return memory.list(projectId);
    }

    @PutMapping("/assistant/memory")
    MemoryResponse saveMemory(@Valid @RequestBody MemoryRequest request) {
        return memory.save(request);
    }

    @DeleteMapping("/assistant/memory/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMemory(@PathVariable String id) {
        memory.delete(id);
    }
}
