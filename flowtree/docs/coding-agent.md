# Coding Agent Integration

FlowTree integrates with Claude Code to execute AI coding prompts as distributed jobs. Prompts are submitted as `ClaudeCodeJob` instances, which invoke Claude Code in headless mode (`claude -p`) and optionally commit the resulting changes to git.

## How It Works

1. A **FlowTreeController** starts a FlowTree **Server** that listens for inbound agent connections.
2. Agents (Docker containers or remote hosts) connect OUT to the controller by setting `FLOWTREE_ROOT_HOST` and `FLOWTREE_ROOT_PORT` environment variables.
3. When a Slack message arrives, the controller creates a **ClaudeCodeJob.Factory** and sends it to a connected agent via `Server.sendTask()`.
4. The agent's idle Node picks up the job and executes `claude -p "<prompt>" --output-format json`.
5. When Claude Code finishes, the job optionally stages, commits, and pushes changes via **GitManagedJob**.
6. A **JobCompletionEvent** fires, notifying any registered listeners (e.g., the Slack notifier).

```
Agent (Docker)                  FlowTreeController              Claude Code
    |                                |                              |
    |-- connects to controller ----->|                              |
    |   (FLOWTREE_ROOT_HOST/PORT)    |                              |
    |                                |                              |
    |                      Slack msg |                              |
    |<-- Server.sendTask(factory) ---|                              |
    |                                |                              |
    |   Node picks up job            |                              |
    |-- executes prompt ------------------------------------------->|
    |<-- output + exit code ----------------------------------------|
    |                                                               |
    |-- GitManagedJob: stage, commit, push                          |
    |-- POST status event ---------> FlowTreeApiEndpoint               |
    |                                |-- SlackNotifier (Slack msg)  |
```

## Key Classes

### ClaudeCodeJob

Executes a single Claude Code prompt. Extends `GitManagedJob`.

**Configuration:**

| Property | Default | Description |
|----------|---------|-------------|
| `prompt` | -- | The prompt to send to Claude Code |
| `allowedTools` | `Read,Edit,Write,Bash,Glob,Grep` | Comma-separated tool allowlist (MCP tools are appended automatically) |
| `maxTurns` | `50` | Maximum number of agent turns |
| `maxBudgetUsd` | `10.0` | Spending cap per job |
| `targetBranch` | `null` | Git branch for commits (disables git if null) |
| `baseBranch` | `"master"` | Branch to create new target branches from (`origin/<baseBranch>`) |
| `workstreamUrl` | `null` | Controller URL for status events and Slack messaging |
| `centralizedMcpConfig` | `null` | JSON mapping centralized MCP server names to HTTP URLs and tool names |
| `pushedToolsConfig` | `null` | JSON mapping pushed tool server names to download URLs and tool names |
| `deduplicationMode` | `null` | Post-work duplicate-method check. `"local"` runs an inline session before committing; `"spawn"` posts a follow-up job to the workstream. `null` disables. |

**Results:**

| Accessor | Description |
|----------|-------------|
| `getSessionId()` | Claude Code session ID (for resuming) |
| `getOutput()` | Full JSON output |
| `getExitCode()` | Process exit code |
| `getDurationMs()` | Total duration reported by Claude Code (ms) |
| `getDurationApiMs()` | Time spent in API calls (ms) |
| `getCostUsd()` | Total cost in USD |
| `getNumTurns()` | Number of agentic turns |
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

// Optional: scan for duplicate methods after Claude Code finishes
// "local"  — inline session before commit (recommended while iterating)
// "spawn"  — separate follow-up job posted to the same workstream
factory.setDeduplicationMode(ClaudeCodeJob.DEDUP_LOCAL);
```

### ClaudeCodeClient

Standalone client for submitting jobs from the command line. Connects outbound to one or more FlowTree agents and submits job factories with round-robin distribution. Connections are **lazy** -- no TCP sockets are opened until the first job is submitted to a given agent.

```java
ClaudeCodeClient client = new ClaudeCodeClient();
client.addAgent("localhost", 7766);
client.addAgent("localhost", 7767);
client.start();

