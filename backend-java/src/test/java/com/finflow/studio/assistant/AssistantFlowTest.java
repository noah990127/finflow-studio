package com.finflow.studio.assistant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=false"
})
@AutoConfigureMockMvc
class AssistantFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void modifyingPlanRequiresAValidConfirmation() throws Exception {
        var project = json(postJson("/api/projects", Map.of(
                "name", "测试项目",
                "description", "助手闭环测试"
        )));
        var session = json(postJson("/api/projects/" + project.get("id").asText() + "/assistant/sessions",
                Map.of("title", "测试会话")));
        var response = json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages",
                Map.of(
                        "text", "根据当前收入字段创建一条数据清理工作流，并生成 PPT 汇报",
                        "page", "workflow",
                        "selection", Map.of(
                                "type", "dataset_columns",
                                "resourceId", "dataset-1",
                                "range", new String[]{"收入"}
                        ),
                        "clientContextVersion", 3
                )));

        var plan = response.get("plan");
        assertThat(plan.get("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(plan.get("steps")).anySatisfy(step ->
                assertThat(step.get("requiresConfirmation").asBoolean()).isTrue());

        var confirmBody = Map.of(
                "planVersion", plan.get("version").asInt(),
                "planHash", plan.get("planHash").asText(),
                "idempotencyKey", "test-key-1",
                "expectedResourceVersions", Map.of("dataset-1", 3)
        );
        var run = json(postJson("/api/assistant/plans/" + plan.get("id").asText() + "/confirm", confirmBody));
        assertThat(run.get("id").asText()).isNotBlank();
    }

    @Test
    void exposesSearchableToolCatalogWithRiskAndConfirmationPolicy() throws Exception {
        var tools = json(mockMvc.perform(post("/api/assistant/tools/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("query", "workflow"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.get("id").asText()).isEqualTo("workflow.run");
            assertThat(tool.get("risk").asText()).isEqualTo("CREATE_VERSION");
            assertThat(tool.get("requiresConfirmation").asBoolean()).isTrue();
        });
    }

    @Test
    void planningPublishesAgentActivityEvents() throws Exception {
        var project = json(postJson("/api/projects", Map.of(
                "name", "事件流测试",
                "description", "验证 Agent 活动流"
        )));
        var session = json(postJson("/api/projects/" + project.get("id").asText() + "/assistant/sessions",
                Map.of("title", "事件流")));
        json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages",
                Map.of("text", "打开项目概览", "page", "project-home", "clientContextVersion", 1)));

        var history = json(mockMvc.perform(get("/api/assistant/sessions/" + session.get("id").asText() + "/event-history"))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(history).anySatisfy(event -> assertThat(event.get("type").asText()).isEqualTo("agent.thinking_summary"));
        assertThat(history).anySatisfy(event -> assertThat(event.get("type").asText()).isEqualTo("agent.plan_updated"));
    }

    private String postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
