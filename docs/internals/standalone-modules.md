# Standalone Modules Reference

This document covers the standalone modules in the Almost Realism Common codebase:
the flowtree family (`flowtree/api`, `flowtree/base`, `flowtree/agents`, `flowtree/python`,
`flowtree/graphpersist`, `flowtree/runtime`) and `tools`.

These modules sit architecturally above the engine layer (Layer 4) but are not part of
any named layer (Layers 1-6). They have their own lifecycle, CI treatment, and deployment
model. Understanding them correctly is essential for CI pipeline work and dependency analysis.

---

## What Makes a Module "Standalone"?

A standalone module has all of the following characteristics:

1. **It depends on engine-layer (or higher) modules** — it sits above Layer 4
2. **Nothing in the named layers (Layers 1-6) depends on it** — it is not a building block for other named layers
3. **It is independently deployable or usable** as a subsystem, tool, or integration
4. **Its CI tests run in a dedicated location** (`build` job or `code-policy-check`), not in the layer-gated `test` job

If a module you are examining satisfies all four criteria, treat it as standalone. Do NOT
create a new layer flag for it in the CI pipeline.

---

## Module Reference

### `flowtree/api` — FlowTree API and Protocol Abstractions

**Artifact ID**: `ar-flowtreeapi`
**Directory**: `flowtree/api/`
**Depends on**: `ar-utils` (engine layer — Layer 4)

#### What it does
`flowtreeapi` defines the protocol and API abstractions for the FlowTree distributed workflow
system. It contains:
- Job interface definitions (`Job`, `JobFactory`, `Task`)
- Network protocol messages and serialization
- Node/cluster abstractions
- The `NodeGroup` and `WorkTree` interfaces

It does NOT contain FlowTree runtime logic — that lives in `flowtree/runtime` itself.

#### Who depends on it
- `ar-flowtree-python` — Python binding layer depends on the API abstractions
- `ar-flowtree-runtime` — The main FlowTree runtime implements and uses these APIs

#### Dependency direction (critical)
```
ar-flowtree-runtime  →  ar-flowtreeapi   (flowtree/runtime DEPENDS ON flowtree/api)
ar-flowtreeapi    →  ar-utils          (flowtree/api depends on engine utils)
```

**WRONG**: "flowtree/api depends on flowtree/runtime" — this reverses the direction.
**WRONG**: "ar-utils depends on flowtreeapi" — this inverts the architectural hierarchy.

#### CI treatment
- Tests run as part of `mvn test -pl flowtree/runtime` in the `build` job
- No separate layer flag exists — changes set `code_changed=true` only
- The `build` job always runs flowtree tests (which transitively covers flowtree/api)

---

### `flowtree/base` — Shared FlowTree Helpers

**Artifact ID**: `ar-flowtree-base`
**Directory**: `flowtree/base/`
**Depends on**: `ar-io` (engine layer — Layer 1, for `ConsoleFeatures`), Jackson databind

#### What it does
`flowtree/base` holds cross-cutting helpers that need to be visible to both `flowtree/agents`
and `flowtree/runtime` but have no flowtree-specific runtime dependencies:

- `io.flowtree.JsonFieldExtractor` — small, side-effect-free utility for pulling typed
  fields out of Claude Code's NDJSON output and similar payloads.
- `io.flowtree.jobs.GitOperations` — wraps `git`/`ssh` subprocess execution with PATH
  augmentation for headless environments.

These were originally in `flowtree/core` but were pulled down so the agent-runner
abstraction (which `ClaudeCodeRunner` consumes both of) does not have to depend on the
runtime.

#### Who depends on it
- `ar-flowtree-agents` — `ClaudeCodeRunner` uses both helpers
- `ar-flowtree-runtime` — `GitOperations` has many callers (`GitManagedJob`,
  `GitRepositorySetup`, `ExternalProcessJob`, `FileStager`, …); `JsonFieldExtractor` is
  used by Slack/HTTP handlers

#### CI treatment
Same as the other flowtree-family modules: no separate layer flag, tested as part of
`flowtree/runtime` in the `build` job.

---

### `flowtree/agents` — Agent Runner Abstraction

**Artifact ID**: `ar-flowtree-agents`
**Directory**: `flowtree/agents/`
**Depends on**: `ar-flowtree-base`, `ar-meta` (Layer 1, for `Named`)

