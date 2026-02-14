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
6. **No workstream creation** - Cannot create a new workstream from Slack; all workstreams must be pre-configured in the YAML file

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
        "command": "/flowtree",
        "description": "Manage the workstream for this channel",
        "usage_hint": "setup <dir> <branch> | info | status | task <prompt> | cancel [job-id] | config [key] [value] | jobs"
      }
    ]
  }
}
```

**Note:** All functionality is unified under a single `/flowtree` command with subcommands. This avoids registering many top-level commands in the Slack workspace and makes the command namespace self-documenting.

**Required OAuth scope:** `commands` (must be added to the manifest's `oauth_config.scopes.bot` list).

### Phase 2: Handle Slash Command in SlackBotController

The Slack Bolt SDK delivers slash command events via `app.command()` handlers. Add a single registration in `SlackBotController.start()`:

```java
app.command("/flowtree", (req, ctx) -> {
    String channelId = req.getPayload().getChannelId();
    String channelName = req.getPayload().getChannelName();
    String text = req.getPayload().getText();
    listener.handleSlashCommand(text, channelId, channelName, ctx);
    return ctx.ack();
});
```

### Phase 3: Extend SlackListener with Slash Command Handling

Add a new public method to `SlackListener` that parses the subcommand from the text and dispatches accordingly. This method supports ephemeral responses (visible only to the invoking user) for informational commands and public responses for actions that the team should see.

```java
/**
 * Handles the /flowtree slash command.
 *
 * @param text        the full command text after "/flowtree " (e.g., "setup /workspace feature/x")
 * @param channelId   the channel where the command was invoked
 * @param channelName the human-readable channel name (from Slack payload)
 * @param ctx         the Bolt command context for ephemeral responses
 */
public void handleSlashCommand(String text, String channelId,
                                String channelName, CommandContext ctx) {
    String[] parts = (text != null ? text.trim() : "").split("\\s+", 2);
    String subcommand = parts.length > 0 ? parts[0].toLowerCase() : "";
    String args = parts.length > 1 ? parts[1] : null;

    switch (subcommand) {
        case "setup":
            handleSetupCommand(channelId, channelName, args, ctx);
            break;
        case "info":
            handleInfoCommand(channelId, ctx);
            break;
        case "status":
            handleSlashStatusCommand(channelId, ctx);
            break;
        case "task":
            handleSlashTaskCommand(channelId, args, ctx);
            break;
        case "cancel":
            handleSlashCancelCommand(channelId, args, ctx);
            break;
        case "config":
            handleSlashConfigCommand(channelId, args, ctx);
            break;
        case "jobs":
            handleSlashJobsCommand(channelId, ctx);
            break;
        default:
            ctx.respond(":information_source: *Flowtree Commands*\n"
                + "  `/flowtree setup <directory> <branch>` — Set up a workstream for this channel\n"
                + "  `/flowtree info` — Show workstream details\n"
                + "  `/flowtree status` — Show agent status\n"
                + "  `/flowtree task <prompt>` — Submit a task\n"
                + "  `/flowtree cancel [job-id]` — Cancel a running job\n"
                + "  `/flowtree config [key] [value]` — View or update settings\n"
                + "  `/flowtree jobs` — List recent jobs");
    }
}
```

### Phase 4: Workstream Management Commands (Priority)

These are the highest-priority commands because they remove the requirement for pre-configured YAML entries, allowing workstreams to be created directly from Slack.

#### `/flowtree setup` - Create or Update Workstream

Creates a new workstream for the current channel, or updates the working directory and branch of an existing one. This is the key command that enables workstream creation from Slack without editing the YAML configuration file.

**Syntax:**
```
/flowtree setup <working-directory> <branch>
```

**Examples:**
```
/flowtree setup /workspace/project/common feature/my-work
/flowtree setup /workspace/project/common develop
```

**Behavior:**

1. If no workstream exists for the channel:
   - Create a new `SlackWorkstream` with a generated UUID
   - Set `channelId` from the Slack payload
   - Set `channelName` from the Slack payload (e.g., `#my-channel`)
   - Set `workingDirectory` and `defaultBranch` from the command arguments
   - Apply default values for other settings (`maxBudgetUsd: 10.0`, `maxTurns: 50`, etc.)
   - Register the workstream with `SlackListener`
   - Persist the new entry to the YAML config file (if one is loaded)
   - Respond with confirmation including the new workstream ID

2. If a workstream already exists for the channel:
   - Update `workingDirectory` and `defaultBranch` with the new values
   - Persist the changes to the YAML config file (if one is loaded)
   - Respond with confirmation showing old and new values

**Implementation notes:**
- `SlackListener` needs a reference to the `WorkstreamConfig` and config `File` so it can persist changes
- Add a `createOrUpdateWorkstream(channelId, channelName, workingDirectory, branch)` method to `SlackListener`
- The method should call `WorkstreamConfig.saveToYaml(file)` after making changes to ensure persistence across restarts
- The new workstream inherits global defaults but can be customized later via `/flowtree config`

