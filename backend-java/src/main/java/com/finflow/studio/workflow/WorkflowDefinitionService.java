package com.finflow.studio.workflow;

import com.finflow.studio.data.DataConnectionService;
import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Service
public class WorkflowDefinitionService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;
    private final DataConnectionService connections;
    private final ExtractJobService extracts;
    private final KnowledgeService knowledge;

    public WorkflowDefinitionService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                                     DataConnectionService connections, ExtractJobService extracts,
                                     KnowledgeService knowledge) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.connections = connections;
        this.extracts = extracts;
        this.knowledge = knowledge;
    }

    @Transactional
    public WorkflowResponse create(String projectId, SaveRequest request) {
        projects.get(projectId);
        return insert(projectId, request);
    }

    @Transactional
    public WorkflowResponse getProjectWorkflow(String projectId) {
        projects.get(projectId);
        return findProjectWorkflow(projectId).orElseGet(() -> insert(projectId,
                new SaveRequest("主工作流", "项目中的资源通过这条工作流完成自动化处理", List.of(), List.of())));
    }

    @Transactional
    public WorkflowResponse saveProjectWorkflow(String projectId, SaveRequest request) {
        projects.get(projectId);
        var existing = findProjectWorkflow(projectId);
        return existing.isPresent() ? update(existing.get().id(), request) : insert(projectId, request);
    }

    private WorkflowResponse insert(String projectId, SaveRequest request) {
        var document = normalize(request);
        var status = !document.nodes().isEmpty() && validate(projectId, document).valid() ? "READY" : "DRAFT";
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        var nextRunAt = WorkflowScheduleSupport.nextRun(document.executionMode(), document.schedule(), now);
        jdbc.sql("""
                insert into workflow_definition(id, project_id, name, description, status, current_version,
                    next_run_at, created_at, updated_at)
                values (:id, :projectId, :name, :description, :status, 1, :nextRunAt, :now, :now)
                """).param("id", id).param("projectId", projectId).param("name", document.name())
                .param("status", status)
                .param("description", document.description()).param("nextRunAt", nextRunAt).param("now", now).update();
        insertVersion(id, 1, document, now);
        return get(id);
    }

    @Transactional
    public WorkflowResponse update(String id, SaveRequest request) {
        var current = get(id);
        if (request.expectedVersion() != null && request.expectedVersion() != current.currentVersion()) {
            throw new IllegalStateException("工作流已被更新，请刷新后再保存");
        }
        var document = normalize(request);
        var status = !document.nodes().isEmpty() && validate(current.projectId(), document).valid() ? "READY" : "DRAFT";
        var version = current.currentVersion() + 1;
        var now = Instant.now();
        var nextRunAt = WorkflowScheduleSupport.nextRun(document.executionMode(), document.schedule(), now);
        var updated = jdbc.sql("""
                update workflow_definition set name = :name, description = :description,
                    current_version = :version, status = :status, next_run_at = :nextRunAt, updated_at = :now
                where id = :id and current_version = :currentVersion
                """).param("name", document.name()).param("description", document.description())
                .param("version", version).param("status", status).param("nextRunAt", nextRunAt)
                .param("now", now).param("id", id)
                .param("currentVersion", current.currentVersion()).update();
        if (updated == 0) throw new IllegalStateException("工作流已被更新，请刷新后再保存");
        insertVersion(id, version, document, now);
        return get(id);
    }

    public List<WorkflowResponse> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql("""
                select d.*, v.definition_json from workflow_definition d
                join workflow_version v on v.workflow_id = d.id and v.version_number = d.current_version
                where d.project_id = :projectId order by d.updated_at desc
                """).param("projectId", projectId).query(this::map).list();
    }

    @Transactional
    public void delete(String id) {
        get(id);
        var activeRuns = jdbc.sql("""
                select count(*) from workflow_run where workflow_id = :id
                and status in ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'WAITING_REVIEW')
                """).param("id", id).query(Long.class).single();
        if (activeRuns > 0) throw new IllegalStateException("工作流正在执行，请停止或完成复核后再删除");
        jdbc.sql("delete from workflow_activity_run where run_id in (select id from workflow_run where workflow_id = :id)")
                .param("id", id).update();
        jdbc.sql("delete from workflow_lineage_edge where run_id in (select id from workflow_run where workflow_id = :id)")
                .param("id", id).update();
        jdbc.sql("delete from workflow_run_event where run_id in (select id from workflow_run where workflow_id = :id)")
                .param("id", id).update();
        jdbc.sql("delete from workflow_node_run where run_id in (select id from workflow_run where workflow_id = :id)")
                .param("id", id).update();
        jdbc.sql("delete from workflow_run where workflow_id = :id").param("id", id).update();
        jdbc.sql("delete from workflow_version where workflow_id = :id").param("id", id).update();
        jdbc.sql("delete from workflow_definition where id = :id").param("id", id).update();
    }

    private Optional<WorkflowResponse> findProjectWorkflow(String projectId) {
        return jdbc.sql("""
                select d.*, v.definition_json from workflow_definition d
                join workflow_version v on v.workflow_id = d.id and v.version_number = d.current_version
                where d.project_id = :projectId order by d.updated_at desc limit 1
                """).param("projectId", projectId).query(this::map).optional();
    }

    public WorkflowResponse get(String id) {
        return jdbc.sql("""
                select d.*, v.definition_json from workflow_definition d
                join workflow_version v on v.workflow_id = d.id and v.version_number = d.current_version
                where d.id = :id
                """).param("id", id).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));
    }

    public WorkflowDocument version(String id, int version) {
        var json = jdbc.sql("select definition_json from workflow_version where workflow_id = :id and version_number = :version")
                .param("id", id).param("version", version).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("工作流版本不存在"));
        return readDocument(json);
    }

    public ValidationResponse validate(String projectId, WorkflowDocument document) {
        var issues = new ArrayList<ValidationIssue>();
        if (document.executionMode() == ExecutionMode.SCHEDULED) {
            if (document.schedule() == null || document.schedule().frequency() == null) {
                issues.add(new ValidationIssue("", "请选择定时执行频率"));
            } else {
                try {
                    WorkflowScheduleSupport.zone(document.schedule().timezone());
                    WorkflowScheduleSupport.parseTime(document.schedule().time());
                } catch (IllegalArgumentException exception) {
                    issues.add(new ValidationIssue("", exception.getMessage()));
                }
            }
        }
        if (document.nodes().isEmpty()) issues.add(new ValidationIssue("", "请先添加至少一个处理步骤"));
        var nodeById = new LinkedHashMap<String, NodeDefinition>();
        for (var node : document.nodes()) {
            if (!node.id().matches("[a-zA-Z0-9_-]{1,100}")) issues.add(new ValidationIssue(node.id(), "步骤标识不正确"));
            if (nodeById.putIfAbsent(node.id(), node) != null) issues.add(new ValidationIssue(node.id(), "步骤标识重复"));
            validateConfig(projectId, node, issues);
        }
        var edges = document.edges() == null ? List.<EdgeDefinition>of() : document.edges();
        var edgeIds = new HashSet<String>();
        for (var edge : edges) {
            if (!edgeIds.add(edge.id())) issues.add(new ValidationIssue(edge.target(), "连线标识重复"));
            if (!nodeById.containsKey(edge.source()) || !nodeById.containsKey(edge.target())) {
                issues.add(new ValidationIssue(edge.target(), "连线指向了不存在的步骤"));
            }
            if (edge.source().equals(edge.target())) issues.add(new ValidationIssue(edge.target(), "步骤不能连接到自己"));
        }
        List<String> order = List.of();
        if (issues.stream().noneMatch(issue -> issue.message().contains("连线") || issue.message().contains("重复"))) {
            try { order = topologicalOrder(document); }
            catch (IllegalArgumentException exception) { issues.add(new ValidationIssue("", exception.getMessage())); }
        }
        var incoming = new HashSet<String>();
        edges.forEach(edge -> incoming.add(edge.target()));
        for (var node : document.nodes()) {
            if (node.type() == NodeType.SPREADSHEET_TRANSFORM && !incoming.contains(node.id())
                    && blank(node.config(), "fileId")) {
                issues.add(new ValidationIssue(node.id(), "请选择表格，或连接一个会产生文件的上游步骤"));
            }
            if (node.type() == NodeType.DATA_TRANSFORM && !incoming.contains(node.id())) {
                issues.add(new ValidationIssue(node.id(), "请至少连接一份结构化数据"));
            }
            if (node.type() == NodeType.AI_ANALYSIS && !incoming.contains(node.id())) {
                issues.add(new ValidationIssue(node.id(), "请连接需要分析的资料或数据"));
            }
            if (node.type() == NodeType.DELIVERABLE && !incoming.contains(node.id()) && blank(node.config(), "body")) {
                issues.add(new ValidationIssue(node.id(), "请连接一个上游步骤，或直接填写内容"));
            }
            if (node.type() == NodeType.REVIEW && !incoming.contains(node.id())) {
                issues.add(new ValidationIssue(node.id(), "请连接需要复核的上游步骤"));
            }
        }
        return new ValidationResponse(issues.isEmpty(), issues, order);
    }

    List<String> topologicalOrder(WorkflowDocument document) {
        var indegree = new LinkedHashMap<String, Integer>();
        var outgoing = new HashMap<String, List<String>>();
        document.nodes().forEach(node -> indegree.put(node.id(), 0));
        for (var edge : document.edges() == null ? List.<EdgeDefinition>of() : document.edges()) {
            if (!indegree.containsKey(edge.source()) || !indegree.containsKey(edge.target())) continue;
            indegree.put(edge.target(), indegree.get(edge.target()) + 1);
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
        }
        var queue = new ArrayDeque<String>();
        indegree.forEach((id, count) -> { if (count == 0) queue.add(id); });
        var order = new ArrayList<String>();
        while (!queue.isEmpty()) {
            var id = queue.removeFirst();
            order.add(id);
            for (var target : outgoing.getOrDefault(id, List.of())) {
                var next = indegree.get(target) - 1;
                indegree.put(target, next);
                if (next == 0) queue.addLast(target);
            }
        }
        if (order.size() != document.nodes().size()) throw new IllegalArgumentException("步骤之间形成了循环，请调整连线");
        return order;
    }

    private void validateConfig(String projectId, NodeDefinition node, List<ValidationIssue> issues) {
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        try {
            switch (node.type()) {
                case FILE_INPUT -> {
                    require(node, config, issues, "resourceId", "请选择已上传文件");
                    if (!blank(config, "resourceId") && !knowledge.get(string(config, "resourceId")).projectId().equals(projectId))
                        issues.add(new ValidationIssue(node.id(), "文件不属于当前项目"));
                }
                case LINK_INPUT -> require(node, config, issues, "url", "请填写链接地址");
                case DATASET_INPUT -> {
                    require(node, config, issues, "extractJobId", "请选择已经采集的数据");
                    if (!blank(config, "extractJobId") && !extracts.get(string(config, "extractJobId")).projectId().equals(projectId))
                        issues.add(new ValidationIssue(node.id(), "数据文件不属于当前项目"));
                }
                case DATA_EXTRACT -> {
                    require(node, config, issues, "connectionId", "请选择数据连接");
                    require(node, config, issues, "sql", "请填写只读查询或 GET");
                    if (!blank(config, "connectionId") && !connections.get(string(config, "connectionId")).projectId().equals(projectId))
                        issues.add(new ValidationIssue(node.id(), "数据连接不属于当前项目"));
                }
                case DATA_TRANSFORM -> {
                    require(node, config, issues, "requirements", "请填写数据加工要求");
                    require(node, config, issues, "script", "请先生成或填写加工脚本");
                    require(node, config, issues, "outputName", "请填写输出文件名");
                }
                case PROCESS -> {
                    require(node, config, issues, "requirements", "请填写处理要求");
                    require(node, config, issues, "script", "请先生成或填写处理脚本");
                    require(node, config, issues, "outputName", "请填写输出名称");
                }
                case SPREADSHEET_TRANSFORM -> { }
                case REF_SEARCH -> require(node, config, issues, "query", "请填写需要查找的内容");
                case AI_ANALYSIS -> require(node, config, issues, "prompt", "请填写分析要求");
                case AGENT_TASK -> require(node, config, issues, "instruction", "请填写希望 Agent 完成的任务");
                case REVIEW -> require(node, config, issues, "instructions", "请填写复核要求");
                case DELIVERABLE -> {
                    require(node, config, issues, "generationPrompt", "请填写成果生成要求");
                    if (!blank(config, "format") && !List.of("PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW")
                            .contains(string(config, "format").toUpperCase(Locale.ROOT))) {
                        issues.add(new ValidationIssue(node.id(), "请选择有效的成果类型"));
                    }
                    if (Boolean.TRUE.equals(config.get("includeCitations")) &&
                            !List.of("IEEE", "APA_7", "GB_T_7714").contains(string(config, "citationStyle"))) {
                        issues.add(new ValidationIssue(node.id(), "请选择有效的引用格式"));
                    }
                }
                case OUTPUT -> {
                    require(node, config, issues, "title", "请填写输出标题");
                    require(node, config, issues, "format", "请选择输出格式");
                    require(node, config, issues, "generationPrompt", "请填写成果生成要求");
                }
                case TOOL -> require(node, config, issues, "capability", "请选择要使用的能力");
                case SUB_WORKFLOW -> require(node, config, issues, "workflowId", "请选择子工作流");
                case RESOURCE, ACQUIRE, CONTROL -> { }
            }
        } catch (IllegalArgumentException exception) {
            issues.add(new ValidationIssue(node.id(), exception.getMessage()));
        }
    }

    private void require(NodeDefinition node, Map<String, Object> config, List<ValidationIssue> issues,
                         String key, String message) {
        if (blank(config, key)) issues.add(new ValidationIssue(node.id(), message));
    }

    private boolean blank(Map<String, Object> config, String key) {
        if (config == null) return true;
        var value = config.get(key);
        return value == null || value.toString().isBlank();
    }

    private String string(Map<String, Object> config, String key) { return Objects.toString(config.get(key), ""); }

    private WorkflowDocument normalize(SaveRequest request) {
        var mode = request.executionMode() == null ? ExecutionMode.MANUAL : request.executionMode();
        return new WorkflowDocument(request.name().trim(), request.description() == null ? "" : request.description().trim(),
                request.nodes().stream().map(this::normalizeNode).toList(),
                request.edges() == null ? List.of() : List.copyOf(request.edges()),
                mode, mode == ExecutionMode.SCHEDULED ? request.schedule() : null);
    }

    private NodeDefinition normalizeNode(NodeDefinition node) {
        if (node.type() == NodeType.DELIVERABLE && node.config() != null) {
            var config = new LinkedHashMap<>(node.config());
            List.of("title", "subtitle", "heading", "targetAudience", "lengthHint").forEach(config::remove);
            return new NodeDefinition(node.id(), node.type(), node.name(), node.x(), node.y(), Map.copyOf(config));
        }
        if (node.type() != NodeType.AI_ANALYSIS || node.config() == null || !node.config().containsKey("maxPoints")) {
            return node;
        }
        var config = new LinkedHashMap<>(node.config());
        config.remove("maxPoints");
        return new NodeDefinition(node.id(), node.type(), node.name(), node.x(), node.y(), Map.copyOf(config));
    }

    private void insertVersion(String id, int version, WorkflowDocument document, Instant now) {
        jdbc.sql("""
                insert into workflow_version(id, workflow_id, version_number, definition_json, created_at)
                values (:versionId, :workflowId, :version, :definition, :now)
                """).param("versionId", UUID.randomUUID().toString()).param("workflowId", id).param("version", version)
                .param("definition", writeJson(document)).param("now", now).update();
    }

    private WorkflowResponse map(ResultSet rs, int rowNum) throws SQLException {
        var document = readDocument(rs.getString("definition_json"));
        return new WorkflowResponse(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getInt("current_version"),
                document.nodes(), document.edges(),
                document.executionMode() == null ? ExecutionMode.MANUAL : document.executionMode(), document.schedule(),
                rs.getTimestamp("next_run_at") == null ? null : rs.getTimestamp("next_run_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("工作流内容无法保存", exception); }
    }

    private WorkflowDocument readDocument(String json) {
        try { return objectMapper.readValue(json, WorkflowDocument.class); }
        catch (JacksonException exception) { throw new IllegalStateException("工作流内容无法读取", exception); }
    }
}
