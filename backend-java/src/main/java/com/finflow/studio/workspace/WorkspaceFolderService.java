package com.finflow.studio.workspace;

import com.finflow.studio.workspace.WorkspaceModels.Folder;
import com.finflow.studio.workspace.WorkspaceModels.FolderRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkspaceFolderService {
    private static final List<String> ROOT_KINDS = List.of("FILES", "DATABASES", "WEB_URLS", "APIS", "OUTPUTS");
    private final JdbcClient jdbc;

    public WorkspaceFolderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Folder> list(String projectId) {
        return jdbc.sql("select * from workspace_folder where project_id = :projectId order by sort_order, name")
                .param("projectId", projectId).query(this::map).list();
    }

    public Map<String, String> locations(String projectId) {
        return jdbc.sql("select resource_type, resource_id, folder_id from workspace_resource_location where project_id = :projectId")
                .param("projectId", projectId)
                .query((rs, row) -> Map.entry(key(rs.getString("resource_type"), rs.getString("resource_id")), rs.getString("folder_id")))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Folder create(String projectId, FolderRequest request) {
        var name = cleanName(request.name());
        var rootKind = cleanRoot(request.rootKind());
        validateParent(projectId, request.parentId(), rootKind, null);
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into workspace_folder(id, project_id, parent_id, root_kind, name, sort_order, created_at, updated_at)
                values (:id, :projectId, :parentId, :rootKind, :name, 0, :now, :now)
                """).param("id", id).param("projectId", projectId).param("parentId", request.parentId())
                .param("rootKind", rootKind).param("name", name).param("now", now).update();
        return get(projectId, id);
    }

    public Folder update(String projectId, String id, FolderRequest request) {
        var current = get(projectId, id);
        var rootKind = request.rootKind() == null || request.rootKind().isBlank() ? current.rootKind() : cleanRoot(request.rootKind());
        var parentId = request.parentId();
        validateParent(projectId, parentId, rootKind, id);
        jdbc.sql("""
                update workspace_folder set parent_id = :parentId, root_kind = :rootKind, name = :name, updated_at = :now
                where id = :id and project_id = :projectId
                """).param("parentId", parentId).param("rootKind", rootKind).param("name", cleanName(request.name()))
                .param("now", Instant.now()).param("id", id).param("projectId", projectId).update();
        return get(projectId, id);
    }

    @Transactional
    public void delete(String projectId, String id) {
        get(projectId, id);
        var childCount = jdbc.sql("select count(*) from workspace_folder where project_id = :projectId and parent_id = :id")
                .param("projectId", projectId).param("id", id).query(Integer.class).single();
        var resourceCount = jdbc.sql("select count(*) from workspace_resource_location where project_id = :projectId and folder_id = :id")
                .param("projectId", projectId).param("id", id).query(Integer.class).single();
        if (childCount > 0 || resourceCount > 0) throw new IllegalStateException("目录不为空，请先移动其中的内容");
        jdbc.sql("delete from workspace_folder where id = :id and project_id = :projectId")
                .param("id", id).param("projectId", projectId).update();
    }

    public void moveResource(String projectId, String resourceType, String resourceId, String expectedRoot, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            jdbc.sql("delete from workspace_resource_location where project_id = :projectId and resource_type = :type and resource_id = :resourceId")
                    .param("projectId", projectId).param("type", resourceType).param("resourceId", resourceId).update();
            return;
        }
        var folder = get(projectId, folderId);
        if (!folder.rootKind().equals(expectedRoot)) throw new IllegalArgumentException("资源只能移动到同一分类下的目录");
        var now = Instant.now();
        var updated = jdbc.sql("""
                update workspace_resource_location set folder_id = :folderId, updated_at = :now
                where project_id = :projectId and resource_type = :type and resource_id = :resourceId
                """).param("projectId", projectId).param("type", resourceType).param("resourceId", resourceId)
                .param("folderId", folderId).param("now", now).update();
        if (updated == 0) {
            jdbc.sql("""
                    insert into workspace_resource_location(project_id, resource_type, resource_id, folder_id, updated_at)
                    values (:projectId, :type, :resourceId, :folderId, :now)
                    """).param("projectId", projectId).param("type", resourceType).param("resourceId", resourceId)
                    .param("folderId", folderId).param("now", now).update();
        }
    }

    private Folder get(String projectId, String id) {
        return jdbc.sql("select * from workspace_folder where id = :id and project_id = :projectId")
                .param("id", id).param("projectId", projectId).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("目录不存在"));
    }

    private void validateParent(String projectId, String parentId, String rootKind, String selfId) {
        if (parentId == null || parentId.isBlank()) return;
        if (parentId.equals(selfId)) throw new IllegalArgumentException("目录不能放入自身");
        var parent = get(projectId, parentId);
        if (!parent.rootKind().equals(rootKind)) throw new IllegalArgumentException("上级目录必须位于同一分类");
        var cursor = parent;
        while (cursor.parentId() != null) {
            if (cursor.parentId().equals(selfId)) throw new IllegalArgumentException("不能形成循环目录");
            cursor = get(projectId, cursor.parentId());
        }
    }

    private String cleanName(String name) {
        var value = name == null ? "" : name.trim();
        if (value.isBlank()) throw new IllegalArgumentException("请输入目录名称");
        if (value.length() > 200) throw new IllegalArgumentException("目录名称不能超过 200 个字");
        return value;
    }

    private String cleanRoot(String rootKind) {
        if (!ROOT_KINDS.contains(rootKind)) throw new IllegalArgumentException("目录分类无效");
        return rootKind;
    }

    private String key(String resourceType, String resourceId) { return resourceType + ":" + resourceId; }

    private Folder map(ResultSet rs, int rowNum) throws SQLException {
        return new Folder(rs.getString("id"), rs.getString("project_id"), rs.getString("parent_id"),
                rs.getString("root_kind"), rs.getString("name"), rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
