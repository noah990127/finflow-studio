package com.finflow.studio.workspace;

import com.finflow.studio.data.DataConnectionService;
import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workspace.WorkspaceModels.Resource;
import com.finflow.studio.workspace.WorkspaceModels.Response;
import com.finflow.studio.workspace.WorkspaceModels.WorkflowSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkspaceResourceService {
    private final ProjectService projects;
    private final DataConnectionService connections;
    private final ExtractJobService extracts;
    private final KnowledgeService files;
    private final DeliverableService deliverables;
    private final WorkflowDefinitionService workflows;
    private final WorkspaceFolderService folders;

    public WorkspaceResourceService(ProjectService projects, DataConnectionService connections,
                                    ExtractJobService extracts, KnowledgeService files,
                                    DeliverableService deliverables, WorkflowDefinitionService workflows,
                                    WorkspaceFolderService folders) {
        this.projects = projects;
        this.connections = connections;
        this.extracts = extracts;
        this.files = files;
        this.deliverables = deliverables;
        this.workflows = workflows;
        this.folders = folders;
    }

    public Response get(String projectId) {
        var project = projects.get(projectId);
        var projectWorkflows = workflows.list(projectId);
        if (projectWorkflows.isEmpty()) projectWorkflows = java.util.List.of(workflows.getProjectWorkflow(projectId));
        var usedIds = new HashSet<String>();
        projectWorkflows.forEach(workflow -> usedIds.addAll(referencedResourceIds(workflow.nodes())));
        var resources = new ArrayList<Resource>();
        var locations = folders.locations(projectId);

        connections.list(projectId).forEach(item -> resources.add(new Resource(
                item.id(), projectId, item.sourceType() == com.finflow.studio.data.DataModels.SourceType.HTTP_API
                        ? "API_CONNECTION" : "DATABASE_CONNECTION",
                "DATA", item.name(), item.sourceType().name(), item.status(), 1, 0,
                usedIds.contains(item.id()), locations.get(resourceKey(item.sourceType() == com.finflow.studio.data.DataModels.SourceType.HTTP_API ? "API_CONNECTION" : "DATABASE_CONNECTION", item.id())),
                item.sourceType() == com.finflow.studio.data.DataModels.SourceType.HTTP_API ? "APIS" : "DATABASES", item.updatedAt(), null)));

        extracts.list(projectId).forEach(item -> resources.add(new Resource(
                item.id(), projectId, "DATASET", "DATA", item.outputName() == null || item.outputName().isBlank()
                        ? item.name() : item.outputName(), "text/csv", item.status(), 1, item.byteCount(),
                usedIds.contains(item.id()), locations.get(resourceKey("DATASET", item.id())), "FILES", latest(item.finishedAt(), item.createdAt()), null)));

        files.list(projectId).forEach(item -> resources.add(new Resource(
                item.id(), projectId, fileType(item.name(), item.mediaType()), fileGroup(item.name(), item.mediaType()),
                item.name(), item.mediaType(), item.status(), item.currentVersion(), item.sizeBytes(),
                usedIds.contains(item.id()), locations.get(resourceKey(fileType(item.name(), item.mediaType()), item.id())), "FILES", item.updatedAt(), null)));

        deliverables.list(projectId).forEach(item -> resources.add(new Resource(
                item.id(), projectId, "DELIVERABLE", "OUTPUT", item.name(), item.format(), item.status(),
                item.currentVersion(), item.sizeBytes(), usedIds.contains(item.id()), locations.get(resourceKey("DELIVERABLE", item.id())), "OUTPUTS", item.updatedAt(), null)));

        var urls = new HashSet<String>();
        projectWorkflows.forEach(workflow -> workflow.nodes().stream()
                .filter(node -> node.type() == com.finflow.studio.workflow.WorkflowModels.NodeType.LINK_INPUT)
                .forEach(node -> {
                    var config = node.config() == null ? Map.<String, Object>of() : node.config();
                    var url = Objects.toString(config.get("url"), "");
                    if (!url.isBlank() && urls.add(node.id())) resources.add(new Resource(node.id(), projectId,
                            "WEB_URL", "KNOWLEDGE", Objects.toString(config.getOrDefault("title", node.name()), node.name()),
                            "text/uri-list", "READY", 1, 0, true,
                            locations.get(resourceKey("WEB_URL", node.id())), "FILES", workflow.updatedAt(), url));
                }));

        resources.sort((left, right) -> right.updatedAt().compareTo(left.updatedAt()));
        var summaries = projectWorkflows.stream().map(workflow -> new WorkflowSummary(workflow.id(), workflow.name(),
                workflow.status(), workflow.currentVersion(), workflow.updatedAt())).toList();
        var latest = summaries.isEmpty() ? null : summaries.getFirst();
        return new Response(project, latest, summaries, folders.list(projectId), resources);
    }

    private Set<String> referencedResourceIds(java.util.List<NodeDefinition> nodes) {
        var ids = new HashSet<String>();
        for (var node : nodes) {
            Map<String, Object> config = node.config() == null ? Map.of() : node.config();
            for (var key : java.util.List.of("resourceId", "connectionId", "datasetId", "extractJobId", "outputResourceId")) {
                var value = Objects.toString(config.get(key), "");
                if (!value.isBlank()) ids.add(value);
            }
        }
        return ids;
    }

    private String fileType(String name, String mediaType) {
        var lower = name.toLowerCase();
        if (lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) {
            return "DATA_FILE";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "OFFICE_FILE";
        }
        return "KNOWLEDGE_FILE";
    }

    private String fileGroup(String name, String mediaType) {
        return fileType(name, mediaType).equals("DATA_FILE") ? "DATA" : "KNOWLEDGE";
    }

    private Instant latest(Instant preferred, Instant fallback) {
        return preferred == null ? fallback : preferred;
    }

    private String resourceKey(String resourceType, String resourceId) { return resourceType + ":" + resourceId; }
}
