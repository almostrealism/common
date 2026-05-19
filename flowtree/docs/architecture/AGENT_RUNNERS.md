# Agent Runners

FlowTree coding jobs run the agent through a pluggable `AgentRunner`
abstraction. Each runner is a thin wrapper around an external coding-agent
CLI (Claude Code, opencode, future agents) that exposes a uniform request
/response shape to the orchestrator.

This document describes the SPI and how the orchestrator dispatches to a
runner. For how individual phases of a job select their runner, see
[PHASES.md](PHASES.md). For operator-level setup of specific runners, see
the documents under `flowtree/docs/operations/`.

---

## Where the code lives

| Module | Contents |
|--------|----------|
| `flowtree/agents/` (`ar-flowtree-agents`) | The SPI: `AgentRunner`, `AgentRunRequest`, `AgentRunResult`, `AgentCapabilities`, `AgentRunnerRegistry`, the `Phase` enum, and the bundled `ClaudeCodeRunner` / `OpencodeRunner` implementations. Depends only on `ar-flowtree-base` and `ar-meta`. |
| `flowtree/runtime/` (`ar-flowtree-runtime`) | The orchestrator (`io.flowtree.jobs.CodingAgentJob`) that builds an `AgentRunRequest` and dispatches it through `AgentRunnerRegistry.get(runnerName)`. Owns MCP config, allowlist construction, and per-phase routing. Depends on `ar-flowtree-agents`. |

The dependency direction is strictly `runtime → agents`. A runner that
needs runtime-only types (e.g. one that posts directly to Slack) must live
in `flowtree/runtime` and register itself with `AgentRunnerRegistry`.

---

## The contract

```java
public interface AgentRunner extends Named {
    AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger);
    AgentCapabilities capabilities();
}
```

A runner is stateless across calls — every per-session input is threaded
in through `AgentRunRequest`. The orchestrator owns session accumulation
(cost totals, denied-tool sets, etc.); the runner returns only what it
observed during the single session it just ran.

### `AgentRunRequest`

Carries everything the runner needs to launch one session:

- `prompt` — the full instruction prompt
- `workingDirectory` — where the agent runs (a per-job git worktree)
- `allowedTools` — CSV of permitted tool names (orchestrator-owned; see below)
- `mcpConfigJson` — MCP server configuration as JSON
- `environment` — additional env vars (includes `AR_AGENT_ACTIVITY`)
- `model`, `effort` — runner-specific aliases; null leaves the runner default
- `maxTurns`, `maxBudgetUsd`, `inactivityTimeoutMillis` — limits
- `taskId`, `activityTag` — used for log decoration
- `outputCapturePath` — where the runner writes its raw NDJSON / JSON output
  for archival

`outputCapturePath` is owned by the orchestrator (the `claude-output/<id>.json`
files today). The runner just writes its raw output there as a side effect of
running.

### `AgentRunResult`

```java
public record AgentRunResult(
        int exitCode,
        boolean killedForInactivity,
        String rawOutput,
        String sessionId,
        long durationMs,
        long durationApiMs,
        int numTurns,
        double costUsd,
        String stopReason,
        boolean sessionIsError,
        List<String> deniedToolNames,
        Map<String, String> runnerMetadata) {}
```

