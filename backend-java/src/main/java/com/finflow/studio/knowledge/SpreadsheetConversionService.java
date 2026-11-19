package com.finflow.studio.knowledge;

import com.finflow.studio.storage.BlobStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class SpreadsheetConversionService {
    private final JdbcClient jdbc;
    private final KnowledgeService knowledge;
    private final BlobStore blobStore;
    private final TaskExecutor executor;
    private final long maxOutputBytes;
    private final Semaphore conversionSlots;
    private final SpreadsheetCsvConverter converter = new SpreadsheetCsvConverter();

    public SpreadsheetConversionService(JdbcClient jdbc, KnowledgeService knowledge, BlobStore blobStore,
                                        @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                        @Value("${finflow.transform.max-output-bytes:107374182400}") long maxOutputBytes,
                                        @Value("${finflow.spreadsheet.max-concurrent-conversions:2}") int maxConcurrentConversions) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.blobStore = blobStore;
        this.executor = executor;
        this.maxOutputBytes = maxOutputBytes;
        this.conversionSlots = new Semaphore(Math.max(1, Math.min(maxConcurrentConversions, 8)));
    }

    public ConversionStatus start(String resourceId, int requestedRowsPerFile) {
        var resource = knowledge.get(resourceId);
        ensureExcel(resource.name());
        var rowsPerFile = Math.max(10_000, Math.min(requestedRowsPerFile, 1_000_000));
        var existing = find(resourceId);
        if (existing != null && existing.versionNumber() == resource.currentVersion()
                && ("QUEUED".equals(existing.status()) || "RUNNING".equals(existing.status()) || "SUCCEEDED".equals(existing.status()))) {
            return existing;
        }
        jdbc.sql("delete from spreadsheet_conversion_job where resource_id = :id").param("id", resourceId).update();
        jdbc.sql("""
                insert into spreadsheet_conversion_job(resource_id, version_number, status, rows_per_file,
                    processed_rows, part_count, error_message, updated_at)
                values (:id, :version, 'QUEUED', :rowsPerFile, 0, 0, '', :now)
                """).param("id", resourceId).param("version", resource.currentVersion())
                .param("rowsPerFile", rowsPerFile).param("now", Instant.now()).update();
        executor.execute(() -> runQueued(resourceId, resource.currentVersion(), resource.projectId(), resource.name(), rowsPerFile));
        return require(resourceId);
    }

    @PostConstruct
    void resumeInterruptedJobs() {
        var jobs = jdbc.sql("""
                select j.resource_id, j.version_number, j.rows_per_file, r.project_id, r.name, r.current_version
                from spreadsheet_conversion_job j join file_resource r on r.id = j.resource_id
                where j.status in ('QUEUED', 'RUNNING')
                """).query((rs, row) -> new PendingJob(rs.getString("resource_id"),
                        rs.getInt("version_number"), rs.getInt("rows_per_file"), rs.getString("project_id"),
                        rs.getString("name"), rs.getInt("current_version"))).list();
        for (var job : jobs) {
            if (job.versionNumber() != job.currentVersion()) {
                fail(job.resourceId(), job.versionNumber(), "文件已有新版本，请重新发起转换");
                continue;
            }
            jdbc.sql("update spreadsheet_conversion_job set status = 'QUEUED', updated_at = :now where resource_id = :id")
                    .param("now", Instant.now()).param("id", job.resourceId()).update();
            executor.execute(() -> runQueued(job.resourceId(), job.versionNumber(), job.projectId(),
                    job.name(), job.rowsPerFile()));
        }
    }

    public ConversionStatus status(String resourceId) {
        knowledge.get(resourceId);
        var status = find(resourceId);
        return status == null ? new ConversionStatus(resourceId, 0, "NOT_STARTED", 0, 0, 0, "", List.of()) : status;
    }

    public List<java.nio.file.Path> convertedPaths(String resourceId) {
        var status = require(resourceId);
        if (!"SUCCEEDED".equals(status.status())) return List.of();
        return jdbc.sql("""
                select storage_path from spreadsheet_conversion_part
                where resource_id = :id and version_number = :version order by sequence_number
                """).param("id", resourceId).param("version", status.versionNumber())
                .query(String.class).list().stream().map(blobStore::materialize).toList();
    }

    public java.nio.file.Path convertedPath(String resourceId, int sequenceNumber) {
        var status = require(resourceId);
        if (!"SUCCEEDED".equals(status.status())) {
            throw new IllegalArgumentException("Excel 还没有完成转换");
        }
        if (sequenceNumber < 1) throw new IllegalArgumentException("转换分片编号无效");
        var location = jdbc.sql("""
                select storage_path from spreadsheet_conversion_part
                where resource_id = :id and version_number = :version and sequence_number = :sequence
                """).param("id", resourceId).param("version", status.versionNumber())
                .param("sequence", sequenceNumber).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("转换分片不存在，请重新转换"));
        return blobStore.materialize(location);
    }

    private void runQueued(String resourceId, int version, String projectId, String sourceName, int rowsPerFile) {
        var acquired = false;
        try {
            conversionSlots.acquire();
            acquired = true;
            convert(resourceId, version, projectId, sourceName, rowsPerFile);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(resourceId, version, "转换任务已中断");
        } finally {
            if (acquired) conversionSlots.release();
        }
    }

    private void convert(String resourceId, int version, String projectId, String sourceName, int rowsPerFile) {
        jdbc.sql("""
                update spreadsheet_conversion_job set status = 'RUNNING', started_at = :now, updated_at = :now,
                    processed_rows = 0, part_count = 0, error_message = ''
                where resource_id = :id and version_number = :version
                """).param("now", Instant.now()).param("id", resourceId).param("version", version).update();
        var newLocations = new java.util.ArrayList<String>();
        try (var result = converter.convert(knowledge.filePath(resourceId, version), sourceName, rowsPerFile,
                rows -> updateProgress(resourceId, version, rows))) {
            var oldLocations = jdbc.sql("select storage_path from spreadsheet_conversion_part where resource_id = :id")
                    .param("id", resourceId).query(String.class).list();
            jdbc.sql("delete from spreadsheet_conversion_part where resource_id = :id").param("id", resourceId).update();
            var sequence = 0;
            long totalRows = 0;
            for (var part : result.parts()) {
                sequence++;
                totalRows += part.rowCount();
                BlobStore.StoredObject stored;
                try (var input = Files.newInputStream(part.path())) {
                    stored = blobStore.put(projectId + "/knowledge/" + resourceId + "/derived/v" + version + "/" + part.name(),
                            input, maxOutputBytes);
                }
                newLocations.add(stored.location());
                jdbc.sql("""
                        insert into spreadsheet_conversion_part(id, resource_id, version_number, sequence_number,
                            sheet_name, part_number, row_count, name, storage_path, size_bytes, checksum, created_at)
                        values (:partId, :resourceId, :version, :sequence, :sheet, :partNumber, :rows, :name,
                            :path, :size, :checksum, :now)
                        """).param("partId", UUID.randomUUID().toString()).param("resourceId", resourceId)
                        .param("version", version).param("sequence", sequence).param("sheet", part.sheetName())
                        .param("partNumber", part.partNumber()).param("rows", part.rowCount()).param("name", part.name())
                        .param("path", stored.location()).param("size", stored.size()).param("checksum", stored.checksum())
                        .param("now", Instant.now()).update();
            }
            jdbc.sql("""
                    update spreadsheet_conversion_job set status = 'SUCCEEDED', processed_rows = :rows,
                        part_count = :parts, finished_at = :now, updated_at = :now
                    where resource_id = :id and version_number = :version
                    """).param("rows", totalRows).param("parts", sequence).param("now", Instant.now())
                    .param("id", resourceId).param("version", version).update();
            oldLocations.forEach(this::deleteQuietly);
        } catch (Exception exception) {
            newLocations.forEach(this::deleteQuietly);
            jdbc.sql("delete from spreadsheet_conversion_part where resource_id = :id and version_number = :version")
                    .param("id", resourceId).param("version", version).update();
            fail(resourceId, version, readableMessage(exception));
        }
    }

    private void fail(String resourceId, int version, String message) {
        jdbc.sql("""
                update spreadsheet_conversion_job set status = 'FAILED', error_message = :message,
                    finished_at = :now, updated_at = :now
                where resource_id = :id and version_number = :version
                """).param("message", message).param("now", Instant.now()).param("id", resourceId)
                .param("version", version).update();
    }

    private void updateProgress(String resourceId, int version, long rows) {
        jdbc.sql("""
                update spreadsheet_conversion_job set processed_rows = :rows, updated_at = :now
                where resource_id = :id and version_number = :version and status = 'RUNNING'
                """).param("rows", rows).param("now", Instant.now()).param("id", resourceId)
                .param("version", version).update();
    }

    private ConversionStatus find(String resourceId) {
        return jdbc.sql("select * from spreadsheet_conversion_job where resource_id = :id")
                .param("id", resourceId).query((rs, row) -> new ConversionStatus(resourceId,
                        rs.getInt("version_number"), rs.getString("status"), rs.getInt("rows_per_file"),
                        rs.getLong("processed_rows"), rs.getInt("part_count"), rs.getString("error_message"),
                        parts(resourceId, rs.getInt("version_number")))).optional().orElse(null);
    }

    private ConversionStatus require(String resourceId) {
        var result = find(resourceId);
        if (result == null) throw new IllegalArgumentException("转换任务不存在");
        return result;
    }

    private List<ConversionPart> parts(String resourceId, int version) {
        return jdbc.sql("""
                select * from spreadsheet_conversion_part where resource_id = :id and version_number = :version
                order by sequence_number
                """).param("id", resourceId).param("version", version).query((rs, row) -> new ConversionPart(
                        rs.getInt("sequence_number"), rs.getString("name"), rs.getString("sheet_name"), rs.getInt("part_number"),
                        rs.getLong("row_count"), rs.getLong("size_bytes"))).list();
    }

    private void ensureExcel(String name) {
        var lower = name.toLowerCase();
        if (!(lower.endsWith(".xlsx") || lower.endsWith(".xlsm"))) {
            throw new IllegalArgumentException("只有 XLSX 或 XLSM 工作簿可以转换为 CSV");
        }
    }

    private String readableMessage(Exception exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? "Excel 转换失败，请确认文件完整且未加密" : message.substring(0, Math.min(1900, message.length()));
    }

    private void deleteQuietly(String location) {
        try { blobStore.delete(location); } catch (Exception ignored) { }
    }

    public record ConversionPart(int sequenceNumber, String name, String sheetName, int partNumber,
                                 long rowCount, long sizeBytes) { }
    public record ConversionStatus(String sourceId, int versionNumber, String status, int rowsPerFile,
                                   long processedRows, int partCount, String errorMessage,
                                   List<ConversionPart> parts) { }
    private record PendingJob(String resourceId, int versionNumber, int rowsPerFile, String projectId,
                              String name, int currentVersion) { }
}
