package com.finflow.studio.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class WorkerClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final WebClient client;

    public WorkerClient(@Value("${finflow.worker.base-url}") String baseUrl) {
        this.client = WebClient.builder().baseUrl(baseUrl).codecs(configurer ->
                configurer.defaultCodecs().maxInMemorySize(512 * 1024 * 1024)).build();
    }

    public Map<String, Object> health() {
        try {
            return client.get().uri("/health")
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .timeout(Duration.ofSeconds(2))
                    .block();
        } catch (RuntimeException ex) {
            return Map.of("status", "offline", "message", "Python 资料服务尚未启动");
        }
    }

    public ParsedDocument parse(Path path, String originalName) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path));
        parts.part("original_name", originalName);
        return client.post().uri("/v1/files/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(ParsedDocument.class)
                .timeout(Duration.ofMinutes(15))
                .block();
    }

    public Map<String, Object> preview(Path path, String originalName) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path));
        parts.part("original_name", originalName);
        var result = client.post().uri("/v1/files/preview")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(5))
                .block();
        if (result == null) throw new IllegalStateException("文件预览没有返回结果");
        return result;
    }

    public byte[] renderOfficePreview(Path path, String originalName) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path)).filename(originalName);
        parts.part("original_name", originalName);
        var result = client.post().uri("/v1/files/render-office-pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMinutes(3))
                .block();
        if (result == null || result.length == 0) throw new IllegalStateException("原样预览没有返回内容");
        return result;
    }

    public byte[] generateDeliverable(String format, Object request) {
        if (!List.of("pptx", "html_slides", "docx", "pdf", "mermaid", "excalidraw", "financial_report").contains(format)) {
            throw new IllegalArgumentException("不支持的输出格式");
        }
        var result = client.post().uri("/v1/deliverables/" + format)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMinutes(5))
                .block();
        if (result == null || result.length == 0) throw new IllegalStateException("输出文件生成失败");
        return result;
    }

    public List<Map<String, Object>> listPptSkills() {
        var result = client.get().uri("/v1/ppt-skills")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .timeout(Duration.ofSeconds(10))
                .block();
        return result == null ? List.of() : result;
    }

    public Map<String, Object> summarize(String text, String sourceName, int maxPoints) {
        var result = client.post().uri("/v1/knowledge/summarize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text, "source_name", sourceName, "max_points", maxPoints))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(5))
                .block();
        if (result == null) throw new IllegalStateException("分析服务没有返回结果");
        return result;
    }

    public Map<String, Object> discoverResearchSources(String topic, int maxSources) {
        var result = client.post().uri("/v1/research/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", topic, "max_sources", maxSources))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(5))
                .block();
        if (result == null) throw new IllegalStateException("资料搜索没有返回结果");
        return result;
    }

    public Map<String, Object> fetchResearchSource(String url) {
        var result = client.post().uri("/v1/research/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("url", url, "domain_allowlist", List.of()))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(workerError(response.statusCode().value(), body))))
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(40))
                .block();
        if (result == null) throw new IllegalStateException("网页资料没有返回内容");
        return result;
    }

    public Map<String, Object> planAgent(Object request) {
        var result = client.post().uri("/v1/agent/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(workerError(response.statusCode().value(), body))))
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(4))
                .block();
        if (result == null) throw new IllegalStateException("Agent 没有返回计划");
        return result;
    }

    public Map<String, Object> testAgentModel(Object request) {
        var result = client.post().uri("/v1/agent/model/test").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request).retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(35)).block();
        if (result == null) throw new IllegalStateException("模型未返回测试结果");
        return result;
    }

    static String workerError(int statusCode, String body) {
        try {
            var detail = OBJECT_MAPPER.readTree(body).path("detail");
            if (detail.isTextual() && !detail.asText().isBlank()) return detail.asText();
            if (detail.isArray()) {
                var messages = new java.util.ArrayList<String>();
                detail.forEach(item -> {
                    var message = item.path("msg").asText("").trim();
                    var location = item.path("loc");
                    var field = location.isArray() && !location.isEmpty()
                            ? location.get(location.size() - 1).asText("") : "";
                    if (!message.isBlank()) messages.add(field.isBlank() ? message : field + "：" + message);
                });
                if (!messages.isEmpty()) return "Worker 参数校验失败（" + String.join("；", messages) + "）";
            }
        } catch (Exception ignored) {
            // Fall through to a compact transport error when the worker did not return JSON.
        }
        return "Agent 服务请求失败（HTTP " + statusCode + "）";
    }

    public Map<String, Object> runAgentTaskStreaming(Object request,
                                                     Consumer<Map<String, Object>> eventConsumer) {
        var finalResult = new AtomicReference<Map<String, Object>>();
        client.post().uri("/v1/agent/tasks/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnNext(event -> {
                    eventConsumer.accept(event);
                    if ("complete".equals(event.get("type")) || "completed".equals(event.get("type"))) finalResult.set(event);
                    if ("error".equals(event.get("type"))) {
                        throw new IllegalStateException(String.valueOf(event.getOrDefault("message", "Agent 任务失败")));
                    }
                })
                .timeout(Duration.ofMinutes(20))
                .blockLast();
        if (finalResult.get() == null) throw new IllegalStateException("Agent 没有返回任务结果");
        return finalResult.get();
    }

    public Map<String, Object> generateContent(String format, String requirements, String sourceText) {
        var result = client.post().uri("/v1/knowledge/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("format", format, "requirements", requirements, "source_text", sourceText))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(workerError(response.statusCode().value(), body))))
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(5))
                .block();
        if (result == null) throw new IllegalStateException("成果生成服务没有返回结果");
        return result;
    }

    public Map<String, Object> generateContentStreaming(String format, String requirements, String sourceText,
                                                        Consumer<Map<String, Object>> eventConsumer) {
        var finalContent = new AtomicReference<String>();
        var mode = new AtomicReference<String>();
        client.post().uri("/v1/knowledge/generate/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("format", format, "requirements", requirements, "source_text", sourceText))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(workerError(response.statusCode().value(), body))))
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnNext(event -> {
                    eventConsumer.accept(event);
                    if ("complete".equals(event.get("type"))) {
                        finalContent.set(String.valueOf(event.getOrDefault("content", "")));
                        mode.set(String.valueOf(event.getOrDefault("mode", "")));
                    }
                    if ("error".equals(event.get("type"))) {
                        throw new IllegalStateException(String.valueOf(event.getOrDefault("message", "大模型生成失败")));
                    }
                })
                .timeout(Duration.ofMinutes(10))
                .blockLast();
        if (finalContent.get() == null || finalContent.get().isBlank()) {
            throw new IllegalStateException("成果生成服务没有返回结果");
        }
        return Map.of("content", finalContent.get(), "mode", mode.get() == null ? "" : mode.get());
    }

    public Map<String, Object> profileSpreadsheet(Path path, String originalName) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path));
        parts.part("original_name", originalName);
        return client.post().uri("/v1/spreadsheets/profile")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(5))
                .block();
    }

    public byte[] transformSpreadsheet(Path path, String originalName, Object operations) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path));
        parts.part("original_name", originalName);
        parts.part("operations", operations).contentType(MediaType.APPLICATION_JSON);
        var result = client.post().uri("/v1/spreadsheets/transform")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMinutes(15))
                .block();
        if (result == null || result.length == 0) throw new IllegalStateException("表格加工失败");
        return result;
    }

    public Map<String, Object> profileData(Path path, String originalName, String sheetName) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(path)).filename(originalName);
        parts.part("original_name", originalName);
        parts.part("sheet_name", sheetName == null ? "" : sheetName);
        var result = client.post().uri("/v1/data-transforms/profile")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(15)).block();
        if (result == null) throw new IllegalStateException("数据结构读取失败");
        return result;
    }

    public Map<String, Object> generateDataTransform(Object request) {
        var result = client.post().uri("/v1/data-transforms/generate")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(10)).block();
        if (result == null) throw new IllegalStateException("加工脚本生成失败");
        return result;
    }

    public Map<String, Object> sampleDataTransform(List<DataTransformInput> inputs, String metadataJson, String script) {
        var parts = transformParts(inputs, metadataJson, script);
        var result = client.post().uri("/v1/data-transforms/sample")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build())).retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(workerError(response.statusCode().value(), body))))
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(30)).block();
        if (result == null) throw new IllegalStateException("数据样本试跑失败");
        return result;
    }

    public void runDataTransform(List<DataTransformInput> inputs, String metadataJson, String script, Path target) {
        var parts = transformParts(inputs, metadataJson, script);
        var body = client.post().uri("/v1/data-transforms/run")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build())).retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class);
        DataBufferUtils.write(body, target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                .timeout(Duration.ofHours(6)).block();
        try {
            if (!java.nio.file.Files.isRegularFile(target) || java.nio.file.Files.size(target) == 0) {
                throw new IllegalStateException("数据全量加工没有产生结果");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("数据加工结果无法保存", exception);
        }
    }

    private MultipartBodyBuilder transformParts(List<DataTransformInput> inputs, String metadataJson, String script) {
        var parts = new MultipartBodyBuilder();
        for (var input : inputs) {
            parts.part("files", new FileSystemResource(input.path())).filename(input.name());
        }
        parts.part("metadata", metadataJson);
        parts.part("script", script);
        return parts;
    }

    public record DataTransformInput(String alias, String name, String sheetName, Path path) { }

    public record ParsedDocument(@JsonProperty("file_name") String fileName,
                                 @JsonProperty("media_type") String mediaType,
                                 String title,
                                 @JsonProperty("text_length") long textLength,
                                 List<ParsedChunk> chunks, List<String> warnings) { }
    public record ParsedChunk(int index, String text, Map<String, Object> location,
                              @JsonProperty("content_hash") String contentHash) { }
}
