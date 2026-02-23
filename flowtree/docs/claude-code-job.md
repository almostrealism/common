# ClaudeCodeJob Reference

This document is the comprehensive reference for `ClaudeCodeJob` and its supporting classes in the `io.flowtree.jobs` package. It covers the class API, execution flow, prompt building, MCP configuration assembly, tool downloading, tool discovery, output parsing, wire serialization, and command-line arguments.

---

## Table of Contents

1. [Class Overview](#class-overview)
2. [Execution Flow](#execution-flow)
3. [InstructionPromptBuilder](#instructionpromptbuilder)
4. [McpConfigBuilder and Tool Configuration](#mcpconfigbuilder-and-tool-configuration)
5. [ManagedToolsDownloader and Pushed Tool Lifecycle](#managedtoolsdownloader-and-pushed-tool-lifecycle)
6. [MCP Tool Discovery](#mcp-tool-discovery)
7. [Output Metric Extraction](#output-metric-extraction)
8. [Factory Serialization Format](#factory-serialization-format)
9. [Command-Line Arguments](#command-line-arguments)

---

## Class Overview

`ClaudeCodeJob` is a `Job` implementation that executes a Claude Code prompt in a headless, non-interactive process. It extends `GitManagedJob`, which provides automatic git branch management, staging, committing, and pushing after the Claude Code process completes. Together, these two classes form the backbone of the FlowTree coding agent system: the controller dispatches `ClaudeCodeJob.Factory` instances to connected agent nodes, each agent runs Claude Code in a subprocess, and the harness handles all git operations and status reporting on behalf of the agent.

### Inheritance Hierarchy

```
Job (interface, io.flowtree.job)
  ^
  |
GitManagedJob (abstract, io.flowtree.jobs)
  ^
  |
ClaudeCodeJob (io.flowtree.jobs)
```

`GitManagedJob` provides:
- Working directory preparation (fetch, checkout, pull, base-branch synchronization, merge conflict detection)
- Repository cloning when a `repoUrl` is specified but no local clone exists
- File staging with guardrails (size limits, binary detection, pattern exclusion, test file protection)
- Committing with configurable git identity
- Pushing with explicit refspecs
- Pull request URL detection via the GitHub REST API
- Status event posting (JSON over HTTP to the workstream URL)

`ClaudeCodeJob` adds:
- Instruction prompt construction (wrapping the user prompt with operational context)
- MCP configuration building and tool downloading
- Claude Code subprocess invocation with structured output parsing
- `ClaudeCodeJobEvent` creation with timing, cost, and session detail fields

### Public API

#### Constructors

| Constructor | Description |
|---|---|
| `ClaudeCodeJob()` | Default constructor for deserialization. Sets `allowedTools` to `DEFAULT_TOOLS`, `maxTurns` to 50, and `maxBudgetUsd` to 10.0. |
| `ClaudeCodeJob(String taskId, String prompt)` | Creates a job with the given task ID and prompt. |
| `ClaudeCodeJob(String taskId, String prompt, String allowedTools)` | Creates a job with a custom allowed-tools list. |

#### Configuration Setters and Getters

| Method | Type | Default | Description |
|---|---|---|---|
| `getPrompt()` / `setPrompt(String)` | `String` | `null` | The prompt to send to Claude Code. |
| `getAllowedTools()` / `setAllowedTools(String)` | `String` | `"Read,Edit,Write,Bash,Glob,Grep"` | Comma-separated list of base tool names. MCP tools are appended automatically. |
| `getMaxTurns()` / `setMaxTurns(int)` | `int` | `50` | Maximum number of agentic turns before Claude Code stops. |
| `getMaxBudgetUsd()` / `setMaxBudgetUsd(double)` | `double` | `10.0` | Maximum spend in USD. Set to 0 to disable the budget cap. |
| `getCentralizedMcpConfig()` / `setCentralizedMcpConfig(String)` | `String` | `null` | JSON mapping centralized MCP server names to HTTP URLs and tool lists. |
| `getPushedToolsConfig()` / `setPushedToolsConfig(String)` | `String` | `null` | JSON mapping pushed tool server names to download URLs, tool lists, and optional env vars. |
| `getWorkstreamEnv()` / `setWorkstreamEnv(Map)` | `Map<String,String>` | `null` | Per-workstream environment variables that override global pushed-tool env vars. |
| `getPlanningDocument()` / `setPlanningDocument(String)` | `String` | `null` | Path (relative to working directory) to a planning document the agent must read. |

All `GitManagedJob` setters are also available: `setTargetBranch`, `setBaseBranch`, `setWorkingDirectory`, `setRepoUrl`, `setDefaultWorkspacePath`, `setPushToOrigin`, `setGitUserName`, `setGitUserEmail`, `setWorkstreamUrl`, `setProtectTestFiles`, and `setMaxFileSizeBytes`. These inherited setters control the git harness behavior and status event delivery. When `targetBranch` is null, git operations (staging, committing, pushing) are skipped entirely, and the job simply runs Claude Code and reports its output. When `pushToOrigin` is false, the commit is created locally but not pushed to the remote.

#### Post-Execution Getters

| Method | Type | Description |
|---|---|---|
| `getSessionId()` | `String` | The Claude Code session ID from the NDJSON result object. |
| `getOutput()` | `String` | The full stdout/stderr captured from the Claude Code process. |
| `getExitCode()` | `int` | The process exit code (0 = success). |

Additional timing and session detail fields are extracted internally and forwarded to the `ClaudeCodeJobEvent` (see [Output Metric Extraction](#output-metric-extraction)).

### Constants

| Constant | Value | Description |
|---|---|---|
| `PROMPT_SEPARATOR` | `";;PROMPT;;"` | Delimiter used to join multiple prompts into a single encoded string in the Factory. This delimiter was chosen to be unlikely to appear in natural language prompts. |
| `DEFAULT_TOOLS` | `"Read,Edit,Write,Bash,Glob,Grep"` | The base set of tools available to every Claude Code session. These are Claude Code's built-in file and shell tools. MCP tools are appended to this base set by `McpConfigBuilder.buildAllowedTools()`. |

The `DEFAULT_TOOLS` constant defines the minimum tool set that every Claude Code session receives. These six tools provide the agent with basic file system access (Read, Write, Edit, Glob, Grep) and shell command execution (Bash). Without these, the agent could not perform any useful work. Additional tools are appended by the MCP config builder, including MCP server tools (prefixed with `mcp__<serverName>__`) and any custom tools defined in the workstream configuration.

### Internal State Fields

In addition to the configuration fields exposed via getters, `ClaudeCodeJob` maintains several internal state fields that are populated during execution and forwarded to the completion event:

| Field | Type | Description |
|---|---|---|
| `sessionId` | `String` | The Claude Code session identifier, extracted from the NDJSON result object. |
| `output` | `String` | The full stdout/stderr captured from the Claude Code process. |
| `exitCode` | `int` | The process exit code (0 = success, non-zero = error). |
| `durationMs` | `long` | Total wall-clock duration reported by Claude Code, in milliseconds. |
| `durationApiMs` | `long` | Time spent in API calls, in milliseconds. |
| `costUsd` | `double` | Total cost of the Claude Code session in USD. |
| `numTurns` | `int` | Number of agentic turns (tool-use cycles) completed. |
| `subtype` | `String` | The session stop reason (e.g., `"success"`, `"error_max_turns"`). |
| `isError` | `boolean` | Whether Claude Code flagged the session as an error. |
| `permissionDenials` | `int` | Number of tool permission denials encountered. |
| `deniedToolNames` | `List<String>` | Names of tools that were denied during execution. |

These fields are extracted from the NDJSON output by `extractOutputMetrics()` and then forwarded to `ClaudeCodeJobEvent` via `populateEventDetails()`.

### Collaborating Objects

`ClaudeCodeJob` instantiates two collaborating objects as final fields:

- `mcpConfigBuilder` (`McpConfigBuilder`): Constructs the `--mcp-config` JSON and `--allowedTools` string. Configured via `configureMcpBuilder()` before each execution.
- `toolsDownloader` (`ManagedToolsDownloader`): Downloads pushed tool server files from the controller. Initialized with a reference to `mcpConfigBuilder` for config parsing.
- `outputMapper` (`ObjectMapper`): A static Jackson mapper shared by all instances for NDJSON parsing and JSON serialization.

### ClaudeCodeJobOutput

`ClaudeCodeJob.ClaudeCodeJobOutput` is a record-style class extending `JobOutput` that captures the prompt, session ID, output text, and exit code. It is passed to the `outputConsumer` callback (if one is registered) after the Claude Code process exits. The `outputConsumer` is set by the FlowTree job dispatch system and is typically used for logging or downstream processing.

```java
public static class ClaudeCodeJobOutput extends JobOutput {
    public String getPrompt();
    public String getSessionId();
    public int getExitCode();
}
```

The constructor takes five parameters: `taskId`, `prompt`, `output`, `sessionId`, and `exitCode`. The `output` text is stored in the superclass `JobOutput` and can be retrieved via `getOutput()`. The `toString()` method returns a compact representation: `ClaudeCodeJobOutput{taskId=..., exitCode=..., sessionId=...}`.

### ClaudeCodeJob.Factory

The `Factory` inner class extends `AbstractJobFactory` and is the primary entry point for submitting Claude Code work to the FlowTree system. The controller creates a Factory with one or more prompts, configures it with tools, branch, MCP config, and workstream settings, then sends it to a connected agent node. The agent calls `nextJob()` repeatedly until all prompts have been dispatched.

#### Factory Constructors

| Constructor | Description |
|---|---|
| `Factory()` | Default constructor for deserialization. Generates a persistent task ID. |
| `Factory(String... prompts)` | Creates a factory with one or more prompts. |
| `Factory(List<String> prompts)` | Creates a factory from an existing list. |

#### Factory Configuration

All job-level configuration setters are mirrored on the Factory: `setAllowedTools`, `setMaxTurns`, `setMaxBudgetUsd`, `setTargetBranch`, `setBaseBranch`, `setWorkingDirectory`, `setRepoUrl`, `setDefaultWorkspacePath`, `setPushToOrigin`, `setGitUserName`, `setGitUserEmail`, `setWorkstreamUrl`, `setCentralizedMcpConfig`, `setPushedToolsConfig`, `setWorkstreamEnv`, `setPlanningDocument`, `setProtectTestFiles`.

Each setter also calls `set(key, value)` to persist the value in the Factory's property map, ensuring survival across wire serialization.

#### Factory Task ID Persistence

The Factory constructor generates a task ID via `KeyUtils.generateKey()` and stores it in the property map under the key `factoryTaskId`. The `getTaskId()` method checks the property map first, falling back to the superclass field. This is necessary because `AbstractJobFactory.encode()` does not serialize the `taskId` field, so without explicit persistence the deserialized factory would get a new random ID on the receiving node.

#### nextJob() and Prompt Distribution

`nextJob()` maintains an internal `index` counter and returns one `ClaudeCodeJob` per call, configured with all the Factory's settings. When `index >= prompts.size()`, it returns `null` to signal that no more work is available. The `getCompleteness()` method returns `index / (double) prompts.size()`, enabling progress tracking on the controller side.

The `nextJob()` method transfers every Factory configuration to the newly created job. This includes all git settings (target branch, base branch, working directory, repo URL, default workspace path, push-to-origin, git identity), all MCP settings (centralized config, pushed tools config, workstream env), workstream URL, planning document, and test file protection. If a setting is null on the Factory, it is simply not set on the job. The `createJob(String data)` method returns `null` because it is not used; jobs are exclusively created via `nextJob()`.

#### Factory Usage Examples

**Simple single-prompt submission:**
```java
ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory("Fix the bug in Parser.java");
factory.setTargetBranch("feature/fix-parser");
factory.setMaxBudgetUsd(5.0);
server.sendTask(factory);
```

**Multi-prompt batch with shared configuration:**
```java
ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(
    "Add unit tests for UserService",
    "Improve error handling in ApiController",
    "Update documentation for the CLI module"
);
factory.setAllowedTools("Read,Edit,Write,Bash,Glob,Grep");
factory.setTargetBranch("feature/improvements");
factory.setMaxTurns(30);
factory.setMaxBudgetUsd(8.0);
factory.setGitUserName("Claude Agent");
factory.setGitUserEmail("claude@example.com");
server.sendTask(factory);
```

Each prompt becomes a separate `ClaudeCodeJob` dispatched sequentially. When an agent finishes one job and becomes idle, the next job from the same factory is dispatched.

**Programmatic submission via the API endpoint:**

The `FlowTreeApiEndpoint.handleSubmit()` method creates a Factory internally from the HTTP request body, configuring it with workstream defaults:

```json
POST /api/workstreams/ws-common/submit
{
  "prompt": "Review PR feedback and address comments",
  "targetBranch": "feature/my-work",
  "maxTurns": 25,
  "maxBudgetUsd": 3.0
}
```

The endpoint resolves the workstream, applies its defaults (allowed tools, MCP config, env vars, git identity), and round-robins the Factory to a connected agent.

---

## Execution Flow

The execution of a `ClaudeCodeJob` follows a deterministic pipeline orchestrated by the `run()` method inherited from `GitManagedJob`. The flow has two major phases: pre-work preparation (handled by `GitManagedJob`) and Claude Code execution (handled by `ClaudeCodeJob.doWork()`).

### Phase 1: Repository and Branch Preparation

This phase is handled entirely by `GitManagedJob.run()` before `doWork()` is called. All git operations use `runGit()`, a utility method that executes git commands via `ProcessBuilder`, captures output, and throws `IOException` on non-zero exit codes. The method logs each command and its output for debugging.

1. **Repository resolution and cloning** -- If `repoUrl` is set but no working directory exists, the repo is cloned into a resolved workspace path. The resolution follows a priority chain: if `defaultWorkspacePath` is set, it is used directly; otherwise, if `/workspace/project` exists (typical in container environments), it is used; as a final fallback, a directory is created under `/tmp/flowtree-workspaces/` using the repository name extracted from the URL. The clone uses `git clone <repoUrl> <path>` and is logged with the destination path for operator visibility.

2. **Uncommitted change detection** -- The working directory is checked for uncommitted changes (excluding ignored patterns like `claude-output/**`, `.claude/**`, `commit.txt`). If found, they are discarded with `git checkout .` and `git clean -fd`, since agent workers should never have manual edits.

3. **Fetch from origin** -- `git fetch origin` brings remote refs up to date.

4. **Branch checkout** -- The target branch is checked out if it exists locally or on the remote. If a local branch with the target name exists, `git checkout <targetBranch>` is used. If the branch exists only on the remote (`origin/<targetBranch>`), `git checkout -b <targetBranch> origin/<targetBranch>` creates a local tracking branch. If the branch does not exist anywhere, `git checkout -b <targetBranch> origin/<baseBranch> --no-track` creates a new branch from the base branch tip without establishing a tracking relationship (the tracking is established later during push). When no target branch is specified at all, the current branch remains checked out.

5. **Sync with remote** -- If the target branch exists on the remote, `git pull --ff-only origin <targetBranch>` attempts a fast-forward merge of any new remote commits. If the fast-forward fails (meaning the local branch has diverged from the remote), `git reset --hard origin/<targetBranch>` is used to force-sync the local state to the remote. This force-sync is safe because agent workers never have meaningful local-only work; any work they produce is pushed immediately after the job completes.

6. **Base branch synchronization** -- If the target branch and base branch differ, `git merge origin/<baseBranch>` is attempted. On success, the merge commit is created automatically. On conflict, the merge is aborted and the conflict details are recorded for inclusion in the agent prompt. Conflict detection works by checking `git status --porcelain` for lines with conflict markers (`UU`, `AA`, `DD`, `AU`, `UA`, `DU`, `UD`). After recording the file list, the merge is aborted with `git merge --abort` so the working directory is clean when the agent starts. The agent's instruction prompt includes the conflict file list and step-by-step resolution instructions.

### Error Handling in Phase 1

If any step in Phase 1 throws an exception, `GitManagedJob.run()` catches it, sets the error, and proceeds directly to event firing (Phase 3 step 6). This means the Claude Code subprocess is never started if the repository preparation fails. Common failure modes include:

- Clone failure (bad credentials, network issues, invalid repo URL)
- Fetch failure (network issues, authentication problems)
- Branch checkout failure (corrupted local state)
- Dirty working directory that cannot be cleaned

Each of these results in a `FAILED` status event being posted to the workstream URL, with the exception message as the error description.

### Phase 2: Claude Code Execution (doWork)

1. **Tool download** -- `toolsDownloader.ensurePushedTools(pushedToolsConfig)` checks each pushed tool server entry. For any that do not already have a local `server.py` file at `~/.flowtree/tools/mcp/<name>/server.py`, the file is downloaded from the controller via HTTP GET. The `0.0.0.0` placeholder in download URLs is replaced with the `FLOWTREE_ROOT_HOST` environment variable value.

2. **Stale commit.txt removal** -- Any `commit.txt` left by a previous run is deleted to prevent stale commit messages from being reused.

3. **Output directory creation** -- A `claude-output/` directory is created (if absent) for saving raw output files.

4. **MCP config building** -- `configureMcpBuilder()` transfers the job's config state to the `McpConfigBuilder`, and `mcpConfigBuilder.buildMcpConfig()` produces the JSON for `--mcp-config`. `mcpConfigBuilder.buildAllowedTools(allowedTools)` produces the comma-separated tools string for `--allowedTools`.

5. **Instruction prompt building** -- `buildInstructionPrompt()` wraps the user's prompt with operational context (Slack instructions, git instructions, merge conflict resolution, branch awareness, budget/turn limits, planning document reference, and more). See [InstructionPromptBuilder](#instructionpromptbuilder) for full details.

6. **MCP tool file verification** -- `toolsDownloader.verifyMcpToolFiles(workDir)` checks that `tools/mcp/slack/server.py` and `tools/mcp/github/server.py` exist in the working directory and logs their modification times. Missing files produce warnings.

7. **Command construction** -- The full `claude` command is assembled. See [Command-Line Arguments](#command-line-arguments).

8. **Process execution** -- A `ProcessBuilder` runs the command. The working directory is set from the job config. `AR_WORKSTREAM_URL` is set in the process environment from the resolved workstream URL (with `0.0.0.0` replaced by `FLOWTREE_ROOT_HOST`). Stdin is redirected from `/dev/null` and stderr is merged with stdout. The process output is read line by line and logged.

9. **Output capture and saving** -- The full output is captured in a `StringBuilder` that accumulates every line read from the process. After the process exits, the complete output is written to `claude-output/<random-key>.json` where the random key is generated by `KeyUtils.generateKey()`. The `claude-output/` directory is created in step 3 and is included in `GitManagedJob`'s default excluded patterns, so output files are never accidentally committed to the repository.

10. **Metric extraction** -- `extractOutputMetrics(output)` parses the NDJSON output to extract session ID, timing, cost, stop reason, and permission denial information. This method locates the final `type:result` object using `JsonFieldExtractor.extractLastJsonObject()`, then parses it with Jackson `ObjectMapper` to extract individual fields. See [Output Metric Extraction](#output-metric-extraction) for the complete list of extracted fields and their JSON paths.

11. **Output consumer callback** -- If an `outputConsumer` functional interface is registered on the job, a `ClaudeCodeJobOutput` record is constructed and delivered via `outputConsumer.accept(output)`. The consumer receives the task ID, prompt text, full output, session ID, and exit code. This callback mechanism is used by the FlowTree job dispatch system to route results back to the controller for aggregation and logging. If no consumer is registered (which is common in standalone testing), this step is silently skipped.

### Error Handling in Phase 2

The entire `doWork()` body is wrapped in a try-catch for `IOException` and `InterruptedException`. If either occurs, the error is logged, `exitCode` is set to `-1`, and the method returns. The exception propagates back to `GitManagedJob.run()`, which will skip git operations and fire a failure event.

If the Claude Code process exits with a non-zero code, this is NOT treated as a Java exception. The process output is still saved, metrics are still extracted, and git operations still proceed. Non-zero exit codes are captured in the `exitCode` field and forwarded to the completion event, where the `SlackNotifier` can report them. This design reflects the fact that non-zero exit codes from Claude Code can indicate turn/budget exhaustion or a soft error, not necessarily a hard failure that should prevent committing the work done so far.

### Phase 3: Post-Work Git Operations

After `doWork()` returns, `GitManagedJob.run()` handles:

1. **Change validation** -- `validateChanges()` is called. `ClaudeCodeJob` overrides this to run `detect-test-hiding.sh` when test file protection is enabled. Exit code 2 means violations were found and the commit is aborted.

2. **File discovery and staging** -- `git status --porcelain` lists all changed files. The output is parsed line by line: lines starting with `?? ` indicate untracked files, and lines starting with ` M `, `M `, `A `, `D `, etc. indicate modified, added, or deleted tracked files. Each file passes through a multi-layer guardrail pipeline: excluded pattern check (glob matching against a comprehensive exclusion list), protected test file check (files on the base branch cannot be modified when `protectTestFiles` is true), file size check (default 1MB max, configurable via `maxFileSizeBytes`), and binary detection (scanning the first 8000 bytes for null content, with a 10% threshold). Files that pass all guardrails are staged with `git add <file>`. Files that fail any guardrail are added to the `skippedFiles` list with a parenthetical reason suffix for inclusion in the completion event.

3. **Commit** -- The commit message is determined by `getCommitMessage()` (see below). The commit command uses explicit git identity flags (`-c user.name=...` and `-c user.email=...`) to override any global git config on the agent machine. This ensures consistent attribution across all agents. If `git commit` returns a non-zero exit code (e.g., "nothing to commit"), the error is logged but does not throw an exception -- the job continues to event firing with no commit hash.

4. **Push** -- `git push -u origin <branch>:<branch>` with an explicit refspec. The `-u` flag sets up tracking so subsequent pushes can use `git push` without arguments. The explicit `<branch>:<branch>` refspec ensures the remote branch name matches the local one. Push is only attempted when `pushToOrigin` is true (the default) and there were files staged and committed. Push failures are logged but do not prevent event firing.

5. **PR detection** -- After a successful push, the system checks for an existing open pull request on the target branch. This is done via the GitHub REST API (or through the controller's GitHub API proxy, depending on configuration). The query searches for PRs where the head branch matches the target branch. If an open PR is found, its URL is attached to the completion event. If no PR exists, the field remains null. This detection is best-effort: API failures are logged but do not prevent event firing.

6. **Event firing** -- A `ClaudeCodeJobEvent` is created, populated with git info, timing info, session details, and PR URL, then POSTed as JSON to the workstream URL for Slack notification.

### Commit Message Generation

The commit message is determined by `getCommitMessage()`, which implements a two-tier strategy:

1. **Agent-written message**: If the agent wrote a `commit.txt` file in the working directory root, its contents (trimmed) are used as the commit message. This allows the agent to craft a meaningful description of its changes. After the commit succeeds, `commit.txt` is deleted to prevent reuse by subsequent runs.

2. **Auto-generated message**: If no `commit.txt` exists or it is empty, a commit message is generated from the prompt:
   ```
   Claude Code: <prompt truncated to 72 chars>

   Prompt: <full prompt>
   Session: <session ID>
   Exit code: <exit code>
   ```

The agent's instruction prompt tells it about the `commit.txt` mechanism (in the "Git Commit Instructions" section), so agents that are configured with a target branch know they can control the commit message.

### Validation: detect-test-hiding.sh

When `protectTestFiles` is enabled, `validateChanges()` runs the `detect-test-hiding.sh` script (located at `tools/ci/detect-test-hiding.sh` relative to the working directory). This script audits the diff against `origin/<baseBranch>` for changes that might "hide" test failures, such as:

- Removing or commenting out existing test methods
- Changing assertions to make failing tests pass trivially
- Deleting test files that exist on the base branch

The script's exit code determines the action:
- Exit code 0: No violations, commit proceeds.
- Exit code 2: Violations found. The commit is aborted, a warning is logged, and the job continues to event firing (which will report the validation failure).
- Exit code 1: Script error (e.g., bad arguments). Logged as a warning but does not block the commit (defense against script bugs).

If the script does not exist at the expected path, validation is silently skipped.

### GitManagedJob Guardrails

The file staging process applies multiple layers of filtering to prevent unintended or dangerous files from being committed:

**Excluded Patterns** -- A comprehensive set of glob patterns covering secrets (`.env`, `*.pem`, `*.key`), build outputs (`target/**`, `build/**`), IDE files (`.idea/**`, `.vscode/**`), binary files (`*.exe`, `*.jar`, `*.png`), databases (`*.db`, `*.sqlite`), logs (`*.log`), AR-specific outputs (`Extensions/**`, `*.cl`, `*.metal`), and agent outputs (`claude-output/**`, `commit.txt`, `.claude/**`).

**Protected Test Files** -- When `protectTestFiles` is true, files matching `**/src/test/**`, `**/src/it/**`, `.github/workflows/**`, or `.github/actions/**` are checked against the base branch. If the file exists on `origin/<baseBranch>`, it is blocked from staging. Files that are new to the branch (not present on the base) are allowed. This check uses `git cat-file -e` for existence testing and fails safe (blocks on error).

**File Size Limit** -- Files larger than `maxFileSizeBytes` (default: 1MB) are skipped. This prevents accidentally committing large generated files or data dumps.

**Binary Detection** -- The first 8000 bytes of each file are scanned for null bytes. If more than 10% are null, the file is classified as binary and skipped.

---

## InstructionPromptBuilder

The `InstructionPromptBuilder` class extracts the prompt-assembly logic from `ClaudeCodeJob` into a standalone, reusable builder. It constructs the full instruction prompt that wraps the user's request with operational context for autonomous coding agent execution. `ClaudeCodeJob` currently uses an inline `buildInstructionPrompt()` method with equivalent logic; the builder class exists for use by other systems that need to construct the same prompt format.

### Section Assembly Order

All setters support chaining. The `build()` method assembles sections in this fixed order:

1. **Opening paragraph** -- Always present. Establishes that the agent is autonomous with no TTY and no interactive session.

2. **Slack Communication** -- Present only when `workstreamUrl` is non-null and non-empty. Instructs the agent to send Slack status updates at task start, milestones, findings, and completion. Includes the directive to continue working after sending (no blocking on replies).

3. **Permission Denials** -- Present only when `workstreamUrl` is set. Instructs the agent to immediately report any tool permission denials via Slack, including the tool name, the intended action, and the error message.

4. **Non-Code Requests** -- Present only when `workstreamUrl` is set. Clarifies that not every task requires code changes; answering questions, running commands, or checking status via Slack is a valid response.

5. **Justifying No Code Changes** -- Present only when `workstreamUrl` is set. If the agent finishes without modifying any git-tracked files, it must send a Slack message explaining why. This does not apply if the agent already fully addressed the request through earlier messages.

6. **GitHub PR Instructions** -- Present when `gitHubMcpEnabled` is true (in the builder) or always present (in `ClaudeCodeJob`'s inline version, since ar-github is always enabled). Lists the available GitHub MCP tools: `github_pr_find`, `github_pr_review_comments`, `github_pr_conversation`, `github_pr_reply`.

7. **Test Integrity Policy** -- Present only when `protectTestFiles` is true. Tells the agent not to modify test files that exist on the base branch and to fix production code instead. Notes that tests introduced on the current branch may be modified, and that the commit harness will reject changes to protected test files.

8. **Git Commit Instructions** -- Always present, but with two variants:
   - When `targetBranch` is set: Tells the agent not to make git commits and that the harness will commit. If the agent wants to control the commit message, it should write to `commit.txt`.
   - When `targetBranch` is not set: Same prohibition on commits, without the `commit.txt` mention.

9. **Merge Conflicts** -- Present only when `hasMergeConflicts` is true. Tells the agent that `origin/<baseBranch>` has diverged from the working branch, lists the conflicted files, and provides step-by-step merge resolution instructions (run `git merge`, resolve conflicts, `git add`, `git commit --no-edit`, then proceed).

10. **Branch Context** -- Always present. Explains that the environment is sandboxed and that user references to branch names always mean `origin/<branch>`. Provides examples.

11. **Working Directory and Branch Info** -- Always present. Reports the working directory path and (if set) the target branch.

12. **Branch Awareness and Continuity** -- Present only when `targetBranch` is set. This is a substantial section with three subsections:

    - **Catching Up on Prior Work** -- Instructs the agent to use `branch_catchup` before making changes. Shows the exact MCP tool call with the branch name interpolated.

    - **Recording Your Work** -- Instructs the agent to store memories with branch context via `mcp__ar-consultant__remember`.

    - **CRITICAL: Avoid Add/Revert Loops** -- Describes the common failure mode where agents add changes, CI fails, agents revert, next session re-adds, etc. Provides explicit "DO" and "DO NOT" guidance: never simply revert, investigate the actual failure, check `branch_catchup` for prior loop occurrences, store memories about CI failures.

13. **Budget and Turn Limits** -- Present when `maxBudgetUsd > 0` or `maxTurns > 0`. Tells the agent its budget and turn cap so it can pace itself.

14. **Task ID and Workstream URL** -- Present when either is set. Provides the task ID and workstream URL as metadata for the agent.

15. **Planning Document** -- Present only when `planningDocument` is non-null and non-empty. Instructs the agent to read the specified file before starting work. Notes that the user's request is a sub-task of the broader goal and that prior session work supporting the planning document should not be reverted.

16. **User Request Markers** -- Always the last section. Wraps the raw user prompt between `--- BEGIN USER REQUEST ---` and `--- END USER REQUEST ---` markers.

### Builder Setters

| Setter | Type | Condition for Inclusion |
|---|---|---|
| `setPrompt(String)` | `String` | Required. Wrapped in the USER REQUEST markers. |
| `setWorkstreamUrl(String)` | `String` | Non-null/non-empty enables Slack sections (2-5). |
| `setGitHubMcpEnabled(boolean)` | `boolean` | `true` enables section 6. |
| `setProtectTestFiles(boolean)` | `boolean` | `true` enables section 7. |
| `setBaseBranch(String)` | `String` | Used in sections 7 and 9. |
| `setTargetBranch(String)` | `String` | Non-null/non-empty enables sections 8 (variant), 11, 12. |
| `setWorkingDirectory(String)` | `String` | Displayed in section 11. Falls back to `user.dir`. |
| `setHasMergeConflicts(boolean)` | `boolean` | `true` enables section 9. |
| `setMergeConflictFiles(List<String>)` | `List<String>` | Listed in section 9. |
| `setMaxBudgetUsd(double)` | `double` | `> 0` enables budget portion of section 13. |
| `setMaxTurns(int)` | `int` | `> 0` enables turn limit portion of section 13. |
| `setTaskId(String)` | `String` | Non-null enables section 14. |
| `setPlanningDocument(String)` | `String` | Non-null/non-empty enables section 15. |

### Relationship Between Builder and Inline Method

`ClaudeCodeJob` contains a private `buildInstructionPrompt()` method that uses the job's own field values directly rather than going through the builder. The builder class (`InstructionPromptBuilder`) is a standalone extraction of this same logic, using setters rather than direct field access. The two implementations produce identical output given the same inputs.

In `ClaudeCodeJob`, the inline method reads from `getWorkstreamUrl()`, `isProtectTestFiles()`, `getBaseBranch()`, `getTargetBranch()`, `getWorkingDirectory()`, `hasMergeConflicts()`, `getMergeConflictFiles()`, `maxBudgetUsd`, `maxTurns`, `getTaskId()`, and `planningDocument`. The GitHub MCP instruction section is always included (unconditionally), since ar-github is always enabled for Claude Code jobs.

The builder class exists to enable prompt construction outside of `ClaudeCodeJob`, for example by testing utilities or alternative job types that need to produce the same prompt format.

### Section Interaction Examples

**Minimal prompt (no workstream, no git management):**
Produces only the opening paragraph, GitHub instructions, git commit prohibition (no `commit.txt` mention), branch context, working directory, and the user request. This is the shortest possible prompt.

**Full prompt (all sections enabled):**
When all features are active (workstream URL set, target branch set, merge conflicts present, planning document set, budget and turn limits configured), the prompt contains all 16 sections and can be several thousand characters long. The `--- BEGIN USER REQUEST ---` / `--- END USER REQUEST ---` markers ensure the agent can locate the actual user request regardless of the prompt length.

**Merge conflict scenario:**
When the base branch has diverged, the merge conflict section provides explicit step-by-step instructions. The agent is told to run `git merge origin/<baseBranch>`, resolve the listed files, stage them, and complete the merge before proceeding with any other work. The "Do NOT skip conflict resolution" directive is critical because the merge must be completed for subsequent commits to be clean.

---

## McpConfigBuilder and Tool Configuration

`McpConfigBuilder` is responsible for constructing two outputs consumed by the Claude Code command line:

1. The JSON string for the `--mcp-config` flag (a `{"mcpServers":{...}}` object)
2. The comma-separated allowed tools string for the `--allowedTools` flag

### Configuration Sources

MCP servers come from four distinct sources, processed in priority order:

#### 1. Centralized Servers (HTTP)

Centralized servers run on or near the controller and are accessed over HTTP. The controller maintains a registry of these servers and passes their configuration to jobs as a JSON string via `setCentralizedMcpConfig()`.

**Input format:**
```json
{
  "ar-slack": {
    "url": "http://0.0.0.0:8090/mcp",
    "tools": ["slack_send_message", "slack_get_stats"]
  },
  "ar-consultant": {
    "url": "http://0.0.0.0:8091/mcp",
    "tools": ["consult", "recall", "remember", "branch_catchup"]
  }
}
```

**Processing:** For each entry, `McpConfigBuilder` emits an HTTP-type MCP server entry. The `0.0.0.0` placeholder in URLs is replaced with the `FLOWTREE_ROOT_HOST` environment variable, which contains the controller's actual hostname or IP address as seen from agent containers.

**Output in --mcp-config:**
```json
{
  "mcpServers": {
    "ar-slack": {
      "type": "http",
      "url": "http://controller-host:8090/mcp"
    }
  }
}
```

**Output in --allowedTools:** For each server's tools list, entries are added in the format `mcp__<serverName>__<toolName>`.

#### 2. Pushed Tools (stdio, downloaded from controller)

Pushed tools are MCP server Python scripts that are downloaded from the controller and run locally on the agent via stdio. This approach is used when HTTP connectivity is unreliable or when the tool needs local filesystem access.

**Input format:**
```json
{
  "ar-memory": {
    "url": "http://0.0.0.0:7780/api/tools/ar-memory",
    "tools": ["memory_store", "memory_search", "memory_list"],
    "env": {
      "AR_MEMORY_DB": "/data/memories.db"
    }
  }
}
```

**Processing:** `ManagedToolsDownloader.ensurePushedTools()` downloads each server's `server.py` to `~/.flowtree/tools/mcp/<name>/server.py` if not already present. Then `McpConfigBuilder` emits a stdio entry pointing to that local file.

**Environment variable merging:** Each pushed tool can have a global `env` object in its config. Per-workstream env vars (from `setWorkstreamEnv()`) are merged on top, with workstream values taking precedence.

**Output in --mcp-config:**
```json
{
  "mcpServers": {
    "ar-memory": {
      "command": "python3",
      "args": ["/home/user/.flowtree/tools/mcp/ar-memory/server.py"],
      "env": {
        "AR_MEMORY_DB": "/data/memories.db",
        "AR_WORKSTREAM_ID": "ws-common"
      }
    }
  }
}
```

#### 3. Project Servers (stdio, from .mcp.json)

Project-level MCP servers are defined in the repository's `.mcp.json` file and optionally filtered by `.claude/settings.json`. These are servers bundled with the project (e.g., `ar-test-runner`, `ar-docs`, `ar-profile-analyzer`).

**Discovery:** `discoverProjectMcpServers()` reads `.mcp.json` from the working directory, parses each server's command and args, extracts the Python source file path (first arg), and cross-references with `.claude/settings.json`'s `enabledMcpjsonServers` array. Servers named `ar-github` and `ar-slack` are always skipped (handled separately). Servers that are already centralized or pushed are also skipped.

**Output in --mcp-config:** Emitted as stdio entries with `command: "python3"` and `args: ["<path>"]`.

**Tool name discovery for --allowedTools:** For each project server, `McpToolDiscovery.discoverToolNames()` scans the Python source file to extract tool names. See [MCP Tool Discovery](#mcp-tool-discovery).

#### 4. Fallback Servers (ar-github, ar-slack)

`ar-github` and `ar-slack` receive special treatment because they have conditional inclusion logic:

- **ar-github**: If not present in the centralized config or pushed tools, a stdio fallback entry is added pointing to `tools/mcp/github/server.py`. Its tools (`github_pr_find`, `github_pr_review_comments`, `github_pr_conversation`, `github_pr_reply`) are always added to the allowed tools list.

- **ar-slack**: If not present in the centralized config or pushed tools, a stdio fallback entry is added pointing to `tools/mcp/slack/server.py` -- but only when a workstream URL is configured. Its tool (`slack_send_message`) is added to the allowed tools list under the same condition.

### buildMcpConfig() Method

This method returns a JSON string with the structure `{"mcpServers":{...}}`. The construction uses Jackson `ObjectNode` and `ArrayNode` for all JSON assembly (no manual string concatenation). If serialization fails, it returns `{"mcpServers":{}}` as a safe fallback.

### buildAllowedTools(String baseTools) Method

This method takes the base tools string (e.g., `"Read,Edit,Write,Bash,Glob,Grep"`) and appends all MCP tool names:

1. Tools from centralized servers: `mcp__<serverName>__<toolName>` for each tool in each centralized server's tools list
2. Tools from pushed tools: same format
3. GitHub tools (unless centralized or pushed): the four `mcp__ar-github__*` tools
4. Slack tool (unless centralized or pushed, and only when workstream URL is set): `mcp__ar-slack__slack_send_message`
5. Tools from discovered project servers: for each server, `discoverToolNames()` is called on the Python source file and the resulting names are formatted as `mcp__<serverName>__<toolName>`

### parseCentralizedConfig() and parsePushedConfig()

Both methods parse their respective JSON strings using Jackson and return `Map<String, List<String>>` mapping server names to tool name lists. `parsePushedConfig()` also has an overload that accepts an explicit JSON string, allowing callers to parse without setting the field.

Both methods expect the same JSON structure: an object whose keys are server names and whose values are objects containing a `tools` array of strings. If the JSON is null, empty, or malformed, they return an empty map and log a warning. Invalid server entries (missing or non-array `tools` fields) are silently skipped.

### discoverProjectMcpServers()

This package-private method performs project-level MCP server discovery by reading the working directory's `.mcp.json` file. The discovery process:

1. **Read .mcp.json**: Parses the file and extracts `mcpServers` entries. Each entry is expected to have a `command` field (ignored during discovery) and an `args` array whose first element is the Python source file path.

2. **Read .claude/settings.json**: If present, extracts the `enabledMcpjsonServers` array. This array acts as a whitelist: only servers named in this array are included. If the file is absent or the array is missing, all servers from `.mcp.json` are considered enabled.

3. **Filter exclusions**: Servers named `ar-github` and `ar-slack` are always excluded. Servers that appear in the centralized config or pushed tools config are also excluded (they are handled by their respective higher-priority paths).

4. **Return**: The remaining servers are returned as a `Map<String, String>` from server name to Python file path.

### Priority and Deduplication

The four configuration sources are processed with strict deduplication to prevent the same server from appearing twice in the MCP config or allowed-tools list:

1. Centralized servers take highest priority. They appear as HTTP entries.
2. Pushed tools take second priority. They appear as stdio entries.
3. Project servers take third priority. They are skipped if the server name already appears in centralized or pushed configs.
4. Fallback servers (ar-github, ar-slack) take lowest priority. They are only added if not already centralized or pushed.

This means that if a server like `ar-slack` is configured as a centralized HTTP server, the stdio fallback for `ar-slack` is NOT added, and the tools for `ar-slack` come from the centralized config's `tools` array rather than from any local discovery.

### Environment Variable Resolution Pattern

The `0.0.0.0` placeholder is used throughout the system as a stand-in for the controller's actual address. This pattern exists because the controller constructs URLs at startup time when it may be listening on all interfaces (`0.0.0.0`). When agents run in Docker containers on a different network, `0.0.0.0` would resolve to the container's loopback. The `FLOWTREE_ROOT_HOST` environment variable provides the actual hostname or IP address visible from the agent's network. Resolution happens in three places:

1. `McpConfigBuilder.buildMcpConfig()`: Replaces `0.0.0.0` in centralized server URLs.
2. `ManagedToolsDownloader.ensurePushedTools()`: Replaces `0.0.0.0` in download URLs.
3. `GitManagedJob.resolveWorkstreamUrl()`: Replaces `0.0.0.0` in the workstream URL used for status events and MCP tool communication.

---

## ManagedToolsDownloader and Pushed Tool Lifecycle

`ManagedToolsDownloader` handles the download and verification of pushed MCP tool server files. It is instantiated within `ClaudeCodeJob` with a reference to the `McpConfigBuilder` for configuration parsing.

### ensurePushedTools(String pushedToolsConfig)

This is the primary entry point. For each server defined in the pushed tools configuration:

1. **Check existence**: If `~/.flowtree/tools/mcp/<name>/server.py` already exists, skip the download and log a message.

2. **Extract URL**: Parse the pushed tools config JSON to find the `url` field for the server name.

3. **Resolve host**: Replace `0.0.0.0` in the URL with the `FLOWTREE_ROOT_HOST` environment variable. This is necessary when the agent runs in a Docker container and the controller is on the host network.

4. **Create directories**: `Files.createDirectories()` ensures the target directory exists.

5. **Download**: `httpGet(url)` performs an HTTP GET with a 10-second connect timeout and 30-second read timeout. Non-2xx responses throw `IOException`.

6. **Write**: The response body is written to `server.py` in UTF-8.

### verifyMcpToolFiles(Path workingDirectory)

Checks for the existence of `tools/mcp/slack/server.py` and `tools/mcp/github/server.py` relative to the working directory. For each file:

- If present: logs the file's age in seconds since last modification. This aids deployment diagnostics (stale tool files indicate a deployment problem).
- If absent: logs a warning with the absolute path. Missing tool files mean the fallback stdio entries in the MCP config will fail at runtime.

### httpGet(String url)

A simple HTTP GET client method that returns the response body as a string. It uses `HttpURLConnection` with a 10-second connect timeout and 30-second read timeout. Throws `IOException` if the response status code is outside the 2xx range.

### Download URL Format

The controller serves pushed tool files at `GET /api/tools/<name>`. This endpoint is handled by `FlowTreeApiEndpoint.handleToolDownload()`, which reads the registered Python source file from disk and serves it as `text/plain`. Tool files are registered on the endpoint by the controller during startup.

The full download URL has the form `http://0.0.0.0:<port>/api/tools/<name>`, where `<port>` is the `FlowTreeApiEndpoint` listening port (default: 7780) and `<name>` is the server name (e.g., `ar-slack`, `ar-memory`). The `0.0.0.0` placeholder is resolved to the actual controller host before the download request is made.

### Pushed Tool File Layout

After downloading, pushed tools are stored in a flat directory structure under the user's home directory:

```
~/.flowtree/
  tools/
    mcp/
      ar-slack/
        server.py
      ar-memory/
        server.py
      ar-consultant/
        server.py
```

Each server's directory contains a single `server.py` file. The directory structure is created by `Files.createDirectories()` if it does not already exist. The file is written in UTF-8 encoding.

### Idempotency and Caching

`ensurePushedTools()` is idempotent: if the `server.py` file already exists for a given server, the download is skipped entirely. There is no version checking or cache invalidation -- once a tool is downloaded, it is never re-downloaded unless the file is manually deleted or the container is rebuilt. This design prioritizes reliability over freshness, since tool file updates are expected to be infrequent and coordinated through container image updates.

### Error Handling

Download failures for individual tools are logged as warnings but do not fail the job. This means that if one pushed tool fails to download (e.g., due to a temporary network issue), the remaining tools are still downloaded, and the Claude Code process is still started. The missing tool will produce errors when Claude Code tries to launch its stdio server, but the agent can still function with the tools that were successfully downloaded.

The `verifyMcpToolFiles()` method provides early detection of missing tool files by logging warnings before the Claude Code process is started. This helps operators diagnose deployment issues by checking the agent's logs.

### Relationship to McpConfigBuilder

The `ManagedToolsDownloader` is constructed with a reference to the `McpConfigBuilder`, but in the current implementation, the downloader has its own JSON parsing methods rather than delegating to the builder. This is because the downloader needs to extract the `url` field (which the builder does not need) and operates on the raw pushed tools config string before the builder has been configured. The builder's `parsePushedConfig()` method is used elsewhere (in `buildMcpConfig()` and `buildAllowedTools()`) for tool name extraction.

---

## MCP Tool Discovery

`McpToolDiscovery` is a utility class that scans Python MCP server source files to discover tool names. It supports two common MCP server implementation patterns and is used by both `McpConfigBuilder` (for project servers in the agent) and `FlowTreeController` (for centralized servers on the controller).

### Decorator Pattern

Used by: ar-slack, ar-memory, ar-consultant, ar-profile-analyzer, ar-github.

In this pattern, each tool is a Python function decorated with `@mcp.tool()`:

```python
@mcp.tool()
def slack_send_message(text: str) -> dict:
    """Send a message to the workstream channel."""
    ...

@mcp.tool()
def slack_get_stats(period: str = "weekly") -> dict:
    """Get job timing statistics."""
    ...
```

**Detection logic**: The scanner iterates through lines sequentially, looking for lines whose trimmed form starts with `@mcp.tool`. When such a line is found, it enters a look-ahead loop that examines the next 5 lines (or until end of file, whichever comes first) for a function definition matching the regex `def\\s+(\\w+)\\s*\\(`. The first capturing group extracts the function name, which becomes the tool name. The 5-line look-ahead window accommodates common Python formatting patterns where blank lines, comments, or additional decorators (such as `@functools.wraps`) appear between the `@mcp.tool()` decorator and the actual `def` statement. If no function definition is found within the window, the decorator is silently ignored (no warning is logged).

### List-Tools Pattern

Used by: ar-test-runner, ar-jmx, ar-docs.

In this pattern, a `@server.list_tools()` (or `@mcp.list_tools()`) handler returns a list of `Tool(name="...", ...)` entries:

```python
@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(name="start_test_run", description="Start a test run"),
        Tool(name="get_run_status", description="Get test run status"),
        Tool(
            name="get_run_output",
            description="Get console output"
        ),
    ]
```

**Detection logic**: The scanner looks for lines starting with `@server.list_tools` or `@mcp.list_tools`. Once inside the handler body (terminated by the next `@server.*` or `@mcp.*` decorator), it uses two sub-patterns:

1. **Inline**: `Tool\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"` matches `Tool(name="start_test_run"` on a single line.
2. **Multi-line**: When `Tool(` is found without an inline name match, the scanner enters "tool constructor" mode and looks for `^\\s*name\\s*=\\s*\"([^\"]+)\"` on subsequent lines.

### discoverToolNames(Path serverFile)

The public entry point. Returns a `List<String>` of tool names. The method reads the entire file into a list of strings (one per line) and processes them sequentially. First tries the decorator pattern via `discoverDecoratorTools(lines)`; if the result list is non-empty, it is returned immediately. If no decorator-style tools were found, falls back to the list-tools pattern via `discoverListToolsEntries(lines)`. Returns an empty list if the file does not exist, cannot be read (logged as a warning), or contains no recognizable tool definitions.

The method is static and stateless -- it can be called concurrently from multiple threads without synchronization. Each invocation reads the file independently, so changes to tool files are reflected on the next call.

### Pattern Priority and Mutual Exclusivity

The two discovery patterns are tried in sequence, not in parallel. The decorator pattern is attempted first because it is simpler and more common. If any tools are found via decorators, the list-tools pattern is never attempted. This means a server that mixes both patterns would only have its decorator-defined tools discovered. In practice, no AR server mixes patterns -- each uses one or the other consistently.

### Edge Cases

- **Empty file**: Returns an empty list.
- **File with no tools**: Returns an empty list. This is valid for servers that are still in development.
- **File that does not exist**: Returns an empty list. This can happen if a project server is defined in `.mcp.json` but the Python file has not been committed.
- **Decorator with blank lines**: The scanner looks up to 5 lines ahead of the `@mcp.tool` decorator for the `def` statement, accommodating blank lines, comments, or additional decorators between the `@mcp.tool()` decorator and the function definition.
- **Multiline Tool constructor**: The multi-line pattern handles `Tool(` on one line and `name="..."` on the next, which is common in formatters like Black that wrap long argument lists.
- **Handler termination**: In the list-tools pattern, the handler body ends at the next `@server.*` or `@mcp.*` decorator (typically `@server.call_tool()` or `@mcp.call_tool()`). This prevents false matches from tool definitions in the call handler.

### Regex Patterns

The class defines three compiled `Pattern` constants:

| Pattern | Regex | Purpose |
|---|---|---|
| `FUNC_DEF_PATTERN` | `def\\s+(\\w+)\\s*\\(` | Matches function definitions and captures the function name. |
| `TOOL_NAME_INLINE_PATTERN` | `Tool\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"` | Matches inline `Tool(name="...")` and captures the tool name. |
| `TOOL_NAME_SEPARATE_PATTERN` | `^\\s*name\\s*=\\s*\"([^\"]+)\"` | Matches `name="..."` on its own line (inside a multi-line `Tool()` constructor). |

---

## Output Metric Extraction

After the Claude Code process exits, `extractOutputMetrics(String jsonOutput)` parses the output to extract structured metrics. Claude Code with `--output-format json` emits NDJSON (newline-delimited JSON), where each line is a complete JSON object representing a turn or the final result.

### NDJSON Structure

The output contains multiple JSON objects. Per-turn objects appear early with partial metrics. The final `"type":"result"` object contains session-level totals and is the one used for metric extraction.

### Result Object Location

`JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result")` scans the NDJSON output backward (from the last line) looking for a JSON object whose `"type"` field equals `"result"`. This reverse scan is important: per-turn objects also contain some of these fields, and a forward scan would match the first turn's partial metrics instead of the session totals.

### Extracted Fields

| Field | JSON Path | Java Type | Fallback |
|---|---|---|---|
| `sessionId` | `session_id` | `String` | `null` |
| `durationMs` | `duration_ms` | `long` | `0` |
| `durationApiMs` | `duration_api_ms` | `long` | `0` |
| `numTurns` | `num_turns` | `int` | `0` |
| `costUsd` | `total_cost_usd` or `cost_usd` | `double` | `0.0` |
| `subtype` | `subtype` | `String` | `null` |
| `isError` | `is_error` | `boolean` | `false` |
| `permissionDenials` | `permission_denials` (array length) | `int` | `0` |
| `deniedToolNames` | `permission_denials[*].tool` | `List<String>` | `null` |

The cost field has a two-step lookup: `total_cost_usd` is checked first, falling back to `cost_usd` if the former is zero or absent. This two-step approach handles different Claude Code output format versions, where the field name changed between releases. The method uses `resultNode.path("total_cost_usd").asDouble(0.0)` for the primary lookup, which returns `0.0` for both missing fields and explicit zero values, then checks `cost_usd` as a fallback only when the primary value is zero.

Permission denials are extracted from a JSON array. Each element is expected to have a `"tool"` field, and the tool names are collected into a list. The count is the array's `size()`.

### JSON Parsing

Metric extraction uses Jackson `ObjectMapper` and `JsonNode` for the result object. The `getTextOrNull()` helper method safely extracts text fields, returning `null` for missing or non-textual nodes. Parse failures are logged as warnings but do not fail the job.

### JsonFieldExtractor and NDJSON Scanning

The `JsonFieldExtractor` utility class in the `io.flowtree` package provides the `extractLastJsonObject()` method used for result line location. It also provides lightweight field extraction methods (`extractString`, `extractInt`, `extractLong`, `extractDouble`, `extractBoolean`, `extractStringArray`) that use simple string scanning rather than a full JSON parser. These are used by `FlowTreeApiEndpoint` for HTTP event deserialization but are NOT used by `ClaudeCodeJob`'s metric extraction (which uses Jackson `ObjectMapper` for full parsing fidelity).

The `extractLastJsonObject()` method scans backward through the NDJSON output looking for lines that start with `{`. For each such line, it checks whether the line contains both `"type"` and `"result"` as string values. This backward scan is more efficient than a forward scan for two reasons: (1) the result object is always the last or near-last line, and (2) it avoids parsing every per-turn object just to check the type field.

If no line matches the requested type, the method returns the last JSON object found as a fallback. If no JSON objects are found at all (e.g., the output is empty or contains only non-JSON text), it returns `null`, and `extractOutputMetrics()` falls back to attempting to parse the entire raw output as a single JSON object.

### Metric Forwarding to the Event

The extracted metrics are stored in `ClaudeCodeJob`'s private fields. They are forwarded to the completion event in `populateEventDetails()`:

```java
@Override
protected void populateEventDetails(JobCompletionEvent event) {
    if (event instanceof ClaudeCodeJobEvent) {
        ClaudeCodeJobEvent ccEvent = (ClaudeCodeJobEvent) event;
        ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode);
        ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);
        ccEvent.withSessionDetails(subtype, isError, permissionDenials, deniedToolNames);
    }
}
```

Note that `populateEventDetails()` checks `instanceof` before casting. This is the only place in the event system where a type check is needed, and it exists because the method receives the event from the base class `createEvent()` which returns the generic type. After this point, all consumers use the polymorphic getters without type checking.

---

## Factory Serialization Format

`ClaudeCodeJob` and its `Factory` use a custom wire format for serialization across FlowTree nodes. This format is based on the `encode()`/`set(key, value)` protocol defined by `AbstractJobFactory`.

### Wire Format Structure

The encoded string uses `::key:=value` delimiters:

```
io.flowtree.jobs.ClaudeCodeJob::taskId:=abc123::branch:=<base64>::prompt:=<base64>::tools:=<base64>...
```

The first token is the fully qualified class name (used by the deserialization framework to instantiate the correct class). Subsequent tokens are key-value pairs.

### Value Encoding

- **Strings** that may contain special characters are Base64-encoded: `prompt`, `tools`, `branch`, `baseBranch`, `workDir`, `repoUrl`, `defaultWsPath`, `workstreamUrl`, `gitUserName`, `gitUserEmail`, `centralMcp`, `pushedTools`, `wsEnv`, `planDoc`
- **Numeric** values are serialized as plain strings: `maxTurns`, `maxBudget`, `maxFileSize`
- **Boolean** values are serialized as plain strings: `push`, `createBranch`, `dryRun`, `protectTests`
- **Map** values (`wsEnv`) are first serialized to JSON using Jackson, then Base64-encoded

### ClaudeCodeJob encode() Fields

| Wire Key | Java Field | Encoding | Condition |
|---|---|---|---|
| `prompt` | `prompt` | Base64 | Always |
| `tools` | `allowedTools` | Base64 | Always |
| `maxTurns` | `maxTurns` | Plain int | Always |
| `maxBudget` | `maxBudgetUsd` | Plain double | Always |
| `centralMcp` | `centralizedMcpConfig` | Base64 | Non-null |
| `pushedTools` | `pushedToolsConfig` | Base64 | Non-null |
| `wsEnv` | `workstreamEnv` | JSON then Base64 | Non-null, non-empty |
| `planDoc` | `planningDocument` | Base64 | Non-null |
| `protectTests` | `protectTestFiles` | Plain boolean | Always |

Additionally, all `GitManagedJob` fields are encoded by `super.encode()`:

| Wire Key | Java Field | Encoding | Condition |
|---|---|---|---|
| `taskId` | `taskId` | Plain string | Always |
| `branch` | `targetBranch` | Base64 | Non-null |
| `baseBranch` | `baseBranch` | Base64 | Non-null, not "master" |
| `workDir` | `workingDirectory` | Base64 | Non-null |
| `repoUrl` | `repoUrl` | Base64 | Non-null |
| `defaultWsPath` | `defaultWorkspacePath` | Base64 | Non-null |
| `maxFileSize` | `maxFileSizeBytes` | Plain long | Always |
| `push` | `pushToOrigin` | Plain boolean | Always |
| `createBranch` | `createBranchIfMissing` | Plain boolean | Always |
| `dryRun` | `dryRun` | Plain boolean | Always |
| `protectTests` | `protectTestFiles` | Plain boolean | Always |
| `gitUserName` | `gitUserName` | Base64 | Non-null |
| `gitUserEmail` | `gitUserEmail` | Base64 | Non-null |
| `workstreamUrl` | `workstreamUrl` | Base64 | Non-null |

### ClaudeCodeJob set() Deserialization

The `set(String key, String value)` method handles incoming key-value pairs during deserialization. It uses a `switch` on the key:

- `prompt`: Base64-decoded to `this.prompt`
- `tools`: Base64-decoded to `this.allowedTools`
- `maxTurns`: Parsed as int
- `maxBudget`: Parsed as double
- `centralMcp`: Base64-decoded to `this.centralizedMcpConfig`
- `pushedTools`: Base64-decoded to `this.pushedToolsConfig`
- `wsEnv`: Base64-decoded to JSON, then parsed to `Map<String, String>` via `parseJsonObjectToMap()`
- `planDoc`: Base64-decoded to `this.planningDocument`
- `protectTests`: Parsed as boolean
- Default: delegated to `super.set(key, value)` for `GitManagedJob` fields

### Factory Serialization

The Factory class mirrors the same key names in its `set(String key, String value)` method, which handles both git-shared keys (`workDir`, `repoUrl`, `defaultWsPath`, `branch`, `baseBranch`, `push`, `workstreamUrl`, `gitUserName`, `gitUserEmail`, `protectTests`) and factory-specific keys (`tools`, `maxTurns`, `maxBudget`, `centralMcp`, `pushedTools`, `wsEnv`, `planDoc`). The factory also stores `factoryTaskId` for task ID persistence.

Prompts are stored via `setPrompts(String... prompts)`, which joins them with `PROMPT_SEPARATOR` (`;;PROMPT;;`), Base64-encodes the result, and stores it under the key `prompts`. Retrieval via `getPrompts()` reverses this process.

### JSON Helpers

Two static methods assist with `Map<String, String>` serialization:

- `mapToJsonObject(Map<String, String>)`: Serializes a map to a JSON object string using Jackson's `ObjectMapper.writeValueAsString()`. Returns `"{}"` on failure. This is used during `encode()` to convert the `workstreamEnv` map into a JSON string before Base64 encoding.
- `parseJsonObjectToMap(String)`: Parses a JSON object string into a `LinkedHashMap<String, String>` using Jackson. The method iterates through the JSON object's fields and includes only entries whose values are textual (i.e., `isTextual()` returns true). Non-textual values (numbers, booleans, nested objects, arrays) are silently skipped. Returns an empty `LinkedHashMap` on null input or parse failure. The use of `LinkedHashMap` preserves insertion order, which ensures deterministic serialization round-trips.

### Wire Format Example

A complete encoded string for a `ClaudeCodeJob` (with values abbreviated for readability):

```
io.flowtree.jobs.ClaudeCodeJob::taskId:=abc123def::branch:=ZmVhdHVyZS9teS13b3Jr::baseBranch:=bWFzdGVy::workDir:=L3dvcmtzcGFjZS9wcm9qZWN0::repoUrl:=aHR0cHM6Ly9naXRodWIuY29tL293bmVyL3JlcG8=::maxFileSize:=1048576::push:=true::createBranch:=true::dryRun:=false::protectTests:=true::gitUserName:=Q2xhdWRlIEFnZW50::gitUserEmail:=Y2xhdWRlQGV4YW1wbGUuY29t::workstreamUrl:=aHR0cDovLzAuMC4wLjA6Nzc4MC9hcGkvd29ya3N0cmVhbXMvd3MtY29tbW9u::prompt:=Rml4IHRoZSBidWcgaW4gUGFyc2VyLmphdmE=::tools:=UmVhZCxFZGl0LFdyaXRlLEJhc2gsR2xvYixHcmVw::maxTurns:=50::maxBudget:=10.0::centralMcp:=eyJhci1zbGFjayI6eyJ1cmwiOiJodHRwOi8vMC4wLjAuMDo4MDkwL21jcCIsInRvb2xzIjpbInNsYWNrX3NlbmRfbWVzc2FnZSJdfX0=::protectTests:=true
```

When decoded:
- `branch` = `feature/my-work`
- `baseBranch` = `master`
- `workDir` = `/workspace/project`
- `repoUrl` = `https://github.com/owner/repo`
- `prompt` = `Fix the bug in Parser.java`
- `tools` = `Read,Edit,Write,Bash,Glob,Grep`
- `centralMcp` = `{"ar-slack":{"url":"http://0.0.0.0:8090/mcp","tools":["slack_send_message"]}}`

### Factory encode() and Prompt Storage

The Factory's `encode()` method inherits from `AbstractJobFactory`, which serializes its property map. Factory setters store values into this map via `set(key, value)`, so the encoded string contains all configured properties. The prompts are stored as a single Base64-encoded string with `;;PROMPT;;` delimiters between individual prompts:

For a factory with two prompts "Fix bug A" and "Fix bug B", the stored value would be:
```
prompts -> Base64("Fix bug A;;PROMPT;;Fix bug B")
```

On deserialization, `getPrompts()` decodes the Base64 string and splits on `;;PROMPT;;` to recover the original list.

### Deserialization on the Receiving Node

When the encoded string arrives at the agent node, the FlowTree deserialization framework:

1. Extracts the class name from the first token (`io.flowtree.jobs.ClaudeCodeJob` for jobs, or `io.flowtree.jobs.ClaudeCodeJob$Factory` for factories).
2. Instantiates the class using the default constructor.
3. Iterates through the remaining `::key:=value` tokens and calls `set(key, value)` for each.

This means the `set()` method must handle all keys that `encode()` produces. The `default` case in `ClaudeCodeJob.set()` delegates to `super.set(key, value)` in `GitManagedJob`, which handles all the git-related keys. Any key not recognized by either class is silently ignored.

---

## Command-Line Arguments

The Claude Code command is constructed in `doWork()` as a `List<String>`. The final command has this structure:

```
claude -p <instruction-prompt> --output-format json --allowedTools <tools> --max-turns <N> [--max-budget-usd <N.NN>] --mcp-config <json>
```

### Arguments in Detail

#### `-p <instruction-prompt>`

The `-p` flag invokes Claude Code in non-interactive (headless) mode. The argument is the full instruction prompt string produced by `buildInstructionPrompt()`, which wraps the user's prompt with operational context. This is passed as a single string argument to the process.

#### `--output-format json`

Requests NDJSON output (one JSON object per line). This enables structured metric extraction after the process completes. Without this flag, Claude Code would produce human-readable text output.

#### `--allowedTools <tools>`

A comma-separated string listing every tool the agent is allowed to use. Constructed by `mcpConfigBuilder.buildAllowedTools(allowedTools)`:

- Starts with the base tools (default: `Read,Edit,Write,Bash,Glob,Grep`)
- Appends centralized server tools as `mcp__<server>__<tool>`
- Appends pushed server tools in the same format
- Appends GitHub tools (4 tools, unless centralized/pushed)
- Appends Slack tool (1 tool, when workstream URL is set and not centralized/pushed)
- Appends discovered project server tools

Example output:
```
Read,Edit,Write,Bash,Glob,Grep,mcp__ar-slack__slack_send_message,mcp__ar-slack__slack_get_stats,mcp__ar-consultant__consult,mcp__ar-consultant__recall,mcp__ar-github__github_pr_find,mcp__ar-github__github_pr_review_comments,mcp__ar-github__github_pr_conversation,mcp__ar-github__github_pr_reply
```

#### `--max-turns <N>`

The maximum number of agentic turns. Default: 50 (from `ClaudeCodeJob`'s default). The Factory mirrors this default. Turns correspond to individual tool-use cycles within the Claude Code session. When the limit is reached, Claude Code terminates with subtype `error_max_turns`.

#### `--max-budget-usd <N.NN>`

Only included when `maxBudgetUsd > 0`. Formatted with two decimal places via `String.format("%.2f", maxBudgetUsd)`. Default: 10.00. When the budget is exhausted, Claude Code terminates.

#### `--mcp-config <json>`

The JSON MCP configuration string produced by `mcpConfigBuilder.buildMcpConfig()`. Contains the `{"mcpServers":{...}}` object with all HTTP and stdio server entries. This tells Claude Code how to connect to each MCP server.

### Environment Variables

The `ProcessBuilder` sets these environment variables on the Claude Code process:

| Variable | Source | Description |
|---|---|---|
| `AR_WORKSTREAM_URL` | `resolveWorkstreamUrl()` | The workstream URL with `0.0.0.0` replaced by the controller's actual host. Used by MCP tools (ar-slack, ar-github) to communicate with the controller. |

### Process Configuration

- **Working directory**: Set from `getWorkingDirectory()` if non-null. This determines the repository root that Claude Code operates on.
- **Stdin**: Redirected from `/dev/null` (no interactive input possible). This is essential for headless operation -- Claude Code's `-p` flag expects no TTY interaction.
- **Stderr**: Merged with stdout via `redirectErrorStream(true)`. Both streams are captured into a single output buffer.
- **Output capture**: Read line-by-line via `BufferedReader` in UTF-8 encoding. Each line is logged with `[ClaudeCode]` prefix for real-time monitoring. The complete output is also accumulated in a `StringBuilder` for post-execution parsing and file saving.
- **PID logging**: After process start, the PID is logged via `process.pid()` for diagnostic purposes.

### Output File Saving

After the process exits, the complete output is saved to `claude-output/<random-key>.json` where `<random-key>` is generated by `KeyUtils.generateKey()`. This provides a persistent record of every Claude Code execution, useful for debugging and auditing. The `claude-output/` directory is included in `GitManagedJob`'s default excluded patterns, so these files are never committed.

### Argument Order

The command-line arguments are added to the `List<String>` in a specific order, which matters because some flags consume the next positional argument:

1. `claude` (the executable)
2. `-p` (headless prompt flag)
3. The instruction prompt string
4. `--output-format`
5. `json`
6. `--allowedTools`
7. The comma-separated tools string
8. `--max-turns`
9. The turn limit as a string
10. `--max-budget-usd` (conditional)
11. The budget as a formatted string (conditional)
12. `--mcp-config`
13. The JSON config string

### Complete Example Command

For a job with all features enabled, the resulting command (with long values abbreviated) would look like:

```
claude -p "You are working autonomously as a coding agent...
--- BEGIN USER REQUEST ---
Fix the memory leak in CacheManager
--- END USER REQUEST ---" \
--output-format json \
--allowedTools Read,Edit,Write,Bash,Glob,Grep,mcp__ar-slack__slack_send_message,mcp__ar-consultant__consult,mcp__ar-consultant__recall,mcp__ar-consultant__remember,mcp__ar-consultant__branch_catchup,mcp__ar-github__github_pr_find,mcp__ar-github__github_pr_review_comments,mcp__ar-github__github_pr_conversation,mcp__ar-github__github_pr_reply \
--max-turns 50 \
--max-budget-usd 10.00 \
--mcp-config '{"mcpServers":{"ar-slack":{"type":"http","url":"http://controller:8090/mcp"},"ar-consultant":{"type":"http","url":"http://controller:8091/mcp"},"ar-github":{"command":"python3","args":["tools/mcp/github/server.py"]}}}'
```

In practice, the prompt and MCP config strings are much longer, but the structure is always the same.

### Logging

Throughout execution, `ClaudeCodeJob` logs extensively using the `ConsoleFeatures` interface (inherited via `GitManagedJob`). Key log points include:

- **Job start**: Logs the truncated task string and allowed tools list.
- **Target branch**: Logged if set.
- **MCP tool verification**: Logs the age of each tool file or warns about missing files.
- **Command**: Logs the full command string (including the prompt, which can be very long).
- **Working directory**: Logged for debugging.
- **Process PID**: Logged immediately after `pb.start()`.
- **AR_WORKSTREAM_URL**: Logged with its resolved value.
- **Process output**: Each line from the Claude Code process is logged with a `[ClaudeCode]` prefix.
- **Exit code**: Logged after `process.waitFor()`.
- **Output file**: The path to the saved output file is logged.

Log messages are formatted by `GitManagedJob.formatMessage()`, which prepends the class name and task ID:
```
ClaudeCodeJob [abc123]: Starting: Fix the bug in Parser...
ClaudeCodeJob [abc123]: Tools: Read,Edit,Write,Bash,Glob,Grep
ClaudeCodeJob [abc123]: [ClaudeCode] {"type":"assistant","content":"..."}
ClaudeCodeJob [abc123]: Completed with exit code: 0
```

This structured logging enables operators to trace individual job executions through the agent's log output, filtering by task ID when multiple jobs run concurrently on the same agent.
