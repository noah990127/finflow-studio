package com.finflow.studio.knowledge.tacit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.Map;

@Component
class OntologyGateway {
    private final WebClient client;
    private final String secret;

    OntologyGateway(@Value("${finflow.worker.base-url}") String url,
                    @Value("${FINFLOW_AUTO_ONTOLOGY_WORKER_SECRET:}") String secret) {
        client = WebClient.builder().baseUrl(url).build();
        this.secret = secret;
    }

    Map<String, Object> target(String project) {
        if (secret.length() < 32) return disabled();
        try {
            return call("target", Map.of("project_id", project), 10);
        } catch (RuntimeException unavailable) {
            return disabled();
        }
    }

    private Map<String, Object> disabled() {
        return Map.of("configured", false, "reason", "本体发布连接未配置或服务暂时不可用");
    }

    Map<String, Object> publish(Map<String, Object> body) {
        return call("publish", body, 150);
    }

    private Map<String, Object> call(String operation, Map<String, Object> body, int seconds) {
        var result = client.post().uri("/tacit/ontology/" + operation)
                .header("X-FinFlow-Ontology-Secret", secret).bodyValue(body).retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(seconds)).block();
        if (result == null) throw new IllegalStateException("本体服务未返回结果");
        return result;
    }
}
