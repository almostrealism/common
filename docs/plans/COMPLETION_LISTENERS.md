# Completion Listeners — Design Plan

**Status:** design only (no implementation in this PR)
**Branch:** `feature/completion-listeners`
**Author:** coding agent session for job `abe8b084-eee6-40f2-9408-c41ae345a82b`

---

## 0. Why this document exists

Today, one FlowTree workstream has no built-in way to wait on another.
Coordination happens out-of-band: an "orchestrator" agent checks
`workstream_get_job` / `workstream_context` in a poll loop, or a CI
pipeline re-submits an auto-resolve job and the agent happens to read the
prior job's result. Both are wasteful (constant polling) or fragile (the
orchestrator only learns the worker's status the next time *it* decides
to look).

We want a primitive: **a workstream B can register as a completion
listener of workstream A, and when any job on A finishes, the controller
automatically submits a "wake-up" job to B carrying a compact summary of
the finished job.** The motivating use case is a strong-model orchestrator
delegating to a fleet of cheap-model workers: the workers finish, the
orchestrator is woken, inspects results, dispatches more, and the cycle
ends by the orchestrator choosing to stop.

The whole feature is a *loop* by design. The hard part of the design —
the part this plan spends the most time on — is making sure that loop
cannot run away. Every other axis is in service of that goal.

This is a planning document only. **Do not implement this plan in this
PR.** A follow-up implementation PR will be opened after the design is
reviewed.

---

## 1. The motivating use case

A `opus` orchestrator workstream executes a large planning document by
delegating chunks to multiple `minimax` worker workstreams. Each worker
declares the orchestrator as a completion listener. When a worker job
finishes:

1. The controller fires the worker's `completionListeners` list.
2. The orchestrator receives a wake-up job whose prompt is a short
   summary: "Worker `w-foo`'s job `j-123` finished `SUCCESS` (or
   `FAILED`); its commit is `abc1234` on `feature/worker-foo`; use
   `workstream_get_job` / `workstream_context` for full details."
3. The orchestrator's wake-up job runs. The orchestrator consults its
   *own* planning doc (read at wake-up time, never pasted into the
   prompt), then **reconciles the full state of every workstream it
   has delegated to** — not only the single worker whose completion
   triggered this wake. The wake-up prompt is treated as a *trigger
   to re-check the world*, not as the authoritative list of what
   needs doing; the orchestrator queries `workstream_context` /
   `workstream_get_job` for every active worker and decides the next
   step: dispatch more workers, integrate, or stop.
4. If the orchestrator dispatches more work, those new worker jobs
   finish and re-trigger the listener. The cycle continues until the
   orchestrator decides to stop.

This is the only use case v1 needs to support cleanly. Anything more
complex (multi-hop orchestrator graphs, conditional listener activation,
priority lanes) is explicitly out of scope (see §11).

---

## 2. Design axes, trade-offs, and recommendations

### 2.1 Cascade / cycle / termination safety — THE CENTRAL CONCERN

The delegation pattern is *intentionally* a loop: worker → orchestrator
→ worker → …, bounded only by the orchestrator choosing to stop. That
makes safety the design's central problem, not an afterthought. Six
distinct sub-axes matter.

#### 2.1.1 Cycle detection in the listener graph

**What could go wrong:** A registers B as a listener; B registers A as
a listener. A job finishing on A wakes B, B does some work, B's job
finishing wakes A, A's work finishes wakes B, ping-pong forever.

Two failure modes to distinguish:

- **Self-listing** (A lists A): trivially a bug. The finished job on A
  fires A's listeners, which include A itself, so A is woken by its own
  completion. There is no plausible reading where this is intended.
