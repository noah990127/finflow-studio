package com.finflow.studio.skill;

import java.time.Instant;

public final class AnalysisSkillModels {
    private AnalysisSkillModels() { }

    public record AnalysisSkill(
            String id,
            String projectId,
            String name,
            String description,
            String instructions,
            String sourceName,
            String contentHash,
            Instant createdAt,
            Instant updatedAt) { }

    public record AnalysisSkillResponse(
            String id,
            String projectId,
            String name,
            String description,
            String sourceName,
            String contentHash,
            Instant createdAt,
            Instant updatedAt) {
        static AnalysisSkillResponse from(AnalysisSkill skill) {
            return new AnalysisSkillResponse(skill.id(), skill.projectId(), skill.name(), skill.description(),
                    skill.sourceName(), skill.contentHash(), skill.createdAt(), skill.updatedAt());
        }
    }
}
