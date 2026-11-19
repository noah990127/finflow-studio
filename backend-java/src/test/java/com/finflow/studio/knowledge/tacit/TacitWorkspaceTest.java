package com.finflow.studio.knowledge.tacit;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tacit-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class TacitWorkspaceTest {
    @Autowired TacitWorkspaceService service;
    @Autowired ProjectService projects;
    @Autowired JdbcClient jdbc;
    @MockitoBean WorkerClient worker;
    String project;

    @BeforeEach
    void setup() {
        project = projects.create("隐性知识测试", "").id();
        when(worker.tacitCommand(any())).thenAnswer(invocation -> transition(invocation.getArgument(0)));
    }

    private Map<String, Object> transition(Map<String, Object> input) {
        var state = new HashMap<>(TacitJson.map(input.get("state")));
        state.put("spec", Map.of("last_action", input.get("action")));
        if ("generate_flow".equals(input.get("action"))) {
            state.put("artifacts", Map.of("flows", Map.of("content", "flow-v1")));
        }
        if ("review_flow".equals(input.get("action"))) {
            state.put("artifacts", Map.of("flows", Map.of("review", Map.of(
                    "decision", "approved", "digest", TacitJson.hash("flow-v1")))));
        }
        var catalog = (List<?>) input.get("catalog");
        return Map.of("state", state, "result", Map.of("sources", catalog.size()));
    }

    @Test
    void repeatedCommandReturnsReceiptAndStaleOrReusedCommandFails() {
        var first = service.executeHuman(project, "save_batch", 0, "command-1", Map.of());
        assertThat(service.executeHuman(project, "save_batch", 0, "command-1", Map.of())).isEqualTo(first);
        assertThat(service.workspace(project)).containsEntry("revision", 1L);
        assertThatThrownBy(() -> service.executeHuman(project, "save_batch", 0, "different", Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.executeHuman(project, "generate_flow", 1, "command-1", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compactTransportRetainsReceiptSemanticsForDefaultAndOptInClients() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var controller = new TacitController(service);
        var compact = json.readValue("""
                {"action":"generate_flow","expected_revision":0,"request_id":"flow","payload":{},"compact":true}
                """, TacitController.Command.class);
        var reply = controller.execute(project, compact);
        assertThat(reply).isEqualTo(Map.of("workspace", Map.of("revision", 1L), "result", Map.of("sources", 0)));
        assertThat(controller.execute(project, compact)).isEqualTo(reply);
        var legacy = json.readValue("""
                {"action":"generate_flow","expected_revision":0,"request_id":"flow","payload":{}}
                """, TacitController.Command.class);
        var replay = controller.execute(project, legacy);
        assertThat(TacitJson.map(replay.get("workspace"))).containsEntry("revision", 1L).containsKeys("spec", "artifacts");
        assertThat(replay.get("result")).isEqualTo(reply.get("result"));
        service.executeHuman(project, "save_batch", 1, "following", Map.of(), true);
        var late = controller.execute(project, compact);
        assertThat(late).isEqualTo(Map.of("workspace", Map.of("revision", 2L),
                "result", Map.of("already_applied", true, "committed_revision", 1L)));
    }

    @Test
    void ledgerReadsOmitDerivedArtifactsAndResultOnlyResponsesNeverReplaceSavedState() {
        service.executeHuman(project, "generate_flow", 0, "start", Map.of());
        var artifactReads = List.of("search_context", "check_candidates");
        doAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            assertThat(input).containsEntry("result_only", true);
            var state = TacitJson.map(input.get("state"));
            if (artifactReads.contains(input.get("action"))) assertThat(state).containsKey("artifacts");
            else assertThat(state).doesNotContainKey("artifacts");
            return Map.of("state", Map.of(), "result", Map.of("marker", 1));
        }).when(worker).tacitCommand(any());
        for (var action : List.of("preview_source", "read_section", "read_fields", "read_clauses",
                "search_context", "check_candidates")) {
            var reply = service.executeHuman(project, action, 1, action, Map.of(), true);
            assertThat(reply).isEqualTo(Map.of("workspace", Map.of("revision", 1L), "result", Map.of("marker", 1)));
        }
        var legacy = service.executeAgent(project, "discuss", "preview_source", 1, "plain", Map.of());
        assertThat(TacitJson.map(legacy.get("workspace"))).containsKeys("artifacts","spec","issues");
        assertThat(TacitJson.map(service.workspace(project).get("artifacts"))).containsKey("flows");
    }

    @Test
    void twoWritersCannotBothWin() throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            barrier.await(10, TimeUnit.SECONDS);
            return transition(call.getArgument(0));
        }).when(worker).tacitCommand(any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = List.of("c1", "c2").stream().map(key -> pool.submit(() -> {
                try { service.executeHuman(project, "save_batch", 0, key, Map.of()); return true; }
                catch (IllegalStateException ex) { return false; }
            })).toList();
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.workspace(project)).containsEntry("revision", 1L);
    }

    @Test
    void sourceVersionChangeInvalidatesCommittedRevisionAndProjectionOmitsSourceText() {
        var file = seedFile(project);
        var prior = service.executeHuman(project, "select_sources", 0, "choose", Map.of("source_ids", List.of(file)));
        var source = TacitJson.map(((List<?>) TacitJson.map(prior.get("workspace")).get("catalog")).getFirst());
        assertThat(source).containsEntry("source_id", file).containsEntry("total_characters", 6)
                .doesNotContainKeys("text", "locations");
        jdbc.sql("update file_version set parse_message='工作表超出解析上限' where resource_id=:id")
                .param("id", file).update();
        var fresh = service.workspace(project);
        assertThat(fresh).containsEntry("revision", 2L);
        assertThat(TacitJson.map(((List<?>)fresh.get("catalog")).getFirst())).containsEntry("incomplete", true);
        assertThatThrownBy(() -> service.executeHuman(project, "finalize", 1, "stale", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void viewRetriesWhenParserFinishesDuringRefresh() {
        var file = seedFile(project);
        service.executeHuman(project, "select_sources", 0, "choose", Map.of("source_ids", List.of(file)));
        jdbc.sql("update file_version set parse_status='PROCESSING' where resource_id=:id").param("id", file).update();
        var updates = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (updates.getAndIncrement() == 0) {
                jdbc.sql("update file_version set parse_status='READY', parsed_at=:now where resource_id=:id")
                        .param("id", file).param("now", Instant.now().plusSeconds(1)).update();
            }
            return transition(call.getArgument(0));
        }).when(worker).tacitCommand(any());
        var fresh = service.workspace(project);
        assertThat(updates.get()).isEqualTo(2);
        assertThat(fresh).containsEntry("revision", 2L);
        assertThat(TacitJson.map(((List<?>) fresh.get("catalog")).getFirst())).containsEntry("incomplete", false);
        assertThatThrownBy(() -> service.executeHuman(project, "finalize", 1, "stale", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOtherProjectsAndSeparatesReviewFromAgentAndDiscussion() {
        var id = seedFile(projects.create("其他项目", "").id());
        assertThatThrownBy(() -> service.executeHuman(project, "select_sources", 0, "id", Map.of("source_ids", List.of(id))))
                .isInstanceOf(IllegalArgumentException.class);
        try (var ignored = ActorContext.bind("other")) {
            assertThatThrownBy(() -> service.workspace(project)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.executeAgent(project, "discuss", "read_source", 0, "read", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executeAgent(project, "build_ontology", "review_flow", 0, "review", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executeHuman(project, "confirm_item", 0, "confirm", Map.of()))
                .isInstanceOf(SecurityException.class);
        try (var ignored = ActorContext.bind("default")) {
            service.executeHuman(project, "generate_flow", 0, "generate", Map.of());
            assertThat(service.executeHuman(project, "review_flow", 1, "review", Map.of("decision", "approved")))
                    .containsKey("workspace");
            var events = (List<?>)service.reviews(project, null, 10).get("items");
            assertThat(events).hasSize(1);
            assertThat(TacitJson.map(events.getFirst())).containsEntry("target_digest", TacitJson.hash("flow-v1"))
                    .containsEntry("actor", "default");
            assertThat(service.reviewSnapshot(project, 2)).containsEntry("target", "flow-v1");
        }
    }

    @Test
    void searchesAndChecksDoNotCommitAndReviewsCannotBeDelegated() {
        for (var action : List.of("search_context", "read_fields")) {
            assertThat(service.executeAgent(project, "discuss", action, 0, action, Map.of())).containsKey("result");
        }
        service.executeAgent(project, "build_ontology", "check_candidates", 0, "check", Map.of());
        assertThat(service.workspace(project)).containsEntry("revision", 0L);
        for (var action : List.of("align_policies", "edit_candidates", "submit_candidates", "propose_scenarios")) {
            assertThatThrownBy(() -> service.executeAgent(project, "discuss", action, 0, action, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (var action : List.of("review_candidate_item", "review_scenario", "review_alignment")) {
            assertThatThrownBy(() -> service.executeAgent(project, "build_ontology", action, 0, action, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.executeHuman(project, action, 0, action, Map.of()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void reconstructsNonBmpCharactersAndOverlappingPages() {
        var text = new TacitCatalog.ParsedText();
        text.add(0, "甲😀乙丙", Map.of("page", 1, "char_start", 0, "char_end", 4));
        text.add(1, "乙丙丁", Map.of("page", 1, "char_start", 2, "char_end", 5));
        text.add(2, "下一页", Map.of("page", 2, "char_start", 0, "char_end", 3));
        assertThat(text.text.toString()).isEqualTo("甲😀乙丙丁\n下一页");
        assertThat(text.complete).isTrue();
        assertThat(text.locations.get(1)).containsEntry("start", 6).containsEntry("end", 9);
        text.add(4, "缺页", Map.of("page", 2, "char_start", 10, "char_end", 12));
        assertThat(text.complete).isFalse();
    }

    private String seedFile(String owner) {
        String id = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
        var now = Instant.now();
        jdbc.sql("""
                insert into file_resource(id,project_id,name,media_type,status,current_version,created_at,updated_at)
                values(:id,:owner,'policy.txt','text/plain','READY',1,:now,:now)
                """).param("id", id).param("owner", owner).param("now", now).update();
        jdbc.sql("""
                insert into file_version(id,resource_id,version_number,original_name,media_type,storage_path,size_bytes,
                  checksum,parse_status,created_at,parsed_at)
                values(:v,:id,1,'policy.txt','text/plain','/fixture',6,'checksum','READY',:now,:now)
                """).param("v", version).param("id", id).param("now", now).update();
        jdbc.sql("""
                insert into knowledge_ref(id,project_id,resource_id,file_version_id,version_number,chunk_index,source_name,
                  text_content,location_json,content_hash,created_at)
                values(:key,:owner,:id,:v,1,0,'policy.txt','业务审核材料','{"char_start":0,"char_end":6}','hash',:now)
                """).param("key", id + ":0").param("owner", owner).param("id", id)
                .param("v", version).param("now", now).update();
        return id;
    }
}