boolean ok = client.submit("Fix the bug in auth.py");
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

- Creates or switches to the target branch (new branches are created from `origin/<baseBranch>`)
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

## Deduplication Check

One of the most common problems with agent-produced code is re-implementing functionality that already exists elsewhere in the codebase. Agents cannot search exhaustively, and their pattern-matching tends toward re-invention. The deduplication check is a post-work step that scans the changes for new Java method declarations and then runs an aggressive second session (or queues a separate job) whose sole purpose is to find and eliminate any duplicates before the work is committed.

### How It Works

After Claude Code finishes (`doWork()` completes) and before the changes are staged and committed, `ClaudeCodeJob.validateChanges()` runs the deduplication scan:

1. `git diff origin/<baseBranch> -- '*.java'` is run to capture new lines in modified tracked Java files.
2. `git ls-files --others --exclude-standard -- '*.java'` lists entirely new (untracked) Java files; all methods in these files are treated as new.
3. New method declarations are extracted with a regex that matches `public|private|protected` access modifiers followed by a return type and a method name before `(`.
4. If any new methods are found, a deduplication prompt is assembled listing all of them (up to 50; the session is told if the list was truncated).
5. Depending on `deduplicationMode`, the prompt is either run inline or submitted as a new job.

### Modes

| Mode | Constant | Behaviour |
|------|----------|-----------|
| Disabled | `null` (default) | No deduplication scan. |
| Inline | `ClaudeCodeJob.DEDUP_LOCAL` | A second Claude Code session is started immediately in the same working directory, with the dedup prompt replacing the original prompt. All session machinery (tools, budget, MCP config) is reused. The dedup edits land in the same working tree and are committed together with the original work. **No extra jobs are spawned; safe to iterate.** |
| Spawn | `ClaudeCodeJob.DEDUP_SPAWN` | A follow-up `ClaudeCodeJob` is posted to the same workstream via `POST /api/submit` after the current commit. Requires a workstream URL. Fire-and-forget. |

### The Deduplication Prompt

The prompt is deliberately aggressive. Key points:

- Each method in the list is *assumed to be a clone* of existing functionality until proven otherwise.
- The agent is told that, by the numbers, the majority of new methods introduced by agent sessions are duplicates — renamed, slightly generalized, or relocated versions of what already exists.
- The agent must search by keyword and logical purpose, not just by name. A clone that performs the same operation under a different name still counts.
- The instruction is unambiguous: if a duplicate is found, remove the new method entirely and redirect all call sites to the existing one.

### Infinite Loop Protection

`DEDUP_LOCAL` runs exactly one additional session. It does not re-trigger `validateChanges()` (which is called from `GitManagedJob.run()`, not from within `executeSingleRun()`), and `deduplicationMode` is never propagated to spawned sessions, so there is no mechanism for the check to chain.

### Via ar-manager MCP

The `workstream_submit_task` tool exposes the `deduplication_mode` parameter:

```
workstream_submit_task(
    prompt="Implement feature X",
    workstream_id="ws-common",
    deduplication_mode="local"   # or "spawn"
)
```

## MCP Tools

Claude Code sessions started by `ClaudeCodeJob` automatically have access to these MCP tools:

- **ar-messages** — Store messages in the memory database and optionally notify the Slack channel, plus query job statistics via the controller's `FlowTreeApiEndpoint`
- **ar-github** — Read and respond to GitHub PR review comments
- **Project servers** — Any servers defined in `.mcp.json` (filtered by `.claude/settings.json`) are included automatically

These tools are appended to `--allowedTools` regardless of the configured allowlist. The `ar-messages` tool uses the `AR_WORKSTREAM_URL` environment variable (derived from `workstreamUrl`) to route messages to the correct channel.

