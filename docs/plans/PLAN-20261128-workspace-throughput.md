---
status: proposed
created: 2026-11-28
closed:
tracker: [ed283f13-7709-41fc-b6a3-0c5804ec69e5]
---

# Workspace Throughput: Retrospective Windows and Forward Forecast

## Summary

The owner cannot answer four questions from the tooling we have:

1. How much happened in the last 1h / 3h / 6h / 24h, or any custom window?
2. How much can we expect over the next 1h / 3h / 6h / 24h?
3. Are we likely to do more over the next day than over the last?
4. If I go to bed now, will the system stay busy all night, and if not, when
   does each pool go idle?

Today the only aggregates are **weekly per-workstream totals**
(`WeeklyStatsAggregator`, surfaced through `workstream_get_status`) and a
per-day agent-time rollup (`DailyAgentTime`). "How much in the last 3 hours"
takes dozens of per-job calls, and "how much tonight" has no answer at all.
Listings also mislead: a `LOST` or `UNRESPONSIVE` job is a read-time *label*,
never a persisted status, so `workstream_list` shows a stale `SUBMITTED`/`STARTED`.

This plan is **design only**. It is grounded in the current code
(§1 cites classes, tables and columns verbatim) and proposes, in stages:
a windowed retrospective aggregator (§2), a simple empirical forecast (§3), a
new query surface that keeps the three capped files from growing (§4), and a
staged build with tests including a reconciliation test against per-job records
(§5). Open questions for the owner are in §6.

**No new Maven module is created** (repository rule). Everything lands as new
*classes* in `flowtree/runtime` (packages `io.flowtree.controller` and
`io.flowtree.api`) and new *Python files* under `tools/mcp/manager/`. The files
that sit close to the 1600-line Checkstyle cap — `server.py` and
`FlowTreeController.java` are within a handful of lines of it, and
`FlowTreeApiEndpoint.java` carries a little more headroom (these counts drift
with every merge; read them from the files before acting) — are touched only
minimally, and §4.4 keeps the one unavoidable touch small.

---

## 1. Inventory — what is recorded per job today

All storage is `JobStatsStore`
(`flowtree/runtime/src/main/java/io/flowtree/controller/JobStatsStore.java`),
an HSQLDB file database extending `ControllerDatabase`. Three tables:

### 1.1 `job_timing` (one row per job, PK `job_id`)

Columns, from the `CREATE_TABLE` DDL plus the `SCHEMA_MIGRATIONS` ALTERs (both
in `JobStatsStore`):

| Column | Meaning | Written by |
|---|---|---|
| `workstream_id` | owning workstream | submit/start/complete |
| `status` | lifecycle status (enum below) | all transitions |
| `started_at` | real start; **seeded to `submitted_at` as a NOT-NULL placeholder** until a real start overwrites it | `recordJobStarted` / `promoteToStarted` |
| `completed_at` | terminal time, null while live | `recordJobCompleted` / `cancelIfInFlight` |
| `wall_clock_ms` | `completed_at − started_at`, computed in Java | `recordJobCompleted` |
| `duration_ms`, `duration_api_ms` | runner-reported durations | completion |
| `cost_usd` | total job cost (USD) | completion |
| `num_turns` | agentic turns | completion |
| `session_id`, `exit_code`, `subtype`, `session_error`, `permission_denials` | session diagnostics | completion |
| `target_branch`, `commit_hash`, `pull_request_url` | git outcome | completion |
| `error_message`, `blocked_category`, `blocked_detail`, `blocked_verified` | failure / block detail | completion |
| `trigger_reason` DEFAULT `'manual'` | `manual` / `auto_resolve` / `completion_listener` | `setTriggerReason`, fan-out |
| `heartbeat_at` | last liveness beat | `recordHeartbeat` |
| `phase` | last lifecycle phase the harness reported | `recordPhase` |
| `submitted_at`, `submitted_by` | controller-accept time and submitter | `recordJobSubmitted` |
| `required_labels` | node-routing labels, serialized `k=v;k=v` | `recordJobSubmitted` |
| `node` | worker identity, learned from a heartbeat or a start event | heartbeat / start |
| `slack_message_ts` | Slack thread ts | `updateJobSlackTs` |

