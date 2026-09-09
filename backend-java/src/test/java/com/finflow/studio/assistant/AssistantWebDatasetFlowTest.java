package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.deliverable.DeliverableService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-web-dataset-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=true"
})
@Import(AssistantWebDatasetFlowTest.WebDatasetWorkerConfiguration.class)
class AssistantWebDatasetFlowTest {
    @Autowired AssistantExecutionService execution;
    @Autowired AssistantWorkspaceToolGateway gateway;
    @Autowired ProjectService projects;
    @Autowired WorkspaceResourceService workspace;
    @Autowired KnowledgeService knowledge;
    @Autowired DeliverableService deliverables;
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

    @Test
    void rejectsAnUnreadableUrlBeforeItChangesTheWorkspace() {
        var project = projects.create("来源验证失败", "验证失败不得入库");
        var before = workspace.get(project.id());
        var effects = new LinkedHashMap<String, Object>();

        assertThatThrownBy(() -> gateway.execute(step("resource.add", Map.of(
                "project_id", project.id(), "url", "https://example.test/unavailable", "name", "失效来源")), effects))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("验证失败，未加入工作区");

        var after = workspace.get(project.id());
        assertThat(after.workflow().currentVersion()).isEqualTo(before.workflow().currentVersion());
        assertThat(after.resources()).noneMatch(item -> "失效来源".equals(item.name()));
    }

