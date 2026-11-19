package com.finflow.studio.knowledge.tacit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class TacitCatalog {
    record File(String id, String title, String version, String versionId, String checksum, String status,
                String message, String parsedAt, String originalName, String mediaType) { }
    private final JdbcClient jdbc;

    TacitCatalog(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<File> files(String project, List<String> selected, boolean lock) {
        if (selected.isEmpty()) return List.of();
        var rows = jdbc.sql("""
                select r.id, r.name, r.current_version, v.id as vid, v.checksum, v.parse_status,
                       v.parse_message, v.parsed_at, v.original_name, v.media_type
                from file_resource r join file_version v on v.resource_id=r.id and v.version_number=r.current_version
                where r.project_id=:project and r.id in (:ids) order by r.id
                """ + (lock ? " for update" : ""))
                .param("project", project).param("ids", selected).query((rs, row) -> new File(rs.getString("id"),
                        rs.getString("name"), rs.getString("current_version"), rs.getString("vid"),
                        rs.getString("checksum"), rs.getString("parse_status"), rs.getString("parse_message"),
                        rs.getTimestamp("parsed_at") == null ? "" : rs.getTimestamp("parsed_at").toInstant().toString(),
                        rs.getString("original_name"), rs.getString("media_type"))).list();
        var byId = new HashMap<String, File>();
        rows.forEach(f -> byId.put(f.id(), f));
        return selected.stream().map(id -> byId.getOrDefault(id,
                new File(id, "已移除资料", "", "", "", "MISSING", "资料已移除，请重新选择", "", "", ""))).toList();
    }

    String stamp(List<File> files) { return TacitJson.hash(TacitJson.write(files)); }

    List<Map<String, Object>> load(String project, List<File> files) {
        var result = new ArrayList<Map<String, Object>>();
        for (var file : files) {
            var source = new LinkedHashMap<String, Object>();
            source.put("source_id", file.id());
            source.put("source_type", "asset");
            source.put("title", file.title());
            source.put("version", file.version());
            var text = "";
            var reason = file.message() == null ? "" : file.message();
            if ("READY".equals(file.status())) {
                var assembler = new ParsedText();
                var lower = file.originalName().toLowerCase(java.util.Locale.ROOT);
                boolean proofRequired = lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".pptx")
                        || "application/pdf".equals(file.mediaType());
                jdbc.sql("""
                        select chunk_index, text_content, location_json from knowledge_ref
                        where project_id=:project and file_version_id=:version order by chunk_index
                        """).param("project", project).param("version", file.versionId()).query((rs) -> {
                            while (rs.next()) {
                                assembler.add(rs.getInt("chunk_index"), rs.getString("text_content"),
                                        TacitJson.object(rs.getString("location_json")));
                            }
                            return true;
                        });
                text = assembler.text.toString();
                if (!assembler.complete || text.isBlank()) reason = "缺少连续可读的原文，请重新解析或补充文本";
                if (proofRequired && !assembler.hasExtractionProof)
                    reason = "该格式的旧版解析未记录未识别页面，请重新解析后核对";
                source.put("locations", assembler.locations);
            } else if (reason.isBlank()) {
                reason = "资料解析状态为 " + file.status();
            }
            source.put("text", text);
            source.put("digest", TacitJson.hash(text));
            source.put("total_characters", text.codePointCount(0, text.length()));
            source.put("incomplete", !reason.isBlank());
            source.put("reason", reason);
            result.add(source);
        }
        return result;
    }

    // Existing Refs overlap. Offsets refer to Unicode code points inside a physical paragraph/page.
    // Keep each physical unit once and retain location spans for citation display.
    static final class ParsedText {
        final StringBuilder text = new StringBuilder();
        final List<Map<String, Object>> locations = new ArrayList<>();
        private int count, previousEnd, position;
        private Map<String, Object> previousUnit;
        private String previousText = "";
        boolean complete = true;
        boolean hasExtractionProof = true;

        void add(int index, String chunk, Map<String, Object> location) {
            if (index != count++) complete = false;
            var unit = new LinkedHashMap<>(location);
            if (!(unit.remove("text_extraction_version") instanceof Number version) || version.intValue() < 2)
                hasExtractionProof = false;
            Object startRaw = unit.remove("char_start"), endRaw = unit.remove("char_end");
            int start = startRaw instanceof Number n ? n.intValue() : -1;
            int end = endRaw instanceof Number n ? n.intValue() : -1;
            if (start < 0 || end <= start || chunk.isBlank()) complete = false;
            int overlap = 0;
            boolean sameUnit = unit.equals(previousUnit) && start > 0;
            if (sameUnit) {
                if (start > previousEnd || end <= previousEnd) complete = false;
                int expected = Math.max(0, previousEnd - start);
                overlap = matchingOverlap(previousText, chunk, expected);
                if (expected > 0 && overlap == 0) complete = false;
            } else {
                if (start > 0) complete = false;
                if (count > 1) { text.append('\n'); position++; }
                var entry = new LinkedHashMap<String, Object>();
                entry.put("locator", TacitJson.write(unit));
                entry.put("start", position);
                locations.add(entry);
            }
            var suffix = chunk.substring(chunk.offsetByCodePoints(0, overlap));
            text.append(suffix);
            position += suffix.codePointCount(0, suffix.length());
            locations.getLast().put("end", position);
            previousUnit = unit;
            previousEnd = end;
            previousText = chunk;
        }

        private static int matchingOverlap(String prior, String current, int expected) {
            int length = Math.min(expected, Math.min(prior.codePointCount(0, prior.length()),
                    current.codePointCount(0, current.length())));
            for (int n = length; n > 0; n--) {
                int priorStart = prior.offsetByCodePoints(prior.length(), -n);
                int currentEnd = current.offsetByCodePoints(0, n);
                if (prior.length() - priorStart == currentEnd && prior.regionMatches(priorStart, current, 0, currentEnd))
                    return n;
            }
            return 0;
        }
    }
}
