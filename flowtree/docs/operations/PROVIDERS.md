# Opencode Providers

The opencode runner exposes a fixed set of **providers** that each point at
a specific OpenAI-compatible inference endpoint. The provider determines
the base URL, how the runner authenticates, and the qualified model name
form passed to the `opencode` CLI (e.g. `openrouter/qwen3-coder:exacto`).

This document covers what the providers are, how to select one, and how
to wire up the API keys for the cloud providers (OpenRouter, Anthropic)
as FlowTree workspace secrets. For the rest of the operator setup —
binary discovery, `--with-llm` quick start, env-var reference, recipe
selection — see [OPENCODE.md](OPENCODE.md).

> **Where the runner runs:** `OpencodeRunner` runs in the agent JVM on the
> agent host (the machine that launches the `opencode` subprocess), not on
> the controller. That host's environment (env vars, locally-installed
> CLI tools, network reachability) is what governs everything below.

---

## The three providers

The `OpencodeRunner.PROVIDER_MAP` (in
`flowtree/agents/src/main/java/io/flowtree/jobs/agent/OpencodeRunner.java`)
is the single source of truth for provider configuration. As of this
writing it declares:

| Provider | Base URL | Secret name | Env-var fallback | Reports cost |
|----------|----------|-------------|------------------|--------------|
| `local` | `http://localhost:8084/v1` (override via `OPENCODE_PROVIDER_URL`) | — | — | false |
| `openrouter` | `https://openrouter.ai/api/v1` | `openrouter-api-key` | `OPENROUTER_API_KEY` | true |
| `anthropic` | `https://api.anthropic.com/v1` | `anthropic-api-key` | `ANTHROPIC_API_KEY` | true |

The provider is opaque to the orchestrator: the controller validates only
that it is non-empty and forwards it to the runner. The runner is the
component that knows what to do with each value.

### API-key resolution order

For providers that require an API key (`openrouter`, `anthropic`), the
runner resolves the key in this order — first non-empty value wins:

1. **`OPENCODE_API_KEY` env var** on the agent host. Treated as a legacy
   override that pins a specific key regardless of provider; useful for
   one-off testing.
2. **Workspace secret** named in the provider's `secretName` column.
   Fetched at job-launch time over HTTP from the FlowTree controller's
   `/api/secrets/{name}?workstream_id=…` endpoint, using the
   `AR_CONTROLLER_URL` / `AR_WORKSTREAM_ID` / `AR_MANAGER_TOKEN`
   environment values the controller already places on the agent for
   `ar-secrets`. This is the recommended production path.
3. **Provider-specific env var** on the agent host (e.g.
   `OPENROUTER_API_KEY`). The local-dev fallback.
4. If none of the above resolves, the runner writes an `opencode` config
   with no `apiKey` field. Cloud providers reject the resulting requests
   with HTTP 401; local providers do not require one.

The runner never logs the API key value and never returns it from any
method.

---

## Selecting a provider on a job

The `provider` value is part of the per-phase configuration bundle
described in [PHASES.md](../architecture/PHASES.md). At submission time
it can be supplied through `workstream_submit_task`:

```text
default_phase_config={"runner": "opencode", "model": "qwen3-coder:exacto",
                      "effort": "medium", "provider": "openrouter"}
```

Or per-phase:

```text
phase_configs={"deduplication": {"runner": "opencode",
                                 "model": "claude-sonnet-4-6",
                                 "provider": "anthropic"},
               "primary":       {"runner": "claude",
                                 "model": "claude-opus-4-7"}}
```

When `runner` is `opencode` and `provider` is omitted, the runner uses
its `defaultProvider()` which is currently `"local"`. Any provider name
not in the table above produces a `400 Unknown provider` response that
also lists the available providers.

---

## Wiring OpenRouter as a workspace secret (worked example)

