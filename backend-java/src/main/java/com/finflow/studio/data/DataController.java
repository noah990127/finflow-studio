package com.finflow.studio.data;

import com.finflow.studio.data.DataModels.*;
import com.finflow.studio.preview.CsvPreviewService;
import com.finflow.studio.preview.PreviewModels.CsvPreview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DataController {
    private final DataConnectionService connections;
    private final ExtractJobService extracts;
    private final CsvPreviewService previews;

    public DataController(DataConnectionService connections, ExtractJobService extracts, CsvPreviewService previews) {
        this.connections = connections;
        this.extracts = extracts;
        this.previews = previews;
    }

    @PostMapping("/data-connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse createConnection(@Valid @RequestBody CreateConnectionRequest request) {
        return connections.create(request);
    }

    @PutMapping("/data-connections/{id}")
    public ConnectionResponse updateConnection(@PathVariable String id,
                                               @Valid @RequestBody UpdateConnectionRequest request) {
        return connections.update(id, request);
    }

    @GetMapping("/projects/{projectId}/data-connections")
    public List<ConnectionResponse> listConnections(@PathVariable String projectId) {
        return connections.list(projectId).stream().map(connections::safeForDisplay).toList();
    }

    @PostMapping("/data-connections/{id}/test")
    public TestConnectionResponse testConnection(@PathVariable String id) {
        return connections.test(id);
    }

    @PostMapping("/data-connections/{id}/preview")
    public ConnectionPreviewResponse previewConnection(@PathVariable String id,
                                                        @Valid @RequestBody PreviewConnectionRequest request) {
        return connections.preview(id, request);
    }

    @GetMapping("/data-connections/{id}/catalog")
    public DatabaseCatalogResponse connectionCatalog(@PathVariable String id) {
        return connections.catalog(id);
    }

    @DeleteMapping("/data-connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConnection(@PathVariable String id) { connections.delete(id); }

    @PostMapping("/extract-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExtractJobResponse createExtract(@Valid @RequestBody CreateExtractRequest request) {
        return extracts.create(request);
    }

    @GetMapping("/projects/{projectId}/extract-jobs")
    public List<ExtractJobResponse> listExtracts(@PathVariable String projectId) {
        return extracts.list(projectId);
    }

    @GetMapping("/extract-jobs/{id}")
    public ExtractJobResponse getExtract(@PathVariable String id) {
        return extracts.get(id);
    }

    @PostMapping("/extract-jobs/{id}/cancel")
    public ExtractJobResponse cancelExtract(@PathVariable String id) {
        return extracts.cancel(id);
    }

    @DeleteMapping("/extract-jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExtract(@PathVariable String id) { extracts.delete(id); }

    @GetMapping("/extract-jobs/{id}/preview")
    public CsvPreview preview(@PathVariable String id, @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "100") int limit) {
        return previews.preview(extracts.outputPath(id), cursor, limit);
    }

    @GetMapping("/extract-jobs/{id}/download")
    public void download(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var job = extracts.get(id);
        var path = extracts.outputPath(id);
        var size = Files.size(path);
        var range = parseRange(request.getHeader("Range"), size);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(job.outputName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        response.setContentType("text/csv; charset=UTF-8");
        response.setStatus(range.partial() ? 206 : 200);
        response.setHeader("Content-Length", Long.toString(range.length()));
        if (range.partial()) {
            response.setHeader("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + size);
        }
        try (var file = new RandomAccessFile(path.toFile(), "r"); var output = response.getOutputStream()) {
            file.seek(range.start());
            var buffer = new byte[64 * 1024];
            var remaining = range.length();
            while (remaining > 0) {
                var read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private ByteRange parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return new ByteRange(0, Math.max(0, size - 1), false);
        }
        try {
            var parts = header.substring(6).split("-", -1);
            long start;
            long end;
            if (parts[0].isBlank()) {
                var suffix = Long.parseLong(parts[1]);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isBlank() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            if (start < 0 || start > end || start >= size) throw new IllegalArgumentException();
            return new ByteRange(start, end, true);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("下载范围不正确");
        }
    }

    private record ByteRange(long start, long end, boolean partial) {
        long length() { return end - start + 1; }
    }
}
