# Node Relay and Job Routing

This document describes how jobs move through the FlowTree network,
from submission on the controller to execution on a remote agent. It
is the authoritative reference for the relay mechanism and should be
consulted before modifying any code in `Node`, `NodeGroup`, or
`Connection`.

## Core Concepts

### Servers, NodeGroups, and Nodes

A FlowTree **Server** hosts a single **NodeGroup**. A NodeGroup
contains one or more **Nodes**. Each Node has a job queue, a worker
thread that executes jobs, and an activity thread that manages peer
connections and relays excess jobs.

```
Server
  └── NodeGroup
        ├── Node 0  ← job queue, worker thread, activity thread
        ├── Node 1
        └── Node 2
```

### Server Connections vs. Peer Connections

These are two distinct networking layers and confusing them is a
common source of bugs.

**Server connections** (`NodeProxy`) are socket-level links between
two Servers. They live on the `NodeGroup.servers` list. When an agent
Server starts, it connects outbound to the controller Server,
creating a `NodeProxy` on each side. Server connections carry
`Message` objects (tasks, connection requests, job data).

**Peer connections** (`Connection`) are logical links between two
individual Nodes on different Servers. They live in each `Node.peers`
set. A peer connection wraps a `NodeProxy` and targets a specific
remote Node by ID. Peer connections are how jobs actually move
between Nodes.

```
Controller Server              Agent Server
  NodeGroup                      NodeGroup
    servers: [NodeProxy] ←TCP→ servers: [NodeProxy]

    Node 0                       Node 0
      peers: [Connection] ←→ peers: [Connection]
```

### How Peer Connections Are Established

Peer connections are not configured manually. Nodes request them
automatically through the activity thread:

1. A Node's activity thread runs periodically (controlled by sleep
   intervals and the `connect` probability).
2. If the Node has fewer peers than `maxPeers`, it calls
   `this.parent.getConnection(this.id)`.
3. The `NodeGroup.getConnection()` method picks a random entry from
   `this.servers` and sends a `Message.ConnectionRequest` through
   that `NodeProxy`.
4. The remote `NodeGroup` receives the request, finds its least
   connected child Node, creates a `Connection`, and sends back a
   `Message.ConnectionConfirmation`.
5. Both sides now have a `Connection` in their `peers` set, linking
   a local Node to a remote Node.

The upshot: once two Servers are connected (via `NodeProxy`), their
child Nodes will gradually establish peer connections with each other.
This takes a few seconds after the initial server connection.

## The Job Lifecycle

### 1. Submission

Jobs enter the system through `Server.addTask(JobFactory)`. The
factory is added to `NodeGroup.tasks`. On each iteration of the
NodeGroup run loop, `addJobs(factory)` calls `factory.nextJob()` to
produce a `Job` and hands it to `getLeastActiveNode().addJob(job)`.

### 2. Queuing

The job enters a child Node's `jobs` queue. `Node.addJob()` also
starts the worker thread if it is not already running.

### 3. Execution or Re-queue

**On a relay Node** (`role:relay`), the worker thread is never
started. Jobs accumulate in the queue and are moved exclusively by
the relay loop in the activity thread. There is no contention.

**On an execution Node**, the worker thread pulls jobs from the
queue. Before executing, it checks `this.satisfies(job.getRequiredLabels())`:

- **Labels match:** The worker executes the job via `job.run()`.
- **Labels do not match:** The worker puts the job back at the
  **end** of the queue via `addJob(job)`, yields, and continues to
  the next job. While the worker is busy executing matching jobs,
  the activity thread's relay loop sends the mismatched job to a
  peer.

### 4. Relay

The activity thread (separate from the worker thread) runs
continuously on every Node. On each iteration, after handling
connection establishment, it evaluates whether to relay a job:

```
if (jobs.size() > minJobs && random < relayProbability) {
    Connection peer = getRandomPeer();
    if (peer != null) {
        Job j = nextJob();
        peer.sendJob(j);  // sends via Message.Job over the NodeProxy
    }
}
```

The relay probability increases with queue depth. Peer selection is
optionally weighted by activity rating (less active peers are
preferred). The relayed job arrives at the remote `NodeGroup` via
`recievedMessage()`, which hands it to `getLeastActiveNode().addJob()`.

On the remote (agent) side, the Node's worker picks up the job,
checks labels, finds a match, and executes it.

### 5. Complete Flow Diagram

```
Controller                              Agent
──────────                              ─────
server.addTask(factory)
  │
  ▼
NodeGroup run loop
  factory.nextJob() → Job
  getLeastActiveNode() → relay Node
  relayNode.addJob(job)
  │
  ▼
relay Node (worker thread not started)
  jobs sit in queue
  │
  ▼
relay Node activity thread
  jobs.size() > minJobs? YES
  getRandomPeer() → Connection
  peer.sendJob(job) ─────────────────→ NodeGroup.recievedMessage()
                                          getLeastActiveNode() → agent Node
                                          agentNode.addJob(job)
                                          │
                                          ▼
                                        agent Node worker thread
                                          nextJob() → job
                                          satisfies()? YES
                                          job.run()
```

