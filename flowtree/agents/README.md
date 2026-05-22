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
| `OPENCODE_PROVIDER_URL` | `http://localhost:8084/v1` | OpenAI-compatible endpoint URL. The default targets the llama.cpp `llama-server` launched by `tools/bin/llama.sh`. |
| `OPENCODE_API_KEY` | empty | API key for the provider. Local llama.cpp/ollama do not require one. |
| `OPENCODE_DEFAULT_MODEL` | (unset; falls back to the literal alias `default`) | Model name used when the submitted job does not specify one. The `default` alias is fine with llama.cpp's `llama-server` (it serves whichever GGUF was loaded, ignoring the model field on the wire); ollama and hosted providers dispatch by name and **require** an explicit value here. |

### Binary discovery order

1. `OPENCODE_BIN` env var (must resolve to a runnable executable).
2. `~/.flowtree/bin/opencode` (operator-managed install).
3. `PATH` lookup of `opencode`.

If none of the three resolves, `OpencodeRunner.run()` throws
`AgentRunnerNotAvailableException` with a message that enumerates what was
checked.

---

## Recommended setup

### llama.cpp server (recommended)

`llama-server` serves whichever GGUF was loaded at launch regardless of the
`model` field on incoming requests, so the runner's compiled-in `default`
alias is sufficient — no `OPENCODE_DEFAULT_MODEL` needed.

```sh
# On the agent host:
./llama-server -m /path/to/model.gguf --port 8080 --api-key ""
export OPENCODE_PROVIDER_URL=http://localhost:8080/v1
# OPENCODE_API_KEY: leave unset (or set to whatever --api-key was given).
# OPENCODE_DEFAULT_MODEL: leave unset — the runner's "default" alias works.
```

### ollama

ollama dispatches by model name, so `OPENCODE_DEFAULT_MODEL` is **required**
— set it to the exact tag you pulled.

```sh
# On the agent host:
ollama serve                          # listens on http://localhost:11434
ollama pull <model-tag>
export OPENCODE_PROVIDER_URL=http://localhost:11434/v1
# OPENCODE_API_KEY: leave unset.
export OPENCODE_DEFAULT_MODEL=<model-tag>      # required for ollama
```

### Cloud OpenAI-compatible provider

Hosted providers dispatch by model name, so `OPENCODE_DEFAULT_MODEL` is
**required** — set it to whichever model identifier the provider documents.

```sh
export OPENCODE_PROVIDER_URL=https://api.openai.com/v1
export OPENCODE_API_KEY=sk-...
export OPENCODE_DEFAULT_MODEL=<provider-model-id>   # required for hosted
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

See [`flowtree/docs/architecture/PHASES.md`](../docs/architecture/PHASES.md) for
the full per-phase precedence rules and
[`flowtree/docs/operations/OPENCODE.md`](../docs/operations/OPENCODE.md) for
operator-facing setup detail.

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
