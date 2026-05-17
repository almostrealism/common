# Pluggable Agent Runners for FlowTree Coding Jobs

---

## Implementation status (as of 2026-05-17, branch `feature/pluggable-agents`)

This document is both a design plan AND the canonical record of what has shipped.
Read this section before reading the rest — the design below describes the
**target state**, not the current state.

### What has landed

- `io.flowtree.jobs.agent.AgentRunner` interface + companion types
  (`AgentRunRequest`, `AgentRunResult`, `AgentCapabilities`,
  `AgentRunnerRegistry`).
- `ClaudeCodeRunner` (implements `AgentRunner`) — owns subprocess
  construction, command-line assembly, NDJSON parsing, model/effort
  validation. The orchestrator no longer mentions `claude`,
  `--allowedTools`, `--mcp-config`, `--max-turns`, `session_id`,
  `total_cost_usd`, etc. directly.
- The job dispatches its agent session through
  `AgentRunnerRegistry.get(runnerName)`; the registry currently knows only
  `"claude"`.
- The job and factory carry a `runnerName` field with wire-format support
  (`::runner:=<name>`, gated on non-default value). Validated against
  `AgentRunnerRegistry.available()`.
- The completion event carries `runnerName` via `withRunnerName(...)`.

### Deviations from the original plan (important — these change the work
required for Phases 2-4)

1. **The job class was renamed.** Plan §2.1 explicitly recommended *keeping*
   `ClaudeCodeJob` for wire-format and call-site reasons. Implementation
   renamed it anyway:
   - `ClaudeCodeJob` → `CodingAgentJob`
   - `ClaudeCodeJobFactory` → `CodingAgentJobFactory`
   - `ClaudeCodeJobEvent` → `CodingAgentJobEvent`
   - File: `flowtree/core/src/main/java/io/flowtree/jobs/CodingAgentJob.java`
   This is a wire-format break. Any persisted/queued job specs serialized
   under the old class discriminator will fail to deserialize on the new
   build. **Deployment requires draining in-flight jobs first** (or
   accepting their loss).
2. **One runner per job, not per phase.** Plan §2.2 specified a
   `Map<Phase, String> runnerByPhase` with a `Phase` enum covering
   `PRIMARY`, the six rule phases, and `GIT_TAMPERING_RESTART`.
   Implementation has a single `runnerName` field on the job/factory; every
   phase (primary, enforcement rules, git-tampering restart) uses the same
   runner. The `Phase` enum was not introduced. Per-phase selection from
   Phase 2 of the plan must be added on top of this — it is not a "wire it
   to the API" step, it is "add the map first, then wire it."

### What has NOT landed

- **Phase 2 (per-job/per-workstream selection through public surfaces) is
  not done.** None of these reference `runner`:
  - `flowtree/core/src/main/java/io/flowtree/slack/Workstream.java`
  - `flowtree/core/src/main/java/io/flowtree/slack/WorkstreamConfig.java`
  - `flowtree/core/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`
  - `tools/mcp/manager/server.py` (`workstream_submit_task` has no
    `runner` / `default_runner` parameter)
  Even though `CodingAgentJob` accepts `runnerName`, no submission surface
  lets an operator set it.
- **Phase 3 (opencode runner) is not done.** No
  `OpencodeRunner.java`/`OpencodeBinaryLocator`/`OpencodeOutputParser`
  exist. `AgentRunnerRegistry` registers only `CLAUDE`. The agent image
  (`flowtree/core/agent/Dockerfile`) installs only `@anthropic-ai/claude-code`;
  no opencode binary is present.
- **Phase 4 (`docs/operations/PLUGGABLE_AGENTS_RECIPES.md`)** does not
  exist.

### Deployment of what currently exists

`flowtree/core/rebuild.sh --agents` is sufficient — all changes are inside the
`flowtree` Maven module; no Dockerfile, compose, or external-binary
changes. The behavior is unchanged from pre-branch (Claude only). The one
operational caveat is the class-rename wire break above.

### Concrete next steps for the next agent

In rough order; each step is a separate PR.

1. **Reconcile the rename decision.** Either (a) revert to `ClaudeCodeJob`
   per plan §2.1, accepting the churn of un-renaming, or (b) keep the
   rename and add a deserialization shim for the legacy class
   discriminator (`AbstractJobFactory` already ignores unknown *keys*; the
   *class name* is a different question — verify how a queued
   `ClaudeCodeJob` spec is dispatched). Document the chosen path before
   touching anything else.
2. **Decide: stay single-runner-per-job or introduce per-phase.** If
   per-phase, introduce the `Phase` enum and `runnerByPhase` map first,
   keeping `runnerName` as a back-compat default. If single-runner is
   accepted, update §2.2, §6, and §7 of this plan to match and shrink the
   Phase 2 work accordingly.
3. **Phase 2 — submission-surface plumbing.** Once step 2 is settled, wire
   `runner` (and optionally `runners`) through `WorkstreamConfig`,
   `Workstream`, `FlowTreeApiEndpoint.handleSubmit`, and
   `workstream_submit_task`. Precedence per plan §6.5. Validate against
   `AgentRunnerRegistry.available()` at submit time.
4. **Phase 3 — opencode runner.** Implement `OpencodeRunner` and friends;
   register in `AgentRunnerRegistry`; add opencode binary install to
   `flowtree/core/agent/Dockerfile`; gate behind `AgentRunnerNotAvailableException`
   for executors lacking the binary. Smoke test `AgentRunnerSmokeIT`.
5. **Phase 4 — recipes doc.** Once at least two runners are usable in
   production, write `docs/operations/PLUGGABLE_AGENTS_RECIPES.md` per
   §9 Phase 4.

The rest of this document is the original design — still the reference for
*what to build*. Read it with the deviations above in mind.

---

## Goal

Refactor the FlowTree coding agent stack so that the code-generating agent
(currently always Claude Code, invoked from `ClaudeCodeJob.executeSingleRun()`)
becomes a pluggable runner. Multiple runner implementations (Claude Code,
opencode, future agents) coexist behind a single `AgentRunner` abstraction, and
each *phase* of a job (primary work, deduplication audit, organizational
placement, post-completion correction, commit-message recovery, git-tampering
re-run) can independently select which runner to use.