**Example handler:**
```java
private void handleSetupCommand(String channelId, String channelName,
                                 String args, CommandContext ctx) {
    if (args == null || args.trim().isEmpty()) {
        ctx.respond(":warning: Usage: `/flowtree setup <working-directory> <branch>`\n"
            + "Example: `/flowtree setup /workspace/project feature/my-work`");
        return;
    }

    String[] setupArgs = args.trim().split("\\s+", 2);
    if (setupArgs.length < 2) {
        ctx.respond(":warning: Both working directory and branch are required.\n"
            + "Usage: `/flowtree setup <working-directory> <branch>`");
        return;
    }

    String workingDirectory = setupArgs[0];
    String branch = setupArgs[1];

    SlackWorkstream existing = channelToWorkstream.get(channelId);
    if (existing != null) {
        String oldDir = existing.getWorkingDirectory();
        String oldBranch = existing.getDefaultBranch();
        existing.setWorkingDirectory(workingDirectory);
        existing.setDefaultBranch(branch);
        persistConfig();

        ctx.respond(":white_check_mark: *Workstream updated*\n"
            + "   Working directory: `" + oldDir + "` → `" + workingDirectory + "`\n"
            + "   Branch: `" + oldBranch + "` → `" + branch + "`");
    } else {
        SlackWorkstream ws = new SlackWorkstream(channelId, channelName);
        ws.setWorkingDirectory(workingDirectory);
        ws.setDefaultBranch(branch);
        registerWorkstream(ws);
        persistConfig();

        ctx.respond(":white_check_mark: *Workstream created*\n"
            + "   Workstream ID: `" + ws.getWorkstreamId() + "`\n"
            + "   Channel: " + channelName + "\n"
            + "   Working directory: `" + workingDirectory + "`\n"
            + "   Branch: `" + branch + "`\n"
            + "   Max budget: $" + String.format("%.2f", ws.getMaxBudgetUsd()) + "\n"
            + "   Max turns: " + ws.getMaxTurns());
    }
}
```

#### `/flowtree info` - Display Workstream Details

Displays the full configuration of the workstream associated with the current channel, including all settings and their current values.

**Syntax:**
```
/flowtree info
```

**Example output:**
```
Workstream Details
   Workstream ID: cf9ead4c-ab39-421b-92a0-42e5ccc3d474
   Channel: #project-agent (C0123456789)
   Working directory: /workspace/project/common
   Branch: feature/my-work
   Push to origin: true
   Allowed tools: Read,Edit,Write,Bash,Glob,Grep
   Max turns: 50
   Max budget: $10.00
   Git user: CI Bot <ci-bot@example.com>
```

**Behavior when no workstream exists:**
```
:warning: No workstream configured for this channel.
Use `/flowtree setup <working-directory> <branch>` to create one.
```

**Implementation notes:**
- This is a read-only informational command
- Displays all `SlackWorkstream` fields including optional ones (git identity, env vars)
- Omits null/unset optional fields from the output for clarity

### Phase 5: Existing Commands (Migrated to `/flowtree` Subcommands)

The existing in-message commands are exposed as `/flowtree` subcommands. The underlying logic is shared.

#### `/flowtree status` - Agent and Connection Status

Shows the number of connected agents and basic connectivity status. This is a subset of `/flowtree info` focused on runtime state rather than configuration.

**Example output:**
```
Agent Status
   Connected agents: 2
   Channel: #project-agent
   Branch: feature/my-work
```

#### `/flowtree task` - Submit a Task

Submit a prompt to be executed by a connected coding agent.

**Syntax:**
```
/flowtree task <prompt>
```

**Example:**
```
/flowtree task Fix the authentication bug in auth.py
```

#### `/flowtree jobs` - List Recent Jobs

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

#### `/flowtree cancel` - Cancel a Running Job

Cancel a specific job by ID. Requires sending a cancellation signal to the agent running the job.

**Syntax:**
```
/flowtree cancel [job-id]
```

**Implementation notes:**
- The `ClaudeCodeJob` harness script supports receiving a cancel signal
- The controller needs a way to route the cancel to the correct agent node
- If no job ID is given, cancel the most recent job for the workstream

#### `/flowtree config` - View/Update Workstream Configuration

View or update workstream settings at runtime without restarting the controller.

**Syntax:**
```
/flowtree config                        → Show all settings
/flowtree config maxBudgetUsd           → Show specific setting
/flowtree config maxBudgetUsd 20.0      → Update setting
/flowtree config defaultBranch feature/x → Update branch
```

**Modifiable settings (via setters on SlackWorkstream):**
- `maxBudgetUsd`
- `maxTurns`
- `defaultBranch`
- `workingDirectory`
- `pushToOrigin`
- `allowedTools`

**Non-modifiable settings (informational only):**
- `workstreamId`
- `channelId`
- `channelName`

---

## Architecture Decisions

### Single Command with Subcommands

