package com.finflow.studio.preview;

import java.util.List;

public final class PreviewModels {
    private PreviewModels() { }

    public record CsvPreview(List<String> columns, List<List<String>> rows, long rowOffset,
                             String nextCursor, boolean hasMore) { }
}
