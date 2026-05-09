# Workspace Secrets Storage

## Goal

Introduce workspace-scoped secret storage in the FlowTree controller, with template-based
file materialization via ar-manager MCP tools. Coding agents can request credential files
(e.g., `~/.aws/credentials`) without raw secret values ever appearing in prompts, responses,
or PR descriptions.

"Workspace" here refers to our internal organizational concept — currently 1:1 with a
connected Slack workspace but treated as conceptually independent. The plan uses "workspace"
throughout for our org concept and "Slack workspace" only where specifically referring to
the Slack connection.

---

## Background

Today an agent that needs AWS credentials or another third-party secret must receive those
values directly in the job prompt. This is both a security risk and operationally tedious.
The workspace secrets feature solves this with two design goals:

1. Secrets are stored once, per workspace, by name. Any workstream in that workspace can
   request a secret via its bearer token — no copy-paste into prompts.

2. Agents never see raw secret values. Instead the agent supplies a *file template* with
   named placeholders. The ar-manager tool resolves the token → workstream → workspace,
   fetches the secret, substitutes the placeholders, and writes the materialized file to a
   path the agent specifies. The agent uses the resulting file without the values ever
   passing through its context window.

---

## Current Architecture — Relevant Parts

### Workspace and Workstream Model

**`WorkstreamConfig`** (`flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java`):
- Each `WorkstreamEntry` has a `slackWorkspaceId` field (Slack team ID, e.g., `T0123456789`)
  that binds the workstream to a workspace.
- `SlackWorkspaceEntry` in the `slackWorkspaces` top-level YAML list defines each workspace,
  including its tokens and (after this plan) its secrets.

**Token flow:**
- The FlowTree controller issues short-lived HMAC temporary tokens for each job:
  `armt_tmp_{base64url(hmac)}:{base64url(payload)}` where payload is
  `{workstream_id}:{job_id}:{expiry_epoch}`.
- ar-manager's `BearerAuthMiddleware` validates these tokens using the shared secret in
  `AR_MANAGER_SHARED_SECRET`. The token's workspace scope is derived automatically from
  the workstream's `slackWorkspaceId`.

**Scope resolution in ar-manager** (`tools/mcp/manager/server.py`):
- `_require_workstream_in_scope(workstream_id)` — resolves workstream → workspace, checks
  that the request token's `workspaceScopes` list permits that workspace.
- `_workspace_for_workstream(id)` — cached (30 s TTL) lookup via `/api/workstreams`.
- `_filter_workstreams_by_scope(entries)` — filters a list to only in-scope workstreams.
- The new secrets tools reuse all of these unchanged.

### ar-manager Tool Pattern

Tools are registered in `tools/mcp/manager/server.py` with `@mcp.tool()` directly above
the function definition. All parameters must appear in the function signature (no `**kwargs`).
Docstrings must include `Args:` and `Returns:` sections. Adding a tool also requires
updating:
- `McpToolDiscoveryTest` in
  `flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java`
- The Python test suite in `tools/mcp/manager/test_server.py`

### Data Storage Locations

- Shared config directory: `/Users/Shared/flowtree/` (macOS shared directory used for
  manager tokens, logs, and config — see `tools/mcp/manager/setup.sh`, line 27).
- Manager tokens: `/Users/Shared/flowtree/manager/manager-tokens.json`
- Secrets will live at: `/Users/Shared/flowtree/secrets/` (one JSON file per secret).
- Controller YAML config (`workstreams.yaml`) declares secret names and their file paths.

### REST API Entry Point

**`FlowTreeApiEndpoint`** (`flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`):
- Extends `NanoHTTPD`, listens on port 7780 by default.
- All `/api/*` paths are handled here.
- New `/api/secrets/*` endpoints belong in this class, following the same routing style as
  the existing `/api/workstreams/*` handlers.

---

## 1. Storage Model

### Secret Structure

Each secret has:
- **name** — unique within a workspace; URL-safe string (lowercase letters, digits, hyphens).
- **workspace** — the workspace that owns it (Slack team ID, e.g., `T0123456789`).
- **payload** — a `Map<String, String>` of key-value pairs (e.g., `access_key_id → AKIA...`).

### File Format

One JSON file per secret, containing only the payload map:

```json
{
  "access_key_id": "AKIAIOSFODNN7EXAMPLE",
  "secret_access_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "region": "us-east-1"
}
```

### Storage Layout

Secrets files live at `/Users/Shared/flowtree/secrets/`, following the same convention as
the manager config directory. Two layout options were considered:

