package com.finflow.studio.knowledge;

import com.finflow.studio.knowledge.KnowledgeModels.FileResourceResponse;
import com.finflow.studio.knowledge.KnowledgeModels.RefResponse;
import com.finflow.studio.preview.CsvPreviewService;
import com.finflow.studio.preview.PreviewModels.CsvPreview;
import com.finflow.studio.worker.WorkerClient;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final WorkerClient worker;
    private final CsvPreviewService previews;

    public KnowledgeController(KnowledgeService knowledge, WorkerClient worker, CsvPreviewService previews) {
        this.knowledge = knowledge;
        this.worker = worker;
        this.previews = previews;
    }

    @PostMapping(value = "/projects/{projectId}/files", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public FileResourceResponse upload(@PathVariable String projectId,
                                       @RequestParam(required = false) String resourceId,
                                       @RequestPart MultipartFile file) {
        return knowledge.upload(projectId, resourceId, file);
    }

    @GetMapping("/projects/{projectId}/files")
    public List<FileResourceResponse> list(@PathVariable String projectId) { return knowledge.list(projectId); }

    @GetMapping("/files/{id}")
    public FileResourceResponse get(@PathVariable String id) { return knowledge.get(id); }

    @DeleteMapping("/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { knowledge.delete(id); }

    @GetMapping("/projects/{projectId}/refs/search")
    public List<RefResponse> search(@PathVariable String projectId, @RequestParam String query,
                                    @RequestParam(defaultValue = "10") int limit) {
        return knowledge.search(projectId, query, limit);
    }

    @GetMapping("/files/{id}/preview")
    public Map<String, Object> preview(@PathVariable String id, @RequestParam(required = false) Integer version) {
        var resource = knowledge.get(id);
        return worker.preview(knowledge.filePath(id, version), resource.name());
    }

    @GetMapping("/files/{id}/csv-preview")
    public CsvPreview previewCsv(@PathVariable String id, @RequestParam(required = false) Integer version,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "100") int limit) {
        return previews.preview(knowledge.filePath(id, version), cursor, limit);
    }

    @GetMapping("/files/{id}/content")
    public void content(@PathVariable String id, @RequestParam(required = false) Integer version,
                        HttpServletResponse response) throws IOException {
        var resource = knowledge.get(id);
        if (!(resource.name().toLowerCase().endsWith(".pdf") || "application/pdf".equals(resource.mediaType()))) {
            throw new IllegalArgumentException("当前只有 PDF 使用原文内联预览");
        }
        var path = knowledge.filePath(id, version);
        response.setContentType("application/pdf");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Length", Long.toString(Files.size(path)));
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" +
                java.net.URLEncoder.encode(path.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) {
            input.transferTo(output);
        }
    }

    @GetMapping("/files/{id}/rendered-preview")
    public void renderedPreview(@PathVariable String id, @RequestParam(required = false) Integer version,
                                HttpServletResponse response) throws IOException {
        var resource = knowledge.get(id);
        writeRenderedPreview(worker.renderOfficePreview(knowledge.filePath(id, version), resource.name()), response);
    }

    @GetMapping("/files/{id}/download")
    public void download(@PathVariable String id, @RequestParam(required = false) Integer version,
                         HttpServletResponse response) throws IOException {
        var resource = knowledge.get(id);
        var path = knowledge.filePath(id, version);
        response.setContentType(resource.mediaType());
        response.setHeader("Content-Length", Long.toString(Files.size(path)));
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(path.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) {
            input.transferTo(output);
        }
    }

    private void writeRenderedPreview(byte[] bytes, HttpServletResponse response) throws IOException {
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Length", Integer.toString(bytes.length));
        response.setHeader("Content-Disposition", "inline; filename=preview.html");
        response.getOutputStream().write(bytes);
    }
}
