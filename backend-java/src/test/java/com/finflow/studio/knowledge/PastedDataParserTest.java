package com.finflow.studio.knowledge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PastedDataParserTest {
    private final PastedDataParser parser = new PastedDataParser(JsonMapper.builder().build());

    @Test
    void detectsBusinessMarkdownTableAndCreatesCsv() {
        var parsed = parser.parse("""
                | 月份 | 区域 | 收入 |
                | --- | --- | ---: |
                | 2026-08 | 华南 | 1200 |
                | 2026-08 | 华东 | 980 |
                """, "AUTO");

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.format()).isEqualTo("MARKDOWN_TABLE");
        assertThat(parsed.columns()).containsExactly("月份", "区域", "收入");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.warnings()).isEmpty();
        assertThat(new String(parser.csv(parsed), StandardCharsets.UTF_8)).contains("2026-08,华南,1200");
    }

    @Test
    void flattensJsonObjectArray() {
        var parsed = parser.parse("""
                [{"产品":"云服务","实际":120},{"产品":"软件","实际":80,"备注":"待核对"}]
                """, "AUTO");

        assertThat(parsed.format()).isEqualTo("JSON");
        assertThat(parsed.columns()).containsExactly("产品", "实际", "备注");
        assertThat(parsed.rows().get(0)).containsExactly("云服务", "120", "");
    }

    @Test
    void keepsNarrativeAsEvidenceInsteadOfPretendingItIsData() {
        var parsed = parser.parse("华南区收入偏差主要来自项目延期，需要业务负责人复核。", "AUTO");

        assertThat(parsed.structured()).isFalse();
        assertThat(parsed.format()).isEqualTo("TEXT");
        assertThat(parsed.warnings()).anyMatch(message -> message.contains("原始资料"));
    }
}