This is a structural refactor — no behavior change in Phase 1, optional
behavior change in later phases.

---

## 0. Background — what the stack looks like today

### 0.1 The single-class orchestrator: `ClaudeCodeJob`

`flowtree/core/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java` (1,576 lines)
holds three concerns in one class:

1. **Job lifecycle** (extends `GitManagedJob`)
   - `doWork()` runs `executeSingleRun()`, then `runEnforcementRules()`.
   - `validateChanges()` runs the test-hiding audit and (in DEDUP_SPAWN mode)
     submits a follow-up job.
   - `onGitTampering()` re-runs Claude with a stern warning.
   - `getCommitMessage()` reads `commit.txt` or falls back to the prompt.
   - `createEvent()` / `populateEventDetails()` build `ClaudeCodeJobEvent`.
   - `encode()` / `set(key, value)` define the wire serialization.

2. **Claude-specific subprocess construction**
   - `executeSingleRun()` deletes stale `commit.txt`, calls
     `launchClaudeAttempt()`, parses JSON metrics, writes the raw output to
     `claude-output/*.json`.
   - `launchClaudeAttempt()` builds the `claude -p <prompt> --output-format
     json --allowedTools ... --max-turns ... [--max-budget-usd ...] [--model
     ...] [--effort ...] --mcp-config ...` command line.
   - `configureMcpBuilder()` wires the `McpConfigBuilder` from job state.
   - `extractOutputMetrics()` reads `session_id`, `subtype`, `is_error`,
     `duration_ms`, `duration_api_ms`, `num_turns`, `total_cost_usd`,
     `permission_denials[]` out of Claude's NDJSON output and accumulates
     them across sessions.
   - `runCorrectionSession()` swaps `this.prompt` and `this.currentActivity`,
     calls `executeSingleRun()`, then restores them.

3. **Enforcement framework**
   - `buildActiveRules()` instantiates the rule list from job flags.
   - `runEnforcementRules()` is the bounded outer loop (cap:
     `DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS = 25`) that, for each rule,
     repeatedly calls `runCorrectionSession()` until `isViolated()` is false,
     `getMaxRetries()` is exhausted, or the agent commits.

### 0.2 Subprocess management

`ClaudeAttemptRunner.runAttempt(pb, inactivityTimeoutMillis, taskId, logger)`
(`flowtree/core/src/main/java/io/flowtree/jobs/ClaudeAttemptRunner.java`) is
generic: it just runs a `ProcessBuilder`, reads stdout line-by-line, and
applies a `ClaudeInactivityMonitor` that destroys the process tree after
stdout silence. This class has no Claude-specific behavior and can be reused
unchanged by every runner.

### 0.3 MCP wiring

`McpConfigBuilder` (`flowtree/core/src/main/java/io/flowtree/jobs/McpConfigBuilder.java`)
emits two artifacts:

- `buildMcpConfig()` returns the JSON for the `--mcp-config` flag — an
  `{"mcpServers": {"ar-manager": {"type":"http", ...}, "<pushed>": {"command":"python3", ...}, ...}}`
  document.
- `buildAllowedTools(baseTools)` returns the CSV passed to
  `--allowedTools` — base tools + `mcp__ar-manager__*` (from
  `AR_MANAGER_TOOL_NAMES`) + pushed-tool entries + discovered project
  servers from `.mcp.json` / `.claude/settings.json`.
- `applyAgentEnvironment(env, wsUrl)` sets `AR_WORKSTREAM_URL`,
  `AR_CONTROLLER_URL`, `AR_WORKSTREAM_ID`, `AR_MANAGER_TOKEN` on the
  subprocess environment.

The MCP config JSON is Claude Code's own schema, but it is a near-superset of
opencode's schema. Both runners can consume the same logical model after a
thin translation layer.

### 0.4 Enforcement rules

All rules implement `io.flowtree.jobs.EnforcementRule`:

```java
public interface EnforcementRule extends Named {
    boolean isViolated(ClaudeCodeJob job);
    String  buildCorrectionPrompt(ClaudeCodeJob job);
    default void onCorrectionAttempted(ClaudeCodeJob job) {}
    default int  getMaxRetries() { return ClaudeCodeJob.DEFAULT_MAX_RULE_RETRIES; }
}
```

Rule list and their `getName()`:

| File | Name | Triggered by | Re-prompts? |
|------|------|--------------|-------------|
| `EnforceChangesRule` | `enforce-changes` | `setEnforceChanges(true)` | no — re-runs primary prompt |
| `DeduplicationRule` | `deduplication` | `deduplicationMode == DEDUP_LOCAL` | yes |
| `OrganizationalPlacementRule` | `organizational-placement` | default on | yes |
| `PostCompletionCommandRule` | `post-completion-command` | `postCompletionCommand` non-empty | yes (with command output) |
| `MavenDependencyProtectionRule` | `no-maven-dependency-changes` | `enforceMavenDependencies` | yes |
| `CommitMessageRule` | `commit-message` | `targetBranch` set (always last) | yes |

The rules depend on `ClaudeCodeJob` mostly for utility methods
(`extractNewMethodNames()`, `extractNewFilePaths()`, `hasUncommittedChanges()`,
`hasMavenDependencyChanges()`, `setCommitMessageSource()`,
`getWorkingDirectory()`, `getPrompt()`, `getBaseBranch()`). None of them
reach into Claude-specific state.

### 0.5 Factory and serialization

`ClaudeCodeJobFactory` produces `ClaudeCodeJob` instances from a list of
prompts plus a string-keyed property map (`AbstractJobFactory.set/get` —
base64-encoded). The factory and job each have parallel `encode()` /
`set(key, value)` paths for every property. Workstream defaults flow through
`Workstream`/`WorkstreamConfig` (model, effort, maxTurns, maxBudgetUsd, etc.)
and are applied by the controller at submit time.

### 0.6 Submission surface

| Surface | File |
|---------|------|
| MCP tool | `tools/mcp/manager/server.py:workstream_submit_task` (~line 1343) |
| HTTP endpoint | `flowtree/core/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` (`POST /api/workstreams/{id}/submit`) |
| Slack listener | `flowtree/core/src/main/java/io/flowtree/slack/SlackListener.java` |
| YAML config | `WorkstreamConfig` / `Workstream` |

