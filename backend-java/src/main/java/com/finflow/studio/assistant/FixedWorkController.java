package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/works/{sessionId}/implementation")
public class FixedWorkController {
    private final FixedWorkService works;
    public FixedWorkController(FixedWorkService works) { this.works = works; }
    @GetMapping
    FixedWorkService.State state(@PathVariable String projectId, @PathVariable String sessionId) {
        return works.state(projectId, sessionId);
    }
    @PostMapping("/runs")
    FixedWorkService.Run start(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody FixedWorkService.Start request) {
        return works.start(projectId, sessionId, request);
    }
    @GetMapping("/versions/{definitionId}")
    FixedWorkService.Definition definition(@PathVariable String projectId, @PathVariable String sessionId, @PathVariable String definitionId) {
        return works.definition(projectId, sessionId, definitionId);
    }
    @PostMapping("/runs/{runId}/cancel")
    void cancel(@PathVariable String projectId, @PathVariable String sessionId, @PathVariable String runId) {
        works.cancel(projectId, sessionId, runId);
    }
}
