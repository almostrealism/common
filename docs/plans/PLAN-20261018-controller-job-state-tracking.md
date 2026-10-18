# Track FlowTree Job State at the Controller

**Tracker task:** `f720b554-e88b-4043-ae27-fcc5d717371d` — "Track FlowTree job
state at the controller: record submissions, job-sent heartbeats, and flag
lost/dead jobs"
**Release:** the release currently under development (the one whose name matches
the version in the root `pom.xml`).

## Motivation

FlowTree is peer to peer: jobs circulate without central coordination, and the
controller today often cannot say what state a job is in. The operator falls
back on two rules of thumb — "probably dead" (running, silent past a point) and
"probably lost" (submitted, never started) — that should be built into the
system rather than applied by hand.

The evidence in the task (Master Agent Dispatch run 39087091856, 2026-10-18
01:36Z) is concrete: of ~7 jobs submitted in a two-second burst, three never
started and posted nothing, two started and went silent, two completed. For all
of them `workstream_get_job` returns "Job not found" and `active_jobs` is empty.
`startedAt == finishedAt` holds for every workstream in
`workstream_list(include_status)`, which is the signature of STARTED rows never
being written. The controller has no durable record of jobs it accepted, so it
cannot distinguish submitted-but-lost from running-but-silent from finished.

Three things are needed, each of which the controller can own without depending
on a notification integration:

1. a durable job-state row written by the controller at **submission**, moved to
   **started** and then **terminal** — independent of Slack;
2. a **heartbeat** the job's own harness posts on a timer for its whole life, so
   liveness no longer depends on agent message activity; and
3. **derived states** (lost / unresponsive / dead) with configurable thresholds,
   surfaced wherever the operator and orchestrators already look.

## Current state (what already exists, and what is missing)

A large part of the liveness machinery already landed on master in commit
`fc4adde79` ("job wall-clock ceiling, liveness, and stall recovery"). The plan
below builds on it and does **not** rebuild it.

Already present:

- `flowtree/runtime/.../controller/JobStatsStore.java` — the `job_timing` table
  already has `heartbeat_at` and `phase` columns and `recordHeartbeat`,
  `recordPhase`, `getJobPhase`, and `getActiveJobs` (returning the `ActiveJob`
  record with `age()`, `sinceHeartbeat()`, `isStale()`). Schema evolution is
  already backfill-safe: `SCHEMA_MIGRATIONS` issues `ALTER TABLE ... ADD COLUMN`
  and the init loop catches and ignores "column already exists".
- `flowtree/runtime/.../controller/StuckJobScanner.java` — a conservative,
  idempotent scanner that fails jobs silent longer than a threshold (currently
  `wallClockCeiling × 2`) through `endpoint::completeJob`, wired by
  `CompletionListenerFanoutWiring.wire()`.
- `FlowTreeApiEndpoint.handleStatusEvent` records a heartbeat on *every* status
  event; the heartbeat/phase HTTP surface already exists and is
  dispatched via `StatsQueryHandler.recordHeartbeat` / `recordPhase`.

Missing — the gaps this task closes:

1. **STARTED persistence is owned by the notifier, not the controller.**
   `handleStatusEvent` calls
   `notifiers.completionListener(ws).onJobStarted(...)`, and
   `SlackNotifier.onJobStarted` is what calls
   `JobStatsStore.recordJobStarted`. That method returns early on "Unknown
   workstream". With no Slack notifier for the workspace, or a stale
   workstream map for a newly registered workstream, **no row is ever written**,
   so `recordHeartbeat` is a no-op, `getActiveJobs`/`StuckJobScanner` never see
   the job, and the completion path falls back to `started_at = completedAt`.
2. **Submissions are never persisted.** At submit (`handleSubmit`)
   the controller has `factory.getTaskId()` (the job id), the resolved
   `workstreamId`, and the resolved `requiredLabels`, but only
   fires `onJobSubmitted` (a Slack message). Nothing writes a row, so
   "submitted but never started" cannot be represented at all.
