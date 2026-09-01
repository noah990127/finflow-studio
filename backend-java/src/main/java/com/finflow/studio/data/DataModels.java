package com.finflow.studio.data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DataModels {
    private DataModels() {
    }

    public enum SourceType {
        POSTGRESQL("jdbc:postgresql:"),
        MYSQL("jdbc:mysql:"),
        OPENGAUSS("jdbc:opengauss:"),
        GAUSS_DWS("jdbc:postgresql:"),
        DUCKDB("jdbc:duckdb:"),
        HTTP_API("http");

        private final String urlPrefix;

        SourceType(String urlPrefix) {
            this.urlPrefix = urlPrefix;
        }

        public String urlPrefix() {
            return urlPrefix;
        }
    }

    public record CreateConnectionRequest(
            @NotBlank String projectId,
            @NotBlank String name,
            @NotNull SourceType sourceType,
            @NotBlank String jdbcUrl,
            String username,
            String secretRef,
            Map<String, String> options) {
    }

    public record UpdateConnectionRequest(
            @NotBlank String name,
            @NotNull SourceType sourceType,
            @NotBlank String jdbcUrl,
            String username,
            String secretRef,
            Map<String, String> options) {
    }

    public record ConnectionResponse(
            String id,
            String projectId,
            String name,
            SourceType sourceType,
            String jdbcUrl,
            String username,
            String secretRef,
            Map<String, String> options,
            String status,
            String lastTestMessage,
            Instant lastTestedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TestConnectionResponse(boolean success, String databaseProduct, String databaseVersion,
                                         long latencyMs, String message) {
    }

    public record PreviewConnectionRequest(String query, @Min(1) @Max(200) Integer limit) {
    }

    public record ConnectionPreviewResponse(List<String> columns, List<List<String>> rows,
                                            int rowCount, boolean truncated, String source) {
    }

    public record DatabaseTableResponse(String catalog, String schema, String name, String description,
                                        String tableType, String previewQuery) {
    }

    public record DatabaseSchemaResponse(String name, String technicalName, List<DatabaseTableResponse> tables) {
    }

    public record DatabaseCatalogResponse(List<DatabaseSchemaResponse> schemas, int tableCount,
                                          boolean truncated) {
    }

    public record CreateExtractRequest(
            @NotBlank String projectId,
            @NotBlank String connectionId,
            @NotBlank String name,
            @NotBlank String sql,
            @Min(100) @Max(100_000) Integer fetchSize,
            String outputName) {
    }

    public record ExtractJobResponse(
            String id,
            String projectId,
            String connectionId,
            String name,
            String status,
            int fetchSize,
            long rowCount,
            long byteCount,
            String outputName,
            String checksum,
            String errorMessage,
            String traceId,
            Instant heartbeatAt,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {
    }
}
