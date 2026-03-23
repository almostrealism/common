# Multiplatform Nodes: Label-Based Job Routing for FlowTree

## Overview

This plan adds a label-based job routing system to FlowTree Nodes. Nodes will have configurable key/value labels describing their capabilities (e.g., `platform:macos`, `gpu:true`). Jobs can specify required labels, and a Node will only execute a job if it satisfies all requirements. Unmatched jobs are returned to circulation.

---

## Requirement 1: Node Labels System

### Current Behavior

`Node` (`flowtree/src/main/java/io/flowtree/node/Node.java`) has no concept of labels or capabilities. It accepts any job unconditionally via `addJob(Job)` (line 519) and executes the next available job in its worker thread (line 153).

`NodeGroup` (`flowtree/src/main/java/io/flowtree/node/NodeGroup.java`) distributes jobs to child `Node` instances via `getLeastActiveNode()` (line 420) and `addJob()` without any filtering.

### Implementation Steps

1. **Add a `Map<String, String> labels` field to `Node`**
   - File: `flowtree/src/main/java/io/flowtree/node/Node.java`
   - Add `private final Map<String, String> labels = new LinkedHashMap<>()` as an instance field
   - Add `public void setLabel(String key, String value)` and `public Map<String, String> getLabels()`
   - Add `public boolean satisfies(Map<String, String> requirements)` — returns true if for every entry in `requirements`, the Node's `labels` map contains the same key with the same value

2. **Populate labels from Properties at construction**
   - File: `flowtree/src/main/java/io/flowtree/node/NodeGroup.java`
   - In the `NodeGroup(Properties, JobFactory)` constructor (line 102), after constructing child Nodes, scan properties for entries matching `nodes.labels.<key>=<value>` and call `setLabel()` on each child Node
   - The NodeGroup itself should also receive these labels (it extends Node)

3. **Support environment variable configuration**
   - In `NodeGroup` constructor, also check `FLOWTREE_NODE_LABELS` environment variable
   - Format: `key1:value1,key2:value2` (e.g., `platform:macos,gpu:true`)
   - Environment variable labels are applied to all child Nodes and the NodeGroup

### Risks and Considerations

- Labels are immutable after startup in this design. Dynamic label changes would require a more complex protocol (deferred to future work).
- The `satisfies()` method uses exact string matching. Wildcard or range matching (e.g., `memory:>=16GB`) is out of scope.

### Backward Compatibility

- Nodes with no labels satisfy any job with no requirements (empty requirements map always matches). This preserves existing behavior completely.

---

## Requirement 2: Built-in Platform Detection

### Current Behavior

There is no platform detection in the FlowTree codebase. Nodes are platform-agnostic.

### Implementation Steps

1. **Auto-detect platform label in `NodeGroup` constructor**
   - File: `flowtree/src/main/java/io/flowtree/node/NodeGroup.java`
   - After label initialization from Properties/env vars, if `labels` does not already contain a `platform` key, auto-detect:
     ```java
     String os = System.getProperty("os.name", "").toLowerCase();
     String platform = os.contains("mac") ? "macos" : "linux";
     setLabel("platform", platform);
     ```
   - Apply the same platform label to all child Nodes
   - The auto-detected value can be overridden by explicitly setting `platform` in Properties or env var

2. **Log the detected platform at startup**
   - Print: `"NodeGroup: Auto-detected platform label: platform=<value>"`

### Risks and Considerations

- Windows is mapped to `linux` by default since FlowTree agents don't target Windows. If needed, a `windows` value can be added later.
- Explicit configuration always overrides auto-detection, so this is safe to deploy.

### Backward Compatibility

- All Nodes will now have a `platform` label, but since existing jobs have no requirements, this has no effect on existing job routing.

---

## Requirement 3: Job Requirements

### Current Behavior

`Job` (`flowtreeapi/src/main/java/io/flowtree/job/Job.java`) extends `KeyValueStore` which provides `encode()` and `set(String key, String value)` for serialization. Jobs carry a task ID and are serialized/deserialized via the key-value encoding format (`classname:key0=value0:key1=value1...`).

