# FlowTree Workstream Job Controls

Status: §3 (per-workstream agentEnv injection) implemented; §1, §2, §4 proposed
Owner: TBD

## TL;DR

These features let an external system drive flowtree as its execution backend
for both **short interactive jobs** and **long-running multi-step jobs** —
where the external system, not flowtree, is the system of record for the work
product, and flowtree provides the per-workstream agent execution. They are
general-purpose flowtree capabilities, useful to any such integration.

Features:

1. **Per-submission resource profiles** — let one workstream run both short
   jobs and long jobs with different turn/budget/timeout limits.
2. **Merge-on-completion reconciliation** — let multiple jobs run concurrently
   against the same repo without locking, reconciling scratch-file changes at
   push time using the existing base-branch conflict-resolution flow.
3. **Per-workstream `agentEnv` injection** — pass per-workstream environment
   into the agent process (e.g. a tenant identifier or a runtime secret name
   that a workstream-scoped MCP reads at startup).
4. **Completion signal** — a deterministic "job done" notification (webhook,
   with polling as the fallback) so the caller can update its own state.

GitHub App credentials per workspace — a related capability — are specified
separately in `GITHUB_APP_CREDENTIALS.md`.

All work is in the `almostrealism/common` repo under `flowtree/`. The agent
runtime is **agent-agnostic**: these features sit below the `AgentRunner` SPI
and work regardless of whether the workstream runs OpenCode, Claude Code, or
another runner.

## 1. Per-submission resource profiles

### Today

`maxTurns`, `maxBudgetUsd`, and the inactivity timeout are configured **per
workstream** (see `workstreams-example.yaml`). Every job on a workstream gets
the same envelope.

### Problem

An interactive integration runs two very different job shapes on the **same**
workstream:

- A **short job** — a few tool calls, seconds to ~a minute.
- A **long job** — many tool calls over potentially several minutes and many
  turns.

A single workstream-level limit can't serve both: size it for the short job and
the long one gets killed; size it for the long one and a runaway short job is
expensive.

### Proposal

Accept optional resource overrides on the submission body:

```json
POST /api/workstreams/<wsId>/submit
{
  "prompt": "...",
  "maxTurns": 80,
  "maxBudgetUsd": 3.0,
  "inactivityTimeoutSeconds": 180
}
```

- Each override is **clamped to the workstream-level ceiling** (a submission can
  request *less* than the workstream cap, never more — the workstream remains
  the hard upper bound).
- Optionally support named **profiles** in `workstreams.yaml` so callers send a
  profile name instead of raw numbers:

  ```yaml
  resourceProfiles:
    quick: { maxTurns: 20, maxBudgetUsd: 0.50, inactivityTimeoutSeconds: 60 }
    long:  { maxTurns: 80, maxBudgetUsd: 3.00, inactivityTimeoutSeconds: 180 }
  ```

  Submission sends `"profile": "long"`; the controller resolves it, still
  clamped to the workstream ceiling.

### Integration points

- `FlowTreeApiEndpoint.handleSubmit()` — parse overrides/profile, clamp.
- `CodingAgentJobFactory` / `CodingAgentJob` — carry the resolved limits onto
  the job instead of always reading workstream defaults.
- The inactivity monitor (`ClaudeInactivityMonitor` and the equivalent in other
  runners) — honor the per-job inactivity timeout.

## 2. Merge-on-completion reconciliation (concurrent jobs per repo)

### Today

A job operates on a per-workstream working directory, and flowtree effectively
runs one job per workstream at a time. `GitRepositorySetup.prepare()` syncs the
working copy at **start** (`fetch`, checkout, ff-pull/reset, then merge the base
branch). When the base-branch merge conflicts, `synchronize()`
(`GitRepositorySetup.java:216`) deliberately leaves the merge **in progress with
conflict markers** so the coding agent resolves them in place, and
`GitCommitHandler` then produces a proper two-parent merge commit.

