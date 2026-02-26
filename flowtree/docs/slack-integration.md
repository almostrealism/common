# Slack Integration

The Slack integration allows operators to submit coding agent prompts by mentioning a bot in Slack. Results and status updates are posted back to the channel in real time. Running Claude Code sessions can also send messages back to Slack through the `ar-slack` MCP tool.

## Architecture

```
Slack (Socket Mode)          FlowTreeController          FlowTree Network
    |                              |                          |
    |-- @bot fix the bug --------->|                          |
    |                              |-- SlackListener          |
    |                              |   extract prompt          |
    |                              |   create Factory          |
    |<-- "Starting job..." --------|                          |
    |                              |-- Server.sendTask() ---->|
    |                              |                          |-- Node runs job
    |                              |                          |
    |                    FlowTreeApiEndpoint (port 7780)          |
    |<-- "Progress update" --------|<-- ar-slack MCP ---------|
    |                              |                          |
    |                              |<-- JobCompletionEvent ---|
    |<-- "Job complete!" ----------|                          |
```

## Components

### FlowTreeController

Top-level coordinator. Manages the Slack connection (Socket Mode), HTTP API endpoint, centralized MCP server lifecycle, pushed tool registration, and event routing.

When the YAML config includes an `mcpServers` section, `loadConfig()` starts each server as an HTTP Python process and builds a `centralizedMcpConfig` JSON that is passed to all jobs via `SlackListener`. When `pushedTools` is present, `registerPushedTools()` (called after the API endpoint starts) registers each tool's source file for serving via `GET /api/tools/{name}` and builds a `pushedToolsConfig` JSON that is also passed to all jobs.

**Startup:**

```java
FlowTreeController controller = new FlowTreeController();
controller.loadConfig(new File("workstreams.yaml")); // also starts centralized MCP servers
controller.setApiPort(7780);
controller.start();
```

**Command-line:**