Index: `idx_timing_ws_started (workstream_id, started_at)`.

### 1.2 `job_runner_cost` and `job_model_cost`

`CREATE_RUNNER_COST_TABLE` and `CREATE_MODEL_COST_TABLE`:
`(job_id, runner|model, cost_usd)`, one row per key, replaced idempotently on
each completion (`recordCostsInTable`). Populated from
`JobCompletionEvent.getCostByRunner()` / `getCostByModel()` (in
`recordJobCompleted(workstreamId, event)`), which snapshot the job's
`JobCostTracker`.

### 1.3 The derived / completion event

`JobCompletionEvent` (`io.flowtree.jobs.JobCompletionEvent`) and its coding
subclass `CodingAgentJobEvent` carry: `totalCostUsd`, `costByRunner`,
`costByModel`, `costIncomplete`, `durationMs`, `durationApiMs`, `costUsd`,
`numTurns`. `getActiveJobs`/`getPendingJobs`/`getJobIntervals`/`getRecentJobs`
read these rows back.

### 1.4 Derived states (SUBMITTED, LOST, RUNNING, UNRESPONSIVE)

Persisted `Status` enum (`JobCompletionEvent.Status`): `SUBMITTED`, `STARTED`,
`SUCCESS`, `FAILED`, `CANCELLED`, `DEGRADED`, `BLOCKED`. **`LOST`,
`UNRESPONSIVE`, `RUNNING`, `DEAD` are not enum values** — they are labels
computed at read time:

- **LOST** — a `SUBMITTED` row with null `completed_at` whose age past
  `submitted_at` exceeds `PendingJob.DEFAULT_LOST_THRESHOLD = 3h`
  (`PendingJob.isLost(threshold, now)` / `derivedState`). Never persisted; the
  row stays `SUBMITTED`. `LostJobRecovery` scans (5-min interval) to escalate.
- **RUNNING vs UNRESPONSIVE** — a `STARTED` row; `UNRESPONSIVE` when
  `now − max(heartbeat_at, started_at)` exceeds the silence threshold
  (`ActiveJob.isStale`; `StatsQueryHandler.DEFAULT_SILENT_THRESHOLD = 15m`,
  advisory only). Never persisted.
- **DEAD** — internal to `StuckJobScanner` (`DEFAULT_DEAD_THRESHOLD = 3h`,
  staleness = `wallClockCeiling × stalenessMultiplier`): on crossing it *emits*
  `JobCompletionEvent.failed(...)`, so the row becomes **`FAILED`**, not `DEAD`.

The controller returns the derived label in the `state` field of the
active/pending job JSON; the Python client prefers that field and only falls
back to its own `_STALLED_WARNING_SECONDS` for an older controller
(`workstream_tools.py:_is_unresponsive`).

### 1.5 Heartbeats, nodes/labels, delay/scheduled start

- **Heartbeats**: `recordHeartbeat(jobId, at, node)` writes `heartbeat_at`
  (and promotes a still-`SUBMITTED` row to `STARTED` via `promoteToStarted`).
- **Node registry / labels**: live only in memory — `NodeGroup.nodes()` /
  `getServers()`, each `Node.labels` (`Map<String,String>`), matched by
  `NodeLabelMatcher.satisfies`, chosen by `NodeGroupMetrics.findNodeForJob`.
  `GET /api/agents` (`AgentsQueryHandler`) serializes connected nodes with
  labels + busy state. The controller otherwise never sees a worker's labels;
  the job self-reports them via `AutomaticLabel` (platform, hostname). A job's
  own `required_labels` is persisted; the *node's* labels are not.
- **Delay / scheduled start**: `delaySeconds` → `DelayedJobQueue` (an in-memory
  `ScheduledExecutorService`). The job is accepted (row written `SUBMITTED`)
  but not dispatched until the delay elapses; `isPending(taskId)` reports it.
  **There is no persisted scheduled-start column** — the only durable time on a
  delayed job is `submitted_at`. This matters for the backlog (§3.1).