- **Direct cycle** (A↔B): could be intentional ("B's success is what
  unblocks A; A's completion of the integrated result is what unblocks
  B for the next round"). But the multi-hop form is indistinguishable
  from a misconfiguration: a 3-node cycle (A→B→C→A) is a bug the same
  way a 2-node cycle usually is.
- **Longer cycle** (A→B→C→A): same as a 2-node cycle, just rarer to
  write by accident.

**Trade-offs:**

- **(i) Reject every cycle at config time.** Strongest invariant. The
  listener graph must be a DAG; cycles are 400s on
  `workstream_register` / `workstream_update_config`. Eliminates the
  ping-pong class of bugs by construction. Cost: rules out
  orchestrator-pair patterns where A↔B really is the intent.
- **(ii) Allow cycles, break them at runtime.** Compute the SCC of the
  listener graph; if an SCC has more than one node, refuse to fire
  within it. Looser; permits A↔B but only one direction's
  notification per cycle. Cost: harder to reason about ("which side
  of the cycle wins?"), still requires runtime detection.
- **(iii) Allow cycles, rely entirely on hard ceilings (§2.1.2).** The
  cheapest design: the only thing that *guarantees* safety is the
  absolute ceiling, not the graph shape. Cost: defeats the point of
  cycle detection. The orchestrator-pair user would still need the
  ceiling to be high enough for the legitimate work, which is a worse
  tradeoff than rejecting the cycle up front.

**Recommendation: (i) reject every cycle at config time, including
self-listing.** Rationale: the motivating use case is acyclic by
construction. The listener graph runs in one direction: each worker
lists the orchestrator as its completion listener, and the
orchestrator does not list the workers as its own listeners. The
edge set is `{worker → orchestrator}` and never the reverse, so the
graph is a DAG by construction. Rejecting cycles costs us nothing
for the design's use case and forecloses a whole class of
misconfiguration. A `workstream_update_config` that
would introduce a cycle returns 400 with a precise error:
`cycle: A -> B -> A`. The check is a single DFS over the registered
listener graph at config time.

If a future user has a real orchestrator-pair use case, the ceiling
in §2.1.2 plus an explicit `allowCycles: bool` opt-in can re-enable
it; that's a v2 feature.

#### 2.1.2 Hard guardrails (the absolute ceiling)

**What could go wrong:** Even with cycle detection, an acyclic graph
can still run away. Consider: 50 worker workstreams each list the
orchestrator. The orchestrator finishes one delegation round by
dispatching 50 new jobs; those 50 workers finish; the orchestrator
wakes 50 times in a tight burst. Each wake-up job submits another
round of 50. The per-round count is small (50), but the rounds are
unbounded, so the *total* is unbounded. The orchestrator's `opus`
judgment is what bounds it, but judgment can be wrong, the model can
bug out, or the operator can forget to set a budget.

**Trade-offs:**

We need hard ceilings that bound cost even if the orchestrator never
decides to stop. Model the design on
`CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES` (10, a per-job absolute
ceiling on enforcement rule re-entries — see
`flowtree/runtime/.../jobs/CodingAgentJob.java:969`). That ceiling
exists for exactly this reason: a per-rule cap is the primary
mechanism, but a bug in exhaustion tracking could let a rule
re-enter indefinitely, so an absolute cap is the safety net that
stops runaway cost "even if no other code does." Apply the same
pattern here.

Four ceilings, all enforced server-side at the controller:

1. **`maxChainDepth`** — the maximum number of edges in a single
   delegation chain. A chain is the sequence
   `A_0 → A_1 → … → A_n` where `A_{i+1}` is in
   `A_i.completionListeners`. Default: **8**. Rationale: large
   enough to express realistic orchestrator → sub-orchestrator →
   worker patterns; small enough that no legitimate graph exceeds
   it.
2. **`maxWakeUpsPerWindow`** — **this is the primary runaway / flood
   protection** (the other ceilings are defense-in-depth; see §12
   for the precise breakdown of what each bounds). The maximum
   number of wake-up jobs the controller will submit *per listener
   workstream* per `windowSeconds` time window. Default: **6 per
   600s (10 min)**.

   - **Scope:** per-listener-workstream. The counter is keyed by
     listener workstream ID, not by source workstream. A given
     listener workstream cannot be woken more than N times per
     window regardless of how many distinct source completions
     occur — the flood case (many sources waking the same listener
     repeatedly) is the scenario this ceiling is sized for.
   - **Why 6 per 600s fits the motivating use case:** the
     orchestrator manages a fleet of workers and is woken roughly
     once per round-trip, where a round-trip is "dispatch workers
     → workers finish → orchestrator processes results → dispatch
     more." Each round-trip takes minutes (the worker jobs run for
     minutes, and the orchestrator's own processing is non-trivial),
     so 6 wake-ups in 10 minutes gives roughly 1 wake-up per
     100 seconds — at least an order of magnitude more than a
     healthy orchestrator needs, while bounding the worst-case
     flood cost to a fixed, small number of wake-ups per window.
   - **Fail-safe on limit hit:** excess wake-ups are **dropped,
     not queued unboundedly**. The 7th wake-up inside the 600s
     window is rejected, a `ceiling_hit` line is logged with the
     listener ID, and the source job's completion still records
     normally. There is no retry; the counter advances; the next
     wake-up that fits the window is the one that fires. Stopping
     is the safe default — the wake-up job handler is required to
     reconcile the full state of its delegated workstreams on
     every wake (see §2.1.6), so a dropped wake-up loses no work:
     whatever was missed is picked up on the next successful wake
     by re-reading the world. The window is a sliding counter, not
     a fixed bucket, so a long quiet stretch does not accumulate
     budget.
3. **`maxActiveWakeUpJobsPerWorkstream`** — the maximum number of
   wake-up jobs the controller will allow to be simultaneously
   `STARTED` on the listener workstream. Default: **1**. Rationale:
   the one-job-per-branch model already in place (see
   `flowtree/runtime/docs/ci-integration.md:478`) extends naturally
   to wake-ups; multiple concurrent wake-ups on the same branch
   would race and one would lose the branch lock anyway. The
   existing branch lock is the floor; this ceiling is the
   documentation of the policy at the listener level.
4. **`maxWakeUpsPerSourceChain`** — the maximum number of wake-up
   jobs the controller will submit, *across all listeners*, that
   share a single `chainId` (see §2.2 for the chain identifier).
   Default: **25**. **Scope:** this bounds fan-out **breadth per
   single source event** (how many listeners one completion can
   notify in one shot), NOT recurrence across source events. The
   chain ID resets per source event: in the motivating use case
   (N workers each listing the orchestrator), every worker
   completion creates a fresh chain ID with chain count = 1, so
   the per-chain counter is effectively 1 for each wake-up. The
   ceiling still earns its keep as defense-in-depth — it bounds a
   listener-list misconfiguration where one workstream lists 25+
   listeners, preventing a single completion from spawning a
   huge immediate fan-out — but it is not, on its own, a flood
   protection. The primary recurrence ceiling is
   `maxWakeUpsPerWindow` (item 2 above). The value 25 is the same
   as `DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS`; large enough for
   a real listener fan-out, small enough to catch a
   listener-list misconfiguration.

`maxWakeUpsPerWindow` is the primary flood protection; the other
three ceilings are defense-in-depth. The four ceilings do not
provide equivalent guarantees — each bounds a different axis of
badness (recurrence, breadth, lineage depth, in-flight count), and
the per-axis scope is detailed inline above and summarized in §12.
The ceilings compound: hitting any one of them stops *that*
wake-up, not the whole system. The behavior on hitting a ceiling is
**fail-safe** (skip the wake-up, log a `ceiling_hit` line, do not
retry) — never a partial submission and never an exception. Default
values are documented in
`flowtree/runtime/.../api/FlowTreeApiEndpoint.java` next to the
related ceiling constants; they are not configurable from MCP in v1
to keep the surface area small (operators can read & edit them in
the source if a deployment legitimately needs different bounds; v2
can add an `api/controller` MCP path if that becomes painful).

#### 2.1.3 Self-trigger avoidance (the "wake-up finishing wakes itself" trap)

**What could go wrong:** Workstream A registers workstream A as a
listener. A job on A finishes. The listener fan-out fires A's
listeners, which is `{A}`. A wake-up job is submitted to A. The
wake-up job finishes. *That* finish fires A's listeners, which is
still `{A}`. Infinite.

This is a special case of the cycle rule (self-listing), so
2.1.1's `reject every cycle at config time` already catches it.
But it is worth calling out explicitly because:

- It is the most common way to write the cycle by accident.
- The check is per-edge: the DFS must reject an edge `(u, u)` even
  when the surrounding graph is empty.

**Recommendation:** the cycle check in §2.1.1 must explicitly treat
`(u, u)` as a cycle and reject the registration with error
`self-listing: workstream A cannot list itself as a completion
listener`. The test plan (§9) covers this.

#### 2.1.4 What stops the system?

Two answers, in order from "design" to "force":

1. **By design:** the orchestrator's job ends without submitting any
   new work. The listeners graph is a DAG with bounded out-degree
   (every workstream has at most `ceil(N/2)` or so listeners in
   practice). The orchestrator *chooses* to stop, and because no
   one is listening to the orchestrator from above, the chain
   terminates. This is the happy path.
2. **By force:** the ceilings in §2.1.2 trip. The chain stops not
   because anyone chose to stop, but because the controller refuses
   to spawn more wake-ups. A tripped ceiling is logged at WARN
   with the chain ID and the listener ID so an operator can see
   what happened.

Failing safe when limits are hit is the *default*. We never:

- Retry a rejected wake-up.
- Block the finished job from recording its completion (the
  finished job is the source event; it must always record normally).
- Disable the listener config (an operator-set policy, not a
  controller-set one).
- Throw an exception that bubbles out of the controller's status
  event handler.

#### 2.1.5 What stops the system *immediately* (the kill switch)

A single MCP controller endpoint:
`POST /api/config/accept-automated-jobs` already exists and rejects
`automated: true` job submissions when off (see
`flowtree/runtime/.../api/FlowTreeApiEndpoint.java:524`). **Wake-up
jobs are auto-submitted by the controller and are always
`automated: true`**, so toggling this switch to `false` halts
*all* wake-up generation immediately while leaving manual job
submissions working. This is the documented kill switch: a single
`POST /api/config/accept-automated-jobs {"accept": false}` stops
the cascade. The existing `controller_update_config` MCP tool
already exposes the gate.

The plan recommends documenting this in the workstream's
configuration docstring (Javadoc on the new `setCompletionListeners`
method) so an operator reading the code sees the kill switch
adjacent to the configuration.

#### 2.1.6 The reconciliation invariant — why dropping or coalescing wake-ups loses no work

Relying on `maxWakeUpsPerWindow` to bound recurrence (item 2 in
§2.1.2) and on the coalesce window in §2.4 to dedupe bursts only
works because the wake-up job handler is required to treat the
wake-up prompt as a *trigger to re-check the world*, not as the
authoritative list of work to do. This is a property of the
listener's wake-up handler design, and it is the reason rate
limiting is sound rather than lossy.

**The invariant.** On every wake-up, the listener's handler MUST
reconcile the *full* state of every workstream it has delegated
to, not only the single completion mentioned in the wake-up
prompt. Concretely:

- The wake-up prompt carries a compact summary of one finished
  job (§2.2). The handler reads that summary as a *signal that
  something may have changed*.
- The handler then issues its own `workstream_context` /
  `workstream_get_job` calls to inspect the state of every
  worker workstream it has dispatched, regardless of whether
  *those* workers' completions are mentioned in this particular
  wake-up.
- The handler's next step (dispatch more, integrate, stop) is
  decided from this reconciled view, not from the prompt's
  single-event mention.

**Why this makes dropping safe.** With the invariant in place,
the controller is free to drop or coalesce wake-ups aggressively
because whatever the listener missed, it picks up on the next
successful wake by re-reading the world:

- A wake-up dropped by `maxWakeUpsPerWindow` (excess over the
  6-per-600s budget) is not a lost signal — the source job's
  completion still records normally, and the next wake-up the
  listener receives (within minutes, the next round-trip) will
  re-read the worker's status and act on it then.
- A wake-up coalesced by the §2.4 30s sliding window does not
  strand the listener — the consolidated wake-up prompt
  *already* carries the full list of coalesced job IDs, and the
  handler's reconciliation pass catches anything the next round
  would have caught anyway.
- A wake-up blocked by `acceptAutomatedJobs = false` (the kill
  switch in §2.1.5) does not strand the listener either: the
  listener is in an automated-job-submission-free state by
  design, and will re-discover the world's state on the first
  manual job or first re-enabled wake-up.

**This is a design requirement, not a suggestion.** The
implementation PR must document the invariant on the wake-up
handler's entry point (the equivalent of
`CodingAgentJob.handleWakeUp` in the new
`CompletionListenerWakeUpHandler`) so that any future handler
implementer is forced to read the worker state at the start of
every wake. The safety test in §9.3
(`runawayCeilingStopsSpawning`) implicitly relies on this — the
orchestrator in that test does not need every wake-up to act
on the *prompt's* job; it only needs every wake-up to act on
*the world*. A handler that trusts the prompt without
re-reading the workers would be subtly wrong even when no
ceiling has tripped, because a controller restart can lose
in-memory coalesce state (§2.6) and force the orchestrator to
rely on the same reconciliation pass to recover.

### 2.2 Wake-up job content & context payload

**What the orchestrator needs to know, in priority order:**

1. *That* a job it was waiting for finished (it may be processing
   several in parallel).
2. *Which* job (so it can fetch the full result).
3. *How* it finished (success / failure / cancelled / degraded).
4. *A pointer* to the full result, so the orchestrator can decide
   what to do without the wake-up prompt itself being huge.

**What it does NOT need pasted into the prompt:**

- The finished job's full transcript, diff, or test output. These
  can be megabytes. The orchestrator fetches them on demand via
  `workstream_get_job` / `workstream_context` / `github_read_file`.
- The orchestrator's *own* planning doc. The orchestrator is
  already configured to read its own `planningDocument` on every
  wake-up; the planning-doc reader logic already exists in
  `InstructionPromptBuilder` and runs unconditionally when the
  workstream has a `planningDocument` set (see
  `flowtree/runtime/docs/ci-integration.md:204-205`). We do *not*
  paste the planning doc into the wake-up prompt.

**Concrete prompt shape (v1):**

```
A job you were waiting for has finished on workstream A.

  Source workstream: ws-orchestrator
  Source workstream branch: feature/plan-20260513-foo
  Finished job ID: j-abc-123
  Finished job status: SUCCESS
  Finished job description: <truncated to 200 chars>
  Commit hash (if any): 9f8e7d6c5b4a
  PR URL (if any): https://github.com/almostrealism/common/pull/298
  Trigger reason: completion listener
  Chain ID: ch-7c97e4dde
  Chain depth: 2

You are worker workstream B. Your standing goal is in your planning
document; read it before deciding what to do. To inspect the finished
job's full result, use the workstream_get_job MCP tool with the
job ID above, or workstream_context to see the workstream's recent
job history.
```

The chain ID is a stable per-trigger identifier. It is the same
across all wake-ups that descend from one source event. A workstream
that gets woken, runs, and dispatches two new workers — those two
worker wake-ups carry *different* chain IDs (they are two children
of the orchestrator's run, not the original source). This makes
the chain ID a useful budget key: ceilings count against the
chain, and the chain ID is what the operator looks up in logs when
a ceiling trips.

The chain ID is a controller-generated UUIDv4 prefixed with `ch-`,
so it is unambiguous in logs. It is not user-visible except in
this prompt and in the workstream-context log.

The chain depth is `1 + (depth of the source job)`. A direct
user-submitted job has depth 0. A wake-up fired by that job's
completion has depth 1. A wake-up fired by the depth-1 wake-up's
completion has depth 2. The `maxChainDepth` ceiling in §2.1.2
counts against this.

The finished job description is truncated to 200 characters, the
same convention used elsewhere in the controller for prompt
summarization. The orchestrator can call `workstream_get_job` for
the full description.

### 2.3 Trigger scope — which finished jobs fire the listener?

**Trade-off: fire on all completions, or only on "ones the listener
is waiting for"?**

- **All completions** is simpler. Every job on A that finishes fires
  every listener of A. An orchestrator that dispatched a
  background-poll job gets woken for that too, which is annoying
  but predictable.
- **Only on "waiting for"** requires the orchestrator to declare
  which jobs it is waiting on (a "subscription" list) and the worker
  to mark the job as "someone is listening." The subscription
  system is more code, more state, more places to go wrong, and
  changes the worker job's *contract* (the worker now has to know
  it is being listened to). The current auto-resolve contract is
  "the worker job is independent of any listener."

**Recommendation: fire on all completions.** It is the simpler
default, the cheaper implementation, and the orchestrator already
has the `workstream_context` tool to triage noise. A future v2
"subscription ID" can narrow the trigger if it becomes a real
problem.

**Success-only or also failures?** The orchestrator almost
certainly *needs* failures. If worker A's job fails, the
orchestrator must be able to react (retry, switch strategy, abort).
Firing on `SUCCESS` only would silently strand the orchestrator
on the failure path. Recommendation: **fire on SUCCESS, FAILED,
CANCELLED, and DEGRADED** — every terminal status, which is the
existing `onJobCompleted` semantics. Do not fire on `STARTED`
(start is not a completion).

### 2.4 Concurrency — coalescing bursty completions

**What could go wrong:** Worker A has 5 jobs in flight that all
finish within a 200ms window. Each fires the listener. The
listener (orchestrator B) gets 5 wake-up jobs in rapid succession.
Each wake-up job competes for the same branch lock, hits
"newer-job-exists," and the most recent one eventually runs — but
the controller did 5× the work (5 wake-up factory builds, 5 job-ID
allocations, 5 branch-lock-acquire attempts) for what could be
one wake-up carrying a "5 jobs finished" summary.

**Trade-offs:**

- **(i) No coalescing.** Each completion fires one wake-up.
  Simplest. Wastes a small amount of controller work and a small
  amount of orchestrator-token on duplicate wake-ups. The
  orchestrator can dedupe by `workstream_context` after the fact.
- **(ii) Coalesce within a short window per (sourceWorkstream,
  listenerWorkstream) pair.** When a completion would fire, check
  if another completion on the same source already fired a
  wake-up to the same listener within the last `coalesceSeconds`
  (default: 30). If so, mark the new completion as "already
  notified" and skip. The next coalesce-window wake-up will
  reference the consolidated list.

  Two implementation shapes:
  - **Persistent coalesce state** (`Map<(src, listener),
    LastFiredAt>`): controller restarts lose state. A wake-up is
    *missed* on restart, not *duplicated*. Failing-safe.
  - **In-memory coalesce state** with no persistence: same
    tradeoff; simpler.

- **(iii) Coalesce into a batched wake-up.** When the window
  expires, the consolidated wake-up carries *all* the completed
  job IDs in its prompt. More context to the orchestrator,
  but more tokens, and a delay equal to the coalesce window
  before the orchestrator learns about the first job. The
  orchestrator can't act until the window closes.

**Recommendation: (ii) with persistent state (in-memory map keyed
by `(sourceWorkstreamId, listenerWorkstreamId)`, with last-fired
timestamp and a "consolidated IDs" list).** Trade-off
justification:

- 30s of coalescing is invisible to the orchestrator's planning
  loop. The orchestrator is doing multi-minute work; a 30s delay
  is in the noise.
- The persistent state survives controller restarts cleanly: a
  restart's first completion after restart still fires a
  wake-up (the map was empty, no "already notified" entry).
  Subsequent completions in the same 30s window may double-fire,
  but the orchestrator's branch lock and `startedAfter`
  guard will dedupe at the *job* level. The coalescing is
  advisory, not authoritative.
- The consolidated wake-up prompt lists *all* job IDs that
  finished in the window. The orchestrator fetches details on
  demand.

Burst handling is bounded by `maxWakeUpsPerWindow` (§2.1.2):
6 wake-ups per 10 minutes is the absolute ceiling. Coalescing
is a soft optimization on top.

### 2.5 Configuration & wiring

**Where the listener list lives:** on the `Workstream` model as a
new field `completionListeners: List<String>`, where each string
is a *workstream ID* of the listener. This is symmetric to the
existing `dependentRepos: List<String>` field
(`flowtree/runtime/.../workstream/Workstream.java:157, 502`).
Persistence follows the same pattern: a new field on
`WorkstreamConfig.WorkstreamEntry` that round-trips through
`saveToYaml` / load.

Listeners are *workstream IDs*, not branch names or repo URLs.
A workstream ID is unambiguous (it is the same identifier the
controller already uses for routing, posting messages, and
resolving active jobs). Listing by branch would require a
resolver at wake-up time, with the same ambiguity problems that
the existing `resolveBranch` already deals with (see
`flowtree/runtime/.../slack/NotifierRegistry.java:260`); we
don't want to add a new ambiguity-prone lookup just for this.

**How it is set:**

- `workstream_register` MCP tool gains an optional
  `completion_listeners: str = ""` parameter. Empty means no
  listeners; a comma-separated list of workstream IDs is parsed
  and validated the same way `dependent_repos` is parsed (see
  `tools/mcp/manager/server.py:2131-2135`).
- `workstream_update_config` MCP tool gains the same parameter.
- The underlying HTTP `POST /api/workstreams` and
  `POST /api/workstreams/{id}/update` handlers parse the field
  the same way `dependentRepos` is parsed
  (`flowtree/runtime/.../api/WorkstreamRegistrationHandler.java:148, 306`).
- The field is stored on `Workstream` and on the YAML
  `WorkstreamEntry`. On YAML load, the listener list is
  validated: any listener ID that does not exist as a
  registered workstream at the time of load is *kept* but
  logged at WARN. A missing listener is not a hard error
  because listeners can be registered later.

**MCP wiring (the recurring failure mode the user flagged):**

The repo's history shows that adding a controller-side field
without registering the MCP tool parameter is a recurring bug
(caught by `McpToolDiscoveryTest.managerRegisterAndUpdateConfigHaveRequiredLabelsAndDependentRepos` —
see `flowtree/runtime/.../jobs/McpToolDiscoveryTest.java:662`).
For `completion_listeners`, the wiring list is:

1. Add `completion_listeners: str = ""` to the Python signatures
   of `workstream_register` and `workstream_update_config` in
   `tools/mcp/manager/server.py` — exactly mirroring the
   `dependent_repos` pattern.
2. Add the parsed value to the controller payload under the
   camelCase key `completionListeners`, parallel to how
   `dependentRepos` flows
   (`flowtree/runtime/.../api/WorkstreamRegistrationHandler.java:148`).
3. On the controller, parse the field in
   `WorkstreamRegistrationHandler` (`handleRegister` and
   `handleUpdate`).
4. Cycle-check the resulting graph at config time. The check
   runs after the workstream's other fields are set, using the
   live `notifiers.allWorkstreams()` view. A cycle returns 400
   with `error: "cycle: <path>"`.
5. The workstream tool allowlist (`AR_MANAGER_TOOL_NAMES` /
   `EXCLUDED_AR_MANAGER_TOOLS` in
   `flowtree/runtime/.../jobs/McpConfigBuilder.java:94, 143`)
   is unchanged: `workstream_register` and
   `workstream_update_config` are *already* in the
   `EXCLUDED_AR_MANAGER_TOOLS` set (admin tools), so a coding
   agent cannot register or update its own listener list.
   Operators do that via the controller's REST API or via the
   Slack listener.
6. `McpToolDiscoveryTest.managerRegisterAndUpdateConfigHaveRequiredLabelsAndDependentRepos`
   (or a new sibling test) must assert that
   `workstream_register` and `workstream_update_config` declare
   `completion_listeners` in their signatures. The test's
   pattern is in §9.
7. `McpConfigBuilderTest.allowlistCoversEveryArManagerTool` and
   `allowlistAndExclusionsAreDisjoint` must still pass (the
   tools themselves aren't being added, so this is automatic,
   but the test is a tripwire).

**Interaction with `acceptAutomatedJobs`:** wake-up jobs are
submitted with `automated: true`. The existing
`acceptAutomatedJobs` gate
(`flowtree/runtime/.../api/FlowTreeApiEndpoint.java:524`)
therefore *also* gates wake-up job submission. Toggling the
gate off (operator kill switch — see §2.1.5) halts all wake-up
generation. This is the intended behavior; the gate's existing
purpose ("don't let automated jobs submit while we debug CI")
extends naturally to "don't let the listener cascade run while
we debug it." The wake-up submit path must log at INFO when a
wake-up is rejected because the gate is closed, distinct from
a ceiling rejection.

**Interaction with the existing auto-resolve machinery:** the
auto-resolve loop (CI failure → submit job → push → CI runs
→ resubmit on failure) is unaffected. A wake-up job is a
*new* kind of automated job that does not exist today, so it
is additive. The `startedAfter` guard
(`FlowTreeApiEndpoint.java:612`) still applies to wake-up
submissions: if the listener workstream already has a more
recent job, the wake-up submission returns `skipped: true`
with `reason: "Newer job exists on this workstream"`. This is
the right behavior — the wake-up is "advisory" and a manual
or already-pending job is more authoritative.

**Interaction with the branch lock:** the existing one-job-per-
branch model (see `flowtree/runtime/docs/ci-integration.md:478`)
already prevents two jobs on the same branch from running
concurrently. The wake-up path *uses* this as the floor:
`maxActiveWakeUpJobsPerWorkstream = 1` (§2.1.2) is enforced by
the same mechanism, not by an additional lock. We do not need
to add a new lock for wake-ups.

### 2.6 Failure & edge cases

**Listener workstream is busy / has an in-flight job.** The
`startedAfter` guard (§2.5) means the wake-up submission is
skipped if a more recent job is already in flight. The
controller logs a `wakeup_skipped_busy` line. The finished job
on the source still records normally; the listener is just
not woken *this* time. An orchestrator that polls
`workstream_context` will see the new finished job in the
listening workstream's history regardless. The wake-up is a
notification optimization, not a guarantee.

**Listener workstream was deleted or has never been
registered.** At wake-up time, the controller resolves the
listener ID against `notifiers.allWorkstreams()`. A missing
listener logs a `wakeup_listener_missing` line at WARN and
silently drops the wake-up. The finished job still records
normally. The source workstream is unaffected.

**The finished job was itself a wake-up job.** The listener
graph is acyclic, so a wake-up on A that fires A's listeners
(including B) is fine, but B's wake-up firing A's listeners
would be a cycle. The cycle check (§2.1.1) catches this at
config time. At runtime, a wake-up job's completion is treated
identically to a non-wake-up job's completion: its listeners
are fired. There is no special "wake-up jobs are silent" rule,
because the cycle rejection at config time already prevents the
bad case. (We do mark the wake-up as
`triggerReason: "completion listener"` in `JobStatsStore` so
operators can filter the noise; see §6.)

**Self-listing.** Rejected at config time (§2.1.3).

**Direct cycle (A↔B).** Rejected at config time (§2.1.1).

**Workstream-loss defect (the existing defect class).** A
listener that disappears after registration is handled by the
"missing listener" branch above. The existing workstream-loss
defect work (`docs/plans/FLOWTREE_RELAY_FAILURE_DETECTION.md`)
addresses in-flight job loss, not listener disappearance;
this plan does not interact with that work.

**Controller restart.** All in-memory state is lost. Specifically:

- The `maxWakeUpsPerWindow` counter resets to 0 — a restart
  gives the listener a fresh budget. This is the safer
  direction; an attacker (or a bug) cannot "use up" the budget
  and then wait for a restart to clear it.
- The coalesce state in §2.4 is lost — a restart may double-fire
  the first completion after restart, but the existing
  `startedAfter` guard and branch lock still prevent
  double-execution of the *orchestrator's job* (the orchestrator
  may see a wake-up about an already-known finished job and
  process it again, which is idempotent at the planning-doc
  level).