**Option A — flat directory, workspace-namespaced filename:**
```
/Users/Shared/flowtree/secrets/T0123456789__aws-prod.json
```
Simple; no subdirectory creation required. Workspace ID is always present in the filename,
preventing cross-workspace name collisions.

**Option B — per-workspace subdirectory:**
```
/Users/Shared/flowtree/secrets/T0123456789/aws-prod.json
```
Slightly cleaner for humans browsing the directory; requires mkdir on first write.

**Recommendation: Option A.** It keeps the secrets directory flat, matches how tokens are
stored (a single directory), and requires no directory-creation logic. The filename pattern
`{workspaceId}__{secretName}.json` is unambiguous.

### Config Declaration

Each workspace's secrets are declared in `workstreams.yaml` under `slackWorkspaces`, in a
new `secrets` list. Declaring secrets explicitly (rather than discovering files by
convention) means the controller can enumerate accessible secrets per workspace at startup
and in the list endpoint, without scanning the filesystem.

```yaml
slackWorkspaces:
  - workspaceId: "T0123456789"
    name: "my-org"
    tokensFile: "/Users/Shared/flowtree/manager/slack-tokens-my-org.json"
    secrets:
      - name: "aws-prod"
        file: "/Users/Shared/flowtree/secrets/T0123456789__aws-prod.json"
      - name: "github-deploy-key"
        file: "/Users/Shared/flowtree/secrets/T0123456789__github-deploy-key.json"
```

The `file` field may be absolute (recommended) or relative to the controller's working
directory. Absolute paths are preferred — they match the existing convention for `tokensFile`.

### New Config Classes

Add to `WorkstreamConfig` (`flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java`):

```java
/** Declares a single workspace-scoped secret available to agent workstreams. */
@JsonIgnoreProperties(ignoreUnknown = true)
public static class WorkspaceSecretEntry {
    private String name;   // unique within workspace, URL-safe
    private String file;   // absolute path to the JSON payload file

    // getters/setters...
}
```

Add `private List<WorkspaceSecretEntry> secrets = new ArrayList<>()` to the existing
`SlackWorkspaceEntry` class.

### File Permissions

Secrets files must be readable only by the controller process. Set permissions to `0600`
on write. The controller should log a warning at startup if a declared secrets file is
readable by group or world (`(perms & 0o077) != 0`).

---

## 2. Controller API

### Token Authentication

All `/api/secrets/*` endpoints authenticate via the `Authorization: Bearer <token>` header,
using the same admin-token validation already present in `FlowTreeApiEndpoint` for
protected paths. The existing token check (`isAuthorized(session)`) is applied to all
management endpoints. The retrieve-by-workstream-token endpoint uses the workstream's
HMAC temp token instead of the admin token.

### Endpoints

#### Retrieve secret (called by ar-manager on behalf of an agent)

```
GET /api/secrets/{secretName}?workstream_id={wsId}
Authorization: Bearer armt_tmp_...   (workstream HMAC token)
```

Response on success (`200`):
```json
{
  "name": "aws-prod",
  "workspace_id": "T0123456789",
  "payload": {
    "access_key_id": "AKIAIOSFODNN7EXAMPLE",
    "secret_access_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    "region": "us-east-1"
  }
}
```

The controller validates that:
1. The HMAC token is valid and not expired.
2. The `workstream_id` matches the token payload.
3. The workstream's workspace (`slackWorkspaceId`) owns a secret with the given name.

On any validation failure: `403` with `{"error": "..."}`. On unknown secret: `404`.

**Audit log** (always written, regardless of success/failure):
```
SECRET_RETRIEVE secret=aws-prod workstream_id=ws-abc workspace_id=T0123456789 result=OK
```
Never log the payload values.

#### List secret names (by workstream token)

```
GET /api/secrets?workstream_id={wsId}
Authorization: Bearer armt_tmp_...
```

Returns the names of all secrets in the workstream's workspace:
```json
{ "names": ["aws-prod", "github-deploy-key"] }
```

No payloads are returned by this endpoint.

#### Create / update secret (admin token)

```
PUT /api/secrets/{secretName}?workspace_id={wsId}
Authorization: Bearer <admin-token>
Content-Type: application/json

{ "key1": "value1", "key2": "value2" }
```

Creates or fully replaces the secret payload. Writes the JSON file atomically (write to a
temp file in the same directory, then `Files.move(ATOMIC_MOVE)`). Sets file permissions to
`0600` after write.

