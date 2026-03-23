# CI Integration and Auto-Resolve Workflow

This document describes how Flowtree integrates with CI/CD pipelines to automatically detect and resolve failures. It covers the auto-resolve workflow, failure detection mechanisms, prompt generation, the job submission API, quality gate evaluation, concurrency model, and test file protection.

---

## Table of Contents

1. [Overview](#overview)
2. [Verify-Completion Workflow](#verify-completion-workflow)
3. [Auto-Resolve Workflow](#auto-resolve-workflow)
4. [Failure Detection](#failure-detection)
5. [Prompt Generation](#prompt-generation)
6. [Job Submission API](#job-submission-api)
7. [Workstream Registration API](#workstream-registration-api)
8. [Quality Gate Evaluation](#quality-gate-evaluation)
9. [Concurrency Model](#concurrency-model)
10. [Test File Protection](#test-file-protection)
11. [Surefire Report Parsing](#surefire-report-parsing)
12. [End-to-End Flow Diagram](#end-to-end-flow-diagram)

---

## Overview

The auto-resolve system creates a feedback loop between CI pipelines and coding agents. When a CI pipeline detects a failure (build error, test failure, or quality gate violation), it submits a job to the Flowtree controller describing the failure. The controller dispatches the job to an available agent, which runs Claude Code to diagnose and fix the issue. The fix is committed and pushed, triggering a new CI run that validates the resolution.

The key components involved are:

- **CI Pipeline** (GitHub Actions workflows): Detects failures and submits resolution jobs
- **FlowTreeApiEndpoint**: The controller's HTTP API that receives job submissions
- **SlackNotifier**: Routes jobs to the appropriate workstream and tracks progress
- **ClaudeCodeJob.Factory**: Constructs the job that will execute on an agent
- **ClaudeCodeJob**: Executes the Claude Code prompt and manages git operations
- **detect-test-hiding.sh**: A validation script that audits changes before commit

---

## Verify-Completion Workflow

The verify-completion workflow (`verify-completion.yaml`) implements a self-improvement loop where coding agents implement and verify plan goals on feature branches. It is triggered manually via `workflow_dispatch` on any non-master branch.

### Three-Phase Pipeline

The workflow runs as a three-job pipeline with conditional execution:

```
detect-plan  ──>  register-workstream (conditional)  ──>  verify
```

**Phase 1: Detect Plan** (`detect-plan`)

Identifies the plan document for the branch. The plan file can be provided explicitly via the `plan_file` workflow input, or auto-detected by scanning for new `.md` files in `docs/plans/` relative to master. The job outputs:
- `plan_file`: Path to the detected plan document
- `is_new_plan`: Whether the plan file is newly added on this branch

**Phase 2: Register Workstream** (`register-workstream`, conditional)

Runs only when `is_new_plan` is `true`. Calls `tools/ci/register-workstream.sh` which POSTs to the controller's `POST /api/workstreams` endpoint. This:
- Creates a new `SlackWorkstream` with the branch name, base branch, repo URL, and plan document path
- Auto-creates a **private** Slack channel named `w-<branch>` (with slashes replaced by hyphens)
- Invites the configured `channelOwnerUserId` to the new channel
- Persists the workstream to the YAML config file

**Phase 3: Verify** (`verify`)

Runs after detect-plan succeeds, even if register-workstream was skipped (but not if it failed). Builds a prompt from the plan document via `build-verify-prompt.sh` and submits it as a job via `submit-agent-job.sh`. The agent then implements any unfinished goals, verifies all goals are complete, and ensures each goal has meaningful test coverage.

### Register Workstream Script

The `tools/ci/register-workstream.sh` script handles workstream registration from CI:

**Required environment variables:**
- `BRANCH` -- target branch for the workstream
- `BASE_BRANCH` -- base branch (e.g., `master`)
- `PLAN_FILE` -- path to the planning document

**Optional environment variables:**
- `CONTROLLER_HOST` -- FlowTree controller hostname (default: `localhost`)
- `CONTROLLER_PORT` -- FlowTree controller port (default: `7780`)
- `REPO_URL` -- repository clone URL

**Channel name derivation:**
The channel name is derived from the branch name: `project/plan-20260223-foo` becomes `w-project-plan-20260223-foo`. The `w-` prefix is always added (idempotently) to distinguish workstream channels from other channels.

---

## Auto-Resolve Workflow

The auto-resolve workflow is triggered by CI pipeline failures and follows this sequence:

### Step 1: CI Pipeline Runs

The CI pipeline (GitHub Actions or equivalent) runs the standard build and test steps. This includes compilation, unit tests, integration tests, and any quality gates defined in the workflow.

### Step 2: Failure Detection

When a step fails, the pipeline's failure handling logic activates. This is typically implemented in a dedicated job or step that runs on `failure()` condition. The failure handler collects:

- The failing step name and exit code
- Build output or test output (truncated to a reasonable size)
- Surefire XML reports (for test failures)
- The branch name and commit that triggered the pipeline
- The repository URL

### Step 3: Job Submission

The failure handler POSTs a JSON payload to the Flowtree controller's submit endpoint. The payload includes the failure information formatted as a prompt that describes what went wrong and what the agent should investigate.

```
POST /api/workstreams/{workstreamId}/submit
Content-Type: application/json

{
  "prompt": "The CI pipeline failed on branch feature/my-work...",
  "targetBranch": "feature/my-work",
  "protectTestFiles": true
}
```

### Step 4: Controller Dispatches Job

The controller's `FlowTreeApiEndpoint` receives the submission, resolves the target workstream (by branch name match or explicit workstream ID), constructs a `ClaudeCodeJob.Factory`, and dispatches it to an available agent via the Flowtree task queue.

### Step 5: Agent Executes Fix

The agent receives the job, clones or updates the repository, runs Claude Code with the failure prompt, and the agent diagnoses and fixes the issue. After Claude Code completes, the git management layer commits and pushes the changes.

### Step 6: New CI Run

The push triggers a new CI run. If the fix resolves the failure, the pipeline succeeds. If not, the cycle can repeat (subject to concurrency limits and budget constraints).

---

## Failure Detection

The CI pipeline detects three categories of failures, each requiring different information in the prompt.

### Build Failures

Build failures occur when `mvn compile` or `mvn package` fails. The failure information includes:

- The Maven module that failed to compile
- The compiler error output (truncated)
- The file and line number of the error, when available

The prompt instructs the agent to read the specific error, understand the compilation issue, and fix the source code. Since build failures typically have clear error messages with file paths and line numbers, they are the most straightforward to resolve.

### Test Failures

Test failures occur when `mvn test` completes but one or more tests fail. The information collected includes:

- The test class and method names that failed
- The assertion failure message or exception stack trace
- The Surefire XML report path (for structured failure data)
- The Maven module containing the failing tests

The prompt includes the specific test names and failure messages, instructing the agent to investigate whether the test is correct (requiring a production code fix) or whether the test itself needs updating (subject to test file protection rules).

### Quality Gate Failures

Quality gate failures are custom checks that enforce code quality standards beyond compilation and tests. These include:

- The `detect-test-hiding.sh` script that audits for attempts to hide test failures
- Custom validation steps defined in workflow files
- Any post-test analysis that produces a non-zero exit code

The prompt includes the quality gate output and the specific rule that was violated.

---

## Prompt Generation

The prompt sent to the agent is the most critical part of the auto-resolve workflow. It must contain enough context for the agent to understand and fix the issue without requiring interactive clarification.

### ClaudeCodeJob Instruction Prompt

The `ClaudeCodeJob.buildInstructionPrompt()` method constructs a comprehensive instruction prompt that wraps the user's request (the failure description) with operational context. The prompt includes several conditional sections:

**Autonomous Agent Context:**
The prompt begins with instructions for autonomous, non-interactive execution. The agent is told there is no TTY and no interactive session, so it should not attempt to wait for user input.

**Slack Communication (when workstream URL is configured):**
The agent is instructed to use the Slack MCP tool for status updates throughout its work. It must send updates when starting, reaching milestones, making key decisions, and finishing. If tool calls are denied, the agent must immediately report the denial via Slack.

**Test Integrity Policy (when protectTestFiles is enabled):**
The agent is explicitly told it must not modify test files that exist on the base branch. It must fix the production code instead. Tests introduced on the current branch may be modified.

**Git Commit Instructions:**
The agent is told not to make git commits directly. Instead, it should write its desired commit message to a `commit.txt` file in the working directory root. The git management harness handles the actual commit after Claude Code finishes.

**Merge Conflict Instructions (when base branch has diverged):**
If the `prepareWorkingDirectory()` step detected merge conflicts between the target branch and the base branch, the prompt includes specific instructions for resolving those conflicts. It lists the conflicted files and instructs the agent to run `git merge`, resolve conflicts, and complete the merge before proceeding with any other work.

**Branch Context:**
The prompt reminds the agent that all branch references in user instructions refer to remote branches (`origin/<branch>`), since the local branches in the sandboxed environment may be stale or absent.

**Branch Awareness and Anti-Loop Guidance:**
The agent is told it is not the first to work on this branch and that previous agent sessions have made changes reflected in the git history. It is instructed to:
- Use `branch_catchup` to understand prior work
- Record decisions and discoveries via the memory system
- Avoid add/revert loops where changes are made, CI fails, changes are reverted, and the cycle repeats

**Planning Document (when configured):**
If the workstream has a planning document configured, the agent is instructed to read it before starting work. The planning document describes the broader goal of the branch, and the current task is a sub-task of that goal.

**Budget and Turn Limits:**
The prompt includes the configured budget (in USD) and maximum turns so the agent can pace itself accordingly.

**User Request:**
Finally, the actual failure description (the prompt from the CI pipeline) is included between `--- BEGIN USER REQUEST ---` and `--- END USER REQUEST ---` markers.

### Prompt Flow from CI to Agent

The prompt flows through several stages:

1. **CI Pipeline** constructs the failure description as a plain text prompt
2. **Controller API** receives the prompt via the `/submit` endpoint
3. **ClaudeCodeJob.Factory** stores the prompt and encodes it with Base64 for wire transmission
4. **ClaudeCodeJob.buildInstructionPrompt()** wraps the prompt with operational context
5. **Claude Code CLI** receives the complete instruction prompt via the `-p` flag

---

## Job Submission API

The Flowtree controller exposes an HTTP API via `FlowTreeApiEndpoint` for receiving job submissions from CI pipelines and other external systems.

### Submit Endpoint

```
POST /api/workstreams/{workstreamId}/submit
Content-Type: application/json
```

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prompt` | `string` | Yes | The task description or failure information to send to the agent |
| `targetBranch` | `string` | No | The branch to commit changes to. When provided, also used for workstream resolution by branch name |
| `baseBranch` | `string` | No | The base branch for new branch creation (default: workstream's configured default) |
| `protectTestFiles` | `boolean` | No | Whether to enable test file protection (default: workstream's configured default) |
| `workstreamId` | `string` | No | Explicit workstream ID override. When present, takes priority over URL path and branch resolution |
| `maxTurns` | `integer` | No | Maximum turns for the Claude Code session |
| `maxBudgetUsd` | `number` | No | Maximum budget in USD for the session |
| `allowedTools` | `string` | No | Comma-separated allowed tools override |

**Response:**

On success (200):
```json
{
  "status": "submitted",
  "jobId": "abc123",
  "workstreamId": "ws-456",
  "targetBranch": "feature/my-work"
}
```

On error (400):
```json
{
  "error": "Missing required field: prompt"
}
```

### Workstream Resolution

The submit endpoint resolves the target workstream through a multi-step process:

1. **Explicit `workstreamId` in body**: If the request body contains a `workstreamId` field and it matches a registered workstream, that workstream is used.

2. **Branch name match**: If `targetBranch` is provided, the controller searches all registered workstreams for one whose `defaultBranch` matches. This allows CI pipelines to submit jobs without knowing the workstream ID.

3. **URL path fallback**: The `{workstreamId}` from the URL path is used if neither of the above resolves to a valid workstream.

This multi-step resolution ensures that auto-resolve jobs from CI pipelines (which typically know only the branch name) are routed to the correct workstream with the right agent pool and configuration.

### Messages Endpoint

```
POST /api/workstreams/{workstreamId}/messages
Content-Type: application/json
```

Used by agents to store messages and send notifications during job execution. The `ar-messages` MCP tool calls this endpoint.

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | `string` | Yes | The message text to send to the workstream's Slack channel |

### Job-Level Messages Endpoint

```
POST /api/workstreams/{workstreamId}/jobs/{jobId}/messages
Content-Type: application/json
```

Sends a message to the specific Slack thread associated with a job, rather than the channel. The `{jobId}` is used to look up the thread timestamp.

### Status Event Endpoint

The workstream URL itself (same as the messages endpoint but without the `/messages` suffix) receives job completion events posted by `GitManagedJob.fireJobCompleted()`:

```
POST /api/workstreams/{workstreamId}/jobs/{jobId}
Content-Type: application/json
```

The JSON payload includes all fields from `JobCompletionEvent` and `ClaudeCodeJobEvent`:

| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `string` | The job identifier |
| `status` | `string` | `SUCCESS`, `FAILED`, or `STARTED` |
| `description` | `string` | Job description (truncated prompt) |
| `targetBranch` | `string` | The target branch |
| `commitHash` | `string` | The commit hash if a commit was made |
| `pushed` | `boolean` | Whether the commit was pushed |
| `stagedFiles` | `string[]` | List of staged file paths |
| `skippedFiles` | `string[]` | List of skipped files with reasons |
| `pullRequestUrl` | `string` | The PR URL if one was detected |
| `errorMessage` | `string` | Error message if the job failed |
| `prompt` | `string` | The original prompt |
| `sessionId` | `string` | Claude Code session ID |
| `exitCode` | `int` | Claude Code exit code |
| `durationMs` | `long` | Total duration in milliseconds |
| `durationApiMs` | `long` | API call duration in milliseconds |
| `costUsd` | `double` | Total cost in USD |
| `numTurns` | `int` | Number of turns consumed |
| `subtype` | `string` | Stop reason subtype |
| `sessionIsError` | `boolean` | Whether the session ended in error |
| `permissionDenials` | `int` | Number of tool permission denials |
| `deniedToolNames` | `string[]` | Names of denied tools |

---

## Workstream Registration API

The controller provides endpoints for dynamic workstream management, used by CI pipelines to create and update workstreams at runtime.

### Register Endpoint

```
POST /api/workstreams
Content-Type: application/json
```

Creates a new workstream. When a `channelName` is provided and Slack is configured, a private channel is created automatically.

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `defaultBranch` | `string` | Yes | The git branch for this workstream |
| `baseBranch` | `string` | No | Base branch (default: `"master"`) |
| `repoUrl` | `string` | No | Repository clone URL |
| `planningDocument` | `string` | No | Path to the plan document relative to repo root |
| `channelName` | `string` | No | Slack channel name (private channel auto-created) |

**Response (200):**

```json
{
  "ok": true,
  "workstreamId": "ws-abc123",
  "channelId": "C0123456789",
  "channelName": "w-project-plan-foo"
}
```

The workstream is registered in memory and persisted to the YAML config file via `SlackListener.registerAndPersistWorkstream()`.

### Update Endpoint

```
POST /api/workstreams/{id}/update
Content-Type: application/json
```

Updates fields on an existing workstream. All fields are optional; only provided non-empty fields are applied. Supports updating: `channelId`, `channelName`, `defaultBranch`, `baseBranch`, `repoUrl`, `planningDocument`.

### Health Check Endpoint

```
GET /api/health
```

Returns:
```json
{
  "status": "ok"
}
```

### Stats Endpoint

```
GET /api/stats?workstream={workstreamId}&period=weekly
```

Returns aggregated job statistics for the current and previous week, optionally filtered by workstream. The `JobStatsStore` persists job timing data in an embedded database.

---

## Quality Gate Evaluation

Quality gates are checks that run after the agent completes its work but before the changes are committed. They serve as a last line of defense against problematic changes.

### ClaudeCodeJob validateChanges()

The `ClaudeCodeJob.validateChanges()` method is called after `doWork()` completes but before git operations begin. When `protectTestFiles` is enabled, it runs the `detect-test-hiding.sh` script:

```java
protected boolean validateChanges() throws Exception {
    if (!isProtectTestFiles()) {
        return true;
    }

    Path auditScript = resolveWorkingPath("tools/ci/agent-protection/detect-test-hiding.sh");
    if (auditScript == null || !Files.exists(auditScript)) {
        return true;  // Skip if script not found
    }

    // Run: bash detect-test-hiding.sh origin/<baseBranch>
    ProcessBuilder pb = new ProcessBuilder("bash", auditScript.toString(),
        "origin/" + baseBranch);
    // ...
    int code = p.waitFor();

    if (code == 2) {
        // Exit code 2 = violations found
        warn("Test-hiding violations detected - aborting commit");
        return false;
    }

    return true;
}
```

### detect-test-hiding.sh

This shell script audits the diff between the current working state and the base branch for patterns that indicate an agent is hiding test failures rather than fixing production code. It checks for:

- Tests that have been deleted or renamed
- Test assertions that have been weakened (e.g., `assertEquals` replaced with `assertTrue(true)`)
- Test methods that have been emptied or had their bodies replaced with `return`
- `@Ignore` or `@Disabled` annotations added to existing tests
- Test timeout values that have been significantly increased

**Exit codes:**
- `0`: No violations detected
- `1`: Script error (bad arguments, missing git ref, etc.)
- `2`: Violations detected -- changes should be rejected

### File Staging Guardrails

In addition to the `validateChanges()` quality gate, the `FileStager` class applies guardrails during file staging (before commit). These guardrails are applied in order:

1. **Pattern exclusion**: Files matching any excluded glob pattern are skipped
2. **Test file protection**: If enabled, files matching protected path patterns that exist on the base branch are blocked
3. **File size**: Files exceeding the maximum size threshold are skipped (deleted files are exempt)
4. **Binary detection**: Files with more than 10% null bytes in the first 8000 bytes are skipped (deleted files are exempt)

The `FileStager.evaluateFiles()` method returns a `StagingResult` that classifies each changed file as "staged" (passed all guardrails) or "skipped" (failed a guardrail, with a reason string).

---

## Concurrency Model

The auto-resolve system enforces a one-job-per-branch concurrency model to prevent conflicting changes.

### One Auto-Resolve Per Branch

Only one auto-resolve job can be active for a given branch at any time. This is enforced at multiple levels:

**At the controller level:** The `SlackNotifier` tracks active jobs per workstream. When a new submission arrives for a branch that already has an active job, the controller can either queue it or reject it depending on configuration.

**At the git level:** The `prepareWorkingDirectory()` method in `GitManagedJob` ensures that the working directory is in a clean, up-to-date state before the agent starts work. If a previous job left uncommitted changes, they are discarded (with a warning log):

```java
private void prepareWorkingDirectory() {
    // 1. Check for uncommitted changes
    List<String> dirtyFiles = checkForUncommittedChanges();
    if (!dirtyFiles.isEmpty()) {
        warn("Uncommitted changes found: " + fileList + " -- resetting");
        executeGit("checkout", ".");
        executeGit("clean", "-fd");
    }

    // 2. Fetch latest from origin
    executeGit("fetch", "origin");

    // 3. Checkout target branch
    ensureOnTargetBranch();

    // 4. Pull latest (fast-forward only, reset if diverged)
    // 5. Synchronize with base branch
}
```

### Base Branch Synchronization

Before each job starts, the `synchronizeWithBaseBranch()` method merges the latest remote base branch into the working branch. This ensures the agent works on code that includes all recent changes to the base branch, reducing merge conflicts at PR time.

If the merge produces conflicts, they are recorded and the merge is aborted. The agent's prompt is augmented with conflict resolution instructions, and the agent is expected to resolve the conflicts as part of its work.

### Push Semantics

The job uses an explicit refspec when pushing: `targetBranch:targetBranch`. This ensures the push always targets the correct remote branch regardless of any inherited upstream tracking configuration. The `-u` flag sets the upstream for subsequent operations.

---

## Test File Protection

Test file protection is a multi-layer defense against agents hiding test failures by modifying tests instead of fixing production code.

### Layer 1: Prompt Instructions

When `protectTestFiles` is enabled, the instruction prompt includes a "Test Integrity Policy" section:

```
## Test Integrity Policy
You MUST NOT modify test files that exist on the base branch (master).
Fix the production code instead. Tests you introduced on this branch
may be modified. The commit harness will reject changes to protected
test files.
```

### Layer 2: File Staging Protection

During file staging, the `FileStager` (and the equivalent logic in `GitManagedJob.stageFiles()`) checks whether each changed file:
1. Matches a protected path pattern (`**/src/test/**`, `**/src/it/**`, `.github/workflows/**`, `.github/actions/**`)
2. Exists on the base branch (checked via `git cat-file -e origin/<baseBranch>:<file>`)

If both conditions are true, the file is blocked from staging with the reason "protected - exists on base branch". Branch-new test files (files that do not exist on the base branch) are allowed through.

The base branch existence check fails safe: if the `git cat-file` command errors out for any reason, the file is treated as protected (existing on the base branch), preventing accidental modifications.

### Layer 3: detect-test-hiding.sh Validation

Even if file staging protection is bypassed (e.g., through test files that pass the pattern check), the `validateChanges()` method runs `detect-test-hiding.sh` as a final audit. This script performs a diff-level analysis to detect semantic test hiding patterns that a simple file-level check would miss.

### Layer 4: CI Pipeline Detection

The CI pipeline itself can also run `detect-test-hiding.sh` as a separate step, providing an additional check that runs on the pipeline's infrastructure rather than the agent's. This is the ultimate backstop because it runs in an environment the agent cannot influence.

### Flow Summary

```
CI detects failure
    |
    v
Submit auto-resolve job (protectTestFiles=true)
    |
    v
Agent receives job with test integrity prompt
    |
    v
Agent fixes production code (not tests)
    |
    v
File staging blocks protected test files
    |
    v
detect-test-hiding.sh validates the diff
    |
    v
Changes committed and pushed
    |
    v
CI re-runs and validates the fix
    |
    v
CI also runs detect-test-hiding.sh (backstop)
```

---

## Surefire Report Parsing

When test failures are the trigger for auto-resolve, the CI pipeline typically includes Surefire XML report data in the prompt. Surefire reports are Maven's standard output format for test results, stored as XML files in `target/surefire-reports/`.

### Report Structure

Each test class produces a Surefire XML file with this structure:

```xml
<testsuite name="org.example.MyTest" tests="5" failures="1" errors="0" skipped="0">
  <testcase name="testMethodA" classname="org.example.MyTest" time="0.123"/>
  <testcase name="testMethodB" classname="org.example.MyTest" time="0.456">
    <failure type="junit.framework.AssertionError" message="expected 42 but was 0">
      Stack trace here...
    </failure>
  </testcase>
</testsuite>
```

### Information Extracted for Prompts

The CI pipeline extracts the following from Surefire reports for inclusion in the auto-resolve prompt:

- **Test class name**: The fully-qualified class name (e.g., `org.almostrealism.ml.OobleckComponentTests`)
- **Failing method name**: The specific `@Test` method that failed
- **Failure message**: The assertion error message or exception message
- **Stack trace**: Truncated stack trace showing where the failure occurred
- **Test count summary**: Total tests, passed, failed, errored, and skipped counts

### Prompt Format for Test Failures

A typical auto-resolve prompt for test failures includes:

```
The CI pipeline on branch feature/my-work failed with test failures.

Module: ml
Test class: org.almostrealism.ml.OobleckComponentTests
Failing tests:
  - testDecoderBlock: AssertionError: expected shape [1, 512] but was [1, 256]
  - testEncoderBlock: NullPointerException at OobleckEncoder.java:142

Recent commits on this branch:
  abc1234 - Added new decoder implementation
  def5678 - Updated encoder configuration

Please investigate these test failures and fix the production code.
Do not modify the test files.
```

---

## End-to-End Flow Diagram

The complete auto-resolve cycle, from CI failure to resolution, follows this sequence:

```
1. Developer pushes to feature/my-work
2. CI pipeline triggers on push
3. Maven build and tests run
4. Test failures detected in surefire reports
5. CI failure handler collects:
   - Branch name
   - Failing test names and messages
   - Surefire XML content
   - Recent commit history
6. CI POSTs to controller:
   POST /api/workstreams/{ws}/submit
   { "prompt": "...", "targetBranch": "feature/my-work", "protectTestFiles": true }
7. Controller resolves workstream by branch match
8. Controller creates ClaudeCodeJob.Factory with:
   - Prompt (failure description)
   - Target branch
   - Workstream configuration (tools, budget, turns)
   - MCP server configuration
   - Test file protection enabled
9. Factory is dispatched to available agent
10. Agent receives and deserializes the factory
11. Factory.nextJob() creates ClaudeCodeJob
12. Job.run() executes:
    a. resolveAndCloneRepository() - Clone/update the repo
    b. prepareWorkingDirectory() - Fetch, checkout, pull, sync with base
    c. doWork() - Execute Claude Code with instruction prompt
    d. validateChanges() - Run detect-test-hiding.sh
    e. handleGitOperations() - Stage, commit, push
    f. fireJobCompleted() - POST status event to controller
13. Controller receives completion event
14. SlackNotifier sends completion message to Slack
15. Push triggers new CI run
16. CI validates the fix
```

Each step in this flow has error handling and logging. Failures at any stage are reported back to the controller via the status event mechanism, and Slack notifications keep the team informed of progress and issues.
