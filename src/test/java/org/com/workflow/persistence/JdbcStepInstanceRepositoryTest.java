package org.com.workflow.persistence;

import org.com.workflow.domain.DependencyGraph;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.StepInstance;
import org.com.workflow.domain.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStepInstanceRepositoryTest {

    private static final String RUN = "run-1";

    @TempDir
    Path directory;

    private JdbcTemplate jdbc;
    private JdbcStepInstanceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = SqliteTestSupport.freshDatabase(directory);
        repository = new JdbcStepInstanceRepository(jdbc);
        repository.createAll(RUN, List.of(
                new StepDefinition("a", 10, 2, List.of()),
                new StepDefinition("b", 5, 1, List.of("a"))));
    }

    @Test
    void createsOnePendingInstancePerStepCarryingTheDefinitionsPriorityAndBudget() {
        assertThat(repository.findByRunId(RUN)).containsExactly(
                new StepInstance("a", StepStatus.PENDING, 0, 2, 10, null),
                new StepInstance("b", StepStatus.PENDING, 0, 1, 5, null));
    }

    @Test
    void copiesTheDependencyEdgesIntoTheRun() {
        DependencyGraph graph = repository.findGraph(RUN);

        assertThat(graph.dependenciesOf("b")).containsExactly("a");
        assertThat(graph.dependenciesOf("a")).isEmpty();
    }

    @Test
    void aClaimMovesThePendingStepToInProgressAndIncrementsTheAttempt() {
        boolean claimed = repository.tryClaim(
                RUN, new StepInstance("a", StepStatus.PENDING, 0, 2, 10, null), "worker-a");

        assertThat(claimed).isTrue();
        assertThat(repository.findByRunId(RUN)).first().isEqualTo(
                new StepInstance("a", StepStatus.IN_PROGRESS, 1, 2, 10, "worker-a"));
    }

    /**
     * The compare-and-swap: a second writer holding a stale observation must lose, so the step is
     * dropped from its batch rather than handed out twice.
     */
    @Test
    void aClaimOnAStaleObservationLosesTheRace() {
        StepInstance stale = new StepInstance("a", StepStatus.PENDING, 0, 2, 10, null);
        repository.tryClaim(RUN, stale, "worker-a");

        boolean secondClaim = repository.tryClaim(RUN, stale, "worker-b");

        assertThat(secondClaim).isFalse();
        assertThat(repository.findByRunId(RUN)).first()
                .extracting(StepInstance::lastWorkerId).isEqualTo("worker-a");
    }

    @Test
    void aStepThatIsNotPendingCannotBeClaimed() {
        repository.applyCompletion(RUN, "a", StepStatus.SUCCEEDED);

        boolean claimed = repository.tryClaim(
                RUN, new StepInstance("a", StepStatus.PENDING, 0, 2, 10, null), "worker-a");

        assertThat(claimed).isFalse();
    }

    @Test
    void refusesToPersistTheDerivedBlockedStatus() {
        assertThatThrownBy(() -> repository.applyCompletion(RUN, "a", StepStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived");
    }

    @Test
    void appendsAttemptEventsInOrder() {
        repository.appendEvent(RUN, "a", 1, "claimed", "worker-a");
        repository.appendEvent(RUN, "a", 1, "failed", "worker-a");

        assertThat(jdbc.queryForList(
                "SELECT event_type FROM step_attempt_event WHERE run_id = ? ORDER BY event_id",
                String.class, RUN))
                .containsExactly("claimed", "failed");
    }
}
