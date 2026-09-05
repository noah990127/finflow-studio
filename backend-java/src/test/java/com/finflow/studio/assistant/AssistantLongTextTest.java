package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-long-text-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.worker.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class AssistantLongTextTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectService projects;
    @Autowired AssistantService assistant;
    @MockitoBean AssistantPlanner planner;

    @ParameterizedTest
    @ValueSource(ints = {1001, 3864, 20000, 100000})
    void preservesFullMessageAndPlanGoalAcrossApiReads(int length) throws Exception {
        var text = "开头\n" + "完整的用户要求。".repeat(length / 8 + 1).substring(0, length) + "\n结尾：不得截断。";
        assertThat(text.length()).isGreaterThan(1000);
        var step = new AssistantModels.PlanStep(UUID.randomUUID().toString(), 1, "folder.create", "tool", "创建目录", "待确认",
                Map.of(), AssistantModels.RiskLevel.CREATE_VERSION, true, "PENDING");
        when(planner.plan(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new AssistantPlanner.PlannedWork("已安排工作", List.of(step), false));
        var project = projects.create("长文本回归", "隔离测试");
        var session = assistant.createSession(project.id(), "长文本");
        var response = mvc.perform(post("/api/assistant/sessions/" + session.id() + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "text", text, "page", "project-home", "executionMode", "APPROVAL"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        var plan = json.readTree(response.getContentAsByteArray()).get("plan");
        assertThat(plan.get("goal").asText()).isEqualTo(text);
        var restored = mvc.perform(get("/api/assistant/plans/" + plan.get("id").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(restored.getContentAsByteArray()).get("goal").asText()).isEqualTo(text);
        var history = mvc.perform(get("/api/assistant/sessions/" + session.id() + "/messages"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(history.getContentAsByteArray())).anySatisfy(message -> {
            assertThat(message.get("role").asText()).isEqualTo("USER");
            assertThat(message.get("content").asText()).isEqualTo(text);
        });
        verify(planner).plan(eq(text), anyString(), any(), any(), eq(session.id()), eq("APPROVAL"));
    }

    @Test
    void upgradesExistingSchemaWithoutLosingGoalsAndCanRunAgain() throws Exception {
        var resource = new ClassPathResource("schema.sql");
        String schema;
        try (var input = resource.getInputStream()) { schema = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        var legacy = schema.replace("goal text not null", "goal varchar(1000) not null")
                .replace("alter table assistant_plan alter column goal type text;", "");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(legacy.getBytes(StandardCharsets.UTF_8)));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into assistant_plan(id,session_id,context_snapshot_id,goal,summary,version,plan_hash,
                          risk_level,status,affected_resources_json,expires_at,created_at)
                        values ('old','session','context','已有目标','已有摘要',1,'hash','READ_ONLY','READY','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
            }
            ScriptUtils.executeSqlScript(connection, resource);
            ScriptUtils.executeSqlScript(connection, resource);
            try (var query = connection.createStatement(); var result = query.executeQuery("select goal from assistant_plan where id='old'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("已有目标");
            }
            var longGoal = "完整需求\n".repeat(20000);
            try (var update = connection.prepareStatement("update assistant_plan set goal=? where id='old'")) {
                update.setString(1, longGoal);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (var query = connection.createStatement(); var result = query.executeQuery("select goal from assistant_plan where id='old'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(longGoal);
            }
        }
    }
}
