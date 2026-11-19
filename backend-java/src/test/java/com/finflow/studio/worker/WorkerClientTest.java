package com.finflow.studio.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerClientTest {

    @Test
    void browserStatusIsForwardedToRunner() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/status", exchange -> {
            var body = "{\"status\":\"HUMAN_CONTROL\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new WorkerClient("http://127.0.0.1:9");
            org.springframework.test.util.ReflectionTestUtils.setField(client, "browserRunnerUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            org.springframework.test.util.ReflectionTestUtils.setField(client, "browserRunnerToken", "x".repeat(32));
            assertThat(client.browser("session/status", java.util.Map.of("owner", "alice", "session", "s")).get("status")).isEqualTo("HUMAN_CONTROL");
        } finally { server.stop(0); }
    }

    @Test
    void browserAuthenticationFailureIsActionableEvenWithEmptyBody() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sites", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new WorkerClient("http://127.0.0.1:9");
            org.springframework.test.util.ReflectionTestUtils.setField(client, "browserRunnerUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            org.springframework.test.util.ReflectionTestUtils.setField(client, "browserRunnerToken", "x".repeat(32));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.browser("sites", java.util.Map.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("浏览器服务鉴权失败");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesWorkerErrorDetailInsteadOfGenericHttpStatus() {
        assertThat(WorkerClient.workerError(503, "{\"detail\":\"Codex CLI 生成超时\"}"))
                .isEqualTo("Codex CLI 生成超时");
    }

    @Test
    void fallsBackToCompactTransportErrorForInvalidResponse() {
        assertThat(WorkerClient.workerError(503, "upstream unavailable"))
                .isEqualTo("Agent 服务请求失败（HTTP 503）");
    }

    @Test
    void exposesPydanticValidationDetails() {
        var body = """
                {"detail":[{"loc":["body","source_text"],"msg":"String should have at most 200000 characters"}]}
                """;

        assertThat(WorkerClient.workerError(422, body))
                .isEqualTo("Worker 参数校验失败（source_text：String should have at most 200000 characters）");
    }
}
