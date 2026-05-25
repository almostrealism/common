# Opencode Runner — Operator Setup

The `opencode` CLI runner drives the [opencode](https://opencode.ai) CLI
against an OpenAI-compatible inference endpoint. The intended use is to
move phases that a local model can handle off Claude Opus (deduplication
audit, organizational placement, commit-message recovery, post-completion
correction) and onto a cheaper local backend, while keeping Claude for
the primary work.

For the runner SPI and how phases pick their runner, see
[../architecture/AGENT_RUNNERS.md](../architecture/AGENT_RUNNERS.md) and
[../architecture/PHASES.md](../architecture/PHASES.md). For recommended
phase mixes, see [RECIPES.md](RECIPES.md). For the provider axis —
the three known providers (`local`, `openrouter`, `anthropic`), how
API keys resolve, and how to wire up OpenRouter or Anthropic as a
FlowTree workspace secret — see [PROVIDERS.md](PROVIDERS.md).

---

## Recommended starter setup

The fastest way to get a working setup on a machine that can host the
model locally:

```sh
./flowtree/runtime/rebuild.sh --with-llm
```

This rebuilds the agent image (with the opencode binary baked in) and
also starts an ollama daemon on the host, pulls the model named by
`LLM_MODEL` (defaults to a coder-tuned 30B variant — override
`LLM_MODEL` at invocation to pull something else), and writes
`OPENCODE_PROVIDER_URL` into the agent `.env` so the agent containers
talk to it.

`OPENCODE_DEFAULT_MODEL` is **not** written automatically. ollama
dispatches by model name, so jobs submitted without an explicit model
field would otherwise be sent to the runner's compiled-in `default`
alias — which ollama does not recognise. The script prints the exact
line to add to `.env` after pulling; do so before submitting jobs that
omit the model field. (llama.cpp users don't need this — see below.)

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
| `OPENCODE_PROVIDER_URL` | `http://localhost:8084/v1` | OpenAI-compatible endpoint URL. When set, overrides the per-provider default for **every** provider — useful for pointing `openrouter`/`anthropic` at a private proxy, in addition to its primary role of selecting the local model server's address. When unset, each provider falls back to its built-in default from `PROVIDER_MAP`. See [PROVIDERS.md](PROVIDERS.md). |
| `OPENCODE_API_KEY` | empty | Legacy override that pins a specific API key regardless of provider. Useful for one-off testing. For production cloud-provider setup, prefer the workspace-secret path documented in [PROVIDERS.md](PROVIDERS.md). |
| `OPENROUTER_API_KEY` / `ANTHROPIC_API_KEY` | empty | Per-provider env-var fallbacks, consulted when `OPENCODE_API_KEY` and the workspace secret are both empty. See [PROVIDERS.md](PROVIDERS.md). |
| `OPENCODE_DEFAULT_MODEL` | (unset; falls back to the literal alias `default`) | Model name used when the submitted job does not specify one. The `default` alias is fine with llama.cpp's `llama-server` (it ignores the model field on the wire and serves whichever GGUF was loaded); ollama and hosted providers dispatch by name and **require** an explicit value here. |
| `OPENCODE_CONFIG` | (set automatically at launch) | Path to the synthesized config file. Set by the runner before launching the opencode subprocess; operators do not need to configure this. |
| `OPENCODE_TRANSCRIPT_DIR` | (see [Session transcripts](#session-transcripts)) | Path to the directory where session transcript JSONL files are written (absolute path recommended; relative paths are resolved against the JVM working directory). When unset, the runner derives the directory from the job's output capture path, falling back to `/tmp/opencode-transcripts`. |

### Binary discovery order

1. `OPENCODE_BIN` env var (must resolve to a runnable executable).
2. `~/.flowtree/bin/opencode` (operator-managed install).
3. `PATH` lookup of `opencode`.

If none of the three resolves, `OpencodeRunner.run()` throws
`AgentRunnerNotAvailableException` with a message that enumerates what
was checked.

---

## Picking a backend

### llama.cpp server (what AR primarily uses)

`llama-server` serves whichever GGUF was loaded at launch and ignores the
`model` field on incoming requests, so the runner's compiled-in `default`
alias is sufficient — no `OPENCODE_DEFAULT_MODEL` needed.

```sh
./llama-server -m /path/to/model.gguf --port 8080 --api-key ""
export OPENCODE_PROVIDER_URL=http://localhost:8080/v1
# OPENCODE_API_KEY: leave unset (or set to whatever --api-key was given).
# OPENCODE_DEFAULT_MODEL: leave unset — the runner's "default" alias works.
```

For the AR-managed Qwen3 setup on `mac-studio`, see
[`tools/bin/llama.sh`](../../../tools/bin/llama.sh) and the
[launchd plist](../../../tools/launchd/com.almostrealism.llama-server.plist).

### ollama (what `--with-llm` provisions)

ollama dispatches by model name, so `OPENCODE_DEFAULT_MODEL` is **required**.
`rebuild.sh --with-llm` writes `OPENCODE_PROVIDER_URL` automatically but
deliberately leaves `OPENCODE_DEFAULT_MODEL` for the operator — see the
note in the quick-start above.

```sh
ollama serve                          # listens on http://localhost:11434
ollama pull <model-tag>
export OPENCODE_PROVIDER_URL=http://localhost:11434/v1
# OPENCODE_API_KEY: leave unset.
export OPENCODE_DEFAULT_MODEL=<model-tag>      # required for ollama
```

To swap the pulled model, override `LLM_MODEL` when invoking
`rebuild.sh --with-llm` (the script `ollama pull`s whatever you set, and
prints the matching `OPENCODE_DEFAULT_MODEL=…` line to copy into `.env`).

### Cloud providers (OpenRouter, Anthropic)

Cloud providers are first-class — pick `provider: openrouter` or
`provider: anthropic` on submission and the runner uses the matching
entry in `OpencodeRunner.PROVIDER_MAP` (base URL, secret name, env-var
fallback). The recommended setup wires the API key as a FlowTree
workspace secret so the agent host doesn't need any env vars at all;
see [PROVIDERS.md](PROVIDERS.md) for the worked example. For one-off
testing, exporting `OPENROUTER_API_KEY` / `ANTHROPIC_API_KEY` (or the
legacy `OPENCODE_API_KEY`) on the agent host is sufficient.

---

## Routing phases to opencode

The orchestrator's per-phase runner selection lets callers route specific
phases to opencode via the `workstream_submit_task` MCP tool's
`default_phase_config` / `phase_configs` parameters. (The legacy
`default_runner` / `runners` parameters are no longer accepted.) Examples:

- Everything on opencode:
  ```
  default_phase_config='{"runner":"opencode"}'
  ```
- Mixed: primary on Claude, audits on opencode:
  ```
  default_phase_config='{"runner":"claude"}'
  phase_configs='{"deduplication":{"runner":"opencode"},"organizational-placement":{"runner":"opencode"},
                  "post-completion":{"runner":"opencode"},"commit-message":{"runner":"opencode"}}'
  ```

See [../architecture/PHASES.md](../architecture/PHASES.md) for the full
per-phase precedence rules and [RECIPES.md](RECIPES.md) for vetted
phase-mix recipes.

---

## Session transcripts

After every opencode session, `OpencodeRunner` automatically writes a structured
JSONL transcript file that captures the full session for postmortem analysis.
This is the primary mechanism for investigating model misbehaviour (corrupted
output, unexpected tool usage, runaway loops, etc.).

### Format

Each transcript file is a JSONL file with three sections:

1. **Header line** (`"type":"transcript_header"`) — session context including
   `job_id`, `workstream_id`, `phase`, `runner`, `model`, `provider`,
   `provider_url`, `opencode_version`, `working_directory`, `prompt`,
   `prompt_length`, `session_id`, `start_epoch_ms`, `start_iso`,
   `format_version`.

2. **Event stream** — the raw NDJSON emitted by opencode on stdout, one event
   per line, reproduced verbatim. Recognised event types include:
   - `step_start` / `step_finish` — turn boundaries
   - `text` with `part.text` — the model's assembled text output for a message
   - `tool_use` / `tool_result` — tool invocations and their outputs
   - `error` — error events emitted by opencode

   Lines that are not valid JSON (for example, truncated output or corruption
   inserted by a misbehaving model) are reproduced as-is. This is intentional:
   the forensic value comes from seeing the exact bytes opencode produced, not
   a cleaned-up interpretation of them.

3. **Footer line** (`"type":"transcript_footer"`) — outcome metrics including
   `exit_code`, `killed_for_inactivity`, `stop_reason`, `session_is_error`,
   `num_turns`, `cost_usd`, `duration_ms`, `denied_tool_names`,
   `session_id`, `end_epoch_ms`, `end_iso`.

### Transcript fidelity and limitations

The transcript captures everything opencode emits on stdout when invoked with
`--format json`. Specifically:

- **Turn-level structure** is visible: each `step_start` event marks a new
  agentic turn, so you can count turns and identify where behaviour changed.
- **Model text** is fully captured: all `text` events contain the assembled
  text of each assistant message.
- **Tool interactions** are captured at the event level: you can see which
  tools were invoked and their results.
- **Corruption is visible**: malformed, non-JSON, or truncated output is
  reproduced verbatim in the event stream section.

Limitations (inherent to the upstream opencode stdout format):

- **Per-tool-call timing** is not emitted by opencode; only step-level
  boundaries are visible.
- **Raw token stream** is not available; only the final assembled text of
  each message appears in `text` events.
- **Internal opencode state** (retry logic, caching, prompt truncation) is
  opaque — the transcript shows what opencode emitted, not its internal
  decisions.
- **Model reasoning / thinking tokens** are not surfaced in the event stream
  even when the underlying model supports them.

### Storage location

Transcript files are named `<yyyyMMdd-HHmmss>-<jobId>-<phase>[-<sessionId>].jsonl`
and written to the first of these that applies:

1. `OPENCODE_TRANSCRIPT_DIR` — set this to a persistent volume path on the
   agent container to ensure transcripts survive container restarts.
2. Next to the job's output capture file, in a `transcripts/` subdirectory.
3. `/tmp/opencode-transcripts` — the default, which is ephemeral on most
   container runtimes.

For long-running investigations, set `OPENCODE_TRANSCRIPT_DIR` to a path on a
mounted NFS volume or bind-mounted host directory. For quick ad-hoc runs on
a developer machine, the `/tmp/` default is sufficient.

### Locating transcripts for a job

Given a job ID `<id>`:

```sh
ls -lt /tmp/opencode-transcripts/*<id>*.jsonl 2>/dev/null | head -5
# or, if using a custom dir:
ls -lt "$OPENCODE_TRANSCRIPT_DIR"/*<id>*.jsonl 2>/dev/null | head -5
```

The header of any matching file contains `job_id`, `workstream_id`, `phase`,
`model`, `provider`, and `session_id` for cross-referencing with controller
logs and the FlowTree memory store.

---

## Capabilities

`OpencodeRunner.capabilities()` declares:

- `reportsCost = true` — cloud providers (`openrouter`, `anthropic`)
  report a per-request bill that the output parser surfaces; the
  per-provider `reportsCost` flag in `PROVIDER_MAP` selects whether the
  parser trusts that field. The `local` provider's cost is meaningless
  so the parser skips it for that provider.
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
- `supportedProviders = {local, openrouter, anthropic}` — sourced
  directly from `PROVIDER_MAP.keySet()` so the capability and the
  routing logic cannot drift.

These flags drive the telemetry-side `RunnerStats.costReported` /
`turnsReported` fields and the top-level `unmeasuredCostRunners` list on
the completion event. A workstream that routes any phase to opencode +
`local` should expect "cost reported by this job is not the total cost";
phases routed to `openrouter` or `anthropic` will include the upstream
cost in the report.
