# FlowTree

FlowTree is the distributed coding-agent platform that drives this
project's autonomous-development work. It is built around three pieces:

- A **controller stack** that accepts job submissions (from Slack, from
  ar-manager MCP tools, from CI hooks, from the HTTP API), maintains the
  list of workstreams, and dispatches work onto a peer-to-peer mesh.
- An **agent pool** of containerized workers that connect outbound to the
  controller, receive `CodingAgentJob` instances, and run a coding agent
  (Claude Code or opencode, selectable per-phase) inside a private git
  worktree.
- The **ar-manager MCP server** that exposes FlowTree to those agents and
  to external callers (Claude mobile, CI pipelines, Claude Code IDE
  integrations) via a unified MCP tool surface.

A coding job is one prompt that the agent should turn into a commit on a
branch. The orchestrator runs the prompt, then runs a series of
**enforcement-rule** correction sessions (deduplication audit,
organizational placement, post-completion command, commit-message
recovery, ...), each of which can independently route to a different
runner. The result is a committed branch, a structured completion event,
and a per-runner cost / turn rollup that downstream tooling consumes.

---

## Submodule layout

```
flowtree/
├── api/             # ar-flowtreeapi          — protocol & API abstractions
├── base/            # ar-flowtree-base        — shared helpers (JsonFieldExtractor, GitOperations)
├── agents/          # ar-flowtree-agents      — AgentRunner SPI + bundled runners (Claude, opencode)
├── python/          # ar-flowtree-python      — Python bindings
├── graphpersist/    # ar-graphpersist         — database persistence, NFS/SSH
├── runtime/         # ar-flowtree-runtime     — controller, jobs, NodeGroup, Slack integration
└── runtime/rebuild.sh  # one-command build + deploy for the controller stack and agent pool
```

Internal dependency order:

```
api    base    graphpersist
            ↓
         agents       (uses base)
            ↓
         runtime      (uses api, base, agents, python, graphpersist)
```

Nothing in the `base/`, `compute/`, `domain/`, `engine/`, `extern/`, or
`studio/` layers depends on `flowtree/`. The runtime module is the only
flowtree module that pulls in engine-layer code (`ar-utils`,
`ar-utils-http`).

For the broader project layer map, see the top-level
[CLAUDE.md](../CLAUDE.md).

---

## How a job flows through the system

1. **Submission.** An external caller (Slack, an agent calling
   `workstream_submit_task`, a CI script, a direct HTTP POST) hits
   `ar-manager`, which forwards to the controller's
   `POST /api/workstreams/{id}/submit`. The controller validates the
   payload (including the per-phase runner map, see below) and creates a
   `CodingAgentJob`.
2. **Dispatch.** The job lands in the controller's `NodeGroup` and is
   relayed to an agent over the peer mesh. The agent clones / fetches
   the workstream's repo into a private worktree.
3. **Primary phase.** The agent's `CodingAgentJob.executeSingleRun()`
   resolves the runner for `Phase.PRIMARY` (see
   [docs/architecture/PHASES.md](docs/architecture/PHASES.md)), builds
   an `AgentRunRequest`, and invokes it. The runner spawns the
   underlying CLI (`claude` or `opencode`) in the worktree.
4. **Enforcement phases.** After the primary phase returns, the
   orchestrator runs the active `EnforcementRule` list (deduplication,
   organizational placement, post-completion command, ...), turning
   each rule into another `AgentRunRequest` for the runner the operator
   selected for that phase.
5. **Completion.** The orchestrator stages the resulting commit, fires
   a `CodingAgentJobEvent` (with `runnerStats` per runner and the
   per-phase runner map), and either pushes the branch directly or
   opens a PR depending on workstream config.

### Where to look in code

| Concern | Code |
|---------|------|
| Job lifecycle, enforcement-rule outer loop | `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java` |
| Per-phase runner dispatch | `CodingAgentJob.executeSingleRun()` → `AgentRunnerRegistry.get(...)` |
| `AgentRunner` SPI + bundled runners | `flowtree/agents/src/main/java/io/flowtree/jobs/agent/` |
| MCP config + tool allowlist construction | `flowtree/runtime/src/main/java/io/flowtree/jobs/McpConfigBuilder.java` |
| Job submission HTTP endpoint | `flowtree/runtime/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` |
| Workstream config (`defaultRunner`, `runners`, ...) | `flowtree/runtime/src/main/java/io/flowtree/slack/{Workstream,WorkstreamConfig}.java` |
| Slack integration | `flowtree/runtime/src/main/java/io/flowtree/slack/SlackListener.java`, `FlowTreeController.java` |
| Controller config + secrets | `/Users/Shared/flowtree/controller/`, `/Users/Shared/flowtree/secrets/` (operator-managed) |

