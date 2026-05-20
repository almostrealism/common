# MCP Server Development Guidelines

## CRITICAL: Adding a New Tool to server.py

**Every single time an agent has added a new MCP tool to `tools/mcp/manager/server.py`, it has
failed to register it correctly. The tool appeared in the source code but was invisible to MCP
clients. This has happened with `dependent_repos`, `required_labels`,
`github_request_copilot_review`, and others. NOT ONCE was a new tool introduced correctly on the
first attempt.**

Before committing, verify all three requirements below. There is a test suite in
`flowtree/runtime/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` that catches violations.

---

## The Three Requirements

### Requirement 1: The `@mcp.tool()` Decorator

Every tool function **MUST** be immediately preceded by `@mcp.tool()` on the line directly above
the `def` statement. Without the decorator the function is a plain Python function, not an MCP
tool, and MCP clients cannot see it.

**Correct:**
```python
@mcp.tool()
def github_request_copilot_review(
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Request a GitHub Copilot automated code review on a pull request.
    ...
    """
    ...
```

**Wrong (function exists but will be invisible to MCP clients):**
```python
# Missing @mcp.tool() — this is just a plain Python function
def github_request_copilot_review(pr_number: int = 0) -> dict:
    ...
```

### Requirement 2: Parameters in the Function Signature

Every parameter the tool accepts **MUST** be declared in the function signature with a type hint
and a default value. FastMCP generates the MCP JSON schema from the signature. If a parameter is
consumed inside the function body (e.g., pulled from `**kwargs` or `locals()`) it will be absent
from the schema and invisible to MCP clients.

**Correct — parameter declared in signature:**
```python
@mcp.tool()
def workstream_register(
    default_branch: str,
    base_branch: str = "master",
    required_labels: str = "",    # <-- declared here, appears in MCP schema
    dependent_repos: str = "",    # <-- declared here, appears in MCP schema
) -> dict:
    ...
    labels = _parse_required_labels(required_labels)
```

**Wrong — parameter handled in body but not in signature (schema will omit it):**
```python
@mcp.tool()
def workstream_register(
    default_branch: str,
    base_branch: str = "master",
    **kwargs,                     # <-- required_labels/dependent_repos hidden here
) -> dict:
    required_labels = kwargs.get("required_labels", "")
```

### Requirement 3: A Complete Docstring

FastMCP uses the docstring for the tool's description in the schema. An absent or malformed
docstring results in no description being shown to the model. Include:

- A one-line summary
- A blank line
- An `Args:` section listing every parameter
- A `Returns:` section

Follow the pattern of `workstream_submit_task` (line ~911 of `server.py`) exactly.

---

## JSON-object parameters: `runners`, `requiredLabels`, etc.

Several MCP tools (`workstream_submit_task`, `workstream_register`,
`workstream_update_config`) accept structured data through string-typed
parameters that the tool then parses locally:

- `required_labels: str = ""` — a comma-separated `key:value` CSV.
- `dependent_repos: str = ""` — a comma-separated list of git URLs.
- `runners: str = ""` — a JSON object whose keys are
  [`Phase`](../../flowtree/agents/src/main/java/io/flowtree/jobs/agent/Phase.java)
  wire names (`"primary"`, `"deduplication"`, `"organizational-placement"`,
  `"enforce-changes"`, `"maven-dependency-protection"`,
  `"post-completion"`, `"commit-message"`, `"git-tampering-restart"`) plus
  an optional `"default"` key. Values are runner identifiers
  (`"claude"`, `"opencode"`, ...). The tool parses the string with
  `json.loads`, validates phase names against the enum, and forwards the
  decoded object to the controller via the `runners` field in the
  submission payload.
- `default_runner: str = ""` — convenience shortcut equivalent to
  `runners='{"default": "<value>"}'`. The explicit `runners["default"]`
  wins when both are supplied.

When adding a new structured parameter, follow the pattern from
`_parse_runners_json` in `tools/mcp/manager/server.py`: parse, validate
locally, and return a 400-style `{"ok": False, "error": "..."}` dict on
shape errors so the caller fails fast instead of waiting on a controller
round-trip.

## The Exact Pattern to Follow

Copy the structure of `workstream_submit_task` when adding a new tool:

```python
@mcp.tool()
def your_new_tool(
    required_param: str,
    optional_param: str = "",
    optional_bool: bool = False,
) -> dict:
    """One-line summary of what this tool does.

    More detailed explanation if needed. Describe when to use this
    tool and what it returns.

    Args:
        required_param: Description of this required parameter.
        optional_param: Description of this optional parameter. Defaults
            to empty string meaning X.
        optional_bool: If true, enables Y behaviour. Defaults to False.

    Returns:
        dict with ok=True on success or ok=False with error details.
    """
    _require_scope("write")
    _audit("your_new_tool", required_param=required_param)
    # ... implementation
    return {"ok": True}
```

---

## The Enforcement Test

After adding a new tool, verify it passes:

```bash
cd /path/to/almostrealism-common
mvn test -pl flowtree/runtime -Dtest=McpToolDiscoveryTest
```

The test `managerAllExpectedToolsAreRegisteredInServerPy` checks that every expected tool name
appears in the `@mcp.tool()` registry. When you add a new tool, also add its name to the
`expected` set in that test so future sessions cannot silently drop it.

