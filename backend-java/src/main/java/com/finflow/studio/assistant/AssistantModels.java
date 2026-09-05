package com.finflow.studio.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AssistantModels {

    private AssistantModels() {
    }

    public enum RiskLevel {
        READ_ONLY,
        DRAFT_ONLY,
        CREATE_VERSION,
        DESTRUCTIVE_OR_EXTERNAL;

        public boolean requiresConfirmation() {
            return this == CREATE_VERSION || this == DESTRUCTIVE_OR_EXTERNAL;
        }
    }

    public enum ExecutionPolicy {
        AUTO,
        APPROVAL;

        public static ExecutionPolicy from(String value) {
            return "AUTO".equalsIgnoreCase(value) ? AUTO : APPROVAL;
        }
    }

    public record CreateSessionRequest(String title) {
    }

    public record SessionResponse(
            String id,
            String projectId,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Selection(
            String type,
            String resourceId,
            List<String> range
    ) {
    }

    public record MessageRequest(
            @NotBlank String text,
            @NotBlank String page,
            String route,
            Selection selection,
            Integer clientContextVersion,
            String executionMode,
            @jakarta.validation.constraints.Size(max = 64) String requestId
    ) {
    }

    public record MessageHistoryItem(
            String id,
            String role,
            String content,
            String modelName,
            String traceId,
            Instant createdAt
    ) {
    }

    public record ContextSnapshot(
            String id,
            String projectId,
            String page,
            Selection selection,
            List<String> allowedResourceIds,
            Map<String, Integer> resourceVersions,
            String contextHash,
            Instant expiresAt
    ) {
    }

    public record PlanStep(
            String id,
            int order,
            String tool,
            String mode,
            String title,
            String description,
            Map<String, Object> arguments,
            RiskLevel risk,
            boolean requiresConfirmation,
            String status
    ) {
    }

    public record PlanResponse(
            String id,
            String sessionId,
            String goal,
            String summary,
            int version,
            String planHash,
            RiskLevel risk,
            String status,
            List<String> affectedResources,
            List<PlanStep> steps,
            Instant expiresAt
    ) {
    }

    public record MessageResponse(
            String sessionId,
            String assistantMessage,
            ContextSnapshot context,
            PlanResponse plan,
            RunResponse run
    ) {
    }

    public record ConfirmPlanRequest(
            int planVersion,
            @NotBlank String planHash,
            @NotBlank String idempotencyKey,
            @NotNull Map<String, Integer> expectedResourceVersions
    ) {
    }

    public record RunResponse(
            String id,
            String sessionId,
            String planId,
            String status,
            int currentStep,
            String resultSummary,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> result
    ) {
    }

    public record AssistantEvent(
            String eventId,
            long eventSeq,
            String sessionId,
            String runId,
            String type,
            Map<String, Object> payload,
            Instant createdAt
    ) {
    }

    public record ToolSearchRequest(String query) {
    }

    public record MemoryRequest(@NotBlank String scope, String projectId, @NotBlank String key,
                                @NotNull Map<String, Object> value, String sourceRef) { }

    public record MemoryResponse(String id, String actorId, String projectId, String scope, String key,
                                 Map<String, Object> value, String sourceRef, String status,
                                 Instant createdAt, Instant updatedAt) { }
}
