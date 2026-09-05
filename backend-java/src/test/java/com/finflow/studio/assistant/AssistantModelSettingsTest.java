package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:model-settings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.agent.key-file=./target/model-settings-test.key"})
@AutoConfigureMockMvc
class AssistantModelSettingsTest {
    @Autowired AssistantModelSettings settings;
    @Autowired AssistantService assistant;
    @Autowired AssistantPlanner planner;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean WorkerClient worker;

    private String session() { return assistant.createSession(projects.create("模型测试", "").id(), "模型测试").id(); }
    private AssistantModelSettings.Update custom() { return new AssistantModelSettings.Update("CUSTOM", "https://model.example/v1/chat/completions", "test-model", "test-private-key"); }

    @Test void defaultsStayUnchangedAndSecretsAreEncryptedAndNeverReturned() throws Exception {
        var id = session();
        assertThat(settings.resolve(id)).isEmpty();
        var saved = settings.save(id, custom());
        assertThat(saved.baseUrl()).isEqualTo("https://model.example/v1");
        assertThat(saved.hasKey()).isTrue();
        var encrypted = jdbc.queryForObject("select encrypted_key from assistant_model_settings where session_id = ?", String.class, id);
        assertThat(encrypted).doesNotContain("test-private-key");
        assertThat(settings.resolve(id).get("api_key")).isEqualTo("test-private-key");
        var response = mvc.perform(get("/api/assistant/sessions/" + id + "/model")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("test-private-key", "encrypted_key", "apiKey");
        assertThat(settings.resolve(session())).isEmpty();
        settings.save(id, new AssistantModelSettings.Update("DEFAULT", null, null, null));
        assertThat(settings.resolve(id)).isEmpty();
        assertThat(settings.get(id).hasKey()).isTrue();
        settings.save(id, new AssistantModelSettings.Update("CUSTOM", saved.baseUrl(), saved.model(), ""));
        assertThat(settings.resolve(id).get("api_key")).isEqualTo("test-private-key");
        settings.clear(id);
        assertThat(settings.get(id).hasKey()).isFalse();
    }

    @Test void neverReusesAKeyForADifferentEndpointAndRejectsUnsafeUrls() {
        var id = session();
        settings.save(id, custom());
        assertThatThrownBy(() -> settings.save(id, new AssistantModelSettings.Update("CUSTOM", "https://other.example/v1", "model", "")))
                .isInstanceOf(IllegalArgumentException.class);
        for (var url : List.of("http://public.example/v1", "https://key@host/v1", "https://host/v1?key=secret", "file:///tmp/key", "https://169.254.169.254/")) {
            assertThatThrownBy(() -> AssistantModelSettings.normalizeUrl(url)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AssistantModelSettings.normalizeUrl("http://localhost:11434/v1/")).isEqualTo("http://localhost:11434/v1");
    }

    @Test void routesInitialAndFollowupDecisionsToCustomModelWithoutSilentFallback() {
        var id = session();
        settings.save(id, custom());
        when(worker.planAgent(any())).thenReturn(Map.of("summary", "已回答", "completed", true, "steps", List.of()));
        planner.plan("你好", "project-home", null, AssistantPlanner.WorkspaceContext.empty(), id, "AUTO");
        planner.continueAfterObservation("你好", "project-home", AssistantPlanner.WorkspaceContext.empty(), id, "AUTO", Map.of("result", "ok"), 1);
        verify(worker, times(2)).planAgent(argThat(value -> ((Map<?, ?>) value).get("model_config") instanceof Map<?, ?> config
                && "test-model".equals(config.get("model")) && "test-private-key".equals(config.get("api_key"))));
        when(worker.planAgent(any())).thenThrow(new IllegalStateException("provider echoed test-private-key"));
        assertThatThrownBy(() -> planner.plan("创建项目", "project-home", null, AssistantPlanner.WorkspaceContext.empty(), id, "AUTO"))
                .hasMessageContaining("自定义模型请求失败").hasMessageNotContaining("test-private-key");
    }

    @Test void testDoesNotSaveConfigurationAndApprovalPreventsSwitching() throws Exception {
        var id = session();
        when(worker.testAgentModel(any())).thenReturn(Map.of("success", true, "message", "连接成功"));
        mvc.perform(post("/api/assistant/sessions/" + id + "/model/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(custom()))).andExpect(status().isOk());
        assertThat(settings.resolve(id)).isEmpty();
        when(worker.planAgent(any())).thenReturn(Map.of("summary", "创建文件夹", "steps", List.of(Map.of(
                "tool", "folder.create", "title", "创建", "description", "创建", "arguments", Map.of("name", "测试", "group", "knowledge")))));
        mvc.perform(post("/api/assistant/sessions/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"新建文件夹\",\"page\":\"project-home\",\"executionMode\":\"APPROVAL\"}"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> settings.save(id, custom())).hasMessageContaining("中断当前任务");
    }
}
