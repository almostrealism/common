# Security Hardening: ar-manager MCP Server

## Context

The ar-manager MCP server (`tools/mcp/manager/server.py`) is about to be deployed on the public internet. A security audit identified 15 issues ranging from critical auth bypasses to low-severity information leaks. This plan addresses all of them before the server goes live.

## Files to Modify

| File | Changes |
|------|---------|
| `tools/mcp/manager/server.py` | Auth bypass fix, rate limiting, timing-safe token comparison, audit logging, input validation, path traversal check, thread-safe init, error sanitization, DNS rebinding note, scope cleanup |
| `tools/mcp/manager/Dockerfile` | Fix healthcheck to work with auth |
| `tools/mcp/manager/generate-token.sh` | Fix shell injection, add "memory" to default scopes |
| `tools/docker-compose.yml` | Add comments about TLS requirement |
| `tools/mcp/common/memory_http_client.py` | ~~Deferred — mac-studio fallback needed by local Tailnet tools~~ |

## Implementation Steps

### Step 1: Fix auth bypass fallback (CRITICAL - server.py lines 1308-1327)

The `except AttributeError` fallback silently drops authentication when `streamable_http_app()` is unavailable. Change this to **refuse to start** if tokens are configured but auth middleware cannot be applied.

```python
except AttributeError:
    print("ar-manager: FATAL: Cannot apply auth middleware — "
          "streamable_http_app() not available in this MCP version. "
          "Upgrade the mcp package or remove tokens to run without auth.",
          file=sys.stderr)
    sys.exit(1)
```

### Step 2: Add rate limiting ASGI middleware (server.py, new class)

Add a `RateLimitMiddleware` ASGI middleware that applies before auth. Uses a sliding-window token bucket keyed by bearer token string (or source IP for unauthenticated requests).

- Default: 60 requests/minute per key
- Configurable via `AR_MANAGER_RATE_LIMIT` env var
- Returns HTTP 429 with `Retry-After` header when exceeded
- Pure in-memory, no external dependencies
- Uses `time.monotonic()` for clock

### Step 3: Fix Docker healthcheck (Dockerfile line 25-26)

Add an auth-exempt `/_health` path in `BearerAuthMiddleware`. Update Dockerfile healthcheck to hit `/_health` instead of `/mcp`. The health handler returns a simple 200 OK.

### Step 4: Timing-safe token comparison (server.py line 209)

Replace dict `in` lookup with iteration using `hmac.compare_digest()`:

```python
matched_scopes = None
for stored_token, scopes in self.token_map.items():
    if hmac.compare_digest(token_value.encode(), stored_token.encode()):
        matched_scopes = scopes
        break
```

### Step 5: Add audit logging (server.py)

- Store token label alongside scopes (change `_set_scopes` to also store label)
- Add `_get_token_label()` helper
- Add audit log calls at the top of each tool function
- Format: `audit: tool=<name> token=<label> key_params=...`

### Step 6: Validate `path` in `project_commit_plan` (server.py ~line 944)

After receiving or auto-generating the path:
- Reject paths with `..` segments
- Reject absolute paths (starting with `/`)
- Reject paths targeting `.github/workflows/` or `.github/actions/`

### Step 7: Add input length limits (server.py)

- `prompt` in `workstream_submit_task`: max 50,000 chars
- `content` in `project_commit_plan`: max 100,000 chars
- `content` in `memory_store`: max 50,000 chars
- String params (branch names, IDs, paths): max 1,000 chars

### Step 8: Fix shell injection in generate-token.sh (lines 49-73)

Replace inline `$VARIABLE` interpolation in Python code with `sys.argv` parameter passing via a heredoc.

### Step 9: Add "memory" scope to default token generation

Change `generate-token.sh` line 22 default scopes from `("read" "write" "pipeline")` to `("read" "write" "pipeline" "memory")`.

### Step 10: Sanitize error messages (server.py)

In `_controller_get`, `_controller_post`, and `_github_request`: log full error details server-side, return only HTTP status code to client (no raw response bodies).

### Step 11: Thread-safe singleton initialization (server.py)

Add `threading.Lock()` with double-checked locking around `_get_memory_client()` and `_get_llm()`.

### Step 12: TLS requirement documentation (docker-compose.yml)

Add prominent warning comments that plain HTTP is exposed and TLS termination is required for public deployment.

### Step 13: Add insecure-mode startup warning (server.py)

Print a prominent warning when binding to `0.0.0.0` with tokens configured, noting that bearer tokens will be transmitted in cleartext without a TLS-terminating reverse proxy.

### Step 14: DNS rebinding protection comment (server.py)

Add a comment explaining why DNS rebinding protection is disabled (required for reverse proxy / Tailscale Funnel deployments).

### ~~Step 15: Remove hardcoded `mac-studio` hostname (memory_http_client.py)~~

**DEFERRED**: The `mac-studio` fallback is used by ar-consultant and other MCP tools running locally inside the Tailnet. Removing it would break those services. Not a risk for the public-facing ar-manager since the Docker container uses explicit `AR_MEMORY_URL` from docker-compose.yml.

## Verification

1. **Auth bypass**: Mock `streamable_http_app` unavailable with tokens configured -> server exits with error
2. **Rate limiting**: >60 rapid requests -> HTTP 429 responses
3. **Healthcheck**: `docker compose up` with auth -> container stays healthy via `/_health`
4. **Timing-safe tokens**: Valid tokens accepted, invalid rejected (functional parity)
5. **Audit logging**: Submit a task -> stderr shows audit line with token label
6. **Path traversal**: `project_commit_plan(path="../../.github/workflows/evil.yaml")` -> rejected
7. **Input limits**: 100K-char prompt -> rejected with clear error
8. **Shell injection**: `./generate-token.sh "'; os.system('echo PWNED'); '"` -> no code execution
9. **Error sanitization**: Controller error -> no raw stack traces in response
10. **Docker build**: `docker compose -f tools/docker-compose.yml build ar-manager` succeeds