The test `managerToolParametersAreProperlyDeclaredInSignatures` checks that key parameters for
important tools are declared in the function signature (not hidden in the body).

---

## What to Do When Adding a New Tool

1. Write the function with the `@mcp.tool()` decorator (Requirement 1 above).
2. Declare all parameters in the signature with type hints (Requirement 2 above).
3. Write a complete docstring (Requirement 3 above).
4. Add the tool name to the `expected` set in `McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy`.
5. Add parameter assertions to `McpToolDiscoveryTest.managerToolParametersAreProperlyDeclaredInSignatures` for any parameters that are not obvious (e.g., optional parameters that were historically missed).
6. Add the tool name to the `expected` set in `TestToolRegistration.test_expected_tool_count` in `tools/mcp/manager/test_server.py`.
7. **Update the agent allowlist in `flowtree/runtime/src/main/java/io/flowtree/jobs/McpConfigBuilder.java`.** Every new tool must be classified as either:
   - **Granted to agents:** add the bare tool name to `AR_MANAGER_TOOL_NAMES`. The Claude Code harness will then include `mcp__ar-manager__<name>` in the launched agent's `--allowedTools` list.
   - **Deliberately excluded:** add the tool name to `EXCLUDED_AR_MANAGER_TOOLS` (admin/orchestration tools, shared-state mutations, anything an autonomous coding agent should not invoke).

   The `allowlistCoversEveryArManagerTool` test in `McpConfigBuilderTest` fails when a tool exists in `server.py` but is in neither set, forcing this decision before merge. The historical failure mode was that new tools were registered on the server but never added to the harness allowlist, so the Claude Code subprocess silently blocked them — the test prevents that.
8. Run `mvn test -pl flowtree/runtime -Dtest=McpToolDiscoveryTest,McpConfigBuilderTest` and confirm all tests pass.
9. Run `python -m pytest tools/mcp/manager/test_server.py` and confirm all tests pass.

---

## Security Rules for Workspace Secrets

Two MCP servers expose secret handling and the rules below apply to **both
pairs without exception**:

- **`ar-secrets`** (stdio in the agent container) — `secret_list_names`,
  `secret_render_file`.  This is what coding agents launched by
  `CodingAgentJob` must call; the rendered file lands on the agent's host.
- **`ar-manager`** (HTTP on the controller) —
  `workspace_secret_list_names`, `workspace_secret_render_file`.  These
  exist for admin/Slack flows that run alongside the controller and are
  excluded from the coding-agent allowlist
  (`EXCLUDED_AR_MANAGER_TOOLS` in `McpConfigBuilder.java`).  An agent that
  reaches for the `workspace_secret_*` names will be denied by the harness;
  the fix is to call the `ar-secrets` pair, not to widen the allowlist.

See `tools/mcp/SECRETS.md` for the full topology and tool reference.

These rules exist because a single accidental echo of a secret into an
agent response, a log line, or a PR description permanently compromises
that credential.

### NEVER read the rendered file back into context

After calling `secret_render_file` (or `workspace_secret_render_file` in
admin tooling), the caller MUST NOT:
- Read the rendered file with any tool (`Read`, `Bash cat`, etc.)
- Echo or print its contents in any response
- Pass its path to a tool that returns file contents

The rendered file is an ephemeral on-disk resource for the process that follows.
Treat it as write-only from the caller's perspective.

### NEVER include secret values in commits, logs, or PR descriptions

Secret values must never appear in:
- Commit messages or commit diffs (`git show`, `git diff`)
- PR titles, descriptions, or review comments
- Log output from `Bash`, test runners, or any tool
- Agent responses, reasoning text, or tool call arguments

If you are about to write something that contains a value from a secret payload
— stop.  Replace it with a placeholder like `<REDACTED>` in any context that
persists beyond the current tool call.

### NEVER return payload values from controller helpers

Any Python helper in `ar-secrets` or `ar-manager` that fetches a
`/api/secrets/{name}` response MUST pass the `payload` only into the
template renderer — never into a return value that crosses the MCP channel
back to the caller.

### Audit log expectations

Every render call (`secret_render_file` on `ar-secrets`,
`workspace_secret_render_file` on `ar-manager`) writes a `secret_access`
audit line via `_audit()`.  The audit line MUST include workstream ID,
secret name, and job ID, but MUST NOT include any key/value from the
payload.  Verify this when adding new code paths that touch secret
payloads.

---

## Server Directory Map

| Directory | Server name | Tool registration pattern |
|-----------|-------------|---------------------------|
| `manager/` | ar-manager | `@mcp.tool()` decorator on each function |
| `consultant/` | ar-consultant | `@mcp.tool()` decorator on each function |
| `build-validator/` | ar-build-validator | `@mcp.tool()` decorator on each function |
| `test-runner/` | ar-test-runner | `@server.list_tools()` handler returning `Tool(name=...)` entries |
| `jmx/` | ar-jmx | `@server.list_tools()` handler returning `Tool(name=...)` entries |
| `memory/` | ar-memory | `@mcp.tool()` decorator on each function |
| `profile-analyzer/` | ar-profile-analyzer | `@mcp.tool()` decorator on each function |

`McpToolDiscovery.discoverToolNames()` handles all three patterns automatically.
