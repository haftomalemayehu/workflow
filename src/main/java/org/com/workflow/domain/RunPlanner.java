package org.com.workflow.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The scheduling decisions, as pure functions over a run's step instances. Keeping them free of
 * persistence is what lets the rules be tested directly and reused unchanged by any repository.
 */
public final class RunPlanner {

    private RunPlanner() {
    }

    /**
     * blocked(s) &hArr; some dependency of s is FAILED, or is itself blocked. Transitive by
     * construction; memoized so each step is evaluated once. The graph is validated acyclic at
     * registration, so the recursion always terminates.
     */
    public static Set<String> blocked(Collection<StepInstance> steps, DependencyGraph graph) {
        Map<String, StepInstance> byId = new LinkedHashMap<>();
        for (StepInstance step : steps) {
            byId.put(step.stepId(), step);
        }

        Map<String, Boolean> memo = new HashMap<>();
        Set<String> blocked = new HashSet<>();
        for (String stepId : byId.keySet()) {
            if (isBlocked(stepId, byId, graph, memo)) {
                blocked.add(stepId);
            }
        }
        return Set.copyOf(blocked);
    }

    private static boolean isBlocked(
            String stepId,
            Map<String, StepInstance> byId,
            DependencyGraph graph,
            Map<String, Boolean> memo) {

        Boolean cached = memo.get(stepId);
        if (cached != null) {
            return cached;
        }

        boolean blocked = false;
        for (String dependency : graph.dependenciesOf(stepId)) {
            StepInstance upstream = byId.get(dependency);
            if (upstream == null) {
                continue;
            }
            if (upstream.status() == StepStatus.FAILED || isBlocked(dependency, byId, graph, memo)) {
                blocked = true;
                break;
            }
        }

        memo.put(stepId, blocked);
        return blocked;
    }

    /**
     * The steps that may be claimed right now, in claim order: higher priority first, then
     * lexicographically smaller stepId. Fully deterministic, so tests can assert exact output.
     */
    public static List<StepInstance> claimable(
            Collection<StepInstance> steps, DependencyGraph graph, int maxCount) {

        Map<String, StepInstance> byId = new LinkedHashMap<>();
        for (StepInstance step : steps) {
            byId.put(step.stepId(), step);
        }
        Set<String> blocked = blocked(steps, graph);

        return steps.stream()
                .filter(step -> isRunnable(step, byId, graph, blocked))
                .sorted(CLAIM_ORDER)
                .limit(maxCount)
                .toList();
    }

    private static final Comparator<StepInstance> CLAIM_ORDER =
            Comparator.comparingInt(StepInstance::priority).reversed()
                    .thenComparing(StepInstance::stepId);

    private static boolean isRunnable(
            StepInstance step,
            Map<String, StepInstance> byId,
            DependencyGraph graph,
            Set<String> blocked) {

        if (step.status() != StepStatus.PENDING
                || !step.hasAttemptsRemaining()
                || blocked.contains(step.stepId())) {
            return false;
        }
        for (String dependency : graph.dependenciesOf(step.stepId())) {
            StepInstance upstream = byId.get(dependency);
            if (upstream == null || upstream.status() != StepStatus.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /**
     * A run is still {@code RUNNING} while there is work in flight <em>or</em> work that can still
     * start; once neither is true it is terminal, and it succeeded only if every step succeeded.
     *
     * <p>Stating it this way keeps the run {@code RUNNING} when one branch has failed terminally
     * but an independent branch is still executing.
     */
    public static RunStatus runStatus(Collection<StepInstance> steps, DependencyGraph graph) {
        Set<String> blocked = blocked(steps, graph);

        boolean allSucceeded = true;
        for (StepInstance step : steps) {
            if (step.status() == StepStatus.IN_PROGRESS) {
                return RunStatus.RUNNING;
            }
            if (step.status() == StepStatus.PENDING && !blocked.contains(step.stepId())) {
                return RunStatus.RUNNING;
            }
            if (step.status() != StepStatus.SUCCEEDED) {
                allSucceeded = false;
            }
        }
        return allSucceeded ? RunStatus.SUCCEEDED : RunStatus.FAILED;
    }
}
