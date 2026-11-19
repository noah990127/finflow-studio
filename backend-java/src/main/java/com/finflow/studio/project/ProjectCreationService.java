package com.finflow.studio.project;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCreationService {
    private final ProjectService projects;

    public ProjectCreationService(ProjectService projects) {
        this.projects = projects;
    }

    @Transactional
    public Project create(String name, String description) {
        return projects.create(name, description);
    }
}
