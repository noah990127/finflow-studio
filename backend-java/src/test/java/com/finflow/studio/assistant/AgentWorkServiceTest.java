package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-work-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=false", "finflow.ai.enabled=false",
        "finflow.worker.base-url=http://127.0.0.1:1", "finflow.storage.root=./target/agent-work-test-storage"
})
class AgentWorkServiceTest {
    @Autowired AgentWorkService works;
    @Autowired ProjectService projects;
    @Autowired AssistantService assistant;
    @Autowired AssistantExecutionService execution;
    @Autowired KnowledgeService files;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired BrowserAgentService browserAgent;

    String seed(String projectId) {
        var session = assistant.createSession(projectId, "经营分析");
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
            insert into assistant_context_snapshot(id, session_id, project_id, page, selection_json, allowed_resource_ids_json,
              resource_versions_json, context_hash, expires_at, created_at)
            values (:id, :session, :project, 'project-home', '{}', '[]', '{}', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """).param("id", id).param("session", session.id()).param("project", projectId).update();
        jdbc.sql("""
            insert into assistant_plan(id, session_id, context_snapshot_id, goal, summary, version, plan_hash, risk_level,
              status, affected_resources_json, expires_at, created_at, execution_mode)
            values (:id, :session, :id, '经营分析', '', 1, 'hash', 'READ_ONLY', 'RUNNING', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'AUTO')
            """).param("id", id).param("session", session.id()).update();
        jdbc.sql("""
            insert into assistant_run(id, session_id, plan_id, idempotency_key, trace_id, status, created_at, gateway_token)
            values (:id, :session, :id, :id, :id, 'RUNNING', CURRENT_TIMESTAMP, 'test-token')
            """).param("id", id).param("session", session.id()).update();
        return id;
    }

    @Test void persistsBusinessStagesAndListsOnlyTheCurrentProject() {
        var p = projects.create("工序测试", ""); var other = projects.create("另一项目", "");
        var run = seed(p.id());
        var stages = List.of(new AgentWorkService.Stage("check", "核对差异", "WAITING_INPUT", "发现跨月退款",
                "退款按哪个月份归属？", List.of(), List.of()));
        works.update(run, Map.of("stages", stages));
        assertThat(works.list(p.id()).getFirst().stages()).isEqualTo(stages);
        assertThat(works.list(other.id())).isEmpty();
        try (var ignored = ActorContext.bind("another-user")) {
            assertThatThrownBy(() -> works.list(p.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> works.update(run, Map.of("stages", stages))).isInstanceOf(IllegalArgumentException.class);
        }
        jdbc.sql("update assistant_run set status = 'CANCELED' where id = :id").param("id", run).update();
        assertThatThrownBy(() -> works.update(run, Map.of("stages", stages))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void browserAttachmentsAreArchivedEvenWhenPublicationFails() {
        var project = projects.create("浏览器附件归档", ""); var run = seed(project.id());
        var session = jdbc.sql("select session_id from assistant_run where id=:id").param("id", run).query(String.class).single();
        var taskId = UUID.randomUUID().toString(); var step = UUID.randomUUID().toString();
        jdbc.sql("insert into assistant_plan_step(id,plan_id,step_order,tool_name,tool_mode,title,description,arguments_json,risk_level,requires_confirmation,status) values(:id,:plan,1,'browser.delegate','WRITE','浏览器','','{}','READ_ONLY',false,'SUCCEEDED')")
                .param("id", step).param("plan", run).update();
        var task = Map.of("id", taskId, "status", Map.of("state", "completed"), "metadata", Map.of("procedureError", "需要整理动作", "procedureState", "NEEDS_REVIEW"),
                "artifacts", List.of(Map.of("parts", List.of(Map.of("file", Map.of("name", "report.md", "mimeType", "text/markdown", "bytes", "IyBSZXBvcnQ="))))));
        jdbc.sql("insert into browser_agent_task(task_id,step_id,owner_id,session_id,last_result) values(:id,:step,:owner,:session,:result)")
                .param("id", taskId).param("step", step).param("owner", ActorContext.current()).param("session", session).param("result", json.writeValueAsString(task)).update();
        var first = browserAgent.status(taskId);
        assertThat(first).doesNotContainKey("archiveIssue");
        assertThat((List<?>) first.get("archivedResources")).hasSize(1);
        assertThat(browserAgent.status(taskId).get("archivedResources")).isEqualTo(first.get("archivedResources"));
        assertThat(jdbc.sql("select count(*) from browser_task_artifact where task_id=:id").param("id", taskId).query(Integer.class).single()).isEqualTo(1);
        assertThat(first).containsEntry("procedureState", "NEEDS_REVIEW").doesNotContainKey("savedProcedure");
    }

    @Test void reviewsAreDurableAndBoundToArtifactVersion() {
        var p = projects.create("产物检查", "");
        var file = files.upload(p.id(), null, new MockMultipartFile("file", "sales.csv", "text/csv", "region,sales\neast,10".getBytes()));
        works.review(p.id(), new AgentWorkService.ResourceRef(file.id(), 1));
        assertThat(works.reviews(p.id())).hasSize(1);
        var updated = files.upload(p.id(), file.id(), new MockMultipartFile("file", "sales.csv", "text/csv", "region,sales\neast,20".getBytes()));
        assertThat(updated.currentVersion()).isEqualTo(2);
        assertThatThrownBy(() -> works.review(p.id(), new AgentWorkService.ResourceRef(file.id(), 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已更新");
        assertThat(works.reviews(p.id()).getFirst().version()).isEqualTo(1);
        works.review(p.id(), new AgentWorkService.ResourceRef(file.id(), 2));
        assertThat(works.reviews(p.id())).hasSize(2);
        var other = projects.create("不可混用", "");
        assertThatThrownBy(() -> works.review(other.id(), new AgentWorkService.ResourceRef(file.id(), 2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void gatewayRejectsMissingTokenAndCrossProjectOrStaleArtifactRefs() {
        var p = projects.create("网关", ""); var run = seed(p.id());
        var stage = new AgentWorkService.Stage("s", "形成汇报", "SUCCEEDED", "报告已保存", "", List.of(),
                List.of(new AgentWorkService.ResourceRef("invented-id", 1)));
        var args = json.convertValue(Map.of("stages", List.of(stage)), Map.class);
        assertThatThrownBy(() -> execution.callContinuousTool(run, "wrong-token", Map.of("tool", "work.update", "arguments", args)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> works.update(run, args)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("当前项目");
    }

    @Test void rejectsDuplicateStagesAndEmptySuccess() {
        var stage = new AgentWorkService.Stage("s", "整理资料", "PENDING", "", "", List.of(), List.of());
        assertThatThrownBy(() -> AgentWorkService.validateStages(List.of(stage, stage))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentWorkService.validateStages(List.of(new AgentWorkService.Stage("s", "汇报", "SUCCEEDED", "", "", List.of(), List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workTitleIsIndependentOfOriginalGoalAndScopedToProject() {
        var p = projects.create("标题测试", ""); var run = seed(p.id());
        var stage = new AgentWorkService.Stage("s", "分析差异", "PENDING", "", "", List.of(), List.of());
        works.update(run, Map.of("title", "月度预算差异分析", "stages", List.of(stage)));
        var work = works.list(p.id()).getFirst();
        assertThat(work.title()).isEqualTo("月度预算差异分析");
        assertThat(work.goal()).isEqualTo("经营分析");
        works.rename(p.id(), work.sessionId(), "季度经营复核");
        assertThat(works.list(p.id()).getFirst().title()).isEqualTo("季度经营复核");
        assertThat(jdbc.sql("select title from assistant_session where id = :id").param("id", work.sessionId()).query(String.class).single()).isEqualTo("经营分析");
        var other = projects.create("其他项目", "");
        assertThatThrownBy(() -> works.rename(other.id(), work.sessionId(), "越界修改")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> works.rename(p.id(), work.sessionId(), " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nextTurnInheritsPendingStagesRatherThanInventingVersionChanges() {
        var p = projects.create("历史工序", ""); var original = seed(p.id());
        works.update(original, Map.of("stages", List.of(new AgentWorkService.Stage(
                "quote", "查询", "RUNNING", "", "", List.of(), List.of()))));
        var next = seed(p.id());
        jdbc.sql("update assistant_run set session_id=(select session_id from assistant_run where id=:old), created_at=DATEADD('SECOND',1,current_timestamp) where id=:next")
                .param("old", original).param("next", next).update();
        assertThat(works.list(p.id()).getFirst().stages()).extracting(AgentWorkService.Stage::status)
                .containsExactly("PENDING");
    }

    @Test void sourceVersionChangesInvalidateDependentStages() {
        var p = projects.create("依赖版本", ""); var run = seed(p.id());
        var source = files.upload(p.id(), null, new MockMultipartFile("file", "source.csv", "text/csv", "x\n1".getBytes()));
        var output = files.upload(p.id(), null, new MockMultipartFile("file", "output.csv", "text/csv", "x\n1".getBytes()));
        var stages = List.of(
                new AgentWorkService.Stage("a", "核对", "SUCCEEDED", "已核对", "", List.of(new AgentWorkService.ResourceRef(source.id(), 1)), List.of(new AgentWorkService.ResourceRef(output.id(), 1))),
                new AgentWorkService.Stage("b", "形成汇报", "SUCCEEDED", "结论已记录", "", List.of(new AgentWorkService.ResourceRef(output.id(), 1)), List.of()));
        works.update(run, Map.of("stages", stages));
        files.upload(p.id(), source.id(), new MockMultipartFile("file", "source.csv", "text/csv", "x\n2".getBytes()));
        assertThat(works.list(p.id()).getFirst().stages()).extracting(AgentWorkService.Stage::status).containsExactly("STALE", "STALE");
    }

    @Test void autoModeStillRequiresAuthorizationForDestructiveTools() {
        var p = projects.create("危险操作", ""); var run = seed(p.id());
        var response = execution.callContinuousTool(run, "test-token", Map.of("tool", "project.delete", "arguments", Map.of("project_id", p.id())));
        assertThat(response.get("failureType")).isEqualTo("AUTHORIZATION");
        assertThat(projects.get(p.id()).id()).isEqualTo(p.id());
    }
}
