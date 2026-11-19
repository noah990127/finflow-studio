package com.finflow.studio.knowledge;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class PastedDataParser {
    private static final int MAX_ROWS = 50_000;
    private static final int PREVIEW_ROWS = 20;
    private final ObjectMapper objectMapper;

    PastedDataParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    Parsed parse(String content, String requestedFormat) {
        var normalized = Objects.requireNonNullElse(content, "").strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("请粘贴需要导入的内容");
        var format = detect(normalized, requestedFormat);
        try {
            return switch (format) {
                case "JSON" -> parseJson(normalized);
                case "MARKDOWN_TABLE" -> parseMarkdown(normalized);
                case "TSV" -> parseDelimited(normalized, '\t', "TSV");
                case "CSV" -> parseDelimited(normalized, ',', "CSV");
                default -> text(normalized);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("没有识别出稳定的表格结构，请检查列名、分隔符或 JSON 格式", exception);
        }
    }

    private Parsed parseDelimited(String content, char delimiter, String formatName) throws Exception {
        var format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).get();
        try (var parser = CSVParser.builder()
                .setInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
                .setCharset(StandardCharsets.UTF_8).setFormat(format).get()) {
            var records = parser.getRecords();
            if (records.size() < 2) return text(content);
            var columns = records.getFirst().stream().map(value -> value.replace("\ufeff", "").strip()).toList();
            var rows = new ArrayList<List<String>>();
            for (var record : records.subList(1, records.size())) {
                if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("粘贴内容超过 50,000 行，请改用文件上传或数据连接");
                var row = new ArrayList<String>();
                record.forEach(row::add);
                while (row.size() < columns.size()) row.add("");
                rows.add(List.copyOf(row));
            }
            return structured(formatName, columns, rows);
        }
    }

    private Parsed parseMarkdown(String content) {
        var lines = content.lines().filter(line -> line.contains("|")).toList();
        if (lines.size() < 3) return text(content);
        var columns = markdownRow(lines.getFirst());
        var start = separator(markdownRow(lines.get(1))) ? 2 : 1;
        var rows = new ArrayList<List<String>>();
        for (var line : lines.subList(start, lines.size())) {
            if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("粘贴内容超过 50,000 行，请改用文件上传或数据连接");
            var row = new ArrayList<>(markdownRow(line));
            while (row.size() < columns.size()) row.add("");
            rows.add(List.copyOf(row));
        }
        return rows.isEmpty() ? text(content) : structured("MARKDOWN_TABLE", columns, rows);
    }

    private Parsed parseJson(String content) throws Exception {
        var value = objectMapper.readValue(content, Object.class);
        List<?> items;
        if (value instanceof List<?> list) items = list;
        else if (value instanceof Map<?, ?> map) {
            var nested = map.values().stream().filter(item -> item instanceof List<?>)
                    .map(item -> (List<?>) item).findFirst();
            items = nested.isPresent() ? nested.get() : List.of(map);
        } else return text(content);
        if (items.isEmpty() || !(items.getFirst() instanceof Map<?, ?>)) return text(content);
        if (items.size() > MAX_ROWS) throw new IllegalArgumentException("粘贴内容超过 50,000 行，请改用文件上传或数据连接");
        var names = new LinkedHashSet<String>();
        items.forEach(item -> { if (item instanceof Map<?, ?> map) map.keySet().forEach(key -> names.add(String.valueOf(key))); });
        var columns = List.copyOf(names);
        var rows = new ArrayList<List<String>>();
        for (var item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            rows.add(columns.stream().map(column -> scalar(map.get(column))).toList());
        }
        return structured("JSON", columns, rows);
    }

    private Parsed structured(String format, List<String> columns, List<List<String>> rows) {
        if (columns.isEmpty() || columns.stream().allMatch(String::isBlank)) return text("");
        var warnings = new ArrayList<String>();
        var duplicates = columns.stream().filter(name -> Collections.frequency(columns, name) > 1).distinct().toList();
        if (!duplicates.isEmpty()) warnings.add("存在重复列名：" + String.join("、", duplicates));
        if (rows.stream().anyMatch(row -> row.size() != columns.size())) warnings.add("部分行的列数与表头不一致，请重点核对预览");
        if (rows.stream().flatMap(Collection::stream).anyMatch(PastedDataParser::formulaLike))
            warnings.add("检测到以 =、+、- 或 @ 开头的内容，导出到电子表格时请确认不是公式注入");
        return new Parsed(format, true, List.copyOf(columns), List.copyOf(rows), List.copyOf(warnings), "");
    }

    private Parsed text(String content) {
        return new Parsed("TEXT", false, List.of(), List.of(),
                List.of("没有检测到稳定表格，将作为原始资料保存，不会作为可计算数据使用"), content);
    }

    byte[] csv(Parsed parsed) {
        try {
            var writer = new StringWriter();
            try (var printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
                printer.printRecord(parsed.columns());
                for (var row : parsed.rows()) printer.printRecord(row);
            }
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("表格转换失败", exception);
        }
    }

    static String hash(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String detect(String content, String requested) {
        var choice = Objects.requireNonNullElse(requested, "AUTO").strip().toUpperCase(Locale.ROOT);
        if (!choice.equals("AUTO")) return choice;
        if (content.startsWith("[") || content.startsWith("{")) return "JSON";
        var firstLines = content.lines().limit(3).toList();
        if (firstLines.size() >= 2 && firstLines.getFirst().contains("|") && separator(markdownRow(firstLines.get(1)))) return "MARKDOWN_TABLE";
        if (firstLines.stream().allMatch(line -> line.contains("\t"))) return "TSV";
        if (firstLines.stream().allMatch(line -> line.contains(","))) return "CSV";
        return "TEXT";
    }

    private static List<String> markdownRow(String line) {
        var trimmed = line.strip().replaceFirst("^\\|", "").replaceFirst("\\|$", "");
        return Arrays.stream(trimmed.split("\\|", -1)).map(String::strip).toList();
    }
    private static boolean separator(List<String> cells) { return !cells.isEmpty() && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?")); }
    private static boolean formulaLike(String value) {
        if (value == null || value.isBlank()) return false;
        var normalized = value.strip();
        if (normalized.matches("[+-]?\\d+(\\.\\d+)?")) return false;
        return "=+-@".indexOf(normalized.charAt(0)) >= 0;
    }
    private static String scalar(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof List<?>) return String.valueOf(value);
        return String.valueOf(value);
    }

    record Parsed(String format, boolean structured, List<String> columns, List<List<String>> rows,
                  List<String> warnings, String text) { }
}