### 1.6 Tokens — the hard gap

**Token counts are not persisted anywhere, for either runner.** There is no
token column on `job_timing` and no token field on `JobCompletionEvent` /
`CodingAgentJobEvent`. `OpencodeOutputParser` *does* parse
`input_tokens` / `output_tokens` / `cache_read_tokens` / `cache_write_tokens`,
but only into a free-form `runnerMetadata` map that never reaches
`AgentRunResult`, the event, or the database. `ClaudeCodeRunner` does not
extract tokens at all (it reads `total_cost_usd`, `num_turns`, `duration_ms`,
`duration_api_ms` only). So:

> **Tokens are unavailable for every job recorded to date, and for new jobs
> until the capture path in §5 Stage 0 lands.** The retrospective's token
> measure will report "not captured" for all history, and the forecast cannot
> project tokens until there is a token history to fit.

Other known gaps: **no persisted job kind** (submission `job_type` is only
`coding` | `shell`; CI-review / planning / QA are not first-class — §2.3), and
`duration_api_ms` is always `0` for opencode.

### 1.7 Existing query surface

- `WeeklyStatsAggregator`: counts by SUCCESS/FAILED/CANCELLED, sums
  of `wall_clock_ms`/`duration_ms`/`cost_usd`/`num_turns`, filtered
  `status NOT IN ('STARTED','SUBMITTED')`, `GROUP BY workstream_id`; joins the
  two cost tables for by-runner / by-model breakdowns.
- `DailyAgentTime`: per-day `wallClockMs` / `runningMs` / `jobCount` / `costUsd`,
  splitting running jobs at local midnight and stopping accrual at the silence
  threshold.
- Endpoints (`FlowTreeApiEndpoint`): `GET /api/stats`, `/api/stats/daily`,
  `/api/workstreams/{id}/jobs/active|pending`, `/api/workstreams/{id}/jobs`,
  `/api/jobs/{id}`, `/api/jobs/in-flight`, `POST /api/jobs/{id}/heartbeat`.

---

## 2. Retrospective query design

### 2.1 Request shape

An arbitrary half-open window `[start, end)`, with presets resolved to it:
`1h`, `3h`, `6h`, `24h`, `7d` → `[now − d, now)`. One request record
(new `io.flowtree.controller.WindowQuery`): `from`, `to`, optional filters
(`workstreamId`, `repo`, `runner`, `model`, `kind`, `nodeLabel`), and the
`silentThreshold` already used by `DailyAgentTime`.

### 2.2 Measures and breakdowns

A new collaborator **`WindowStatsAggregator`** (controller, modelled exactly on
`WeeklyStatsAggregator` — reads through the live connection, recreated on open)
computes, over `[start, end)`:

- **Jobs by outcome**: submitted, started, finished, and SUCCESS / FAILED /
  DEGRADED / CANCELLED / BLOCKED / LOST (LOST derived per §2.4).
- **Tokens**: input / output / cache — **reported as `null`/"not captured"
  until §5 Stage 0**, then summed from the new token table.
- **Spend**: total, by runner (`job_runner_cost`), by model (`job_model_cost`).
- **Agent wall-clock**: summed overlap of each job's `[started_at, completed_at)`
  with the window (§2.4), not raw `wall_clock_ms`.
- **Node busy time vs available time**: busy = summed job overlap grouped by
  `node` and by node label; available = window length × node-count. Because the
  node registry is in-memory and the only durable per-job node attribution is
  `job_timing.node`, *historical* node-busy is computed from `node`; *available*
  is approximated from the live roster (§3.2) and flagged approximate for past
  windows (open question §6.4).

Breakdowns (each a `GROUP BY` or an in-memory fold over the window rows):
repo (derived from workstream → repo mapping the controller already holds),
workstream, job kind (§2.3), runner, model, node label.

The aggregator returns a `WindowStats` value object (new) with nested
breakdown maps; it is deliberately a sibling of `WeeklyStats`, not a
generalization of it, to avoid destabilizing the Slack `/flowtree stats` path.

### 2.3 Job kind