`ClaudeCodeJob` (`flowtree/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java`) is the primary Job implementation. Its `encode()` (line 769) serializes all fields and its `set()` (line 791) deserializes them.

`JobFactory` (`flowtreeapi/src/main/java/io/flowtree/job/JobFactory.java`) produces Jobs and also implements `encode()`/`set()` for transmission between servers.

### Implementation Steps

1. **Add requirements to `Job` interface**
   - File: `flowtreeapi/src/main/java/io/flowtree/job/Job.java`
   - Add default method:
     ```java
     default Map<String, String> getRequiredLabels() {
         return Collections.emptyMap();
     }
     ```

2. **Implement requirements in `GitManagedJob`**
   - File: `flowtree/src/main/java/io/flowtree/jobs/GitManagedJob.java`
   - Add `private final Map<String, String> requiredLabels = new LinkedHashMap<>()`
   - Add `public void setRequiredLabel(String key, String value)` and override `getRequiredLabels()`
   - Serialize in `encode()`: encode required labels as `req.<key>=<value>` entries
   - Deserialize in `set()`: keys starting with `req.` populate `requiredLabels`

3. **Add requirements to `ClaudeCodeJob.Factory`**
   - File: `flowtree/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java`
   - Add `private final Map<String, String> requiredLabels = new LinkedHashMap<>()`
   - Add `public void setRequiredLabel(String key, String value)`
   - In `Factory.encode()`, serialize as `req.<key>=<value>`
   - In `Factory.set()`, deserialize keys starting with `req.`
   - In `Factory.nextJob()` / `createJob()`, propagate required labels to the created `ClaudeCodeJob`

### Risks and Considerations

- The `req.` prefix in the encoding format must not conflict with existing key names. Current keys use full names like `targetBranch`, `allowedTools`, etc., so `req.` is safe.
- The `AbstractJobFactory` base class also has `encode()`/`set()` — ensure the `req.` keys are handled at the right level in the class hierarchy.

### Backward Compatibility

- `getRequiredLabels()` returns an empty map by default, so all existing Job implementations continue to work without changes.

---

## Requirement 4: Job Circulation on Mismatch

### Current Behavior

Jobs flow through the system via several paths:

1. **Controller to Agent**: `SlackListener.submitJob()` (line 340) calls `server.sendTask(factory, index)` which sends the encoded `JobFactory` to a connected agent server via `NodeGroup.sendTask(JobFactory, int)` (line 788). This sends a `Message.Task` to the remote NodeProxy.

2. **Agent receives task**: On the agent side, `NodeGroup.recievedMessage()` (line 1327) handles `Message.Task` by calling `addTask(m.getData())` (line 1455), which decodes and adds the `JobFactory` to the local task list.

3. **Task produces jobs**: The NodeGroup's run loop (line 1236) iterates tasks and calls `addJobs(JobFactory)` (line 1281), which gets jobs from the factory via `nextJob()` and assigns them to the least active Node via `getLeastActiveNode().addJob(j)`.

4. **Node executes job**: The Node's worker thread (line 151) calls `nextJob()` to dequeue and `j.run()` to execute.

5. **Node relays jobs**: When a Node has too many jobs (`js > minJobs`), it relays jobs to peers via `Connection.sendJob(j)` (line 1039) or back to the parent NodeGroup via `parent.addJob(j)` (line 1031).

6. **Inter-server job relay**: Jobs sent via `Connection.sendJob()` are transmitted as `Message.Job` messages. On the receiving end, `NodeGroup.recievedMessage()` handles `Message.Job` (line 1334) by assigning to the least active node.

### Implementation Steps

