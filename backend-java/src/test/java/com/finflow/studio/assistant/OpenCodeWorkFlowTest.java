package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:opencode-work;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "finflow.auth.enabled=false"})
@AutoConfigureMockMvc
@Import(OpenCodeWorkFlowTest.Configuration.class)
class OpenCodeWorkFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectService projects;
    @Autowired BrowserAuthorizationService browserAuthorizations;
    @Autowired com.finflow.studio.workflow.WorkflowDefinitionService workflows;

    @Test void batchDeletionNeedsOnlyOneConfirmationAndExecutesBeforeCliResumes() throws Exception {
        var p = projects.create("batch approval", "");
        for (int i = 0; i < 15; i++) workflows.create(p.id(), new com.finflow.studio.workflow.WorkflowModels.SaveRequest("delete" + i, "", List.of(), List.of()));
        var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "batch"));
        var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", p.id(), "text", "batch-delete-test", "page", "project-home", "executionMode", "AUTO"));
        var runId = response.path("run").path("id").asText();
        JsonNode run = null;
        for (int i = 0; i < 100; i++) {
            run = read("/api/assistant/runs/" + runId);
            if ("WAITING_CONFIRMATION".equals(run.path("status").asText())) break;
            Thread.sleep(30);
        }
        assertThat(run.path("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(workflows.list(p.id())).hasSize(15);
        var planId = response.path("plan").path("id").asText();
        var plan = read("/api/assistant/plans/" + planId);
        assertThat(plan.path("steps")).anySatisfy(s -> assertThat(s.path("description").asText()).contains("15 项"));
        var later = workflows.create(p.id(), new com.finflow.studio.workflow.WorkflowModels.SaveRequest("keep", "", List.of(), List.of()));
        postJson("/api/assistant/plans/" + planId + "/confirm", Map.of("planVersion", plan.path("version").asInt(),
                "planHash", plan.path("planHash").asText(), "idempotencyKey", java.util.UUID.randomUUID().toString(), "expectedResourceVersions", Map.of()));
        assertThat(terminal(runId).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(workflows.list(p.id())).extracting(w -> w.id()).containsExactly(later.id());
    }

    @Test void waitingForBusinessInputDoesNotClaimWorkIsComplete() throws Exception {
        var p = projects.create("OpenCode业务工序", "");
        var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "口径核对"));
        var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", p.id(), "text", "核对跨月退款", "page", "project-home", "executionMode", "AUTO"));
        var run = terminal(response.path("run").path("id").asText());
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("result").path("workStatus").asText()).isEqualTo("WAITING_INPUT");
        assertThat(run.path("resultSummary").asText()).contains("需要你的输入").contains("哪个月份");
        var works = read("/api/projects/" + p.id() + "/works");
        assertThat(works.get(0).path("stages").get(0).path("title").asText()).isEqualTo("统一口径");
        assertThat(works.get(0).path("stages").get(0).path("status").asText()).isEqualTo("WAITING_INPUT");
    }

    @Test void unfinishedBusinessStagesFailEvenWhenCliReturnsCompleted() throws Exception {
        var p = projects.create("未完成工序", "");
        var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "未完成"));
        var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", p.id(), "text", "unfinished", "page", "project-home", "executionMode", "AUTO"));
        var run = terminal(response.path("run").path("id").asText());
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("resultSummary").asText()).contains("业务工序尚未完成");
    }

    @Test void failedBusinessStagePreservesActionableReason() throws Exception {
        var p = projects.create("浏览器阻塞", "");
        var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "能力检查"));
        var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", p.id(), "text", "browser-blocked", "page", "project-home", "executionMode", "AUTO"));
        var run = terminal(response.path("run").path("id").asText());
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("resultSummary").asText()).contains("浏览器服务未配置授权凭证").doesNotContain("不能报告完成");
    }

    @Test void browserSubmissionRequiresConfirmationEvenInAutoMode() throws Exception {
        browserAuthorizations.registerSession(Map.of("url", "https://example.com/"), "test-session");
        var p = projects.create("浏览器审批验证", "");
        var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "浏览器审批"));
        var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                Map.of("projectId", p.id(), "text", "browser-approval", "page", "project-home", "executionMode", "AUTO"));
        var runId = response.path("run").path("id").asText();
        JsonNode run = null;
        for (int i = 0; i < 100; i++) {
            run = read("/api/assistant/runs/" + runId);
            if ("WAITING_CONFIRMATION".equals(run.path("status").asText())) break;
            Thread.sleep(30);
        }
        assertThat(run.path("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(run.path("result").has("browserResult")).isFalse();
        var planId = response.path("plan").path("id").asText();
        var plan = read("/api/assistant/plans/" + planId);
        assertThat(plan.path("steps")).anySatisfy(step -> assertThat(step.path("description").asText()).contains("test@example.invalid"));
        assertThat(plan.path("steps")).allSatisfy(step -> {
            assertThat(step.path("description").asText().length()).isLessThanOrEqualTo(1000);
            assertThat(step.path("description").asText()).doesNotContain("snapshot-only", "diagnostics-only");
        });
        postJson("/api/assistant/plans/" + planId + "/confirm", Map.of("planVersion", plan.path("version").asInt(),
                "planHash", plan.path("planHash").asText(), "idempotencyKey", java.util.UUID.randomUUID().toString(), "expectedResourceVersions", Map.of()));
        assertThat(terminal(runId).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void rememberedConnectionSkipsSecondApprovalAndRevocationIsAudited() throws Exception {
        var p = projects.create("网站授权记忆验证", "");
        for (int attempt = 0; attempt < 2; attempt++) {
            var session = postJson("/api/projects/" + p.id() + "/assistant/sessions", Map.of("title", "连接"));
            var response = postJson("/api/assistant/sessions/" + session.path("id").asText() + "/messages",
                    Map.of("projectId", p.id(), "text", "browser-connect-memory", "page", "project-home", "executionMode", "AUTO"));
            var runId = response.path("run").path("id").asText();
            if (attempt == 0) {
                JsonNode run = null;
                for (int i = 0; i < 100; i++) {
                    run = read("/api/assistant/runs/" + runId);
                    if ("WAITING_CONFIRMATION".equals(run.path("status").asText())) break;
                    Thread.sleep(30);
                }
                assertThat(run.path("status").asText()).isEqualTo("WAITING_CONFIRMATION");
                var planId = response.path("plan").path("id").asText();
                var plan = read("/api/assistant/plans/" + planId);
                postJson("/api/assistant/plans/" + planId + "/confirm", Map.of("planVersion", plan.path("version").asInt(),
                        "planHash", plan.path("planHash").asText(), "idempotencyKey", java.util.UUID.randomUUID().toString(),
                        "expectedResourceVersions", Map.of(), "rememberBrowserAuthorization", true));
            }
            assertThat(terminal(runId).path("status").asText()).isEqualTo("SUCCEEDED");
        }
        var grants = read("/api/browser/authorizations");
        var grant = grants.get(0);
        mvc.perform(delete("/api/browser/authorizations/" + grant.path("id").asText())).andExpect(status().isNoContent());
        assertThat(read("/api/browser/authorizations/history")).anySatisfy(item -> assertThat(item.path("event").asText()).isEqualTo("AUTHORIZATION_REUSED"));
        assertThat(read("/api/browser/authorizations/history")).anySatisfy(item -> assertThat(item.path("event").asText()).isEqualTo("REVOKED"));
    }

    JsonNode terminal(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            var run = read("/api/assistant/runs/" + id);
            if (List.of("SUCCEEDED", "FAILED").contains(run.path("status").asText())) return run;
            Thread.sleep(30);
        }
        throw new AssertionError("Run did not finish");
    }
    JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode postJson(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary WorkerClient opencodeWorker(ObjectProvider<AssistantExecutionService> execution,
                @org.springframework.beans.factory.annotation.Value("${test.agent.runtime:opencode}") String runtime) {
            return new WorkerClient("http://127.0.0.1:9") {
                @Override public Map<String, Object> browser(String operation, Object body) {
                    var arguments = (Map<?, ?>) body;
                    assertThat(arguments.get("owner")).isNotNull();
                    if ("session/preview".equals(operation)) return Map.of(
                            "site", "https://example.com", "action", "click", "target", Map.of("name", "发送"),
                            "value", "test@example.invalid", "scopeHash", "a".repeat(64),
                            "page", Map.of("snapshot", "snapshot-only".repeat(2000), "diagnostics", "diagnostics-only".repeat(2000)));
                    if ("session/commit".equals(operation)) return Map.of("actionExecuted", true);
                    if ("session/open".equals(operation)) {
                        assertThat(arguments.get("user_authorized")).isEqualTo(true);
                        return Map.of("session", java.util.UUID.randomUUID().toString(), "status", "READY");
                    }
                    if ("session/close".equals(operation)) return Map.of("status", "CLOSED");
                    throw new AssertionError("Unexpected browser operation " + operation);
                }
                @Override public Map<String, Object> health() { return Map.of("status", "online", "agentRuntimeMode", runtime, "continuousAgent", true); }
                @Override public Map<String, Object> planAgent(Object request) { throw new AssertionError("Legacy planner must not run"); }
                @Override @SuppressWarnings("unchecked") public Map<String, Object> runContinuousAgentStreaming(Object value, Consumer<Map<String, Object>> events) {
                    var req = (Map<String, Object>) value;
                    if ("browser-connect-memory".equals(req.get("goal"))) {
                        var service = execution.getObject();
                        var observation = (Map<String, Object>) req.get("observation");
                        var previous = (Map<String, Object>) observation.get("previousEffects");
                        if (!previous.containsKey("browserResult")) {
                            var result = service.callContinuousTool(req.get("run_id").toString(), req.get("gateway_token").toString(),
                                    Map.of("tool", "browser.open", "arguments", Map.of("url", "https://authorization-test.example/")));
                            if ("AUTHORIZATION".equals(result.get("failureType")))
                                return Map.of("type", "waiting_confirmation", "actions", List.of(Map.of("toolName", "browser.open", "arguments", Map.of("url", "https://authorization-test.example/"))));
                            assertThat(result.get("success")).isEqualTo(true);
                        }
                        service.callContinuousTool(req.get("run_id").toString(), req.get("gateway_token").toString(),
                                Map.of("tool", "work.update", "arguments", Map.of("stages", List.of(Map.of(
                                        "id", "connect", "title", "连接网站", "status", "SUCCEEDED", "summary", "连接已验证", "inputs", List.of(), "outputs", List.of())))));
                        return Map.of("type", "completed", "content", "连接成功");
                    }
                    if ("browser-approval".equals(req.get("goal"))) {
                        var service = execution.getObject();
                        var observation = (Map<String, Object>) req.get("observation");
                        var previous = (Map<String, Object>) observation.get("previousEffects");
                        if (previous.containsKey("browserResult")) {
                            assertThat((List<?>) observation.get("approvedActions")).isEmpty();
                            service.callContinuousTool(req.get("run_id").toString(), req.get("gateway_token").toString(),
                                    Map.of("tool", "work.update", "arguments", Map.of("stages", List.of(Map.of(
                                            "id", "browser", "title", "检查发送结果", "status", "SUCCEEDED", "summary", "模拟服务已验证", "inputs", List.of(), "outputs", List.of())))));
                            return Map.of("type", "completed", "content", "测试操作完成");
                        }
                        var args = Map.of("session", "test-session", "pending", "test-pending");
                        var blocked = service.callContinuousTool(req.get("run_id").toString(), req.get("gateway_token").toString(),
                                Map.of("tool", "browser.commit", "arguments", args));
                        assertThat(blocked.get("success")).isEqualTo(false);
                        return Map.of("type", "waiting_confirmation", "actions", List.of(Map.of("toolName", "browser.commit", "arguments", args)));
                    }
                    if ("batch-delete-test".equals(req.get("goal"))) {
                        var service = execution.getObject();
                        var runId = req.get("run_id").toString();
                        var token = req.get("gateway_token").toString();
                        var observation = (Map<String, Object>) req.get("observation");
                        var previous = (Map<String, Object>) observation.get("previousEffects");
                        if (previous.containsKey("deletionResult")) {
                            var deleted = (Map<String, Object>) previous.get("deletionResult");
                            assertThat(deleted.get("count")).isEqualTo(15);
                            assertThat((List<?>) observation.get("approvedActions")).isEmpty();
                            var repeated = service.callContinuousTool(runId, token, Map.of("tool", "resource.delete_batch", "arguments",
                                    Map.of("project_id", req.get("project_id"), "items", deleted.get("deleted"))));
                            assertThat(repeated.get("success")).isEqualTo(true);
                            service.callContinuousTool(runId, token, Map.of("tool", "work.update", "arguments", Map.of("stages", List.of(
                                    Map.of("id", "cleanup", "title", "清理", "status", "SUCCEEDED", "summary", "已验证15项删除", "inputs", List.of(), "outputs", List.of())))));
                            return Map.of("type", "completed", "content", "已完成清理");
                        }
                        var preview = service.callContinuousTool(runId, token, Map.of("tool", "resource.prepare_delete", "arguments",
                                Map.of("project_id", req.get("project_id"), "scope", "project_contents")));
                        assertThat(preview.get("success")).isEqualTo(true);
                        var output = (Map<String, Object>) preview.get("output");
                        var items = (List<?>) ((Map<?, ?>) output.get("deletionPreview")).get("items");
                        assertThat(items).hasSize(15);
                        var args = Map.of("project_id", req.get("project_id"), "items", items);
                        var blocked = service.callContinuousTool(runId, token, Map.of("tool", "resource.delete_batch", "arguments", args));
                        assertThat(blocked.get("success")).isEqualTo(false);
                        return Map.of("type", "waiting_confirmation", "actions", List.of(Map.of("toolName", "resource.delete_batch", "arguments", args)));
                    }
                    assertThat((List<Map<String, Object>>) req.get("capabilities")).anyMatch(c -> "deliverable.create".equals(c.get("id")));
                    var unfinished = "unfinished".equals(req.get("goal"));
                    var blocked = "browser-blocked".equals(req.get("goal"));
                    var stage = Map.of("id", "scope", "title", "统一口径", "status", blocked ? "FAILED" : unfinished ? "RUNNING" : "WAITING_INPUT",
                            "summary", blocked ? "浏览器服务未配置授权凭证" : "发现跨月退款", "question", unfinished || blocked ? "" : "退款归属哪个月份？", "inputs", List.of(), "outputs", List.of());
                    execution.getObject().callContinuousTool(req.get("run_id").toString(), req.get("gateway_token").toString(),
                            Map.of("tool", "work.update", "arguments", Map.of("stages", List.of(stage))));
                    return Map.of("type", "completed", "content", "模型声称已完成");
                }
            };
        }
    }
}
