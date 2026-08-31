# Workflow Scheduler — Design

## 1. Goals and scope

Build a persistent workflow scheduler that stores workflow definitions and runs, decides which steps are ready to execute, hands them to workers, and tracks retries and terminal outcomes.

In scope: the five required operations, the dependency/priority/retry rules, validation, and durable state.

Out of scope: executing the step bodies themselves. This is the *scheduler*; workers are external callers that claim work and report results.

Design priorities, in order: **correctness** of state transitions → **clarity** about the single source of truth → **testability** → a credible production evolution path.

## 2. Storage decision

**SQLite, accessed through Spring's `JdbcTemplate`.**

Rationale:

- The exercise is titled *Persistent* Workflow Scheduler; with SQLite, state survives restart with no extra work.
- Setup stays lightweight — a single file, no server, no container. `spring.sql.init` applies `schema.sql` on boot.
- It makes the claim-atomicity discussion concrete rather than hypothetical: there is a real transaction boundary and a real write lock to reason about.
- Plain `JdbcTemplate` rather than JPA keeps the SQL visible, which is the part of this exercise most worth reviewing.

Repository interfaces sit above the SQL so a PostgreSQL implementation is a drop-in replacement (§9).

## 3. Domain model

**WorkflowDefinition** — `workflowName` (PK), steps.

**StepDefinition** — `stepId`, `priority` (higher runs first), `maxAttempts` (> 0), `dependencies: List<stepId>`.

**WorkflowRun** — `runId` (PK, UUID), `workflowName`, `requestId`, `runStatus ∈ {running, succeeded, failed}`, `createdAt`, `updatedAt`.

**StepInstance** — per run: `(runId, stepId)` PK, `status`, `attemptCount`, `lastWorkerId`, plus `priority` and `maxAttempts` **copied from the definition at run start**.

Two modeling decisions worth calling out:

**Definition fields are copied into the run, not joined.** A run is a snapshot. Re-registering a workflow later must not retroactively change the retry budget or ordering of a run already in flight.

**`attemptCount` is the number of attempts *started*, and it increments at claim time.** One counter is enough. When a step is `in_progress`, `attemptCount` is the number of the attempt currently running, so the value a worker must echo back on completion is exactly `attemptCount`. This makes the wrong-attempt check a single comparison and removes any question about whether an in-flight attempt is counted.

## 4. Step status and the `blocked` derivation

Four statuses are **stored**: `pending`, `in_progress`, `succeeded`, `failed`.

A fifth, `blocked`, is **derived** and never written:

```
blocked(s) ⟺ ∃ d ∈ dependencies(s) : d.status = failed ∨ blocked(d)
```

This is transitive by construction, which is exactly the propagation the brief requires. It is computed by memoized DFS over the dependency edges; the graph is validated acyclic at registration (§6), so the recursion always terminates.

Deriving rather than storing it means there is no cached flag to invalidate when a step goes `failed → pending` on retry — a class of bug that is easy to write and hard to test for. The cost is an O(V+E) walk per read, negligible at any realistic DAG size, and revisited in §9 for very large graphs.

A `blocked` step is never claimable, and is reported as `blocked` in the run summary.

## 5. Scheduling rules

**Runnable** — a step may be claimed when all of:

- `status = pending`
- `attemptCount < maxAttempts`
- every dependency has `status = succeeded`
- it is not `blocked` (implied by the previous condition, but checked explicitly for clarity)

**Claim ordering** — `priority` descending, then `stepId` ascending. Fully deterministic, so tests can assert exact output.

**Claim** transitions `pending → in_progress`, sets `attemptCount += 1` and `lastWorkerId`, and returns `attemptNumber = attemptCount` (post-increment).

**Complete** requires `status = in_progress`, a matching `workerId`, and a matching `attemptNumber`. Then:

- `success` → `succeeded`
- `fail` and `attemptCount >= maxAttempts` → `failed` (terminal)
- `fail` and `attemptCount < maxAttempts` → back to `pending`, eligible to be re-claimed

**Run status** is recomputed inside every transaction that changes a step:

```
running    if any step is in_progress
           OR any step is pending and not blocked
succeeded  otherwise, if every step is succeeded
failed     otherwise
```

Read it as: the run is still running while there is work in flight *or* work that can still start; once neither is true the run is terminal, and it succeeded only if everything succeeded. This handles the case a naive rule gets wrong — one branch failing terminally while an independent branch is still executing keeps the run `running` until that branch settles.

## 6. Validation

**At registration** (400 Bad Request, with field-level messages):

blank `workflowName` · blank `stepId` · duplicate `stepId` · `maxAttempts <= 0` · dependency on an unknown step · self-dependency · cycle in the dependency graph.

