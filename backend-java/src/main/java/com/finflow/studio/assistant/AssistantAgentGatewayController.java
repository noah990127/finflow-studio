package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AssistantAgentGatewayController {
    private final AssistantExecutionService execution;

    public AssistantAgentGatewayController(AssistantExecutionService execution) {
        this.execution = execution;
    }

    @PostMapping("/internal/assistant/runs/{runId}/tools/call")
    Map<String, Object> callTool(@PathVariable String runId,
                                 @RequestHeader("X-Agent-Gateway-Token") String gatewayToken,
                                 @RequestBody Map<String, Object> request) {
        return execution.callContinuousTool(runId, gatewayToken, request);
    }

    @GetMapping("/internal/assistant/runs/{runId}/workflow-runs/{workflowRunId}")
    Map<String, Object> workflowRunStatus(@PathVariable String runId,
                                          @PathVariable String workflowRunId,
                                          @RequestHeader("X-Agent-Gateway-Token") String gatewayToken) {
        return execution.workflowRunStatus(runId, workflowRunId, gatewayToken);
    }
}
