package com.finflow.studio.knowledge;

import com.finflow.studio.knowledge.PasteImportModels.Imported;
import com.finflow.studio.knowledge.PasteImportModels.Preview;
import com.finflow.studio.knowledge.PasteImportModels.Request;
import com.finflow.studio.project.ProjectService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class PasteImportService {
    private final KnowledgeService knowledge;
    private final ProjectService projects;
    private final JdbcClient jdbc;
    private final PastedDataParser parser;

    public PasteImportService(KnowledgeService knowledge, ProjectService projects, JdbcClient jdbc,
                              ObjectMapper objectMapper) {
        this.knowledge = knowledge;
        this.projects = projects;
        this.jdbc = jdbc;
        this.parser = new PastedDataParser(objectMapper);
    }

    public Preview preview(String projectId, Request request) {
        projects.get(projectId);
        var parsed = parser.parse(request.content(), request.format());
        return response(parsed, request.content());
    }

    @Transactional
    public Imported importContent(String projectId, Request request) {
        if (!request.confirmed()) throw new IllegalArgumentException("请先检查预览并确认内容无误");
        var parsed = parser.parse(request.content(), request.format());
        var name = cleanName(request.name(), parsed.structured() ? ".csv" : ".md");
        var bytes = parsed.structured() ? parser.csv(parsed) : request.content().getBytes(StandardCharsets.UTF_8);
        var mediaType = parsed.structured() ? "text/csv" : "text/markdown";
        var resource = knowledge.importBytes(projectId, name, mediaType, bytes);
        jdbc.sql("""
                insert into pasted_import_provenance(resource_id, project_id, source_agent, source_question,
                    captured_at, detected_format, raw_hash, raw_content, created_at)
                values (:resourceId, :projectId, :sourceAgent, :sourceQuestion, :capturedAt,
                    :format, :rawHash, :rawContent, :createdAt)
                """).param("resourceId", resource.id()).param("projectId", projectId)
                .param("sourceAgent", safe(request.sourceAgent(), "未注明"))
                .param("sourceQuestion", safe(request.sourceQuestion(), ""))
                .param("capturedAt", request.capturedAt() == null ? Instant.now() : request.capturedAt())
                .param("format", parsed.format()).param("rawHash", PastedDataParser.hash(request.content()))
                .param("rawContent", request.content()).param("createdAt", Instant.now()).update();
        return new Imported(resource, response(parsed, request.content()));
    }

    private Preview response(PastedDataParser.Parsed parsed, String raw) {
        var sample = parsed.rows().stream().limit(20).toList();
        return new Preview(parsed.format(), parsed.structured(), parsed.columns(), sample,
                parsed.rows().size(), parsed.warnings(), PastedDataParser.hash(raw), true);
    }

    private String cleanName(String value, String extension) {
        var base = safe(value, "外部 Agent 结果").replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return base.toLowerCase().endsWith(extension) ? base : base + extension;
    }
    private String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.strip(); }
}
