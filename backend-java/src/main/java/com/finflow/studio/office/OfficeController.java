package com.finflow.studio.office;

import com.finflow.studio.office.OfficeModels.CallbackRequest;
import com.finflow.studio.office.OfficeModels.SessionResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/files/{resourceId}/callback")
    public Map<String, Integer> callback(@PathVariable String resourceId, @RequestParam int version,
                                         @RequestBody CallbackRequest request,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return office.fileCallback(resourceId, version, request, authorization);
    }

    @PostMapping("/deliverables/{resourceId}/callback")
    public Map<String, Integer> deliverableCallback(@PathVariable String resourceId, @RequestParam int version,
                                                    @RequestBody CallbackRequest request,
                                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return office.deliverableCallback(resourceId, version, request, authorization);
    }
}