Cycle detection uses **Kahn's algorithm**: repeatedly remove zero-in-degree nodes; if any remain, the leftovers are the cycle and get named in the error message. Preferred over DFS colouring here because the remaining set is directly reportable to the caller.

**At run / claim / complete time:**

| Condition | Status |
|---|---|
| unknown `workflowName` | 404 |
| unknown `runId` | 404 |
| blank `requestId` | 400 |
| blank `workerId` | 400 |
| `maxCount <= 0` | 400 |
| invalid `result` value | 400 |
| step not `in_progress` | 409 |
| wrong `workerId` | 409 |
| wrong `attemptNumber` | 409 |

409 rather than 400 for the last three: the request is well-formed, it simply lost a race or the worker is out of date. That distinction is what tells a client to re-read state rather than fix its payload.

## 7. API

All errors return RFC 9457 `ProblemDetail`, native in Spring Boot.

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/workflows` | `{workflowName, steps[]}` | 201 |
| POST | `/workflows/{name}/runs` | `{requestId}` | 201 created / **200 replayed** |
| POST | `/runs/{runId}/claims` | `{workerId, maxCount}` | 200 `[{stepId, attemptNumber}]` |
| POST | `/runs/{runId}/steps/{stepId}/complete` | `{workerId, attemptNumber, result}` | 200 |
| GET | `/runs/{runId}` | — | 200 run summary |

**Idempotency** is enforced by a unique index on `(workflow_name, request_id)`, not by a read-then-write check — the database is the arbiter, so a duplicate is impossible even under concurrent submission. The insert is attempted; on unique-constraint violation the existing run is loaded and returned with 200. The distinct 201/200 lets a caller tell a replay from a create without changing the response body.

Run summary returns steps sorted by `stepId` ascending, each with `stepId`, `status` (including derived `blocked`), `attemptCount`, `lastWorkerId`.

## 8. Schema and the claim transaction

```sql
CREATE TABLE workflow_definition (
  workflow_name TEXT PRIMARY KEY,
  created_at    TEXT NOT NULL);

CREATE TABLE step_definition (
  workflow_name TEXT NOT NULL REFERENCES workflow_definition(workflow_name),
  step_id       TEXT NOT NULL,
  priority      INTEGER NOT NULL,
  max_attempts  INTEGER NOT NULL CHECK (max_attempts > 0),
  PRIMARY KEY (workflow_name, step_id));

CREATE TABLE step_dependency (
  workflow_name TEXT NOT NULL,
  step_id       TEXT NOT NULL,
  depends_on    TEXT NOT NULL,
  PRIMARY KEY (workflow_name, step_id, depends_on),
  FOREIGN KEY (workflow_name, step_id)    REFERENCES step_definition(workflow_name, step_id),
  FOREIGN KEY (workflow_name, depends_on) REFERENCES step_definition(workflow_name, step_id));

CREATE TABLE workflow_run (
  run_id        TEXT PRIMARY KEY,
  workflow_name TEXT NOT NULL REFERENCES workflow_definition(workflow_name),
  request_id    TEXT NOT NULL,
  run_status    TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL);
CREATE UNIQUE INDEX ux_run_idempotency ON workflow_run(workflow_name, request_id);

CREATE TABLE step_instance (
  run_id         TEXT NOT NULL REFERENCES workflow_run(run_id),
  step_id        TEXT NOT NULL,
  status         TEXT NOT NULL,          -- pending|in_progress|succeeded|failed
  attempt_count  INTEGER NOT NULL DEFAULT 0,
  max_attempts   INTEGER NOT NULL,
  priority       INTEGER NOT NULL,
  last_worker_id TEXT,
  updated_at     TEXT NOT NULL,
  PRIMARY KEY (run_id, step_id));
CREATE INDEX ix_step_claim ON step_instance(run_id, status, priority DESC, step_id);

CREATE TABLE step_attempt_event (        -- append-only audit
  event_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id         TEXT NOT NULL,
  step_id        TEXT NOT NULL,
  attempt_number INTEGER NOT NULL,
  event_type     TEXT NOT NULL,          -- claimed|succeeded|failed
  worker_id      TEXT NOT NULL,
  occurred_at    TEXT NOT NULL);
```

`run_status` is a denormalized cache recomputed in the same transaction as any step change — it keeps `GET /runs/{id}` a single cheap read while the step rows remain the source of truth.

### The claim transaction

```
BEGIN IMMEDIATE                          -- take the write lock up front
  SELECT all step_instance rows for run_id
  SELECT dependency edges for the workflow
  compute blocked set (memoized DFS)
  candidates := runnable steps (§5)
  ORDER BY priority DESC, step_id ASC     -- in memory; the set is small
  FOR each of the first maxCount:
      UPDATE step_instance
         SET status = 'in_progress',
             attempt_count  = attempt_count + 1,
             last_worker_id = :worker,
             updated_at     = :now
       WHERE run_id = :run AND step_id = :step
         AND status = 'pending' AND attempt_count = :observed
      -- 0 rows affected ⇒ another writer moved it; drop it from the result
      INSERT INTO step_attempt_event (..., 'claimed', attempt_count)
  recompute and persist run_status
