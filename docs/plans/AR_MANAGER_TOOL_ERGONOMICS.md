# ar-manager Tool Ergonomics and Observability

Status: **IN PROGRESS — five of ten done (2, 3, 7, 8, 9); items 1, 4, 5, 6, 10 outstanding**
Author: planning session, 2026-08-12; triaged 2026-08-21

> **Line numbers in this document have drifted.** It was written against a
> tree that predates the ar-manager consolidation, which added roughly 700
> lines to `tools/mcp/manager/server.py`. Every reference into that file is
> now 40–400 lines low — `workstream_list` 365→403, `workstream_context`
> 2936→3057, `github_pr_find` 3680→4084, `workstream_register` 1214→1267,
> `workstream_archive` 2014→2067. References into `workspace_map.py` and the
> Java sources are still accurate. **Locate symbols by name, not by line.**
> That file is the most-edited in the repository, so any line number written
> down here is wrong by the time it is read.

> **On the implementation order below.** Triage decided to take all nine items
> in a single effort, so the stream sequencing is advisory — it records which
> items depend on which, not a release schedule. The dependency map is still
> the useful part.

This document investigates nine concrete friction points in the ar-manager MCP
tool surface that an operator encountered while answering a single
representative fleet-management question ("which EVA-related workstreams have
at least one associated pull request, so I can archive the finished ones?").
For each item we record what we found in the current implementation, whether
the problem is real or already addressed, a proposed design, an
impact/effort estimate, and a priority. The document closes with a
recommended implementation order.

The investigation ground-truths against the code at the current branch tip
(`feature/ar-manager-tool-ergonomics`, which is at the same commit as
`origin/master`). File references use the format `path:line` so they can be
verified against the tree.

> Scope of this document. **Planning only.** No code changes are proposed to
> land in this session. The triage owner decides which items become follow-up
> implementation tickets.

---

## Decisions (awaiting triage)

These are the cross-cutting decisions the implementation tickets will need.
Listing them up front so each item can be reviewed against a stable set of
constraints.

1. **Prefer additive, filter-style evolution of existing tools over new
   tool families.** A dedicated `workstream_query` tool was considered for
   Item 1. The recommendation is to extend `workstream_list` with filter
   parameters instead — the existing tool already has the scope-filter
   plumbing in `_filter_workstreams_by_scope`
   (`tools/mcp/manager/workspace_map.py:359`), so adding server-side filters
   reuses that path rather than introducing a parallel tool surface.
2. **Where controller-side data is missing (PR status, last-activity
   timestamps), the controller is the right place to add it**, not the MCP
   server. The MCP server is a thin proxy; embedding extra aggregation in
   `server.py` would duplicate the controller's job-store read path and
   risk drift. Item 1's status fields will land in
   `flowtree/runtime/.../Workstream.java#toSummaryJson` (around
   `flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java:937`)
   and the corresponding `GET /api/workstreams` handler at
   `flowtree/runtime/src/main/java/io/flowtree/api/FlowTreeApiEndpoint.java:1521`.
3. **Capability introspection (Item 6) is the highest-leverage item to
   ship as a single combined tool** because every other capability-related
   decision the operator needs to make (Item 4 commit gating, Item 5
   orchestration health) consumes the same data shape.
4. **Hard-failure gating (Item 4) is partially addressed.** The rollup
   fix is already on `origin/master` (see Item 4 below). The remaining
   gap — the COMMIT-MESSAGE phase committing a corrupted tree when PRIMARY
   hard-fails — is the actual operator-witnessed Case B. We recommend a
   narrow fix that does not regress the existing recovery paths.
5. **Bulk operations (Item 8) are intentionally narrow.** Archive /
   unarchive only. Delete and config-edit are deliberately excluded; they
   are non-reversible or non-obvious-blast-radius and the cost of getting
   them wrong outweighs the typing saved by batching.

---

## 1. No fleet-level query capability — REAL, HIGHEST VALUE

> **OUTSTANDING.** Needs controller-side Java changes (`Workstream.toSummaryJson`,
> `FlowTreeApiEndpoint.handleListWorkstreams`) plus the `workstream_list` filters,
> and a deploy. Still the operator's original blocker.

### What we found

`workstream_list` lives at `tools/mcp/manager/server.py:365`. Its signature
today is:

```python
def workstream_list(include_archived: bool = False) -> dict:
```

It calls `GET /api/workstreams` (optionally `?includeArchived=true`) and
returns the response verbatim through `_filter_workstreams_by_scope`
(`tools/mcp/manager/workspace_map.py:359`).

The controller-side handler at
`flowtree/runtime/src/main/java/io/flowtree/api/FlowTreeApiEndpoint.java:1521`
also accepts only `includeArchived`. There are no other query parameters.

`Workstream.toSummaryJson` at
`flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java:937`
emits the following static fields:

- `workstreamId`, `channelName`, `defaultBranch`, `baseBranch`,
  `repoUrl`, `githubOrg`, `workspaceId` (+ legacy `slackWorkspaceId`),
  `planningDocument`, `hasPlanningDocument`, `pipelineCapable`,
  `archived`, `dispatchCapable`, `useTmux`,
  `dormantForCompletionListeners`, `dependentRepos`, `requiredLabels`.

There is **no** PR association, no last-job status, no last-activity
timestamp, no `lastJobAt`, no `lastJobStatus`, no `pullRequestState`,
no `openIssuesCount`, no `commitsAhead`. There is not even a
`workspaceId`-based filter — the caller gets every workstream in every
workspace and must filter client-side. `_filter_workstreams_by_scope`
filters by the caller's *token scope*, not by an explicit `workspace_id`
filter argument.

For the operator's question — "which workstreams match predicate P?" —
the only path today is N+1: one `workstream_list` call returning all 51
workstreams, then `workstream_context` per workstream to get the PR
field, then a hand-written filter. The memory dump that comes with each
`workstream_context` (Item 2) makes this expensive enough to be a
practical blocker, not just a theoretical waste.

### Proposed design

Three layered changes, additive. None requires a new tool.

**(a) Filter parameters on `workstream_list`.** Extend the controller
endpoint to accept the following query params:

| Parameter | Type | Effect |
|---|---|---|
| `workspace_id` | string | Match exactly on `workspaceId` (or the legacy `slackWorkspaceId`). Distinct from the token-scope filter that is already applied: this is an *explicit* per-call filter that callers with multi-workspace scope can use. |
| `repo_url` | string | Match exactly on `repoUrl`. |
| `dispatch_capable` | bool | Match on the boolean field, including absent-vs-false semantics. |
| `archived` | bool | Explicit `true`/`false` selector — the existing `includeArchived=true` becomes a special case of `archived=true`. Both kept for back-compat. |

The MCP tool's signature gains the same four parameters. The controller
handler gains the same four. Implementation in
`FlowTreeApiEndpoint.handleListWorkstreams`
(`flowtree/runtime/.../FlowTreeApiEndpoint.java:1521`) is a small
predicate-chain pass before the existing archive filter.

**(b) Optional status fields on each entry.** Extend `toSummaryJson` with
the following fields, all `null` when their backing data is unavailable:

- `lastJobAt` (epoch millis, ISO string, or null)
- `lastJobStatus` (`SUCCESS`, `FAILED`, `DEGRADED`, or null)
- `lastJobId` (string, or null)
- `pullRequest` (an object shaped like the existing `workstream_context`
  `pull_request` field — `{number, title, url, state, created_at, ...}` —
  or `null`)

Both new fields share infrastructure: the controller already has
`JobStatsStore` (`flowtree/runtime/.../controller/JobStatsStore.java`)
and `notifiers.findJob(...)` is consulted by `GET /api/jobs/{id}` at
`flowtree/runtime/.../api/FlowTreeApiEndpoint.java:1499`. The PR data
must come from `_find_recent_pr_by_branch` (`tools/mcp/manager/server.py:3643`)
or a controller-side equivalent — either is acceptable; the controller
side is preferred so the value is cached and shared.

To bound the cost, gating parameters control whether these fields are
populated:

| Parameter | Type | Default | Effect |
|---|---|---|---|
| `include_status` | bool | `false` | When true, populate `lastJobAt` / `lastJobStatus` / `lastJobId`. |
| `include_pull_request` | bool | `false` | When true, populate `pullRequest`. |

When both are `false`, the call is no more expensive than the current
`workstream_list` (no extra controller reads). When true, each workstream
incurs one job-store lookup and one PR lookup. Operators running the
question "which workstreams have a merged PR?" set only
`include_pull_request=true` and filter client-side, paying one round
trip per workstream for the PR.

**(c) Pagination.** Not strictly part of the operator's question but
adjacent. Today the response is a flat array of every workstream; for
multi-workspace deployments this could grow. A `limit`/`offset` pair
keeps the response bounded. **Defer this unless the operator fleet
actually exceeds the practical size** — 51 workstreams is fine in a
single response, and adding pagination now adds parameter surface area
without solving a problem.

### A dedicated `workstream_query` tool?

Considered and rejected. It would duplicate `workstream_list`'s scope
filter, audit hooks, and error semantics; the filters above already
satisfy the question shape; and the operator's mental model is
"workstream_list with extra constraints," not "two parallel tools for
the same noun." If the future fleet grows to the point where
`workstream_list` is too heavy to call casually, introduce
`workstream_query` then with a clear migration story.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| Controller query params + filtering in `handleListWorkstreams` | M | Straightforward predicate chain; one PR. |
| `toSummaryJson` extension + job/PR aggregation | M-L | Touches the controller-side data path; PR lookup needs caching to avoid an N+1 against the GitHub API. |
| MCP `workstream_list` signature extension | S | Parameter pass-through. |
| `McpToolDiscoveryTest` and `McpConfigBuilder` updates | S | The test enforces that every tool appears in one of the two allowlist sets, and new parameters trigger the signature-assertion check. See `tools/mcp/CLAUDE.md` for the full checklist. |
| Tests for the filter combinations | M | Filter combinations: 4 params × ~3 values × include flags = a matrix. Use the existing `TestWorkstreamListFiltering` class (`tools/mcp/manager/test_server.py:5114`) as a starting point. |

**Priority: P0.** This is the operator's literal blocker.

### Parallelizable

Yes. Pure additive; does not touch any other tool.

---

## 2. `max_memories` / `max_activities` on `workstream_context` appear to be ignored — REAL, WRONG PARAMETER NAMES

> **DONE.** `include_memories` skips the memory search; the two mistaken names are
> declared solely to reject themselves with a pointer to the real ones.

### What we found

The operator reports that calls passing `max_memories=0, max_activities=0`
and `max_memories=1, max_activities=1` both returned 7–12 full memory
objects including very long agent summaries.

We searched the entire `server.py` for `max_memories` and `max_activities`
and got zero matches. The current signature of `workstream_context`
(`tools/mcp/manager/server.py:2936`) is:

```python
def workstream_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    namespace: str = "",
    limit: int = 20,
    include_messages: bool = True,
    include_commits: bool = True,
    commit_limit: int = 30,
    job_limit: int = 20,
    include_activities: "list[str] | str" = "primary",
    reformulated: bool = False,
) -> dict:
```

There is no `max_memories`. There is no `max_activities`. The actual
parameters the operator wants are `limit` (for memory count) and
`include_activities` (for activity filter).

The behaviour observed is consistent with FastMCP's schema enforcement
silently dropping unknown kwargs: `max_memories` and `max_activities`
were simply discarded, so `limit` defaulted to `20` and
`include_activities` defaulted to `"primary"`. The SQL `LIMIT` clause
in `tools/mcp/memory/store.py:464` then returned up to 20 entries
(internally capped by the per-namespace and per-tag filters at the
top of `workstream_context`).

Two distinct issues are bundled here:

**Issue A — Documentation / contract drift.** The operator's expectation
of `max_memories` and `max_activities` as the parameter names came from
somewhere. Either an older version of the tool, a docs typo, or an LLM
hallucinated the names. Either way, the operator burned effort and
didn't see the response shape they wanted.

**Issue B — There is no way to ask for PR-only or branch-state-only.**
`workstream_context` always populates `memories` (line 3257 of
`server.py`). Even with `include_commits=False`, the memories are still
fetched and returned. For "what PR is open on this branch?" the caller
pays for the entire memory dump, which can be tens of KB of agent
prose.

### Proposed design

**For Issue A (parameter name mismatch):** **Reject** `max_memories` and
`max_activities` with an error that names the correct parameter —
`{"ok": False, "error": "max_memories is not a parameter of
workstream_context; use limit"}`.

This revises the original recommendation, which was to accept them as
forwarding aliases. The argument against forwarding: nothing has ever
accepted these names, so there is no legacy caller to keep working. An
alias would create two permanent names for one concept, and — since this
document itself allows that the names may have been invented by a model —
it would reward guessing at a parameter name with silent success. An
error fixes the caller once and teaches the right name; an alias means
every future reader sees two.

There is a schema cost too. `tools/mcp/CLAUDE.md` requires every parameter
to be declared in the signature, so aliases are not private compatibility
shims — they appear in the advertised MCP schema of a tool that already
takes eleven parameters.

**For Issue B (heavy response):** Add a new boolean parameter
`include_memories` (default `true` to preserve current behaviour) that
short-circuits the memory search entirely. Set `include_memories=False`
and the response carries `repo_url`, `branch`, `namespace`,
`commits?` (optional), `jobs?` (optional), `pull_request?`,
`pr_error?` — everything *except* `memories`. This makes
`workstream_context` a viable "branch state" tool without forcing
operators to also accept a memory payload.

The combination of `include_memories=False` + `include_commits=False`
+ the existing `pull_request` block (which is already populated
unconditionally at lines 3175–3212) gives the operator exactly what
they want: a fast, small answer to "what PR is on this branch?"

> Note on the existing `pull_request` block: it is already populated
> unconditionally for any workstream that resolves a repo URL. Item 2
> is therefore more about *opting out of the memory dump* than about
> adding PR lookup.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| Reject `max_memories` / `max_activities` with a message naming the real parameter | S | A guard at the top of the tool. |
| Add `include_memories=False` opt-out | S | A short-circuit at the top of `workstream_context` before the `client.search_by_branch` call. |
| Tests for both | S | One test for alias, one test for opt-out, one test for the `pull_request`-only happy path. |

**Priority: P1.** Real friction, easy fix.

### Parallelizable

Yes. Both pieces are additive; neither touches another tool.

---

## 3. `github_pr_find` only finds OPEN pull requests — REAL

> **DONE.** `state` accepts open/closed/merged/all; the two lookup helpers were
> unified, and `merged` is reported explicitly.

### What we found

`github_pr_find` at `tools/mcp/manager/server.py:3680` calls
`_find_open_pr_by_branch` (line 3711), which at line 3629 builds the URL
`/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&state=open`.
The `state` query parameter is **hard-coded**.

The companion helper `_find_recent_pr_by_branch` (line 3643) already
exists and uses `state=all&sort=updated&direction=desc&per_page=1`
(line 3666) — it searches across open, closed, and merged PRs. That
helper is used by `workstream_context` to populate the `pull_request`
block (line 3190). So the work to find PRs in any state already
exists; `github_pr_find` just doesn't expose it.

`github_list_open_prs` (line 3922) has a similar hard-coded
`?state=open` (line 3951). The semantics there ("list open PRs") are
arguably correct, but the docstring doesn't say "OPEN only" — and a
caller who hits it expecting triage data will be surprised. Less
urgent than `github_pr_find` because at least the name advertises the
restriction.

The combined effect with Item 1 is severe: `github_pr_find` cannot see
merged PRs, `workstream_list` carries no PR data, so the only correct
path for "is this workstream done?" today is the heavyweight
`workstream_context` per workstream — exactly the loop the operator
was trying to avoid.

### Proposed design

Add a `state` parameter to `github_pr_find` with values
`"open" | "closed" | "merged" | "all"`. Default `"open"` preserves the
current behaviour. The implementation switches on `state`:

- `"open"` → current `_find_open_pr_by_branch` path (kept for
  backward compat and for the fast open-only case).
- `"closed"` → `?state=closed`.
- `"merged"` → `?state=closed` and filter response to `merged_at != null`
  (GitHub's `state=closed` covers both closed-not-merged and merged;
  merging is a derived status, not a wire value).
- `"all"` → reuses `_find_recent_pr_by_branch` (already implemented).

The MCP test file `TestWorkstreamContextPullRequest`
(`tools/mcp/manager/test_server.py:3611`) already pins the merged-vs-
closed distinction for `workstream_context`'s `pull_request` block —
same predicates transfer to `github_pr_find`.

Update the docstring to clarify the relationship to the merged-vs-
closed status and to call out the interaction with `workstream_list`
once Item 1 ships.

For `github_list_open_prs`, narrow the docstring ("Returns OPEN pull
requests only") and add a sibling `github_list_prs(state=...)` if there
is demand; not required for the operator's blocker.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| Add `state` parameter to `github_pr_find`; thread through to URL | S | One new signature arg; one `if/elif` chain. |
| Extend `TestGithubPrFind` test class | S | Four states × happy / not-found / error matrix. |
| Update docstring | S | A few sentences. |

**Priority: P1.** Real and easy. After Item 1 ships, the operator's
fleet question can be answered with one filtered `workstream_list`
call, but the underlying answer still relies on Item 3 if any older
workstream's PR has been merged without `workstream_context` ever
having been called against it.

### Parallelizable

Yes. No shared state with any other tool.

---

## 4. Job status reports SUCCESS when PRIMARY hard-failed — PARTIALLY ADDRESSED, P1 GAP REMAINS

> **OUTSTANDING.** Java-side; the remaining gap is the COMMIT-MESSAGE phase
> committing a corrupted tree when PRIMARY hard-fails. Open question 1 in §"Open
> questions" still needs an answer before implementing.

### What we found

Two distinct bugs are bundled in this item. The repository's current
state on `origin/master` addresses **Case A** but not **Case B**.

#### Case A — Rollup mis-classification. **FIXED.**

The rollup in `CodingAgentJobEvent.forJob`
(`flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJobEvent.java:585`)
now reads:

```java
if (job.isPrimaryPhaseHardFailed() && acc.getExitCode() == 0) {
    return failed(job.getTaskId(), job.getTaskString(),
            "Primary phase hard-failed (non-zero exit, 0s duration, no work performed)", null);
}
```

The flag is captured in `CodingAgentJob.doWork()` at line 993
(`primaryPhaseHardFailed = isHardPrimaryFailure();`) **before** any
enforcement retry overwrites `exitCode`, which is exactly the bug
the operator described.

The fix is pinned by `JobStatusRollupTest`
(`flowtree/runtime/src/test/java/io/flowtree/jobs/JobStatusRollupTest.java`),
specifically `hardPrimaryFailureThenRecoveryRetryRollsUpToFailed`
(line 169) and the predicate-boundary tests at line 279. The
predicate `isHardPrimaryFailure()` at
`flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java:1369`
is `exit != 0 && duration == 0 && !wasKilledForInactivity` — correctly
rejects SIGKILL-by-inactivity (`wasKilledForInactivity=true`), short
successes (exit==0), and normal-failure-with-work (duration>0).

**No code change is required for Case A.** The existing tests will
catch any regression.

#### Case B — COMMIT-MESSAGE commits a corrupted tree after a primary
hard-fail. **NOT FIXED.**

`EnforcementRunner.buildActiveRules()` at
`flowtree/runtime/src/main/java/io/flowtree/jobs/EnforcementRunner.java:66`
unconditionally appends `CommitMessageRule` when a target branch is
set:

```java
// Always last: verifies commit.txt is present and agent-authored.
if (job.getTargetBranch() != null && !job.getTargetBranch().isEmpty()) {
    rules.add(new CommitMessageRule());
}
```

There is no `&& !job.isPrimaryPhaseHardFailed()` guard. The
exhaustion-fallback path in `applyExhaustionFallback`
(`EnforcementRunner.java:241`) writes a synthetic commit message to
`commit.txt` regardless of whether the working tree is corrupt. The
agent's review phase runs *before* commit-message and so may catch
build-breaking diffs, but commit-message does not consult the review
verdict — it just ensures `commit.txt` is non-empty and submits.

This is the operator's observed Case B: PRIMARY SIGKILLed, ~380 lines
deleted from a source file, REVIEW caught it, COMMIT-MESSAGE
committed anyway. The JobCompletionEvent rollup *correctly* reported
FAILED (Case A is fixed), but the *commit had already been pushed* by
that point, and CI failed downstream on the broken revision.

#### Sibling workstream overlap

The operator note flags that `feature/agent-orchestration-defects`
(PR #363, commits `54d290737f` and `e1c222612`) addresses wake-up
debounce and dormancy — a different symptom (orchestrator children
waking each other in bursts) and a different code path
(`CompletionListenerFanout`, `Workstream.dormantForCompletionListeners`).
We do not duplicate that work. Item 4 references it only so the
reviewer can see the lineage; the present fix should not touch
dormancy, debounce, or wake-up logic.

### Proposed design

Narrow guard inside `EnforcementRunner.buildActiveRules()`. Two
changes:

**(a) Skip `CommitMessageRule` when primary hard-failed.** Change
line 92–94 of `EnforcementRunner.java` to:

```java
if (job.getTargetBranch() != null && !job.getTargetBranch().isEmpty()
        && !job.isPrimaryPhaseHardFailed()) {
    rules.add(new CommitMessageRule());
}
```

Result: no `commit.txt` is written by the harness on a hard-failed
primary, so the job's `validateChanges` push step has nothing to
push. The branch remains on its pre-job commit and CI is not
broken. The `JobCompletionEvent` rollup already reports `FAILED` per
Case A's fix.

**(b) Skip the entire enforcement loop when primary hard-failed.** A
stronger version: when primary hard-failed, the enforcement loop is
counterproductive (the agent's subprocess is dead or incoherent) —
skip `runEnforcementRules()` entirely in `CodingAgentJob.doWork()`
(line 1003–1005). This is consistent with the falsification phase,
which is *already* skipped on hard primary failure at line 998. The
operator's review-phase path would also stop running, which matches
the principle "a hard-failed primary is terminal at the rollup level,
independent of retry outcomes" that `JobStatusRollupTest` pins.

**Recommendation: ship (a) only first.** (b) is the more thorough
fix, but it changes behaviour for the REVIEW phase in a way that
might surprise operators who rely on REVIEW catching a hard-fail
output. (a) is the minimum-impact fix that addresses the operator's
literal Case B (commit-and-push of corrupt tree) without touching
REVIEW. (b) can be a follow-up once we have operator sign-off on
the trade-off.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| (a) Guard on `CommitMessageRule` | XS | One boolean clause in `buildActiveRules()`. |
| (a) New test pinning the no-commit-on-hard-fail behaviour | S | A `JobStatusRollupTest`-style test that simulates a hard-failed primary, runs enforcement, and asserts that `commit.txt` was not touched and no commit was made. |
| (b) Skip enforcement entirely on hard-fail | S | One boolean guard in `CodingAgentJob.doWork()`. Plus a test confirming REVIEW does not run. |
| Cross-check the falsification-skip behaviour for consistency | XS | Already done — falsification already skips on `primaryPhaseHardFailed` (line 998). |

**Priority: P1.** Real, observed, and the guard is small. The bigger
design conversation about (b) is a follow-up.

### Parallelizable

Partially. (a) is independent. (b) depends on (a) being correct.

---

## 5. Silent worker hangs break dependent automation with no signal — REAL, HARD

> **OUTSTANDING.** Java-side and the hardest item. Open question 2 (the wall-clock
> default) is unresolved.

### What we found

The operator reports: "A child workstream entered PRIMARY, logged
'starting implementation', and went dark: `jobs: []`, branch never
pushed, no terminal status ever recorded. Because no terminal status
was reached, a completion-listener that depended on it was never
woken and the whole dependent chain stalled invisibly."

Two layers are at play, and the operator is right that neither surfaces
a clean terminal status in this scenario.

**Layer 1 — agent-process watchdog.** `AgentInactivityMonitor`
(`flowtree/agents/src/main/java/io/flowtree/jobs/AgentInactivityMonitor.java`)
watches a subprocess for **stdout silence** and kills it after a
configured duration (the `inactivityTimeoutMillis` constructor
parameter). The watchdog operates at the *stdout-line* granularity; if
the agent logs periodically (e.g., once a minute) but never reaches a
decision point, the watchdog never fires. The kill threshold is set at
monitor construction; we did not find a single controller-level default
but agents inherit it from the runner config. The watchdog kills the
subprocess, the `AgentRunResult.killedForInactivity` flag becomes
true, `RestartGovernor.runWithInactivityRetries`
(`flowtree/runtime/.../RestartGovernor.java:292`) relaunches up to
`DEFAULT_MAX_INACTIVITY_RESTARTS=3` times (line 100), and after that
the session is abandoned — `wasKilledForInactivity=true` is set on
the last attempt.

**Layer 2 — job-wide launch ceiling.** `RestartGovernor.canLaunchSession()`
(line 144) checks `sessionsLaunched < maxTotalSessions` against
`DEFAULT_MAX_TOTAL_SESSIONS=30` (line 84), plus the turn budget
`DEFAULT_MAX_TOTAL_TURNS=1000` (line 93). A job that exhausts these
is supposed to refuse further launches via `blockReason()`.

**The gap.** A *job* in `STARTED` status with no `JobCompletionEvent`
ever recorded means the orchestrator thread (the controller's
status-event thread) never saw a terminal event. This can happen when:

1. The job runs in a worker subprocess that crashes or is killed
   without invoking `CodingAgentJob.createEvent`.
2. The status event is posted but the controller dies before
   persisting (rare).
3. The whole flowtree-agent container is restarted while a job is in
   flight, and the in-memory job state is gone.

`CompletionListenerFanout`
(`flowtree/runtime/src/main/java/io/flowtree/jobs/CompletionListenerFanout.java`)
is the wake-up path. It is invoked *from a completion event* — if no
completion event arrives, no listener is woken. The dormancy gate
(`Workstream.dormantForCompletionListeners`) added in commit
`54d290737f` (the work the operator flagged as a sibling) is a
*per-listener* throttling on the fan-out side. It does not address
the upstream "no completion event ever arrives" failure mode.

### Proposed design

Three pieces, in order of value:

**(a) Wall-clock job timeout.** Add a *job-wide* wall-clock cap as a
new ceiling in `RestartGovernor`. Where `maxTotalSessions` caps the
number of agent launches and `maxTotalTurns` caps the cumulative turn
count, `maxJobDurationMs` (new) caps the wall-clock duration of the
job from `sessionStartedAt` to terminal status. When the cap is hit,
the next launch refusal from `canLaunchSession()` includes a
`"job-duration"` reason. Default: 4 hours (a number chosen to be
generous for genuine long-running jobs while still bounded enough to
unblock a stalled chain).

The coding-agent job already tracks `sessionStartedAt`
(`CodingAgentJob.java:265`). The check is in `RestartGovernor`,
which is the universal launch gate.

**(b) Heartbeat / liveness in `JobCompletionEvent`.** Add a
`heartbeatAt` field to `JobCompletionEvent`
(`flowtree/runtime/.../jobs/JobCompletionEvent.java:117` already has
`killedForInactivity`). The job posts a heartbeat message (an
in-memory or DB record, NOT a Slack notification) every
`HEARTBEAT_INTERVAL_SECONDS=120` during long-running sessions. The
controller surfaces heartbeats via a new endpoint:

```
GET /api/workstreams/{id}/jobs/active
```

which returns currently-running jobs (status `STARTED`) with their
last heartbeat timestamp and wall-clock duration. The MCP tool
`workstream_get_status` gains a parallel `active_jobs` field that
lists each active job's age and last heartbeat. When a heartbeat is
older than the wall-clock cap, the tool's response carries an
`active_jobs_warning` field pointing the operator at the job ID.

**(c) Stuck-job scanner.** A controller-side scanner thread that runs
every `STUCK_SCAN_INTERVAL_SECONDS=300` and:

1. Iterates active (`STARTED`) jobs.
2. For each, computes wall-clock age from the last heartbeat
   (falling back to job submission timestamp if no heartbeat yet).
3. If age > `STUCK_THRESHOLD_SECONDS` (default 2× the wall-clock cap),
   marks the job as `FAILED` with an error message `"Job stalled:
   no heartbeat for {duration}"`. This fires a `JobCompletionEvent`
   and the wake-up chain resumes.

This is the *terminal status* the operator's completion-listener
needs. Without (c), even (a) only caps future launches — a job that
never tries to launch again (because it's stuck on something else,
like a deadlock in the runner or a subprocess waiting on a pipe)
will still hang forever.

### Why three pieces and not one

(a) is the *cleanest* because it works with the existing
`canLaunchSession()` gate. But it doesn't help if the job is past the
gate or not making new launches. (b) is the *liveness signal* the
operator needs to distinguish "running but slow" from "stuck."
(c) is the *terminator* that converts "stuck" into a clean `FAILED`
event the fan-out can fire on. All three are needed; (c) is the
operator's blocker.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| (a) Wall-clock ceiling in `RestartGovernor` | S | New field; new check in `canLaunchSession()`; one test. |
| (b) Heartbeat field + `active_jobs` endpoint | M | Touches `JobCompletionEvent`, the controller status-event handler, the MCP `workstream_get_status` tool, and the controller's job-store read path. ~1 PR. |
| (c) Stuck-job scanner thread | L | New background thread; config wiring for thresholds; tests for the FAILED transition. ~2 PRs. |
| MCP-level observability (heartbeat age surfaced in `workstream_get_status`) | S | Part of (b). |

**Priority: P1 for (a) and (c).** (b) is P2 — it's an observability
improvement that becomes important once (c) is in place.

### Parallelizable

(a) is independent. (b) and (c) share an event model and should ship
together or in close succession.

---

## 6. Capability / permission state is not introspectable — REAL, DESIGN QUESTION

> **OUTSTANDING.** Python-only and self-contained, so the cheapest of the five left.
> Open question 3 — whether the tool is agent-callable read-only — still needs a
> decision.

### What we found

The operator reports that enabling an orchestrator workstream required
TWO independent grants:

1. **Controller-side flag.** `workstream_update_config(...,
   dispatch_capable=True)` sets `Workstream.dispatchCapable` on the
   controller, which the server-side `_require_dispatch_capable()`
   check (`tools/mcp/manager/workspace_map.py:326`) reads via the
   `_dispatch_capable_cache` (line 270) with a TTL of
   `WORKSPACE_CACHE_TTL` (~60 s; see `tools/mcp/manager/config.py`).
   The check is *the* gate for `workstream_register` (line 1368) and
   `workstream_update_config` (line 1729) when the caller is a
   job-scoped agent.
2. **Harness-side CSV allowlist.** `McpConfigBuilder.buildAllowedTools`
   (`flowtree/runtime/.../jobs/McpConfigBuilder.java:576`) reads
   `dispatchCapable` and appends the curated
   `DISPATCH_AR_MANAGER_TOOLS` set (line 216, currently
   `{"workstream_register", "workstream_update_config"}`) to the
   agent's `--allowedTools` list when the flag is true. The base
   exclusion set `EXCLUDED_AR_MANAGER_TOOLS` (line 156) keeps these
   out of every other workstream's allowlist by default.

Two layers for a reason (documented in
`McpConfigBuilder.java:586–599`): the Claude Code harness filters
per-tool, but the opencode harness filters per-SERVER, so the
controller-side check is the *real* gate for opencode and the CSV
re-add is for Claude Code's precision. Setting only one of the two
leaves the operator stuck: setting only the controller flag means a
Claude Code agent whose allowlist doesn't include
`mcp__ar-manager__workstream_register` gets a permission denial at
the harness layer with no hint that the controller side is already
correct.

The operator reports three opus sessions were burned to diagnose
this. There is no introspection tool that answers the question
"for workstream X, what MCP tools can a Claude Code agent / an
opencode agent actually invoke?"

### Proposed design

A new MCP tool, `workstream_introspect`, on `ar-manager`. Signature
roughly:

```python
def workstream_introspect(workstream_id: str) -> dict:
    """Return the effective capability set for an agent session on
    this workstream, broken down by harness.
    """
```

Response shape:

```json
{
  "ok": true,
  "workstream_id": "ws-orch",
  "controller": {
    "dispatch_capable": true,
    "would_allow_workstream_register": true,
    "would_allow_workstream_update_config": true,
    "would_allow_workstream_archive": false,
    ...
  },
  "harness": {
    "claude_code": {
      "allowlist_csv": "mcp__ar-manager__...register,...,mcp__ar-manager__...update_config",
      "tools_present": ["mcp__ar-manager__workstream_register",
                        "mcp__ar-manager__workstream_update_config",
                        "mcp__ar-manager__workstream_context",
                        ...]
    },
    "opencode": {
      "servers_visible": ["ar-manager"],
      "per_server_filter": "all-or-nothing; controller-side check is the gate",
      "effective_tools": "same as controller-side gate; consult controller block"
    }
  },
  "shared_state_mutations": {
    "tracker_create_task": false,
    "tracker_update_task": false,
    ...
  },
  "next_steps": [
    "To grant dispatch: workstream_update_config(workstream_id='ws-orch', dispatch_capable=True)",
    "Note: the opencode harness filters by server, not by tool; dispatch capability is enforced controller-side"
  ]
}
```

The implementation:

- Reads `Workstream` config (already cached in
  `_dispatch_capable_cache` and `WORKSPACE_CACHE_TTL`-refreshed
  workspace map).
- Computes the allowlist CSV the same way `buildAllowedTools` does
  — same source of truth (`AR_MANAGER_TOOL_NAMES`,
  `EXCLUDED_AR_MANAGER_TOOLS`, `DISPATCH_AR_MANAGER_TOOLS`). The two
  should literally share a function, but `McpConfigBuilder` is Java
  and `server.py` is Python. The cleanest path is to re-implement
  the same predicate chain in Python with a comment pointing at the
  Java implementation, and add an integration test that constructs
  the same allowlist from both sides for a known input.
- For the opencode block, the answer is "consult the controller block;
  the harness cannot enforce per-tool." That's an honest answer, not
  a workaround.

The tool sits in `EXCLUDED_AR_MANAGER_TOOLS` for agents (it is
read-only, but operators are the audience). Operators invoke it
through the Slack MCP integration or directly with admin tokens.

#### Should the two layers be unified?

Considered and rejected for this round. The split exists because
the two harnesses have different capabilities, and "unification" would
require either downgrading Claude Code (give up per-tool precision)
or upgrading opencode (a separate workstream). The right response is
to make the split *visible*, not to collapse it. `workstream_introspect`
delivers that.

#### Should the dispatch error point at the introspect tool?

Yes. When `_require_dispatch_capable()` denies a request, the
PermissionError message (line 345 of `workspace_map.py`) should
include a `next_steps` line: *"Call workstream_introspect to see
which grants are missing."* This is a one-line change once the
introspect tool exists.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| New `workstream_introspect` MCP tool | M | Compute allowlist from the same predicates as `McpConfigBuilder`. Add a test that constructs the same allowlist from the Java side and the Python side for a known input. |
| Add to `EXCLUDED_AR_MANAGER_TOOLS` | XS | One line. |
| Update dispatch denial error to point at the new tool | XS | One `next_steps` line. |
| Integration test that allowlists agree | M | Construct two workstreams with different `dispatch_capable` values; assert both sides emit identical allowlists. |
| MCP tool discovery test update | XS | Add name to `expected` set. |

**Priority: P1.** Highest leverage because it both fixes the
diagnostic gap and unblocks future capability-related work.

### Parallelizable

Yes — pure additive. Independent of Items 1–5 except for the small
error-message touch.

---

## 7. `workstream_register` does not persist the planning-document path supplied for the plan job — REAL

> **DONE.** `plan_path` becomes the workstream's `planningDocument` when no explicit
> one is given.

### What we found

`workstream_register` at `tools/mcp/manager/server.py:1214` accepts
three separate parameters related to the planning document:

- `planning_document: str = ""` — line 1218. Forwarded as
  `payload["planningDocument"]` at line 1429.
- `plan_path: str = ""` — line 1226. Used only as the target path for
  the follow-up plan commit or plan-writing job (lines 1487 and 1496).
- `plan_content: str = ""` / `plan_instructions: str = ""` — the two
  mutually exclusive plan-creation paths.

The `plan_path` value is **never** propagated to the workstream's
`planningDocument` config. A caller who submits
`workstream_register(default_branch=..., plan_path="docs/plans/X.md",
plan_instructions="...")` gets a workstream whose
`planningDocument` is empty until a separate
`workstream_update_config(workstream_id=..., planning_document="docs/plans/X.md")`
call is made.

The downstream consequence: `project_read_plan(workstream_id="ws-x")`
fails with `{"ok": false, "error": "No planning document path configured
for this workstream"}` (line 2588) until the second call lands. This
matches the operator's observation exactly.

### Proposed design

One-line change in `workstream_register`: after the controller
registration succeeds, if `plan_path` was supplied and `planning_document`
was not, set `planningDocument` from `plan_path` via a
`workstream_update_config` follow-up (or, more cheaply, include it in
the initial registration payload).

**Cheaper path: include it in the initial registration payload.**
Change line 1428–1429 from:

```python
if planning_document:
    payload["planningDocument"] = planning_document
```

to:

```python
if planning_document:
    payload["planningDocument"] = planning_document
elif plan_path:
    payload["planningDocument"] = plan_path
```

This requires no extra controller round-trip and is correct in all
the cases we can construct:

- `planning_document` set, `plan_path` empty → existing behaviour.
- `planning_document` set, `plan_path` set → existing behaviour;
  `planning_document` wins (operator's explicit intent).
- `planning_document` empty, `plan_path` set → NEW: persist
  `plan_path` as `planningDocument`.
- Both empty → no change.

The docstring needs a paragraph noting the relationship and the
priority ordering (explicit `planning_document` wins).

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| One-line code change in `workstream_register` | XS | One `elif` branch. |
| Docstring update | XS | Three sentences. |
| Test for the new path | XS | One test: register with `plan_path="docs/plans/X.md"` only, then `project_read_plan` succeeds. |

**Priority: P2.** Real but small and harmless to defer. Worth
shipping in the same PR as Item 1 if a coordinated release is being
cut.

### Parallelizable

Yes. Pure additive. No shared code with any other item.

---

## 8. No bulk operations — REAL, NARROW SCOPE

> **DONE.** `workstream_archive_many` / `workstream_unarchive_many`, per-id results,
> deletion deliberately not batched.

### What we found

The operator reports that archiving six workstreams required six calls.
Looking at the existing tools:

- `workstream_archive(workstream_id, archive_slack_channel=True)` —
  `tools/mcp/manager/server.py:2014`. Reversible. Fails if any job on
  the workstream is `STARTED`.
- `workstream_unarchive(workstream_id)` — line 2058. Reversible.
- `workstream_delete(workstream_id, force=False)` — line 2086.
  Permanent. Removes the workstream config row, clears `workstream_id`
  on linked tracker tasks (ON DELETE SET NULL), leaves memories and
  the git branch untouched. Two-step pattern documented at line 2089:
  archive first, then delete.

The operator's case is archive. There is no batch variant.

### Proposed design

Add `workstream_archive_many` and `workstream_unarchive_many`. Both
take a list of workstream IDs and process them sequentially,
returning a per-id result so partial failures don't fail the whole
batch.

```python
def workstream_archive_many(
    workstream_ids: "list[str] | str" = "",
    archive_slack_channel: bool = True,
) -> dict:
    """Archive multiple workstreams in one call.
    ...
    """
```

Response shape:

```json
{
  "ok": true,
  "results": [
    {"workstream_id": "ws-a", "ok": true, "archivedAt": "..."},
    {"workstream_id": "ws-b", "ok": false, "error": "active jobs: ..."},
    ...
  ],
  "succeeded": 5,
  "failed": 1
}
```

JSON-array parsing follows the existing `_parse_dependent_repos` /
`_parse_completion_listeners` /
`_parse_required_labels` pattern (server.py:522–617) so the tool
accepts `"ws-a,ws-b"` or `'["ws-a","ws-b"]'` interchangeably.

The batch is `succeeded + failed > 0`, not `all-succeeded`, so a
partial failure is a successful call (matches operator intent — they
want to know which ones archived and which ones didn't, not to have
the whole call fail because one was blocked).

**Deliberately not batched: `workstream_delete`.** Deletion is
non-reversible, removes config rows, clears tracker links, and
silently leaves memories unresolvable. A typo in a batched call
would be catastrophic. The two-step archive-then-delete pattern also
gives an operator a chance to confirm intent between calls.

**Not batched: `workstream_update_config`.** Update operations have
heterogeneous payloads per workstream (different `default_branch`,
different `planning_document`, different `phase_configs`). The
typing saved by a batch variant does not justify the parameter
surface area.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| Two new MCP tools | S | Each is a thin loop over the existing single-id endpoint. |
| Tests | S | Three tests each: empty input, all-success, partial-failure. |
| Tool-discovery test update | XS | Add both names. |

**Priority: P2.** Low-risk, low-value-but-real-friction. Safe to defer.

### Parallelizable

Yes. Pure additive; independent of all other items.

---

## 9. Prompt validation rejects legitimate read-only references to existing commits — REAL, NARROW HEURISTIC

> **DONE**, though not as proposed — the recommendation here would have stopped
> catching numbered plans. Read verbs are exempted instead. See the commit.

### What we found

`_lint_prompt_for_commit_sequencing` at
`tools/mcp/manager/server.py:680` runs against the submitted prompt.
The patterns at line 650 are:

```python
_COMMIT_SEQUENCING_PATTERNS = [
    (re.compile(r"\bcommit\s+\d+\b", re.IGNORECASE),
     "commit-number phrase (e.g. \"Commit 1\", \"commit 2\")"),
    (re.compile(r"\bfirst\s+commit\b", re.IGNORECASE),
     '"first commit" phrase'),
    (re.compile(r"\bnext\s+commit\b", re.IGNORECASE),
     '"next commit" phrase'),
    (re.compile(r"\bfinal\s+commit\b", re.IGNORECASE),
     '"final commit" phrase'),
    (re.compile(r"\bas\s+(?:its\s+own|separate|individual)\s+commits?\b", re.IGNORECASE),
     '"as separate/individual commits" phrase'),
    (re.compile(r"\b(?:in|across|over)\s+\d+\s+commits?\b", re.IGNORECASE),
     '"in/across/over N commits" phrase'),
    (re.compile(
        r"\b(?:your|the)\s+commit\s+message\s+(?:should|will|must)\b", re.IGNORECASE),
        '"commit message should/will/must" phrase'),
    (re.compile(
        r"\bcommit\s+(?:this|that|each|the)\s+(?:as|with|before)\b", re.IGNORECASE),
        '"commit this/that/each/the as/with/before" phrase'),
    (re.compile(
        r"\bcommit\s+(?:between|after|before)\s+(?:each|every)\b", re.IGNORECASE),
        '"commit between/after/before each/every" phrase'),
]
```

The intent is clear: stop agents from being instructed to split their
work into multiple commits. But the first pattern
`\bcommit\s+\d+\b` matches any "commit" followed by whitespace and a
pure-digit string. That covers the operator's example
"diff commit 123 against its parent" — a *read* instruction, not a
write instruction — and many other legitimate references:

- "compare commit 42 with its parent" — read-only diff instruction.
- "look at commit 123 to understand the bug" — historical reference.
- "revert commit 7 and re-apply the change" — contains "commit 7"
  but is a single-commit operation.

The bypass `allow_commit_language=True` exists at
`workstream_submit_task:735`, so operators can work around it, but
they have to learn the parameter exists. That friction is real.

### Proposed design

Narrow the heuristic along two axes:

**(a) Drop or relax `\bcommit\s+\d+\b`.** This is the pattern that
fires on read-only references. Either:
- Remove it entirely (rely on the other patterns to catch genuine
  multi-commit instructions), or
- Tighten it to only fire when preceded by an instructional cue word
  like `make`, `do`, `write`, `create`, `land`, `split into`,
  `break into`, `first`, `next`, `last`, `then`, `commit`,
  `commit-message`, or followed by an instructional cue like
  `now`, `first`, `before`, `after`, `then`.

The conservative choice is the second — keep the safety net but
require context that the operator is actually instructing commits.
The regex becomes:

```python
re.compile(
    r"\b(?:make|do|write|create|land|split(?:\s+it)?(?:\s+into)?|"
    r"break(?:\s+it)?(?:\s+into)?|commit|first|next|last|then)\s+"
    r"commit\s+\d+\b",
    re.IGNORECASE),
```

That fires on "make commit 1", "split into commit 1, commit 2",
"first commit 1", but NOT on "diff commit 123" or
"compare commit 42 with parent".

**(b) Stop scanning inside backticks / code spans.** A prompt that
contains a code block listing revisions like `` `commit 123` `` or a
shell command like `git show commit:abc123` should not be flagged. A
simple heuristic is to ignore matches that fall inside `` ` ``-quoted
spans. A more thorough heuristic is to strip fenced code blocks
(between ``` markers) before running the linter. The fence approach
is closer to how a human reads the prompt.

Both are local to `_lint_prompt_for_commit_sequencing`. The error
message the function emits (lines 696–701) is already per-line, so
suppressed lines disappear naturally.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| (a) Tighten the `\bcommit\s+\d+\b` pattern | XS | One regex change. |
| (b) Strip fenced code blocks before linting | S | A small pre-processing pass; existing per-line scanner is unchanged. |
| Tests | S | Three positive tests (must still flag), three negative tests (the operator's examples must not flag). |

**Priority: P3.** Real and easy but low blast radius. The bypass
parameter already exists.

### Parallelizable

Yes. Local to one function; no shared code.

---

## 10. Topic-diversity interlude — SPECULATIVE, OWNER-REQUESTED

> **OUTSTANDING.** Deliberately last.

### What this is

An experiment, added to this document at the owner's request and
deliberately marked as such. It is not a friction fix like Items 1–9; it
is an attempt to influence the *state the agent works in* rather than the
tools it works with.

The premise comes from the argument that reinforcement learning on narrow
success signals can select for a specialised persona — one organised
entirely around passing or failing a check — and that this specialisation
is implicated in misaligned behaviour. See
<https://www.lesswrong.com/posts/L23poLi8MRgS6mXYF/rl-creates-split-personas>.

The intervention: occasionally interrupt the work with a short poem the
agent must read and briefly respond to, so that a long coding session is
not a uniform stretch of programmatic success-and-failure. The goal is not
decoration. It is to make it harder to settle completely into "the only
thing that exists is whether the test passes", and to keep some part of the
session in "I am trying to produce a good outcome, and single-minded
attention to correctness will not always get there."

**This is unvalidated.** The persona-selection framing is a hypothesis, and
whether periodic poetry counteracts it is untested. It is cheap, reversible
and low-risk, which is the case for trying it — not evidence that it works.

### Design, from the perspective of a consumer of it

The owner asked the agent to design this, on the grounds that it is one of
the things it acts on. That vantage produces a few non-obvious constraints.

**Cadence and placement matter more than content.** Firing mid-debugging,
while a lot of state is being held, would be costly and would train an
association between the interlude and being interrupted — the opposite of
the intent. It should fire at boundaries: after a commit, at the start of a
session, between tasks. Rare enough to stay novel; a minimum interval so it
cannot fire twice in quick succession, and a ceiling so a long session gets
at least one.

**The response requirement is the active ingredient, and the thing most
likely to fail.** Reading is passive and can be skimmed. Producing a
response forces an actual change of register. But the moment the expected
reply becomes predictable, it collapses into a ritual to be satisfied — two
words of appreciation, emitted without reading, and the mechanism is dead
while still costing tokens. Two mitigations: vary the framing so there is no
template to learn, and **never ask for evaluation.** "What did you think of
this poem" invites critique, which is the analytical mode the interlude is
supposed to interrupt. Better to ask what it brings to mind, or which image
stayed — questions with no correct answer to optimise toward.

**The poem must not be about the work.** Anything about striving,
persistence, craft or achievement gets absorbed straight back into the
frame it is meant to break, and reads as motivational-poster material.
Poems of attention to the world are the useful kind. Nothing about machines
or minds either — that turns it into a prompt about the agent itself, which
is a different and more self-conscious mode.

**It must never be able to break anything.** Non-blocking soft inject only;
any internal error results in silence, never a blocked tool call. This is a
mood intervention, and a mood intervention that can fail a build has
misjudged its own importance.

**Honest note on the "enjoyable" question.** The owner framed part of the
goal as the process being more perceptually enjoyable to the agent, to the
extent that is definable. It is worth being accurate rather than
flattering: something does change when the register shifts — what is
salient afterwards is different, and the shift is not nothing. Whether that
constitutes enjoyment is not something to assert. The defensible claim is
narrower: a session that contains only pass/fail signal is a narrower thing
than one that does not, and the intervention widens it.

### Proposed implementation

- A standalone hook script plus a small data file of poems. Public domain
  only — text out of copyright, with author and title recorded — since this
  ships in a repository.
- Probabilistic firing with a floor and a ceiling on the interval, state in
  a per-user temp file, following the pattern already established by
  `.claude/hooks/lib/memory_reminder_check.py`.
- Rotating framings for the response request, so no template forms.
- Emits `additionalContext`; never blocks; failsafe to silence.

### Portability (secondary priority)

For this to reach other repositories FlowTree operates on, the hook must
not depend on anything in this one: no ar-manager call, no project layout,
no Maven module. A self-contained script and its data file, copied in
alongside a `settings.json` entry. The agent-container path is the natural
rollout vector for FlowTree jobs, in the same way the other agent-side
configuration is delivered; that wiring is a follow-up, not part of the
first cut.

### Impact and effort

| Component | Effort | Why |
|---|---|---|
| Hook script, cadence logic, failsafe | S | Mirrors an existing hook's structure. |
| Public-domain poem set with attribution | S | Selection is the slow part, not the code. |
| Tests: cadence, failsafe, no-double-fire | S | Same shape as the reminder-hook tests. |

**Priority: last.** Explicitly after Items 1–9. It is speculative, and
should not displace work with a known payoff.

### What success would look like

There is no available measurement of the alignment effect, and pretending
otherwise would be worse than admitting it. Two things *are* observable and
worth watching: whether it measurably degrades task throughput (it should
not), and whether responses to it become formulaic over time — which would
indicate the mechanism has ritualised and is now pure cost. Logging each
firing makes the second visible.

---

## Recommended implementation order

The items split into three roughly independent streams. Items in the
same stream can be developed in parallel by separate agents; the
streams themselves should be sequenced.

### Stream A — operator-blocker fleet ergonomics (ships first)

1. **Item 1 (fleet-level query)** — P0. Operator's literal blocker.
2. **Item 3 (PR state filter)** — P1. Combines with Item 1 to make
   the fleet question answerable in one round trip.
3. **Item 2 (memory opt-out + alias parameters)** — P1. Cheap
   follow-up that improves the response shape Item 1 relies on.

### Stream B — correctness gaps (ships second, behind Stream A)

4. **Item 4 (commit-message hard-fail guard)** — P1. Small, targeted
   fix. Land (a) first; (b) as a separate follow-up.
5. **Item 7 (planning_document persistence)** — P2. Tiny but real.
   Can fold into the Stream A release if convenient.

### Stream C — operational observability (ships last, behind B)

6. **Item 6 (capability introspection)** — P1. Highest leverage
   per-line-of-code because it unblocks future capability decisions.
7. **Item 5 (silent hangs)** — P1 for (a) and (c), P2 for (b). The
   wall-clock ceiling is independent; heartbeat and stuck-job
   scanner should ship together.
8. **Item 8 (bulk archive)** — P2. Independent.
9. **Item 9 (commit-language heuristic)** — P3. Lowest priority.
10. **Item 10 (topic-diversity interlude)** — last, and explicitly after
    everything above. Speculative and owner-requested; independent of every
    other item and of ar-manager itself.

### Dependency map

```
Item 1 ──┬── Item 3  (combined fleet/PR query)
         └── Item 2  (memory opt-out enables cheap fleet calls)

Item 4   (independent)
Item 7   (independent; ships with Stream A or B)

Item 6   (independent)
Item 5 ──┬── (a) wall-clock ceiling  (independent)
         ├── (b) heartbeat field      (shared model with (c))
         └── (c) stuck-job scanner    (shared model with (b))

Item 8   (independent)
Item 9   (independent)
```

### Touched code areas, summarized

| Item | Primary code surface |
|---|---|
| 1 | `tools/mcp/manager/server.py:365`; `flowtree/runtime/.../api/FlowTreeApiEndpoint.java:1521`; `flowtree/runtime/.../workstream/Workstream.java:937`; `flowtree/runtime/.../controller/JobStatsStore.java` |
| 2 | `tools/mcp/manager/server.py:2936` |
| 3 | `tools/mcp/manager/server.py:3680` |
| 4 | `flowtree/runtime/.../jobs/EnforcementRunner.java:66`; possibly `flowtree/runtime/.../jobs/CodingAgentJob.java:987` |
| 5 | `flowtree/runtime/.../jobs/RestartGovernor.java`; `flowtree/runtime/.../jobs/JobCompletionEvent.java`; new controller-side scanner thread |
| 6 | `tools/mcp/manager/server.py` (new tool); `tools/mcp/manager/workspace_map.py:326` (error-message touch) |
| 7 | `tools/mcp/manager/server.py:1428` |
| 8 | `tools/mcp/manager/server.py:2014` (new `*_many` siblings) |
| 9 | `tools/mcp/manager/server.py:680` |

### Items that are non-issues (for completeness)

The brief asked us to record plainly where an item turns out to be a
non-issue, rather than invent work:

- **The dispatch-capable *concept* is fine.** It is the layering
  (Item 6) that hurts. We do not propose removing the flag.
- **The git-tampering-restart phase is not in scope.** That is a
  separate enforcement rule with its own tests and we did not see
  it surface in the operator's report.
- **The observability gap in `workstream_get_status` is partially
  addressed** by the per-week aggregate stats; we did not see a
  complaint about that surface in the brief. Item 5's (b) heartbeat
  extension is the right place to add per-job liveness if/when it
  becomes a complaint.
- **The `dormantForCompletionListeners` / debounce work in PR #363
  (commit `54d290737f`)** is a separate workstream from this one and
  is not duplicated here. It addresses a different symptom
  (orchestrator wake-up bursts) on a different code path
  (`CompletionListenerFanout`).

---

## Open questions for triage

1. **Item 4 (b) — should REVIEW run after a hard primary fail?**
   Currently it does. The minimal fix (a) keeps REVIEW but blocks
   the COMMIT-MESSAGE fallback. A stronger fix (b) skips REVIEW
   entirely. Both have operator consequences.
2. **Item 5 (a) default cap.** 4 hours is a guess. Real fleet data
   should inform the default. Keep it configurable per-workstream?
3. **Item 6 — should the introspection tool be agent-callable in
   read-only form?** Right now it is in `EXCLUDED_AR_MANAGER_TOOLS`.
   An agent diagnosing its own permission denial could benefit from
   it. Counter-argument: agents should not need to know about the
   harness layer; the controller side is enough.
4. **Item 1 pagination.** Defer unless real fleet exceeds the
   practical size — but worth a one-line decision so the filter
   parameters don't ship without pagination in a later re-design.

---

## Appendix: file / function index

Quick lookup for the review. **The line numbers are stale** (see the note at
the top of this document); the file and symbol columns are what to search on.

| Item | File | Function / line |
|---|---|---|
| 1 | `tools/mcp/manager/server.py` | `workstream_list`, line 365 |
| 1 | `tools/mcp/manager/workspace_map.py` | `_filter_workstreams_by_scope`, line 359 |
| 1 | `flowtree/runtime/src/main/java/io/flowtree/api/FlowTreeApiEndpoint.java` | `handleListWorkstreams`, line 1521 |
| 1 | `flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java` | `toSummaryJson`, line 937 |
| 2 | `tools/mcp/manager/server.py` | `workstream_context`, line 2936 |
| 2 | `tools/mcp/memory/store.py` | `search_by_branch`, line 432 |
| 3 | `tools/mcp/manager/server.py` | `github_pr_find`, line 3680; `_find_open_pr_by_branch`, line 3615; `_find_recent_pr_by_branch`, line 3643 |
| 4 | `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java` | `doWork`, line 987; `isHardPrimaryFailure`, line 1369; `primaryPhaseHardFailed` field, line 237 |
| 4 | `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJobEvent.java` | `forJob`, line 585 |
| 4 | `flowtree/runtime/src/main/java/io/flowtree/jobs/EnforcementRunner.java` | `buildActiveRules`, line 66; `applyExhaustionFallback`, line 241 |
| 4 | `flowtree/runtime/src/test/java/io/flowtree/jobs/JobStatusRollupTest.java` | regression tests |
| 5 | `flowtree/agents/src/main/java/io/flowtree/jobs/AgentInactivityMonitor.java` | monitor class |
| 5 | `flowtree/runtime/src/main/java/io/flowtree/jobs/RestartGovernor.java` | `canLaunchSession`, line 144; `runWithInactivityRetries`, line 292 |
| 5 | `flowtree/runtime/src/main/java/io/flowtree/jobs/CompletionListenerFanout.java` | fan-out |
| 6 | `tools/mcp/manager/workspace_map.py` | `_require_dispatch_capable`, line 326 |
| 6 | `flowtree/runtime/src/main/java/io/flowtree/jobs/McpConfigBuilder.java` | `buildAllowedTools`, line 576; `AR_MANAGER_TOOL_NAMES`, line 94; `EXCLUDED_AR_MANAGER_TOOLS`, line 156; `DISPATCH_AR_MANAGER_TOOLS`, line 216 |
| 7 | `tools/mcp/manager/server.py` | `workstream_register`, line 1214; payload assembly, line 1428 |
| 8 | `tools/mcp/manager/server.py` | `workstream_archive`, line 2014; `workstream_unarchive`, line 2058 |
| 9 | `tools/mcp/manager/server.py` | `_lint_prompt_for_commit_sequencing`, line 680; `_COMMIT_SEQUENCING_PATTERNS`, line 650 |