package com.finflow.studio.workflow;

import com.finflow.studio.data.DataModels.CreateExtractRequest;
import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.data.DataTransformService;
import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.CitationRequest;
import com.finflow.studio.deliverable.DeliverableModels.SectionRequest;
import com.finflow.studio.deliverable.DeliverableService;
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

import java.nio.file.Path;
import java.nio.file.Files;
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
    private final DeliverableService deliverables;
    private final TaskExecutor taskExecutor;
    private final WorkflowRunEventService events;

    public WorkflowRunService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                              WorkflowDefinitionService definitions, ExtractJobService extracts,
                              DataTransformService dataTransforms, KnowledgeService knowledge, WorkerClient worker,
                              DeliverableService deliverables,
                              @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                              WorkflowRunEventService events) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.definitions = definitions;
        this.extracts = extracts;
        this.dataTransforms = dataTransforms;
        this.knowledge = knowledge;
        this.worker = worker;
        this.deliverables = deliverables;
        this.taskExecutor = taskExecutor;
        this.events = events;
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
                try {
                    if (node.type() == NodeType.REVIEW) {
                        waitForReview(runId, node, upstream, context, endProgress);
                        return;
                    }
                    var output = executeNode(run, runId, node, upstream, context, startProgress, endProgress);
                    context.put(node.id(), output);
                    finishNode(runId, node.id(), "SUCCEEDED", output, "");
                    completed++;
                    events.publish(runId, "NODE_COMPLETED", node.id(), node.name(), "SUCCEEDED", endProgress,
                            node.name() + "已完成，用时 " + elapsed(nodeStartedAt), "");
                } catch (Exception exception) {
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
                var refs = knowledge.currentRefs(file.id(), 24);
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
            case SPREADSHEET_TRANSFORM -> transformSpreadsheet(config, upstream);
            case REF_SEARCH -> searchRefs(run.projectId(), config);
            case AI_ANALYSIS -> analyze(run.projectId(), runId, node, config, upstream, context,
                    startProgress, endProgress);
            case REVIEW -> throw new IllegalStateException("复核步骤应由运行器暂停处理");
            case DELIVERABLE -> createDeliverable(run.projectId(), runId, node, config, upstream, context,
                    startProgress, endProgress);
        };
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
        var items = refs.stream().map(ref -> Map.<String, Object>of("id", ref.id(), "sourceName", ref.sourceName(),
                "text", ref.text(), "location", ref.location())).toList();
        return Map.of("count", items.size(), "refs", items, "refIds", refs.stream().map(ref -> ref.id()).toList());
    }

    private Map<String, Object> analyze(String projectId, String runId, NodeDefinition node, Map<String, Object> config,
                                        Map<String, Map<String, Object>> upstream,
                                        Map<String, Map<String, Object>> context,
                                        int startProgress, int endProgress) {
        var prompt = text(config, "prompt");
        var sourceText = collectText(upstream);
        var refIds = collectRefIds(context);
        if (sourceText.isBlank()) {
            var refs = knowledge.search(projectId, prompt, 8);
            sourceText = refs.stream().map(ref -> ref.text()).reduce("", (left, right) -> left + "\n" + right).trim();
            refIds = refs.stream().map(ref -> ref.id()).toList();
        }
        if (sourceText.isBlank()) sourceText = prompt;
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
        var maxPoints = integer(config, "maxPoints", 6);
        var points = Arrays.stream(analysis.split("\\R"))
                .map(String::trim).filter(line -> !line.isBlank()).limit(maxPoints).toList();
        if (points.isEmpty() && !analysis.isBlank()) points = List.of(analysis);
        return Map.of("analysis", analysis, "points", points, "refIds", refIds,
                "analysisMode", Objects.toString(result.get("mode"), ""));
    }

    private Map<String, Object> createDeliverable(String projectId, String runId, NodeDefinition node,
                                                  Map<String, Object> config,
                                                  Map<String, Map<String, Object>> upstream,
                                                  Map<String, Map<String, Object>> context,
                                                  int startProgress, int endProgress) {
        var body = optional(config, "body", findString(upstream, "analysis"));
        if (body.isBlank()) body = collectText(upstream);
        var configuredFormat = text(config, "format");
        var format = configuredFormat.equalsIgnoreCase("MERMAID") && bool(config, "handDrawn")
                ? "EXCALIDRAW" : configuredFormat;
        var requirements = text(config, "generationPrompt");
        var pptSkill = List.of("PPTX", "HTML_SLIDES").contains(format.toUpperCase(Locale.ROOT))
                ? optional(config, "pptSkill", "") : "";
        var includeCitations = !config.containsKey("includeCitations") || bool(config, "includeCitations");
        var citationStyle = optional(config, "citationStyle", "IEEE").toUpperCase(Locale.ROOT);
        var skillRequirement = "guizang-huawei-style-c".equals(pptSkill)
                ? "\nPPT 技能：华为企业汇报 Style C。采用结论先行的管理层叙事，避免模板化堆框。"
                  + "每页只表达一个判断；标题必须单行且不超过22个汉字，摘要不超过48个汉字，"
                  + "每页2至3条要点、每条不超过42个汉字；禁止手工换行、长段落和重复措辞。"
                  + "有可靠连续数据、分类比较或构成数据时生成原生图表，并用一句话解释其业务含义。"
                : "frontend-slides".equals(pptSkill)
                ? "\n演示技能：Frontend Slides 网页演示。产物是 HTML + JavaScript，不是 PowerPoint 文件。"
                  + "内容应适合浏览器全屏演示，强调视觉层级、页面节奏、图表证据与版式变化；"
                  + "每页只表达一个判断，标题不超过22个汉字，每页2至3条要点，有可靠数值时优先使用图表。"
                : "";
        var citationRequirement = citationRequirement(includeCitations, citationStyle);
        var completeRequirements = "使用对象：%s\n期望篇幅或规模：%s\n%s%s\n%s\n"
                + "图表规则：仅使用输入中存在且口径明确的数值，不得估算或编造。";
        completeRequirements = completeRequirements.formatted(
                optional(config, "targetAudience", "业务用户"), optional(config, "lengthHint", "适中"),
                requirements, skillRequirement, citationRequirement);
        var fullContext = collectText(context);
        var referenceCatalog = collectReferenceCatalog(context);
        var generationSource = body;
        if (!fullContext.isBlank() && !fullContext.equals(body)) {
            generationSource += "\n\n--- 上游数据与资料 ---\n" + fullContext;
        }
        if (includeCitations && !referenceCatalog.isBlank()) {
            generationSource += "\n\n--- 可用参考来源（正文必须使用对应编号） ---\n" + referenceCatalog;
        }
        var span = Math.max(1, endProgress - startProgress);
        var generation = worker.generateContentStreaming(format, completeRequirements, generationSource, event -> {
            var type = Objects.toString(event.get("type"), "status");
            var modelProgress = event.get("progress") instanceof Number number ? number.intValue() : 50;
            var progress = startProgress + Math.max(1, (int) Math.floor(span * modelProgress / 100.0));
            if ("content".equals(type)) {
                events.publish(runId, "MODEL_OUTPUT", node.id(), node.name(), "RUNNING", progress,
                        "正在生成成果内容", Objects.toString(event.get("content"), ""));
            } else if (!"complete".equals(type)) {
                events.publish(runId, "MODEL_STATUS", node.id(), node.name(), "RUNNING", progress,
                        Objects.toString(event.get("message"), "正在生成成果"), "");
            }
        });
        var generatedBody = Objects.toString(generation.get("content"), body);
        var refIds = includeCitations ? collectRefIds(context) : List.<String>of();
        var section = new SectionRequest(optional(config, "heading", "分析结果"), List.of(generatedBody),
                stringList(findValue(upstream, "points")), refIds,
                includeCitations ? collectCitations(context) : List.of());
        events.publish(runId, "STEP_PROGRESS", node.id(), node.name(), "RUNNING", Math.max(startProgress, endProgress - 1),
                "正在写入 " + format + " 文件", "");
        var item = deliverables.create(new CreateRequest(projectId, optional(config, "outputResourceId", null), text(config, "title"),
                optional(config, "subtitle", "由工作流自动生成"), format, pptSkill,
                includeCitations, citationStyle, List.of(section)));
        return Map.of("deliverableId", item.id(), "name", item.name(), "format", item.format(),
                "version", item.currentVersion(), "downloadUrl", "/api/deliverables/" + item.id() + "/download",
                "analysisMode", Objects.toString(generation.get("mode"), "local-extractive"));
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
                rs.getString("trace_id"), nodes, instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private NodeRunResponse mapNodeRun(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRunResponse(rs.getString("id"), rs.getString("node_id"), rs.getString("node_name"),
                NodeType.valueOf(rs.getString("node_type")), rs.getInt("step_order"), rs.getString("status"),
                readMap(rs.getString("input_json")), readMap(rs.getString("output_json")), rs.getString("error_message"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
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

    private String collectText(Map<String, Map<String, Object>> values) {
        var result = new StringBuilder();
        for (var output : values.values()) {
            for (var key : List.of("analysis", "summary", "text")) {
                if (output.get(key) != null) result.append(output.get(key)).append('\n');
            }
            for (var item : list(output.get("refs"))) {
                if (item instanceof Map<?, ?> map && map.get("text") != null) result.append(map.get("text")).append('\n');
            }
            var url = Objects.toString(output.get("url"), "");
            if (!url.isBlank()) result.append("网站资料：").append(Objects.toString(output.get("title"), url))
                    .append(" ").append(url).append('\n');
            var fileId = Objects.toString(output.get("fileId"), "");
            if (!fileId.isBlank()) appendFilePreview(result, knowledge.filePath(fileId,
                    output.get("version") instanceof Number number ? number.intValue() : null));
            var extractId = Objects.toString(output.get("extractJobId"), "");
            if (!extractId.isBlank()) appendFilePreview(result, extracts.outputPath(extractId));
        }
        return result.toString().trim();
    }

    private String collectReferenceCatalog(Map<String, Map<String, Object>> context) {
        var byId = new LinkedHashMap<String, Map<?, ?>>();
        for (var output : context.values()) {
            for (var item : list(output.get("refs"))) {
                if (item instanceof Map<?, ?> map) {
                    var id = Objects.toString(map.get("id"), "");
                    if (!id.isBlank()) byId.putIfAbsent(id, map);
                }
            }
        }
        var catalog = new StringBuilder();
        var ids = collectRefIds(context);
        for (var index = 0; index < ids.size(); index++) {
            var ref = byId.get(ids.get(index));
            if (ref == null) continue;
            catalog.append("[Ref ").append(index + 1).append("] ")
                    .append(Objects.toString(ref.get("sourceName"), "项目资料"));
            var location = ref.get("location");
            if (location != null) catalog.append("，位置：").append(location);
            var text = Objects.toString(ref.get("text"), "").replaceAll("\\s+", " ").trim();
            if (!text.isBlank()) catalog.append("，摘录：").append(text, 0, Math.min(text.length(), 500));
            catalog.append('\n');
        }
        return catalog.toString().trim();
    }

    private void appendFilePreview(StringBuilder result, Path path) {
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(300_000);
            if (bytes.length > 0) {
                result.append("\n--- 结构化数据预览 ---\n")
                        .append(new String(bytes, StandardCharsets.UTF_8));
                if (input.read() >= 0) result.append("\n[数据较大，以上为前 300KB 预览]");
                result.append('\n');
            }
        } catch (Exception ignored) {
            // Non-text files remain available to dedicated file-processing steps.
        }
    }

    private List<String> collectRefIds(Map<String, Map<String, Object>> context) {
        var ids = new LinkedHashSet<String>();
        context.values().forEach(output -> stringList(output.get("refIds")).forEach(ids::add));
        return List.copyOf(ids);
    }

    private Map<String, Object> linkInput(NodeDefinition node, Map<String, Object> config) {
        var url = text(config, "url");
        var title = optional(config, "title", node.name());
        var id = "link:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
        var ref = Map.<String, Object>of("id", id, "resourceId", "", "version", 0,
                "sourceName", title, "text", "网页资料：" + title,
                "location", Map.of("url", url), "contentHash", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());
        return Map.of("url", url, "title", title, "refs", List.of(ref), "refIds", List.of(id));
    }

    private Map<String, Object> refMap(com.finflow.studio.knowledge.KnowledgeModels.RefResponse ref) {
        return Map.of("id", ref.id(), "resourceId", ref.resourceId(), "version", ref.version(),
                "sourceName", ref.sourceName(), "text", ref.text(), "location", ref.location(),
                "contentHash", ref.contentHash());
    }

    private List<CitationRequest> collectCitations(Map<String, Map<String, Object>> context) {
        var result = new LinkedHashMap<String, CitationRequest>();
        for (var output : context.values()) {
            for (var item : list(output.get("refs"))) {
                if (!(item instanceof Map<?, ?> ref)) continue;
                var id = Objects.toString(ref.get("id"), "");
                if (id.isBlank()) continue;
                var location = new LinkedHashMap<String, Object>();
                if (ref.get("location") instanceof Map<?, ?> rawLocation) {
                    rawLocation.forEach((key, value) -> location.put(Objects.toString(key), value));
                }
                result.putIfAbsent(id, new CitationRequest(id, Objects.toString(ref.get("resourceId"), ""),
                        ref.get("version") instanceof Number number ? number.intValue() : 0,
                        Objects.toString(ref.get("sourceName"), "未命名资料"), Objects.toString(ref.get("text"), ""),
                        location, Objects.toString(ref.get("contentHash"), "")));
            }
        }
        return List.copyOf(result.values());
    }

    private String citationRequirement(boolean include, String style) {
        if (!include) {
            return "来源标注：关闭。正文、图表和页脚中不要输出 [Ref N]、来源编号或参考文献。";
        }
        return switch (style) {
            case "APA_7" -> "来源标注：开启，使用 APA 第 7 版。正文采用（机构或作者, 年份）格式；缺少年份时使用 n.d.；图表 source_ref 使用相同格式；末尾生成按作者排序的参考文献。";
            case "GB_T_7714" -> "来源标注：开启，使用 GB/T 7714-2015 顺序编码制。正文和图表使用 [1]、[2] 编号；末尾生成对应编号的参考文献。";
            default -> "来源标注：开启，使用 IEEE 顺序编码制。正文和图表使用 [1]、[2] 编号；末尾生成对应编号的参考文献。";
        };
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
