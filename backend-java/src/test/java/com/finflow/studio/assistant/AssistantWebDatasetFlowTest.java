package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.workspace.WorkspaceResourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-web-dataset-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=true"
})
@Import(AssistantWebDatasetFlowTest.WebDatasetWorkerConfiguration.class)
class AssistantWebDatasetFlowTest {
    @Autowired AssistantExecutionService execution;
    @Autowired ProjectService projects;
    @Autowired WorkspaceResourceService workspace;
    @Autowired KnowledgeService knowledge;
    @Autowired ObjectMapper objectMapper;

    @Test
    void materializesAWebJsonResourceBeforeTransformingIt() throws Exception {
        var project = projects.create("公开时间序列", "验证通用网页数据抽取");
        var effects = new LinkedHashMap<String, Object>();

        execution.executeStep(step("resource.add", Map.of(
                "project_id", project.id(), "url", "https://example.test/series.json", "name", "公开指标")), effects);
        var webResourceId = effects.get("resourceId").toString();
        execution.executeStep(step("resource.read", Map.of(
                "project_id", project.id(), "resource_id", webResourceId)), effects);

        var extractMessage = execution.executeStep(step("dataset.extract", Map.of(
                "project_id", project.id(), "resource_id", webResourceId,
                "schema", Map.of("mapping", "observations"), "target_name", "月度公开指标")), effects);
        var datasetId = effects.get("datasetId").toString();

        assertThat(extractMessage).contains("2 行数据");
        assertThat(datasetId).isNotEqualTo(webResourceId);
        assertThat(workspace.get(project.id()).resources()).anySatisfy(resource -> {
            assertThat(resource.id()).isEqualTo(datasetId);
            assertThat(resource.resourceType()).isEqualTo("DATA_FILE");
        });
        var rows = objectMapper.<List<Map<String, Object>>>readValue(
                Files.readString(knowledge.filePath(datasetId, null)), new TypeReference<>() { });
        assertThat(rows).extracting(row -> row.get("period")).containsExactly("2026-01", "2026-02");
        @SuppressWarnings("unchecked")
        var provenance = (Map<String, Object>) effects.get("datasetProvenance");
        assertThat(provenance)
                .containsEntry("sourceResourceId", webResourceId)
                .containsEntry("sourcePath", "observations")
                .containsEntry("rowCount", 2);

        var transformMessage = execution.executeStep(step("dataset.transform", Map.of(
                "project_id", project.id(), "dataset_id", datasetId,
                "requirements", "保留月份和数值", "script", "SELECT * FROM source",
                "target_name", "月度公开指标-整理.csv")), effects);

        assertThat(transformMessage).contains("已生成加工数据集");
        assertThat(effects.get("datasetId")).isNotEqualTo(webResourceId).isNotEqualTo(datasetId);
    }

    private PlanStep step(String tool, Map<String, Object> arguments) {
        return new PlanStep("test-" + tool, 1, tool, "WRITE", tool, tool, arguments,
                RiskLevel.CREATE_VERSION, false, "PENDING");
    }

    @TestConfiguration
    static class WebDatasetWorkerConfiguration {
        @Bean
        @Primary
        WorkerClient webDatasetWorker() {
            return new WorkerClient("http://127.0.0.1:9") {
                @Override
                public Map<String, Object> fetchResearchSource(String url) {
                    return Map.of(
                            "title", "Public monthly series",
                            "text", """
                                    {"provider":"example","observations":[
                                      {"period":"2026-01","value":12.5},
                                      {"period":"2026-02","value":13.2}
                                    ]}
                                    """,
                            "tables", List.of(), "content_type", "application/json",
                            "final_url", url, "content_hash", "sha256:test-source");
                }

                @Override
                public ParsedDocument parse(Path path, String originalName) {
                    return new ParsedDocument(originalName, "application/json", originalName,
                            0, List.of(), List.of());
                }

                @Override
                public Map<String, Object> sampleDataTransform(List<DataTransformInput> inputs,
                                                               String metadataJson, String script) {
                    assertThat(inputs).singleElement().satisfies(input -> {
                        assertThat(input.alias()).isEqualTo("source");
                        assertThat(input.name()).endsWith(".json");
                        assertThat(input.path()).isRegularFile();
                    });
                    return Map.of("valid", true, "sampleRowCount", 2);
                }

                @Override
                public void runDataTransform(List<DataTransformInput> inputs, String metadataJson,
                                             String script, Path target) {
                    try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
                        writeEntry(output, "result.csv", "period,value\n2026-01,12.5\n2026-02,13.2\n");
                        writeEntry(output, "quality.json", "{\"valid\":true,\"rowCount\":2}");
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }

                private void writeEntry(ZipOutputStream output, String name, String content) throws IOException {
                    output.putNextEntry(new ZipEntry(name));
                    output.write(content.getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            };
        }
    }
}