This is the recommended production path for routing a phase to a model
hosted on [OpenRouter](https://openrouter.ai). After this, every job
submitted under the workspace that picks `provider: openrouter` will
authenticate transparently — no env-var wrangling on the agent host.

### 1. Get an OpenRouter API key

Create one at <https://openrouter.ai/keys>. Note that OpenRouter charges
per-request; the runner advertises `reportsCost = true` for this
provider, so job-completion telemetry will include the bill.

### 2. Create the secret payload file on the controller host

Workspace secrets live as JSON files on the controller's filesystem
(see [`tools/mcp/SECRETS.md`](../../../tools/mcp/SECRETS.md) for the full
storage model). For Slack workspace `T01AB2CD3EF`:

```bash
install -m 600 /dev/null \
  /Users/Shared/flowtree/secrets/T01AB2CD3EF__openrouter-api-key.json
```

Then write the payload with an editor (don't pipe through `>` — that
ignores `umask` and risks leaving a world-readable file). The payload is
a JSON object containing the API key. Two shapes are accepted:

```json
{ "openrouter-api-key": "sk-or-v1-..." }
```

(value keyed by the secret name — the canonical form), **or**

```json
{ "value": "sk-or-v1-..." }
```

(single-key payload — the runner returns the lone value). Anything else
yields `null` and the runner falls back to the env-var path.

The controller prints a startup warning if any secret file has
permissions wider than `0600`. Fix the permissions; don't ignore the
warning.

### 3. Declare the secret in `workstreams.yaml`

Add (or extend) the `secrets:` list under the workspace entry:

```yaml
slackWorkspaces:
  - id: T01AB2CD3EF
    name: "Engineering"
    secrets:
      - name: openrouter-api-key
        file: /Users/Shared/flowtree/secrets/T01AB2CD3EF__openrouter-api-key.json
```

The `name` field MUST be exactly `openrouter-api-key` — the runner's
`PROVIDER_MAP` looks the secret up by that name. Renaming the secret
breaks the lookup silently (the runner falls through to the env-var
path), so keep this consistent with the table above.

### 4. Reload the controller

Restart the FlowTree controller. On startup it rebuilds the in-memory
secrets cache from `workstreams.yaml` and re-checks file permissions:

```text
Loaded N secret(s) across M workspace(s)
```

A missing or unreadable file is logged but does not block startup; the
secret simply won't resolve until you fix it.

### 5. Submit a job that uses the provider

Any submission under a workstream that belongs to workspace
`T01AB2CD3EF` can now route phases to OpenRouter:

```text
workstream_submit_task
  workstream_id=ws-eng-demo
  phase_configs='{"deduplication":
                  {"runner": "opencode",
                   "model": "qwen3-coder:exacto",
                   "provider": "openrouter"}}'
```

When that phase runs, the controller-side wiring (already in place for
`ar-secrets`) injects `AR_CONTROLLER_URL` / `AR_WORKSTREAM_ID` /
`AR_MANAGER_TOKEN` onto the agent process environment. `OpencodeRunner`
reads those three values from `AgentRunRequest.getEnvironment()`, issues
`GET /api/secrets/openrouter-api-key?workstream_id=ws-eng-demo` with an
HMAC bearer token, extracts the key from the response payload, and
writes it into the synthesized `opencode` config under
`provider.openrouter.options.apiKey` for the launched subprocess. The
config file is mode `0600` and is deleted after the subprocess exits.

---

## Anthropic provider

`anthropic` works identically. Replace every `openrouter` with
`anthropic` in the steps above; the secret name is `anthropic-api-key`
and the env-var fallback is `ANTHROPIC_API_KEY`. The base URL points at
Anthropic's OpenAI-compatible endpoint
(`https://api.anthropic.com/v1`).

---

## Local provider

`local` requires no API key. The runner's resolution chain skips the
secret/env steps entirely and writes a config with no `apiKey` field —
which is what llama.cpp's `llama-server` and ollama expect.

`OPENCODE_PROVIDER_URL` on the agent host overrides the compiled-in
default URL, which is the right knob when the local backend lives at
`http://mac-studio:8084/v1` or some other non-localhost address. See
[OPENCODE.md](OPENCODE.md#picking-a-backend) for backend specifics
(llama.cpp vs. ollama).

---

## Verifying the wiring

After completing the worked example above, the fastest sanity check is
to look at the controller's startup log for the `Loaded N secret(s)`
line, then submit a tiny job (e.g. a single-pass deduplication audit)
under the workspace and watch the controller log for a
`event=secret_retrieve secret=openrouter-api-key … result=OK` entry. A
`result=NOT_FOUND` line means the secret name in `workstreams.yaml`
doesn't match the configured name in `PROVIDER_MAP`; a `result=FILE_ERROR`
means the payload file is missing or unparseable JSON.

If you see no `secret_retrieve` log line at all but the job still uses
the OpenRouter base URL, the runner found the API key via the env-var
fallback (`OPENROUTER_API_KEY` or `OPENCODE_API_KEY`). That's fine for
local development but it means the workspace-secret path isn't being
exercised — re-check the steps above before declaring the wiring done.
