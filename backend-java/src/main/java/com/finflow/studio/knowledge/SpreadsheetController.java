package com.finflow.studio.knowledge;

import com.finflow.studio.worker.WorkerClient;
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

    public SpreadsheetController(KnowledgeService knowledge, WorkerClient worker) {
        this.knowledge = knowledge;
        this.worker = worker;
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

    private void ensureSpreadsheet(String name) {
        var suffix = Path.of(name).getFileName().toString().toLowerCase();
        if (!(suffix.endsWith(".xlsx") || suffix.endsWith(".xlsm") || suffix.endsWith(".csv") || suffix.endsWith(".tsv"))) {
            throw new IllegalArgumentException("该文件不是可加工的表格");
        }
    }

    public record FormulaColumn(@NotBlank String name, @NotBlank String formula) { }

    public record TransformRequest(String sheetName,
                                   Map<String, String> renameHeaders,
                                   Map<String, Object> fillBlanks,
                                   @Size(max = 50) List<@Valid FormulaColumn> formulaColumns,
                                   boolean removeDuplicates) { }
}
