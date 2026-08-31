package com.finflow.studio.common;

import com.finflow.studio.worker.WorkerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final WorkerClient worker;

    public SystemStatusController(WorkerClient worker) {
        this.worker = worker;
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        var workerStatus = worker.health();
        return Map.of(
                "java", Map.of("status", "online", "version", Runtime.version().feature()),
                "pythonWorker", workerStatus,
                "reportEngine", Map.of("status", "online", "renderer", "ECharts", "explorer", "Perspective"),
                "llm", Map.of(
                        "provider", workerStatus.getOrDefault("llmProvider", "local"),
                        "model", workerStatus.getOrDefault("llmModel", "local-extractive"),
                        "configured", workerStatus.getOrDefault("llmConfigured", false),
                        "assistantPlanner", Boolean.TRUE.equals(workerStatus.get("llmConfigured")) ? "model" : "local-rules")
        );
    }

}