3. **No agent-side periodic heartbeat.** The heartbeat is recorded only as a
   side effect of a status event landing, i.e. it is coupled to agent message
   activity — exactly what the task wants to decouple. `GitManagedJob` posts
   STARTED and terminal events; nothing posts a heartbeat on a timer.
4. **Only one derived state.** The scanner's single staleness threshold
   (`2× wall-clock ceiling`) is the only derived judgement, and it is far too
   slow for the operator's "silent means dead". There is no LOST
   (submitted-never-started), no UNRESPONSIVE distinct from DEAD, and no
   configurable thresholds.
5. **The surfaces show none of this.** `tools/mcp/manager/workstream_tools.py`
   exposes `workstream_get_job` (→ `GET /api/jobs/{id}`), `workstream_get_status`
   (→ `/api/stats` + `/api/workstreams/{id}/jobs/active`), and
   `workstream_list(include_status)` (→ `/api/stats` lastJob fields). Active jobs
   carry `ageSeconds`/`sinceHeartbeatSeconds`, but there is no pending/lost list
   and no derived-state label.

## Scope

In scope, all within existing Maven modules (no new modules):

1. **Controller-owned job-state persistence.** Persist a `job_timing` row at
   submission with a new `SUBMITTED` state, the submitter, and the required
   labels; move it to `STARTED` (with node identity) when the started event
   lands; and to terminal on completion. The write must happen in a
   controller-owned component invoked from the controller's own submit and
   status-event paths — **not** inside any `JobCompletionListener`/notifier.
   Backfill-safe `job_timing` migration for the new columns, following the
   existing `SCHEMA_MIGRATIONS` pattern.
2. **Job heartbeat from the harness.** The job's harness posts a heartbeat to
   the controller every N minutes for the whole life of the job — queue pickup,
   every phase, and post-completion processing — carrying node, phase, and pid.
   It is independent of the agent session and of agent messages, so a silent but
   healthy agent is still visibly alive and a dead worker is visibly dead.
3. **Derived states with configurable thresholds:**
   - **LOST** — `SUBMITTED`, never `STARTED`, after `T_lost`.
   - **UNRESPONSIVE** — `STARTED`, no heartbeat for `T_silent`.
   - **DEAD** — UNRESPONSIVE for longer than `T_dead`; failed through the
     ordinary completion path (`completeJob`) so listeners fire. Preserve the
     scanner's idempotence and conservatism; never terminate a job that still
     heartbeats.
4. **Surface the states** in `workstream_get_job`, `workstream_get_status`
   (`active_jobs`, plus a pending/lost list) and `workstream_list(include_status)`.
5. **Tests** for the state derivation and the persistence path, including a
   workspace with no Slack configured.

Out of scope (named as dependencies / related work below, not widened into):

- Peer-disconnect relay loss and requeue — covered by
  `docs/plans/FLOWTREE_RELAY_FAILURE_DETECTION.md` (Proposed).
- The root cause of the batch-submit drop (~7 near-simultaneous submissions) —
  a separate investigation job. The `SUBMITTED` state makes that loss class
  *visible* regardless of its cause; it does not fix the drop.

## Approach

Grounded in the files above. The organizing principle is the task's own
requirement and the FlowTree guideline that **the controller owns its job
record**: persistence of controller-side job state must not live in a notifier.

### 1. A controller-owned job-state recorder

Introduce a single cohesive collaborator in `io.flowtree.controller` (working
name `JobLifecycleRecorder`) that owns the controller-side writes to
`JobStatsStore` across the lifecycle:

- `recordSubmitted(jobId, workstreamId, description, submittedBy, requiredLabels, submittedAt)`
- `recordStarted(jobId, workstreamId, description, node, startedAt)`
- `recordTerminal(workstreamId, event)` delegating to the existing
  `JobStatsStore.recordJobCompleted`.

