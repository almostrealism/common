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
| `workstream_list` | read | List all workstreams with capabilities |
| `workstream_get_status` | read | Job stats for a workstream |
| `workstream_get_job` | read | Fetch a specific job by id |
| `workstream_submit_task` | submit | Submit a coding task prompt |
| `workstream_register` | write | Register a new workstream |
| `workstream_update_config` | write | Update workstream settings |
| `workspace_update_config` | write | Update workspace-level config (name, default channel, runner defaults) |
| `send_message` | write | Send a Slack message |

### Tier 2: Pipeline-capable workstreams only

| Tool | Scope | Description |
|------|-------|-------------|
| `project_create_branch` | pipeline | Create branch + dispatch project-manager |
| `project_verify_branch` | pipeline | Dispatch verify-completion workflow |
| `project_commit_plan` | pipeline | Commit a plan document to a branch |

**Planned:** Add `github_dismiss_code_scanning_alert` — dismiss GitHub Advanced Security
code-scanning alerts by alert number (e.g., to close bot-generated scanner warnings on
resolved issues). Requires `security_events: write` permission on the PAT.

### Tier 3: GitHub

| Tool | Scope | Description |
|------|-------|-------------|
| `github_pr_find` | github | Find a PR by branch/number |
| `github_pr_review_comments` | github | Get unresolved review thread comments on a PR |
| `github_pr_conversation` | github | Get the issue-style conversation comments on a PR |
| `github_pr_reply` | github | Reply to a PR review thread |
| `github_pr_check_status` | github | Get CI/check status for a PR head commit |
| `github_list_workflow_runs` | github | Search GitHub Actions workflow runs (by workflow/branch/event/status) |
| `github_workflow_run_status` | github | Get a workflow run's jobs and failed steps by run id |
| `github_list_open_prs` | github | List open PRs for a repo |
| `github_create_pr` | github | Create a pull request |
| `github_request_copilot_review` | github | Request a Copilot automated review on a PR |
| `github_read_file` | github | Read a file from a GitHub repo at a branch/ref |
| `project_read_plan` | github | Read the planning document for a workstream |

### Tier 4: Memory

| Tool | Scope | Description |
|------|-------|-------------|
| `memory_recall` | memory-read | Semantic search with optional LLM synthesis |
| `memory_namespaces` | memory-read | List namespaces with entry counts and latest-write times, newest first |
| `workstream_context` | memory-read | Get memories, commits, PR, and jobs for a branch (a workstream is optional — `repo_url` + `branch` is enough; only the jobs stream needs one) |
| `memory_store` | memory-write | Store a memory, optionally reformulated |
| `consult` | consult | Documentation-grounded Q&A: retrieves the project docs and the relevant memories, then synthesises an answer (or returns retrieval-only when no model is reachable, marked `degraded: true`). Replaces the retired `ar-consultant` `consult` tool |

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
See [the Consultant README](../consultant/README.md#memory-text-original-vs-reformulated)
for the full contract.

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
| `AR_CONSULTANT_BACKEND` | `llamacpp` | LLM backend for memory synthesis |
| `AR_CONSULTANT_LLAMA_URL` | (auto-discovered) | llama.cpp server URL |
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

When running from a source checkout, `AR_DOCS_DIR` can be left unset —
`DocsRetriever` falls back to the repository layout relative to its own location.

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