- The cycle check is re-run on every `workstream_register` /
  `workstream_update_config` call, so a transient config drift
  is re-validated on the next update.

**Workstream archived but listeners not cleared.** Archive
(`workstream_archive`) does not currently clear the listener
graph. Archived workstreams are hidden from `workstream_list`
but their listeners are still consulted. The plan recommends
*not* clearing listeners on archive in v1: an archived
workstream can be unarchived, and clearing on archive would
silently break a delegation chain across the archive
boundary. The behavior is documented on the
`setArchived` Javadoc: "Archive does not clear the listener
graph. An archived source workstream will continue to wake its
listeners."

**Listener archive in the other direction (the listener is
archived).** A wake-up submission to an archived workstream is
allowed in v1 — the controller has a uniform submission path
and an archived workstream is technically still routable. This
is slightly surprising; a future v2 can short-circuit "listener
is archived" to a `wakeup_listener_archived` log line and skip
the wake-up. v1 ships without this.

**Job submitted manually *and* by a wake-up at the same time.**
The `startedAfter` guard handles the race: whichever
submission's `startedAfter` is more recent wins, the other
returns `skipped: true`. The orchestrator that receives a
manual job from a human and a wake-up about an old finished
job at the same moment will run the manual job first; the
wake-up is dropped (the orchestrator will see the finished
job via `workstream_context` next time it looks).

