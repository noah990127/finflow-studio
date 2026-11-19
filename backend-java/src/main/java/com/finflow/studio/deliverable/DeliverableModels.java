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
                                 List<String> refIds, List<CitationRequest> citations,
                                 String chapter, String coreMessage, String layout, Map<String, Object> chart,
                                 Map<String, Object> visual) {
        public SectionRequest(String heading, List<String> paragraphs, List<String> bullets,
                              List<String> refIds, List<CitationRequest> citations,
                              String chapter, String coreMessage, String layout, Map<String, Object> chart) {
            this(heading, paragraphs, bullets, refIds, citations, chapter, coreMessage, layout, chart, null);
        }
        public SectionRequest(String heading, List<String> paragraphs, List<String> bullets,
                              List<String> refIds, List<CitationRequest> citations) {
            this(heading, paragraphs, bullets, refIds, citations, null, null, null, null);
        }
    }

    public record CitationRequest(String id, String resourceId, int version, String sourceName,
                                  String text, Map<String, Object> location, String contentHash) { }

    public record CreateRequest(@NotBlank String projectId, String resourceId, @NotBlank String title,
                                String subtitle, @NotBlank String format, String pptSkill,
                                boolean includeCitations, String citationStyle,
                                @NotEmpty List<@Valid SectionRequest> sections, String planMode,
                                Map<String, Object> presentationBrief) {
        public CreateRequest(String projectId, String resourceId, String title, String subtitle,
                             String format, String pptSkill, boolean includeCitations, String citationStyle,
                             List<SectionRequest> sections, String planMode) {
            this(projectId, resourceId, title, subtitle, format, pptSkill, includeCitations, citationStyle,
                    sections, planMode, Map.of());
        }
        public CreateRequest(String projectId, String resourceId, String title, String subtitle,
                             String format, String pptSkill, boolean includeCitations, String citationStyle,
                             List<SectionRequest> sections) {
            this(projectId, resourceId, title, subtitle, format, pptSkill, includeCitations, citationStyle, sections, "auto");
        }
    }

    public record Response(String id, String projectId, String name, String format, int currentVersion,
                           String status, long sizeBytes, String checksum, Instant createdAt, Instant updatedAt) { }
}
