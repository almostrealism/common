# Workspace Secrets

Workspace secrets let agents read credential files from the FlowTree controller
host without those credentials ever appearing in agent output, prompts, PR
descriptions, or logs.  The agent requests that a secret be **rendered into a
file** at a path it supplies.  The controller stores and serves the JSON
payload; an MCP server fetches that payload, substitutes it into the supplied
template, and writes the rendered file on the host where the agent runs.  The
agent receives only a success/failure acknowledgement — never the credential
values themselves.

## Two MCP servers, two topologies

There are two MCP servers that expose secret rendering, and the right one to
use depends on where the calling agent runs relative to the controller.

| Server | Topology | Use for |
|--------|----------|---------|
| `ar-secrets` (`tools/mcp/secrets/server.py`) | **stdio in the agent container** | Coding agents launched by `ClaudeCodeJob` — the default and recommended path. Tools: `secret_list_names`, `secret_render_file`. |
| `ar-manager` (`workspace_secret_list_names`, `workspace_secret_render_file`) | HTTP, on the controller | Admin/Slack flows where the caller already runs alongside the controller and the rendered file is meant to land on the controller host. |

The two are not interchangeable. `ar-manager`'s `workspace_secret_render_file`
writes the rendered file on the **controller's** filesystem; in the default
`ClaudeCodeJob` topology the agent runs in a separate container with a
different filesystem namespace, so the agent never sees the file.
`ar-secrets` is a small stdio MCP server that the agent launches locally, so
its `secret_render_file` writes the file in the same filesystem namespace as
the agent. Both servers talk to the same controller `/api/secrets/*`
endpoints; only the location of the file write differs.

`ar-secrets` reads its authentication context from environment variables that
`ClaudeCodeJob` injects on the agent process — `AR_CONTROLLER_URL`,
`AR_WORKSTREAM_ID`, and `AR_MANAGER_TOKEN` (the same `armt_tmp_*` bearer that
ar-manager already uses). No additional configuration is required in
`workstreams.yaml`.

---

## Storage model

Each secret is a single JSON file on the controller host.  The recommended
location is `/Users/Shared/flowtree/secrets/` (macOS) or an equivalent
shared-but-restricted directory on Linux.

### File naming

```
{workspaceId}__{secretName}.json
```

Examples:

```
T01AB2CD3EF__aws-prod.json
T01AB2CD3EF__gh-deploy-token.json
T9XYZ012345__aws-staging.json
```

The double underscore `__` separates the Slack workspace ID from the secret
name.  Secret names must match `[a-z0-9][a-z0-9\-]*` (lower-case, hyphens
allowed).

### File permissions

Secret files **must** be owner-read/write only (`0600`).  The controller
prints a startup warning for any file with group- or world-readable bits.
Create files correctly from the start:

```bash
install -m 600 /dev/null /Users/Shared/flowtree/secrets/T01AB2CD3EF__aws-prod.json
# then write the JSON with your editor — do not use > redirection which ignores umask
```

### JSON payload format

Any valid JSON object whose keys match the placeholder names you will use in
templates:

```json
{
  "access_key_id":     "AKIAIOSFODNN7EXAMPLE",
  "secret_access_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "region":            "us-east-1"
}
```

---

## Declaring secrets in workstreams.yaml

Add a `secrets` list under the relevant Slack workspace entry:

```yaml
slackWorkspaces:
  - id: T01AB2CD3EF
    name: "My Workspace"
    secrets:
      - name: aws-prod
        file: /Users/Shared/flowtree/secrets/T01AB2CD3EF__aws-prod.json
      - name: gh-deploy-token
        file: /Users/Shared/flowtree/secrets/T01AB2CD3EF__gh-deploy-token.json
```

The `name` field is what agents use when calling the MCP tools.  The `file`
field is the absolute path to the JSON payload file on the controller host.

### Granting workspace access

Only workstreams whose Slack workspace ID (`slackWorkspaceId`) matches the
workspace that owns the secret can retrieve it.  There is no cross-workspace
sharing.  To give a workstream access to a secret, ensure its `slackWorkspaceId`
matches the `id` of the `slackWorkspaces` entry that declares the secret.

