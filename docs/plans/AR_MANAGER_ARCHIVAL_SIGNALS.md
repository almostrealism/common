# ar-manager: Workstream Lifecycle Signals for Archival Assessment

Status: proposed. Investigation only; no production changes yet.
Owner: TBD.
Scope: ar-manager MCP server (`tools/mcp/manager`) and FlowTree controller
(`flowtree/runtime/src/main/java/io/flowtree/{api,controller,jobs,slack,workstream}`).

## Motivation

On 2026-09-01 an operator asked an agent to classify ~30 live workstreams as
completed or active so stale ones could be archived. The agent needed ~35 tool
calls to reach a confident answer, and the two fields that should have settled
most of the question — `lastJobAt`, `lastJobStatus` — were wrong or
misleading. This plan fixes the underlying data defects and adds the small
number of fields that would let `workstream_list` answer "is this workstream
done?" in a single call.

The motivating call shape is therefore:

```text
workstream_list(include_status=True, include_pr_state=True,
                lifecycle="merged", idle_days=30)
```

which today cannot be expressed; the closest analogue, `workstream_list(
include_status=True)`, returns per-row `lastJobAt` / `lastJobStatus` whose
timestamps do not correspond to the underlying events (Finding 1).

## Findings (defects and gaps observed)

### 1. Job timestamps are stamped at read time, not event time

**Code path.** `JobCompletionEvent` is constructed in two places:

- `flowtree/runtime/src/main/java/io/flowtree/api/FlowTreeApiEndpoint.java:1079`
  (and 1086) constructs a fresh event when the agent POSTs a status event.
- `flowtree/runtime/src/main/java/io/flowtree/controller/JobStatsStore.java:644-652`
  constructs a fresh event when a job row is read back from `job_timing`.

Both call the `JobCompletionEvent` constructor at
`flowtree/runtime/src/main/java/io/flowtree/jobs/JobCompletionEvent.java:132-139`,
which sets `this.timestamp = Instant.now()`. The event is therefore stamped
at **the moment the controller instantiates it**, not at the moment the agent
emitted it nor at the moment the row was inserted into HSQLDB.

