# Slack Integration

The Slack integration allows operators to submit coding agent prompts by mentioning a bot in Slack. Results and status updates are posted back to the channel in real time. Running Claude Code sessions can also send messages back to Slack through the `ar-slack` MCP tool.

## Architecture

```
Slack (Socket Mode)          SlackBotController          FlowTree Network
    |                              |                          |
    |-- @bot fix the bug --------->|                          |
    |                              |-- SlackListener          |
    |                              |   extract prompt          |
    |                              |   create Factory          |
    |<-- "Starting job..." --------|                          |
    |                              |-- Server.sendTask() ---->|
    |                              |                          |-- Node runs job
    |                              |                          |
    |                    SlackApiEndpoint (port 7780)          |
    |<-- "Progress update" --------|<-- ar-slack MCP ---------|
    |                              |                          |
    |                              |<-- JobCompletionEvent ---|
    |<-- "Job complete!" ----------|                          |
```

## Components

### SlackBotController

Top-level coordinator. Manages the Slack connection (Socket Mode), HTTP API endpoint, centralized MCP server lifecycle, pushed tool registration, and event routing.

When the YAML config includes an `mcpServers` section, `loadConfig()` starts each server as an HTTP Python process and builds a `centralizedMcpConfig` JSON that is passed to all jobs via `SlackListener`. When `pushedTools` is present, `registerPushedTools()` (called after the API endpoint starts) registers each tool's source file for serving via `GET /api/tools/{name}` and builds a `pushedToolsConfig` JSON that is also passed to all jobs.

**Startup:**

```java
SlackBotController controller = new SlackBotController();
controller.loadConfig(new File("workstreams.yaml")); // also starts centralized MCP servers
controller.setApiPort(7780);
controller.start();
```

**Command-line:**

```bash
java -cp flowtree.jar io.flowtree.slack.SlackBotController \
    --tokens slack-tokens.json \
    --config workstreams.yaml \
    --api-port 7780
```

### SlackListener

Processes incoming Slack messages. Extracts the prompt from `@bot` mentions, looks up the workstream for the channel, creates a `ClaudeCodeJob.Factory`, and submits it to the connected FlowTree agents. If the controller has started centralized MCP servers, the `centralizedMcpConfig` is passed to each factory so agents connect to those servers over HTTP. If pushed tools are configured, the `pushedToolsConfig` is also passed so agents can download and run those tools locally.

**Built-in commands:**

| Command | Description |
|---------|-------------|
| `/status` | Show workstream status |
| `/task <prompt>`, `/do <prompt>`, `/run <prompt>` | Submit a prompt |
| `/stats` | Show weekly job statistics (this week and last week) |

### SlackNotifier

Sends messages to Slack channels using the Bot User OAuth Token. Handles job-started and job-completed notifications with status indicators.

### SlackApiEndpoint

Lightweight HTTP server (NanoHTTPD, default port 7780) that receives status events and Slack messages from agents via the workstream URL pattern.

**Endpoints:**

| Method | Path | Body | Description |
|--------|------|------|-------------|
| POST | `/api/workstreams/{id}/messages` | `{"text":"..."}` | Post a message to the workstream's channel |
| POST | `/api/workstreams/{id}/jobs/{jobId}/messages` | `{"text":"..."}` | Post a message to the job's thread |
| POST | `/api/workstreams/{id}` | `{"jobId":"...","status":"..."}` | Receive a status event |
| POST | `/api/workstreams/{id}/jobs/{jobId}` | `{"jobId":"...","status":"..."}` | Receive a job status event |
| GET | `/api/health` | -- | Health check |
| GET | `/api/stats` | -- | Weekly job statistics (query params: `workstream`, `period`) |
| GET | `/api/tools/{name}` | -- | Download a pushed tool's Python source file |

When a `ClaudeCodeJob` has `workstreamUrl` set, it passes the URL to Claude Code as the `AR_WORKSTREAM_URL` environment variable. The `ar-slack` MCP server reads this and POSTs messages to `{url}/messages`.

### JobStatsStore

HSQLDB-backed storage for job timing data and statistics. Records job start/completion events with timing metrics extracted from Claude Code output. Provides weekly aggregation queries for the `/flowtree stats` command and the `/api/stats` endpoint.

- Database path: `~/.flowtree/stats` (HSQLDB file database)
- Automatically cleans orphaned STARTED rows older than 7 days on initialization
- Initialized and wired by `SlackBotController` at startup

