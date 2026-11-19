package com.finflow.studio.preview;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetPreviewServiceTest {
    @TempDir Path directory;

    @Test
    void pagesRowsAndKeepsSheetMetadata() throws Exception {
        var path = directory.resolve("large.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var first = workbook.createSheet("经营明细");
            var header = first.createRow(0);
            header.createCell(0).setCellValue("明细ID");
            header.createCell(1).setCellValue("产品编码");
            for (var index = 1; index <= 45; index++) {
                var row = first.createRow(index);
                row.createCell(0).setCellValue("TXN" + index);
                row.createCell(1).setCellValue("P" + index);
            }
            workbook.createSheet("产品维度").createRow(0).createCell(0).setCellValue("产品编码");
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }

        var service = new SpreadsheetPreviewService();
        var first = service.preview(path, "经营明细", null, 20);
        var second = service.preview(path, "经营明细", first.nextCursor(), 20);
        var third = service.preview(path, "经营明细", second.nextCursor(), 20);

        assertThat(first.sheets()).containsExactly("经营明细", "产品维度");
        assertThat(first.columns()).containsExactly("明细ID", "产品编码");
        assertThat(first.rows()).hasSize(20);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalRows()).isEqualTo(45);
        assertThat(first.pageSize()).isEqualTo(20);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(second.rowOffset()).isEqualTo(20);
        assertThat(second.rows().getFirst()).containsExactly("TXN21", "P21");
        assertThat(third.rows()).hasSize(5);
        assertThat(third.hasMore()).isFalse();
        assertThat(third.totalRows()).isEqualTo(45);
        assertThat(third.totalPages()).isEqualTo(3);
    }

    @Test
    @EnabledIfSystemProperty(named = "finflow.preview.large.path", matches = ".+")
    void previewsAConfiguredLargeWorkbookWithoutLoadingItIntoMemory() {
        var path = Path.of(System.getProperty("finflow.preview.large.path"));
        var service = new SpreadsheetPreviewService();

        var first = service.preview(path, null, null, 100);
        var second = service.preview(path, first.sheetName(), first.nextCursor(), 100);

        assertThat(first.columns()).hasSize(200);
        assertThat(first.rows()).hasSize(100);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalRows()).isEqualTo(100_000);
        assertThat(first.totalPages()).isEqualTo(1_000);
        assertThat(second.rowOffset()).isEqualTo(100);
        assertThat(second.rows()).hasSize(100);
    }
}
