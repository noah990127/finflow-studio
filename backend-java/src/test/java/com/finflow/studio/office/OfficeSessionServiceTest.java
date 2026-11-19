package com.finflow.studio.office;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-office-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=${java.io.tmpdir}/finflow-office-test",
        "finflow.office.enabled=true",
        "finflow.office.max-editor-file-bytes=32",
        "finflow.office.api-base-url=http://java-api:8080",
        "finflow.office.jwt-secret=office-test-secret"
})
class OfficeSessionServiceTest {
    @Autowired ProjectService projects;
    @Autowired KnowledgeService knowledge;
    @Autowired OfficeSessionService office;

    @Test
    void createsSignedContentUrlThatRestoresTheOwningAccount() {
        String resourceId;
        OfficeModels.SessionResponse session;
        try (var ignored = ActorContext.bind("test-office-user")) {
            var project = projects.create("Office 测试", "验证文件桥接签名");
            var file = new MockMultipartFile("file", "brief.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "fake-pptx-content".getBytes(StandardCharsets.UTF_8));
            resourceId = knowledge.upload(project.id(), null, file).id();
            session = office.createFileSession(resourceId, "edit");
        }

        @SuppressWarnings("unchecked")
        var document = (java.util.Map<String, Object>) session.config().get("document");
        var contentUrl = URI.create((String) document.get("url"));
        var token = queryValue(contentUrl, "token");
        var content = office.fileContent(resourceId, 1, token);
        @SuppressWarnings("unchecked")
        var editor = (java.util.Map<String, Object>) session.config().get("editorConfig");
        var callbackToken = queryValue(URI.create((String) editor.get("callbackUrl")), "accessToken");
        var onlyOfficeToken = JWT.create().withClaim("key", resourceId + "-v1")
                .sign(Algorithm.HMAC256("office-test-secret"));
        var callbackResult = office.fileCallback(resourceId, 1, callbackToken,
                new OfficeModels.CallbackRequest(1, null, resourceId + "-v1", onlyOfficeToken), null);

        assertThat(content.name()).isEqualTo("brief.pptx");
        assertThat(content.path()).hasContent("fake-pptx-content");
        assertThat(JWT.decode((String) session.config().get("token")).getSubject()).isEqualTo("test-office-user");
        assertThat(callbackResult).containsEntry("error", 0);
    }

    @Test
    void rejectsMissingOrMismatchedContentSignatures() {
        String resourceId;
        String token;
        try (var ignored = ActorContext.bind("test-office-signature")) {
            var project = projects.create("Office 签名测试", "拒绝篡改地址");
            var file = new MockMultipartFile("file", "table.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "fake-xlsx-content".getBytes(StandardCharsets.UTF_8));
            resourceId = knowledge.upload(project.id(), null, file).id();
            @SuppressWarnings("unchecked")
            var document = (java.util.Map<String, Object>) office.createFileSession(resourceId, "edit")
                    .config().get("document");
            token = queryValue(URI.create((String) document.get("url")), "token");
        }

        var finalResourceId = resourceId;
        assertThatThrownBy(() -> office.fileContent(finalResourceId, 1, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺少签名");
        assertThatThrownBy(() -> office.fileContent(finalResourceId, 2, token))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不匹配");
    }

    @Test
    void returnsAUsefulLargeFileModeMessageBeforeCallingOnlyOffice() {
        try (var ignored = ActorContext.bind("test-office-large-file")) {
            var project = projects.create("Office 大文件测试", "验证自动分流");
            var file = new MockMultipartFile("file", "large.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new byte[64]);
            var resource = knowledge.upload(project.id(), null, file);

            var session = office.createFileSession(resource.id(), "edit");

            assertThat(session.enabled()).isFalse();
            assertThat(session.message()).contains("超过在线 Office 编辑上限", "大型表格模式");
        }
    }

    private String queryValue(URI uri, String name) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .filter(part -> part[0].equals(name) && part.length == 2)
                .map(part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }
}
