package com.finflow.studio.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerClientTest {

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
