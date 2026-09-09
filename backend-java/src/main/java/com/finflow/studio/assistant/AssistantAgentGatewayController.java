package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.PathVariable;
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
}