### SlackWorkstream

Maps a Slack channel to a set of job defaults. Each workstream has:

- **channelId / channelName** -- the Slack channel
- **defaultBranch** -- git branch for commits
- **baseBranch** -- branch to create new target branches from (defaults to `"master"`)
- **allowedTools, maxTurns, maxBudgetUsd** -- job configuration defaults

Agents connect inbound to the controller's FlowTree server. The controller distributes jobs round-robin to whichever agents are currently connected.

## Configuration

### Token Resolution

Tokens are resolved in order (first match wins):

1. `--tokens <file>` CLI argument
2. `./slack-tokens.json` in the working directory
3. `SLACK_BOT_TOKEN` / `SLACK_APP_TOKEN` environment variables

**Token file format:**

```json
{
  "botToken": "xoxb-...",
  "appToken": "xapp-..."
}
```

### Workstream Configuration (YAML)

```yaml
# Optional: centralized MCP servers started by the controller as HTTP processes.
# Agents connect over HTTP instead of stdio. Enables shared state and
# cross-project portability. Omit this section for local stdio (the default).
mcpServers:
  ar-memory:
    source: tools/mcp/memory/server.py
    port: 7783
  ar-consultant:
    source: tools/mcp/consultant/server.py
    port: 7784

# Optional: pushed MCP tools served as files by the controller and downloaded
# into dev containers on first use. Use for tools that depend on per-job state.
# Each entry supports an optional 'env' map for per-tool environment variables.
pushedTools:
  ar-slack:
    source: tools/mcp/slack/server.py
  ar-github:
    source: tools/mcp/github/server.py
    env:
      GITHUB_TOKEN: ghp_your_token_here

workstreams:
  - channelId: "C0123456789"
    channelName: "#project-agent"
    defaultBranch: "feature/work"
    baseBranch: "master"             # New branches created from origin/<baseBranch>
    pushToOrigin: true
    allowedTools: "Read,Edit,Write,Bash,Glob,Grep"
    maxTurns: 50
    maxBudgetUsd: 10.0

  # Per-workstream env vars override global pushed tool env.
  # Useful for per-org tokens or workstream-specific config.
  - channelId: "C9876543210"
    channelName: "#ops-agent"
    defaultBranch: "feature/ops"
    baseBranch: "develop"            # Branch from develop instead of master
    maxBudgetUsd: 5.0
    env:
      GITHUB_TOKEN: ghp_ops_org_token
```

See the [Centralized MCP Servers](coding-agent.md#centralized-mcp-servers) and [Pushed Tools](coding-agent.md#pushed-tools) sections in the coding agent docs for architecture details.

### Environment Variables

| Variable | Description |
|----------|-------------|
| `SLACK_BOT_TOKEN` | Bot User OAuth Token (`xoxb-...`) |
| `SLACK_APP_TOKEN` | App-level token for Socket Mode (`xapp-...`) |
| `SLACK_CHANNEL_ID` | Default channel (when not using YAML config) |
| `SLACK_CHANNEL_NAME` | Human-readable name for the default channel |
| `FLOWTREE_PORT` | FlowTree server listening port (default: `7766`) |
| `GIT_DEFAULT_BRANCH` | Default branch for commits |

### CLI Arguments

| Argument | Description |
|----------|-------------|
| `--tokens, -t <file>` | JSON file with `botToken` and `appToken` |
| `--config, -c <file>` | YAML workstream configuration file |
| `--channel <id>` | Single channel to monitor |
| `--channel-name <name>` | Human-readable channel name |
| `--branch <name>` | Default git branch |
| `--api-port <port>` | HTTP API endpoint port (default: 7780) |
| `--flowtree-port <port>` | FlowTree server listening port (default: 7766) |

## Simulation Mode

When Slack tokens are not provided, the controller starts in **simulation mode**. The HTTP API endpoint still runs, but no Slack connection is established. Use `simulateMessage(channelId, text)` to test the pipeline without a live Slack workspace.

```java
SlackBotController controller = new SlackBotController("", "");
controller.registerWorkstream(workstream);
controller.start(); // Enters simulation mode

controller.setEventSimulator((channel, message) -> {
    System.out.println("Would send to " + channel + ": " + message);
});

controller.simulateMessage("C0123456789", "@bot fix the login bug");
```
