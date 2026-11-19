package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.data.DataConnectionService;
import com.finflow.studio.data.DataModels.CreateConnectionRequest;
import com.finflow.studio.data.DataModels.CreateExtractRequest;
import com.finflow.studio.data.DataModels.PreviewConnectionRequest;
import com.finflow.studio.data.DataModels.SourceType;
import com.finflow.studio.data.DataTransformService;
import com.finflow.studio.data.StructuredDataMaterializer;
import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.SectionRequest;
import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.EdgeDefinition;
import com.finflow.studio.workflow.WorkflowModels.ExecutionMode;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.workspace.WorkspaceFolderService;
import com.finflow.studio.workspace.WorkspaceFileClassifier;
import com.finflow.studio.workspace.WorkspaceModels.FolderRequest;
import com.finflow.studio.workspace.WorkspaceModels.Resource;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class AssistantWorkspaceToolGateway {
    private static final Set<String> SUPPORTED = Set.of(
            "workspace.select",
            "project.list", "project.open", "project.rename", "project.delete",
            "folder.create", "folder.rename", "folder.move", "folder.delete",
            "resource.upload", "resource.add", "resource.open", "resource.read", "resource.edit",
            "resource.rename", "resource.move", "resource.delete",
            "resource.prepare_delete", "resource.delete_batch",
            "source.verify", "source.add_verified",
            "knowledge.read", "knowledge.parse", "knowledge.extract_table",
            "dataset.add_source", "dataset.connect", "dataset.import", "dataset.query", "dataset.extract",
            "dataset.create", "dataset.transform", "dataset.open", "dataset.delete", "dataset.profile",
            "workflow.open", "workflow.edit", "workflow.add_node", "workflow.remove_node", "workflow.connect",
            "workflow.duplicate", "workflow.save_version", "workflow.delete",
            "deliverable.open", "deliverable.edit", "deliverable.export", "deliverable.delete"
    );

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final WorkspaceResourceService workspace;
    private final WorkspaceFolderService folders;
    private final KnowledgeService knowledge;
    private final DataConnectionService connections;
    private final com.finflow.studio.data.ExtractJobService extracts;
    private final DataTransformService transforms;
    private final WorkflowDefinitionService workflows;
    private final DeliverableService deliverables;
    private final WorkerClient worker;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate deletionTransaction;

    public AssistantWorkspaceToolGateway(JdbcClient jdbc, ProjectService projects, WorkspaceResourceService workspace,
                                         WorkspaceFolderService folders, KnowledgeService knowledge,
                                         DataConnectionService connections,
                                         com.finflow.studio.data.ExtractJobService extracts,
                                         DataTransformService transforms, WorkflowDefinitionService workflows,
                                         DeliverableService deliverables, WorkerClient worker,
                                         tools.jackson.databind.ObjectMapper objectMapper,
                                         org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.workspace = workspace;
        this.folders = folders;
        this.knowledge = knowledge;
        this.connections = connections;
        this.extracts = extracts;
        this.transforms = transforms;
        this.workflows = workflows;
        this.deliverables = deliverables;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.deletionTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.deletionTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public static boolean supports(String tool) {
        return SUPPORTED.contains(tool);
    }

    public static Set<String> supportedTools() {
        return SUPPORTED;
    }

    public String execute(PlanStep step, Map<String, Object> effects) {
        AssistantToolContracts.validate(step.tool(), step.arguments());
        return switch (step.tool()) {
            case "workspace.select" -> select(step, effects);
            case "project.list" -> listProjects(step, effects);
            case "project.open" -> openProject(step, effects);
            case "project.rename" -> renameProject(step, effects);
            case "project.delete" -> deleteProject(step, effects);
            case "folder.create" -> createFolder(step, effects);
            case "folder.rename", "folder.move" -> updateFolder(step, effects);
            case "folder.delete" -> deleteFolder(step, effects);
            case "resource.upload" -> uploadResource(step, effects);
            case "resource.add" -> addResource(step, effects);
            case "source.verify" -> verifySource(step, effects);
            case "source.add_verified" -> addVerifiedSource(step, effects);
            case "resource.open" -> openResource(step, effects);
            case "resource.read" -> readResource(step, effects);
            case "resource.edit" -> editResource(step, effects);
            case "resource.rename" -> renameResource(step, effects);
            case "resource.move" -> moveResource(step, effects);
            case "resource.delete" -> deleteResource(step, effects);
            case "resource.prepare_delete" -> prepareDeletion(step, effects);
            case "resource.delete_batch" -> deleteBatch(step, effects);
            case "knowledge.read" -> readKnowledge(step, effects);
            case "knowledge.parse" -> parseKnowledge(step, effects);
            case "knowledge.extract_table" -> extractTable(step, effects);
            case "dataset.add_source" -> addDataSource(step, effects);
            case "dataset.connect" -> connectDataSource(step, effects);
            case "dataset.import", "dataset.extract" -> importDataset(step, effects);
            case "dataset.query" -> queryDataset(step, effects);
            case "dataset.create" -> createDataset(step, effects);
            case "dataset.transform" -> transformDataset(step, effects);
            case "dataset.open" -> openDataset(step, effects);
            case "dataset.delete" -> deleteDataset(step, effects);
            case "dataset.profile" -> profileDataset(step, effects);
            case "workflow.open" -> openWorkflow(step, effects);
            case "workflow.edit" -> editWorkflow(step, effects);
            case "workflow.duplicate" -> duplicateWorkflow(step, effects);
            case "workflow.add_node" -> addWorkflowNode(step, effects);
            case "workflow.remove_node" -> removeWorkflowNode(step, effects);
            case "workflow.connect" -> connectWorkflow(step, effects);
            case "workflow.save_version" -> saveWorkflowVersion(step, effects);
            case "workflow.delete" -> deleteWorkflow(step, effects);
            case "deliverable.open" -> openDeliverable(step, effects);
            case "deliverable.edit" -> editDeliverable(step, effects);
            case "deliverable.export" -> exportDeliverable(step, effects);
            case "deliverable.delete" -> deleteDeliverable(step, effects);
            default -> throw new IllegalArgumentException("未注册的工作台工具：" + step.tool());
        };
    }

    private String select(PlanStep step, Map<String, Object> effects) {
        var action = new LinkedHashMap<String, Object>();
        action.put("type", "SELECT_RESOURCE");
        action.put("resourceId", required(step, "target_id", "目标"));
        action.put("resourceType", argument(step, "target_type", "RESOURCE"));
        putIfPresent(action, "panel", step.arguments().get("panel"));
        effects.put("uiAction", action);
        return "已在工作区选择目标对象";
    }

    private String listProjects(PlanStep step, Map<String, Object> effects) {
        var query = argument(step, "query", "").toLowerCase(Locale.ROOT);
        var items = projects.list().stream()
                .filter(project -> query.isBlank() || project.name().toLowerCase(Locale.ROOT).contains(query))
                .map(project -> Map.<String, Object>of("id", project.id(), "name", project.name(),
                        "description", project.description(), "status", project.status()))
                .toList();
        effects.put("projects", items);
        return "已找到 " + items.size() + " 个项目";
    }

    private String openProject(PlanStep step, Map<String, Object> effects) {
        var project = projects.get(required(step, "project_id", "项目"));
        effects.put("currentProjectId", project.id());
        effects.put("currentProjectName", project.name());
        effects.put("uiAction", Map.of("type", "OPEN_PROJECT", "projectId", project.id(),
                "refreshWorkspace", true));
        return "已打开项目“" + project.name() + "”";
    }

    private String renameProject(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "project_id", "项目");
        var current = projects.get(id);
        var updated = projects.update(id, required(step, "new_name", "新名称"), current.description());
        effects.put("uiAction", Map.of("type", "OPEN_PROJECT", "projectId", id, "refreshWorkspace", true));
        return "已将项目重命名为“" + updated.name() + "”";
    }

    private String deleteProject(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "project_id", "项目");
        projects.delete(id);
        effects.put("uiAction", Map.of("type", "OPEN_HOME", "refreshWorkspace", true));
        return "已删除项目";
    }

    private String createFolder(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var folder = folders.create(projectId, new FolderRequest(nullable(step, "parent_id"),
                rootKind(argument(step, "group", "FILES")), required(step, "name", "目录名称")));
        effects.put("folderId", folder.id());
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已创建目录“" + folder.name() + "”";
    }

    private String updateFolder(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "folder_id", "目录");
        var current = folders.list(projectId).stream().filter(folder -> folder.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目录不存在"));
        var name = argument(step, "new_name", current.name());
        var parentId = step.arguments().containsKey("target_parent_id") ? nullable(step, "target_parent_id") : current.parentId();
        var updated = folders.update(projectId, id, new FolderRequest(parentId, current.rootKind(), name));
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已更新目录“" + updated.name() + "”";
    }

    private String deleteFolder(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        folders.delete(projectId, required(step, "folder_id", "目录"));
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已删除目录";
    }

    private String deleteWorkflow(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "workflow_id", "工作流");
        var workflow = workflows.get(id);
        if (!projectId.equals(workflow.projectId())) throw new IllegalArgumentException("工作流不属于当前项目");
        workflows.delete(id);
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已删除工作流“" + workflow.name() + "”";
    }

    private String uploadResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var content = required(step, "content", "文件内容");
        var name = required(step, "file_name", "文件名");
        var mediaType = argument(step, "media_type", mediaType(name));
        var resource = knowledge.importBytes(projectId, name, mediaType, content.getBytes(StandardCharsets.UTF_8));
        moveIfRequested(projectId, resource.id(), fileResourceType(resource.name()), "FILES", nullable(step, "folder_id"));
        effects.put("resourceId", resource.id());
        effects.put("uiAction", resourceAction(projectId, resource.id(), true));
        return "已上传“" + resource.name() + "”";
    }

    private String addResource(PlanStep step, Map<String, Object> effects) {
        var url = argument(step, "url", "");
        if (url.isBlank()) return uploadResource(step, effects);
        return addVerifiedLinkSource(step, effects);
    }

    private String addVerifiedLinkSource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var url = required(step, "url", "资料地址");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IllegalArgumentException("资源地址必须使用 HTTP 或 HTTPS");
        var name = argument(step, "name", url);
        var fetched = fetchWebUrl(url, name);
        var current = workflows.ensureProjectWorkflow(projectId);
        var nodes = new ArrayList<>(current.nodes());
        var id = "link_" + shortId();
        nodes.add(new NodeDefinition(id, NodeType.LINK_INPUT, name, 80, 80 + nodes.size() * 90,
                Map.of("title", name, "url", url, "verified", true,
                        "finalUrl", Objects.toString(fetched.get("final_url"), url),
                        "contentHash", Objects.toString(fetched.get("content_hash"), ""),
                        "verifiedAt", Instant.now().toString())));
        var updated = workflows.update(current.id(), save(current, nodes, current.edges()));
        var snapshotBody = "来源标题：" + Objects.toString(fetched.get("title"), name)
                + "\n原始地址：" + url
                + "\n最终地址：" + Objects.toString(fetched.get("final_url"), url)
                + "\n验证时间：" + Instant.now()
                + "\n内容类型：" + Objects.toString(fetched.get("content_type"), "")
                + "\n内容哈希：" + Objects.toString(fetched.get("content_hash"), "")
                + "\n\n" + Objects.toString(fetched.get("text"), "");
        var snapshot = knowledge.importBytes(projectId, name + "-网页快照.md", "text/markdown",
                snapshotBody.getBytes(StandardCharsets.UTF_8));
        moveIfRequested(projectId, snapshot.id(), fileResourceType(snapshot.name()), "FILES", nullable(step, "folder_id"));
        var verified = verifiedSource(id, updated.currentVersion(), url, fetched, snapshot.id());
        appendVerifiedSource(effects, verified);
        appendCitation(effects, citationFromVerified(verified));
        effects.put("resourceId", id);
        effects.put("snapshotResourceId", snapshot.id());
        effects.put("workflowId", current.id());
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId,
                "resourceId", id, "refreshWorkspace", true));
        return "已验证并添加网页来源“" + name + "”，同时保存了可追溯快照";
    }

    private String verifySource(PlanStep step, Map<String, Object> effects) {
        var url = required(step, "url", "资料地址");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IllegalArgumentException("资源地址必须使用 HTTP 或 HTTPS");
        var fetched = fetchWebUrl(url, argument(step, "name", url));
        var verified = verifiedSource("", 0, url, fetched, null);
        effects.put("verifiedSourceCandidate", verified);
        return "来源已验证，可读取“" + Objects.toString(fetched.get("title"), argument(step, "name", url)) + "”";
    }

    private String addVerifiedSource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        projects.get(projectId);
        var candidates = sourceCandidates(step);
        List<SourceFetch> fetchedSources;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = candidates.stream().map(candidate -> CompletableFuture.supplyAsync(() -> {
                try {
                    return new SourceFetch(candidate.url(), candidate.name(),
                            fetchWebUrl(candidate.url(), candidate.name()), null);
                } catch (RuntimeException exception) {
                    return new SourceFetch(candidate.url(), candidate.name(), Map.of(), exception.getMessage());
                }
            }, executor)).toList();
            fetchedSources = tasks.stream().map(CompletableFuture::join).toList();
        }
        var successful = fetchedSources.stream().filter(item -> item.error() == null).toList();
        if (successful.isEmpty()) {
            var reasons = fetchedSources.stream().map(item -> item.name() + "：" + item.error()).toList();
            throw new IllegalStateException("来源验证失败，未加入工作区：" + String.join("；", reasons));
        }
        var sourceIds = new ArrayList<String>();
        var snapshotIds = new ArrayList<String>();
        for (var index = 0; index < successful.size(); index++) {
            var item = successful.get(index);
            var snapshotBody = "来源标题：" + Objects.toString(item.fetched().get("title"), item.name())
                    + "\n原始地址：" + item.url()
                    + "\n最终地址：" + Objects.toString(item.fetched().get("final_url"), item.url())
                    + "\n验证时间：" + Instant.now()
                    + "\n内容类型：" + Objects.toString(item.fetched().get("content_type"), "")
                    + "\n内容哈希：" + Objects.toString(item.fetched().get("content_hash"), "")
                    + "\n\n" + Objects.toString(item.fetched().get("text"), "");
            var snapshot = knowledge.importBytes(projectId, item.name() + "-网页快照.md", "text/markdown",
                    snapshotBody.getBytes(StandardCharsets.UTF_8));
            sourceIds.add(snapshot.id());
            snapshotIds.add(snapshot.id());
            moveIfRequested(projectId, snapshot.id(), fileResourceType(snapshot.name()), "FILES", nullable(step, "folder_id"));
            var verified = verifiedSource(snapshot.id(), snapshot.currentVersion(), item.url(), item.fetched(), snapshot.id());
            appendVerifiedSource(effects, verified);
            appendCitation(effects, citationFromVerified(verified));
        }
        effects.put("resourceId", sourceIds.getLast());
        effects.put("resourceIds", sourceIds);
        effects.put("snapshotResourceId", snapshotIds.getLast());
        effects.put("snapshotResourceIds", snapshotIds);
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId,
                "resourceId", sourceIds.getLast(), "refreshWorkspace", true));
        var failed = fetchedSources.size() - successful.size();
        return "已验证并添加 " + successful.size() + " 个网页来源，同时保存可追溯快照"
                + (failed == 0 ? "" : "；另有 " + failed + " 个候选无法读取，未加入工作区");
    }

    private List<SourceCandidate> sourceCandidates(PlanStep step) {
        var result = new ArrayList<SourceCandidate>();
        var rawSources = step.arguments().get("sources");
        if (rawSources instanceof List<?> values) {
            for (var value : values) {
                var source = map(value);
                var url = Objects.toString(source.get("url"), "").trim();
                if (url.isBlank()) continue;
                result.add(new SourceCandidate(url, Objects.toString(source.get("name"), url)));
            }
        }
        var url = argument(step, "url", "").trim();
        if (!url.isBlank()) result.add(new SourceCandidate(url, argument(step, "name", url)));
        if (result.isEmpty()) throw new IllegalArgumentException("请至少提供一个资料地址");
        if (result.size() > 8) throw new IllegalArgumentException("一次最多验证 8 个资料地址");
        for (var item : result) if (!item.url().startsWith("http://") && !item.url().startsWith("https://"))
            throw new IllegalArgumentException("资源地址必须使用 HTTP 或 HTTPS：" + item.url());
        return result.stream().distinct().toList();
    }

    private record SourceCandidate(String url, String name) { }
    private record SourceFetch(String url, String name, Map<String, Object> fetched, String error) { }

    private String openResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "resource_id", "资源");
        resource(projectId, id);
        effects.put("uiAction", resourceAction(projectId, id, false));
        return "已打开资源";
    }

    private String readResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var item = resource(projectId, required(step, "resource_id", "资源"));
        var detail = new LinkedHashMap<String, Object>();
        detail.put("id", item.id()); detail.put("name", item.name()); detail.put("type", item.resourceType());
        detail.put("status", item.status()); detail.put("version", item.currentVersion()); detail.put("url", Objects.toString(item.url(), ""));
        if ("WEB_URL".equals(item.resourceType())) {
            var fetched = fetchWebResource(item);
            detail.put("title", Objects.toString(fetched.get("title"), item.name()));
            detail.put("content", Objects.toString(fetched.get("text"), ""));
            detail.put("contentType", Objects.toString(fetched.get("content_type"), ""));
            detail.put("finalUrl", Objects.toString(fetched.get("final_url"), item.url()));
            detail.put("contentHash", Objects.toString(fetched.get("content_hash"), ""));
            detail.put("tables", fetched.getOrDefault("tables", List.of()));
            detail.put("refs", List.of(webRef(item, fetched)));
            var verified = verifiedSource(item.id(), Math.max(1, item.currentVersion()), item.url(), fetched, null);
            appendVerifiedSource(effects, verified);
            appendCitation(effects, citationFromVerified(verified));
        } else if (Set.of("KNOWLEDGE_FILE", "DATA_FILE", "OFFICE_FILE").contains(item.resourceType())) {
            detail.put("refs", knowledge.currentRefs(item.id(), 20));
            var file = knowledge.filePath(item.id(), null);
            try {
                if (item.mediaType() != null && (item.mediaType().startsWith("text/")
                        || Set.of("application/json", "application/xml").contains(item.mediaType()))) {
                    var bytes = Files.readAllBytes(file);
                    var length = Math.min(bytes.length, 1_000_000);
                    detail.put("content", new String(bytes, 0, length, StandardCharsets.UTF_8));
                    detail.put("truncated", bytes.length > length);
                } else {
                    detail.put("preview", worker.preview(file, item.name()));
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("资源内容读取失败", exception);
            }
        }
        effects.put("resource", detail);
        return "已读取资源“" + item.name() + "”";
    }

    private String editResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "resource_id", "资源");
        var item = resource(projectId, id);
        if ("DELIVERABLE".equals(item.resourceType())) return editDeliverable(remap(step, Map.of("deliverable_id", id)), effects);
        if ("WORKFLOW".equals(item.resourceType())) return editWorkflow(remap(step, Map.of("workflow_id", id)), effects);
        var patch = required(step, "patch", "编辑内容");
        var current = knowledge.get(id);
        var expected = integer(step, "expected_version", current.currentVersion());
        if (current.currentVersion() != expected) throw new IllegalStateException("资源已有更新版本，请刷新后重试");
        var updated = knowledge.createGeneratedVersion(projectId, id, current.name(), current.mediaType(),
                patch.getBytes(StandardCharsets.UTF_8));
        effects.put("uiAction", resourceAction(projectId, id, true));
        return "已保存资源新版本 v" + updated.currentVersion();
    }

    private String renameResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "resource_id", "资源");
        var item = resource(projectId, id);
        var name = required(step, "new_name", "新名称");
        var table = switch (item.resourceType()) {
            case "DELIVERABLE" -> "deliverable_resource";
            case "DATABASE_CONNECTION", "API_CONNECTION" -> "data_connection";
            case "DATASET" -> "extract_job";
            default -> "file_resource";
        };
        jdbc.sql("update " + table + " set name = :name, updated_at = :now where id = :id")
                .param("name", name).param("now", Instant.now()).param("id", id).update();
        effects.put("uiAction", resourceAction(projectId, id, true));
        return "已将资源重命名为“" + name + "”";
    }

    private String moveResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "resource_id", "资源");
        var item = resource(projectId, id);
        folders.moveResource(projectId, item.resourceType(), id, item.rootKind(), nullable(step, "target_folder_id"));
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已移动资源“" + item.name() + "”";
    }

    private List<Map<String, Object>> deletionInventory(String projectId) {
        var result = new ArrayList<Map<String, Object>>();
        var current = workspace.get(projectId);
        for (var item : current.resources()) result.add(Map.of("resource_id", item.id(), "name", item.name(),
                "type", item.resourceType(), "version", Math.max(1, item.currentVersion()), "revision", Objects.toString(item.updatedAt(), "unknown")));
        for (var item : workflows.list(projectId)) result.add(Map.of("resource_id", item.id(), "name", item.name(),
                "type", "WORKFLOW", "version", item.currentVersion(), "revision", Objects.toString(item.updatedAt(), "unknown")));
        return result;
    }

    private String prepareDeletion(PlanStep step, Map<String, Object> effects) {
        var inventory = deletionInventory(required(step, "project_id", "项目"));
        var ids = list(step.arguments().get("resource_ids")).stream().map(String::valueOf).toList();
        var scope = Objects.toString(step.arguments().get("scope"), "");
        if ("selected".equalsIgnoreCase(scope)) {
            if (ids.isEmpty()) throw new IllegalArgumentException("指定删除范围需要 resource_ids");
            var available = inventory.stream().map(i -> i.get("resource_id")).toList();
            if (!available.containsAll(ids)) throw new IllegalArgumentException("删除清单包含不属于当前项目的资源");
            inventory = inventory.stream().filter(i -> ids.contains(i.get("resource_id"))).toList();
        } else {
            if (!"project_contents".equalsIgnoreCase(scope)) throw new IllegalArgumentException("请明确指定删除范围");
            if (!ids.isEmpty()) throw new IllegalArgumentException("全项目范围不能同时提供 resource_ids");
        }
        if (inventory.size() > 500) throw new IllegalArgumentException("单批最多500项，请使用 selected 范围分批处理");
        effects.put("deletionPreview", Map.of("projectId", required(step, "project_id", "项目"), "items", inventory));
        return "已整理 " + inventory.size() + " 项删除清单，尚未删除；请一次调用 resource.delete_batch";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> validateDeletion(Map<String, Object> arguments) {
        AssistantToolContracts.validate("resource.delete_batch", arguments);
        var items = (List<Map<String, Object>>) arguments.get("items");
        if (items.isEmpty() || items.size() > 500) throw new IllegalArgumentException("删除清单必须包含1至500项");
        var ids = items.stream().map(i -> i.get("resource_id")).toList();
        if (ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("删除清单包含重复对象");
        var inventory = deletionInventory(arguments.get("project_id").toString());
        for (var item : items) {
            var actual = inventory.stream().filter(i -> i.get("resource_id").equals(item.get("resource_id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("删除对象不存在或不属于当前项目：" + item.get("name")));
            if (!actual.equals(item)) throw new IllegalStateException("删除对象已变化，请重新生成清单并确认：" + actual.get("name"));
        }
        return items;
    }

    public String deletionDescription(Map<String, Object> arguments) {
        var items = validateDeletion(arguments);
        return "将永久删除以下 " + items.size() + " 项（工作流含其历史运行记录），保留项目与目录：\n" + items.stream()
                .map(i -> i.get("name") + " · " + i.get("type") + " · 第" + i.get("version") + "版")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String deleteBatch(PlanStep step, Map<String, Object> effects) {
        var deleted = deletionTransaction.execute(status -> {
            var items = validateDeletion(step.arguments());
            var projectId = required(step, "project_id", "项目");
            // URL nodes must be removed before their owning workflows disappear.
            var ordered = items.stream().sorted(java.util.Comparator.comparing(i -> "WORKFLOW".equals(i.get("type")))).toList();
            var localEffects = new LinkedHashMap<String, Object>();
            for (var item : ordered) deleteResource(remap(step, Map.of("resource_id", item.get("resource_id"))), localEffects);
            var remaining = deletionInventory(projectId).stream().map(i -> i.get("resource_id")).toList();
            if (items.stream().anyMatch(i -> remaining.contains(i.get("resource_id"))))
                throw new IllegalStateException("批量删除校验失败，已回滚");
            return items;
        });
        effects.put("deletionResult", Map.of("deleted", deleted, "count", deleted.size(), "verified", true));
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", required(step, "project_id", "项目")));
        return "已批量删除并验证 " + deleted.size() + " 项；未删除清单之外的任何内容";
    }

    private String deleteResource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "resource_id", "资源");
        // workspace.inspect also exposes workflows; keep deletion consistent with that catalog.
        if (workflows.list(projectId).stream().anyMatch(workflow -> workflow.id().equals(id))) {
            return deleteWorkflow(remap(step, Map.of("workflow_id", id)), effects);
        }
        var item = resource(projectId, id);
        switch (item.resourceType()) {
            case "DELIVERABLE" -> deliverables.delete(id);
            case "DATASET" -> extracts.delete(id);
            case "DATABASE_CONNECTION", "API_CONNECTION" -> connections.delete(id);
            case "WEB_URL" -> removeLinkNode(projectId, id);
            default -> knowledge.delete(id);
        }
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE", "projectId", projectId));
        return "已删除资源“" + item.name() + "”";
    }

    private String readKnowledge(PlanStep step, Map<String, Object> effects) {
        var resourceId = argument(step, "resource_id", "");
        List<?> refs;
        if (!resourceId.isBlank()) refs = knowledge.currentRefs(resourceId, 30);
        else {
            var citationId = required(step, "citation_id", "引用");
            refs = jdbc.sql("select id, project_id, resource_id, version_number, source_name, text_content, location_json, content_hash from knowledge_ref where id = :id")
                    .param("id", citationId).query((rs, row) -> Map.of("id", rs.getString("id"),
                            "projectId", rs.getString("project_id"), "resourceId", rs.getString("resource_id"),
                            "version", rs.getInt("version_number"), "sourceName", rs.getString("source_name"),
                            "text", rs.getString("text_content"), "location", rs.getString("location_json"),
                            "contentHash", rs.getString("content_hash"))).list();
        }
        effects.put("knowledgeRefs", refs);
        return "已读取 " + refs.size() + " 条知识片段";
    }

    private String parseKnowledge(PlanStep step, Map<String, Object> effects) {
        var resource = knowledge.reparse(required(step, "resource_id", "资料"));
        effects.put("resourceId", resource.id());
        return "已提交“" + resource.name() + "”重新解析";
    }

    private String extractTable(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "resource_id", "资料");
        var projectId = required(step, "project_id", "项目");
        var item = resource(projectId, id);
        List<?> tables;
        String sourceName;
        Map<String, Object> provenance;
        if ("WEB_URL".equals(item.resourceType())) {
            var fetched = fetchWebResource(item);
            tables = list(fetched.get("tables"));
            sourceName = Objects.toString(fetched.get("title"), item.name());
            provenance = Map.of("sourceResourceId", item.id(), "sourceUrl", item.url(),
                    "contentHash", Objects.toString(fetched.get("content_hash"), ""),
                    "fetchedFrom", Objects.toString(fetched.get("final_url"), item.url()));
            var snapshotText = "来源标题：" + sourceName + "\n原始地址：" + item.url()
                    + "\n最终地址：" + provenance.get("fetchedFrom")
                    + "\n内容哈希：" + provenance.get("contentHash") + "\n\n```json\n"
                    + Objects.toString(fetched.get("text"), "") + "\n```\n";
            var snapshot = knowledge.importBytes(projectId, sourceName + "-网页快照.md", "text/markdown",
                    snapshotText.getBytes(StandardCharsets.UTF_8));
            effects.put("sourceResourceId", snapshot.id());
            effects.put("sourceUrl", item.url());
            provenance = new LinkedHashMap<>(provenance);
            provenance.put("snapshotResourceId", snapshot.id());
        } else {
            var resource = knowledge.get(id);
            sourceName = resource.name();
            tables = tablesFromPreview(worker.preview(knowledge.filePath(id, null), resource.name()));
            provenance = Map.of("sourceResourceId", resource.id(), "sourceVersion", resource.currentVersion(),
                    "sourceName", resource.name(), "contentHash", Objects.toString(resource.checksum(), ""));
        }
        if (tables.isEmpty()) throw new IllegalStateException("资料正文已读取，但其中没有识别到结构化表格");
        var name = argument(step, "target_dataset_name", sourceName + "-表格.json");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name += ".json";
        var created = knowledge.importBytes(projectId, name, "application/json",
                writeBytes(Map.of("provenance", provenance, "tables", tables)));
        effects.put("provenance", provenance);
        effects.put("datasetId", created.id());
        effects.put("uiAction", resourceAction(projectId, created.id(), true));
        return "已从“" + sourceName + "”抽取 " + tables.size() + " 个表格并创建数据文件";
    }

    private String addDataSource(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var connection = map(step.arguments().get("connection"));
        var type = sourceType(argument(step, "source_type", Objects.toString(connection.get("source_type"), "POSTGRESQL")));
        var url = firstNonBlank(connection.get("jdbc_url"), connection.get("url"), step.arguments().get("url"));
        var created = connections.create(new CreateConnectionRequest(projectId, required(step, "name", "数据源名称"),
                type, url, Objects.toString(connection.get("username"), ""),
                Objects.toString(connection.get("secret_ref"), ""), stringMap(connection.get("options"))));
        effects.put("sourceId", created.id());
        effects.put("uiAction", resourceAction(projectId, created.id(), true));
        return "已添加数据源“" + created.name() + "”";
    }

    private String connectDataSource(PlanStep step, Map<String, Object> effects) {
        var result = connections.test(required(step, "source_id", "数据源"));
        effects.put("connectionTest", Map.of("success", result.success(), "message", result.message(),
                "latencyMs", result.latencyMs()));
        if (!result.success()) throw new IllegalStateException("数据源连接失败：" + result.message());
        return "数据源连接成功，耗时 " + result.latencyMs() + "ms";
    }

    private String importDataset(PlanStep step, Map<String, Object> effects) {
        var sourceId = argument(step, "source_id", "");
        if (sourceId.isBlank()) sourceId = argument(step, "connection_id", "");
        if (sourceId.isBlank()) sourceId = argument(step, "resource_id", "");
        var projectId = required(step, "project_id", "项目");
        var item = resource(projectId, sourceId);
        if ("WEB_URL".equals(item.resourceType())) {
            var fetched = fetchedResource(item, effects);
            var materialized = StructuredDataMaterializer.materialize(fetched, step.arguments().get("schema"), objectMapper);
            var name = argument(step, "target_name", item.name() + "-数据集");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name += ".json";
            var created = knowledge.importBytes(projectId, name, "application/json", writeBytes(materialized.rows()));
            var provenance = new LinkedHashMap<String, Object>();
            provenance.put("sourceResourceId", item.id());
            provenance.put("sourceUrl", item.url());
            provenance.put("finalUrl", Objects.toString(fetched.get("final_url"), item.url()));
            provenance.put("contentHash", Objects.toString(fetched.get("content_hash"), ""));
            provenance.put("sourcePath", materialized.sourcePath());
            provenance.put("rowCount", materialized.rows().size());
            effects.put("datasetId", created.id());
            effects.put("datasetProvenance", provenance);
            effects.put("provenance", provenance);
            effects.put("uiAction", resourceAction(projectId, created.id(), true));
            return "已从网页资源抽取 " + materialized.rows().size() + " 行数据并创建数据集“" + created.name() + "”";
        }
        if (!Set.of("DATABASE_CONNECTION", "API_CONNECTION").contains(item.resourceType())) {
            effects.put("datasetId", sourceId);
            return "现有数据文件已可作为数据集使用";
        }
        var name = argument(step, "target_name", item.name() + "采集结果");
        var query = argument(step, "query", "API_CONNECTION".equals(item.resourceType()) ? "GET /" : "select 1");
        var job = extracts.create(new CreateExtractRequest(projectId, sourceId, name, query, 5000, name + ".csv"));
        effects.put("datasetId", job.id());
        effects.put("extractStatus", job.status());
        return "已创建数据采集任务“" + name + "”";
    }

    private String queryDataset(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "dataset_id", "数据集");
        var item = resource(projectId, id);
        Object result;
        if (Set.of("DATABASE_CONNECTION", "API_CONNECTION").contains(item.resourceType())) {
            result = connections.preview(id, new PreviewConnectionRequest(argument(step, "sql", argument(step, "analysis_request", "select 1")), 100));
        } else if ("DATASET".equals(item.resourceType())) {
            var extract = extracts.get(id);
            result = worker.profileData(extracts.outputPath(id), extract.outputName(), null);
        } else {
            var file = knowledge.get(id);
            result = worker.profileData(knowledge.filePath(id, null), file.name(), null);
        }
        effects.put("datasetQuery", result);
        return "已读取数据集查询结果";
    }

    private String createDataset(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var name = argument(step, "name", "Agent 数据集");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name += ".json";
        var rows = step.arguments().getOrDefault("rows", List.of());
        var payload = Map.of("schema", step.arguments().getOrDefault("schema", Map.of()), "rows", rows);
        var resource = knowledge.importBytes(projectId, name, "application/json", writeBytes(payload));
        effects.put("datasetId", resource.id());
        effects.put("uiAction", resourceAction(projectId, resource.id(), true));
        return "已创建数据集“" + resource.name() + "”";
    }

    private String transformDataset(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = required(step, "dataset_id", "数据集");
        var item = resource(projectId, id);
        var input = new DataTransformService.SourceRequest("DATASET".equals(item.resourceType()) ? "EXTRACT" : "FILE",
                id, "source", item.name(), null, null);
        var requirements = required(step, "requirements", "加工要求");
        var script = argument(step, "script", "");
        if (!looksLikeSqlQuery(script)) {
            var generated = transforms.generate(projectId, new DataTransformService.GenerateRequest(requirements, List.of(input)));
            script = Objects.toString(generated.get("script"), "");
        }
        if (script.isBlank()) throw new IllegalStateException("数据加工没有生成可执行脚本");
        var config = Map.<String, Object>of("script", script, "requirements", requirements,
                "inputAliases", Map.of("input", "source"),
                "outputName", argument(step, "target_name", "transformed_data.csv"));
        var upstream = "DATASET".equals(item.resourceType()) ? Map.of("input", Map.<String, Object>of("extractJobId", id))
                : Map.of("input", Map.<String, Object>of("fileId", id));
        var result = transforms.execute(projectId, config, upstream);
        effects.put("datasetId", result.resource().id());
        effects.put("qualityReport", result.qualityReport());
        return "已生成加工数据集“" + result.resource().name() + "”";
    }

    private boolean looksLikeSqlQuery(String value) {
        return value != null && value.strip().toLowerCase(Locale.ROOT).matches("(?s)^(select|with)\\b.*");
    }

    private String openDataset(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "dataset_id", "数据集");
        effects.put("uiAction", Map.of("type", "OPEN_DATA", "projectId", required(step, "project_id", "项目"), "resourceId", id));
        return "已打开数据集";
    }

    private String deleteDataset(PlanStep step, Map<String, Object> effects) {
        return deleteResource(remap(step, Map.of("resource_id", required(step, "dataset_id", "数据集"))), effects);
    }

    private String profileDataset(PlanStep step, Map<String, Object> effects) {
        return queryDataset(remap(step, Map.of("dataset_id", required(step, "resource_id", "数据集"), "analysis_request", "profile")), effects);
    }

    private String openWorkflow(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var id = resolveWorkflowId(step, projectId, effects);
        effects.put("workflowId", id);
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId, "workflowId", id));
        var workflow = workflows.get(id);
        effects.put("workflow", workflow);
        var schema = new LinkedHashMap<String, Object>();
        for (var node : workflow.nodes()) schema.put(node.id(), Map.of("name", node.name(), "outputs",
                com.finflow.studio.workflow.WorkflowVariables.outputs(node.type())));
        effects.put("nodeOutputs", schema);
        return "已打开工作流“" + workflow.name() + "”，共 " + workflow.nodes().size()
                + " 个节点、" + workflow.edges().size() + " 条连线";
    }

    private String editWorkflow(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "workflow_id", "工作流");
        var current = workflows.get(id);
        var expected = integer(step, "expected_version", current.currentVersion());
        if (expected != current.currentVersion()) throw new IllegalStateException("工作流版本已变化，请读取最新版本再修改");
        var request = WorkflowAgentPatch.apply(current, step.arguments().get("patch"), expected);
        request = normalizeAgentWorkflow(current.projectId(), request);
        boolean changed = !request.name().equals(current.name()) || !request.description().equals(current.description())
                || !request.nodes().equals(current.nodes()) || !request.edges().equals(current.edges());
        var updated = changed ? workflows.update(id, request) : current;
        effects.put("workflowId", id);
        effects.put("workflow", updated);
        effects.put("changed", changed);
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", current.projectId(), "workflowId", id, "refreshWorkspace", true));
        return changed ? "已保存节点配置和连线，工作流版本 v" + updated.currentVersion() : "配置与当前版本一致，没有产生修改，请基于已返回的工作流继续或结束任务";
    }

    private String duplicateWorkflow(PlanStep step, Map<String, Object> effects) {
        var projectId = required(step, "project_id", "项目");
        var source = workflows.get(required(step, "workflow_id", "工作流"));
        if (!projectId.equals(source.projectId())) throw new IllegalArgumentException("工作流不属于当前项目");
        var copy = workflows.create(projectId, new SaveRequest(required(step, "name", "新工作流名称"),
                source.description(), source.nodes(), source.edges(), ExecutionMode.MANUAL, null, null));
        effects.put("workflowId", copy.id());
        effects.put("workflow", copy);
        effects.put("changed", true);
        effects.put("uiAction", Map.of("type", "OPEN_WORKFLOW", "projectId", projectId,
                "workflowId", copy.id(), "refreshWorkspace", true));
        return "已复制工作流为“" + copy.name() + "”，保留节点、连线和分析口径，未携带历史运行结果";
    }

    private SaveRequest normalizeAgentWorkflow(String projectId, SaveRequest request) {
        var normalizedNodes = request.nodes().stream().map(node -> normalizeAgentInput(projectId, node)).toList();
        var byId = new LinkedHashMap<String, NodeDefinition>();
        normalizedNodes.forEach(node -> byId.put(node.id(), node));
        var result = new ArrayList<NodeDefinition>();
        for (var node : normalizedNodes) {
            if (node.type() != NodeType.DATA_TRANSFORM || node.config() == null
                    || node.config().get("transformPlan") instanceof Map<?, ?> plan && !plan.isEmpty()) {
                result.add(node);
                continue;
            }
            var upstreamIds = request.edges().stream().filter(edge -> edge.target().equals(node.id()))
                    .map(EdgeDefinition::source).toList();
            var upstream = upstreamIds.stream().map(byId::get).filter(Objects::nonNull)
                    .filter(item -> Set.of(NodeType.FILE_INPUT, NodeType.DATASET_INPUT, NodeType.DATA_EXTRACT).contains(item.type()))
                    .toList();
            if (upstream.isEmpty()) {
                result.add(node);
                continue;
            }
            result.add(withGeneratedTransformPlan(projectId, node, upstream));
        }
        return new SaveRequest(request.name(), request.description(), result, request.edges(),
                request.executionMode(), request.schedule(), request.expectedVersion());
    }

    private NodeDefinition normalizeAgentInput(String projectId, NodeDefinition node) {
        if (node.type() == NodeType.DELIVERABLE && node.config() != null) {
            var config = new LinkedHashMap<>(node.config());
            config.put("fastPlanning", true);
            return new NodeDefinition(node.id(), node.type(), node.name(), node.x(), node.y(), Map.copyOf(config));
        }
        if (node.type() != NodeType.DATASET_INPUT || node.config() == null) return node;
        var resourceId = Objects.toString(node.config().get("extractJobId"),
                Objects.toString(node.config().get("resourceId"), ""));
        if (resourceId.isBlank()) return node;
        try {
            var item = resource(projectId, resourceId);
            if ("DATASET".equals(item.resourceType())) return node;
            if (Set.of("DATA_FILE", "OFFICE_FILE", "KNOWLEDGE_FILE").contains(item.resourceType())) {
                return new NodeDefinition(node.id(), NodeType.FILE_INPUT, node.name(), node.x(), node.y(),
                        Map.of("resourceId", resourceId));
            }
        } catch (IllegalArgumentException ignored) { }
        return node;
    }

    private NodeDefinition withGeneratedTransformPlan(String projectId, NodeDefinition transform,
                                                      List<NodeDefinition> upstream) {
        var sources = new ArrayList<DataTransformService.SourceRequest>();
        var aliases = new LinkedHashMap<String, String>();
        for (var index = 0; index < upstream.size(); index++) {
            var input = upstream.get(index);
            var config = input.config() == null ? Map.<String, Object>of() : input.config();
            var alias = "data_" + (index + 1);
            aliases.put(input.id(), alias);
            switch (input.type()) {
                case FILE_INPUT -> sources.add(new DataTransformService.SourceRequest("FILE",
                        Objects.toString(config.get("resourceId"), ""), alias, input.name(), null,
                        Objects.toString(config.get("sheetName"), "")));
                case DATASET_INPUT -> sources.add(new DataTransformService.SourceRequest("EXTRACT",
                        Objects.toString(config.get("extractJobId"), ""), alias, input.name(), null, null));
                case DATA_EXTRACT -> sources.add(new DataTransformService.SourceRequest("CONNECTION",
                        Objects.toString(config.get("connectionId"), ""), alias, input.name(),
                        Objects.toString(config.get("sql"), ""), null));
                default -> { }
            }
        }
        var config = new LinkedHashMap<>(transform.config());
        var requirements = Objects.toString(config.get("requirements"), "整理并关联输入数据");
        var generated = transforms.generate(projectId, new DataTransformService.GenerateRequest(requirements, sources));
        config.put("transformPlan", generated.get("plan"));
        config.put("script", Objects.toString(generated.get("script"), ""));
        config.put("scriptSummary", Objects.toString(generated.get("summary"), "已生成可复核加工方案"));
        config.put("scriptMode", "GENERATED");
        config.put("inputAliases", aliases);
        config.putIfAbsent("outputFormat", "parquet");
        config.putIfAbsent("outputName", "数据加工结果.parquet");
        config.put("transparent", true);
        if (generated.get("assumptions") != null) config.put("assumptions", generated.get("assumptions"));
        if (generated.get("quality_rules") != null) config.put("qualityRules", generated.get("quality_rules"));
        if (generated.get("warnings") != null) config.put("warnings", generated.get("warnings"));
        return new NodeDefinition(transform.id(), transform.type(), transform.name(), transform.x(), transform.y(), Map.copyOf(config));
    }

    private String addWorkflowNode(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "workflow_id", "工作流");
        var current = workflows.get(id);
        var nodes = new ArrayList<>(current.nodes());
        var position = map(step.arguments().get("position"));
        var node = new NodeDefinition("node_" + shortId(), nodeType(required(step, "node_type", "节点类型")),
                argument(step, "name", "新步骤"), number(position.get("x"), 200), number(position.get("y"), 160),
                map(step.arguments().get("config")));
        WorkflowAgentPatch.node(null, Map.of("id", node.id(), "type", node.type().name(), "name", node.name(), "config", node.config()));
        nodes.add(node);
        var updated = workflows.update(id, save(current, nodes, current.edges()));
        effects.put("workflowId", id); effects.put("workflowNodeId", node.id());
        effects.put("workflow", updated);
        return "已添加工作流步骤“" + node.name() + "”，版本 v" + updated.currentVersion();
    }

    private String removeWorkflowNode(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "workflow_id", "工作流");
        var nodeId = required(step, "node_id", "节点");
        var current = workflows.get(id);
        var nodes = current.nodes().stream().filter(node -> !node.id().equals(nodeId)).toList();
        if (nodes.size() == current.nodes().size()) throw new IllegalArgumentException("工作流节点不存在");
        var edges = current.edges().stream().filter(edge -> !edge.source().equals(nodeId) && !edge.target().equals(nodeId)).toList();
        var updated = workflows.update(id, new SaveRequest(current.name(), current.description(), nodes, edges,
                current.executionMode(), current.schedule(), integer(step, "expected_version", current.currentVersion())));
        effects.put("workflowId", id);
        effects.put("workflow", updated);
        return "已移除工作流步骤";
    }

    private String connectWorkflow(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "workflow_id", "工作流");
        var current = workflows.get(id);
        var edge = new EdgeDefinition("edge_" + shortId(), required(step, "source_node_id", "起点"),
                required(step, "target_node_id", "终点"));
        if (current.nodes().stream().noneMatch(node -> node.id().equals(edge.source())) || current.nodes().stream().noneMatch(node -> node.id().equals(edge.target())))
            throw new IllegalArgumentException("连线的起点或终点不存在");
        if (current.edges().stream().anyMatch(item -> item.source().equals(edge.source()) && item.target().equals(edge.target()))) {
            effects.put("changed", false); effects.put("workflow", current);
            return "连线已经存在，不再重复添加";
        }
        var edges = new ArrayList<>(current.edges()); edges.add(edge);
        var updated = workflows.update(id, save(current, current.nodes(), edges));
        effects.put("workflowId", id);
        effects.put("workflow", updated);
        return "已连接工作流步骤";
    }

    private String saveWorkflowVersion(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "workflow_id", "工作流");
        var current = workflows.get(id);
        var description = current.description();
        var message = argument(step, "message", "");
        if (!message.isBlank()) description = description + "\n\n版本说明：" + message;
        var updated = workflows.update(id, new SaveRequest(current.name(), description, current.nodes(), current.edges(),
                current.executionMode(), current.schedule(), current.currentVersion()));
        effects.put("workflowId", id);
        return "已保存工作流版本 v" + updated.currentVersion();
    }

    private String openDeliverable(PlanStep step, Map<String, Object> effects) {
        var item = deliverables.get(resolveDeliverableId(step, effects));
        effects.put("uiAction", Map.of("type", "OPEN_DELIVERABLE", "projectId", item.projectId(), "resourceId", item.id()));
        return "已打开交付件“" + item.name() + "”";
    }

    private String resolveDeliverableId(PlanStep step, Map<String, Object> effects) {
        var explicitId = argument(step, "deliverable_id", "");
        if (!explicitId.isBlank() && !explicitId.contains("${")) return explicitId;

        var requestedFormat = argument(step, "format", "").toUpperCase(Locale.ROOT);
        if (effects.get("deliverables") instanceof List<?> values) {
            var candidates = values.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .filter(item -> requestedFormat.isBlank()
                            || requestedFormat.equals(Objects.toString(item.get("format"), "").toUpperCase(Locale.ROOT)))
                    .map(item -> Objects.toString(item.get("deliverableId"),
                            Objects.toString(item.get("id"), "")))
                    .filter(id -> !id.isBlank())
                    .toList();
            if (!candidates.isEmpty()) return candidates.getFirst();
        }
        throw new IllegalArgumentException("未找到可打开的交付件");
    }

    private String editDeliverable(PlanStep step, Map<String, Object> effects) {
        var id = required(step, "deliverable_id", "交付件");
        var current = deliverables.get(id);
        var expected = integer(step, "expected_version", current.currentVersion());
        if (expected != current.currentVersion()) throw new IllegalStateException("交付件已有更新版本，请刷新后重试");
        var content = argument(step, "patch", "");
        var updated = content.isBlank() ? deliverables.rerender(id) : deliverables.create(new CreateRequest(
                current.projectId(), id, current.name(), "由 Agent 编辑", current.format(), null,
                false, "IEEE", List.of(new SectionRequest("更新内容", List.of(content), List.of(), List.of(), List.of()))));
        effects.put("uiAction", Map.of("type", "OPEN_DELIVERABLE", "projectId", updated.projectId(),
                "resourceId", updated.id(), "refreshWorkspace", true));
        return "已保存交付件版本 v" + updated.currentVersion();
    }

    private String exportDeliverable(PlanStep step, Map<String, Object> effects) {
        var item = deliverables.get(required(step, "deliverable_id", "交付件"));
        var requested = argument(step, "format", item.format());
        if (!requested.equalsIgnoreCase(item.format()))
            throw new IllegalArgumentException("导出只下载现有格式 " + item.format() + "，不能转换为 " + requested + "；请用document.render生成目标格式");
        deliverables.path(item.id(), null);
        var export = Map.of("deliverableId", item.id(), "format", item.format(),
                "downloadUrl", "/api/deliverables/" + item.id() + "/download");
        effects.put("export", export);
        return "已准备“" + item.name() + "”的下载文件";
    }

    private String deleteDeliverable(PlanStep step, Map<String, Object> effects) {
        deliverables.delete(required(step, "deliverable_id", "交付件"));
        effects.put("uiAction", Map.of("type", "REFRESH_WORKSPACE"));
        return "已删除交付件";
    }

    private void removeLinkNode(String projectId, String nodeId) {
        var workflow = workflows.list(projectId).stream().filter(item -> item.nodes().stream().anyMatch(node -> node.id().equals(nodeId)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("网页资源不存在"));
        var nodes = workflow.nodes().stream().filter(node -> !node.id().equals(nodeId)).toList();
        var edges = workflow.edges().stream().filter(edge -> !edge.source().equals(nodeId) && !edge.target().equals(nodeId)).toList();
        workflows.update(workflow.id(), save(workflow, nodes, edges));
    }

    private String resolveWorkflowId(PlanStep step, String projectId, Map<String, Object> effects) {
        var id = argument(step, "workflow_id", Objects.toString(effects.get("workflowId"), ""));
        if (!id.isBlank()) return workflows.get(id).id();
        var name = argument(step, "workflow_name", "");
        return workflows.list(projectId).stream().filter(item -> name.isBlank() || item.name().contains(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name.isBlank()
                        ? "项目还没有工作流"
                        : "没有找到名称包含“" + name + "”的工作流"))
                .id();
    }

    private Resource resource(String projectId, String id) {
        return workspace.get(projectId).resources().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工作区资源不存在：" + id));
    }

    private Map<String, Object> fetchWebResource(Resource item) {
        return fetchWebUrl(item.url(), item.name());
    }

    private Map<String, Object> fetchWebUrl(String url, String name) {
        try {
            var fetched = worker.fetchResearchSource(url);
            if (Objects.toString(fetched.get("text"), "").isBlank()) {
                throw new IllegalStateException("网页没有返回可读取正文");
            }
            return fetched;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("网页来源“" + name + "”验证失败，未加入工作区：" + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> verifiedSource(String resourceId, int version, String url,
                                               Map<String, Object> fetched, String snapshotResourceId) {
        var value = new LinkedHashMap<String, Object>();
        value.put("resourceId", resourceId);
        value.put("version", Math.max(1, version));
        value.put("sourceName", Objects.toString(fetched.get("title"), url));
        value.put("url", url);
        value.put("finalUrl", Objects.toString(fetched.get("final_url"), url));
        value.put("contentType", Objects.toString(fetched.get("content_type"), ""));
        value.put("contentHash", Objects.toString(fetched.get("content_hash"), ""));
        value.put("verifiedAt", Instant.now().toString());
        value.put("excerpt", Objects.toString(fetched.get("text"), "").substring(0,
                Math.min(1200, Objects.toString(fetched.get("text"), "").length())));
        if (snapshotResourceId != null && !snapshotResourceId.isBlank()) value.put("snapshotResourceId", snapshotResourceId);
        return Map.copyOf(value);
    }

    @SuppressWarnings("unchecked")
    private void appendVerifiedSource(Map<String, Object> effects, Map<String, Object> source) {
        var values = effects.get("verifiedSources") instanceof List<?> existing
                ? new ArrayList<>(existing.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList())
                : new ArrayList<Map<String, Object>>();
        values.removeIf(item -> Objects.equals(item.get("resourceId"), source.get("resourceId"))
                || Objects.equals(item.get("url"), source.get("url")));
        values.add(source);
        effects.put("verifiedSources", List.copyOf(values));
    }

    @SuppressWarnings("unchecked")
    private void appendCitation(Map<String, Object> effects, Map<String, Object> citation) {
        var values = effects.get("knowledgeCitations") instanceof List<?> existing
                ? new ArrayList<>(existing.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList())
                : new ArrayList<Map<String, Object>>();
        values.removeIf(item -> Objects.equals(item.get("citationId"), citation.get("citationId")));
        values.add(citation);
        effects.put("knowledgeCitations", List.copyOf(values));
    }

    private Map<String, Object> citationFromVerified(Map<String, Object> source) {
        return Map.of(
                "citationId", "web:" + UUID.nameUUIDFromBytes(Objects.toString(source.get("url"), "").getBytes(StandardCharsets.UTF_8)),
                "resourceId", Objects.toString(source.get("resourceId"), ""),
                "version", source.get("version"),
                "sourceName", Objects.toString(source.get("sourceName"), "公开网页"),
                "excerpt", Objects.toString(source.get("excerpt"), ""),
                "location", Map.of("url", Objects.toString(source.get("url"), ""),
                        "finalUrl", Objects.toString(source.get("finalUrl"), ""),
                        "verifiedAt", Objects.toString(source.get("verifiedAt"), "")),
                "contentHash", Objects.toString(source.get("contentHash"), ""),
                "score", 1.0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchedResource(Resource item, Map<String, Object> effects) {
        if (effects.get("resource") instanceof Map<?, ?> existing
                && item.id().equals(Objects.toString(existing.get("id"), ""))
                && !Objects.toString(existing.get("content"), "").isBlank()) {
            var fetched = new LinkedHashMap<String, Object>();
            fetched.put("title", existing.get("title"));
            fetched.put("text", existing.get("content"));
            fetched.put("content_type", existing.get("contentType"));
            fetched.put("final_url", existing.get("finalUrl"));
            fetched.put("content_hash", existing.get("contentHash"));
            fetched.put("tables", existing.containsKey("tables") ? existing.get("tables") : List.of());
            return fetched;
        }
        return fetchWebResource(item);
    }

    private Map<String, Object> webRef(Resource item, Map<String, Object> fetched) {
        return Map.of("id", "web:" + UUID.nameUUIDFromBytes(item.url().getBytes(StandardCharsets.UTF_8)),
                "resourceId", item.id(), "version", Math.max(1, item.currentVersion()),
                "sourceName", Objects.toString(fetched.get("title"), item.name()),
                "text", Objects.toString(fetched.get("text"), ""),
                "location", Map.of("url", item.url(), "finalUrl", Objects.toString(fetched.get("final_url"), item.url())),
                "contentHash", Objects.toString(fetched.get("content_hash"), ""));
    }

    private List<?> tablesFromPreview(Map<String, Object> preview) {
        var result = new ArrayList<>();
        for (var pageValue : list(preview.get("pages"))) {
            if (!(pageValue instanceof Map<?, ?> page)) continue;
            for (var blockValue : list(page.get("blocks"))) {
                if (!(blockValue instanceof Map<?, ?> block) || !"table".equals(Objects.toString(block.get("type"), ""))) continue;
                var rows = list(block.get("rows"));
                if (!rows.isEmpty()) result.add(Map.of("page", page.containsKey("number") ? page.get("number") : 1, "rows", rows));
            }
        }
        return List.copyOf(result);
    }

    private SaveRequest save(com.finflow.studio.workflow.WorkflowModels.WorkflowResponse current,
                             List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new SaveRequest(current.name(), current.description(), nodes, edges, current.executionMode(),
                current.schedule(), current.currentVersion());
    }

    private PlanStep remap(PlanStep step, Map<String, Object> additions) {
        var arguments = new LinkedHashMap<>(step.arguments()); arguments.putAll(additions);
        return new PlanStep(step.id(), step.order(), step.tool(), step.mode(), step.title(), step.description(),
                arguments, step.risk(), step.requiresConfirmation(), step.status());
    }

    private void moveIfRequested(String projectId, String resourceId, String type, String root, String folderId) {
        if (folderId != null && !folderId.isBlank()) folders.moveResource(projectId, type, resourceId, root, folderId);
    }

    private Map<String, Object> resourceAction(String projectId, String resourceId, boolean refresh) {
        return Map.of("type", "OPEN_RESOURCE", "projectId", projectId, "resourceId", resourceId, "refreshWorkspace", refresh);
    }

    private String required(PlanStep step, String key, String label) {
        var value = argument(step, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private String argument(PlanStep step, String key, String fallback) {
        var value = Objects.toString(step.arguments().get(key), "").trim();
        return value.isBlank() ? fallback : value;
    }

    private String nullable(PlanStep step, String key) {
        var value = argument(step, key, "");
        return value.isBlank() ? null : value;
    }

    private int integer(PlanStep step, String key, int fallback) {
        return step.arguments().get(key) instanceof Number number ? number.intValue() : fallback;
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(Objects.toString(key), item));
        return result;
    }

    private Map<String, String> stringMap(Object value) {
        var result = new LinkedHashMap<String, String>();
        map(value).forEach((key, item) -> result.put(key, Objects.toString(item, "")));
        return result;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !Objects.toString(value, "").isBlank()) target.put(key, value);
    }

    private String firstNonBlank(Object... values) {
        for (var value : values) {
            var text = Objects.toString(value, "").trim();
            if (!text.isBlank()) return text;
        }
        throw new IllegalArgumentException("数据源连接地址不能为空");
    }

    private SourceType sourceType(String value) {
        try { return SourceType.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("不支持的数据源类型：" + value); }
    }

    private NodeType nodeType(String value) {
        try { return NodeType.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("不支持的工作流节点类型：" + value); }
    }

    private String rootKind(String value) {
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DATA", "FILE", "FILES", "KNOWLEDGE" -> "FILES";
            case "DATABASE", "DATABASES" -> "DATABASES";
            case "WEB", "WEB_URL", "WEB_URLS" -> "WEB_URLS";
            case "API", "APIS" -> "APIS";
            case "OUTPUT", "OUTPUTS", "DELIVERABLE" -> "OUTPUTS";
            default -> throw new IllegalArgumentException("目录分类无效：" + value);
        };
    }

    private String mediaType(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".parquet")) return "application/vnd.apache.parquet";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    private String fileResourceType(String name) {
        return WorkspaceFileClassifier.resourceType(name, mediaType(name));
    }

    private byte[] writeBytes(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        catch (tools.jackson.core.JacksonException exception) { throw new IllegalArgumentException("结构化内容无法保存", exception); }
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