1. **Add label check in the Node worker thread execution path (NOT in `addJob()`)**
   - File: `flowtree/src/main/java/io/flowtree/node/Node.java`
   - Jobs must be **accepted into any Node's queue** regardless of labels. This is critical because Nodes serve as relay points — other Nodes connected via peers can pull jobs from the queue. If `addJob()` rejected mismatched jobs, they would have nowhere to sit while waiting for a matching Node to pick them up.
   - Instead, add the label check in the worker thread's execution loop (line ~153), before calling `j.run()`:
     ```java
     Job j = nextJob();
     if (j != null) {
         if (!satisfies(j.getRequiredLabels())) {
             displayMessage("Skipping job " + j.getTaskId() + " -- labels mismatch, relaying");
             if (this.parent != null) {
                 this.parent.addJob(j);  // Relay back to NodeGroup for re-routing
             }
             continue;
         }
         j.run();
     }
     ```
   - When a Node dequeues a job it cannot execute, it relays it back to its parent NodeGroup. The NodeGroup then routes it to another local Node or to a connected peer server.

2. **Handle relayed jobs in `NodeGroup`**
   - File: `flowtree/src/main/java/io/flowtree/node/NodeGroup.java`
   - When a job is relayed back from a child Node via `addJob()`, the NodeGroup should attempt to route it to a different local Node. If no local Node can handle it (checked via `satisfies()`), send it to a connected peer server.
   - Add a method `findMatchingNode(Job)` that iterates `this.nodes` and returns the first Node whose labels satisfy the job's requirements. If none match locally, relay to a peer server.

3. **Relay unmatched jobs to peer servers**
   - When no local Node can run a job, iterate `servers` (the list of connected NodeProxy peers) and send the job as a `Message.Job` to the first available one. This leverages the existing inter-server relay mechanism.
   - Add a relay count or TTL to prevent infinite circulation. Include a `relayCount` in the Job encoding that increments each time the job is relayed. If it exceeds a threshold (e.g., 10), log a warning and drop the job.

4. **Ensure `addJob()` always accepts jobs (no label check)**
   - `Node.addJob(Job)` (line 519) remains unchanged — it unconditionally adds jobs to the queue. This preserves its role as a queue/warehouse that any connected Node can pull from.

### Risks and Considerations

- **Infinite circulation**: Without a TTL, a job with requirements that no Node satisfies could circulate forever. The relay count/TTL is essential.
- **Performance**: The label check is a fast map lookup (O(k) where k = number of required labels, typically 1-2), so there is negligible overhead. The relay path (dequeue → check → relay to parent → route to peer) adds minimal latency.
- **Relay Node churn**: The controller's relay Node (see Requirement 5) will dequeue jobs, fail the label check, and relay them back to its parent NodeGroup. This is by design — the relay Node exists to hold jobs in its queue so connected agent Nodes can discover them. The worker thread's `sleepPeriod` prevents tight spinning.
- **Job ordering**: Relayed jobs re-enter the NodeGroup's routing, which may alter ordering. This is acceptable since the system already does not guarantee strict ordering.

### Backward Compatibility

- Jobs with no requirements (`getRequiredLabels()` returns empty map) always pass the `satisfies()` check (empty requirements match any Node). Existing job flow is completely unchanged.
- `addJob()` behavior is unchanged — all jobs are accepted into any Node's queue as before.

---

## Requirement 5: Controller Relay Node

### Current Behavior

The `FlowTreeController` (line 382-388 in `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java`) currently sets `nodes.initial=0`:

```java
// Set nodes.initial=0 so the controller never processes jobs locally --
// all jobs are forwarded to connected agents.
Properties flowtreeProps = new Properties();
flowtreeProps.setProperty("server.port", String.valueOf(flowtreePort));
flowtreeProps.setProperty("nodes.initial", "0");
flowtreeServer = new Server(flowtreeProps);
```

With `nodes.initial=0`, the `NodeGroup` creates no child Nodes, so `getLeastActiveNode()` returns null. In the current system (without labels), this works because jobs are sent directly to agents via `sendTask()` and never need to come back through the controller.

### Problem

With label-based routing, jobs can be **rejected** by an agent Node whose labels don't match. Requirement 4 specifies that unmatched jobs are relayed to peer servers, which means they can flow back to the controller's NodeGroup. When this happens with `nodes.initial=0`:

1. `NodeGroup.recievedMessage()` handles `Message.Job` by calling `getLeastActiveNode()`, which returns `null`
2. The job is logged and **silently discarded** (line ~1338)
3. Similarly, `addJobs()` silently drops jobs when `getLeastActiveNode()` returns `null`

