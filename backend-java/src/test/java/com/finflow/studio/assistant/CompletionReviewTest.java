package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CompletionReviewTest {
    @Test void waitingQuestionIsNotHiddenByHistoricalStaleStage() {
        var stale = new AgentWorkService.Stage("quote", "查询", "STALE", "待核对历史执行", "", List.of(), List.of());
        var waiting = new AgentWorkService.Stage("confirm", "确认", "WAITING_INPUT", "", "仅固化还是重新执行？", List.of(), List.of());
        assertThat(AssistantExecutionService.completionBlocker(List.of(stale, waiting))).isEmpty();
        assertThat(AssistantExecutionService.completionBlocker(List.of(stale))).contains(stale);
    }

    private PlanStep step(String tool, String mode, String status) {
        return new PlanStep(tool, 1, tool, mode, tool, "", Map.of(), null, false, status);
    }

    @Test void historicalReadsIgnoreCoordinatorButNeverClaimExecution() {
        assertThat(AssistantExecutionService.isReadOnlyReview(List.of(
                step("agent.execute", "WRITE", "SUCCEEDED"),
                step("resource.read", "READ", "SUCCEEDED")), Map.of())).isTrue();
    }

    @Test void emptyFailedAndWriteOperationsAreNotReviews() {
        assertThat(AssistantExecutionService.isReadOnlyReview(List.of(), Map.of())).isFalse();
        assertThat(AssistantExecutionService.isReadOnlyReview(List.of(step("resource.read", "READ", "FAILED")), Map.of())).isFalse();
        assertThat(AssistantExecutionService.isReadOnlyReview(List.of(step("resource.create", "WRITE", "SUCCEEDED")), Map.of())).isFalse();
    }

    @Test void publishedExecutedAndDelegatedWorkKeepCompletionChecks() {
        for (var key : List.of("fixedImplementationId", "fixedRunId", "browserAgentTask")) {
            assertThat(AssistantExecutionService.isReadOnlyReview(
                    List.of(step("resource.read", "READ", "SUCCEEDED")), Map.of(key, "id"))).isFalse();
        }
    }
}
