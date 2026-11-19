package com.finflow.studio.knowledge;

import com.finflow.studio.knowledge.PasteImportModels.Imported;
import com.finflow.studio.knowledge.PasteImportModels.Preview;
import com.finflow.studio.knowledge.PasteImportModels.Request;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/paste-imports")
public class PasteImportController {
    private final PasteImportService service;

    public PasteImportController(PasteImportService service) { this.service = service; }

    @PostMapping("/preview")
    public Preview preview(@PathVariable String projectId, @Valid @RequestBody Request request) {
        return service.preview(projectId, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Imported importContent(@PathVariable String projectId, @Valid @RequestBody Request request) {
        return service.importContent(projectId, request);
    }
}
