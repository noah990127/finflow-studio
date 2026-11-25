package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Retries persistence only; never dispatches a tool or repeats external side effects. */
@Service
@ConditionalOnProperty(name = "finflow.execution.reconcile-enabled", havingValue = "true", matchIfMissing = true)
public class TechnicalArchiveReconciler {
    private final JdbcClient jdbc;
    private final BrowserAgentService browsers;
    private final TechnicalExecutionJournal journal;
    public TechnicalArchiveReconciler(JdbcClient jdbc, BrowserAgentService browsers, TechnicalExecutionJournal journal) {
        this.jdbc = jdbc; this.browsers = browsers; this.journal = journal;
    }
    @Scheduled(initialDelayString = "${finflow.execution.reconcile-initial-delay-ms:60000}", fixedDelayString = "${finflow.execution.reconcile-delay-ms:15000}")
    public void reconcile() {
        jdbc.sql("select task_id,owner_id from browser_agent_task where reconciled=false order by coalesce(reconciliation_checked_at,created_at) limit 20")
                .query((rs, row) -> new String[]{rs.getString(1), rs.getString(2)}).list()
                .forEach(row -> ActorContext.runAs(row[1], () -> reconcileBrowser(row[0])));
        jdbc.sql("""
                select r.id,d.project_id,s.default_user_id from fixed_work_run r
                join fixed_work_definition d on d.id=r.definition_id
                join assistant_session s on s.id=r.session_id
                where r.status in ('SUCCEEDED','FAILED','CANCELED')
                and exists(select 1 from technical_step_execution e where e.run_id=r.id)
                and not exists(select 1 from technical_artifact a where a.owner_kind='EXECUTION' and a.owner_id=r.id)
                order by r.created_at limit 20
                """).query((rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).list()
                .forEach(row -> ActorContext.runAs(row[2], () -> {
                    try {
                        journal.interrupted(row[0], "执行已结束但调用结果未确认；不得自动重复提交");
                        journal.archive(row[1], row[0]);
                    } catch (RuntimeException error) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Execution archive pending: {}", row[0], error);
                    }
                }));
    }
    void reconcileBrowser(String id) {
        try {
            var task = browsers.status(id);
            if (task.containsKey("archiveIssue") || task.containsKey("procedureError"))
                throw new IllegalStateException(task.getOrDefault("archiveIssue", task.get("procedureError")).toString());
            if (task.get("status") instanceof Map<?, ?> status &&
                    java.util.Set.of("completed", "failed", "canceled", "rejected").contains(status.get("state"))) {
                jdbc.sql("update browser_agent_task set reconciled=true,reconciliation_error='' where task_id=:id")
                        .param("id", id).update();
            }
        } catch (RuntimeException error) {
            jdbc.sql("update browser_agent_task set reconciliation_error=:error where task_id=:id")
                    .param("id", id).param("error", java.util.Objects.toString(error.getMessage(), "归档待重试")).update();
        } finally {
            jdbc.sql("update browser_agent_task set reconciliation_checked_at=:now where task_id=:id")
                    .param("id", id).param("now", java.time.Instant.now()).update();
        }
    }
}