### 0.7 Telemetry

`ClaudeCodeJobEvent extends JobCompletionEvent`:

- Claude-agnostic fields: `prompt`, `sessionId`, `exitCode`, `durationMs`,
  `durationApiMs`, `costUsd`, `numTurns`, `subtype`, `sessionIsError`,
  `permissionDenials`, `deniedToolNames`, `commitMessageSource`.

Almost every field maps onto a generic concept — except `numTurns` and the
exact `subtype` string set ("success", "error_max_turns"), which are
Claude-specific. Cost is a number (USD); a runner that does not report cost
will report 0 here.

### 0.8 References to `ClaudeCodeJob` outside the package

```
flowtree/core/src/main/java/io/flowtree/ClaudeCodeClient.java
flowtree/core/src/main/java/io/flowtree/slack/Workstream.java
flowtree/core/src/main/java/io/flowtree/slack/WorkstreamConfig.java
flowtree/core/src/main/java/io/flowtree/slack/SlackListener.java
flowtree/core/src/main/java/io/flowtree/slack/FlowTreeController.java
flowtree/core/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java
```

Test sites (`flowtree/core/src/test/java/io/flowtree/...`) reference the class
heavily and exercise nearly every property setter.

---

## 1. The `AgentRunner` interface

### 1.1 Shape — recommendation

A **Java interface in the `io.flowtree.jobs.agent` package**, with a small
companion record `AgentRunResult`. Interface (not abstract class) because:

- Rules and the orchestrator only call the interface; no shared state needs
  to live in a base class.
- Existing flowtree code style favors `Named`-style interfaces with default
  methods (see `EnforcementRule`); an abstract class would be inconsistent.
- ServiceLoader is overkill for the expected number of implementations (2-3)
  and adds packaging complexity. Use a simple `AgentRunnerRegistry` keyed by
  string name; see §1.4.

The interface lives in the same module as `ClaudeCodeJob` (the `flowtree`
module). Do **not** introduce a new Maven module for it — see §10.

### 1.2 The contract

```java
package io.flowtree.jobs.agent;

import io.almostrealism.uml.Named;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Pluggable backend that knows how to run one agent session against a
 * given prompt and return the resulting metrics.  Stateless across calls —
 * the orchestrator threads context in via {@link AgentRunRequest}.
 */
public interface AgentRunner extends Named {

    /**
     * Runs one agent session.  Returns the captured stdout, exit code,
     * accumulated metrics, and any runner-specific metadata.  Throws only
     * for unrecoverable wiring errors (binary missing, working directory
     * does not exist); a non-zero process exit code is a normal result.
     */
    AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger);

    /**
     * Declares which optional capabilities this runner faithfully reports.
     * The orchestrator and telemetry use this to decide whether to surface
     * a value or mark it as N/A.
     */
    AgentCapabilities capabilities();
}
```

### 1.3 `AgentRunRequest` — what the orchestrator hands over

```java
public final class AgentRunRequest {
    private final String prompt;                  // full instruction prompt
    private final Path workingDirectory;          // where the agent runs
    private final String allowedTools;            // CSV — orchestrator-owned (§5)
    private final String mcpConfigJson;           // MCP servers JSON
    private final Map<String, String> environment; // additional env vars
    private final String model;                   // runner-specific alias; null = runner default
    private final String effort;                  // optional thinking level; null = runner default
    private final int    maxTurns;                // 0 = runner default
    private final double maxBudgetUsd;            // <= 0 = unlimited
    private final long   inactivityTimeoutMillis; // shared inactivity watchdog
    private final String taskId;                  // for log decoration
    private final String activityTag;             // AR_AGENT_ACTIVITY value, null in primary
    private final Path   outputCapturePath;       // where to dump raw output for archival
    // ... builder + getters
}
```

The `outputCapturePath` is owned by the orchestrator (the `claude-output/<id>.json`
file today); the runner just writes its raw output there as a side effect.

### 1.4 `AgentRunResult`

```java
public record AgentRunResult(
        int    exitCode,            // process exit code; -1 on launch failure
        boolean killedForInactivity,// inactivity watchdog fired
        String responseText,        // any final textual answer (may be empty)
        String sessionId,           // runner's session id; may be null
        long   durationMs,
        long   durationApiMs,       // 0 when the runner does not separate
        int    numTurns,            // 0 when the runner has no turn concept
        double costUsd,             // 0 when not reported
        String stopReason,          // "success" | "error_max_turns" | runner-specific
        boolean sessionIsError,
        List<String> deniedToolNames,
        Map<String, String> runnerMetadata // free-form; e.g. opencode session path
) {}
```

### 1.5 `AgentCapabilities`

```java
public record AgentCapabilities(
        boolean reportsCost,
        boolean reportsTurns,
        boolean supportsEffortLevel,
        boolean supportsMaxBudget,
        boolean supportsMcpHttpTransport,
        boolean supportsMcpStdioTransport,
        boolean supportsPermissionDenialReporting,
        Set<String> supportedModels    // for validation
) {}
```

When a runner does not report cost, the telemetry layer surfaces `costUsd=0`
and tags the phase with `cost_reported=false` in the event metadata
(§7).

### 1.6 In-process vs. subprocess runners

The contract is process-agnostic: a future in-JVM runner that calls an HTTP
inference endpoint would simply not start a subprocess. Today, both Claude
Code and opencode are CLI tools — both runners launch a subprocess via
`ClaudeAttemptRunner.runAttempt(...)` (which is being renamed in Phase 1; see
§2.1).

### 1.7 Registry

A small singleton:

```java
public final class AgentRunnerRegistry {
    public static void register(String name, Supplier<AgentRunner> factory);
    public static AgentRunner get(String name);
    public static Set<String> available();
}
```

Static initializer pre-registers `"claude"` and (once added) `"opencode"`.

---

## 2. Refactored orchestrator

### 2.1 Decision: **keep `ClaudeCodeJob` as the orchestrator class name**

**Recommendation: keep the name.** Rationale:

- Wire serialization. `ClaudeCodeJob.encode()` produces wire-format keys
  ("prompt", "tools", "maxTurns", etc.) that are persisted on the receiving
  node and replayed on deserialization. Renaming the class would change the
  class-discriminator used by `AbstractJobFactory` and break in-flight jobs.
