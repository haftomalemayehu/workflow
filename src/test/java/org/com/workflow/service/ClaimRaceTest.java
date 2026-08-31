package org.com.workflow.service;

import org.com.workflow.domain.ClaimedStep;
import org.com.workflow.domain.DependencyGraph;
import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.StepInstance;
import org.com.workflow.domain.StepStatus;
import org.com.workflow.domain.WorkflowRun;
import org.com.workflow.persistence.JdbcStepInstanceRepository;
import org.com.workflow.persistence.JdbcWorkflowDefinitionRepository;
import org.com.workflow.persistence.JdbcWorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WorkflowSchedulerService#claim}'s handling of a candidate whose compare-and-swap
 * loses — dropped from the result rather than double-assigned.
 *
 * <p>Real SQLite can't reach this within one {@code claim()} call: the whole batch runs inside one
 * {@code BEGIN IMMEDIATE} transaction, so every candidate's CAS is checked against a row nothing
 * else could have touched since it was read moments earlier in the same transaction. Forcing a
 * candidate to lose needs a mocked repository, same reasoning as {@link StartRunRaceTest}.
 */
class ClaimRaceTest {

    @Test
    void claimDropsACandidateWhoseCompareAndSwapLoses() {
        JdbcWorkflowDefinitionRepository definitions = mock(JdbcWorkflowDefinitionRepository.class);
        JdbcWorkflowRunRepository runs = mock(JdbcWorkflowRunRepository.class);
        JdbcStepInstanceRepository stepInstances = mock(JdbcStepInstanceRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        String runId = "run-1";
        when(runs.findById(runId))
                .thenReturn(Optional.of(new WorkflowRun(runId, "wide", "r", RunStatus.RUNNING)));

        DependencyGraph graph = DependencyGraph.of(List.of(
                new StepDefinition("a", 1, 1, List.of()),
                new StepDefinition("b", 1, 1, List.of())));
        when(stepInstances.findGraph(runId)).thenReturn(graph);

        StepInstance a = new StepInstance("a", StepStatus.PENDING, 0, 1, 1, null);
        StepInstance b = new StepInstance("b", StepStatus.PENDING, 0, 1, 1, null);
        when(stepInstances.findByRunId(runId)).thenReturn(List.of(a, b));

        when(stepInstances.tryClaim(runId, a, "worker-a")).thenReturn(true);
        // As if another writer claimed "b" between it being read as a candidate and this CAS.
        when(stepInstances.tryClaim(runId, b, "worker-a")).thenReturn(false);

        WorkflowSchedulerService service = new WorkflowSchedulerService(
                definitions, runs, stepInstances, transactions);

        List<ClaimedStep> claimed = service.claim(runId, "worker-a", 2);

        assertThat(claimed).containsExactly(new ClaimedStep("a", 1));
        verify(stepInstances).appendEvent(runId, "a", 1, "claimed", "worker-a");
        verify(stepInstances, never()).appendEvent(eq(runId), eq("b"), anyInt(), any(), any());
    }
}
