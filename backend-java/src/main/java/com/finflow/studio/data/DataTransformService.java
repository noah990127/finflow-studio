package com.finflow.studio.data;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.knowledge.KnowledgeModels.FileResourceResponse;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.worker.WorkerClient.DataTransformInput;
import org.springframework.stereotype.Service;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

import com.finflow.studio.data.DataModels.PreviewConnectionRequest;

@Service
public class DataTransformService {
    private final ProjectService projects;
    private final KnowledgeService knowledge;
    private final ExtractJobService extracts;
    private final DataConnectionService connections;
    private final ReadOnlySqlValidator sqlValidator;
    private final WorkerClient worker;
    private final ObjectMapper objectMapper;

    public DataTransformService(ProjectService projects, KnowledgeService knowledge, ExtractJobService extracts,
                                DataConnectionService connections, ReadOnlySqlValidator sqlValidator,
                                WorkerClient worker, ObjectMapper objectMapper) {
        this.projects = projects;
        this.knowledge = knowledge;
        this.extracts = extracts;
        this.connections = connections;
        this.sqlValidator = sqlValidator;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    public record SourceRequest(String sourceKind, String resourceId, String alias, String name,
                                String query, String sheetName) { }
    public record GenerateRequest(String requirements, List<SourceRequest> inputs) { }
    public record SampleRequest(String script, Map<String, Object> plan, List<SourceRequest> inputs) { }
    public record ExecutionResult(FileResourceResponse resource, Map<String, Object> sampleReport,
                                  Map<String, Object> qualityReport) { }

    public Map<String, Object> generate(String projectId, GenerateRequest request) {
        projects.get(projectId);
        if (request.requirements() == null || request.requirements().isBlank()) {
            throw new IllegalArgumentException("请填写数据加工要求");
        }
        var sources = requiredInputs(request.inputs());
        var profiles = new ArrayList<Map<String, Object>>(sources.size());
        var actor = ActorContext.current();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = sources.stream()
                    .map(source -> CompletableFuture.supplyAsync(() -> {
                        try (var ignored = ActorContext.bind(actor)) {
                            return profile(projectId, source);
                        }
                    }, executor))
                    .toList();
            for (var task : tasks) profiles.add(task.join());
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("无法读取输入数据结构", exception.getCause());
        }
        return worker.generateDataTransform(Map.of("requirements", request.requirements().trim(), "inputs", profiles));
    }

    public Map<String, Object> sample(String projectId, SampleRequest request) {
        projects.get(projectId);
        var resolved = resolveSources(projectId, requiredInputs(request.inputs()));
        try {
            var planJson = request.plan() == null || request.plan().isEmpty() ? "" : writeJson(request.plan());
            var script = Objects.toString(request.script(), "").trim();
            if (planJson.isBlank()) script = required(script, "加工脚本");
            return worker.sampleDataTransform(resolved.inputs(), writeJson(resolved.metadata()), script, planJson);
        } finally {
            resolved.temporaryPaths().forEach(this::deleteQuietly);
        }
    }

