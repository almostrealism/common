# Unified Per-Phase Configuration: `runner`, `model`, `effort`

## Goal

Bring `model` and `effort` under the same per-phase precedence ladder that
`runner` already uses. Today, runner selection is per-phase (a workspace,
workstream, or job-submission can route REVIEW to a different runner than
PRIMARY), while `model` and `effort` are per-job — one value applies to every
phase. This split is wrong long-term: different phases benefit from different
model/effort settings just as they benefit from different runners.

The target shape is a uniform per-phase `PhaseConfig` value carrying
`(runner, model, effort)` at every container level (workspace, workstream,
job) with each field resolved independently through the same precedence
ladder.

---

## Current State

### Where the three settings live today

| Setting | Workspace | Workstream | Job submission | Runtime owner |
|---|---|---|---|---|
| `runner` | per-phase (`WorkspaceEntry.runners`, `WorkspaceEntry.defaultRunner`) | per-phase (`Workstream.runners`, `Workstream.defaultRunner`) | per-phase (`runners` JSON in submission body) | `CodingAgentJob.runnerByPhase` / `defaultRunner` |
| `model`  | — | job-level (`WorkstreamConfig.WorkstreamEntry.model`, `Workstream.model`) | job-level (`model` parameter) | `CodingAgentJob.model` (single field) |
| `effort` | — | job-level (`WorkstreamConfig.WorkstreamEntry.effort`, `Workstream.effort`) | job-level (`effort` parameter) | `CodingAgentJob.effort` (single field) |

Specific class references:

- `flowtree/runtime/src/main/java/io/flowtree/slack/WorkstreamConfig.java`
  - `WorkspaceEntry` (lines ~116–277): has `defaultRunner`, `runners`. **No `model` or `effort`.**
  - `WorkstreamEntry` (lines ~308+): has `model`, `effort`, `defaultRunner`, `runners`.
- `flowtree/runtime/src/main/java/io/flowtree/slack/Workstream.java`
  - In-memory workstream object: has `model`, `effort`, `defaultRunner`, `runners` (`getRunners(): Map<String,String>`).
- `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java`
  - Fields: `model`, `effort` (singletons); `defaultRunner`, `runnerByPhase: Map<Phase,String>`.
  - `buildAgentRunRequest` (line ~1180–1196) passes the same `model`/`effort` for every phase.
- `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJobFactory.java`
  - Mirrors `CodingAgentJob`: factory-level `model`/`effort` singletons, plus `defaultRunner`/`runnerByPhase`.
  - Wire-format encoding lives in `Phase.encodeRunnerMap` / `Phase.decodeRunnerMap`.
- `flowtree/agents/src/main/java/io/flowtree/jobs/agent/AgentRunRequest.java`
  - Per-invocation parameter object built by `buildAgentRunRequest`. Already
    carries `model` and `effort` per invocation — the data model below the
    job already supports per-phase variation.

### How the values reach the runner today

```
submission body → FlowTreeApiEndpoint.handleSubmit
   → CodingAgentJobFactory.set("model" / "effort" / "defaultRunner" / "runners")
   → CodingAgentJob (newJob copies model, effort, defaultRunner, runnerByPhase)
   → for each phase: AgentRunRequest.builder()
                       .model(job.model)        // same value every phase
                       .effort(job.effort)      // same value every phase
                       …
   → resolveRunner(phase) picks runner per phase
   → runner.run(request, job)
```

`CodingAgentJob.executeOnce` (around line 1060) calls
`resolveCurrentPhase()` then `resolveRunner(currentPhase)`. The
`AgentRunRequest` it builds is per-invocation, so by the time the runner
sees the request the values *could* differ between phases — they simply
don't, today, because the job carries a single `model`/`effort` pair.

### The runner contract

`AgentCapabilities` (a record at `flowtree/agents/src/main/java/io/flowtree/jobs/agent/AgentCapabilities.java`)
declares per-runner flags including:

- `supportsEffortLevel: boolean`
- `supportedModels: Set<String>` (empty means "unconstrained")

Today, `opencode` advertises `supportsEffortLevel = false` (see
`OpencodeRunner.capabilities()` / `PHASES.md`). The runner ignores any
effort value it is handed. There is no validation that rejects an effort
value passed to a runner that doesn't support it — the value is silently
dropped on the runner side.