No kind is persisted today. The plan adds a first-class `job_kind` via a
deterministic classifier `JobKind.classify(trigger_reason, phase, factoryType,
command)`:

- `SHELL` — `ShellCommandJob` (`jobType=shell`).
- `CI_REVIEW` — `trigger_reason = auto_resolve`, or a job whose phase is a
  review/enforcement phase.
- `PLANNING` — a coding job flagged plan-only (today only inferrable from the
  prompt; see §6.3 — recommend a thread-through flag).
- `QA` — retrospective / falsification / QA enforcement phases.
- `CODING` — everything else.

Per `flowtree/CLAUDE.md` ("the job is the source of truth"), the durable fix is
a `job_kind` column on `job_timing`, written at submit time from a `kind`
property threaded through `CodingAgentJobFactory`/`CodingAgentJob`/codec. For
**history before this change**, kind is back-derived from
`trigger_reason` + `phase` + factory where possible and reported as
`UNKNOWN` where not — the retrospective must label that bucket honestly.

### 2.4 Counting rules (the subtle part)

- **Straddling the boundary**: a job's *outcome, spend, tokens, turns* are
  counted in the window that contains its `completed_at`. Its *time* measures
  (wall-clock, node-busy) are counted as the **overlap** of
  `[started_at, min(completed_at, now)]` with `[start, end)` — so a job running
  across the boundary contributes only the slice inside the window. This mirrors
  `DailyAgentTime`'s midnight split and is the only rule under which per-window
  busy time sums back to total busy time. The existing
  `getJobIntervals(from, to)` already selects "started before `to`, not
  completed before `from`" — the aggregator reuses it.
- **Running jobs** (no `completed_at`): contribute time overlap up to
  `min(now, end)`, but stop accruing at `max(heartbeat_at, started_at) +
  silentThreshold` when `UNRESPONSIVE` (same cutoff as `DailyAgentTime`). They
  are counted in "started" and "running", never in a terminal outcome.
- **LOST**: a `SUBMITTED` row, null `completed_at`, age past `submitted_at`
  exceeding the lost threshold, with `submitted_at ∈ [start, end)`. Counted as
  a distinct `lost` outcome and **excluded from spend/time** (it never ran).
  Jobs still `SUBMITTED` but younger than the threshold are counted as
  `submitted` only.
- **UNRESPONSIVE**: a `STARTED` row stale past the silence threshold. Counted
  under "started"/"running" with its busy time truncated at last heartbeat;
  surfaced as a separate `unresponsive` count so it is visible, not hidden.
- **Missing history**: for any window before §5 Stage 0, tokens are `null` and
  job-kind may be `UNKNOWN`; the response carries an explicit
  `coverage: {tokens: "unavailable_before_<date>", kind: "partial"}` block so
  the number is never silently wrong.

---

## 3. Forecast design

One paragraph, as required: **We take the real backlog (queued jobs that are
not LOST, each placed at the later of now and its scheduled start), draw a
duration and a cost from the empirical distribution for that job's kind and the
pool that can run it, and lay the draws onto each pool's available workers by
earliest-free-slot; a finished coding job spawns follow-on CI-review/settled-CI
jobs at an empirically measured rate, which are themselves scheduled the same
way. Repeating the draw many times (a few hundred Monte-Carlo trials over the
horizon) gives a distribution of finished jobs, spend and tokens, and the clock
time each pool first runs dry; we report p10/p50/p90 and the time-to-idle, and
print the trailing window of equal length beside it.**

### 3.1 Backlog

`BacklogSnapshot` (new): the set of real pending work.
- Source: `getPendingJobs(null)` (the `SUBMITTED`, null-`completed_at` rows).
- **Exclude LOST** (age past lost threshold) — they will never run.
- **Place delayed jobs at their start time**: today only `submitted_at` is
  durable and `DelayedJobQueue` is in-memory, so the honest input is
  `effectiveStart = max(now, submitted_at + delaySeconds)` — which requires
  persisting `delaySeconds`/scheduled-start (§5 Stage 0, §6.1). Until then,
  delayed jobs are placed at `now` and the limitation is logged.

### 3.2 Capacity pools