### Problem

An integration may want **concurrent** jobs against one workstream's repo — e.g.
a long job and a quick job at the same time. Serializing them means a long job
blocks the quick one. When the durable work product is written to the external
system (via MCP tools), **not** to git — so the git repo only holds the agent's
**scratch** files — the only thing two concurrent jobs can collide on is those
scratch files, which are append-mostly. We don't need locking; we need
**reconciliation at push time**.

### Proposal

Add a **completion-time reconciliation** step to the commit/push tail
(generalizing `synchronize()` to run at the end against the *target* branch,
not just at the start against the *base* branch):

1. When a job finishes and is about to push, `git fetch origin` and check
   whether `origin/<targetBranch>` advanced since the job started.
2. If it did, merge `origin/<targetBranch>` into the local branch.
3. **Clean merge** → commit and push.
4. **Conflict** → reuse the existing flow: leave the merge in progress with
   conflict markers and **kick off the same conflict-resolution we use for
   base-branch sync** (agent resolves markers → `GitCommitHandler` makes the
   merge commit), then push. Because conflicts are limited to append-mostly
   scratch files, this is rare and cheap.
5. Loop once more if the remote advanced again during resolution (bounded
   retry), then fail the push loudly if it still can't converge.

Concurrent jobs each operate on their own working copy (a per-job worktree or
clone — implementation choice; the persistent per-workstream checkout is reused
as the source). With reconciliation at push, they never need to lock.

### Why this is safe

- When the durable work product goes to an external system, concurrent jobs
  never contend on the user-visible output — only on scratch.
- Scratch reconciliation reuses a mechanism that already exists and is already
  trusted for base-branch sync.

### Integration points

- `GitManagedJob` commit/push tail — add the fetch + merge-against-target step
  before push.
- `GitCommitHandler` — already handles in-progress merges; confirm it triggers
  for the completion-time merge too.
- Concurrency: allow N concurrent jobs per workstream up to a configurable cap,
  each with its own working copy.

## 3. Per-workstream `agentEnv` injection

Add an optional `agentEnv: { KEY: VALUE }` map per workstream in
`workstreams.yaml`. The controller threads it into the agent subprocess
environment (via `AgentProcessRunner`), so a workstream-scoped MCP can read its
configuration (e.g. a tenant identifier or a runtime secret name) at startup
and scope itself accordingly. No change to ar-secrets. Unlike the existing
pushed-tool `env` (which targets pushed-tool MCP stdio configs), `agentEnv` is
set on the agent process itself and is therefore inherited by every process the
agent spawns, including project-local MCP servers declared in the repo.

## 4. Completion signal

A caller that submits jobs needs to know when one finishes (to update its own
state or run follow-up steps). Two options, in order of preference for v1:

- **Polling** `GET /api/jobs/{id}` — no new inbound trust boundary, no flowtree
  change; fine for v1.
- **Signed completion webhook** (later optimization) — `completionWebhookUrl`
  on the submission, validated against a host allowlist and signed (HMAC/JWS
  with timestamp + nonce) so the caller can trust the callback.

## 5. Phasing

1. `agentEnv` injection (smallest, unblocks workstream-scoped MCP config).
2. Per-submission resource profiles.
3. Merge-on-completion reconciliation + concurrent-jobs-per-repo.
4. Completion webhook (optional; polling works until then).

GitHub App credentials (`GITHUB_APP_CREDENTIALS.md`) are a related capability
sequenced alongside these.

## 6. Open questions

1. **Per-job working copy: worktree vs clone.** Worktrees share the object
   store (cheaper disk, faster) but complicate cleanup; per-job clones are
   simpler but heavier. Decide during implementation of §2.
2. **Concurrency cap default.** Start low (e.g. 2–3 per workstream) and tune.
3. **Reconciliation retry bound.** How many times to re-merge if the remote
   keeps advancing before giving up and failing the push.
