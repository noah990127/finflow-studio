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

    public record WebPreview(String title, String url, String siteName, String summary,
                             List<WebPreviewFact> facts, List<String> highlights, List<WebPreviewSection> sections,
                             List<WebPreviewLink> links,
                             String previewMode, String verifiedAt, String fetchedAt) { }

    public record WebEmbedStatus(String status, String reason) { }

    public record WebPreviewSection(String heading, List<String> paragraphs) { }

    public record WebPreviewFact(String label, String value) { }

    public record WebPreviewLink(String label, String url) { }

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

    public record Response(Project project, WorkflowSummary workflow, List<WorkflowSummary> workflows,
                           List<Folder> folders, List<Resource> resources, java.util.Set<String> internalResourceIds) { }
}