- `ClaudeCodeJob.Factory` is a backward-compat alias that is itself part of
  the wire-format identity (see `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java:1568`).
  Removing it forces every persisted job spec to be rewritten.
- 6 production files and 7 test files reference `ClaudeCodeJob` by name
  (§0.8). Each is a small change individually, but each is also an
  observable wire-format break.
- The class will still semantically be a coding-agent job — it just gates
  the agent choice through a runner map. The "Claude Code" in its name will
  become slightly stale, but stale-name-but-working is preferable to
  fresh-name-with-migration-pain.

If renaming is later deemed worthwhile, the swap is a separate ticket from
this refactor. The cosmetic rename does not affect any of the design
decisions below.

Internal subprocess classes **are** renamed (since they are package-private
and not part of the wire format):

| Old | New |
|-----|-----|
| `ClaudeAttemptRunner` | `AgentProcessRunner` |
| `ClaudeInactivityMonitor` | `AgentInactivityMonitor` |

### 2.2 Per-phase runner map

`ClaudeCodeJob` gains:

```java
private final Map<Phase, String> runnerByPhase = new EnumMap<>(Phase.class);
private String defaultRunner = "claude";

public enum Phase {
    PRIMARY,
    DEDUPLICATION,
    ORGANIZATIONAL_PLACEMENT,
    ENFORCE_CHANGES,
    MAVEN_DEPENDENCY_PROTECTION,
    POST_COMPLETION,
    COMMIT_MESSAGE,
    GIT_TAMPERING_RESTART
}

public void setRunnerForPhase(Phase phase, String runnerName) { ... }
public String getRunnerForPhase(Phase phase) {
    return runnerByPhase.getOrDefault(phase, defaultRunner);
}
public void setDefaultRunner(String runnerName) { ... }
```

The phase values map 1-1 onto the enforcement rule `getName()` values plus
`PRIMARY` and `GIT_TAMPERING_RESTART`. Every existing call site that
ultimately reaches `executeSingleRun()` is replaced by:

```java
String runnerName = getRunnerForPhase(currentPhase);
AgentRunner runner = AgentRunnerRegistry.get(runnerName);
AgentRunResult result = runner.run(buildRequest(currentPhase), this);
absorbResult(result);
```

`buildRequest()` and `absorbResult()` are new private methods that wrap the
existing `launchClaudeAttempt()` and `extractOutputMetrics()` bodies, with
the Claude-specific logic moved to `ClaudeCodeRunner` (§3).

### 2.3 `currentActivity` already maps onto the phase

Today `runCorrectionSession()` sets `currentActivity` to the rule name. The
refactor reuses this as the phase tag — no new state is needed during a
session, only at dispatch time. `runEnforcementRules()` already iterates the
rule list in order; it passes each rule's `Phase` to the runner lookup.

`Phase.GIT_TAMPERING_RESTART` corresponds to the re-run inside
`onGitTampering()`. `Phase.PRIMARY` is the initial `executeSingleRun()` in
`doWork()`.

### 2.4 Serialization

The runner map is serialized as a compact CSV in the existing wire format:

```
::runners:=primary=claude,dedup=opencode,org=claude,...
```

`encode()` writes this key only when the map differs from the default
("claude" for every phase). `set("runners", value)` parses the CSV back into
the EnumMap. A missing key (older wire formats) leaves the map empty — every
lookup falls back to `defaultRunner="claude"`, preserving current behavior.

A separate `::defaultRunner:=opencode` key holds `defaultRunner` when set
non-Claude; default omits the key.

### 2.5 Migration path

- **In-flight jobs** (already encoded on disk on a node) deserialize without
  the new keys. `defaultRunner` stays `"claude"`. Behavior unchanged.
- **Existing jobs in flight in a database / queue** carry encoded
  `ClaudeCodeJob` strings; deserialization is identical to today plus the
  ignored-unknown-key tolerance already present in
  `AbstractJobFactory.set()`.
- **Workstream config YAML** silently ignores a `defaultRunners` block when
  the controller is the old build (Jackson is configured to ignore unknown
  properties — see `WorkstreamConfig`).
- **MCP tool** `workstream_submit_task` gains optional `runners` and
  `default_runner` parameters. Missing → "claude" everywhere.

The deserialize-unknown-key tolerance is the safety net: even if a new
encoder writes `::runners:=...` to an old decoder, the old decoder ignores
it.

---

## 3. The Claude Code runner

### 3.1 Scope of the move

`ClaudeCodeRunner` implements `AgentRunner` and absorbs:

- `ClaudeCodeJob.launchClaudeAttempt()` (lines ~942-1018)
- `ClaudeCodeJob.extractOutputMetrics()` (lines ~1382-1429)
- `ClaudeCodeJob.getTextOrNull()` static helper

The orchestrator no longer mentions `claude`, `--allowedTools`,
`--mcp-config`, `--max-turns`, `--max-budget-usd`, `--model`, `--effort`,
`session_id`, `total_cost_usd`, `num_turns`, or the
`mcpConfigBuilder`/`toolsDownloader` fields anywhere in its body. Those
become `ClaudeCodeRunner` internals.

### 3.2 Configuration the runner receives via `AgentRunRequest`

Already enumerated in §1.3. Specific Claude mappings:

| Request field | Claude flag |
|---------------|-------------|
| `prompt` | `-p <prompt>` |
| `allowedTools` | `--allowedTools <csv>` |
| `mcpConfigJson` | `--mcp-config <json>` |
| `maxTurns` | `--max-turns <n>` |
| `maxBudgetUsd` | `--max-budget-usd <%.2f>` (if > 0) |
| `model` | `--model <id>` (if non-null; validated against `VALID_MODELS`) |
| `effort` | `--effort <level>` (validated against `VALID_EFFORT_LEVELS`) |
| `outputCapturePath` | written by runner with `--output-format json` stdout |
| `activityTag` | env `AR_AGENT_ACTIVITY` |