The persisted `job_timing.started_at` and `job_timing.completed_at` *are* real
event times — `recordJobStarted` writes the timestamp the agent passed and
`recordJobCompleted` writes `event.getTimestamp()` to `completed_at`. But
`WorkstreamListing.statusFields` reads the `JobCompletionEvent.timestamp` (not
the row's `started_at`/`completed_at`) and emits it as `lastJobAt`:

`flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamListing.java:163-175`

So `lastJobAt` is always `Instant.now()` at the moment the listing request is
served. Two listings issued seconds apart on the same workstream return
different `lastJobAt` values, and the per-row values for 30 workstreams served
in one listing all land in the same wall-clock second because they are stamped
in the same `toJson` invocation. That is what produced the observed
"returned the same second for all 30 workstreams" symptom, and it is what
made `workstream_get_job().timestamp` for a job whose PR merged on 2026-05-29
return the second of the read rather than any 2026-05-29 timestamp.

### 2. `lastJobStatus` is misleading for lifecycle questions

`WorkstreamListing.statusFields` emits `lastJobStatus` from the most recent
job in `JobStatsStore.getRecentJobs(workstreamId, 20)` — see
`WorkstreamListing.java:163-175`. A wake-up or post-completion job that ran
*after* the work's PR merged can move the row's status to `FAILED` /
`CANCELLED` / `DEGRADED` while the underlying work has already landed. Two
fields whose truth does not include "the work merged", combined with the
absence of a "last merged PR" field (Finding 3), force every archival scan
back into a per-workstream `workstream_get_status` / PR lookup to settle what
should be a one-call question.

### 3. `workstream_list.pullRequest` only reflects PRs a job recorded

`WorkstreamListing.statusFields` builds `pullRequest` by scanning the same
20-deep job history for a non-null `pullRequestUrl`:
`WorkstreamListing.java:176-186`. A workstream whose PR merged before the
controller recorded a job with a non-null `pullRequestUrl` field — or whose PR
was opened and merged entirely on the GitHub side without that field being
populated by the agent — appears in `workstream_list` with no `pullRequest`
entry at all, even when `github_pr_find(state="merged")` finds the PR. Four
of the 30 workstreams scanned on 2026-09-01 fell into this bucket.

The richer per-branch GitHub lookup that would have caught them
(`_find_recent_pr_by_branch`, `tools/mcp/manager/server.py:1019`) already
exists; `workstream_list` does not use it. The narrative tool
`workstream_context` *does* use it — see
`tools/mcp/manager/workstream_tools.py:778-815` — which is why the same scan
returned PRs for the same workstreams when rephrased as
`workstream_context`.

### 4. Job description is empty on every job read

`workstream_submit_task(description=...)` is forwarded to the controller at
`tools/mcp/manager/workstream_submit_tools.py:496-497`, and the controller
extracts and applies it to the factory at
`flowtree/runtime/src/main/java/io/flowtree/api/FlowTreeApiEndpoint.java:679-702`.
The factory stores it on `CodingAgentJob.description`.

The agent process never propagates that description to its outbound
`JobCompletionEvent`. `GitManagedJob.createEvent` and
`CodingAgentJob.populateEventDetails` use `getTaskString()` (the prompt
itself) for every status event; the factory's `description` field is consumed
only by Slack notification helpers. The controller-side handler at
`FlowTreeApiEndpoint.java:1056` reads `description` from the inbound status
JSON, but since the agent never sends one, every persisted `job_timing`
`description` column is the prompt, not the submitted short label.

The result is that `workstream_get_job().description` is the prompt of the
job, not the human-readable label the operator submitted with — defeating
the only purpose of `description` in archival triage.

### 5. No way to distinguish standing workstreams from feature branches

`Workstream.toSummaryJson`
(`flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java:1003-1074`)
emits `archived`, `dispatchCapable`, `useTmux`, and the like, but nothing that
records whether a workstream is a long-lived orchestrator / master-targeting
inbox driver (`#eva-orchestrator`, `#remanence`) versus a single feature
branch. `dispatchCapable` is close but conflates "is allowed to register
children" with "lives forever" — a feature workstream that needs to register a
child workstream during CI looks identical to an orchestrator.

Without that distinction, every archival candidate list includes workstreams
that the operator will never want to archive, and the agent has to walk each
one and decide by hand from `channelName` / `branch` heuristics.

### 6. `workstream_get_status` is the only working idle signal — at one call per workstream

`workstream_get_status`
(`tools/mcp/manager/workstream_tools.py:144-214`) returns weekly aggregates
covering only the last two weeks — see `StatsQueryHandler.handle`,
`flowtree/runtime/src/main/java/io/flowtree/api/StatsQueryHandler.java:134-160`.
For an archival scan of 30 workstreams the operator (or agent) pays 30 calls
to learn what `workstream_list` could have returned in one. `period` is
rejected with a 400 for any value other than `"weekly"` at lines 177-186 of
`workstream_tools.py`, despite the agent's request being a 30-day window.

## Scope

Each scope item names the concrete files and methods involved, the new wire
shape, and the verification that proves it landed. The order at the bottom
follows the dependency graph — items that other items depend on come first.

### A. Fix event timestamps (correctness bug; ship first)

**Goal.** `lastJobAt`, `workstream_get_job().timestamp`, and any field that
reports when a job actually happened must reflect the persisted `started_at`
or `completed_at` from `job_timing`, not `Instant.now()` at read time.

**Concrete steps.**

1. `JobCompletionEvent.timestamp` remains `final` but is no longer the
   authoritative event time. Add a new field `eventTime` of type `Instant`
   that is set in the constructor (kept distinct from `timestamp` so existing
   tests of the in-memory-event flow keep working without modification). Both
   fields are stamped at construction for now; the read path will be fixed in
   step 2.
   `flowtree/runtime/src/main/java/io/flowtree/jobs/JobCompletionEvent.java`.

2. `JobStatsStore.rowToEvent`
   (`flowtree/runtime/src/main/java/io/flowtree/controller/JobStatsStore.java:627-667`)
   must read the row's `completed_at` (falling back to `started_at` for
   `STARTED` rows) and assign it to `event.timestamp` *and* `event.eventTime`
   before the event leaves the row constructor. The current code rebuilds a
   fresh event whose constructor fires `Instant.now()` and discards the
   persisted timestamp entirely — the fix is to overwrite both fields
   post-construction on the way out.

