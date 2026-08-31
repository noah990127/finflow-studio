package com.finflow.studio.project;

import java.time.Instant;

public record Project(
        String id,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}