`CapacityPool` (new): a pool is a predicate over node labels (e.g.
`platform=macos`, or "any" = no requirement). Live availability from
`NodeGroup.getServers()` / `Node.labels` + busy state (same data as
`/api/agents`). Each pool knows its worker count and currently-free count. A
job joins the pools whose label predicate its `required_labels` select (reusing
`NodeLabelMatcher.satisfies`). The macOS-vs-Linux split the task names falls out
of this directly.

### 3.3 Historical distributions

`DurationCostDistribution` (new): per `(kind, pool)`, the empirical
distribution of `wall_clock_ms` and `cost_usd` over a trailing training window
(default 7–14 days) of finished jobs. Stored as sorted samples; we sample by
drawing a random order statistic (no parametric assumption). Tokens get the
same treatment once captured. Sparse buckets fall back to the parent
(`(kind, any)`, then global) and the fallback is reported.

### 3.4 Follow-on rate

`FollowOnRate` (new): empirically, `P(a finished CODING job in a repo is
followed by a CI_REVIEW job)` and the mean count of settled-CI follow-ons,
measured from `trigger_reason` + the completion-listener fan-out history
(`CompletionListenerFanout` sets `trigger_reason=completion_listener`). A drawn
coding completion spawns follow-on jobs with that probability/count, scheduled
onto the pools like any other.

### 3.5 Output and self-check

- **Output**: for the horizon (1h/3h/6h/24h or custom), p10/p50/p90 of finished
  jobs, spend, tokens (when available); per pool, the projected instant it first
  goes idle (empty backlog + no running job), or "busy through end of horizon".
  Plus the trailing equal-length window's *actual* totals, side by side — which
  is exactly question 3 ("more next day than last") and question 4 ("busy all
  night").
- **Accuracy check**: each forecast is written to a new `forecast_snapshot`
  table (horizon, p10/p50/p90 per measure, made-at). A scheduled scorer
  re-runs the §2 retrospective for that elapsed window and records whether the
  actual landed inside p10–p90 (coverage) and the p50 error. Over time this
  calibrates the model and is itself queryable. This is the honest answer to
  "how will we know it was right."

Simplicity guardrails: no parametric fitting, no per-job ML; the model is
"draw from history, pack onto pools, add follow-ons." It is explainable and its
error is measured, not asserted.

---

## 4. Surface

### 4.1 Controller endpoints

New handler class **`ThroughputQueryHandler`** (`io.flowtree.api`, new file —
keeps logic out of the capped `FlowTreeApiEndpoint`), serving:

- `GET /api/throughput/window?from=&to=` (or `?preset=3h`) → `WindowStats` JSON.
- `GET /api/throughput/forecast?horizon=24h` → forecast JSON (ranges, per-pool
  time-to-idle, trailing comparison).
- `GET /api/throughput/pools` → live pools with worker/free counts and labels.

`JobQueryHandler`/`StatsQueryHandler` are the siblings this joins; it reads
`JobStatsStore` (new aggregators) and `NodeGroup` (live roster).

### 4.2 ar-manager tools (one call each → one question)

New Python file `tools/mcp/manager/throughput_tools.py` (keeps the bulk out of
the near-cap `server.py`):

- `throughput_window(preset|start,end, breakdown=...)` → Q1 (last N / custom).
- `throughput_forecast(horizon)` → Q2 (next N), returns ranges + time-to-idle.
- `throughput_compare_next_vs_last(horizon)` → Q3 ("more next day than last"):
  forecast horizon beside the trailing actual, with a plain verdict.
- `throughput_idle_forecast()` → Q4 ("busy all night"): per-pool projected idle
  time, worst-case and p50.

Each wraps a single controller call. Registered in `server.py` by importing the
module and calling one `register(mcp, ...)` function — one import line, not new
inline tool bodies, so `server.py` does not grow materially.

### 4.3 Fix `workstream_list` status

`workstream_list` must show `LOST`/`UNRESPONSIVE` instead of stale
`SUBMITTED`/`STARTED`. `workstream_get_status` already reads the controller's
derived `state`; the fix is to (a) have `workstream_list`'s per-workstream
enrichment fold in the active/pending `state` labels (count of LOST /
UNRESPONSIVE), and (b) ensure the controller stamps `state` on every
job-listing payload, not only the active/pending endpoints. This is a bug fix
within the existing derive-at-read design — no new persisted status.

### 4.4 Respecting the 1600-line cap

`server.py` and `FlowTreeController.java` sit within a handful of lines of the
1600-line cap; `FlowTreeApiEndpoint.java` has a little more room than those two
but is itself close to the cap and well over the ~1500-line recommendation —
`flowtree/CLAUDE.md` lists it by name among the files that "run close to the
1600-line Checkstyle `FileLength` limit." (Verify the current counts against the
files before acting — they move with every merge into this branch.) New behaviour goes
in the new classes/files above. The one unavoidable touch is route registration
in `FlowTreeApiEndpoint.serve()`, which is a few lines.