---

## 3. Recommended design — summary

A workstream declares a list of completion listener workstream IDs.
The controller, on every job completion (any terminal status except
`STARTED`), fires the listener fan-out for the *source* workstream.
For each listener:

1. **Cycle check at config time** (DFS over the live listener graph)
   rejects any registration that would create a cycle, including
   self-listing. The 400 error message names the cycle path.
2. **Coalesce** within a 30-second sliding window per
   `(sourceWorkstreamId, listenerWorkstreamId)` pair. The first
   completion in a window fires a wake-up; subsequent completions
   in the same window are added to a "consolidated IDs" list and
   do not fire additional wake-ups.
3. **Apply four hard ceilings**, in order, per-listener. These do
   NOT provide equivalent guarantees — each bounds a different
   axis of badness. The primary recurrence / flood protection is
   `maxWakeUpsPerWindow`; the other three are defense-in-depth:
   - `maxWakeUpsPerWindow = 6 per 600s` (sliding counter) —
     **PRIMARY**: bounds how many times a given listener can be
     woken in a window, regardless of source count. Dropped
     wake-ups are safe because the wake-up handler reconciles
     the full state of its delegated workstreams on every wake
     (§2.1.6).
   - `maxChainDepth = 8` (counts edges in the source chain) —
     bounds lineage depth of an automatically-fired chain.
   - `maxActiveWakeUpJobsPerWorkstream = 1` (existing branch
     lock is the floor) — bounds in-flight wake-ups per
     listener; documents the policy of the existing branch
     lock at the listener level.
   - `maxWakeUpsPerSourceChain = 25` (chain-keyed counter) —
     bounds fan-out **breadth per single source event** (how
     many listeners one completion notifies); resets per
     source event. This is NOT a flood protection on its own
     (§2.1.2 item 4).
   Hitting any ceiling logs `ceiling_hit` at WARN with chain ID
   and listener ID, and skips the wake-up. No retries.
