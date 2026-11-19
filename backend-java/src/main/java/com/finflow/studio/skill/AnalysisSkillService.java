package com.finflow.studio.skill;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.skill.AnalysisSkillModels.AnalysisSkill;
import com.finflow.studio.skill.AnalysisSkillModels.AnalysisSkillResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalysisSkillService {
    static final long MAX_SKILL_BYTES = 256 * 1024;

    private final JdbcClient jdbc;
    private final ProjectService projects;

    public AnalysisSkillService(JdbcClient jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    public List<AnalysisSkillResponse> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql("select * from analysis_skill where project_id = :projectId order by updated_at desc")
                .param("projectId", projectId).query(this::map).list().stream()
                .map(AnalysisSkillResponse::from).toList();
    }

    public AnalysisSkill get(String id) {
        var skill = jdbc.sql("select * from analysis_skill where id = :id")
                .param("id", id).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("分析 Skill 不存在"));
        projects.get(skill.projectId());
        return skill;
    }

    public AnalysisSkill getForProject(String projectId, String id) {
        projects.get(projectId);
        return jdbc.sql("select * from analysis_skill where id = :id and project_id = :projectId")
                .param("id", id).param("projectId", projectId).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("分析 Skill 不存在或不属于当前项目"));
    }

    @Transactional
    public AnalysisSkillResponse upload(String projectId, MultipartFile file) {
        projects.get(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Skill 文件不能为空");
        if (file.getSize() > MAX_SKILL_BYTES) throw new IllegalArgumentException("Skill 文件不能超过 256 KB");
        var sourceName = safeFileName(file.getOriginalFilename());
        var extension = sourceName.substring(sourceName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!List.of("md", "markdown", "txt").contains(extension)) {
            throw new IllegalArgumentException("Skill 仅支持 .md、.markdown 或 .txt 文件");
        }
        try {
            var bytes = file.getBytes();
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var content = decoder.decode(ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n").trim();
            if (content.length() < 20) throw new IllegalArgumentException("Skill 内容过短，请补充适用场景、步骤和检查方法");
            var metadata = metadata(content, sourceName);
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var existing = jdbc.sql("select id from analysis_skill where project_id = :projectId and lower(name) = lower(:name)")
                    .param("projectId", projectId).param("name", metadata.get("name"))
                    .query(String.class).optional();
            var now = Instant.now();
            var id = existing.orElseGet(() -> UUID.randomUUID().toString());
            if (existing.isPresent()) {
                jdbc.sql("""
                        update analysis_skill set description = :description, instructions = :instructions,
                            source_name = :sourceName, content_hash = :hash, updated_at = :now where id = :id
                        """).param("description", metadata.get("description")).param("instructions", content)
                        .param("sourceName", sourceName).param("hash", hash).param("now", now).param("id", id).update();
            } else {
                jdbc.sql("""
                        insert into analysis_skill(id, project_id, name, description, instructions, source_name,
                            content_hash, created_at, updated_at)
                        values (:id, :projectId, :name, :description, :instructions, :sourceName, :hash, :now, :now)
                        """).param("id", id).param("projectId", projectId).param("name", metadata.get("name"))
                        .param("description", metadata.get("description")).param("instructions", content)
                        .param("sourceName", sourceName).param("hash", hash).param("now", now).update();
            }
            return AnalysisSkillResponse.from(getForProject(projectId, id));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Skill 文件无法读取，请确认它是 UTF-8 文本", exception);
        }
    }

    @Transactional
    public void delete(String id) {
        var skill = get(id);
        jdbc.sql("delete from analysis_skill where id = :id and project_id = :projectId")
                .param("id", id).param("projectId", skill.projectId()).update();
    }

    private Map<String, String> metadata(String content, String sourceName) {
        var values = new LinkedHashMap<String, String>();
        if (content.startsWith("---\n")) {
            var end = content.indexOf("\n---", 4);
            if (end > 0) {
                for (var line : content.substring(4, end).split("\n")) {
                    var separator = line.indexOf(':');
                    if (separator > 0) values.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                            unquote(line.substring(separator + 1).trim()));
                }
            }
        }
        var fallbackName = sourceName.substring(0, sourceName.lastIndexOf('.')).replaceAll("[_-]+", " ").trim();
        var name = values.getOrDefault("name", fallbackName).trim();
        if (name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Skill 名称需为 1 到 200 个字符");
        var description = values.getOrDefault("description", firstDescription(content)).trim();
        if (description.length() > 1000) description = description.substring(0, 1000);
        return Map.of("name", name, "description", description);
    }

    private String firstDescription(String content) {
        for (var line : content.split("\n")) {
            var value = line.trim();
            if (!value.isBlank() && !value.equals("---") && !value.startsWith("#") && !value.matches("[A-Za-z_-]+:.*")) {
                return value;
            }
        }
        return "用户上传的项目分析方法";
    }

    private String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) return value.substring(1, value.length() - 1);
        return value;
    }

    private String safeFileName(String originalName) {
        var value = originalName == null ? "skill.md" : originalName.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (value.isBlank() || value.length() > 500 || !value.contains(".")) throw new IllegalArgumentException("Skill 文件名不正确");
        return value;
    }

    private AnalysisSkill map(ResultSet rs, int rowNum) throws SQLException {
        return new AnalysisSkill(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("instructions"), rs.getString("source_name"),
                rs.getString("content_hash"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
