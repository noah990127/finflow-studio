package com.finflow.studio.skill;

import com.finflow.studio.skill.AnalysisSkillModels.AnalysisSkillResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AnalysisSkillController {
    private final AnalysisSkillService skills;

    public AnalysisSkillController(AnalysisSkillService skills) {
        this.skills = skills;
    }

    @GetMapping("/projects/{projectId}/analysis-skills")
    public List<AnalysisSkillResponse> list(@PathVariable String projectId) {
        return skills.list(projectId);
    }

    @PostMapping(value = "/projects/{projectId}/analysis-skills", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public AnalysisSkillResponse upload(@PathVariable String projectId, @RequestPart MultipartFile file) {
        return skills.upload(projectId, file);
    }

    @DeleteMapping("/analysis-skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        skills.delete(id);
    }
}
