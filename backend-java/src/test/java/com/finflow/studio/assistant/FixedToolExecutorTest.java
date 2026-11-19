package com.finflow.studio.assistant;

import com.finflow.studio.deliverable.DeliverableService;
import com.finflow.studio.deliverable.DeliverableModels.*;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedToolExecutorTest {
    @Test void editedInvalidInputsAreRejectedBeforeWorkerCall() {
        var worker=mock(WorkerClient.class); var files=mock(KnowledgeService.class); var docs=mock(DeliverableService.class);
        var executor=new FixedToolExecutor(worker,files,docs,json);
        var definition=step("""
            {"tool":"research.discover","version":1,"arguments":{"topic":{"$input":"data","as":"json","path":"/topic"}}}
            """);
        assertThatThrownBy(() -> executor.execute("project",definition,Map.of("data","{\"topic\":123}"),()->{}))
                .hasMessageContaining("topic");
        assertThatThrownBy(() -> executor.execute("project",definition,Map.of("data","{}"),()->{}))
                .hasMessageContaining("缺少字段");
        verifyNoInteractions(worker,files,docs);
    }
    @Test void collectionPreservesReadableSourcesAndRecordsFailures() {
        var worker=mock(WorkerClient.class); var files=mock(KnowledgeService.class); var docs=mock(DeliverableService.class);
        var output=mock(com.finflow.studio.knowledge.KnowledgeModels.FileResourceResponse.class);
        when(output.id()).thenReturn("snapshot"); when(output.name()).thenReturn("sources.json");
        when(output.currentVersion()).thenReturn(1);
        when(files.importBytes(anyString(),anyString(),anyString(),any(byte[].class))).thenReturn(output);
        when(worker.fetchResearchSource("https://example.com/empty")).thenReturn(Map.of("text",""));
        when(worker.fetchResearchSource("https://example.com/error")).thenThrow(new IllegalStateException("403"));
        when(worker.fetchResearchSource("https://example.com/good")).thenReturn(Map.of("text","实际正文","url","https://example.com/good"));
        var executor=new FixedToolExecutor(worker,files,docs,json);
        var definition=step("""
                {"tool":"research.collect","version":1,"arguments":{"sources":{"$input":"data","as":"json"}}}
                """);
        executor.execute("p",definition,Map.of("data","[\"https://example.com/empty\",\"https://example.com/error\",\"https://example.com/good\"]"),()->{});
        var content=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(files).importBytes(eq("p"),anyString(),eq("application/json"),content.capture());
        var snapshot=json.readTree(content.getValue());
        assertThat(snapshot.get("status").asText()).isEqualTo("partial");
        assertThat(snapshot.get("sources").size()).isEqualTo(1);
        assertThat(snapshot.get("failedSources").size()).isEqualTo(2);
        clearInvocations(files);
        assertThatThrownBy(()->executor.execute("p",definition,Map.of("data","[\"https://example.com/empty\"]"),()->{}))
                .hasMessageContaining("网页采集全部失败").hasMessageContaining("https://example.com/empty");
        verifyNoInteractions(files,docs);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv->{cancelled.set(true);throw new IllegalStateException("连接中断");}).when(worker).fetchResearchSource("https://example.com/error");
        assertThatThrownBy(()->executor.execute("p",definition,Map.of("data","[\"https://example.com/error\",\"https://example.com/good\"]"),()->{
            if(cancelled.get()) throw new java.util.concurrent.CancellationException("已停止");
        })).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(files,docs);
    }
    final ObjectMapper json = new ObjectMapper();
    FixedWorkDefinition.Step step(String code) {
        return new FixedWorkDefinition.Step("render","制作文档","TOOL",code,"报告",
                List.of(new FixedWorkDefinition.Input("data","resource",null,null)));
    }
    @Test void browserRunImportsSeparateEvidenceAndStopsOnFailedValidation() {
        var worker = mock(WorkerClient.class);
        var files = mock(KnowledgeService.class);
        var docs = mock(DeliverableService.class);
        var output = mock(com.finflow.studio.knowledge.KnowledgeModels.FileResourceResponse.class);
        when(output.id()).thenReturn("12345678-resource");
        when(output.name()).thenReturn("报告.csv");
        when(output.currentVersion()).thenReturn(1);
        when(files.importBytes(anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(output);
        var definition = step("""
                {"tool":"browser.run","version":1,"arguments":{"script":{"version":1,"site":"demo","steps":[]},"parameters":{"$input":"data","as":"json"}}}
                """);
        var executor = new FixedToolExecutor(worker, files, docs, json);
        when(worker.browser(eq("run"), anyMap())).thenReturn(Map.of(
                "audit", Map.of("status", "SUCCEEDED", "scriptHash", "same-script"),
                "contentBase64", Base64.getEncoder().encodeToString("date,amount\n2026-11-01,1\n".getBytes()),
                "screenshotBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        assertThat(executor.execute("project", definition, Map.of("data", "{}"), () -> {}).resourceId()).isEqualTo(output.id());
        verify(files).importBytes(eq("project"), eq("报告.csv"), eq("text/csv"), any(byte[].class));
        verify(files).importBytes(eq("project"), eq("报告-执行记录-12345678.json"), eq("application/json"), any(byte[].class));
        verify(files).importBytes(eq("project"), eq("报告-页面凭证-12345678.png"), eq("image/png"), any(byte[].class));
        clearInvocations(files);
        when(worker.browser(eq("run"), anyMap())).thenReturn(Map.of("audit", Map.of("status", "FAILED")));
        assertThatThrownBy(() -> executor.execute("project", definition, Map.of("data", "{}"), () -> {}))
                .hasMessageContaining("结果校验");
        verifyNoInteractions(files, docs);
    }
    @Test void documentRenderingUsesCurrentDataWithFixedFormatAndNoModel() {
        var worker=mock(WorkerClient.class); var files=mock(KnowledgeService.class); var docs=mock(DeliverableService.class);
        var calls=new ArrayList<CreateRequest>();
        when(docs.create(any())).thenAnswer(inv -> {
            CreateRequest req=inv.getArgument(0); calls.add(req);
            return new Response(UUID.randomUUID().toString(),"project",req.title(),req.format(),1,"READY",10,"hash",null,null);
        });
        var executor=new FixedToolExecutor(worker,files,docs,json);
        var definition=step("""
                {"tool":"document.render","version":1,"arguments":{"format":"pptx","document":{"$input":"data","as":"json"}}}
                """);
        for(var text: List.of("old","new","new")) executor.execute("project",definition,Map.of("data",
                json.writeValueAsString(Map.of("sections",List.of(Map.of("heading","汇总","paragraphs",List.of(text)))))),()->{});
        assertThat(calls).extracting(CreateRequest::format).containsExactly("pptx","pptx","pptx");
        assertThat(calls).allMatch(c -> c.resourceId()==null);
        assertThat(calls).allMatch(c -> "fixed".equals(c.planMode()));
        assertThat(calls.get(0).sections().getFirst().paragraphs()).containsExactly("old");
        assertThat(calls.get(1).sections()).isEqualTo(calls.get(2).sections());
        executor.execute("project", definition, Map.of("data", json.writeValueAsString(Map.of(
                "title", "管理层简报", "sections", List.of(Map.of("heading", "结论"))))), () -> {});
        assertThat(calls.getLast().title()).isEqualTo("管理层简报");
        var named = new FixedWorkDefinition.Step("render", "制作文档", "TOOL", definition.code(), "报告.pptx", definition.inputs());
        executor.execute("project", named, Map.of("data", "{\"sections\":[{\"heading\":\"结论\"}]}"), () -> {});
        assertThat(calls.getLast().title()).isEqualTo("报告");
        executor.execute("project", definition, Map.of("data", json.writeValueAsString(Map.of(
                "includeCitations", false, "presentationBrief", Map.of("purpose", "培训", "density", "reading"),
                "sections", List.of(Map.of("heading", "岗位概览"))))), () -> {});
        assertThat(calls.getLast().includeCitations()).isFalse();
        assertThat(calls.getLast().presentationBrief()).containsEntry("purpose", "培训").containsEntry("density", "reading");
        var visual = Map.of("kind", "matrix", "rationale", "岗位与阶段对应", "columns", List.of("阶段"),
                "rows", List.of(Map.of("label", "导师", "cells", List.of("反馈"))));
        executor.execute("project", definition, Map.of("data", json.writeValueAsString(Map.of(
                "sections", List.of(Map.of("heading", "责任矩阵", "visual", visual))))), () -> {});
        assertThat(calls.getLast().sections().getFirst().visual()).isEqualTo(visual);
        executor.execute("project", definition, Map.of("data", """
                {"sections":[{"heading":"新闻","citations":[{"id":1,"title":"原始新闻","url":"https://example.com/news","publisher":"发布者","publishedAt":"2026-11-17","version":null}]}]}
                """), () -> {});
        var citation = calls.getLast().sections().getFirst().citations().getFirst();
        assertThat(citation.id()).isEqualTo("1");
        assertThat(citation.version()).isZero();
        assertThat(citation.sourceName()).isEqualTo("原始新闻");
        assertThat(citation.location()).containsEntry("url","https://example.com/news")
                .containsEntry("publisher","发布者").containsEntry("publishedAt","2026-11-17");
        assertThatThrownBy(() -> executor.execute("project", step("""
                {"tool":"document.render","version":1,"arguments":{"format":"docx","pptSkill":"blue-professional-v2","document":{"$input":"data","as":"json"}}}
                """), Map.of("data","{\"sections\":[{\"heading\":\"报告\"}]}"),()->{})).hasMessageContaining("不兼容");
        verifyNoInteractions(worker,files);
    }
    @Test void bindingIsStructuredAndCannotChangeToolOrFormat() {
        assertThatThrownBy(() -> FixedToolCall.parse(json, step("""
                {"tool":"browser.run","version":1,"arguments":{"script":{"$input":"data","as":"json"},"parameters":{}}}
                """))).hasMessageContaining("脚本必须固化");
        assertThatThrownBy(() -> FixedToolCall.parse(json, step("""
                {"tool":"browser.run","version":1,"arguments":{"script":{"site":{"$input":"data"}},"parameters":{}}}
                """))).hasMessageContaining("脚本必须固化");
        var browser = FixedToolCall.parse(json, step("""
                {"tool":"browser.run","version":1,"arguments":{"script":{"version":1,"site":"demo","steps":[]},"parameters":{"$input":"data","as":"json"}}}
                """));
        assertThat(browser.tool()).isEqualTo("browser.run");
        var invalid=step("""
                {"tool":"document.render","version":1,"arguments":{"format":{"$input":"data"},"document":{}}}
                """);
        assertThatThrownBy(() -> FixedToolCall.parse(json,invalid)).hasMessageContaining("格式必须固定");
        assertThatThrownBy(() -> FixedToolCall.parse(json,step("""
                {"tool":"research.fetch","version":1,"arguments":{"url":{"$input":"unknown"}}}
                """))).hasMessageContaining("无效输入绑定");
        assertThat(FixedToolCall.bind(Map.of("$input","data"),Map.of("data","{\"tool\":\"delete\"}"),json))
                .isEqualTo("{\"tool\":\"delete\"}");
    }
    @Test void emptySearchFailsAndDoesNotImportAnArtifact() {
        var worker=mock(WorkerClient.class); var files=mock(KnowledgeService.class); var docs=mock(DeliverableService.class);
        when(worker.discoverResearchSources("topic",5)).thenReturn(Map.of("sources",List.of(),"mode","search-unavailable"));
        var executor=new FixedToolExecutor(worker,files,docs,json);
        assertThatThrownBy(() -> executor.execute("project",step("""
                {"tool":"research.discover","version":1,"arguments":{"topic":"topic"}}
                """),Map.of(),()->{})).hasMessageContaining("搜索未返回来源");
        verifyNoInteractions(files,docs);
    }
    @Test void searchFailurePreservesCauseAndDistinguishesEmptyResults() {
        var worker=mock(WorkerClient.class); var files=mock(KnowledgeService.class); var docs=mock(DeliverableService.class);
        var executor=new FixedToolExecutor(worker,files,docs,json);
        var definition=step("""
                {"tool":"research.discover","version":1,"arguments":{"topic":"topic"}}
                """);
        for (var status : List.of("unavailable", "failed")) {
            when(worker.discoverResearchSources("topic",5)).thenReturn(Map.of(
                    "status",status,"summary","尚未配置联网搜索服务","sources",List.of()));
            assertThatThrownBy(() -> executor.execute("project",definition,Map.of(),()->{}))
                    .hasMessageContaining("尚未配置联网搜索服务");
        }
        when(worker.discoverResearchSources("topic",5)).thenReturn(Map.of("status","empty","sources",List.of()));
        assertThatThrownBy(() -> executor.execute("project",definition,Map.of(),()->{}))
                .hasMessageContaining("请调整检索范围或关键词");
        verifyNoInteractions(files,docs);
    }
}
