package com.finflow.studio.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/mystery-node")
public class MysteryNodeController {
    private final MysteryNodeService service;

    public MysteryNodeController(MysteryNodeService service) { this.service = service; }

    @PostMapping("/preview")
    public Map<String, Object> preview(@PathVariable String projectId, @Valid @RequestBody PreviewRequest request) {
        return service.preview(projectId, request.content(), request.hint(), request.target());
    }

    public record PreviewRequest(
            @NotBlank @Size(max = 200_000) String content,
            @Size(max = 2_000) String hint,
            @Size(max = 24) String target) { }
}
