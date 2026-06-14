# Shared Schema Contract — Go/No-Go Feasibility Study

**Status:** NO-GO — See verdict at the end.

**Branch:** `feature/shared-schema-contract`  
**Date:** 2026-06-14

---

## 1. The Problem Under Study

The FlowTree controller API and the ar-manager MCP server
(`tools/mcp/manager/server.py`) maintain **two hand-written copies of the
same parameter schema**:

- The controller validates config fields in Java.
- The MCP tools separately re-declare those fields as Python parameters with
  natural-language docstrings.

They drift. At least five "controller field added, MCP parameter forgotten"
gaps are documented: `phase_configs`, `workspace_update_config` (workspace
endpoint), `retrospective_enabled`, `completion_listeners`, `dispatch_capable`.

**The idea under study:** have both the API and the MCP tool surface derive
from one shared OpenAPI-style specification, so divergence is structurally
impossible.

**Acceptance criterion:** adding or changing a config field must require
editing exactly **one artifact**, after which BOTH the controller validation
AND the MCP tool parameter (including its natural-language description) update
automatically, with **no separate human step**.

---

## 2. The Two-Copy Map (what exists today)

### 2.1 Copy 1 — Java controller (authoritative)

The controller is a NanoHTTPD Java HTTP server. Config fields are read and
applied in two handler classes:

**`WorkstreamRegistrationHandler.handleUpdate()`**
(`flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamRegistrationHandler.java`):
```java
String defaultBranch = JsonFieldExtractor.extractString(body, "defaultBranch");
String baseBranch    = JsonFieldExtractor.extractString(body, "baseBranch");
String repoUrl       = JsonFieldExtractor.extractString(body, "repoUrl");
String planningDocument = JsonFieldExtractor.extractString(body, "planningDocument");
String channelName   = JsonFieldExtractor.extractString(body, "channelName");
Map<String, String> requiredLabels   = JsonFieldExtractor.extractStringObject(body, "requiredLabels");
List<String> dependentRepos          = JsonFieldExtractor.extractStringArray(body, "dependentRepos");
List<String> completionListeners     = extractCompletionListeners(body);
// plus phaseConfigs / defaultPhaseConfig via PhaseConfigResolver
```

**`WorkspaceConfigHandler.handle()`**
(`flowtree/runtime/src/main/java/io/flowtree/api/WorkspaceConfigHandler.java`):
```java
String name           = JsonFieldExtractor.extractString(body, "name");
String defaultChannel = JsonFieldExtractor.extractString(body, "defaultChannel");
String newId          = JsonFieldExtractor.extractString(body, "newId");
boolean slackTeamIdPresent = body.contains("\"slackTeamId\"");
// plus phaseConfigs / defaultPhaseConfig via PhaseConfigResolver
```

There is **no schema file** for either endpoint. The field list is implicit in
the `JsonFieldExtractor` call sequence. Adding a new field means writing a new
`extractString()` call.

### 2.2 Copy 2 — Python MCP server (the second copy)

`tools/mcp/manager/server.py` (6,510 lines) declares matching tools using
`from mcp.server.fastmcp import FastMCP` and `@mcp.tool()` decorators:

```python
@mcp.tool()
def workstream_update_config(
    workstream_id: str,
    default_branch: str = "",
    base_branch: str = "",
    repo_url: str = "",
    planning_document: str = "",
    channel_name: str = "",
    required_labels: str = "",
    dependent_repos: str = "",
    completion_listeners: str = "",
    default_phase_config: str = "",
    phase_configs: str = "",
    ...
) -> dict:
    """Update configuration for an existing workstream.
    ...300+ words of context-specific docstring...
    """
```

Each parameter has a multi-sentence docstring explaining its semantics to MCP
clients. The function body converts snake_case Python params to camelCase JSON
and POSTs to the controller. A new field requires: (a) a new parameter in the
Python signature, (b) a docstring update, and (c) a payload-forward line in
the body.

### 2.3 The existing backstop

`McpToolWorkstreamConfigSurfaceTest.java`
(`flowtree/runtime/src/test/java/io/flowtree/jobs/`) is a static text-parsing
test that reads `server.py` and asserts that every entry in a curated set
(`REQUIRED_ON_BOTH_REGISTER_AND_UPDATE`) appears in the Python function
signature. It fails CI when a controller field is added without the Python
parameter. It does **not** verify docstring content.

---

## 3. FastMCP OpenAPI Integration — Concrete Finding

### 3.1 Which FastMCP is this?

