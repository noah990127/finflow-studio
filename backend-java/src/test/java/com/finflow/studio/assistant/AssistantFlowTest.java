package com.finflow.studio.assistant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.finflow.studio.assistant.AssistantModels.PlanStep;
import com.finflow.studio.assistant.AssistantModels.RiskLevel;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.ExecutionMode;
import com.finflow.studio.workflow.WorkflowModels.NodeDefinition;
import com.finflow.studio.workflow.WorkflowModels.NodeType;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.ai.enabled=false"
})
@AutoConfigureMockMvc
class AssistantFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AssistantExecutionService execution;

    @Autowired
    ProjectService projects;

    @Autowired
    WorkflowDefinitionService workflows;

    @Test
    void modifyingPlanRequiresAValidConfirmation() throws Exception {
        var project = json(postJson("/api/projects", Map.of(
                "name", "测试项目",
                "description", "助手闭环测试"
        )));
        var session = json(postJson("/api/projects/" + project.get("id").asText() + "/assistant/sessions",
                Map.of("title", "测试会话")));
        var response = json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages",
                Map.of(
                        "text", "根据当前收入字段创建一条数据清理工作流，并生成 PPT 汇报",
                        "page", "workflow",
                        "selection", Map.of(
                                "type", "dataset_columns",
                                "resourceId", "dataset-1",
                                "range", new String[]{"收入"}
                        ),
                        "clientContextVersion", 3
                )));

        var plan = response.get("plan");
        assertThat(plan.get("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(plan.get("steps")).anySatisfy(step ->
                assertThat(step.get("requiresConfirmation").asBoolean()).isTrue());

        var confirmBody = Map.of(
                "planVersion", plan.get("version").asInt(),
                "planHash", plan.get("planHash").asText(),
                "idempotencyKey", "test-key-1",
                "expectedResourceVersions", Map.of("dataset-1", 3)
        );
        var run = json(postJson("/api/assistant/plans/" + plan.get("id").asText() + "/confirm", confirmBody));
        assertThat(run.get("id").asText()).isNotBlank();
    }

    @Test
    void exposesSearchableToolCatalogWithRiskAndConfirmationPolicy() throws Exception {
        var tools = json(mockMvc.perform(post("/api/assistant/tools/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("query", "workflow"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.get("id").asText()).isEqualTo("workflow.run");
            assertThat(tool.get("risk").asText()).isEqualTo("CREATE_VERSION");
            assertThat(tool.get("requiresConfirmation").asBoolean()).isTrue();
        });
    }

    @Test
    void planningPublishesAgentActivityEvents() throws Exception {
        var project = json(postJson("/api/projects", Map.of(
                "name", "事件流测试",
                "description", "验证 Agent 活动流"
        )));
        var session = json(postJson("/api/projects/" + project.get("id").asText() + "/assistant/sessions",
                Map.of("title", "事件流")));
        json(postJson("/api/assistant/sessions/" + session.get("id").asText() + "/messages",
                Map.of("text", "打开项目概览", "page", "project-home", "clientContextVersion", 1)));

        var history = json(mockMvc.perform(get("/api/assistant/sessions/" + session.get("id").asText() + "/event-history"))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(history).anySatisfy(event -> assertThat(event.get("type").asText()).isEqualTo("agent.thinking_summary"));
        assertThat(history).anySatisfy(event -> assertThat(event.get("type").asText()).isEqualTo("agent.plan_updated"));
    }

    @Test
    void workspaceToolsCreateReadAndRunRealResources() {
        var project = projects.create("Agent 工具场景", "验证工作区与工作流工具真实执行");
        var effects = new LinkedHashMap<String, Object>();

        var folderResult = execution.executeStep(step("folder.create", Map.of(
                "project_id", project.id(), "group", "knowledge", "name", "战略资料")), effects);
        assertThat(folderResult).contains("已创建目录");
        var folderId = effects.get("folderId").toString();

        var uploadResult = execution.executeStep(step("resource.upload", Map.of(
                "project_id", project.id(), "folder_id", folderId, "file_name", "strategy-notes.md",
                "media_type", "text/markdown", "content", "2026 strategy and operating evidence")), effects);
        assertThat(uploadResult).contains("已上传");
        var resourceId = effects.get("resourceId").toString();

        var readResult = execution.executeStep(step("resource.read", Map.of(
                "project_id", project.id(), "resource_id", resourceId)), effects);
        assertThat(readResult).contains("已读取资源");
        var resource = (Map<?, ?>) effects.get("resource");
        assertThat(resource.get("content").toString()).contains("operating evidence");

        var workflow = workflows.create(project.id(), new SaveRequest(
                "Agent 可执行工作流", "真实运行检查",
                List.of(new NodeDefinition("control_1", NodeType.CONTROL, "准备成果", 120, 120, Map.of())),
                List.of(), ExecutionMode.MANUAL, null, null));
        effects.put("workflowId", workflow.id());

        var runResult = execution.executeStep(step("workflow.run", Map.of(
                "project_id", project.id(), "workflow_id", "${workflow.prepare.workflow_id}")), effects);
        assertThat(runResult).isEqualTo("工作流已执行完成");
        assertThat(effects).containsEntry("workflowRunStatus", "SUCCEEDED");

        var outputArguments = Map.<String, Object>of(
                "project_id", project.id(), "goal", "生成网页摘要", "output_formats", List.of("HTML_SLIDES"));
        execution.executeStep(step("workflow.add_outputs", outputArguments), effects);
        var duplicateResult = execution.executeStep(step("workflow.add_outputs", outputArguments), effects);
        assertThat(duplicateResult).contains("已经在工作流中");
        assertThat(workflows.get(workflow.id()).nodes().stream()
                .filter(node -> node.type() == NodeType.DELIVERABLE)).hasSize(1);

        var prepareEffects = new LinkedHashMap<String, Object>();
        execution.executeStep(step("workflow.prepare", Map.of(
                "project_id", project.id(), "goal", "分析资料并输出12页PPT和HTML网页报告")), prepareEffects);
        var prepared = workflows.get(prepareEffects.get("workflowId").toString());
        assertThat(prepared.nodes().stream()
                .filter(node -> node.type() == NodeType.DELIVERABLE)
                .map(node -> node.config().get("format")))
                .containsExactlyInAnyOrder("PPTX", "HTML_SLIDES");
    }

    private PlanStep step(String tool, Map<String, Object> arguments) {
        return new PlanStep("test-" + tool, 1, tool, "WRITE", tool, tool, arguments,
                RiskLevel.CREATE_VERSION, false, "PENDING");
    }

    private String postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