#### Delete secret (admin token)

```
DELETE /api/secrets/{secretName}?workspace_id={wsId}
Authorization: Bearer <admin-token>
```

Deletes the secrets file from disk and removes the entry from the in-memory config.
Does **not** persist a config change automatically — the admin is expected to also remove
the entry from `workstreams.yaml` to avoid a "declared but missing file" warning on next
restart.

#### List secret names for workspace (admin token)

```
GET /api/secrets?workspace_id={wsId}
Authorization: Bearer <admin-token>
```

Returns names only, same format as the workstream-token list endpoint. Never returns
payloads via this endpoint.

### Implementation Location

Add a `SecretsHandler` inner class (or a dedicated `handleSecretsRequest` method) to
`FlowTreeApiEndpoint`. Follow the existing handler pattern: extract path segments, dispatch
on method+path, return `NanoHTTPD.Response`.

The `FlowTreeController` loads `WorkstreamConfig` at startup and on `reloadConfig()`.
After this plan, it also resolves and caches the `WorkspaceSecretEntry` list per workspace
in memory (a `Map<String, Map<String, WorkspaceSecretEntry>>` keyed by workspaceId → name).

### Concurrency

Secrets files are read-mostly. Use the atomic write strategy (temp file + move) described
above. No file locking is required for reads — the kernel guarantees that a complete
64 KB `read()` syscall on a small JSON file is atomic in practice. For the rare case where
a write races a read, the worst outcome is a read of the old content, which is safe.

---

## 3. ar-manager MCP Tools

Two new tools in `tools/mcp/manager/server.py`, following the `@mcp.tool()` pattern.

### `workspace_secret_list_names`

```python
@mcp.tool()
def workspace_secret_list_names(
    workstream_id: str,
) -> dict:
    """List the names of secrets accessible to the calling workstream's workspace.

    Returns only names — no payload values. Useful for an agent to discover
    what secrets are available before calling workspace_secret_render_file.

    Args:
        workstream_id: The workstream whose workspace's secrets to list.

    Returns:
        dict with ok=True and names list, or ok=False with error.
    """
    _require_scope("read")
    _require_workstream_in_scope(workstream_id)
    _audit("workspace_secret_list_names", workstream_id=workstream_id)

    resp = _controller_get(
        f"/api/secrets?workstream_id={workstream_id}",
        token=_get_temp_token_for_workstream(workstream_id),
    )
    if not resp.get("ok", True):
        return {"ok": False, "error": resp.get("error", "controller error")}
    return {"ok": True, "names": resp.get("names", [])}
```

### `workspace_secret_render_file`

```python
@mcp.tool()
def workspace_secret_render_file(
    workstream_id: str,
    secret_name: str,
    template: str,
    output_path: str,
    mode: str = "0600",
) -> dict:
    """Fetch a workspace secret and render it into a file using a template.

    The agent supplies a template with {{key}} placeholders. The secret payload
    is fetched from the controller (the agent never sees the raw values), all
    placeholders are substituted, and the result is written to output_path with
    the specified permissions. The rendered content is never returned to the agent.

    Template placeholders use {{key}} syntax (double curly braces). Every {{key}}
    in the template must exist in the secret payload — unresolved placeholders
    cause an error and no file is written. Extra keys in the payload that are not
    referenced in the template are silently ignored.

    Example usage for AWS credentials:

        template = \"\"\"[default]
    aws_access_key_id = {{access_key_id}}
    aws_secret_access_key = {{secret_access_key}}
    region = {{region}}
    \"\"\"
        workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="aws-prod",
            template=template,
            output_path="~/.aws/credentials",
        )

    After this call the agent can run AWS CLI commands without ever having seen
    the credential values.

    Args:
        workstream_id: The workstream whose workspace owns the secret.
        secret_name: Name of the secret to fetch.
        template: Template string with {{key}} placeholders.
        output_path: Destination file path (~ is expanded).
        mode: Octal file permissions string, e.g. "0600" (default).

    Returns:
        dict with ok=True and output_path on success, or ok=False with error.
        The rendered content is never included in the response.
    """
    _require_scope("read")
    _require_workstream_in_scope(workstream_id)
    _audit(
        "workspace_secret_render_file",
        workstream_id=workstream_id,
        secret_name=secret_name,
        output_path=output_path,
        mode=mode,
        # template deliberately omitted from audit log
    )

    # Fetch secret from controller
    resp = _controller_get(
        f"/api/secrets/{secret_name}?workstream_id={workstream_id}",
        token=_get_temp_token_for_workstream(workstream_id),
    )
    if not resp.get("ok", True):
        return {"ok": False, "error": resp.get("error", "controller error")}

    payload = resp.get("payload", {})

    # Strict placeholder resolution — every {{key}} must be present
    import re
    placeholders = re.findall(r"\{\{(\w+)\}\}", template)
    missing = [p for p in placeholders if p not in payload]
    if missing:
        return {
            "ok": False,
            "error": f"Template references unknown keys: {missing}. "
                     f"Available keys: {sorted(payload.keys())}",
        }

    rendered = template
    for key in placeholders:
        rendered = rendered.replace(f"{{{{{key}}}}}", payload[key])

    # Write file — never log rendered content
    import os
    expanded = os.path.expanduser(output_path)
    os.makedirs(os.path.dirname(expanded) or ".", exist_ok=True)
    file_mode = int(mode, 8)
    fd = os.open(expanded, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, file_mode)
    try:
        os.write(fd, rendered.encode("utf-8"))
    finally:
        os.close(fd)

    return {"ok": True, "output_path": expanded}
```