The controller's Node is essential as a **warehouse/relay point** in the job circulation network. Nodes serve as connection points — jobs sit in a Node's queue and get picked up by other Nodes from other Servers that are connected to it. Without a Node, the controller is a dead end: jobs that arrive via `Message.Job` have nowhere to go.

### Implementation Steps

1. **Change controller to `nodes.initial=1` with a relay label**
   - File: `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java`
   - Update the controller's FlowTree properties:
     ```java
     Properties flowtreeProps = new Properties();
     flowtreeProps.setProperty("server.port", String.valueOf(flowtreePort));
     flowtreeProps.setProperty("nodes.initial", "1");
     flowtreeProps.setProperty("nodes.labels.role", "relay");
     flowtreeServer = new Server(flowtreeProps);
     ```
   - The controller now has one child Node with the label `role:relay`

2. **Ensure the relay Node participates in job circulation**
   - The controller's Node joins the circulation network like any other Node. Connected Nodes from agent Servers can pull jobs from it via the existing peer relay mechanism (`Connection.sendJob()` / `parent.addJob()`)
   - When the controller's NodeGroup receives a `Message.Job`, `getLeastActiveNode()` now returns the relay Node instead of null, so the job enters its queue instead of being dropped
   - When the controller's NodeGroup receives a `Message.Task`, `addJobs()` assigns generated jobs to the relay Node's queue, from which they can be relayed to connected agent Nodes

3. **Label mismatch prevents execution**
   - No job will ever have `role:relay` in its required labels (this is a controller-internal label, not a platform capability)
   - When the relay Node's worker thread dequeues a job and checks `satisfies(j.getRequiredLabels())` (the execution-path label check from Requirement 4), the check fails because `role:relay` does not match requirements like `platform:macos`
   - The Node relays the job back to its parent NodeGroup, which routes it to a connected agent server
   - This is the same mechanism used by all Nodes — the relay Node is not special-cased, it simply never matches any job's requirements

### Risks and Considerations

- **The relay Node's worker thread will spin on unmatched jobs**: If many jobs arrive that the relay Node can't execute, it will repeatedly dequeue and re-relay them. This is acceptable because (a) the controller handles relatively few concurrent jobs, and (b) the relay loop is fast (dequeue → check labels → relay to parent → parent sends to peer). The existing `sleepPeriod` in the Node worker thread prevents tight spinning when the queue is empty.
- **TTL interaction**: The relay count/TTL from Requirement 4 must account for the extra hop through the controller's relay Node. A job's typical path is: controller relay Node → agent Node A (mismatch) → controller relay Node → agent Node B (match). Each hop increments the relay count. The TTL threshold should be generous enough to allow multi-hop routing (the default of 10 is sufficient).
- **Label choice**: `role:relay` is used here because it's a distinct namespace from platform capabilities. No job should ever require `role:relay`. If a more robust approach is desired, the relay Node could have **no labels at all** — since `satisfies()` checks that the Node's labels contain every entry in the job's requirements, a Node with zero labels will fail to satisfy any job that has at least one requirement.

### Backward Compatibility

- The controller changes from zero Nodes to one Node. Existing jobs with no requirements (empty `getRequiredLabels()`) will match the relay Node's `satisfies()` check (empty requirements always match), so the relay Node would execute them. To prevent this, the execution-path label check should also verify that the Node has at least one label that is **not** `role:relay` before executing — or more simply, jobs with no requirements should continue to be routed to agents via `sendTask()` as they are today, bypassing the relay Node's queue entirely.
- The `sendTask()` path (controller → agent for new tasks) is unchanged. The relay Node only participates when jobs arrive via `Message.Job` (the recirculation path).

---

## Requirement 6: MCP/API Integration

### Current Behavior

Job submission flows through two paths:

1. **HTTP API**: `FlowTreeApiEndpoint.handleSubmit()` (line 691) accepts POST requests with JSON body containing `prompt`, `targetBranch`, `workstreamId`, etc. It creates a `ClaudeCodeJob.Factory`, configures it, and calls `server.sendTask(factory, index)`.