3. `WorkstreamListing.statusFields`
   (`flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamListing.java:163-175`)
   must read from the reconstructed event's persisted time, not the
   `Instant.now()` baked in by the constructor. Once step 2 lands, this is
   automatic — but the listing should additionally prefer `completed_at` over
   `started_at` when both are present, since "last completed job" is what an
   archival scan actually wants. `JobStatsStore.getRecentJobs` already orders
   by `COALESCE(completed_at, started_at) DESC` at line 581, so the "latest"
   semantics line up.

4. Add `lastJobStartedAt` and `lastJobFinishedAt` to `WorkstreamListing.statusFields`
   output. `lastJobAt` stays as the finished time for compatibility.

5. New test `WorkstreamListingTimestampTest`
   (`flowtree/runtime/src/test/java/io/flowtree/api/`) — submit a job whose
   status is `SUCCESS` and persists a row with `started_at = T0` and
   `completed_at = T1`; read it back via `SlackNotifier.getJob` and assert
   `event.getTimestamp()` equals `T1`, not "now". Then list the workstream
   twice, a few seconds apart, and assert the two `lastJobAt` values are
   byte-identical.

**Risks / open questions.**

- The constructor stamp at
  `JobCompletionEvent.java:136` is the only field used by a handful of tests
  to verify "the event was created". Tests that read `event.getTimestamp()`
  immediately after constructing without going through the DB will start
  failing once the field is sourced from the row. The reconciliation is
  fine for DB-backed reads but in-memory events from `SlackNotifier.trackJob`
  (`flowtree/runtime/src/main/java/io/flowtree/slack/SlackNotifier.java:879-891`)
  will still carry the controller-receive-time stamp. The two readers —
  `notifiers.findJob` and `JobStatsStore.getJob` — should agree on the same
  field, which means `trackJob` should also set `event.eventTime` /
  `event.timestamp` from the inbound `JobCompletionEvent` carried in (which
  will already have come from `GitManagedJob.postStatusEvent`'s JSON, which
  the agent constructed from the agent's own `Instant.now()`). This needs a
  decision: do we trust the agent's reported `timestamp` JSON field (round-trip
  it) or stamp at the controller receive boundary?

### B. Add PR lifecycle to `workstream_list`

**Goal.** A single `workstream_list` call tells the caller whether the
workstream's branch has an open / merged / closed-unmerged PR, when that PR's
state changed, and how many PRs the branch has had — without forcing a
separate `github_pr_find` per row.

**Concrete steps.**

1. Add `include_pr_state: bool = False` to `workstream_list`
   (`tools/mcp/manager/workstream_tools.py:35-43`). When `True`, the response
   payload adds `?includePullRequestState=true` to the controller query string
   at lines 104-118, and the response entries grow a `pullRequest` field with
   `state` (`open` / `closed` / `merged`), `merged` (bool), `mergedAt`, `closedAt`,
   `number`, and `url`.

2. In `WorkstreamListing.statusFields`
   (`flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamListing.java:156-188`),
   when the request carries `includePullRequestState=true`, do **two** reads:
   the existing 20-deep job scan (unchanged) and a single GitHub pull-request
   list call per distinct `(owner, repo)` for the surviving workstreams.
   Coalesce by `(owner, repo)` so a 30-row list that lives on two repositories
   is two GitHub calls, not 30.

3. The GitHub call should resolve the PR via `defaultBranch` and the GitHub
   default branch logic, mirroring
   `tools/mcp/manager/workstream_tools.py:778-815` rather than relying on a
   job-recorded URL. The `pullRequest` field emitted today is the
   *job-recorded* URL — keep that as `pullRequest` for compatibility and add
   `pullRequestState` for the GitHub-derived view, OR change the field
   contract: emit one `pullRequest` object with all the fields. The draft
   assumes the latter; the field renames are an open question for code review.

4. `prCount` (int): the number of PRs the branch has had across all states.
   Computed from the same `?head=owner:branch&state=all` listing.

5. Cache: store the per-`(owner, repo, branch)` PR lookup for a short TTL
   (suggested: 60 s). A subsequent `workstream_list` call within that window
   reads from the in-memory cache rather than GitHub.

**Verification.**

