# Make controller-stack deploys safe: a drain that sees real job state, recovery of queued jobs, and a reliable STARTED transition

- **Tracker task:** `9856a34e-658f-4e82-ad5b-6d671c979f2f` — "Make controller-stack
  deploys safe: explicit trigger, a drain that sees real job state, and recovery
  of queued jobs"
- **Release:** Common 0.76
- **Branch:** `project/task-20261020-055709`

---

## Motivation

On 2026-10-19 the *Deploy Controller Stack* workflow rebuilt and restarted the
controller stack and every agent node while jobs were in flight. Three running
jobs were killed (one, consolidation `5fa15e0b`, had finished its work and was
in review with nothing committed) and four queued jobs never started. No work
resumed afterwards. The incident exposed four independent failures, each of
which can lose work on its own:

1. **Deploys were automatic and the operator did not know.** A `workflow_run`
   trigger deployed on every green master run; the `production` environment gate
   had no required reviewer. The only reason this was rare is that master runs
   usually contain at least one failure — a load-bearing safety assumption that
   evaporates the moment master passes reliably.
2. **The drain could not see the running jobs.** It reported "No in-flight agent
   jobs" at 20:51:05 while three were running, so the deploy proceeded into live
   work.
3. **Queued jobs did not survive the restart.** The dispatch queue lives only in
   the controller's memory; the four queued jobs were stranded.
4. **The STARTED transition was sometimes not applied.** Two jobs ran and
   recorded phases but stayed `SUBMITTED`, so they were reported as *lost / never
   started* rather than *running-then-killed*, misdirecting any recovery.

The task is to close each failure so that a deploy is a deliberate act that
refuses to destroy active work, and so that work the controller accepted is not
silently lost across a restart.

---

## What already landed, and what this plan covers

