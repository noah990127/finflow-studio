package com.finflow.studio.deliverable;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DeliverableModels {
    private DeliverableModels() { }

    public record SectionRequest(@NotBlank String heading, List<String> paragraphs, List<String> bullets,
                                 List<String> refIds, List<CitationRequest> citations) { }

    public record CitationRequest(String id, String resourceId, int version, String sourceName,
                                  String text, Map<String, Object> location, String contentHash) { }

    public record CreateRequest(@NotBlank String projectId, String resourceId, @NotBlank String title,
                                String subtitle, @NotBlank String format, String pptSkill,
                                boolean includeCitations, String citationStyle,
                                @NotEmpty List<@Valid SectionRequest> sections) { }

    public record Response(String id, String projectId, String name, String format, int currentVersion,
                           String status, long sizeBytes, String checksum, Instant createdAt, Instant updatedAt) { }
}
