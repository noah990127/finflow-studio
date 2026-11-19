package com.finflow.studio.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class CsvPreviewServiceTest {
    @TempDir Path directory;

    @Test
    void continuesFromRecordBoundaryWithoutLoadingWholeFile() throws Exception {
        var file = directory.resolve("preview.csv");
        var content = new StringBuilder("id,description\r\n");
        for (var index = 1; index <= 25; index++) {
            var value = index == 20 ? "\"contains, comma and\r\na second line\"" : "value-" + index;
            content.append(index).append(',').append(value).append("\r\n");
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);

        var service = new CsvPreviewService();
        var first = service.preview(file, null, 20);
        var second = service.preview(file, first.nextCursor(), 20);

        assertThat(first.columns()).containsExactly("id", "description");
        assertThat(first.rows()).hasSize(20);
        assertThat(first.rows().get(19).get(1)).contains("a second line");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.rowOffset()).isEqualTo(20);
        assertThat(second.rows()).hasSize(5);
        assertThat(second.rows().getFirst()).containsExactly("21", "value-21");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void previewsAndPagesParquetResults() throws Exception {
        var path = Files.createTempFile("finflow-preview-", ".parquet");
        try {
            try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                 var statement = connection.createStatement()) {
                var escaped = path.toAbsolutePath().toString().replace("'", "''");
                statement.execute("COPY (SELECT range AS id, 'row-' || range AS name FROM range(45)) " +
                        "TO '" + escaped + "' (FORMAT PARQUET)");
            }
            var service = new CsvPreviewService();
            var first = service.preview(path, null, 20);
            assertThat(first.columns()).containsExactly("id", "name");
            assertThat(first.rows()).hasSize(20);
            assertThat(first.hasMore()).isTrue();

            var second = service.preview(path, first.nextCursor(), 20);
            assertThat(second.rowOffset()).isEqualTo(20);
            assertThat(second.rows().getFirst()).containsExactly("20", "row-20");
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
