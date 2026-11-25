package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/browser/agent-tasks")
public class BrowserAgentController {
    private final BrowserAgentService service;
    public BrowserAgentController(BrowserAgentService service) { this.service = service; }
    @GetMapping public List<Map<String, Object>> list(@RequestParam String sessionId) { return service.list(sessionId); }
    @GetMapping("/{id}/files/{artifact}/{part}") public org.springframework.http.ResponseEntity<byte[]> download(
            @PathVariable String id, @PathVariable int artifact, @PathVariable int part) {
        var file = service.download(id, artifact, part);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
    public record Control(int promptId) { }
    @PostMapping("/{id}/{action}") public Map<String, Object> control(@PathVariable String id,
            @PathVariable String action, @RequestBody Control body) { return service.control(id, action, body.promptId()); }
}
