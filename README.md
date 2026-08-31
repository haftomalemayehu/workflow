# Persistent Workflow Scheduler

A small backend platform service that stores workflow definitions and workflow runs, decides which
steps are ready to execute, hands them to workers, and tracks retries and final outcomes.

The full design — domain model, state machine, schema, and production evolution path — is in
[`docs/design/workflow-scheduler-design.md`](docs/design/workflow-scheduler-design.md).
The original brief is in [`docs/spec/take-home-coding-assessment.md`](docs/spec/take-home-coding-assessment.md).

## Storage choice

**Database-backed, using SQLite** via Spring's `JdbcTemplate`.

The exercise allowed either in-memory or a lightweight database. SQLite was chosen because the
exercise is titled *Persistent* Workflow Scheduler and SQLite gives that for free — a single file,
no server, no container — while still providing a real transaction boundary and a real write lock.
That matters for the most interesting part of the problem: making step claiming atomic. With an
in-memory map, claim safety is a paragraph of prose; with SQLite it is something the code actually
does and the tests actually exercise.

Repository interfaces sit above the SQL, so swapping in PostgreSQL (and its
`SELECT ... FOR UPDATE SKIP LOCKED` claim) is a new implementation class rather than a rewrite.
See §9 of the design doc.

## Running it

Requires **Java 21** (the POM sets `java.version` to 21) and no other setup — the SQLite file is
created on first run.

```bash
# verify your toolchain
java -version          # must report 21.x

# run the tests
./mvnw test

# start the service on :8080
./mvnw spring-boot:run
```

If `java -version` reports something older, point `JAVA_HOME` at a 21 JDK:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
```

The database file defaults to `workflow.db` in the working directory. Override it with the
`WORKFLOW_DB` environment variable:

```bash
WORKFLOW_DB=/tmp/scheduler.db ./mvnw spring-boot:run
```

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/workflows` | Register a workflow definition |
| POST | `/workflows/{name}/runs` | Start a run (idempotent by `requestId`) |
| POST | `/runs/{runId}/claims` | Claim up to `maxCount` runnable steps |
| POST | `/runs/{runId}/steps/{stepId}/complete` | Complete a claimed attempt |
| GET | `/runs/{runId}` | Current run summary |

Errors are returned as RFC 9457 `ProblemDetail`. The status codes carry meaning: `400` for a
malformed request, `404` for an unknown workflow or run, and `409` for a well-formed request that
lost a race — wrong worker, wrong attempt number, or a step that is not currently `in_progress`.
A `409` tells a client to re-read state rather than fix its payload.

## Project layout

```
domain/       pure scheduling rules, no Spring and no SQL
              WorkflowValidator  registration rules + Kahn's cycle detection
              RunPlanner         blocked derivation, claim selection, run status
              DependencyGraph    the edges of one workflow
persistence/  JdbcTemplate repositories; the claim compare-and-swap lives here
service/      WorkflowSchedulerService — the five operations, one transaction each
api/          controllers, request/response payloads, ProblemDetail mapping
```

The scheduling decisions are pure functions over a run's step instances, which is why they can be
tested directly and why swapping the repository for PostgreSQL leaves them untouched.

## Tests

`./mvnw test` runs 80 tests:

- **Unit, no Spring context** — the seven registration failures including two- and three-node
  cycles; transitive blocked propagation and un-blocking on retry; claim ordering and every
  runnable-condition guard; run-status aggregation including the parallel-branch case.
- **Integration, real SQLite in a JUnit `@TempDir`** — a fresh database file per test, including
  the exact scenario from the exercise brief asserted output by output.
- **HTTP** — the status-code contract, particularly the 400 / 404 / 409 split.
- **Concurrency** — eight threads claiming at once never receive the same step twice, and
  concurrent `startRun` calls with one `requestId` converge on a single run.
- **Configuration** — the shipped datasource URL must carry `transaction_mode=IMMEDIATE`. This
  guards a defect that was real: the fixtures set it, `application.yaml` did not, and the suite was
  green while the running app returned 500s under concurrent load.

