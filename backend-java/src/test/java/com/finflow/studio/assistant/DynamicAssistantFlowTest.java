package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workspace.WorkspaceFolderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-dynamic-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=true"
})
@AutoConfigureMockMvc
@Import(DynamicAssistantFlowTest.TestAgentConfiguration.class)
class DynamicAssistantFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProjectService projects;
    @Autowired WorkspaceFolderService folders;

    @Test
    void autoModeExecutesMultipleToolsAndReplansFromFreshWorkspaceState() throws Exception {
        var project = projects.create("动态 Auto", "多步执行测试");
        var session = json(postJson("/api/projects/" + project.id() + "/assistant/sessions", Map.of("title", "Auto")));

        var response = json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages", Map.of(
                "text", "创建临时目录后把它改名为最终目录", "page", "project-home",
                "clientContextVersion", 1, "executionMode", "AUTO")));
        var run = awaitStatus(response.get("run").get("id").asText(), "SUCCEEDED");
        var plan = json(getJson("/api/assistant/plans/" + response.get("plan").get("id").asText()));

        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(plan.get("steps")).extracting(step -> step.get("tool").asText())
                .containsExactly("workspace.inspect", "folder.create", "folder.rename");
        assertThat(plan.get("steps")).allSatisfy(step -> assertThat(step.get("status").asText()).isEqualTo("SUCCEEDED"));
        assertThat(folders.list(project.id())).extracting(item -> item.name()).containsExactly("最终目录");

        var events = json(getJson("/api/assistant/sessions/" + session.get("id").asText() + "/event-history"));
        assertThat(events).filteredOn(event -> "agent.observation".equals(event.get("type").asText())).hasSize(3);
        assertThat(events).filteredOn(event -> "agent.observation".equals(event.get("type").asText()))
                .anySatisfy(event -> assertThat(event.get("payload").get("message").asText()).contains("动态 Auto"));
        assertThat(events).anySatisfy(event -> assertThat(event.get("type").asText()).isEqualTo("agent.plan_updated"));
    }

    @Test
    void approvalModePausesForADynamicallyChosenWriteThenResumesTheSameRun() throws Exception {
        var project = projects.create("动态审批", "中途审批测试");
        var session = json(postJson("/api/projects/" + project.id() + "/assistant/sessions", Map.of("title", "审批")));

        var response = json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages", Map.of(
                "text", "审批场景：检查项目后创建审批目录", "page", "project-home",
                "clientContextVersion", 1, "executionMode", "APPROVAL")));
        var runId = response.get("run").get("id").asText();
        var waitingRun = awaitStatus(runId, "WAITING_CONFIRMATION");
        var plan = json(getJson("/api/assistant/plans/" + response.get("plan").get("id").asText()));

        assertThat(waitingRun.get("id").asText()).isEqualTo(runId);
        assertThat(plan.get("steps").get(plan.get("steps").size() - 1).get("tool").asText()).isEqualTo("folder.create");
        assertThat(plan.get("steps").get(plan.get("steps").size() - 1).get("requiresConfirmation").asBoolean()).isTrue();

        var resumed = json(postJson("/api/assistant/plans/" + plan.get("id").asText() + "/confirm", Map.of(
                "planVersion", plan.get("version").asInt(), "planHash", plan.get("planHash").asText(),
                "idempotencyKey", "dynamic-resume", "expectedResourceVersions", Map.of())));
        assertThat(resumed.get("id").asText()).isEqualTo(runId);
        assertThat(awaitStatus(runId, "SUCCEEDED").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(folders.list(project.id())).extracting(item -> item.name()).contains("审批目录");
    }

    @Test
    void longDynamicTaskCanContinuePastTheFormerTwelveActionLimit() throws Exception {
        var project = projects.create("动态长任务", "验证执行预算不会过早终止");
        var session = json(postJson("/api/projects/" + project.id() + "/assistant/sessions", Map.of("title", "长任务")));

        var response = json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages", Map.of(
                "text", "长任务：连续检查多个工作区状态后完成", "page", "project-home",
                "clientContextVersion", 1, "executionMode", "AUTO")));
        var run = awaitStatus(response.get("run").get("id").asText(), "SUCCEEDED");
        var plan = json(getJson("/api/assistant/plans/" + response.get("plan").get("id").asText()));

        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(plan.get("steps")).hasSize(15);
        assertThat(plan.get("steps")).filteredOn(step -> "project.list".equals(step.get("tool").asText())).hasSize(14);
    }

    private JsonNode awaitStatus(String runId, String expected) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        JsonNode run;
        do {
            run = json(getJson("/api/assistant/runs/" + runId));
            if (expected.equals(run.get("status").asText())) return run;
            if (List.of("FAILED", "CANCELED").contains(run.get("status").asText())) {
                throw new AssertionError("Run ended as " + run);
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not reach " + expected + ": " + run);
    }

    private String postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private String getJson(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    @TestConfiguration
    static class TestAgentConfiguration {
        @Bean
        @Primary
        WorkerClient scriptedWorkerClient() {
            return new WorkerClient("http://127.0.0.1:9") {
                @Override
                @SuppressWarnings("unchecked")
                public Map<String, Object> planAgent(Object value) {
                    var request = (Map<String, Object>) value;
                    var continuation = Boolean.TRUE.equals(request.get("continuation"));
                    var goal = String.valueOf(request.get("goal"));
                    var completed = ((Number) request.getOrDefault("completed_actions", 0)).intValue();
                    if (goal.contains("长任务")) {
                        if (completed < 14) return action("project.list", "检查工作区 " + (completed + 1),
                                "读取当前项目状态", Map.of());
                        return Map.of("summary", "长任务已经完成", "completed", true, "steps", List.of());
                    }
                    if (!continuation && goal.contains("审批场景")) {
                        return action("project.list", "检查项目", "先读取项目列表", Map.of());
                    }
                    if (!continuation) {
                        return action("folder.create", "创建临时目录", "先创建一个目录", Map.of(
                                "name", "临时目录", "group", "knowledge"));
                    }
                    if (goal.contains("审批场景") && completed == 1) {
                        return action("folder.create", "创建审批目录", "只读检查后创建目录", Map.of(
                                "name", "审批目录", "group", "knowledge"));
                    }
                    if (!goal.contains("审批场景") && completed == 1) {
                        var resources = (List<Map<String, Object>>) request.get("resources");
                        var folderId = resources.stream().filter(item -> "临时目录".equals(item.get("name")))
                                .findFirst().orElseThrow().get("id").toString();
                        return action("folder.rename", "重命名目录", "根据最新工作区继续重命名", Map.of(
                                "folder_id", folderId, "new_name", "最终目录"));
                    }
                    return Map.of("summary", "目标已经完成", "completed", true, "steps", List.of());
                }

                private Map<String, Object> action(String tool, String title, String description,
                                                   Map<String, Object> arguments) {
                    return Map.of("summary", title, "completed", false, "steps", List.of(Map.of(
                            "tool", tool, "title", title, "description", description, "arguments", arguments)));
                }
            };
        }
    }
}
