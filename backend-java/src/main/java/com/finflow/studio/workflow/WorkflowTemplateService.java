package com.finflow.studio.workflow;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowModels.*;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowTemplateService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;
    private final WorkflowDefinitionService workflows;

    public WorkflowTemplateService(JdbcClient jdbc, ObjectMapper objectMapper, ProjectService projects,
                                   WorkflowDefinitionService workflows) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.workflows = workflows;
    }

    @PostConstruct
    void seedBuiltIn() {
        var count = jdbc.sql("select count(*) from workflow_template where built_in = true").query(Long.class).single();
        if (count > 0) return;
        var research = new NodeDefinition("research", NodeType.AGENT_TASK, "研究与分析", 180, 180,
                Map.of("instruction", "结合项目资料完成研究，区分事实、推断和不确定项",
                        "externalResearch", "PUBLIC_READ", "maxToolCalls", 40, "timeoutSeconds", 600));
        createInternal(new TemplateRequest("开放研究与分析", "可读取项目资料并按需联网的通用 Agent 工作流", "通用",
                new WorkflowDocument("开放研究与分析", "从开放任务开始，可继续添加数据和输出步骤",
                        List.of(research), List.of(), ExecutionMode.MANUAL, null)), true);
    }

    public List<TemplateResponse> list() {
        return jdbc.sql("select * from workflow_template order by built_in desc, updated_at desc").query(this::map).list();
    }

    public TemplateResponse create(TemplateRequest request) { return createInternal(request, false); }

    public WorkflowResponse instantiate(String templateId, String projectId, String name) {
        projects.get(projectId);
        var template = get(templateId);
        var document = template.definition();
        return workflows.create(projectId, new SaveRequest(name == null || name.isBlank() ? document.name() : name.trim(),
                document.description(), document.nodes(), document.edges(), document.executionMode(), document.schedule(), null));
    }

    public void delete(String id) {
        var template = get(id);
        if (template.builtIn()) throw new IllegalStateException("内置模板不能删除");
        jdbc.sql("delete from workflow_template where id = :id").param("id", id).update();
    }

    private TemplateResponse createInternal(TemplateRequest request, boolean builtIn) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into workflow_template(id, name, description, category, definition_json, built_in, created_at, updated_at)
                values (:id, :name, :description, :category, :definition, :builtIn, :now, :now)
                """).param("id", id).param("name", request.name().trim())
                .param("description", request.description() == null ? "" : request.description().trim())
                .param("category", request.category() == null || request.category().isBlank() ? "通用" : request.category().trim())
                .param("definition", writeJson(request.definition())).param("builtIn", builtIn).param("now", now).update();
        return get(id);
    }

    private TemplateResponse get(String id) {
        return jdbc.sql("select * from workflow_template where id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("工作流模板不存在"));
    }

    private TemplateResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new TemplateResponse(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("category"), readDocument(rs.getString("definition_json")), rs.getBoolean("built_in"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("模板无法保存", exception); }
    }
    private WorkflowDocument readDocument(String value) {
        try { return objectMapper.readValue(value, WorkflowDocument.class); }
        catch (JacksonException exception) { throw new IllegalStateException("模板无法读取", exception); }
    }
}
