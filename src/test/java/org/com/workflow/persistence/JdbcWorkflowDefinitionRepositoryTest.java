package org.com.workflow.persistence;

import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkflowDefinitionRepositoryTest {

    @TempDir
    Path directory;

    private JdbcWorkflowDefinitionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = SqliteTestSupport.freshDatabase(directory);
        repository = new JdbcWorkflowDefinitionRepository(jdbc);
    }

    @Test
    void roundTripsAWorkflowWithItsStepsAndDependencies() {
        WorkflowDefinition saved = new WorkflowDefinition("model-publish", List.of(
                new StepDefinition("load-model", 10, 1, List.of()),
                new StepDefinition("validate-schema", 9, 2, List.of("load-model")),
                new StepDefinition("publish-event", 7, 1, List.of("validate-schema", "load-model"))));

        repository.save(saved);

        WorkflowDefinition loaded = repository.findByName("model-publish").orElseThrow();
        assertThat(loaded.workflowName()).isEqualTo("model-publish");
        assertThat(loaded.steps()).containsExactly(
                new StepDefinition("load-model", 10, 1, List.of()),
                new StepDefinition("publish-event", 7, 1, List.of("load-model", "validate-schema")),
                new StepDefinition("validate-schema", 9, 2, List.of("load-model")));
    }

    @Test
    void returnsEmptyForAnUnknownWorkflow() {
        assertThat(repository.findByName("ghost")).isEmpty();
    }

    @Test
    void reRegisteringAWorkflowReplacesItsPreviousSteps() {
        repository.save(new WorkflowDefinition("wf",
                List.of(new StepDefinition("old", 1, 1, List.of()))));

        repository.save(new WorkflowDefinition("wf",
                List.of(new StepDefinition("new", 2, 3, List.of()))));

        WorkflowDefinition loaded = repository.findByName("wf").orElseThrow();
        assertThat(loaded.steps())
                .containsExactly(new StepDefinition("new", 2, 3, List.of()));
    }
}
