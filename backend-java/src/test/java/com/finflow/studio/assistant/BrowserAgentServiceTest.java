package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BrowserAgentServiceTest {
    BrowserAgentService service;
    JdbcClient jdbc;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = JdbcClient.create(ds);
        var json = new ObjectMapper();
        var result = Map.of("id", "task-1", "status", Map.of("state", "completed"),
                "artifacts", List.of(Map.of("parts", List.of(Map.of("file", Map.of("name", "report.csv", "bytes", "YSxiCg=="))))));
        jdbc.sql("insert into browser_agent_task(task_id,step_id,owner_id,session_id,last_result) values('task-1','step-1','alice','session-1',:result)")
                .param("result", json.writeValueAsString(result)).update();
        service = new BrowserAgentService(jdbc, json, new AssistantEventService(jdbc, json), "http://127.0.0.1:1", "test-token-".repeat(4));
    }
    @Test void cachedResultSurvivesAgentRestartAndDoesNotSendFileBytesToModel() {
        try (var ignored = ActorContext.bind("alice")) {
            var status = service.status("task-1");
            assertThat(status).containsEntry("stepId", "step-1");
            assertThat(service.list("session-1").getFirst()).containsEntry("stepId", "step-1");
            assertThat(status).containsKey("files").doesNotContainKey("artifacts");
            assertThat(status.toString()).doesNotContain("YSxiCg==");
            assertThat(new String(service.download("task-1", 0, 0).bytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a,b\n");
        }
    }
    @Test void otherUserCannotReadControlOrDownloadTask() {
        try (var ignored = ActorContext.bind("bob")) {
            assertThat(service.conversationTasks("session-1")).isEmpty();
            assertThat(service.list("session-1")).isEmpty();
            assertThatThrownBy(() -> service.status("task-1")).hasMessage("浏览器任务不存在");
            assertThatThrownBy(() -> service.download("task-1", 0, 0)).hasMessage("浏览器任务不存在");
            assertThatThrownBy(() -> service.control("task-1", "resume", 1)).hasMessage("浏览器任务不存在");
        }
    }
    @Test void nextTurnReceivesPersistedTaskReferenceWithoutRestartingTask() {
        try (var ignored = ActorContext.bind("alice")) {
            var tasks = service.conversationTasks("session-1");
            assertThat(tasks).hasSize(1);
            assertThat(tasks.getFirst()).containsEntry("task_id", "task-1").containsEntry("cached", true);
            assertThat(tasks.getFirst()).doesNotContainKey("artifacts");
            assertThat(service.conversationTasks("another-session")).isEmpty();
        }
    }
    @Test void historyIncludesOlderTasksAndTheirConversationTraces() {
        jdbc.sql("""
                insert into assistant_plan(id,session_id,context_snapshot_id,goal,summary,version,
                plan_hash,risk_level,status,affected_resources_json,expires_at,created_at)
                values('plan-1','session-1','context','goal','summary',1,'hash','READ_ONLY',
                'SUCCEEDED','[]',current_timestamp,current_timestamp)
                """).update();
        jdbc.sql("""
                insert into assistant_plan_step(id,plan_id,step_order,tool_name,tool_mode,title,
                description,arguments_json,risk_level,requires_confirmation,status)
                values('step-1','plan-1',1,'browser.delegate','WRITE','title','','{}','READ_ONLY',false,'SUCCEEDED')
                """).update();
        jdbc.sql("""
                insert into assistant_run(id,session_id,plan_id,idempotency_key,trace_id,status,created_at)
                values('run-1','session-1','plan-1','key','trace','SUCCEEDED',current_timestamp)
                """).update();
        for (int i = 0; i < 11; i++) jdbc.sql("""
                insert into browser_agent_task(task_id,step_id,owner_id,session_id,last_result)
                values(:id,:step,'alice','session-1',:result)
                """).param("id", "older-" + i).param("step", "older-step-" + i)
                .param("result", "{\"status\":{\"state\":\"completed\"}}").update();
        try (var ignored = ActorContext.bind("alice")) {
            assertThat(service.status("task-1").get("conversationTraceIds"))
                    .isEqualTo(List.of("plan-1", "run-1"));
            assertThat(service.list("session-1")).hasSize(12);
        }
    }
    @Test void approvalShowsFullScopeButRejectsOversizedDescription() {
        var approval = BrowserAgentService.approvalDescription(Map.of("url", "https://example.com/?sid=private", "goal", "查询报表", "allowed_domains", List.of("login.example.com")));
        assertThat(approval).contains("查询报表", "login.example.com").doesNotContain("private");
        assertThatThrownBy(() -> BrowserAgentService.approvalDescription(Map.of("url", "https://example.com", "goal", "x".repeat(600), "allowed_domains", List.of("a".repeat(500) + ".com"))))
                .hasMessageContaining("不会截断");
    }
}
