# Job Completion Events Reference

This document covers the event system used by FlowTree jobs to report their completion status. It details the `JobCompletionEvent` base class, the `ClaudeCodeJobEvent` subclass, the `JobCompletionListener` interface, and the JSON serialization and HTTP transport used to deliver events from agents to the controller.

---

## Table of Contents

1. [JobCompletionEvent Field Reference](#jobcompletionevent-field-reference)
2. [ClaudeCodeJobEvent Extension Fields](#claudecodejobEvent-extension-fields)
3. [Event Lifecycle](#event-lifecycle)
4. [JobCompletionListener Interface Contract](#jobcompletionlistener-interface-contract)
5. [JSON Serialization Format](#json-serialization-format)
6. [Workstream URL Resolution and HTTP Posting](#workstream-url-resolution-and-http-posting)
7. [The Polymorphism Model](#the-polymorphism-model)

---

## JobCompletionEvent Field Reference

`JobCompletionEvent` is the base event class in the `io.flowtree.jobs` package. It carries all the information needed to report a job's outcome -- both for generic jobs and, via default-returning getter methods, for Claude Code jobs.

### Status Enum

The `Status` enum defines four possible states:

| Value | Description |
|---|---|
| `STARTED` | Job has started execution but is not yet complete. |
| `SUCCESS` | Job completed successfully. |
| `FAILED` | Job failed with an error. |
| `CANCELLED` | Job was cancelled before completion. |

### Core Fields

These fields are set during event construction and are always present:

| Field | Type | Set By | Description |
|---|---|---|---|
| `jobId` | `String` | Constructor | The unique identifier for the job. Corresponds to the task ID from the Factory. |
| `status` | `Status` | Constructor | The completion status of the job. |
| `description` | `String` | Constructor | Human-readable description of the job (typically a truncated prompt or task summary). |
| `timestamp` | `Instant` | Constructor (auto) | The instant at which the event was created. Set to `Instant.now()` in the constructor. |

### Git Fields

These fields are set via the `withGitInfo()` builder method after git operations complete:

| Field | Type | Default | Description |
|---|---|---|---|
| `targetBranch` | `String` | `null` | The git branch that the job targeted for its changes. |
| `commitHash` | `String` | `null` | The SHA hash of the commit created by the job, or `null` if no commit was made. |
| `stagedFiles` | `List<String>` | `emptyList()` | List of file paths that were staged and committed. |
| `skippedFiles` | `List<String>` | `emptyList()` | List of file paths that were skipped during staging, with reasons (e.g., "(excluded pattern)", "(exceeds 1.0 MB)", "(binary file)", "(protected - exists on base branch)"). |
| `pushed` | `boolean` | `false` | Whether the commit was pushed to origin. |

### Error Fields

These fields are set during event construction for `FAILED` status events:

| Field | Type | Default | Description |
|---|---|---|---|
| `errorMessage` | `String` | `null` | Human-readable error description. |
| `exception` | `Throwable` | `null` | The exception that caused the failure, if available. Not serialized to JSON (used for local logging only). |

### Pull Request Field

| Field | Type | Default | Description |
|---|---|---|---|
| `pullRequestUrl` | `String` | `null` | The GitHub pull request URL detected after pushing, or `null` if no open PR was found. Set via `withPullRequestUrl()`. |

### Claude Code Default Getters

The base class declares getter methods for all Claude Code-specific fields, returning zero/null defaults. This allows consumers (such as `SlackNotifier`) to call these methods uniformly on any `JobCompletionEvent` without type-checking:

| Method | Return Type | Base Class Default |
|---|---|---|
| `getPrompt()` | `String` | `null` |
| `getSessionId()` | `String` | `null` |
| `getExitCode()` | `int` | `0` |
| `getDurationMs()` | `long` | `0` |
| `getDurationApiMs()` | `long` | `0` |
| `getCostUsd()` | `double` | `0` |
| `getNumTurns()` | `int` | `0` |
| `getSubtype()` | `String` | `null` |
| `isSessionError()` | `boolean` | `false` |
| `getPermissionDenials()` | `int` | `0` |
| `getDeniedToolNames()` | `List<String>` | `emptyList()` |

### Factory Methods

| Method | Status | Description |
|---|---|---|
| `started(jobId, description)` | `STARTED` | Creates a started event. |
| `success(jobId, description)` | `SUCCESS` | Creates a success event. |
| `failed(jobId, description, errorMessage, exception)` | `FAILED` | Creates a failure event with error details. |

### Builder Methods

| Method | Returns | Description |
|---|---|---|
| `withGitInfo(branch, commitHash, staged, skipped, pushed)` | `this` | Sets all git-related fields. |
| `withPullRequestUrl(url)` | `this` | Sets the pull request URL. |

### Protected Setters

Two protected setters exist for use by subclass factory methods:

- `setErrorMessage(String)` -- Sets the error message. Used by `ClaudeCodeJobEvent.failed()`.
- `setException(Throwable)` -- Sets the exception. Used by `ClaudeCodeJobEvent.failed()`.

These are `protected` rather than `public` because they should only be called during event construction (in factory methods or subclass constructors), never by external consumers. The `final` fields (`jobId`, `status`, `description`, `timestamp`) are set only in the constructor and have no setters, making them effectively immutable.

### toString() Representation

The `toString()` method returns a compact representation suitable for log output:
```
JobCompletionEvent{jobId='abc123', status=SUCCESS, description='Fix the bug...', commitHash='a1b2c3d'}
```

This includes only the most essential fields for quick identification in log streams.

---

## ClaudeCodeJobEvent Extension Fields

`ClaudeCodeJobEvent` extends `JobCompletionEvent` to add fields specific to Claude Code job execution. Only `ClaudeCodeJob` creates instances of this subclass.

### Claude Code Identification Fields

Set via `withClaudeCodeInfo(prompt, sessionId, exitCode)`:

| Field | Type | Description |
|---|---|---|
| `prompt` | `String` | The full prompt that was sent to Claude Code (the raw user request, not the wrapped instruction prompt). |
| `sessionId` | `String` | The Claude Code session identifier, extracted from the NDJSON result object. Can be used to resume the session. |
| `exitCode` | `int` | The process exit code from the Claude Code subprocess (0 typically indicates success). |

### Timing Information Fields

Set via `withTimingInfo(durationMs, durationApiMs, costUsd, numTurns)`:

| Field | Type | Description |
|---|---|---|
| `durationMs` | `long` | Total wall-clock duration of the Claude Code session in milliseconds, as reported by Claude Code's result object. |
| `durationApiMs` | `long` | Time spent in API calls during the session, in milliseconds. The difference `durationMs - durationApiMs` represents time spent in local tool execution and thinking. |
| `costUsd` | `double` | Total cost of the session in USD. |
| `numTurns` | `int` | The number of agentic turns (tool-use cycles) completed during the session. |

### Session Detail Fields

Set via `withSessionDetails(subtype, sessionIsError, permissionDenials, deniedToolNames)`:

| Field | Type | Description |
|---|---|---|
| `subtype` | `String` | The session subtype or stop reason from Claude Code output. Common values: `"success"` (completed normally), `"error_max_turns"` (hit turn limit). |
| `sessionIsError` | `boolean` | Whether Claude Code flagged the session as an error (distinct from the job-level `FAILED` status, which indicates a process-level or git-level failure). |
| `permissionDenials` | `int` | Count of tool permission denials that occurred during the session. Non-zero values indicate misconfigured allowed-tools lists. |
| `deniedToolNames` | `List<String>` | Names of tools that were denied. The same tool may appear multiple times if it was denied more than once. Returns an empty list (not null) when no denials occurred. |

### Factory Methods

| Method | Status | Description |
|---|---|---|
| `success(jobId, description)` | `SUCCESS` | Creates a Claude Code success event. |
| `failed(jobId, description, errorMessage, exception)` | `FAILED` | Creates a Claude Code failure event. Uses the protected `setErrorMessage()` and `setException()` methods from the base class. |

### Overridden Getters

`ClaudeCodeJobEvent` overrides all the Claude Code default getters from the base class to return the actual stored values instead of zero/null defaults. The overridden methods are: `getPrompt()`, `getSessionId()`, `getExitCode()`, `getDurationMs()`, `getDurationApiMs()`, `getCostUsd()`, `getNumTurns()`, `getSubtype()`, `isSessionError()`, `getPermissionDenials()`, `getDeniedToolNames()`.

This is the core of the polymorphism model: any code that holds a `JobCompletionEvent` reference and calls these getters will transparently get real values when the reference actually points to a `ClaudeCodeJobEvent`, and zero/null defaults when it points to a base `JobCompletionEvent`. No `instanceof` checks are needed by the caller.

### Field Population Order

The builder methods on `ClaudeCodeJobEvent` are designed to be called in a specific order that mirrors the availability of data during job execution:

1. **Construction**: `success()` or `failed()` sets the jobId, status, and description.
2. **Claude Code info**: `withClaudeCodeInfo()` sets the prompt, session ID, and exit code -- these are known immediately after the process exits.
3. **Timing info**: `withTimingInfo()` sets duration, API duration, cost, and turns -- these are extracted from the NDJSON output.
4. **Session details**: `withSessionDetails()` sets the stop reason, error flag, and permission denial details -- these are also extracted from the NDJSON output.
5. **Git info**: `withGitInfo()` (inherited) sets branch, commit hash, staged/skipped files, and push status -- this is populated by the git harness after Claude Code finishes.
6. **PR URL**: `withPullRequestUrl()` (inherited) sets the pull request URL -- detected after pushing.

---

## Event Lifecycle

The lifecycle of a job completion event spans four stages: construction, population, serialization, and delivery.

### Stage 1: Construction

Events are created by `GitManagedJob.fireJobCompleted(Exception error)`, which delegates to `createEvent(Exception error)`. `ClaudeCodeJob` overrides `createEvent()` to return a `ClaudeCodeJobEvent`:

```java
@Override
protected JobCompletionEvent createEvent(Exception error) {
    if (error != null) {
        return ClaudeCodeJobEvent.failed(
            getTaskId(), getTaskString(),
            error.getMessage(), error
        );
    } else {
        return ClaudeCodeJobEvent.success(getTaskId(), getTaskString());
    }
}
```

For non-Claude jobs, the base `GitManagedJob.createEvent()` returns `JobCompletionEvent.failed()` or `JobCompletionEvent.success()`.

### Stage 2: Population

After construction, `fireJobCompleted()` populates the event in three steps:

1. **Git info**: `event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, pushed)` -- the `pushed` flag is `true` only when git operations succeeded, `pushToOrigin` was enabled, and there were staged files.

2. **Pull request URL**: `event.withPullRequestUrl(pullRequestUrl)` -- set only if a PR was detected.

3. **Subclass details**: `populateEventDetails(event)` is called. `ClaudeCodeJob` overrides this to call:
   - `ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode)` -- the raw prompt, extracted session ID, and process exit code.
   - `ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns)` -- timing and cost metrics from the NDJSON output.
   - `ccEvent.withSessionDetails(subtype, isError, permissionDenials, deniedToolNames)` -- stop reason, error flag, and permission denial details.

### Stage 3: Serialization

`GitManagedJob.postStatusEvent(event)` calls `buildEventJson(event)`, which uses Jackson `ObjectNode` to construct a flat JSON object. The serialization calls the getter methods on the event, which means the polymorphism model is active: for `ClaudeCodeJobEvent` instances, the real values are serialized; for base `JobCompletionEvent` instances, the zero/null defaults are serialized.

### Stage 4: Delivery

The serialized JSON is POSTed to the resolved workstream URL via `postJson(url, json)`. This is a fire-and-forget operation: HTTP errors are logged as warnings but do not fail the job. The controller's `FlowTreeApiEndpoint` receives the POST, deserializes the event, and delegates to the `SlackNotifier`.

### STARTED Events

`STARTED` events follow a shorter lifecycle. They are constructed by `JobCompletionEvent.started(jobId, description)`, populated with git info (branch name only, no commit hash), and sent to the `SlackNotifier` via `notifier.onJobStarted()`. They are not serialized through the HTTP path in all cases; the API endpoint's submit handler calls `notifier.onJobStarted()` directly.

STARTED events always use the base `JobCompletionEvent` class, never `ClaudeCodeJobEvent`, because no Claude Code-specific data is available at job start time (the Claude Code process has not been launched yet).

### Event Timing

The `timestamp` field on the event is set to `Instant.now()` in the constructor. This means it reflects the time of event construction on the agent node, not the time of receipt on the controller. For `SUCCESS` and `FAILED` events, this is the time at which the git harness completed all post-work operations. For `STARTED` events created by the API endpoint, this is the time of job dispatch.

The difference between the STARTED event timestamp and the SUCCESS/FAILED event timestamp gives an approximate wall-clock duration for the entire job (including git operations). This is distinct from the `durationMs` field in `ClaudeCodeJobEvent`, which measures only the Claude Code subprocess duration (excluding git operations).

---

## JobCompletionListener Interface Contract

`JobCompletionListener` is a single-method interface (with a default method) in `io.flowtree.jobs`:

```java
public interface JobCompletionListener {
    void onJobCompleted(String workstreamId, JobCompletionEvent event);

    default void onJobStarted(String workstreamId, JobCompletionEvent event) {
        // Default: no-op
    }
}
```

### Method Contract

#### onJobCompleted(String workstreamId, JobCompletionEvent event)

Called when a job completes, whether successfully or with failure. The `workstreamId` parameter identifies which workstream owns the job. When events arrive via HTTP (from the agent to the controller), the workstream ID is extracted from the URL path (`/api/workstreams/{id}`) rather than from the event payload. This ensures the routing is determined by the URL structure, not by potentially mutable event data.

The event may be either a `JobCompletionEvent` or a `ClaudeCodeJobEvent`. Implementations should use the polymorphic getters (e.g., `event.getCostUsd()`) rather than type-checking, since the base class getters return safe defaults.

The primary implementation is `SlackNotifier`, which formats the event into a Slack message and posts it to the workstream's channel. The Slack message includes:
- Status (success/failure indicator)
- Job description
- Git details (branch, commit hash, staged/skipped files)
- Pull request URL (if detected)
- Claude Code details (cost, turns, duration, session ID) for `ClaudeCodeJobEvent` instances
- Error details for failed jobs

#### onJobStarted(String workstreamId, JobCompletionEvent event)

Called when a job begins execution. The default implementation is a no-op. The `SlackNotifier` overrides this to post a "job started" message to the workstream's Slack channel, including the job description and target branch.

### Thread Safety

The `JobCompletionListener` interface makes no thread safety guarantees. Implementations should handle their own synchronization if they maintain mutable state. In the current system, the `SlackNotifier` implementation is called from multiple threads (one per agent connection plus the API endpoint thread), but Slack API calls are inherently serialized by the HTTP request/response cycle.

### Multiple Listeners

The `JobCompletionListener` interface is designed for a single implementation (typically `SlackNotifier`). However, `FlowTreeApiEndpoint` and `SlackListener` both route events to the same `SlackNotifier` instance. The `FlowTreeApiEndpoint` handles events arriving via HTTP from agents, while `SlackListener` handles events from the Slack message queue. Both call the same `onJobCompleted()` and `onJobStarted()` methods, ensuring consistent Slack notification formatting regardless of the event source.

---

## JSON Serialization Format

Events are serialized to JSON in `GitManagedJob.buildEventJson()` using Jackson `ObjectNode`. The format is a flat object with no nesting (except for array fields).

### Full JSON Schema

```json
{
  "jobId": "string",
  "status": "STARTED | SUCCESS | FAILED | CANCELLED",
  "description": "string",
  "targetBranch": "string | null",
  "commitHash": "string | null",
  "pushed": true | false,
  "stagedFiles": ["string", ...],
  "skippedFiles": ["string (reason)", ...],
  "pullRequestUrl": "string | null",
  "errorMessage": "string | null",
  "prompt": "string | null",
  "sessionId": "string | null",
  "exitCode": 0,
  "durationMs": 0,
  "durationApiMs": 0,
  "costUsd": 0.0,
  "numTurns": 0,
  "subtype": "string | null",
  "sessionIsError": false,
  "permissionDenials": 0,
  "deniedToolNames": ["string", ...]
}
```

### Field Details

| JSON Field | Java Getter | Type | Notes |
|---|---|---|---|
| `jobId` | `getJobId()` | string | Always present. |
| `status` | `getStatus().name()` | string | Enum name: `STARTED`, `SUCCESS`, `FAILED`, or `CANCELLED`. |
| `description` | `getDescription()` | string | Truncated prompt or task summary. |
| `targetBranch` | `getTargetBranch()` | string/null | `null` when no git management is active. |
| `commitHash` | `getCommitHash()` | string/null | Full SHA hash of the commit, or `null`. |
| `pushed` | `isPushed()` | boolean | `true` only when files were committed and pushed to origin. |
| `stagedFiles` | `getStagedFiles()` | array | Empty array when no files were staged. |
| `skippedFiles` | `getSkippedFiles()` | array | Each entry includes a reason suffix in parentheses. |
| `pullRequestUrl` | `getPullRequestUrl()` | string/null | GitHub PR URL or `null`. |
| `errorMessage` | `getErrorMessage()` | string/null | `null` for successful jobs. |
| `prompt` | `getPrompt()` | string/null | `null` for non-Claude jobs (base class default). |
| `sessionId` | `getSessionId()` | string/null | `null` for non-Claude jobs. |
| `exitCode` | `getExitCode()` | int | `0` for non-Claude jobs (base class default). |
| `durationMs` | `getDurationMs()` | long | `0` for non-Claude jobs. |
| `durationApiMs` | `getDurationApiMs()` | long | `0` for non-Claude jobs. |
| `costUsd` | `getCostUsd()` | double | `0.0` for non-Claude jobs. |
| `numTurns` | `getNumTurns()` | int | `0` for non-Claude jobs. |
| `subtype` | `getSubtype()` | string/null | `null` for non-Claude jobs. |
| `sessionIsError` | `isSessionError()` | boolean | `false` for non-Claude jobs. |
| `permissionDenials` | `getPermissionDenials()` | int | `0` for non-Claude jobs. |
| `deniedToolNames` | `getDeniedToolNames()` | array | Empty array for non-Claude jobs. |

### Serialization Details

Jackson `ObjectNode` is used for all construction. The `eventMapper` is a static `ObjectMapper` instance shared across calls. Serialization uses `eventMapper.writeValueAsString(root)`. If serialization fails, `"{}"` is returned as a fallback, ensuring that the HTTP POST never sends malformed content.

The construction process follows a consistent pattern for each field:
- String fields use `root.put(fieldName, event.getterMethod())`, which handles null values by inserting a JSON null.
- Numeric fields use `root.put(fieldName, event.getterMethod())`, which serializes zero values as `0` or `0.0`.
- Boolean fields use `root.put(fieldName, event.getterMethod())`, which serializes as `true` or `false`.
- Array fields (like `stagedFiles`, `skippedFiles`, `deniedToolNames`) use `root.putArray(fieldName)` to create a JSON array node, then iterate through the list calling `arrayNode.add(element)` for each entry. Empty lists produce empty JSON arrays `[]`, not null.

Note that the `timestamp` and `exception` fields are NOT serialized. The `timestamp` field is omitted because the receiving side creates a fresh timestamp when it constructs the deserialized event object. The `exception` field is omitted because `Throwable` objects cannot be meaningfully serialized to JSON -- they contain stack traces, circular cause references, and class-specific state that would be opaque to the receiver. Exception information is instead captured in the `errorMessage` string field, which is a human-readable summary.

---

## Workstream URL Resolution and HTTP Posting

### URL Structure

The workstream URL follows one of two patterns:

- **Job-level**: `http://<host>:<port>/api/workstreams/<wsId>/jobs/<jobId>` -- Used when the job was submitted through a workstream with a specific job ID. Slack messages sent to this URL are threaded under the job's thread.
- **Workstream-level**: `http://<host>:<port>/api/workstreams/<wsId>` -- Used when no job-specific routing is needed.

The URL is constructed by `FlowTreeApiEndpoint.handleSubmit()` and set on the Factory before dispatch:

```java
String baseUrl = "http://0.0.0.0:" + listeningPort
    + "/api/workstreams/" + workstream.getWorkstreamId()
    + "/jobs/" + factory.getTaskId();
factory.setWorkstreamUrl(baseUrl);
```

### Host Resolution

The URL initially uses `0.0.0.0` as the host, which is a placeholder. When the job runs on an agent (potentially in a Docker container), `resolveWorkstreamUrl()` replaces `0.0.0.0` with the value of the `FLOWTREE_ROOT_HOST` environment variable, which contains the controller's actual hostname or IP address as seen from the agent's network.

### HTTP Posting

`GitManagedJob.postJson(url, json)` performs the POST:

- Method: `POST`
- Content-Type: `application/json`
- Connect timeout: 5000ms
- Read timeout: 10000ms
- Fire-and-forget: non-2xx responses and exceptions are logged as warnings but do not fail the job or propagate exceptions.

### Receiving Side (FlowTreeApiEndpoint)

`FlowTreeApiEndpoint.handleStatusEvent()` handles POST requests to `/api/workstreams/{id}` and `/api/workstreams/{id}/jobs/{jobId}`. It:

1. Reads the POST body as a string.
2. Extracts the `status` field and validates it against the `Status` enum.
3. Determines whether to create a `ClaudeCodeJobEvent` or `JobCompletionEvent` based on the presence of `prompt` or `sessionId` fields.
4. Populates the event from the JSON body using `JsonFieldExtractor` methods (`extractString`, `extractInt`, `extractLong`, `extractDouble`, `extractBoolean`, `extractStringArray`).
5. Delegates to `notifier.onJobStarted()` for `STARTED` events or `notifier.onJobCompleted()` for all other statuses.

The `JsonFieldExtractor` class provides lightweight JSON parsing that avoids requiring a full Jackson dependency on the receiving side for simple field extraction. It uses string-based scanning to extract individual field values from a JSON string. Each `extract*` method searches for the field name as a quoted key, then parses the value based on the expected type. String values are extracted between quote delimiters, numeric values are parsed using `Long.parseLong()` or `Double.parseDouble()`, and boolean values are matched against the literals `true` and `false`. Array values are extracted by finding matching brackets and splitting on comma delimiters.

This approach trades full JSON parsing fidelity for simplicity and zero external dependencies. It works correctly for the flat, well-structured JSON that `buildEventJson()` produces, but would break on nested objects or escaped characters in field values. Since the serialization side (which uses Jackson) controls the format, and the deserialization side (which uses `JsonFieldExtractor`) expects exactly that format, this limitation is acceptable in practice.

---

## The Polymorphism Model

The event system uses a deliberate polymorphism design to allow uniform handling of both generic jobs and Claude Code jobs. This section explains the design rationale and mechanics.

### Problem Statement

The `SlackNotifier` and other event consumers need to format job completion messages that may or may not include Claude Code-specific information (cost, turns, session ID, etc.). Without a polymorphism model, consumers would need to type-check every event and handle two completely different code paths.

### Solution: Default Getters on the Base Class

`JobCompletionEvent` declares getter methods for all Claude Code-specific fields, each returning a zero or null default:

```java
// In JobCompletionEvent (base class)
public String getPrompt() { return null; }
public double getCostUsd() { return 0; }
public int getNumTurns() { return 0; }
// ... etc.
```

`ClaudeCodeJobEvent` overrides these getters to return the actual stored values:

```java
// In ClaudeCodeJobEvent (subclass)
@Override
public String getPrompt() { return prompt; }
@Override
public double getCostUsd() { return costUsd; }
@Override
public int getNumTurns() { return numTurns; }
// ... etc.
```

### How Consumers Use It

Consumers call the getters uniformly on any `JobCompletionEvent`:

```java
void onJobCompleted(String workstreamId, JobCompletionEvent event) {
    // These work for both event types:
    String jobId = event.getJobId();         // Always present
    double cost = event.getCostUsd();         // 0.0 for non-Claude, real value for Claude
    int turns = event.getNumTurns();          // 0 for non-Claude, real value for Claude

    // Conditional formatting based on whether real data exists:
    if (cost > 0) {
        message.append("Cost: $" + cost);
    }
}
```

No `instanceof` checks are needed. Non-zero/non-null values indicate that the event is from a Claude Code job without requiring explicit type inspection.

### How Serialization Uses It

The `buildEventJson()` method in `GitManagedJob` calls the getter methods on the event object:

```java
root.put("prompt", event.getPrompt());         // null or real value
root.put("costUsd", event.getCostUsd());        // 0.0 or real value
root.put("numTurns", event.getNumTurns());      // 0 or real value
```

Because these are virtual method calls, Java dispatches to the correct implementation:
- For `JobCompletionEvent`: the base class defaults (null, 0, false)
- For `ClaudeCodeJobEvent`: the actual stored values

### How Deserialization Uses It

On the receiving side (`FlowTreeApiEndpoint.handleStatusEvent()`), the reverse happens. The deserializer checks for the presence of `prompt` or `sessionId` fields to decide which class to instantiate:

```java
boolean isClaudeCodeEvent = prompt != null || sessionId != null;

if (isClaudeCodeEvent) {
    event = new ClaudeCodeJobEvent(jobId, eventStatus, description);
} else {
    event = new JobCompletionEvent(jobId, eventStatus, description);
}
```

If a `ClaudeCodeJobEvent` is created, its builder methods (`withClaudeCodeInfo`, `withTimingInfo`, `withSessionDetails`) are called to populate the Claude-specific fields. The populated event is then passed to the `SlackNotifier`, which uses the same uniform getter pattern.

### Design Benefits

1. **No type-checking in consumers**: Event handlers never need `if (event instanceof ClaudeCodeJobEvent)` guards. This eliminates an entire category of bugs where new event types are added but consumers forget to add the corresponding `instanceof` check. Every consumer works correctly with every event type by default.

2. **Safe defaults**: Non-Claude events naturally produce zero/null for Claude-specific fields, which format correctly in Slack messages (zero values are simply omitted). A consumer that checks `if (cost > 0)` before formatting the cost line automatically does the right thing for both event types without any awareness of the type distinction.

3. **Single serialization path**: `buildEventJson()` does not need separate code paths for different event types. Virtual method dispatch handles the differentiation transparently. The serialized JSON always contains all fields, with zeros/nulls for non-applicable ones.

4. **Extensibility**: New event subclasses can be added by overriding the relevant getters without modifying consumers. For example, a hypothetical `BatchJobEvent` could add batch-specific fields by declaring new default getters on the base class and overriding them in the subclass, following the exact same pattern.

5. **Backward compatibility**: If a consumer is unaware of Claude Code fields, it silently ignores them (they are just zero/null on the base class). This is particularly important for the deserialization path: if an older version of the controller receives a `ClaudeCodeJobEvent` JSON payload, the extra fields are simply parsed and stored as zero/null, producing a valid `JobCompletionEvent` with no Claude-specific data.

### Where instanceof IS Used

Despite the design goal of avoiding type-checking, there is exactly one place in the system where `instanceof` is used: in `ClaudeCodeJob.populateEventDetails()`, which casts the event to `ClaudeCodeJobEvent` before calling the builder methods. This is necessary because the base class `fireJobCompleted()` method calls `populateEventDetails()` with the generic `JobCompletionEvent` type, and the builder methods (`withClaudeCodeInfo`, `withTimingInfo`, `withSessionDetails`) are defined only on the subclass. This single type-check is confined to the event producer side and does not affect any consumer code.

### Comparison with Alternative Approaches

The default-getters-on-base-class approach was chosen over two alternative designs:

1. **Separate event hierarchies**: Having `JobCompletionEvent` and `ClaudeCodeJobEvent` as unrelated classes (or sharing only a marker interface) would require every consumer to handle both types explicitly. This was rejected because it pushes complexity to every consumer site.

2. **Optional fields with a flat class**: Using a single `JobCompletionEvent` class with all fields and letting non-Claude jobs leave them null would avoid the subclass entirely. This was rejected because it loses the clear structural distinction between Claude-specific and generic fields, and it would allow callers to accidentally set Claude-specific fields on non-Claude events.
