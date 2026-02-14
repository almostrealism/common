# Slack Slash Commands - Design Document

This document outlines the plan for adding native Slack slash command support to the FlowtTree Slack bot controller.

---

## Current State

The bot currently handles commands through **app mention parsing** in `SlackListener`. When a user mentions the bot or sends a direct message, the `COMMAND_PATTERN` regex (`^/(\w+)(?:\s+(.*))?$`) matches text starting with `/` and dispatches to `handleCommand()`.

### Existing Commands (in-message only)

| Command  | Handler                | Status      |
|----------|------------------------|-------------|
| `/status` | `handleStatusCommand()` | Working     |
| `/cancel` | `handleCancelCommand()` | Stubbed (TODO) |
| `/task`   | `submitJob()`          | Working     |
| `/do`     | `submitJob()`          | Working     |
| `/run`    | `submitJob()`          | Working     |

These are **not** true Slack slash commands. They are plain text patterns recognized within `app_mention` or `message.im` events. Users type `@flowtree.io /status` and the listener picks it up.

### Limitations of In-Message Commands

1. **No autocomplete** - Users must remember command syntax
2. **No privacy** - Messages are visible to the entire channel
3. **No ephemeral responses** - Status output is posted publicly
4. **No Slack-native help** - No `/help` integration
5. **Requires @mention** - Cannot use bare `/command` syntax

---

## Proposed Design

### Phase 1: Register Slash Commands in Slack App

Update the Slack app manifest (`flowtree/src/main/resources/slack-app-manifest.json`) to register native slash commands. Since the bot uses **Socket Mode**, slash commands are received as events over the WebSocket connection -- no public HTTP endpoint is needed.

**Manifest additions:**
```json
{
  "features": {
    "slash_commands": [
      {
        "command": "/ft-status",
        "description": "Show workstream status and connected agents",
        "usage_hint": ""
      },
      {
        "command": "/ft-task",
        "description": "Submit a task to a coding agent",
        "usage_hint": "[prompt]"
      },
      {
        "command": "/ft-cancel",
        "description": "Cancel a running job",
        "usage_hint": "[job-id]"
      },
      {
        "command": "/ft-config",
        "description": "View or update workstream configuration",
        "usage_hint": "[key] [value]"
      },
      {
        "command": "/ft-jobs",
        "description": "List recent jobs and their status",
        "usage_hint": ""
      }
    ]
  }
}
```

**Note:** Commands use the `/ft-` prefix to avoid collisions with other Slack apps. The prefix stands for "flowtree".

