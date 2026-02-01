# JMX MCP Server

An MCP server for JVM memory analysis and leak detection on forked test JVMs. Connects to JVM processes spawned by `ar-test-runner` via JDK diagnostic tools (`jcmd`, `jstat`, `jps`, `jfr`) to provide real-time heap inspection, GC statistics, allocation profiling, and native memory tracking.

## Features

- **Heap Inspection**: Pool-level memory utilization from `jstat -gc`
- **GC Analysis**: Cause, timing, and utilization from `jstat -gccause`
- **Class Histograms**: Live object counts via `jcmd GC.class_histogram` with snapshot diffing
- **JFR Allocation Profiling**: Start/stop Flight Recorder and aggregate allocation samples by class
- **Thread Dumps**: `jcmd Thread.print` with regex filtering
- **Native Memory Tracking**: `jcmd VM.native_memory summary` for JNI/off-heap leaks
- **Continuous Monitoring**: Background `jstat` sampling with JSONL timeline and trend analysis (growth rate, estimated OOM time)

## Installation

```bash
pip install -r requirements.txt
```

Requires JDK diagnostic tools (`jcmd`, `jstat`, `jps`, `jfr`) on the system PATH.

## MCP Configuration

Already configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "ar-jmx": {
      "command": "python3",
      "args": ["tools/mcp/jmx/server.py"],
      "description": "JVM memory analysis and leak detection via JDK diagnostic tools"
    }
  }
}
```

## Prerequisites

The target JVM must be started with `jmx_monitoring: true` via `ar-test-runner`. This injects:
- `-XX:StartFlightRecording=...` for JFR allocation profiling
- `-XX:NativeMemoryTracking=summary` for native memory reports

The test runner automatically discovers the forked Surefire `ForkedBooter` PID and writes it to `metadata.json`.

## Available Tools

### attach_to_run

Verify connectivity to a forked test JVM. Returns JVM version info.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID from `ar-test-runner` |

### get_heap_summary

Heap pool sizes, utilization, and GC totals from `jstat -gc`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |

### get_gc_stats

GC cause, utilization percentages, and timing from `jstat -gccause`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |

### get_class_histogram

Live object histogram from `jcmd GC.class_histogram`. Supports filtering, sorting, and saving named snapshots for later diffing.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `pattern` | string | No | Regex to filter class names (e.g., `org\.almostrealism`) |
| `sort_by` | string | No | `bytes`, `instances`, or `class_name` (default: `bytes`) |
| `limit` | int | No | Max classes to return (default: 30) |
| `snapshot_id` | string | No | Save raw histogram with this name for later diffing |

### diff_class_histogram

Compare two saved histogram snapshots to identify per-class memory growth.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `before_snapshot` | string | Yes | Name of the earlier snapshot |
| `after_snapshot` | string | Yes | Name of the later snapshot |
| `limit` | int | No | Max classes to return (default: 30) |

### start_jfr_recording

Start a Java Flight Recorder recording for allocation profiling. Only needed if JFR was not started automatically via `jmx_monitoring`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `duration` | string | No | Recording duration (e.g., `60s`, `5m`). Omit for continuous. |
| `settings` | string | No | `default` or `profile` (default: `default`) |

### stop_jfr_recording

Dump and stop the active JFR recording.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |

### get_allocation_report

Aggregate allocation samples from a JFR recording. Shows top allocating classes and their stack traces.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `limit` | int | No | Max classes to return (default: 20) |

### get_thread_dump

Thread dump with optional regex filtering by thread name or stack content.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `filter` | string | No | Regex to filter thread blocks |

### get_native_memory

Native Memory Tracking summary from `jcmd VM.native_memory`. Requires NMT to have been enabled via `jmx_monitoring`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |

### start_memory_monitor

Start background `jstat` sampling that writes heap metrics to a JSONL timeline for trend analysis. Automatically stops when the target process exits.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `interval_seconds` | int | No | Seconds between samples (default: 5) |

### get_memory_timeline

Read timeline samples and compute trend analysis (heap growth rate, GC frequency, estimated time to OOM).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `run_id` | string | Yes | Test run ID |
| `last_n` | int | No | Return only the last N samples |

## Typical Workflow

```
1. Start test with monitoring:
   start_test_run  module:"ml"  test_classes:["OobleckDecoderTest"]
                   jmx_monitoring:true  jvm_args:["-Xmx4g"]

2. Attach and verify connectivity:
   attach_to_run  run_id:"<id>"

3. Start continuous monitoring:
   start_memory_monitor  run_id:"<id>"  interval_seconds:5

4. Take baseline histogram:
   get_class_histogram  run_id:"<id>"  snapshot_id:"baseline"

5. Wait for test to progress, then take a second snapshot:
   get_class_histogram  run_id:"<id>"  snapshot_id:"after_5min"

6. Diff to find leaking classes:
   diff_class_histogram  run_id:"<id>"  before_snapshot:"baseline"  after_snapshot:"after_5min"

7. Check allocation hotspots:
   get_allocation_report  run_id:"<id>"

8. Review growth trend:
   get_memory_timeline  run_id:"<id>"

9. Check native memory (for PackedCollection / JNI leaks):
   get_native_memory  run_id:"<id>"
```

## Storage

Run data is stored under `tools/mcp/test-runner/runs/{run_id}/jmx/`:
- `jfr_recording.jfr` - Flight Recorder data
- `timeline.jsonl` - Memory monitor samples
- `snapshots/` - Named class histogram snapshots

## Architecture

```
ar-test-runner                    ar-jmx
(starts test, discovers PID)      (reads PID, runs diagnostics)
        |                                |
        |   runs/{id}/metadata.json      |
        +----------> forked_pid -------->+
        |                                |
        v                                v
   Maven Surefire                  jcmd / jstat / jfr
        |                                |
        v                                |
   ForkedBooter JVM  <-------------------+
   (JFR + NMT enabled)
```

The two servers communicate via the filesystem: `ar-test-runner` writes PID and config to `metadata.json`, and `ar-jmx` reads it.

## Limitations

- **Single fork only**: Supports `forkCount=1` (the default). Multiple forked JVMs are not tracked.
- **Same-user requirement**: `jcmd` requires the calling process and target JVM to run as the same OS user.
- **JDK 17 assumed**: JFR `--json` output parsing targets JDK 17.

## Dependencies

- Python: `mcp>=1.0.0`
- JDK tools: `jcmd`, `jstat`, `jps`, `jfr`
