# Plan: .claude Rules for MCP Tool Registration

## Problem Statement

Every time an agent adds a new MCP tool to `tools/mcp/manager/server.py`, the tool fails to
appear in the MCP tool registry because the agent makes one or more of these mistakes:

1. Defines the function without the `@mcp.tool()` decorator
2. Accepts parameters inside the function body (via `**kwargs`) rather than in the function signature
3. Writes an incomplete or missing docstring

This has happened with `dependent_repos`, `required_labels`, `github_request_copilot_review`, and
others. The systematic fix is a combination of an enforcement test (already implemented in
`McpToolDiscoveryTest`) and rules in `.claude` that fire before the agent makes the mistake.

---

## Rules to Add to `.claude`

The project owner should add these rules to the appropriate `.claude` configuration. The agent
cannot modify `.claude` rules itself (they are not in version control in a form agents can edit),
so this document provides the exact text and location.

### Rule 1: Mandatory @mcp.tool() Decorator Check

**Where:** `.claude/commands/` or a hook triggered when `tools/mcp/manager/server.py` is edited.

**Rule text to add to CLAUDE.md or a project-level instruction:**

```
When adding any function to tools/mcp/manager/server.py that is intended to be an MCP tool:

STEP 1 — DECORATOR: The function MUST be immediately preceded by @mcp.tool() with no blank
lines between the decorator and the def statement. Do not skip this step.

STEP 2 — SIGNATURE: Every parameter the caller can provide MUST be declared in the function
signature with a type hint and a default value. Do not accept parameters via **kwargs.

STEP 3 — DOCSTRING: Write a docstring with a one-line summary, Args: section for every
parameter, and Returns: section.

STEP 4 — TEST: Add the tool name to the expected set in
McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy (Java) AND to the
expected set in TestToolRegistration.test_expected_tool_count (Python test_server.py).

STEP 5 — VERIFY: Run `mvn test -pl flowtree -Dtest=McpToolDiscoveryTest` before declaring done.
```

### Rule 2: Pre-Commit Verification Hook

**Where:** `.claude/hooks/` or `.claude/settings.json` post-edit hook for `server.py`.

**What the hook should do:**

When `tools/mcp/manager/server.py` is saved/written, automatically run:
```bash
cd /path/to/almostrealism-common
mvn test -pl flowtree -Dtest=McpToolDiscoveryTest#managerAllExpectedToolsAreRegisteredInServerPy
```

If this fails, block the commit and show the error.

**Implementation in `.claude/settings.json`:**
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "if echo \"$CLAUDE_TOOL_ARGS\" | grep -q 'tools/mcp/manager/server.py'; then cd /path/to/project && mvn test -pl flowtree -Dtest=McpToolDiscoveryTest -q && echo 'MCP tool registry check PASSED' || echo 'MCP TOOL REGISTRY CHECK FAILED — run McpToolDiscoveryTest for details'; fi"
          }
        ]
      }
    ]
  }
}
```

### Rule 3: Checklist Trigger in CLAUDE.md

**Where:** `tools/mcp/manager/CLAUDE.md` (create if it does not exist) or in the project root
`CLAUDE.md` under a "MCP Servers" section.

**Rule text:**

```markdown
## Adding a New MCP Tool

BEFORE committing any new function in tools/mcp/manager/server.py, run this checklist:

- [ ] The function is decorated with `@mcp.tool()` on the line directly above `def`
- [ ] Every parameter is declared in the function signature (NOT in **kwargs)
- [ ] Type hints are present for every parameter
- [ ] Default values are present for every optional parameter
- [ ] A complete docstring is present with Args: and Returns: sections
- [ ] The tool name is added to the expected set in McpToolDiscoveryTest.java
- [ ] The tool name is added to test_expected_tool_count in test_server.py
- [ ] `mvn test -pl flowtree -Dtest=McpToolDiscoveryTest` passes
- [ ] `python -m pytest tools/mcp/manager/test_server.py::TestToolRegistration` passes

REFERENCE: See tools/mcp/CLAUDE.md for the exact pattern to follow.
```

---

## Enforcement: What Is Already in Place

These safeguards already exist on the `feature/devtools-qa` branch and will be merged to master:

| Safeguard | Location | What it catches |
|-----------|----------|-----------------|
| `McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy` | `flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` | Tool function missing `@mcp.tool()` decorator |
| `McpToolDiscoveryTest.managerToolParametersAreProperlyDeclaredInSignatures` | `flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` | Key parameters missing from function signature |
| `McpToolDiscoveryTest.managerRegisterAndUpdateConfigHaveRequiredLabelsAndDependentRepos` | `flowtree/src/test/java/io/flowtree/jobs/McpToolDiscoveryTest.java` | `required_labels` and `dependent_repos` missing from workstream tools |
| `TestToolRegistration.test_expected_tool_count` | `tools/mcp/manager/test_server.py` | Any tool in the expected set not in the actual registry |
| `TestGithubRequestCopilotReview` | `tools/mcp/manager/test_server.py` | `github_request_copilot_review` tool behavior |
| `tools/mcp/CLAUDE.md` | `tools/mcp/CLAUDE.md` | Human/agent-readable instructions |

The Java tests (`McpToolDiscoveryTest`) run as part of the `flowtree` module in CI. They
succeed only if the server.py file's tool registrations match the expected inventory.

---

## Why This Keeps Happening

The root cause is that FastMCP makes registration look optional:

```python
# Looks harmless — but without @mcp.tool(), this function is INVISIBLE
def github_request_copilot_review(pr_number: int = 0) -> dict:
    """Request Copilot review."""
    ...
```

There is no syntax error, no import error, no startup error. The server starts fine. The function
works if called directly from Python. Only when an MCP client queries the tool list does the
function fail to appear.

Agents write the function, test it by calling it directly, see it works, and mark the task done —
without ever checking the MCP tool registry. The `.claude` rules above address this by making the
registry check an explicit, required step.

---

## Priority

This should be treated as high-priority. The failing-to-register pattern wastes developer review
cycles on every PR that adds a new tool. The enforcement test is a one-time fix that pays
compounding dividends. Add the `.claude` rules as soon as the `feature/devtools-qa` branch is
merged.
