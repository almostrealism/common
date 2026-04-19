# FlowTree

FlowTree is a distributed processing framework built on a decentralized peer-to-peer network. Each node in the network can accept, execute, and relay jobs to other nodes, enabling work to be distributed across an arbitrary number of machines without a central coordinator.

## System Overview

A full FlowTree deployment has two parts — a **controller stack** running on a server and an **agent pool** running wherever compute is available. The controller coordinates job dispatch and integrates with external services; the agents do the actual work.

```
  External triggers
  (Slack, CI, API)
         │
         ▼
┌────────────────────────────────────────────────────────┐
│  Controller Stack  (flowtree/controller/docker-compose) │
│                                                        │
│  ┌─────────────────────┐    ┌──────────────────────┐  │
│  │  flowtree-controller │    │      ar-manager       │  │
│  │                     │◄───│   (MCP bridge)        │  │
│  │  :7766  peer port   │    │   :8010               │  │
│  │  :7780  HTTP API    │    │                       │  │
│  └──────────┬──────────┘    └──────────────────────┘  │
│             │                          │               │
│             │               ┌──────────▼─────────┐    │
│             │               │      ar-memory      │    │
│             │               │   (vector store)    │    │
│             │               │   :8020             │    │
│             │               └─────────────────────┘    │
└─────────────┼──────────────────────────────────────────┘
              │ outbound :7766
              ▼
┌────────────────────────────────────────────────────────┐
│  Agent Pool  (flowtree/agent/docker-compose)           │
│                                                        │
│  ┌───────────┐   ┌───────────┐   ┌───────────┐        │
│  │  agent-1  │   │  agent-2  │   │  agent-N  │        │
│  │  Claude   │   │  Claude   │   │  Claude   │        │
│  │  Code CLI │   │  Code CLI │   │  Code CLI │        │
│  └───────────┘   └───────────┘   └───────────┘        │
└────────────────────────────────────────────────────────┘
```

### Component Roles

| Component | Location | Role |
|-----------|----------|------|
| `flowtree-controller` | `flowtree/controller/Dockerfile` | Java server that accepts agent connections (:7766), exposes a low-level job submission API (:7780), integrates with Slack, and dispatches `ClaudeCodeJob` instances to agents |
| **`ar-manager`** | `tools/mcp/manager/` | **Preferred entrypoint.** Python MCP server that exposes FlowTree to Claude Code agents, Claude mobile, CI pipelines, and any other HTTP client. Handles authentication, rate limiting, GitHub integration, and memory. |
| `ar-memory` | `tools/mcp/memory/` | Semantic vector store that persists agent memories and decisions across sessions |
| Agent containers | `flowtree/agent/` | Each agent connects outbound to the controller, receives `ClaudeCodeJob` instances, and executes Claude Code prompts inside a Docker container with its own git workspace |

### ar-manager is the preferred entrypoint

**Do not call the FlowTree controller API (port 7780) directly** except for
low-level diagnostics. Everything that submits jobs, registers workstreams, or
queries status should go through ar-manager:

- **Claude mobile / external AI** — configure ar-manager's public Tailscale
  Funnel URL as a remote MCP server.
- **Claude Code agents** — MCP tools (`workstream_submit_task`,
  `workstream_get_status`, etc.) are served by ar-manager.
- **CI pipelines** — `tools/ci/submit-agent-job.sh` calls ar-manager's HTTP
  API, not the controller directly.
- **Slack** — the controller listens to Slack for interactive use, but
  programmatic submission should still go through ar-manager.

The controller's port 7780 API exists for ar-manager and the Slack integration
to use internally. Bypassing ar-manager means losing authentication, rate
limiting, GitHub integration, and memory tooling.

See the [ar-manager README](../tools/mcp/manager/README.md) for setup
instructions, including how to expose it publicly via Tailscale Funnel.

Agents connect **out** to the controller — the controller never initiates
connections to agents. Agents can therefore run behind NAT or on ephemeral
machines with no inbound firewall rules required.

---

## Running the Controller Stack

### Prerequisites

- Docker with Compose v2
- Java 17 + Maven (for the build step)
- `/Users/Shared/flowtree/controller/workstreams.yaml` — workstream configuration
- `/Users/Shared/flowtree/secrets/` — token files (auto-generated on first run)

### Start everything

```bash
./flowtree/rebuild.sh
```

This script (run from the repo root):
1. Generates an `ar-manager` shared secret if one doesn't exist
2. Runs `mvn package` on the flowtree module
3. Builds and starts `flowtree-controller`, `ar-memory`, and `ar-manager` via Docker Compose

### Rebuild a single service

```bash
./flowtree/rebuild.sh flowtree-controller   # just the controller
./flowtree/rebuild.sh ar-manager            # just the manager
```

### Start the agent pool too

