package com.finflow.studio.workflow;

import com.finflow.studio.data.DataModels.CreateExtractRequest;
import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.data.DataTransformService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workflow.WorkflowModels.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;

@Service
public class WorkflowRunService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;
    private final WorkflowDefinitionService definitions;
    private final ExtractJobService extracts;
    private final DataTransformService dataTransforms;
    private final KnowledgeService knowledge;
    private final WorkerClient worker;
    private final WorkflowDeliverableService workflowDeliverables;
    private final WorkflowContextAssembler contexts;
    private final TaskExecutor taskExecutor;
    private final WorkflowRunEventService events;
    private final WorkflowFactService facts;

    public WorkflowRunService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                              WorkflowDefinitionService definitions, ExtractJobService extracts,
                              DataTransformService dataTransforms, KnowledgeService knowledge, WorkerClient worker,
                              WorkflowDeliverableService workflowDeliverables, WorkflowContextAssembler contexts,
                              @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                              WorkflowRunEventService events, WorkflowFactService facts) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.definitions = definitions;
        this.extracts = extracts;
        this.dataTransforms = dataTransforms;
        this.knowledge = knowledge;
        this.worker = worker;
        this.workflowDeliverables = workflowDeliverables;
        this.contexts = contexts;
        this.taskExecutor = taskExecutor;
        this.events = events;
        this.facts = facts;
    }

    @Transactional
    public RunResponse start(String workflowId) {
        return start(workflowId, "MANUAL");
    }

    @Transactional
    public RunResponse startScheduled(String workflowId) {
        return start(workflowId, "SCHEDULED");
    }

    private RunResponse start(String workflowId, String triggerType) {
        var workflow = definitions.get(workflowId);
        projects.get(workflow.projectId());
        var document = definitions.version(workflowId, workflow.currentVersion());
        var validation = definitions.validate(workflow.projectId(), document);
        if (!validation.valid()) throw new IllegalArgumentException(validation.issues().getFirst().message());
        return createRun(workflow, document, workflow.currentVersion(), null, triggerType, Map.of());
    }

    @Transactional
    public RunResponse retry(String runId) {
        var previous = get(runId);
        if (!List.of("FAILED", "CANCELED").contains(previous.status())) {
            throw new IllegalStateException("只有失败或已停止的运行可以继续");
        }
        var workflow = definitions.get(previous.workflowId());
        var document = definitions.version(previous.workflowId(), previous.workflowVersion());
        var reusable = new LinkedHashMap<String, Map<String, Object>>();
        previous.nodes().stream().filter(node -> List.of("SUCCEEDED", "REUSED").contains(node.status()))
                .forEach(node -> reusable.put(node.nodeId(), node.output()));
        return createRun(workflow, document, previous.workflowVersion(), previous.id(), "RETRY", reusable);
    }

    public RunResponse cancel(String id) {
        var run = get(id);
        if (List.of("SUCCEEDED", "FAILED", "CANCELED", "REJECTED").contains(run.status())) return run;
        if ("WAITING_REVIEW".equals(run.status())) {
            var now = Instant.now();
            jdbc.sql("update workflow_node_run set status = 'CANCELED', error_message = '复核时已停止', finished_at = :now where run_id = :id and status = 'WAITING_REVIEW'")
                    .param("now", now).param("id", id).update();
            jdbc.sql("update workflow_run set status = 'CANCELED', current_node_id = null, error_message = '运行已停止', finished_at = :now where id = :id")
                    .param("now", now).param("id", id).update();
            events.publish(id, "RUN_COMPLETED", "", "", "CANCELED", 100, "运行已停止", "");
            return get(id);
        }
        jdbc.sql("update workflow_run set status = 'CANCEL_REQUESTED' where id = :id")
                .param("id", id).update();
        return get(id);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        var now = Instant.now();
        jdbc.sql("""
                update workflow_node_run set status = 'FAILED',
                    error_message = '服务重启，当前步骤已中断', finished_at = :now
                where status = 'RUNNING'
                """).param("now", now).update();
        jdbc.sql("""
                update workflow_run set status = 'FAILED', current_node_id = null,
                    error_message = '服务重启导致运行中断，请重新执行', finished_at = :now
                where status in ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED')
                """).param("now", now).update();
    }

    public List<RunResponse> list(String workflowId) {
        definitions.get(workflowId);
        return jdbc.sql("select * from workflow_run where workflow_id = :workflowId order by created_at desc")
                .param("workflowId", workflowId).query(this::mapRun).list();
    }

    public RunResponse get(String id) {
        return jdbc.sql("select * from workflow_run where id = :id").param("id", id).query(this::mapRun).optional()
                .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在"));
    }

    public WorkflowPatch proposeSolidification(String id) {
        var run = get(id);
        if (!"SUCCEEDED".equals(run.status())) throw new IllegalStateException("只有完成的运行可以整理为工作流步骤");
        var workflow = definitions.get(run.workflowId());
        var existingResourceIds = workflow.nodes().stream().map(NodeDefinition::config)
                .filter(Objects::nonNull).map(config -> Objects.toString(config.get("resourceId"), ""))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        var operations = new ArrayList<PatchOperation>();
        var changes = 0;
        for (var nodeRun : run.nodes()) {
            var snapshots = list(nodeRun.output().get("sourceSnapshots"));
            for (var raw : snapshots) {
                if (!(raw instanceof Map<?, ?> snapshot)) continue;
                var resourceId = Objects.toString(snapshot.get("resourceId"), "");
                if (resourceId.isBlank() || existingResourceIds.contains(resourceId)) continue;
                var nodeId = "source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                var title = Objects.toString(snapshot.get("title"), "研究采用资料");
                var sourceNode = new NodeDefinition(nodeId, NodeType.FILE_INPUT, title,
                        Math.max(40, workflow.nodes().stream().mapToDouble(NodeDefinition::x).min().orElse(300) - 280),
                        80 + changes * 120, Map.of("resourceId", resourceId, "capturedFromRun", id));
                operations.add(new PatchOperation("add_node", null, null, sourceNode, null, null));
                operations.add(new PatchOperation("add_edge", null, null, null,
                        new EdgeDefinition("edge_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                                nodeId, nodeRun.nodeId()), null));
                existingResourceIds.add(resourceId);
                changes++;
            }
        }
        return new WorkflowPatch(workflow.currentVersion(),
                changes == 0 ? "本次运行没有需要新增到画布的稳定步骤" : "把本次采用的 " + changes + " 份外部资料固化为工作流输入",
                List.copyOf(operations), List.of(), List.of(), List.of());
    }

    private RunResponse createRun(WorkflowResponse workflow, WorkflowDocument document, int workflowVersion,
                                  String retryOf, String triggerType,
                                  Map<String, Map<String, Object>> reusable) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into workflow_run(id, workflow_id, project_id, workflow_version, retry_of_run_id,
                    trigger_type, status, output_json, trace_id, created_at)
                values (:id, :workflowId, :projectId, :version, :retryOf, :triggerType, 'QUEUED', '{}', :traceId, :now)
                """).param("id", id).param("workflowId", workflow.id()).param("projectId", workflow.projectId())
                .param("version", workflowVersion)
                .param("retryOf", retryOf).param("triggerType", triggerType)
                .param("traceId", UUID.randomUUID().toString()).param("now", now).update();
        var order = definitions.topologicalOrder(document);
        var nodes = document.nodes().stream().collect(java.util.stream.Collectors.toMap(NodeDefinition::id, node -> node));
        for (var index = 0; index < order.size(); index++) {
            var node = nodes.get(order.get(index));
            var reused = reusable.get(node.id());
            jdbc.sql("""
                    insert into workflow_node_run(id, run_id, node_id, node_name, node_type, step_order,
                        status, output_json, started_at, finished_at)
                    values (:id, :runId, :nodeId, :nodeName, :nodeType, :stepOrder,
                        :status, :output, :startedAt, :finishedAt)
                    """).param("id", UUID.randomUUID().toString()).param("runId", id).param("nodeId", node.id())
                    .param("nodeName", node.name()).param("nodeType", node.type().name()).param("stepOrder", index + 1)
                    .param("status", reused == null ? "PENDING" : "REUSED")
                    .param("output", writeJson(reused == null ? Map.of() : reused))
                    .param("startedAt", reused == null ? null : now).param("finishedAt", reused == null ? null : now).update();
        }
        schedule(id, document, reusable);
        events.publish(id, "RUN_STATUS", "", "", "QUEUED", 0, "运行已加入队列", "");
        return get(id);
    }

    private void execute(String runId, WorkflowDocument document, Map<String, Map<String, Object>> reusable) {
        var now = Instant.now();
        jdbc.sql("update workflow_run set status = 'RUNNING', started_at = coalesce(started_at, :now) where id = :id and status = 'QUEUED'")
                .param("now", now).param("id", runId).update();
        events.publish(runId, "RUN_STATUS", "", "", "RUNNING", 2, "工作流开始执行", "");
        var run = get(runId);
        var context = new LinkedHashMap<String, Map<String, Object>>(reusable);
        var byId = document.nodes().stream().collect(java.util.stream.Collectors.toMap(NodeDefinition::id, node -> node));
        context.replaceAll((id, value) -> byId.containsKey(id) ? WorkflowVariables.publish(byId.get(id).type(), value) : value);
        try {
            var order = definitions.topologicalOrder(document);
            var completed = 0;
            for (var nodeId : order) {
                if (context.containsKey(nodeId)) continue;
                ensureNotCanceled(runId);
                var node = byId.get(nodeId);
                var nodeStartedAt = Instant.now();
                var upstream = upstream(document, nodeId, context);
                startNode(runId, node, upstream);
                var startProgress = 5 + (int) Math.floor(88.0 * completed / Math.max(1, order.size()));
                var endProgress = 5 + (int) Math.floor(88.0 * (completed + 1) / Math.max(1, order.size()));
                events.publish(runId, "NODE_STARTED", node.id(), node.name(), "RUNNING", startProgress,
                        "开始：" + node.name(), "");
                var activityId = facts.beginActivity(runId, node.id(), activityType(node.type()),
                        capability(node), node.name(), Map.copyOf(upstream));
                try {
                    WorkflowVariables.validate(document, node);
                    var resolvedConfig = WorkflowVariables.resolve(node.config() == null ? Map.of() : node.config(), context);
                    var effectiveNode = new NodeDefinition(node.id(), node.type(), node.name(), node.x(), node.y(), resolvedConfig);
                    for (var reference : WorkflowVariables.references(node.config() == null ? Map.of() : node.config())) {
                        if (context.containsKey(reference.nodeId())) upstream.putIfAbsent(reference.nodeId(), context.get(reference.nodeId()));
                    }
                    var inputSource = WorkflowVariables.inputSource(resolvedConfig);
                    if (inputSource != null) {
                        var source = context.get(inputSource.nodeId());
                        var selected = new LinkedHashMap<String, Object>();
                        selected.put("text", WorkflowVariables.asText(WorkflowVariables.read(inputSource, context)));
                        selected.put("refs", source.getOrDefault("sources", source.getOrDefault("refs", List.of())));
                        selected.put("refIds", source.getOrDefault("refIds", List.of()));
                        upstream.clear();
                        upstream.put(inputSource.nodeId(), selected);
                    }
                    var actualInput = new LinkedHashMap<String, Map<String, Object>>();
                    actualInput.put("config", resolvedConfig);
                    actualInput.put("upstream", new LinkedHashMap<>(upstream));
                    startNode(runId, node, node.type() == NodeType.REVIEW ? upstream : actualInput);
                    if (node.type() == NodeType.REVIEW) {
                        waitForReview(runId, effectiveNode, upstream, context, endProgress);
                        return;
                    }
                    var output = WorkflowVariables.publish(node.type(), executeNode(run, runId, effectiveNode, upstream, context, startProgress, endProgress));
                    context.put(node.id(), output);
                    facts.completeActivity(activityId, "SUCCEEDED", output, "");
                    facts.recordNodeLineage(runId, node.id(), upstream, output);
                    finishNode(runId, node.id(), "SUCCEEDED", output, "");
                    completed++;
                    events.publish(runId, "NODE_COMPLETED", node.id(), node.name(), "SUCCEEDED", endProgress,
                            node.name() + "已完成，用时 " + elapsed(nodeStartedAt), "");
                } catch (Exception exception) {
                    facts.completeActivity(activityId, exception instanceof CancellationException ? "CANCELED" : "FAILED",
                            Map.of(), sanitize(exception.getMessage()));
                    finishNode(runId, node.id(), exception instanceof CancellationException ? "CANCELED" : "FAILED",
                            Map.of(), sanitize(exception.getMessage()));
                    events.publish(runId, "NODE_FAILED", node.id(), node.name(),
                            exception instanceof CancellationException ? "CANCELED" : "FAILED", startProgress,
                            sanitize(exception.getMessage()), "");
                    throw exception;
                }
            }
            jdbc.sql("""
                    update workflow_run set status = 'SUCCEEDED', current_node_id = null,
                        output_json = :output, finished_at = :now where id = :id
                    """).param("output", writeJson(context)).param("now", Instant.now()).param("id", runId).update();
            events.publish(runId, "RUN_COMPLETED", "", "", "SUCCEEDED", 100,
                    "工作流运行完成，成果已加入项目", "");
        } catch (CancellationException exception) {
            finishRun(runId, "CANCELED", "运行已停止", context);
            events.publish(runId, "RUN_COMPLETED", "", "", "CANCELED", 100, "运行已停止", "");
        } catch (Exception exception) {
            finishRun(runId, "FAILED", sanitize(exception.getMessage()), context);
            events.publish(runId, "RUN_COMPLETED", "", "", "FAILED", 100,
                    sanitize(exception.getMessage()), "");
        }
    }

    private Map<String, Object> executeNode(RunResponse run, String runId, NodeDefinition node,
                                            Map<String, Map<String, Object>> upstream,
                                            Map<String, Map<String, Object>> context,
                                            int startProgress, int endProgress) throws InterruptedException {
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        return switch (node.type()) {
            case FILE_INPUT -> {
                var file = knowledge.get(text(config, "resourceId"));
                var refs = knowledge.sampledCurrentRefs(file.id(), 160);
                yield Map.of("fileId", file.id(), "name", file.name(), "version", file.currentVersion(),
                        "downloadUrl", "/api/files/" + file.id() + "/download",
                        "refs", refs.stream().map(this::refMap).toList(),
                        "refIds", refs.stream().map(ref -> ref.id()).toList());
            }
            case LINK_INPUT -> linkInput(node, config);
            case DATASET_INPUT -> {
                var job = extracts.get(text(config, "extractJobId"));
                if (!job.projectId().equals(run.projectId())) throw new IllegalArgumentException("数据文件不属于当前项目");
                if (!"SUCCEEDED".equals(job.status())) throw new IllegalStateException("数据文件尚未采集完成");
                yield Map.of("extractJobId", job.id(), "rowCount", job.rowCount(), "byteCount", job.byteCount(),
                        "fileName", job.outputName(), "downloadUrl", "/api/extract-jobs/" + job.id() + "/download");
            }
            case DATA_EXTRACT -> runExtract(run, runId, node, config, startProgress, endProgress);
            case DATA_TRANSFORM -> transformData(run, runId, node, config, upstream, startProgress, endProgress);
            case PROCESS -> transformData(run, runId, node, config, upstream, startProgress, endProgress);
            case SPREADSHEET_TRANSFORM -> transformSpreadsheet(config, upstream);
            case REF_SEARCH -> searchRefs(run.projectId(), config);
            case AI_ANALYSIS -> analyze(runId, node, config, upstream,
                    startProgress, endProgress);
            case AGENT_TASK -> agentTask(run.projectId(), runId, node, config, upstream, context,
                    startProgress, endProgress);
            case REVIEW -> throw new IllegalStateException("复核步骤应由运行器暂停处理");
            case DELIVERABLE -> workflowDeliverables.create(run.projectId(), runId, node, config, upstream,
                    startProgress, endProgress);
            case OUTPUT -> workflowDeliverables.create(run.projectId(), runId, node, config, upstream,
                    startProgress, endProgress);
            case SUB_WORKFLOW -> runSubWorkflow(run, config, upstream);
            case RESOURCE, ACQUIRE, TOOL, CONTROL -> passThrough(upstream);
        };
    }

    private Map<String, Object> runSubWorkflow(RunResponse parent, Map<String, Object> config,
                                                Map<String, Map<String, Object>> upstream) throws InterruptedException {
        var workflowId = text(config, "workflowId");
        if (workflowId.equals(parent.workflowId())) throw new IllegalArgumentException("工作流不能调用自身");
        var childWorkflow = definitions.get(workflowId);
        if (!childWorkflow.projectId().equals(parent.projectId())) {
            throw new IllegalArgumentException("只能复用当前项目中的工作流");
        }
        var child = start(workflowId);
        var deadline = Instant.now().plus(Duration.ofSeconds(Math.max(30,
                Math.min(3600, integer(config, "timeoutSeconds", 900)))));
        while (Instant.now().isBefore(deadline)) {
            ensureNotCanceled(parent.id());
            child = get(child.id());
            if ("SUCCEEDED".equals(child.status())) {
                var output = passThrough(upstream);
                output.put("subWorkflowId", workflowId);
                output.put("subWorkflowRunId", child.id());
                output.put("subWorkflowVersion", child.workflowVersion());
                output.put("result", child.output());
                return output;
            }
            if (List.of("FAILED", "CANCELED", "REJECTED").contains(child.status())) {
                throw new IllegalStateException("复用的工作流执行失败：" + sanitize(child.errorMessage()));
            }
            Thread.sleep(500);
        }
        cancel(child.id());
        throw new IllegalStateException("复用的工作流执行超时");
    }

    private void waitForReview(String runId, NodeDefinition node,
                               Map<String, Map<String, Object>> upstream,
                               Map<String, Map<String, Object>> context, int progress) {
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        var output = passThrough(upstream);
        var content = firstText(upstream);
        output.put("reviewTitle", optional(config, "reviewTitle", node.name()));
        output.put("instructions", text(config, "instructions"));
        output.put("editable", !config.containsKey("editable") || bool(config, "editable"));
        output.put("requireComment", bool(config, "requireComment"));
        output.put("reviewContent", content);
        output.put("reviewStatus", "WAITING_REVIEW");
        output.put("reviewItems", reviewItems(upstream));
        jdbc.sql("""
                update workflow_node_run set status = 'WAITING_REVIEW', output_json = :output,
                    error_message = '' where run_id = :runId and node_id = :nodeId
                """).param("output", writeJson(output)).param("runId", runId).param("nodeId", node.id()).update();
        jdbc.sql("""
                update workflow_run set status = 'WAITING_REVIEW', current_node_id = :nodeId,
                    output_json = :output where id = :runId
                """).param("nodeId", node.id()).param("output", writeJson(context)).param("runId", runId).update();
        events.publish(runId, "REVIEW_REQUIRED", node.id(), node.name(), "WAITING_REVIEW", progress,
                "等待你复核：" + node.name(), content);
    }

    @Transactional
    public RunResponse confirmReview(String runId, ReviewRequest request) {
        var run = get(runId);
        if (!"WAITING_REVIEW".equals(run.status()) || run.currentNodeId() == null) {
            throw new IllegalStateException("当前运行没有等待复核的步骤");
        }
        var review = run.nodes().stream().filter(node -> node.nodeId().equals(run.currentNodeId())
                && "WAITING_REVIEW".equals(node.status())).findFirst()
                .orElseThrow(() -> new IllegalStateException("复核步骤状态已变化"));
        var output = passThroughMaps(review.input());
        var adjusted = request.adjustedContent() == null ? "" : request.adjustedContent().trim();
        if (!adjusted.isBlank()) {
            output.put("analysis", adjusted);
            output.put("summary", adjusted);
            output.put("points", Arrays.stream(adjusted.split("\\R")).map(String::trim)
                    .filter(line -> !line.isBlank()).limit(20).toList());
        }
        refreshFileVersion(output);
        output.put("reviewStatus", "CONFIRMED");
        output.put("reviewComment", request.comment() == null ? "" : request.comment().trim());
        output.put("reviewedBy", "当前用户");
        output.put("reviewedAt", Instant.now().toString());
        finishNode(runId, review.nodeId(), "SUCCEEDED", output, "");
        var context = completedContext(runId);
        jdbc.sql("""
                update workflow_run set status = 'QUEUED', current_node_id = null, output_json = :output,
                    error_message = '', finished_at = null where id = :id
                """).param("output", writeJson(context)).param("id", runId).update();
        var document = definitions.version(run.workflowId(), run.workflowVersion());
        schedule(runId, document, context);
        events.publish(runId, "REVIEW_CONFIRMED", review.nodeId(), review.nodeName(), "SUCCEEDED", 0,
                "复核已确认，继续执行后续步骤", "");
        return get(runId);
    }

    @Transactional
    public RunResponse rejectReview(String runId, ReviewRequest request) {
        var run = get(runId);
        if (!"WAITING_REVIEW".equals(run.status()) || run.currentNodeId() == null) {
            throw new IllegalStateException("当前运行没有等待复核的步骤");
        }
        var comment = request.comment() == null ? "" : request.comment().trim();
        var message = comment.isBlank() ? "复核未通过" : "复核未通过：" + comment;
        var now = Instant.now();
        jdbc.sql("""
                update workflow_node_run set status = 'REJECTED', error_message = :message,
                    output_json = :output, finished_at = :now where run_id = :runId and node_id = :nodeId
                """).param("message", message).param("output", writeJson(Map.of("reviewStatus", "REJECTED", "reviewComment", comment,
                        "reviewedBy", "当前用户", "reviewedAt", now.toString())))
                .param("now", now).param("runId", runId).param("nodeId", run.currentNodeId()).update();
        jdbc.sql("""
                update workflow_run set status = 'REJECTED', current_node_id = null,
                    error_message = :message, finished_at = :now where id = :id
                """).param("message", message).param("now", now).param("id", runId).update();
        events.publish(runId, "RUN_COMPLETED", run.currentNodeId(), "人工复核", "REJECTED", 100, message, "");
        return get(runId);
    }

    private Map<String, Object> passThrough(Map<String, Map<String, Object>> upstream) {
        var output = new LinkedHashMap<String, Object>();
        upstream.values().forEach(output::putAll);
        output.put("upstream", upstream);
        output.put("refs", contexts.sourceRefs(upstream));
        output.put("refIds", contexts.refIds(upstream));
        output.remove("sources");
        output.remove("output");
        return output;
    }

    private Map<String, Object> passThroughMaps(Map<String, Object> input) {
        var typed = new LinkedHashMap<String, Map<String, Object>>();
        input.forEach((key, value) -> typed.put(key, map(value)));
        return passThrough(typed);
    }

    private String firstText(Map<String, Map<String, Object>> upstream) {
        for (var key : List.of("analysis", "summary", "text")) {
            var value = findString(upstream, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private List<Map<String, Object>> reviewItems(Map<String, Map<String, Object>> upstream) {
        var items = new ArrayList<Map<String, Object>>();
        upstream.forEach((nodeId, value) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("nodeId", nodeId);
            for (var key : List.of("name", "fileId", "extractJobId", "deliverableId", "downloadUrl", "fileName", "rowCount", "version")) {
                if (value.get(key) != null) item.put(key, value.get(key));
            }
            item.put("hasText", !Objects.toString(value.get("analysis"), "").isBlank()
                    || !Objects.toString(value.get("summary"), "").isBlank());
            items.add(Map.copyOf(item));
        });
        return List.copyOf(items);
    }

    private void refreshFileVersion(Map<String, Object> output) {
        var fileId = Objects.toString(output.get("fileId"), "");
        if (fileId.isBlank()) return;
        var file = knowledge.get(fileId);
        output.put("version", file.currentVersion());
        output.put("name", file.name());
        output.put("downloadUrl", "/api/files/" + file.id() + "/download");
    }

    private Map<String, Map<String, Object>> completedContext(String runId) {
        var context = new LinkedHashMap<String, Map<String, Object>>();
        jdbc.sql("select node_id, output_json from workflow_node_run where run_id = :runId and status in ('SUCCEEDED','REUSED') order by step_order")
                .param("runId", runId).query((rs, rowNum) -> Map.entry(rs.getString("node_id"), readMap(rs.getString("output_json"))))
                .list().forEach(entry -> context.put(entry.getKey(), entry.getValue()));
        return context;
    }

    private Map<String, Object> transformData(RunResponse run, String runId, NodeDefinition node,
                                              Map<String, Object> config,
                                              Map<String, Map<String, Object>> upstream,
                                              int startProgress, int endProgress) {
        events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING", startProgress,
                "正在校验脚本并试跑样本", "");
        var result = dataTransforms.execute(run.projectId(), config, upstream);
        events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING", Math.max(startProgress, endProgress - 1),
                "全量加工完成，正在登记结果文件", "");
        var resource = result.resource();
        var output = new LinkedHashMap<String, Object>();
        output.put("fileId", resource.id());
        output.put("name", resource.name());
        output.put("version", resource.currentVersion());
        output.put("downloadUrl", "/api/files/" + resource.id() + "/download");
        output.put("sampleReport", result.sampleReport());
        output.put("qualityReport", result.qualityReport());
        output.put("script", Objects.toString(config.get("script"), ""));
        output.put("requirements", Objects.toString(config.get("requirements"), ""));
        return output;
    }

    private Map<String, Object> runExtract(RunResponse run, String runId, NodeDefinition node,
                                           Map<String, Object> config, int startProgress,
                                           int endProgress) throws InterruptedException {
        events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING", startProgress,
                "正在连接数据源", "");
        var job = extracts.create(new CreateExtractRequest(run.projectId(), text(config, "connectionId"),
                optional(config, "taskName", node.name()), text(config, "sql"), integer(config, "fetchSize", 5000),
                optional(config, "outputName", node.id() + ".csv")));
        long lastReportedRows = -1;
        while (!List.of("SUCCEEDED", "FAILED", "CANCELED").contains(job.status())) {
            try {
                ensureNotCanceled(runId);
            } catch (CancellationException exception) {
                extracts.cancel(job.id());
                throw exception;
            }
            Thread.sleep(500);
            job = extracts.get(job.id());
            if (job.rowCount() != lastReportedRows) {
                lastReportedRows = job.rowCount();
                events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING",
                        Math.min(endProgress - 1, startProgress + 2),
                        "已写入 " + String.format("%,d", job.rowCount()) + " 行数据", "");
            }
        }
        if (!"SUCCEEDED".equals(job.status())) throw new IllegalStateException(job.errorMessage());
        return Map.of("extractJobId", job.id(), "rowCount", job.rowCount(), "byteCount", job.byteCount(),
                "fileName", job.outputName(), "downloadUrl", "/api/extract-jobs/" + job.id() + "/download");
    }

    private Map<String, Object> transformSpreadsheet(Map<String, Object> config,
                                                     Map<String, Map<String, Object>> upstream) {
        var fileId = optional(config, "fileId", findString(upstream, "fileId"));
        var file = knowledge.get(fileId);
        var operations = Map.of(
                "sheet_name", optional(config, "sheetName", ""),
                "rename_headers", map(config.get("renameHeaders")),
                "fill_blanks", map(config.get("fillBlanks")),
                "formula_columns", list(config.get("formulaColumns")),
                "remove_duplicates", bool(config, "removeDuplicates"));
        var bytes = worker.transformSpreadsheet(knowledge.filePath(file.id(), null), file.name(), operations);
        var result = knowledge.createGeneratedVersion(file.projectId(), file.id(), file.name(), file.mediaType(), bytes);
        return Map.of("fileId", result.id(), "name", result.name(), "version", result.currentVersion(),
                "downloadUrl", "/api/files/" + result.id() + "/download");
    }

    private Map<String, Object> searchRefs(String projectId, Map<String, Object> config) {
        var refs = knowledge.search(projectId, text(config, "query"), integer(config, "limit", 10));
        var items = refs.stream().map(this::refMap).toList();
        return Map.of("count", items.size(), "refs", items, "refIds", refs.stream().map(ref -> ref.id()).toList());
    }

    private Map<String, Object> analyze(String runId, NodeDefinition node, Map<String, Object> config,
                                        Map<String, Map<String, Object>> upstream,
                                        int startProgress, int endProgress) {
        var prompt = text(config, "prompt");
        var sourceText = contexts.collectText(upstream);
        var refIds = contexts.refIds(upstream);
        if (sourceText.isBlank()) throw new IllegalStateException("智能分析没有可读取的上游资料，请先连接资料或数据节点");
        var span = Math.max(1, endProgress - startProgress);
        var result = worker.generateContentStreaming("ANALYSIS", prompt, sourceText, event -> {
            var type = Objects.toString(event.get("type"), "status");
            var modelProgress = event.get("progress") instanceof Number number ? number.intValue() : 50;
            var progress = startProgress + Math.max(1, (int) Math.floor(span * modelProgress / 100.0));
            if ("content".equals(type)) {
                events.publish(runId, "MODEL_OUTPUT", node.id(), node.name(), "RUNNING", progress,
                        "正在生成分析内容", Objects.toString(event.get("content"), ""));
            } else if (!"complete".equals(type)) {
                events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", progress,
                        Objects.toString(event.get("message"), "正在分析"), "");
            }
        });
        var analysis = Objects.toString(result.get("content"), "");
        var mode = Objects.toString(result.get("mode"), "");
        if (mode.contains("fallback")) throw new IllegalStateException("大模型当前不可用，智能分析未执行");
        var points = Arrays.stream(analysis.split("\\R"))
                .map(String::trim).filter(line -> !line.isBlank()).toList();
        if (points.isEmpty() && !analysis.isBlank()) points = List.of(analysis);
        return Map.of("analysis", analysis, "points", points, "refIds", refIds, "refs", contexts.sourceRefs(upstream),
                "analysisMode", mode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agentTask(String projectId, String runId, NodeDefinition node,
                                          Map<String, Object> config,
                                          Map<String, Map<String, Object>> upstream,
                                          Map<String, Map<String, Object>> context,
                                          int startProgress, int endProgress) {
        var instruction = text(config, "instruction");
        var sourceContext = contexts.collectText(upstream);
        var externalResearch = optional(config, "externalResearch", "OFF").toUpperCase(Locale.ROOT);
        var policy = new LinkedHashMap<String, Object>();
        policy.put("external_research", externalResearch);
        policy.put("domain_allowlist", stringList(config.get("domainAllowlist")));
        policy.put("max_tool_calls", integer(config, "maxToolCalls", 80));
        policy.put("timeout_seconds", integer(config, "timeoutSeconds", 900));
        var request = new LinkedHashMap<String, Object>();
        request.put("task", instruction);
        request.put("project_id", projectId);
        request.put("source_context", sourceContext);
        request.put("resources", List.of());
        request.put("skills", stringList(config.get("skills")));
        request.put("policy", policy);
        var activityIds = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var span = Math.max(1, endProgress - startProgress);
        var result = worker.runAgentTaskStreaming(request, event -> {
            var type = Objects.toString(event.get("type"), "status");
            var eventProgress = event.get("progress") instanceof Number number ? number.intValue() : 50;
            var progress = startProgress + Math.max(1, (int) Math.floor(span * eventProgress / 100.0));
            var message = Objects.toString(event.get("message"), "Agent 正在处理");
            var externalId = Objects.toString(event.get("activity_id"), UUID.randomUUID().toString());
            if ("tool_started".equals(type)) {
                var activity = facts.beginActivity(runId, node.id(), "TOOL_CALL",
                        Objects.toString(event.get("capability"), "agent.tool"), message, Map.of());
                activityIds.put(externalId, activity);
                events.publish(runId, "AGENT_TOOL_STARTED", node.id(), node.name(), "RUNNING", progress, message, "");
            } else if ("tool_completed".equals(type)) {
                var activity = activityIds.remove(externalId);
                if (activity != null) facts.completeActivity(activity, "SUCCEEDED", map(event.get("output")), "");
                events.publish(runId, "AGENT_TOOL_COMPLETED", node.id(), node.name(), "RUNNING", progress, message, "");
            } else if ("content".equals(type)) {
                events.publish(runId, "MODEL_OUTPUT", node.id(), node.name(), "RUNNING", progress,
                        "Agent 正在整理结果", Objects.toString(event.get("content"), ""));
            } else if (!"complete".equals(type)) {
                events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", progress, message, "");
            }
        });
        var analysis = Objects.toString(result.get("content"), "").trim();
        var snapshots = new ArrayList<Map<String, Object>>();
        var sourceRefs = new ArrayList<Map<?, ?>>(contexts.sourceRefs(upstream));
        var sourceIds = new LinkedHashSet<String>(contexts.refIds(upstream));
        if (result.get("used_sources") instanceof List<?> usedSources) {
            for (var raw : usedSources) {
                if (!(raw instanceof Map<?, ?> source)) continue;
                var title = safeFileName(Objects.toString(source.get("title"), "网页资料"));
                var url = Objects.toString(source.get("final_url"), Objects.toString(source.get("url"), ""));
                var sourceText = Objects.toString(source.get("text"), "");
                if (sourceText.isBlank()) continue;
                var markdown = "# " + title + "\n\n原始地址：" + url + "\n抓取时间：" + Instant.now()
                        + "\n内容哈希：" + Objects.toString(source.get("content_hash"), "") + "\n\n" + sourceText;
                var resource = knowledge.importBytes(projectId, title + ".md", "text/markdown",
                        markdown.getBytes(StandardCharsets.UTF_8));
                var refId = "agent:" + resource.id();
                sourceIds.add(refId);
                sourceRefs.add(Map.of("id", refId, "resourceId", resource.id(), "version", resource.currentVersion(),
                        "sourceName", title, "text", sourceText, "location", Map.of("url", url),
                        "contentHash", Objects.toString(source.get("content_hash"), "")));
                facts.recordExternalSource(runId, node.id(), resource.id(), resource.currentVersion(), url,
                        Objects.toString(source.get("content_hash"), ""));
                snapshots.add(Map.of("resourceId", resource.id(), "version", resource.currentVersion(),
                        "title", title, "url", url, "downloadUrl", "/api/files/" + resource.id() + "/download"));
            }
        }
        var points = Arrays.stream(analysis.split("\\R")).map(String::trim).filter(line -> !line.isBlank())
                .limit(integer(config, "maxPoints", 10)).toList();
        var output = new LinkedHashMap<String, Object>();
        output.put("analysis", analysis);
        output.put("points", points);
        output.put("analysisMode", Objects.toString(result.get("mode"), "deep-agents"));
        output.put("sourceSnapshots", snapshots);
        output.put("externalResearch", externalResearch);
        output.put("toolCalls", result.getOrDefault("tool_calls", 0));
        output.put("refIds", List.copyOf(sourceIds));
        output.put("refs", List.copyOf(sourceRefs));
        return Map.copyOf(output);
    }

    private String safeFileName(String value) {
        var result = value.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (result.isBlank()) result = "网页资料";
        return result.substring(0, Math.min(result.length(), 180));
    }

    private Map<String, Map<String, Object>> upstream(WorkflowDocument document, String nodeId,
                                                       Map<String, Map<String, Object>> context) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        document.edges().stream().filter(edge -> edge.target().equals(nodeId))
                .forEach(edge -> { if (context.containsKey(edge.source())) result.put(edge.source(), context.get(edge.source())); });
        return result;
    }

    private void startNode(String runId, NodeDefinition node, Map<String, Map<String, Object>> input) {
        jdbc.sql("update workflow_run set current_node_id = :nodeId where id = :runId")
                .param("nodeId", node.id()).param("runId", runId).update();
        jdbc.sql("""
                update workflow_node_run set status = 'RUNNING', input_json = :input, started_at = :now
                where run_id = :runId and node_id = :nodeId
                """).param("input", writeJson(input)).param("now", Instant.now()).param("runId", runId)
                .param("nodeId", node.id()).update();
    }

    private void finishNode(String runId, String nodeId, String status, Map<String, Object> output, String error) {
        jdbc.sql("""
                update workflow_node_run set status = :status, output_json = :output,
                    error_message = :error, finished_at = :now where run_id = :runId and node_id = :nodeId
                """).param("status", status).param("output", writeJson(output)).param("error", error)
                .param("now", Instant.now()).param("runId", runId).param("nodeId", nodeId).update();
    }

    private void finishRun(String runId, String status, String error, Map<String, Map<String, Object>> context) {
        jdbc.sql("""
                update workflow_run set status = :status, output_json = :output,
                    error_message = :error, finished_at = :now where id = :id
                """).param("status", status).param("output", writeJson(context)).param("error", error)
                .param("now", Instant.now()).param("id", runId).update();
    }

    private void ensureNotCanceled(String runId) {
        var status = jdbc.sql("select status from workflow_run where id = :id").param("id", runId)
                .query(String.class).single();
        if ("CANCEL_REQUESTED".equals(status)) throw new CancellationException("运行已停止");
    }

    private RunResponse mapRun(ResultSet rs, int rowNum) throws SQLException {
        var id = rs.getString("id");
        var nodes = jdbc.sql("select * from workflow_node_run where run_id = :runId order by step_order")
                .param("runId", id).query(this::mapNodeRun).list();
        return new RunResponse(id, rs.getString("workflow_id"), rs.getString("project_id"),
                rs.getInt("workflow_version"), rs.getString("retry_of_run_id"), rs.getString("trigger_type"), rs.getString("status"),
                rs.getString("current_node_id"), readMap(rs.getString("output_json")), rs.getString("error_message"),
                rs.getString("trace_id"), nodes, facts.lineageForRun(id), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private NodeRunResponse mapNodeRun(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRunResponse(rs.getString("id"), rs.getString("node_id"), rs.getString("node_name"),
                NodeType.valueOf(rs.getString("node_type")), rs.getInt("step_order"), rs.getString("status"),
                readMap(rs.getString("input_json")), readMap(rs.getString("output_json")), rs.getString("error_message"),
                facts.activitiesForNode(rs.getString("id")),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private String activityType(NodeType type) {
        return switch (type) {
            case AGENT_TASK, AI_ANALYSIS -> "PLAN";
            case DELIVERABLE, OUTPUT -> "ARTIFACT";
            case DATA_TRANSFORM, PROCESS, SPREADSHEET_TRANSFORM -> "TOOL_CALL";
            case REVIEW -> "MESSAGE";
            default -> "TOOL_CALL";
        };
    }

    private String capability(NodeDefinition node) {
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        return Objects.toString(config.getOrDefault("capability", node.type().name().toLowerCase(Locale.ROOT)), "");
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void schedule(String id, WorkflowDocument document, Map<String, Map<String, Object>> reusable) {
        var task = (Runnable) () -> execute(id, document, reusable);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { taskExecutor.execute(task); }
            });
        } else taskExecutor.execute(task);
    }

    private Map<String, Object> linkInput(NodeDefinition node, Map<String, Object> config) {
        var url = text(config, "url");
        var title = optional(config, "title", node.name());
        var id = "link:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
        var sourceText = "网页资料：" + title;
        var contentHash = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
        try {
            var fetched = worker.fetchResearchSource(url);
            sourceText = Objects.toString(fetched.get("text"), sourceText);
            contentHash = Objects.toString(fetched.get("content_hash"), contentHash);
            title = Objects.toString(fetched.get("title"), title);
        } catch (RuntimeException ignored) {
            sourceText += "（正文读取失败，仅保留来源定位）";
        }
        var ref = Map.<String, Object>of("id", id, "resourceId", "", "version", 0,
                "sourceName", title, "text", sourceText,
                "location", Map.of("url", url), "contentHash", contentHash);
        return Map.of("url", url, "title", title, "text", sourceText, "refs", List.of(ref), "refIds", List.of(id));
    }

    private Map<String, Object> refMap(com.finflow.studio.knowledge.KnowledgeModels.RefResponse ref) {
        return Map.of("id", ref.id(), "resourceId", ref.resourceId(), "version", ref.version(),
                "sourceName", ref.sourceName(), "text", ref.text(), "location", ref.location(),
                "contentHash", ref.contentHash());
    }

    private Object findValue(Map<String, Map<String, Object>> values, String key) {
        return values.values().stream().map(value -> value.get(key)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private String findString(Map<String, Map<String, Object>> values, String key) {
        return Objects.toString(findValue(values, key), "");
    }

    private String text(Map<String, Object> config, String key) {
        var value = Objects.toString(config.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("步骤缺少配置：" + key);
        return value;
    }

    private String optional(Map<String, Object> config, String key, String fallback) {
        var value = Objects.toString(config.get(key), "").trim();
        return value.isBlank() ? fallback : value;
    }

    private int integer(Map<String, Object> config, String key, int fallback) {
        var value = config.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean bool(Map<String, Object> config, String key) {
        return Boolean.TRUE.equals(config.get(key)) || "true".equalsIgnoreCase(Objects.toString(config.get(key), ""));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private List<?> list(Object value) { return value instanceof List<?> items ? items : List.of(); }
    private List<String> stringList(Object value) {
        return list(value).stream().map(item -> Objects.toString(item, "")).filter(item -> !item.isBlank()).toList();
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("工作流运行结果无法保存", exception); }
    }

    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { return Map.of(); }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "步骤没有完成，请检查配置";
        var clean = value.replaceAll("(?i)(password|pwd|token|secret)=[^&;\\s]+", "$1=***");
        return clean.substring(0, Math.min(clean.length(), 1800));
    }

    private String elapsed(Instant startedAt) {
        var seconds = Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
        return seconds < 60 ? seconds + " 秒" : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }
}
