package com.finflow.studio.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class PasteImportModels {
    private PasteImportModels() { }

    public record Request(
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 5_000_000) String content,
            @Size(max = 32) String format,
            @Size(max = 200) String sourceAgent,
            @Size(max = 2_000) String sourceQuestion,
            Instant capturedAt,
            boolean confirmed) { }

    public record Preview(String detectedFormat, boolean structured, List<String> columns,
                          List<List<String>> rows, int rowCount, List<String> warnings,
                          String rawHash, boolean requiresConfirmation) { }

    public record Imported(KnowledgeModels.FileResourceResponse resource, Preview preview) { }
}
