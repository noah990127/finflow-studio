package com.finflow.studio.preview;

import java.util.List;

public final class PreviewModels {
    private PreviewModels() { }

    public record CsvPreview(List<String> columns, List<List<String>> rows, long rowOffset,
                             String nextCursor, boolean hasMore) { }

    public record SpreadsheetPreview(List<String> sheets, String sheetName, List<String> columns,
                                     List<List<String>> rows, long rowOffset, String nextCursor,
                                     boolean hasMore, long totalRows, int pageSize, long totalPages) { }
}