4. **Submit the wake-up** as a coding-agent job with
   `automated: true`, `startedAfter: now` (so an in-flight job
   wins the race), a generated `chainId`, and a compact prompt
   carrying: source workstream ID, finished job ID, status,
   description (truncated to 200 chars), commit hash (if any),
   PR URL (if any), chain ID, chain depth, and a pointer to
   `workstream_get_job` / `workstream_context` for full details.
5. **On hit of the `acceptAutomatedJobs` kill switch** (gate
   `false`), the controller skips *all* wake-up submissions
   globally and logs `wakeup_kill_switch_active` at WARN. The
   source job's completion still records normally.

The chain ID is a controller-generated UUIDv4 prefixed with `ch-`.
It is the same across all wake-ups descending from a single source
event. New work submitted by a wake-up job gets a new chain ID
(the chain forks, not extends — a wake-up is a *new* root).

The cycle DFS runs at config time only, not on every completion.
The coalesce and ceiling state are in-memory (controller-local),
reset on controller restart. The cycle check is re-run on every
config update, so it survives restarts.

---

## 4. Implementation outline

A future PR implementing this plan should land the changes in this
order. Each phase ends with a passing build + relevant tests, and
each phase is independently reviewable.

### Phase A — Data model

1. `Workstream.java`: add `private List<String> completionListeners;`
   with getter / setter and a JSON-serializable toString. Default
   empty list.
2. `WorkstreamConfig.WorkstreamEntry.java`: add the corresponding
   field, getter, setter, and update `toWorkstream()` to copy it
   onto the `Workstream` instance.
3. `WorkstreamRegistrationHandler`: parse `completionListeners` from
   the request body in `handleRegister` and `handleUpdate`, set on
   the workstream. Empty body field is treated as "no change."
4. The change is wiring-only at this phase; no cycle check, no
   fan-out. Existing tests should continue to pass with the new
   field defaulting to empty.

### Phase B — Cycle check

