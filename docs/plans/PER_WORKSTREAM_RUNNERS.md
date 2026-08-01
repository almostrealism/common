# Per-Workstream Runner Configuration (and Agent Option Discovery)

## Goal

Finish wiring the **pluggable agent runner** feature so that runner selection is
configured **at the workstream level**, not per-job, and so MCP clients can
**discover** the valid values (runner names, phase names, model names) instead
of guessing them.

The Java side is already fully wired. What is missing is purely the MCP
plumbing on top of it, plus a discovery surface.

---

## Current State (do not re-do)

These pieces already exist on the Java side and are working:

- `Workstream.defaultRunner` (String) and `Workstream.runners` (Map<phase, runner>)
- `WorkstreamConfig` deserialises both from `workstreams.yaml`
- `SubmissionRunnerResolver` applies the precedence ladder:
  **job override → workstream per-phase → workstream default → built-in default ("claude")**
- `Phase` enum has 8 values with `wireName()` mapping
- `AgentRunnerRegistry.available()` returns the set of registered runner names
  (currently `{"claude", "opencode"}`)
- `flowtree/docs/architecture/PHASES.md` already documents the routing precedence
- `workstream_submit_task` already accepts `runners` and `default_runner` per-job

If you edit `workstreams.yaml` by hand right now and restart the controller,
per-workstream per-phase routing already works end-to-end. The remaining work
is exposing this surface through MCP.

---

## Scope

Three concrete deliverables:

1. **MCP tools that set runner config on a workstream** (so the YAML doesn't
   have to be hand-edited).
2. **A controller endpoint** that enumerates available runners, phases, and
   models — including per-runner capability flags — so the MCP surface has
   one source of truth.
3. **An MCP tool that proxies that endpoint** so coding agents (and humans)
   can list valid values before configuring a workstream.

Non-goals (explicitly out of scope here):
- Adding new runners. The set stays `{"claude", "opencode"}`.
- Changing the Java routing logic or precedence ladder.
- Reworking the phases enum or its wire names.
- UI/Slack changes.

---

## 1. Controller: `GET /api/agents`

Add a new HTTP endpoint in `FlowTreeApiEndpoint` (or its sibling JSON
endpoints under `io.flowtree.api`) that returns the metadata MCP clients
need to populate runner/phase/model pickers.

### Response shape

```json
{
  "ok": true,
  "runners": [
    {
      "name": "claude",
      "capabilities": {
        "reportsCost": true,
        "reportsTurns": true,
        "supportsEffortLevel": true,
        "supportsMaxBudget": true,
        "supportsMcpHttpTransport": true,
        "supportsMcpStdioTransport": true,
        "supportsPermissionDenialReporting": true,
        "supportedModels": ["claude-opus-4-7", "claude-sonnet-4-6", "..."]
      }
    },
    {
      "name": "opencode",
      "capabilities": {
        "reportsCost": false,
        "reportsTurns": true,
        "supportsEffortLevel": false,
        "supportsMaxBudget": false,
        "supportsMcpHttpTransport": true,
        "supportsMcpStdioTransport": true,
        "supportsPermissionDenialReporting": false,
        "supportedModels": []
      }
    }
  ],
  "phases": [
    {"name": "primary",                   "description": "Main task work"},
    {"name": "deduplication",             "description": "Pre-commit dedup audit"},
    {"name": "organizational-placement",  "description": "..."},
    {"name": "enforce-changes",           "description": "..."},
    {"name": "maven-dependency-protection","description": "..."},
    {"name": "post-completion",           "description": "..."},
    {"name": "commit-message",            "description": "..."},
    {"name": "git-tampering-restart",     "description": "..."}
  ],
  "models": ["claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5-20251001", "..."],
  "default_runner": "claude"
}
```

### Implementation notes

- **Source of truth for runners**: enumerate `AgentRunnerRegistry.available()` and
  call `AgentRunnerRegistry.get(name).capabilities()` on each.
- **Source of truth for phases**: iterate `Phase.values()` and return
  `{wireName(), descriptionOrJavadocSummary()}`. If the enum doesn't already
  carry descriptions, add a field with a 1-line description for each value.
- **Source of truth for models**: `CodingAgentJob.VALID_MODELS` (or whatever
  field holds the validated set). When a runner advertises empty
  `supportedModels`, that means it accepts any string (e.g. opencode against
  a local llama.cpp).
- **No auth scope required**: this is read-only metadata, same as
  `controller_health`. Return JSON with `ok: true`.