`CodingAgentJob.setModel` / `setEffort` validate the job-level value
against `ClaudeCodeRunner.VALID_MODELS` / `VALID_EFFORT_LEVELS` (aliased on
`CodingAgentJob` as `VALID_MODELS` and `VALID_EFFORT_LEVELS`). Validation
is currently global to the job rather than per-phase, which means a
mixed-runner job today cannot legally set an `effort` that the Claude
runner would accept but that opencode does not understand — even though
opencode would silently ignore it.

### The runner-resolution ladder (today)

`flowtree/runtime/src/main/java/io/flowtree/slack/SubmissionRunnerResolver.java`
already implements the 6-level ladder for **runner only**:

1. Per-job override (`runners` JSON: phase key)
2. Per-job default (`runners` JSON: `"default"` key)
3. Workstream per-phase map
4. Workstream `defaultRunner`
5. Workspace per-phase map
6. Workspace `defaultRunner`
7. Controller default (`"claude"`)

Levels 5–6 are skipped when the workstream sets `defaultRunner`, so the
workstream-level default fully shadows the workspace's per-phase entries —
documented in the resolver's javadoc and `flowtree/docs/architecture/PHASES.md`.

### MCP surface (today)

In `tools/mcp/manager/server.py`:

- `workstream_submit_task` accepts: `model`, `effort`, `runners` (JSON
  object: phase → runner, plus optional `"default"`), `default_runner`.
- `workstream_register` accepts: same.
- `workstream_update_config` accepts: same.
- `workspace_update_config` accepts: `default_runner`, `runners` (JSON). It
  does **not** currently accept `model` or `effort` (workspace-level
  model/effort is not modelled on `WorkspaceEntry`).
- `agent_options` returns runners, phases, and models for discovery (per
  the prior `PER_WORKSTREAM_RUNNERS.md` plan).

The shared validator is `_parse_runners_json(runners)` — parses, validates
phase keys against the enum, returns a 400-style dict on shape errors.

---

## The New Data Model

### `PhaseConfig` value object

Add a new immutable record:

```java
package io.flowtree.jobs.agent;

/**
 * Per-phase configuration triple. Each field is {@code null} when this
 * level has nothing to say about it, so the {@code PhaseConfigResolver}
 * falls through to the next precedence level for that field
 * independently of the other two.
 */
public record PhaseConfig(String runner, String model, String effort) {

    /** Sentinel: nothing configured at this level. */
    public static final PhaseConfig EMPTY = new PhaseConfig(null, null, null);

    /** True when every field is null. */
    public boolean isEmpty() { return runner == null && model == null && effort == null; }

    /** Returns a copy with the given runner; preserves model and effort. */
    public PhaseConfig withRunner(String r)  { return new PhaseConfig(r, model, effort); }
    public PhaseConfig withModel(String m)   { return new PhaseConfig(runner, m, effort); }
    public PhaseConfig withEffort(String e)  { return new PhaseConfig(runner, model, e); }

    /**
     * Returns a config where each null field of {@code this} is filled in
     * from {@code other}; non-null fields of {@code this} win. Used by the
     * resolver to layer one precedence level on top of another.
     */
    public PhaseConfig overlayOn(PhaseConfig other) {
        if (other == null) return this;
        return new PhaseConfig(
            runner != null ? runner : other.runner,
            model  != null ? model  : other.model,
            effort != null ? effort : other.effort);
    }
}
```

**Module placement.** `PhaseConfig` belongs in
`flowtree/agents/src/main/java/io/flowtree/jobs/agent/`, next to `Phase`
and `AgentCapabilities`. Reasons:

- It is consumed by `AgentRunRequest.builder()` callers and the resolver,
  both of which already live in or depend on `ar-flowtree-agents`.
- Placing it any higher in the dependency chain (e.g. on `ar-flowtreeapi`)
  is unnecessary because the API layer does not need the record type
  itself — it works in terms of JSON objects.

### `PhaseConfigBundle` (per-container holder)

Each container (Workspace, Workstream, Job submission) holds a small,
serialisable bundle:

```java
public record PhaseConfigBundle(PhaseConfig defaultPhaseConfig,
                                Map<Phase, PhaseConfig> phaseConfigs) {

    public static final PhaseConfigBundle EMPTY =
        new PhaseConfigBundle(PhaseConfig.EMPTY, Map.of());

    public PhaseConfigBundle {
        if (defaultPhaseConfig == null) defaultPhaseConfig = PhaseConfig.EMPTY;
        phaseConfigs = phaseConfigs == null
            ? Map.of()
            : Map.copyOf(new EnumMap<>(phaseConfigs));
    }

    /** Resolved config for the given phase at THIS level only (no inheritance). */
    public PhaseConfig forPhase(Phase phase) {
        if (phase == null) return defaultPhaseConfig;
        PhaseConfig override = phaseConfigs.get(phase);
        return override == null
            ? defaultPhaseConfig
            : override.overlayOn(defaultPhaseConfig);
    }
}
```

