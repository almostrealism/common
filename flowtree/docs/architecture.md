# Flowtree Jobs Architecture

This document describes the architecture of the `io.flowtree.jobs` package and its relationship
to the `io.flowtree.job` API package. Together, these packages implement the distributed job
execution system that powers autonomous coding agent workflows in the Flowtree framework.

---

## Table of Contents

1. [Package Overview](#1-package-overview)
2. [Class Hierarchy](#2-class-hierarchy)
3. [Job Lifecycle](#3-job-lifecycle)
4. [Factory Pattern](#4-factory-pattern)
5. [Git-Managed Job Lifecycle](#5-git-managed-job-lifecycle)
6. [Event Reporting Pipeline](#6-event-reporting-pipeline)
7. [Configuration Model](#7-configuration-model)
8. [MCP Configuration](#8-mcp-configuration)

---

## 1. Package Overview

The Flowtree job system spans two Maven modules and two Java packages:

**`io.flowtree.job` (flowtreeapi module)** -- Defines the core abstractions for distributed job
execution. This is the API layer that the Flowtree network infrastructure depends on. It contains
only interfaces and abstract base classes, with no knowledge of git, Claude Code, or any
specific job implementation.

**`io.flowtree.jobs` (flowtree module)** -- Contains concrete job implementations that build on
the API layer. This package is where git-managed workflows, Claude Code integration, event
reporting, MCP configuration, and file staging logic live. It depends on the API package but
never the reverse.

### Role in the Distributed System

The Flowtree system is a distributed computation framework where work units (jobs) are created
on a controller node, serialized into a wire format, transmitted to worker nodes, deserialized,
and executed. The `io.flowtree.jobs` package specializes this general-purpose mechanism for a
specific use case: orchestrating autonomous coding agents that clone repositories, execute
Claude Code prompts, commit changes, push to remote branches, detect pull requests, and report
results back to the controller for Slack notification.

The key insight is that a `ClaudeCodeJob` is not a special-purpose script -- it is a standard
Flowtree job that participates in the same serialization, distribution, and scheduling
mechanisms as any other job type. This means a single Flowtree cluster can run a mix of coding
agent jobs, computation jobs (`TemporalJob`), and process-spawning jobs (`ExternalProcessJob`)
without any special infrastructure.

### Package Contents at a Glance

| Class | Responsibility |
|-------|---------------|
| `GitManagedJob` | Abstract base for jobs that commit results via git |
| `ClaudeCodeJob` | Concrete job that invokes Claude Code CLI in headless mode |
| `ClaudeCodeJob.Factory` | JobFactory that produces ClaudeCodeJobs from a prompt list |
| `GitOperations` | Encapsulates all git subprocess execution |
| `FileStager` | Stateless guardrail evaluator for file staging decisions |
| `WorkspaceResolver` | Resolves workspace paths and workstream URLs |
| `PullRequestDetector` | Queries GitHub API for open PRs on a branch |
| `McpConfigBuilder` | Assembles MCP server configuration for Claude Code |
| `ManagedToolsDownloader` | Downloads and verifies pushed MCP tool files |
| `InstructionPromptBuilder` | Constructs the full instruction prompt from job state |
| `GitJobConfig` | Immutable configuration for git operations |
| `FileStagingConfig` | Immutable configuration for file staging guardrails |
| `StagingResult` | Immutable result of a file staging evaluation |
| `JobCompletionEvent` | Event carrying job completion details |
| `ClaudeCodeJobEvent` | Event subclass with Claude Code-specific metrics |
| `JobCompletionListener` | Listener interface for completion events |
| `McpToolDiscovery` | Discovers tool names from MCP server source files |

---

## 2. Class Hierarchy

### Core Job Interfaces and Classes (flowtreeapi)

```
KeyValueStore (org.almostrealism.util)
    |
    +-- Job (io.flowtree.job)
    |       interface: Runnable + KeyValueStore
    |       methods: getTaskId(), getTaskString(), encode(), set(),
    |                getCompletableFuture(), setExecutorService(),
    |                setOutputConsumer()
    |
    +-- JobFactory (io.flowtree.job)
            interface: encode(), set(), nextJob(), createJob(),
                       getCompleteness(), isComplete(), getPriority()
            |
            +-- AbstractJobFactory
                    abstract class with Map<String,String> property storage
                    methods: encode() -> "classname::key:=value::key:=value..."
                             set(key, value) -> properties.put(key, value)
                             get(key) -> properties.get(key)
```

### Concrete Job Hierarchy (flowtree)

```
Job (interface)
 |
 +-- GitManagedJob (abstract)
 |       implements: Job, ConsoleFeatures
 |       template method: run() orchestrates the full lifecycle
 |       abstract: doWork(), getCommitMessage()
 |       hook: validateChanges() (default: true)
 |       hook: createEvent(), populateEventDetails()
 |       |
 |       +-- ClaudeCodeJob (concrete)
 |               overrides: doWork() -> invokes claude CLI
 |               overrides: getCommitMessage() -> reads commit.txt or generates
 |               overrides: validateChanges() -> runs detect-test-hiding.sh
 |               overrides: createEvent() -> returns ClaudeCodeJobEvent
 |               overrides: populateEventDetails() -> adds timing/session info
 |               |
 |               +-- ClaudeCodeJob.Factory (static inner class)
 |                       extends: AbstractJobFactory
 |                       holds: List<String> prompts
 |                       produces: ClaudeCodeJob instances via nextJob()
 |
 +-- ExternalProcessJob
 +-- TemporalJob

AbstractJobFactory
 |
 +-- ClaudeCodeJob.Factory
```

### Helper Classes

```
GitOperations          -- Subprocess execution for git commands
FileStager             -- Stateless file staging guardrail evaluator
WorkspaceResolver      -- Static utility for path and URL resolution
PullRequestDetector    -- GitHub REST API client for PR detection
McpConfigBuilder       -- Assembles --mcp-config JSON and --allowedTools string
ManagedToolsDownloader -- Downloads pushed MCP tool files from controller
InstructionPromptBuilder -- Constructs the instruction prompt from job state
McpToolDiscovery       -- Discovers tool names by parsing Python MCP source files
```

### Configuration and Result Classes

```
GitJobConfig           -- Immutable git configuration (Builder pattern)
FileStagingConfig      -- Immutable staging guardrail configuration (Builder pattern)
StagingResult          -- Immutable result of file staging evaluation
```

### Event Hierarchy

```
JobCompletionEvent
 |   fields: jobId, status, description, timestamp,
 |           targetBranch, commitHash, stagedFiles, skippedFiles,
 |           pushed, errorMessage, exception, pullRequestUrl
 |   Claude-specific getters return null/0 defaults
 |
 +-- ClaudeCodeJobEvent
         adds: prompt, sessionId, exitCode,
               durationMs, durationApiMs, costUsd, numTurns,
               subtype, sessionIsError, permissionDenials, deniedToolNames

JobCompletionListener (interface)
     methods: onJobCompleted(workstreamId, event)
              onJobStarted(workstreamId, event)  [default no-op]
```

---

## 3. Job Lifecycle

Every job in the Flowtree system follows a six-phase lifecycle: creation, serialization,
transmission, deserialization, execution, and completion. Understanding this lifecycle is
essential because the wire protocol imposes constraints on what data a job can carry and
how it reconstructs itself on a remote node.

### Phase 1: Creation

A job begins its life on the controller node. For Claude Code jobs, the controller creates
a `ClaudeCodeJob.Factory` with one or more prompts and configures it with git settings, MCP
configuration, workstream URL, and other parameters:

```java
ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(
    "Fix the null pointer in UserService.java",
    "Add unit tests for the fix"
);
factory.setTargetBranch("feature/fix-user-service");
factory.setRepoUrl("https://github.com/owner/repo.git");
factory.setAllowedTools("Read,Edit,Write,Bash,Glob,Grep");
factory.setWorkstreamUrl("http://controller:8080/api/workstreams/ws-1/jobs/job-1");
```

### Phase 2: Serialization (encode)

When the factory is transmitted to a worker node, the Flowtree network calls `encode()` on
the factory. This produces a single string using the wire protocol format:

```
classname::key1:=value1::key2:=value2::key3:=value3...
```

The protocol uses two separators defined in `JobFactory`:

| Constant | Value | Purpose |
|----------|-------|---------|
| `ENTRY_SEPARATOR` | `::` | Separates key-value entries from each other |
| `KEY_VALUE_SEPARATOR` | `:=` | Separates the key from its value within an entry |

The first token (before the first `::`) is always the fully-qualified class name of the
`JobFactory` implementation. This tells the receiving node which class to instantiate.

**Base64 encoding for safety.** Values that may contain the separator characters (`:=` or `::`)
are Base64-encoded before being placed into the wire format. The `GitManagedJob` class provides
`base64Encode()` and `base64Decode()` utility methods for this purpose. Fields like branch names,
repository URLs, prompts, working directories, MCP configuration JSON, and git identity strings
are all Base64-encoded. Simple scalar values like booleans and numbers are transmitted as plain
strings since they cannot contain separator characters.

Here is an example of what the encoded string looks like for a `ClaudeCodeJob.Factory`:

```
io.flowtree.jobs.ClaudeCodeJob$Factory::factoryTaskId:=abc-123::prompts:=Rml4IHRo...::branch:=ZmVhdHVy...::push:=true::tools:=Read,Edit,Write,Bash,Glob,Grep::maxTurns:=50::maxBudget:=10.0::protectTests:=false
```

The `AbstractJobFactory.encode()` implementation iterates over its internal properties map
and emits each key-value pair:

```java
public String encode() {
    return properties.entrySet().stream()
        .map(ent -> ENTRY_SEPARATOR + ent.getKey()
                   + KEY_VALUE_SEPARATOR + ent.getValue())
        .collect(Collectors.joining("", getClass().getName(), ""));
}
```

For `Job` implementations (as opposed to `JobFactory`), the `encode()` method on
`GitManagedJob` builds the string directly by appending fields with their keys, calling
`super.encode()` from `ClaudeCodeJob` to include the parent fields first.

### Phase 3: Transmission

The encoded string is transmitted over the Flowtree network to a worker node. The network
layer is transparent -- it simply passes the string. No special framing or compression is
applied at this layer; the encoding is entirely self-contained.

### Phase 4: Deserialization (set)

On the receiving worker node, the Flowtree infrastructure:

1. Splits the encoded string on the first `::` to extract the class name.
2. Instantiates the class using its no-argument constructor (e.g., `ClaudeCodeJob.Factory()`).
3. Splits the remaining string into key-value entries on `::`.
4. For each entry, splits on `:=` to get the key and value.
5. Calls `set(key, value)` on the newly created instance for each pair.

The `set()` method on each class is responsible for decoding and storing the values. For
example, in `ClaudeCodeJob`:

```java
public void set(String key, String value) {
    switch (key) {
        case "prompt":
            this.prompt = base64Decode(value);
            break;
        case "tools":
            this.allowedTools = base64Decode(value);
            break;
        case "maxTurns":
            this.maxTurns = Integer.parseInt(value);
            break;
        // ... other cases ...
        default:
            super.set(key, value);  // Delegate to GitManagedJob
    }
}
```

Notice the `default` case at the bottom. `ClaudeCodeJob.set()` handles its own keys and
delegates unknown keys to `GitManagedJob.set()`, which handles git-related keys. This
chain ensures each level of the hierarchy processes the keys it owns.

### Phase 5: Execution

Once deserialized, the factory's `nextJob()` method is called to produce individual `Job`
instances. Each job is then executed by calling `run()`. The `run()` method on `GitManagedJob`
implements the Template Method pattern:

```java
public final void run() {
    try {
        resolveAndCloneRepository();   // Clone if needed
        prepareWorkingDirectory();     // Fetch, checkout, sync
        doWork();                      // Abstract -- subclass implements
        if (validateChanges()) {       // Hook -- default returns true
            handleGitOperations();     // Stage, commit, push
        }
    } catch (Exception e) {
        // ...
    } finally {
        fireJobCompleted(error);       // Report result
        future.complete(null);         // Signal CompletableFuture
    }
}
```

### Phase 6: Completion

After `run()` finishes (successfully or with an error), the job:

1. Creates a `JobCompletionEvent` (or `ClaudeCodeJobEvent`) with the result details.
2. POSTs the event as JSON to the `workstreamUrl` on the controller.
3. Completes its `CompletableFuture` to signal the Flowtree scheduler.

The controller receives the event and can trigger downstream actions (e.g., Slack
notifications, PR comments, retry logic).

### Lifecycle Diagram

```
Controller Node                           Worker Node
===============                           ===========

Factory.new()
    |
    v
Factory.encode()
    |                 [wire: classname::k:=v::k:=v]
    +-----------------------------------------------> Class.forName(classname)
                                                          |
                                                          v
                                                      new Factory()
                                                          |
                                                          v
                                                      set(k1, v1)
                                                      set(k2, v2)
                                                      set(k3, v3)
                                                          |
                                                          v
                                                      factory.nextJob()
                                                          |
                                                          v
                                                      job.run()
                                                          |
                                                      +---+---+
                                                      |       |
                                                      v       v
                                                   doWork()  handleGitOperations()
                                                      |       |
                                                      +---+---+
                                                          |
                                                          v
                                                    fireJobCompleted()
                                                          |
                [HTTP POST /api/workstreams/ws/jobs/j]    |
Controller <----------------------------------------------+
    |
    v
SlackNotifier.onJobCompleted()
    |
    v
Slack message posted to channel/thread
```

---

## 4. Factory Pattern

The `JobFactory` interface is the unit of distribution in Flowtree. While a `Job` represents a
single execution, a `JobFactory` represents a task that may produce multiple jobs. The factory
pattern serves three purposes:

1. **Batching** -- A factory can hold a list of prompts, producing one job per prompt. This
   allows the controller to submit a batch of work as a single factory, and the worker node
   processes them sequentially.

2. **Serialization boundary** -- The factory is the unit that gets serialized and transmitted.
   Individual jobs are created on the worker node by the factory, never transmitted directly.

3. **Completeness tracking** -- The factory reports its progress via `getCompleteness()`,
   allowing the scheduler to monitor how far through the batch the worker has progressed.

### AbstractJobFactory

`AbstractJobFactory` provides the foundational property storage mechanism that all factories
build upon:

- **Property map (`Map<String, String>`)** -- Stores all serializable state as string key-value
  pairs. The `set(key, value)` method populates this map during deserialization, and `encode()`
  serializes it to the wire format.

- **Task identity** -- Stores a `taskId` (network-wide unique identifier), a human-readable
  `name`, and a `priority` value.

- **Completion tracking** -- Provides a `CompletableFuture` for async completion monitoring
  and delegates `isComplete()` to `getCompleteness() >= 1.0`.

### ClaudeCodeJob.Factory

The `ClaudeCodeJob.Factory` extends `AbstractJobFactory` and adds:

- **Prompt list storage** -- Prompts are joined with the `;;PROMPT;;` separator, Base64-encoded,
  and stored under the `"prompts"` property key. On the receiving side, `getPrompts()` decodes
  and splits them back into a `List<String>`.

- **Index-based job production** -- An internal `index` counter tracks which prompt comes next.
  Each call to `nextJob()` creates a new `ClaudeCodeJob` with the next prompt and copies all
  configuration properties (branch, tools, MCP config, workstream URL, etc.) from the factory
  to the job.

- **Dual-path `set()` method** -- The factory's `set()` method both stores the value in the
  parent's property map (via `super.set()`) and populates the factory's typed fields. This
  ensures the value is available both for re-serialization and for typed access.

- **Task ID persistence** -- The factory generates a random task ID in its constructor and
  stores it under the `"factoryTaskId"` property key. This is necessary because
  `AbstractJobFactory.encode()` does not serialize the `taskId` field -- it only serializes
  the properties map. Without this, a deserialized factory would receive a new random ID.

```java
public Job nextJob() {
    List<String> p = getPrompts();
    if (index >= p.size()) return null;

    ClaudeCodeJob job = new ClaudeCodeJob(getTaskId(), p.get(index++));
    job.setAllowedTools(allowedTools);
    job.setWorkingDirectory(workingDirectory);
    job.setMaxTurns(maxTurns);
    // ... copies all other configuration ...
    return job;
}

public double getCompleteness() {
    List<String> p = getPrompts();
    return p.isEmpty() ? 1.0 : index / (double) p.size();
}
```

---

## 5. Git-Managed Job Lifecycle

`GitManagedJob` is the abstract base class for any job that manages its output through git
operations. Its `run()` method is declared `final` and orchestrates a multi-phase lifecycle.
This section traces each phase in detail.

### Phase 1: Repository Resolution and Cloning

When a `repoUrl` is configured but no `workingDirectory` is specified, the job must determine
where to check out the repository. This is handled by `resolveAndCloneRepository()`, which
delegates path resolution to `WorkspaceResolver`:

```
WorkspaceResolver.resolve(configuredPath, repoUrl)
    |
    +-- repoName = extractRepoName(repoUrl)
    +-- If configuredPath is non-null/non-empty -> configuredPath/<repoName>
    +-- If /workspace/project exists on disk   -> /workspace/project/<repoName>
    +-- Otherwise -> /tmp/flowtree-workspaces/<repoName>
```

The `extractRepoName()` method handles both SSH and HTTPS URL formats:
- `git@github.com:owner/repo.git` becomes `owner-repo`
- `https://github.com/owner/repo.git` becomes `owner-repo`

If the resolved directory does not contain a `.git` subdirectory, `cloneRepository()` is invoked.
All git subprocesses are configured with `GIT_SSH_COMMAND` set to
`ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes` to prevent interactive prompts in
headless environments.

### Phase 2: Working Directory Preparation

`prepareWorkingDirectory()` ensures the repository is in a clean, up-to-date state before the
agent starts work:

```
prepareWorkingDirectory()
    |
    +-- 1. Check for uncommitted changes
    |       - Get git status --porcelain
    |       - Filter out files matching excluded patterns
    |       - If dirty files remain: git checkout . && git clean -fd
    |
    +-- 2. Fetch latest from origin
    |       - git fetch origin
    |
    +-- 3. Checkout target branch
    |       - If branch exists locally or on origin: git checkout <branch>
    |       - If branch does not exist and createBranchIfMissing:
    |           git checkout -b <targetBranch> --no-track origin/<baseBranch>
    |
    +-- 4. Sync with remote target branch (if it exists)
    |       - git pull --ff-only origin <targetBranch>
    |       - If ff-only fails (diverged): git reset --hard origin/<targetBranch>
    |
    +-- 5. Synchronize with base branch
            - Merge origin/<baseBranch> into current branch
            - If merge conflicts:
                - Record conflicted files in mergeConflictFiles
                - git merge --abort
                - Set mergeConflictsDetected = true
                - Agent will be instructed to resolve conflicts in its prompt
```

The cleaning behavior in step 1 is intentional: agent workers should never have manual edits,
so any uncommitted changes are residue from a prior (likely failed) job run and can be safely
discarded.

The `--no-track` flag in step 3 is critical: it prevents the new branch from inheriting
`origin/<baseBranch>` as its upstream. The `pushToOrigin()` method later sets the upstream
to `origin/<targetBranch>` explicitly via the `-u` flag.

### Phase 3: Work Execution

The abstract `doWork()` method is called. For `ClaudeCodeJob`, this means:

1. Download pushed MCP tools from the controller (if configured).
2. Remove any stale `commit.txt` from previous runs.
3. Build the instruction prompt (wrapping the user's request with operational context).
4. Build the MCP configuration JSON and allowed tools string.
5. Launch the `claude` CLI process with `-p` (non-interactive mode).
6. Stream and log the output.
7. Extract session metrics (sessionId, duration, cost, turns) from the JSON output.

### Phase 4: Change Validation

Before git operations proceed, `validateChanges()` is called. The base class returns `true`
by default. `ClaudeCodeJob` overrides this to run the `detect-test-hiding.sh` script when
`protectTestFiles` is enabled, which performs a diff audit to ensure the agent has not modified
existing test files to hide failures rather than fixing production code.

### Phase 5: Git Operations

`handleGitOperations()` performs the commit workflow:

```
handleGitOperations()
    |
    +-- 1. Ensure on target branch
    |       (in case doWork() switched branches)
    |
    +-- 2. Find changed files
    |       - git status --porcelain
    |       - Parse modified, added, deleted, untracked, renamed
    |
    +-- 3. Stage files (with guardrails)
    |       For each changed file, apply four guardrails in order:
    |         a. Pattern exclusion -- skip if matches excluded glob
    |         b. Test file protection -- block if matches protected
    |            pattern AND exists on the base branch
    |         c. File size -- skip if > maxFileSizeBytes (default 1MB)
    |         d. Binary detection -- skip if >10% null bytes in first 8KB
    |       If file passes all guardrails: git add <file>
    |       Track stagedFiles and skippedFiles (with reasons)
    |
    +-- 4. Commit
    |       - Read commit message from commit.txt if present
    |       - Otherwise generate from prompt text
    |       - git -c user.name=... -c user.email=... commit -m <message>
    |       - Record commitHash
    |       - Clean up commit.txt
    |
    +-- 5. Push to origin
    |       - git push -u origin <targetBranch>:<targetBranch>
    |       - Explicit refspec ensures correct remote branch
    |
    +-- 6. Detect pull request
            - Query GitHub REST API for open PR on targetBranch
            - Via GITHUB_TOKEN/GH_TOKEN directly, or via controller proxy
            - Record pullRequestUrl if found
```

### Phase 6: Event Firing

The `fireJobCompleted()` method creates a completion event and POSTs it to the controller:

```
fireJobCompleted(error)
    |
    +-- createEvent(error) -> JobCompletionEvent or ClaudeCodeJobEvent
    +-- event.withGitInfo(branch, commit, staged, skipped, pushed)
    +-- event.withPullRequestUrl(url)
    +-- populateEventDetails(event) -> adds Claude Code-specific fields
    +-- postStatusEvent(event) -> HTTP POST to workstreamUrl
```

### Full Lifecycle Diagram

```
run()
 |
 +-- resolveAndCloneRepository()
 |       WorkspaceResolver.resolve()
 |       cloneRepository() if needed
 |
 +-- prepareWorkingDirectory()
 |       checkForUncommittedChanges()
 |       git fetch origin
 |       ensureOnTargetBranch()
 |       git pull --ff-only || git reset --hard
 |       synchronizeWithBaseBranch()
 |
 +-- doWork()                              <-- subclass implements
 |       [ClaudeCodeJob: launch claude CLI]
 |
 +-- validateChanges()                     <-- subclass may override
 |       [ClaudeCodeJob: detect-test-hiding.sh]
 |
 +-- handleGitOperations()
 |       ensureOnTargetBranch()
 |       findChangedFiles()
 |       stageFiles() with guardrails
 |       commit()
 |       pushToOrigin()
 |       detectPullRequestUrl()
 |
 +-- fireJobCompleted()
 |       createEvent() -> ClaudeCodeJobEvent
 |       withGitInfo(), withPullRequestUrl()
 |       populateEventDetails()
 |       postStatusEvent() -> HTTP POST
 |
 +-- future.complete(null)
```

---

## 6. Event Reporting Pipeline

When a job completes, the result must flow from the worker node back to the controller and
ultimately to the user (typically via Slack). This section traces the full pipeline.

### Event Creation

`GitManagedJob.fireJobCompleted()` creates the event object. The base class method
`createEvent()` returns a `JobCompletionEvent`, but `ClaudeCodeJob` overrides it to return
a `ClaudeCodeJobEvent`:

```java
// In ClaudeCodeJob
protected JobCompletionEvent createEvent(Exception error) {
    if (error != null) {
        return ClaudeCodeJobEvent.failed(getTaskId(), getTaskString(),
            error.getMessage(), error);
    } else {
        return ClaudeCodeJobEvent.success(getTaskId(), getTaskString());
    }
}
```

The event is then populated with git information and Claude Code-specific details:

```java
event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, pushed);
event.withPullRequestUrl(pullRequestUrl);
populateEventDetails(event);  // Adds timing, session, permission denial info
```

### Event Serialization

The event is serialized to JSON using Jackson `ObjectMapper`. The JSON structure includes all
fields from both `JobCompletionEvent` and `ClaudeCodeJobEvent`:

```json
{
    "jobId": "abc-123",
    "status": "SUCCESS",
    "description": "Fix the null pointer in...",
    "targetBranch": "feature/fix-user-service",
    "commitHash": "a1b2c3d4e5f6...",
    "pushed": true,
    "stagedFiles": ["src/main/java/UserService.java"],
    "skippedFiles": [],
    "pullRequestUrl": "https://github.com/owner/repo/pull/42",
    "errorMessage": null,
    "prompt": "Fix the null pointer in UserService.java",
    "sessionId": "session-xyz",
    "exitCode": 0,
    "durationMs": 45000,
    "durationApiMs": 30000,
    "costUsd": 0.85,
    "numTurns": 12,
    "subtype": "end_turn",
    "sessionIsError": false,
    "permissionDenials": 0,
    "deniedToolNames": []
}
```

### Event Transmission

The serialized JSON is POSTed to the `workstreamUrl` via a fire-and-forget HTTP request:

```java
private void postStatusEvent(JobCompletionEvent event) {
    if (workstreamUrl == null || workstreamUrl.isEmpty()) return;

    String baseUrl = resolveWorkstreamUrl();
    String json = buildEventJson(event);
    postJson(baseUrl, json);
}
```

The `resolveWorkstreamUrl()` method replaces the `0.0.0.0` placeholder with the real
controller host from the `FLOWTREE_ROOT_HOST` environment variable. This is necessary when
jobs run inside Docker containers where the original URL was configured with a placeholder.

The workstream URL follows the pattern:
```
http://controller:port/api/workstreams/{workstreamId}/jobs/{jobId}
```

### Controller Processing

On the controller side, the HTTP endpoint receives the JSON payload and deserializes it into
a `JobCompletionEvent`. The controller then invokes all registered `JobCompletionListener`
implementations.

### Slack Notification

The `SlackNotifier` (a `JobCompletionListener` implementation on the controller) receives
the event and formats an appropriate Slack message. The message includes:

- Job status (success/failure)
- Branch name and commit hash
- Number of staged/skipped files
- Pull request URL (if detected)
- Error message (if failed)
- Claude Code session metrics (duration, cost, turns)
- Permission denial warnings (if any)

The Slack message is posted to the workstream's configured Slack channel or thread.

### Pipeline Diagram

```
Worker Node                     Controller                      Slack
===========                     ==========                      =====

job.run() completes
    |
    v
fireJobCompleted()
    |
    v
createEvent() -> ClaudeCodeJobEvent
    |
    v
buildEventJson() -> JSON string
    |
    v
postJson(workstreamUrl, json)
    |
    +--[HTTP POST]----------------> /api/workstreams/{ws}/jobs/{j}
                                        |
                                        v
                                    deserialize JSON
                                        |
                                        v
                                    for each JobCompletionListener:
                                        listener.onJobCompleted(wsId, event)
                                        |
                                        v
                                    SlackNotifier.onJobCompleted()
                                        |
                                        v
                                    format Slack message
                                        |
                                        +--[Slack API]--------> Channel/Thread
```

### Event Class Polymorphism

The event system uses polymorphism to keep generic and Claude Code-specific concerns separate:

- `JobCompletionEvent` carries all generic fields (job ID, status, git info, errors).
  It also declares Claude Code-specific getter methods with null/zero defaults (e.g.,
  `getPrompt()` returns `null`, `getCostUsd()` returns `0`).

- `ClaudeCodeJobEvent` extends the base class and overrides the Claude Code-specific getters
  to return real values. It uses builder-pattern setters (`withClaudeCodeInfo()`,
  `withTimingInfo()`, `withSessionDetails()`) for fluent construction.

This design allows consumers like `SlackNotifier` to call any getter uniformly on any event
type. A generic `JobCompletionEvent` simply returns defaults for Claude Code-specific fields,
so the Slack message gracefully omits those sections for non-Claude jobs.

---

## 7. Configuration Model

The job system uses two immutable configuration classes and a mutable property-based
deserialization layer. Understanding the distinction is important for working with the code.

### GitJobConfig (Immutable)

`GitJobConfig` consolidates all git-related configuration into an immutable value object
with a Builder pattern:

```java
GitJobConfig config = GitJobConfig.builder()
    .targetBranch("feature/my-work")
    .baseBranch("main")
    .repoUrl("https://github.com/owner/repo.git")
    .pushToOrigin(true)
    .protectTestFiles(true)
    .gitUserName("agent-bot")
    .gitUserEmail("agent@example.com")
    .maxFileSizeBytes(2 * 1024 * 1024)
    .build();
```

**Fields:**

| Field | Default | Description |
|-------|---------|-------------|
| `targetBranch` | `null` | Branch to commit to (null = skip git ops) |
| `baseBranch` | `"master"` | Starting point for new branches |
| `workingDirectory` | `null` | Absolute path to the git working directory |
| `repoUrl` | `null` | Git clone URL for automatic checkout |
| `defaultWorkspacePath` | `null` | Explicitly configured workspace path |
| `maxFileSizeBytes` | 1,048,576 (1 MB) | Maximum file size for staging |
| `excludedPatterns` | See `DEFAULT_EXCLUDED_PATTERNS` | Glob patterns always excluded |
| `additionalExcludedPatterns` | empty | Extra exclusion patterns |
| `pushToOrigin` | `true` | Whether to push after committing |
| `createBranchIfMissing` | `true` | Whether to create the target branch if absent |
| `dryRun` | `false` | Log operations without executing |
| `protectTestFiles` | `false` | Block changes to test files on the base branch |
| `gitUserName` | `null` | Git author/committer name |
| `gitUserEmail` | `null` | Git author/committer email |
| `workstreamUrl` | `null` | Controller URL for status reporting |

**Default Excluded Patterns** cover secrets (.env, .pem, .key), build outputs (target/,
build/, node_modules/), IDE files (.idea/, .vscode/), binaries (.exe, .dll, .so, .dylib),
media files (.png, .jpg, .mp3, .mp4), database files (.db, .sqlite), hardware acceleration
outputs (Extensions/, .cl, .metal), and Claude Code artifacts (claude-output/, .claude/).

**Protected Path Patterns** identify test and CI infrastructure:
- `**/src/test/**`
- `**/src/it/**`
- `.github/workflows/**`
- `.github/actions/**`

### FileStagingConfig (Immutable)

`FileStagingConfig` encapsulates the rules that `FileStager` applies when evaluating files:

```java
FileStagingConfig config = FileStagingConfig.builder()
    .maxFileSizeBytes(2 * 1024 * 1024)
    .excludedPatterns(GitJobConfig.DEFAULT_EXCLUDED_PATTERNS)
    .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
    .protectTestFiles(true)
    .baseBranch("main")
    .build();
```

| Field | Default | Description |
|-------|---------|-------------|
| `maxFileSizeBytes` | 1,048,576 | Maximum file size threshold |
| `excludedPatterns` | empty | Glob patterns to exclude |
| `protectedPathPatterns` | empty | Patterns for protected test/CI files |
| `protectTestFiles` | `false` | Whether test file protection is active |
| `baseBranch` | `"master"` | Branch used for existence checks |

### StagingResult (Immutable)

The result of `FileStager.evaluateFiles()` is a `StagingResult` containing two lists:

- `stagedFiles` -- Files that passed all guardrails and are eligible for `git add`.
- `skippedFiles` -- Files that were skipped, each with a human-readable reason in the format
  `"filename (reason)"`.

Both lists are stored as unmodifiable copies.

### Deserialization Configuration

During deserialization via `set(key, value)`, the mutable fields on `GitManagedJob` and
`ClaudeCodeJob` are populated directly. The immutable config classes (`GitJobConfig`,
`FileStagingConfig`) are used by the extracted helper classes (`FileStager`, etc.) but the
current `GitManagedJob.run()` method still reads from its mutable fields. The config classes
exist as the target architecture for new code paths and for extracted helper classes.

### How Configuration Flows

The configuration flow from controller to execution follows this path:

```
Controller (YAML config / API request)
    |
    v
ClaudeCodeJob.Factory setter methods
    |
    v
Factory.set(key, base64Encode(value))
    [stores in AbstractJobFactory.properties map]
    |
    v
Factory.encode()
    [serializes properties to wire format]
    |
    v
--- wire transmission ---
    |
    v
new Factory() + set(key, value) calls
    [populates both properties map and typed fields]
    |
    v
Factory.nextJob()
    [copies typed fields to new ClaudeCodeJob]
    |
    v
ClaudeCodeJob.run()
    [reads from mutable fields set by Factory]
```

---

## 8. MCP Configuration

The Model Context Protocol (MCP) configuration determines which tool servers are available
to the Claude Code agent during execution. The `McpConfigBuilder` is responsible for
assembling the final configuration from multiple sources and producing the `--mcp-config`
JSON and `--allowedTools` string for the Claude Code command line.

### Configuration Sources

MCP servers can come from three distinct sources, each with different deployment
characteristics:

#### 1. Centralized Servers (HTTP)

Centralized servers run on the controller or a shared infrastructure host. They are accessed
by the agent over HTTP, which means the agent does not need to have the server code installed
locally.

The configuration arrives as a JSON string in the `centralizedMcpConfig` field:

```json
{
    "ar-messages": {
        "url": "http://0.0.0.0:8080/mcp/ar-messages",
        "tools": ["send_message", "get_stats"]
    },
    "ar-consultant": {
        "url": "http://0.0.0.0:8080/mcp/ar-consultant",
        "tools": ["consult", "recall", "remember", "search_docs", ...]
    }
}
```

When building the MCP config, the `0.0.0.0` placeholder is replaced with the value of
`FLOWTREE_ROOT_HOST` so the agent can reach the controller from inside a container.

In the final `--mcp-config` JSON, centralized servers appear as HTTP entries:

```json
{
    "mcpServers": {
        "ar-messages": {
            "type": "http",
            "url": "http://192.168.1.100:8080/mcp/ar-messages"
        }
    }
}
```

#### 2. Pushed Servers (stdio, downloaded from controller)

Pushed servers are Python MCP tool files that the controller makes available for download.
The agent downloads them to `~/.flowtree/tools/mcp/{name}/server.py` and runs them locally
via stdio. This approach is used when the tool needs to run in the agent's environment (e.g.,
for filesystem access) but the source code is managed centrally.

The configuration arrives as a JSON string in the `pushedToolsConfig` field:

```json
{
    "ar-memory": {
        "url": "http://0.0.0.0:8080/api/tools/ar-memory/source",
        "tools": ["memory_store", "memory_search", "memory_list", "memory_delete"],
        "env": {
            "AR_MEMORY_DB": "/data/memory.db"
        }
    }
}
```

The `ManagedToolsDownloader.ensurePushedTools()` method handles downloading:

1. Parse the pushed tools config to get server names.
2. For each server, check if `~/.flowtree/tools/mcp/{name}/server.py` exists.
3. If not, extract the download URL, resolve `0.0.0.0`, HTTP GET the source, and save it.

Per-workstream environment variables can override the global env from the pushed config:

```json
// workstreamEnv (per-workstream overrides)
{
    "AR_MEMORY_DB": "/data/workstream-42/memory.db",
    "CUSTOM_VAR": "custom_value"
}
```

In the final MCP config, pushed servers appear as stdio entries:

```json
{
    "mcpServers": {
        "ar-memory": {
            "command": "python3",
            "args": ["/home/agent/.flowtree/tools/mcp/ar-memory/server.py"],
            "env": {
                "AR_MEMORY_DB": "/data/workstream-42/memory.db",
                "CUSTOM_VAR": "custom_value"
            }
        }
    }
}
```

#### 3. Project Servers (stdio, from repository)

Project servers are defined in the repository's `.mcp.json` file. These are discovered
automatically by scanning the working directory. The `.claude/settings.json` file is
consulted to determine which servers are enabled.

`McpConfigBuilder.discoverProjectMcpServers()` performs this discovery:

1. Read `.mcp.json` from the working directory.
2. Extract server names and their Python source file paths.
3. Read `.claude/settings.json` to get the `enabledMcpjsonServers` list.
4. Filter out `ar-github` and `ar-messages` (handled separately).
5. Filter out any servers already covered by centralized or pushed configs.
6. Return the remaining servers.

For each discovered project server, `McpToolDiscovery.discoverToolNames()` parses the
Python source file to extract the tool function names, so they can be added to the
`--allowedTools` list.

### Special Servers: ar-github and ar-messages

Two MCP servers receive special treatment because they are always needed (ar-github) or
conditionally needed (ar-messages):

**ar-github** is always included. Its tools (`github_pr_find`, `github_pr_review_comments`,
`github_pr_conversation`, `github_pr_reply`) are essential for reading and responding to
PR review comments. If ar-github is not provided via centralized or pushed configs, a stdio
fallback is added pointing to `tools/mcp/github/server.py` in the working directory.

**ar-messages** is included only when a `workstreamUrl` is configured (i.e., the job has a
communication channel back to the user). Its tool (`send_message`) allows the agent
to store messages and post status updates. Like ar-github, if not centralized or pushed, a stdio fallback is
used pointing to `tools/mcp/messages/server.py`.

### Configuration Assembly

`McpConfigBuilder.buildMcpConfig()` assembles the final JSON:

```
buildMcpConfig()
    |
    +-- Parse centralizedMcpConfig -> Map<serverName, List<tools>>
    +-- Parse pushedToolsConfig -> Map<serverName, List<tools>>
    |
    +-- Emit centralized servers as {"type":"http","url":"..."}
    |       (with 0.0.0.0 -> FLOWTREE_ROOT_HOST replacement)
    |
    +-- Emit pushed servers as {"command":"python3","args":["~/.flowtree/..."],"env":{...}}
    |       (with global env merged with workstreamEnv, workstream wins)
    |
    +-- Discover project servers from .mcp.json
    |       (skip ar-github, ar-messages, already centralized, already pushed)
    |       Emit as {"command":"python3","args":["path/to/server.py"]}
    |
    +-- If ar-github not centralized and not pushed:
    |       Emit stdio fallback: {"command":"python3","args":["tools/mcp/github/server.py"]}
    |
    +-- If ar-messages not centralized and not pushed AND workstreamUrl is set:
            Emit stdio fallback: {"command":"python3","args":["tools/mcp/messages/server.py"]}
```

`McpConfigBuilder.buildAllowedTools()` assembles the comma-separated tools string:

```
buildAllowedTools(baseTools)
    |
    +-- Start with baseTools (e.g., "Read,Edit,Write,Bash,Glob,Grep")
    |
    +-- For each centralized server:
    |       Append: mcp__<serverName>__<tool1>,mcp__<serverName>__<tool2>,...
    |
    +-- For each pushed server:
    |       Append: mcp__<serverName>__<tool1>,mcp__<serverName>__<tool2>,...
    |
    +-- If ar-github not centralized/pushed:
    |       Append: mcp__ar-github__github_pr_find,...
    |
    +-- If ar-messages not centralized/pushed AND workstreamUrl set:
    |       Append: mcp__ar-messages__send_message
    |
    +-- For each discovered project server:
            Parse Python source for tool names
            Append: mcp__<serverName>__<tool1>,...
```

### MCP Config Priority

When the same server name appears in multiple sources, the priority is:

1. **Centralized** (highest) -- HTTP entry, no local execution needed
2. **Pushed** -- stdio entry with downloaded source
3. **Project** (lowest) -- stdio entry from repository `.mcp.json`

Centralized servers take precedence because they are explicitly configured by the controller
administrator and represent infrastructure-level decisions. Pushed servers take precedence
over project servers because they represent controller-managed tooling that may be a newer
or customized version of what the repository ships.

### ManagedToolsDownloader

The `ManagedToolsDownloader` class handles two responsibilities:

1. **Downloading pushed tools** -- `ensurePushedTools()` downloads server files from the
   controller to `~/.flowtree/tools/mcp/{name}/server.py`. Files are only downloaded if
   they do not already exist, avoiding redundant downloads across jobs.

2. **Verifying MCP tool files** -- `verifyMcpToolFiles()` checks that the expected tool
   files exist in the working directory and logs their modification times. This aids
   deployment diagnostics by making it visible when tool files are stale or missing.

### InstructionPromptBuilder

The `InstructionPromptBuilder` constructs the full prompt that wraps the user's request
with operational context. Sections are conditionally included based on the job's
configuration:

| Section | Condition |
|---------|-----------|
| Slack Communication | workstreamUrl is set |
| Permission Denials | workstreamUrl is set |
| Non-Code Requests | workstreamUrl is set |
| Justifying No Code Changes | workstreamUrl is set |
| GitHub instructions | ar-github is in MCP config (always true) |
| Test Integrity Policy | protectTestFiles is enabled |
| Git commit instructions | targetBranch is set |
| Merge Conflicts | mergeConflictsDetected is true |
| Branch Context | always included |
| Branch Awareness and Continuity | targetBranch is set |
| Budget and turn limits | maxBudgetUsd > 0 or maxTurns > 0 |
| Task ID and Workstream context | taskId or workstreamUrl is set |
| Planning Document | planningDocument is set |
| User Request (wrapped in markers) | always included |

The prompt is designed for autonomous execution: it tells the agent not to wait for user
input, instructs it to use Slack for status updates, provides conflict resolution
instructions when needed, warns against common anti-patterns (like add/revert loops), and
embeds the user's actual request between `--- BEGIN USER REQUEST ---` and
`--- END USER REQUEST ---` markers.

---

## Appendix: Key Constants Reference

| Constant | Location | Value | Purpose |
|----------|----------|-------|---------|
| `ENTRY_SEPARATOR` | `JobFactory` / `JobOutput` | `"::"` | Separates entries in wire format |
| `KEY_VALUE_SEPARATOR` | `JobFactory` | `":="` | Separates key from value |
| `PROMPT_SEPARATOR` | `ClaudeCodeJob` | `";;PROMPT;;"` | Separates prompts in the factory |
| `DEFAULT_TOOLS` | `ClaudeCodeJob` | `"Read,Edit,Write,Bash,Glob,Grep"` | Default allowed tools |
| `DEFAULT_MAX_FILE_SIZE` | `GitJobConfig` | 1,048,576 (1 MB) | Max file size for staging |
| `FALLBACK_WORKSPACE_DIR` | `WorkspaceResolver` | `"/tmp/flowtree-workspaces"` | Fallback checkout path |