COMMIT
```

Two mechanisms, deliberately layered:

**`BEGIN IMMEDIATE`** acquires SQLite's write lock at the start of the transaction rather than on first write. Without it, two concurrent claims can both begin as readers and then deadlock when both try to upgrade — SQLite returns `SQLITE_BUSY` and one is rolled back. Taking the lock immediately makes claims serialize cleanly instead.

**The conditional `UPDATE`** is a compare-and-swap on `(status, attempt_count)`. It is redundant under SQLite's single-writer model, and that is the point: it is the invariant that keeps the claim correct when the repository is swapped for PostgreSQL, where two transactions genuinely can interleave. A lost race yields `0 rows affected`, and that step is silently dropped from the returned batch — never double-assigned.

The completion transaction follows the same shape: `BEGIN IMMEDIATE`, load the row, run the three guards (§6), apply the transition, append the audit event, recompute `run_status`, commit.

Connection settings: `journal_mode=WAL` and a `busy_timeout`, so readers never block behind a writer.

## 9. Production evolution

**Multi-instance claiming.** SQLite's single writer is a correctness guarantee but a throughput ceiling. Moving to PostgreSQL replaces the read-then-CAS with one statement:

```sql
UPDATE step_instance
   SET status = 'in_progress', attempt_count = attempt_count + 1, ...
 WHERE (run_id, step_id) IN (
   SELECT run_id, step_id FROM step_instance
    WHERE run_id = ? AND status = 'pending' AND attempt_count < max_attempts
      AND <deps all succeeded>
    ORDER BY priority DESC, step_id ASC
    LIMIT ? FOR UPDATE SKIP LOCKED)
RETURNING step_id, attempt_count;
```

`SKIP LOCKED` lets N application instances claim disjoint step sets concurrently with no external coordination. The repository interfaces exist so this is one new class, not a rewrite.

**Stuck steps and crash recovery.** Today a worker that dies mid-attempt strands a step in `in_progress` forever. Production adds a `lease_expires_at` column set at claim time; a reaper job returns expired leases to `pending` **without consuming an attempt** (a crash is not a failure), and manual retry becomes the same operation triggered by an operator.

**Observability.** `step_attempt_event` is already an append-only log, so audit and replay come free — run state can be reconstructed by folding it. Add structured events on claim / complete / retry / terminal, metrics for claim latency, an `in_progress` gauge, retry and failure rates per step, and traces correlated by `runId` + `attemptNumber`.

**Very large DAGs.** The derived-`blocked` walk is O(V+E) per read. If that becomes hot, cache blocked flags with invalidation scoped to the dependents subtree rather than the whole run — a deliberate trade of the simplicity in §4 for throughput, worth making only once measured.

## 10. Testing strategy

Unit, no Spring context:

- validator: each of the seven registration failures, including a 2-node and a 3-node cycle
- blocked derivation: transitive propagation across three levels; retry un-blocks downstream
- run-status aggregation: all-succeeded; failed-with-blocked-dependents; and the parallel-branch case where one branch fails terminally while another is still `in_progress`

Integration, real SQLite in a JUnit `@TempDir` (fresh file per test):

- **the exact scenario from the exercise brief**, asserted output by output
- claim ordering by priority then `stepId`; `maxCount` capping; empty result when nothing is runnable
- retry: fail → re-claimable at `attemptNumber + 1` → exhaust → `failed`
- rejection paths: wrong worker, wrong attempt number, completing a step that is not `in_progress`
- idempotent start: same `(workflowName, requestId)` returns the same `runId`, 201 then 200

`MockMvc` tests assert the status-code contract in §7, particularly the 400 / 404 / 409 split.

## 11. Assumptions and tradeoffs

**Assumptions.** Workers are trusted to complete only claims they hold — the worker and attempt guards catch mistakes, not malice. Step bodies are idempotent, so a retry is safe. No per-step timeout in v1.

**Tradeoffs, stated plainly.**

- **SQLite serializes writes.** Correct and simple; a throughput ceiling, and the reason §9 leads with PostgreSQL.
- **Derived `blocked`** trades a small read cost for the elimination of a whole class of cache-invalidation bugs.
- **Copied definition fields** duplicate data in order to make runs immutable snapshots.
- **Cached `run_status`** duplicates derivable state for read speed, kept honest by recomputing it in the same transaction as every step change.