Each bundle field is independently nullable. Within a single level, a
`PhaseConfig` for a phase overlays its container's `defaultPhaseConfig`
field-by-field: a phase that sets only `model` inherits `runner` and
`effort` from the level default.

### Containers gain a bundle

- `WorkstreamConfig.WorkspaceEntry` gains
  `private PhaseConfigBundle phaseConfigs = PhaseConfigBundle.EMPTY;`
  alongside the existing `defaultRunner`/`runners` (deprecated, see
  Migration below).
- `WorkstreamConfig.WorkstreamEntry` likewise.
- In-memory `Workstream` likewise.
- `CodingAgentJob` and `CodingAgentJobFactory` likewise — replacing the
  current `defaultRunner` + `runnerByPhase` + `model` + `effort` singletons
  with one bundle (with legacy aliases retained for source compatibility,
  same shape used during the runner-only migration in Phase 2 of the
  pluggable-agents project).

---

## The Precedence Ladder (uniform across all three fields)

For a given (job, phase) lookup, each field of the final `PhaseConfig` is
resolved independently:

1. Job per-phase override
2. Job `defaultPhaseConfig`
3. Workstream per-phase override
4. Workstream `defaultPhaseConfig`
5. Workspace per-phase override
6. Workspace `defaultPhaseConfig`
7. Controller default (currently runner=`claude`, model=null, effort=null)

A field's resolution stops at the first non-null value. Because each
container's `forPhase(phase)` already overlays its phase override on its
own default, the resolver is just `overlayOn` walked from the job level
upward:

```java
PhaseConfig resolved = job.bundle.forPhase(phase)
    .overlayOn(workstream.bundle.forPhase(phase))
    .overlayOn(workspace.bundle.forPhase(phase))
    .overlayOn(CONTROLLER_DEFAULT);
```

This single line is the entire resolution rule. The current
`SubmissionRunnerResolver` precedence-skipping rule (workspace per-phase
entries are skipped when the workstream sets `defaultRunner`) **is
preserved by accident, not by design** — once each field falls through
independently, "workstream default shadows workspace per-phase" is no
longer correct: a workstream that sets only `defaultPhaseConfig.runner`
should still let `model`/`effort` fall through to workspace per-phase
entries. **This is a behavioural change** and must be called out in the
release notes.

(Alternative: preserve the shadowing rule per-field. Rejected. The
shadowing rule was a workaround for the runner-only model; with three
independent fields it becomes incomprehensible. The simpler "each field
falls through independently" is the correct long-term shape, and the
fallout is small because the only existing per-field-shadowing data is
the runner map, which existing operators have set explicitly at one level
or another.)

---

## Runner Contract: capability mismatches

Three cases:

1. **Effort set, runner doesn't support it** (e.g.
   `phaseConfigs[REVIEW].effort = "high"` resolving to opencode).
   **Silently passed through to the request and ignored by the runner.**
   No warning at submission. `AgentCapabilities.supportsEffortLevel` is
   metadata, not a gate. Documented in `AGENT_RUNNERS.md`.

2. **Model set, runner has non-empty `supportedModels` that excludes it.**
   `CodingAgentJob.setModel` currently validates against the Claude set
   only. With per-phase models the validation must move to per-phase, and
   it must be per-runner because different phases may use different
   runners. Recommended: validate at resolution time (when the request is
   built) against the runner that the resolver picked for that phase.
   When `supportedModels` is empty, accept any string (current behaviour
   for opencode against arbitrary OpenAI-compatible endpoints).

3. **Runner name unknown.** Same as today — fail fast at submission
   (`AgentRunnerRegistry.validateName`).

**Validation timing.** Today `setModel`/`setEffort` validate at field-set
time. For per-phase configs, eager validation against a single runner is
impossible (the model field belongs to a phase that may route to any
runner). Two options:

- **(a) Validate at resolution.** When the orchestrator builds the
  `AgentRunRequest` for a phase, it has the resolved runner and the
  resolved model — at that point it can check against the runner's
  `supportedModels`. Errors here become job-execution failures, not
  submission failures.
