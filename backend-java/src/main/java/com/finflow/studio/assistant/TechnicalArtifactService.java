package com.finflow.studio.assistant;

import com.finflow.studio.knowledge.KnowledgeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Version-pinned resources, not mutable display copies, are the execution source. */
@Service
public class TechnicalArtifactService {
    private final JdbcClient jdbc;
    private final KnowledgeService files;
    private final ObjectMapper json;
    public TechnicalArtifactService(JdbcClient jdbc, KnowledgeService files, ObjectMapper json) {
        this.jdbc = jdbc; this.files = files; this.json = json;
    }
    public record Artifact(String part, String resourceId, int version, String hash) { }
    public List<Artifact> list(String kind, String owner) {
        return jdbc.sql("select part_key,resource_id,resource_version,content_hash from technical_artifact where owner_kind=:kind and owner_id=:owner order by part_key")
                .param("kind", kind).param("owner", owner).query((rs, row) -> new Artifact(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4))).list();
    }
    public synchronized Artifact save(String project, String kind, String owner, String part, String name, String content) {
        var hash = HashSupport.sha256(content);
        var existing = list(kind, owner).stream().filter(a -> a.part().equals(part)).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().hash().equals(hash)) throw new IllegalStateException("已归档技术实现不可覆盖，请创建新版本");
            return existing.get();
        }
        var file = files.importBytes(project, name, name.endsWith(".json") ? "application/json" : "text/plain", content.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("insert into technical_artifact(owner_kind,owner_id,part_key,project_id,resource_id,resource_version,content_hash,created_at) values(:kind,:owner,:part,:project,:resource,:version,:hash,:now)")
                .param("kind", kind).param("owner", owner).param("part", part).param("project", project)
                .param("resource", file.id()).param("version", file.currentVersion()).param("hash", hash).param("now", Instant.now()).update();
        return new Artifact(part, file.id(), file.currentVersion(), hash);
    }
    public String read(Artifact artifact) {
        try {
            var content = Files.readString(files.filePath(artifact.resourceId(), artifact.version()), StandardCharsets.UTF_8);
            if (!HashSupport.sha256(content).equals(artifact.hash())) throw new IllegalStateException("技术资源完整性校验失败，已阻止执行");
            return content;
        } catch (java.io.IOException error) { throw new IllegalStateException("固定技术资源不可读取，已阻止执行", error); }
    }
    public synchronized Artifact pin(String project, String kind, String owner, String part, String resourceId, int version) {
        try {
            var content = Files.readString(files.filePath(resourceId, version), StandardCharsets.UTF_8);
            var hash = HashSupport.sha256(content);
            var existing = list(kind, owner).stream().filter(a -> a.part().equals(part)).findFirst();
            if (existing.isPresent()) {
                var value = existing.get();
                if (!value.resourceId().equals(resourceId) || value.version() != version || !value.hash().equals(hash))
                    throw new IllegalStateException("已归档技术资源与固定版本不一致");
                return value;
            }
            jdbc.sql("insert into technical_artifact(owner_kind,owner_id,part_key,project_id,resource_id,resource_version,content_hash,created_at) values(:kind,:owner,:part,:project,:resource,:version,:hash,:now)")
                    .param("kind", kind).param("owner", owner).param("part", part).param("project", project)
                    .param("resource", resourceId).param("version", version).param("hash", hash).param("now", Instant.now()).update();
            return new Artifact(part, resourceId, version, hash);
        } catch (java.io.IOException error) { throw new IllegalStateException("技术资源不可读取", error); }
    }
    public void archiveDefinition(String project, String id, String encoded, String expectedHash) {
        if (!HashSupport.sha256(encoded).equals(expectedHash)) throw new IllegalStateException("实现版本校验失败");
        var definition = json.readValue(encoded, FixedWorkDefinition.class);
        definition.validate();
        for (var step : definition.steps()) {
            var extension = "SQL".equals(step.kind()) ? ".sql" : "TOOL".equals(step.kind()) ? ".json" : ".txt";
            save(project, "DEFINITION", id, step.id(), "实现-" + id.substring(0, 8) + "-" + step.id() + extension, step.code());
        }
        save(project, "DEFINITION", id, "@manifest", "实现-" + id.substring(0, 8) + ".json", encoded);
    }
    public FixedWorkDefinition loadDefinition(String id, String expectedHash) {
        var artifacts = list("DEFINITION", id);
        var manifest = artifacts.stream().filter(a -> a.part().equals("@manifest")).findFirst()
                .orElseThrow(() -> new IllegalStateException("技术实现尚未归档，不能开始执行"));
        if (!manifest.hash().equals(expectedHash)) throw new IllegalStateException("技术实现版本不匹配");
        var definition = json.readValue(read(manifest), FixedWorkDefinition.class);
        definition.validate();
        for (var step : definition.steps()) {
            var code = artifacts.stream().filter(a -> a.part().equals(step.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("技术步骤缺少归档代码：" + step.id()));
            if (!step.code().equals(read(code))) throw new IllegalStateException("技术步骤与实现清单不一致");
        }
        return definition;
    }
}