## The Controller's Relay Node

The `FlowTreeController` starts its Server with:

```java
flowtreeProps.setProperty("nodes.initial", "1");
flowtreeProps.setProperty("nodes.labels.role", "relay");
```

This creates a single child Node labeled `role:relay` with a large
queue (`nodes.jobs.max=100`) and zero minimum job threshold
(`nodes.mjp=0.0`). The `Node.satisfies()` method returns `false`
for any Node with `role:relay`, regardless of the job's
requirements. This means:

- **The relay Node never executes jobs.** Its worker thread always
  rejects jobs back into the queue.
- **Jobs accumulate in the queue** until the relay Node establishes
  peer connections with agent Nodes.
- **The activity thread relays them** to peers using the standard
  relay loop.

The relay Node's worker thread is never started (the `addJob()`
method skips worker startup for Nodes with `role:relay`). This means
there is no contention between the worker and the relay loop — jobs
enter the queue and stay there until the activity thread sends them
to a peer. This uses exactly the same relay infrastructure that any
Node uses when its queue exceeds `minJobs`.

## Label-Based Routing

### Node Labels

Each Node has a `Map<String, String>` of labels set via
`Node.setLabel(key, value)`. Labels are configured through:

- Properties file: `nodes.labels.<key>=<value>`
- Environment variable: `FLOWTREE_NODE_LABELS=key1:value1,key2:value2`
- Auto-detection: `platform` label is set to `macos` or `linux`

### Job Requirements

Each `Job` can declare required labels via `getRequiredLabels()`.
`ClaudeCodeJob.Factory` propagates these from `setRequiredLabel()`.

### Matching Rules

`Node.satisfies(requirements)` returns true if and only if:

1. The Node does **not** have `role:relay` (relay nodes never execute).
2. Every key-value pair in `requirements` exists in the Node's labels.
3. If `requirements` is empty, any non-relay Node satisfies it.

### Important Invariant

**Jobs are never filtered at the queue level.** Any Node accepts any
job into its queue via `addJob()`. On relay Nodes, the worker thread
is never started, so jobs remain in the queue for the activity
thread's relay loop to forward them. On execution Nodes, the worker
pulls and runs jobs directly.

## Common Pitfalls

### Do Not Bypass the Relay Mechanism

The relay loop is the only correct way to move jobs between Nodes on
different Servers. Do not:

- Send jobs directly to a specific server index (`sendTask` with a
  hardcoded index). This bypasses label matching and fails silently
  if the server is unreachable.
- Try to send jobs from the NodeGroup's worker thread using
  `instanceof` checks or special NodeGroup methods. The NodeGroup's
  role is to distribute to child Nodes; the child Nodes relay to
  peers.
- Create parallel relay paths outside the activity thread. The
  activity thread's relay loop already handles peer selection,
  activity weighting, and connection management.

### Relay Depends on Peer Connections

Jobs will not move to remote Servers until peer connections are
established. This happens automatically but takes a few seconds after
server connection. If jobs appear stuck on the controller:

1. Check that the agent Server connected (look for "Added server" in
   logs).
2. Check that the relay Node established peers (look for "Selected"
   or connection messages).
3. Verify `nodes.peers.max` is at least 1 (default: 2).

### Relay Depends on Queue Exceeding minJobs

The relay loop only fires when `jobs.size() > minJobs`. The
`minJobs` threshold is computed as `(int)(maxJobs * minJobP)`. With
the defaults (`maxJobs=4`, `minJobP=0.4`), `minJobs` is 1 — meaning
a single job in the queue will **not** trigger relay.

For a relay Node that must forward every job, set `nodes.mjp=0.0`
so that `minJobs` is 0 and any job in the queue triggers relay. The
`FlowTreeController` does this automatically.

### The Worker Thread and Activity Thread Are Independent

The worker thread pulls jobs and either executes or re-queues them.
The activity thread manages connections and relays excess jobs. They
operate on the same job queue but serve different purposes. The
worker thread should never attempt to relay jobs itself — it simply
puts rejected jobs back in the queue for the activity thread to
handle.

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `nodes.initial` | `1` | Number of child Nodes in the NodeGroup |
| `nodes.jobs.max` | `4` | Maximum job queue depth per Node |
| `nodes.peers.max` | `2` | Maximum peer connections per Node |
| `nodes.labels.<key>` | -- | Label key-value pairs for all Nodes |
| `nodes.relay` | `1.0` | Base relay probability (0.0 to 1.0) |
| `group.thread.sleep` | `10000` | NodeGroup run loop sleep (ms) |