```bash
java -cp flowtree.jar io.flowtree.slack.FlowTreeController \
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

Sends messages to Slack channels using the Bot User OAuth Token. Handles job-started and job-completed notifications with status indicators. Can create private Slack channels and invite a designated owner user.

**Channel creation:**

When a workstream is registered via the API without an existing channel, `createChannel(name)` creates a **private** Slack channel and optionally invites the configured `channelOwnerUserId`. Returns the new channel ID or `null` in simulation mode (no bot token).

**Null-safe messaging:**

`postMessage` and `postMessageInThread` gracefully handle `null` or empty channel IDs by returning `null` without throwing. This supports workstreams that are registered before their Slack channel exists.

### FlowTreeApiEndpoint

Lightweight HTTP server (NanoHTTPD, default port 7780) that receives status events and Slack messages from agents via the workstream URL pattern.

**Endpoints:**

| Method | Path | Body | Description |
|--------|------|------|-------------|
| POST | `/api/workstreams/{id}/messages` | `{"text":"..."}` | Post a message to the workstream's channel |
| POST | `/api/workstreams/{id}/jobs/{jobId}/messages` | `{"text":"..."}` | Post a message to the job's thread |
| POST | `/api/workstreams/{id}/submit` | `{"prompt":"..."}` | Submit a new job (see [Pipeline Agents](../../PIPELINE_AGENTS.md)) |
| POST | `/api/workstreams` | `{"defaultBranch":"...","channelName":"..."}` | Register a new workstream (auto-creates private Slack channel) |
| POST | `/api/workstreams/{id}/update` | `{"channelId":"...","channelName":"..."}` | Update an existing workstream |
| POST | `/api/workstreams/{id}` | `{"jobId":"...","status":"..."}` | Receive a status event |
| POST | `/api/workstreams/{id}/jobs/{jobId}` | `{"jobId":"...","status":"..."}` | Receive a job status event |
| GET | `/api/health` | -- | Health check |
| GET | `/api/stats` | -- | Weekly job statistics (query params: `workstream`, `period`) |
| GET | `/api/tools/{name}` | -- | Download a pushed tool's Python source file |

When a `ClaudeCodeJob` has `workstreamUrl` set, it passes the URL to Claude Code as the `AR_WORKSTREAM_URL` environment variable. The `ar-slack` MCP server reads this and POSTs messages to `{url}/messages`.

#### Workstream Registration Endpoint

```
POST /api/workstreams
Content-Type: application/json
```

Registers a new workstream dynamically. When a `channelName` is provided and a Slack bot token is available, a **private** Slack channel is automatically created and the configured `channelOwnerUserId` is invited. This is used by CI pipelines (via `register-workstream.sh`) to create workstreams on the fly when new plan documents are detected.

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `defaultBranch` | `string` | Yes | The git branch for this workstream |
| `baseBranch` | `string` | No | Base branch for new branches (default: `"master"`) |
| `repoUrl` | `string` | No | Repository clone URL |
| `planningDocument` | `string` | No | Path to the plan document (relative to repo root) |
| `channelName` | `string` | No | Desired Slack channel name (a private channel is created if provided) |

**Response (200):**

```json
{
  "ok": true,
  "workstreamId": "ws-abc123",
  "channelId": "C0123456789",
  "channelName": "w-project-plan-foo"
}
```

#### Workstream Update Endpoint

```
POST /api/workstreams/{id}/update
Content-Type: application/json
```

Updates fields on an existing workstream. All fields are optional; only provided non-empty fields are applied.

**Request body:**

| Field | Type | Description |
|-------|------|-------------|
| `channelId` | `string` | Override the Slack channel ID |
| `channelName` | `string` | Override the channel display name |
| `defaultBranch` | `string` | Change the target branch |
| `baseBranch` | `string` | Change the base branch |
| `repoUrl` | `string` | Change the repository URL |
| `planningDocument` | `string` | Change the plan document path |

### JobStatsStore

HSQLDB-backed storage for job timing data and statistics. Records job start/completion events with timing metrics extracted from Claude Code output. Provides weekly aggregation queries for the `/flowtree stats` command and the `/api/stats` endpoint.

- Database path: `~/.flowtree/stats` (HSQLDB file database)
- Automatically cleans orphaned STARTED rows older than 7 days on initialization
- Initialized and wired by `FlowTreeController` at startup

### SlackListener

Manages the mapping between Slack channels and workstreams. Extracts prompts from `@bot` mentions, creates `ClaudeCodeJob.Factory` instances, and submits them to connected agents.

**Dynamic registration:** `registerAndPersistWorkstream(workstream)` registers a workstream in memory and persists the updated config to the YAML file. This is used by the registration API endpoint to support runtime workstream creation from CI pipelines.

### SlackWorkstream

Maps a Slack channel to a set of job defaults. Each workstream has:

- **channelId / channelName** -- the Slack channel (nullable for workstreams created before their channel exists)
- **defaultBranch** -- git branch for commits
- **baseBranch** -- branch to create new target branches from (defaults to `"master"`)
- **planningDocument** -- path to a plan document (relative to repo root) that agents read before starting work
- **repoUrl** -- repository clone URL for automatic checkout
- **allowedTools, maxTurns, maxBudgetUsd** -- job configuration defaults

Workstreams can be defined statically in the YAML config or registered dynamically via `POST /api/workstreams`. A workstream without a `channelId` (registered before its Slack channel is created or in simulation mode) is still functional for job dispatch.

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
# Channel owner (optional, global).
# Slack user ID to auto-invite when creating new private workstream channels
# via the POST /api/workstreams registration endpoint.
# Get your user ID from Slack profile > "..." > "Copy member ID".
# channelOwnerUserId: U0123456789

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
    baseBranch: "master"
    maxBudgetUsd: 5.0
    env:
      GITHUB_TOKEN: ghp_ops_org_token
```

**Dynamic workstream registration:** Workstreams can also be created at runtime via `POST /api/workstreams`. The registered workstream is added to the in-memory config and persisted back to the YAML file. This is used by the `verify-completion.yaml` CI workflow to create workstreams automatically when a new plan document is detected on a feature branch.

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
FlowTreeController controller = new FlowTreeController("", "");
controller.registerWorkstream(workstream);
controller.start(); // Enters simulation mode

controller.setEventSimulator((channel, message) -> {
    System.out.println("Would send to " + channel + ": " + message);
});

controller.simulateMessage("C0123456789", "@bot fix the login bug");
```
