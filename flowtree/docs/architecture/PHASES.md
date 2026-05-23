# Job Phases and Per-Phase Runner Selection

A FlowTree coding job runs in multiple **phases** — the primary work, then
a series of enforcement-rule corrections, then the commit-message recovery,
and (if triggered) a git-tampering restart. Each phase can independently
select which `AgentRunner` executes it. This document describes the phase
inventory, the routing precedence ladder, and the telemetry surfaced for
the result of each phase.

For the runner SPI itself, see [AGENT_RUNNERS.md](AGENT_RUNNERS.md).

---

## The `Phase` enum

`io.flowtree.jobs.agent.Phase` lives in `flowtree/agents/` alongside
`AgentRunner`:

| Phase | Wire name | When it runs |
|-------|-----------|--------------|
| `PRIMARY` | `primary` | Initial `executeSingleRun()` in `doWork()` — the prompt the operator submitted. |
| `ENFORCE_CHANGES` | `enforce-changes` | `EnforceChangesRule` — re-runs the primary prompt if no production code changed. |
| `REVIEW` | `review` | `ReviewRule` — one-shot second-pass review of the primary diff (default-on); makes small surgical fixes and defers the rest. |
| `DEDUPLICATION` | `deduplication` | `DeduplicationRule` correction, when local dedup mode is on. |
| `ORGANIZATIONAL_PLACEMENT` | `organizational-placement` | `OrganizationalPlacementRule` correction (default-on). |
| `MAVEN_DEPENDENCY_PROTECTION` | `maven-dependency-protection` | `MavenDependencyProtectionRule` correction. |
| `POST_COMPLETION` | `post-completion` | `PostCompletionCommandRule` correction with the script's output. |
| `COMMIT_MESSAGE` | `commit-message` | `CommitMessageRule` — always the last enforcement phase. |
| `GIT_TAMPERING_RESTART` | `git-tampering-restart` | `onGitTampering()` re-run with a stern warning. |

The orchestrator resolves the current phase from the existing
`currentActivity` field — no new session state was needed. Most rule
`getName()` values match the kebab-case wire names above directly. Two do
not: `MavenDependencyProtectionRule.getName()` returns
`"no-maven-dependency-changes"` (wire name: `maven-dependency-protection`)
and `PostCompletionCommandRule.getName()` returns
`"post-completion-command"` (wire name: `post-completion`).
`Phase.fromRuleName(String)` handles both mappings; use it instead of
`fromWireName` when translating a rule name to a phase.

---

## Routing precedence

A runner is resolved per phase from this ladder, top-to-bottom:

```
1. per-job override        (CodingAgentJob.runnerByPhase[phase])
2. per-job default         (CodingAgentJob.defaultRunner)
3. workstream per-phase    (Workstream.runners[phase])
4. workstream default      (Workstream.defaultRunner)
5. workspace per-phase     (WorkspaceEntry.runners[phase])
6. workspace default       (WorkspaceEntry.defaultRunner)
7. controller default      (controller config)
8. "claude"                (compiled-in fallback)
```

The workspace layer (levels 5 and 6) is consulted when the workstream's
`workspaceId` matches a configured `workspaces[]` (or legacy
`slackWorkspaces[]`) entry. A workstream with no `workspaceId` — or one
whose ID does not match any configured workspace — skips the workspace
layer cleanly and falls through to the controller default. Workstream-level config fully shadows the
workspace it belongs to: if the workstream sets `defaultRunner`, the
workspace's per-phase entries are ignored (the workstream default would
have applied to every otherwise-unmapped phase anyway).

Each level may set fewer phases than the level above; unset phases inherit
through to the next non-empty level. This means an operator can keep most
of a workstream's defaults and override a single phase per job without
restating the full map.

Validation runs at submit time:

- Every runner name must appear in `AgentRunnerRegistry.available()`.
- Phase keys must be a known wire name from the table above.

An unknown runner or phase key returns a 400 from
`FlowTreeApiEndpoint.handleSubmit(...)` and an `is_error=true` MCP
response from `workstream_submit_task`. Jobs are never dispatched with an
invalid runner choice.

---

## Wire serialization

`CodingAgentJob.encode()` writes two compact keys, but only when the
values differ from the default ("claude" for every phase):

```
::defaultRunner:=opencode
::runners:=primary=claude,deduplication=opencode,commit-message=claude
```

`set("runners", value)` parses the CSV back into an EnumMap. A missing
key (older wire formats) leaves the map empty — every lookup falls back
to `defaultRunner="claude"`, preserving pre-branch behavior.

The legacy `::runner:=<name>` key (single-runner-for-the-whole-job, from
the original Phase 1 ship) is still accepted on deserialization and is
applied as the job-level default. New encoders never write it.

---

## `GIT_TAMPERING_RESTART` as a separate phase