`FlowTreeApiEndpoint` calls it directly at all three transitions:

- in `handleSubmit`, right where `onJobSubmitted` is fired today,
  so a row exists the instant the controller accepts the job;
- in `handleStatusEvent`, in the `STARTED` branch, so STARTED
  persistence no longer depends on `SlackNotifier.onJobStarted`; and
- in `completeJob`, the controller's single terminal choke point, so terminal
  persistence no longer depends on `SlackNotifier.onJobCompleted`.

The terminal wiring point is load-bearing and must be `completeJob`
specifically, not the `handleStatusEvent` terminal branch: `completeJob` is the
one path every terminal event travels, including the synthetic completions
`StuckJobScanner` routes through `endpoint::completeJob`. Wiring the recorder
there — before or after the existing `onJobCompleted` fan-out — means a
scanner-failed job and an ordinary completion both update `job_timing`, with or
without a Slack notifier. Wiring it only into the status-event branch would
leave scanner failures (the DEAD path this very plan adds) unpersisted.

`SlackNotifier.onJobStarted`/`onJobCompleted` stop being the persistence owners
and go back to doing only what a notifier should — posting messages. This is the
crux of root cause #1: a workspace with no Slack notifier, or a notifier with a
stale workstream map, must still get a complete job row — at submit, at start,
and at terminal.

Because `FlowTreeApiEndpoint`, `CodingAgentJob`, and `CodingAgentJobFactory` all
run close to the 1600-line Checkstyle `FileLength` limit, the recorder is a
**separate class**, not inline code, and (per the FlowTree guideline) any
refactor needed to make room is part of this change, not a follow-up.

`JobStatsStore` gains:

- new columns via `SCHEMA_MIGRATIONS` (backfill-safe): `submitted_at`,
  `submitted_by`, `required_labels` (serialized label map), and a `node`
  identity column for the started worker; and
- `recordJobSubmitted(...)`, plus a `getPendingJobs(...)` query for rows in
  `SUBMITTED` with no `started_at`, mirroring the existing `getActiveJobs`.

Persisted `status` progression becomes `SUBMITTED → STARTED → terminal`.
`getActiveJobs` keeps its current meaning (STARTED, not completed); weekly-stats
queries that already filter `status <> 'STARTED'` must also exclude
`'SUBMITTED'` so a pending job is not counted as completed work. `SUBMITTED` is
added to `JobCompletionEvent.Status`.

### 2. Harness-side periodic heartbeat

Add a small heartbeat sender on the worker/harness side (in the job lifecycle
owned by `GitManagedJob`/`CodingAgentJob`, alongside the existing
`postStatusEvent` plumbing) that, on a scheduled timer every N minutes, POSTs to
the controller's existing heartbeat endpoint for the whole life of the job —
from queue pickup through every phase to post-completion — and stops when the
job terminates. The payload carries node, current phase, and pid. It must run
independently of the agent session (so it keeps beating while an agent is
legitimately silent inside a long tool call) and must not itself be mistaken for
agent activity by `AgentInactivityMonitor`.

The controller side already accepts and records the heartbeat
(`StatsQueryHandler.recordHeartbeat` → `JobStatsStore.recordHeartbeat`); the
heartbeat endpoint may need to accept and persist the node/phase/pid fields the
harness now sends (phase already has a path via `recordPhase`). N and the
endpoint contract are an open question below.

### 3. Derived states

Extend the derivation that `StuckJobScanner` + `ActiveJob` already embody rather
than adding a parallel mechanism:

- **LOST**: a read-time/scan-time classification over `getPendingJobs` — a
  `SUBMITTED` row with no `started_at` older than `T_lost`. Surfaced, not
  auto-failed (a lost job never ran, so there is nothing to fail through the
  worker; whether the controller should also emit a terminal event for it is an
  open question).
