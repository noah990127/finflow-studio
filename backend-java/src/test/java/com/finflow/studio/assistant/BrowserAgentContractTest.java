package com.finflow.studio.assistant;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class BrowserAgentContractTest {
    @Test void exposesDelegationInsteadOfHandwrittenBrowserLoop() {
        var ids = AssistantCapabilityRegistry.agentCatalog().stream().map(c -> c.get("id")).toList();
        assertThat(ids).contains("browser.delegate", "browser.task_status")
                .doesNotContain("browser.open", "browser.observe", "browser.act", "browser.explore");
    }
    @Test void validatesGoalWithoutDelegationApproval() {
        AssistantToolContracts.validate("browser.delegate", Map.of("url", "https://example.com", "goal", "读取本月报表"));
        assertThatThrownBy(() -> AssistantToolContracts.validate("browser.delegate", Map.of("url", "https://example.com", "goal", "x".repeat(601))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AssistantCapabilityRegistry.find("browser.delegate").orElseThrow().confirmationRequirement()).isEqualTo("never");
    }
}
