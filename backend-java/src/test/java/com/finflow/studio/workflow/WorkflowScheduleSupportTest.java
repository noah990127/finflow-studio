package com.finflow.studio.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.finflow.studio.workflow.WorkflowModels.ExecutionMode.MANUAL;
import static com.finflow.studio.workflow.WorkflowModels.ExecutionMode.SCHEDULED;
import static com.finflow.studio.workflow.WorkflowModels.ScheduleFrequency.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowScheduleSupportTest {
    @Test
    void calculatesFriendlySchedulesInConfiguredTimezone() {
        var after = Instant.parse("2026-08-30T04:05:00Z"); // 12:05 in Shanghai

        assertThat(WorkflowScheduleSupport.nextRun(SCHEDULED,
                new WorkflowModels.ScheduleDefinition(HOURLY, "00:10", null, null, "Asia/Shanghai"), after))
                .isEqualTo(Instant.parse("2026-08-30T04:10:00Z"));
        assertThat(WorkflowScheduleSupport.nextRun(SCHEDULED,
                new WorkflowModels.ScheduleDefinition(DAILY, "09:00", null, null, "Asia/Shanghai"), after))
                .isEqualTo(Instant.parse("2026-08-31T01:00:00Z"));
        assertThat(WorkflowScheduleSupport.nextRun(SCHEDULED,
                new WorkflowModels.ScheduleDefinition(WEEKLY, "09:00", 1, null, "Asia/Shanghai"), after))
                .isEqualTo(Instant.parse("2026-08-31T01:00:00Z"));
        assertThat(WorkflowScheduleSupport.nextRun(MANUAL, null, after)).isNull();
    }
}