5. Add a `ListenerCycleChecker` utility in
   `flowtree/runtime/.../workstream/` (package placement TBD with
   reviewer) with a single static method
   `check(List<String> proposedListeners, Workstream self, Map<String, Workstream> all) -> List<String>` returning the
   cycle path or empty on success.
6. Call it in `WorkstreamRegistrationHandler.handleRegister` and
   `handleUpdate` after the workstream is fully populated but
   before `listener.registerAndPersistWorkstream`. On cycle,
   return 400 with the path.

### Phase C — Fan-out

7. Add a `CompletionListenerFanout` class
   (`flowtree/runtime/.../jobs/CompletionListenerFanout.java`)
   with a method
   `fanout(String sourceWorkstreamId, JobCompletionEvent event, NotifierRegistry notifiers)`.
8. In `FlowTreeApiEndpoint.handleStatusEvent`, after
   `targetNotifier.onJobCompleted`, call
   `fanout.fanout(...)`. The fanout is a no-op if the source
   workstream has no listeners.
9. The fanout enforces the four ceilings in §2.1.2. Each ceiling
   is a private method on the fanout class with its own
   `ceiling_hit` log line. The ceilings are constants on the
   fanout class (or on `CodingAgentJob` next to the existing
   ceiling constants, for consistency).

### Phase D — Wake-up job construction

10. The fanout builds a `CodingAgentJob.Factory` for the listener
    workstream, populating it from the *listener* workstream's
    config (tools, budget, planning document, phase config) — not
    the source workstream's. The orchestrator wakes up as itself.
11. The factory carries `automated: true`, `startedAfter: now`,
    and a prompt shaped as in §2.2. The prompt references
    `workstream_get_job` and `workstream_context` for follow-up
    detail. The factory is added to the server via
    `server.addTask(factory)`, the same path
    `FlowTreeApiEndpoint.handleSubmit` uses
    (`FlowTreeApiEndpoint.java:855`).

### Phase E — Coalescing

12. The fanout keeps a `Map<String, CoalesceState>` keyed by
    `sourceWorkstreamId + "|" + listenerWorkstreamId`. Each
    `CoalesceState` has `lastFiredAt` and `consolidatedJobIds`.
    On a fan-out call:
    - If a state exists and is within `coalesceSeconds` of
      `now`, append the new job ID to `consolidatedJobIds` and
      skip.
    - Otherwise, fire the wake-up with the current job ID as
      the "primary" and any prior `consolidatedJobIds` as
      additional context.
13. The map is in-memory, no persistence, no eviction beyond
    natural GC pressure. A production deployment with a steady
    fan-out rate may need a periodic cleanup; v1 leaves the
    map unbounded and notes the limitation.

### Phase F — Tests

14. Add the unit and integration tests in §9. The
    `McpToolDiscoveryTest` wiring test is mandatory before any
    MCP tool can be touched.

### Phase G — Docs

15. Update `flowtree/runtime/docs/ci-integration.md` with a
    "Completion Listeners" section describing the feature,
    the kill switch, and the ceilings. Update
    `flowtree/runtime/docs/configuration.md` to document the
    new YAML field.

---

## 5. Config & MCP wiring checklist

The recurring MCP registration failure mode is documented in
`docs/plans/MCP_TOOL_REGISTRATION_RULES.md`. The wiring list for
this plan is:

- [ ] `tools/mcp/manager/server.py` — `workstream_register` gains
      `completion_listeners: str = ""` in its signature and a
      corresponding docstring entry.
- [ ] `tools/mcp/manager/server.py` — `workstream_update_config`
      gains the same parameter and docstring entry.
- [ ] `tools/mcp/manager/server.py` — body of both tools parses
      the comma-separated string into a list of workstream IDs,
      rejecting empty strings within the list. Pattern is
      `_parse_dependent_repos` (already in the file).
- [ ] `tools/mcp/manager/test_server.py` — add the new field to
      the expected set in `TestToolRegistration.test_expected_tool_count`
      and add a test that exercising the field with a valid
      workstream ID does not error.
- [ ] `flowtree/runtime/.../jobs/McpToolDiscoveryTest.java` —
      add a sibling test
      `managerRegisterAndUpdateConfigHaveCompletionListeners`
      that asserts both tools declare `completion_listeners`
      in their signatures.
- [ ] `flowtree/runtime/.../jobs/McpConfigBuilder.java` — verify
      `AR_MANAGER_TOOL_NAMES` and `EXCLUDED_AR_MANAGER_TOOLS`
      still cover every tool (no change expected; the test
      `allowlistCoversEveryArManagerTool` is the tripwire).
- [ ] `flowtree/runtime/.../workstream/Workstream.java` — add
      `completionListeners` field, getter, setter, JSON entry
      in `toString()`.
- [ ] `flowtree/runtime/.../workstream/WorkstreamConfig.java`
      `WorkstreamEntry` — add the same field, getter, setter,
      copy in `toWorkstream()`.
- [ ] `flowtree/runtime/.../api/WorkstreamRegistrationHandler.java`
      — parse `completionListeners` in both `handleRegister`
      and `handleUpdate`; pass through the cycle checker.

---

## 6. Data model additions (no schema migration required)

The only persistent state added to `JobStatsStore` is the
`triggerReason` column on the `job_timing` table. The DDL
(`JobStatsStore.java:53-75`) does an `IF NOT EXISTS` create, so
adding a column requires a small migration step:

```sql
ALTER TABLE job_timing ADD COLUMN trigger_reason VARCHAR(64) DEFAULT 'manual'
```

`triggerReason` is one of `manual` (default — user-submitted),
`auto_resolve` (CI auto-resolve), or `completion_listener`
(wake-up). The value is set by the `CodingAgentJob.Factory`
based on which path built it. Operators can filter
`JobStatsStore.getRecentJobs` by `triggerReason` to find wake-up
jobs in a sea of manual ones.

`JobCompletionEvent` does *not* grow a new field. The wake-up
prompt is constructed by the fan-out from the existing
`JobCompletionEvent` and the listener's own workstream config.
The `triggerReason` lives on the wake-up job's *own* record, not
on the source event that fired it.

---

## 7. Out of scope for v1 / future

These are explicitly deferred to keep v1 small and reviewable.
Each is mentioned with a brief rationale.

- **Multi-hop listener graphs (A→B→C, where A's completion wakes
  B, B's completion wakes C as a *continuation* of the same
  chain).** A two-hop chain is supported (depth 2 in §2.1.2),
  but a 3+ hop chain that shares a chain ID is not modeled
  separately; each wake-up starts a new chain ID at the
  orchestrator's level. Reasoning for deferral: the motivating
  use case is two-hop (orchestrator → workers); multi-hop is a
  separate research question.
- **Per-listener trigger filters** (fire only on `SUCCESS` only,
  fire only on jobs with a specific tag, fire only on a specific
  source-job ID). Deferral rationale: §2.3's "fire on all
  completions" is the simpler default and the orchestrator can
  triage noise via `workstream_context`.
- **Priority / lane separation** for wake-up jobs (run wake-up
  jobs on a dedicated agent pool). Deferral rationale: v1
  treats wake-ups as ordinary coding-agent jobs; the existing
  routing (`requiredLabels`, `phaseConfigs`) is enough for v1.
- **Coalesce window persistence.** v1 uses an in-memory map;
  a controller restart resets coalesce state. A future
  enhancement could persist the map to HSQLDB alongside
  `JobStatsStore`.
