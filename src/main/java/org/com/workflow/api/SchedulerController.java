package org.com.workflow.api;

import org.com.workflow.api.ApiPayloads.ClaimRequest;
import org.com.workflow.api.ApiPayloads.ClaimedStepResponse;
import org.com.workflow.api.ApiPayloads.CompleteStepRequest;
import org.com.workflow.api.ApiPayloads.RegisterWorkflowRequest;
import org.com.workflow.api.ApiPayloads.RunResponse;
import org.com.workflow.api.ApiPayloads.RunSummaryResponse;
import org.com.workflow.api.ApiPayloads.StartRunRequest;
import org.com.workflow.domain.StartRunOutcome;
import org.com.workflow.domain.StepResult;
import org.com.workflow.domain.ValidationException;
import org.com.workflow.service.WorkflowSchedulerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
class SchedulerController {

    private final WorkflowSchedulerService scheduler;

    SchedulerController(WorkflowSchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    void registerWorkflow(@RequestBody RegisterWorkflowRequest request) {
        scheduler.registerWorkflow(request.toDomain());
    }

    /** 201 when the run is created, 200 when an existing run is replayed for the same requestId. */
    @PostMapping("/workflows/{workflowName}/runs")
    ResponseEntity<RunResponse> startRun(
            @PathVariable String workflowName, @RequestBody StartRunRequest request) {

        StartRunOutcome outcome = scheduler.startRun(workflowName, request.requestId());
        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(RunResponse.from(outcome.run()));
    }

    @PostMapping("/runs/{runId}/claims")
    List<ClaimedStepResponse> claim(
            @PathVariable String runId, @RequestBody ClaimRequest request) {

        int maxCount = request.maxCount() == null ? 0 : request.maxCount();
        return scheduler.claim(runId, request.workerId(), maxCount).stream()
                .map(step -> new ClaimedStepResponse(step.stepId(), step.attemptNumber()))
                .toList();
    }

    @PostMapping("/runs/{runId}/steps/{stepId}/complete")
    void completeStep(
            @PathVariable String runId,
            @PathVariable String stepId,
            @RequestBody CompleteStepRequest request) {

        int attemptNumber = request.attemptNumber() == null ? -1 : request.attemptNumber();
        scheduler.completeStep(
                runId, stepId, request.workerId(), attemptNumber, parseResult(request.result()));
    }

    @GetMapping("/runs/{runId}")
    RunSummaryResponse runSummary(@PathVariable String runId) {
        return RunSummaryResponse.from(scheduler.runSummary(runId));
    }

    /** Parsed here rather than bound by Jackson so an unknown value is a 400, not a 500. */
    private static StepResult parseResult(String result) {
        if (result == null) {
            throw new ValidationException(List.of("result must be 'success' or 'fail'"));
        }
        return switch (result.toLowerCase(Locale.ROOT)) {
            case "success" -> StepResult.SUCCESS;
            case "fail" -> StepResult.FAIL;
            default -> throw new ValidationException(
                    List.of("result must be 'success' or 'fail', was '%s'".formatted(result)));
        };
    }
}
