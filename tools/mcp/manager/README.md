# AR Manager MCP Server

Internet-facing MCP endpoint for managing FlowTree workstreams, submitting
coding tasks, triggering project workflows, and accessing agent memories.
Designed for naive clients (Claude mobile, other AI agents) that have no
repo checkout or CLAUDE.md context.

## Architecture

```
Claude Mobile / External AI
     |
     | HTTPS (TLS terminated at reverse proxy)
     |
[Reverse proxy: Tailscale Funnel / Caddy / nginx]
     |
     | HTTP (localhost)
     |
[ar-manager MCP server]  (port 8010)
     |         \          \
     |          \          \-- ar-memory HTTP (port 8020)
     |           \
     |            \-- GitHub API (workflow dispatch + Contents API)
     |
     | HTTP (port 7780)
     |
[FlowTree controller]  (FlowTreeApiEndpoint)
```

The server is **stateless** -- all workstream state lives in the controller. It
is a thin orchestration facade that:
- Calls the controller REST API for Tier 1 operations (CRUD)
- Calls the GitHub API directly for Tier 2 operations (workflow dispatch, file commits)
- Calls the ar-memory HTTP API for Tier 3 operations (memory recall, store, branch context)
- Validates bearer tokens at the HTTP transport level
- Returns self-documenting responses with `next_steps` guidance

## Tools

### Tier 1: Universal (any workstream)

| Tool | Scope | Description |
|------|-------|-------------|
| `controller_health` | read | Check controller liveness |
| `controller_update_config` | write | Update controller-wide config |
| `agent_options` | read | List agent runners, phases, models, the default runner, and connected `nodes` with the labels and busy state each reported |
| `workstream_list` | read | List all workstreams with capabilities |
| `workstream_get_status` | read | Job stats for a workstream |
| `workstream_get_job` | read | Fetch a specific job by id |
| `workstream_submit_task` | submit | Submit a coding task prompt |
| `workstream_register` | write | Register a new workstream |
| `workstream_update_config` | write | Update workstream settings |
| `phase_config_explain` | read | Explain per-phase runner/model/effort/provider resolution and dry-run validate a submission |
| `workspace_update_config` | write | Update workspace-level config (name, default channel, runner defaults) |
| `send_message` | write | Send a Slack message |
| `mailbox_peek` | read | Show a workstream's conversation without waiting or consuming it |
| `job_report_blocked` | write | Report that the current job cannot proceed; ends it `BLOCKED` |

### Reporting a block

When a coding agent genuinely cannot complete its task — a guardrail or hook
denies a change it needs, a required resource is missing, the task is ambiguous
or out of scope, or the work already exists — it calls
`job_report_blocked(category, detail)` instead of exiting silently or writing a
free-text abandonment note. The call records the block against the job bound to
the token and ends the job with a terminal `BLOCKED` status that the
enforcement rules do not retry; the `category` and `detail` are visible in
`workstream_get_job` and the job's completion event.

`category` is one of a fixed set:

| Category | Meaning |
|----------|---------|
| `guardrail` | A guardrail or hook denied an action the agent needed to take |
| `missing_resource` | A required library, model, credential, or file is absent |
| `ambiguous_task` | The request needs a clarifying decision before it can be acted on |
| `out_of_scope` | The change does not belong on this branch |
| `already_done` | The work already exists, typically on the base branch |

A `guardrail` claim is re-verified against the tool and permission denials the
session actually recorded; a claim the harness cannot corroborate is kept but
marked unverified (`blockedVerified: false`). A block is for a real
impediment, not an escape hatch from difficult but doable work.

### Starting deliberate work

Work an operator starts on purpose — as opposed to the merge-triggered
`plan-next-task` automation that maintains a fixed number of active project
branches — goes through `workstream_register` directly:

- `workstream_register(..., plan_content=...)` when the operator already knows
  what the plan document should say: it is committed to the new branch
  immediately.
- `workstream_register(..., plan_instructions=...)` when the operator wants an
  agent to write the plan from a natural-language brief: a coding job is
  submitted to draft and commit it. `plan_instructions` is also persisted on
  the workstream, in the controller's workstream metadata database rather than
  the workstreams YAML (returned by `workstream_list`, updatable via
  `workstream_update_config`) as a record of the intent that seeded the
  work — not a specification anything verifies against.

Either way, follow up with `workstream_submit_task` to send further work to
the branch once it exists. `project_create_branch` (below) dispatches the
same merge-triggered `plan-next-task` job the automation uses and is not the
recommended way to start deliberate work — it exists for the automation path,
which still accepts and forwards the `plan_title`/`plan_content` dispatch
inputs `project_create_branch` sends it.

### Tier 2: Pipeline-capable workstreams only

