package com.finflow.studio.deliverable;

import com.finflow.studio.deliverable.DeliverableModels.CreateRequest;
import com.finflow.studio.deliverable.DeliverableModels.Response;
import com.finflow.studio.worker.WorkerClient;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DeliverableController {
    private final DeliverableService deliverables;
    private final WorkerClient worker;
    private final DataFormulatorService dataFormulator;

    public DeliverableController(DeliverableService deliverables, WorkerClient worker,
                                 DataFormulatorService dataFormulator) {
        this.deliverables = deliverables;
        this.worker = worker;
        this.dataFormulator = dataFormulator;
    }

    @PostMapping("/deliverables")
    @ResponseStatus(HttpStatus.CREATED)
    public Response create(@Valid @RequestBody CreateRequest request) { return deliverables.create(request); }

    @GetMapping("/projects/{projectId}/deliverables")
    public List<Response> list(@PathVariable String projectId) { return deliverables.list(projectId); }

    @GetMapping("/deliverables/{id}")
    public Response get(@PathVariable String id) { return deliverables.get(id); }

    @PostMapping("/deliverables/{id}/data-formulator/sync")
    public Map<String, Object> syncDataFormulator(@PathVariable String id) { return dataFormulator.sync(id); }

    @DeleteMapping("/deliverables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { deliverables.delete(id); }

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
            case "financial_report", "html_slides" -> "text/html; charset=UTF-8";
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
        if (!List.of("financial_report", "html_slides").contains(item.format())) throw new IllegalArgumentException("当前输出件不支持内嵌预览");
        var path = deliverables.path(id, version);
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        var policy = "html_slides".equals(item.format())
                ? "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; frame-ancestors 'self'; base-uri 'none'; form-action 'none'; object-src 'none'"
                : "default-src 'none'; style-src 'unsafe-inline'; frame-src http://127.0.0.1:5567 http://localhost:5567; frame-ancestors 'self'; base-uri 'none'; form-action 'none'";
        response.setHeader("Content-Security-Policy", policy);
        response.setHeader("Content-Disposition", "inline; filename=" + ("html_slides".equals(item.format()) ? "web-presentation.html" : "financial-report.html"));
        try (var input = Files.newInputStream(path); var output = response.getOutputStream()) { input.transferTo(output); }
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
