CREATE TABLE IF NOT EXISTS workflow_definition (
  workflow_name TEXT PRIMARY KEY,
  created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS step_definition (
  workflow_name TEXT NOT NULL REFERENCES workflow_definition(workflow_name),
  step_id       TEXT NOT NULL,
  priority      INTEGER NOT NULL,
  max_attempts  INTEGER NOT NULL CHECK (max_attempts > 0),
  PRIMARY KEY (workflow_name, step_id)
);

CREATE TABLE IF NOT EXISTS step_dependency (
  workflow_name TEXT NOT NULL,
  step_id       TEXT NOT NULL,
  depends_on    TEXT NOT NULL,
  PRIMARY KEY (workflow_name, step_id, depends_on),
  FOREIGN KEY (workflow_name, step_id)    REFERENCES step_definition(workflow_name, step_id),
  FOREIGN KEY (workflow_name, depends_on) REFERENCES step_definition(workflow_name, step_id)
);

CREATE TABLE IF NOT EXISTS workflow_run (
  run_id        TEXT PRIMARY KEY,
  workflow_name TEXT NOT NULL REFERENCES workflow_definition(workflow_name),
  request_id    TEXT NOT NULL,
  run_status    TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

-- Idempotency is enforced here, not by a read-then-write check in application code.
CREATE UNIQUE INDEX IF NOT EXISTS ux_run_idempotency
  ON workflow_run(workflow_name, request_id);

CREATE TABLE IF NOT EXISTS step_instance (
  run_id         TEXT NOT NULL REFERENCES workflow_run(run_id),
  step_id        TEXT NOT NULL,
  status         TEXT NOT NULL,
  attempt_count  INTEGER NOT NULL DEFAULT 0,
  max_attempts   INTEGER NOT NULL,
  priority       INTEGER NOT NULL,
  last_worker_id TEXT,
  updated_at     TEXT NOT NULL,
  PRIMARY KEY (run_id, step_id)
);

-- The run's own copy of the dependency edges. Snapshotting these alongside priority and
-- max_attempts is what makes a run genuinely immutable: re-registering the workflow afterwards
-- cannot change what an in-flight run considers runnable or blocked.
CREATE TABLE IF NOT EXISTS run_step_dependency (
  run_id     TEXT NOT NULL,
  step_id    TEXT NOT NULL,
  depends_on TEXT NOT NULL,
  PRIMARY KEY (run_id, step_id, depends_on)
);

CREATE INDEX IF NOT EXISTS ix_step_claim
  ON step_instance(run_id, status, priority DESC, step_id);

-- Same log as schema.sql; event_id uses PostgreSQL's identity-column syntax instead of SQLite's
-- "INTEGER PRIMARY KEY AUTOINCREMENT" extension.
CREATE TABLE IF NOT EXISTS step_attempt_event (
  event_id       INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id         TEXT NOT NULL,
  step_id        TEXT NOT NULL,
  attempt_number INTEGER NOT NULL,
  event_type     TEXT NOT NULL,
  worker_id      TEXT NOT NULL,
  occurred_at    TEXT NOT NULL
);