The codebase uses:
```python
from mcp.server.fastmcp import FastMCP   # mcp >= 1.0.0
mcp = FastMCP("ar-manager")
```

This is the **Anthropic MCP SDK**'s built-in FastMCP class, from the `mcp`
package (`requirements.txt`: `mcp>=1.0.0`).

The `from_openapi()` class method and its `OpenAPIProvider` — the features
needed to source an MCP server from an OpenAPI spec — belong to the
**standalone `fastmcp` library** (by Jerrod Tanner / jlowin). That is a
**different package** not present in this codebase. The two libraries share a
name but have separate PyPI entries, separate APIs, and are not
interchangeable.

Concretely:
- `mcp.server.fastmcp.FastMCP` — has `@mcp.tool()` decorator only; no
  `from_openapi()`.
- `fastmcp 2.x` (standalone) — has `FastMCP.from_openapi()`; but is not
  installed here and switching would require rewriting the entire server.

### 3.2 Does the controller serve OpenAPI docs?

No. The controller is a NanoHTTPD HTTP server with hand-rolled JSON
request/response handling. It exposes no `/openapi.json`, no Swagger endpoint,
and no machine-readable schema. There is no framework (Spring, JAX-RS, Quarkus,
etc.) generating OpenAPI from the Java handler code.

### 3.3 What would "sourcing from OpenAPI" require?

To use `FastMCP.from_openapi()` (if we switched libraries), the MCP server
would need to fetch a live OpenAPI document from the controller at startup.
This requires:

1. **Switching from `mcp.server.fastmcp` to the standalone `fastmcp 2.x`** —
   a full rewrite of the 6,510-line `server.py`.
2. **The controller serving an OpenAPI document** — which requires adding an
   OpenAPI generation mechanism to the Java NanoHTTPD server (either annotating
   the handler methods for a reflection-based generator, or hand-authoring a
   YAML/JSON spec file).
3. **The descriptions problem** (see §4) — still unsolved even if steps 1 and
   2 were done.

Even completing steps 1 and 2 does not satisfy the acceptance criterion,
because the Java controller's field validation still comes from
`JsonFieldExtractor.extractString()` calls, not from the spec. The spec and
the Java handler remain two independent artifacts.

---

## 4. The Descriptions Problem

The MCP docstrings for `workstream_update_config` are not simple field names.
They are context-specific, multi-sentence explanations written for AI clients:

```
default_phase_config: New workstream-level default configuration as a
    JSON object with optional ``runner`` / ``model`` / ``effort`` /
    ``provider`` keys. Pass ``'{}'`` to clear the stored default
    (all phases will then fall through to the workspace or controller
    default). Empty string leaves it unchanged. Use ``agent_options``
    to discover available runner names. Example::

        '{"runner": "opencode", "model": "qwen3-coder:exacto",
          "effort": "medium", "provider": "openrouter"}'
```

An OpenAPI `description` field could technically hold this text. But there is
no mechanism for auto-generating these descriptions from Java code. They would
have to be authored in the spec file by a human. This means:

- **The spec file becomes the new second copy** — a human edits it whenever a
  field is added, exactly as they today edit the Python signature.
- **Or** the descriptions live only in the Python tool function, which means
  the Python file and the spec are two copies.

Neither path satisfies the acceptance criterion.

---

## 5. Legitimate Surface Differences

The API surface and MCP surface **legitimately differ** in several ways. A
shared-spec approach must handle these without reintroducing the manual
bookkeeping it replaces:

| Controller-side field | MCP exposure | Reason for difference |
|---|---|---|
| `slackTeamId` (workspace) | Exposed as `slack_team_id` (with sentinel for absent vs. empty) | Complex absence-vs-empty semantics require MCP-layer logic |
| `channelId` (workstream) | Not exposed on update | Setting channelId on update is unsafe; the MCP tool omits it |
| `archived` | Not in update payload | Archival has its own endpoint with safety checks |
| Legacy `model`/`effort`/`runners` | Exposed as rejected parameters with a 400 error | Backwards-compat error messaging for old clients |
| `retrospective_enabled` | MCP param only (on submit, not update) | Submit-time parameter, not a persisted config field |
| `workspace_id` | Register-only, not update | Controller update handler does not accept it |

For a shared spec to "express these cleanly without a curation layer," it would
need an `x-mcp-exclude` or similar extension on each internal-only field. But
that extension is itself a **per-field annotation that must be maintained in the
spec** every time a new field is added — exactly the kind of recurring manual
step the acceptance criterion rules out as insufficient.

---

## 6. The Killer Finding: Two-Copy Problem Relocates, Not Disappears

