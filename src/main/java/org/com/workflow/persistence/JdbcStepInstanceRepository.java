package org.com.workflow.persistence;

import org.com.workflow.domain.DependencyGraph;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.StepInstance;
import org.com.workflow.domain.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Step instances, the claim compare-and-swap, and the append-only attempt log. */
@Repository
public class JdbcStepInstanceRepository {

    private final JdbcTemplate jdbc;

    public JdbcStepInstanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * priority and maxAttempts are copied from the definition here, so the run is a snapshot that a
     * later re-registration of the workflow cannot change.
     */
    public void createAll(String runId, List<StepDefinition> steps) {
        String now = Instant.now().toString();
        for (StepDefinition step : steps) {
            jdbc.update("INSERT INTO step_instance"
                            + " (run_id, step_id, status, attempt_count, max_attempts, priority,"
                            + "  last_worker_id, updated_at) VALUES (?, ?, ?, 0, ?, ?, NULL, ?)",
                    runId, step.stepId(), StepStatus.PENDING.name(),
                    step.maxAttempts(), step.priority(), now);

            for (String dependency : step.dependencies()) {
                jdbc.update("INSERT INTO run_step_dependency (run_id, step_id, depends_on)"
                        + " VALUES (?, ?, ?)", runId, step.stepId(), dependency);
            }
        }
    }

    /**
     * The run's own dependency edges, copied at run start. Read from here rather than from the
     * live definition so a later re-registration cannot steer a run already in flight.
     */
    public DependencyGraph findGraph(String runId) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        jdbc.query("SELECT step_id, depends_on FROM run_step_dependency"
                        + " WHERE run_id = ? ORDER BY step_id, depends_on",
                rs -> {
                    edges.computeIfAbsent(rs.getString("step_id"), key -> new ArrayList<>())
                            .add(rs.getString("depends_on"));
                },
                runId);
        return DependencyGraph.ofEdges(edges);
    }

    public List<StepInstance> findByRunId(String runId) {
        return jdbc.query("SELECT step_id, status, attempt_count, max_attempts, priority,"
                        + " last_worker_id FROM step_instance WHERE run_id = ? ORDER BY step_id",
                (rs, row) -> new StepInstance(
                        rs.getString("step_id"),
                        StepStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getInt("priority"),
                        rs.getString("last_worker_id")),
                runId);
    }

    /**
     * Compare-and-swap on (status, attempt_count). Returns false when another writer moved the row
     * first, in which case the caller drops the step from the batch rather than double-assigning
     * it. Redundant under SQLite's single writer; it is what keeps the claim correct on PostgreSQL.
     */
    public boolean tryClaim(String runId, StepInstance observed, String workerId) {
        int updated = jdbc.update("UPDATE step_instance"
                        + " SET status = ?, attempt_count = attempt_count + 1,"
                        + "     last_worker_id = ?, updated_at = ?"
                        + " WHERE run_id = ? AND step_id = ? AND status = ? AND attempt_count = ?",
                StepStatus.IN_PROGRESS.name(), workerId, Instant.now().toString(),
                runId, observed.stepId(), StepStatus.PENDING.name(), observed.attemptCount());
        return updated == 1;
    }

    public void applyCompletion(String runId, String stepId, StepStatus status) {
        if (!status.isPersistable()) {
            throw new IllegalArgumentException(status + " is derived and must never be persisted");
        }
        jdbc.update("UPDATE step_instance SET status = ?, updated_at = ?"
                        + " WHERE run_id = ? AND step_id = ?",
                status.name(), Instant.now().toString(), runId, stepId);
    }

    public void appendEvent(
            String runId, String stepId, int attemptNumber, String eventType, String workerId) {
        jdbc.update("INSERT INTO step_attempt_event"
                        + " (run_id, step_id, attempt_number, event_type, worker_id, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                runId, stepId, attemptNumber, eventType, workerId, Instant.now().toString());
    }
}