Model/effort validation moves to `ClaudeCodeRunner.capabilities().supportedModels`
+ `ClaudeCodeRunner.validateRequest(request)`. The legacy
`ClaudeCodeJob.VALID_MODELS` / `VALID_EFFORT_LEVELS` constants are retained
as package-private references for tests and the controller; their canonical
home becomes `ClaudeCodeRunner`.

### 3.3 Per-phase override of model/effort

Because phase-level configuration may need different models, the
`AgentRunRequest` carries the *resolved* model and effort. The orchestrator
chooses, per phase, which model and effort to request — see §6.

### 3.4 Output absorption

`extractOutputMetrics()` becomes `ClaudeCodeRunner.parseClaudeNdjson()` and
returns the populated `AgentRunResult`. The orchestrator's `absorbResult()`
accumulates `durationMs`, `durationApiMs`, `numTurns`, `costUsd`, and
`permissionDenials` across sessions exactly as today.

### 3.5 MCP configuration

`McpConfigBuilder` stays in the orchestrator package. The orchestrator
builds the JSON and CSV (since they are independent of the runner;
ar-manager is the same MCP server regardless of which CLI calls it) and
hands them to the runner via the request. See §5 for the allowlist
discussion.

---

## 4. The opencode runner

### 4.1 Headless invocation

opencode supports a non-interactive `opencode run` subcommand that accepts
a prompt and runs an agent session to completion. The runner spawns:

```
opencode run \
    --model <provider/model> \
    --config <path/to/config.json> \
    --output-format json \
    --prompt <prompt>
```

Exact flag names must be confirmed against opencode's release at
implementation time (the project is under active development and CLI flags
have changed across releases). The runner reads the help output during its
own startup probe (see §8.2) and caches the discovered flag map; this lets
the runner adapt to flag drift without requiring a code change.

### 4.2 MCP integration

opencode supports MCP servers via its `mcp` configuration block. The
existing `McpConfigBuilder` JSON shape (`{"mcpServers":{...}}`) is the same
shape opencode accepts in its config file's `mcp` section. The runner does
**one** translation step:

- Wrap the orchestrator-built JSON under `{"mcp": {...mcpServers...}}` (or
  whatever opencode's release expects) and write it to a temp config file
  next to `claude-output/`.

ar-manager exposes itself via HTTP+bearer, which both Claude Code and
opencode support. The pushed-tools stdio entries (`python3 path/to/server.py`)
are identical for both runners.

### 4.3 Allowlist / permissions

opencode does not use a `--allowedTools` flag the way Claude Code does. It
has a permission model based on tool categories and per-tool config. The
runner translates the CSV allowlist into the opencode permission file shape:

- Built-in tools (`Read`, `Edit`, `Bash`, `Glob`, `Grep`, `Write`) map to
  opencode's built-in tool toggles.
- `mcp__<server>__<tool>` entries map to per-server tool grants in the
  opencode config.

The translation is owned by `OpencodeRunner.translateAllowedTools(...)`;
unit tests cover round-trips for every known prefix.

### 4.4 Cost reporting

opencode emits per-session token usage in its JSON output. The runner
estimates cost from `tokens × provider_rate` using a small in-runner price
table keyed by model name. Cost is approximate. The runner reports
`capabilities().reportsCost = true` only when a price is known for the
selected model; otherwise it reports `costUsd = 0` and `reportsCost = false`.

A controller-side `controller.opencode.pricing` config (or env var) lets
operators override the embedded table without a code change.

### 4.5 Turn counting

opencode's "iterations" or "steps" count is reported as `numTurns`. The
mapping is exact when opencode reports a `steps` field; otherwise the
runner counts assistant messages in the transcript.

### 4.6 Feature gaps and how the runner handles them

| Feature | Claude Code | opencode (typical) | Resolution |
|---------|-------------|---------------------|------------|
| `--max-budget-usd` | yes | no | runner enforces by polling cost between turns and aborting; treat as best-effort |
| `--effort` thinking level | yes | partial (model-specific) | accept the value, pass through if the chosen provider supports it, else log and proceed |
| Correction sessions | yes | yes (new `opencode run`) | each session is a fresh invocation; no special support needed |
| `permission_denials` reporting | yes | needs verification | report empty list and set `supportsPermissionDenialReporting=false` if absent |
| JSON output schema | stable | evolving | implement a forgiving parser that warns on unknown fields |

When a feature is missing for the selected phase, the orchestrator does
**not** transparently switch runners. The job submitter chose this runner
explicitly; silently falling back to Claude would hide cost/behavior
differences that are the whole point of the per-phase override. Instead,
the missing capability is logged at runner-launch time and the run
proceeds with the runner's best-effort behavior.

### 4.7 Binary discovery

The runner looks for the opencode binary in this order:

1. `OPENCODE_BIN` env var.
2. Path stored in workstream config (`opencodeBinary`).
3. `~/.flowtree/bin/opencode` (the controller-managed install — see §10).
4. `PATH` lookup of `opencode`.

If nothing is found, the runner throws an `AgentRunnerNotAvailableException`
at `run()` time, which the orchestrator surfaces as a job failure with a
clear message — no silent fallback.

---

## 5. MCP server and ar-manager considerations

### 5.1 Who owns the allowlist?

**The orchestrator owns the allowlist.** Each phase passes the same
`McpConfigBuilder`-produced CSV to whichever runner is selected; the runner
is responsible for adapting that list to its native permission shape. This
is the right boundary because:

- The allowlist is a *policy* concern (what the agent is permitted to do on
  the workspace), not a *runner* concern (how that policy is expressed on
  the wire).
- ar-manager's tool inventory (`AR_MANAGER_TOOL_NAMES` /
  `EXCLUDED_AR_MANAGER_TOOLS`) is identical regardless of runner. Owning the
  CSV in one place keeps the `allowlistCoversEveryArManagerTool` test —
  which is one of the load-bearing safety nets for new MCP tools — in the
  Claude-runner package today and applicable to every runner.
- The MCP server inventory (ar-manager + pushed + project) likewise is
  workstream policy, not a runner choice.

### 5.2 Tools that may behave differently across runners

`workstream_submit_task` self-collision is enforced server-side (the agent
gets a clear error if it tries to submit to its own workstream — see
`tools/mcp/manager/server.py:1466-1526`). No runner-specific concern.

