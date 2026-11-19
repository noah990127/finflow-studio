package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class AgentWorkController {
    private final AgentWorkService works;
    public AgentWorkController(AgentWorkService works) { this.works = works; }

    @GetMapping("/works")
    List<AgentWorkService.Work> list(@PathVariable String projectId) { return works.list(projectId); }

    public record Rename(String title) { }
    @PatchMapping("/works/{sessionId}/title")
    void rename(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody Rename request) {
        works.rename(projectId, sessionId, request.title());
    }

    @GetMapping("/artifact-reviews")
    List<AgentWorkService.Review> reviews(@PathVariable String projectId) { return works.reviews(projectId); }

    @PostMapping("/artifact-reviews")
    AgentWorkService.Review review(@PathVariable String projectId, @RequestBody AgentWorkService.ResourceRef ref) {
        return works.review(projectId, ref);
    }
}