- **Configurable ceilings via MCP.** v1 ships the ceilings as
  constants in the controller source. Operators edit them in
  code; a v2 `controller_update_config` extension can add
  fields. The point of v1 is to keep the surface area small
  and the safety guarantee uniform across deployments.
- **Allowing cycles under explicit opt-in** (`allowCycles: bool`).
  v1 rejects all cycles. A v2 feature can relax this if a real
  orchestrator-pair use case emerges.
- **Wake-up job that is itself a "do nothing" prompt** (the
  listener just inspects state and exits). v1 always submits
  a coding-agent job; the orchestrator's *own* logic decides
  what to do on a wake-up. A "read-only" wake-up is a v2
  optimization.
- **Wake-up fan-out to non-workstream entities** (e.g. directly
  to a Slack channel, or to an external webhook). v1 is
  workstream-to-workstream only.
- **Wake-up precedence over manually-submitted jobs.** v1
  uses the `startedAfter` guard, so a manual job submitted
  *after* a wake-up wins. A future enhancement could let
  the listener mark itself "wake-up preempts manual" via a
  per-workstream config flag.

---

## 8. Open questions

Items the reviewer should weigh in on:

1. **Default ceiling values.** §2.1.2 picks numbers out of
   thin air. Realistic numbers depend on how aggressively
   orchestrators fan out. A user with 50 workers may want
   `maxWakeUpsPerWindow` higher than 6; a paranoid user may
   want it lower. The defaults are documented as constants
   in the controller source for v1 — reviewer should weigh
   in before the implementation PR is opened.
2. **Coalesce window length (30s).** Aggressive coalescing
   reduces controller work and orchestrator-token burn at
   the cost of latency. 30s is a guess. A shorter window
   (5s) preserves more immediacy; a longer window (120s)
   reduces duplicate wake-ups in heavier fan-out patterns.
3. **Chain ID format.** `ch-<uuid>` is a placeholder. A
   shorter format (`<src>-<seq>`) is more greppable in logs
   but leaks the source workstream ID into the chain key,
   which has subtle security implications (an operator
   reading the log learns which workstreams the chain
   involved). v1 ships with the UUID form for safety.
4. **Wake-up prompt truncation of "description".** 200 chars
   is the same convention used elsewhere
   (`CodingAgentJob.summarizePrompt`). For very long job
   descriptions this loses information; the orchestrator
   has `workstream_get_job` for the full version. A
   reviewer may want this to be configurable.
5. **Whether cycle rejection is per-edge or per-SCC.** A
   2-node SCC (A↔B) is rejected. A 1-node self-loop is
   rejected. A 3-node cycle (A→B→C→A) is also rejected.
   The DFS in §2.1.1 catches all of these uniformly. The
   question is whether the *error message* should be
   uniform ("cycle: ...") or differentiate ("self-listing
   vs. mutual listing vs. longer cycle"). v1 ships with
   the uniform message; a v2 can differentiate.
6. **Wake-up for shell-command jobs.** A `shell` job
   (`flowtree/runtime/.../jobs/ShellCommandJob.java`) on the
   source workstream fires the listener. Is that intended?
   A shell command is a single shell invocation, often a
   CI helper; waking the orchestrator on its completion is
   probably wrong. v1 fires on shell jobs too (uniform
   semantics); a v2 can filter. Reviewer should weigh in.
7. **Backwards compatibility of YAML.** Adding a new field
   to `WorkstreamConfig.WorkstreamEntry` is backwards
   compatible (older YAMLs without the field load with an
   empty listener list, which is the v0 behavior). Older
   controllers loading newer YAMLs ignore the unknown field
   (the existing `@JsonIgnoreProperties(ignoreUnknown = true)`
   on `WorkstreamConfig` covers this). No migration
   required. Reviewer should confirm.

---

## 9. Test plan

The implementation PR (not this one) must land with all of the
following tests green. The tests are the safety contract: if a
ceiling doesn't halt runaway spawning, the test is wrong.

### 9.1 Unit tests

- **`ListenerCycleCheckerTest`** (new) in
  `flowtree/runtime/.../workstream/`:
  - `emptyListenersReturnsEmpty`
  - `singleNonSelfListenerReturnsEmpty`
  - `selfListingReturnsPath` (A→A)
  - `mutualListingReturnsPath` (A→B→A)
  - `threeNodeCycleReturnsPath` (A→B→C→A)
  - `disjointListenersReturnEmpty` (A→B, A→C, no edges back)
  - `diamondReturnsEmpty` (A→B, A→C, B→D, C→D — diamond, not a
    cycle)
  - `proposedListenersAreCheckedBeforePersist` (a
    registration that *would* create a cycle is rejected
    before the workstream is registered)

- **`CompletionListenerFanoutTest`** (new) in
  `flowtree/runtime/.../jobs/`:
  - `noListenersNoOp` (a finished job on a workstream with
    no listeners fires no wake-ups)
  - `singleListenerFiresOnce`
  - `burstInCoalesceWindowFiresOnce` (5 completions in
    <30s on (A, B) → 1 wake-up, with the 5 job IDs in the
    consolidated list)
  - `burstOutsideCoalesceWindowFiresMultiple`
  - `ceilingMaxChainDepthStopsFurtherWakes` (depth 9 source
    → no wake-up, `ceiling_hit` log line)
  - `ceilingMaxWakeUpsPerWindowStops` (7 wake-ups in
    <600s → 7th rejected, `ceiling_hit` log)
  - `ceilingMaxWakeUpsPerSourceChainStops` (26 wake-ups
    on chain `ch-xxx` → 26th rejected, `ceiling_hit` log)
  - `acceptAutomatedJobsFalseBlocksAllWakes`
  - `finishedWakeUpJobDoesNotFireItself` (a wake-up job's
    own completion on the listener does not fan out to
    its own listener list — the cycle was rejected at
    config time, so this is just a defensive test)
  - `missingListenerIsLoggedAndSkipped` (a listener ID
    that does not exist in `notifiers.allWorkstreams()`
    causes a `wakeup_listener_missing` log and no wake-up)
  - `killSwitchBlocksWakesBeforeAnyOtherCheck`
    (the kill-switch check runs first, before cycle /
    coalesce / ceilings, so a tripped kill switch is the
    cheapest way to stop the system)
  - `wakeUpJobFactoryCarriesAutomatedTrue`
  - `wakeUpJobFactoryCarriesStartedAfterNow`
  - `wakeUpPromptContainsChainIdAndDepth`
  - `wakeUpPromptDoesNotContainFinishedJobTranscript`
    (the prompt is compact; full transcript is fetched
    on demand)

- **`WorkstreamCompletionListenersConfigTest`** (new) in
  `flowtree/runtime/.../workstream/`:
  - `roundTripYmlPreservesListeners` (write a workstream
    with a listener list, save YAML, load YAML, assert
    the listener list is preserved)
  - `emptyListenersRoundTrip` (a workstream with no
    listeners round-trips cleanly; older YAML without the
    field loads with an empty list)
  - `unknownListenerIdIsKeptAndLogged` (a listener that
    does not exist at YAML-load time is preserved and
    emits a WARN log; not a hard error)

### 9.2 Integration tests

- **`McpToolDiscoveryTest.managerRegisterAndUpdateConfigHaveCompletionListeners`**
  (new) — asserts both `workstream_register` and
  `workstream_update_config` declare `completion_listeners`
  in their signatures. Mirrors the existing
  `managerRegisterAndUpdateConfigHaveRequiredLabelsAndDependentRepos`
  test (`McpToolDiscoveryTest.java:662`).
- **`TestToolRegistration.test_completion_listeners_field_parsed`**
  (new, in `tools/mcp/manager/test_server.py`) — calls
  `workstream_register` with `completion_listeners` set to a
  valid workstream ID and asserts the field is plumbed through.

### 9.3 Safety tests (the cascade-actually-halts tests)

These are the most important tests in this PR. They are
expensive (they construct a controller, register a workstream,
fire many completions) and run at `@TestDepth(2)` or higher.

- **`CompletionListenerSafetyIntegrationTest.runawayCeilingStopsSpawning`**
  (new) — registers an orchestrator and 10 workers. Each
  worker lists the orchestrator. Fires 100 completions on a
  single worker. Asserts: at most `maxWakeUpsPerWindow` wake-ups
  are submitted; the rest are rejected with `ceiling_hit` log
  lines. The orchestrator is *not* flooded.
- **`CompletionListenerSafetyIntegrationTest.killSwitchHaltsCascade`**
  (new) — same setup. Fires 10 completions. Asserts 10 wake-ups
  are queued. Sets `acceptAutomatedJobs = false` via
  `controller_update_config`. Fires 10 more completions.
  Asserts *zero* new wake-ups are submitted, and every
  `wakeup_kill_switch_active` log line is present.
- **`CompletionListenerSafetyIntegrationTest.cycleConfigRejected`**
  (new) — registers A, B. Tries to update A to list B as a
  listener. Asserts success. Tries to update B to list A as a
  listener. Asserts a 400 with error matching `cycle:`.
- **`CompletionListenerSafetyIntegrationTest.selfListingRejected`**
  (new) — registers A. Tries to update A to list A as a
  listener. Asserts a 400 with error matching `self-listing`.

### 9.4 Test integrity assertions

Per the project's CLAUDE.md rules:

- Every new test has a `timeout` parameter.
- No `@Disabled` on any safety test. The safety tests must
  run; if they fail, the implementation is wrong, not the
  test.
- No tolerance weakening. A safety test that asserts "at most
  N wake-ups" does not get relaxed to "at most N+5 wake-ups"
  to make a buggy implementation pass.
- No `detect-test-hiding` violations. Tests that *look* like
  they assert safety but actually weaken the assertion are
  the failure mode. The safety tests must use the explicit
  exact-count assertions above.

---

## 10. Acceptance criteria

The implementation PR is "done" when:

1. All tests in §9 pass at `@TestDepth(2)` and the safety tests
   pass at `@TestDepth(3)`.
2. The MCP wiring checklist in §5 is complete: every checkbox
   is ticked, the corresponding tests are green, and
   `McpConfigBuilderTest.allowlistCoversEveryArManagerTool`
   and `McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy`
   both pass.
3. `mvn clean install -DskipTests` succeeds (full project
   build).
4. `mvn test -pl flowtree/runtime` succeeds (or, for the
   safety tests, the targeted runner invocation in the build
   validator).
5. The four ceilings are constants in the controller source
   with Javadoc, alongside the existing
   `CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES`.
6. The kill switch (existing `acceptAutomatedJobs` gate) is
   documented in the Javadoc on `setCompletionListeners` and
   in `flowtree/runtime/docs/ci-integration.md`.
7. The `triggerReason` column on `job_timing` exists, has a
   default of `manual`, and is set to `completion_listener` on
   wake-up jobs.

---

## 11. Relationship to the planning document's broader goal

This plan implements one specific feature called out by the
branch's planning document (`docs/plans/COMPLETION_LISTENERS.md`,
which is this very file once it is committed). The broader
goal — orchestrator / worker delegation — depends on this
feature, on the existing `workstream_context` and
`workstream_get_job` tools, and on the planning document
sub-feature. No other planned feature on the branch is blocked
on this work. The implementation PR for this plan is the
*first* of several PRs that will land the broader goal.

