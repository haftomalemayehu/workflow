package org.com.workflow.api;

import org.com.workflow.domain.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiPayloadsTest {

    @Test
    void toDomainTreatsAMissingStepsListAsEmpty() {
        ApiPayloads.RegisterWorkflowRequest request =
                new ApiPayloads.RegisterWorkflowRequest("wf", null);

        WorkflowDefinition definition = request.toDomain();

        assertThat(definition.workflowName()).isEqualTo("wf");
        assertThat(definition.steps()).isEmpty();
    }

    @Test
    void toDomainTreatsAMissingDependenciesListAsEmpty() {
        ApiPayloads.RegisterWorkflowRequest.StepPayload step =
                new ApiPayloads.RegisterWorkflowRequest.StepPayload("a", 1, 1, null);
        ApiPayloads.RegisterWorkflowRequest request =
                new ApiPayloads.RegisterWorkflowRequest("wf", List.of(step));

        WorkflowDefinition definition = request.toDomain();

        assertThat(definition.steps()).singleElement()
                .satisfies(s -> assertThat(s.dependencies()).isEmpty());
    }
}
