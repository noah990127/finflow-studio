package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.RunResponse;
import com.finflow.studio.workflow.WorkflowModels.ReviewRequest;
import com.finflow.studio.workflow.WorkflowModels.SaveRequest;
import com.finflow.studio.workflow.WorkflowModels.ValidationResponse;
import com.finflow.studio.workflow.WorkflowModels.WorkflowDocument;
import com.finflow.studio.workflow.WorkflowModels.WorkflowResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowDefinitionService definitions;
    private final WorkflowRunService runs;
    private final WorkflowRunEventService events;

    public WorkflowController(WorkflowDefinitionService definitions, WorkflowRunService runs,
                              WorkflowRunEventService events) {
        this.definitions = definitions;
        this.runs = runs;
        this.events = events;
    }

    @GetMapping("/projects/{projectId}/workflows")
    public List<WorkflowResponse> list(@PathVariable String projectId) {
        return definitions.list(projectId);
    }

    @GetMapping("/projects/{projectId}/workflow")
    public WorkflowResponse getProjectWorkflow(@PathVariable String projectId) {
        return definitions.getProjectWorkflow(projectId);
    }

    @PutMapping("/projects/{projectId}/workflow")
    public WorkflowResponse saveProjectWorkflow(@PathVariable String projectId,
                                                @Valid @RequestBody SaveRequest request) {
        return definitions.saveProjectWorkflow(projectId, request);
    }

    @PostMapping("/projects/{projectId}/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowResponse create(@PathVariable String projectId, @Valid @RequestBody SaveRequest request) {
        return definitions.create(projectId, request);
    }

    @GetMapping("/workflows/{id}")
    public WorkflowResponse get(@PathVariable String id) {
        return definitions.get(id);
    }

    @PutMapping("/workflows/{id}")
    public WorkflowResponse update(@PathVariable String id, @Valid @RequestBody SaveRequest request) {
        return definitions.update(id, request);
    }

    @DeleteMapping("/workflows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        definitions.delete(id);
    }

    @PostMapping("/projects/{projectId}/workflows/validate")
    public ValidationResponse validate(@PathVariable String projectId, @Valid @RequestBody SaveRequest request) {
        return definitions.validate(projectId, new WorkflowDocument(request.name(), request.description(),
                request.nodes(), request.edges() == null ? List.of() : request.edges(),
                request.executionMode(), request.schedule()));
    }

    @PostMapping("/projects/{projectId}/workflow/validate")
    public ValidationResponse validateProjectWorkflow(@PathVariable String projectId,
                                                       @Valid @RequestBody SaveRequest request) {
        return validate(projectId, request);
    }

    @PostMapping("/workflows/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse start(@PathVariable String id) {
        return runs.start(id);
    }

    @GetMapping("/workflows/{id}/runs")
    public List<RunResponse> listRuns(@PathVariable String id) {
        return runs.list(id);
    }

    @GetMapping("/workflow-runs/{id}")
    public RunResponse getRun(@PathVariable String id) {
        return runs.get(id);
    }

    @GetMapping(value = "/workflow-runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String id,
                                @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId,
                                @RequestParam(value = "after", required = false) Long after) {
        runs.get(id);
        return events.subscribe(id, after == null ? lastEventId : after);
    }

    @GetMapping("/workflow-runs/{id}/event-history")
    public List<WorkflowRunEventService.RunProgressEvent> eventHistory(
            @PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        runs.get(id);
        return events.list(id, after);
    }

    @PostMapping("/workflow-runs/{id}/cancel")
    public RunResponse cancel(@PathVariable String id) {
        return runs.cancel(id);
    }

    @PostMapping("/workflow-runs/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse retry(@PathVariable String id) {
        return runs.retry(id);
    }

    @PostMapping("/workflow-runs/{id}/review/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse confirmReview(@PathVariable String id, @Valid @RequestBody ReviewRequest request) {
        return runs.confirmReview(id, request);
    }

    @PostMapping("/workflow-runs/{id}/review/reject")
    public RunResponse rejectReview(@PathVariable String id, @Valid @RequestBody ReviewRequest request) {
        return runs.rejectReview(id, request);
    }
}
