package com.finflow.studio.knowledge;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SpreadsheetCsvConverter {
    static final int DEFAULT_ROWS_PER_FILE = 250_000;
    private static final int OUTPUT_BUFFER_SIZE = 1024 * 1024;

    ConversionResult convert(Path source, String sourceName, int requestedRowsPerFile) {
        return convert(source, sourceName, requestedRowsPerFile, ignored -> { });
    }

    ConversionResult convert(Path source, String sourceName, int requestedRowsPerFile,
                             java.util.function.LongConsumer progress) {
        var rowsPerFile = Math.max(10_000, Math.min(requestedRowsPerFile, 1_000_000));
        var outputs = new ArrayList<CsvPart>();
        try (var workbook = OPCPackage.open(source.toFile(), PackageAccess.READ)) {
            var reader = new XSSFReader(workbook);
            var iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            var sheetIndex = 0;
            var completedRows = new long[] { 0 };
            while (iterator.hasNext()) {
                sheetIndex++;
                try (var stream = iterator.next()) {
                    var sheetName = iterator.getSheetName();
                    var handler = new SheetHandler(sourceName, sheetName, sheetIndex, rowsPerFile, outputs,
                            rows -> progress.accept(completedRows[0] + rows));
                    var parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null,
                            reader.getSharedStringsTable(), handler, new DataFormatter(Locale.CHINA), false));
                    try {
                        parser.parse(new InputSource(stream));
                        handler.finish();
                        completedRows[0] += handler.processedRows();
                    } catch (Exception exception) {
                        handler.abort();
                        throw exception;
                    }
                }
            }
            if (outputs.isEmpty()) throw new IllegalArgumentException("工作簿中没有可转换的工作表");
            return new ConversionResult(List.copyOf(outputs));
        } catch (IllegalArgumentException exception) {
            delete(outputs);
            throw exception;
        } catch (Exception exception) {
            delete(outputs);
            throw new IllegalArgumentException("Excel 转换失败，请确认文件完整且未加密", exception);
        }
    }

    record CsvPart(Path path, String name, String sheetName, int partNumber, long rowCount) { }

    record ConversionResult(List<CsvPart> parts) implements AutoCloseable {
        @Override public void close() { delete(parts); }
    }

    private static final class SheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final String sourceBaseName;
        private final String sheetName;
        private final int sheetIndex;
        private final int rowsPerFile;
        private final List<CsvPart> outputs;
        private final List<String> header = new ArrayList<>();
        private List<String> current = new ArrayList<>();
        private Path currentPath;
        private String currentName;
        private BufferedWriter writer;
        private int partNumber;
        private long rowsInPart;
        private long processedRows;
        private boolean headerRead;
        private final java.util.function.LongConsumer progress;

        private SheetHandler(String sourceName, String sheetName, int sheetIndex, int rowsPerFile,
                             List<CsvPart> outputs, java.util.function.LongConsumer progress) {
            this.sourceBaseName = stripExtension(sourceName);
            this.sheetName = sheetName;
            this.sheetIndex = sheetIndex;
            this.rowsPerFile = rowsPerFile;
            this.outputs = outputs;
            this.progress = progress;
        }

        @Override public void startRow(int rowNum) { current = new ArrayList<>(); }

        @Override public void endRow(int rowNum) {
            try {
                if (!headerRead) {
                    header.addAll(current);
                    headerRead = true;
                    return;
                }
                if (writer == null || rowsInPart >= rowsPerFile) openPart();
                pad(current, header.size());
                writeRecord(writer, current);
                rowsInPart++;
                processedRows++;
                if (processedRows % 5_000 == 0) progress.accept(processedRows);
            } catch (Exception exception) {
                throw new IllegalStateException("写入 CSV 失败", exception);
            }
        }

        @Override public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            var index = columnIndex(cellReference);
            while (current.size() < index) current.add("");
            current.add(formattedValue == null ? "" : formattedValue);
        }

        private void openPart() throws Exception {
            closePart();
            partNumber++;
            rowsInPart = 0;
            currentName = "%s_%02d_%s_%03d.csv".formatted(sourceBaseName, sheetIndex,
                    safeSegment(sheetName), partNumber);
            currentPath = Files.createTempFile("studio-xlsx-csv-", ".csv");
            writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(currentPath),
                    StandardCharsets.UTF_8), OUTPUT_BUFFER_SIZE);
            if (!header.isEmpty()) writeRecord(writer, header);
        }

        private void closePart() throws Exception {
            if (writer == null) return;
            writer.close();
            outputs.add(new CsvPart(currentPath, currentName, sheetName, partNumber, rowsInPart));
            writer = null;
        }

        private void finish() throws Exception {
            if (writer == null) openPart();
            closePart();
            progress.accept(processedRows);
        }

        private void abort() {
            try { closePart(); } catch (Exception ignored) { }
        }

        private long processedRows() { return processedRows; }
    }

    private static void writeRecord(BufferedWriter writer, List<String> values) throws java.io.IOException {
        for (var index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            var value = values.get(index);
            var quoted = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
            if (!quoted) {
                writer.write(value);
                continue;
            }
            writer.write('"');
            for (var characterIndex = 0; characterIndex < value.length(); characterIndex++) {
                var character = value.charAt(characterIndex);
                if (character == '"') writer.write('"');
                writer.write(character);
            }
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private static int columnIndex(String reference) {
        if (reference == null) return 0;
        var result = 0;
        for (var index = 0; index < reference.length(); index++) {
            var character = reference.charAt(index);
            if (!Character.isLetter(character)) break;
            result = result * 26 + Character.toUpperCase(character) - 'A' + 1;
        }
        return Math.max(0, result - 1);
    }

    private static void pad(List<String> values, int size) {
        while (values.size() < size) values.add("");
    }

    private static String stripExtension(String name) {
        var value = Path.of(name).getFileName().toString();
        var dot = value.lastIndexOf('.');
        return safeSegment(dot > 0 ? value.substring(0, dot) : value);
    }

    private static String safeSegment(String value) {
        var safe = value == null ? "sheet" : value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return safe.isBlank() ? "sheet" : safe;
    }

    private static void delete(List<CsvPart> parts) {
        for (var part : parts) {
            try { Files.deleteIfExists(part.path()); } catch (Exception ignored) { }
        }
    }
}
