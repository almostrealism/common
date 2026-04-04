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
| `workstream_list` | read | List all workstreams with capabilities |
| `workstream_get_status` | read | Job stats for a workstream |
| `workstream_submit_task` | write | Submit a coding task prompt |
| `workstream_register` | write | Register a new workstream |
| `workstream_update_config` | write | Update workstream settings |

### Tier 2: Pipeline-capable workstreams only

| Tool | Scope | Description |
|------|-------|-------------|
| `project_create_branch` | pipeline | Create branch + dispatch project-manager |
| `project_verify_branch` | pipeline | Dispatch verify-completion workflow |
| `project_commit_plan` | pipeline | Commit a plan document to a branch |
| `github_pr_review_comments` | pipeline | Get unresolved review thread comments on a PR |

**Planned:** Add `github_dismiss_code_scanning_alert` — dismiss GitHub Advanced Security
code-scanning alerts by alert number (e.g., to close bot-generated scanner warnings on
resolved issues). Requires `security_events: write` permission on the PAT.

### Tier 3: Memory

| Tool | Scope | Description |
|------|-------|-------------|
| `memory_recall` | memory | Semantic search with optional LLM synthesis |
| `memory_branch_context` | memory | Get all memories for a specific branch |
| `memory_store` | memory | Store a memory from an external client |

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
      "scopes": ["read", "write", "pipeline", "memory"],
      "label": "Claude mobile"
    },
    {
      "value": "armt_...",
      "scopes": ["read", "memory"],
      "label": "Monitoring dashboard"
    }
  ]
}
```

Generate a token with `tools/mcp/manager/generate-token.sh`. Default scopes
are `read`, `write`, `pipeline`, `memory`.

**Scopes:**
- `read` -- list workstreams, get stats, health check
- `write` -- submit tasks, register/update workstreams
- `pipeline` -- trigger GitHub workflows, commit plan files
- `memory` -- recall, store, and browse agent memories

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

ar-manager is defined as a service in `tools/docker-compose.yml` alongside
ar-memory and the FlowTree controller:

```bash
docker compose -f tools/docker-compose.yml up -d
```

Place `manager-tokens.json` in `/Users/Shared/flowtree/manager/` on the host.

**TLS is required for public deployments.** The compose file exposes plain HTTP.
Use Tailscale Funnel, Caddy, or nginx as a TLS-terminating reverse proxy.

### Tailscale Funnel (quickest public endpoint)

```bash
cd tools/mcp/manager
./setup.sh
```

This generates a token, builds the container, and sets up Tailscale Funnel.

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
