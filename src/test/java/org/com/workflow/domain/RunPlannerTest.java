package org.com.workflow.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunPlannerTest {

    private static StepDefinition def(String id, String... dependencies) {
        return new StepDefinition(id, 1, 1, List.of(dependencies));
    }

    private static StepInstance instance(String id, StepStatus status) {
        return new StepInstance(id, status, 0, 1, 1, null);
    }

    /** a <- b <- c : a fails, so b is blocked, and c is blocked through b. */
    @Test
    void blockedPropagatesTransitivelyFromAFailedStep() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a"), def("c", "b")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.FAILED),
                instance("b", StepStatus.PENDING),
                instance("c", StepStatus.PENDING));

        assertThat(RunPlanner.blocked(steps, graph)).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void aStepWhoseDependencyIsMerelyPendingIsNotBlocked() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.PENDING),
                instance("b", StepStatus.PENDING));

        assertThat(RunPlanner.blocked(steps, graph)).isEmpty();
    }

    /** A retry returns a step to PENDING, which must un-block everything downstream of it. */
    @Test
    void aStepReturnedToPendingByARetryUnblocksItsDependents() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a"), def("c", "b")));
        List<StepInstance> steps = List.of(
                new StepInstance("a", StepStatus.PENDING, 1, 2, 1, "worker-a"),
                instance("b", StepStatus.PENDING),
                instance("c", StepStatus.PENDING));

        assertThat(RunPlanner.blocked(steps, graph)).isEmpty();
    }

    @Test
    void anIndependentBranchIsNotBlockedByAFailureInTheOther() {
        DependencyGraph graph =
                DependencyGraph.of(List.of(def("a"), def("left", "a"), def("right", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.SUCCEEDED),
                instance("left", StepStatus.FAILED),
                instance("right", StepStatus.PENDING));

        assertThat(RunPlanner.blocked(steps, graph)).isEmpty();
    }

    /**
     * Shouldn't happen in practice — dependency edges and step instances are written together at
     * run start — but a dependency naming a step with no matching instance must be skipped, not
     * NPE or wrongly block the step that names it.
     */
    @Test
    void aDependencyWithNoMatchingStepInstanceIsSkippedRatherThanBlocking() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a", "ghost")));
        List<StepInstance> steps = List.of(instance("a", StepStatus.PENDING));

        assertThat(RunPlanner.blocked(steps, graph)).isEmpty();
    }

    // --- claim selection (design doc §5) ---

    private static StepInstance pending(String id, int priority, int maxAttempts) {
        return new StepInstance(id, StepStatus.PENDING, 0, maxAttempts, priority, null);
    }

    @Test
    void claimsHigherPriorityFirst() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("low"), def("high")));
        List<StepInstance> steps = List.of(pending("low", 1, 1), pending("high", 9, 1));

        assertThat(RunPlanner.claimable(steps, graph, 2))
                .extracting(StepInstance::stepId)
                .containsExactly("high", "low");
    }

    @Test
    void breaksPriorityTiesByLexicographicallySmallerStepId() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("b"), def("a")));
        List<StepInstance> steps = List.of(pending("b", 5, 1), pending("a", 5, 1));

        assertThat(RunPlanner.claimable(steps, graph, 2))
                .extracting(StepInstance::stepId)
                .containsExactly("a", "b");
    }

    @Test
    void returnsAtMostMaxCountSteps() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b"), def("c")));
        List<StepInstance> steps =
                List.of(pending("a", 1, 1), pending("b", 1, 1), pending("c", 1, 1));

        assertThat(RunPlanner.claimable(steps, graph, 2)).hasSize(2);
    }

    @Test
    void doesNotClaimAStepWhoseDependencyHasNotSucceeded() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps = List.of(pending("a", 1, 1), pending("b", 9, 1));

        assertThat(RunPlanner.claimable(steps, graph, 10))
                .extracting(StepInstance::stepId)
                .containsExactly("a");
    }

    @Test
    void doesNotClaimABlockedStep() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps =
                List.of(instance("a", StepStatus.FAILED), pending("b", 9, 1));

        assertThat(RunPlanner.claimable(steps, graph, 10)).isEmpty();
    }

    @Test
    void doesNotClaimAStepThatIsAlreadyInProgress() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a")));
        List<StepInstance> steps =
                List.of(new StepInstance("a", StepStatus.IN_PROGRESS, 1, 3, 1, "worker-a"));

        assertThat(RunPlanner.claimable(steps, graph, 10)).isEmpty();
    }

    @Test
    void doesNotClaimAStepWithNoAttemptsRemaining() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a")));
        List<StepInstance> steps =
                List.of(new StepInstance("a", StepStatus.PENDING, 2, 2, 1, "worker-a"));

        assertThat(RunPlanner.claimable(steps, graph, 10)).isEmpty();
    }

    /** Same defensive case as {@link #aDependencyWithNoMatchingStepInstanceIsSkippedRatherThanBlocking()}, for claim eligibility. */
    @Test
    void doesNotClaimAStepWhoseDependencyHasNoMatchingStepInstance() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a", "ghost")));
        List<StepInstance> steps = List.of(pending("a", 1, 1));

        assertThat(RunPlanner.claimable(steps, graph, 10)).isEmpty();
    }

    // --- run status aggregation (design doc §5) ---

    @Test
    void runSucceedsWhenEveryStepSucceeded() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.SUCCEEDED), instance("b", StepStatus.SUCCEEDED));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void runIsRunningWhileAStepIsInProgress() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a")));
        List<StepInstance> steps =
                List.of(new StepInstance("a", StepStatus.IN_PROGRESS, 1, 1, 1, "worker-a"));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void runIsRunningWhileAPendingStepCanStillStart() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.SUCCEEDED), instance("b", StepStatus.PENDING));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void runFailsWhenAFailedStepHasBlockedEverythingLeft() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a"), def("b", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.FAILED), instance("b", StepStatus.PENDING));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.FAILED);
    }

    /**
     * The case a naive rule gets wrong: one branch has failed terminally, but an independent branch
     * is still executing, so the run has not settled yet.
     */
    @Test
    void runKeepsRunningWhileAnIndependentBranchIsStillExecuting() {
        DependencyGraph graph =
                DependencyGraph.of(List.of(def("a"), def("left", "a"), def("right", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.SUCCEEDED),
                instance("left", StepStatus.FAILED),
                new StepInstance("right", StepStatus.IN_PROGRESS, 1, 1, 1, "worker-a"));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void runFailsOnceThatIndependentBranchFinallySettles() {
        DependencyGraph graph =
                DependencyGraph.of(List.of(def("a"), def("left", "a"), def("right", "a")));
        List<StepInstance> steps = List.of(
                instance("a", StepStatus.SUCCEEDED),
                instance("left", StepStatus.FAILED),
                instance("right", StepStatus.SUCCEEDED));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void runIsRunningWhenAFailedAttemptStillHasRetriesLeft() {
        DependencyGraph graph = DependencyGraph.of(List.of(def("a")));
        List<StepInstance> steps =
                List.of(new StepInstance("a", StepStatus.PENDING, 1, 2, 1, "worker-a"));

        assertThat(RunPlanner.runStatus(steps, graph)).isEqualTo(RunStatus.RUNNING);
    }
}
