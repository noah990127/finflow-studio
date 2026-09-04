package com.finflow.studio.data;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Converts fetched JSON or HTML tables into rows that the data tools can consume. */
public final class StructuredDataMaterializer {
    private static final Pattern DATE_KEY = Pattern.compile("\\d{4}-\\d{2}(?:-\\d{2})?");
    private static final List<String> PATH_HINT_KEYS = List.of(
            "path", "root", "mapping", "records_path", "data_path", "table", "table_path");

    private StructuredDataMaterializer() { }

    public record Result(List<Map<String, Object>> rows, String sourcePath) { }

    public static Result materialize(Map<String, Object> fetched, Object schemaHint, ObjectMapper objectMapper) {
        var text = Objects.toString(fetched.get("text"), "").trim();
        if (!text.isBlank()) {
            try {
                Object payload = objectMapper.readValue(text, new TypeReference<>() { });
                var explicit = hintedCandidate(payload, schemaHint);
                var candidate = explicit == null ? bestCandidate(payload, "root", 0) : explicit;
                if (candidate != null) {
                    var rows = rows(candidate.value(), candidate.path());
                    if (!rows.isEmpty()) return new Result(List.copyOf(rows), candidate.path());
                }
            } catch (JacksonException ignored) {
                // Non-JSON pages can still expose parsed HTML tables below.
            }
        }
        var table = selectTable(fetched.get("tables"), schemaHint);
        if (table != null) return table;
        throw new IllegalStateException("网页正文已读取，但没有找到可转换为数据集的 JSON 记录或表格");
    }

    private static Candidate hintedCandidate(Object payload, Object schemaHint) {
        for (var hint : pathHints(schemaHint)) {
            var value = atPath(payload, hint);
            if (value != null) return new Candidate(value, normalizePath(hint), 10_000);
        }
        return null;
    }

    private static List<String> pathHints(Object schemaHint) {
        if (schemaHint instanceof String value && !value.isBlank()) return List.of(value);
        if (!(schemaHint instanceof Map<?, ?> map)) return List.of();
        var result = new ArrayList<String>();
        for (var key : PATH_HINT_KEYS) {
            var value = map.get(key);
            if (value instanceof String text && !text.isBlank()) result.add(text);
        }
        return result;
    }

    private static Object atPath(Object payload, String rawPath) {
        var current = payload;
        var path = normalizePath(rawPath);
        if (path.equals("root") || path.isBlank()) return current;
        for (var part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else return null;
            if (current == null) return null;
        }
        return current;
    }

    private static String normalizePath(String path) {
        var normalized = path == null ? "" : path.trim();
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        if (normalized.startsWith("root.")) normalized = normalized.substring(5);
        return normalized.isBlank() ? "root" : normalized;
    }

    private static Candidate bestCandidate(Object value, String path, int depth) {
        if (value == null || depth > 8) return null;
        Candidate best = candidate(value, path);
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>)) continue;
                var child = bestCandidate(entry.getValue(), childPath(path, Objects.toString(entry.getKey())), depth + 1);
                if (child != null && (best == null || child.score() > best.score())) best = child;
            }
        }
        return best;
    }

    private static Candidate candidate(Object value, String path) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            var maps = list.stream().filter(Map.class::isInstance).count();
            return new Candidate(value, path, maps == list.size() ? 4_000 + list.size() : 1_000 + list.size());
        }
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return null;
        var structured = map.values().stream().filter(item -> item instanceof Map<?, ?>).count();
        var scalar = map.values().stream().filter(StructuredDataMaterializer::isScalar).count();
        if (structured == map.size()) return new Candidate(value, path, 3_000 + map.size());
        if (scalar == map.size()) return new Candidate(value, path, 500 + map.size());
        return null;
    }

    private static List<Map<String, Object>> rows(Object value, String path) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?> list) {
            for (var index = 0; index < list.size(); index++) {
                var item = list.get(index);
                if (item instanceof Map<?, ?> map) result.add(flatten(map));
                else result.add(Map.of("index", index, "value", scalarValue(item)));
            }
            return result;
        }
        if (!(value instanceof Map<?, ?> map)) return result;
        var keyField = keyField(map, path);
        for (var entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                var row = new LinkedHashMap<String, Object>();
                row.put(keyField, Objects.toString(entry.getKey()));
                row.putAll(flatten(nested));
                result.add(row);
            } else {
                result.add(Map.of(keyField, Objects.toString(entry.getKey()), "value", scalarValue(entry.getValue())));
            }
        }
        return result;
    }

    private static Map<String, Object> flatten(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        flattenInto(result, "", source);
        return result;
    }

    private static void flattenInto(Map<String, Object> result, String prefix, Map<?, ?> source) {
        source.forEach((key, value) -> {
            var name = prefix.isBlank() ? Objects.toString(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) flattenInto(result, name, nested);
            else if (value instanceof List<?> list) result.put(name, list);
            else result.put(name, scalarValue(value));
        });
    }

    private static Object scalarValue(Object value) {
        return value == null || isScalar(value) ? value : Objects.toString(value);
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String keyField(Map<?, ?> map, String path) {
        if (map.keySet().stream().map(Objects::toString).allMatch(key -> DATE_KEY.matcher(key).matches())) return "date";
        var segment = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        segment = segment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (segment.endsWith("s") && segment.length() > 1) segment = segment.substring(0, segment.length() - 1);
        return segment.isBlank() || "root".equals(segment) ? "key" : segment + "_key";
    }

    private static Result selectTable(Object value, Object schemaHint) {
        if (!(value instanceof List<?> tables)) return null;
        var hints = pathHints(schemaHint).stream().map(item -> item.toLowerCase(Locale.ROOT)).toList();
        Map<?, ?> selected = null;
        var selectedSize = -1;
        for (var item : tables) {
            if (!(item instanceof Map<?, ?> table) || !(table.get("rows") instanceof List<?> rows) || rows.size() < 2) continue;
            var title = Objects.toString(table.get("title"), "table");
            var matches = hints.stream().anyMatch(hint -> title.toLowerCase(Locale.ROOT).contains(hint));
            var score = rows.size() + (matches ? 100_000 : 0);
            if (score > selectedSize) {
                selected = table;
                selectedSize = score;
            }
        }
        if (selected == null) return null;
        var rawRows = (List<?>) selected.get("rows");
        if (!(rawRows.getFirst() instanceof List<?> headers)) return null;
        var names = uniqueHeaders(headers);
        var result = new ArrayList<Map<String, Object>>();
        for (var index = 1; index < rawRows.size(); index++) {
            if (!(rawRows.get(index) instanceof List<?> row)) continue;
            var mapped = new LinkedHashMap<String, Object>();
            for (var column = 0; column < names.size(); column++) {
                mapped.put(names.get(column), column < row.size() ? scalarValue(row.get(column)) : null);
            }
            result.add(mapped);
        }
        return result.isEmpty() ? null : new Result(List.copyOf(result), Objects.toString(selected.get("title"), "table"));
    }

    private static List<String> uniqueHeaders(List<?> headers) {
        var result = new ArrayList<String>();
        for (var index = 0; index < headers.size(); index++) {
            var base = Objects.toString(headers.get(index), "").trim();
            if (base.isBlank()) base = "column_" + (index + 1);
            var name = base;
            var suffix = 2;
            while (result.contains(name)) name = base + "_" + suffix++;
            result.add(name);
        }
        return result;
    }

    private static String childPath(String path, String child) {
        return "root".equals(path) ? child : path + "." + child;
    }

    private record Candidate(Object value, String path, int score) { }
}
