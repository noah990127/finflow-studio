package com.finflow.studio.knowledge.tacit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/tacit")
public class TacitController {
    private final TacitWorkspaceService service;
    public TacitController(TacitWorkspaceService service) { this.service = service; }

    public record Command(@NotBlank String action, @NotNull @PositiveOrZero @JsonProperty("expected_revision") Long revision,
                          @NotBlank @JsonProperty("request_id") String requestId, Map<String, Object> payload, boolean compact) { }

    @GetMapping
    public Map<String, Object> workspace(@PathVariable String projectId) {
        return service.workspace(projectId);
    }

    @ExceptionHandler(WorkerClient.TacitUnavailable.class)
    ResponseEntity<Map<String, String>> unavailable(WorkerClient.TacitUnavailable failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "TACIT_SERVICE_UNAVAILABLE", "message", failure.getMessage()));
    }

    @GetMapping("/reviews/{revision}")
    public Map<String, Object> reviewSnapshot(@PathVariable String projectId, @PathVariable long revision) {
        return service.reviewSnapshot(projectId, revision);
    }

    @GetMapping("/reviews")
    public Map<String, Object> reviews(@PathVariable String projectId,
                                       @RequestParam(name = "before_revision", required = false) Long beforeRevision,
                                       @RequestParam(defaultValue = "30") int limit) {
        return service.reviews(projectId, beforeRevision, limit);
    }

    @PostMapping("/commands")
    public Map<String, Object> execute(@PathVariable String projectId, @RequestBody @Valid Command command) {
        return service.executeHuman(projectId, command.action(), command.revision(), command.requestId(), command.payload(), command.compact());
    }
}
