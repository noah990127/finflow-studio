package com.finflow.studio.knowledge;

import java.time.Instant;
import java.util.Map;

public final class KnowledgeModels {
    private KnowledgeModels() { }

    public record FileResourceResponse(String id, String projectId, String name, String mediaType, String status,
                                       int currentVersion, long sizeBytes, String checksum, String parseStatus,
                                       String parseMessage, Instant createdAt, Instant updatedAt) { }

    public record RefResponse(String id, String projectId, String resourceId, int version, String sourceName,
                              String text, Map<String, Object> location, String contentHash, double score) { }
}