### Centralized MCP Servers

When the controller's YAML config includes an `mcpServers` section, those servers are started as HTTP processes on the controller host. Agents connect to them over HTTP instead of stdio, which enables:

- **Cross-project portability** — agents don't need local Python source files for these tools
- **Shared state** — stateful servers like ar-memory and ar-consultant share a single database across all agents

**Server classification:**

| Server | Mode | Reason |
|--------|------|--------|
| ar-messages | Pushed | Depends on per-job `AR_WORKSTREAM_URL` |
| ar-github | Pushed | Depends on local git repo/branch |
| ar-memory | Centralized | SQLite + FAISS state shared across agents |
| ar-consultant | Centralized | Memory + history DB; must be shared |
| ar-docs | Local (.mcp.json) | Project-specific repo docs |
| ar-test-runner | Local (.mcp.json) | Local `mvn test` |
| ar-jmx | Local (.mcp.json) | Local JVM attachment |
| ar-profile-analyzer | Local (.mcp.json) | Local XML files |

When `centralizedMcpConfig` is set on a job, `buildMcpConfig()` emits `{"type":"http","url":"..."}` entries for centralized servers and stdio entries for local ones. The `0.0.0.0` placeholder in URLs is resolved to `FLOWTREE_ROOT_HOST` at runtime.

**Backward compatibility:** When the `mcpServers` section is absent from the YAML, behavior is identical to the all-local-stdio default. Python servers default to stdio when `MCP_TRANSPORT` is not set, so interactive `claude` sessions are unaffected. `discoverProjectMcpServers()` skips servers that appear in the centralized config.

**Known limitations:**

- When ar-consultant is centralized, it searches docs from the controller host's repo, not the target project
- No auto-restart if a centralized server process crashes
- Ports are fixed per config; dynamic allocation is future work

### Pushed Tools

Some MCP tools cannot run as centralized HTTP servers because they depend on per-job state:

- **ar-messages** reads `AR_WORKSTREAM_URL` at module load time, and each agent job has a different workstream URL
- **ar-github** detects the local git branch via subprocess, so it must run inside the agent's working directory

These tools are **pushed** to dev containers: the controller serves the Python source files over HTTP, and `ClaudeCodeJob` downloads them to `~/.flowtree/tools/mcp/{name}/server.py` before starting Claude Code.

**YAML configuration:**

```yaml
pushedTools:
  ar-messages:
    source: tools/mcp/messages/server.py
  ar-github:
    source: tools/mcp/github/server.py
    env:
      GITHUB_TOKEN: ghp_your_token_here
```

Each entry supports an optional `env` map of environment variables that are injected into the MCP stdio config as defaults. Per-workstream overrides are also supported via the `env` field on each workstream entry:

```yaml
workstreams:
  - channelId: "C0123456789"
    channelName: "#org-a-agent"
    env:
      GITHUB_TOKEN: ghp_org_a_token

  - channelId: "C9876543210"
    channelName: "#org-b-agent"
    env:
      GITHUB_TOKEN: ghp_org_b_token
```

When both are present, workstream-level env vars override the global pushed tool defaults. This allows different workstreams to target repos in different GitHub orgs without modifying the tool source or the container's global environment.

**How it works:**

1. At startup, `FlowTreeController.registerPushedTools()` resolves each source path, discovers tool names via `McpToolDiscovery`, and registers the files with `FlowTreeApiEndpoint` for serving via `GET /api/tools/{name}`.
2. The resulting `pushedToolsConfig` JSON (mapping server names to download URLs and tool lists) is stored on `SlackListener` and passed to every `ClaudeCodeJob.Factory`.
3. At job execution time, `ClaudeCodeJob.ensurePushedTools()` downloads any missing tools from the controller to `~/.flowtree/tools/mcp/{name}/server.py`.
4. `buildMcpConfig()` emits stdio entries pointing to `~/.flowtree/tools/mcp/{name}/server.py` for pushed tools.

