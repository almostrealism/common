# FlowTree

FlowTree is a distributed processing framework built on a decentralized peer-to-peer network. Each node in the network can accept, execute, and relay jobs to other nodes, enabling work to be distributed across an arbitrary number of machines without a central coordinator.

## Architecture

A FlowTree network consists of **Servers** and **Nodes**:

- A **Server** listens for peer connections and manages a **NodeGroup** -- a collection of Nodes that process jobs locally.
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

## Key Components

| Class | Role |
|-------|------|
| `Server` | Accepts peer connections, manages a `NodeGroup`, routes jobs |
| `NodeGroup` | Collection of `Node` instances sharing a `JobFactory` |
| `Node` | Processes one job at a time, relays excess work to peers |
| `Agent` | Minimal entry point for starting a passive server node |
| `Manager` | Entry point for starting a server with a task to distribute |

## Capabilities

- **[Coding Agent Integration](docs/coding-agent.md)** -- Execute Claude Code prompts as distributed jobs, with automatic git management for committing and pushing changes.
- **[Slack Integration](docs/slack-integration.md)** -- Operate coding agents through Slack, with real-time status updates and bidirectional messaging via MCP tools. Includes dynamic workstream registration with auto-created private Slack channels.
- **[CI Integration](docs/ci-integration.md)** -- Auto-resolve CI failures and implement plan goals via the verify-completion workflow, with workstream registration, prompt generation, and quality gates.
- **[Agent Pool](docs/agent-pool.md)** -- Self-contained Docker setup for running a scalable pool of agent nodes. Includes automated build, OAuth token authentication, and one-command startup.
- **[MCP Tools for Agent Jobs](../tools/mcp/README.md)** -- Detailed documentation on which MCP tools are available to coding agents, how they are delivered (centralized, pushed, or local), and how to configure `workstreams.yaml` to ensure cross-repo jobs have access to messaging, consultation, GitHub, and memory tools.

## Running

### Start an Agent Pool (Docker)

The recommended way to run agents. See [Agent Pool](docs/agent-pool.md) for full details.

```bash
cd flowtree/agent
./start.sh
```

This builds the flowtree JAR, prompts for your controller host and Claude Code OAuth token, and launches a pool of agent containers.

### Start a Single Agent (bare metal)

```bash
./bin/start-agent.sh
```

Set `FLOWTREE_ROOT_HOST` and `FLOWTREE_ROOT_PORT` to point at the controller. Requires Java 17, Maven, Node.js, and Claude Code CLI installed locally.

### Start a Server

```bash
java -cp flowtree.jar io.flowtree.Server <properties-file> <JobFactory-class>
```

The properties file configures networking, node counts, and monitoring. Pass `-p` instead of a class name to run in passive mode (no local job generation).

### Key Properties

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `7766` | Port for peer connections (-1 to disable) |
| `nodes.peers.max` | -- | Maximum peer connections per node |
| `nodes.jobs.max` | -- | Maximum job queue depth per node |
| `nodes.workingDirectory` | -- | Server-side workspace root for repo checkouts |
| `group.msc` | -- | Max sleep coefficient (controls idle backoff) |

## Module Structure

```
flowtree/
  src/main/java/io/flowtree/
    Server.java             # Core server with peer networking
    Agent.java              # Passive agent entry point
    Manager.java            # Task manager entry point
    ClaudeCodeClient.java   # Client for submitting coding agent jobs
    node/                   # Node, NodeGroup, Client, Proxy
    msg/                    # Message, Connection, NodeProxy
    jobs/                   # ClaudeCodeJob, GitManagedJob, ExternalProcessJob
    slack/                  # FlowTreeController, SlackListener, SlackNotifier, JobStatsStore
    job/                    # Job, JobFactory interfaces
    fs/                     # Distributed file system
    scheduler/              # Scheduled job support
  agent/                    # Docker agent pool (Dockerfile, compose, start script)
  bin/                      # Startup scripts (start-agent.sh, start-controller.sh)
  conf/                     # Properties files (agent.properties)
```
