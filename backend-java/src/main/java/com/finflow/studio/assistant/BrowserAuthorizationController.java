package com.finflow.studio.assistant;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/browser/authorizations")
public class BrowserAuthorizationController {
    private final BrowserAuthorizationService service;
    public BrowserAuthorizationController(BrowserAuthorizationService service) { this.service = service; }
    @GetMapping("/connections") public List<java.util.Map<String, Object>> connections() { return service.connections(); }
    @PostMapping("/connections/{id}/{action}") public java.util.Map<String, Object> control(@PathVariable String id, @PathVariable String action) {
        return service.control(id, action);
    }
    @GetMapping public List<BrowserAuthorizationService.Grant> list() { return service.grants(); }
    @GetMapping("/history") public List<BrowserAuthorizationService.Audit> history(@RequestParam(defaultValue="0") int page) { return service.history(page); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id) { service.revoke(id); }
}
