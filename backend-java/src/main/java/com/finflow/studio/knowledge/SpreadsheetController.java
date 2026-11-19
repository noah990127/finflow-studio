package com.finflow.studio.knowledge;

import com.finflow.studio.worker.WorkerClient;
import com.finflow.studio.preview.CsvPreviewService;
import com.finflow.studio.preview.PreviewModels.CsvPreview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files/{fileId}/spreadsheet")
public class SpreadsheetController {
    private final KnowledgeService knowledge;
    private final WorkerClient worker;
    private final SpreadsheetConversionService conversions;
    private final CsvPreviewService csvPreviews;

    public SpreadsheetController(KnowledgeService knowledge, WorkerClient worker,
                                 SpreadsheetConversionService conversions, CsvPreviewService csvPreviews) {
        this.knowledge = knowledge;
        this.worker = worker;
        this.conversions = conversions;
        this.csvPreviews = csvPreviews;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@PathVariable String fileId) {
        var file = knowledge.get(fileId);
        ensureSpreadsheet(file.name());
        return worker.profileSpreadsheet(knowledge.filePath(fileId, null), file.name());
    }

    @PostMapping("/transform")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeModels.FileResourceResponse transform(@PathVariable String fileId,
                                                          @Valid @RequestBody TransformRequest request) {
        var file = knowledge.get(fileId);
        ensureSpreadsheet(file.name());
        var operations = Map.of(
                "sheet_name", request.sheetName() == null ? "" : request.sheetName(),
                "rename_headers", request.renameHeaders() == null ? Map.of() : request.renameHeaders(),
                "fill_blanks", request.fillBlanks() == null ? Map.of() : request.fillBlanks(),
                "formula_columns", request.formulaColumns() == null ? List.of() : request.formulaColumns(),
                "remove_duplicates", request.removeDuplicates());
        var bytes = worker.transformSpreadsheet(knowledge.filePath(fileId, null), file.name(), operations);
        return knowledge.createGeneratedVersion(file.projectId(), fileId, file.name(), file.mediaType(), bytes);
    }

    @PostMapping("/convert-to-csv")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SpreadsheetConversionService.ConversionStatus convertToCsv(@PathVariable String fileId,
                                                                        @RequestBody(required = false) CsvConversionRequest request) {
        var file = knowledge.get(fileId);
        ensureExcel(file.name());
        var requestedRows = request == null || request.rowsPerFile() == null
                ? SpreadsheetCsvConverter.DEFAULT_ROWS_PER_FILE : request.rowsPerFile();
        return conversions.start(fileId, requestedRows);
    }

    @GetMapping("/convert-to-csv")
    public SpreadsheetConversionService.ConversionStatus conversionStatus(@PathVariable String fileId) {
        return conversions.status(fileId);
    }

    @GetMapping("/convert-to-csv/preview")
    public CsvPreview previewConvertedCsv(@PathVariable String fileId,
                                          @RequestParam(defaultValue = "1") int part,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "100") int limit) {
        return csvPreviews.preview(conversions.convertedPath(fileId, part), cursor, limit);
    }

    private void ensureSpreadsheet(String name) {
        var suffix = Path.of(name).getFileName().toString().toLowerCase();
        if (!(suffix.endsWith(".xlsx") || suffix.endsWith(".xlsm") || suffix.endsWith(".csv") || suffix.endsWith(".tsv"))) {
            throw new IllegalArgumentException("该文件不是可加工的表格");
        }
    }

    private void ensureExcel(String name) {
        var suffix = Path.of(name).getFileName().toString().toLowerCase();
        if (!(suffix.endsWith(".xlsx") || suffix.endsWith(".xlsm"))) {
            throw new IllegalArgumentException("只有 XLSX 或 XLSM 工作簿可以转换为 CSV");
        }
    }

    public record FormulaColumn(@NotBlank String name, @NotBlank String formula) { }

    public record TransformRequest(String sheetName,
                                   Map<String, String> renameHeaders,
                                   Map<String, Object> fillBlanks,
                                   @Size(max = 50) List<@Valid FormulaColumn> formulaColumns,
                                   boolean removeDuplicates) { }

    public record CsvConversionRequest(Integer rowsPerFile) { }
}