All functionality is unified under `/flowtree` rather than separate `/flowtree-status`, `/flowtree-task`, etc. commands. This:
- Requires only one slash command registration in the Slack app manifest
- Provides a natural help output when invoked without arguments
- Keeps the workspace's command namespace clean
- Makes discoverability easier (one command to remember)

### Workstream Auto-Creation via `/flowtree setup`

The `setup` subcommand addresses the key limitation that workstreams currently require pre-configuration in YAML. By allowing creation from Slack:
- Users can start using the bot in a new channel without server-side config changes
- The created workstream is persisted to the YAML file for durability across restarts
- Default values are applied automatically (budget, turns, tools) and can be tuned later via `/flowtree config`

### Ephemeral vs. Public Responses

| Command              | Response Type | Rationale                                    |
|----------------------|---------------|----------------------------------------------|
| `/flowtree` (help)   | Ephemeral     | Help text, avoids channel noise              |
| `/flowtree setup`    | Ephemeral     | Administrative, avoids noise                 |
| `/flowtree info`     | Ephemeral     | Informational query, avoids noise            |
| `/flowtree status`   | Ephemeral     | Status is informational, avoids channel noise |
| `/flowtree task`     | Public        | Team should see that a task was submitted     |
| `/flowtree jobs`     | Ephemeral     | Informational query, avoids noise             |
| `/flowtree cancel`   | Public        | Team should see cancellations                 |
| `/flowtree config`   | Ephemeral     | Administrative, avoids noise                  |

### Config Persistence

When a workstream is created or updated via `/flowtree setup` or `/flowtree config`, changes are persisted to the YAML config file (if one was loaded at startup). This ensures workstreams survive controller restarts. If no config file was loaded (e.g., single-channel mode via `--channel`), changes are runtime-only and a warning is shown.

### Backward Compatibility

In-message commands (`@flowtree.io /status`, etc.) will continue to work. The slash command handlers share the same underlying logic. No existing behavior is removed.

### Slash Commands vs. App Mention Commands

Both paths converge on the same `handleCommand()` logic. The difference is how responses are delivered:

- **App mention:** Response is a regular channel message via `SlackNotifier.postMessage()`
- **Slash command:** Response can be ephemeral (only visible to invoker) via `ctx.respond()`

---

## Files to Modify

| File | Change |
|------|--------|
| `flowtree/src/main/resources/slack-app-manifest.json` | Add `/flowtree` slash command and `commands` scope |
| `flowtree/src/main/java/io/flowtree/slack/SlackBotController.java` | Register `app.command("/flowtree", ...)` handler; pass config file reference to listener |
| `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` | Add `handleSlashCommand()` with subcommand dispatch, `handleSetupCommand()`, `handleInfoCommand()`, config persistence via `WorkstreamConfig` |
| `flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java` | Add `addOrUpdateWorkstream()` helper for creating/updating entries programmatically |
| `flowtree/src/main/java/io/flowtree/slack/SlackNotifier.java` | Add job tracking hooks for `/flowtree jobs` |
| `flowtree/src/test/java/io/flowtree/slack/SlackIntegrationTest.java` | Add tests for slash command handling, including setup and info |

---

## Implementation Order

1. Register `/flowtree` slash command in the Slack app manifest with `commands` scope
2. Add `app.command("/flowtree", ...)` handler in `SlackBotController`
3. Add `handleSlashCommand()` to `SlackListener` with subcommand parsing and help output
4. **Implement `/flowtree setup`** — workstream creation/update with config persistence (highest priority)
5. **Implement `/flowtree info`** — workstream detail display (highest priority)
6. Implement `/flowtree status` (reuses existing `handleStatusCommand()` logic)
7. Implement `/flowtree task` (reuses existing `submitJob()`)
8. Implement `/flowtree jobs` with job tracking
9. Implement `/flowtree cancel` with agent routing
10. Implement `/flowtree config` with runtime settings and persistence
11. Add tests for all new commands
12. Update `docs/slack-integration.md` with slash command documentation

---

## Open Questions

1. **Config file bootstrapping:** If the controller was started without `--config` (e.g., single-channel mode), `/flowtree setup` cannot persist. Should it create a default `workstreams.yaml` automatically, or just warn the user?
2. **Job tracking retention:** How many completed jobs should be kept in memory? Proposed: last 100 per workstream, configurable.
3. **Config persistence for `/flowtree config`:** Should changes persist to the YAML file, or only apply until restart? Proposed: persist by default (matching `/flowtree setup` behavior), with a `--no-persist` flag later if needed.
4. **Permissions:** Should certain commands (e.g., `/flowtree setup`, `/flowtree config`, `/flowtree cancel`) require specific Slack user roles? Not in Phase 1, but worth considering.
5. **Working directory validation:** Should `/flowtree setup` verify that the specified directory exists on the agent nodes? This is difficult since agents are remote, so initially we trust the user's input and let the job fail with a clear error if the directory is invalid.
