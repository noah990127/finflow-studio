package com.finflow.studio.auth;

import com.finflow.studio.assistant.AgentMemoryService;
import com.finflow.studio.assistant.AssistantModels.MemoryRequest;
import com.finflow.studio.assistant.AssistantService;
import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-auth-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=true",
        "finflow.storage.root=${java.io.tmpdir}/finflow-auth-test"
})
@AutoConfigureMockMvc
class AuthenticationIsolationTest {
    @Autowired MockMvc mvc;
    @Autowired FixedAccountService accounts;
    @Autowired ProjectService projects;
    @Autowired AssistantService assistant;
    @Autowired AgentMemoryService memory;

    @Test
    void exposesOnlyDefaultAccountWithoutPrivateCredentialsAndRequiresLogin() throws Exception {
        assertThat(accounts.count()).isEqualTo(1);
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"test100\",\"password\":\"Pr0d1234\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"default\",\"password\":\"Pr0d1234\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly(AuthController.COOKIE_NAME, true))
                .andExpect(jsonPath("$.username").value("default"));
    }

    @Test
    void isolatesProjectsByAccountIncludingDirectIdLookup() {
        String defaultProjectId;
        String testSessionId;
        try (var ignored = ActorContext.bind("default")) {
            defaultProjectId = projects.create("样板项目", "只属于 default").id();
            assertThat(projects.list()).extracting("id").contains(defaultProjectId);
        }
        try (var ignored = ActorContext.bind("test1")) {
            assertThat(projects.list()).isEmpty();
            assertThatThrownBy(() -> projects.get(defaultProjectId)).isInstanceOf(IllegalArgumentException.class);
            var own = projects.create("test1 项目", "隔离验证");
            assertThat(projects.list()).extracting("id").containsExactly(own.id());
            testSessionId = assistant.createSession(own.id(), "test1 对话").id();
            memory.save(new MemoryRequest("USER_PREFERENCE", "", "language",
                    Map.of("value", "中文"), "test"));
            assertThat(memory.list("")).hasSize(1);
        }
        try (var ignored = ActorContext.bind("test2")) {
            assertThatThrownBy(() -> assistant.getSession(testSessionId))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(memory.list("")).isEmpty();
        }
    }
}
