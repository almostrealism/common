# Opencode Runner — Operator Setup

The `opencode` runner drives the [opencode](https://opencode.ai) CLI
against an OpenAI-compatible inference endpoint. The intended use is to
move phases that a local model can handle off Claude Opus (deduplication
audit, organizational placement, commit-message recovery, post-completion
correction) and onto a cheaper local backend, while keeping Claude for
the primary work.

For the runner SPI and how phases pick their runner, see
[../architecture/AGENT_RUNNERS.md](../architecture/AGENT_RUNNERS.md) and
[../architecture/PHASES.md](../architecture/PHASES.md). For recommended
phase mixes, see [RECIPES.md](RECIPES.md).

---

## Recommended starter setup

The fastest way to get a working setup on a machine that can host the
model locally:

```sh
./flowtree/runtime/rebuild.sh --with-llm
```

This rebuilds the agent image (with the opencode binary baked in) and
also starts an ollama daemon on the host, pulls the default model
(`qwen3-coder:30b`), and configures the agent containers to talk to it
via `OPENCODE_PROVIDER_URL`.

The `--with-llm` flag is opt-in. Operators on hardware that cannot
sustain a 30B-parameter model should omit it and point
`OPENCODE_PROVIDER_URL` at a different endpoint (see below).

See [SETUP.md](SETUP.md) for the broader operator setup story.

---

## Environment variables read by the runner

These are intentionally host-level concerns (different agents run on
different machines, each with its own local model server reachable on
its own host) — there are no per-workstream overrides.

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENCODE_BIN` | (unset) | Absolute path to the `opencode` binary. Takes precedence over every other discovery rule. |
| `OPENCODE_PROVIDER_URL` | `http://localhost:11434/v1` | OpenAI-compatible endpoint URL. |
| `OPENCODE_API_KEY` | empty | API key for the provider. Local llama.cpp / ollama do not require one. |
| `OPENCODE_DEFAULT_MODEL` | (unset; falls back to `qwen3-coder-30b`) | Model name used when the submitted job does not specify one. |

### Binary discovery order

1. `OPENCODE_BIN` env var (must resolve to a runnable executable).
2. `~/.flowtree/bin/opencode` (operator-managed install).
3. `PATH` lookup of `opencode`.

If none of the three resolves, `OpencodeRunner.run()` throws
`AgentRunnerNotAvailableException` with a message that enumerates what
was checked.

---

## Picking a backend

### ollama (default; what `--with-llm` provisions)

```sh
ollama serve                          # listens on http://localhost:11434
ollama pull qwen3-coder:30b
export OPENCODE_PROVIDER_URL=http://localhost:11434/v1
# OPENCODE_API_KEY: leave unset.
export OPENCODE_DEFAULT_MODEL=qwen3-coder:30b
```

To swap the default model, override `LLM_MODEL` when invoking
`rebuild.sh --with-llm` (the script `ollama pull`s whatever you set).

### llama.cpp server

`--with-llm` does **not** provision llama.cpp. To use it, install and
run it yourself, then point the runner at it:

```sh
./llama-server -m /path/to/model.gguf --port 8080 --api-key ""
export OPENCODE_PROVIDER_URL=http://localhost:8080/v1
# OPENCODE_API_KEY: leave unset (or set to whatever --api-key was given).
export OPENCODE_DEFAULT_MODEL=qwen3-coder-30b
```

### Cloud OpenAI-compatible provider

```sh
export OPENCODE_PROVIDER_URL=https://api.openai.com/v1
export OPENCODE_API_KEY=sk-...
export OPENCODE_DEFAULT_MODEL=gpt-4o-mini
```

---

## Routing phases to opencode

The orchestrator's per-phase runner selection lets callers route specific
phases to opencode via the `workstream_submit_task` MCP tool. Examples:

- Everything on opencode:
  ```
  default_runner=opencode
  ```
- Mixed: primary on Claude, audits on opencode:
  ```
  runners={"deduplication":"opencode","organizational-placement":"opencode",
           "post-completion":"opencode","commit-message":"opencode"}
  ```

See [../architecture/PHASES.md](../architecture/PHASES.md) for the full
per-phase precedence rules and [RECIPES.md](RECIPES.md) for vetted
phase-mix recipes.

---

## Capabilities

`OpencodeRunner.capabilities()` declares:

- `reportsCost = false` — local-model cost is meaningless; downstream
  telemetry treats opencode-runner cost as N/A rather than zero.
- `reportsTurns = true` — best-effort, extracted from the output's `steps`
  or `iterations` field, with a fallback to counting assistant transcript
  entries.
- `supportsEffortLevel = false`
- `supportsMaxBudget = false`
- `supportsMcpHttpTransport = true`
- `supportsMcpStdioTransport = true`
- `supportsPermissionDenialReporting = false`
- `supportedModels = {}` — empty; the runner trusts the provider to
  validate model names.

These flags drive the telemetry-side `RunnerStats.costReported` /
`turnsReported` fields and the top-level `unmeasuredCostRunners` list on
the completion event. A workstream that routes any phase to opencode
should expect "cost reported by this job is not the total cost" — that
is the trade-off behind the runner.
