package com.finflow.studio.office;

import java.util.Map;

public final class OfficeModels {
    private OfficeModels() { }

    public record SessionResponse(boolean enabled, String documentServerUrl, String workingResourceId,
                                  String message, Map<String, Object> config) { }

    public record CallbackRequest(Integer status, String url, String key, String token) { }
}
