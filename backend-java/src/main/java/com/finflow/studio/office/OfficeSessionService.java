package com.finflow.studio.office;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.office.OfficeModels.CallbackRequest;
import com.finflow.studio.office.OfficeModels.SessionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OfficeSessionService {
    private final JdbcClient jdbc;
    private final KnowledgeService knowledge;
    private final ExtractJobService extracts;
    private final DeliverableService deliverables;
    private final WebClient webClient;
    private final boolean enabled;
    private final String documentServerUrl;
    private final String apiBaseUrl;
    private final String internalDocumentServerUrl;
    private final String jwtSecret;

    public OfficeSessionService(JdbcClient jdbc, KnowledgeService knowledge, ExtractJobService extracts,
                                DeliverableService deliverables,
                                @Value("${finflow.office.enabled:false}") boolean enabled,
                                @Value("${finflow.office.document-server-url:http://localhost:8082}") String documentServerUrl,
                                @Value("${finflow.office.api-base-url:http://java-api:8080}") String apiBaseUrl,
                                @Value("${finflow.office.internal-document-server-url:http://onlyoffice-documentserver}") String internalDocumentServerUrl,
                                @Value("${finflow.office.jwt-secret:}") String jwtSecret) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.extracts = extracts;
        this.deliverables = deliverables;
        this.webClient = WebClient.builder().codecs(configurer ->
                configurer.defaultCodecs().maxInMemorySize(512 * 1024 * 1024)).build();
        this.enabled = enabled;
        this.documentServerUrl = stripSlash(documentServerUrl);
        this.apiBaseUrl = stripSlash(apiBaseUrl);
        this.internalDocumentServerUrl = stripSlash(internalDocumentServerUrl);
        this.jwtSecret = jwtSecret;
    }

    public SessionResponse createFileSession(String resourceId) {
        return createFileSession(resourceId, "edit");
    }

    public SessionResponse createFileSession(String resourceId, String mode) {
        var file = knowledge.get(resourceId);
        return createSession(resourceId, file.name(), file.currentVersion(),
                apiBaseUrl + "/api/files/" + resourceId + "/download?version=" + file.currentVersion(),
                apiBaseUrl + "/api/office/files/" + resourceId + "/callback?version=" + file.currentVersion(),
                resourceId, mode);
    }

    @Transactional
    public SessionResponse createExtractSession(String extractJobId) {
        return createExtractSession(extractJobId, "edit");
    }

    @Transactional
    public SessionResponse createExtractSession(String extractJobId, String mode) {
        if (!enabled) return unavailable(extractJobId);
        var job = extracts.get(extractJobId);
        if (!"SUCCEEDED".equals(job.status())) throw new IllegalStateException("数据采集完成后才能在线编辑");
        var workingId = jdbc.sql("select resource_id from office_working_copy where source_kind = 'EXTRACT' and source_id = :id")
                .param("id", extractJobId).query(String.class).optional().orElse(null);
        if (workingId == null) {
            var copy = knowledge.importFile(job.projectId(), job.outputName(), "text/csv", extracts.outputPath(extractJobId));
            workingId = copy.id();
            jdbc.sql("""
                    insert into office_working_copy(source_kind, source_id, resource_id, created_at)
                    values ('EXTRACT', :sourceId, :resourceId, :now)
                    """).param("sourceId", extractJobId).param("resourceId", workingId)
                    .param("now", Instant.now()).update();
        }
        return createFileSession(workingId, mode);
    }

    public SessionResponse createDeliverableSession(String resourceId) {
        return createDeliverableSession(resourceId, "edit");
    }

    public SessionResponse createDeliverableSession(String resourceId, String mode) {
        var item = deliverables.get(resourceId);
        var extension = item.format().equalsIgnoreCase("mermaid") ? "mmd" : item.format().toLowerCase(Locale.ROOT);
        if (extension.equals("mmd")) throw new IllegalArgumentException("Mermaid 请使用文本编辑器，不通过 ONLYOFFICE 编辑");
        return createSession(resourceId, item.name() + "." + extension, item.currentVersion(),
                apiBaseUrl + "/api/deliverables/" + resourceId + "/download?version=" + item.currentVersion(),
                apiBaseUrl + "/api/office/deliverables/" + resourceId + "/callback?version=" + item.currentVersion(),
                resourceId, mode);
    }

    private SessionResponse createSession(String id, String name, int version, String downloadUrl,
                                          String callbackUrl, String workingResourceId, String requestedMode) {
        if (!enabled) return unavailable(workingResourceId);
        if (jwtSecret.isBlank()) throw new IllegalStateException("ONLYOFFICE JWT 密钥尚未配置");
        var mode = normalizeMode(requestedMode);
        var readOnly = mode.equals("view");
        var extension = extension(name);
        var document = new LinkedHashMap<String, Object>();
        document.put("fileType", extension);
        document.put("key", id + "-v" + version);
        document.put("title", name);
        document.put("url", downloadUrl);
        document.put("permissions", Map.of("edit", !readOnly, "download", true, "print", true,
                "comment", !readOnly, "fillForms", !readOnly, "modifyFilter", !readOnly));
        var editor = new LinkedHashMap<String, Object>();
        if (!readOnly) editor.put("callbackUrl", callbackUrl);
        editor.put("lang", "zh-CN");
        editor.put("mode", mode);
        editor.put("user", Map.of("id", "default_user", "name", "我"));
        editor.put("customization", readOnly
                ? Map.of("compactHeader", true, "hideRightMenu", true, "toolbarNoTabs", false)
                : Map.of("autosave", true, "compactHeader", true, "forcesave", true));
        var config = new LinkedHashMap<String, Object>();
        config.put("documentType", documentType(extension));
        config.put("document", document);
        config.put("editorConfig", editor);
        config.put("height", "100%");
        config.put("width", "100%");
        config.put("token", JWT.create().withPayload(config).withIssuedAt(Instant.now()).sign(Algorithm.HMAC256(jwtSecret)));
        return new SessionResponse(true, documentServerUrl, workingResourceId, "", config);
    }

    private String normalizeMode(String value) {
        var mode = value == null ? "edit" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("view", "edit").contains(mode)) throw new IllegalArgumentException("Office 打开模式只支持查看或编辑");
        return mode;
    }

    private SessionResponse unavailable(String resourceId) {
        return new SessionResponse(false, documentServerUrl, resourceId,
                "ONLYOFFICE Document Server 尚未启动", Map.of());
    }

    public Map<String, Integer> fileCallback(String resourceId, int editingVersion, CallbackRequest request,
                                             String authorization) {
        verifyToken(request.token(), authorization);
        if (!shouldSave(request)) return Map.of("error", 0);
        var file = knowledge.get(resourceId);
        if (file.currentVersion() != editingVersion) throw new IllegalStateException("文件已有更新版本，本次编辑未覆盖最新内容");
        knowledge.createGeneratedVersion(file.projectId(), file.id(), file.name(), file.mediaType(), downloadEdited(request.url()));
        return Map.of("error", 0);
    }

    public Map<String, Integer> deliverableCallback(String resourceId, int editingVersion, CallbackRequest request,
                                                    String authorization) {
        verifyToken(request.token(), authorization);
        if (!shouldSave(request)) return Map.of("error", 0);
        deliverables.createEditedVersion(resourceId, editingVersion, downloadEdited(request.url()));
        return Map.of("error", 0);
    }

    private boolean shouldSave(CallbackRequest request) {
        return request.status() != null && List.of(2, 6).contains(request.status());
    }

    private byte[] downloadEdited(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("ONLYOFFICE 回调缺少保存地址");
        var downloadUri = URI.create(url);
        var allowedHost = URI.create(internalDocumentServerUrl).getHost();
        if (allowedHost == null || !allowedHost.equalsIgnoreCase(downloadUri.getHost())) {
            throw new IllegalArgumentException("ONLYOFFICE 返回了不受信任的文件地址");
        }
        var bytes = webClient.get().uri(downloadUri).retrieve().bodyToMono(byte[].class).block();
        if (bytes == null || bytes.length == 0) throw new IllegalStateException("ONLYOFFICE 没有返回保存内容");
        return bytes;
    }

    private void verifyToken(String bodyToken, String authorization) {
        var token = bodyToken;
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) token = authorization.substring(7);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("ONLYOFFICE 回调缺少签名");
        try { JWT.require(Algorithm.HMAC256(jwtSecret)).build().verify(token); }
        catch (Exception exception) { throw new IllegalArgumentException("ONLYOFFICE 回调签名无效"); }
    }

    private String extension(String name) {
        var dot = name.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("文件格式不支持在线编辑");
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String documentType(String extension) {
        if (List.of("doc", "docx", "odt", "rtf", "txt").contains(extension)) return "word";
        if (List.of("xls", "xlsx", "xlsm", "ods", "csv").contains(extension)) return "cell";
        if (List.of("ppt", "pptx", "odp").contains(extension)) return "slide";
        if (extension.equals("pdf")) return "pdf";
        throw new IllegalArgumentException("当前文件格式不支持 ONLYOFFICE 在线编辑");
    }

    private String stripSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