`secret_render_file` (ar-secrets, in-container stdio) writes to a path on the
agent's host. Both runners run in the same host filesystem namespace, so
the secret file is reachable from either. Documented in
`tools/mcp/SECRETS.md`; no change.

The runner audit pass during Phase 3 catalogues every tool currently in
`AR_MANAGER_TOOL_NAMES` and flags any that uses a Claude-specific feature
(none today, but the audit is mandatory before opencode launch).

### 5.3 MCP config translation

| Source field | Claude shape | opencode shape |
|--------------|--------------|----------------|
| HTTP MCP (ar-manager) | `{"type":"http","url":...,"headers":{...}}` | `{"type":"http","url":...,"headers":{...}}` (verify) |
| stdio MCP (pushed/project) | `{"command":"python3","args":[...]}` | same shape |
| top-level key | `mcpServers` | `mcp` (verify against opencode release) |

The `OpencodeRunner` performs the top-level wrap; the inner JSON is reused.
If a future opencode release diverges further, the runner gains a
translation table without touching the orchestrator.

---

## 6. Per-phase runner selection

### 6.1 Job submission JSON

Adds a single field on `POST /api/workstreams/{id}/submit`:

```json
{
  "prompt": "...",
  "runners": {
    "default": "claude",
    "primary": "opencode",
    "deduplication": "claude",
    "organizational-placement": "claude",
    "enforce-changes": "opencode",
    "post-completion": "opencode",
    "maven-dependency-protection": "claude",
    "commit-message": "claude",
    "git-tampering-restart": "claude"
  }
}
```

`default` is optional and defaults to `"claude"`. Per-phase keys are
optional; unspecified keys inherit `default`. Phase keys use the same
strings as the rule names (kebab-case) for consistency.

### 6.2 `workstream_submit_task` MCP tool

Adds parameters:

```python
runners: str = ""          # JSON string, e.g. '{"primary":"opencode"}'
default_runner: str = ""   # convenience for the all-phases case
```

Empty strings → server falls back to workstream defaults → falls back to
controller defaults → falls back to `"claude"` everywhere.

The MCP tool validates the JSON shape locally and rejects unknown phase
names with a clear error so misconfigured submissions fail at the caller.

### 6.3 Workstream YAML

`WorkstreamConfig.Entry` gains:

```yaml
workstreams:
  - workstreamId: ws-foo
    defaultRunner: opencode
    runners:
      primary: opencode
      deduplication: claude
      commit-message: claude
```

`Workstream` carries the same fields. `FlowTreeApiEndpoint`'s submit path
merges:

```
job.runners[p] = request.runners[p]
              ?? workstream.runners[p]
              ?? workstream.defaultRunner
              ?? controller.defaultRunner
              ?? "claude"
```

### 6.4 Validation

The controller validates every runner name against
`AgentRunnerRegistry.available()` at submit time. Unknown name → 400. This
fails *before* a job is dispatched so the operator gets a clean error
rather than a runtime exception on the node.

### 6.5 Precedence summary

```
per-job override > workstream.runners.<phase> > workstream.defaultRunner
                 > controller default > "claude"
```

---

## 7. Telemetry

### 7.1 New fields on `ClaudeCodeJobEvent`

(The event class is named `ClaudeCodeJobEvent` today; renaming it carries
the same wire-format risk as renaming `ClaudeCodeJob`. Keep the name; add
fields.)

```java
/** Runner used per phase. Map<phase-name, runner-name>. */
private Map<String, String> runnerByPhase;

/** Per-runner cost/turn rollup, populated by accumulateRunnerStats(). */
private Map<String, RunnerStats> runnerStats;

public record RunnerStats(
        int    sessions,
        long   durationMs,
        long   durationApiMs,
        double costUsd,
        int    numTurns,
        boolean costReported,
        boolean turnsReported) {}
```

`commitMessageSource` already exists; no change.

### 7.2 Capture path

After each `AgentRunner.run()` call, the orchestrator updates the matching
`RunnerStats` entry with the absorbed metrics. The
`capabilities().reportsCost/reportsTurns` flags determine `costReported` /
`turnsReported`. Total `costUsd` continues to be the sum across all
sessions; the rollup lets analysts see how the total breaks down by runner.

### 7.3 Handling runners that don't report cost or turns

- `costUsd` from the runner is summed into the total. A non-reporting runner
  contributes 0. The per-runner `costReported=false` flag is the canonical
  "did this number include the runner?" indicator.
- `numTurns` likewise; a non-reporting runner contributes 0 and
  `turnsReported=false`.
- An aggregate field `unmeasuredCostRunners: List<String>` is set when any
  runner reports `reportsCost=false`. This is the field downstream
  dashboards should consult before drawing per-job cost conclusions.

### 7.4 Wire format

`JobCompletionEvent` is JSON-serialized when sent over HTTP (the
`/messages` and `/update` endpoints). The new fields are added to the JSON
schema (`tools/mcp/manager/server.py` consumers should tolerate unknown
keys today; verify and add a test).

---

## 8. Testing strategy

### 8.1 Unit tests — per runner

`ClaudeCodeRunnerTest`:
- Builds an `AgentRunRequest`, mocks `AgentProcessRunner.runAttempt()` via a
  test seam, and asserts the exact command line passed to `ProcessBuilder`.
- Parses a recorded NDJSON sample (already present in
  `claude-output/`-shaped fixtures) and asserts metric extraction matches a
  known-good `AgentRunResult`.
- Exercises model / effort validation.
- Confirms that the `currentActivity` env var maps to `AR_AGENT_ACTIVITY`.

`OpencodeRunnerTest`:
- Same shape, against opencode's CLI and JSON output. Initial test uses
  pre-recorded JSON fixtures captured during opencode binary probing.
- Translates a known `--allowedTools` CSV to the opencode permission file
  and asserts the JSON is structurally correct.
- Wraps and unwraps the MCP config JSON between Claude shape and opencode
  shape.

### 8.2 Integration tests — orchestrator

`ClaudeCodeJobPhaseDispatchTest`:
- Registers a recording mock runner.
- Submits a job with a mix of runners per phase.
- Asserts the orchestrator called `recordingRunner.run()` exactly once per
  phase, in the expected order, with the expected `AgentRunRequest`
  contents.