- **UNRESPONSIVE**: a `STARTED` job whose `sinceHeartbeat(now) > T_silent`.
  Surfaced as a warning state; `ActiveJob.isStale` already computes the
  arithmetic.
- **DEAD**: UNRESPONSIVE beyond `T_dead` → failed via `completeJob`, exactly as
  the scanner already does, preserving its idempotent `terminated` set and its
  "never kill a job that still heartbeats" conservatism. The scanner's threshold
  changes from the single `2× wall-clock ceiling` to the configurable `T_silent`
  / `T_dead` pair.

Thresholds `T_lost`, `T_silent`, `T_dead` become configurable (setters on the
scanner with documented defaults, matching how `setWallClockCeiling` /
`setStalenessMultiplier` already work), so the operator's "silent means dead"
judgement can be tuned without a rebuild.

### 4. Surfaces

- **Java.** `ActiveJob.toJson` gains a derived `state` label
  (`RUNNING`/`UNRESPONSIVE`), and `StatsQueryHandler.handleActiveJobs` keeps
  rendering it. Add a pending/lost listing (new `getPendingJobs` →
  `/api/workstreams/{id}/jobs/pending`, or fold into the active-jobs response as
  a `pending` array — see open questions). `JobQueryHandler.getJob` (backing
  `GET /api/jobs/{id}`) reports `SUBMITTED`/derived state and `submittedAt`.
- **Python.** `tools/mcp/manager/workstream_tools.py`:
  `workstream_get_job` surfaces the submitted/lost/unresponsive state and
  `submittedAt`; `workstream_get_status` adds a pending/lost list beside
  `active_jobs` and labels each active job with its derived state (replacing the
  ad-hoc 6h `active_jobs_warning` synthesized in Python);
  `workstream_list(include_status)` reflects a last-job or current state that can
  be `SUBMITTED`/`LOST`. Keep the controller the source of truth for the
  derivation — Python presents, it does not re-derive thresholds.

## Success criteria

A reviewer can confirm the task is done when:

- A job submitted to a workspace **with no Slack notifier configured** produces
  a durable `job_timing` row at submit time (`SUBMITTED`, with submitter and
  required labels), transitions to `STARTED` with node identity when the started
  event lands, and to a terminal state on completion — verified by a unit test
  that drives the controller-owned recorder with no notifier present.
- `recordJobStarted` is no longer reachable only through
  `SlackNotifier.onJobStarted`, and `recordJobCompleted` no longer only through
  `SlackNotifier.onJobCompleted`; the controller records STARTED and terminal
  state on its own path (`completeJob`), so the same no-notifier workspace that
  gets a SUBMITTED and STARTED row also gets its terminal row.
- A job failed by `StuckJobScanner` (the DEAD path) — a synthetic completion
  routed through `endpoint::completeJob` — persists its terminal state, verified
  without any notifier configured.
- The harness posts heartbeats on a timer independent of agent messages, and a
  started-then-silent job becomes UNRESPONSIVE after `T_silent` and DEAD (failed
  via `completeJob`, firing listeners) after `T_dead`, while a job that keeps
  heartbeating is never failed — verified by extending `StuckJobScannerTest`.
- A submitted job that never starts is reported LOST after `T_lost` and appears
  in the pending/lost surface — verified by a unit test over `getPendingJobs`
  and the derivation.
- `workstream_get_job`, `workstream_get_status`
  (`active_jobs` + pending/lost), and `workstream_list(include_status)` show the
  new states.
- New columns are added by backfill-safe migration: an existing `job_timing`
  database opens without error and old rows read back correctly.