**Scope item 1 (make deploying a deliberate act) is already delivered on
`master`.** Commit `b465c7a21` ("Make Deploy Controller Stack workflow
manual-only") removed the `workflow_run` trigger — `.github/workflows/deploy.yaml`
is now `workflow_dispatch`-only — and documented the choice in `.github/CLAUDE.md`
("What the `Deploy Controller Stack` workflow does"). No further change to the
trigger or that section is needed; the plan records it as complete so the
reviewer is not surprised to find it done. This branch builds on that commit.

This plan therefore covers the remaining scope items 2–5:

- **(2)** Drive the drain from the controller's own job state.
- **(3)** Recover persisted `SUBMITTED` jobs across a controller restart.
- **(4)** Find and fix the missing `STARTED` transition.
- **(5, optional)** Have the deploy refuse to restart agent nodes while any job
  is active even when the drain times out, unless `skip_drain` is set.

---

## Background: how job state works today

The controller-owned job-state tracking (`5ff6e7ff`, PR #676; design in
[`PLAN-20261018-controller-job-state-tracking.md`](PLAN-20261018-controller-job-state-tracking.md))
persists a `job_timing` row per job in `JobStatsStore`, progressing
`SUBMITTED → STARTED → terminal`:

- **`recordJobSubmitted`** writes the `SUBMITTED` row at acceptance
  (`FlowTreeApiEndpoint.handleSubmit`, via `JobLifecycleRecorder.recordSubmitted`).
- **`recordJobStarted`** moves it to `STARTED` with node identity. It is called
  **only** from `JobLifecycleRecorder.recordStarted`, which is called **only**
  from the `STARTED` branch of `FlowTreeApiEndpoint.handleStatusEvent`
  (`FlowTreeApiEndpoint.java:1290`) — i.e. only when a `status=STARTED` event is
  POSTed to the controller.
- **`recordHeartbeat`** (`JobStatsStore.java:676`) and **`recordPhase`**
  (`JobStatsStore.java:712`) are bare `UPDATE`s of `heartbeat_at`/`node` and
  `phase` respectively; **neither touches `status`.**
- `getActiveJobs(ws)` returns rows with `status='STARTED' AND completed_at IS NULL`;
  `getPendingJobs(ws)` returns `status='SUBMITTED' AND completed_at IS NULL`.
  `PendingJob` derives `LOST` once a `SUBMITTED` row ages past `T_lost`;
  `ActiveJob` derives `UNRESPONSIVE` once a `STARTED` row is silent past
  `T_silent`.

A running job proves its liveness through two paths, **neither of which promotes
`SUBMITTED` to `STARTED`**:

- `HarnessHeartbeatSender` POSTs `/api/jobs/{id}/heartbeat` (node + phase) for the
  whole life of the job → `StatsQueryHandler.handleHeartbeat` → `recordHeartbeat`
  (+ `recordPhase`).
- `HarnessStatusReporter.phaseEntry`/`recordPhaseEntry`
  (`CodingAgentJob.java:1245`) POST a message carrying a `phase` field to
  `/messages` → `MessageEndpointHandler` → `statsQueryHandler::recordPhase`.

`GitManagedJob.postStatusEvent` (fire-and-forget; errors only logged, never
retried, `GitManagedJob.java:1305`) is invoked **only at completion**
(`fireJobCompleted`, line 599). No code path posts a `status=STARTED` event from
the running Java job.

---

## Scope and approach

### Item 4 — the missing STARTED transition *(do this first; items 2, 3, 5 depend on the state being trustworthy)*

**Root cause.** The promotion to `STARTED` hangs on a single `status=STARTED`
event reaching `handleStatusEvent`. The two signals that *prove* a job is running
— the periodic heartbeat and the phase entries — land on the row but leave its
`status` at `SUBMITTED`. When that one event is lost or never arrives (the two
jobs started ~20:28Z recorded phases "primary"/"review" but stayed `SUBMITTED`,
while jobs started ~19:58Z transitioned correctly), a demonstrably-running job is
indistinguishable from one that never started, and is reported `LOST`.

**Fix.** Make a running-proof signal promote the row idempotently. In
`JobStatsStore`, when a heartbeat or phase update arrives for a row still in
`SUBMITTED` (and not terminal), transition it to `STARTED`, seeding `started_at`
(from the signal's instant) and `node` (from the heartbeat, when present),
exactly as `recordJobStarted`'s `WHEN MATCHED AND status='SUBMITTED'` MERGE does.
A row already `STARTED` or terminal is untouched, so the promotion is idempotent
and a later real `STARTED` event is a no-op. This makes `STARTED` *derivable from
evidence the job is alive* rather than dependent on one fragile event.

The cleanest placement is a single private `promoteToStarted(jobId, at, node)`
helper on `JobStatsStore` that `recordHeartbeat` and `recordPhase` call before
their existing `UPDATE` (both already run on the store that owns the status
column). Confirm whether a genuine `status=STARTED` emitter is also expected to
exist; if the intended emitter is the PRIMARY phase-entry message, the promotion
above *is* the repair and no separate emitter is needed. Do not remove the
existing `handleStatusEvent` STARTED branch — it remains the path that carries
node identity for jobs that do post it.

**Tests** (`JobStatsStoreLifecycleTest`, extended):
- a phase update on a `SUBMITTED` row promotes it to `STARTED` and it then
  appears in `getActiveJobs` and not in `getPendingJobs` — "phase updates
  arriving before, or without, a STARTED event" (the task's named regression);
- a heartbeat on a `SUBMITTED` row promotes it and records the node;
- a promotion does not overwrite an existing `started_at`/node for a row already
  `STARTED`, and never resurrects a terminal row.

### Item 2 — drive the drain from real job state

**Root cause (deeper than the task states).** `tools/ci/drain-agent-jobs.sh`
polls `GET /api/jobs?limit=100` and counts only a hard-coded set of status
strings (`running/in_progress/started/pending/queued`), ignoring unknown ones —
so `SUBMITTED` would not be counted. But there is **no `GET /api/jobs` list route
at all**: the controller exposes `/api/stats`, `/api/workstreams`,
`/api/workstreams/{id}/jobs[/active|/pending]`, and `/api/jobs/{id}`, nothing
else. `GET /api/jobs` (no id) matches nothing, so `curl -fsS` fails and
`count_running` prints `0`. **The drain reports zero on every call regardless of
what is running** — which is exactly what it did during the incident. The
status-string problem is real but secondary to the missing endpoint.

**Fix.**
- Add an all-workstreams in-flight endpoint to `FlowTreeApiEndpoint`, backed by
  the store's existing `getActiveJobs(null)` and `getPendingJobs(null)`. Both
  accept a `null` workstream id to mean "every workstream"; `getActiveJobs(null)`
  is already exercised that way in-process by `StuckJobScanner`, while
  `getPendingJobs` is so far only called per-workstream (by `StatsQueryHandler`),
  so the `null` path is supported but new to this caller. Preferred shape:
  `GET /api/jobs/in-flight`
  returning a small JSON object `{ "active": N, "pending": N, "total": N }` where
  `pending` counts `SUBMITTED` rows (assigned but not yet `STARTED`) and `active`
  counts `STARTED` rows. Returning the derived state alongside lets the drain log
  what it is waiting on. **Routing-order caveat:** a path under `/api/jobs/…` is
  shadowed by the existing `uri.startsWith("/api/jobs/")` → `getJob` branch
  (`FlowTreeApiEndpoint.java:646`); register the new route *before* it, or choose
  a non-colliding path (e.g. `/api/jobs?state=in-flight` handled on the `/api/jobs`
  exact match).
- Rewrite `count_running` in `drain-agent-jobs.sh` to call the new endpoint and
  read the integer count directly, rather than enumerating status strings. "In
  flight" = `STARTED` + `SUBMITTED` (both hold, or are about to hold, an
  ar-manager connection). Keep the existing conservative behaviour for an
  unreachable controller (count as zero; the workflow already skips the drain
  when the controller is down before it starts).

**The regression the task asks for: a test that fails if a new non-terminal
status is not counted.** Add a controller-side test
(`JobStatsStoreLifecycleTest` or a small `FlowTreeApiEndpoint` endpoint test)
that asserts the in-flight count is computed from the set of non-terminal
statuses, so that introducing a new non-terminal status without counting it fails
the test. Concretely: drive the count off the same `getActiveJobs`/`getPendingJobs`
views the operator surfaces use, and assert a `SUBMITTED`-but-assigned row and a
`STARTED` row both count while terminal rows do not — so a future status that
should be in-flight but is omitted from those views is caught here rather than in
a future incident.

### Item 3 — recover persisted SUBMITTED jobs across a restart

**Root cause.** The dispatch queue (`DelayedJobQueue`, `flowtree/runtime/.../api/
DelayedJobQueue.java`) holds pending dispatch futures only in memory and is lost
on restart. The `SUBMITTED` rows persist (`JobStatsStore`'s data dir is a volume
that survives rebuilds), but `job_timing` stores only
`jobId/workstreamId/description/requiredLabels/submittedBy` — **not** the full
`CodingAgentJobFactory` (repo URL, branch, prompt, phase config), so a persisted
row alone cannot be re-dispatched.

**Fix.** Persist enough to reconstruct the job, then re-dispatch on startup with
an idempotency guard:
- Persist the job's serialized factory at submission time, keyed by job id, so a
  `SUBMITTED` job can be rehydrated. `CodingAgentJobCodec` (and
  `GitManagedJobCodec`) already encode/decode factories for the wire; reuse that
  encoding rather than inventing a second serialization. Store the blob alongside
  the `job_timing` row (new column or sidecar table) under the same persistent
  data dir, written in the same place `recordJobSubmitted` writes the row and
  cleared when the job reaches a terminal state.
- On controller start — in `FlowTreeController.startApiEndpoint` (~line 1188),
  immediately after `JobStatsStore` is rebuilt from the data dir and the endpoint
  / `DelayedJobQueue` are wired — query the persisted `SUBMITTED`-and-not-terminal
  rows, decode each factory, and re-submit it through the same
  `delayedJobs.submit(...)` path a fresh submission uses.
- **Idempotency guard.** Re-dispatch only rows still `SUBMITTED` (not `STARTED`,
  not terminal). Because item 4 makes a running job promote itself to `STARTED` on
  its first heartbeat/phase, a job that actually started is no longer `SUBMITTED`
  and is not re-run. Guard additionally against a job that starts between the
  startup query and the re-submit (e.g. a MERGE-guarded "claim" that marks the
  row before re-dispatch, or a check that the row is still `SUBMITTED` at submit
  time), so a job cannot be run twice.

**Tests:**
- round-trip the persisted factory blob and assert the rehydrated factory
  re-dispatches the same job (reuse `GitManagedJobSerializationTest.roundTripFactory`
  per `flowtree/CLAUDE.md`);
- a startup-recovery test: given a persisted `SUBMITTED` row with a stored
  factory, startup re-dispatches it exactly once; given a `STARTED` or terminal
  row, startup does not re-dispatch it.

### Item 5 (optional) — refuse to restart agents while work is active

With item 2 in place the drain reports true in-flight counts, so the deploy's
existing behaviour (fail rather than force when the drain times out) already
protects active work on the common path. The optional hardening: in `deploy.yaml`,
after the drain step, when `skip_drain != 'true'`, re-check the in-flight count
and fail the agent-restarting steps if any job is active even if the drain
exited on timeout — a belt-and-braces guard so a drain that gives up never
proceeds into live work silently. This is a small `deploy.yaml` addition gated on
`skip_drain`; include it if item 2's endpoint makes the re-check a one-line
`curl`. `.github/workflows/` is under the CI-file lock, so any `deploy.yaml`
change must be made on a `ci/…`-named branch — see Dependencies.

---

## Success criteria

A reviewer can confirm the task is done when:

1. **STARTED transition (item 4):** a job that posts a phase or heartbeat while
   `SUBMITTED` is promoted to `STARTED`; the new `JobStatsStoreLifecycleTest`
   cases (phase-before/without-STARTED, heartbeat-promotes-with-node,
   no-resurrection-of-terminal) pass.
2. **Drain (item 2):** `GET`-ing the new in-flight endpoint returns the true
   active + pending count; `drain-agent-jobs.sh` blocks while that count is
   non-zero and the controller-side test fails if a non-terminal status is
   dropped from the count.
3. **Recovery (item 3):** the factory round-trips through the persisted blob, and
   the startup-recovery test shows a persisted `SUBMITTED` job is re-dispatched
   exactly once while `STARTED`/terminal jobs are not.
4. **Build + targeted tests:** `mvn clean install -DskipTests` succeeds; the
   affected `flowtree/runtime` tests (`JobStatsStoreLifecycleTest`,
   `JobLifecycleRecorderTest`, `GitManagedJobSerializationTest`, any new endpoint
   test) pass via the MCP test runner; build-validator checkstyle/code-policy are
   clean.
5. **Deliberate trigger (item 1):** already satisfied on master (`b465c7a21`); no
   change required.

---

## Dependencies and open questions

- **Build on `master` HEAD.** Item 1 is already merged; this branch must sit on
  top of `b465c7a21` so it does not reintroduce the `workflow_run` trigger.
- **CI-file lock splits the work.** Everything under `.github/workflows/` and
  `tools/ci/` (the `drain-agent-jobs.sh` rewrite in item 2, and the optional
  `deploy.yaml` guard in item 5) may only change on a branch named `ci/…`
  (`check-ci-file-lock.sh`). The controller/Java changes (items 2-endpoint, 3,
  4) have no such restriction. **Open question for the approver: should this land
  as two coordinated branches — a `ci/…` branch for the shell/workflow edits and
  this `project/…` branch for the controller code — or should the whole task be
  re-homed onto a `ci/…` branch?** The controller endpoint must merge before, or
  with, the drain-script change, since the script depends on the new endpoint.
- **Where to persist the factory blob (item 3).** Options: a new column on
  `job_timing`, or a sidecar table/file under the same data dir. The approver
  should confirm the data dir is the correct durable location (it already holds
  `JobStatsStore`'s H2 database and survives container rebuilds) and that storing
  a serialized factory there is acceptable (it may contain submission metadata;
  it does **not** contain long-lived secrets — the ar-manager token is minted
  per-dispatch in `handleSubmit`, so a rehydrated factory will mint a fresh
  token on re-dispatch, which should be verified).
- **Confirm the intended STARTED emitter (item 4).** The fix promotes on
  liveness, which is robust regardless. If investigation finds a half-wired
  `status=STARTED` emitter that was *supposed* to fire (e.g. on node pickup), the
  implementer should note it; the promotion approach does not require repairing
  it, but a comment recording the decision belongs on `recordJobStarted`.
- **Task description precision.** The task's framing of cause 2 ("counts only the
  statuses … SUBMITTED isn't counted") understates the defect: the queried
  endpoint does not exist, so the drain counts zero unconditionally. A dated
  clarification has been appended to the tracker task so a reviewer reads the
  accurate root cause; the original text is preserved.
- **`FileLength` pressure.** `JobStatsStore.java` (1536 lines), `GitManagedJob.java`
  (1597), and `FlowTreeApiEndpoint.java` are all near the 1600-line checkstyle
  hard limit. Items 2-4 add code to `JobStatsStore` and `FlowTreeApiEndpoint`.
  The implementer must budget an orderly collaborator extraction as part of this
  change rather than compressing to fit — e.g. the persisted-factory recovery
  (item 3) is a cohesive responsibility that can live in its own collaborator
  (a `PendingJobRecovery` / job-recovery type) that `FlowTreeController` invokes,
  keeping the new logic out of the already-large endpoint and store.

---

## Estimated complexity

**Large.** Item 4 is a contained store change with tests; item 2 is a small
endpoint plus a shell rewrite; but item 3 (persist the full factory, re-dispatch
on startup, idempotency) touches submission, persistence, serialization and
controller startup, and the CI-file lock forces the shell/workflow pieces onto a
separate `ci/…` branch. The three items are independently reviewable and should
be implemented in the order 4 → 2 → 3 (→ 5) so each rests on trustworthy state;
if the approver prefers smaller units, item 4 and item 2 could ship first and
item 3 follow as its own change.
