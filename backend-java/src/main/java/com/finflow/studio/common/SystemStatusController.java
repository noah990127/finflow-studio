package com.finflow.studio.common;

import com.finflow.studio.worker.WorkerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final WorkerClient worker;
    private final String dataFormulatorUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    public SystemStatusController(WorkerClient worker,
                                  @Value("${finflow.data-formulator.url:http://127.0.0.1:5567}") String dataFormulatorUrl) {
        this.worker = worker;
        this.dataFormulatorUrl = dataFormulatorUrl.replaceAll("/$", "");
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        var workerStatus = worker.health();
        return Map.of(
                "java", Map.of("status", "online", "version", Runtime.version().feature()),
                "pythonWorker", workerStatus,
                "dataFormulator", dataFormulatorStatus(),
                "llm", Map.of(
                        "provider", workerStatus.getOrDefault("llmProvider", "local"),
                        "model", workerStatus.getOrDefault("llmModel", "local-extractive"),
                        "configured", workerStatus.getOrDefault("llmConfigured", false),
                        "assistantPlanner", Boolean.TRUE.equals(workerStatus.get("llmConfigured")) ? "model" : "local-rules")
        );
    }

    private Map<String, Object> dataFormulatorStatus() {
        try {
            var request = HttpRequest.newBuilder(URI.create(dataFormulatorUrl + "/"))
                    .timeout(Duration.ofMillis(1200))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            var online = response.statusCode() >= 200 && response.statusCode() < 500;
            return Map.of(
                    "status", online ? "online" : "offline",
                    "online", online,
                    "url", dataFormulatorUrl,
                    "message", online ? "报告设计器可以使用" : "报告设计器没有响应"
            );
        } catch (Exception ignored) {
            return Map.of(
                    "status", "offline",
                    "online", false,
                    "url", dataFormulatorUrl,
                    "message", "Data Formulator 尚未启动"
            );
        }
    }
}