| Tool | Scope | Description |
|------|-------|-------------|
| `project_create_branch` | pipeline | Dispatch the merge-triggered `plan-next-task` job on demand. Prefer `workstream_register` with `plan_instructions`/`plan_content` for deliberately-started work (see above). |
| `project_verify_branch` | pipeline | Dispatch verify-completion workflow against a workstream's planning document |
| `project_commit_plan` | pipeline | Commit a plan document to a branch (defaults the path to the workstream's configured planning document); a plan under `docs/plans/` without lifecycle front matter gets a `status: proposed` header, and one with a malformed header is refused |

**Planned:** Add `github_dismiss_code_scanning_alert` — dismiss GitHub Advanced Security
code-scanning alerts by alert number (e.g., to close bot-generated scanner warnings on
resolved issues). Requires `security_events: write` permission on the PAT.

### Tier 3: GitHub

| Tool | Scope | Description |
|------|-------|-------------|
| `github_pr_find` | github | Find a PR by branch/number |
| `github_pr_reviews` | github | Get review bodies on a PR (e.g. GitHub Copilot's per-round overview) |
| `github_pr_review_comments` | github | Get review thread comments on a PR (unresolved by default; pass `include_resolved=true` for resolved threads too) |
| `github_pr_conversation` | github | Get the issue-style conversation comments on a PR |
| `github_pr_reply` | github | Reply to a PR review thread |
| `github_pr_check_status` | github | Get CI/check status for a PR head commit |
| `github_pr_labels` | github | Read a PR's labels and draft status (read-only) |
| `github_list_workflow_runs` | github | Search GitHub Actions workflow runs (by workflow/branch/event/status) |
| `github_workflow_run_status` | github | Get a workflow run's jobs and failed steps by run id |
| `github_job_logs` | github | Read a workflow job's log (tail, optionally filtered by `grep`) |
| `github_run_artifacts` | github | List a workflow run's artifacts, or read files inside one (e.g. the `crash-reports-*` dumpstreams and `hs_err_pid*.log`); read-only, archives over 1 MB are refused and at most `max_bytes` (capped at 1 MB) of member content is decompressed |
| `github_list_open_prs` | github | List open PRs for a repo |
| `github_create_pr` | github | Create a pull request |
| `github_update_pr` | github | Change an existing PR's title and/or description |
| `github_set_pr_state` | github | Close a pull request, or reopen one that was closed without merging (state only; a merged PR cannot be reopened) |
| `github_start_test_run` | github | Start a branch's CI test run by deploying it to the `test` environment through the controller's own GitHub credential; refuses a raw SHA, a pull ref, or a fork, and resolves the branch against `refs/heads` first |
| `github_rerun_failed_jobs` | github | Rerun only the failed/cancelled/timed-out jobs of an existing workflow run in place (a new attempt of the same run), through the controller's GitHub credential; refuses a run still queued or in progress or one with nothing failed. Use instead of `github_start_test_run` when a run already exists and some jobs failed |
| `github_request_copilot_review` | github | Request a Copilot automated review on a PR |
| `github_read_file` | github | Read a file from a GitHub repo at a branch/ref |
| `project_read_plan` | github | Read the planning document for a workstream |

A PR carries three distinct comment surfaces, each read by a different tool:
review bodies — the top-level text a reviewer (including GitHub Copilot's
per-round "Changes recommended" / "Needs a closer look" summary) submits
alongside a review verdict — are read by `github_pr_reviews`; inline
review-thread comments are read by `github_pr_review_comments`; and
issue-style top-level PR conversation comments are read by
`github_pr_conversation`. Copilot never posts to the conversation thread, and
once a review thread is marked resolved its comment vanishes from the
`github_pr_review_comments` default view (`include_resolved=true` brings it
back) — but a review body has no resolved state, so `github_pr_reviews`
always has the full history.

### Tier 4: Memory

| Tool | Scope | Description |
|------|-------|-------------|
| `memory_recall` | memory-read | Semantic search with optional LLM synthesis |
| `memory_namespaces` | memory-read | List namespaces with entry counts and latest-write times, newest first |
| `workstream_context` | memory-read | Get memories, commits, PR, jobs, and detected enforcement loops for a branch (a workstream is optional — `repo_url` + `branch` is enough; only the jobs stream needs one) |
| `memory_store` | memory-write | Store a memory, optionally reformulated |
| `consult` | consult | Documentation-grounded Q&A: retrieves the project docs and the relevant memories, then synthesises an answer (or returns retrieval-only when no model is reachable, marked `degraded: true`). Replaces the retired `ar-consultant` `consult` tool |
| `inference_health` | read | Diagnose the inference backend behind `consult`/`memory_recall` on demand: the selected backend and why, a classified availability probe, and when synthesis last succeeded |

Memory tools resolve `repo_url` and `branch` from a `workstream_id` when not
provided directly. LLM synthesis (via llama.cpp) is attempted for `memory_recall`
summaries when a backend is available.

`memory_store` and the Consultant's `remember` write to the same corpus and
apply the same policy: a memory can carry two versions of its text — what the
agent wrote, and a rewrite ("reformulation") produced by a small local model —
and when no model is reachable the agent's text is stored unreformulated rather
than refused. Reformulation is off unless the repository enables it (see
[Per-Repository Configuration](#per-repository-configuration)) or the caller
passes `reformulate=true`. Reformulation is a **beta feature**, so `memory_recall` and
`workstream_context` return the original text and mark it with `text_source`.
Pass `reformulated=true` to see the rewrite instead — the response then also
carries the original for comparison plus a `notice` about the feature's state.
See [Memory Text: Original vs Reformulated](#memory-text-original-vs-reformulated)
for the full contract.

### Enforcement loops in `workstream_context`

When a branch goes round an enforcement loop — an agent edits, a rule rejects, a
remediation job runs, repeat — the raw streams show it as a long run of
near-identical jobs and memories that buries the substantive notes. `workstream_context`
detects two shapes and collapses each into a single `loop` entry carrying a
`count` and a time `span` (`from`/`to`):

- **job loops** — consecutive jobs where the same enforcement phase
  (`description`) produced the same verdict (`status`);
- **memory loops** — runs of near-identical memories written within one job
  (bounded by the jobs timeline so a run never spans two jobs).

The response always includes a `loops` array (empty when none are found). By
default the runs are also collapsed in the `memories` and `jobs` streams to one
representative entry each, annotated with a `loop` field. Pass
`collapse_loops=false` to keep every individual entry listed while still
receiving the `loops` summary. Memory near-identity here is judged textually
(`difflib`), because the manager has no embedder; the minimum run length (3) and
similarity floor live as named constants in `loop_summary.py`.

### Near-duplicate memory merging (ar-memory, store time)

The ar-memory server merges near-duplicate memories **at store time** rather
than through a periodic compaction pass. A looping branch writes the same note
serially (each new "rule X failed again…" near-identical to the last), so
collapsing on arrival keeps the index free of duplicates with bounded per-store
work — one extra nearest-neighbour search, reusing the embedding already
computed for indexing — and no scheduler or scan window during which duplicates
pollute recall. The trade-off is that only already-stored notes are considered
(two truly simultaneous writes cannot merge), which is exactly the serial loop
this targets.

A new memory whose cosine similarity to an existing note on the **same
repository and branch** meets `NEAR_DUPLICATE_SIMILARITY_THRESHOLD` (a named
constant in `store.py`, default `0.97`, override with
`AR_MEMORY_NEAR_DUPLICATE_THRESHOLD`) replaces that note: the newest entry is
kept and its `merged_count` records how many source notes it now stands for.
Distinct memories, and identical text on another branch or repository, are never
merged. Set `AR_MEMORY_MERGE_NEAR_DUPLICATES=0` to disable the behaviour.

## Memory Text: Original vs Reformulated

ar-manager's `memory_store` can rewrite a note before storing it so the wording
matches project terminology (enable it per repository with `reformulateOnStore`,
or per call with `reformulate=true`). Both versions are kept: the rewrite becomes
the entry's `content` (that is what gets embedded and ranked) and the text the
agent actually wrote is preserved alongside it. When no inference backend is
reachable the note is stored unreformulated rather than refused, and the response
carries `degraded: true` — a missing model never costs you the memory.

The reformulation itself lives on `InferenceBackend.reformulate` in
`tools/mcp/common/inference.py`, and the dual encoding on
`MemoryHTTPClient.store_dual`, so every writer shares one implementation.

**Reformulation is a beta feature.** The rewrite runs on a small local model and
can drop detail, collapse specifics, or state things the author did not. So every
retrieval path — `consult` and ar-manager's `memory_recall` and
`workstream_context` — returns the **original text by default**. Each returned memory carries a `text_source` field (`original` or
`reformulated`) saying which version it holds.

To read the rewrite instead — when evaluating reformulation quality, for example —
pass `reformulated=true`. The response then contains the rewrite as `content`, the
author's text as `original` for comparison, and a `notice` repeating the beta
warning. Setting `AR_MEMORY_REFORMULATED=1` makes rewrites the default for a whole
session, including the surfaces that expose no parameter of their own (`consult`,
`workstream_context`).

Retrieval still *ranks* on the rewrite, because that is what was embedded;
only the text shown to the caller changes. Memories stored by FlowTree jobs never
went through reformulation and are unaffected either way.

The encoding and the presentation rules live in
[`tools/mcp/common/memory_text.py`](../common/memory_text.py), shared by every
retrieval path.

## Optional Enforcement Phases

Some post-primary enforcement phases are controlled per job through a
`workstream_submit_task` flag. The submit response echoes the effective setting
so the caller can confirm it.

| Flag | Default | Controller field | Response echo | Phase it controls |
|------|---------|------------------|---------------|-------------------|
| `organizational_placement_enabled` | off | `enforceOrganizationalPlacement` | `organizationalPlacementEnabled` | Organizational placement review of new files. Forwarded only when `True`; an omitted or `False` flag leaves it off |
| `object_oriented_review_enabled` | **on** | `objectOrientedReviewEnabled` | `objectOrientedReviewEnabled` | `ObjectOrientedDesignRule`: a single design-review session over the changed production classes a deterministic risk gate flags. Always forwarded, so `False` switches it off for a job. Takes effect only while `review_enabled` is also `True` |

Both are job-level settings; there is no workstream or workspace default for them.
Organizational placement stays off unless a submission requests it. The
object-oriented design review is on by default, and the risk gate keeps its cost
low by spending a session only on classes whose design it flags; a submission can
send `object_oriented_review_enabled=False` to switch it off.

**On for initial work, off for CI follow-ups.** The review belongs to the job that
creates a branch's work — QA rounds, planning and task jobs, and jobs submitted by
hand — which leave the field unset and get the on-by-default. The follow-up jobs CI
submits in a loop turn it off: `auto-review` (with its code-policy and quality-gate
routes), `auto-resolve-python` and `auto-resolve` can each fire many times on one
branch, so each sends `objectOrientedReviewEnabled: false` and the review does not
ride along on every pass. Those submitters set `OBJECT_ORIENTED_REVIEW_ENABLED=false`
on the trusted submit step; `tools/ci/submit-agent-job.sh` sends the field only when
that knob is set, so every other path inherits the default.

## Per-Phase Configuration and the Fallback

`workstream_submit_task` sets the runner, model, effort and provider a job uses,
per phase, through two pairs of parameters:

- **`default_phase_config` / `phase_configs`** — the per-job *override*. A
  value here wins over the target workstream's own configuration. Use it when
  the submission must pin a model regardless of how the workstream is set up.
- **`fallback_default_phase_config` / `fallback_phase_configs`** — the per-job
  *fallback*. It applies ONLY to phases the workstream leaves unset. A phase
  whose workstream-level config is set, or every phase when the workstream has
  its own `defaultPhaseConfig`, keeps the workstream's choice and ignores the
  fallback. Use it to run a job on a cheaper setup (for example the opencode
  runner against an inexpensive OpenRouter model) without overriding a
  workstream an owner configured on purpose.

Each is a JSON object with optional `runner` / `model` / `effort` / `provider`
keys (the `*_phase_configs` variants map phase wire names to such objects); the
same client-side validation applies to both pairs (`phase_config.py`). The
controller resolves the precedence ladder — per-job override, workstream,
**fallback**, workspace default, controller default — in
`io.flowtree.submission.PhaseConfigResolver`. A fallback entry applies as a
whole, not field-by-field: when it applies it overlays onto the workspace and
controller defaults and the resulting runner/provider/model combination is
validated at submission, so a fallback that completes into an invalid
combination is rejected. The submit response echoes the resolved
`defaultPhaseConfig` / `phaseConfigs`, and — when a fallback was supplied —
a `fallbackResolution` object reporting, per phase, the resolved runner/model/
effort/provider and whether the fallback was used or ignored and why.

A fallback naming `provider=openrouter` needs the workspace's
`openrouter-api-key` secret (see `flowtree/docs/operations/PROVIDERS.md`).
Submission validates only the runner/model/provider combination, not the
presence of the secret: the opencode runner resolves the key when the job
starts, so a missing secret surfaces as a runtime job failure, not a
submission rejection.

CI uses the fallback through the `AR_CI_FALLBACK_PHASE_CONFIG` repository
variable, which `tools/ci/submit-agent-job.sh` forwards as `fallbackPhaseConfig`
for the auto-review, code-policy, quality-gate, auto-resolve and
auto-resolve-python submissions. Unset → nothing is sent and resolution is
byte-for-byte what it is today.

### Explaining resolution — `phase_config_explain`

`phase_config_explain` is a read-only diagnostic that answers "what runner,
model, effort and provider will each phase use, and where did each value come
from?" for a workstream. For every phase it reports each field's resolved
`value` and its `source` — one of `job per-phase`, `job default`,
`workstream per-phase`, `workstream default`, `fallback per-phase`,
`fallback default`, `workspace per-phase`, `workspace default`,
`controller default`, or `none` — so an unexpected model or a per-phase
override that silently did nothing is traceable without reading the raw
workstream and workspace configuration by hand.

The optional `default_phase_config` / `phase_configs` / `fallback_*`
arguments preview a *hypothetical* submission: they are resolved exactly as
`workstream_submit_task` would resolve them, on top of the workstream and
workspace configuration, without submitting a job. Resolution runs the same
validation the submit path does, so an unsupported model-for-runner or a
provider outside the runner's advertised `supportedProviders` is returned as an
`error` here — the tool doubles as a dry-run validator. The controller also
checks the request shape strictly (malformed JSON, an unknown phase or key, a
non-string value), and answers `404` for an unknown workstream and `400` for
an invalid configuration, with the `ok:false` body either way. When a resolved phase sets an `effort` for a runner that
cannot honour it, the response also carries a `warnings` entry (the job still
runs; the effort is ignored).

This is a dedicated tool rather than a field on `workstream_get_status`
because that tool reports operational analytics (job counts, cost, turns),
whereas `phase_config_explain` resolves configuration and accepts hypothetical
per-job overrides to preview a submission. The controller serves it at
`POST /api/workstreams/{id}/phase-config`
(`io.flowtree.api.PhaseConfigQueryHandler`).

## Test Execution Limits

`workstream_submit_task` mechanically rejects submissions that would have an agent or
job submitter run a full/whole test suite, a module's whole suite, or a CI shard —
there is no bypass flag, unlike `allow_commit_language`. Three independent checks run
before the submission ever reaches the controller:

- **`post_completion_command` / `command`** — rejected when it runs a Maven
  test-executing phase (`test`/`integration-test`/`verify`/`install`/`package`/`deploy`)
  without a bounded `-Dtest` selection — at most 5 test classes and at most 40
  named `Class#method` tests; a bare class or a few classes is fine, but no
  selector at all, more than those caps, a Surefire wildcard (`*`, `?`), the `+`
  method-list separator, `!` negation, a `%regex[...]` pattern, or an unresolvable
  `$VAR` is rejected — references `AR_TEST_GROUP`/`AR_TEST_GROUPS`, or runs pytest
  against a directory, a whole file, or more than one node id instead of exactly
  one explicit node id. A `post_completion_command` runs unattended, so it is held
  to one stricter rule on top of that: every `-Dtest` entry must name a
  `Class#method`, not a bare class.
- **`post_completion_timeout_seconds`** — rejected above 2400 (40 minutes).
- **`prompt`** — rejected when it instructs the agent, in English, to run a
  full/whole/entire suite, a module's tests, a shard, `mvn test` without a
  bounded `-Dtest` selection, or `AR_TEST_GROUP`.

The command validator is implemented in [`execution_limits.py`](execution_limits.py)
(see its module docstring for the incident that made this a hard requirement) and
the prompt linter in [`prompt_test_lint.py`](prompt_test_lint.py). The controller
(`FlowTreeApiEndpoint#handleSubmit`) applies the same command validation independently
via `io.flowtree.jobs.PostCompletionCommandValidator`, so a direct API call cannot
bypass what this server rejects, and clamps `postCompletionTimeoutSeconds` to the same
2400s ceiling. The `mcp__ar-test-runner__start_test_run` tool enforces the mirror image
of this rule for interactive agent use: it rejects `test_group`/`test_groups` (CI-shard
reproduction) outright and caps `timeout_minutes` at 40.

## Letting a Job Edit Agent Tooling (`.claude/hooks/`)

Two operator-only `workstream_submit_task` flags govern two separate gates, and a
job that must edit `.claude/hooks/` or `.claude/settings.json` needs both of them
lifted:

| Flag | Gate it lifts | Where it acts |
|------|---------------|---------------|
| `sensitive_file_protection_enabled=False` | The harness's test-file and CI-file staging locks, and the controller-signed `Sensitive-File-Bypass` commit trailer | Harness side only (`GitCommitHandler`, `FileStager`); never reaches the agent subprocess |
| `skip_agent_permission_prompts=True` | The agent runtime's own per-call refusal of writes under `.claude/`, environment and credential files ("... which is a sensitive file") | The agent subprocess: Claude Code is launched with `--permission-mode bypassPermissions` (`ClaudeCodeRunner.buildCommandLine`) |

`sensitive_file_protection_enabled=False` on its own does **not** let the agent
write under `.claude/hooks/`: every Edit, Write and Bash write there is still
refused. `skip_agent_permission_prompts=True` is also required, unless the
workstream's agent-permission-bypass branch prefixes already match the job's
target branch (`Workstream.applyCapabilities`). The pipeline's CI-file lock
(`check-ci-file-lock.sh`) covers only `.github/workflows/`, `.github/actions/` and
`tools/ci/`, never `.claude/hooks/`, so it is not what stands in the way of such an
edit: the permission-prompt bypass is the flag that decides whether the edit can be
made at all. Neither flag can be set by an in-flight coding agent.

## Inference Backends

`consult`, `memory_recall` summaries and memory reformulation run on one inference backend
(`tools/mcp/common/inference.py`), selected by auto-detection or the
`AR_CONSULTANT_BACKEND` environment variable (the name predates the move of
`consult` into ar-manager). There are five.

### Auto-Detection Order

When `AR_CONSULTANT_BACKEND` is unset or `"auto"` (the default), ar-manager tries backends in this order:

1. **llama.cpp** -- checks `localhost:8084`, then `mac-studio:8084` as fallback
2. **Ollama** -- checks `localhost:11434`
3. **MLX** -- checks if `mlx-lm` is importable (Apple Silicon only)
4. **OpenRouter** -- only when an API key is configured; paid per token, so it is
   tried after every local model and auto-detection moves back to a local model
   as soon as one comes up
5. **Degraded** -- no model; retrieval-only responses marked `degraded: true`

Detection is re-run whenever the current choice goes stale, so **no restart is
needed** when a model comes up or goes away. Start `llama-server` mid-session and
the next consult synthesizes; kill it and the next consult degrades to retrieval.
Health is cached for `AR_INFERENCE_HEALTH_TTL` seconds (default 30) so a status
check is not a network round trip; set it to `0` in tests to probe every time.

Because health is re-probed rather than fixed at startup, `inference_health`
always agrees with what the other tools will actually do.

### OpenRouter (hosted)

For when no lab machine can be spared to run a model. Requests go to
OpenRouter's OpenAI-compatible API and are billed per token.

```bash
export AR_CONSULTANT_BACKEND=openrouter          # or auto, to prefer a local model
export AR_CONSULTANT_OPENROUTER_API_KEY_FILE=/path/to/consultant__openrouter-api-key.json
export AR_CONSULTANT_OPENROUTER_MODEL=minimax/minimax-m3   # the default
```

The key file follows the workspace-secret convention
(`flowtree/docs/operations/PROVIDERS.md`): a JSON object keyed by the secret
name, created with mode `0600`.

```json
{ "openrouter-api-key": "sk-or-v1-..." }
```

In the controller deployment it is ar-manager's own key, kept apart from
the OpenRouter keys the opencode agents use:
`/Users/Shared/flowtree/secrets/consultant__openrouter-api-key.json` on the host,
mounted at `/secrets/consultant__openrouter-api-key.json` (see
`flowtree/runtime/controller/docker-compose.yml`).

Set `AR_CONSULTANT_BACKEND=openrouter` in the controller stack's **operator
settings file** — `/Users/Shared/flowtree/secrets/controller.env` on the host
(overridable with the `FLOWTREE_CONTROLLER_ENV` repository variable), beside the
other deploy secrets. Do **not** put it in the compose project's
`flowtree/runtime/controller/.env`: that file is gitignored and a deploy's
checkout clean deletes it, so a setting kept only there resets to the compose
default (`llamacpp`) on the next deploy. `rebuild.sh` reads the operator
settings file and carries it into the compose `.env`, and the deploy workflow
prints the effective backend in its job summary so a reset is visible. See
`flowtree/runtime/README.md` ("Controller operator settings").

Two request defaults are deliberate:

- **Reasoning is off** (`reasoning.enabled = false`). OpenRouter counts reasoning
  tokens against `max_tokens`, so a thinking model can spend a consult's whole
  budget reasoning and return an empty (but billed) answer. Set
  `AR_CONSULTANT_OPENROUTER_REASONING` to an effort level (`high`/`medium`/`low`,
  sent as `reasoning.effort`) to turn it on, or to an empty string to leave the
  model's own default. (`none`, the default, is a disable sentinel — not an
  OpenRouter effort level.)
- **Providers that retain prompts are excluded** (`provider.data_collection =
  "deny"`). Prompts carry the memory corpus. If a model has no compliant
  provider, requests fail; set `AR_CONSULTANT_OPENROUTER_DATA_COLLECTION=allow`
  only if that trade is acceptable.

The health probe calls `GET /key`, which costs nothing, and reports the backend
down when the key has no credit left, so auto-detection moves on instead of
failing every request with HTTP 402. The provider's error message (for example
"Insufficient credits") is returned as the degraded consult's reason.

### llama.cpp (Recommended)

The recommended backend for most setups. Runs a llama.cpp server on any machine and connects via the OpenAI-compatible HTTP API.

#### Starting the Server

```bash
llama-server \
    -m /path/to/model.gguf \
    --host 0.0.0.0 \
    --port 8084 \
    -ngl 99 \
    -c 8192
```

Key flags:
- `--host 0.0.0.0` -- listen on all interfaces (required for remote access)
- `--port 8084` -- the default port ar-manager checks
- `-ngl 99` -- offload all layers to GPU
- `-c 8192` -- context window size

Recommended models (Q4_K_M or Q5_K_M quantization):
- `qwen2.5-coder-32b-instruct` -- best quality for code understanding
- `qwen2.5-coder-14b-instruct` -- good balance of speed and quality

#### Remote Setup (e.g., Mac Studio)

When running llama-server on a remote machine (e.g., `mac-studio`) and ar-manager elsewhere:

1. Start llama-server on the remote host with `--host 0.0.0.0`
2. ar-manager auto-detects `mac-studio:8084` as a fallback when `localhost:8084` is unreachable

To explicitly set the URL:
```bash
export AR_CONSULTANT_LLAMACPP_URL=http://mac-studio:8084
```

#### macOS Firewall (Common Issue)

**If the server is running but unreachable from another machine**, the macOS Application Firewall is almost certainly blocking it. Symptoms:

- `nc -z mac-studio 8084` succeeds (TCP connects)
- `curl http://mac-studio:8084/health` hangs and eventually times out
- `curl http://localhost:8084/health` works on the server machine itself

The macOS firewall accepts the TCP connection at the kernel level (so `nc` succeeds) but blocks the application from receiving data (so HTTP requests hang indefinitely with no response).

**Fix:**

```bash
# Check firewall state
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate

# Allow llama-server through the firewall
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add $(which llama-server)
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --unblockapp $(which llama-server)

# Restart llama-server after changing firewall rules
```

This applies to any llama.cpp binary name (`llama-server`, `llama-server-metal`, etc.).

#### Container Setup (Docker/Linux)

When ar-manager runs inside a container and llama-server runs on the Docker host:

```bash
export AR_CONSULTANT_LLAMACPP_URL=http://host.docker.internal:8084
```

This is the default when a container is detected (via `/.dockerenv`).

### Ollama

Uses the Ollama HTTP API. Ollama must be running and the model must be pulled.

#### Starting the Server

```bash
# Start Ollama (if not already running as a service)
ollama serve

# Pull the default model
ollama pull qwen2.5-coder:32b-instruct-q4_K_M
```

#### Configuration

```bash
# Custom model
export AR_CONSULTANT_MODEL=qwen2.5-coder:14b-instruct-q4_K_M

# Custom Ollama URL (default: http://localhost:11434)
export AR_CONSULTANT_OLLAMA_URL=http://mac-studio:11434
```

### MLX (Apple Silicon)

Native inference on Apple Silicon using MLX-LM. No separate server needed, but the model loads into the ar-manager process memory.

#### Installation

```bash
pip install mlx mlx-lm
```

#### Configuration

```bash
# Custom model (default: mlx-community/Qwen2.5-Coder-32B-Instruct-4bit)
export AR_CONSULTANT_MLX_MODEL=mlx-community/Qwen2.5-Coder-14B-Instruct-4bit
```

The model is downloaded on first use from Hugging Face.

### Passthrough (No Model)

Skips LLM synthesis entirely. Every tool returns its retrieval results marked
`degraded: true`. Useful for exercising the retrieval pipeline or when no GPU is
available.

```bash
export AR_CONSULTANT_BACKEND=passthrough
```

Note that pinning any backend by name disables auto-detection, so a pinned
backend that is unreachable degrades rather than falling back to another one.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_CONTROLLER_URL` | `http://localhost:7780` | FlowTree controller base URL |
| `AR_MEMORY_URL` | (auto-discovered) | ar-memory HTTP server URL |
| `AR_MEMORY_REFORMULATED` | unset (off) | Show reformulated memory text by default instead of the original (beta) |
| `AR_MANAGER_GITHUB_TOKEN` | (none) | GitHub PAT for Tier 2 ops (falls back to `GITHUB_TOKEN`) |
| `AR_MANAGER_TOKEN_FILE` | `~/.config/ar/manager-tokens.json` | Bearer token config file |
| `AR_MANAGER_TOKENS` | (none) | JSON string of token config (overrides file) |
| `AR_MANAGER_REPO_CONFIG_FILE` | `/config/repo-config.json` | Per-repository settings (see below) |
| `AR_CONSULTANT_BACKEND` | `auto` (library); the controller compose deployment pins `llamacpp` | LLM backend for memory synthesis: `llamacpp`, `ollama`, `mlx`, `openrouter`, `passthrough`, or `auto` |
| `AR_CONSULTANT_LLAMACPP_URL` | (auto-discovered) | llama.cpp server URL |
| `AR_CONSULTANT_OPENROUTER_API_KEY_FILE` | `/secrets/consultant__openrouter-api-key.json` (compose) | ar-manager's OpenRouter secret file; other `AR_CONSULTANT_OPENROUTER_*` settings are in [OpenRouter (hosted)](#openrouter-hosted) |
| `AR_CONSULTANT_OLLAMA_URL` | `http://localhost:11434` | Ollama server URL |
| `AR_CONSULTANT_MODEL` | `qwen2.5-coder:32b-instruct-q4_K_M` | Ollama model name |
| `AR_CONSULTANT_MLX_MODEL` | `mlx-community/Qwen2.5-Coder-32B-Instruct-4bit` | MLX model path |
| `AR_CONSULTANT_OPENROUTER_API_KEY` | (none) | OpenRouter API key, if not using the file |
| `AR_CONSULTANT_OPENROUTER_MODEL` | `minimax/minimax-m3` | OpenRouter model slug |
| `AR_CONSULTANT_OPENROUTER_REASONING` | `none` | `none` disables reasoning (`reasoning.enabled=false`); `high`/`medium`/`low` set `reasoning.effort`; empty string sends no reasoning field |
| `AR_CONSULTANT_OPENROUTER_DATA_COLLECTION` | `deny` | `provider.data_collection`: `deny` or `allow`; a blank or unrecognised value falls back to `deny` so the privacy restriction is never silently dropped |
| `AR_CONSULTANT_OPENROUTER_URL` | `https://openrouter.ai/api/v1` | OpenRouter API base URL |
| `AR_INFERENCE_HEALTH_TTL` | `30` | Seconds a backend health probe is trusted before re-probing; `0` probes every time |
| `AR_SNAPSHOT_INDEX` | `/app/snapshot/snapshot-index.json` (image) | The snapshot index consult checks names and extracts facts from; unset in a checkout, which disables both |
| `MCP_TRANSPORT` | `http` | Transport: `http` or `sse`. `stdio` is rejected (auth-only HTTP server) |
| `MCP_PORT` | `8010` | Port for http/sse transport |

## Documentation Grounding

`memory_recall` grounds its summary in the project documentation as well as the
retrieved memories, and returns the documents it consulted as `doc_references`.
The summarizer is asked to flag places where the documentation contradicts a
memory — memories go stale, and that contradiction is usually the most useful
thing in the answer.

The corpus is baked into the image (`AR_DOCS_DIR`, set by the Dockerfile) because
a container has no checkout to read. A multi-stage build copies the directories
that can contain documentation and prunes everything that is not `*.md` or
`*.html`, so the Java source tree stays out of the image.

Both the corpus and the inference backend are optional. Without a corpus the
summary is memory-only and `doc_references` is omitted; without a model the
memories are returned unsummarized. Neither ever costs you the memories.

### Diagnosing a degraded result

When synthesis is lost, `consult` and `memory_recall` set `degraded: true` and
carry a structured `cause` so the failure is actionable rather than a bare
`<urlopen error ...>` string. The `cause` names:

- `backend` and `backend_selection` — the selected backend and *why* it is the
  one in use: an explicit `AR_CONSULTANT_BACKEND` value, or which candidate
  auto-detection settled on and which it skipped.
- `endpoint` — the `scheme://host` it contacted. Never a path (which for some
  providers bears a token) and never a credential.
- `classification` — one of `not_configured`, `credential_missing`,
  `credential_rejected`, `quota_exhausted`, `connection_refused`, `dns_failure`,
  `timeout`, `malformed_response`, `unreachable`. A refused TCP connection
  (`connection_refused`) is a down local server, **not** a rejected key — a key
  problem shows as `credential_rejected`/`quota_exhausted` with an HTTP status.
- `http_status` and `detail` — the provider's status and message when there was
  one (e.g. HTTP 402 "Insufficient credits").
- `operator_hint` — one sentence naming the environment variable or secret file
  that governs the fix (never a secret's value).

The degraded result also carries `agent_action_required`: an explicit
instruction to tell the person you are working for that synthesis is
unavailable, quoting the classified reason, rather than proceeding as though an
answer were produced.

`inference_health` reports the same diagnosis on demand without issuing a
synthesis request — the configured backend, a freshly classified availability
probe (with a per-candidate breakdown under `auto`), and
`last_synthesis_success` (when a synthesis last succeeded in this process). It
is the read-only sibling of `controller_health` for the inference dependency.

When running from a source checkout, `AR_DOCS_DIR` can be left unset —
`DocsRetriever` falls back to the repository layout relative to its own location.

The retriever indexes every `*.md` and `*.html` under the corpus root except
the entries in `INDEX_EXCLUSIONS` (`docs/plans/`, the root `README.md` and stray
working files, the consult evaluation's own reports). A packaging test fails
when a document in the repository is neither copied into the image and indexed
nor excluded on purpose. Each retrieved chunk carries its line range and the
heading trail above it, and a chunk that falls in a short subsection is the
whole subsection.

## Consult: Evidence, Tiers and Citations

`consult` (`consult_engine.py`) answers from a single evidence list. Every item
has an ID the model cites and a tier that says what it may supply:

| ID | Kind | Tier | May supply |
|----|------|------|------------|
| `M1` | curated concept-map entry (`docs/internals/consult-concept-map.md`), matched on the question, keywords and context; presented first | M | present fact: the type that already represents the concept and how to extend it; carried into follow-ups with its concept name and canonical types |
| `D1` | documentation chunk | A | present fact for the snapshot |
| `I1` | snapshot-index fact (a script's header or exit codes, a literal constant) | A | present fact for the snapshot |
| `N1` | note, current: on the target branch or merged into it, or on the caller's own branch, within 45 days | B | a fact the documentation lacks; the answer then opens with a fixed sentence saying it rests on notes |
| `N1` | note, branch-local: on another open or unmerged branch | B′ | a fact qualified by its branch only |
| `N1` | note, historical: older, closed unmerged, or of unknown branch state | C | history and rationale (`history` field) |
| `N1` | note, stale: names a type or path the snapshot no longer contains | C✗ | history only; the absent names are listed |

The target branch is the caller's workstream `baseBranch` (default `master`).
A note's branch state comes from its branch's most recent pull request, looked
up only when the caller's token holds `github` and its workspace may see the
note's organization; otherwise it is `unknown` and `degraded_reasons` lists
`branch_state`. Notes are searched in every semantic namespace (at most six,
reported as `memory_namespaces_capped` when cut), and a scope that cannot be
resolved stops note retrieval (`memory_scope`) rather than widening it.

The model cites IDs, never file names; the server expands `[D1]` to
`[D1: path:start-end]`. The answer basis is recomputed from the IDs actually
cited, whatever the model's `BASIS:` line says. A deterministic verifier then
checks each sentence against what it cites — every identifier and path must
appear in a cited item, every number and literal verbatim, a present-tense
claim may not rest only on historical notes, and a branch-local claim must name
its branch. An uncited specific found in no evidence item at all is flagged
too. A sentence making a polarity or exclusivity claim ("only", "never",
"does not", "always", "one X per Y") must be echoed by a cited passage that
makes a claim of the same kind about the same things; one that is not — the
shape of a confident "it does not do X, only Y" that no evidence says — is
flagged `polarity`. When any sentence fails, the model is asked once to revise
its answer, from the same evidence, with each failing sentence and its reason in
front of it (`InferenceBackend.revise`). The revision is kept only if it has
fewer warnings, does not answer "Not documented." where the draft answered,
leaves a non-empty answer body, keeps every `BASIS:`/`CONFLICTS:` trailer
line the draft had (dropping just one is rejected), and leaves the draft's
reported conflicts unchanged (the retry repairs citations, so it may not drop,
add or rewrite a `CONFLICTS:` entry that would otherwise alter the published
conflict metadata), does not raise the count of uncited claims (a
grounded-but-uncited sentence is not a warning, so fixing a flagged citation by
dropping a marker instead of correcting it must not be rewarded with a lower
warning count), and leaves every sentence the verifier did not flag unchanged
(the model is shown only the flagged sentences and told to keep the rest as
they are, so a revision may not rewrite, drop, reorder or duplicate an
unflagged, correctly cited sentence — those must survive in the draft's order
and count — nor add a sentence beyond one in place of each flagged sentence,
so a new claim appended or inserted between unflagged sentences keeps the
draft, while removing a flagged sentence is accepted);
`citation_retry` in the response records the warning counts before and after
and which answer was `kept`. Sentences that still fail are marked
`[unsupported]` in the answer and listed in `citation_warnings`. A question naming a file the snapshot does not contain is
answered directly with `nonexistent: true`.

The snapshot index is built in the Dockerfile's `docs` stage before the prune
(`tools/mcp/common/snapshot_index.py`) and records the commit it was built from,
passed as the `CORPUS_COMMIT` build argument (`rebuild.sh` exports it; a build
that omits it records `unknown`). `consult` returns it as `corpus`. It also
records when each file last changed in the last 90 days: `rebuild.sh` writes
that `git log` to `tools/mcp/manager/snapshot-path-dates.txt` in the build
context, because the context has no `.git`. A note naming a file (one the name
resolves to unambiguously) that changed after the note was written is tier C,
"names files changed since it was written". It may describe the file as it was,
so it supplies history, not present fact. A build without that file records no
dates, and then no note is demoted this way.

Every consult returns a `conversation_id`, and passing it back continues the
consult: a follow-up keeps the evidence IDs of earlier turns and sees their
questions and condensed answers. A conversation is bound to the token identity
that started it — a job token's workstream and job, a static token's label —
and to the first turn's repository. Its id is long and random, and holding it is
the capability to continue it, so several clients sharing one static token keep
separate conversations; an id presented by another identity is refused exactly
like an unknown one. Conversations are in-process, expire after 30 idle minutes
or four hours, allow 12 turns, and persist nothing.

`eval/run_consult_eval.py` measures all of this against a version-controlled
case set (`eval/consult_cases.json`): `replay` grades frozen deployed
responses, `scripted` runs the live pipeline with canned model answers, and
`local` runs it against a real backend.

CI runs the eval tests (`eval/test_*.py`) in their own `python-evals` job, on
any change to the docs or sources they read; `eval/` is not a package, so the
manager's own `unittest discover` does not reach them. A scripted answer cites
evidence by content (`[D:<path>@"<phrase>"]`), never by line number.

## Per-Repository Configuration

One ar-manager process serves every repository, so settings that differ by
repository cannot come from environment variables. They live in a JSON file
mounted alongside `manager-tokens.json`:

```json
{
  "default": {
    "reformulateOnStore": false,
    "preferReformulatedOnRead": false
  },
  "almostrealism/common": {
    "reformulateOnStore": true
  }
}
```

Keys are `owner/repo`, matched case-insensitively; the `git@`, `https://`, and
`.git`-suffixed spellings of a repository all resolve to the same entry. A
repository with no entry of its own falls back to `default`, then to the
process-wide `AR_MEMORY_REFORMULATED`.

| Setting | Applies to | Effect |
|---------|-----------|--------|
| `reformulateOnStore` | `memory_store` | Rewrite a note to match project terminology before storing, keeping both versions. Callers can override per call with the `reformulate` argument. |
| `preferReformulatedOnRead` | `memory_recall`, `workstream_context` | Return the rewrite instead of the author's text by default. Callers can override per call with `reformulated`. |

Reformulation degrades safely: when no inference backend is reachable the
author's original text is stored unreformulated and the response carries
`degraded: true` with a note. A memory is never lost to a missing model.

The file is re-read on a short TTL, so an edit takes effect without a restart.
A missing or malformed file is not an error — every setting falls back.

## Authentication

### Token file format

```json
{
  "tokens": [
    {
      "value": "armt_...",
      "scopes": ["read", "write", "submit", "pipeline", "github", "memory-read", "memory-write"],
      "label": "Claude mobile"
    },
    {
      "value": "armt_...",
      "scopes": ["read", "memory-read"],
      "label": "Monitoring dashboard",
      "workspaceScopes": ["T0123ABC"]
    }
  ]
}
```

Generate a token with `tools/mcp/manager/generate-token.sh`. Default scopes
are `read`, `write`, `submit`, `pipeline`, `github`, `memory-read`, and
`memory-write`. The optional `workspaceScopes` field restricts a token to
specific Slack workspace IDs; omit it (or pass an empty list) for an
unscoped/superadmin token.

**Scopes:**
- `read` -- list workstreams, get stats, get jobs, health check
- `write` -- register/update workstreams, update controller config, send messages
- `submit` -- submit a coding task prompt to a workstream
- `pipeline` -- trigger GitHub workflows (create branch / verify), commit plan files
- `github` -- read PR conversations and review comments, list/create PRs, reply
  on review threads, request Copilot review, read repository files, read planning
  documents
- `memory-read` -- recall memories, fetch workstream branch context
- `memory-write` -- store new memories from an external client

### Security

- Timing-safe token comparison (`hmac.compare_digest`)
- Per-client rate limiting (60 req/min sliding window)
- Input length validation on all parameters
- Path traversal protection on `project_commit_plan`
- Audit logging with token labels
- Auth-exempt `/_health` endpoint for Docker healthchecks

### Interactive access from a repo checkout (personal tokens)

A developer running Claude Code against the `common` repo reaches ar-manager over
HTTP with a **personal bearer token** — the same static-token mechanism Claude
mobile uses, but scoped to the human rather than a job. The repo's `.mcp.json`
does **not** define `ar-manager`; you configure it once in your *user-scoped*
Claude config so the token (a secret) never lands in a committed file.

1. **Mint a personal token on the ar-manager host** (where the token file lives):

   ```bash
   ./tools/mcp/manager/generate-token.sh "your-name-laptop"
   ```

   This appends an entry to `manager-tokens.json` and prints the token value.
   Restart ar-manager so it loads the new token (tokens are read at startup).

2. **Register ar-manager in your user-scoped Claude config** (not in the repo),
   with the token value in the header:

   ```bash
   claude mcp add --transport http --scope user ar-manager \
     https://<your-public-ar-manager-url>/ \
     --header "Authorization: Bearer armt_…"
   ```

   This writes the entry into your user config (`~/.claude.json`), which lives
   outside any repo. `--scope user` makes it apply to every project/session
   automatically. Treat `~/.claude.json` as a secret (`chmod 600`) since the token
   is stored there literally.

   *Alternative:* if you prefer not to keep the literal value in the config file,
   use `--header "Authorization: Bearer ${AR_MANAGER_TOKEN}"` and export
   `AR_MANAGER_TOKEN` in your environment before launching Claude Code (it is
   expanded at session start; Claude Code does not autoload `.env` files). Note
   this exposes the token to every child process of that shell, which is why the
   literal-in-config form above is usually preferable.

   *opencode:* the repo's `.opencode/opencode.json` does not define `ar-manager`
   either. Add it to your user config (`~/.config/opencode/opencode.json`) as a
   remote server:

   ```json
   "mcp": {
     "ar-manager": {
       "type": "remote",
       "url": "https://<your-public-ar-manager-url>/",
       "oauth": false,
       "headers": { "Authorization": "Bearer armt_…" }
     }
   }
   ```

   FlowTree jobs need none of this: the job runner supplies its own
   `ar-manager` entry with the job's token.

**Over SSH this just works.** A static bearer needs no browser and no localhost
redirect, so a remote Claude Code session over SSH authenticates exactly the same
way — there is no OAuth callback to forward. (This is why repo-originated access
uses a personal token rather than the OAuth flow that Claude mobile / claude.ai
use.)

### Authentication is mandatory (no no-auth / stdio mode)

ar-manager runs **only** as an authenticated HTTP/SSE server. It refuses to start
when:

- `MCP_TRANSPORT` is not `http` or `sse` (the old stdio mode is rejected), or
- no tokens are configured (neither `AR_MANAGER_TOKENS` nor a token file).

This closes the tokenless escape hatch: a caller with no token is
indistinguishable from any other, so the job / workspace / permission context an
ar-manager token carries would be silently lost. There is no "trusted LAN"
fallback — configure a token (see above) and reach the server over HTTP.

## Deployment

### Docker Compose (recommended)

ar-manager is defined as a service in `flowtree/runtime/controller/docker-compose.yml` alongside
ar-memory and the FlowTree controller:

```bash
docker compose -f flowtree/runtime/controller/docker-compose.yml up -d
```

Place `manager-tokens.json` in `/Users/Shared/flowtree/manager/` on the host.

**TLS is required for public deployments.** The compose file exposes plain HTTP.
Use Tailscale Funnel, Caddy, or nginx as a TLS-terminating reverse proxy.

### Tailscale Funnel (recommended for public access)

Tailscale Funnel gives ar-manager a stable public HTTPS URL with zero certificate
management. This is the recommended way to expose ar-manager to Claude mobile or
other external clients.

#### Prerequisites

1. **Tailscale installed and authenticated** on the host machine.
   ```bash
   tailscale status   # should show "100.x.x.x  <hostname>  ..."
   ```

2. **Funnel enabled** for your Tailscale account. In the Tailscale admin console
   go to **DNS → Enable HTTPS** and then **Access controls → Enable Funnel**.
   Funnel requires a Tailscale account on the Personal or Team plan.

3. **The controller stack running** (`./flowtree/runtime/rebuild.sh` from the repo root).
   ar-manager depends on `flowtree-controller` and `ar-memory` being up.

#### Setup

Run the setup script from the repo root:

```bash
./tools/mcp/manager/setup.sh
```

The script does the following steps:

1. Creates `/Users/Shared/flowtree/manager/` and generates a bearer token into
   `manager-tokens.json` if none exists yet.
2. Derives your Tailscale DNS name (e.g. `my-host.taild1234.ts.net`) so it can
   set `AR_MANAGER_ISSUER_URL` before the container starts.
3. Builds and starts the `ar-manager` container via Docker Compose.
4. Waits for the `/_health` endpoint to respond.
5. Runs `tailscale funnel --bg <port>` to punch the port through to the internet.

When it finishes you will see:

```
==> Funnel active. Public MCP endpoint:

    https://my-host.taild1234.ts.net/

    Configure this URL in Claude mobile as a remote MCP server.
    OAuth will prompt you to enter your bearer token.
```

#### Verify the funnel

```bash
tailscale funnel status
curl https://my-host.taild1234.ts.net/_health
```

#### Re-running after a reboot

Tailscale Funnel survives reboots automatically once configured. The Docker
container does not — run `./flowtree/runtime/rebuild.sh` (or `docker compose ... up -d`)
to bring it back. The funnel itself does not need to be re-configured.

```bash
# Bring the stack back up after a reboot
./flowtree/runtime/rebuild.sh

# Confirm funnel is still active
tailscale funnel status
```

#### Skip the funnel (LAN-only)

```bash
./tools/mcp/manager/setup.sh --no-funnel
```

ar-manager will be reachable at `http://localhost:8010` but not from the internet.

#### Token-only (no container changes)

```bash
./tools/mcp/manager/setup.sh --token-only
```

Generates a bearer token and exits without touching Docker or Tailscale. Useful
when adding a second client (e.g. a CI pipeline) to an already-running deployment.

### Reverse proxy examples

#### nginx

```nginx
upstream ar_manager {
    server 127.0.0.1:8010;
}

server {
    listen 443 ssl http2;
    server_name manager.example.com;

    ssl_certificate     /etc/letsencrypt/live/manager.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/manager.example.com/privkey.pem;

    limit_req_zone $binary_remote_addr zone=mcp:10m rate=10r/m;

    location /mcp {
        limit_req zone=mcp burst=5 nodelay;
        proxy_pass http://ar_manager;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

#### Caddy

```
manager.example.com {
    rate_limit {
        zone mcp {
            key    {remote_host}
            events 10
            window 1m
        }
    }
    reverse_proxy localhost:8010
}
```

## Response Format

Every tool response includes `next_steps` -- a list of strings guiding the
client on what to do next:

```json
{
    "ok": true,
    "job_id": "task-abc123",
    "workstream_id": "ws-rings",
    "next_steps": [
        "Use workstream_get_status to check job progress",
        "The agent will push commits to branch 'feature/my-work'"
    ]
}
```