Tests to add/extend (bounded, per the project's test-execution limits):

- `JobStatsStore` persistence: submit → start → complete path, the new columns,
  `getPendingJobs`, and open-existing-db migration safety
  (new test class alongside `JobStatsStorePhaseTest` /
  `JobStatsStoreModelCostTest`).
- Controller-owned recorder with **no Slack notifier** configured.
- `StuckJobScannerTest`: LOST, UNRESPONSIVE, and DEAD derivations and threshold
  configurability; idempotence and "still heartbeating ⇒ never failed" retained.
- Harness heartbeat sender: posts on its timer and stops at termination
  (in `flowtree/agents` or `flowtree/runtime` jobs tests, matching where the
  sender lives); round-trip any new job property through
  `GitManagedJobSerializationTest.roundTripFactory` if a job property is added.
- Python: `tools/mcp/manager` test(s) for the new fields on the three tools.

Run only the directly-affected classes (≤5 classes / ≤40 methods per the limits);
the full matrix is CI's job. Validate with `ar-build-validator` (checkstyle,
code_policy, test_timeouts, duplicate_code) before completion — the new
collaborator and any extraction must stay under the 1600-line `FileLength` cap.

## Dependencies and open questions

- **Threshold defaults and the heartbeat interval N.** The task says each derived
  state has configurable thresholds and the harness beats "every N minutes" —
  concrete defaults for `T_lost`, `T_silent`, `T_dead`, and N need a decision.
  Suggested starting point for review: N = 2–5 min; `T_silent` a small multiple
  of N (e.g. 15–20 min) to realize the operator's "silent means dead" faster
  than today's `2× wall-clock ceiling`; `T_dead` a further margin; `T_lost` on
  the order of tens of minutes. These are proposals, not decided.
- **Node / pid identity source.** Confirm how the harness names its node and
  obtains its pid for the heartbeat payload, and whether a `node` column on
  `job_timing` is the right home for the started-worker identity.
- **Trusted source for `submitted_by`.** `handleSubmit` has no `submittedBy`
  field today — the submit path resolves a workstream, labels and config, but
  nothing that identifies the submitter. Decide where the value comes from and
  whether it is trustworthy: the temporary ar-manager token's subject, an
  explicit `submittedBy` field on the submit payload, or the authenticated
  caller identity. An unauthenticated, caller-supplied string is worth little;
  if no trusted source exists yet, the column may start nullable and be
  populated once one does. This affects the `recordSubmitted` signature above.
- **Does a LOST job also get a terminal event?** It never ran, so there is no
  worker to fail; surfacing it may be enough, or the controller may want to emit
  a terminal `FAILED`/`LOST` event so dependent chains are released. Needs a
  decision — it affects whether `JobCompletionEvent.Status` gains `LOST`.
- **Pending/lost surface shape.** New `/jobs/pending` endpoint vs. folding a
  `pending` array into the existing active-jobs response — an API-shape choice
  for the operator tools.
- **Heartbeat payload vs. the "job is the source of truth" rule.** The heartbeat
  carries runtime liveness (node/phase/pid), not a configured job property, so it
  is harness lifecycle state rather than a `CodingAgentJob` property threaded
  through the whole submit path. Confirm this reading so the heartbeat sender is
  not mistakenly required to round-trip through the factory/codec.
- **Related, not blocking:** `FLOWTREE_RELAY_FAILURE_DETECTION.md` and the
  batch-submit-loss investigation. This task is complementary — it makes the loss
  observable; those fix the loss.

## Estimated complexity

**Large.** It spans the controller (new collaborator + submit/status wiring),
the stats store (schema + queries), the harness (periodic heartbeat sender), the
HTTP API, the Python operator tools, and a cross-cutting refactor to keep
`FlowTreeApiEndpoint` under the line cap — plus tests at each layer. If the
reviewer prefers smaller increments, a natural split is: (A) controller-owned
SUBMITTED/STARTED/terminal persistence + migration + surfaces; (B) harness
heartbeat sender; (C) LOST/UNRESPONSIVE/DEAD derivation and thresholds on top of
(A) and (B). (A) is the foundation the other two build on.