The git-tampering restart is its own phase rather than a continuation of
`PRIMARY`. Operators may want a different runner to "calm down" a
tampering agent than the one that did the primary work — for example,
routing the restart to a cheaper local model to discourage repeated
billing on an agent that already misbehaved. Keeping the restart
addressable in the per-phase map preserves that option without changing
the default behavior (it inherits `PRIMARY`'s runner if unset).

---

## `DEDUPLICATION` and local-model routing

The `DEDUPLICATION` phase is a natural candidate for routing to the
`OpencodeRunner` backed by a local llama.cpp inference server instead of
Claude Code. Deduplication is a structured, rule-following task — identify
and remove any duplicate methods introduced by the primary phase — that does
not require cloud-scale reasoning. Routing it to a local model such as
Qwen3-235B-A22B-Instruct-2507 (currently served from `mac-studio:8084`)
eliminates the Claude API cost for that phase while keeping the primary and
other enforcement phases on the cloud model. To enable this, set the
`deduplication` wire name to `"opencode"` in the per-workstream or per-job
runner map and point `OPENCODE_PROVIDER_URL` at the llama.cpp endpoint; the
routing precedence ladder handles the rest without any further configuration.

The same argument applies to `REVIEW`. The review phase exists to give a
cheaper/faster second-pass reviewer a chance to make small surgical fixes
before the rest of the enforcement pipeline runs; the prompt is engineered
with a strong "defer rather than edit" bias so a weaker model can do
useful work without risking bad edits. The recommended deployment is:

```json
{ "runners": { "review": "opencode" } }
```

at workspace level. Every job picked up by that workspace then routes its
primary work to the cloud model and its review pass to the local model.

---

## `REVIEW`: the second-pass review phase

`REVIEW` is enabled by default on every job. It sits between
`ENFORCE_CHANGES` and `DEDUPLICATION`, so the working tree it sees has
at least one round of "did the agent actually produce changes" applied,
and any small fixes it makes are still subject to the standard
deduplication and organizational-placement checks via the outer
correction loop.

The phase is one-shot: `ReviewRule.isViolated()` returns `true` the first
time it sees new or modified files on the branch, the framework runs one
correction session for it, and after `onCorrectionAttempted()` fires the
rule reports satisfied for the rest of the job. The outer
`runEnforcementRules()` loop is responsible for re-walking dedup and
placement against whatever the review pass changed; review itself does
not loop.

The review prompt (`ReviewPromptBuilder`) is engineered around a single
philosophy: **make simple fixes, defer substantial issues**. The reviewer
is told:

- Categories of fix it may make directly: typos in strings/comments/docs;
  obvious missing imports; trivial one-line guard clauses; unambiguous
  one-line bug fixes; a single missing test method on a newly-introduced
  method.
- Categories of fix it must defer: architectural concerns; cross-cutting
  changes; performance concerns; style or naming concerns; anything that
  requires reading code outside the diff to evaluate.
- "When in doubt, defer" — the bias toward filing a memory plus a TODO
  comment over making a wrong edit is explicit in the prompt.

When the reviewer chooses to defer, the prompt directs it to do two
things at the relevant code location:

1. Call `memory_store` with tags including `review-followup` and
   `workstream:<workstream-id>` so the next primary-phase session can
   discover the deferred items via `memory_recall`.
2. Leave an inline `TODO(review):` comment so reviewers reading the PR
   can see what was flagged without searching the memory store.

To find deferred items later, an operator (or the next primary-phase
session) can call `memory_recall` with `tags=["review-followup"]` or
`workstream_context` with namespace/branch filtering. The
`TODO(review):` comment is the in-code breadcrumb.

### Configuration surface

| Layer | How to enable/disable | How to choose the runner |
|-------|------------------------|---------------------------|
| Per job | `setReviewEnabled(false)` or `review_enabled=false` on the MCP submit | `setRunnerForPhase(Phase.REVIEW, "...")` |
| Per workstream | `reviewEnabled: false` in workstream config | `runners: {"review": "..."}` |
| Per workspace | `reviewEnabled: false` in workspace config | `runners: {"review": "..."}` |
| Globally | (No global default — disable per workspace.) | Controller default. |

The pass cap is `1` by default. Operators who want the review phase to
make more than one correction attempt set `max_review_passes` (MCP) or
`setMaxReviewPasses(int)` (Java); the framework caps the inner correction
loop at that value.

### Disabling the review phase entirely

We follow the convention already used by
`setEnforceOrganizationalPlacement(false)`: there is no `"none"` sentinel
in the runner map. To skip review, set the boolean:

- `setReviewEnabled(false)` on `CodingAgentJob` / `CodingAgentJobFactory`
- `review_enabled=false` on `workstream_submit_task`
- `reviewEnabled: false` in workspace or workstream config

This keeps the runner-map vocabulary uncluttered and lets the workspace
or workstream still configure a review runner for the (uncommon) case
where review is re-enabled later.

---

## Telemetry

Every completed job emits a `CodingAgentJobEvent` whose payload includes:

- The total across all sessions: `costUsd`, `numTurns`, `durationMs`,
  `durationApiMs`, `permissionDenials`.
- `runnerByPhase` — `Map<phase-name, runner-name>` recording which runner
  actually executed each phase.
- `runnerStats` — `Map<runner-name, RunnerStats>` rolling up sessions,
  duration, cost, and turn count per runner.

```java
public record RunnerStats(
        int    sessions,
        long   durationMs,
        long   durationApiMs,
        double costUsd,
        int    numTurns,
        boolean costReported,
        boolean turnsReported) {}
```

`costReported` / `turnsReported` come from
`AgentCapabilities.reportsCost` / `reportsTurns`. They tell consumers
whether the zero in a `costUsd` total is "the runner reported 0" or
"the runner does not report cost." A top-level
`unmeasuredCostRunners: List<String>` lists every runner whose phases
contributed without a real cost figure; dashboards should consult this
list before drawing per-job cost conclusions.

A runner that does not report a value still contributes 0 to the total —
the totals are the sum across sessions, and a non-reporting runner's
sessions add nothing. This keeps the total honest as long as readers
consult the per-runner flags before interpreting it.
