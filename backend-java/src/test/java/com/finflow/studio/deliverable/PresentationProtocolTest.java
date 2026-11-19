package com.finflow.studio.deliverable;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class PresentationProtocolTest {
    @Test void structuredSlideFieldsSurviveJsonBinding() {
        var section = new ObjectMapper().readValue("""
            {"heading":"销售增长","coreMessage":"增长来自产品组合","chapter":"经营",
             "layout":"chart","chart":{"type":"bar","categories":["Q1","Q2"],
             "series":[{"name":"收入","values":[10,20]}]}}
            """, DeliverableModels.SectionRequest.class);
        assertThat(section.coreMessage()).isEqualTo("增长来自产品组合");
        assertThat(section.layout()).isEqualTo("chart");
        assertThat(section.chart()).containsKey("series");
    }
}