---

## 12. Summary of safety guarantees (the only section a hurried reviewer needs)

The four protections below are **not equivalent guarantees**. Each
bounds a distinct axis of badness. Conflating them — e.g. reading
"four ceilings" as four redundant ways to stop a flood — is
exactly the misreading this section is here to prevent.

- **Cycle rejection at config time** (DFS over the live graph
  including self-listing). A bad graph cannot exist. This is a
  *config-time* guarantee, not a runtime cap; it forecloses the
  ping-pong class of bugs by construction.

- **`maxWakeUpsPerWindow = 6 per 600s`** (per-listener, sliding
  counter) — **PRIMARY flood / recurrence protection.** Bounds
  how many times a given listener workstream can be woken in a
  10-minute window, regardless of how many distinct source
  completions occur. Excess wake-ups are **dropped, not queued**;
  the 7th wake-up inside the window is rejected and logged at
  WARN. This is the single guard that actually bounds the
  runaway-orchestrator / flood case the design is protecting
  against. The other three ceilings below are defense-in-depth;
  on their own, none of them stops a flood.

- **Dropping is sound because of the reconciliation invariant**
  (§2.1.6). The wake-up job handler is required to reconcile
  the *full* state of every workstream it has delegated to on
  every wake, not only the single completion mentioned in the
  wake-up prompt. A dropped wake-up therefore loses no work:
  whatever was missed is picked up on the next successful wake
  by re-reading the world. This invariant is required, not
  optional, and the wake-up handler's entry point must document
  it.

- **`maxWakeUpsPerSourceChain = 25`** — bounds fan-out **breadth
  per single source event** (how many listeners one completion
  can notify in one shot), NOT recurrence across source events.
  The chain ID resets per source event (§2.2), so in the
  motivating use case (N workers each listing the orchestrator)
  the per-chain counter is effectively 1 per wake-up.
  Defense-in-depth against a listener-list misconfiguration (one
  workstream listing 25+ listeners), not a flood protection.

- **`maxChainDepth = 8`** — bounds the lineage depth of an
  automatically-fired chain. Not a flood protection.

- **`maxActiveWakeUpJobsPerWorkstream = 1`** — bounds in-flight
  wake-ups per listener. Reuses the existing branch lock as the
  floor. Not a flood protection; documents the policy of the
  existing branch lock at the listener level.

- **Coalescing within 30s windows** (advisory; not a guarantee,
  but reduces fan-out work). Combined with the reconciliation
  invariant, coalesced wake-ups lose no work — the consolidated
  wake-up prompt already lists the coalesced job IDs and the
  handler's reconciliation pass catches anything the next round
  would have caught anyway.

- **Kill switch** via the existing `acceptAutomatedJobs` gate:
  `false` halts *all* wake-up generation globally. This is the
  force-stop, not a ceiling. Combined with the reconciliation
  invariant, the listener is never stranded: the first manual
  job or first re-enabled wake-up re-reads the world and resumes.

- **Fail-safe behavior on every limit hit**: skip the wake-up,
  log a precise reason, do not retry, do not block the finished
  job from recording. The source job always records normally.

- **No new Maven modules.** Configuration is added to the
  existing `Workstream` / `WorkstreamConfig` data model.

- **No new model / effort / runner params.** The MCP wiring
  follows the existing `dependent_repos` /
  `required_labels` / `phase_configs` patterns.

- **No new lock.** The existing branch lock is reused as the
  "max one wake-up in flight per workstream" floor.
- **English only.** The plan, the Javadoc, the log lines, the
  prompt text, and the test descriptions are all in English.