---

## Controller REST API

The controller exposes `/api/secrets/*` endpoints.  Normal agents use
workstream-scoped HMAC temporary tokens.  Admin operations (create, delete,
list by workspace) require the `SHARED_SECRET`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET` | `/api/secrets?workstream_id=` | temp token | List secret names for the workstream's workspace |
| `GET` | `/api/secrets/{name}?workstream_id=` | temp token | Retrieve a secret payload |
| `GET` | `/api/secrets?workspace_id=` | shared secret | Admin: list names for a workspace |
| `PUT` | `/api/secrets/{name}?workspace_id=` | shared secret | Admin: write/update a secret file |
| `DELETE` | `/api/secrets/{name}?workspace_id=` | shared secret | Admin: delete a secret file |

Retrieve responses include the JSON payload under a `payload` key:

```json
{
  "name": "aws-prod",
  "workspace_id": "T01AB2CD3EF",
  "payload": { "access_key_id": "...", "secret_access_key": "...", "region": "..." }
}
```

---

## MCP tools

Each topology has its own tool names.  **Coding agents use the `ar-secrets`
tools** (no `workspace_` prefix).  The `workspace_secret_*` tools on
`ar-manager` exist only for admin/Slack flows that run alongside the
controller and are deliberately not on the agent allowlist — calling them
from a coding agent will fail with a tool-denied error.  Pick the row that
matches where your caller runs:

| Caller runs in… | Server | Tool to list | Tool to render | Rendered file lands on… |
|----------------|--------|--------------|----------------|-------------------------|
| Agent container (`ClaudeCodeJob`, the default) | `ar-secrets` | `mcp__ar-secrets__secret_list_names` | `mcp__ar-secrets__secret_render_file` | Agent's filesystem |
| Controller host (admin tooling, Slack handlers) | `ar-manager` | `mcp__ar-manager__workspace_secret_list_names` | `mcp__ar-manager__workspace_secret_render_file` | Controller's filesystem |

The two pairs are functionally identical aside from where the rendered file
is written.  Both pairs talk to the same controller `/api/secrets/*`
endpoints; both omit payload values from every response and audit line.

### Coding-agent tools (`ar-secrets`)

This is what an agent launched by `ClaudeCodeJob` should call.  No
`workspace_id` argument — the workstream is read from the
`AR_WORKSTREAM_ID` environment variable that `ClaudeCodeJob` injects.

#### `secret_list_names`

Returns the names of all secrets available to the current workstream's
workspace.  Payloads are **never** returned.

```python
secret_list_names()
# {"ok": True, "names": ["aws-prod", "gh-deploy-token"]}
```

#### `secret_render_file`

Fetches a secret and renders a template into a file **on the agent's host**.
The `template` string uses `{{key}}` placeholders matching keys in the JSON
payload.  **Rendered content is never returned to the caller.**

```python
secret_render_file(
    secret_name = "aws-prod",
    template    = "[default]\naws_access_key_id={{access_key_id}}\naws_secret_access_key={{secret_access_key}}\nregion={{region}}\n",
    output_path = "~/.aws/credentials",
    mode        = "0600",       # optional, defaults to "0600"
)
# {"ok": True, "output_path": "/home/agent/.aws/credentials"}
```

### Controller/admin tools (`ar-manager`) — not callable by coding agents

Use these only when the caller already runs alongside the controller — for
example, Slack command handlers or administrative scripts where the
rendered file is meant to land on the controller host.  Both require a
`workstream_id` argument because the caller is not bound to a workstream
by environment.

`flowtree/src/main/java/io/flowtree/jobs/McpConfigBuilder.java` keeps both
of these on the agent allowlist's exclusion set
(`EXCLUDED_AR_MANAGER_TOOLS`), so a `ClaudeCodeJob` agent that calls them
will see a tool-denied error.  If you see that error, the agent has the
wrong tool name — switch to the `ar-secrets` pair above.

#### `workspace_secret_list_names`

```python
workspace_secret_list_names(workstream_id="ws-my-project")
# {"ok": True, "names": ["aws-prod", "gh-deploy-token"]}
```

#### `workspace_secret_render_file`

Renders into a file **on the controller's host**:

```python
workspace_secret_render_file(
    workstream_id = "ws-my-project",
    secret_name   = "aws-prod",
    template      = "[default]\naws_access_key_id={{access_key_id}}\naws_secret_access_key={{secret_access_key}}\nregion={{region}}\n",
    output_path   = "/Users/Shared/flowtree/rendered/aws-credentials",
    mode          = "0600",
)
# {"ok": True, "output_path": "/Users/Shared/flowtree/rendered/aws-credentials"}
```

### Shared behaviour

**Strict placeholder matching** (both pairs): every `{{key}}` in the
template must exist as a key in the JSON payload — a missing payload key
causes an error and no file is written.  Extra keys present in the payload
but not referenced by the template are silently ignored, so a single
secret can carry more keys than any one template needs.

---

## Worked example — AWS credentials

### 1. Create the secret file on the controller host

```bash
install -m 600 /dev/null /Users/Shared/flowtree/secrets/T01AB2CD3EF__aws-prod.json
cat > /tmp/aws-creds.json <<'EOF'
{
  "access_key_id":     "AKIAIOSFODNN7EXAMPLE",
  "secret_access_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "region":            "us-east-1"
}
EOF
# Copy via editor or scp — never paste secrets into terminal history
```

### 2. Declare the secret in workstreams.yaml

```yaml
slackWorkspaces:
  - id: T01AB2CD3EF
    name: "Engineering"
    secrets:
      - name: aws-prod
        file: /Users/Shared/flowtree/secrets/T01AB2CD3EF__aws-prod.json
```

### 3. Restart the controller to pick up the new config

```bash
# Restart FlowTree controller so it rebuilds the secrets cache
```

### 4. Agent usage

The coding agent running inside a `ClaudeCodeJob` on workstream
`ws-deploy-prod` (which belongs to workspace `T01AB2CD3EF`) calls the
**`ar-secrets`** tools.  No `workstream_id` argument — `AR_WORKSTREAM_ID`
is already set in the agent's environment.

```python
# Step 1: confirm the secret exists
secret_list_names()
# → {"ok": True, "names": ["aws-prod"]}

# Step 2: render into ~/.aws/credentials on the agent's host
secret_render_file(
    secret_name = "aws-prod",
    template    = "[default]\naws_access_key_id={{access_key_id}}\naws_secret_access_key={{secret_access_key}}\nregion={{region}}\n",
    output_path = "~/.aws/credentials",
)
# → {"ok": True, "output_path": "/home/agent/.aws/credentials"}

# Step 3: run AWS CLI — it reads the file directly; credentials never enter agent context
```

Agents must not call `workspace_secret_list_names` /
`workspace_secret_render_file`.  Those are the `ar-manager` variants for
admin tooling that runs on the controller; they are excluded from the
`ClaudeCodeJob` allowlist and will return a tool-denied error.

---

## Security properties

- **Payloads stay on the writer's host**: the renderer (whichever server is
  in use) fetches the JSON payload from the controller over an authenticated
  channel and performs template substitution locally.  Only the rendered
  file path crosses the MCP channel back to the caller; the values never do.
  For `ar-secrets` the write lands on the agent's host (the recommended
  path for coding agents); for `ar-manager`'s `workspace_secret_render_file`
  the write lands on the controller's host.
- **Audit log**: every retrieve call logs `secret_access` with the secret name,
  workstream ID, job ID, and workspace ID — but never the payload values.
- **Scope enforcement**: agents must hold a `read`-or-higher scope token and the
  workstream must be in their token's scope before any secret operation is allowed.
- **Workspace isolation**: a workstream in workspace A cannot access secrets
  declared under workspace B.
- **File permissions**: the controller enforces `0600` on write and warns at
  startup for any existing file with looser permissions.