#### What it does
`flowtree/agents` is the SPI for pluggable coding-agent backends. It contains:

- `io.flowtree.jobs.agent.AgentRunner` — interface every runner implements
- `io.flowtree.jobs.agent.AgentRunRequest` / `AgentRunResult` / `AgentCapabilities` —
  data carriers exchanged with runners
- `io.flowtree.jobs.agent.AgentRunnerRegistry` — process-wide name→supplier map
- `io.flowtree.jobs.agent.ClaudeCodeRunner` — the bundled default runner that drives the
  Claude Code CLI as a subprocess
- `io.flowtree.jobs.AgentProcessRunner` / `AgentInactivityMonitor` — subprocess plumbing
  consumed only by runners

Pulling these out of `flowtree/runtime` lets new runners (e.g. for OpenCode, Aider, etc.)
register themselves without touching the controller's classpath. See
`docs/plans/PLUGGABLE_AGENTS.md` for the broader plan.

#### Who depends on it
- `ar-flowtree-runtime` — `CodingAgentJob`, `CodingAgentJobFactory`, `CodingAgentJobEvent`
  reference `AgentRunner`, `AgentRunnerRegistry`, and constants from `ClaudeCodeRunner`

#### Dependency direction (critical)
```
ar-flowtree-runtime  →  ar-flowtree-agents   (runtime DEPENDS ON agents)
ar-flowtree-agents   →  ar-flowtree-base     (agents DEPENDS ON base)
ar-flowtree-agents   →  ar-meta              (for Named)
```

`ar-flowtree-agents` does NOT depend on `ar-flowtree-runtime`. Any runner that needs
runtime types (e.g. a future runner that posts directly to Slack) must live in
`flowtree/runtime` and register itself with `AgentRunnerRegistry.register(...)`.

#### CI treatment
Same as flowtree/base.

---

### `flowtree/graphpersist` — Graph Persistence and Storage

**Artifact ID**: `ar-graphpersist`
**Directory**: `flowtree/graphpersist/`
**Depends on**: `ar-utils` (engine layer — Layer 4)

#### What it does
`graphpersist` handles persistent storage of computation graphs, neural network model weights,
and associated data. It provides:
- NFS and SSH-backed storage backends
- Graph serialization and deserialization
- Weight persistence for trained models (via `StateDictionary` integration)
- Storage abstraction interfaces so the rest of the system is backend-agnostic

#### Who depends on it
- `ar-flowtree-runtime` — FlowTree uses graphpersist to store job results, checkpoints, and model weights

#### Dependency direction
```
ar-graphpersist  →  ar-utils         (graphpersist depends on engine utils)
ar-flowtree-runtime →  ar-graphpersist  (flowtree/runtime depends on graphpersist)
```

Nothing in the named layers depends on graphpersist. The named layers do not know about
persistence backends.

#### CI treatment
Same as flowtree/api: no separate layer flag, tested as part of `flowtree/runtime` in the `build` job.

---

### `flowtree/python` — Python Bindings for FlowTree

**Artifact ID**: `ar-flowtree-python`
**Directory**: `flowtree/python/`
**Depends on**: `ar-flowtreeapi`

#### What it does
`flowtree-python` provides Python language bindings that allow Python code to submit jobs
to, query, and interact with a FlowTree cluster. This enables:
- Python scripts to submit ML training jobs
- Data scientists to use FlowTree without writing Java
- Integration with Python ML tooling (PyTorch, HuggingFace, etc.) on the submission side

#### Who depends on it
- `ar-flowtree-runtime` — the main FlowTree runtime includes flowtree/python as part of its distribution

#### Dependency direction
```
ar-flowtree-python  →  ar-flowtreeapi      (python bindings depend on the API)
ar-flowtree-runtime    →  ar-flowtree-python  (runtime includes the python bindings)
```

#### CI treatment
Same as other flowtree-family modules: no separate flag, tested in `build`.

---

### `flowtree/runtime` — FlowTree Distributed Workflow Engine

**Artifact ID**: `ar-flowtree-runtime`
**Directory**: `flowtree/runtime/`
**Depends on**: `ar-flowtreeapi`, `ar-flowtree-python`, `ar-graphpersist`, `ar-utils-http`

This is the top of the standalone module hierarchy. It depends on all other standalone modules
plus the engine-layer HTTP utilities.

