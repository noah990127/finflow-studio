package com.finflow.studio.project;

import com.finflow.studio.workflow.WorkflowDefinitionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCreationService {
    private final ProjectService projects;
    private final WorkflowDefinitionService workflows;

    public ProjectCreationService(ProjectService projects, WorkflowDefinitionService workflows) {
        this.projects = projects;
        this.workflows = workflows;
    }

    @Transactional
    public Project create(String name, String description) {
        var project = projects.create(name, description);
        workflows.getProjectWorkflow(project.id());
        return project;
    }
}
