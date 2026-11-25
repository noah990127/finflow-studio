package com.finflow.studio.assistant;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class BrowserWorkOutcomeTest {
    private AgentWorkService.Work work() {
        return new AgentWorkService.Work("run", "session", "goal", "SUCCEEDED", "委派中", "title", Instant.now(),
                List.of(new AgentWorkService.Stage("stage", "网页操作", "RUNNING", "", "", List.of(), List.of())),
                Map.of("workStatus", "DELEGATED"));
    }

    @Test void completedTaskDoesNotStayRunningWhenPublicationFails() {
        var task = Map.<String,Object>of("status", Map.of("state", "completed"),
                "metadata", Map.of("procedureError", "操作未明确", "procedureState", "NEEDS_REVIEW"),
                "artifacts", List.of(Map.of("bytes", "private-file")));
        var ref = new AgentWorkService.ResourceRef("file", 1, "SUPPORTING");
        var projected = AgentWorkService.projectBrowserOutcome(work(), task, List.of(ref));
        assertThat(projected.stages().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(projected.stages().getFirst().outputs()).containsExactly(ref);
        assertThat(projected.resultSummary()).contains("尚未固化", "操作未明确");
        assertThat(projected.result().toString()).doesNotContain("private-file");
    }

    @Test void pendingTasksRemainRunningAndFailureIsNotSuccess() {
        var work = work();
        assertThat(AgentWorkService.projectBrowserOutcome(work, Map.of("status", Map.of("state", "working")), List.of())).isSameAs(work);
        assertThat(AgentWorkService.projectBrowserOutcome(work, Map.of("status", Map.of("state", "failed")), List.of()).status()).isEqualTo("FAILED");
    }
}