    public ExecutionResult execute(String projectId, Map<String, Object> config,
                                   Map<String, Map<String, Object>> upstream) {
        projects.get(projectId);
        if (upstream.isEmpty()) throw new IllegalArgumentException("数据加工步骤至少需要连接一个数据输入");
        var aliases = stringMap(config.get("inputAliases"));
        var sheetNames = stringMap(config.get("sheetNames"));
        var inputs = new ArrayList<DataTransformInput>();
        var metadata = new ArrayList<Map<String, Object>>();
        var usedAliases = new HashSet<String>();
        var index = 0;
        for (var entry : upstream.entrySet()) {
            index++;
            var output = entry.getValue();
            Path path;
            String name;
            if (output.get("fileId") != null) {
                var fileId = Objects.toString(output.get("fileId"));
                var file = knowledge.get(fileId);
                if (!file.projectId().equals(projectId)) throw new IllegalArgumentException("输入文件不属于当前项目");
                var version = output.get("version") instanceof Number number ? number.intValue() : null;
                path = knowledge.filePath(fileId, version);
                name = file.name();
            } else if (output.get("extractJobId") != null) {
                var extractId = Objects.toString(output.get("extractJobId"));
                var extract = extracts.get(extractId);
                if (!extract.projectId().equals(projectId)) throw new IllegalArgumentException("输入数据不属于当前项目");
                path = extracts.outputPath(extractId);
                name = extract.outputName();
            } else {
                throw new IllegalArgumentException("上游步骤“" + entry.getKey() + "”没有产生可加工的数据文件");
            }
            var alias = normalizeAlias(aliases.getOrDefault(entry.getKey(), "data_" + index));
            if (!usedAliases.add(alias.toLowerCase())) throw new IllegalArgumentException("数据别名不能重复：" + alias);
            var sheetName = sheetNames.getOrDefault(entry.getKey(), "");
            inputs.add(new DataTransformInput(alias, name, sheetName, path));
            var sourceKind = output.get("sourceKind") == null
                    ? (output.get("extractJobId") == null ? "FILE" : "EXTRACT")
                    : Objects.toString(output.get("sourceKind"));
            metadata.add(metadata(alias, name, sheetName, sourceKind));
        }
        var plan = map(config.get("transformPlan"));
        var planJson = plan.isEmpty() ? "" : writeJson(plan);
        var script = Objects.toString(config.get("script"), "").trim();
        if (planJson.isBlank()) script = required(script, "加工脚本");
        var outputFormat = Objects.toString(config.getOrDefault("outputFormat",
                planJson.isBlank() ? "csv" : "parquet"), "parquet").toLowerCase(Locale.ROOT);
        if (!Set.of("parquet", "csv").contains(outputFormat)) throw new IllegalArgumentException("输出格式只支持 Parquet 或 CSV");
        var sampleReport = worker.sampleDataTransform(inputs, writeJson(metadata), script, planJson);
        Path zip = null;
        Path result = null;
        try {
            zip = Files.createTempFile("finflow-data-transform-", ".zip");
            var extension = outputFormat.equals("csv") ? ".csv" : ".parquet";
            result = Files.createTempFile("finflow-data-result-", extension);
            worker.runDataTransform(inputs, writeJson(metadata), script, planJson, outputFormat, zip);
            Map<String, Object> quality;
            try (var archive = new ZipFile(zip.toFile())) {
                var resultEntry = archive.getEntry("result" + extension);
                var qualityEntry = archive.getEntry("quality.json");
                var manifestEntry = archive.getEntry("manifest.json");
                if (resultEntry == null || qualityEntry == null || manifestEntry == null) {
                    throw new IllegalStateException("加工结果包不完整");
                }
                try (var input = archive.getInputStream(resultEntry); var output = Files.newOutputStream(result)) {
                    input.transferTo(output);
                }
                try (var input = archive.getInputStream(qualityEntry)) {
                    quality = objectMapper.readValue(input, new TypeReference<>() { });
                }
            }
            var defaultName = "数据加工结果" + extension;
            var outputName = Objects.toString(config.getOrDefault("outputName", defaultName), defaultName);
            outputName = outputName.replaceFirst("(?i)\\.(csv|parquet)$", "") + extension;
            var mediaType = outputFormat.equals("csv") ? "text/csv" : "application/vnd.apache.parquet";
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("数据处理已中止");
            var resource = knowledge.importDataFile(projectId, outputName, mediaType, result);
            return new ExecutionResult(resource, sampleReport, quality);
        } catch (IOException exception) {
            throw new IllegalStateException("加工结果无法保存", exception);
        } finally {
            deleteQuietly(zip);
            deleteQuietly(result);
        }
    }

    private Map<String, Object> profile(String projectId, SourceRequest source) {
        var alias = normalizeAlias(source.alias());
        var kind = required(source.sourceKind(), "输入类型").toUpperCase(Locale.ROOT);
        Map<String, Object> profile;
        switch (kind) {
            case "FILE" -> {
                var file = knowledge.get(required(source.resourceId(), "文件"));
                if (!file.projectId().equals(projectId)) throw new IllegalArgumentException("输入文件不属于当前项目");
                profile = worker.profileData(knowledge.filePath(file.id(), null), file.name(), source.sheetName());
            }
            case "EXTRACT" -> {
                var extract = extracts.get(required(source.resourceId(), "采集数据"));
                if (!extract.projectId().equals(projectId)) throw new IllegalArgumentException("输入数据不属于当前项目");
                profile = worker.profileData(extracts.outputPath(extract.id()), extract.outputName(), source.sheetName());
            }
            case "CONNECTION" -> profile = profileQuery(projectId, source);
            default -> throw new IllegalArgumentException("暂不支持的数据输入类型：" + kind);
        }
        var result = new LinkedHashMap<String, Object>(profile);
        result.put("alias", alias);
        result.put("name", source.name() == null || source.name().isBlank()
                ? Objects.toString(profile.getOrDefault("name", alias)) : source.name());
        return result;
    }

