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
| `github_list_open_prs` | github | List open PRs for a repo |
| `github_create_pr` | github | Create a pull request |
| `github_request_copilot_review` | github | Request a Copilot automated review on a PR |
| `github_read_file` | github | Read a file from a GitHub repo at a branch/ref |
| `project_read_plan` | github | Read the planning document for a workstream |

### Tier 4: Memory

| Tool | Scope | Description |
|------|-------|-------------|
| `memory_recall` | memory-read | Semantic search with optional LLM synthesis |
| `workstream_context` | memory-read | Get memories, commits, and jobs for a workstream branch |
| `memory_store` | memory-write | Store a memory from an external client |

Memory tools resolve `repo_url` and `branch` from a `workstream_id` when not
provided directly. LLM synthesis (via llama.cpp) is attempted for `memory_recall`
summaries when a backend is available.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_CONTROLLER_URL` | `http://localhost:7780` | FlowTree controller base URL |
| `AR_MEMORY_URL` | (auto-discovered) | ar-memory HTTP server URL |
| `AR_MANAGER_GITHUB_TOKEN` | (none) | GitHub PAT for Tier 2 ops (falls back to `GITHUB_TOKEN`) |
| `AR_MANAGER_TOKEN_FILE` | `~/.config/ar/manager-tokens.json` | Bearer token config file |
| `AR_MANAGER_TOKENS` | (none) | JSON string of token config (overrides file) |
| `AR_CONSULTANT_BACKEND` | `llamacpp` | LLM backend for memory synthesis |
| `AR_CONSULTANT_LLAMA_URL` | (auto-discovered) | llama.cpp server URL |
| `MCP_TRANSPORT` | `stdio` | Transport: `stdio`, `http`, or `sse` |
| `MCP_PORT` | `8010` | Port for http/sse transport |

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

### No-auth mode

When no token file exists and `AR_MANAGER_TOKENS` is unset, the server runs
without authentication (for trusted LAN use). A warning is logged on startup.

## Deployment

### Docker Compose (recommended)

ar-manager is defined as a service in `flowtree/controller/docker-compose.yml` alongside
ar-memory and the FlowTree controller:

```bash
docker compose -f flowtree/controller/docker-compose.yml up -d
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

3. **The controller stack running** (`./flowtree/rebuild.sh` from the repo root).
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
container does not — run `./flowtree/rebuild.sh` (or `docker compose ... up -d`)
to bring it back. The funnel itself does not need to be re-configured.

```bash
# Bring the stack back up after a reboot
./flowtree/rebuild.sh

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
