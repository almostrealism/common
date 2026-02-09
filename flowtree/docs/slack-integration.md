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
    |                              |-- ClaudeCodeClient ------>|
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

Top-level coordinator. Manages the Slack connection (Socket Mode), HTTP API endpoint, and event routing.

**Startup:**

```java
SlackBotController controller = new SlackBotController();
controller.loadConfig(new File("workstreams.yaml"));
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

Processes incoming Slack messages. Extracts the prompt from `@bot` mentions, looks up the workstream for the channel, creates a `ClaudeCodeJob.Factory`, and submits it to the connected FlowTree agents.

**Built-in commands:**

| Command | Description |
|---------|-------------|
| `/status` | Show workstream status |
| `/task <prompt>`, `/do <prompt>`, `/run <prompt>` | Submit a prompt |

### SlackNotifier

Sends messages to Slack channels using the Bot User OAuth Token. Handles job-started and job-completed notifications with status indicators.

### SlackApiEndpoint

Lightweight HTTP server (NanoHTTPD, default port 7780) that Claude Code sessions use to send messages back to Slack via the `ar-slack` MCP tool.

**Endpoints:**

| Method | Path | Body | Description |
|--------|------|------|-------------|
| POST | `/api/slack/message` | `{"channel_id":"C...","text":"..."}` | Post a message |
| POST | `/api/slack/thread` | `{"channel_id":"C...","thread_ts":"...","text":"..."}` | Reply in a thread |
| GET | `/api/slack/health` | -- | Health check |

When a `ClaudeCodeJob` has `slackApiUrl` set, it passes the URL to Claude Code as the `AR_SLACK_API_URL` environment variable. The `ar-slack` MCP server reads this and routes calls to the endpoint.

### SlackWorkstream

Maps a Slack channel to a pool of FlowTree agents and a set of job defaults. Each workstream has:

- **channelId / channelName** -- the Slack channel
- **agents** -- one or more FlowTree agent endpoints (host + port)
- **defaultBranch** -- git branch for commits
- **allowedTools, maxTurns, maxBudgetUsd** -- job configuration defaults

Agent selection uses round-robin within the workstream.

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
workstreams:
  - channelId: "C0123456789"
    channelName: "#project-agent"
    agents:
      - host: "localhost"
        port: 7766
      - host: "localhost"
        port: 7767
    defaultBranch: "feature/work"
    pushToOrigin: true
    allowedTools: "Read,Edit,Write,Bash,Glob,Grep"
    maxTurns: 50
    maxBudgetUsd: 10.0

  - channelId: "C9876543210"
    channelName: "#ops-agent"
    agents:
      - host: "10.0.1.5"
        port: 7766
    defaultBranch: "feature/ops"
    maxBudgetUsd: 5.0
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `SLACK_BOT_TOKEN` | Bot User OAuth Token (`xoxb-...`) |
| `SLACK_APP_TOKEN` | App-level token for Socket Mode (`xapp-...`) |
| `SLACK_CHANNEL_ID` | Default channel (when not using YAML config) |
| `SLACK_CHANNEL_NAME` | Human-readable name for the default channel |
| `FLOWTREE_AGENT_HOST` | Agent hostname (default: `localhost`) |
| `FLOWTREE_AGENT_PORT` | Agent port (default: `7766`) |
| `GIT_DEFAULT_BRANCH` | Default branch for commits |

### CLI Arguments

| Argument | Description |
|----------|-------------|
| `--tokens, -t <file>` | JSON file with `botToken` and `appToken` |
| `--config, -c <file>` | YAML workstream configuration file |
| `--channel <id>` | Single channel to monitor |
| `--channel-name <name>` | Human-readable channel name |
| `--agent <host:port>` | FlowTree agent endpoint |
| `--branch <name>` | Default git branch |
| `--api-port <port>` | HTTP API endpoint port (default: 7780) |

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
