package com.finflow.studio.data;

import com.finflow.studio.data.DataModels.*;
import com.finflow.studio.project.ProjectService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class DataConnectionService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;
    private final SecretResolver secrets;
    private final ReadOnlySqlValidator sqlValidator;

    public DataConnectionService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                                 SecretResolver secrets, ReadOnlySqlValidator sqlValidator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.secrets = secrets;
        this.sqlValidator = sqlValidator;
        DriverManager.setLoginTimeout(15);
    }

    public ConnectionResponse create(CreateConnectionRequest request) {
        projects.get(request.projectId());
        validateUrl(request.sourceType(), request.jdbcUrl());
        if (request.secretRef() != null && !request.secretRef().isBlank() && !request.secretRef().startsWith("env:")) {
            throw new IllegalArgumentException("只保存 env:变量名形式的密钥引用");
        }
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into data_connection(id, project_id, name, source_type, jdbc_url, username,
                    secret_ref, options_json, status, created_at, updated_at)
                values (:id, :projectId, :name, :type, :url, :username, :secretRef, :options,
                    'NOT_TESTED', :createdAt, :updatedAt)
                """)
                .param("id", id)
                .param("projectId", request.projectId())
                .param("name", request.name().trim())
                .param("type", request.sourceType().name())
                .param("url", request.jdbcUrl().trim())
                .param("username", safe(request.username()))
                .param("secretRef", safe(request.secretRef()))
                .param("options", writeJson(request.options() == null ? Map.of() : request.options()))
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return safeForDisplay(get(id));
    }

    public List<ConnectionResponse> list(String projectId) {
        projects.get(projectId);
        return jdbc.sql("select * from data_connection where project_id = :projectId order by created_at desc")
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    @Transactional
    public ConnectionResponse update(String id, UpdateConnectionRequest request) {
        var current = get(id);
        validateUrl(request.sourceType(), request.jdbcUrl());
        var secretRef = safe(request.secretRef());
        if (secretRef.isBlank() || secretRef.equals("已配置")) secretRef = current.secretRef();
        if (!secretRef.isBlank() && !secretRef.startsWith("env:")) {
            throw new IllegalArgumentException("只保存 env:变量名形式的密钥引用");
        }
        var options = new LinkedHashMap<String, String>();
        if (request.options() != null) options.putAll(request.options());
        options.replaceAll((key, value) -> isSensitiveOption(key) && "已配置".equals(value)
                ? current.options().getOrDefault(key, "") : value);
        var url = request.jdbcUrl().contains("***") && sanitizeUrl(current.jdbcUrl()).equals(request.jdbcUrl())
                ? current.jdbcUrl() : request.jdbcUrl().trim();
        jdbc.sql("""
                update data_connection set name = :name, source_type = :type, jdbc_url = :url,
                    username = :username, secret_ref = :secretRef, options_json = :options,
                    status = 'NOT_TESTED', last_test_message = '', last_tested_at = null, updated_at = :updatedAt
                where id = :id
                """)
                .param("name", request.name().trim())
                .param("type", request.sourceType().name())
                .param("url", url)
                .param("username", safe(request.username()))
                .param("secretRef", secretRef)
                .param("options", writeJson(options))
                .param("updatedAt", Instant.now())
                .param("id", id)
                .update();
        return safeForDisplay(get(id));
    }

    public ConnectionResponse get(String id) {
        return jdbc.sql("select * from data_connection where id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("数据连接不存在"));
    }

    public ConnectionResponse safeForDisplay(ConnectionResponse definition) {
        var displayOptions = new LinkedHashMap<String, String>();
        definition.options().forEach((key, value) ->
                displayOptions.put(key, isSensitiveOption(key) ? "已配置" : value));
        return new ConnectionResponse(definition.id(), definition.projectId(), definition.name(),
                definition.sourceType(), sanitizeUrl(definition.jdbcUrl()), definition.username(),
                definition.secretRef().isBlank() ? "" : "已配置", Map.copyOf(displayOptions),
                definition.status(), definition.lastTestMessage(), definition.lastTestedAt(),
                definition.createdAt(), definition.updatedAt());
    }

    public TestConnectionResponse test(String id) {
        var definition = get(id);
        var started = Instant.now();
        if (definition.sourceType() == SourceType.HTTP_API) return testHttp(definition, started);
        try (var connection = open(definition)) {
            var metadata = connection.getMetaData();
            var latency = Duration.between(started, Instant.now()).toMillis();
            var response = new TestConnectionResponse(true, metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(), latency, "连接成功");
            updateTest(id, "READY", response.message());
            return response;
        } catch (Exception exception) {
            var message = sanitize(exception.getMessage());
            updateTest(id, "FAILED", message);
            return new TestConnectionResponse(false, "", "", Duration.between(started, Instant.now()).toMillis(), message);
        }
    }

    public ConnectionPreviewResponse preview(String id, PreviewConnectionRequest request) {
        var definition = get(id);
        var limit = request.limit() == null ? 100 : Math.max(1, Math.min(request.limit(), 200));
        return definition.sourceType() == SourceType.HTTP_API
                ? previewHttp(definition, limit)
                : previewDatabase(definition, request.query(), limit);
    }

    public DatabaseCatalogResponse catalog(String id) {
        var definition = get(id);
        if (definition.sourceType() == SourceType.HTTP_API) {
            throw new IllegalArgumentException("数据服务没有数据表目录");
        }
        var groups = new LinkedHashMap<String, List<DatabaseTableResponse>>();
        var technicalNames = new LinkedHashMap<String, String>();
        var truncated = false;
        var count = 0;
        try (var connection = open(definition)) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData();
            var quote = metadata.getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) quote = "\"";
            try (var tables = metadata.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (tables.next()) {
                    var catalog = safe(tables.getString("TABLE_CAT"));
                    var schema = safe(tables.getString("TABLE_SCHEM"));
                    var table = tables.getString("TABLE_NAME");
                    if (isSystemSchema(catalog, schema) || table == null || table.isBlank()) continue;
                    if (count >= 10_000) { truncated = true; break; }
                    var technical = !schema.isBlank() ? schema : !catalog.isBlank() ? catalog : "default";
                    var key = catalog + "\u0000" + schema;
                    technicalNames.putIfAbsent(key, technical);
                    var qualified = qualify(quote, catalog, schema, table, definition.sourceType());
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new DatabaseTableResponse(
                            catalog, schema, table, safe(tables.getString("REMARKS")),
                            tables.getString("TABLE_TYPE"), "SELECT * FROM " + qualified));
                    count++;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalArgumentException("数据目录读取失败：" + sanitize(exception.getMessage()), exception);
        }
        var schemas = new ArrayList<DatabaseSchemaResponse>();
        groups.forEach((key, tables) -> {
            tables.sort(java.util.Comparator.comparing(DatabaseTableResponse::name, String.CASE_INSENSITIVE_ORDER));
            var technical = technicalNames.get(key);
            schemas.add(new DatabaseSchemaResponse(businessSchemaName(technical), technical, List.copyOf(tables)));
        });
        schemas.sort(java.util.Comparator.comparing(DatabaseSchemaResponse::technicalName, String.CASE_INSENSITIVE_ORDER));
        return new DatabaseCatalogResponse(List.copyOf(schemas), count, truncated);
    }

    private String qualify(String quote, String catalog, String schema, String table, SourceType type) {
        var parts = new ArrayList<String>();
        if (type == SourceType.MYSQL && !catalog.isBlank()) parts.add(identifier(quote, catalog));
        else if (!schema.isBlank()) parts.add(identifier(quote, schema));
        parts.add(identifier(quote, table));
        return String.join(".", parts);
    }

    private String identifier(String quote, String value) {
        return quote + value.replace(quote, quote + quote) + quote;
    }

    private boolean isSystemSchema(String catalog, String schema) {
        var value = (!schema.isBlank() ? schema : catalog).toLowerCase();
        return value.equals("information_schema") || value.equals("pg_catalog") || value.equals("pg_toast")
                || value.equals("mysql") || value.equals("performance_schema") || value.equals("sys");
    }

    private String businessSchemaName(String technicalName) {
        if (technicalName.equalsIgnoreCase("public") || technicalName.equalsIgnoreCase("default")) return "主要数据";
        return technicalName.replace('_', ' ');
    }

    private ConnectionPreviewResponse previewDatabase(ConnectionResponse definition, String query, int limit) {
        var sql = sqlValidator.validate(query);
        try (var connection = open(definition)) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(limit + 1);
                statement.setFetchSize(Math.min(limit + 1, 200));
                statement.setQueryTimeout(20);
                try (var result = statement.executeQuery()) {
                    var metadata = result.getMetaData();
                    var columns = new ArrayList<String>(metadata.getColumnCount());
                    for (var index = 1; index <= metadata.getColumnCount(); index++) {
                        columns.add(metadata.getColumnLabel(index));
                    }
                    var rows = new ArrayList<List<String>>(limit);
                    var truncated = false;
                    while (result.next()) {
                        if (rows.size() == limit) { truncated = true; break; }
                        var row = new ArrayList<String>(columns.size());
                        for (var index = 1; index <= columns.size(); index++) row.add(cell(result.getObject(index)));
                        rows.add(row);
                    }
                    return new ConnectionPreviewResponse(columns, rows, rows.size(), truncated, "DATABASE");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalArgumentException("预览查询没有完成：" + sanitize(exception.getMessage()), exception);
        }
    }

    private ConnectionPreviewResponse previewHttp(ConnectionResponse definition, int limit) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(definition.jdbcUrl())).timeout(Duration.ofSeconds(20))
                    .header("Accept", "text/csv, application/json, application/x-ndjson, application/jsonl");
            applyHttpMethod(builder, definition);
            var token = resolveSecret(definition);
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            definition.options().forEach((key, value) -> {
                if (key.startsWith("header.") && key.length() > 7 && !key.toLowerCase().contains("authorization")) {
                    builder.header(key.substring(7), value);
                }
            });
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalArgumentException("数据服务返回 HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var input = response.body()) {
                bytes = input.readNBytes(5 * 1024 * 1024 + 1);
            }
            if (bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("预览响应超过 5 MB，请缩小接口返回范围");
            var contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
            if (contentType.contains("csv") || definition.options().getOrDefault("format", "").equalsIgnoreCase("csv")) {
                return previewCsv(bytes, limit);
            }
            return previewJson(bytes, contentType.contains("ndjson") || contentType.contains("jsonl"), limit);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("数据服务预览失败：" + sanitize(exception.getMessage()), exception);
        }
    }

    private ConnectionPreviewResponse previewCsv(byte[] bytes, int limit) throws Exception {
        var format = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get();
        try (var parser = CSVParser.builder().setInputStream(new ByteArrayInputStream(bytes))
                .setCharset(StandardCharsets.UTF_8).setFormat(format).get()) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) return new ConnectionPreviewResponse(List.of(), List.of(), 0, false, "HTTP_API");
            var columns = new ArrayList<String>();
            iterator.next().forEach(value -> columns.add(value.replace("\ufeff", "")));
            var rows = new ArrayList<List<String>>(limit);
            var truncated = false;
            while (iterator.hasNext()) {
                var record = iterator.next();
                if (rows.size() == limit) { truncated = true; break; }
                var row = new ArrayList<String>(columns.size());
                record.forEach(value -> row.add(cell(value)));
                while (row.size() < columns.size()) row.add("");
                rows.add(row);
            }
            return new ConnectionPreviewResponse(columns, rows, rows.size(), truncated, "HTTP_API");
        }
    }

    private ConnectionPreviewResponse previewJson(byte[] bytes, boolean jsonLines, int limit) throws Exception {
        var records = new ArrayList<Object>();
        if (jsonLines) {
            for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) records.add(objectMapper.readValue(line, Object.class));
                if (records.size() > limit) break;
            }
        } else {
            var parsed = objectMapper.readValue(bytes, Object.class);
            if (parsed instanceof List<?> list) records.addAll(list);
            else if (parsed instanceof Map<?, ?> map) {
                var nested = firstRecordList(map);
                if (nested != null) records.addAll(nested); else records.add(map);
            } else records.add(parsed);
        }
        var truncated = records.size() > limit;
        var visible = records.subList(0, Math.min(records.size(), limit));
        var columns = new LinkedHashSet<String>();
        visible.forEach(record -> { if (record instanceof Map<?, ?> map) map.keySet().forEach(key -> columns.add(String.valueOf(key))); else columns.add("value"); });
        var columnList = new ArrayList<>(columns);
        var rows = new ArrayList<List<String>>(visible.size());
        for (var record : visible) {
            var values = new LinkedHashMap<String, Object>();
            if (record instanceof Map<?, ?> map) map.forEach((key, value) -> values.put(String.valueOf(key), value));
            else values.put("value", record);
            var row = new ArrayList<String>(columnList.size());
            columnList.forEach(column -> row.add(cell(values.get(column))));
            rows.add(row);
        }
        return new ConnectionPreviewResponse(columnList, rows, rows.size(), truncated, "HTTP_API");
    }

    private List<?> firstRecordList(Map<?, ?> map) {
        for (var key : List.of("data", "items", "results", "records", "rows")) {
            if (map.get(key) instanceof List<?> list) return list;
        }
        return null;
    }

    private String cell(Object value) {
        if (value == null) return "";
        var text = value instanceof Map<?, ?> || value instanceof List<?> ? writeJson(value) : String.valueOf(value);
        return text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
    }

    @Transactional
    public void delete(String id) {
        get(id);
        var jobs = jdbc.sql("select count(*) from extract_job where connection_id = :id")
                .param("id", id).query(Long.class).single();
        if (jobs > 0) throw new IllegalStateException("请先删除这个连接产生的 CSV 数据");
        jdbc.sql("delete from data_connection where id = :id").param("id", id).update();
    }

    public Connection open(ConnectionResponse definition) throws SQLException {
        if (definition.sourceType() == SourceType.HTTP_API) {
            throw new IllegalArgumentException("HTTP 数据服务不使用 JDBC 连接");
        }
        validateUrl(definition.sourceType(), definition.jdbcUrl());
        var properties = new Properties();
        if (!definition.username().isBlank()) {
            properties.setProperty("user", definition.username());
        }
        var password = secrets.resolve(definition.secretRef());
        if (!password.isBlank()) {
            properties.setProperty("password", password);
        }
        definition.options().forEach((key, value) -> {
            if (key.matches("[a-zA-Z][a-zA-Z0-9._-]{0,63}") && !isSensitiveOption(key)) {
                properties.setProperty(key, value);
            }
        });
        return DriverManager.getConnection(definition.jdbcUrl(), properties);
    }

    private void validateUrl(SourceType type, String url) {
        var matches = type == SourceType.HTTP_API
                ? url != null && (url.startsWith("https://") || url.startsWith("http://"))
                : url != null && url.startsWith(type.urlPrefix());
        if (!matches) {
            throw new IllegalArgumentException("连接地址与数据源类型不匹配，需要以 " + type.urlPrefix() + " 开头");
        }
        if (url.contains("\n") || url.contains("\r") || url.length() > 2000) {
            throw new IllegalArgumentException("连接地址格式不正确");
        }
    }

    String resolveSecret(ConnectionResponse definition) {
        return secrets.resolve(definition.secretRef());
    }

    private TestConnectionResponse testHttp(ConnectionResponse definition, Instant started) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(definition.jdbcUrl())).timeout(Duration.ofSeconds(15));
            applyHttpMethod(builder, definition);
            var token = resolveSecret(definition);
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
                    .build().send(builder.build(), HttpResponse.BodyHandlers.discarding());
            var success = response.statusCode() >= 200 && response.statusCode() < 300;
            var message = success ? "数据服务连接成功" : "数据服务返回 HTTP " + response.statusCode();
            updateTest(definition.id(), success ? "READY" : "FAILED", message);
            return new TestConnectionResponse(success, "HTTP API", "HTTP/" + response.version(),
                    Duration.between(started, Instant.now()).toMillis(), message);
        } catch (Exception exception) {
            var message = sanitize(exception.getMessage());
            updateTest(definition.id(), "FAILED", message);
            return new TestConnectionResponse(false, "HTTP API", "", Duration.between(started, Instant.now()).toMillis(), message);
        }
    }

    private void applyHttpMethod(HttpRequest.Builder builder, ConnectionResponse definition) {
        var method = definition.options().getOrDefault("method", "GET").trim().toUpperCase();
        if (method.equals("GET")) {
            builder.GET();
            return;
        }
        if (method.equals("POST")) {
            builder.header("Content-Type", definition.options().getOrDefault("contentType", "application/json"));
            builder.POST(HttpRequest.BodyPublishers.ofString(definition.options().getOrDefault("body", ""), StandardCharsets.UTF_8));
            return;
        }
        throw new IllegalArgumentException("目前数据服务仅支持 GET 或 POST 请求");
    }

    private boolean isSensitiveOption(String key) {
        var lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("token") || lower.contains("key");
    }

    private void updateTest(String id, String status, String message) {
        jdbc.sql("update data_connection set status = :status, last_test_message = :message, last_tested_at = :now, updated_at = :now where id = :id")
                .param("status", status).param("message", message).param("now", Instant.now()).param("id", id).update();
    }

    private ConnectionResponse map(java.sql.ResultSet rs, int rowNum) throws SQLException {
        return new ConnectionResponse(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                SourceType.valueOf(rs.getString("source_type")), rs.getString("jdbc_url"), rs.getString("username"),
                rs.getString("secret_ref"), readMap(rs.getString("options_json")), rs.getString("status"),
                rs.getString("last_test_message"), instant(rs, "last_tested_at"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("连接选项无法保存", exception);
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            return Map.of();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "连接失败，请检查地址、账号和网络";
        }
        var clean = message.replaceAll("(?i)(password|pwd|token|secret)=[^&;\\s]+", "$1=***");
        return clean.substring(0, Math.min(clean.length(), 900));
    }

    private String sanitizeUrl(String url) {
        return url.replaceAll("(?i)([?&](?:password|pwd|token|secret|api[_-]?key)=)[^&]+", "$1***");
    }
}
