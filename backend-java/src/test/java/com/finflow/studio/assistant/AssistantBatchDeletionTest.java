package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.assistant.AssistantModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:batch-delete;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=./target/batch-delete-storage", "finflow.ai.enabled=false", "finflow.worker.base-url=http://127.0.0.1:1"})
class AssistantBatchDeletionTest {
    @Autowired ProjectService projects;
    @Autowired WorkflowDefinitionService workflows;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired AssistantWorkspaceToolGateway gateway;
    @Autowired com.finflow.studio.knowledge.KnowledgeService files;

    PlanStep step(String tool, Map<String, Object> args) {
        return new PlanStep(UUID.randomUUID().toString(), 1, tool, "WRITE", "delete", "delete", args,
                RiskLevel.DESTRUCTIVE_OR_EXTERNAL, true, "PENDING");
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> snapshot(String project) {
        var effects = new HashMap<String, Object>();
        gateway.execute(step("resource.prepare_delete", Map.of("project_id", project, "scope", "project_contents")), effects);
        return (List<Map<String, Object>>) ((Map<?, ?>) effects.get("deletionPreview")).get("items");
    }
    String create(String project, String name) {
        return workflows.create(project, new SaveRequest(name, "", List.of(), List.of())).id();
    }
    @Test void deletesFullFrozenListButKeepsLaterObjects() {
        var project = projects.create("batch", "").id();
        for (int i = 0; i < 15; i++) create(project, "item" + i);
        var items = snapshot(project);
        assertThat(items).hasSize(15);
        var later = create(project, "later");
        var effects = new HashMap<String, Object>();
        var args = Map.<String, Object>of("project_id", project, "items", items);
        assertThat(gateway.deletionDescription(args)).contains("15", "item14");
        gateway.execute(step("resource.delete_batch", args), effects);
        assertThat(workflows.list(project)).extracting(w -> w.id()).containsExactly(later);
        assertThat(((Map<?, ?>) effects.get("deletionResult")).get("count")).isEqualTo(15);
        assertThat(((Map<?, ?>) effects.get("deletionResult")).get("verified")).isEqualTo(true);
    }
    @Test void changedVersionRejectsEntireBatch() {
        var project = projects.create("stale", "").id();
        create(project, "first");
        var changed = create(project, "second");
        var items = snapshot(project);
        workflows.update(changed, new SaveRequest("changed", "", List.of(), List.of()));
        assertThatThrownBy(() -> gateway.execute(step("resource.delete_batch", Map.of("project_id", project, "items", items)), new HashMap<>()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(workflows.list(project)).hasSize(2);
    }
    @Test void rejectsDuplicatesAndForeignProject() {
        var project = projects.create("owned", "").id();
        create(project, "one");
        var items = snapshot(project);
        var other = projects.create("other", "").id();
        assertThatThrownBy(() -> gateway.execute(step("resource.delete_batch", Map.of("project_id", other, "items", items)), new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.execute(step("resource.delete_batch", Map.of("project_id", project, "items", List.of(items.getFirst(), items.getFirst()))), new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workflows.list(project)).hasSize(1);
    }

    @Test void failureInLaterDeletionRollsBackEarlierDeletion() {
        var project = projects.create("rollback", "").id();
        create(project, "first");
        create(project, "second");
        var items = snapshot(project);
        var lastId = items.getLast().get("resource_id").toString();
        jdbc.sql("insert into workflow_run(id, workflow_id, project_id, workflow_version, status, trace_id, created_at) values (:id, :workflow, :project, 1, 'RUNNING', 'test', CURRENT_TIMESTAMP)")
                .param("id", UUID.randomUUID().toString()).param("workflow", lastId).param("project", project).update();
        var effects = new HashMap<String, Object>();
        assertThatThrownBy(() -> gateway.execute(step("resource.delete_batch", Map.of("project_id", project, "items", items)), effects))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("正在执行");
        assertThat(workflows.list(project)).hasSize(2);
        assertThat(effects).doesNotContainKey("deletionResult");
    }

    @Test void deletesFilesAndWorkflowsTogether() {
        var project = projects.create("mixed", "").id();
        create(project, "workflow");
        files.upload(project, null, new org.springframework.mock.web.MockMultipartFile("file", "data.csv", "text/csv", "x\n1".getBytes()));
        var items = snapshot(project);
        assertThat(items).hasSize(2);
        gateway.execute(step("resource.delete_batch", Map.of("project_id", project, "items", items)), new HashMap<>());
        assertThat(snapshot(project)).isEmpty();
    }
}
