package com.finflow.studio.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectCreationService creation;

    public ProjectController(ProjectService projects, ProjectCreationService creation) {
        this.projects = projects;
        this.creation = creation;
    }

    @GetMapping
    List<Project> list() {
        return projects.list();
    }

    @GetMapping("/{id}")
    Project get(@PathVariable String id) {
        return projects.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Project create(@Valid @RequestBody CreateProjectRequest request) {
        return creation.create(request.name(), request.description());
    }

    @PutMapping("/{id}")
    Project update(@PathVariable String id, @Valid @RequestBody CreateProjectRequest request) {
        return projects.update(id, request.name(), request.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) {
        projects.delete(id);
    }

    public record CreateProjectRequest(@NotBlank String name, String description) {
    }
}
