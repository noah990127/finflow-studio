package com.finflow.studio.deliverable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DataFormulatorClient {
    private final WebClient client;

    public DataFormulatorClient(@Value("${finflow.data-formulator.internal-url:${finflow.data-formulator.url:http://127.0.0.1:5567}}") String internalUrl) {
        this.client = WebClient.builder().baseUrl(internalUrl.replaceAll("/$", "")).build();
    }

    public boolean online() {
        try {
            client.get().uri("/").retrieve().toBodilessEntity().timeout(Duration.ofSeconds(3)).block();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public void ensureWorkspace(String workspaceId, String displayName) {
        var response = get("/api/sessions/list", Duration.ofSeconds(10));
        var sessions = response.get("sessions");
        var exists = sessions instanceof List<?> list && list.stream().anyMatch(item ->
                item instanceof Map<?, ?> map && workspaceId.equals(String.valueOf(map.get("id"))));
        if (!exists) {
            postJson("/api/sessions/create", Map.of("id", workspaceId), Duration.ofSeconds(10));
        }
        postJson("/api/sessions/update-meta", Map.of("id", workspaceId, "display_name", displayName), Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importFile(String workspaceId, String tableName, Path path, String originalName) {
        var parts = new MultipartBodyBuilder();
        parts.part("table_name", tableName);
        parts.part("replace_source", "true");
        parts.part("file", new FileSystemResource(path)).filename(originalName);
        var response = client.post().uri("/api/tables/create-table")
                .header("X-Workspace-Id", workspaceId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMinutes(10))
                .block();
        return unwrap(response);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listTables(String workspaceId) {
        var response = client.get().uri("/api/tables/list-tables")
                .header("X-Workspace-Id", workspaceId)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        return unwrap(response);
    }

    @SuppressWarnings("unchecked")
    public void syncWorkspaceState(String workspaceId, String displayName) {
        var loaded = postJson("/api/sessions/load", Map.of("id", workspaceId), Duration.ofSeconds(30));
        var state = loaded.get("state") instanceof Map<?, ?> existing
                ? new LinkedHashMap<String, Object>((Map<String, Object>) existing)
                : new LinkedHashMap<String, Object>();
        var tablePayload = listTables(workspaceId).get("tables");
        var tables = new ArrayList<Map<String, Object>>();
        if (tablePayload instanceof List<?> list) {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                var table = (Map<String, Object>) raw;
                var names = new ArrayList<String>();
                var metadata = new LinkedHashMap<String, Object>();
                if (table.get("columns") instanceof List<?> columns) {
                    for (var columnItem : columns) {
                        if (!(columnItem instanceof Map<?, ?> column)) continue;
                        var name = String.valueOf(column.get("name"));
                        names.add(name);
                        var columnMeta = new LinkedHashMap<String, Object>();
                        columnMeta.put("type", column.get("type") == null ? "string" : String.valueOf(column.get("type")));
                        columnMeta.put("semanticType", "");
                        columnMeta.put("levels", List.of());
                        if (column.get("description") != null) columnMeta.put("description", column.get("description"));
                        metadata.put(name, columnMeta);
                    }
                }
                var name = String.valueOf(table.get("name"));
                var source = new LinkedHashMap<String, Object>();
                source.put("type", "upload".equals(table.get("source_type")) ? "file" : "database");
                source.put("originalTableName", table.getOrDefault("original_name", name));
                if ("file".equals(source.get("type"))) source.put("fileName", table.getOrDefault("source_filename", name));
                else source.put("databaseTable", name);

                var frontendTable = new LinkedHashMap<String, Object>();
                frontendTable.put("kind", "table");
                frontendTable.put("id", name);
                frontendTable.put("displayId", name);
                frontendTable.put("names", names);
                frontendTable.put("metadata", metadata);
                frontendTable.put("rows", table.getOrDefault("sample_rows", List.of()));
                frontendTable.put("virtual", Map.of("tableId", name, "rowCount", table.getOrDefault("row_count", 0)));
                frontendTable.put("anchored", true);
                frontendTable.put("description", table.getOrDefault("description", ""));
                frontendTable.put("source", source);
                tables.add(frontendTable);
            }
        }
        state.put("tables", tables);
        state.putIfAbsent("charts", List.of());
        state.putIfAbsent("draftNodes", List.of());
        state.putIfAbsent("conceptShelfItems", List.of());
        state.put("activeWorkspace", Map.of("id", workspaceId, "displayName", displayName));
        postJson("/api/sessions/save", Map.of("id", workspaceId, "state", state), Duration.ofMinutes(2));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String uri, Duration timeout) {
        var response = client.get().uri(uri).retrieve().bodyToMono(Map.class).timeout(timeout).block();
        return unwrap(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String uri, Map<String, Object> body, Duration timeout) {
        var response = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(Map.class).timeout(timeout).block();
        return unwrap(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(Map<?, ?> response) {
        if (response == null) throw new IllegalStateException("Data Formulator 没有返回结果");
        if ("error".equals(response.get("status"))) {
            var error = response.get("error");
            var message = error instanceof Map<?, ?> map ? map.get("message") : error;
            throw new IllegalStateException("Data Formulator 处理失败：" + String.valueOf(message));
        }
        var data = response.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : (Map<String, Object>) response;
    }
}
