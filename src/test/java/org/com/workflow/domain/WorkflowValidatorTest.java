package org.com.workflow.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowValidatorTest {

    private static StepDefinition step(String id, String... dependencies) {
        return new StepDefinition(id, 1, 1, List.of(dependencies));
    }

    @Test
    void rejectsBlankWorkflowName() {
        WorkflowDefinition definition = new WorkflowDefinition("  ", List.of(step("a")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .anySatisfy(message -> assertThat(message).contains("workflowName"));
    }

    @Test
    void rejectsBlankStepId() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", List.of(step(" ")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stepId must not be blank");
    }

    @Test
    void rejectsDuplicateStepId() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", List.of(step("a"), step("a")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate stepId 'a'");
    }

    @Test
    void rejectsNonPositiveMaxAttempts() {
        WorkflowDefinition definition =
                new WorkflowDefinition("wf", List.of(new StepDefinition("a", 1, 0, List.of())));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void acceptsAValidDefinition() {
        WorkflowDefinition definition =
                new WorkflowDefinition("wf", List.of(step("a"), step("b", "a")));

        WorkflowValidator.validate(definition);
    }

    @Test
    void rejectsDependencyOnUnknownStep() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", List.of(step("a", "ghost")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknown step 'ghost'");
    }

    @Test
    void rejectsSelfDependency() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", List.of(step("a", "a")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("step 'a' depends on itself");
    }

    @Test
    void rejectsTwoNodeCycle() {
        WorkflowDefinition definition =
                new WorkflowDefinition("wf", List.of(step("a", "b"), step("b", "a")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    void rejectsThreeNodeCycle() {
        WorkflowDefinition definition = new WorkflowDefinition(
                "wf", List.of(step("a", "c"), step("b", "a"), step("c", "b")));

        assertThatThrownBy(() -> WorkflowValidator.validate(definition))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void acceptsADiamondWhichIsNotACycle() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", List.of(
                step("load"), step("left", "load"), step("right", "load"),
                step("join", "left", "right")));

        WorkflowValidator.validate(definition);
    }
}