#### What it does
FlowTree is a distributed workflow orchestration system built on top of the Almost Realism
compute stack. It enables:
- Distributed execution of `Job` instances across a cluster of agents
- `ClaudeCodeJob` — an AI-powered job type that uses Claude Code to implement tasks
- `ExternalProcessJob` — run arbitrary shell commands as distributed tasks
- Workstream management (groups of related jobs)
- Agent pool management with Docker Compose (`flowtree/runtime/agent/`)
- MCP server integration for controller management (`tools/mcp/manager/`)

#### Key classes
- `io.flowtree.jobs.ClaudeCodeJob` — submits Claude Code tasks to agents
- `io.flowtree.jobs.ClaudeCodeJobFactory` — creates `ClaudeCodeJob` instances from configuration
- `io.flowtree.jobs.ExternalProcessJob` — runs shell commands as distributed jobs
- `io.flowtree.node.NodeGroup` — manages a group of worker nodes
- `io.flowtree.fs.distributed.NetworkFile` — distributed file system abstraction

#### Deduplication modes (ClaudeCodeJob)
`ClaudeCodeJob` supports three deduplication modes:
- `DEDUP_LOCAL` (`"local"`) — **default** — runs deduplication as a second Claude Code invocation in the same agent after the primary work
- `DEDUP_SPAWN` (`"spawn"`) — spawns a new distributed job for deduplication
- `DEDUP_NONE` (`"none"`) — skips deduplication entirely

When `DEDUP_LOCAL` runs, it saves and restores `commit.txt` around the dedup invocation
so that the primary work's commit message is preserved (not overwritten by the dedup agent's message).

#### Docker deployment
Agents are deployed via `flowtree/runtime/agent/docker-compose.yml`. Key design decisions:
- Each agent gets its own **anonymous** volume for `/workspace/project` (no sharing between agents — build artifacts from different agents would conflict)
- Each agent gets its own **anonymous** volume for `/home/agent/.m2` (Maven cache is per-agent)
- Only read-only mounts are shared: SSH keys, model files, audio samples
- Named writable volumes are strictly prohibited (causes cross-agent build artifact contamination)

#### CI treatment
- **Changes anywhere under `flowtree/` set `code_changed=true` only** — no layer flag exists
- Tests run via `mvn test -pl flowtree/runtime` inside the `build` job
- Coverage uploaded as `coverage-flowtree` artifact
- The `analysis` job waits on `build` to get this coverage

**Why tests are in `build`, not a separate job**: FlowTree tests depend on having all modules
built (`mvn install -DskipTests` runs first in `build`). More importantly, flowtree/ changes
don't set any layer flag, so layer-gated jobs (`test`, `test-media`) would be skipped on
flowtree-only branches. Running tests in `build` ensures they always execute.

---

### `tools` — Development Tools and MCP Servers

**Artifact ID**: `ar-tools`
**Directory**: `tools/`
**Depends on**: `ar-ml` (engine layer — Layer 4)

#### What it does
`tools/` contains development tooling, MCP (Model Context Protocol) servers, and utilities
for working with the Almost Realism codebase. It includes:

- `tools/mcp/manager/` — MCP server for managing FlowTree jobs, GitHub PRs, memory, workstreams
- `tools/mcp/docs/` — MCP server for reading AR documentation and source code
- `tools/mcp/jmx/` — MCP server for JVM memory diagnostics and JFR profiling
- `tools/mcp/test-runner/` — MCP server for running Maven tests asynchronously
- `tools/mcp/profile-analyzer/` — MCP server for analyzing profiling output
- `tools/mcp/consultant/` — MCP server for AI-assisted code consultation and memory

#### Dependency on ar-ml
`tools` depends on `ar-ml` because some development tools use ML capabilities directly
(e.g., embedding-based semantic search in the consultant tool, model analysis utilities).

#### CI treatment
- Tests run in `code-policy-check` job (not in the layer-gated `test` job)
- Also tested in `test-timeout-check` for timeout-specific scenarios
- No separate layer flag — changes set `code_changed=true` only
- `code-policy-check` always runs when `code_changed=true`

**Why tests are in `code-policy-check`**: The `tools/` module has no layer flag. It tests
development tooling that should always be verified when any code changes (not just when
specific layers change). `code-policy-check` always runs on code changes, making it the
appropriate home for tools tests.

---

## CI Pipeline Treatment Summary

