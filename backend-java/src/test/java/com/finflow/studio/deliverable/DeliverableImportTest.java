package com.finflow.studio.deliverable;

import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-deliverable-import-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=${java.io.tmpdir}/finflow-deliverable-import-test"
})
class DeliverableImportTest {
    @Autowired ProjectService projects;
    @Autowired DeliverableService deliverables;

    @Test
    void importsPortableArtifactAndAddsAChangedVersion() throws Exception {
        var project = projects.create("案例快照", "导入精选输出件");
        var sourceSpec = """
                {"sections":[{"heading":"经营判断","refs":[{"ref_id":"ref-1","source_name":"年度报告","text":"收入增长","location":{"page":3}}]}]}
                """;
        var first = new MockMultipartFile("file", "report.json", "application/json",
                "{\"schema_version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8));
        var created = deliverables.importArtifact(project.id(), null, "经营交互报告",
                "financial_report", sourceSpec, first);

        assertThat(created.currentVersion()).isEqualTo(1);
        assertThat(deliverables.path(created.id(), null)).hasContent("{\"schema_version\":\"1.0\"}");
        assertThat(deliverables.citations(created.id(), null)).singleElement()
                .satisfies(citation -> assertThat(citation).containsEntry("source_name", "年度报告"));

        var second = new MockMultipartFile("file", "report.json", "application/json",
                "{\"schema_version\":\"1.1\"}".getBytes(StandardCharsets.UTF_8));
        var updated = deliverables.importArtifact(project.id(), created.id(), "经营交互报告",
                "financial_report", sourceSpec, second);

        assertThat(updated.currentVersion()).isEqualTo(2);
        assertThat(deliverables.path(created.id(), 1)).hasContent("{\"schema_version\":\"1.0\"}");
        assertThat(deliverables.path(created.id(), 2)).hasContent("{\"schema_version\":\"1.1\"}");
    }

    @Test
    void rejectsArtifactWithMismatchedExtension() {
        var project = projects.create("格式校验", "拒绝错误格式");
        var file = new MockMultipartFile("file", "report.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> deliverables.importArtifact(project.id(), null, "管理层汇报",
                "pptx", "{}", file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".pptx");
    }
}
