package com.finflow.studio.knowledge.tacit;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ontology-publication;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class OntologyPublicationsTest {
    static final String DIGEST = "a".repeat(64);
    static final String TARGET = "instance-A";
    static final Map<String,Object> BUNDLE = Map.of("digest", DIGEST, "model", Map.of("format_version","1.3.0"),
            "quality", Map.of("publication_ready",true), "manifest", Map.of("candidates",List.of()));
    @Autowired OntologyPublications service;
    @Autowired ProjectService projects;
    @Autowired JdbcClient jdbc;
    @MockitoBean OntologyGateway gateway;
    ActorContext.Scope actor;
    String project;

    @BeforeEach
    void setup() {
        actor = ActorContext.bind("reviewer");
        project = projects.create("模型验收", "").id();
        jdbc.sql("""
            insert into tacit_workspace(project_id,revision,selected_json,source_stamp,state_json)
            values (:p,1,'[]',:stamp,:state)
            """).param("p", project).param("stamp", TacitJson.hash("[]")).param("state", TacitJson.write(
                Map.of("artifacts", Map.of("ontology_model", Map.of("status","ready","content",BUNDLE))))).update();
        when(gateway.target(project)).thenReturn(Map.of("configured",true,"target_id",TARGET));
    }
    @AfterEach void release() { actor.close(); }
    private OntologyPublicationController.Command command(String key, long revision) {
        return new OntologyPublicationController.Command(revision, key, DIGEST, TARGET);
    }
    private Map<String,Object> verified() {
        return Map.of("status","VERIFIED","receipt",Map.of("verified",true,"digest",DIGEST,"target_id",TARGET));
    }

    @Test void repeatedRequestDuringImportIsReservedExactlyOnce() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(gateway.publish(any())).thenAnswer(call -> {
            started.countDown();
            assertThat(finish.await(8, TimeUnit.SECONDS)).isTrue();
            return verified();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try (var ignored = ActorContext.bind("reviewer")) { return service.publish(project, command("one",1)); }
            });
            try {
                assertThat(started.await(8, TimeUnit.SECONDS)).isTrue();
                var pending = service.publish(project, command("two",1));
                assertThat(pending).containsEntry("status","PUBLISHING");
                assertThat(service.retry(project, (String)pending.get("id"),1)).containsEntry("attempt",1);
            } finally { finish.countDown(); }
            var done = result.get(8,TimeUnit.SECONDS);
            assertThat(done).containsEntry("status","VERIFIED");
            assertThat(service.publish(project, command("one",1))).isEqualTo(done);
            assertThat(service.snapshot(project, (String)done.get("id"))).isEqualTo(BUNDLE);
            assertThatThrownBy(() -> service.publish(project,command("one",2))).isInstanceOf(IllegalStateException.class);
            verify(gateway,times(1)).publish(any());
        }
    }

    @Test void uncertainCommitIsReconciledReadOnlyEvenWhenCurrentWorkspaceChanges() {
        when(gateway.publish(any())).thenThrow(new IllegalStateException("connection lost"))
                .thenAnswer(call -> {
                    Map<String,Object> request = call.getArgument(0);
                    assertThat(request).containsEntry("verify_only",true).containsEntry("digest",DIGEST);
                    return verified();
                });
        var first = service.publish(project, command("lost",1));
        assertThat(first).containsEntry("status","UNCERTAIN");
        jdbc.sql("update tacit_workspace set revision=2,state_json='{}' where project_id=:p").param("p", project).update();
        var recovered = service.retry(project,(String)first.get("id"),2);
        assertThat(recovered).containsEntry("status","VERIFIED").containsEntry("attempt",2).containsEntry("source_revision",1L);
        assertThat(service.list(project,null).get("items")).isEqualTo(List.of(recovered));
    }

    @Test void mismatchedReceiptPreservesUncertaintyAndFailedReconciliationCannotTriggerWrite() {
        when(gateway.publish(any())).thenReturn(Map.of("status","VERIFIED","receipt",Map.of("verified",true,"digest","other","target_id",TARGET)),
                Map.of("status","FAILED","message","unavailable"), Map.of("status","ABSENT","receipt",Map.of()));
        var first = service.publish(project, command("bad-ack",1));
        String id = (String)first.get("id");
        assertThat(first).containsEntry("status","UNCERTAIN");
        assertThat(service.retry(project,id,1)).containsEntry("status","UNCERTAIN");
        assertThat(service.retry(project,id,1)).containsEntry("status","ABSENT");
        jdbc.sql("update tacit_workspace set revision=2 where project_id=:p").param("p",project).update();
        assertThatThrownBy(() -> service.retry(project,id,1)).isInstanceOf(IllegalStateException.class);
        verify(gateway,times(3)).publish(any());
    }

    @Test void expiredReservationCanBeProbedAndHistoricalSnapshotIsOwnerScoped() {
        when(gateway.publish(any())).thenReturn(verified());
        var first = service.publish(project, command("recover",1));
        String id = (String)first.get("id");
        jdbc.sql("update tacit_ontology_publication set status='PUBLISHING',updated_at=:past where id=:id")
                .param("past", Instant.now().minusSeconds(600)).param("id",id).update();
        when(gateway.publish(any())).thenAnswer(call -> {
            assertThat(call.<Map<String,Object>>getArgument(0)).containsEntry("verify_only",true);
            return verified();
        });
        assertThat(service.retry(project,id,1)).containsEntry("attempt",2);
        try (var ignored = ActorContext.bind("outsider")) {
            assertThatThrownBy(() -> service.snapshot(project,id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.publish(project,command("other",1))).isInstanceOf(IllegalArgumentException.class);
        }
        actor.close();
        assertThatThrownBy(() -> service.list(project,null)).isInstanceOf(SecurityException.class);
    }

    @Test void historyCursorKeepsDistinctRowsWhenTimestampsCoincide() {
        var sameTime = Instant.parse("2026-11-01T08:12:15Z");
        for (int i = 0; i < 32; i++) {
            String id = String.format("%03d",i);
            jdbc.sql("""
                    insert into tacit_ontology_publication
                       (id,project_id,request_id,source_revision,model_digest,target_id,actor_id,snapshot_json,status,
                        attempt,result_json,created_at,updated_at)
                    values (:id,:p,:id,1,:d,:target,'reviewer','{}','VERIFIED',1,'{}',:now,:now)
                    """).param("p",project).param("id",id).param("d",TacitJson.hash(id)).param("target",TARGET)
                    .param("now",sameTime).update();
        }
        var first = service.list(project,null);
        assertThat((List<?>) first.get("items")).hasSize(30);
        assertThat(first).containsEntry("next_before","002");
        var second = service.list(project,(String)first.get("next_before"));
        assertThat(((List<?>)second.get("items")).stream().map(TacitJson::map).map(r -> r.get("id")).toList())
                .containsExactly("001","000");
        assertThat(second).containsEntry("next_before",null);
    }

    @Test void offlineSnapshotNeedsWalkthroughBeforeExternalPublication() {
        var bundle = new java.util.HashMap<>(BUNDLE);
        bundle.put("quality",Map.of("publication_ready",false));
        var state = Map.of("artifacts",Map.of("ontology_model",Map.of("status","ready","content",bundle)));
        jdbc.sql("update tacit_workspace set state_json=:s where project_id=:p").param("p",project)
                .param("s",TacitJson.write(state)).update();
        assertThatThrownBy(() -> service.publish(project,command("offline",1))).isInstanceOf(IllegalStateException.class);
        verify(gateway,never()).publish(any());
    }

    @Test void staleRevisionSourceCatalogAndChangedTargetNeverReserveOrTransmit() {
        assertThatThrownBy(() -> service.publish(project,command("stale",0))).isInstanceOf(IllegalStateException.class);
        when(gateway.target(project)).thenReturn(Map.of("configured",true,"target_id","rebound"));
        assertThatThrownBy(() -> service.publish(project,command("target",1))).isInstanceOf(IllegalStateException.class);
        when(gateway.target(project)).thenReturn(Map.of("configured",true,"target_id",TARGET));
        jdbc.sql("update tacit_workspace set source_stamp='superseded' where project_id=:p").param("p",project).update();
        assertThatThrownBy(() -> service.publish(project,command("catalog",1))).isInstanceOf(IllegalStateException.class);
        assertThat(service.list(project,null).get("items")).isEqualTo(List.of());
        verify(gateway,never()).publish(any());
    }
}
