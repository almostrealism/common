# MCP Test Runner Server

An MCP server for running and managing Almost Realism test executions. This server provides a dedicated interface for test parameterization, execution tracking, and result retrieval.

## Features

- **Async Test Execution**: Run tests in the background without blocking
- **Run Tracking**: Each test run gets a unique ID for result retrieval
- **Configurable Depth**: Control test complexity via `AR_TEST_DEPTH`
- **Selective Testing**: Run specific classes or methods
- **Result Parsing**: Parse surefire XML reports for detailed results
- **Output Capture**: Access console output with filtering options
- **Preflight Seeding**: On the first invocation in a fresh worktree, the
  upstream `ar-*` module artifacts for the target Maven module are seeded
  into `~/.m2/repository/` automatically. This avoids the previous
  fail→install→retry cycle that pushed agents toward bash `mvn install`.
  Subsequent invocations skip the seed (idempotent).

## Preflight Seeding

Maven's `mvn test -pl <module>` (without `-am`) assumes the upstream
modules' jars are already installed in `~/.m2`. In a fresh worktree
they aren't — the first test invocation used to fail with an
unresolvable-dependency error, forcing the agent to drop to bash
`mvn install` to seed them.

The test runner now performs that seed itself, lazily, before the
first test invocation. Implementation: `preflight.py`.

The flow per `start_test_run`:

1. Parse the target module's `pom.xml` for direct `<dependency>`
   entries with `<groupId>org.almostrealism</groupId>`.
2. Look for each artifact's `.jar` in `~/.m2/repository/...`.
3. If every direct dep is already present, **skip** the seed (a few
   milliseconds of inspection).
4. Otherwise, run `mvn -pl <module> -am install -DskipTests -B` from
   the project root. `-am` ensures Maven builds the entire upstream
   reactor chain — so the next test invocation has everything it needs.

The seed's stdout/stderr is captured in the run's `output.txt`
between two `PREFLIGHT:` banners, so an agent inspecting
`get_run_output` sees clearly what was done.

When the seed itself fails (e.g., a build error in an upstream
module), the run is marked `failed` immediately and no Maven test
process is launched — the agent gets a fast, accurate failure
instead of a redundant dependency-resolution error from `mvn test`.

## Installation

```bash
pip install -r requirements.txt
```

## MCP Configuration

Already configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "ar-test-runner": {
      "command": "python3",
      "args": ["tools/mcp/test-runner/server.py"],
      "description": "Test execution and result tracking server"
    }
  }
}
```

The server automatically derives the project root from its location, so no environment variables are needed.

## Available Tools

### start_test_run

Start a new test run asynchronously.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `depth` | int (0-10) | No | AR_TEST_DEPTH value |
| `module` | string | No | Maven module (default: "engine/utils") |
| `test_classes` | string[] | No | Specific test class names |
| `test_methods` | object[] | No | Specific methods: `[{"class": "...", "method": "..."}]` |
| `timeout_minutes` | int | No | Max run time (default: 30) |
| `jvm_args` | string[] | No | Additional JVM arguments |

**Examples:**
```python
# Run all tests with depth 1
start_test_run(depth=1)

# Run specific test class
start_test_run(test_classes=["MeshIntersectionTest"])

# Run specific methods
start_test_run(test_methods=[
  {"class": "MeshIntersectionTest", "method": "triangleIntersectAtKernel"}
])

# Run with extra memory
start_test_run(test_classes=["LargeModelTest"], jvm_args=["-Xmx8g"])
```

### get_run_status

Check the status of a test run.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `run_id` | string | Yes | The run identifier |
| `block` | boolean | No | When true, wait server-side until the run reaches a terminal state (completed/failed/timeout/cancelled) before responding. Default: false. |
| `timeout_seconds` | integer | No | Maximum seconds to wait when `block=true` (default: 600, max: 3600). If it elapses, the latest still-running status is returned. Ignored when `block` is false. |

**Returns:** Status, timing, and test counts from surefire reports.

With `block=true` you can wait for a run to finish with a single call instead of
polling in a loop. Use it when you have nothing else to do while waiting;
otherwise leave it off, return, and do other work between checks.

### get_run_output

Get the console output from a test run.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `run_id` | string | Yes | The run identifier |
| `tail` | int | No | Only return last N lines |
| `filter` | string | No | Regex to filter lines |

### get_run_failures

Get detailed information about test failures.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `run_id` | string | Yes | The run identifier |

**Returns:** List of failures with stack traces, plus timing for all tests.

### list_runs

List recent test runs.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `limit` | int | No | Max runs to return (default: 10) |
| `status` | string | No | Filter by status |

### cancel_run

Cancel a running test.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `run_id` | string | Yes | The run identifier |

## Storage

Test run data is stored in `runs/{run_id}/`:
- `metadata.json` - Run configuration and status
- `output.txt` - Captured stdout/stderr
- `reports/` - Copied surefire reports

Maximum 50 runs are retained; oldest runs are cleaned up automatically.

## Environment Variables

The server does not set `AR_HARDWARE_LIBS` — it is auto-detected by the system. Do not set it manually.

`AR_HARDWARE_DRIVER` is **not** set by the test runner — leave it unset to inherit the best available backend for the system.

## Troubleshooting

**Tests not starting:**
- Check that Maven is installed and in PATH
- Verify AR_PROJECT_ROOT points to the common directory

**Results not appearing:**
- Wait for run status to show "completed" or "failed"
- Check output.txt for Maven errors

**Timeout issues:**
- Increase timeout_minutes for long-running tests
- Use depth parameter to limit test complexity
