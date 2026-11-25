package com.finflow.studio.assistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DynamicDomainApprovalTest {
    JdbcClient jdbc;
    AssistantExecutionService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = JdbcClient.create(ds);
        service = mock(AssistantExecutionService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        jdbc.sql("""
                insert into assistant_plan(id,session_id,context_snapshot_id,goal,summary,version,plan_hash,risk_level,status,
                affected_resources_json,expires_at,created_at) values ('p','s','c','visit','',1,'old','READ_ONLY','RUNNING','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).update();
    }
    @Test void explicitWebsiteConsentSurvivesLowRiskDynamicAppend() {
        var proposed = new AssistantModels.PlanStep("new", 0, "browser.delegate", "WRITE", "授权网站访问", "mail.example.com",
                Map.of("url", "https://mail.example.com"), AssistantModels.RiskLevel.READ_ONLY, true, "PENDING");
        AssistantModels.PlanStep saved = ReflectionTestUtils.invokeMethod(service, "appendDynamicStep", "p", proposed, "APPROVAL", "等待网站授权");
        assertThat(saved.requiresConfirmation()).isTrue();
        assertThat(jdbc.sql("select requires_confirmation from assistant_plan_step").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select status from assistant_plan").query(String.class).single()).isEqualTo("WAITING_CONFIRMATION");
    }
    @Test void recoveryRestoresPromptWithoutGrantingOrExecutingAndIsIdempotent() {
        jdbc.sql("""
                insert into assistant_plan_step(id,plan_id,step_order,tool_name,tool_mode,title,description,arguments_json,risk_level,requires_confirmation,status)
                values('step','p',1,'browser.delegate','WRITE','授权网站访问','mail.example.com','{}','READ_ONLY',false,'PENDING')
                """).update();
        jdbc.sql("""
                insert into assistant_run(id,session_id,plan_id,idempotency_key,trace_id,status,created_at)
                values('run','s','p','key','trace','WAITING_CONFIRMATION',CURRENT_TIMESTAMP)
                """).update();
        service.repairWaitingDomainApprovals();
        service.repairWaitingDomainApprovals();
        assertThat(jdbc.sql("select requires_confirmation from assistant_plan_step").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select version from assistant_plan").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select status from assistant_run").query(String.class).single()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(jdbc.sql("select count(*) from browser_user_grant").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from browser_agent_task").query(Integer.class).single()).isZero();
    }
}
