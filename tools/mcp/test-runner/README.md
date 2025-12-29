# MCP Test Runner Server

An MCP server for running and managing Almost Realism test executions. This server provides a dedicated interface for test parameterization, execution tracking, and result retrieval.

## Features

- **Async Test Execution**: Run tests in the background without blocking
- **Run Tracking**: Each test run gets a unique ID for result retrieval
- **Configurable Depth**: Control test complexity via `AR_TEST_DEPTH`
- **Selective Testing**: Run specific classes or methods
- **Result Parsing**: Parse surefire XML reports for detailed results
- **Output Capture**: Access console output with filtering options

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
| `module` | string | No | Maven module (default: "utils") |
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

**Returns:** Status, timing, and test counts from surefire reports.

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

The server sets these for test execution:
- `AR_HARDWARE_LIBS=/tmp/ar_libs/`
- `AR_HARDWARE_DRIVER=native`

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
