package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserAuthorizationServiceTest {
    JdbcClient jdbc;
    WorkerClient worker;
    BrowserAuthorizationService service;
    Map<String, Object> website = Map.of("url", "https://example.com/path?sid=secret");

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = JdbcClient.create(ds);
        worker = mock(WorkerClient.class);
        service = new BrowserAuthorizationService(jdbc, worker, new ObjectMapper());
    }

    AssistantModels.PlanStep step(String tool, Map<String, Object> args) {
        return new AssistantModels.PlanStep(UUID.randomUUID().toString(), 1, tool, "WRITE", "Connect", "", args,
                AssistantModels.RiskLevel.DESTRUCTIVE_OR_EXTERNAL, true, "PENDING");
    }

    @Test void rememberedWebsiteSurvivesServiceRecreationButIsOwnerScoped() {
        try (var ignored = ActorContext.bind("alice")) {
            service.approve(List.of(step("browser.open", website)), true);
            var restored = new BrowserAuthorizationService(jdbc, worker, new ObjectMapper());
            assertThat(restored.canReuse("browser.open", Map.of("url", "https://example.com/other"))).isTrue();
            assertThat(restored.canReuse("browser.open", Map.of("url", "https://other.example/"))).isFalse();
            assertThat(restored.grants().getFirst().label()).doesNotContain("secret");
            restored.execute("browser.open", website, "another-step", () -> "ok");
            assertThat(restored.history(0)).extracting(BrowserAuthorizationService.Audit::event).contains("GRANTED", "AUTHORIZATION_REUSED", "EXECUTION_SUCCEEDED");
        }
        try (var ignored = ActorContext.bind("bob")) {
            assertThat(service.grants()).isEmpty();
            assertThat(service.history(0)).isEmpty();
            assertThat(service.canReuse("browser.open", website)).isFalse();
        }
    }

    @Test void delegatedDomainsAreOwnerScopedRevocableAndIndependentOfTaskContent() {
        var goal = Map.<String,Object>of("url", "https://example.com/", "goal", "向已展示的收件人发送指定内容",
                "allowed_domains", List.of("mail.example.com", "login.example.com"));
        try (var ignored = ActorContext.bind("alice")) {
            var approved = step("browser.delegate", goal);
            service.approve(List.of(approved), true);
            service.execute("browser.delegate", goal, approved.id(), () -> "task-1");
            var reordered = new HashMap<>(goal);
            reordered.put("allowed_domains", List.of("login.example.com", "mail.example.com"));
            assertThat(service.canReuse("browser.delegate", reordered)).isTrue();
            var changed = new HashMap<>(goal); changed.put("goal", "发送另一份内容");
            assertThat(service.canReuse("browser.delegate", changed)).isTrue();
            changed = new HashMap<>(goal); changed.put("allowed_domains", List.of("other.example.com"));
            assertThat(service.canReuse("browser.delegate", changed)).isFalse();
            service.execute("browser.delegate", goal, "new-step", () -> "task-2");
            assertThat(service.history(0)).extracting(BrowserAuthorizationService.Audit::event).contains("AUTHORIZATION_REUSED");
            try (var other = ActorContext.bind("bob")) { assertThat(service.canReuse("browser.delegate", goal)).isFalse(); }
            service.revoke(service.grants().getFirst().id());
            assertThat(service.canReuse("browser.delegate", goal)).isFalse();
            assertThatThrownBy(() -> service.execute("browser.delegate", goal, "step-3", () -> "unexpected")).hasMessageContaining("需先授权网站访问");
        }
    }

    @Test void domainApprovalIsPersistedEvenWithoutLegacyTaskRememberFlag() {
        var goal = Map.<String,Object>of("url", "https://example.com/", "goal", "查询报表");
        var approved = step("browser.delegate", goal);
        service.approve(List.of(approved), false);
        service.execute("browser.delegate", goal, approved.id(), () -> "task");
        assertThat(service.canReuse("browser.delegate", goal)).isTrue();
        assertThat(service.grants()).hasSize(1);
        assertThat(service.grants().getFirst().kind()).isEqualTo("DOMAIN");
    }

    @Test void allDestinationsAreCheckedBeforeStartingAndOnlyMissingOnesAreShown() {
        var goal = Map.<String,Object>of("url", "https://example.com/quote", "goal", "打开 https://mail.example.com/inbox", "allowed_domains", List.of("login.example.com"));
        try (var ignored = ActorContext.bind("alice")) {
            assertThat(service.missingDomains(goal)).containsExactly("example.com", "login.example.com", "mail.example.com");
            service.approve(List.of(step("browser.delegate", Map.of("url", "https://example.com"))), true);
            assertThat(service.missingDomains(goal)).containsExactly("login.example.com", "mail.example.com");
            assertThatThrownBy(() -> service.execute("browser.delegate", goal, "id", () -> "must not start"))
                    .hasMessageContaining("需先授权网站访问");
            service.approve(List.of(step("browser.delegate", goal)), true);
            var restored = new BrowserAuthorizationService(jdbc, worker, new ObjectMapper());
            assertThat(restored.missingDomains(goal)).isEmpty();
            jdbc.sql("update browser_user_grant set expires_at=:past").param("past", java.time.Instant.now().minusSeconds(60)).update();
            assertThat(restored.missingDomains(goal)).hasSize(3);
        }
    }

    @Test void domainApprovalDoesNotIncludeSiblingHostsOrWildcards() {
        assertThatThrownBy(() -> BrowserAuthorizationService.requestedDomains(Map.of("url", "https://example.com", "allowed_domains", List.of("*.qq.com"))))
                .isInstanceOf(IllegalArgumentException.class);
        service.approve(List.of(step("browser.delegate", Map.of("url", "https://mail.example.com"))), true);
        assertThat(service.canReuse("browser.delegate", Map.of("url", "https://other.example.com"))).isFalse();
    }

    @Test void oldWebsiteGrantDoesNotSilentlyExpandToNormalTransportPermission() {
        var step = step("browser.open", website);
        service.approve(List.of(step), true);
        jdbc.sql("update browser_user_grant set scope_key=:scope")
                .param("scope", HashSupport.sha256("website:v1:https://example.com")).update();
        assertThat(service.canReuse("browser.open", website)).isFalse();
        service.approve(List.of(step("browser.open", website)), true);
        assertThat(service.canReuse("browser.open", website)).isTrue();
        assertThat(service.scope("browser.open", website).label()).contains("正常联网", "自动保存", "对外操作仍需授权");
    }

    @Test void onceReceiptCannotBeReplayedAndFailuresAreAudited() {
        var step = step("browser.open", website);
        service.approve(List.of(step), false);
        assertThatThrownBy(() -> service.execute("browser.open", website, step.id(), () -> { throw new IllegalStateException("failed"); })).hasMessage("failed");
        assertThatThrownBy(() -> service.execute("browser.open", website, step.id(), () -> "unexpected")).hasMessageContaining("没有有效");
        assertThat(service.grants()).isEmpty();
        assertThat(service.history(0)).extracting(BrowserAuthorizationService.Audit::event).contains("APPROVED_ONCE", "EXECUTION_FAILED", "DENIED");
    }

    @Test void revocationClosesBoundSessionAndInvalidatesUnusedReceipt() {
        var step = step("browser.open", website);
        service.approve(List.of(step), true);
        service.registerSession(website, "browser-session");
        service.checkSession("browser-session");
        var id = service.grants().getFirst().id();
        try (var ignored = ActorContext.bind("other")) {
            assertThatThrownBy(() -> service.revoke(id)).hasMessage("授权不存在");
            assertThatThrownBy(() -> service.checkSession("browser-session")).hasMessageContaining("没有有效");
        }
        service.revoke(id);
        assertThat(service.canReuse("browser.open", website)).isFalse();
        assertThatThrownBy(() -> service.checkSession("browser-session")).hasMessageContaining("撤销");
        assertThatThrownBy(() -> service.execute("browser.open", website, step.id(), () -> "unexpected")).hasMessageContaining("没有有效");
        verify(worker).browser("session/close", Map.of("owner", "default", "session", "browser-session"));
    }

    @Test void expiredGrantDoesNotAuthorizeExecution() {
        service.approve(List.of(step("browser.open", website)), true);
        jdbc.sql("update browser_user_grant set expires_at=TIMESTAMP '2000-01-01 00:00:00'").update();
        assertThat(service.canReuse("browser.open", website)).isFalse();
        assertThat(service.grants().getFirst().status()).isEqualTo("EXPIRED");
    }

    @Test void reusedConnectionsRegisterOnceAndDirectControlIsOwnerScoped() {
        service.approve(List.of(step("browser.open", website)), true);
        service.registerSession(website, "same-window");
        service.registerSession(website, "same-window");
        assertThat(jdbc.sql("select count(*) from browser_authorized_session").query(Integer.class).single()).isEqualTo(1);
        when(worker.browser(eq("session/status"), any())).thenReturn(Map.of("status", "HUMAN_CONTROL", "origin", "https://example.com"));
        when(worker.browser(eq("session/resume"), any())).thenReturn(Map.of("status", "READY"));
        assertThat(service.connections()).hasSize(1);
        try (var ignored = ActorContext.bind("other")) {
            assertThat(service.connections()).isEmpty();
            assertThatThrownBy(() -> service.control("same-window", "resume")).hasMessageContaining("没有有效");
        }
        assertThat(service.control("same-window", "resume").get("session")).isEqualTo("same-window");
        assertThat(service.history(0)).extracting(BrowserAuthorizationService.Audit::event).contains("USER_CONTROL_REQUESTED", "BROWSER_OPERATION");
        assertThatThrownBy(() -> service.control("same-window", "commit")).hasMessageContaining("不支持");
        verify(worker, times(1)).browser(eq("session/resume"), any());
    }

    @Test void changedActionNeedsFreshApprovalAndMissingFingerprintFailsClosed() {
        var args = Map.<String, Object>of("session", "s", "pending", "p");
        when(worker.browser(eq("session/preview"), any())).thenReturn(Map.of("scopeHash", "a".repeat(64)));
        var step = step("browser.commit", args);
        service.approve(List.of(step), true);
        assertThat(service.canReuse("browser.commit", args)).isTrue();
        when(worker.browser(eq("session/preview"), any())).thenReturn(Map.of("scopeHash", "b".repeat(64)));
        assertThat(service.canReuse("browser.commit", args)).isFalse();
        assertThatThrownBy(() -> service.execute("browser.commit", args, step.id(), () -> "unexpected")).hasMessageContaining("没有有效");
        when(worker.browser(eq("session/preview"), any())).thenReturn(Map.of());
        assertThatThrownBy(() -> service.canReuse("browser.commit", args)).hasMessageContaining("范围指纹");
    }

    @Test void restartLoginIsOwnerScopedAndAudited() {
        service.approve(List.of(step("browser.open", website)), true);
        service.registerSession(website, "login-window");
        when(worker.browser(eq("session/restart_login"), any())).thenReturn(Map.of("status", "HUMAN_CONTROL"));
        try (var ignored = ActorContext.bind("other")) {
            assertThatThrownBy(() -> service.control("login-window", "restart_login")).hasMessageContaining("没有有效");
        }
        assertThat(service.control("login-window", "restart_login").get("status")).isEqualTo("HUMAN_CONTROL");
        verify(worker, times(1)).browser("session/restart_login", Map.of("session", "login-window", "owner", "default"));
        assertThat(service.history(0)).anyMatch(a -> a.event().equals("BROWSER_OPERATION") && a.label().contains("user.restart_login"));
        service.revoke(service.grants().getFirst().id());
        assertThatThrownBy(() -> service.control("login-window", "restart_login")).hasMessageContaining("撤销");
    }

    @Test void humanLoginContractRequiresBoolean() {
        AssistantToolContracts.validate("browser.open", Map.of("url", "https://example.com", "human_login", true));
        AssistantToolContracts.validate("browser.open", Map.of("url", "https://example.com", "human_login", false));
        assertThatThrownBy(() -> AssistantToolContracts.validate("browser.open", Map.of("url", "https://example.com", "human_login", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void approvalDescriptionBoundsOnlyDisplayFieldsAndNeverSerializesPage() {
        var preview = Map.<String, Object>of("site", "s".repeat(2000), "action", "click",
                "target", Map.of("name", "目标".repeat(2000)), "frame", 2,
                "value", "正文".repeat(10000), "scopeHash", "a".repeat(64),
                "page", Map.of("snapshot", "private-page".repeat(5000), "diagnostics", "private-diagnostics"));
        var description = BrowserAuthorizationService.approvalDescription(preview);
        assertThat(description.length()).isLessThanOrEqualTo(1000);
        assertThat(description).contains("点击", "页面框架：2", "已缩略", "原浏览器窗口");
        assertThat(description).doesNotContain("private-page", "private-diagnostics", "scopeHash");
        assertThat(preview.get("value")).isEqualTo("正文".repeat(10000));
        assertThat(preview.get("scopeHash")).isEqualTo("a".repeat(64));
    }
}
