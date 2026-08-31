package com.finflow.studio.workspace;

import com.finflow.studio.project.Project;

import java.time.Instant;
import java.util.List;

public final class WorkspaceModels {
    private WorkspaceModels() { }

    public record WorkflowSummary(String id, String name, String status, int currentVersion, Instant updatedAt) { }

    public record Folder(String id, String projectId, String parentId, String rootKind, String name,
                         int sortOrder, Instant createdAt, Instant updatedAt) { }

    public record FolderRequest(String parentId, String rootKind, String name) { }

    public record MoveResourceRequest(String folderId) { }

    public record Resource(
            String id,
            String projectId,
            String resourceType,
            String group,
            String name,
            String mediaType,
            String status,
            int currentVersion,
            long sizeBytes,
            boolean inProjectWorkflow,
            String folderId,
            String rootKind,
            Instant updatedAt,
            String url) { }

    public record Response(Project project, WorkflowSummary workflow, List<Folder> folders, List<Resource> resources) { }
}
