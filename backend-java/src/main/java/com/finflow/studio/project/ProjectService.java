package com.finflow.studio.project;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final JdbcClient jdbc;

    public ProjectService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Project> list() {
        return jdbc.sql("select * from project where deleted = false order by updated_at desc")
                .query(this::map)
                .list();
    }

    public Project get(String id) {
        return jdbc.sql("select * from project where id = :id and deleted = false")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
    }

    public Project create(String name, String description) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into project(id, name, description, status, deleted, created_at, updated_at)
                values (:id, :name, :description, 'ACTIVE', false, :createdAt, :updatedAt)
                """)
                .param("id", id)
                .param("name", name)
                .param("description", description == null ? "" : description)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return get(id);
    }

    public Project update(String id, String name, String description) {
        get(id);
        jdbc.sql("""
                update project set name = :name, description = :description, updated_at = :updatedAt
                where id = :id and deleted = false
                """).param("id", id).param("name", name.trim())
                .param("description", description == null ? "" : description.trim())
                .param("updatedAt", Instant.now()).update();
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        get(id);
        jdbc.sql("update project set deleted = true, status = 'DELETED', updated_at = :now where id = :id")
                .param("now", Instant.now()).param("id", id).update();
    }

    private Project map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
