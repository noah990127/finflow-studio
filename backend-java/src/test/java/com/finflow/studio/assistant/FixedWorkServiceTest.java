package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.data.DataTransformService;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fixed-work-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.auth.enabled=false", "finflow.ai.enabled=false", "finflow.storage.root=./target/fixed-work-test-storage"})
class FixedWorkServiceTest {
    @Autowired FixedWorkService fixed;
    @Autowired ProjectService projects;
    @Autowired KnowledgeService files;
    @Autowired AssistantService assistant;
    @Autowired JdbcClient jdbc;
    @Autowired FakeWorker worker;
    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        FakeWorker fixedTestWorker() { return new FakeWorker(); }
    }
    static class FakeWorker extends WorkerClient {
        final List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public Map<String,Object> fetchResearchSource(String url) {
            fetched.add(url);
            return Map.of("url",url,"text","fresh text for " + url);
        }
        volatile java.util.function.Function<Map<String,Object>, Map<String,Object>> generate = request -> Map.of("content","result");
        FakeWorker() { super("http://127.0.0.1:1"); }
        @Override public ParsedDocument parse(java.nio.file.Path path, String name) {
            return new ParsedDocument(name,"text/plain",name,0,List.of(),List.of());
        }
        @Override @SuppressWarnings("unchecked")
        public Map<String,Object> fixedWorkModel(String operation, Object request) {
            return operation.equals("snapshot") ? Map.of("provider","test","model","fixed") : generate.apply((Map<String,Object>)request);
        }
    }
    @MockitoBean DataTransformService transforms;

    @Test void publicationValidatesExistingParameterFieldsWithoutRunningTools() {
        var c=seed();
        files.createTextVersion(c.input(),1,"{\"topic\":\"news\",\"maxSources\":\"5\"}".getBytes());
        String code="""
            {"tool":"research.discover","version":1,"arguments":{
              "topic":{"$input":"source","as":"json","path":"/topic"},
              "maxSources":{"$input":"source","as":"json","path":"/maxSources"}}}
            """;
        assertThatThrownBy(() -> publish(c,"TOOL",code)).hasMessageContaining("参数校验失败").hasMessageContaining("maxSources");
        assertThat(fixed.state(c.project(),c.session()).definition()).isNull();
        files.createTextVersion(c.input(),2,"{\"topic\":\"news\",\"maxSources\":5}".getBytes());
        assertThat(publish(c,"TOOL",code).version()).isEqualTo(1);
        assertThat(fixed.state(c.project(),c.session()).runs()).isEmpty();
    }

    @Test void fixedFetchBindsNewInputAndKeepsEarlierOutput() throws Exception {
        var c=seed();
        files.createTextVersion(c.input(), 1, "https://example.org/old".getBytes());
        var d=publish(c,"TOOL","""
                {"tool":"research.fetch","version":1,"arguments":{"url":{"$input":"source"}}}
                """);
        fixed.requirePublished(c.run()); completeAgent(c);
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(d.id(),UUID.randomUUID().toString()));
        var first=waitRun(c);
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        var old=java.nio.file.Files.readString(files.filePath(first.stages().getFirst().output().resourceId(),1));
        files.createTextVersion(c.input(), 2, "https://example.org/new".getBytes());
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(d.id(),UUID.randomUUID().toString()));
        var second=waitRun(c);
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(second.definitionId()).isEqualTo(first.definitionId());
        assertThat(second.stages().getFirst().inputs().getFirst().version()).isEqualTo(3);
        assertThat(second.stages().getFirst().output().resourceId()).isNotEqualTo(first.stages().getFirst().output().resourceId());
        assertThat(java.nio.file.Files.readString(files.filePath(first.stages().getFirst().output().resourceId(),1))).isEqualTo(old);
        assertThat(worker.fetched).contains("https://example.org/old","https://example.org/new");
    }

    @Test void businessContractIsVersionedAndOldRunsKeepTheirOwnGrouping() throws Exception {
        worker.generate = request -> Map.of("content", "readable business result");
        var c = seed();
        var steps = List.of(
                new FixedWorkDefinition.Step("prepare", "整理数据", "PROMPT", "整理输入", "prepared.md", List.of(new FixedWorkDefinition.Input("source", c.input(), null, null))),
                new FixedWorkDefinition.Step("summary", "形成结论", "PROMPT", "总结输入", "summary.md", List.of(new FixedWorkDefinition.Input("source", null, "prepare", null))));
        var group = new FixedWorkDefinition.BusinessStage("review", "检查业务成果", "形成结果", "核对内容", List.of("prepare", "summary"),
                List.of(new FixedWorkDefinition.Outcome("summary", "PRIMARY")));
        var first = fixed.publish(c.run(), Map.of("implementation", new FixedWorkDefinition("业务工作", steps, Map.of(), List.of(group)), "expected_version", 0));
        fixed.startForAgent(c.run());
        var finished = waitRun(c);
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(finished.businessStages()).hasSize(1);
        assertThat(finished.businessStages().getFirst().outputs()).extracting(AgentWorkService.ResourceRef::role).containsExactly("INTERNAL", "PRIMARY");
        var renamed = new FixedWorkDefinition.BusinessStage("review", "重新检查", group.goal(), group.acceptance(), group.stepIds(), group.outcomes());
        var second = fixed.publish(c.run(), Map.of("implementation", new FixedWorkDefinition("业务工作", steps, Map.of(), List.of(renamed)), "expected_version", 1));
        assertThat(second.hash()).isNotEqualTo(first.hash());
        assertThat(fixed.state(c.project(), c.session()).runs().getFirst().businessStages().getFirst().title()).isEqualTo("检查业务成果");
    }

    @Test void completionRequiresPublishedImplementationAndRejectsUnsafeTools() {
        var c=seed();
        assertThatThrownBy(() -> fixed.requirePublished(c.run())).hasMessageContaining("尚未固化");
        assertThatThrownBy(() -> publish(c,"TOOL","{\"tool\":\"resource.delete\",\"version\":1,\"arguments\":{}}"))
                .hasMessageContaining("不受支持");
    }

    @Test void agentCanExecutePublishedWorkWithoutEndingItsConversation() throws Exception {
        worker.generate = request -> Map.of("content", "result");
        var c = seed();
        var d = publish(c, "PROMPT", "Summarize the input without tools");
        assertThatThrownBy(() -> fixed.start(c.project(), c.session(),
                new FixedWorkService.Start(d.id(), UUID.randomUUID().toString())))
                .hasMessageContaining("Agent正在修改");
        AssistantToolContracts.validate("work.execute_implementation", Map.of());
        var first = fixed.startForAgent(c.run());
        assertThat(waitRun(c).status()).isEqualTo("SUCCEEDED");
        assertThat(fixed.startForAgent(c.run()).id()).isEqualTo(first.id());
        assertThat(fixed.state(c.project(), c.session()).definition().verified()).isTrue();
        var revised = new FixedWorkDefinition(d.implementation().title(), List.of(
                new FixedWorkDefinition.Step("analysis", "Revised analysis", "PROMPT", "Produce a shorter summary",
                        "revised.md", List.of(new FixedWorkDefinition.Input("source", c.input(), null, null)))), Map.of());
        fixed.publish(c.run(), Map.of("implementation", revised, "expected_version", 1));
        assertThat(fixed.startForAgent(c.run()).id()).isNotEqualTo(first.id());
        assertThat(waitRun(c).status()).isEqualTo("SUCCEEDED");
        completeAgent(c);
        assertThatThrownBy(() -> fixed.startForAgent(c.run())).isInstanceOf(RuntimeException.class);
    }

    @Test void implementationFollowsProjectCreatedDuringTheConversation() {
        var c=seed(); var target=projects.create("新建工作项目", "").id();
        jdbc.sql("update assistant_run set effects_json=:effects where id=:id")
                .param("effects","{\"currentProjectId\":\""+target+"\"}").param("id",c.run()).update();
        jdbc.sql("update assistant_session set project_id=:project where id=:id")
                .param("project",target).param("id",c.session()).update();
        var def=new FixedWorkDefinition("新项目采集",List.of(new FixedWorkDefinition.Step("fetch","采集网页","TOOL",
                "{\"tool\":\"research.fetch\",\"version\":1,\"arguments\":{\"url\":\"https://example.org\"}}","snapshot.json",List.of())),Map.of());
        var published=fixed.publish(c.run(),Map.of("implementation",def,"expected_version",0));
        assertThat(fixed.state(target,c.session()).definition().id()).isEqualTo(published.id());
        assertThat(fixed.state(c.project(),c.session()).definition()).isNull();
    }

    record Context(String project, String session, String run, String input) { }
    Context seed() {
        var project = projects.create("固定执行测试", "").id();
        var session = assistant.createSession(project, "固定执行").id();
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                insert into assistant_context_snapshot(id,session_id,project_id,page,selection_json,allowed_resource_ids_json,resource_versions_json,context_hash,expires_at,created_at)
                values (:id,:session,:project,'project-home','{}','[]','{}','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("id",id).param("session",session).param("project",project).update();
        jdbc.sql("""
                insert into assistant_plan(id,session_id,context_snapshot_id,goal,summary,version,plan_hash,risk_level,status,affected_resources_json,expires_at,created_at,execution_mode)
                values (:id,:session,:id,'测试','',1,'hash','READ_ONLY','RUNNING','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'AUTO')
                """).param("id",id).param("session",session).update();
        jdbc.sql("""
                insert into assistant_run(id,session_id,plan_id,idempotency_key,trace_id,status,created_at,gateway_token)
                values (:id,:session,:id,:id,:id,'RUNNING',CURRENT_TIMESTAMP,'token')
                """).param("id",id).param("session",session).update();
        var file = files.upload(project, null, new MockMultipartFile("file","input.csv","text/csv","amount\n10\n".getBytes()));
        return new Context(project, session, id, file.id());
    }
    FixedWorkDefinition implementation(Context c, String kind, String code) {
        return new FixedWorkDefinition("固定业务流程", List.of(new FixedWorkDefinition.Step("s1", "处理数据", kind, code, "result",
                List.of(new FixedWorkDefinition.Input("source",c.input(),null,null)))), Map.of());
    }
    FixedWorkService.Definition publish(Context c, String kind, String code) {
        return fixed.publish(c.run(), Map.of("implementation",implementation(c, kind, code),"expected_version",0));
    }
    void completeAgent(Context c) { jdbc.sql("update assistant_run set status='SUCCEEDED' where id=:id").param("id",c.run()).update(); }
    FixedWorkService.Run waitRun(Context c) throws Exception {
        for (int i=0;i<100;i++) {
            var run = fixed.state(c.project(),c.session()).runs().getFirst();
            if (Set.of("SUCCEEDED","FAILED","CANCELED").contains(run.status())) return run;
            Thread.sleep(30);
        }
        throw new AssertionError("run did not finish");
    }
    @Test void frozenSqlUsesLatestSavedInputWithoutRegeneratingAndRetainsRuns() throws Exception {
        var c=seed(); var sql="select amount * 2 as amount from source";
        var definition=publish(c,"SQL",sql); completeAgent(c);
        when(transforms.execute(eq(c.project()),anyMap(),anyMap())).thenAnswer(call -> {
            Map<String,Object> config=call.getArgument(1); Map<String,Map<String,Object>> upstream=call.getArgument(2);
            assertThat(config.get("script")).isEqualTo(sql);
            var input=upstream.get("source");
            var text=java.nio.file.Files.readString(files.filePath(c.input(), (Integer)input.get("version")));
            var output=files.importBytes(c.project(),"out.csv","text/csv",text.getBytes());
            return new DataTransformService.ExecutionResult(output,Map.of(),Map.of());
        });
        var request=new FixedWorkService.Start(definition.id(),UUID.randomUUID().toString());
        var first=fixed.start(c.project(),c.session(),request); var finished=waitRun(c);
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(fixed.start(c.project(),c.session(),request).id()).isEqualTo(first.id());
        assertThat(finished.snapshot().get("s1/source").version()).isEqualTo(1);
        files.upload(c.project(),c.input(),new MockMultipartFile("file","input.csv","text/csv","amount\n20\n".getBytes()));
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(definition.id(),UUID.randomUUID().toString()));
        var second=waitRun(c);
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(second.snapshot().get("s1/source").version()).isEqualTo(2);
        assertThat(second.stages().getFirst().output().resourceId()).isNotEqualTo(finished.stages().getFirst().output().resourceId());
        assertThat(fixed.state(c.project(),c.session()).definition().hash()).isEqualTo(definition.hash());
        assertThat(fixed.state(c.project(),c.session()).definition().verified()).isTrue();
        verify(transforms,never()).generate(anyString(),any());
    }
    @Test void failureDoesNotRepairSqlOrRunDownstream() throws Exception {
        var c=seed(); var d=publish(c,"SQL","select missing_column from source"); completeAgent(c);
        when(transforms.execute(eq(c.project()),anyMap(),anyMap())).thenThrow(new IllegalArgumentException("missing_column不存在"));
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(d.id(),UUID.randomUUID().toString()));
        var run=waitRun(c);
        assertThat(run.status()).isEqualTo("FAILED"); assertThat(run.error()).contains("missing_column");
        assertThat(fixed.state(c.project(),c.session()).definition().verified()).isFalse();
        verify(transforms,never()).generate(anyString(),any());
    }
    @Test void fixedPromptUsesSnapshotAndCanBeInterruptedWithoutOutput() throws Exception {
        var c=seed(); var entered=new CountDownLatch(1);
        var d=publish(c,"PROMPT","Summarize inputs only."); completeAgent(c);
        worker.generate = request -> {
            assertThat(request.get("prompt")).isEqualTo("Summarize inputs only.");
            assertThat(request.get("snapshot")).isEqualTo(Map.of("provider","test","model","fixed"));
            entered.countDown(); try { Thread.sleep(10000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); } return Map.of("content","must not be saved");
        };
        var run=fixed.start(c.project(),c.session(),new FixedWorkService.Start(d.id(),UUID.randomUUID().toString()));
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> fixed.start(c.project(),c.session(),new FixedWorkService.Start(d.id(),UUID.randomUUID().toString()))).hasMessageContaining("正在执行");
        fixed.cancel(c.project(),c.session(),run.id());
        assertThat(waitRun(c).status()).isEqualTo("CANCELED");
        assertThat(waitRun(c).stages().getFirst().output()).isNull();
    }
    @Test void versionConflictAndOtherUserAreRejected() {
        var c=seed(); var d=publish(c,"SQL","select * from source");
        assertThatThrownBy(() -> publish(c,"SQL","select amount from source")).hasMessageContaining("版本已变化");
        var updated=fixed.publish(c.run(),Map.of("implementation",implementation(c,"SQL","select amount from source"),"expected_version",1));
        assertThat(updated.version()).isEqualTo(2); assertThat(updated.hash()).isNotEqualTo(d.hash());
        try(var ignored=ActorContext.bind("other-user")) {
            assertThatThrownBy(() -> fixed.state(c.project(),c.session())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixed.publish(c.run(),Map.of("implementation",implementation(c,"SQL","select * from source")))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsCyclesAndUnsupportedExecutionKinds() {
        var cyclic=new FixedWorkDefinition("test",List.of(new FixedWorkDefinition.Step("s","step","SQL","select * from source","out",List.of(new FixedWorkDefinition.Input("source",null,"s",null)))),Map.of());
        assertThatThrownBy(cyclic::validate).hasMessageContaining("前序");
        var c=seed(); assertThatThrownBy(() -> publish(c,"AGENT","do anything")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void previousOutputBindingReadsManualEditsWithoutOverwritingOldOutput() throws Exception {
        var c=seed();
        var implementation=new FixedWorkDefinition("可编辑底稿", List.of(new FixedWorkDefinition.Step("s1","汇总","PROMPT","保留人工说明再汇总","summary.md",
                List.of(new FixedWorkDefinition.Input("draft",c.input(),null,"s1")))),Map.of());
        var definition=fixed.publish(c.run(),Map.of("implementation",implementation,"expected_version",0)); completeAgent(c);
        worker.generate = request -> Map.of("content","first result");
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(definition.id(),UUID.randomUUID().toString()));
        var first=waitRun(c); assertThat(first.status()).isEqualTo("SUCCEEDED");
        var output=first.stages().getFirst().output();
        files.upload(c.project(),output.resourceId(),new MockMultipartFile("file","summary.md","text/markdown","human edits".getBytes()));
        worker.generate = request -> {
            assertThat(request.get("inputs")).isEqualTo(Map.of("draft","human edits"));
            return Map.of("content","new result preserving human edits");
        };
        fixed.start(c.project(),c.session(),new FixedWorkService.Start(definition.id(),UUID.randomUUID().toString()));
        var second=waitRun(c); assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(second.snapshot().get("s1/draft").version()).isEqualTo(2);
        assertThat(java.nio.file.Files.readString(files.filePath(output.resourceId(),2))).isEqualTo("human edits");
        assertThat(second.stages().getFirst().output().resourceId()).isNotEqualTo(output.resourceId());
    }
}