- **(b) Validate at submission, per-phase, against the resolved runner
  for that phase.** Requires the resolver to be available at submission
  time (it is — it already resolves runners there). Errors at submission
  are friendlier than errors mid-job.

Recommend **(b)**: validate per-phase at submission, surfacing the same
400 the runner-resolution errors already do. Adds a single nested loop
in the submission handler.

(See the "Open questions" section for the deferred Phase 3 capability
mismatch warning, distinct from this validation.)

---

## Migration: legacy YAML and legacy MCP parameters

The whole point of the design is that nothing existing breaks. The
legacy fields remain as input conveniences that populate the new
`PhaseConfigBundle` on the receiving side; on the output side, the new
fields are serialised, and the legacy fields are not. After the
migration:

- A YAML with only `defaultRunner: claude` continues to load and behaves
  identically (its `defaultRunner` populates
  `defaultPhaseConfig.runner = "claude"`; nothing else differs).
- A YAML with `model: opus`, `effort: high` (workstream-level) continues
  to load — populating `defaultPhaseConfig.model` and
  `defaultPhaseConfig.effort`. The workstream entry's legacy `model`/
  `effort` fields become deprecated getters that delegate to the bundle.

### YAML schema additions

```yaml
workspaces:
  - id: almostrealism
    defaultPhaseConfig:
      runner: claude
      model: claude-opus-4-7
      effort: high
    phaseConfigs:
      review:
        runner: opencode
        model: qwen3-coder-30b
      commit-message:
        runner: opencode
        model: qwen3-coder-30b
    # Legacy fields below still accepted on input. Not emitted on output.
    defaultRunner: claude            # → defaultPhaseConfig.runner
    runners:                          # → phaseConfigs[*].runner
      review: opencode
```

Workstream entries gain the same two fields with the same semantics. On
input, the legacy `defaultRunner`/`runners` populate the new bundle. The
new fields take precedence when both are supplied — exposed via Jackson
`@JsonAlias` on the bundle setter, plus a custom setter for
`defaultRunner`/`runners` that writes into the bundle's
`defaultPhaseConfig`/`phaseConfigs`. When both forms set the same field
(e.g. both `defaultRunner: claude` and `defaultPhaseConfig.runner:
opencode`), the new form wins; emit a warning during config load via the
existing `WorkstreamConfig` warning sink so operators notice.

`WorkstreamConfig.validateWorkspaceRunners` and `validateWorkstreamEntry`
(around line 1100+) need to gain analogous validation for the new
fields. Keep their existing warnings; add field-specific warnings for
invalid models/efforts inside per-phase configs.

### Wire format for `CodingAgentJobFactory`

`CodingAgentJobFactory` currently serialises its state to a flat
key=value record (see `set("model", …)`, `set("effort", …)`,
`set("defaultRunner", …)`, `set("runners", …)` calls and the decoding
`switch` around line 1201–1239). For the unified shape, add:

- `defaultPhaseConfig` — a compact comma-separated triple, e.g.
  `runner=claude,model=claude-opus-4-7,effort=high`. Use a key/value
  format similar to `Phase.encodeRunnerMap` for symmetry.
- `phaseConfigs` — repeated semicolon-separated entries of the same form,
  one per phase, prefixed with the phase wire name:
  `review:runner=opencode,model=qwen3-coder-30b;commit-message:runner=opencode`.

Decode pass keeps the old `model`, `effort`, `defaultRunner`, `runners`
keys as legacy inputs (they target `defaultPhaseConfig` and the
runner-only per-phase map respectively).

Encode pass writes the new keys only when the bundle has any non-null
field at that position; suppress the legacy keys entirely so wire output
stays compact. Per-byte-identical legacy output is **not** preserved
(unlike the runner-only migration which was careful about this); the
key list is changing. Document this in the release notes.

### MCP parameters

- `workstream_submit_task`, `workstream_register`, `workstream_update_config`
  gain two new parameters:
  - `default_phase_config: str = ""` — JSON object with keys `runner`,
    `model`, `effort`, any of which may be omitted.
  - `phase_configs: str = ""` — JSON object whose keys are `Phase`
    wire names and whose values are the same `{runner, model, effort}`
    triples.