    @Test
    void savesAVerifiedSnapshotAndCitationBeforeExposingTheSource() {
        var project = projects.create("来源验证成功", "保存来源快照");
        var effects = new LinkedHashMap<String, Object>();

        var result = gateway.execute(step("source.add_verified", Map.of(
                "project_id", project.id(), "url", "https://example.test/series.json", "name", "公开指标")), effects);

        assertThat(result).contains("已验证并添加").contains("快照");
        assertThat(effects).containsKeys("resourceId", "snapshotResourceId", "verifiedSources", "knowledgeCitations");
        assertThat(workspace.get(project.id()).resources()).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(effects.get("snapshotResourceId"));
            assertThat(item.name()).contains("网页快照");
        });
        @SuppressWarnings("unchecked")
        var citations = (List<Map<String, Object>>) effects.get("knowledgeCitations");
        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.get("resourceId")).isEqualTo(effects.get("resourceId"));
            assertThat(citation.get("version")).isEqualTo(2);
            assertThat(citation.get("contentHash")).isEqualTo("sha256:test-source");
        });
    }

    @Test
    void bindsOnlyVerifiedSourcesToGeneratedDeliverables() {
        var project = projects.create("可追溯成果", "验证来源与成果引用绑定");
        var effects = new LinkedHashMap<String, Object>();
        gateway.execute(step("source.add_verified", Map.of(
                "project_id", project.id(), "url", "https://example.test/series.json", "name", "公开指标")), effects);
        var sourceId = effects.get("resourceId").toString();

        execution.executeStep(step("deliverable.create", Map.of(
                "project_id", project.id(), "format", "HTML_SLIDES", "title", "公开指标分析",
                "goal", "根据已核验来源生成简短网页",
                "citations", List.of(Map.of("resource_id", sourceId, "usage", "月度指标数据")))), effects);

        @SuppressWarnings("unchecked")
        var outputs = (List<Map<String, Object>>) effects.get("deliverables");
        var outputId = outputs.getLast().get("id").toString();
        assertThat(deliverables.citations(outputId, null)).singleElement().satisfies(citation -> {
            assertThat(citation.get("resource_id")).isEqualTo(sourceId);
            assertThat(citation.get("version")).isEqualTo(2);
            assertThat(citation.get("content_hash")).isEqualTo("sha256:test-source");
        });
    }

    @Test
    void acceptsARequestedCitationAfterTheKnowledgeSourceWasRead() {
        var project = projects.create("已有知识来源", "读取成功的知识片段可以进入成果引用");
        var effects = new LinkedHashMap<String, Object>();
        gateway.execute(step("source.add_verified", Map.of(
                "project_id", project.id(), "url", "https://example.test/series.json", "name", "公开指标")), effects);
        @SuppressWarnings("unchecked")
        var readRefs = (List<Map<String, Object>>) effects.get("knowledgeCitations");
        var sourceId = readRefs.getFirst().get("resourceId").toString();
        effects.remove("verifiedSources");
        effects.remove("knowledgeCitations");
        effects.put("knowledgeRefs", readRefs);

        execution.executeStep(step("deliverable.create", Map.of(
                "project_id", project.id(), "format", "HTML_SLIDES", "title", "已有资料分析",
                "goal", "根据已读取资料生成简短网页",
                "citations", List.of(Map.of("resource_id", sourceId, "usage", "月度指标数据")))), effects);

        @SuppressWarnings("unchecked")
        var outputs = (List<Map<String, Object>>) effects.get("deliverables");
        var outputId = outputs.getLast().get("id").toString();
        assertThat(deliverables.citations(outputId, null)).isNotEmpty()
                .allSatisfy(citation -> assertThat(citation.get("resource_id")).isEqualTo(sourceId));
    }

    @Test
    void rejectsAnUnverifiedCitationDuringDeliverableCreation() {
        var project = projects.create("拒绝伪引用", "未读取来源不得进入成果");
        var effects = new LinkedHashMap<String, Object>();

        assertThatThrownBy(() -> execution.executeStep(step("deliverable.create", Map.of(
                "project_id", project.id(), "format", "HTML_SLIDES", "title", "无依据分析",
                "content", "测试内容", "citations", List.of(Map.of(
                        "resource_id", "unknown-source", "url", "https://example.test/unknown")))), effects))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未成功读取的来源");
    }

    @Test
    void defaultsAnalysisDeliverablesToPptWhenTheModelSelectsAnInteractiveReport() {
        var proposed = step("deliverable.create", Map.of(
                "project_id", "project-1", "format", "FINANCIAL_REPORT", "title", "深圳文化活动分析"));

        var corrected = execution.applyDeliverablePolicy(proposed, "搜索网页资料，分析一下近期深圳文化活动有哪些，怎么报名");

        assertThat(corrected.arguments()).containsEntry("format", "PPTX");
    }

    @Test
    void keepsInteractiveReportOnlyWhenTheUserExplicitlyRequestsIt() {
        var proposed = step("deliverable.create", Map.of(
                "project_id", "project-1", "format", "FINANCIAL_REPORT", "title", "活动数据看板"));

        var corrected = execution.applyDeliverablePolicy(proposed, "根据活动 CSV 生成一个可交互的数据看板");

        assertThat(corrected.arguments()).containsEntry("format", "FINANCIAL_REPORT");
    }

    @Test
    void rejectsInteractiveReportsWithoutTabularProjectData() {
        var project = projects.create("无表格数据", "只有文本资料");
        var effects = new LinkedHashMap<String, Object>();

        assertThatThrownBy(() -> execution.executeStep(step("deliverable.create", Map.of(
                "project_id", project.id(), "format", "FINANCIAL_REPORT", "title", "活动交互报告",
                "content", "已核验的活动文本资料")), effects))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CSV、TSV 或数据采集结果")
                .hasMessageContaining("PPTX");
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
                    if (url.contains("unavailable")) throw new IllegalStateException("404 Not Found");
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
                public Map<String, Object> generateContent(String format, String requirements, String sourceText) {
                    return Map.of("content", "<h1>公开指标分析</h1><p>数据来自已核验的月度公开指标。</p>");
                }

                @Override
                public byte[] generateDeliverable(String format, Object request) {
                    return "<!doctype html><html><body><h1>公开指标分析</h1></body></html>"
                            .getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public DeliverableArtifact generateDeliverableArtifact(String format, Object request) {
                    return new DeliverableArtifact(generateDeliverable(format, request),
                            Map.of("passed", true, "score", 100, "issueCount", 0,
                                    "validatorVersion", "test"));
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
