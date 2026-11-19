package com.finflow.studio.knowledge;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetCsvConverterTest {
    @TempDir Path directory;

    @Test
    void convertsEverySheetAndSplitsLargeSheetsWithoutLosingDisplayedValues() throws Exception {
        var workbookPath = directory.resolve("经营明细.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var detail = workbook.createSheet("经营明细");
            var header = detail.createRow(0);
            header.createCell(0).setCellValue("明细ID");
            header.createCell(1).setCellValue("说明");
            for (var index = 1; index <= 10_001; index++) {
                var row = detail.createRow(index);
                row.createCell(0).setCellValue("TXN" + index);
                row.createCell(1).setCellValue(index == 1 ? "深圳, \"福田\"" : "第" + index + "行");
            }
            var dimension = workbook.createSheet("产品维度");
            dimension.createRow(0).createCell(0).setCellValue("产品编码");
            dimension.createRow(1).createCell(0).setCellValue("P001");
            try (var output = Files.newOutputStream(workbookPath)) { workbook.write(output); }
        }

        var converter = new SpreadsheetCsvConverter();
        var progress = new java.util.ArrayList<Long>();
        try (var result = converter.convert(workbookPath, "经营明细.xlsx", 10_000, progress::add)) {
            assertThat(result.parts()).hasSize(3);
            assertThat(result.parts()).extracting(SpreadsheetCsvConverter.CsvPart::sheetName)
                    .containsExactly("经营明细", "经营明细", "产品维度");
            assertThat(result.parts()).extracting(SpreadsheetCsvConverter.CsvPart::rowCount)
                    .containsExactly(10_000L, 1L, 1L);
            assertThat(result.parts().get(0).name()).isEqualTo("经营明细_01_经营明细_001.csv");
            assertThat(result.parts().get(1).name()).isEqualTo("经营明细_01_经营明细_002.csv");
            assertThat(progress.getLast()).isEqualTo(10_002L);

            try (var parser = CSVParser.parse(result.parts().get(0).path(), StandardCharsets.UTF_8,
                    CSVFormat.DEFAULT)) {
                var records = parser.getRecords();
                assertThat(records).hasSize(10_001);
                assertThat(records.get(0).toList()).containsExactly("明细ID", "说明");
                assertThat(records.get(1).toList()).containsExactly("TXN1", "深圳, \"福田\"");
            }
            try (var parser = CSVParser.parse(result.parts().get(1).path(), StandardCharsets.UTF_8,
                    CSVFormat.DEFAULT)) {
                var records = parser.getRecords();
                assertThat(records).hasSize(2);
                assertThat(records.get(0).toList()).containsExactly("明细ID", "说明");
                assertThat(records.get(1).toList()).containsExactly("TXN10001", "第10001行");
            }
        }
    }
}