| Module | Layer Flag | Tests Run In | Coverage Artifact |
|--------|------------|--------------|-------------------|
| flowtree/api | none | `build` (via flowtree/runtime) | `coverage-flowtree` |
| flowtree/base | none | `build` (via flowtree/runtime) | `coverage-flowtree` |
| flowtree/agents | none | `build` (via flowtree/runtime) | `coverage-flowtree` |
| flowtree/graphpersist | none | `build` (via flowtree/runtime) | `coverage-flowtree` |
| flowtree/python | none | `build` (via flowtree/runtime) | `coverage-flowtree` |
| flowtree/runtime | none | `build` | `coverage-flowtree` |
| tools | none | `code-policy-check` | none (not in coverage merge) |

All of the above set `code_changed=true` when changed, triggering `build`, `code-policy-check`,
and `test-timeout-check`. They do NOT trigger `test` or `test-media` because those jobs are
gated on named-layer flags.

---

## What NOT to Do

### DO NOT add layer flags for standalone modules

**Wrong**:
```yaml
# In the changes job detection loop:
flowtree_changed: ${{ steps.filter.outputs.flowtree == 'true' }}
```

Layer flags exist for named layers only. The `test` and `test-media` jobs test named-layer
modules. Flowtree tests run in `build`. Adding a `flowtree_changed` flag that nobody consumes
creates confusion and dead code.

### DO NOT move flowtree tests out of `build`

The `build` job is the only job that always runs when `code_changed=true`. If flowtree tests
were moved to a separately-gated job, they would be skipped on flowtree-only branches (because
no layer flag is set). Keep flowtree tests in `build`.

### DO NOT assume "nothing depends on X" after one grep

Always check BOTH directions:
1. What does X depend on? (X's pom.xml dependencies)
2. What depends on X? (grep all pom.xml files for X's artifact ID)

A module being standalone does NOT mean nothing uses it. `ar-flowtree-runtime` uses all other
standalone modules. Only after checking the consumer graph can you conclude a module is
truly a leaf.

### DO NOT confuse standalone with isolated

Standalone means "not depended on by named layers." It does NOT mean the module is
independent of everything. `ar-flowtree-runtime` has a rich dependency graph — it just happens
to be at the top of the standalone tree.

---

## Dependency Graph Visualization

```
Named Layers (Layers 1-6)          Standalone Modules
══════════════════════              ══════════════════
                                   ar-flowtree-runtime
                                   ├── ar-flowtreeapi ──┐
                                   ├── ar-flowtree-python──┘  (ar-flowtreeapi)
                                   ├── ar-graphpersist ─────── ar-utils (Layer 4)
                                   └── ar-utils-http ─────────────────────────┐
                                                                               │
Layer 6 — Studio                                                               │
Layer 5 — Extern                                                               │
Layer 4 — Engine ── ar-utils ──────────────────────────────────────────────────┘
Layer 3 — Domain    ar-utils-http
Layer 2 — Compute   ar-ml ──────── ar-tools (Standalone)
Layer 1 — Base
```

Arrows point FROM consumer TO dependency (i.e., `ar-flowtree-runtime → ar-utils` means flowtree
depends on utils, not the other way around).

---

## Historical Notes

### Why flowtree is not a named layer

FlowTree was added to the project after the named layer architecture was established. It
depends on the engine layer (utils, utils-http) but is not a "computation substrate" in
the way the named layers are — it's an application built ON TOP of the computation stack,
not an extension of it. Making it a named layer would imply that something above it in the
hierarchy could depend on it, which is not the intended design.

### Why Maven module creation is externally controlled

The Maven module structure of this project is managed separately from the source code. An
external system controls when new modules are added, how they're positioned in the reactor
order, and how the parent pom.xml is updated. **Agents must never create new pom.xml files
or add `<module>` entries to parent pom.xml.** This constraint exists because the module
structure affects the entire build order, dependency resolution, and CI pipeline.

---

## Related Documentation

- `.github/CLAUDE.md` — CI pipeline module dependency graph and rules
- `.github/CI_ARCHITECTURE.md` — Comprehensive CI job reference
- `docs/internals/module-dependency-architecture.md` — Full dependency graph with verification commands
- `docs/internals/ci-investigation-protocol.md` — Step-by-step CI investigation guide
- `flowtree/runtime/agent/docker-compose.yml` — Agent deployment configuration
- `tools/mcp/manager/server.py` — Manager MCP server implementation