```bash
./flowtree/rebuild.sh --agents        # controller stack + agent pool
./flowtree/rebuild.sh --agents-only   # agent pool only (controller already running)
```

### Manual compose commands

```bash
# Start
docker compose -f flowtree/controller/docker-compose.yml up -d

# View logs
docker compose -f flowtree/controller/docker-compose.yml logs -f flowtree-controller

# Stop
docker compose -f flowtree/controller/docker-compose.yml down
```

### Workstream configuration

Workstreams are defined in `/Users/Shared/flowtree/controller/workstreams.yaml`. Each workstream maps a Slack channel (or API endpoint) to a git repository and branch. See `flowtree/src/main/resources/workstreams-example.yaml` for the full reference.

---

## Running the Agent Pool

See [Agent Pool](docs/agent-pool.md) for full details.

```bash
# Docker (recommended)
./flowtree/rebuild.sh --agents-only

# Or directly
cd flowtree/agent && ./start.sh
```

Agents read `FLOWTREE_ROOT_HOST` to find the controller and connect outbound on port 7766.

### Start a single agent (bare metal)

```bash
./bin/start-agent.sh
```

Requires Java 17, Maven, Node.js, and the Claude Code CLI installed locally.

---

## Core Architecture

A FlowTree network consists of **Servers** and **Nodes**:

- A **Server** listens for peer connections and manages a **NodeGroup** — a collection of Nodes that process jobs locally.
- Each **Node** maintains peer connections to Nodes on other Servers, forming a mesh network. Nodes relay jobs to peers based on activity ratings and queue depth, naturally load-balancing work across the network.
- Jobs are created by **JobFactory** instances and distributed through the network. When a Node finishes a job, it picks up the next one from its queue or receives relayed work from a peer.

```
Server A (port 7766)         Server B (port 7766)
  NodeGroup                    NodeGroup
    Node 0 <--- peer ----------> Node 0
    Node 1 <--- peer ----------> Node 1
    Node 2 <--- peer ----------> Node 2
```

Servers discover each other through explicit connection (host + port) or through peer-list exchange. The network self-organizes: Nodes adjust their sleep intervals based on activity, request new connections when under-connected, and relay excess jobs to less busy peers.

### Key Classes

| Class | Role |
|-------|------|
| `Server` | Accepts peer connections, manages a `NodeGroup`, routes jobs |
| `NodeGroup` | Collection of `Node` instances sharing a `JobFactory` |
| `Node` | Processes one job at a time, relays excess work to peers |
| `FlowTreeController` | Entry point for the controller; integrates Slack and the HTTP API |
| `ClaudeCodeJob` | Executes a Claude Code prompt, commits results to git |
| `GitManagedJob` | Base class; handles branch setup, commit, push, and workspace locking |

---

## Capabilities

- **[Coding Agent Integration](docs/coding-agent.md)** — Execute Claude Code prompts as distributed jobs, with automatic git management for committing and pushing changes.
- **[Slack Integration](docs/slack-integration.md)** — Operate coding agents through Slack, with real-time status updates and bidirectional messaging via MCP tools. Includes dynamic workstream registration with auto-created private Slack channels.
- **[CI Integration](docs/ci-integration.md)** — Auto-resolve CI failures and implement plan goals via the verify-completion workflow, with workstream registration, prompt generation, and quality gates.
- **[Node Relay and Job Routing](docs/node-relay.md)** — How jobs move through the network: server vs. peer connections, the relay loop, label-based routing, and the controller's relay Node. **Read this before modifying Node, NodeGroup, or Connection.**
- **[Agent Pool](docs/agent-pool.md)** — Self-contained Docker setup for running a scalable pool of agent nodes.
- **[MCP Tools for Agent Jobs](../tools/mcp/README.md)** — Which MCP tools are available to coding agents, how they are delivered, and how to configure `workstreams.yaml`.

---

## Module Structure

```
flowtree/
  controller/               # Controller stack
    Dockerfile
    docker-compose.yml
  agent/                    # Agent pool Docker Compose + Dockerfile
    docker-compose.yml
    Dockerfile
    start.sh
  bin/                      # Bare-metal startup scripts
    start-agent.sh
    start-controller.sh
  conf/                     # Runtime properties
    agent.properties
  rebuild.sh                # One-command build + deploy script
  docs/                     # Architecture and integration guides
  src/main/java/io/flowtree/
    Server.java             # Core server with peer networking
    node/                   # Node, NodeGroup, Client, Proxy
    msg/                    # Message, Connection, NodeProxy
    jobs/                   # ClaudeCodeJob, GitManagedJob, ExternalProcessJob
    slack/                  # FlowTreeController, SlackListener, SlackNotifier
    job/                    # Job, JobFactory interfaces
    fs/                     # Distributed file system
    scheduler/              # Scheduled job support
```