For the acceptance criterion to be met, BOTH of the following must be true when
a new field is added:

1. The **controller validates** the new field.
2. The **MCP tool exposes** the new field with a meaningful description.

Under any shared-spec approach studied here:

- **Controller side:** Still validated by `JsonFieldExtractor.extractString()`
  in `WorkstreamRegistrationHandler.handleUpdate()`. The spec does not drive
  controller behavior. Editing the spec has zero effect on the controller.
  **The spec and the Java handler are the new two copies.**

- **MCP side:** Even with `fastmcp 2.x` + `from_openapi()`, the generated MCP
  tool would have parameters derived from the spec's schema, not from the
  (absent) descriptions. A human must still author the description text
  somewhere — either in the spec (making the spec a maintained artifact) or in
  a separate overlay (a third artifact).

The approach trades one drift point for two, not one.

---

## 7. Honest Comparison Against the Divergence Test

The question asks whether a shared-spec approach is better than "just keep the
divergence test." Let us compare honestly:

| Property | Shared OpenAPI spec | `McpToolWorkstreamConfigSurfaceTest` |
|---|---|---|
| Catches missing MCP param at CI | Yes (if spec is kept current) | Yes (already does this today) |
| Human edit required for new field | Yes — edit spec + Java handler + MCP docstring | Yes — edit Java handler + Python param + docstring |
| Edit count per new field | 3 artifacts | 3 artifacts (same) |
| Catches missing docstring content | No — spec descriptions would be stubs | No — test only checks param names |
| Implementation complexity | Very high (library swap, controller redesign) | Already shipped, costs nothing |
| Risk | High — full MCP server rewrite | None — test already exists |
| Constraint on surface differences | Forces one-for-one mapping or per-field curation | Allows arbitrary divergence via `REQUIRED_ON_REGISTER_ONLY` set |

The divergence test **already provides the same CI-level catch** as a shared
spec, with zero implementation cost and zero rigidity on legitimate surface
differences.

---

## 8. Verdict: NO-GO

**The shared-spec approach does not satisfy the acceptance criterion.**

Specifically:

1. **The controller does not derive from any spec.** Its field validation is
   `JsonFieldExtractor.extractString()` calls in Java. Making it derive from a
   spec would require redesigning the controller's request handling. Even then,
   the spec would be a second artifact alongside the Java code.

2. **FastMCP `from_openapi()` is not available** in the MCP library used by
   this codebase (`mcp.server.fastmcp`). Using it requires a library swap and a
   full rewrite of the 6,510-line `server.py`.

3. **Descriptions cannot be auto-generated.** The MCP docstrings contain
   context-specific, multi-sentence AI-targeted guidance that has no source in
   the Java code. A spec's `description` fields would require human authoring —
   making the spec a maintained artifact identical in burden to the Python
   function docstrings.

4. **Legitimate surface differences require per-field curation.** An
   "exclude from MCP" marker on internal-only fields is itself a recurring
   manual edit — exactly the kind of human step the acceptance criterion
   prohibits.

5. **The two-copy problem relocates, not disappears.** In the realistic
   implementation, the spec + the Java handler are the new two copies. The edit
   count per new field does not decrease; the implementation cost is very high.

**What blocks it, exactly stated:** There is no mechanism by which editing one
artifact causes the Java controller to validate a new field AND the MCP tool to
expose it with a useful description. Any approach that achieves both requires at
minimum two separate edits: one to the Java handler and one to the descriptions.
That is the definition of a two-copy system.

---

## 9. What to Do Instead

The existing `McpToolWorkstreamConfigSurfaceTest` already provides CI-enforced
detection of parameter divergence. It should be **extended, not replaced**:

1. **Expand the `REQUIRED_ON_BOTH_REGISTER_AND_UPDATE` set** as each new field
   is added. This is the authoritative list of "what must be exposed via MCP."
   Making this set the canonical record is lighter than a full OpenAPI spec and
   more honest about what the test actually checks.

2. **Add a docstring completeness check** if desired — a test that asserts each
   required parameter has a non-empty docstring in the Python tool (catching
   the case where a param is added with an empty description).

3. **Document the "add a field" workflow** in `CLAUDE.md` or a developer guide:
   - Add the `extractString()` call in the Java handler.
   - Add the parameter to the Python tool signature with a docstring.
   - Add the parameter name to `REQUIRED_ON_BOTH_REGISTER_AND_UPDATE`.
   - CI fails if step 3 is forgotten.

This three-step workflow is the honest answer to what the problem requires. The
shared-spec approach does not reduce the step count.
