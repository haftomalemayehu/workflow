Take-Home Coding Exercise: Persistent Workflow Scheduler

Objective
Build a small workflow scheduling component that stores workflow definitions and workflow runs, coordinates which steps are ready to execute, and tracks retries and final outcomes.
This should feel like a backend platform service, not just a pure algorithm exercise.

Problem Statement
Implement a workflow scheduler with the following responsibilities:
- store a workflow definition
- start a workflow run
- claim runnable steps for a worker
- complete a claimed step attempt
- return the current run summary

You may implement persistence in either of these ways:
- in memory
- SQLite, PostgreSQL, or another lightweight database

If you choose in-memory storage, keep the design structured so that a database-backed implementation would be straightforward to add later.

Required Operations
Implement the equivalent of the following operations. They may be methods in a library, service-layer functions, or API endpoints.

1. Register Workflow Definition
Input:
workflowName
steps

Each step contains:
- stepId
- priority
- maxAttempts
- dependencies, a list of stepId values

2. Start Workflow Run
Input:
- workflowName
- requestId

Behavior:
- starting a run should be idempotent by requestId
- if the same workflowName and requestId are submitted again, return the existing run instead of creating a new one

3. Claim Runnable Steps
Input:
- runId
- workerId
- maxCount

Behavior:
- return up to maxCount steps that are ready to run
- claiming a step should mark it as in_progress
- the claim result should include the attemptNumber for each claimed step

4. Complete Step Attempt
Input:
- runId
- stepId
- workerId
- attemptNumber
- result, which is either success or fail

Behavior:
- only a currently claimed step can be completed
- if a step fails and has attempts remaining, it becomes runnable again later
- if a step fails and has no attempts remaining, it becomes failed

5. Get Run Summary
Return a summary containing:
- runId
- workflowName
- runStatus, which is one of running, succeeded, or failed
- steps, containing for each step:
  - stepId
  - status, which is one of pending, in_progress, succeeded, failed, or blocked
  - attemptCount
  - lastWorkerId
Return steps sorted by stepId.

Rules
- a step is runnable only when all of its dependencies have succeeded
- if any dependency becomes failed or blocked, dependent steps should become blocked
- blocked status should propagate transitively
- when multiple runnable steps are available, claim them in this order:
  1. higher priority first
  2. lexicographically smaller stepId second
- maxAttempts includes the first attempt
- maxCount must be greater than 0
- step claiming should behave atomically from the perspective of the scheduler state
- no real concurrency is required, but the design should make the consistency rules clear

Validation Rules
Workflow definition is invalid if any of the following are true:
- missing or blank workflowName
- duplicate stepId
- missing or blank stepId
- maxAttempts <= 0
- a dependency references an unknown step
- a step depends on itself
- the dependency graph contains a cycle

Workflow run or execution operations are invalid if any of the following are true:
- unknown workflowName
- unknown runId
- blank requestId
- blank workerId
- maxCount <= 0
- trying to complete a step that is not currently in_progress
- trying to complete a step using the wrong workerId
- trying to complete a step using the wrong attemptNumber
- invalid result value

Example Workflow Definition
{
  "workflowName": "model-publish",
  "steps": [
    { "stepId": "load-model", "priority": 10, "maxAttempts": 1, "dependencies": [] },
    { "stepId": "validate-schema", "priority": 9, "maxAttempts": 2, "dependencies": ["load-model"] },
    { "stepId": "write-audit", "priority": 5, "maxAttempts": 1, "dependencies": ["load-model"] },
    { "stepId": "persist-metadata", "priority": 8, "maxAttempts": 1, "dependencies": ["validate-schema"] },
    { "stepId": "publish-event", "priority": 7, "maxAttempts": 1, "dependencies": ["persist-metadata", "write-audit"] }
  ]
}

Example Interaction
Start Run
Input:
{ "workflowName": "model-publish", "requestId": "req-1001" }
Output:
{ "runId": "run-1", "workflowName": "model-publish", "runStatus": "running" }

Claim Runnable Steps
Input:
{ "runId": "run-1", "workerId": "worker-a", "maxCount": 2 }
Output:
[
  { "stepId": "load-model", "attemptNumber": 1 }
]

Complete Step
Input:
{ "runId": "run-1", "stepId": "load-model", "workerId": "worker-a", "attemptNumber": 1, "result": "success" }

Claim Runnable Steps Again
Input:
{ "runId": "run-1", "workerId": "worker-a", "maxCount": 2 }
Output:
[
  { "stepId": "validate-schema", "attemptNumber": 1 },
  { "stepId": "write-audit", "attemptNumber": 1 }
]

Complete One Success and One Failure
{ "runId": "run-1", "stepId": "write-audit", "workerId": "worker-a", "attemptNumber": 1, "result": "success" }
{ "runId": "run-1", "stepId": "validate-schema", "workerId": "worker-a", "attemptNumber": 1, "result": "fail" }

Claim Runnable Steps Again
Input:
{ "runId": "run-1", "workerId": "worker-b", "maxCount": 2 }
Output:
[
  { "stepId": "validate-schema", "attemptNumber": 2 }
]

Expected Deliverables
Please provide:
- your source code
- unit tests
- a short README with:
  - how to run the solution
  - whether you chose in-memory or database-backed storage
  - any assumptions you made
  - any tradeoffs in your design

Expectations
We are primarily looking for:
- correctness
- maintainable design
- strong handling of state transitions and edge cases
- readable code and tests
- ability to explain how the design would evolve in production

Notes
- You may use any programming language
- You do not need to build a UI
- A library, console application, or small HTTP service is fine
- If you choose a database-backed solution, keep setup lightweight
- If you choose an in-memory solution, explain how you would make claims and updates safe in a multi-worker environment

Optional Follow-Up Discussion
Be prepared to discuss:
- how you would make ClaimRunnableSteps safe across multiple application instances
- how you would persist state and recover after a crash
- how you would add observability, audit history, and replay
- how you would support timeouts, stuck steps, or manual retries