- `workspace_update_config` gains the same two parameters.
- Legacy parameters `model`, `effort`, `runners`, `default_runner` remain
  accepted. On the receiving side they are merged into the new shape
  before transmission to the controller:
  - `model="opus"` → contributes to `default_phase_config.model`.
  - `effort="high"` → contributes to `default_phase_config.effort`.
  - `default_runner="claude"` → contributes to `default_phase_config.runner`.
  - `runners` JSON entries → contribute to `phase_configs[<phase>].runner`,
    with the `"default"` key contributing to `default_phase_config.runner`.
- **Precedence when both legacy and new are supplied: new wins**
  (consistent with YAML). Tool returns `{ok: false, error: "..."}` only
  on shape errors; conflicts emit no error but the new field is what
  ships.

Shared validator: extend `_parse_runners_json` into a new
`_parse_phase_configs_json` that parses one phase-config JSON object,
validates each phase key against the enum, validates each runner against
`agent_options`, and returns `(parsed_dict, error_dict_or_none)` in the
existing pattern. Build a second helper for `default_phase_config` that
parses one un-keyed `{runner, model, effort}` object.

### Controller endpoint changes

`FlowTreeApiEndpoint` (`flowtree/runtime/src/main/java/io/flowtree/slack/`)
needs:

- Submission handler (`handleSubmit`, around line 1100+): replace the
  `extractJsonObjectFields(body, "runners")` →
  `SubmissionRunnerResolver.resolve(…)` block with a parallel block that
  also reads `phaseConfigs` and `defaultPhaseConfig` from the body and
  hands the merged result to `PhaseConfigResolver.resolve(…)`. The
  resolver's `applyTo(factory)` now populates the factory's full
  `PhaseConfigBundle`, not just the runner map.
- Workstream-config endpoint (`handleSetWorkstreamConfig`, lines ~756 and
  ~855): wire the new JSON objects into the persisted `Workstream`
  bundle. Keep `SubmissionRunnerResolver.applyToWorkstream` as a
  delegating wrapper that funnels into the bundle for backwards
  source-compatibility with any other caller.
- Workspace-config endpoint (in `WorkspaceConfigHandler.java`, line ~182):
  same pattern for the workspace bundle.

---

## Resolution Logic: `PhaseConfigResolver`

Rename and generalise `SubmissionRunnerResolver` →
`PhaseConfigResolver`. Keep the file name change explicit in the commit
so blame stays clean. The interface becomes:

```java
public final class PhaseConfigResolver {

    private final String error;
    private final PhaseConfigBundle resolved;

    public static PhaseConfigResolver resolve(
            PhaseConfigBundle requestBundle,
            PhaseConfigBundle workstreamBundle,
            PhaseConfigBundle workspaceBundle) { … }

    /** Returns the resolved config for a specific phase. */
    public PhaseConfig forPhase(Phase phase) { … }

    /** Applies the resolved bundle to a factory. */
    public void applyTo(CodingAgentJobFactory factory) { … }

    /** Returns a 400-able validation error, or null on success. */
    public String error() { … }
}
```

Validation done in `resolve`:

- Every phase key is a known wire name (or the bundle came from Java
  code, in which case the `Map<Phase, …>` enforces this).
- Every runner name is known via `AgentRunnerRegistry.available()`.
- For every phase, the resolved model (after fall-through) is either
  null or valid for the resolved runner for that phase. Reuse the
  per-runner `supportedModels` set; empty set means "unconstrained".
- For every phase, the resolved effort is either null or in
  `ClaudeCodeRunner.VALID_EFFORT_LEVELS`. (We do not yet support
  runner-specific effort vocabularies; that becomes a Phase 4 follow-up
  if/when needed.)

`applyTo(factory)` sets `factory.setPhaseConfigBundle(bundle)` and
suppresses the bundle's defaults that match the controller defaults
(runner=claude, model=null, effort=null) so the encoded wire format
stays compact.

---

## Orchestrator Changes

`CodingAgentJob.buildAgentRunRequest(int attempt)` currently uses
`this.model` and `this.effort`. It must consult the bundle:

```java
Phase phase = resolveCurrentPhase();
PhaseConfig effective = bundle.forPhase(phase)
    .overlayOn(CONTROLLER_DEFAULT);

return AgentRunRequest.builder()
    .…
    .model(effective.model())
    .effort(effective.effort())
    .…
    .build();
```

`resolveRunner(Phase)` becomes a thin lookup over the same bundle.

`CodingAgentJob.setModel/setEffort` are retained as legacy mutators that
write into `bundle.defaultPhaseConfig`. The single-field validation
moves from these mutators into `PhaseConfigResolver.resolve(…)` so
mixed-runner jobs aren't rejected by a Claude-specific check.

