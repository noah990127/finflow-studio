package com.finflow.studio.preview;

import com.finflow.studio.preview.PreviewModels.CsvPreview;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class CsvPreviewService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CELL_CHARS = 4000;
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get();

    public CsvPreview preview(Path path, String cursorValue, int requestedLimit) {
        var limit = Math.max(20, Math.min(requestedLimit, MAX_PAGE_SIZE));
        var columns = readHeader(path);
        if (columns.isEmpty()) return new CsvPreview(List.of(), List.of(), 0, null, false);
        var cursor = decodeCursor(cursorValue, path);
        try (var input = Files.newInputStream(path)) {
            if (cursor.byteOffset() > 0) input.skipNBytes(cursor.byteOffset());
            try (var parser = CSVParser.builder().setInputStream(input).setCharset(StandardCharsets.UTF_8)
                    .setFormat(FORMAT).setTrackBytes(true).get()) {
                var iterator = parser.iterator();
                if (cursor.byteOffset() == 0 && iterator.hasNext()) iterator.next();
                var rows = new ArrayList<List<String>>(limit);
                String nextCursor = null;
                while (iterator.hasNext()) {
                    var record = iterator.next();
                    if (rows.size() == limit) {
                        nextCursor = encodeCursor(cursor.byteOffset() + record.getBytePosition(),
                                cursor.rowOffset() + rows.size());
                        break;
                    }
                    var row = new ArrayList<String>(Math.max(columns.size(), record.size()));
                    record.forEach(value -> row.add(truncate(value)));
                    while (row.size() < columns.size()) row.add("");
                    rows.add(row);
                }
                return new CsvPreview(columns, rows, cursor.rowOffset(), nextCursor, nextCursor != null);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("CSV 文件无法读取，请检查文件编码和格式", exception);
        }
    }

    private List<String> readHeader(Path path) {
        try (var parser = CSVParser.builder().setPath(path).setCharset(StandardCharsets.UTF_8)
                .setFormat(FORMAT).setTrackBytes(true).get()) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) return List.of();
            var header = new ArrayList<String>();
            iterator.next().forEach(value -> header.add(truncate(value.replace("\ufeff", ""))));
            return header;
        } catch (IOException exception) {
            throw new IllegalArgumentException("CSV 文件无法读取", exception);
        }
    }

    private Cursor decodeCursor(String value, Path path) {
        if (value == null || value.isBlank()) return new Cursor(0, 0);
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(":", -1);
            var byteOffset = Long.parseLong(decoded[0]);
            var rowOffset = Long.parseLong(decoded[1]);
            if (byteOffset <= 0 || byteOffset >= Files.size(path) || rowOffset < 0) throw new IllegalArgumentException();
            return new Cursor(byteOffset, rowOffset);
        } catch (Exception exception) {
            throw new IllegalArgumentException("表格翻页位置已失效，请从第一页重新查看");
        }
    }

    private String encodeCursor(long byteOffset, long rowOffset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (byteOffset + ":" + rowOffset).getBytes(StandardCharsets.US_ASCII));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_CELL_CHARS) return value == null ? "" : value;
        return value.substring(0, MAX_CELL_CHARS) + "…";
    }

    private record Cursor(long byteOffset, long rowOffset) { }
}