- **Route**: under the existing API mount, e.g. `GET /api/agents`. Match the
  naming pattern of `GET /api/workstreams`.

---

## 2. MCP tool: `agent_options`

In `tools/mcp/manager/server.py`, add a new tool that proxies
`/api/agents` and returns the same structure.

```python
@mcp.tool()
def agent_options() -> dict:
    """List the runner names, phase names, and model names accepted by the
    controller, plus the capability flags of each runner.

    Use this when configuring a workstream's runner routing or submitting a
    job. The values returned here are the valid inputs for the `runners`,
    `default_runner`, and `model` arguments of `workstream_register`,
    `workstream_update_config`, and `workstream_submit_task`.

    Returns:
        dict with `ok=True`, `runners`, `phases`, `models`, `default_runner`.
    """
    _audit("agent_options")
    return _controller_get("/api/agents")
```

No `_require_scope("write")` — it's read-only.

---

## 3. MCP tools: extend `workstream_register` and `workstream_update_config`

Both tools in `tools/mcp/manager/server.py` currently accept `model`, `effort`,
and friends but NOT `runners` / `default_runner`. Mirror the existing
job-level handling from `workstream_submit_task`:

- Add `runners: str = ""` (JSON object keyed by `Phase.wireName()`).
- Add `default_runner: str = ""`.
- Re-use `_parse_runners_json` for shape validation, returning the same
  `{ok: False, error: "..."}` failure mode on malformed input.
- Forward to the controller via the `runners` and `default_runner` fields in
  the existing register / update payloads. The controller already knows how
  to persist them into `Workstream`.

Document the precedence ladder explicitly in the tool docstring so MCP
clients understand that **per-job runners override per-workstream runners**.

---

## 4. Discovery registration (required by `tools/mcp/CLAUDE.md`)

For each new MCP tool, follow the registration rules in `tools/mcp/CLAUDE.md`:

1. Add `agent_options` to the `expected` set in
   `flowtree/runtime/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy`.
2. Add `agent_options` to the `expected` set in
   `tools/mcp/manager/test_server.py::test_expected_tool_count`.
3. Add `agent_options` to `AR_MANAGER_TOOL_NAMES` in
   `flowtree/runtime/src/main/java/io/flowtree/jobs/McpConfigBuilder.java`
   (read-only metadata → granted to agents).
4. For the parameter assertions test in `McpToolDiscoveryTest`, add the new
   `runners` and `default_runner` parameters on `workstream_register` and
   `workstream_update_config` so the next session can't silently drop them.

---

## 5. Tests

- **Java**: `FlowTreeApiEndpointTest` or sibling — add a test that hits
  `/api/agents` and asserts the response shape, the runner list contents
  (`claude` + `opencode`), the phase list size and wire names, and the
  presence of the model list.
- **Java**: `McpToolDiscoveryTest` updates (above) catch the registration drift.
- **Python**: in `tools/mcp/manager/test_server.py`, mock the controller HTTP
  call and assert `agent_options` returns the expected dict. Also assert
  that `workstream_register` and `workstream_update_config` pass `runners`
  and `default_runner` through to the controller payload, and that malformed
  `runners` JSON yields `{ok: False, error: ...}` without touching the
  controller.

---

## 6. Documentation

Update `flowtree/docs/architecture/AGENT_RUNNERS.md`:
- Add a "Configuring runners" section that describes the three levels
  (built-in default, workstream config, per-job override) and points at
  `agent_options` for discovery.
- Note that the YAML form remains supported but is the slow path; the MCP
  tools are the recommended interface.

Do NOT re-create `docs/plans/PLUGGABLE_AGENTS.md` — that file was deliberately
removed when its content migrated to `flowtree/docs/`.

---

## Out of Scope

- Adding new runners.
- Per-runner model validation in the resolver (the controller already
  validates the `model` field; per-runner `supportedModels` is metadata
  only).
- Live discovery of "what models is this opencode server actually
  serving" — opencode runs against arbitrary OpenAI-compatible endpoints,
  so we cannot enumerate them at controller startup.
- Slack-bot UX changes.

---

## Sequencing

1. Controller endpoint (`GET /api/agents`) + Java test.
2. MCP `agent_options` tool + Python test.
3. Extend `workstream_register` and `workstream_update_config` + Python tests.
4. Discovery-registration updates (`McpToolDiscoveryTest`, `McpConfigBuilder`,
   `test_server.py`).
5. Docs (`AGENT_RUNNERS.md`).

Each step is independently mergeable.
