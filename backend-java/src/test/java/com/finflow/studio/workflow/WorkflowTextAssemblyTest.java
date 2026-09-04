package com.finflow.studio.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTextAssemblyTest {

    private final WorkflowRunService service = new WorkflowRunService(
            null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void parsedReferencesPreventRawFileBytesFromBeingReadAgain() {
        var output = Map.<String, Object>of(
                "fileId", "parsed-pdf",
                "version", 1,
                "refs", List.of(
                        Map.of("text", "第一页的已解析内容"),
                        Map.of("text", "第二页的已解析内容")));

        assertThat(service.collectText(Map.of("input", output)))
                .isEqualTo("第一页的已解析内容\n第二页的已解析内容");
    }

    @Test
    void generatedContextNeverExceedsWorkerRequestLimit() {
        var oversized = "数据".repeat(WorkflowRunService.MAX_WORKFLOW_CONTEXT_CHARS);

        var result = service.collectText(Map.of("analysis", Map.of("analysis", oversized)));

        assertThat(result).hasSizeLessThan(200_000)
                .endsWith("[工作流上下文较长，已按安全上限截断]");
    }
}