`ClaudeCodeJobSerializationTest` extension:
- Round-trips a job whose `runnerByPhase` map is set, asserting
  `encode()` → `set()` reproduces the original.
- Round-trips with an old wire-format string (no `runners` key) and asserts
  `defaultRunner == "claude"`.

`McpToolDiscoveryTest`:
- Already covers `workstream_submit_task`. Extend to confirm `runners` and
  `default_runner` parameters are declared (the `expected` set in
  `McpToolDiscoveryTest.managerToolParametersAreProperlyDeclaredInSignatures`
  is the right place — see `tools/mcp/CLAUDE.md`).

### 8.3 Live smoke fixture

`AgentRunnerSmokeIT` (integration test, disabled by default, run manually
or by a labeled CI job):
- Trivial task: "Write a `hello.txt` file with the content 'hello'."
- Runs each registered runner once, asserting that:
  - `hello.txt` exists on disk afterward.
  - `AgentRunResult.exitCode == 0`.
  - `responseText` is non-empty.

This test guards the contract: every runner has to be able to do at least
one trivial file edit and report success. Smoke fixtures are the only test
that requires a real `claude` or `opencode` binary on the executor.

### 8.4 Regression sweeps

The full existing test suite in
`flowtree/core/src/test/java/io/flowtree/jobs/` must pass unchanged through
Phase 1. Any test that asserts specific Claude flag construction moves into
`ClaudeCodeRunnerTest` (no behavior change, just relocation).

### 8.5 Cost-and-turn-reporting test

`AgentRunResultRollupTest`:
- Feeds a sequence of results from runners with mixed
  `reportsCost`/`reportsTurns` capabilities and asserts that the resulting
  event correctly populates `unmeasuredCostRunners` and per-runner stats.

---

## 9. Implementation phases

Every phase ends with the existing test suite (`flowtree` module +
`tools/mcp/manager` pytest) green. Phases are sized to be one PR each.

### Phase 1 — extract `AgentRunner` and move Claude logic out

**Files added:**
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentRunner.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentRunRequest.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentRunResult.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentCapabilities.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentRunnerRegistry.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/ClaudeCodeRunner.java`
- `flowtree/core/src/test/java/io/flowtree/jobs/agent/ClaudeCodeRunnerTest.java`

**Files renamed:**
- `ClaudeAttemptRunner.java` → `AgentProcessRunner.java`
- `ClaudeInactivityMonitor.java` → `AgentInactivityMonitor.java`

**Files modified:**
- `ClaudeCodeJob.java` — `launchClaudeAttempt()` and
  `extractOutputMetrics()` move out; `executeSingleRun()` becomes a thin
  wrapper around `AgentRunnerRegistry.get("claude").run(...)`.

**Behavioral change:** none. Phase 1 is a pure refactor.

**Risks:**
- Wire-format drift: verify `ClaudeCodeJob.encode()` output is unchanged
  using `ClaudeCodeJobSerializationTest` and the existing snapshot tests.
- Allowlist regression: keep `McpConfigBuilder` in its current package; add
  no new behavior to it.

### Phase 2 — plumb per-phase runner selection through the surface

**Files added/modified:**
- `ClaudeCodeJob` — add `runnerByPhase` map, `Phase` enum, getters/setters,
  encode/set support, dispatch through the map at every site that today
  calls `executeSingleRun()`.
- `ClaudeCodeJobFactory` — propagate the map onto created jobs.
- `Workstream`, `WorkstreamConfig` — add `defaultRunner` + `runners` map.
- `FlowTreeApiEndpoint.handleSubmit(...)` — parse `runners` from the
  request body, validate against `AgentRunnerRegistry`, apply precedence.
- `SlackListener` — pass-through; no UX change yet.
- `tools/mcp/manager/server.py:workstream_submit_task` — add `runners` and
  `default_runner` parameters. Update the docstring (see
  `tools/mcp/CLAUDE.md`).
- `tools/mcp/manager/test_server.py` — assert parameters declared in
  signature.
- `flowtree/core/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` —
  extend `managerToolParametersAreProperlyDeclaredInSignatures`.

**Behavioral change:** none unless the submitter actively sets `runners`;
defaults reproduce Phase 1 behavior.

**Risks:**
- Validation rejection at submit time should not regress legitimate
  submissions. The test matrix must cover: empty `runners`, all-defaults
  `runners`, partial `runners` (some phases set, others inherit), unknown
  runner name (→ 400), legacy submission (`runners` absent).
- Tests asserting bytes-on-the-wire for `ClaudeCodeJob.encode()` may need
  updates when `runnerByPhase` is non-default; gate the new key on
  non-default value to keep current outputs unchanged.

### Phase 3 — add the opencode runner

**Files added:**
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/OpencodeRunner.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/OpencodeBinaryLocator.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/OpencodeOutputParser.java`
- `flowtree/core/src/test/java/io/flowtree/jobs/agent/OpencodeRunnerTest.java`
- `flowtree/core/src/test/java/io/flowtree/jobs/agent/OpencodeOutputParserTest.java`
- `flowtree/core/src/test/java/io/flowtree/jobs/agent/AgentRunnerSmokeIT.java`
  (disabled by default).

**Files modified:**
- `AgentRunnerRegistry` — register `"opencode"`.
- `tools/mcp/manager/server.py` — validate opencode is a known runner
  (server-side enum lives in a small new module; agent side trusts the
  controller to validate).

**Behavioral change:** opencode becomes selectable on any phase. Jobs that
do not select it are unaffected.

**Risks:**
- opencode binary not present on the executor — handled by
  `AgentRunnerNotAvailableException`.
- opencode CLI flag drift — handled by the runner's startup help-probe
  (`opencode --help` → flag map; cached per process).
- Cost reporting precision — initial release flags `reportsCost=false`
  unless a price table for the chosen model is configured. Better to
  under-promise than to mislead operators.

### Phase 4 — recommended-configurations rollout

**Files added:**
- `docs/operations/PLUGGABLE_AGENTS_RECIPES.md` — recommended phase mixes
  with the rationale for each.

**Files modified:**
- `WorkstreamConfig` validation — warn on configurations that select a
  runner without the capability the phase requires (e.g., a phase that
  semantically needs cost accounting paired with a non-reporting runner).
