package com.finflow.studio.deliverable;

import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.Response;
import com.finflow.studio.worker.WorkerClient;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DeliverableController {
    private final DeliverableService deliverables;
    private final WorkerClient worker;

    public DeliverableController(DeliverableService deliverables, WorkerClient worker) {
        this.deliverables = deliverables;
        this.worker = worker;
    }

    @PostMapping("/deliverables")
    @ResponseStatus(HttpStatus.CREATED)
    public Response create(@Valid @RequestBody CreateRequest request) { return deliverables.create(request); }

    @PostMapping(value = "/projects/{projectId}/deliverables/import", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Response importArtifact(@PathVariable String projectId,
                                   @RequestParam(required = false) String resourceId,
                                   @RequestParam String title,
                                   @RequestParam String format,
                                   @RequestParam(defaultValue = "{}") String sourceSpec,
                                   @RequestPart MultipartFile file) {
        return deliverables.importArtifact(projectId, resourceId, title, format, sourceSpec, file);
    }

    @GetMapping("/projects/{projectId}/deliverables")
    public List<Response> list(@PathVariable String projectId) { return deliverables.list(projectId); }

    @GetMapping("/deliverables/{id}")
    public Response get(@PathVariable String id) { return deliverables.get(id); }

    @DeleteMapping("/deliverables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { deliverables.delete(id); }

    @PostMapping("/deliverables/{id}/rerender")
    public Response rerender(@PathVariable String id) { return deliverables.rerender(id); }

    @GetMapping("/deliverables/{id}/preview")
    public Map<String, Object> preview(@PathVariable String id, @RequestParam(required = false) Integer version) {
        var item = deliverables.get(id);
        return worker.preview(deliverables.path(id, version), item.name() + "." +
                (item.format().equals("mermaid") ? "mmd" : item.format()));
    }

    @GetMapping("/deliverables/{id}/download")
    public void download(@PathVariable String id, @RequestParam(required = false) Integer version,
                         HttpServletResponse response) throws IOException {
        var item = deliverables.get(id);
        var path = deliverables.path(id, version);
        response.setContentType(switch (item.format()) {
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pdf" -> "application/pdf";
            case "excalidraw" -> "application/json; charset=UTF-8";
            case "financial_report" -> "application/json; charset=UTF-8";
            case "html_slides" -> "text/html; charset=UTF-8";
            default -> "text/plain; charset=UTF-8";
        });
        response.setHeader("Content-Length", Long.toString(Files.size(path)));
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(path.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) { input.transferTo(output); }
    }

    @GetMapping("/deliverables/{id}/content")
    public void content(@PathVariable String id, @RequestParam(required = false) Integer version,
                        HttpServletResponse response) throws IOException {
        var item = deliverables.get(id);
        if (!List.of("html_slides", "pdf").contains(item.format())) {
            throw new IllegalArgumentException("当前输出件不支持内嵌预览");
        }
        var path = deliverables.path(id, version);
        response.setContentType("pdf".equals(item.format()) ? "application/pdf" : "text/html; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        var policy = "pdf".equals(item.format())
                ? "default-src 'none'; frame-ancestors 'self'; object-src 'self' blob:"
                : "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; frame-ancestors 'self'; base-uri 'none'; form-action 'none'; object-src 'none'";
        response.setHeader("Content-Security-Policy", policy);
        response.setHeader("Content-Disposition", "inline; filename=" + ("pdf".equals(item.format()) ? "preview.pdf" : "web-presentation.html"));
        response.setHeader("Content-Length", Long.toString(Files.size(path)));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) { input.transferTo(output); }
    }

    @GetMapping("/deliverables/{id}/report-spec")
    public void reportSpec(@PathVariable String id, @RequestParam(required = false) Integer version,
                           HttpServletResponse response) throws IOException {
        var item = deliverables.get(id);
        if (!"financial_report".equals(item.format())) throw new IllegalArgumentException("当前输出件不是财务报告");
        var path = deliverables.path(id, version);
        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Length", Long.toString(Files.size(path)));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) { input.transferTo(output); }
    }

    @GetMapping("/deliverables/{id}/citations")
    public List<Map<String, Object>> citations(@PathVariable String id,
                                               @RequestParam(required = false) Integer version) {
        return deliverables.citations(id, version);
    }

    @GetMapping("/deliverables/{id}/rendered-preview")
    public void renderedPreview(@PathVariable String id, @RequestParam(required = false) Integer version,
                                HttpServletResponse response) throws IOException {
        var item = deliverables.get(id);
        var bytes = worker.renderOfficePreview(deliverables.path(id, version), item.name() + "." + item.format());
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Length", Integer.toString(bytes.length));
        response.setHeader("Content-Disposition", "inline; filename=preview.html");
        response.getOutputStream().write(bytes);
    }
}
