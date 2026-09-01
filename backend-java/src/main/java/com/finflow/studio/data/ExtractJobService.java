package com.finflow.studio.data;

import com.finflow.studio.data.DataModels.*;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.storage.BlobStore;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class ExtractJobService {
    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final DataConnectionService connections;
    private final ReadOnlySqlValidator sqlValidator;
    private final TaskExecutor taskExecutor;
    private final Path storageRoot;
    private final BlobStore blobStore;
    private final long maxOutputBytes;
    private final int defaultFetchSize;
    private final int progressInterval;
    private final int queryTimeoutSeconds;
    private final ObjectMapper objectMapper;

    public ExtractJobService(JdbcClient jdbc, ProjectService projects, DataConnectionService connections,
                             ReadOnlySqlValidator sqlValidator,
                             @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                             BlobStore blobStore,
                             @Value("${finflow.storage.root}") String storageRoot,
                             @Value("${finflow.extract.max-output-bytes:107374182400}") long maxOutputBytes,
                             @Value("${finflow.extract.fetch-size}") int defaultFetchSize,
                             @Value("${finflow.extract.progress-interval}") int progressInterval,
                             @Value("${finflow.extract.query-timeout-seconds}") int queryTimeoutSeconds,
                             ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.connections = connections;
        this.sqlValidator = sqlValidator;
        this.taskExecutor = taskExecutor;
        this.blobStore = blobStore;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxOutputBytes = maxOutputBytes;
        this.defaultFetchSize = defaultFetchSize;
        this.progressInterval = progressInterval;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.objectMapper = objectMapper;
    }

    public ExtractJobResponse create(CreateExtractRequest request) {
        projects.get(request.projectId());
        var connection = connections.get(request.connectionId());
        if (!connection.projectId().equals(request.projectId())) {
            throw new IllegalArgumentException("数据连接不属于当前项目");
        }
        var sql = connection.sourceType() == SourceType.HTTP_API ? validateHttpOperation(request.sql()) : sqlValidator.validate(request.sql());
        var id = UUID.randomUUID().toString();
        var traceId = UUID.randomUUID().toString();
        var outputName = safeOutputName(request.outputName(), request.name(), id);
        var fetchSize = request.fetchSize() == null ? defaultFetchSize : request.fetchSize();
        jdbc.sql("""
                insert into extract_job(id, project_id, connection_id, name, sql_text, sql_fingerprint,
                    status, fetch_size, output_name, trace_id, heartbeat_at, created_at)
                values (:id, :projectId, :connectionId, :name, :sql, :fingerprint, 'QUEUED',
                    :fetchSize, :outputName, :traceId, :now, :now)
                """)
                .param("id", id).param("projectId", request.projectId()).param("connectionId", request.connectionId())
                .param("name", request.name().trim()).param("sql", sql).param("fingerprint", sha256(sql))
                .param("fetchSize", fetchSize).param("outputName", outputName).param("traceId", traceId)
                .param("now", Instant.now()).update();
        scheduleAfterCommit(id);
        return get(id);
    }

    public ExtractJobResponse get(String id) {
        return jdbc.sql("select * from extract_job where id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("抽取任务不存在"));
    }

    public List<ExtractJobResponse> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql("select * from extract_job where project_id = :projectId order by created_at desc")
                .param("projectId", projectId).query(this::map).list();
    }

    public ExtractJobResponse cancel(String id) {
        var job = get(id);
        if (List.of("SUCCEEDED", "FAILED", "CANCELED").contains(job.status())) {
            return job;
        }
        jdbc.sql("update extract_job set status = 'CANCEL_REQUESTED', heartbeat_at = :now where id = :id")
                .param("now", Instant.now()).param("id", id).update();
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        var job = get(id);
        if (List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(job.status())) {
            throw new IllegalStateException("数据仍在采集中，请先停止后再删除");
        }
        var location = jdbc.sql("select output_path from extract_job where id = :id")
                .param("id", id).query(String.class).optional().orElse("");
        jdbc.sql("delete from office_working_copy where source_kind = 'extract-jobs' and source_id = :id")
                .param("id", id).update();
        jdbc.sql("delete from extract_job where id = :id").param("id", id).update();
        if (!location.isBlank()) afterCommit(() -> blobStore.delete(location));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    public Path outputPath(String id) {
        var location = jdbc.sql("select output_path from extract_job where id = :id and status = 'SUCCEEDED'")
                .param("id", id).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("抽取文件尚未生成"));
        return blobStore.materialize(location);
    }

    private void execute(String id) {
        var startedAt = Instant.now();
        jdbc.sql("update extract_job set status = 'RUNNING', started_at = :now, heartbeat_at = :now where id = :id and status = 'QUEUED'")
                .param("now", startedAt).param("id", id).update();
        var source = loadSource(id);
        Path temp = null;
        try {
            var directory = storageRoot.resolve(source.projectId()).resolve("extracts").normalize();
            if (!directory.startsWith(storageRoot)) {
                throw new IllegalStateException("输出目录不合法");
            }
            Files.createDirectories(directory);
            temp = directory.resolve(id + ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            long rows;
            long bytes;
            var definition = connections.get(source.connectionId());
            if (definition.sourceType() == SourceType.HTTP_API) {
                var result = executeHttp(id, definition, temp, digest);
                rows = result.rows();
                bytes = result.bytes();
            } else try (var connection = connections.open(definition)) {
                configureReadOnly(connection);
                try (var statement = connection.prepareStatement(source.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    statement.setFetchSize(source.fetchSize());
                    statement.setQueryTimeout(queryTimeoutSeconds);
                    try (var resultSet = statement.executeQuery();
                         var file = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                         var counted = new CountingOutputStream(new BufferedOutputStream(new DigestOutputStream(file, digest), 1024 * 1024));
                         var writer = new OutputStreamWriter(counted, StandardCharsets.UTF_8);
                         var printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
                        var metadata = resultSet.getMetaData();
                        var columnCount = metadata.getColumnCount();
                        var headers = new String[columnCount];
                        for (var column = 1; column <= columnCount; column++) {
                            headers[column - 1] = metadata.getColumnLabel(column);
                        }
                        printer.printRecord((Object[]) headers);
                        rows = 0;
                        while (resultSet.next()) {
                            if (rows % progressInterval == 0 && cancelRequested(id)) {
                                statement.cancel();
                                throw new CancellationException("任务已取消");
                            }
                            for (var column = 1; column <= columnCount; column++) {
                                printer.print(resultSet.getObject(column));
                            }
                            printer.println();
                            rows++;
                            if (rows % progressInterval == 0) {
                                printer.flush();
                                progress(id, rows, counted.count());
                            }
                        }
                        printer.flush();
                        bytes = counted.count();
                    }
                }
            }
            BlobStore.StoredObject stored;
            try (var input = Files.newInputStream(temp)) {
                stored = blobStore.put(source.projectId() + "/extracts/" + id + "/" + source.outputName(), input, maxOutputBytes);
            }
            deleteQuietly(temp);
            jdbc.sql("""
                    update extract_job set status = 'SUCCEEDED', row_count = :rows, byte_count = :bytes,
                        output_path = :path, checksum = :checksum, heartbeat_at = :now, finished_at = :now
                    where id = :id
                    """).param("rows", rows).param("bytes", stored.size()).param("path", stored.location())
                    .param("checksum", stored.checksum())
                    .param("now", Instant.now()).param("id", id).update();
        } catch (CancellationException exception) {
            deleteQuietly(temp);
            finishFailure(id, "CANCELED", "任务已取消");
        } catch (Exception exception) {
            deleteQuietly(temp);
            finishFailure(id, "FAILED", sanitize(exception.getMessage()));
        }
    }

    private HttpResult executeHttp(String id, ConnectionResponse definition, Path temp, MessageDigest digest) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(definition.jdbcUrl()))
                .timeout(Duration.ofSeconds(queryTimeoutSeconds)).GET()
                .header("Accept", "text/csv, application/x-ndjson, application/jsonl");
        var token = connections.resolveSecret(definition);
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        definition.options().forEach((key, value) -> {
            if (key.startsWith("header.") && key.length() > 7 && !key.toLowerCase().contains("authorization")) {
                builder.header(key.substring(7), value);
            }
        });
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL)
                .build().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("数据服务返回 HTTP " + response.statusCode());
        }
        var contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        try (var input = response.body();
             var file = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             var counted = new CountingOutputStream(new BufferedOutputStream(new DigestOutputStream(file, digest), 1024 * 1024))) {
            if (contentType.contains("csv") || definition.options().getOrDefault("format", "").equalsIgnoreCase("csv")) {
                var buffer = new byte[1024 * 1024];
                long rows = -1;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (cancelRequested(id)) throw new CancellationException("任务已取消");
                    counted.write(buffer, 0, read);
                    for (var index = 0; index < read; index++) if (buffer[index] == '\n') rows++;
                    progress(id, Math.max(0, rows), counted.count());
                }
                counted.flush();
                return new HttpResult(Math.max(0, rows), counted.count());
            }
            if (!(contentType.contains("ndjson") || contentType.contains("jsonl") ||
                    definition.options().getOrDefault("format", "").equalsIgnoreCase("jsonl"))) {
                throw new IllegalArgumentException("HTTP 数据服务目前支持 CSV 或 JSONL 流式响应");
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                 var writer = new OutputStreamWriter(counted, StandardCharsets.UTF_8);
                 var printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
                String line;
                long rows = 0;
                List<String> headers = null;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    if (rows % progressInterval == 0 && cancelRequested(id)) throw new CancellationException("任务已取消");
                    Map<String, Object> row = readJsonLine(line);
                    if (headers == null) {
                        headers = List.copyOf(row.keySet());
                        printer.printRecord(headers);
                    }
                    for (var header : headers) printer.print(row.get(header));
                    printer.println();
                    rows++;
                    if (rows % progressInterval == 0) { printer.flush(); progress(id, rows, counted.count()); }
                }
                printer.flush();
                return new HttpResult(rows, counted.count());
            }
        }
    }

    private Map<String, Object> readJsonLine(String line) {
        try { return objectMapper.readValue(line, new TypeReference<>() {}); }
        catch (JacksonException exception) { throw new IllegalArgumentException("JSONL 数据行格式不正确", exception); }
    }

    private String validateHttpOperation(String value) {
        if (!"GET".equalsIgnoreCase(value == null ? "" : value.trim())) {
            throw new IllegalArgumentException("HTTP 数据服务当前只允许 GET 读取");
        }
        return "GET";
    }

    private void configureReadOnly(java.sql.Connection connection) throws java.sql.SQLException {
        try {
            connection.setReadOnly(true);
        } catch (java.sql.SQLFeatureNotSupportedException ignored) {
        }
        try {
            connection.setAutoCommit(false);
        } catch (java.sql.SQLException ignored) {
        }
    }

    private boolean cancelRequested(String id) {
        return "CANCEL_REQUESTED".equals(jdbc.sql("select status from extract_job where id = :id")
                .param("id", id).query(String.class).single());
    }

    private void progress(String id, long rows, long bytes) {
        jdbc.sql("update extract_job set row_count = :rows, byte_count = :bytes, heartbeat_at = :now where id = :id")
                .param("rows", rows).param("bytes", bytes).param("now", Instant.now()).param("id", id).update();
    }

    private void finishFailure(String id, String status, String message) {
        jdbc.sql("update extract_job set status = :status, error_message = :message, heartbeat_at = :now, finished_at = :now where id = :id")
                .param("status", status).param("message", message).param("now", Instant.now()).param("id", id).update();
    }

    private SourceRow loadSource(String id) {
        return jdbc.sql("select project_id, connection_id, sql_text, fetch_size, output_name from extract_job where id = :id")
                .param("id", id).query((rs, rowNum) -> new SourceRow(rs.getString("project_id"), rs.getString("connection_id"),
                        rs.getString("sql_text"), rs.getInt("fetch_size"), rs.getString("output_name"))).single();
    }

    private ExtractJobResponse map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExtractJobResponse(rs.getString("id"), rs.getString("project_id"), rs.getString("connection_id"),
                rs.getString("name"), rs.getString("status"), rs.getInt("fetch_size"), rs.getLong("row_count"),
                rs.getLong("byte_count"), rs.getString("output_name"), rs.getString("checksum"),
                rs.getString("error_message"), rs.getString("trace_id"), instant(rs, "heartbeat_at"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void scheduleAfterCommit(String id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { taskExecutor.execute(() -> execute(id)); }
            });
        } else {
            taskExecutor.execute(() -> execute(id));
        }
    }

    private String safeOutputName(String requested, String name, String id) {
        var candidate = requested == null || requested.isBlank() ? name + "-" + id.substring(0, 8) + ".csv" : requested;
        candidate = candidate.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
        if (!candidate.toLowerCase().endsWith(".csv")) candidate += ".csv";
        if (candidate.length() > 240 || candidate.equals(".csv")) throw new IllegalArgumentException("输出文件名不正确");
        return candidate;
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) return "抽取失败，请检查查询和数据源状态";
        var clean = message.replaceAll("(?i)(password|pwd|token|secret)=[^&;\\s]+", "$1=***");
        return clean.substring(0, Math.min(clean.length(), 1800));
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private record SourceRow(String projectId, String connectionId, String sql, int fetchSize, String outputName) { }
    private record HttpResult(long rows, long bytes) { }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;
        private CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); count += length; }
        long count() { return count; }
    }
}
