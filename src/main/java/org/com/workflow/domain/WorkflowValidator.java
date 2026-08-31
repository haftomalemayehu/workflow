package org.com.workflow.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates a workflow definition before it is stored. All problems are collected so the caller
 * gets one complete answer rather than a first-failure-wins message.
 */
public final class WorkflowValidator {

    private WorkflowValidator() {
    }

    public static void validate(WorkflowDefinition definition) {
        List<String> errors = new ArrayList<>();

        if (isBlank(definition.workflowName())) {
            errors.add("workflowName must not be blank");
        }

        Set<String> stepIds = collectStepIds(definition, errors);
        validateDependencies(definition, stepIds, errors);

        // Only worth looking for a cycle once the edges are known to be well formed.
        if (errors.isEmpty()) {
            detectCycle(definition).ifPresent(errors::add);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private static Set<String> collectStepIds(WorkflowDefinition definition, List<String> errors) {
        Set<String> stepIds = new HashSet<>();
        for (StepDefinition step : definition.steps()) {
            if (isBlank(step.stepId())) {
                errors.add("stepId must not be blank");
                continue;
            }
            if (!stepIds.add(step.stepId())) {
                errors.add("duplicate stepId '%s'".formatted(step.stepId()));
            }
            if (step.maxAttempts() <= 0) {
                errors.add("step '%s': maxAttempts must be > 0".formatted(step.stepId()));
            }
        }
        return stepIds;
    }

    private static void validateDependencies(
            WorkflowDefinition definition, Set<String> stepIds, List<String> errors) {

        for (StepDefinition step : definition.steps()) {
            if (isBlank(step.stepId())) {
                continue;
            }
            for (String dependency : step.dependencies()) {
                if (step.stepId().equals(dependency)) {
                    errors.add("step '%s' depends on itself".formatted(step.stepId()));
                } else if (!stepIds.contains(dependency)) {
                    errors.add("step '%s' depends on unknown step '%s'"
                            .formatted(step.stepId(), dependency));
                }
            }
        }
    }

    /**
     * Kahn's algorithm. Steps with no unmet dependencies are removed repeatedly; whatever cannot be
     * removed is exactly the set of steps involved in, or downstream of, a cycle. Reporting that
     * remainder is why this is preferred over DFS colouring here.
     */
    private static java.util.Optional<String> detectCycle(WorkflowDefinition definition) {
        Map<String, Integer> unmetDependencies = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (StepDefinition step : definition.steps()) {
            unmetDependencies.put(step.stepId(), step.dependencies().size());
            for (String dependency : step.dependencies()) {
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(step.stepId());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        unmetDependencies.forEach((stepId, count) -> {
            if (count == 0) {
                ready.add(stepId);
            }
        });

        int removed = 0;
        while (!ready.isEmpty()) {
            String stepId = ready.removeFirst();
            removed++;
            for (String dependent : dependents.getOrDefault(stepId, List.of())) {
                if (unmetDependencies.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (removed == unmetDependencies.size()) {
            return java.util.Optional.empty();
        }

        Set<String> stuck = new TreeSet<>();
        unmetDependencies.forEach((stepId, count) -> {
            if (count > 0) {
                stuck.add(stepId);
            }
        });
        return java.util.Optional.of("dependency graph contains a cycle involving " + stuck);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
