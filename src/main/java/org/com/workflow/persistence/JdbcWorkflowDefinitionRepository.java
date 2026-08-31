package org.com.workflow.persistence;

import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.WorkflowDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores workflow definitions. Re-registering a name replaces its steps wholesale. */
@Repository
public class JdbcWorkflowDefinitionRepository {

    private final JdbcTemplate jdbc;

    public JdbcWorkflowDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(WorkflowDefinition definition) {
        String name = definition.workflowName();

        jdbc.update("DELETE FROM step_dependency WHERE workflow_name = ?", name);
        jdbc.update("DELETE FROM step_definition WHERE workflow_name = ?", name);
        jdbc.update("INSERT OR REPLACE INTO workflow_definition (workflow_name, created_at)"
                + " VALUES (?, ?)", name, Instant.now().toString());

        for (StepDefinition step : definition.steps()) {
            jdbc.update("INSERT INTO step_definition"
                            + " (workflow_name, step_id, priority, max_attempts) VALUES (?, ?, ?, ?)",
                    name, step.stepId(), step.priority(), step.maxAttempts());
            for (String dependency : step.dependencies()) {
                jdbc.update("INSERT INTO step_dependency"
                                + " (workflow_name, step_id, depends_on) VALUES (?, ?, ?)",
                        name, step.stepId(), dependency);
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowDefinition> findByName(String workflowName) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_definition WHERE workflow_name = ?",
                Integer.class, workflowName);
        if (found == null || found == 0) {
            return Optional.empty();
        }

        Map<String, List<String>> dependencies = loadDependencies(workflowName);

        List<StepDefinition> steps = jdbc.query(
                "SELECT step_id, priority, max_attempts FROM step_definition"
                        + " WHERE workflow_name = ? ORDER BY step_id",
                (rs, row) -> new StepDefinition(
                        rs.getString("step_id"),
                        rs.getInt("priority"),
                        rs.getInt("max_attempts"),
                        dependencies.getOrDefault(rs.getString("step_id"), List.of())),
                workflowName);

        return Optional.of(new WorkflowDefinition(workflowName, steps));
    }

    private Map<String, List<String>> loadDependencies(String workflowName) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        jdbc.query("SELECT step_id, depends_on FROM step_dependency"
                        + " WHERE workflow_name = ? ORDER BY step_id, depends_on",
                rs -> {
                    dependencies
                            .computeIfAbsent(rs.getString("step_id"), key -> new ArrayList<>())
                            .add(rs.getString("depends_on"));
                },
                workflowName);
        return dependencies;
    }
}
