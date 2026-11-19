package com.finflow.studio.workflow;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysteryNodeServiceTest {
    @Test
    void previewsModelInterpretationAsStrictDownstreamContract() {
        var worker = mock(WorkerClient.class);
        var projects = mock(ProjectService.class);
        var service = new MysteryNodeService(worker, new ObjectMapper(), projects);
        when(worker.generateContent(eq("MYSTERY"), contains("处理方向：WORKFLOW"), contains("偏差分析")))
                .thenReturn(Map.of("mode", "llm", "content", """
                        {"detected_kind":"notes","understanding":"这是周期性偏差分析作业",
                         "inferred_intent":"沉淀成可复用流程","output_kind":"WORKFLOW_DRAFT","title":"偏差分析流程",
                         "content":"1. 获取数据\\n2. 分析偏差\\n3. 生成报告",
                         "work_steps":[{"title":"获取数据","goal":"取得最新数据","input":"数据源","output":"待分析数据"}],
                         "assumptions":["数据口径需确认"],"suggested_next":"连接数据节点","confidence":0.82,"needs_confirmation":false}
                        """));

        var result = service.preview("project-1", "每月做偏差分析", "想要复用", "workflow");

        verify(projects).get("project-1");
        assertThat(result).containsEntry("outputKind", "WORKFLOW_DRAFT")
                .containsEntry("detectedKind", "notes")
                .containsEntry("confidence", 0.82)
                .containsEntry("needsConfirmation", true);
        assertThat(result.get("workSteps")).asList().hasSize(1);
    }

    @Test
    void rejectsUnknownDirectionsBeforeCallingTheModel() {
        var service = new MysteryNodeService(mock(WorkerClient.class), new ObjectMapper(), mock(ProjectService.class));

        assertThatThrownBy(() -> service.preview("project-1", "一段内容", "", "DELETE_EVERYTHING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("处理方向无效");
    }
}