2. **MCP Tool**: `workstream_submit_task()` in `tools/mcp/manager/server.py` (line 989) accepts parameters including `prompt`, `workstream_id`, `target_branch`, etc. It posts to the controller's `/api/submit` endpoint.

Neither path supports specifying job requirements/labels.

### Implementation Steps

1. **Add `requiredLabels` to the HTTP API submit endpoint**
   - File: `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`
   - In `handleSubmit()`, parse an optional `requiredLabels` JSON object from the request body:
     ```json
     {
       "prompt": "Build and test on macOS",
       "requiredLabels": {"platform": "macos"}
     }
     ```
   - After creating the `ClaudeCodeJob.Factory`, call `factory.setRequiredLabel(key, value)` for each entry
   - Use a helper to extract the JSON object (the existing `extractJsonField()` handles strings; add `extractJsonObjectFields()` for nested objects)

2. **Add `requiredLabels` to `SlackListener.submitJob()`**
   - File: `flowtree/src/main/java/io/flowtree/slack/SlackListener.java`
   - Accept an optional `Map<String, String> requiredLabels` parameter
   - Apply labels to the factory before sending

3. **Add `required_labels` parameter to MCP `workstream_submit_task`**
   - File: `tools/mcp/manager/server.py`
   - Add parameter: `required_labels: str = ""` (comma-separated `key:value` pairs, e.g., `"platform:macos,gpu:true"`)
   - Parse and include as `requiredLabels` JSON object in the POST payload to `/api/submit`

4. **Add `requiredLabels` to the generic `/api/submit` endpoint**
   - File: `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java`
   - The `/api/submit` handler delegates to `handleSubmit()`, so this is covered by step 1

### Risks and Considerations

- The MCP tool uses a string format (`key:value,key:value`) rather than a nested JSON object, since MCP tool parameters are flat. This is a minor UX trade-off.
- Label keys and values should be validated (no empty strings, no special characters that conflict with encoding).

### Backward Compatibility

- The `requiredLabels` field is optional in all APIs. Omitting it produces a job with no requirements, which runs on any Node. Fully backward compatible.

---

## Implementation Order

1. **Node Labels System** (Requirement 1) — Foundation that everything else builds on
2. **Platform Detection** (Requirement 2) — Immediate value, simple to implement
3. **Job Requirements** (Requirement 3) — Extends Job/JobFactory model
4. **Job Circulation on Mismatch** (Requirement 4) — Core routing logic, label check in execution path
5. **Controller Relay Node** (Requirement 5) — Controller runs a `role:relay` Node for job circulation
6. **MCP/API Integration** (Requirement 6) — Exposes the feature to callers

Requirements 1 and 2 can be implemented together in a single change. Requirements 3 and 4 depend on 1 and should be implemented together. Requirement 5 depends on 4 (the execution-path label check). Requirement 6 depends on 3.

## Files to Change

| File | Changes |
|------|---------|
| `flowtree/src/main/java/io/flowtree/node/Node.java` | Add `labels` map, `setLabel()`, `getLabels()`, `satisfies()`, label check in worker thread execution path (not `addJob()`), relay mismatched jobs to parent |
| `flowtree/src/main/java/io/flowtree/node/NodeGroup.java` | Load labels from Properties/env var, auto-detect platform, route relayed jobs to matching Nodes or peer servers |
| `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java` | Change `nodes.initial=0` to `nodes.initial=1`, add `role:relay` label for the controller's relay Node |
| `flowtreeapi/src/main/java/io/flowtree/job/Job.java` | Add `getRequiredLabels()` default method |
| `flowtree/src/main/java/io/flowtree/jobs/GitManagedJob.java` | Add `requiredLabels` field, serialization in `encode()`/`set()` |
| `flowtree/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java` | Add `requiredLabels` to `Factory`, propagate to created jobs |
| `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` | Parse `requiredLabels` from submit request body |
| `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` | Pass required labels through `submitJob()` |
| `tools/mcp/manager/server.py` | Add `required_labels` parameter to `workstream_submit_task` |
