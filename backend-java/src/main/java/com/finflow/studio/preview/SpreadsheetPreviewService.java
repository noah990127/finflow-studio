package com.finflow.studio.preview;

import com.finflow.studio.preview.PreviewModels.SpreadsheetPreview;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.util.XMLHelper;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.XMLFilterImpl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
public class SpreadsheetPreviewService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CELL_CHARS = 4000;

    public SpreadsheetPreview preview(Path path, String requestedSheet, String cursorValue, int requestedLimit) {
        var limit = Math.max(20, Math.min(requestedLimit, MAX_PAGE_SIZE));
        var rowOffset = decodeCursor(cursorValue);
        try (var workbook = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            var reader = new XSSFReader(workbook);
            var sheets = new ArrayList<String>();
            InputStream selected = null;
            String selectedName = null;
            var iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                var stream = iterator.next();
                var name = iterator.getSheetName();
                sheets.add(name);
                var matches = requestedSheet == null || requestedSheet.isBlank()
                        ? selected == null : requestedSheet.equals(name);
                if (matches) {
                    selected = stream;
                    selectedName = name;
                } else {
                    stream.close();
                }
            }
            if (selected == null) throw new IllegalArgumentException("指定的工作表不存在");
            var selectedStream = selected;
            try (selectedStream) {
                var handler = new PageHandler(rowOffset, limit);
                var parser = XMLHelper.newXMLReader();
                var dimension = new SheetDimensionFilter(parser);
                dimension.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null,
                        reader.getSharedStringsTable(), handler, new DataFormatter(Locale.CHINA), false));
                try {
                    dimension.parse(new InputSource(selectedStream));
                } catch (StopParsing ignored) {
                    // The requested page is complete; the remainder of a large sheet must not be parsed.
                }
                var observedRows = rowOffset + handler.rows().size() + (handler.hasMore() ? 1 : 0);
                var totalRows = Math.max(dimension.totalDataRows(), observedRows);
                var totalPages = totalRows == 0 ? 0 : (totalRows + limit - 1) / limit;
                var next = handler.hasMore() ? encodeCursor(rowOffset + handler.rows().size()) : null;
                return new SpreadsheetPreview(sheets, selectedName, handler.columns(), handler.rows(),
                        rowOffset, next, handler.hasMore(), totalRows, limit, totalPages);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 文件无法分页读取，请检查文件是否完整", exception);
        }
    }

    private long decodeCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            var rowOffset = Long.parseLong(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII));
            if (rowOffset < 0) throw new IllegalArgumentException();
            return rowOffset;
        } catch (Exception exception) {
            throw new IllegalArgumentException("表格翻页位置已失效，请从第一页重新查看");
        }
    }

    private String encodeCursor(long rowOffset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Long.toString(rowOffset).getBytes(StandardCharsets.US_ASCII));
    }

    private static final class PageHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final long offset;
        private final int limit;
        private final List<String> columns = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();
        private List<String> current;
        private long dataIndex;
        private boolean hasMore;

        private PageHandler(long offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }

        @Override
        public void startRow(int rowNum) {
            current = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) {
                columns.addAll(current);
                return;
            }
            if (dataIndex++ < offset) return;
            if (rows.size() >= limit) {
                hasMore = true;
                throw new StopParsing();
            }
            while (current.size() < columns.size()) current.add("");
            rows.add(List.copyOf(current));
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            var index = columnIndex(cellReference);
            while (current.size() < index) current.add("");
            current.add(truncate(formattedValue));
        }

        private int columnIndex(String reference) {
            var result = 0;
            for (var index = 0; index < reference.length(); index++) {
                var character = reference.charAt(index);
                if (!Character.isLetter(character)) break;
                result = result * 26 + Character.toUpperCase(character) - 'A' + 1;
            }
            return Math.max(0, result - 1);
        }

        private String truncate(String value) {
            if (value == null) return "";
            return value.length() <= MAX_CELL_CHARS ? value : value.substring(0, MAX_CELL_CHARS) + "…";
        }

        private List<String> columns() { return List.copyOf(columns); }
        private List<List<String>> rows() { return List.copyOf(rows); }
        private boolean hasMore() { return hasMore; }
    }

    private static final class SheetDimensionFilter extends XMLFilterImpl {
        private long totalDataRows = -1;

        private SheetDimensionFilter(org.xml.sax.XMLReader parent) {
            super(parent);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if ("dimension".equals(localName) || "dimension".equals(qName)) {
                totalDataRows = dataRowsFromReference(attributes.getValue("ref"));
            }
            super.startElement(uri, localName, qName, attributes);
        }

        private long totalDataRows() {
            return Math.max(0, totalDataRows);
        }

        private long dataRowsFromReference(String reference) {
            if (reference == null || reference.isBlank()) return -1;
            var end = reference.contains(":") ? reference.substring(reference.indexOf(':') + 1) : reference;
            var firstDigit = 0;
            while (firstDigit < end.length() && !Character.isDigit(end.charAt(firstDigit))) firstDigit++;
            if (firstDigit == end.length()) return -1;
            try {
                return Math.max(0, Long.parseLong(end.substring(firstDigit)) - 1);
            } catch (NumberFormatException exception) {
                return -1;
            }
        }
    }

    private static final class StopParsing extends RuntimeException { }
}