Two ways to make the room, for the owner to choose between (§6.7):

- **Minimal touch.** Add the three route branches directly to
  `FlowTreeApiEndpoint.serve()` in the room that remains. Smallest diff, no
  cross-cutting change, but leaves the file nearly at the cap.
- **Extract-first.** **Stage 1 extracts the route dispatch into an `ApiRouter`**
  (a mechanical move of the existing `handleXxx` wiring) before adding the new
  routes, giving a clean home for them and pulling `FlowTreeApiEndpoint` well
  under the recommended 1500. `flowtree/CLAUDE.md` ("the refactoring is part of
  the scope of whatever change needed the room") permits folding such a refactor
  into the change that needs the room, and names `FlowTreeApiEndpoint` among the
  files running close to the limit. The minimal-touch route can still fit the
  three new route branches, but it does so by consuming nearly all the remaining
  room and leaves the file over the ~1500-line recommendation — so the extraction
  is closer to the room-making refactor `flowtree/CLAUDE.md` anticipates than to
  an optional cleanup. Against that, it is behaviour-preserving and cross-cutting
  rather than part of the throughput feature itself, which is the cost of
  coupling it to this branch. That is the trade the owner should weigh (§6.7).

---

## 5. Staged implementation

Each stage compiles, is independently reviewable, and ships its own tests. The
harness commits the whole branch once; the stages describe logical phases.

**Stage 0 — close the data gaps (schema + capture).**
- Add `job_token` table `(job_id, kind, input_tokens, output_tokens,
  cache_read_tokens, cache_write_tokens)` and a `job_kind` column + persisted
  `delay_seconds`/scheduled-start on `job_timing` (all via `SCHEMA_MIGRATIONS`,
  the existing additive-ALTER pattern).
- Thread tokens through: `ClaudeCodeRunner` reads `usage.*` from the result
  JSON; `OpencodeRunner` promotes its already-parsed `runnerMetadata` tokens
  into `AgentRunResult` → `JobCompletionEvent` → `recordJobCompleted`. Thread a
  `kind` property through `CodingAgentJobFactory`/`CodingAgentJob`/codec.
- *Tests*: `GitManagedJobSerializationTest.roundTripFactory` asserts `kind` and
  token fields survive the wire; `JobStatsStoreTest` asserts tokens/kind persist
  and read back; a runner-parser test for each runner's token extraction.
- *Note*: this stage does nothing for historical rows — history stays
  token-less and kind-`UNKNOWN`, by design.

**Stage 1 — route extraction + window aggregator.**
- Extract `ApiRouter` from `FlowTreeApiEndpoint` (§4.4); behaviour-preserving.
- Add `WindowQuery`, `WindowStats`, `WindowStatsAggregator`, `JobKind`.
- Wire `GET /api/throughput/window` + `/pools` via `ThroughputQueryHandler`.
- *Tests*: `WindowStatsAggregatorTest` with seeded `job_timing` rows covering
  each counting rule in §2.4 (straddle, running, LOST, UNRESPONSIVE, missing
  tokens); `ApiRouterTest` asserting every existing route still resolves.

**Stage 2 — forecast.**
- Add `BacklogSnapshot`, `CapacityPool`, `DurationCostDistribution`,
  `FollowOnRate`, the Monte-Carlo `ThroughputForecast`, and
  `forecast_snapshot` persistence.
- Wire `GET /api/throughput/forecast`.
- *Tests*: deterministic forecast test with a fixed RNG seed and a hand-built
  history → asserted p10/p50/p90 and per-pool idle time; a follow-on-rate test;
  an empty-backlog test (idle now).

**Stage 3 — ar-manager tools + list fix.**
- Add `throughput_tools.py` (four tools, §4.2); fix `workstream_list` (§4.3).
- *Tests*: `tools/mcp/manager/test_throughput_tools.py` (one node id per tool,
  mocking the controller HTTP); a `workstream_list` test asserting LOST/
  UNRESPONSIVE surface instead of stale SUBMITTED/STARTED.

**Stage 4 — reconciliation test (the "done when").**
- A test that, over a seeded sample window, computes the §2 aggregate and
  **independently re-derives** every measure by iterating the per-job records
  (`getRecentJobs` / `getJob`) and asserts they match within rounding — the
  task's "numbers must reconcile with per-job records." Include the forecast
  scorer's coverage check against a replayed window.

---

## 6. Open questions for the owner

1. **Persist scheduled start?** The backlog's "delayed jobs at their start time"
   needs a durable scheduled-start; `DelayedJobQueue` is in-memory today
   (§1.5/§3.1). Persist `delay_seconds` + computed `scheduled_start` on
   `job_timing` (recommended), or accept that delayed jobs are placed at `now`?
2. **Tokens for history.** They cannot be recovered (§1.6). Confirm the
   retrospective should report "unavailable before `<Stage-0 date>`" rather than
   estimating tokens from cost.
3. **Planning-kind marker.** `PLANNING` is only inferrable from prompt text
   today. Add a first-class plan-only job flag threaded through the job
   (recommended, matches `flowtree/CLAUDE.md`), or classify by submitter
   convention?
4. **Node available-time for past windows.** The node roster is in-memory; past
   availability is not recorded. Approximate from the current roster (flagged
   approximate), or add a node-presence log (new table) so historical
   utilisation is exact?
5. **Repo attribution.** Confirm the workstream→repo mapping the controller
   holds is complete enough to group by repo, or should `repo` be persisted on
   `job_timing` at submit time?
6. **Forecast horizon cost.** A few hundred Monte-Carlo trials per request is
   cheap, but confirm the forecast endpoint may be computed on demand rather
   than precomputed on a timer.
7. **`ApiRouter` extraction on this branch?** §4.4 offers two ways to make room
   for the three new routes. The extract-first option couples a behaviour-
   preserving but cross-cutting refactor of `FlowTreeApiEndpoint` to this
   feature branch. `FlowTreeApiEndpoint` still runs close to the cap and over the
   ~1500-line recommendation — `flowtree/CLAUDE.md` lists it among the files near
   the limit — so the minimal-touch route fits the three new branches only by
   consuming nearly all the remaining room; the extraction is the room-making
   refactor that guidance anticipates rather than a pure cleanliness choice. Fold
   it into this branch, do it first as a standalone refactor change, or skip it
   and take the minimal-touch route? This is the one place the plan steps beyond
   purely additive new classes.

---

## 7. Files this plan would add or touch

**New Java (`flowtree/runtime`):** `WindowQuery`, `WindowStats`,
`WindowStatsAggregator`, `JobKind`, `BacklogSnapshot`, `CapacityPool`,
`DurationCostDistribution`, `FollowOnRate`, `ThroughputForecast`,
`ThroughputQueryHandler`, `ApiRouter` (extracted). **New Python:**
`tools/mcp/manager/throughput_tools.py`, `test_throughput_tools.py`.
**Touched (minimally):** `JobStatsStore` (new aggregator wiring + Stage-0
columns/tables), `FlowTreeApiEndpoint` (route extraction only), the two runners
and `CodingAgentJob*`/codec (Stage-0 token+kind thread-through), `server.py`
(one import+register line), `workstream_tools.py` (list fix). **No new Maven
module.**
