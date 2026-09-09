package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantFailurePolicyTest {
    @Test
    void stopsAnIdenticalParameterFailureOnTheSecondAttempt() {
        var attempts = new LinkedHashMap<String, Integer>();
        var observation = Map.<String, Object>of("success", false, "failureType", "PARAMETER",
                "retryable", false, "recoveryHint", "补全参数");

        assertThat(AssistantFailurePolicy.assess(step(Map.of("name", "")), observation, attempts).stop()).isFalse();
        var repeated = AssistantFailurePolicy.assess(step(Map.of("name", "")), observation, attempts);

        assertThat(repeated.stop()).isTrue();
        assertThat(repeated.stopMessage()).contains("相同参数").contains("参数不符合契约");
    }

    @Test
    void changedArgumentsAreNotTreatedAsTheSameFailedOperation() {
        var attempts = new LinkedHashMap<String, Integer>();
        var observation = Map.<String, Object>of("success", false, "failureType", "RESOURCE_MISSING",
                "retryable", false, "recoveryHint", "重新查找");
        AssistantFailurePolicy.assess(step(Map.of("resource_id", "missing-a")), observation, attempts);

        assertThat(AssistantFailurePolicy.assess(step(Map.of("resource_id", "missing-b")), observation, attempts).attempt())
                .isEqualTo(1);
    }

    @Test
    void permitsOnlyTwoRetriesForAnIdenticalNetworkFailure() {
        var attempts = new LinkedHashMap<String, Integer>();
        var observation = Map.<String, Object>of("success", false, "failureType", "NETWORK",
                "retryable", true, "recoveryHint", "稍后重试");

        assertThat(AssistantFailurePolicy.assess(step(Map.of("url", "https://example.com")), observation, attempts).stop()).isFalse();
        assertThat(AssistantFailurePolicy.assess(step(Map.of("url", "https://example.com")), observation, attempts).stop()).isFalse();
        assertThat(AssistantFailurePolicy.assess(step(Map.of("url", "https://example.com")), observation, attempts).stop()).isTrue();
    }

    private PlanStep step(Map<String, Object> arguments) {
        return new PlanStep("step", 1, "resource.read", "READ", "读取", "读取资料", arguments,
                RiskLevel.READ_ONLY, false, "PENDING");
    }
}