**Required OAuth scope:** `commands` (must be added to the manifest's `oauth_config.scopes.bot` list).

### Phase 2: Handle Slash Commands in SlackBotController

The Slack Bolt SDK delivers slash command events via `app.command()` handlers. Add registration in `SlackBotController.start()`:

```java
app.command("/ft-status", (req, ctx) -> {
    String channelId = req.getPayload().getChannelId();
    listener.handleSlashCommand("status", null, channelId, ctx);
    return ctx.ack();
});

app.command("/ft-task", (req, ctx) -> {
    String channelId = req.getPayload().getChannelId();
    String text = req.getPayload().getText();
    listener.handleSlashCommand("task", text, channelId, ctx);
    return ctx.ack();
});
```

### Phase 3: Extend SlackListener with Slash Command Handling

Add a new public method to `SlackListener` that handles slash command dispatch. This method shares the same `handleCommand()` logic but supports ephemeral responses (visible only to the invoking user).

```java
/**
 * Handles a Slack slash command.
 *
 * @param command   the command name (without the /ft- prefix)
 * @param args      the command arguments (may be null)
 * @param channelId the channel where the command was invoked
 * @param ctx       the Bolt command context for ephemeral responses
 */
public void handleSlashCommand(String command, String args,
                                String channelId, CommandContext ctx) {
    SlackWorkstream workstream = channelToWorkstream.get(channelId);
    if (workstream == null) {
        ctx.respond(":warning: No workstream configured for this channel.");
        return;
    }

    switch (command.toLowerCase()) {
        case "status":
            ctx.respond(buildStatusMessage(workstream));
            break;
        case "task":
            // Submit job, respond ephemerally with confirmation
            break;
        case "cancel":
            // Cancel job
            break;
        case "config":
            // Show or update config
            break;
        case "jobs":
            // List recent jobs
            break;
        default:
            ctx.respond(":warning: Unknown command: " + command);
    }
}
```

### Phase 4: New Commands

#### `/ft-jobs` - List Recent Jobs

Show recently submitted jobs and their current status. Requires tracking active and completed jobs in memory.

**Implementation notes:**
- Add a `Map<String, JobCompletionEvent>` to `SlackListener` or a new `JobTracker` class
- `SlackNotifier.onJobStarted()` and `onJobCompleted()` already receive events -- add tracking there
- Display the last N jobs (default 10) with status emoji, job ID, description, and elapsed time

**Example output:**
```
Recent Jobs
  :white_check_mark: abc123 - Fix auth bug (2m 34s)
  :hourglass_flowing_sand: def456 - Implement caching layer (running)
  :x: ghi789 - Refactor database queries (failed, exit 1)
```

#### `/ft-cancel` - Cancel a Running Job

Cancel a specific job by ID. Requires sending a cancellation signal to the agent running the job.

**Implementation notes:**
- The `ClaudeCodeJob` harness script supports receiving a cancel signal
- The controller needs a way to route the cancel to the correct agent node
- If no job ID is given, cancel the most recent job for the workstream

#### `/ft-config` - View/Update Workstream Configuration

View or update workstream settings at runtime without restarting the controller.

**Example usage:**
```
/ft-config                        → Show all settings
/ft-config maxBudgetUsd           → Show specific setting
/ft-config maxBudgetUsd 20.0      → Update setting
/ft-config defaultBranch feature/x → Update branch
```

**Modifiable settings (via setters on SlackWorkstream):**
- `maxBudgetUsd`
- `maxTurns`
- `defaultBranch`
- `pushToOrigin`
- `allowedTools`

**Non-modifiable settings (informational only):**
- `workstreamId`
- `channelId`
- `channelName`

---

## Architecture Decisions

### Ephemeral vs. Public Responses

| Command     | Response Type | Rationale                                    |
|-------------|---------------|----------------------------------------------|
| `/ft-status` | Ephemeral     | Status is informational, avoids channel noise |
| `/ft-task`   | Public        | Team should see that a task was submitted     |
| `/ft-jobs`   | Ephemeral     | Informational query, avoids noise             |
| `/ft-cancel` | Public        | Team should see cancellations                 |
| `/ft-config` | Ephemeral     | Administrative, avoids noise                  |

### Backward Compatibility

In-message commands (`@flowtree.io /status`, etc.) will continue to work. The slash command handlers share the same underlying logic. No existing behavior is removed.

### Slash Commands vs. App Mention Commands

Both paths converge on the same `handleCommand()` method. The difference is how responses are delivered:

- **App mention:** Response is a regular channel message via `SlackNotifier.postMessage()`
- **Slash command:** Response can be ephemeral (only visible to invoker) via `ctx.respond()`

---

## Files to Modify

| File | Change |
|------|--------|
| `flowtree/src/main/resources/slack-app-manifest.json` | Add `slash_commands` section and `commands` scope |
| `flowtree/src/main/java/io/flowtree/slack/SlackBotController.java` | Register `app.command()` handlers |
| `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` | Add `handleSlashCommand()`, extract `buildStatusMessage()`, add job tracking |
| `flowtree/src/main/java/io/flowtree/slack/SlackNotifier.java` | Add job tracking hooks for `/ft-jobs` |
| `flowtree/src/test/java/io/flowtree/slack/SlackIntegrationTest.java` | Add tests for slash command handling |

---

## Implementation Order

1. Update the Slack app manifest with slash command definitions and `commands` scope
2. Add `app.command()` handlers in `SlackBotController`
3. Add `handleSlashCommand()` to `SlackListener` with ephemeral response support
4. Implement `/ft-status` (simplest, reuses existing logic)
5. Implement `/ft-task` (reuses existing `submitJob()`)
6. Implement `/ft-jobs` with job tracking
7. Implement `/ft-cancel` with agent routing
8. Implement `/ft-config` with runtime settings
9. Add tests for all new commands
10. Update `docs/slack-integration.md` with slash command documentation

---

## Open Questions

1. **Command prefix:** `/ft-` is proposed to avoid collisions. Alternatives: `/flowtree-`, `/agent-`, or unprefixed if the workspace is dedicated.
2. **Job tracking retention:** How many completed jobs should be kept in memory? Proposed: last 100 per workstream, configurable.
3. **Config persistence:** Should `/ft-config` changes persist to the YAML file, or only apply until restart? Proposed: runtime-only initially, with a `/ft-config save` subcommand later.
4. **Permissions:** Should certain commands (e.g., `/ft-config`, `/ft-cancel`) require specific Slack user roles? Not in Phase 1, but worth considering.
