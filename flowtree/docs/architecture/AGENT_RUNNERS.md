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

## Subprocess launch backends

`AgentProcessRunner.runAttempt` has two launch backends:

| Backend | Selected by | Where the child runs |
|---------|-------------|----------------------|
| Direct `Process` | `useTmux=false` (default) | A direct child of the JVM. `stdin` is redirected from `/dev/null`; `stdout`/`stderr` are merged into a single pipe that the runner reads line-by-line. |
| `TmuxSession` | `useTmux=true` | The child is launched inside a `tmux` session (one pane per attempt). The pane is connected to a real pty; pane output is captured by `tmux pipe-pane` into a tail log that the runner reads line-by-line. |

`ClaudeCodeRunner` selects the backend at runtime based on the
`AR_AGENT_USE_TMUX` env var:

- Unset (the default) → direct `Process`. Live deployments behave exactly
  as before the tmux work landed.
- `enabled` and `tmux` is on `PATH` → `TmuxSession`.
- `enabled` but `tmux` is missing → direct `Process`, with a one-line
  warning in the job log so the misconfiguration is visible.
- `disabled` → direct `Process`.
- Any other value → a startup error from `SystemUtils.isEnabled` (the
  helper intentionally rejects boolean synonyms like `true`/`false` to
  prevent silent misconfiguration).

`OpencodeRunner` uses the direct-process backend unconditionally. The
choice is per-call — `runAttempt` exposes a `useTmux` boolean and any
caller may flip it independently.

### Why a tty matters

The Claude Code CLI behaves differently when launched as a non-interactive
subprocess versus when it has a controlling terminal. Some authentication
modes — specifically the OAuth-token flow that the agent containers ship
with — require an interactive context to validate the session. Without a
tty the process exits with an auth error before producing structured
output. The tmux-backed launch path exists to give the child a real pty
without requiring a human attached to the terminal.

### Behavioral consequences of `useTmux=true`

1. **The reported PID is the bash wrapper, not the agent binary.** Each
   tmux session runs a small wrapper that synchronizes with the JVM, runs
   the agent command, and records its exit code. The PID in the runner's
   `Process started (PID: …)` log line is the wrapper's; the agent binary
   is its child.
2. **The kill signal is `SIGHUP`, not `SIGKILL`.** The inactivity
   watchdog and the lifecycle `close()` call kill the session via
   `tmux kill-session`, which sends `SIGHUP` to the pane's process group.
   `claude` and `opencode` both respond to that the same way they respond
   to a `Ctrl-C`. Anything that catches `SIGHUP` and refuses to exit will
   leak.
3. **Tmux sessions outlive the JVM if the JVM dies abnormally.** The
   `tmux` server is a separate process. A clean `close()` (try-with-
   resources on the `TmuxSession`) tears the session down; a `kill -9` on
   the JVM leaves it running. Operators should periodically prune stale
   `agent-*` sessions: `tmux ls 2>/dev/null | grep '^agent-' | cut -d:
   -f1 | xargs -r -I{} tmux kill-session -t {}`.
4. **`TERM` and `NO_COLOR` are forced.** Before the session starts,
   `runInTmux` sets `TERM=dumb` and `NO_COLOR=1` on the environment
   passed to tmux if they are not already present. This suppresses ANSI
   color codes, spinners, and other tty-conditional decoration that
   would corrupt the runner's NDJSON parsing. A caller that genuinely
   wants tty-conditional output (for example, a future runner that
   parses ANSI escapes itself) can pre-set these vars in
   `AgentRunRequest.environment` and the defaults will not overwrite
   them.
5. **The full inherited environment is forwarded via `tmux -e`.**
   Tmux's session-level environment overrides take precedence over the
   server's cached environment. This is how `HOME`,
   `CLAUDE_CODE_OAUTH_TOKEN`, MCP-related vars, and
   `AR_AGENT_ACTIVITY` reach the child correctly when the tmux server
   was started by some other process at boot.

### Operator login workflow (for `ClaudeCodeRunner`)

`ClaudeCodeRunner` does not perform login. It relies on credentials that
already exist on disk inside the agent container — by convention under
`~/.claude/`. For container deployments the recommended procedure is:

1. Mount `~/.claude/` as a persistent volume on the agent container so
   credentials survive container restarts.
2. After the first `rebuild.sh --agents` run, exec into the container and
   run `claude login` once:

   ```sh
   docker exec -it <agent-container> claude login
   ```

   `claude` prints a URL and a verification code. The operator opens the
   URL in their own browser, pastes the code, and the CLI writes
   credentials to the mounted `~/.claude/` volume.
3. Subsequent `ClaudeCodeJob` runs read those credentials and need no
   further interaction.

This is the only step that genuinely requires a human; everything after
the credentials are cached runs unattended. The same one-time login also
satisfies any policy that disallows headless-token use for interactive
sessions, because the cached credentials are obtained through the
operator's authenticated browser session, not from a long-lived token in
the container environment.

If a job ever fails with an auth error, the remedy is the same as
day-one: exec in and run `claude login` again. The doc index for this
procedure lives in [../operations/SETUP.md](../operations/SETUP.md).

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

