package com.finflow.studio.deliverable;

import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.Response;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.storage.BlobStore;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class DeliverableService {
    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final WorkerClient worker;
    private final ObjectMapper objectMapper;
    private final BlobStore blobStore;
    private final long maxFileBytes;

    public DeliverableService(JdbcClient jdbc, ProjectService projects, WorkerClient worker,
                              ObjectMapper objectMapper, BlobStore blobStore,
                              @Value("${finflow.storage.max-file-bytes}") long maxFileBytes) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.blobStore = blobStore;
        this.maxFileBytes = maxFileBytes;
    }

    @Transactional
    public Response create(CreateRequest request) {
        projects.get(request.projectId());
        var format = normalizeFormat(request.format());
        var payload = buildPayload(request);
        var bytes = worker.generateDeliverable(format, payload);
        var newResource = request.resourceId() == null || request.resourceId().isBlank();
        var resourceId = newResource ? UUID.randomUUID().toString() : request.resourceId();
        var now = Instant.now();
        int version;
        if (newResource) {
            version = 1;
            jdbc.sql("""
                    insert into deliverable_resource(id, project_id, name, format, current_version, status, created_at, updated_at)
                    values (:id, :projectId, :name, :format, 1, 'READY', :now, :now)
                    """).param("id", resourceId).param("projectId", request.projectId()).param("name", request.title())
                    .param("format", format).param("now", now).update();
        } else {
            var row = jdbc.sql("select project_id, current_version, format from deliverable_resource where id = :id")
                    .param("id", resourceId).query((rs, rowNum) -> Map.of("projectId", rs.getString("project_id"),
                            "version", rs.getInt("current_version"), "format", rs.getString("format"))).optional()
                    .orElseThrow(() -> new IllegalArgumentException("输出文件不存在"));
            if (!request.projectId().equals(row.get("projectId")) || !format.equals(row.get("format"))) {
                throw new IllegalArgumentException("输出文件不属于当前项目或格式不一致");
            }
            version = (Integer) row.get("version") + 1;
            jdbc.sql("update deliverable_resource set name = :name, current_version = :version, updated_at = :now where id = :id")
                    .param("name", request.title()).param("version", version).param("now", now).param("id", resourceId).update();
        }
        var stored = store(request.projectId(), resourceId, version, request.title(), format, bytes);
        jdbc.sql("""
                insert into deliverable_version(id, resource_id, version_number, storage_path, size_bytes,
                    checksum, source_spec_json, created_at)
                values (:id, :resourceId, :version, :path, :size, :checksum, :spec, :now)
                """).param("id", UUID.randomUUID().toString()).param("resourceId", resourceId).param("version", version)
                .param("path", stored.location()).param("size", stored.size()).param("checksum", stored.checksum())
                .param("spec", writeJson(payload)).param("now", now).update();
        return get(resourceId);
    }

    public List<Response> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql(latestSql() + " where r.project_id = :projectId order by r.updated_at desc")
                .param("projectId", projectId).query(this::map).list();
    }

    public Response get(String id) {
        return jdbc.sql(latestSql() + " where r.id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("输出文件不存在"));
    }

    @Transactional
    public void delete(String id) {
        get(id);
        var locations = jdbc.sql("select storage_path from deliverable_version where resource_id = :id")
                .param("id", id).query(String.class).list();
        jdbc.sql("delete from office_working_copy where source_kind = 'deliverables' and source_id = :id")
                .param("id", id).update();
        jdbc.sql("delete from deliverable_version where resource_id = :id").param("id", id).update();
        jdbc.sql("delete from deliverable_resource where id = :id").param("id", id).update();
        afterCommit(() -> locations.forEach(blobStore::delete));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    @Transactional
    public Response createEditedVersion(String id, int expectedVersion, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("编辑结果为空");
        var current = get(id);
        if (current.currentVersion() != expectedVersion) throw new IllegalStateException("输出件已有更新版本，请重新打开后编辑");
        var nextVersion = expectedVersion + 1;
        var stored = store(current.projectId(), id, nextVersion, current.name(), current.format(), bytes);
        var now = Instant.now();
        var updated = jdbc.sql("""
                update deliverable_resource set current_version = :nextVersion, updated_at = :now
                where id = :id and current_version = :expectedVersion
                """).param("nextVersion", nextVersion).param("now", now).param("id", id)
                .param("expectedVersion", expectedVersion).update();
        if (updated == 0) throw new IllegalStateException("输出件已有更新版本，请重新打开后编辑");
        jdbc.sql("""
                insert into deliverable_version(id, resource_id, version_number, storage_path, size_bytes,
                    checksum, source_spec_json, created_at)
                values (:versionId, :resourceId, :version, :path, :size, :checksum, :spec, :now)
                """).param("versionId", UUID.randomUUID().toString()).param("resourceId", id)
                .param("version", nextVersion).param("path", stored.location()).param("size", stored.size())
                .param("checksum", stored.checksum()).param("spec", "{\"source\":\"onlyoffice\"}")
                .param("now", now).update();
        return get(id);
    }

    public Path path(String id, Integer version) {
        var sql = version == null
                ? "select v.storage_path from deliverable_resource r join deliverable_version v on v.resource_id = r.id and v.version_number = r.current_version where r.id = :id"
                : "select storage_path from deliverable_version where resource_id = :id and version_number = :version";
        var query = jdbc.sql(sql).param("id", id);
        if (version != null) query.param("version", version);
        var location = query.query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("输出版本不存在"));
        return blobStore.materialize(location);
    }

    private Map<String, Object> buildPayload(CreateRequest request) {
        var sections = new ArrayList<Map<String, Object>>();
        for (var section : request.sections()) {
            var refs = new ArrayList<Map<String, Object>>();
            for (var refId : section.refIds() == null ? List.<String>of() : section.refIds()) {
                var ref = jdbc.sql("""
                                select k.source_name, k.location_json from knowledge_ref k
                                join file_resource r on r.id = k.resource_id and r.current_version = k.version_number
                                where k.id = :id and k.project_id = :projectId
                                """).param("id", refId).param("projectId", request.projectId())
                        .query((rs, rowNum) -> Map.<String, Object>of("source_name", rs.getString("source_name"),
                                "location", readMap(rs.getString("location_json")))).optional()
                        .orElseThrow(() -> new IllegalArgumentException("Ref 不存在、已过期或不属于当前项目：" + refId));
                refs.add(ref);
            }
            sections.add(Map.of("heading", section.heading(),
                    "paragraphs", section.paragraphs() == null ? List.of() : section.paragraphs(),
                    "bullets", section.bullets() == null ? List.of() : section.bullets(), "refs", refs));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", request.title());
        payload.put("subtitle", request.subtitle() == null ? "" : request.subtitle());
        payload.put("theme", "blue-white");
        payload.put("sections", sections);
        payload.put("include_citations", request.includeCitations());
        payload.put("citation_style", normalizeCitationStyle(request.citationStyle()));
        if (request.pptSkill() != null && !request.pptSkill().isBlank()) {
            if (!formatSupportsSkill(request.format(), request.pptSkill())) {
                throw new IllegalArgumentException("当前输出格式不支持所选 PPT 技能");
            }
            payload.put("ppt_skill", request.pptSkill());
        }
        return payload;
    }

    private String normalizeCitationStyle(String value) {
        var style = value == null ? "IEEE" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("IEEE", "APA_7", "GB_T_7714").contains(style)) {
            throw new IllegalArgumentException("引用格式只支持 IEEE、APA 7 和 GB/T 7714");
        }
        return style;
    }

    private boolean formatSupportsSkill(String format, String skill) {
        return ("pptx".equalsIgnoreCase(format) && "guizang-huawei-style-c".equals(skill)) ||
                ("html_slides".equalsIgnoreCase(format) && "frontend-slides".equals(skill));
    }

    private BlobStore.StoredObject store(String projectId, String resourceId, int version, String title, String format, byte[] bytes) {
        var safeTitle = title.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
        if (safeTitle.length() > 180) safeTitle = safeTitle.substring(0, 180);
        var key = projectId + "/deliverables/" + resourceId + "/v" + version + "/" + safeTitle + "." +
                (format.equals("mermaid") ? "mmd" : format.equals("financial_report") ? "json" : format.equals("html_slides") ? "html" : format);
        return blobStore.putBytes(key, bytes, maxFileBytes);
    }

    private String normalizeFormat(String value) {
        var format = value.toLowerCase(Locale.ROOT).trim();
        if (!List.of("pptx", "html_slides", "docx", "pdf", "mermaid", "excalidraw", "financial_report").contains(format)) throw new IllegalArgumentException("输出格式只支持 PPTX、网页演示、DOCX、PDF、Mermaid、Excalidraw 和财务报告");
        return format;
    }

    private String latestSql() {
        return """
                select r.*, v.size_bytes, v.checksum from deliverable_resource r
                join deliverable_version v on v.resource_id = r.id and v.version_number = r.current_version
                """;
    }

    private Response map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Response(rs.getString("id"), rs.getString("project_id"), rs.getString("name"), rs.getString("format"),
                rs.getInt("current_version"), rs.getString("status"), rs.getLong("size_bytes"), rs.getString("checksum"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("输出规格无法保存", exception); }
    }

    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { return Map.of(); }
    }
}
