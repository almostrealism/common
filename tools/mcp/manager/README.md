# AR Manager MCP Server

Internet-facing MCP endpoint for managing FlowTree workstreams, submitting
coding tasks, and triggering project workflows. Designed for naive clients
(Claude mobile, other AI agents) that have no repo checkout or CLAUDE.md context.

## Architecture

```
Claude Mobile / External AI
     |
     | HTTPS (TLS terminated at reverse proxy)
     |
[Reverse proxy: Caddy/nginx]  -- rate limiting, TLS
     |
     | HTTP (localhost)
     |
[ar-manager MCP server]  (port 8010)
     |         \
     |          \-- GitHub API (direct, for workflow dispatch + Contents API)
     |
     | HTTP (port 7780)
     |
[FlowTree controller]  (FlowTreeApiEndpoint)
```

The server is **stateless** -- all workstream state lives in the controller. It is
a thin orchestration facade that:
- Calls the controller REST API for Tier 1 operations (CRUD)
- Calls the GitHub API directly for Tier 2 operations (workflow dispatch, file commits)
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

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_CONTROLLER_URL` | `http://localhost:7780` | FlowTree controller base URL |
| `AR_MANAGER_GITHUB_TOKEN` | (none) | GitHub PAT for Tier 2 ops (falls back to `GITHUB_TOKEN`) |
| `AR_MANAGER_TOKEN_FILE` | `~/.config/ar/manager-tokens.json` | Bearer token config file |
| `AR_MANAGER_TOKENS` | (none) | JSON string of token config (overrides file) |
| `MCP_TRANSPORT` | `stdio` | Transport: `stdio`, `http`, or `sse` |
| `MCP_PORT` | `8010` | Port for http/sse transport |

## Authentication

### Token file format

Create `~/.config/ar/manager-tokens.json`:

```json
{
  "tokens": [
    {
      "value": "tok_abc123",
      "scopes": ["read", "write", "pipeline"],
      "label": "Claude mobile"
    },
    {
      "value": "tok_xyz456",
      "scopes": ["read"],
      "label": "Monitoring dashboard"
    }
  ]
}
```

**Scopes:**
- `read` -- list workstreams, get stats, health check
- `write` -- submit tasks, register/update workstreams
- `pipeline` -- trigger GitHub workflows, commit plan files

### No-auth mode

When no token file exists and `AR_MANAGER_TOKENS` is unset, the server runs
without authentication (for trusted LAN use). A warning is logged on startup.

## Deployment

### Centralized MCP server (production)

Add to your workstreams YAML config under `mcpServers`:

```yaml
mcpServers:
  ar-manager:
    command: python3
    args: [tools/mcp/manager/server.py]
    env:
      MCP_TRANSPORT: http
      MCP_PORT: "8010"
      AR_MANAGER_GITHUB_TOKEN: ${GITHUB_TOKEN}
      AR_MANAGER_TOKEN_FILE: /etc/ar/manager-tokens.json
```

The controller will start it automatically via `FlowTreeController.startCentralizedMcpServers()`.

### Reverse proxy (TLS + rate limiting)

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

### Local development (stdio)

The server is configured in `.mcp.json` for stdio-mode local development:

```json
{
  "mcpServers": {
    "ar-manager": {
      "command": "python3",
      "args": ["tools/mcp/manager/server.py"]
    }
  }
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

### Capability errors (Tier 2 on non-pipeline workstream)

```json
{
    "ok": false,
    "error": "Workstream 'ws-foo' does not support pipeline operations",
    "reason": "Missing: repo_url is not configured",
    "suggestion": "Use workstream_update_config to set repo_url",
    "next_steps": [
        "Call workstream_update_config with repo_url=...",
        "Then call workstream_list to confirm pipeline_capable=true"
    ]
}
```