    private Map<String, Object> profileQuery(String projectId, SourceRequest source) {
        var definition = connections.get(required(source.resourceId(), "数据连接"));
        if (!definition.projectId().equals(projectId)) throw new IllegalArgumentException("数据连接不属于当前项目");
        if (definition.sourceType() == DataModels.SourceType.HTTP_API) {
            var preview = connections.preview(definition.id(), new PreviewConnectionRequest(source.query(), 20));
            var rows = new ArrayList<Map<String, Object>>();
            for (var values : preview.rows()) {
                var row = new LinkedHashMap<String, Object>();
                for (var index = 0; index < preview.columns().size(); index++) {
                    row.put(preview.columns().get(index), index < values.size() ? values.get(index) : null);
                }
                rows.add(row);
            }
            var columns = preview.columns().stream()
                    .map(column -> Map.<String, Object>of("name", column, "data_type", "VARCHAR", "nullable", true))
                    .toList();
            return Map.of("name", source.name() == null ? definition.name() : source.name(), "columns", columns,
                    "sample_rows", rows, "estimated_rows", preview.rowCount());
        }
        var sql = sqlValidator.validate(required(source.query(), "只读查询"));
        try (var connection = connections.open(definition); var statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(1);
            ResultSetMetaData metadata = statement.getMetaData();
            if (metadata == null) {
                try (var rows = statement.executeQuery()) { metadata = rows.getMetaData(); }
            }
            var columns = new ArrayList<Map<String, Object>>();
            for (var column = 1; column <= metadata.getColumnCount(); column++) {
                columns.add(Map.of("name", metadata.getColumnLabel(column), "data_type", metadata.getColumnTypeName(column),
                        "nullable", metadata.isNullable(column) != ResultSetMetaData.columnNoNulls));
            }
            return Map.of("name", source.name() == null ? definition.name() : source.name(), "columns", columns,
                    "sample_rows", List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取查询结果字段：" + Objects.toString(exception.getMessage(), "连接失败"), exception);
        }
    }

    private ResolvedSources resolveSources(String projectId, List<SourceRequest> sources) {
        var inputs = new ArrayList<DataTransformInput>();
        var metadata = new ArrayList<Map<String, Object>>();
        var temporaryPaths = new ArrayList<Path>();
        var aliases = new HashSet<String>();
        try {
            for (var source : sources) {
                var alias = normalizeAlias(source.alias());
                if (!aliases.add(alias.toLowerCase())) throw new IllegalArgumentException("数据别名不能重复：" + alias);
                var kind = required(source.sourceKind(), "输入类型").toUpperCase(Locale.ROOT);
                Path path;
                String name;
                if (kind.equals("FILE")) {
                    var file = knowledge.get(required(source.resourceId(), "文件"));
                    if (!file.projectId().equals(projectId)) throw new IllegalArgumentException("输入文件不属于当前项目");
                    path = knowledge.filePath(file.id(), null);
                    name = file.name();
                } else if (kind.equals("EXTRACT")) {
                    var extract = extracts.get(required(source.resourceId(), "采集数据"));
                    if (!extract.projectId().equals(projectId)) throw new IllegalArgumentException("输入数据不属于当前项目");
                    path = extracts.outputPath(extract.id());
                    name = extract.outputName();
                } else if (kind.equals("CONNECTION")) {
                    var definition = connections.get(required(source.resourceId(), "数据连接"));
                    if (!definition.projectId().equals(projectId)) throw new IllegalArgumentException("数据连接不属于当前项目");
                    var preview = connections.preview(definition.id(), new PreviewConnectionRequest(source.query(), 100));
                    if (preview.columns().isEmpty()) throw new IllegalStateException("数据连接没有返回可预览的字段");
                    path = writePreviewCsv(alias, preview.columns(), preview.rows());
                    temporaryPaths.add(path);
                    name = definition.name() + "_预览.csv";
                    kind = definition.sourceType().name() + "_PREVIEW";
                } else throw new IllegalArgumentException("暂不支持的数据输入类型：" + kind);
                inputs.add(new DataTransformInput(alias, name, source.sheetName(), path));
                metadata.add(metadata(alias, name, source.sheetName(), kind));
            }
        } catch (RuntimeException exception) {
            temporaryPaths.forEach(this::deleteQuietly);
            throw exception;
        }
        return new ResolvedSources(inputs, metadata, temporaryPaths);
    }

    private Path writePreviewCsv(String alias, List<String> columns, List<List<String>> rows) {
        try {
            var path = Files.createTempFile("finflow-duckdb-preview-" + alias + "-", ".csv");
            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                 var printer = new CSVPrinter(writer, CSVFormat.RFC4180.builder()
                         .setHeader(columns.toArray(String[]::new)).get())) {
                for (var row : rows) printer.printRecord(row);
            }
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("连接数据无法装入预览沙箱", exception);
        }
    }

    private Map<String, Object> metadata(String alias, String name, String sheetName) {
        return metadata(alias, name, sheetName, "WORKFLOW_INPUT");
    }

    private Map<String, Object> metadata(String alias, String name, String sheetName, String sourceKind) {
        return Map.of("alias", alias, "name", name, "sheet_name", sheetName == null ? "" : sheetName,
                "source_kind", sourceKind);
    }

    private List<SourceRequest> requiredInputs(List<SourceRequest> inputs) {
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("请至少连接一份结构化数据");
        if (inputs.size() > 20) throw new IllegalArgumentException("单个加工步骤最多连接 20 份数据");
        return inputs;
    }

    private String normalizeAlias(String value) {
        var alias = Objects.toString(value, "").trim();
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("数据别名需以字母或下划线开头，仅包含字母、数字和下划线");
        }
        return alias;
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请填写" + label);
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        var result = new LinkedHashMap<String, String>();
        raw.forEach((key, item) -> result.put(Objects.toString(key), Objects.toString(item, "")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, item) -> result.put(Objects.toString(key), item));
        return result;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("数据加工配置无法读取", exception); }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private record ResolvedSources(List<DataTransformInput> inputs, List<Map<String, Object>> metadata,
                                   List<Path> temporaryPaths) { }
}
