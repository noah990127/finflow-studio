package com.finflow.studio.data;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/data-transforms")
public class DataTransformController {
    private final DataTransformService transforms;

    public DataTransformController(DataTransformService transforms) {
        this.transforms = transforms;
    }

    @PostMapping("/generate-script")
    public Map<String, Object> generate(@PathVariable String projectId,
                                        @RequestBody DataTransformService.GenerateRequest request) {
        return transforms.generate(projectId, request);
    }

    @PostMapping("/sample")
    public Map<String, Object> sample(@PathVariable String projectId,
                                      @RequestBody DataTransformService.SampleRequest request) {
        return transforms.sample(projectId, request);
    }
}
