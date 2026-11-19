package com.finflow.studio.assistant;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:codex-work;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=false", "test.agent.runtime=codex-cli"})
class CodexWorkFlowTest extends OpenCodeWorkFlowTest {
}
