package com.finflow.studio.office;

import com.finflow.studio.office.OfficeModels.CallbackRequest;
import com.finflow.studio.office.OfficeModels.SessionResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

@RestController
@RequestMapping("/api/office")
public class OfficeController {
    private final OfficeSessionService office;

    public OfficeController(OfficeSessionService office) { this.office = office; }

    @PostMapping("/files/{resourceId}/session")
    public SessionResponse createFile(@PathVariable String resourceId,
                                      @RequestParam(defaultValue = "edit") String mode) {
        return office.createFileSession(resourceId, mode);
    }

    @PostMapping("/extract-jobs/{resourceId}/session")
    public SessionResponse createExtract(@PathVariable String resourceId,
                                         @RequestParam(defaultValue = "edit") String mode) {
        return office.createExtractSession(resourceId, mode);
    }

    @PostMapping("/deliverables/{resourceId}/session")
    public SessionResponse createDeliverable(@PathVariable String resourceId,
                                             @RequestParam(defaultValue = "edit") String mode) {
        return office.createDeliverableSession(resourceId, mode);
    }

    @GetMapping("/files/{resourceId}/content")
    public void fileContent(@PathVariable String resourceId, @RequestParam int version,
                            @RequestParam String token, HttpServletResponse response) throws IOException {
        writeContent(office.fileContent(resourceId, version, token), response);
    }

    @GetMapping("/deliverables/{resourceId}/content")
    public void deliverableContent(@PathVariable String resourceId, @RequestParam int version,
                                   @RequestParam String token, HttpServletResponse response) throws IOException {
        writeContent(office.deliverableContent(resourceId, version, token), response);
    }

    @PostMapping("/files/{resourceId}/callback")
    public Map<String, Integer> callback(@PathVariable String resourceId, @RequestParam int version,
                                         @RequestParam String accessToken,
                                         @RequestBody CallbackRequest request,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return office.fileCallback(resourceId, version, accessToken, request, authorization);
    }

    @PostMapping("/deliverables/{resourceId}/callback")
    public Map<String, Integer> deliverableCallback(@PathVariable String resourceId, @RequestParam int version,
                                                    @RequestParam String accessToken,
                                                    @RequestBody CallbackRequest request,
                                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return office.deliverableCallback(resourceId, version, accessToken, request, authorization);
    }

    private void writeContent(OfficeSessionService.ContentResource content, HttpServletResponse response) throws IOException {
        response.setContentType(content.mediaType());
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Content-Length", Long.toString(Files.size(content.path())));
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode(content.name(), StandardCharsets.UTF_8).replace("+", "%20"));
        try (var input = Files.newInputStream(content.path()); var output = response.getOutputStream()) {
            input.transferTo(output);
        }
    }
}
