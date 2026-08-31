package com.finflow.studio.deliverable;

import com.finflow.studio.worker.WorkerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PptSkillController {
    private final WorkerClient worker;

    public PptSkillController(WorkerClient worker) {
        this.worker = worker;
    }

    @GetMapping("/ppt-skills")
    public List<Map<String, Object>> list() {
        return worker.listPptSkills();
    }
}