---

## Documentation

### Pluggable agent runners (this branch's design)

- [docs/architecture/AGENT_RUNNERS.md](docs/architecture/AGENT_RUNNERS.md)
  — The `AgentRunner` SPI: `AgentRunRequest`, `AgentRunResult`,
  `AgentCapabilities`, `AgentRunnerRegistry`. Where allowlist ownership
  lives. How a runner that lacks a binary fails.
- [docs/architecture/PHASES.md](docs/architecture/PHASES.md) — The phase
  inventory, per-phase runner routing precedence ladder, wire-format keys
  for the per-phase map, and the telemetry model on
  `CodingAgentJobEvent`.
- [docs/operations/SETUP.md](docs/operations/SETUP.md) — Operator setup
  index: where workstreams, secrets, Slack, GitHub, and tracker
  integration are configured.
- [docs/operations/OPENCODE.md](docs/operations/OPENCODE.md) — Opencode
  runner setup: environment variables, binary discovery, recommended
  starter setup (`./flowtree/runtime/rebuild.sh --with-llm`), alternative
  backends (llama.cpp, cloud).
- [docs/operations/RECIPES.md](docs/operations/RECIPES.md) — Recommended
  phase-mix recipes (All-Claude, Mixed-review, All-opencode) and how to
  pick between them.

### Runtime (controller + agent pool) detail

- [runtime/README.md](runtime/README.md) — Controller-stack and
  agent-pool deployment, ports, `rebuild.sh` usage, manual Docker
  Compose commands.
- [runtime/docs/](runtime/docs/) — Deeper runtime topics
  (`agent-pool.md`, `coding-agent.md`, `slack-integration.md`,
  `ci-integration.md`, `node-relay.md`, `git-operations.md`, ...).

### Submodule READMEs

- [agents/README.md](agents/README.md) — Per-module README for
  `ar-flowtree-agents`, focused on operator-facing opencode env vars and
  capabilities.
- [api/](api/), [base/](base/), [python/](python/),
  [graphpersist/](graphpersist/) — Each submodule directory has its own
  pom + sources; see the parent [CLAUDE.md](../CLAUDE.md) for the
  responsibilities of each.

### External integrations (configuration pointers)

- **Slack** — workstreams are mapped to Slack channels; tokens live
  under `/Users/Shared/flowtree/secrets/`. See
  [runtime/docs/slack-integration.md](runtime/docs/slack-integration.md).
- **GitHub** — org and repo allowlists, PR-creation behavior, and
  GitHub tokens are configured through `WorkstreamConfig` and the
  ar-manager secret bundle. See
  [runtime/docs/pull-request-detection.md](runtime/docs/pull-request-detection.md)
  and [`tools/mcp/SECRETS.md`](../tools/mcp/SECRETS.md).
- **Tracker** — project-board integration is exposed through the
  ar-manager `tracker_*` MCP tools. See
  [`tools/mcp/CLAUDE.md`](../tools/mcp/CLAUDE.md).
- **Secrets** — the secret-file rendering tool and the controller secret
  bundle layout are documented in
  [`tools/mcp/SECRETS.md`](../tools/mcp/SECRETS.md).

---

## Where to start

- **You're an operator bringing up a new host:** start at
  [docs/operations/SETUP.md](docs/operations/SETUP.md).
- **You're tuning costs by routing phases to a cheaper local model:**
  start at [docs/operations/OPENCODE.md](docs/operations/OPENCODE.md)
  and [docs/operations/RECIPES.md](docs/operations/RECIPES.md).
- **You're adding a new agent runner:** start at
  [docs/architecture/AGENT_RUNNERS.md](docs/architecture/AGENT_RUNNERS.md).
- **You're modifying how the controller dispatches jobs:** start at
  [runtime/README.md](runtime/README.md) and the design docs under
  `runtime/docs/`.
