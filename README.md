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

**Definition fields are copied into each run.** `priority` and `maxAttempts` are denormalised onto
`step_instance` at run start, so re-registering a workflow cannot retroactively change the retry
budget of a run already in flight. A run is an immutable snapshot.

**`run_status` is a cached derivation.** It duplicates state that could be computed from the step
rows, in exchange for making `GET /runs/{id}` a single cheap read. It is kept honest by being
recomputed inside the same transaction as every step change.
