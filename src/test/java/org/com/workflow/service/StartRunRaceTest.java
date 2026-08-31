package org.com.workflow.service;

import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.StartRunOutcome;
import org.com.workflow.domain.WorkflowRun;
import org.com.workflow.persistence.JdbcStepInstanceRepository;
import org.com.workflow.persistence.JdbcWorkflowDefinitionRepository;
import org.com.workflow.persistence.JdbcWorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.com.workflow.service.SchedulerTestFixtures.MODEL_PUBLISH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WorkflowSchedulerService#startRun}'s catch of {@link DuplicateKeyException} —
 * the branch taken when a concurrent submitter's insert commits first.
 *
 * <p>{@link ConcurrentClaimTest#concurrentStartRunWithTheSameRequestIdYieldsASingleRun()} exercises
 * real concurrent {@code startRun} calls, but can't reach this branch: SQLite's {@code BEGIN
 * IMMEDIATE} serializes writers, so by the time a second caller gets the write lock, the first
 * caller's row is already visible and the idempotency check above the insert short-circuits before
 * a second insert is ever attempted. Forcing the race requires mocking the repository directly.
 */
class StartRunRaceTest {

    @Test
    void startRunReturnsTheWinnersRunWhenItsOwnInsertLosesTheUniqueConstraintRace() {
        JdbcWorkflowDefinitionRepository definitions = mock(JdbcWorkflowDefinitionRepository.class);
        JdbcWorkflowRunRepository runs = mock(JdbcWorkflowRunRepository.class);
        JdbcStepInstanceRepository stepInstances = mock(JdbcStepInstanceRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        when(definitions.findByName("model-publish")).thenReturn(Optional.of(MODEL_PUBLISH));

        WorkflowRun winner = new WorkflowRun("winner-id", "model-publish", "req-1", RunStatus.RUNNING);
        // Not present when startRun makes its own idempotency check, but present once startRun
        // re-checks after losing the race — as if a concurrent caller committed in between.
        when(runs.findByIdempotencyKey("model-publish", "req-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        doThrow(new DuplicateKeyException("unique constraint violated"))
                .when(runs).insert(any());

        WorkflowSchedulerService service = new WorkflowSchedulerService(
                definitions, runs, stepInstances, transactions);

        StartRunOutcome outcome = service.startRun("model-publish", "req-1");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.run()).isEqualTo(winner);
    }
}
