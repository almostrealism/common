# ar-flowtree-agents

Pluggable agent-runner SPI for FlowTree coding jobs.

The orchestrator (`io.flowtree.jobs.CodingAgentJob`) dispatches each phase of a
job through an `AgentRunner` looked up in `AgentRunnerRegistry`. Two runners
ship today:

- **`claude`** — drives the Claude Code CLI (`@anthropic-ai/claude-code`).
- **`opencode`** — drives the [opencode](https://opencode.ai) CLI, primarily
  pointed at a local OpenAI-compatible inference server (llama.cpp,
  [ollama](https://ollama.ai)) on the operator's network.

The migration motivating opencode is to move phases a local model can handle
(deduplication audit, organizational placement, commit-message recovery,
post-completion correction) off Claude Opus and onto a cheaper local backend
while keeping Claude for primary work.

---

## Opencode runner — environment variables

The runner reads the following environment variables at launch time. They are
intentionally host-level concerns (different agents run on different machines
with different local model servers reachable) — there are no per-workstream
overrides.

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENCODE_BIN` | (unset) | Absolute path to the `opencode` binary. Takes precedence over every other discovery rule. |
| `OPENCODE_PROVIDER_URL` | `http://localhost:11434/v1` | OpenAI-compatible endpoint URL. |
| `OPENCODE_API_KEY` | empty | API key for the provider. Local llama.cpp/ollama do not require one. |
| `OPENCODE_DEFAULT_MODEL` | (unset; falls back to `qwen3-coder-30b`) | Model name used when the submitted job does not specify one. |

### Binary discovery order

1. `OPENCODE_BIN` env var (must resolve to a runnable executable).
2. `~/.flowtree/bin/opencode` (operator-managed install).
3. `PATH` lookup of `opencode`.

If none of the three resolves, `OpencodeRunner.run()` throws
`AgentRunnerNotAvailableException` with a message that enumerates what was
checked.

---

## Recommended setup

### ollama (default)

```sh
# On the agent host:
ollama serve                          # listens on http://localhost:11434
ollama pull qwen3-coder:30b
export OPENCODE_PROVIDER_URL=http://localhost:11434/v1
# OPENCODE_API_KEY: leave unset.
export OPENCODE_DEFAULT_MODEL=qwen3-coder:30b
```

### llama.cpp server

```sh
# On the agent host:
./llama-server -m /path/to/model.gguf --port 8080 --api-key ""
export OPENCODE_PROVIDER_URL=http://localhost:8080/v1
# OPENCODE_API_KEY: leave unset (or set to whatever --api-key was given).
export OPENCODE_DEFAULT_MODEL=qwen3-coder-30b
```

### Cloud provider (also works)

```sh
export OPENCODE_PROVIDER_URL=https://api.openai.com/v1
export OPENCODE_API_KEY=sk-...
export OPENCODE_DEFAULT_MODEL=gpt-4o-mini
```

---

## Routing phases to opencode

The orchestrator's per-phase runner selection (Phase 2 of the pluggable-agents
plan) lets callers route specific phases to opencode via the
`workstream_submit_task` MCP tool. Examples:

- Everything on opencode:
  ```
  default_runner=opencode
  ```
- Mixed: primary on Claude, audits on opencode:
  ```
  runners={"deduplication":"opencode","organizational-placement":"opencode",
           "post-completion":"opencode","commit-message":"opencode"}
  ```

See `docs/plans/PLUGGABLE_AGENTS.md` for the full per-phase precedence rules.

---

## Capabilities

`OpencodeRunner.capabilities()` declares:

- `reportsCost = false` — local-model cost is meaningless; downstream telemetry
  treats opencode-runner cost as N/A rather than zero.
- `reportsTurns = true` — best-effort, extracted from the output's `steps` or
  `iterations` field, with a fallback to counting assistant transcript entries.
- `supportsEffortLevel = false`
- `supportsMaxBudget = false`
- `supportsMcpHttpTransport = true`
- `supportsMcpStdioTransport = true`
- `supportsPermissionDenialReporting = false` (until proven otherwise)
- `supportedModels = {}` — empty; the runner trusts the provider to validate
  model names.
