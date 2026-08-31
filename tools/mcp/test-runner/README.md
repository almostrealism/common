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

## Modules

`server.py` owns the MCP surface and the lifecycle of a running Maven process —
building the command, spawning it, watching it, retrying, timing it out. Each of
the other concerns it used to carry inline is a collaborator:

| Module | Concern |
|--------|---------|
| `project.py` | Which Maven project and module a run targets: root resolution, and the per-module CI test-group count read from the project's own workflow |
| `run_store.py` | The on-disk record of runs: metadata, captured output, listing, retiring old runs, marking abandoned ones |
| `reports.py` | Surefire XML: collecting reports out of the project, and reading counts, failures, and per-test times back |
| `timing.py` | Statistics over a repeated run — duration spread and per-test pass rates |
| `preflight.py` | What the upstream artifact state *is*: which are missing, how stale, how to seed them |
| `preflight_runner.py` | Driving those preflight steps for one run and narrating them into its output |
| `fork_discovery.py` | Locating the surefire-forked JVM so `ar-jmx` can attach |
| `watcher.py` | A detached process that finalises a run's metadata if the parent dies mid-run |

Two constraints govern where code may live, both learned the hard way:

- **The `Tool(...)` definitions must stay in `server.py`.** `McpToolDiscovery`
  (`flowtree/runtime`) scans that file by path and parses the entries out of the
  `@server.list_tools()` handler; `McpToolDiscoveryTest` asserts it finds them.
  Moving the schema elsewhere would silently empty the discovered tool list.
- **Process execution stays in `server.py`.** The integration tests substitute
  `server.subprocess` and `server.threading` to run without spawning Maven, so
  those calls must resolve in the server module's namespace.

Note that a constant's home is where it is *read*: patching `server.PROJECT_ROOT`
no longer affects resolution, because `project.resolve_project_root` reads
`project.PROJECT_ROOT`. Tests patch the owning module.

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
| `project` | string | No | Root of the Maven project (default: this repository) |
| `module` | string | No | Maven module, relative to the project root (default: "engine/utils") |
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

#### Testing another Maven project

The runner is not limited to the repository it ships in. `project` names the
directory holding the reactor `pom.xml` of any Maven project — a sibling
checkout, a downstream consumer, a worktree. A relative path is resolved
against this repository, so a sibling is named the way a shell here would name
it, and `module` is always relative to that root:

```python
start_test_run(project="../downstream", module="app",
               test_classes=["SomeTest"])
```

This matters because direct `mvn test` is blocked for agents. Without
`project`, work on a downstream consumer had no sanctioned way to run its
tests at all. A path that does not exist, or that holds no `pom.xml`, is
rejected when the run is requested rather than surfacing later as an opaque
Maven failure.

Everything else is unchanged by the target: `run_id`s, output, and copied
surefire reports still live under this server's own `runs/` directory, so runs
against different projects are tracked side by side. Two caveats follow from
targeting a foreign project — `test_group` needs an explicit `test_groups`
unless that project has its own `.github/workflows/analysis.yaml`, and the
upstream-artifact preflight can only seed modules that are part of the target
project's own reactor.

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

**"another Maven run is still using the same build tree":**
- A build validation, or another test run, is in flight against the same
  project. Both write and read the same `target/` directories, so the results
  of overlapping runs describe neither. Wait for the named run
  (`get_run_status` / `get_validation_status` with `block=true`) or cancel it.
- A run against a different `project` is unaffected and starts normally.
- A plain `mvn` in a shell leaves no record, so it cannot be detected. If a
  check fails with a `NoSuchFileException` under `target/` and no violations,
  that is what happened — see the `note` on the affected check.

**Tests not starting:**
- Check that Maven is installed and in PATH
- Verify AR_PROJECT_ROOT points to the common directory

**Results not appearing:**
- Wait for run status to show "completed" or "failed"
- Check output.txt for Maven errors

**Timeout issues:**
- Increase timeout_minutes for long-running tests
- Use depth parameter to limit test complexity
