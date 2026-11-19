package com.finflow.studio.knowledge.tacit;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/tacit/ontology/publications")
class OntologyPublicationController {
    private final OntologyPublications publications;
    OntologyPublicationController(OntologyPublications publications) { this.publications = publications; }

    record Command(@NotNull @PositiveOrZero @JsonProperty("expected_revision") Long revision,
                   @NotBlank @Pattern(regexp="[a-zA-Z0-9._:-]{1,128}") @JsonProperty("request_id") String requestId,
                   @NotBlank @Pattern(regexp="[0-9a-f]{64}") @JsonProperty("model_digest") String digest,
                   @NotBlank @Size(max=256) @JsonProperty("target_id") String target) {}
    record Retry(@NotNull @PositiveOrZero @JsonProperty("expected_revision") Long revision) {}

    @GetMapping
    public Map<String,Object> list(@PathVariable String projectId, @RequestParam(required=false) String before) {
        return publications.list(projectId, before);
    }

    @PostMapping
    public Map<String,Object> publish(@PathVariable String projectId, @RequestBody @Valid Command command) {
        return publications.publish(projectId, command);
    }

    @PostMapping("/{id}/retry")
    public Map<String,Object> retry(@PathVariable String projectId, @PathVariable String id, @RequestBody @Valid Retry command) {
        return publications.retry(projectId, id, command.revision());
    }

    @GetMapping("/{id}/snapshot")
    public Map<String,Object> snapshot(@PathVariable String projectId, @PathVariable String id) {
        return publications.snapshot(projectId, id);
    }
}
