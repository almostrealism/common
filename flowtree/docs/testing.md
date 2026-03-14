# Flowtree Testing Guide

This document describes the test architecture, patterns, and practices for the Flowtree module's `jobs` and `slack` packages. It covers test organization, unit test strategies for each source class, integration test patterns, and instructions for running tests and adding new ones.

---

## Table of Contents

1. [Test Architecture and Patterns](#test-architecture-and-patterns)
2. [Test File Organization](#test-file-organization)
3. [Unit Test Strategy for the Jobs Package](#unit-test-strategy-for-the-jobs-package)
4. [Unit Test Strategy for the Slack Package](#unit-test-strategy-for-the-slack-package)
5. [Integration Test Patterns](#integration-test-patterns)
6. [How to Add Tests for New Functionality](#how-to-add-tests-for-new-functionality)
7. [Running Tests](#running-tests)

---

## Test Architecture and Patterns

### Extend TestSuiteBase

All Flowtree test classes must extend `TestSuiteBase` from the `ar-utils` module. This is a project-wide requirement documented in the root CLAUDE.md, and it applies to Flowtree tests as well.

```java
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

public class MyNewTest extends TestSuiteBase {

    @Test(timeout = 30000)
    public void myTestMethod() {
        // Test implementation
    }
}
```

`TestSuiteBase` provides:
- Test grouping for hash-based distribution across CI runners
- `@TestDepth` annotation support for gating expensive tests
- All `TestFeatures` utilities (assertions, kernel testing, etc.)
- Integration with the `AR_TEST_DEPTH` and `AR_TEST_PROFILE` environment variables

### JUnit 4

Flowtree tests use JUnit 4 (not JUnit 5). Key differences:
- Import `org.junit.Test` (not `org.junit.jupiter.api.Test`)
- Use `@Test(timeout = 30000)` for timeout control
- Use `org.junit.Assert` static imports for assertions
- Use `@Before` and `@After` (not `@BeforeEach` and `@AfterEach`)

### Timeout Convention

Every `@Test` method should include a timeout annotation. The standard timeout for fast unit tests is 30 seconds (`timeout = 30000`). For tests in the `slack` package that start HTTP servers, 10 seconds (`timeout = 10000`) is typical. Use longer timeouts only for integration tests that require real process execution or network I/O.

```java
@Test(timeout = 30000)   // Standard for jobs package tests
public void unitTest() { ... }

@Test(timeout = 10000)   // Standard for slack package tests
public void apiTest() { ... }
```

### No External Dependencies in Unit Tests

Flowtree unit tests are designed to run without external dependencies:
- No real git repositories (use in-memory or temp directory mocks)
- No real GitHub API calls (test URL parsing and error handling only)
- No real Claude Code execution (test configuration and prompt building)
- No real Slack connections (use callback-based message capture)
- No real Flowtree server connections (use local NanoHTTPD endpoints)

---

## Test File Organization

The test files are organized into two packages mirroring the source structure:

### io.flowtree.jobs (7 test classes)

| Test Class | Source Class(es) Covered | Focus |
|------------|--------------------------|-------|
| `GitJobConfigTest` | `GitJobConfig` | Builder defaults, immutability, git-enabled detection, excluded pattern merging |
| `WorkspaceResolverTest` | `WorkspaceResolver` | Path resolution priority, repo name extraction from SSH/HTTPS URLs, workstream URL resolution |
| `PullRequestDetectorTest` | `PullRequestDetector` | Owner/repo extraction from SSH/HTTPS URLs, validation of owner/repo format, empty-result preconditions |
| `FileStagerTest` | `FileStager`, `FileStagingConfig`, `StagingResult` | Pattern exclusion, size limits, binary detection, test file protection, deleted file handling, glob matching |
| `McpConfigBuilderTest` | `McpConfigBuilder` | Centralized HTTP entries, pushed stdio entries, project server discovery from .mcp.json, config parsing, allowed tools assembly |
| `McpToolDiscoveryTest` | `McpToolDiscovery` | `@mcp.tool()` decorator pattern discovery, `@server.list_tools()` handler pattern discovery, actual server file scanning, edge cases |
| `JobCompletionEventTest` | `JobCompletionEvent` | Factory methods, builder-pattern setters, default values, toString output |
| `ClaudeCodeJobEventTest` | `ClaudeCodeJobEvent` | Inheritance from `JobCompletionEvent`, Claude Code-specific builder methods, default values |

### io.flowtree.slack (1 comprehensive test class)

| Test Class | Source Class(es) Covered | Focus |
|------------|--------------------------|-------|
| `SlackIntegrationTest` | `SlackWorkstream`, `SlackNotifier`, `SlackListener`, `FlowTreeController`, `FlowTreeApiEndpoint`, `WorkstreamConfig`, `SlackTokens`, `JobStatsStore`, `JsonFieldExtractor` | Workstream configuration, agent round-robin, prompt extraction, notification formatting, API endpoints, YAML/JSON config loading, slash commands, job tracking, stats |

### io.flowtree.test (4 legacy test classes)

| Test Class | Focus |
|------------|-------|
| `DefaultProducer` | Helper class for Flowtree server tests |
| `TestJobFactory` | Helper factory for server integration tests |
| `UrlProfilingJob` / `UrlProfilingTask` | Legacy test jobs for Flowtree server functionality |
| `ServerTest` | Flowtree server lifecycle tests |
| `SubmitJobTest` | Job submission flow tests |

---

## Unit Test Strategy for the Jobs Package

### GitJobConfigTest

Tests the immutable configuration class and its builder:

**Builder creates immutable config**: Verifies that all builder setters produce the expected values in the built config, including non-default values for every field.

**Default values**: Verifies the builder's defaults match the documented defaults (`baseBranch` = `"master"`, `pushToOrigin` = `true`, `createBranchIfMissing` = `true`, `protectTestFiles` = `false`, `maxFileSizeBytes` = `DEFAULT_MAX_FILE_SIZE`).

**Null branch means no git ops**: Verifies that `isGitEnabled()` returns `false` when `targetBranch` is null (the default) and `true` when it is set.

**Pattern merging**: Verifies that `getAllExcludedPatterns()` returns the union of `excludedPatterns` and `additionalExcludedPatterns`, and that the total size equals the sum of both sets (assuming no overlap).

### WorkspaceResolverTest

Tests the stateless utility class:

**Uses configured path first**: Passes a non-null `configuredPath` and verifies it is returned unchanged, regardless of what `repoUrl` is.

**Falls back to temp with repo name**: Passes `null` for `configuredPath` and verifies the result ends with the expected repo name derived from the URL. Since `/workspace/project` typically does not exist on the test machine, the fallback to `/tmp/flowtree-workspaces/<repo-name>` is exercised.

**Extracts repo name from SSH**: Verifies `extractRepoName("git@github.com:owner/repo.git")` returns `"owner-repo"`.

**Extracts repo name from HTTPS**: Verifies `extractRepoName("https://github.com/owner/repo.git")` returns `"owner-repo"`.

**Resolves workstream URL**: Tests the `resolveWorkstreamUrl()` method. Since `FLOWTREE_ROOT_HOST` is not set in the test environment, verifies the URL is returned unchanged.

### PullRequestDetectorTest

Tests URL parsing and precondition checking without making real HTTP calls:

**SSH extraction**: Verifies `extractOwnerRepo("git@github.com:owner/repo.git")` returns `"owner/repo"`.

**HTTPS extraction**: Verifies `extractOwnerRepo("https://github.com/owner/repo.git")` returns `"owner/repo"`.

**Non-GitHub returns null**: Verifies that non-GitHub URLs (e.g., GitLab) return `null`.

**Validation accepts valid**: Verifies that well-formed `owner/repo` paths pass validation.

**Validation rejects invalid**: Verifies that paths without exactly two segments are rejected (e.g., `"noslash"` returns `null`).

**Detect returns empty for null remote**: Verifies that `detect(null, "branch", null)` returns `Optional.empty()`.

### FileStagerTest

Tests the file staging guardrails using temp directories and mock git operations:

**Mocking strategy**: The `FileStager.GitOperations` interface is implemented as a lambda in tests:
```java
// Git returns exit code 0 (file exists on base branch)
(String... args) -> 0

// Git returns exit code 1 (file does NOT exist on base branch)
(String... args) -> 1
```

**Stages non-excluded files**: Creates real files in a temp directory and verifies they pass all guardrails.

**Skips excluded patterns**: Creates files matching excluded patterns (`.env`, `target/**`) and verifies they are skipped.

**Skips oversized files**: Creates a file exceeding the configured `maxFileSizeBytes` and verifies it is skipped with a reason containing "exceeds".

**Skips binary files**: Creates a file with >10% null bytes and verifies binary detection works.

**Protects existing test files**: Configures `protectTestFiles=true` with a mock git operation that returns 0 (file exists on base branch) and verifies the test file is blocked.

**Allows branch-new test files**: Configures `protectTestFiles=true` with a mock git operation that returns 1 (file does NOT exist on base branch) and verifies the new test file is allowed.

**Handles deleted files**: Tests that files not present on disk (representing git deletions) are staged without triggering size or binary checks.

**Glob pattern matching**: Tests individual glob patterns including single star (`*.log`), double star (`target/**`), and exact name (`.DS_Store`) matching.

### McpConfigBuilderTest

Tests MCP configuration building from JSON inputs:

**Centralized HTTP entries**: Sets a centralized config JSON and verifies the output contains `"type":"http"` entries.

**Pushed stdio entries**: Sets a pushed tools config JSON and verifies the output contains `"command":"python3"` entries.

**Project server entries**: Creates a temp directory with `.mcp.json` and `.claude/settings.json` files, sets the working directory, and verifies the builder discovers and includes the project server.

**Config parsing**: Verifies `parseCentralizedConfig()` correctly extracts server names and tool lists from JSON.

**Allowed tools assembly**: Verifies `buildAllowedTools("Read,Edit")` includes base tools, centralized tools (as `mcp__<server>__<tool>`), and pushed tools.

### McpToolDiscoveryTest

Tests Python source file parsing for MCP tool name discovery:

**Decorator pattern**: Creates a temp Python file with `@mcp.tool()` decorators and verifies the function names are extracted.

**List tools pattern**: Creates a temp Python file with `Tool(name="...")` entries in a `@server.list_tools()` handler and verifies the names are extracted.

**Actual server files**: Conditionally tests against real server files in the repository (`tools/mcp/test-runner/server.py`, `docs/mcp/server.py`, `tools/mcp/jmx/server.py`). These tests skip gracefully if the files are not present.

**Missing file**: Verifies that a nonexistent file path returns an empty list.

**Null file**: Verifies that a null path returns an empty list.

### JobCompletionEventTest and ClaudeCodeJobEventTest

Test the event data classes:

**Factory methods**: Verify `success()` and `failed()` factory methods produce events with the correct status.

**Builder pattern**: Verify `withGitInfo()`, `withPullRequestUrl()`, `withClaudeCodeInfo()`, and `withSessionDetails()` populate the expected fields.

**Default values**: Verify that lists default to empty (not null) and numeric fields default to 0.

**Inheritance**: Verify that `ClaudeCodeJobEvent` is an instance of `JobCompletionEvent`.

---

## Unit Test Strategy for the Slack Package

### SlackIntegrationTest

This is a comprehensive test class covering multiple source classes. It uses several patterns:

**Callback-based message capture**: Instead of connecting to a real Slack API, the tests use `SlackNotifier.setMessageCallback()` to capture outgoing messages:
```java
List<String> messages = new ArrayList<>();
notifier.setMessageCallback(json -> {
    // Extract text from JSON and store
    messages.add(extractedText);
});
```

**Local NanoHTTPD server**: Tests that verify HTTP API behavior use `FlowTreeApiEndpoint` with port 0 (auto-assigned) and `NanoHTTPD.SOCKET_READ_TIMEOUT`:
```java
FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
try {
    int port = endpoint.getListeningPort();
    // Make HTTP requests to localhost:port
} finally {
    endpoint.stop();
}
```

**Event simulation**: Instead of connecting real agents, tests simulate events directly:
```java
notifier.onJobStarted(workstream.getWorkstreamId(), startEvent);
notifier.onJobCompleted(workstream.getWorkstreamId(), successEvent);
```

The test methods cover: workstream configuration, agent round-robin, prompt extraction, notification formatting, job completion events, API endpoints (messages, submit, health, stats), YAML/JSON config loading, slash commands, job tracking, branch-to-workstream resolution, and stats persistence.

---

## Integration Test Patterns

### Tests That Need Real File System

Several tests create temporary files and directories:

```java
Path tempDir = Files.createTempDirectory("stager-test");
try {
    Files.writeString(tempDir.resolve("Foo.java"), "public class Foo {}");
    // ... test logic ...
} finally {
    deleteRecursively(tempDir);
}
```

Always clean up temp files in a `finally` block. The `FileStagerTest` class includes a `deleteRecursively()` helper method for this purpose.

### Tests That Need Process Execution

Tests that verify actual MCP server file discovery (e.g., `McpToolDiscoveryTest.discoverFromActualTestRunner()`) depend on real files existing in the repository. These tests use a conditional guard:

```java
Path serverFile = Path.of("tools/mcp/test-runner/server.py");
if (!Files.exists(serverFile)) return;  // Skip if file not present
```

This pattern allows the test to run when the full repository is available (normal development) and skip gracefully in minimal CI environments.

### Tests That Need HTTP Servers

The `SlackIntegrationTest` tests that verify API endpoints start a local HTTP server on an auto-assigned port. The server is started in the test method and stopped in a `finally` block:

```java
FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
try {
    int port = endpoint.getListeningPort();
    HttpURLConnection conn = (HttpURLConnection) new URL(
        "http://localhost:" + port + "/api/health").openConnection();
    // ... assertions ...
} finally {
    endpoint.stop();
}
```

The 10-second test timeout ensures that stuck HTTP connections do not hang indefinitely.

### Tests That Need Embedded Databases

The `SlackIntegrationTest.testApiStatsEndpoint()` test uses `JobStatsStore` with an embedded database in a temp directory:

```java
File tempDir = Files.createTempDirectory("stats-test").toFile();
tempDir.deleteOnExit();
String dbPath = new File(tempDir, "stats").getAbsolutePath();

JobStatsStore store = new JobStatsStore(dbPath);
store.initialize();
try {
    // Seed data and test
} finally {
    store.close();
}
```

---

## How to Add Tests for New Functionality

### Step 1: Identify the Test Class

Match your new code to the appropriate existing test class based on the source class it covers. Refer to the [Test File Organization](#test-file-organization) table. If you are adding a new source class, create a corresponding test class.

### Step 2: Create the Test Class (if new)

```java
package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link MyNewClass} covering [describe coverage].
 */
public class MyNewClassTest extends TestSuiteBase {

    @Test(timeout = 30000)
    public void myFirstTest() {
        // Arrange
        // Act
        // Assert
    }
}
```

Key requirements:
- Extend `TestSuiteBase`
- Use JUnit 4 annotations
- Include a timeout on every `@Test` method
- Add a class-level Javadoc describing what the test covers

### Step 3: Follow Existing Patterns

Study the existing test that is most similar to what you need:

- **For configuration/builder tests**: Follow `GitJobConfigTest` or `FileStagingConfig` patterns
- **For URL parsing/extraction tests**: Follow `PullRequestDetectorTest` or `WorkspaceResolverTest` patterns
- **For file system tests**: Follow `FileStagerTest` patterns (temp dirs, cleanup)
- **For MCP config tests**: Follow `McpConfigBuilderTest` patterns (temp .mcp.json files)
- **For HTTP API tests**: Follow `SlackIntegrationTest` patterns (NanoHTTPD, auto-port)
- **For event/data class tests**: Follow `JobCompletionEventTest` patterns

### Step 4: Mock External Dependencies

The `FileStager.GitOperations` interface demonstrates the project's preferred mocking pattern: define a simple functional interface that can be implemented as a lambda in tests:

```java
// Production code defines the interface
public interface GitOperations {
    int execute(String... args) throws IOException, InterruptedException;
}

// Test provides a lambda implementation
StagingResult result = stager.evaluateFiles(files, config, tempDir.toFile(),
    (String... args) -> 0);  // Always returns success
```

For HTTP-based interactions, use `SlackNotifier.setMessageCallback()` or similar callback patterns rather than mocking HTTP clients.

### Step 5: Test Edge Cases

Every test class in the project includes edge case tests. Common patterns:

- **Null inputs**: Verify graceful handling of null arguments
- **Empty inputs**: Verify behavior with empty strings, empty lists, empty sets
- **Missing resources**: Verify files that don't exist return empty results
- **Invalid formats**: Verify malformed URLs, bad JSON, etc. are handled
- **Boundary values**: Verify behavior at size limits, zero values, maximum values

---

## Running Tests

### Using the MCP Test Runner (Preferred)

The MCP test runner is the preferred method for running Flowtree tests. It automatically handles environment variable setup and provides structured failure reporting.

To run all Flowtree tests:

```
mcp__ar-test-runner__start_test_run
  module: "flowtree"
  timeout_minutes: 10
```

To run a specific test class:

```
mcp__ar-test-runner__start_test_run
  module: "flowtree"
  test_classes: ["McpToolDiscoveryTest"]
  timeout_minutes: 5
```

To run a specific test method:

```
mcp__ar-test-runner__start_test_run
  module: "flowtree"
  test_methods: [{"class": "FileStagerTest", "method": "skipsBinaryFiles"}]
  timeout_minutes: 5
```

After starting a test run, check its status with:

```
mcp__ar-test-runner__get_run_status
  run_id: "<run_id>"
```

And get failure details with:

```
mcp__ar-test-runner__get_run_failures
  run_id: "<run_id>"
```

### Using Maven Directly (When Necessary)

If you need to run tests via Maven directly (e.g., for CI pipeline debugging), set the required environment variables first:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
# AR_HARDWARE_DRIVER is best left unset to auto-detect the best available backend
```

Then run from the **project root** (`common/`), never from the module directory:

```bash
# All Flowtree tests
mvn test -pl flowtree

# Single test class
mvn test -pl flowtree -Dtest=GitJobConfigTest

# Single test class with full package
mvn test -pl flowtree -Dtest=io.flowtree.jobs.GitJobConfigTest
```

Important: Never use `#` syntax for single test methods (causes shell issues). Run the entire test class instead.

### Test Depth and Profiles

Flowtree tests generally run at the default depth level and do not require special profiles. The standard `@Test(timeout = ...)` annotation provides sufficient gating. If you add a long-running test (30+ minutes), use the `skipLongTests` guard:

```java
@Test(timeout = 300000)
public void expensiveIntegrationTest() {
    if (skipLongTests) return;
    // Long-running test logic
}
```

### Debugging Test Failures

When a test fails, use the MCP test runner's structured output to understand the failure:

1. Get the failure details: `mcp__ar-test-runner__get_run_failures run_id:"<id>"`
2. Read the full test output: `mcp__ar-test-runner__get_run_output run_id:"<id>"`
3. If the failure involves file system or HTTP interactions, check that temp resources are being cleaned up properly
4. If the failure involves timing, check that the test timeout is sufficient for the environment

For flaky tests related to HTTP ports, ensure tests use port 0 (auto-assigned) rather than hardcoded ports to avoid conflicts when tests run in parallel.
