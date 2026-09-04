package com.finflow.studio.data;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDataMaterializerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsNestedKeyedJsonIntoRowsUsingASchemaPathHint() {
        var fetched = Map.<String, Object>of("text", """
                {"source":"public-api","rates":{
                  "2026-01-01":{"ALPHA":1.25,"BETA":7.1},
                  "2026-01-02":{"ALPHA":1.30,"BETA":7.2}
                }}
                """);

        var result = StructuredDataMaterializer.materialize(
                fetched, Map.of("mapping", "rates"), objectMapper);

        assertThat(result.sourcePath()).isEqualTo("rates");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().getFirst()).containsEntry("date", "2026-01-01")
                .containsEntry("ALPHA", 1.25).containsEntry("BETA", 7.1);
    }

    @Test
    void discoversRecordArraysWithoutBusinessSpecificRules() {
        var fetched = Map.<String, Object>of("text", """
                {"metadata":{"provider":"example"},"observations":[
                  {"period":"2026-Q1","value":18},
                  {"period":"2026-Q2","value":21}
                ]}
                """);

        var result = StructuredDataMaterializer.materialize(fetched, Map.of(), objectMapper);

        assertThat(result.sourcePath()).isEqualTo("observations");
        assertThat(result.rows()).containsExactly(
                Map.of("period", "2026-Q1", "value", 18),
                Map.of("period", "2026-Q2", "value", 21));
    }

    @Test
    void fallsBackToParsedHtmlTables() {
        var fetched = Map.<String, Object>of("text", "not-json", "tables", List.of(Map.of(
                "title", "Monthly metrics",
                "rows", List.of(List.of("month", "value"), List.of("Jan", "12"), List.of("Feb", "15")))));

        var result = StructuredDataMaterializer.materialize(fetched, "Monthly metrics", objectMapper);

        assertThat(result.sourcePath()).isEqualTo("Monthly metrics");
        assertThat(result.rows()).containsExactly(
                Map.of("month", "Jan", "value", "12"),
                Map.of("month", "Feb", "value", "15"));
    }
}