**Download-on-first-use:** Tools are only downloaded once per container. Subsequent jobs reuse the files already present in `~/.flowtree/tools/`. The `FLOWTREE_ROOT_HOST` environment variable is used to resolve the `0.0.0.0` placeholder in download URLs.

**Backward compatibility:** When the `pushedTools` section is absent from the YAML, ar-messages and ar-github fall back to the hardcoded `tools/mcp/` paths (requiring the files to exist locally in the project directory).

### McpToolDiscovery

Shared utility class (`io.flowtree.jobs.McpToolDiscovery`) that scans Python MCP server source files for `@mcp.tool()` decorated functions. Used by `ClaudeCodeJob` (for local servers), `FlowTreeController` (for centralized servers and pushed tools at startup).

## Job Lifecycle Events

`JobCompletionEvent` carries lifecycle information through the system:

| Event | Fired by | When |
|-------|----------|------|
| `JobCompletionEvent.started(...)` | Controller (SlackListener) | Job is created and dispatched |
| `JobCompletionEvent.success(...)` | Agent (GitManagedJob) | Job completed successfully |
| `JobCompletionEvent.failed(...)` | Agent (GitManagedJob) | Job failed with an error |

Start events are fired by the controller immediately when a job is submitted, providing fast feedback to the Slack channel. Completion events are fired by the agent after Claude Code finishes and git operations complete. The agent POSTs completion events to the controller via the `workstreamUrl`, where `SlackNotifier` formats and posts the result to Slack.

Completion events carry timing information extracted from Claude Code's `--output-format json` output: `durationMs`, `durationApiMs`, `costUsd`, and `numTurns`. These are populated via `withTimingInfo()` on the agent side (by `ClaudeCodeJob.populateEventDetails()`) and deserialized on the controller side (by `FlowTreeApiEndpoint.handleStatusEvent()`). The `SlackNotifier` writes timing data to `JobStatsStore` for aggregation.

## Serialization

`ClaudeCodeJob` and its factory support FlowTree's wire protocol via `encode()` and `set(key, value)`. Prompts and string properties are Base64-encoded for safe transport over the peer-to-peer message layer. The `centralizedMcpConfig` JSON is serialized under the `centralMcp` key and `pushedToolsConfig` under the `pushedTools` key. Completion events include timing fields (`durationMs`, `durationApiMs`, `costUsd`, `numTurns`) serialized by `GitManagedJob.buildEventJson()`.

## Operator Scripts

Convenience scripts live in `flowtree/bin/` and use `mvn exec:java` for classpath resolution. Each script auto-builds the module on first run if the `target/` directory is missing.

### start-controller.sh

Starts the `FlowTreeController`. Requires Slack tokens via environment variables or a `--tokens` file. The controller starts a FlowTree Server that listens for inbound agent connections.

```bash
# Using environment variables
export SLACK_BOT_TOKEN="xoxb-..."
export SLACK_APP_TOKEN="xapp-..."
./flowtree/bin/start-controller.sh --channel C0123ABCDEF

# Using a tokens file
./flowtree/bin/start-controller.sh --tokens slack-tokens.json --config workstreams.yaml

# Custom FlowTree port
./flowtree/bin/start-controller.sh --flowtree-port 8800 --config workstreams.yaml

# Show full usage
./flowtree/bin/start-controller.sh --help
```

### start-agent.sh

Starts a FlowTree agent that connects OUT to a controller. The agent uses `FLOWTREE_ROOT_HOST` and `FLOWTREE_ROOT_PORT` environment variables to locate the controller and auto-reconnects every 30 seconds if the connection drops.

```bash
# Connect to controller on the Docker host (default)
./flowtree/bin/start-agent.sh

# Connect to a specific controller
FLOWTREE_ROOT_HOST=10.0.0.1 FLOWTREE_ROOT_PORT=7766 ./flowtree/bin/start-agent.sh
```
