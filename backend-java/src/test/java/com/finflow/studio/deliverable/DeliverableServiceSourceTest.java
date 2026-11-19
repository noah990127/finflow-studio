package com.finflow.studio.deliverable;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeliverableServiceSourceTest {
    @Test
    void assemblesReadableSourceTextFromDeliverableSpecification() {
        var specification = Map.<String, Object>of(
                "title", "季度经营复盘",
                "subtitle", "管理层摘要",
                "sections", List.of(Map.of(
                        "heading", "核心结论",
                        "paragraphs", List.of("收入增长 12%。"),
                        "bullets", List.of("华南区域贡献最大"))));

        assertThat(DeliverableService.sourceText(specification))
                .isEqualTo("季度经营复盘\n\n管理层摘要\n\n核心结论\n\n收入增长 12%。\n\n华南区域贡献最大");
    }
}