---

## Implementation Phases

The project mirrors the pluggable-agents sequencing: introduce data
shape first, wire it in second, refine afterwards.

### Phase 1 — Introduce the data shape (no behaviour change)

**Goal:** every container, every endpoint, every MCP tool can accept and
emit the new shape. The orchestrator still resolves `model`/`effort` per
phase but every phase resolves to the same value because none of the
existing config sets a per-phase model/effort.

Files added:

- `flowtree/agents/src/main/java/io/flowtree/jobs/agent/PhaseConfig.java`
- `flowtree/agents/src/main/java/io/flowtree/jobs/agent/PhaseConfigBundle.java`
- `flowtree/runtime/src/main/java/io/flowtree/slack/PhaseConfigResolver.java`
  (replacing `SubmissionRunnerResolver`)

Files modified:

- `flowtree/runtime/src/main/java/io/flowtree/slack/WorkstreamConfig.java`
  — add `phaseConfigs` and `defaultPhaseConfig` to both `WorkspaceEntry`
  and `WorkstreamEntry`. Add `@JsonAlias` fall-throughs so legacy fields
  populate the bundle.
- `flowtree/runtime/src/main/java/io/flowtree/slack/Workstream.java`
  — add bundle field; rewire `getModel/getEffort/getDefaultRunner/getRunners`
  to read from the bundle.
- `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java`
  and `CodingAgentJobFactory.java` — add bundle field; rewire singleton
  setters to update the bundle; add `getPhaseConfigBundle` /
  `setPhaseConfigBundle`. Encoded wire format gains two new keys (see
  Migration above).
- `flowtree/runtime/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`
  — submission handler accepts the new JSON objects; resolver is
  invoked with the new shape; existing `runners`/`default_runner`/
  `model`/`effort` keys merged into the bundle first.
- `flowtree/runtime/src/main/java/io/flowtree/slack/WorkspaceConfigHandler.java`
  — same accept-new-fields treatment for the workspace endpoint.
- `tools/mcp/manager/server.py`
  — add `default_phase_config` and `phase_configs` parameters to
  `workstream_submit_task`, `workstream_register`,
  `workstream_update_config`, `workspace_update_config`. Add parsers
  `_parse_phase_configs_json` and `_parse_default_phase_config_json`.
  Merge with legacy params before sending.

Tests added:

- `PhaseConfigTest` — overlay semantics, isEmpty, withX.
- `PhaseConfigBundleTest` — forPhase fall-through within a level.
- `PhaseConfigResolverTest` — full ladder, each field independent. All
  existing `SubmissionRunnerResolverTest` cases ported, plus parallel
  cases for `model` and `effort`.
- `WorkstreamConfigTest` — legacy YAML still loads and round-trips.
- `CodingAgentJobModelConfigTest` (file already exists) — extend to
  verify bundle round-trip.
- Python `test_server.py` — new params accepted, legacy params still
  accepted, conflict resolution (new wins), shape errors return 400.

**Acceptance:** all existing tests pass unchanged. A workstream with
`defaultPhaseConfig.model = "claude-opus-4-7"` and no per-phase override
behaves identically to one with `model: "claude-opus-4-7"` today.

### Phase 2 — Wire per-phase config to the runner

**Goal:** setting `phaseConfigs[REVIEW].model = "qwen3-coder-30b"`
actually changes only the REVIEW phase.

Files modified:

- `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java`
  — `buildAgentRunRequest` calls
  `bundle.forPhase(currentPhase).overlayOn(CONTROLLER_DEFAULT)` and uses
  the resolved model and effort. Adds a small helper
  `resolveEffectivePhaseConfig(Phase)`.

Tests added:

- `CodingAgentJobMixedPhaseConfigTest` — boot a job with three different
  per-phase configs; verify each `AgentRunRequest` carries the right
  model/effort/runner combination. Use a mock `AgentRunner` whose `run`
  records its incoming request, run a sequence of phases, assert the
  expected per-phase values landed.
- Integration test in `flowtree/runtime` that exercises
  `FlowTreeApiEndpoint.handleSubmit` with a real `CodingAgentJobFactory`
  and inspects the resolved bundle.

**Acceptance:** the mixed-runner example from the task summary works
end-to-end:

