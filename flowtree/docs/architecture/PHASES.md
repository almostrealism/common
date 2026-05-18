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
| `DEDUPLICATION` | `deduplication` | `DeduplicationRule` correction, when local dedup mode is on. |
| `ORGANIZATIONAL_PLACEMENT` | `organizational-placement` | `OrganizationalPlacementRule` correction (default-on). |
| `ENFORCE_CHANGES` | `enforce-changes` | `EnforceChangesRule` — re-runs the primary prompt if no production code changed. |
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
5. controller default      (controller config)
6. "claude"                (compiled-in fallback)
```

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