- Existing `WorkstreamListFilterTest`
  (`flowtree/runtime/src/test/java/io/flowtree/api/WorkstreamListFilterTest.java`)
  must continue to pass without modification (the existing fields remain
  backward-compatible).
- New test: a workstream whose `defaultBranch` has an open PR on GitHub but
  no job has reported `pullRequestUrl` → with `include_pr_state=True`, the
  listing entry carries `pullRequest` with the GitHub-derived values.

### C. Add a lifecycle classification field

**Goal.** A single `lifecycle` enum + `lifecycleReason` summary in each
`workstream_list` entry answers "is this workstream done?" without further
calls. The classifier runs only when `include_lifecycle: bool = True`, so the
cost is opt-in.

**Concrete steps.**

1. New param `include_lifecycle: bool = False`,
   `idle_days: int = 14`,
   `lifecycle: str = ""` (filter: `active` / `merged` / `abandoned` /
   `idle` / `standing` / `unknown`) on `workstream_list`. The filter is
   applied server-side so "show me archive candidates" is one call.
   `tools/mcp/manager/workstream_tools.py:35-43` (signature) and 104-118
   (params forwarded).

2. Controller side: `WorkstreamListing.toJson` accepts the new
   `includeLifecycle` and `idLeDays` parameters and applies the filter after
   all enrichment. Classification rules:
   - `standing` / `orchestrator` — see D; the classifier never overrides `kind`.
   - `active` — at least one job's persisted `completed_at` (or `started_at`
     for `STARTED` rows) is within `idle_days` of `now`, OR the workstream
     has an open PR on its `defaultBranch`.
   - `merged` — no job in `idle_days`, AND the most recent PR for
     `defaultBranch` is merged (see B's GitHub-derived state).
   - `abandoned` — no job in `idle_days`, AND the most recent PR is closed
     and not merged.
   - `idle` — no job in `idle_days`, AND no PR for `defaultBranch`.
   - `unknown` — repo/branch cannot be resolved to a GitHub repo, so PR state
     is uncategorisable.

3. `lifecycleReason` is a short string summarising the inputs, e.g.
   `"PR #411 merged 2026-08-23; no job since"`. The point of the field is to
   let an operator audit the classification without re-running it.

4. The classifier runs *after* the merge-sort-by-idle-days step; a
   `lifecycle="merged"` filter does not pay for `active` classification.

**Verification.**

- New `WorkstreamLifecycleClassificationTest`:
  - Workstream with merged PR, no recent job → `lifecycle="merged"`.
  - Workstream with no PR, no recent job → `lifecycle="idle"`.
  - Workstream with an open PR → `lifecycle="active"`.
  - Standing workstream (`kind="standing"`) → `lifecycle="standing"`,
    regardless of PR state.
  - `lifecycle=` filter applied to a 30-row list returns only matching rows
    and reports a `count` matching the filtered subset.

### D. Mark standing workstreams

**Goal.** Operators and agents can tell at a glance which workstreams should
never be archived, and the `lifecycle` classifier (C) defers to `kind`.

**Concrete steps.**

1. New workstream field `kind` with values `feature` (default),
   `orchestrator`, `standing`. Settable via `workstream_register(kind=...)`
   and `workstream_update_config(kind=...)`. Empty string leaves the existing
   value untouched (presence signal).
   `tools/mcp/manager/workstream_config_tools.py:43,355-380` — add to both
   signatures and the payload construction at lines 254-307 / 483-531.

2. Heuristic default at registration time (in
   `flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamRegistrationHandler.java`
   `registerWorkstream`): if the caller did not pass `kind`, infer
   - `orchestrator` when `defaultBranch == baseBranch` (the branch IS the
     base, so the workstream sits at the trunk rather than tracking a feature).
   - `standing` when `defaultBranch` starts with `orchestration/` (matches the
     branch-name convention referenced in
     `docs/plans/FLOWTREE_WORKSTREAM_JOB_CONTROLS.md`).
   - `feature` otherwise.
   An explicit `kind` always wins over the heuristic.

3. `Workstream.toSummaryJson` (lines 1003-1074) emits `kind` on every entry.
   Omitted when default (`feature`) is set and the caller didn't ask for it;
   always present when non-default. The other capability flags in this method
   follow the same omission rule.

4. In `WorkstreamListing.statusFields` / C's classifier, `standing` and
   `orchestrator` are terminal classifications — they never report `merged`,
   `abandoned`, or `idle`. Reporting them as `standing` short-circuits the
   classifier.

**Verification.**

- New test in `AgentsEndpointTest`
  (`flowtree/runtime/src/test/java/io/flowtree/api/AgentsEndpointTest.java`)
  — registering a workstream with `defaultBranch == baseBranch` and no
  explicit `kind` produces `kind="orchestrator"` in the listing response.
- `workstream_register(kind="standing")` followed by
  `workstream_list(include_lifecycle=True)` reports `lifecycle="standing"`
  for that entry even when an old PR is merged.

### E. Populate job descriptions

**Goal.** `workstream_get_job().description` (and the compact form in
`workstream_context.jobs[]`) carry the human-readable label the operator
submitted with, not the prompt.

**Concrete steps.**

1. The description forwarded by `workstream_submit_task` already reaches
   `CodingAgentJob.setDescription` via
   `FlowTreeApiEndpoint.handleSubmit` (lines 679-702). The wiring from
   `CodingAgentJob.description` to outbound status events is the missing
   piece. `GitManagedJob.createEvent`
   (`flowtree/runtime/src/main/java/io/flowtree/jobs/GitManagedJob.java:567-576`)
   and any equivalent in `CodingAgentJob.populateEventDetails` must set
   `event.description = factory.getDescription()` (truncated to
   `VARCHAR(1000)` to fit `job_timing.description`).
   When the factory description is `null` or empty, fall back to the
   current `getTaskString()` behaviour so no submitted job regresses.

2. Truncate at 200 characters in the agent before posting, so the persisted
   description stays well under the 1000-char column. The Slack notifier
   already truncates to 80 in `SlackNotifier.formatStartedMessage`, so
   truncation at the source is consistent.

3. `workstream_get_job` and `workstream_context` already return
   `description` from the reconstructed event — they need no changes once
   the agent propagates it.

**Verification.**

- New test `WorkstreamJobDescriptionPropagationTest`: submit
  `workstream_submit_task(prompt="...", description="short label")`, drive
  the job to a `SUCCESS` event, then read the event back through
  `workstream_get_job(job_id)` and assert `description == "short label"`
  (or its 200-char truncation).

### F. Extend `workstream_get_status`

**Goal.** A 30-day idle window is answerable in one call rather than 30.
Two viable shapes:

1. Accept `period="30d"` / `"90d"` in addition to `"weekly"`. The
   `StatsQueryHandler` is the only path; the controller currently rejects
   non-weekly periods at
   `flowtree/runtime/src/main/java/io/flowtree/api/StatsQueryHandler.java:141-143`
   and the manager side rejects at
   `tools/mcp/manager/workstream_tools.py:177-186`.

2. Once A lands, add a top-level `lastJobAt` (already present in
   `include_status=True`) plus `lastJobStartedAt` / `lastJobFinishedAt` to
   the `workstream_get_status` response so a caller can compute its own
   idle window without needing an additional DB query.

**Recommendation.** Prefer (2) — it costs nothing in the stats query and
the new fields already exist on `JobCompletionEvent`. (1) is the fallback
if A is delayed; either alone suffices, but (2) only works once A ships,
which is why the order below keeps A first.

**Verification.**

- `workstream_get_status(workstream_id=..., period="weekly")` continues to
  return weekly aggregates (no regression).
- After A lands, the response carries `lastJobAt` matching the persisted
  `completed_at`, not `Instant.now()`.

## Non-goals

- **Automatic archival.** This plan makes archival *assessable*; the decision
  stays with an operator or with an explicitly instructed agent. The
  `workstream_archive` / `workstream_archive_many` tools already exist and
  are unchanged.
- **Changing Slack archival behaviour.** `workstream_archive`'s
  `archive_slack_channel` flag and the broader Slack-side archival flow are
  untouched.
- **Migrating existing `job_timing` rows.** Pre-fix rows have
  `started_at` / `completed_at` populated but `JobCompletionEvent.timestamp`
  was never persisted. The fix only applies to reads going forward;
  historical rows already have the right timestamps in the columns that
  matter (A only changes the read path, not the schema).

## Acceptance criteria

- `workstream_list(include_status=True)` returns distinct, stable `lastJobAt`
  values that match the underlying `job_timing.completed_at` rows. Reading
  the same listing twice in a row returns byte-identical timestamps.
- `workstream_list(include_status=True, include_lifecycle=True,
  lifecycle="merged")` returns only the workstreams whose branch has a
  merged PR and no job in the last 30 days, with one GitHub API call per
  distinct repository.
- `workstream_list` shows `kind` for every entry. `#eva-orchestrator` and
  `#remanence` report `standing` or `orchestrator` without manual edits to
  existing workstream rows — at minimum, the heuristic infers them at the
  time the workstream is re-registered; legacy rows are migrated by an
  operator run of `workstream_update_config` if necessary.
- `workstream_get_job(job_id)` returns a non-empty `description` for a job
  submitted with `description=...`. The description matches the operator's
  input modulo a 200-character truncation.
- `workstream_context(workstream_id=...)` shows the same description in the
  compact `jobs[]` timeline.
- Existing callers of `workstream_list` without any new flag see no change
  in response shape beyond additive fields (`lastJobStartedAt`,
  `lastJobFinishedAt`, `kind`, etc.).

## Suggested order

**A, D, B, C, E, F.** A is a correctness bug and ships alone first. D is
small and unblocks C's standing-workstream short-circuit. B brings in the
PR data that C classifies over. C is the read-side product. E is a separate,
isolated change to the agent-to-controller wire shape. F is a thin extension
once A has landed.

The dependencies between scopes:

- C depends on B (PR state) and D (kind).
- B depends on nothing.
- F depends on A (the read-time stamp fix is what makes the new
  `lastJobAt` trustworthy).
- E is independent.

## Risks and open questions

- **Read-time vs event-time for in-memory events.** The proposed fix in A
  reconciles the DB-backed read path, but `SlackNotifier.trackJob` keeps an
  in-memory `JobCompletionEvent` whose `timestamp` is controller-receive-time.
  When `notifiers.findJob` consults the in-memory cache before the DB (it
  doesn't today — `SlackNotifier.getJob` consults the DB first at lines
  918-925 — but the in-memory path remains a fallback when the store is
  unavailable), the in-memory event will keep its old stamp. Decision
  needed: should `trackJob` set `event.eventTime` from the inbound
  `JobCompletionEvent.timestamp` JSON field, which round-trips the
  agent's emit-time, or trust the controller-receive-time?

- **Backfill of `kind` for existing workstreams.** The heuristic in D runs
  at registration time. Existing workstreams keep their absent `kind` (which
  the classifier treats as `feature`) until an operator re-registers or
  updates them. The 30-workstream scan on 2026-09-01 would not have benefited
  from the heuristic alone — the orchestrator and standing workstreams were
  known well in advance, and a one-time `workstream_update_config(kind=...)`
  pass is acceptable.

- **Description length budget.** `job_timing.description` is
  `VARCHAR(1000)`; truncating at 200 in the agent keeps headroom for the
  operator's full label and prevents accidental oversize payloads. The
  truncation is silent — if an operator's label is longer than 200 chars,
  the suffix is dropped without notice. Either widen the budget to 500 or
  surface the truncation in the response are alternatives; the smaller
  change is the silent 200-char cap.

- **`pullRequest` field collision in B.** Today the field is sourced from
  the most recent job that recorded a URL. Switching it to be GitHub-derived
  changes the value some callers see for workstreams whose job-recorded URL
  points at a stale or wrong PR. The safer shape is to add a new
  `pullRequestState` field and leave the existing `pullRequest` alone,
  deprecating it later. Decision needed before B lands.

- **GitHub rate-limit cost of B.** A 30-row list that crosses N repositories
  is N GitHub `/pulls?head=...&state=all` calls. The TTL in step 5 of B
  keeps repeated scans cheap but the first scan after the cache expires is
  the cost we pay. Reasonable to accept; a counter on
  `workstream_list(include_pr_state=True)` response surfaces when the cache
  misses.

- **Heuristic for `kind=orchestrator` in D.** `defaultBranch == baseBranch`
  is a reasonable proxy but not authoritative — a feature workstream that
  branched from `master` and is committing to `master` for some reason (rare
  but possible) would be misclassified. The always-wins-explicit-value rule
  makes this a self-correcting problem for anyone who notices, but the plan
  should call out the assumption.
