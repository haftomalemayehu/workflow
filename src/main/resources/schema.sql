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

CREATE INDEX IF NOT EXISTS ix_step_claim
  ON step_instance(run_id, status, priority DESC, step_id);

CREATE TABLE IF NOT EXISTS step_attempt_event (
  event_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id         TEXT NOT NULL,
  step_id        TEXT NOT NULL,
  attempt_number INTEGER NOT NULL,
  event_type     TEXT NOT NULL,
  worker_id      TEXT NOT NULL,
  occurred_at    TEXT NOT NULL
);
