# MCP Server Development Guidelines

## CRITICAL: Adding a New Tool to server.py

**Every single time an agent has added a new MCP tool to `tools/mcp/manager/server.py`, it has
failed to register it correctly. The tool appeared in the source code but was invisible to MCP
clients. This has happened with `dependent_repos`, `required_labels`,
`github_request_copilot_review`, and others. NOT ONCE was a new tool introduced correctly on the
first attempt.**

Before committing, verify all three requirements below. There is a test suite in
`flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` that catches violations.

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
mvn test -pl flowtree -Dtest=McpToolDiscoveryTest
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
7. Run `mvn test -pl flowtree -Dtest=McpToolDiscoveryTest` and confirm all tests pass.
8. Run `python -m pytest tools/mcp/manager/test_server.py` and confirm all tests pass.

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