### Tool Registration and Discovery

After adding these two tools, update:

1. **`McpToolDiscoveryTest.java`**
   (`flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java`) —
   add `"workspace_secret_list_names"` and `"workspace_secret_render_file"` to the expected
   tools list.

2. **`test_server.py`**
   (`tools/mcp/manager/test_server.py`) — add the new tools to the expected-tools list
   assertion and add unit tests (see §7 below).

---

## 4. Placeholder Syntax

**Decision: `{{key}}` (double curly braces / Mustache-style).**

Tradeoffs considered:

| Syntax | Risk of collision | Familiarity |
|---|---|---|
| `{{key}}` | Low — rare in credential files | Jinja2 / Mustache ecosystem |
| `${key}` | Medium — used in shell scripts, some config formats | Shell, Gradle |
| `%(key)s` | Low — uncommon outside Python | Python `%`-formatting |
| `{key}` | High — used in AWS policy JSON, TOML | Python `.format()` |

`${key}` is the riskiest because AWS credential files and INI-style configs occasionally
contain dollar-sign expressions. `{{key}}` is unambiguous in every common credential
format (INI, TOML, YAML, JSON) and is widely understood. **Use `{{key}}`.**

---

## 5. Security Considerations

### What this protects against

The template model prevents secret values from appearing in:
- The Claude prompt or context window
- ar-manager tool call responses (the rendered content is never returned)
- Agent stdout/stderr and log files
- PR descriptions, commit messages, and GitHub comments written by the agent

### What this does not protect against

An agent that renders a file can read that file back using the `Read` tool (or `cat`).
This is intentional — the agent needs to use the credentials. The trust boundary is the
workstream grant: **granting a workstream access to a secret means the agent on that
workstream can use the secret.** The protection is against accidental leakage into
observable text channels, not against a hostile or buggy agent.

Document this clearly in the ar-manager README so workspace owners understand the
grant semantics.

### Template injection

Placeholder substitution is strict string replacement — no eval, no format strings, no
shell expansion. The `re.findall` + `str.replace` approach in §3 is deliberately simple.
There is no code execution risk from template or payload content.

### Unresolved placeholders

**Strict mode is required.** If the template references `{{foo}}` and the secret has no
`foo` key, the tool returns an error and writes no file. A partially-rendered credential
file (with `{{foo}}` literally in it) is silently broken and may cause confusing downstream
failures. Strict matching catches the misconfiguration immediately.

Extra keys in the secret payload that are not referenced in the template are silently
ignored — this allows secrets to carry more keys than any single template needs.

### Audit logging

Every call to `workspace_secret_render_file` and the controller's retrieve endpoint logs:
- `secret_name`
- `workstream_id`
- `workspace_id`
- `output_path` (render tool only)
- `result` (OK / error code)

Never log: template contents, rendered output, or any key values from the payload.

### File permissions

The rendered file is written with the mode supplied by the caller (default `0600`).
Agents should not override this to something more permissive unless the target tool
requires it (e.g., some tools insist on `0644`). The plan recommends keeping the default.

---

## 6. Migration and Rollout

This is purely additive. No existing behavior changes.

### Ordering

1. **Controller storage + API** — add `WorkspaceSecretEntry` to config, implement
   `/api/secrets/*` endpoints in `FlowTreeApiEndpoint`, add startup permission check.
