package com.finflow.studio.knowledge;

import com.finflow.studio.knowledge.KnowledgeModels.FileResourceResponse;
import com.finflow.studio.knowledge.KnowledgeModels.RefResponse;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.storage.BlobStore;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeService {
    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final WorkerClient worker;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final BlobStore blobStore;
    private final long maxFileBytes;
    private final long maxDataOutputBytes;

    public KnowledgeService(JdbcClient jdbc, ProjectService projects, WorkerClient worker,
                            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                            ObjectMapper objectMapper, BlobStore blobStore,
                            @Value("${finflow.storage.max-file-bytes}") long maxFileBytes,
                            @Value("${finflow.transform.max-output-bytes:107374182400}") long maxDataOutputBytes) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.worker = worker;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
        this.blobStore = blobStore;
        this.maxFileBytes = maxFileBytes;
        this.maxDataOutputBytes = maxDataOutputBytes;
    }

    @Transactional
    public FileResourceResponse upload(String projectId, String resourceId, MultipartFile file) {
        projects.get(projectId);
        if (file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        if (file.getSize() > maxFileBytes) throw new IllegalArgumentException("文件超过允许的大小");
        var originalName = safeName(file.getOriginalFilename());
        var mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        var now = Instant.now();
        var newResource = resourceId == null || resourceId.isBlank();
        var resolvedResourceId = newResource ? UUID.randomUUID().toString() : resourceId;
        int version;
        if (newResource) {
            version = 1;
            jdbc.sql("""
                    insert into file_resource(id, project_id, name, media_type, status, current_version, created_at, updated_at)
                    values (:id, :projectId, :name, :mediaType, 'PROCESSING', 1, :now, :now)
                    """).param("id", resolvedResourceId).param("projectId", projectId).param("name", originalName)
                    .param("mediaType", mediaType).param("now", now).update();
        } else {
            var current = jdbc.sql("select current_version from file_resource where id = :id and project_id = :projectId")
                    .param("id", resolvedResourceId).param("projectId", projectId).query(Integer.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("资料不存在或不属于当前项目"));
            version = current + 1;
            jdbc.sql("update file_resource set name = :name, media_type = :mediaType, status = 'PROCESSING', current_version = :version, updated_at = :now where id = :id")
                    .param("name", originalName).param("mediaType", mediaType).param("version", version)
                    .param("now", now).param("id", resolvedResourceId).update();
        }
        var versionId = UUID.randomUUID().toString();
        var stored = store(projectId, resolvedResourceId, version, originalName, file);
        jdbc.sql("""
                insert into file_version(id, resource_id, version_number, original_name, media_type, storage_path,
                    size_bytes, checksum, parse_status, created_at)
                values (:id, :resourceId, :version, :name, :mediaType, :path, :size, :checksum, 'QUEUED', :now)
                """).param("id", versionId).param("resourceId", resolvedResourceId).param("version", version)
                .param("name", originalName).param("mediaType", mediaType).param("path", stored.location())
                .param("size", stored.size()).param("checksum", stored.checksum()).param("now", now).update();
        scheduleParse(versionId);
        return get(resolvedResourceId);
    }

    @Transactional
    public FileResourceResponse createGeneratedVersion(String projectId, String resourceId, String name,
                                                       String mediaType, byte[] content) {
        projects.get(projectId);
        if (content == null || content.length == 0) throw new IllegalArgumentException("加工结果为空");
        if (content.length > maxFileBytes) throw new IllegalArgumentException("加工结果超过允许的大小");
        var originalName = safeName(name);
        var current = jdbc.sql("select current_version from file_resource where id = :id and project_id = :projectId")
                .param("id", resourceId).param("projectId", projectId).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("表格不存在或不属于当前项目"));
        var version = current + 1;
        var now = Instant.now();
        jdbc.sql("update file_resource set name = :name, media_type = :mediaType, status = 'PROCESSING', current_version = :version, updated_at = :now where id = :id")
                .param("name", originalName).param("mediaType", mediaType).param("version", version)
                .param("now", now).param("id", resourceId).update();
        var stored = storeBytes(projectId, resourceId, version, originalName, content);
        var versionId = UUID.randomUUID().toString();
        jdbc.sql("""
                insert into file_version(id, resource_id, version_number, original_name, media_type, storage_path,
                    size_bytes, checksum, parse_status, created_at)
                values (:id, :resourceId, :version, :name, :mediaType, :path, :size, :checksum, 'QUEUED', :now)
                """).param("id", versionId).param("resourceId", resourceId).param("version", version)
                .param("name", originalName).param("mediaType", mediaType).param("path", stored.location())
                .param("size", stored.size()).param("checksum", stored.checksum()).param("now", now).update();
        scheduleParse(versionId);
        return get(resourceId);
    }

    @Transactional
    public FileResourceResponse importFile(String projectId, String name, String mediaType, Path source) {
        return importFile(projectId, name, mediaType, source, maxFileBytes);
    }

    @Transactional
    public FileResourceResponse importDataFile(String projectId, String name, String mediaType, Path source) {
        return importFile(projectId, name, mediaType, source, maxDataOutputBytes);
    }

    @Transactional
    public FileResourceResponse importBytes(String projectId, String name, String mediaType, byte[] content) {
        projects.get(projectId);
        if (content == null || content.length == 0) throw new IllegalArgumentException("资料内容为空");
        if (content.length > maxFileBytes) throw new IllegalArgumentException("资料超过允许的大小");
        var resourceId = UUID.randomUUID().toString();
        var originalName = safeName(name);
        var now = Instant.now();
        jdbc.sql("""
                insert into file_resource(id, project_id, name, media_type, status, current_version, created_at, updated_at)
                values (:id, :projectId, :name, :mediaType, 'PROCESSING', 1, :now, :now)
                """).param("id", resourceId).param("projectId", projectId).param("name", originalName)
                .param("mediaType", mediaType).param("now", now).update();
        var stored = storeBytes(projectId, resourceId, 1, originalName, content);
        var versionId = UUID.randomUUID().toString();
        jdbc.sql("""
                insert into file_version(id, resource_id, version_number, original_name, media_type, storage_path,
                    size_bytes, checksum, parse_status, created_at)
                values (:id, :resourceId, 1, :name, :mediaType, :path, :size, :checksum, 'QUEUED', :now)
                """).param("id", versionId).param("resourceId", resourceId).param("name", originalName)
                .param("mediaType", mediaType).param("path", stored.location()).param("size", stored.size())
                .param("checksum", stored.checksum()).param("now", now).update();
        scheduleParse(versionId);
        return get(resourceId);
    }

    private FileResourceResponse importFile(String projectId, String name, String mediaType, Path source,
                                            long sizeLimit) {
        projects.get(projectId);
        try {
            var size = Files.size(source);
            if (size <= 0) throw new IllegalArgumentException("文件内容为空");
            if (size > sizeLimit) throw new IllegalArgumentException("数据加工结果超过允许的大小");
            var resourceId = UUID.randomUUID().toString();
            var originalName = safeName(name);
            var now = Instant.now();
            jdbc.sql("""
                    insert into file_resource(id, project_id, name, media_type, status, current_version, created_at, updated_at)
                    values (:id, :projectId, :name, :mediaType, 'PROCESSING', 1, :now, :now)
                    """).param("id", resourceId).param("projectId", projectId).param("name", originalName)
                    .param("mediaType", mediaType).param("now", now).update();
            BlobStore.StoredObject stored;
            try (var input = Files.newInputStream(source)) {
                stored = blobStore.put(objectKey(projectId, resourceId, 1, originalName), input, sizeLimit);
            }
            var versionId = UUID.randomUUID().toString();
            jdbc.sql("""
                    insert into file_version(id, resource_id, version_number, original_name, media_type, storage_path,
                        size_bytes, checksum, parse_status, created_at)
                    values (:id, :resourceId, 1, :name, :mediaType, :path, :size, :checksum, 'QUEUED', :now)
                    """).param("id", versionId).param("resourceId", resourceId).param("name", originalName)
                    .param("mediaType", mediaType).param("path", stored.location()).param("size", stored.size())
                    .param("checksum", stored.checksum()).param("now", now).update();
            scheduleParse(versionId);
            return get(resourceId);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("可编辑副本创建失败", exception);
        }
    }

    public List<FileResourceResponse> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql(latestFileSql() + " where r.project_id = :projectId order by r.updated_at desc")
                .param("projectId", projectId).query(this::mapFile).list();
    }

    public FileResourceResponse get(String resourceId) {
        return jdbc.sql(latestFileSql() + " where r.id = :id").param("id", resourceId).query(this::mapFile).optional()
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
    }

    @Transactional
    public void delete(String resourceId) {
        get(resourceId);
        var locations = jdbc.sql("select storage_path from file_version where resource_id = :id")
                .param("id", resourceId).query(String.class).list();
        jdbc.sql("delete from office_working_copy where resource_id = :id or (source_kind = 'files' and source_id = :id)")
                .param("id", resourceId).update();
        jdbc.sql("delete from knowledge_ref where resource_id = :id").param("id", resourceId).update();
        jdbc.sql("delete from file_version where resource_id = :id").param("id", resourceId).update();
        jdbc.sql("delete from file_resource where id = :id").param("id", resourceId).update();
        afterCommit(() -> locations.forEach(blobStore::delete));
    }

    public Path filePath(String resourceId, Integer version) {
        var sql = version == null
                ? "select v.storage_path from file_resource r join file_version v on v.resource_id = r.id and v.version_number = r.current_version where r.id = :id"
                : "select storage_path from file_version where resource_id = :id and version_number = :version";
        var client = jdbc.sql(sql).param("id", resourceId);
        if (version != null) client.param("version", version);
        var value = client.query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("文件版本不存在"));
        return blobStore.materialize(value);
    }

    public List<RefResponse> search(String projectId, String query, int limit) {
        projects.get(projectId);
        var terms = tokens(query);
        if (terms.isEmpty()) return List.of();
        return jdbc.sql("""
                        select k.* from knowledge_ref k
                        join file_resource r on r.id = k.resource_id and r.current_version = k.version_number
                        where k.project_id = :projectId order by k.created_at desc limit 5000
                        """).param("projectId", projectId)
                .query((rs, rowNum) -> {
                    var text = rs.getString("text_content");
                    var score = score(terms, text);
                    return new RefResponse(rs.getString("id"), rs.getString("project_id"), rs.getString("resource_id"),
                            rs.getInt("version_number"), rs.getString("source_name"), text,
                            readMap(rs.getString("location_json")), rs.getString("content_hash"), score);
                }).list().stream().filter(item -> item.score() > 0).sorted(Comparator.comparingDouble(RefResponse::score).reversed())
                .limit(Math.max(1, Math.min(limit, 20))).toList();
    }

    public List<RefResponse> currentRefs(String resourceId, int limit) {
        get(resourceId);
        return jdbc.sql("""
                        select k.* from knowledge_ref k
                        join file_resource r on r.id = k.resource_id and r.current_version = k.version_number
                        where k.resource_id = :resourceId order by k.chunk_index limit :limit
                        """).param("resourceId", resourceId).param("limit", Math.max(1, Math.min(limit, 50)))
                .query((rs, rowNum) -> new RefResponse(rs.getString("id"), rs.getString("project_id"),
                        rs.getString("resource_id"), rs.getInt("version_number"), rs.getString("source_name"),
                        rs.getString("text_content"), readMap(rs.getString("location_json")),
                        rs.getString("content_hash"), 1.0)).list();
    }

    public List<RefResponse> sampledCurrentRefs(String resourceId, int limit) {
        get(resourceId);
        var refs = jdbc.sql("""
                        select k.* from knowledge_ref k
                        join file_resource r on r.id = k.resource_id and r.current_version = k.version_number
                        where k.resource_id = :resourceId order by k.chunk_index limit 5000
                        """).param("resourceId", resourceId)
                .query((rs, rowNum) -> new RefResponse(rs.getString("id"), rs.getString("project_id"),
                        rs.getString("resource_id"), rs.getInt("version_number"), rs.getString("source_name"),
                        rs.getString("text_content"), readMap(rs.getString("location_json")),
                        rs.getString("content_hash"), 1.0)).list();
        return evenlySample(refs, Math.max(1, Math.min(limit, 500)));
    }

    static <T> List<T> evenlySample(List<T> items, int limit) {
        if (items.size() <= limit) return List.copyOf(items);
        if (limit == 1) return List.of(items.getFirst());
        var sampled = new ArrayList<T>(limit);
        for (int index = 0; index < limit; index++) {
            var sourceIndex = (int) Math.round(index * (items.size() - 1.0) / (limit - 1.0));
            sampled.add(items.get(sourceIndex));
        }
        return List.copyOf(sampled);
    }

    public FileResourceResponse reparse(String resourceId) {
        var resource = get(resourceId);
        var versionId = jdbc.sql("select id from file_version where resource_id = :id and version_number = :version")
                .param("id", resourceId).param("version", resource.currentVersion())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("文件版本不存在"));
        jdbc.sql("update file_version set parse_status = 'QUEUED', parse_message = '' where id = :id")
                .param("id", versionId).update();
        jdbc.sql("update file_resource set status = 'PROCESSING', updated_at = :now where id = :id")
                .param("now", Instant.now()).param("id", resourceId).update();
        scheduleParse(versionId);
        return get(resourceId);
    }

    private void parse(String versionId) {
        var version = jdbc.sql("""
                        select v.*, r.project_id from file_version v join file_resource r on r.id = v.resource_id
                        where v.id = :id
                        """).param("id", versionId).query((rs, rowNum) -> new VersionRow(rs.getString("id"),
                        rs.getString("resource_id"), rs.getString("project_id"), rs.getInt("version_number"),
                        rs.getString("original_name"), rs.getString("storage_path"))).single();
        jdbc.sql("update file_version set parse_status = 'PROCESSING' where id = :id").param("id", versionId).update();
        try {
            var parsed = worker.parse(blobStore.materialize(version.location()), version.originalName());
            jdbc.sql("delete from knowledge_ref where file_version_id = :id").param("id", versionId).update();
            for (var chunk : parsed.chunks()) {
                var refId = version.resourceId() + ":v" + version.version() + ":" + chunk.index();
                jdbc.sql("""
                        insert into knowledge_ref(id, project_id, resource_id, file_version_id, version_number,
                            chunk_index, source_name, text_content, location_json, content_hash, created_at)
                        values (:id, :projectId, :resourceId, :versionId, :version, :chunkIndex, :sourceName,
                            :text, :location, :contentHash, :now)
                        """).param("id", refId).param("projectId", version.projectId()).param("resourceId", version.resourceId())
                        .param("versionId", versionId).param("version", version.version()).param("chunkIndex", chunk.index())
                        .param("sourceName", version.originalName()).param("text", chunk.text())
                        .param("location", writeJson(chunk.location())).param("contentHash", chunk.contentHash())
                        .param("now", Instant.now()).update();
            }
            var warning = parsed.warnings() == null ? "" : String.join("；", parsed.warnings());
            jdbc.sql("update file_version set parse_status = 'READY', parse_message = :message, parsed_at = :now where id = :id")
                    .param("message", warning).param("now", Instant.now()).param("id", versionId).update();
            jdbc.sql("update file_resource set status = 'READY', updated_at = :now where id = :id")
                    .param("now", Instant.now()).param("id", version.resourceId()).update();
        } catch (RuntimeException exception) {
            var message = exception.getMessage() == null ? "资料解析失败" : exception.getMessage();
            message = message.substring(0, Math.min(message.length(), 1800));
            jdbc.sql("update file_version set parse_status = 'FAILED', parse_message = :message where id = :id")
                    .param("message", message).param("id", versionId).update();
            jdbc.sql("update file_resource set status = 'FAILED', updated_at = :now where id = :id")
                    .param("now", Instant.now()).param("id", version.resourceId()).update();
        }
    }

    private BlobStore.StoredObject store(String projectId, String resourceId, int version, String name, MultipartFile file) {
        try {
            return blobStore.put(objectKey(projectId, resourceId, version, name), file.getInputStream(), maxFileBytes);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("文件保存失败", exception);
        }
    }

    private BlobStore.StoredObject storeBytes(String projectId, String resourceId, int version, String name, byte[] content) {
        return blobStore.putBytes(objectKey(projectId, resourceId, version, name), content, maxFileBytes);
    }

    private String objectKey(String projectId, String resourceId, int version, String name) {
        return projectId + "/knowledge/" + resourceId + "/v" + version + "/" + name;
    }

    private void scheduleParse(String versionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { taskExecutor.execute(() -> parse(versionId)); }
            });
        } else taskExecutor.execute(() -> parse(versionId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private Set<String> tokens(String text) {
        var result = new LinkedHashSet<String>();
        if (text == null) return result;
        for (var word : text.toLowerCase().split("[^a-z0-9_\\u4e00-\\u9fff]+")) {
            if (word.isBlank()) continue;
            if (word.matches("[\\u4e00-\\u9fff]+")) {
                for (var i = 0; i < Math.max(1, word.length() - 1); i++) result.add(word.substring(i, Math.min(word.length(), i + 2)));
            } else if (word.length() > 1) result.add(word);
        }
        return result;
    }

    private double score(Set<String> terms, String text) {
        var lower = text.toLowerCase();
        long matches = terms.stream().filter(lower::contains).count();
        return (double) matches / Math.sqrt(terms.size() * Math.max(1, lower.length() / 20.0));
    }

    private String latestFileSql() {
        return """
                select r.*, v.size_bytes, v.checksum, v.parse_status, v.parse_message
                from file_resource r join file_version v on v.resource_id = r.id and v.version_number = r.current_version
                """;
    }

    private FileResourceResponse mapFile(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FileResourceResponse(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("media_type"), rs.getString("status"), rs.getInt("current_version"),
                rs.getLong("size_bytes"), rs.getString("checksum"), rs.getString("parse_status"),
                rs.getString("parse_message"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String safeName(String value) {
        var name = value == null ? "未命名文件" : Path.of(value).getFileName().toString();
        name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (name.isBlank() || name.length() > 240) throw new IllegalArgumentException("文件名不正确");
        return name;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("Ref 位置无法保存", exception); }
    }

    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { return Map.of(); }
    }

    private record VersionRow(String id, String resourceId, String projectId, int version, String originalName, String location) { }
}
