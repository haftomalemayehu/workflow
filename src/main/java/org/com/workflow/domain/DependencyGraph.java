package org.com.workflow.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The dependency edges of one workflow, validated acyclic by {@link WorkflowValidator}. */
public final class DependencyGraph {

    private final Map<String, List<String>> dependencies;

    private DependencyGraph(Map<String, List<String>> dependencies) {
        this.dependencies = dependencies;
    }

    public static DependencyGraph of(List<StepDefinition> steps) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (StepDefinition step : steps) {
            dependencies.put(step.stepId(), List.copyOf(step.dependencies()));
        }
        return new DependencyGraph(Map.copyOf(dependencies));
    }

    /** Builds the graph straight from stored edges, as a run's snapshotted copy is loaded. */
    public static DependencyGraph ofEdges(Map<String, List<String>> edges) {
        return new DependencyGraph(Map.copyOf(edges));
    }

    public List<String> dependenciesOf(String stepId) {
        return dependencies.getOrDefault(stepId, List.of());
    }
}
