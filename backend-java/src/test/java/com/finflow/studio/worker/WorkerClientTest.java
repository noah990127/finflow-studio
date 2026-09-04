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
}