A runner that cannot report a value returns the zero/empty for it and
declares the gap in `capabilities()`. See [Telemetry](PHASES.md#telemetry)
for how downstream consumers interpret an unreported value.

### `AgentCapabilities`

```java
public record AgentCapabilities(
        boolean reportsCost,
        boolean reportsTurns,
        boolean supportsEffortLevel,
        boolean supportsMaxBudget,
        boolean supportsMcpHttpTransport,
        boolean supportsMcpStdioTransport,
        boolean supportsPermissionDenialReporting,
        Set<String> supportedModels) {}
```

Capability flags are intentionally **per-runner-class**, not per-session.
They describe what the runner can faithfully report, not what the current
backend happens to support. A runner that talks to a cloud provider that
emits cost figures and a local llama.cpp that does not should declare
`reportsCost = false` — the operator-visible cost figure is "unknown" in
both cases, because the runner cannot guarantee accuracy.

---

## Registry

`AgentRunnerRegistry` is a process-wide name → supplier map seeded at class
load time with the bundled runners:

```java
AgentRunner runner = AgentRunnerRegistry.get(runnerName);  // throws if unknown
Set<String> known = AgentRunnerRegistry.available();
AgentRunnerRegistry.register("custom", CustomRunner::new); // for external runners
```

Names are short kebab-case strings (`"claude"`, `"opencode"`) and are part
of the wire format. The orchestrator validates job-submission requests
against `available()` and rejects unknown names with a clear error before
the job is dispatched.

---

## Dispatch in the orchestrator

`CodingAgentJob.executeSingleRun()` is the single site where a runner is
invoked. The orchestrator:

1. Resolves the runner for the current phase
   (see [PHASES.md](PHASES.md) for the precedence ladder).
2. Builds an `AgentRunRequest` by combining job-level fields
   (prompt, working directory, model, effort, limits), orchestrator-owned
   policy (allowlist, MCP JSON), and the current phase's environment
   (`AR_AGENT_ACTIVITY`).
3. Calls `runner.run(request, this)`.
4. Absorbs the returned `AgentRunResult` into accumulated session stats
   (`durationMs`, `costUsd`, `numTurns`, `permissionDenials`) and into the
   per-runner rollup carried on the completion event.

No site outside `CodingAgentJob` mentions runner-specific flags or fields.
Enforcement rules (`EnforceChangesRule`, `DeduplicationRule`, etc.)
generate correction prompts via `buildCorrectionPrompt(job)` and the
orchestrator turns each into its own `AgentRunRequest`; the rule does not
know which runner will execute the correction.

---

## Allowlist ownership

The orchestrator owns the tool allowlist. Each phase passes the same
`McpConfigBuilder`-produced CSV to whichever runner is selected, and the
runner is responsible for translating that CSV into its native permission
shape.

This boundary is deliberate:

- The allowlist is a **policy** concern (what the agent is permitted to do
  on the workspace), not a runner concern (how that policy is expressed on
  the wire).
- The ar-manager tool inventory (`AR_MANAGER_TOOL_NAMES` /
  `EXCLUDED_AR_MANAGER_TOOLS`) is identical regardless of runner. Keeping
  the CSV in one place keeps the existing
  `allowlistCoversEveryArManagerTool` safety-net test applicable to every
  runner.
- The MCP server inventory (ar-manager + pushed + project) is workstream
  policy, not a runner choice.

`OpencodeRunner.translateAllowedTools(...)` performs the runner-side
translation for opencode; `ClaudeCodeRunner` passes the CSV through
verbatim as `--allowedTools`. Unit tests cover round-trips for every known
prefix.

---

## In-process vs. subprocess runners

The contract is process-agnostic. Both runners that ship today launch a
subprocess via `AgentProcessRunner.runAttempt(...)` and consume its stdout
through `AgentInactivityMonitor`, but a future in-JVM runner that calls a
local inference endpoint over HTTP would simply not start a subprocess. It
would still produce an `AgentRunResult` and the orchestrator would absorb
it the same way.

---

## When a runner is unavailable

If a runner's underlying binary is missing on the executor, the runner
throws `AgentRunnerNotAvailableException` at `run()` time. The
orchestrator surfaces this as a job failure with a message that names the
runner and what it looked for. The orchestrator does **not** silently
fall back to a different runner: a per-phase runner selection is an
operator decision, and substituting a different runner would hide the
cost/behavior differences that motivated the choice.

---

## Configuring runners

Runner selection follows a three-level precedence ladder (highest to lowest):

1. **Per-job override** — the `runners` / `defaultRunner` fields in the
   job submission payload, forwarded from `workstream_submit_task`'s
   `runners` and `default_runner` parameters.
2. **Workstream default** — the `runners` / `defaultRunner` fields stored
   in the workstream's configuration, set via `workstream_register` or
   `workstream_update_config`'s `runners` and `default_runner` parameters.
3. **Built-in default** — `"claude"` (`AgentRunnerRegistry.CLAUDE`).

`SubmissionRunnerResolver` in `flowtree/runtime` implements this ladder.
Never bypass it: the three levels exist so operators can set a workstream
policy without touching job payloads, and individual jobs can still
override the policy when needed.

### Discovering available options

Call the `agent_options` MCP tool (read-only; no write scope required) to
enumerate:

- **`runners`** — available runner names and their `AgentCapabilities` flags.
  Use the `name` field as the value in the `runners` JSON object.
- **`phases`** — the eight phase wire names (`"primary"`, `"deduplication"`,
  `"organizational-placement"`, `"enforce-changes"`,
  `"maven-dependency-protection"`, `"post-completion"`,
  `"commit-message"`, `"git-tampering-restart"`) and their descriptions.
  Use these as keys in the `runners` JSON object.
- **`models`** — accepted model aliases and full identifiers.
- **`defaultRunner`** — the current built-in default (`"claude"`).

The `agent_options` tool proxies `GET /api/agents` on the controller.

### Setting a workstream-level default

```python
# Route all phases to opencode by default for this workstream
workstream_update_config(
    workstream_id="ws-abc123",
    default_runner="opencode",
)

# Or specify per-phase overrides
workstream_update_config(
    workstream_id="ws-abc123",
    runners='{"primary":"opencode","deduplication":"opencode"}',
)
```

### Overriding at job-submission time

```python
# Use opencode for primary work, claude for all enforcement phases
workstream_submit_task(
    workstream_id="ws-abc123",
    prompt="...",
    default_runner="opencode",
)

# Or fine-grained per-phase control
workstream_submit_task(
    workstream_id="ws-abc123",
    prompt="...",
    runners='{"primary":"opencode","deduplication":"claude"}',
)
```

The `runners` JSON object may contain any subset of phase wire names plus
the optional `"default"` key. An explicit `runners["default"]` wins over
the separate `default_runner` parameter when both are supplied.
