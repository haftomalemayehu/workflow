package org.com.workflow.persistence;

import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcWorkflowRunRepositoryTest {

    @TempDir
    Path directory;

    private JdbcWorkflowRunRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new JdbcWorkflowRunRepository(SqliteTestSupport.freshDatabase(directory));
    }

    private static WorkflowRun run(String runId, String requestId) {
        return new WorkflowRun(runId, "wf", requestId, RunStatus.RUNNING);
    }

    @Test
    void roundTripsARun() {
        repository.insert(run("run-1", "req-1"));

        assertThat(repository.findById("run-1")).contains(run("run-1", "req-1"));
    }

    @Test
    void findsARunByItsIdempotencyKey() {
        repository.insert(run("run-1", "req-1"));

        assertThat(repository.findByIdempotencyKey("wf", "req-1")).contains(run("run-1", "req-1"));
        assertThat(repository.findByIdempotencyKey("wf", "other")).isEmpty();
    }

    @Test
    void returnsEmptyForAnUnknownRunId() {
        assertThat(repository.findById("ghost")).isEmpty();
    }

    /** The unique index is the arbiter of idempotency, not a read-then-write check. */
    @Test
    void theDatabaseRejectsASecondRunForTheSameWorkflowAndRequestId() {
        repository.insert(run("run-1", "req-1"));

        assertThatThrownBy(() -> repository.insert(run("run-2", "req-1")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aDifferentRequestIdIsAllowed() {
        repository.insert(run("run-1", "req-1"));
        repository.insert(run("run-2", "req-2"));

        assertThat(repository.findById("run-2")).isPresent();
    }

    @Test
    void updatesTheRunStatus() {
        repository.insert(run("run-1", "req-1"));

        repository.updateStatus("run-1", RunStatus.FAILED);

        assertThat(repository.findById("run-1")).get()
                .extracting(WorkflowRun::runStatus).isEqualTo(RunStatus.FAILED);
    }
}
