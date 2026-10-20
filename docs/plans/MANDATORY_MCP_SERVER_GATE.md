# Mandatory MCP Server Gate

**Status:** proposal, needs a human to apply (agent sessions cannot write under
`.claude/hooks/` or `.claude/settings.json`)

## Problem

CLAUDE.md Rule 4 names five MCP servers as mandatory (ar-manager,
ar-build-validator, ar-test-runner, ar-jmx, ar-profile-analyzer) and requires a
session to stop when one is missing. Nothing enforces that. In a session where
all five had disconnected, an agent spent tool calls on a task instead of
stopping, and no hook intervened:

- `enforce-consultant-first.sh` only warns, and only before Edit/Write of
  computation-layer Java. A Bash read never reaches it.
- `session-start-tools.sh` lists local binaries, not MCP connections.
- Every hook whose matcher names an MCP tool runs after that tool is called.
  When the server is gone the tool is never called, so those hooks never run.

The owner's rule is stricter than Rule 4's wording: the absence of a mandated
tool means no tokens are to be spent until the owner says otherwise. The
"no user present" exception must not be available to an interactive session.

## Proposed change

Add a blocking gate that runs before every tool call (a `PreToolUse` hook with
a catch-all matcher) and before the turn starts (`UserPromptSubmit`):

1. Determine which of the five servers are connected. This needs a source the
   harness exposes to hooks (for example, the session's MCP status as reported
   by the CLI, or a heartbeat file each server's wrapper refreshes). Which
   source is reliable must be established first; guessing from config files
   only shows what is configured, not what is connected.
2. If any is missing, block with a message naming the missing servers and
   telling the agent to report to the user and stop.
3. Allow only the tool calls needed to tell the user, so the block cannot
   strand the session silently.
4. Lift the block only on an explicit owner override recorded for the session,
   never on agent judgment.

## Open question

How a hook can observe MCP connection state. Until that is answered, the gate
cannot be written correctly.
