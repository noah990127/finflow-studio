package com.finflow.studio.workspace;

import com.finflow.studio.workspace.WorkspaceModels.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/workspace")
public class WorkspaceController {
    private final WorkspaceResourceService workspace;
    private final WorkspaceFolderService folders;
    private final WebPreviewService webPreviews;

    public WorkspaceController(WorkspaceResourceService workspace, WorkspaceFolderService folders,
                               WebPreviewService webPreviews) {
        this.workspace = workspace;
        this.folders = folders;
        this.webPreviews = webPreviews;
    }

    @GetMapping
    public Response get(@PathVariable String projectId) {
        return workspace.get(projectId);
    }

    @GetMapping("/resources")
    public Response resources(@PathVariable String projectId) {
        return workspace.get(projectId);
    }

    @GetMapping("/web-preview/{resourceId}")
    public WorkspaceModels.WebPreview webPreview(@PathVariable String projectId, @PathVariable String resourceId,
                                                  @RequestParam(defaultValue = "false") boolean refresh) {
        return webPreviews.get(projectId, resourceId, refresh);
    }

    @GetMapping("/web-embed-status/{resourceId}")
    public WorkspaceModels.WebEmbedStatus webEmbedStatus(@PathVariable String projectId,
                                                          @PathVariable String resourceId,
                                                          @RequestParam(defaultValue = "") String studioOrigin) {
        return webPreviews.embedStatus(projectId, resourceId, studioOrigin);
    }

    @PostMapping("/folders")
    public WorkspaceModels.Folder createFolder(@PathVariable String projectId, @RequestBody WorkspaceModels.FolderRequest request) {
        return folders.create(projectId, request);
    }

    @PutMapping("/folders/{folderId}")
    public WorkspaceModels.Folder updateFolder(@PathVariable String projectId, @PathVariable String folderId,
                                                @RequestBody WorkspaceModels.FolderRequest request) {
        return folders.update(projectId, folderId, request);
    }

    @DeleteMapping("/folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(@PathVariable String projectId, @PathVariable String folderId) {
        folders.delete(projectId, folderId);
    }

    @PutMapping("/resources/{resourceType}/{resourceId}/folder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveResource(@PathVariable String projectId, @PathVariable String resourceType,
                             @PathVariable String resourceId, @RequestBody WorkspaceModels.MoveResourceRequest request) {
        var resource = workspace.get(projectId).resources().stream()
                .filter(item -> item.id().equals(resourceId) && item.resourceType().equals(resourceType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("资源不存在"));
        folders.moveResource(projectId, resourceType, resourceId, resource.rootKind(), request.folderId());
    }
}