```json
{
  "default_phase_config": {"runner":"claude","model":"claude-opus-4-7","effort":"high"},
  "phase_configs": {
    "commit-message":           {"runner":"opencode","model":"qwen3-coder-30b"},
    "organizational-placement": {"runner":"opencode","model":"qwen3-coder-30b"}
  }
}
```

### Phase 3 — Capability-mismatch warnings (optional follow-up)

At workspace/workstream config load time, walk each resolved
`PhaseConfig`. When a phase resolves to a runner with
`supportsEffortLevel = false` and `effort != null`, emit a load-time
warning identifying the layer that set the effort and the phase it
resolves to. Same for `model` against the runner's `supportedModels`.

This is opt-in: enable via a workspace-level flag
`strictCapabilityChecks: true`, default off so the existing behaviour
(silent ignore) remains the norm.

Files modified:

- `WorkstreamConfig.validateWorkspaceRunners` /
  `validateWorkstreamEntry` — add warnings.
- `WorkstreamConfig` — load the flag.

This phase has no MCP changes and no behaviour change unless the flag is
set. Ship if convenient; skip if Phase 1/2 land before a Phase 3
need arises.

---

## Open Questions (decisions to record before Phase 1 starts)

1. **Is `defaultPhaseConfig` necessary, or is the per-phase map enough?**
   **Recommendation: keep `defaultPhaseConfig`.** Without it, expressing
   "use opus high for every phase" requires enumerating all 8 phases,
   which is the most common case. The default-plus-overrides shape is
   strictly more expressive and matches the existing
   `defaultRunner`/`runners` split.

2. **YAML: sibling-with-deprecation or replacement?**
   **Recommendation: sibling with deprecation warning.** The legacy
   fields continue to accept input but are no longer emitted on output.
   Operators are not asked to edit YAML; the auto-migration is cheap.

3. **Should the resolver expose introspection via an MCP tool?**
   **Recommendation: yes, Phase 2 follow-up.** Add
   `workstream_resolve_phase_config(workstream_id, phase, …)` that
   returns the resolved bundle for a phase, with each field annotated
   with the precedence level it came from. Cheap diagnostic that pays
   for itself the first time an operator asks "why is REVIEW using
   claude when I set opencode in the workstream?". Out of scope for the
   first cut.

4. **Should a phase be able to opt OUT of inheriting from
   `defaultPhaseConfig`?** **Recommendation: no for v1.** Setting a
   field to `null` is the inheritance signal; there is no way to say
   "ignore the parent default and use the runner's built-in default"
   short of an explicit value. The use case is theoretical; add a
   sentinel value (e.g. `""`) if it ever becomes concrete.

5. **What does the controller default look like after the unification?**
   **Decision: `runner=claude`, `model=null`, `effort=null`.** Same as
   today — the runner picks its own default model/effort when nothing
   is set. `agent_options` returns the controller default so MCP
   clients can show it in pickers.

6. **Should validation of unknown model/effort be hard-fail at
   submission, or warn at load and silently drop at runtime?**
   **Recommendation: hard-fail at submission via
   `PhaseConfigResolver.resolve`, against the resolved runner for the
   phase.** Failing fast at submit time is much friendlier than failing
   mid-job. Load-time YAML warnings remain in addition.

---

## Testing Strategy

This is a significant refactor and tests are critical. Organise tests
along three axes: data-model unit tests, resolution behaviour, and
end-to-end integration.

### Data-model unit tests

- `PhaseConfigTest` — overlay semantics: every combination of
  null/non-null for each field; chain three levels; verify `isEmpty`
  and the `withX` methods preserve siblings.
- `PhaseConfigBundleTest` — `forPhase` returns the level default when no
  per-phase override exists; returns the per-phase override overlaid on
  the level default when both exist; handles a `null` phase argument.

### Resolver tests (port from `SubmissionRunnerResolverTest`)

Test matrix to cover, for each of `runner`, `model`, `effort`
independently and combined:

- Job override only.
- Job default only.
- Workstream override only.
- Workstream default only.
- Workspace override only.
- Workspace default only.
- Controller default.
- Job override beats workstream override.
- Workstream override beats workspace override.
- Workspace per-phase no longer shadowed by workstream default
  (behavioural change — assert explicitly).
- Each field falls through independently when the others are set at
  the same level. E.g.
  `workstream.defaultPhaseConfig = {runner:"claude"}`,
  `workspace.phaseConfigs[REVIEW] = {model:"opus"}` → REVIEW resolves
  to `runner=claude, model=opus, effort=null`.

