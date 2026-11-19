package com.finflow.studio.data;

import com.finflow.studio.data.DataModels.CreateConnectionRequest;
import com.finflow.studio.data.DataModels.CreateExtractRequest;
import com.finflow.studio.data.DataModels.SourceType;
import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-extract-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=false",
        "finflow.storage.root=${java.io.tmpdir}/finflow-extract-test",
        "finflow.data.connection-key-file=${java.io.tmpdir}/finflow-extract-test/data-connection.key",
        "finflow.extract.progress-interval=1000"
})
class ExtractJobFlowTest {
    @Autowired ProjectService projects;
    @Autowired DataConnectionService connections;
    @Autowired ExtractJobService extracts;
    @Autowired ReadOnlySqlValidator validator;

    @Test
    void streamsDuckDbQueryToChecksummedCsv() throws Exception {
        var project = projects.create("抽取测试", "DuckDB 流式抽取");
        var connection = connections.create(new CreateConnectionRequest(project.id(), "本地 DuckDB",
                SourceType.DUCKDB, "jdbc:duckdb:", "", "", Map.of()));

        var testResult = connections.test(connection.id());
        assertThat(testResult.success()).isTrue();

        var job = extracts.create(new CreateExtractRequest(project.id(), connection.id(), "千万行方案验证",
                "select i, i * 2 as value from range(0, 10000) t(i)", 500, "stream-test.csv"));
        var deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            job = extracts.get(job.id());
            if (java.util.List.of("SUCCEEDED", "FAILED", "CANCELED").contains(job.status())) break;
            Thread.sleep(100);
        }

        assertThat(job.status()).as(job.errorMessage()).isEqualTo("SUCCEEDED");
        assertThat(job.rowCount()).isEqualTo(10_000);
        assertThat(job.checksum()).startsWith("sha256:");
        assertThat(Files.size(extracts.outputPath(job.id()))).isGreaterThan(10_000);
    }

    @Test
    void rejectsMutatingOrMultipleStatements() {
        assertThatThrownBy(() -> validator.validate("delete from account"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("select 1; select 2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptsUserEnteredConnectionPasswordAndNeverReturnsItForDisplay() {
        var project = projects.create("连接密码测试", "表单密码应加密保存");
        var created = connections.create(new CreateConnectionRequest(project.id(), "预算数据库",
                SourceType.DUCKDB, "jdbc:duckdb:", "budget_user", "plain-password", Map.of()));

        var stored = connections.get(created.id());
        assertThat(stored.secretRef()).startsWith("enc:v1:").doesNotContain("plain-password");
        assertThat(connections.resolveSecret(stored)).isEqualTo("plain-password");
        assertThat(created.secretRef()).isEqualTo("已配置");
        assertThat(connections.list(project.id()).stream().filter(item -> item.id().equals(created.id()))
                .map(connections::safeForDisplay).findFirst().orElseThrow().secretRef())
                .isEqualTo("已配置");
    }
}
