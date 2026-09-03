package com.finflow.studio.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkflowModels {
    private WorkflowModels() { }

    public enum NodeType {
        RESOURCE,
        ACQUIRE,
        PROCESS,
        AGENT_TASK,
        TOOL,
        CONTROL,
        SUB_WORKFLOW,
        OUTPUT,
        FILE_INPUT,
        LINK_INPUT,
        DATASET_INPUT,
        DATA_EXTRACT,
        DATA_TRANSFORM,
        SPREADSHEET_TRANSFORM,
        REF_SEARCH,
        AI_ANALYSIS,
        REVIEW,
        DELIVERABLE
    }

    public enum ExecutionMode { MANUAL, SCHEDULED }

    public enum ScheduleFrequency { HOURLY, DAILY, WEEKLY, MONTHLY }

    public record ScheduleDefinition(
            @NotNull ScheduleFrequency frequency,
            @Size(max = 5) String time,
            Integer dayOfWeek,
            Integer dayOfMonth,
            @Size(max = 80) String timezone) { }

    public record NodeDefinition(
            @NotBlank @Size(max = 100) String id,
            @NotNull NodeType type,
            @NotBlank @Size(max = 300) String name,
            double x,
            double y,
            Map<String, Object> config) { }

    public record EdgeDefinition(
            @NotBlank @Size(max = 100) String id,
            @NotBlank @Size(max = 100) String source,
            @NotBlank @Size(max = 100) String target) { }

    public record SaveRequest(
            @NotBlank @Size(max = 300) String name,
            @Size(max = 2000) String description,
            @NotNull @Size(max = 100) List<@Valid NodeDefinition> nodes,
            @Size(max = 300) List<@Valid EdgeDefinition> edges,
            ExecutionMode executionMode,
            @Valid ScheduleDefinition schedule,
            Integer expectedVersion) {
        public SaveRequest(String name, String description, List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
            this(name, description, nodes, edges, ExecutionMode.MANUAL, null, null);
        }

        public SaveRequest(String name, String description, List<NodeDefinition> nodes, List<EdgeDefinition> edges,
                           Integer expectedVersion) {
            this(name, description, nodes, edges, ExecutionMode.MANUAL, null, expectedVersion);
        }
    }

    public record WorkflowResponse(
            String id,
            String projectId,
            String name,
            String description,
            String status,
            int currentVersion,
            List<NodeDefinition> nodes,
            List<EdgeDefinition> edges,
            ExecutionMode executionMode,
            ScheduleDefinition schedule,
            Instant nextRunAt,
            Instant createdAt,
            Instant updatedAt) { }

    public record ValidationIssue(String nodeId, String message) { }

    public record ValidationResponse(boolean valid, List<ValidationIssue> issues, List<String> executionOrder) { }

    public record NodeRunResponse(
            String id,
            String nodeId,
            String nodeName,
            NodeType nodeType,
            int stepOrder,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            String errorMessage,
            List<ActivityRunResponse> activities,
            Instant startedAt,
            Instant finishedAt) { }

    public record ActivityRunResponse(
            String id,
            String runId,
            String nodeRunId,
            int order,
            String type,
            String capability,
            String title,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) { }

    public record LineageEdgeResponse(
            String id,
            String runId,
            String nodeRunId,
            String sourceKind,
            String sourceRef,
            Integer sourceVersion,
            String targetKind,
            String targetRef,
            Integer targetVersion,
            String relation,
            Map<String, Object> details,
            Instant createdAt) { }

    public record RunResponse(
            String id,
            String workflowId,
            String projectId,
            int workflowVersion,
            String retryOfRunId,
            String triggerType,
            String status,
            String currentNodeId,
            Map<String, Object> output,
            String errorMessage,
            String traceId,
            List<NodeRunResponse> nodes,
            List<LineageEdgeResponse> lineage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) { }

    public record ReviewRequest(
            @Size(max = 4000) String comment,
            @Size(max = 100_000) String adjustedContent) { }

    public record WorkflowDocument(String name, String description,
                                   List<NodeDefinition> nodes, List<EdgeDefinition> edges,
                                   ExecutionMode executionMode, ScheduleDefinition schedule) {
        public WorkflowDocument(String name, String description, List<NodeDefinition> nodes,
                                List<EdgeDefinition> edges) {
            this(name, description, nodes, edges, ExecutionMode.MANUAL, null);
        }
    }

    public record PatchOperation(
            @NotBlank String op,
            String nodeId,
            String edgeId,
            NodeDefinition node,
            EdgeDefinition edge,
            Map<String, Object> patch) { }

    public record WorkflowPatch(
            int baseRevision,
            @NotBlank String summary,
            @NotNull List<@Valid PatchOperation> operations,
            List<String> missingInputs,
            List<String> assumptions,
            List<String> expectedOutputs) { }

    public record PatchPreview(WorkflowDocument document, ValidationResponse validation,
                               List<String> changes, int baseRevision) { }

    public record TemplateRequest(@NotBlank @Size(max = 300) String name,
                                  @Size(max = 2000) String description,
                                  @Size(max = 100) String category,
                                  @NotNull @Valid WorkflowDocument definition) { }

    public record TemplateResponse(String id, String name, String description, String category,
                                   WorkflowDocument definition, boolean builtIn,
                                   Instant createdAt, Instant updatedAt) { }
}
