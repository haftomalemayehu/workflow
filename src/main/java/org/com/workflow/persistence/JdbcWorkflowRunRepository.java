package org.com.workflow.persistence;

import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.WorkflowRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Run rows. Idempotency is enforced by the unique index on (workflow_name, request_id). */
@Repository
public class JdbcWorkflowRunRepository {

    private static final String SELECT =
            "SELECT run_id, workflow_name, request_id, run_status FROM workflow_run";

    private final JdbcTemplate jdbc;

    public JdbcWorkflowRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(WorkflowRun run) {
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO workflow_run"
                        + " (run_id, workflow_name, request_id, run_status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                run.runId(), run.workflowName(), run.requestId(), run.runStatus().name(), now, now);
    }

    public Optional<WorkflowRun> findByIdempotencyKey(String workflowName, String requestId) {
        return single(jdbc.query(SELECT + " WHERE workflow_name = ? AND request_id = ?",
                JdbcWorkflowRunRepository::mapRun, workflowName, requestId));
    }

    public Optional<WorkflowRun> findById(String runId) {
        return single(jdbc.query(SELECT + " WHERE run_id = ?",
                JdbcWorkflowRunRepository::mapRun, runId));
    }

    public void updateStatus(String runId, RunStatus status) {
        jdbc.update("UPDATE workflow_run SET run_status = ?, updated_at = ? WHERE run_id = ?",
                status.name(), Instant.now().toString(), runId);
    }

    private static WorkflowRun mapRun(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new WorkflowRun(
                rs.getString("run_id"),
                rs.getString("workflow_name"),
                rs.getString("request_id"),
                RunStatus.valueOf(rs.getString("run_status")));
    }

    private static Optional<WorkflowRun> single(List<WorkflowRun> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