The guards were mutation-tested: removing the compare-and-swap, the block on persisting a derived
status, or the SQLite exception translator each makes a specific test fail. Worth knowing that the
concurrency tests prove the *transaction* boundary, not the CAS — SQLite serializes writers, so the
CAS only becomes load-bearing on PostgreSQL and is covered separately at the repository level.

Every step transition is also written to `step_attempt_event`, an append-only log, so a run's
history can be replayed:

```
load-model       1  claimed    worker-a
load-model       1  succeeded  worker-a
validate-schema  1  claimed    worker-a
validate-schema  1  failed     worker-a
validate-schema  2  claimed    worker-b
```

## Follow-up discussion

The brief lists four topics to be ready to discuss. Each is worked through in the design doc:

| Question | Where |
|---|---|
| Making `ClaimRunnableSteps` safe across multiple application instances | §9, *Multi-instance claiming* — PostgreSQL `SELECT … FOR UPDATE SKIP LOCKED`; the CAS in the current claim is what survives the move |
| Persisting state and recovering after a crash | §9, *Stuck steps and crash recovery* — a `lease_expires_at` column plus a reaper that returns expired leases to `pending` **without** consuming an attempt |
| Observability, audit history, and replay | §9, *Observability* — `step_attempt_event` is already an append-only log, so run state can be reconstructed by folding it |
| Timeouts, stuck steps, manual retries | §9 — the same lease-and-reaper mechanism; a manual retry is that operation triggered by an operator |

## Assumptions

- **Workers are trusted** to complete only the attempts they hold. The `workerId` and
  `attemptNumber` guards exist to catch stale or confused workers, not malicious ones.
- **Step bodies are idempotent**, so re-running an attempt after a failure is safe. The scheduler
  does not attempt exactly-once execution — that is the worker's responsibility.
- **No per-step timeout in v1.** A worker that dies mid-attempt leaves its step `in_progress`
  indefinitely. The lease-and-reaper design that fixes this is described in §9 of the design doc but
  deliberately not built here.
- **`blocked` is a reported status, not a stored one** — it is derived from dependency state on
  every read.
- **`maxAttempts` includes the first attempt**, per the brief. A step with `maxAttempts: 1` gets one
  try and no retry.

## Design tradeoffs

**SQLite serialises writes.** This is a correctness guarantee and a throughput ceiling in the same
breath. For a single-instance take-home it is the right trade; §9 of the design doc leads with
PostgreSQL for exactly this reason.

**Claims use `BEGIN IMMEDIATE` plus a conditional `UPDATE`.** SQLite has no `SKIP LOCKED`, so the
write lock is taken up front — otherwise two concurrent claims can both start as readers and then
deadlock trying to upgrade. The conditional `UPDATE` on top of that is a compare-and-swap that is
technically redundant under SQLite's single writer. It is kept deliberately: it is the invariant
that keeps the claim correct when the repository is swapped for PostgreSQL, where transactions
genuinely can interleave.

**`blocked` is derived, not stored.** A stored flag would need invalidating every time a step went
`failed → pending` on retry — a bug that is easy to write and hard to test for. The cost is an
O(V+E) walk per read, which is negligible at any realistic DAG size.

**One attempt counter, not two.** `attemptCount` counts attempts *started* and increments at claim
time, so while a step is `in_progress` it is also the number of the attempt currently running. That
makes the wrong-attempt check a single comparison and removes any ambiguity about whether an
in-flight attempt is counted.

**The definition is copied into each run.** `priority` and `maxAttempts` are denormalised onto
`step_instance` at run start, and the dependency edges into `run_step_dependency`. Copying the
edges is the part that is easy to miss: if the scheduler rebuilt the graph from the live definition,
removing a dependency would instantly make an in-flight run's step runnable and adding one would
block it. With them copied, a run is a genuine immutable snapshot.

**`run_status` is a cached derivation.** It duplicates state that could be computed from the step
rows, in exchange for making `GET /runs/{id}` a single cheap read. It is kept honest by being
recomputed inside the same transaction as every step change, and at run creation — a run with no
runnable work is terminal the moment it exists.
