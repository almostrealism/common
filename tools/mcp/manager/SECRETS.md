# Workspace Secrets

Workspace secrets let agents read credential files from the FlowTree controller
host without those credentials ever appearing in agent output, prompts, PR
descriptions, or logs.  The agent requests that a secret be **rendered into a
file** at a path it supplies.  The controller stores and serves the JSON
payload; the ar-manager MCP server fetches that payload, substitutes it into
the supplied template, and writes the rendered file on the host where
ar-manager (and the agent) is running.  The agent receives only a
success/failure acknowledgement — never the credential values themselves.

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

### `workspace_secret_list_names`

Returns the names of all secrets available to a workstream's workspace.
Payloads are **never** returned.

```python
workspace_secret_list_names(workstream_id="ws-my-project")
# {"ok": True, "names": ["aws-prod", "gh-deploy-token"]}
```

### `workspace_secret_render_file`

Fetches a secret and renders a template into a file on the controller host.
The `template` string uses `{{key}}` placeholders matching keys in the JSON
payload.  **Rendered content is never returned to the caller.**

```python
workspace_secret_render_file(
    workstream_id = "ws-my-project",
    secret_name   = "aws-prod",
    template      = "[default]\naws_access_key_id={{access_key_id}}\naws_secret_access_key={{secret_access_key}}\nregion={{region}}\n",
    output_path   = "~/.aws/credentials",
    mode          = "0600",       # optional, defaults to "0600"
)
# {"ok": True, "output_path": "/home/agent/.aws/credentials"}
```

**Strict placeholder matching**: every `{{key}}` in the template must exist as
a key in the JSON payload — a missing payload key causes an error and no file
is written.  Extra keys present in the payload but not referenced by the
template are silently ignored, so a single secret can carry more keys than any
one template needs.

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

The agent running inside a job on workstream `ws-deploy-prod` (which belongs to
workspace `T01AB2CD3EF`) calls:

```python
# Step 1: confirm the secret exists
workspace_secret_list_names(workstream_id="ws-deploy-prod")
# → {"ok": True, "names": ["aws-prod"]}

# Step 2: render into ~/.aws/credentials
workspace_secret_render_file(
    workstream_id = "ws-deploy-prod",
    secret_name   = "aws-prod",
    template      = "[default]\naws_access_key_id={{access_key_id}}\naws_secret_access_key={{secret_access_key}}\nregion={{region}}\n",
    output_path   = "~/.aws/credentials",
)
# → {"ok": True, "output_path": "/home/agent/.aws/credentials"}

# Step 3: run AWS CLI — it reads the file directly; credentials never enter agent context
```

---

## Security properties

- **Payloads stay on the agent host**: `workspace_secret_render_file` writes
  the rendered file on the host where the ar-manager MCP server runs (which
  is also where the calling agent runs).  The values are fetched from the
  controller over an authenticated channel and substituted into the template
  inside ar-manager — they never cross the MCP channel back to the agent.
- **Audit log**: every retrieve call logs `secret_access` with the secret name,
  workstream ID, job ID, and workspace ID — but never the payload values.
- **Scope enforcement**: agents must hold a `read`-or-higher scope token and the
  workstream must be in their token's scope before any secret operation is allowed.
- **Workspace isolation**: a workstream in workspace A cannot access secrets
  declared under workspace B.
- **File permissions**: the controller enforces `0600` on write and warns at
  startup for any existing file with looser permissions.
