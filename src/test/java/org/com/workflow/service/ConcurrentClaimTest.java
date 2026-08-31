package org.com.workflow.service;

import org.com.workflow.domain.ClaimedStep;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.StepInstance;
import org.com.workflow.domain.StepStatus;
import org.com.workflow.domain.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exercise does not require real concurrency, but the design claims claiming is atomic, so
 * these exercise that claim rather than leaving it as prose.
 *
 * <p>What they actually prove is the <em>transaction</em> boundary: SQLite's BEGIN IMMEDIATE
 * serializes writers, so eight threads claiming at once cannot interleave. Removing the
 * compare-and-swap from the repository does not make these fail — verified by mutation. That is
 * expected, and it is exactly why the CAS has its own test at the repository level
 * ({@code JdbcStepInstanceRepositoryTest#aClaimOnAStaleObservationLosesTheRace}): the CAS is the
 * guard that becomes load-bearing on PostgreSQL, where two transactions genuinely can interleave.
 */
class ConcurrentClaimTest {

    private static final int WORKERS = 8;

    @TempDir
    Path directory;

    private WorkflowSchedulerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = SchedulerTestFixtures.serviceOn(directory);
    }

    /** Six independent steps, so every one of them is runnable at the same instant. */
    private static WorkflowDefinition sixIndependentSteps() {
        List<StepDefinition> steps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            steps.add(new StepDefinition("step-" + i, i, 1, List.of()));
        }
        return new WorkflowDefinition("wide", steps);
    }

    private <T> List<T> inParallel(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                return task.call();
            }));
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    void concurrentClaimsNeverHandTheSameStepToTwoWorkers() throws Exception {
        service.registerWorkflow(sixIndependentSteps());
        String runId = service.startRun("wide", "r").run().runId();

        List<List<ClaimedStep>> batches =
                inParallel(() -> service.claim(runId, Thread.currentThread().getName(), 3));

        List<String> allClaimed = batches.stream()
                .flatMap(List::stream)
                .map(ClaimedStep::stepId)
                .toList();

        assertThat(allClaimed).doesNotHaveDuplicates();
        assertThat(allClaimed).containsExactlyInAnyOrder(
                "step-0", "step-1", "step-2", "step-3", "step-4", "step-5");
    }

    @Test
    void everyStepEndsOnExactlyOneAttemptAfterConcurrentClaims() throws Exception {
        service.registerWorkflow(sixIndependentSteps());
        String runId = service.startRun("wide", "r").run().runId();

        inParallel(() -> service.claim(runId, Thread.currentThread().getName(), 3));

        assertThat(service.runSummary(runId).steps())
                .allSatisfy(step -> {
                    assertThat(step.status()).isEqualTo(StepStatus.IN_PROGRESS);
                    assertThat(step.attemptCount()).isEqualTo(1);
                });
    }

    @Test
    void concurrentStartRunWithTheSameRequestIdYieldsASingleRun() throws Exception {
        service.registerWorkflow(sixIndependentSteps());

        List<String> runIds =
                inParallel(() -> service.startRun("wide", "req-1").run().runId());

        assertThat(runIds).doesNotContainNull();
        assertThat(runIds).containsOnly(runIds.getFirst());
    }
}
