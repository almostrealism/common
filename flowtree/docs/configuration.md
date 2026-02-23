# Flowtree Job Configuration

This document provides a comprehensive reference for configuring Flowtree jobs, covering the `GitJobConfig` immutable configuration class, the `WorkspaceResolver` utility, environment variable handling, MCP server configuration modes, and the wire protocol used for job serialization.

---

## Table of Contents

1. [Overview](#overview)
2. [GitJobConfig Field Reference](#gitjobconfig-field-reference)
3. [Builder Pattern Usage](#builder-pattern-usage)
4. [GitManagedJob Mutable Configuration](#gitmanagedob-mutable-configuration)
5. [Environment Variables](#environment-variables)
6. [WorkspaceResolver](#workspacerresolver)
7. [MCP Server Configuration](#mcp-server-configuration)
8. [Wire Protocol: Serialization with encode() and set()](#wire-protocol-serialization-with-encode-and-set)
9. [Configuration Lifecycle](#configuration-lifecycle)

---

## Overview

Flowtree jobs are configured through two complementary mechanisms:

- **`GitJobConfig`**: An immutable configuration object constructed via a Builder pattern. It holds all git-related settings (branches, URLs, guardrails) and is designed for scenarios where configuration is set once during deserialization and then frozen. This eliminates race conditions from setters being called after `run()` starts.

- **`GitManagedJob` setters**: The abstract base class retains mutable setters for backward compatibility and for cases where the Factory populates job fields during `nextJob()`. The Factory uses these setters to transfer configuration from itself to each produced job instance.

Both mechanisms converge on the same underlying fields. The immutable `GitJobConfig` is preferred for new code, while the mutable setter API on `GitManagedJob` exists for the Factory deserialization path.

---

## GitJobConfig Field Reference

The following table documents every field in `GitJobConfig`, its type, default value, and purpose.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `targetBranch` | `String` | `null` | The branch to commit and push changes to. When `null` or empty, git operations are entirely skipped. This is the primary on/off switch for git management. |
| `baseBranch` | `String` | `"master"` | The branch used as the starting point when creating a new target branch. New branches are created from `origin/<baseBranch>` after fetching. Also used as the reference for test file protection checks (files that exist on the base branch are protected). |
| `workingDirectory` | `String` | `null` | The absolute path to the git working directory. When `null`, the job uses the JVM's current working directory. When `repoUrl` is set and `workingDirectory` is `null`, the workspace path is resolved automatically by `WorkspaceResolver`. |
| `repoUrl` | `String` | `null` | The git clone URL (SSH or HTTPS). When set and no working directory exists, the repository is cloned into the resolved workspace path before the job starts. |
| `defaultWorkspacePath` | `String` | `null` | An explicitly configured workspace path that takes highest priority in workspace resolution. This is typically set from the controller's YAML configuration. |
| `maxFileSizeBytes` | `long` | `1,048,576` (1 MB) | Maximum file size in bytes that will be committed. Files exceeding this threshold are skipped during staging, with a log entry explaining the skip. The constant `DEFAULT_MAX_FILE_SIZE` defines this value. |
| `excludedPatterns` | `Set<String>` | See below | Glob patterns for files that are always excluded from commits. Initialized with `DEFAULT_EXCLUDED_PATTERNS` which covers secrets, build outputs, IDE files, binaries, databases, hardware acceleration outputs, and Claude Code agent outputs. |
| `additionalExcludedPatterns` | `Set<String>` | Empty set | Additional glob patterns to exclude, merged with `excludedPatterns` via `getAllExcludedPatterns()`. Use this to add project-specific exclusions without replacing the safety defaults. |
| `pushToOrigin` | `boolean` | `true` | Whether to push commits to the remote after committing. Set to `false` for local-only commits (useful for testing and dry runs). |
| `createBranchIfMissing` | `boolean` | `true` | Whether to create the target branch from `origin/<baseBranch>` if it does not already exist. When `false`, the job fails if the target branch is not found locally or on the remote. |
| `dryRun` | `boolean` | `false` | When `true`, git operations are logged but not executed. Useful for validating configuration without making actual changes. |
| `protectTestFiles` | `boolean` | `false` | When `true`, test and CI files that exist on the base branch cannot be staged. This prevents agents from hiding test failures by modifying existing tests. Branch-new test files (not present on the base branch) are still allowed. |
| `gitUserName` | `String` | `null` | The name to use for git commits. Passed via `git -c user.name=...` on the command line, which overrides any global or repository-level git configuration. |
| `gitUserEmail` | `String` | `null` | The email to use for git commits. Passed via `git -c user.email=...` on the command line. |
| `workstreamUrl` | `String` | `null` | The controller URL for status reporting and Slack messaging. Follows the pattern `http://controller/api/workstreams/{id}/jobs/{jobId}`. The `0.0.0.0` placeholder is replaced with `FLOWTREE_ROOT_HOST` at runtime. |

### Default Excluded Patterns

The `DEFAULT_EXCLUDED_PATTERNS` set contains the following categories of patterns:

**Secrets and credentials:**
`.env`, `.env.*`, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `credentials.json`, `secrets.json`, `**/secrets/**`

**Build outputs and dependencies:**
`target/**`, `build/**`, `dist/**`, `out/**`, `node_modules/**`, `.gradle/**`, `.m2/**`, `*.class`, `*.jar`, `*.war`, `*.ear`

**IDE and OS files:**
`.idea/**`, `.vscode/**`, `*.iml`, `.DS_Store`, `Thumbs.db`

**Binary and media files:**
`*.exe`, `*.dll`, `*.so`, `*.dylib`, `*.zip`, `*.tar`, `*.gz`, `*.rar`, `*.7z`, `*.png`, `*.jpg`, `*.jpeg`, `*.gif`, `*.bmp`, `*.ico`, `*.mp3`, `*.mp4`, `*.wav`, `*.avi`, `*.mov`, `*.pdf`, `*.doc`, `*.docx`, `*.xls`, `*.xlsx`

**Database and logs:**
`*.db`, `*.sqlite`, `*.log`

**Hardware acceleration outputs (AR-specific):**
`Extensions/**`, `*.cl`, `*.metal`

**Claude Code agent outputs:**
`claude-output/**`, `commit.txt`, `.claude/**`, `settings.local.json`

### Protected Path Patterns

When `protectTestFiles` is enabled, the following patterns identify protected files:

| Pattern | Covers |
|---------|--------|
| `**/src/test/**` | All test source files in any Maven module |
| `**/src/it/**` | Integration test source files |
| `.github/workflows/**` | CI workflow definitions |
| `.github/actions/**` | Custom GitHub Actions |

---

## Builder Pattern Usage

`GitJobConfig` is constructed exclusively through its `Builder` class. The builder provides a fluent API where each setter returns `this` for chaining.

### Basic Construction

```java
GitJobConfig config = GitJobConfig.builder()
    .targetBranch("feature/my-work")
    .baseBranch("main")
    .repoUrl("https://github.com/owner/repo.git")
    .pushToOrigin(true)
    .build();
```

### Complete Configuration Example

```java
GitJobConfig config = GitJobConfig.builder()
    .targetBranch("feature/auto-resolve-123")
    .baseBranch("master")
    .repoUrl("https://github.com/almostrealism/common.git")
    .defaultWorkspacePath("/workspace/project")
    .maxFileSizeBytes(2 * 1024 * 1024)  // 2 MB
    .pushToOrigin(true)
    .createBranchIfMissing(true)
    .protectTestFiles(true)
    .gitUserName("flowtree-agent")
    .gitUserEmail("agent@flowtree.io")
    .workstreamUrl("http://0.0.0.0:7780/api/workstreams/ws-123/jobs/j-456")
    .addExcludedPatterns("*.bak", "scratch/**")
    .build();
```

### Adding Exclusion Patterns

There are three ways to manage exclusion patterns:

```java
// Add patterns to the existing defaults
GitJobConfig config = GitJobConfig.builder()
    .addExcludedPatterns("*.tmp", "scratch/**")
    .build();

// Replace the additional patterns entirely
Set<String> custom = new HashSet<>(Arrays.asList("*.tmp", "*.bak"));
GitJobConfig config = GitJobConfig.builder()
    .additionalExcludedPatterns(custom)
    .build();

// Clear all defaults (use with extreme caution)
GitJobConfig config = GitJobConfig.builder()
    .clearDefaultExcludedPatterns()
    .excludedPatterns(myCustomSet)
    .build();
```

### Querying Combined Patterns

The `getAllExcludedPatterns()` method returns the union of `excludedPatterns` and `additionalExcludedPatterns`:

```java
Set<String> all = config.getAllExcludedPatterns();
// Contains both default patterns and any additional patterns
```

### Checking Git Enablement

The `isGitEnabled()` method returns `true` if and only if `targetBranch` is non-null and non-empty:

```java
GitJobConfig disabled = GitJobConfig.builder().build();
disabled.isGitEnabled();  // false -- targetBranch is null

GitJobConfig enabled = GitJobConfig.builder()
    .targetBranch("feature/x")
    .build();
enabled.isGitEnabled();  // true
```

---

## GitManagedJob Mutable Configuration

While `GitJobConfig` represents the immutable target, the `GitManagedJob` abstract class exposes mutable setters that are used during the Factory-based deserialization path. These setters mirror the fields in `GitJobConfig`:

```java
job.setTargetBranch("feature/my-work");
job.setBaseBranch("main");
job.setWorkingDirectory("/workspace/project");
job.setRepoUrl("https://github.com/owner/repo.git");
job.setDefaultWorkspacePath("/workspace/project");
job.setMaxFileSizeBytes(2048);
job.setPushToOrigin(true);
job.setCreateBranchIfMissing(true);
job.setDryRun(false);
job.setProtectTestFiles(true);
job.setGitUserName("agent");
job.setGitUserEmail("agent@example.com");
job.setWorkstreamUrl("http://controller/api/workstreams/ws1/jobs/j1");
job.addExcludedPatterns("*.bak", "scratch/**");
```

When the `ClaudeCodeJob.Factory` produces a job via `nextJob()`, it propagates all configured fields from the factory to the new job instance through these setters.

---

## Environment Variables

Flowtree jobs rely on several environment variables for hardware acceleration, network resolution, and API authentication.

### AR_HARDWARE_LIBS

The directory where hardware acceleration libraries (JNI `.so` files, OpenCL kernels, etc.) are generated and loaded from. Must be set before running any AR code that uses hardware acceleration.

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
```

### AR_HARDWARE_DRIVER

Selects the hardware backend for computation. Available drivers:

| Value | Description |
|-------|-------------|
| `native` | Standard JNI operations with runtime-generated native code (default) |
| `opencl` | OpenCL acceleration (CPU/GPU) |
| `metal` | Metal GPU acceleration (Apple Silicon) |
| `external` | Generated executable approach |

```bash
export AR_HARDWARE_DRIVER=native
```

### AR_HARDWARE_MEMORY_SCALE

Controls the maximum memory available to the hardware backend. The value is a power of 2 exponent that determines the memory limit:

| Value | Memory Limit |
|-------|-------------|
| `7` | 8 GB (default) |
| `8` | 16 GB |
| `9` | 32 GB |

```bash
export AR_HARDWARE_MEMORY_SCALE=8  # 16 GB
```

### FLOWTREE_ROOT_HOST

The hostname or IP address of the Flowtree controller. When jobs run inside Docker containers, the workstream URL is configured with `0.0.0.0` as a placeholder. The `WorkspaceResolver.resolveWorkstreamUrl()` method and `GitManagedJob.resolveWorkstreamUrl()` method both replace this placeholder with the value of `FLOWTREE_ROOT_HOST`.

```bash
export FLOWTREE_ROOT_HOST=10.0.0.5
```

This environment variable is critical for containerized deployments where the controller runs on the host machine and agents run in containers. Without it, status event POSTs and Slack messages from agents cannot reach the controller.

### GITHUB_TOKEN / GH_TOKEN

Used for GitHub API authentication when detecting pull requests and querying the GitHub REST API. The system checks `GITHUB_TOKEN` first, falling back to `GH_TOKEN` if the former is not set.

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
```

These tokens are used by:
- `PullRequestDetector` for detecting open PRs after push
- `GitManagedJob.detectPullRequestUrl()` for the same purpose
- The GitHub proxy endpoint on the controller as a fallback when no local token is available

---

## WorkspaceResolver

`WorkspaceResolver` is a stateless utility class that centralizes three concerns previously embedded in `GitManagedJob`:

1. Workspace path resolution with a three-level priority scheme
2. Extraction of filesystem-safe repository names from git URLs
3. Replacement of the `0.0.0.0` placeholder in workstream URLs

### Workspace Path Resolution Priority

The `resolve(String configuredPath, String repoUrl)` method determines where a repository checkout should live on disk. The resolution follows a strict priority order:

**Priority 1: Configured path**

If a non-null, non-empty `configuredPath` is provided (typically from the `defaultWorkspacePath` field in YAML configuration), it is used directly without any further checks.

```java
WorkspaceResolver.resolve("/my/configured/path", repoUrl);
// Returns: "/my/configured/path"
```

**Priority 2: /workspace/project**

If no configured path is provided, the resolver checks whether `/workspace/project` exists on disk. This path is the standard mount point in Flowtree Docker containers, where the host project directory is volume-mounted into the container.

```java
// If /workspace/project exists as a directory:
WorkspaceResolver.resolve(null, repoUrl);
// Returns: "/workspace/project"
```

**Priority 3: /tmp/flowtree-workspaces/<repo-name>**

As a last resort, the resolver constructs a path under the temporary directory using a filesystem-safe name derived from the repository URL.

```java
WorkspaceResolver.resolve(null, "https://github.com/owner/repo.git");
// Returns: "/tmp/flowtree-workspaces/owner-repo"
```

The fallback directory constant is `WorkspaceResolver.FALLBACK_WORKSPACE_DIR` which is set to `/tmp/flowtree-workspaces`.

### Repository Name Extraction

The `extractRepoName(String url)` method converts a git URL into a filesystem-safe directory name. It handles both SSH and HTTPS formats:

**SSH format:**
```
git@github.com:owner/repo.git  ->  owner-repo
```

**HTTPS format:**
```
https://github.com/owner/repo.git  ->  owner-repo
```

The method strips the protocol and host, removes the `.git` suffix, and replaces path separators (`/`) with dashes (`-`). For null or empty input, it returns `"unknown"`.

### Workstream URL Resolution

The `resolveWorkstreamUrl(String workstreamUrl)` method replaces the `0.0.0.0` placeholder with the value of the `FLOWTREE_ROOT_HOST` environment variable.

```java
// With FLOWTREE_ROOT_HOST=10.0.0.5
WorkspaceResolver.resolveWorkstreamUrl("http://0.0.0.0:7780/api/workstreams/ws1");
// Returns: "http://10.0.0.5:7780/api/workstreams/ws1"

// Without FLOWTREE_ROOT_HOST or URL without placeholder:
WorkspaceResolver.resolveWorkstreamUrl("http://10.0.0.1:7780/api");
// Returns: "http://10.0.0.1:7780/api" (unchanged)
```

---

## MCP Server Configuration

`ClaudeCodeJob` configures MCP (Model Context Protocol) servers for the Claude Code agent through the `McpConfigBuilder` class. There are three distinct configuration modes, plus two fallback servers that are always conditionally included.

### Centralized MCP Servers

Centralized servers run on (or near) the controller and are accessed over HTTP. The configuration is a JSON string mapping server names to their HTTP URLs and tool lists:

```json
{
  "ar-slack": {
    "url": "http://0.0.0.0:8080/mcp",
    "tools": ["slack_send_message", "slack_get_stats"]
  },
  "ar-memory": {
    "url": "http://0.0.0.0:8081/mcp",
    "tools": ["memory_store", "memory_search"]
  }
}
```

In the generated MCP config, these become HTTP entries:

```json
{
  "mcpServers": {
    "ar-slack": {
      "type": "http",
      "url": "http://10.0.0.5:8080/mcp"
    }
  }
}
```

Note that `0.0.0.0` in the URL is replaced with `FLOWTREE_ROOT_HOST` at config build time.

Set via `ClaudeCodeJob.Factory.setCentralizedMcpConfig(String)` or the `centralMcp` wire key.

### Pushed MCP Tools

Pushed tools are Python MCP server scripts that are downloaded from the controller and run locally via stdio in the agent's container. The configuration JSON has the same structure as centralized config but includes optional environment variables:

```json
{
  "ar-docs": {
    "url": "http://controller/api/tools/ar-docs/download",
    "tools": ["search_ar_docs", "read_ar_module", "list_ar_modules"],
    "env": {
      "AR_DOCS_INDEX": "/data/docs-index"
    }
  }
}
```

In the generated MCP config, pushed tools become stdio entries pointing to `~/.flowtree/tools/mcp/{name}/server.py`:

```json
{
  "mcpServers": {
    "ar-docs": {
      "command": "python3",
      "args": ["~/.flowtree/tools/mcp/ar-docs/server.py"],
      "env": {
        "AR_DOCS_INDEX": "/data/docs-index"
      }
    }
  }
}
```

The `ManagedToolsDownloader` class handles downloading the Python files from the controller before job execution. Per-workstream environment variables (set via `workstreamEnv`) override the global env vars defined in the pushed tool entry.

Set via `ClaudeCodeJob.Factory.setPushedToolsConfig(String)` or the `pushedTools` wire key.

### Project MCP Servers

Project servers are discovered from the repository's `.mcp.json` file and cross-referenced with `.claude/settings.json` to determine which servers are enabled. This allows repositories to define their own MCP tool servers that are automatically included when an agent works on that repository.

The `.mcp.json` file structure:

```json
{
  "mcpServers": {
    "ar-test-runner": {
      "command": "python3",
      "args": ["tools/mcp/test-runner/server.py"]
    },
    "ar-jmx": {
      "command": "python3",
      "args": ["tools/mcp/jmx/server.py"]
    }
  }
}
```

The `.claude/settings.json` file optionally restricts which servers are enabled:

```json
{
  "enabledMcpjsonServers": ["ar-test-runner", "ar-jmx"]
}
```

Project servers named `ar-github` or `ar-slack` are always excluded from this discovery (they have special handling). Servers that are already centralized or pushed are also excluded to avoid duplicates.

Tool names for project servers are automatically discovered by `McpToolDiscovery`, which parses the Python source files for `@mcp.tool()` decorators and `Tool(name="...")` entries in `@server.list_tools()` handlers.

### Fallback Servers: ar-github and ar-slack

Two servers receive special conditional handling:

**ar-github** is always included unless it is already centralized or pushed. When neither applies, it falls back to a local stdio entry pointing to `tools/mcp/github/server.py`. Its tools (`github_pr_find`, `github_pr_review_comments`, `github_pr_conversation`, `github_pr_reply`) are always added to the allowed tools list.

**ar-slack** is included only when a workstream URL is configured (meaning Slack communication is relevant). Like ar-github, if it is not centralized or pushed, it falls back to a local stdio entry at `tools/mcp/slack/server.py`.

### Allowed Tools Assembly

The `McpConfigBuilder.buildAllowedTools(String baseTools)` method constructs the complete comma-separated tools string for the `--allowedTools` flag. It starts with the base tools (e.g., `"Read,Edit,Write,Bash,Glob,Grep"`) and appends:

1. Tools from centralized servers as `mcp__{serverName}__{toolName}`
2. Tools from pushed tools as `mcp__{serverName}__{toolName}`
3. GitHub tools (unless ar-github is centralized/pushed)
4. Slack tool (unless ar-slack is centralized/pushed, and only when workstream URL is set)
5. Tools from discovered project servers (auto-discovered from Python source)

---

## Wire Protocol: Serialization with encode() and set()

Flowtree uses a custom key-value wire format for serializing jobs across the network. Both `GitManagedJob` and `ClaudeCodeJob` implement `encode()` and `set()` methods that handle this serialization.

### Format

The encoded string uses `::` as a pair separator and `:=` as a key-value separator:

```
io.flowtree.jobs.ClaudeCodeJob::taskId:=abc123::branch:=ZmVhdHVyZS90ZXN0::push:=true::prompt:=Rml4IHRoZSBidWc=
```

The first segment is the fully-qualified class name, followed by key-value pairs.

### Base64 Encoding

String values that may contain special characters (colons, equals signs, newlines, Unicode) are encoded using standard Base64. The `base64Encode()` and `base64Decode()` helper methods handle the conversion:

```java
protected static String base64Encode(String s) {
    return s == null ? "" : Base64.getEncoder()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
}

protected static String base64Decode(String s) {
    return s == null || s.isEmpty() ? null
        : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
}
```

### GitManagedJob Wire Keys

The following keys are used by `GitManagedJob.encode()` and decoded by `GitManagedJob.set()`:

| Wire Key | Field | Encoding | Notes |
|----------|-------|----------|-------|
| `taskId` | `taskId` | Plain text | Always present |
| `branch` | `targetBranch` | Base64 | Only present when non-null |
| `baseBranch` | `baseBranch` | Base64 | Only present when not `"master"` (the default) |
| `workDir` | `workingDirectory` | Base64 | Only present when non-null |
| `repoUrl` | `repoUrl` | Base64 | Only present when non-null |
| `defaultWsPath` | `defaultWorkspacePath` | Base64 | Only present when non-null |
| `maxFileSize` | `maxFileSizeBytes` | Plain long | Always present |
| `push` | `pushToOrigin` | Plain boolean | Always present |
| `createBranch` | `createBranchIfMissing` | Plain boolean | Always present |
| `dryRun` | `dryRun` | Plain boolean | Always present |
| `protectTests` | `protectTestFiles` | Plain boolean | Always present |
| `gitUserName` | `gitUserName` | Base64 | Only present when non-null |
| `gitUserEmail` | `gitUserEmail` | Base64 | Only present when non-null |
| `workstreamUrl` | `workstreamUrl` | Base64 | Only present when non-null |

### ClaudeCodeJob Wire Keys

`ClaudeCodeJob` extends `GitManagedJob`'s encoding with additional keys. The `encode()` method calls `super.encode()` first and then appends:

| Wire Key | Field | Encoding | Notes |
|----------|-------|----------|-------|
| `prompt` | `prompt` | Base64 | The Claude Code prompt text |
| `tools` | `allowedTools` | Base64 | Comma-separated allowed tools |
| `maxTurns` | `maxTurns` | Plain int | Default: 50 |
| `maxBudget` | `maxBudgetUsd` | Plain double | Default: 10.0 |
| `centralMcp` | `centralizedMcpConfig` | Base64 | JSON string, only when non-null |
| `pushedTools` | `pushedToolsConfig` | Base64 | JSON string, only when non-null |
| `wsEnv` | `workstreamEnv` | Base64 | JSON object string, only when non-empty |
| `planDoc` | `planningDocument` | Base64 | Only when non-null |
| `protectTests` | `protectTestFiles` | Plain boolean | Repeated from parent |

### Deserialization Flow

During deserialization, the encoded string is split on `::` separators. Each key-value pair is split on `:=` and passed to the `set(String key, String value)` method. `ClaudeCodeJob.set()` handles its own keys and delegates unrecognized keys to `super.set()` (which is `GitManagedJob.set()`).

```java
// ClaudeCodeJob.set() handles:
case "prompt": this.prompt = base64Decode(value); break;
case "tools": this.allowedTools = base64Decode(value); break;
case "maxTurns": this.maxTurns = Integer.parseInt(value); break;
case "maxBudget": this.maxBudgetUsd = Double.parseDouble(value); break;
// ... other ClaudeCodeJob-specific keys ...
default: super.set(key, value);  // Delegates to GitManagedJob
```

### Factory Wire Protocol

The `ClaudeCodeJob.Factory` also participates in wire serialization. It extends `AbstractJobFactory` which provides its own `encode()`/`set()` mechanism based on a properties map. The Factory stores its configuration as properties via `set(String, String)`, which both stores in the properties map and updates the local field:

```java
public void setTargetBranch(String targetBranch) {
    this.targetBranch = targetBranch;
    set("branch", base64Encode(targetBranch));  // Stores in properties map
}
```

During deserialization, `Factory.set()` intercepts known keys to populate local fields and delegates to `super.set()` for the properties map storage. Prompts are stored as a single Base64-encoded string with the separator `;;PROMPT;;` between individual prompts.

---

## Configuration Lifecycle

A typical job configuration lifecycle proceeds through these stages:

1. **Controller creates a Factory** with prompts, branch settings, MCP configuration, and workstream URL.

2. **Factory is serialized** via `AbstractJobFactory.encode()` which captures all properties set via `set(key, value)`.

3. **Factory is transmitted** to an agent node via the Flowtree wire protocol.

4. **Factory is deserialized** on the agent node. The `set()` method is called for each key-value pair, populating local fields.

5. **Factory produces a Job** via `nextJob()`, which creates a `ClaudeCodeJob` instance and copies all configuration from the Factory to the Job through the Job's setters.

6. **Job resolves workspace** by calling `resolveAndCloneRepository()` if `repoUrl` is set. The `WorkspaceResolver` determines the checkout path.

7. **Job prepares working directory** by fetching, checking out the target branch, and synchronizing with the base branch.

8. **Job configures MCP** via `McpConfigBuilder`, which assembles the `--mcp-config` JSON and `--allowedTools` string from centralized, pushed, and project server configurations.

9. **Job executes work** (Claude Code prompt execution) with the fully resolved configuration.

10. **Job performs git operations** using the configuration to determine branch targeting, file staging guardrails, commit identity, and push behavior.

11. **Job reports completion** by POSTing a status event to the resolved workstream URL.
