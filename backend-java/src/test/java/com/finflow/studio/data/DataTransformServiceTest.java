package com.finflow.studio.data;

import com.finflow.studio.data.DataModels.ConnectionPreviewResponse;
import com.finflow.studio.data.DataModels.ConnectionResponse;
import com.finflow.studio.data.DataModels.PreviewConnectionRequest;
import com.finflow.studio.data.DataModels.SourceType;
import com.finflow.studio.knowledge.KnowledgeService;
import com.finflow.studio.project.ProjectService;
import com.finflow.studio.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataTransformServiceTest {

    @Test
    void materializesApiPreviewAsTemporaryDuckDbInput() {
        var projects = mock(ProjectService.class);
        var knowledge = mock(KnowledgeService.class);
        var extracts = mock(ExtractJobService.class);
        var connections = mock(DataConnectionService.class);
        var validator = mock(ReadOnlySqlValidator.class);
        var worker = mock(WorkerClient.class);
        var service = new DataTransformService(projects, knowledge, extracts, connections, validator,
                worker, new ObjectMapper());
        var now = Instant.now();
        when(connections.get("api-1")).thenReturn(new ConnectionResponse(
                "api-1", "project-1", "经营指标 API", SourceType.HTTP_API,
                "https://example.com/data", "", "", Map.of(), "READY", "", null, now, now));
        when(connections.preview("api-1", new PreviewConnectionRequest("GET /", 100))).thenReturn(
                new ConnectionPreviewResponse(List.of("period", "value"),
                        List.of(List.of("2026-01", "12.5"), List.of("2026-02", "13.2")),
                        2, false, "HTTP_API"));
        when(worker.sampleDataTransform(anyList(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            List<WorkerClient.DataTransformInput> inputs = call.getArgument(0);
            assertThat(inputs).hasSize(1);
            assertThat(inputs.getFirst().name()).endsWith("_预览.csv");
            assertThat(Files.readString(inputs.getFirst().path()))
                    .contains("period,value").contains("2026-01,12.5");
            assertThat(call.<String>getArgument(1)).contains("HTTP_API_PREVIEW");
            return Map.of("valid", true, "sampleRowCount", 2);
        });

        var result = service.sample("project-1", new DataTransformService.SampleRequest("", Map.of(
                "version", "1.0", "goal", "预览", "steps", List.of(Map.of(
                        "id", "result", "type", "select", "input", "api_data", "columns", List.of("period", "value")
                ))), List.of(new DataTransformService.SourceRequest(
                "CONNECTION", "api-1", "api_data", "API 数据", "GET /", ""))));

        assertThat(result).containsEntry("valid", true).containsEntry("sampleRowCount", 2);
        verify(connections).preview("api-1", new PreviewConnectionRequest("GET /", 100));
    }
}
