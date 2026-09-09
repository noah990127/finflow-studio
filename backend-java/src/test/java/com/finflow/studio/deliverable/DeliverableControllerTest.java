package com.finflow.studio.deliverable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import com.finflow.studio.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-deliverable-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=${java.io.tmpdir}/finflow-deliverable-controller-test"
})
class DeliverableControllerTest {
    @Autowired ProjectService projects;
    @Autowired DeliverableService deliverables;
    @Autowired DeliverableController controller;

    @Test
    void previewsCsvDeliverableWithDeliverableStoragePath() {
        var project = projects.create("CSV 输出预览", "验证输出件可以在线访问");
        var file = new MockMultipartFile("file", "companies.csv", "text/csv",
                "company,revenue\n示例科技,100\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var created = deliverables.importArtifact(project.id(), null, "经营数据", "csv", "{}", file);

        var result = controller.previewCsv(created.id(), null, null, 50);

        assertThat(result.columns()).containsExactly("company", "revenue");
        assertThat(result.rows()).containsExactly(java.util.List.of("示例科技", "100"));
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void rejectsCsvPreviewForAnotherDeliverableFormat() {
        var project = projects.create("格式保护", "非 CSV 不进入表格预览");
        var file = new MockMultipartFile("file", "diagram.mmd", "text/plain",
                "flowchart LR\nA --> B".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var created = deliverables.importArtifact(project.id(), null, "业务流程", "mermaid", "{}", file);

        assertThatThrownBy(() -> controller.previewCsv(created.id(), null, null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 CSV");
    }
}