Runner selection follows a four-level precedence ladder (highest to lowest):

1. **Per-job override** — the `runners` / `defaultRunner` fields in the
   job submission payload, forwarded from `workstream_submit_task`'s
   `runners` and `default_runner` parameters.
2. **Workstream default** — the `runners` / `defaultRunner` fields stored
   in the workstream's configuration, set via `workstream_register` or
   `workstream_update_config`'s `runners` and `default_runner` parameters.
3. **Workspace default** — the `runners` / `defaultRunner` fields on a
   `workspaces[]` (or legacy `slackWorkspaces[]`) entry in
   `workstreams.yaml`. Every workstream whose `workspaceId` matches the
   workspace's `id` inherits this default unless it sets its own.
4. **Built-in default** — `"claude"` (`AgentRunnerRegistry.CLAUDE`).

`SubmissionRunnerResolver` in `flowtree/runtime` implements this ladder.
Never bypass it: the four levels exist so operators can set a workspace
policy once across many workstreams, narrow it on individual workstreams,
and still override on individual jobs.

### Discovering available options

Call the `agent_options` MCP tool (read-only; no write scope required) to
enumerate:

- **`runners`** — available runner names and their `AgentCapabilities` flags.
  Use the `name` field as the value in the `runners` JSON object.
- **`phases`** — the eight phase entries (each with a `name` and
  `description`). The phase wire names — `"primary"`, `"deduplication"`,
  `"organizational-placement"`, `"enforce-changes"`,
  `"maven-dependency-protection"`, `"post-completion"`,
  `"commit-message"`, `"git-tampering-restart"` — are the values of the
  `name` field, and are used as keys in the `runners` JSON object.
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

### Setting a workspace-level default

A workspace-level default applies to every workstream whose
`workspaceId` matches the workspace's `id`. Useful when an operator runs
many workstreams in the same workspace and wants the same defaults
across all of them — e.g. "use opencode for `commit-message` and
`organizational-placement` everywhere in this org". Workspaces in
higher-risk orgs simply leave these fields unset and inherit the controller
default.

**Preferred path: `workspace_update_config` MCP tool.** Mirrors
`workstream_update_config` in shape but is keyed on the workspace's
operator-chosen `id`. Discover IDs via the `workspaceId` field returned
by `workstream_list` (the legacy `slackWorkspaceId` alias is still
emitted for backward compatibility). The change is persisted back to
`workstreams.yaml` so it survives a controller restart.

```python
workspace_update_config(
    workspace_id="almostrealism",
    default_runner="claude",
    runners='{"commit-message":"opencode","organizational-placement":"opencode"}',
    # name="team-alpha",
    # default_channel="C0987654321",
)
```

The legacy parameter name `slack_workspace_id` is still accepted as a
deprecated alias for `workspace_id`.

The tool exposes `default_runner`, `runners`, `name`, `default_channel`,
`new_id`, and `slack_team_id`. **Credential and ACL fields**
(`tokensFile`, `botToken`, `appToken`, `githubOrgs`,
`channelOwnerUserId`) are deliberately NOT settable here — those must
be edited in the YAML directly so the change can be code-reviewed
before it lands.

#### Renaming a workspace

Workspaces created from a legacy `slackWorkspaces:` entry start out with
their Slack team ID as the workspace ID. To migrate to a friendlier
operator-chosen ID, pass `new_id`:

```python
workspace_update_config(
    workspace_id="T0123456789",
    new_id="almostrealism",
)
```

Every workstream that referenced the old ID is updated atomically. The
workspace's Slack team binding (`slackTeamId`) survives the rename, so
channel routing keeps working.

#### Unbinding a workspace from Slack

Pass an explicit empty string for `slack_team_id` to clear the Slack
connection. After clearing, channel/notifier operations on this
workspace skip cleanly.

```python
workspace_update_config(
    workspace_id="almostrealism",
    slack_team_id="",
)
```

Pass a non-empty `slack_team_id` to point the workspace at a different
Slack team.

**Fallback path: edit `workstreams.yaml` directly.** The MCP tool only
covers the fields above; everything else (tokens, GitHub orgs, secrets
list) is still YAML-only. The on-disk shape:

```yaml
workspaces:
  - id: "almostrealism"           # operator-chosen workspace identifier
    slackTeamId: "T0123456789"    # optional; omit for no Slack integration
    name: "team-alpha"
    botToken: "xoxb-..."
    appToken: "xapp-..."
    defaultRunner: "claude"
    runners:
      commit-message: "opencode"
      organizational-placement: "opencode"
```

The legacy `slackWorkspaces:` top-level key is still loaded; each entry
auto-migrates so its `id` doubles as its `slackTeamId`, preserving
existing channel routing. Both keys may appear in the same file during
the migration window — entries from both are merged into the same
workspace list.

Unknown phase keys in `workspaces[].runners` are rejected at load
time with a clear error naming the offending workspace; the resolver does
not silently route around them. Workstream-level config fully shadows the
workspace it belongs to — when a workstream sets `defaultRunner`, the
workspace's per-phase entries are skipped for that workstream.

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
