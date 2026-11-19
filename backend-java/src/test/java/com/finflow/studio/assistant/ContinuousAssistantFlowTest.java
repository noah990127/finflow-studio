package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:continuous-agent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=false"
})
@AutoConfigureMockMvc
@Import(ContinuousAssistantFlowTest.Configuration.class)
class ContinuousAssistantFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectService projects;
    @Autowired ScriptedWorker worker;

    @Test
    void deepAgentsOwnsTheTaskLoopWithoutCallingTheLegacyPlanner() throws Exception {
        worker.legacyPlanCalls.set(0);
        worker.continuousCalls.set(0);
        var response = send("只读取当前项目并回答", "AUTO");
        var run = awaitStatus(response.path("run").path("id").asText(), "SUCCEEDED");
        var plan = read("/api/assistant/plans/" + response.path("plan").path("id").asText());

        assertThat(run.path("resultSummary").asText()).isEqualTo("已在同一个 Agent 循环中完成");
        assertThat(plan.path("steps")).extracting(item -> item.path("tool").asText())
                .containsExactly("agent.execute");
        assertThat(worker.legacyPlanCalls.get()).isZero();
        assertThat(worker.continuousCalls.get()).isEqualTo(1);
    }

    @Test
    void semanticReadOnlyDecisionSurvivesAnAttemptedContinuousGatewayWrite() throws Exception {
        var response = send("请修改本体候选", "AUTO");
        var run = awaitStatus(response.at("/run/id").asText(), "SUCCEEDED");
        assertThat(run.path("resultSummary").asText()).contains("只读");
        var plan = read("/api/assistant/plans/" + response.at("/plan/id").asText());
        assertThat(plan.path("steps")).extracting(item -> item.path("tool").asText()).containsExactly("agent.execute");
    }

    @Test
    void completionWithoutARecordedCandidateSubmissionFailsTheRun() throws Exception {
        var response = send("请提交本体候选", "AUTO");
        var run = awaitStatus(response.at("/run/id").asText(), "FAILED");
        assertThat(run.path("resultSummary").asText()).contains("尚未");
    }

    @Test
    void approvalInterruptIsAttachedToTheSameRun() throws Exception {
        var response = send("需要审批：创建资料目录", "APPROVAL");
        var runId = response.path("run").path("id").asText();
        awaitStatus(runId, "WAITING_CONFIRMATION");
        var plan = read("/api/assistant/plans/" + response.path("plan").path("id").asText());

        assertThat(plan.path("steps")).extracting(item -> item.path("tool").asText())
                .containsExactly("agent.execute", "folder.create");
        assertThat(plan.path("steps").get(1).path("requiresConfirmation").asBoolean()).isTrue();
        var events = read("/api/assistant/sessions/" + response.path("sessionId").asText() + "/event-history");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("runId").asText()).isEqualTo(runId);
            assertThat(event.path("type").asText()).isEqualTo("agent.waiting_confirmation");
        });
    }

    private JsonNode send(String text, String mode) throws Exception {
        var project = projects.create("连续 Agent", "测试");
        var session = parse(postJson("/api/projects/" + project.id() + "/assistant/sessions", Map.of("title", "连续任务")));
        return parse(postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", project.id(), "text", text, "page", "project-home", "executionMode", mode)));
    }

    private JsonNode awaitStatus(String runId, String expected) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        JsonNode value;
        do {
            value = read("/api/assistant/runs/" + runId);
            if (expected.equals(value.path("status").asText())) return value;
            Thread.sleep(30);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not reach " + expected + ": " + value);
    }

    private String postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode read(String path) throws Exception {
        return parse(mvc.perform(get(path)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode parse(String value) throws Exception { return json.readTree(value); }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary
        ScriptedWorker continuousWorker(org.springframework.beans.factory.ObjectProvider<AssistantExecutionService> execution) {
            return new ScriptedWorker(execution);
        }
    }

    static class ScriptedWorker extends WorkerClient {
        final AtomicInteger legacyPlanCalls = new AtomicInteger();
        final AtomicInteger continuousCalls = new AtomicInteger();

        final org.springframework.beans.factory.ObjectProvider<AssistantExecutionService> execution;

        ScriptedWorker(org.springframework.beans.factory.ObjectProvider<AssistantExecutionService> execution) {
            super("http://127.0.0.1:9");
            this.execution = execution;
        }

        @Override public Map<String,Object> tacitIntent(Map<String,Object> request) {
            var result = new java.util.LinkedHashMap<String,Object>();
            result.put("mode", "请提交本体候选".equals(request.get("directive")) ? "execute" : "discuss");
            result.put("task_intent","build_ontology"); result.put("reason","当前业务意图"); result.put("question",null);
            return result;
        }

        @Override public Map<String, Object> health() {
            return Map.of("status", "online", "agentRuntimeMode", "deep-agents", "continuousAgent", true);
        }

        @Override public Map<String, Object> planAgent(Object request) {
            legacyPlanCalls.incrementAndGet();
            throw new AssertionError("Legacy planner must not be called");
        }

        @Override @SuppressWarnings("unchecked")
        public Map<String, Object> runContinuousAgentStreaming(Object value,
                                                               Consumer<Map<String, Object>> events) {
            continuousCalls.incrementAndGet();
            var request = (Map<String, Object>) value;
            if ("请修改本体候选".equals(request.get("goal"))) {
                assertThat((List<Map<String,Object>>)request.get("capabilities")).extracting(c -> c.get("id"))
                        .contains("tacit.search_context").doesNotContain("tacit.edit_candidates","work.update");
                var denied = execution.getObject().callContinuousTool(request.get("run_id").toString(),
                        request.get("gateway_token").toString(), Map.of("tool","tacit.edit_candidates",
                                "arguments",Map.of("expected_revision",0,"remove_ids",List.of("candidate"))));
                assertThat(denied.get("failureType")).isEqualTo("TASK_SCOPE");
                return Map.of("type","completed","content","已修改本体候选");
            }
            if (String.valueOf(request.get("goal")).contains("需要审批")) {
                return Map.of("type", "waiting_confirmation", "approvalCount", 1,
                        "actions", List.of(Map.of("toolName", "folder_create", "arguments",
                                Map.of("project_id", request.get("project_id"), "name", "资料", "group", "KNOWLEDGE"))));
            }
            return Map.of("type", "completed", "content", "已在同一个 Agent 循环中完成");
        }
    }
}