2. **ar-manager tools** — add `workspace_secret_list_names` and
   `workspace_secret_render_file` to `server.py`, update discovery tests.
3. **Seed one real secret** — create `T{workspace}__aws-prod.json` with the AWS prod
   credentials, declare it in `workstreams.yaml`, verify end-to-end with a test job.

### Seed Secret for Validation

```bash
# Create secrets directory (controller host)
mkdir -p /Users/Shared/flowtree/secrets
chmod 700 /Users/Shared/flowtree/secrets

# Write seed secret (replace values with real credentials)
cat > /Users/Shared/flowtree/secrets/T0123456789__aws-prod.json << 'EOF'
{
  "access_key_id": "AKIAIOSFODNN7EXAMPLE",
  "secret_access_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "region": "us-east-1"
}
EOF
chmod 600 /Users/Shared/flowtree/secrets/T0123456789__aws-prod.json
```

Declare in `workstreams.yaml`:
```yaml
slackWorkspaces:
  - workspaceId: "T0123456789"
    secrets:
      - name: "aws-prod"
        file: "/Users/Shared/flowtree/secrets/T0123456789__aws-prod.json"
```

---

## 7. Testing Strategy

### Controller Endpoint Tests

Add to `flowtree/src/test/java/io/flowtree/slack/FlowTreeApiEndpointTest.java` (or create
`SecretsEndpointTest.java` in the same package):

| Test | Setup | Expected outcome |
|---|---|---|
| Valid retrieve | Valid HMAC token + matching workstream + declared secret | `200` with payload |
| Wrong-workspace token | Valid token for workspace B, secret in workspace A | `403` |
| Unknown secret | Valid token, secret not declared for workspace | `404` |
| Missing file | Secret declared in config but file absent | `500` with no payload exposed |
| Admin list | Admin token, `GET /api/secrets?workspace_id=...` | `200` with names only |
| Admin create | Admin token, `PUT /api/secrets/new-secret` | `200`, file written at declared path |
| File permission check | Secret file mode is `0644` | Startup log warning |
| Audit log | Any retrieve call | Log line contains `secret_name`, `workstream_id`, no values |

### ar-manager Tool Tests

Add to `tools/mcp/manager/test_server.py`:

| Test | Mock | Expected outcome |
|---|---|---|
| `list_names` success | Controller returns `{"names": ["aws-prod"]}` | Returns `{"ok": True, "names": [...]}` |
| `render_file` success | Controller returns valid payload; template uses all keys | File written with correct content; response contains no values |
| Missing placeholder | Template has `{{missing_key}}`, secret lacks `missing_key` | `{"ok": False, "error": ...}`, no file written |
| Output file mode | `mode="0600"` | File created with permissions `0o600` |
| Output path expansion | `output_path="~/.aws/credentials"` | `~` expanded to home dir |
| Wrong-workspace token | `_require_workstream_in_scope` raises | `PermissionError` propagated |
| Audit log assertion | Any successful render | Log contains `secret_name` + `output_path`, does not contain template or payload |

### End-to-End Smoke Test

A fixture-based smoke test:

1. Write a fake secret JSON to a temp file.
2. Start the controller in test mode with a `workstreams.yaml` pointing to that file.
3. Mint a valid HMAC temp token for a test workstream.
4. Call `workspace_secret_render_file` via the real ar-manager (not mocked).
5. Assert the output file exists, has mode `0600`, and contains the expected rendered content.
6. Assert the raw secret values do not appear in any log output captured during the test.

---

## 8. Open Questions

The following decisions must be confirmed before implementation begins.

| # | Question | Recommendation |
|---|---|---|
| 1 | **Placeholder syntax** | `{{key}}` — low collision risk, widely understood |
| 2 | **Unresolved placeholder behavior** | Strict — error out, write no file |
| 3 | **Secrets file location** | `/Users/Shared/flowtree/secrets/` with `{workspaceId}__{name}.json` filenames |
| 4 | **Template rendering location** | ar-manager only — controller returns raw payload; rendered file never leaves agent host |
| 5 | **Server-side rendering endpoint** | Omit from controller — keeps controller surface minimal |
| 6 | **Management auth model** | Admin token for create/update/delete; workstream token for retrieve and list-names |
| 7 | **Config declaration required?** | Yes — secrets must be declared in YAML; controller does not scan filesystem |
| 8 | **Multi-workspace mode required?** | Yes — single-workspace installations work via the first/only `SlackWorkspaceEntry` |