- Slack help text in `FlowTreeController.handleHelp(...)` (if present) —
  mention pluggable runners.

Recommended starting recipes (validated against the smoke fixture):

| Recipe | primary | dedup | placement | post-completion | commit-message | rationale |
|--------|---------|-------|-----------|-----------------|----------------|-----------|
| All-Claude (default) | claude | claude | claude | claude | claude | reproduces today |
| Mixed-review | opencode | claude | claude | opencode | claude | use opencode where exploratory work and verification dominate; keep Claude for prompts that have proven prompts under load |
| All-opencode | opencode | opencode | opencode | opencode | opencode | for workstreams cost-sensitive enough to accept best-effort cost reporting |

**Behavioral change:** documentation only; default behavior unchanged.

**Risks:** none; documentation cannot regress the runtime.

---

## 10. Open questions for the human reviewer

The plan as written makes these recommendations; please confirm before
implementation starts.

1. **Rename `ClaudeCodeJob`?** Plan recommends *no* (wire-format + call
   sites). Renaming `ClaudeAttemptRunner`→`AgentProcessRunner` and
   `ClaudeInactivityMonitor`→`AgentInactivityMonitor` is safe (package-
   private). Confirm.

2. **Runner abstraction shape:** plan recommends a Java interface
   (`AgentRunner`) plus a small `AgentRunnerRegistry`. Alternative was
   `ServiceLoader` — rejected because the number of runners is small and a
   ServiceLoader adds a packaging step with no payoff. Confirm.

3. **Runners that don't faithfully report cost or turns:** plan returns
   `costUsd=0` / `numTurns=0` with `reportsCost=false` /
   `reportsTurns=false`, and surfaces a top-level
   `unmeasuredCostRunners: List<String>` on the event. Alternative was a
   per-event field `costAccountingComplete: boolean`. Confirm preferred
   surface shape.

4. **`--allowedTools` composition:** plan recommends **orchestrator-owned**
   (the orchestrator passes a CSV and an MCP-config JSON to every runner;
   the runner translates to native shape). Alternative was runner-owned —
   rejected because it duplicates the ar-manager inventory list and breaks
   the `allowlistCoversEveryArManagerTool` test. Confirm.

5. **opencode binary location and version policy:**
   - Discovery: `OPENCODE_BIN` env var → workstream `opencodeBinary` →
     `~/.flowtree/bin/opencode` → `PATH`.
   - Version: pinned per-controller via `controller.opencode.minVersion` in
     `WorkstreamConfig`; the runner checks `opencode --version` at startup
     and refuses to launch if older.
   - Provisioning: deliberately **not** bundled with FlowTree; install path
     is `~/.flowtree/bin/`, owned by the operator. Confirm this is the
     right boundary (the alternative is for the controller to manage the
     install via something like `ManagedToolsDownloader`).

6. **New Maven module for the runner abstraction?** Per the project rule
   *"agents must NEVER create new Maven modules"*, this plan does not
   create one. From a design standpoint, a new `flowtree-agents` module
   would arguably be cleaner (smaller dependency surface — the runner
   classes don't need the rest of `flowtree`). If you want that module,
   create it manually and the implementation jobs will move
   `io.flowtree.jobs.agent.*` into it as Phase 1 lands. If you do not,
   the package stays inside `flowtree` and nothing else changes.

7. **`Phase` enum membership:** plan includes `PRIMARY`, the six rule
   phases, plus `GIT_TAMPERING_RESTART`. The git-tampering restart could
   instead be treated as a continuation of `PRIMARY` (since
   `onGitTampering()` re-runs the same prompt). Confirm whether the
   git-tampering restart should be configurable separately (recommended:
   yes, because a future operator might want a different runner to "calm
   down" a tampering agent).

8. **What identifies a runner on the wire?** Plan uses short string names
   (`"claude"`, `"opencode"`). Alternative is a fully qualified class name
   for forward compatibility. Strings are cleaner and easier to validate
   on the MCP tool side. Confirm.

9. **Test depth for smoke fixture:** plan disables `AgentRunnerSmokeIT` by
   default and runs it only via a labeled CI job, because it requires real
   binaries. Should it run on every push, only on the
   `feature/pluggable-agents` branch, or only when explicitly requested?

10. **Behavior when a phase requests a runner that is not available on the
    executor:** plan treats this as a job failure (no fallback). Confirm
    this is correct rather than silently substituting the default runner;
    silent substitution would hide cost/behavior differences that are the
    whole point of the per-phase override.

---

## Appendix A — file inventory

### Modified in Phase 1 (refactor)
- `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeAttemptRunner.java` → rename
- `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeInactivityMonitor.java` → rename

### Modified in Phase 2 (per-phase selection)
- `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java`
- `flowtree/core/src/main/java/io/flowtree/jobs/ClaudeCodeJobFactory.java`
- `flowtree/core/src/main/java/io/flowtree/slack/Workstream.java`
- `flowtree/core/src/main/java/io/flowtree/slack/WorkstreamConfig.java`
- `flowtree/core/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`
- `tools/mcp/manager/server.py`
- `tools/mcp/manager/test_server.py`
- `flowtree/core/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java`
- `flowtree/core/src/test/java/io/flowtree/jobs/ClaudeCodeJob*Test.java` (where
  serialization is asserted)

### Modified in Phase 3 (opencode runner)
- `flowtree/core/src/main/java/io/flowtree/jobs/agent/AgentRunnerRegistry.java`
- `tools/mcp/manager/server.py` (registry-aware validation)

### Modified in Phase 4 (docs + validation)
- `flowtree/core/src/main/java/io/flowtree/slack/WorkstreamConfig.java`
- `docs/operations/PLUGGABLE_AGENTS_RECIPES.md` (new)

## Appendix B — Out of scope

- Changes to how the agent itself communicates with the controller
  (ar-manager remains the canonical channel; the runner abstraction does
  not touch this layer).
- Changes to the enforcement rule contract beyond passing the `Phase`
  argument through to the dispatch site.
- Adding a third-runner template — explicitly deferred until a concrete
  need (e.g., a self-hosted model) emerges.
- Persisting runner choice on an aborted job's re-submit — out of scope;
  re-submission is a separate flow.
