package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.MemoryRequest;
import com.finflow.studio.assistant.AssistantModels.MemoryResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentMemoryService {
    private static final String ACTOR = "default_user";
    private static final Set<String> SCOPES = Set.of("SESSION", "PROJECT", "USER_PREFERENCE");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AgentMemoryService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<MemoryResponse> list(String projectId) {
        return jdbc.sql("""
                select * from agent_memory where actor_id = :actor and status = 'ACTIVE'
                    and (project_id = :projectId or project_id = '')
                order by case memory_scope when 'USER_PREFERENCE' then 2 else 1 end, updated_at desc
                """).param("actor", ACTOR).param("projectId", normalizeProject(projectId))
                .query(this::map).list();
    }

    public MemoryResponse save(MemoryRequest request) {
        var scope = request.scope().toUpperCase();
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException("不支持的记忆范围");
        var projectId = "USER_PREFERENCE".equals(scope) ? "" : normalizeProject(request.projectId());
        if (!"USER_PREFERENCE".equals(scope) && projectId.isBlank()) throw new IllegalArgumentException("项目记忆必须属于一个项目");
        var existing = jdbc.sql("""
                select id from agent_memory where actor_id = :actor and project_id = :projectId
                    and memory_scope = :scope and memory_key = :key
                """).param("actor", ACTOR).param("projectId", projectId).param("scope", scope)
                .param("key", request.key().trim()).query(String.class).optional();
        var now = Instant.now();
        var id = existing.orElseGet(() -> UUID.randomUUID().toString());
        if (existing.isPresent()) {
            jdbc.sql("""
                    update agent_memory set value_json = :value, source_ref = :sourceRef,
                        status = 'ACTIVE', updated_at = :now where id = :id
                    """).param("value", writeJson(request.value())).param("sourceRef", safe(request.sourceRef()))
                    .param("now", now).param("id", id).update();
        } else {
            jdbc.sql("""
                    insert into agent_memory(id, actor_id, project_id, memory_scope, memory_key, value_json,
                        source_ref, status, created_at, updated_at)
                    values (:id, :actor, :projectId, :scope, :key, :value, :sourceRef, 'ACTIVE', :now, :now)
                    """).param("id", id).param("actor", ACTOR).param("projectId", projectId).param("scope", scope)
                    .param("key", request.key().trim()).param("value", writeJson(request.value()))
                    .param("sourceRef", safe(request.sourceRef())).param("now", now).update();
        }
        return get(id);
    }

    public void delete(String id) {
        get(id);
        jdbc.sql("update agent_memory set status = 'DELETED', updated_at = :now where id = :id")
                .param("now", Instant.now()).param("id", id).update();
    }

    private MemoryResponse get(String id) {
        return jdbc.sql("select * from agent_memory where id = :id and actor_id = :actor")
                .param("id", id).param("actor", ACTOR).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
    }

    private MemoryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryResponse(rs.getString("id"), rs.getString("actor_id"), rs.getString("project_id"),
                rs.getString("memory_scope"), rs.getString("memory_key"), readMap(rs.getString("value_json")),
                rs.getString("source_ref"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String normalizeProject(String value) { return value == null ? "" : value.trim(); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("记忆无法保存", exception); }
    }
    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException exception) { return Map.of(); }
    }
}
