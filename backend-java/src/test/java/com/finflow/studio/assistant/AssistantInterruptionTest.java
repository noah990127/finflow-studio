package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workspace.WorkspaceFolderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-interrupt;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AssistantInterruptionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectService projects;
    @Autowired WorkspaceFolderService folders;
    @MockitoBean WorkerClient worker;

    @Test
    void interruptsInitialThinkingAndAllowsANewMessage() throws Exception {
        var session = session();
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(worker.planAgent(any())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { stopped.countDown(); throw new CancellationException(); }
            return answer();
        });
        var response = CompletableFuture.supplyAsync(() -> send(session, "thinking", "AUTO"));
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        postJson("/api/assistant/sessions/" + session + "/requests/thinking/cancel", Map.of());
        assertThat(response.get(3, TimeUnit.SECONDS).get("plan").isNull()).isTrue();
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        var history = getJson("/api/assistant/sessions/" + session + "/messages");
        assertThat(history).anySatisfy(item -> assertThat(item.get("content").asText()).contains("已停止本次思考"));
        when(worker.planAgent(any())).thenReturn(answer());
        var next = send(session, "next", "AUTO");
        awaitStatus(next.get("run").get("id").asText(), "SUCCEEDED");
    }

    @Test
    void cancellationBeforeMessageArrivalDoesNotStartTheModel() throws Exception {
        var session = session();
        postJson("/api/assistant/sessions/" + session + "/requests/early/cancel", Map.of());
        assertThat(send(session, "early", "AUTO").get("plan").isNull()).isTrue();
        verify(worker, never()).planAgent(any());
    }

    @Test
    void cancelsApprovalWithoutExecutingTheWrite() throws Exception {
        var session = session();
        when(worker.planAgent(any())).thenReturn(folderAction());
        var response = send(session, "approval", "APPROVAL");
        var plan = response.get("plan");
        postJson("/api/assistant/plans/" + plan.get("id").asText() + "/cancel", Map.of());
        assertThat(getJson("/api/assistant/plans/" + plan.get("id").asText()).get("status").asText()).isEqualTo("CANCELED");
        mvc.perform(post("/api/assistant/plans/" + plan.get("id").asText() + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "planVersion", plan.get("version").asInt(), "planHash", plan.get("planHash").asText(),
                        "idempotencyKey", UUID.randomUUID().toString(), "expectedResourceVersions", Map.of()))))
                .andExpect(status().is4xxClientError());
        assertThat(folders.list(response.get("context").get("projectId").asText())).isEmpty();
    }

    @Test
    void interruptsReplanningButKeepsCompletedWrites() throws Exception {
        var session = session();
        var entered = new CountDownLatch(1);
        when(worker.planAgent(any())).thenAnswer(call -> {
            var request = (Map<?, ?>) call.getArgument(0);
            if (!Boolean.TRUE.equals(request.get("continuation"))) return folderAction();
            entered.countDown();
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { throw new CancellationException(); }
            return answer();
        });
        var response = send(session, "running", "AUTO");
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var id = response.get("run").get("id").asText();
        postJson("/api/assistant/runs/" + id + "/cancel", Map.of());
        postJson("/api/assistant/runs/" + id + "/cancel", Map.of());
        awaitStatus(id, "CANCELED");
        assertThat(folders.list(response.get("context").get("projectId").asText())).extracting(folder -> folder.name()).containsExactly("中断测试");
        assertThat(getJson("/api/assistant/sessions/" + session + "/event-history"))
                .noneSatisfy(event -> assertThat(event.get("type").asText()).isIn("agent.completed", "agent.failed"));
    }

    @Test
    void lateToolResultCannotResurrectCanceledRun() throws Exception {
        var session = session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(worker.planAgent(any())).thenReturn(Map.of("summary", "回答", "steps", List.of(Map.of(
                "tool", "assistant.respond", "title", "回答", "description", "读取后回答", "arguments", Map.of()))));
        when(worker.summarize(anyString(), anyString(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of("summary", "迟到的回答");
        });
        var response = send(session, "late", "AUTO");
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var id = response.get("run").get("id").asText();
        postJson("/api/assistant/runs/" + id + "/cancel", Map.of());
        release.countDown();
        for (int i = 0; i < 60; i++) {
            var run = getJson("/api/assistant/runs/" + id);
            if (run.get("result").path("assistantResponse").asText().equals("迟到的回答")) break;
            Thread.sleep(25);
        }
        var run = getJson("/api/assistant/runs/" + id);
        assertThat(run.get("result").path("assistantResponse").asText()).isEqualTo("迟到的回答");
        assertThat(run.get("status").asText()).isEqualTo("CANCELED");
        verify(worker, times(1)).planAgent(any());
    }

    private String session() throws Exception {
        var project = projects.create("中断回归", "测试");
        return postJson("/api/projects/" + project.id() + "/assistant/sessions", Map.of("title", "中断")).get("id").asText();
    }
    private JsonNode send(String session, String id, String mode) {
        try { return postJson("/api/assistant/sessions/" + session + "/messages", Map.of(
                "text", "执行中断回归", "page", "project-home", "requestId", id, "executionMode", mode)); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }
    private JsonNode postJson(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void awaitStatus(String id, String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (getJson("/api/assistant/runs/" + id).get("status").asText().equals(expected)) return;
            Thread.sleep(25);
        }
        assertThat(getJson("/api/assistant/runs/" + id).get("status").asText()).isEqualTo(expected);
    }
    private Map<String, Object> answer() { return Map.of("summary", "新的回答", "completed", true, "steps", List.of()); }
    private Map<String, Object> folderAction() {
        return Map.of("summary", "创建目录", "steps", List.of(Map.of("tool", "folder.create", "title", "创建目录",
                "description", "创建测试目录", "arguments", Map.of("name", "中断测试", "group", "knowledge"))));
    }
}
