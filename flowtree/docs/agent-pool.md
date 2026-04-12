# Agent Pool

A self-contained Docker setup for running a pool of FlowTree agent nodes.
Each container runs a FlowTree `Server` that connects outbound to your
controller and executes `ClaudeCodeJob` instances via the Claude Code CLI.

## Prerequisites

- Docker and Docker Compose
- Maven (for building the flowtree JAR)
- A running FlowTree controller (see [Slack Integration](slack-integration.md))
- A Claude Code OAuth token **or** an Anthropic API key

## Quick Start

```bash
cd flowtree/agent
./start.sh
```

The script will:
1. Build the flowtree module if JARs are not present
2. Prompt for the controller host and Claude Code token
3. Launch the agent pool via Docker Compose

On subsequent runs, previously entered values are loaded from `.env` and
pressing Enter keeps them unchanged.

### Scaling

The default pool size is 2 agents. Change it via the `AGENT_COUNT`
environment variable or `.env`:

```bash
AGENT_COUNT=4 ./start.sh
```

Or scale after launch:

```bash
docker compose -f docker-compose.yml up -d --scale agent=4
```

### Stopping

```bash
./start.sh --stop
```

### Status

```bash
./start.sh --status
```

## Authentication

Agent containers need credentials to run Claude Code. Two methods are
supported; the OAuth token approach is recommended for Max/Pro/Team plans.

### OAuth Token (recommended)

Generate a long-lived token from any machine where you can open a browser:

```bash
claude setup-token
```

Copy the resulting token and provide it when `start.sh` prompts, or set it
in `.env`:

```
CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-...
```

The token is shared by all agent containers in the pool.

### API Key

If you prefer pay-as-you-go billing, set `ANTHROPIC_API_KEY` instead.
Add it to `.env` and remove or leave blank the `CLAUDE_CODE_OAUTH_TOKEN`
line. Update `docker-compose.yml` to pass it through:

```yaml
environment:
  - ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
```

## Configuration Reference

All settings live in `flowtree/agent/.env` (copied from `.env.example`).

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CLAUDE_CODE_OAUTH_TOKEN` | Yes* | -- | OAuth token from `claude setup-token` |
| `FLOWTREE_ROOT_HOST` | Yes | -- | Controller hostname or IP |
| `FLOWTREE_ROOT_PORT` | No | `7766` | Controller FlowTree port |
| `AGENT_COUNT` | No | `2` | Number of agent containers |
| `AGENT_CPUS` | No | `4` | CPU limit per container |
| `AGENT_MEMORY` | No | `4g` | Memory limit per container |
| `AGENT_JVM_OPTS` | No | `-Xmx2048m` | JVM options for the FlowTree Server |
| `GIT_USER_NAME` | No | `FlowTree Agent` | Git author name for commits |
| `GIT_USER_EMAIL` | No | `agent@flowtree.io` | Git author email |
| `SSH_KEY_DIR` | No | -- | Host path to SSH keys (for private repos) |
| `FLOWTREE_NODE_LABELS` | No | -- | Comma-separated `key:value` node labels |

\* Either `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` must be set.

## Working Directory

Agent containers use `/workspace/project` as the workspace root, backed
by a Docker volume. When a job arrives with a `repoUrl`, the repository is
cloned into a subdirectory (e.g., `/workspace/project/almostrealism-common`).

This is controlled by the `flowtree.workingDirectory` system property,
which can also be set via the `nodes.workingDirectory` property in
`conf/agent.properties`. The `start.sh` script and `docker-compose.yml`
set this automatically via the `FLOWTREE_WORKING_DIR` environment variable.

## How It Works

```
                     +-----------+
                     | Controller|
                     | :7766     |
                     +-----+-----+
                           |
              +------------+------------+
              |            |            |
         +----+----+  +---+----+  +----+----+
         | Agent 1 |  | Agent 2|  | Agent 3 |
         | Server  |  | Server |  | Server  |
         +---------+  +--------+  +---------+
              |            |            |
         Claude Code  Claude Code  Claude Code
```

Each agent container runs:
1. A FlowTree `Server` (Java) that connects outbound to the controller
2. The server's `Node` receives `ClaudeCodeJob` instances from the network
3. Each job spawns `claude -p "<prompt>"` via the Claude Code CLI
4. Git operations (clone, branch, commit, push) happen inside the container
5. Job completion events are posted back to the controller

The controller distributes jobs across connected agents based on node
activity and queue depth. Agents with fewer active jobs receive work first.

## Private Repositories

To clone private repos via SSH, mount your SSH keys:

```
SSH_KEY_DIR=/path/to/.ssh
```

The keys are mounted read-only at `/home/agent/.ssh` inside the container.

## Rebuilding

To force a fresh Maven build before starting:

```bash
./start.sh --rebuild
```

This rebuilds the flowtree JAR and re-copies all dependency JARs before
launching the Docker build.