### Backwards-compat tests

- Legacy YAML with `defaultRunner` + `runners` loads with no warnings
  and produces a bundle whose per-phase runners match.
- Legacy YAML with workstream-level `model: opus` + `effort: high`
  loads with no warnings and produces a bundle whose
  `defaultPhaseConfig` carries those values.
- Combined legacy + new YAML: new wins, warning emitted to the
  configured warning sink, field-by-field.
- MCP `workstream_submit_task` with legacy `model`/`effort`/`runners`
  /`default_runner` only — payload sent to controller carries new
  shape; legacy keys are still echoed for older controllers.
- MCP `workstream_submit_task` with legacy + new — new wins; no
  controller round-trip needed to detect conflict.

### Runner-behaviour tests

- Opencode still ignores effort regardless of resolution path. Mock
  runner asserts the request reached it carrying the configured effort
  string; runner-internal handling continues to drop it.
- Mixed-runner job (claude primary, opencode commit-message) with
  different model per runner: each `AgentRunRequest` reaching the
  runner has the expected model.

### Migration tests

- A `Workstream` deserialised from legacy YAML, then mutated via the
  new endpoint, then re-serialised: the resulting YAML uses the new
  shape only.
- A `CodingAgentJobFactory` round-tripped through its key=value wire
  format with the new keys preserves bundle structure.

### Integration tests

- `FlowTreeApiEndpoint` submission handler exercise: POST a body with
  `default_phase_config` and `phase_configs`; assert the created
  factory has the expected bundle.
- End-to-end with a `TestAgentRunner` that records every `run(request)`
  call; submit a job with three per-phase configs and force each phase
  to run; assert the recorded requests' model/effort/runner identifier.

### Negative tests

- Unknown phase wire name in `phase_configs` → 400 with the offender
  named.
- Unknown runner name anywhere → 400.
- Model not in resolved runner's `supportedModels` (and the runner has
  a non-empty set) → 400.
- Effort not in `VALID_EFFORT_LEVELS` → 400.

---

## Risks

- **Behavioural change in workspace-vs-workstream shadowing.** Section
  "Precedence Ladder" above. Operators currently relying on the
  shadowing rule (workstream `defaultRunner` shadows workspace
  per-phase) will see new resolutions when they had relied on the old
  rule. Mitigation: the shadowing rule applies only to the runner
  field, and operators relying on it almost certainly set everything
  at one level; we have no production examples relying on the cross-
  field shadow.

- **Wire-format change for `CodingAgentJobFactory`.** A controller
  restart mid-job that re-reads an in-flight factory record may see
  unfamiliar keys. Solved by Phase 1 implementing decode for both
  forms; encode emits new only.

- **Model validation per-runner.** The current global `VALID_MODELS`
  alias on `CodingAgentJob` is widely consumed. Either we keep it as
  a Claude-runner-specific alias (it already is — see line ~79) or we
  inline its uses. Recommend keeping the alias and removing the
  validation from `setModel`/`setEffort` so per-phase configurations
  with non-Claude runners work.

- **MCP backwards-compat surface area.** Four tools all gain two new
  parameters and must keep four existing parameters working. The
  parser and merge logic is shared, but every tool's test in
  `test_server.py` needs both-paths coverage. Budget for it.

---

## Out of Scope

- Adding new runners or new runner capabilities.
- Per-runner effort vocabularies (currently all runners share
  `VALID_EFFORT_LEVELS` from `ClaudeCodeRunner`; no caller distinguishes
  yet).
- Live model discovery from runner endpoints. `agent_options` still
  exposes a static list per runner.
- Slack-bot UX changes.
- New Maven modules — this refactor stays within the existing module
  graph. `PhaseConfig` and `PhaseConfigBundle` land in
  `ar-flowtree-agents`; the resolver stays in `ar-flowtree-runtime`.

---

## Sequencing Summary

1. Phase 1: data shape, resolver, MCP plumbing, legacy migration.
   Tests: data-model, resolver, backwards-compat, MCP.
2. Phase 2: orchestrator wires bundle to per-invocation request.
   Tests: mixed-config end-to-end.
3. Phase 3 (optional): capability-mismatch load-time warnings behind a
   workspace flag.

Each phase is independently mergeable. Phase 1 ships with behaviour
unchanged; Phase 2 enables the new behaviour but only for configs that
opt in (every existing config produces the same resolution as before);
Phase 3 is operator-visible diagnostics only.
