package com.finflow.studio.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantExecutionServiceTest {

    @Test
    void everyDiscoverableToolHasAnExecutionHandler() {
        assertThat(AssistantExecutionService.supportedTools())
                .containsExactlyInAnyOrderElementsOf(AssistantCapabilityRegistry.ids());
    }
}
