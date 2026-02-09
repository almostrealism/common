# Coding Agent Integration

FlowTree integrates with Claude Code to execute AI coding prompts as distributed jobs. Prompts are submitted as `ClaudeCodeJob` instances, which invoke Claude Code in headless mode (`claude -p`) and optionally commit the resulting changes to git.

## How It Works

1. A **ClaudeCodeJob.Factory** is created with one or more prompts.
2. The factory is submitted to the FlowTree network via a **ClaudeCodeClient** (or through the [Slack integration](slack-integration.md)).
3. An idle Node picks up the job and executes `claude -p "<prompt>" --output-format json`.
4. When Claude Code finishes, the job optionally stages, commits, and pushes changes via **GitManagedJob**.
5. A **JobCompletionEvent** fires, notifying any registered listeners (e.g., the Slack notifier).

```
ClaudeCodeClient            FlowTree Network            Claude Code
    |                            |                          |
    |-- submit(factory) -------->|                          |
    |                            |-- Node picks up job ---->|
    |                            |                          |-- executes prompt
    |                            |                          |-- edits files
    |                            |<--- output + exit code --|
    |                            |-- GitManagedJob: stage, commit, push
    |                            |-- fire JobCompletionEvent
```

## Key Classes

### ClaudeCodeJob

Executes a single Claude Code prompt. Extends `GitManagedJob`.

**Configuration:**

| Property | Default | Description |
|----------|---------|-------------|
| `prompt` | -- | The prompt to send to Claude Code |
| `allowedTools` | `Read,Edit,Write,Bash,Glob,Grep` | Comma-separated tool allowlist |
| `maxTurns` | `50` | Maximum number of agent turns |
| `maxBudgetUsd` | `10.0` | Spending cap per job |
| `targetBranch` | `null` | Git branch for commits (disables git if null) |
| `slackApiUrl` | `null` | HTTP URL for the ar-slack MCP endpoint |
| `slackChannelId` | `null` | Slack channel ID for MCP messages |
| `slackThreadTs` | `null` | Thread timestamp for threaded replies |

**Results:**

| Accessor | Description |
|----------|-------------|
| `getSessionId()` | Claude Code session ID (for resuming) |
| `getOutput()` | Full JSON output |
| `getExitCode()` | Process exit code |
| `getStagedFiles()` | Files that were committed (from GitManagedJob) |
| `getCommitHash()` | Git commit hash (from GitManagedJob) |

### ClaudeCodeJob.Factory

Produces one `ClaudeCodeJob` per prompt for distribution across the network. Implements `JobFactory` so FlowTree nodes pull jobs one at a time.

```java
ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(
    "Fix the null pointer in UserService",
    "Add unit tests for the login flow"
);
factory.setAllowedTools("Read,Edit,Bash,Glob,Grep");
factory.setTargetBranch("feature/fixes");
factory.setMaxBudgetUsd(25.0);
```

### ClaudeCodeClient

Connects to one or more FlowTree agents and submits job factories with round-robin distribution. Connections are **lazy** -- no TCP sockets are opened until the first job is submitted to a given agent. If an agent has restarted since the last submission, the client automatically detects the dead connection and reconnects (with one retry). All `submit` methods return a `boolean` indicating success so callers can handle unreachable agents.

```java
ClaudeCodeClient client = new ClaudeCodeClient();
client.addAgent("localhost", 7766);
client.addAgent("localhost", 7767);
client.start();  // No connections opened yet

boolean ok = client.submit("Fix the bug in auth.py");  // Connects lazily
if (!ok) {
    System.err.println("Agent unreachable");
}
```

**Command-line usage:**

```bash
java -cp flowtree.jar io.flowtree.ClaudeCodeClient \
    --host localhost --port 7766,7767 \
    --prompt "Review and improve error handling" \
    --max-budget 25.0
```

### GitManagedJob

Base class providing automatic git operations after job completion:

- Creates or switches to the target branch
- Detects modified files via `git status`
- Stages files with guardrails (skips secrets, binaries, large files, build artifacts)
- Commits with a descriptive message
- Optionally pushes to origin

**File guardrails** -- the following are automatically excluded:

- Sensitive files: `.env`, `*.pem`, `*.key`, `credentials.*`
- Build artifacts: `target/`, `build/`, `node_modules/`, `*.class`
- IDE files: `.idea/`, `.vscode/`
- Binary files detected by content inspection
- Files larger than 1MB (configurable via `setMaxFileSizeBytes`)

## Job Lifecycle Events

`JobCompletionEvent` carries lifecycle information through the system:

| Event | When |
|-------|------|
| `JobCompletionEvent.started(...)` | Job begins execution |
| `JobCompletionEvent.success(...)` | Job completed successfully |
| `JobCompletionEvent.failed(...)` | Job failed with an error |

Events include the prompt, session ID, exit code, staged files, and commit hash. Listeners implement `JobCompletionListener` to receive these events.

## Serialization

`ClaudeCodeJob` and its factory support FlowTree's wire protocol via `encode()` and `set(key, value)`. Prompts and string properties are Base64-encoded for safe transport over the peer-to-peer message layer.

## Operator Scripts

Convenience scripts live in `flowtree/bin/` and use `mvn exec:java` for classpath resolution. Each script auto-builds the module on first run if the `target/` directory is missing.

### start-slack-controller.sh

Starts the `SlackBotController`. Requires Slack tokens via environment variables or a `--tokens` file.

```bash
# Using environment variables
export SLACK_BOT_TOKEN="xoxb-..."
export SLACK_APP_TOKEN="xapp-..."
./flowtree/bin/start-slack-controller.sh --channel C0123ABCDEF --agent localhost:7766

# Using a tokens file
./flowtree/bin/start-slack-controller.sh --tokens slack-tokens.json --config workstreams.yaml

# Show full usage
./flowtree/bin/start-slack-controller.sh --help
```

### start-agent.sh

Starts a FlowTree `Agent` node that listens for job submissions. Sets `AR_HARDWARE_LIBS` and `AR_HARDWARE_DRIVER` defaults automatically if not already present in the environment.

```bash
./flowtree/bin/start-agent.sh
```
