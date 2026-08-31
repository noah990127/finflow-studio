package com.finflow.studio.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

@Service
public class WorkflowScheduleService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduleService.class);
    private final JdbcClient jdbc;
    private final WorkflowDefinitionService definitions;
    private final WorkflowRunService runs;

    public WorkflowScheduleService(JdbcClient jdbc, WorkflowDefinitionService definitions, WorkflowRunService runs) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.runs = runs;
    }

    @Scheduled(fixedDelayString = "${finflow.workflow.scheduler-poll-ms:30000}")
    public void runDueWorkflows() {
        var now = Instant.now();
        var due = jdbc.sql("""
                select id, next_run_at from workflow_definition
                where next_run_at is not null and next_run_at <= :now
                order by next_run_at limit 20
                """).param("now", now).query(this::mapDue).list();
        for (var item : due) {
            try {
                var workflow = definitions.get(item.id());
                var document = definitions.version(item.id(), workflow.currentVersion());
                var next = WorkflowScheduleSupport.nextRun(workflow.executionMode(), workflow.schedule(), now);
                var claimed = jdbc.sql("""
                        update workflow_definition set next_run_at = :next
                        where id = :id and next_run_at = :due
                        """).param("next", next).param("id", item.id()).param("due", item.due()).update();
                if (claimed == 1) runs.startScheduled(item.id());
            } catch (Exception exception) {
                log.warn("Scheduled workflow {} was not started: {}", item.id(), exception.getMessage());
            }
        }
    }

    private DueWorkflow mapDue(ResultSet rs, int rowNum) throws SQLException {
        return new DueWorkflow(rs.getString("id"), rs.getTimestamp("next_run_at").toInstant());
    }

    private record DueWorkflow(String id, Instant due) { }
}
