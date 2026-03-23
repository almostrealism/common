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

1. **Add label check in `Node.addJob(Job)`**
   - File: `flowtree/src/main/java/io/flowtree/node/Node.java`
   - At the beginning of `addJob(Job)` (line 519), before adding to the queue:
     ```java
     if (!satisfies(j.getRequiredLabels())) {
         displayMessage("Rejecting job " + j.getTaskId() + " -- labels mismatch");
         return -2;  // New return code for label mismatch
     }
     ```

2. **Handle mismatch return in `NodeGroup`**
   - File: `flowtree/src/main/java/io/flowtree/node/NodeGroup.java`
   - In the run loop where `target.addJob(j)` is called (line 1262), check the return value. If `-2` (label mismatch), try the next least active Node. If no Node can take the job, re-add it to the NodeGroup's own job queue so it can be relayed to another server.
   - Similarly in `addJobs(JobFactory)` (line 1303) and `recievedMessage()` for `Message.Job` (line 1338).

3. **Add label check in `NodeGroup` for tasks**
   - In `addJobs(JobFactory)` (line 1281), after getting a job from the factory, check if any local Node can satisfy it. If not, the job should be sent to a connected peer server instead of being dropped.

4. **Relay unmatched jobs to peer servers**
   - When no local Node can run a job, iterate `servers` (the list of connected NodeProxy peers) and send the job as a `Message.Job` to the first available one. This leverages the existing inter-server relay mechanism.
   - Add a relay count or TTL to prevent infinite circulation. Include a `relayCount` in the Job encoding that increments each time the job is relayed. If it exceeds a threshold (e.g., 10), log a warning and drop the job.

### Risks and Considerations

- **Infinite circulation**: Without a TTL, a job with requirements that no Node satisfies could circulate forever. The relay count/TTL is essential.
- **Performance**: The label check is a fast map lookup (O(k) where k = number of required labels, typically 1-2), so there is negligible overhead.
- **Race conditions**: The `addJob()` method is already synchronized on `this.jobs`. The label check happens before the synchronized block, which is safe since labels are immutable after startup.
- **Job ordering**: Rejected jobs are re-queued, which may alter ordering. This is acceptable since the system already does not guarantee strict ordering.

### Backward Compatibility

- Jobs with no requirements (`getRequiredLabels()` returns empty map) always pass the label check. Existing job flow is completely unchanged.

---

## Requirement 5: Controller "No Execute" Mode

### Current Behavior

The `FlowTreeController` (line 382-388 in `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java`) already avoids local execution by setting `nodes.initial=0`:

```java
// Set nodes.initial=0 so the controller never processes jobs locally --
// all jobs are forwarded to connected agents.
Properties flowtreeProps = new Properties();
flowtreeProps.setProperty("server.port", String.valueOf(flowtreePort));
flowtreeProps.setProperty("nodes.initial", "0");
flowtreeServer = new Server(flowtreeProps);
```

With `nodes.initial=0`, the `NodeGroup` creates no child Nodes, so `getLeastActiveNode()` returns null, and jobs are only sent to connected peer servers via `sendTask()`.

### Implementation Steps

1. **No changes needed for controller behavior**
   - The existing `nodes.initial=0` approach already prevents the controller from executing jobs. The label system provides an alternative mechanism but is not required here.

2. **Optional: Document the label-based alternative**
   - If desired, the controller could instead use `nodes.initial=1` with an empty label set, which would cause all jobs with any requirements to be rejected and relayed. However, `nodes.initial=0` is simpler and already works.
   - The label system provides value for agent Nodes that should only run certain types of jobs, not for the controller which should run no jobs at all.

### Risks and Considerations

- No risk. The current approach is already clean and correct.

### Backward Compatibility

- No changes to controller behavior.

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
4. **Job Circulation on Mismatch** (Requirement 4) — Core routing logic
5. **MCP/API Integration** (Requirement 6) — Exposes the feature to callers
6. **Controller No-Execute** (Requirement 5) — Already works, document only

Requirements 1 and 2 can be implemented together in a single change. Requirements 3 and 4 depend on 1 and should be implemented together. Requirement 6 depends on 3.

## Files to Change

| File | Changes |
|------|---------|
| `flowtree/src/main/java/io/flowtree/node/Node.java` | Add `labels` map, `setLabel()`, `getLabels()`, `satisfies()`, label check in `addJob()` |
| `flowtree/src/main/java/io/flowtree/node/NodeGroup.java` | Load labels from Properties/env var, auto-detect platform, handle label mismatch in job distribution, relay unmatched jobs |
| `flowtreeapi/src/main/java/io/flowtree/job/Job.java` | Add `getRequiredLabels()` default method |
| `flowtree/src/main/java/io/flowtree/jobs/GitManagedJob.java` | Add `requiredLabels` field, serialization in `encode()`/`set()` |
| `flowtree/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java` | Add `requiredLabels` to `Factory`, propagate to created jobs |
| `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` | Parse `requiredLabels` from submit request body |
| `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` | Pass required labels through `submitJob()` |
| `tools/mcp/manager/server.py` | Add `required_labels` parameter to `workstream_submit_task` |
