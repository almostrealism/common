#!/usr/bin/env python3
"""
MCP JVM Diagnostics Server for Almost Realism.

Provides tools for JVM memory analysis and leak detection on forked test
JVMs via JDK diagnostic tools (jcmd, jstat, jfr). Integrates with the
ar-test-runner by reading run metadata to discover forked JVM PIDs.

Tools:
  attach_to_run         - Verify connectivity to a forked test JVM
  attach_to_pid         - Attach to any JVM by PID (creates synthetic run entry)
  get_heap_summary      - Heap pool sizes and utilization from jstat
  get_gc_stats          - GC cause, utilization, and timing from jstat
  get_class_histogram   - Object histogram from jcmd GC.class_histogram
  diff_class_histogram  - Per-class growth between two histogram snapshots (same-run or cross-run)
  start_jfr_recording   - Start a JFR recording on the target JVM
  stop_jfr_recording    - Dump and stop a JFR recording
  get_allocation_report - Aggregate allocation samples from a JFR recording
  get_thread_dump       - Thread dump with optional regex filtering
  get_native_memory     - Native Memory Tracking summary from jcmd
  start_memory_monitor  - Start background jstat sampling with JSONL output
  get_memory_timeline   - Read timeline samples and compute memory trends
"""

import asyncio
import json
import re
import uuid
from datetime import datetime
from pathlib import Path
from typing import Optional

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

from jvm_diagnostics import (
    is_process_alive,
    run_jcmd,
    run_jstat,
    find_forked_booter_pids,
    start_jfr_recording as _start_jfr,
    stop_jfr_recording as _stop_jfr,
    parse_jfr_json,
    ProcessNotFoundError,
    JVMDiagnosticsError,
)
from histogram_parser import parse_histogram, filter_histogram, diff_histograms
from jfr_parser import parse_allocation_events
from timeline import MemoryMonitor, read_timeline, compute_trend

# Paths - test-runner runs directory is a sibling
RUNS_DIR = Path(__file__).parent.parent / "test-runner" / "runs"

# Active memory monitors keyed by run_id
active_monitors: dict[str, MemoryMonitor] = {}

# Create MCP server
server = Server("ar-jmx")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def load_metadata(run_id: str) -> dict:
    """Load test run metadata. Raises ValueError if not found."""
    metadata_path = RUNS_DIR / run_id / "metadata.json"
    if not metadata_path.exists():
        raise ValueError(f"Run {run_id} not found (no metadata.json)")
    with open(metadata_path) as f:
        return json.load(f)


def get_forked_pid(run_id: str) -> int:
    """Extract the forked JVM PID from run metadata and verify it is alive.

    Raises:
        ValueError: If run not found or no forked PID recorded.
        ProcessNotFoundError: If the forked process is no longer running.
    """
    metadata = load_metadata(run_id)
    pid = metadata.get("forked_pid")
    if pid is None:
        raise ValueError(
            f"Run {run_id} has no forked_pid. "
            "Was jmx_monitoring=true when the test was started?"
        )
    pid = int(pid)
    if not is_process_alive(pid):
        raise ProcessNotFoundError(
            f"Forked JVM (PID {pid}) for run {run_id} is no longer running"
        )
    return pid


def _jmx_dir(run_id: str) -> Path:
    """Return the jmx subdirectory for a run, creating it if needed."""
    jmx_path = RUNS_DIR / run_id / "jmx"
    jmx_path.mkdir(parents=True, exist_ok=True)
    return jmx_path


def _error_response(error: str, run_id: str = "") -> list[TextContent]:
    """Build a structured error response."""
    return [TextContent(
        type="text",
        text=json.dumps({"error": error, "run_id": run_id}, indent=2)
    )]


def _ok_response(data: dict) -> list[TextContent]:
    """Build a structured success response."""
    return [TextContent(type="text", text=json.dumps(data, indent=2))]


# ---------------------------------------------------------------------------
# Tool definitions
# ---------------------------------------------------------------------------

@server.list_tools()
async def list_tools():
    """List available JVM diagnostic tools."""
    return [
        Tool(
            name="attach_to_run",
            description="Verify connectivity to a forked test JVM. Returns JVM version and basic info.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier from ar-test-runner"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_heap_summary",
            description="Get heap pool sizes, utilization, and GC totals from jstat -gc.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_gc_stats",
            description="Get GC cause, utilization percentages, and timing from jstat -gccause.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_class_histogram",
            description="Get live object histogram from jcmd GC.class_histogram. Optionally filter by class pattern and save a named snapshot for later diffing.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "pattern": {
                        "type": "string",
                        "description": "Regex pattern to filter class names (e.g., 'org\\.almostrealism')"
                    },
                    "sort_by": {
                        "type": "string",
                        "enum": ["bytes", "instances", "class_name"],
                        "description": "Sort field (default: bytes)"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max classes to return (default: 30)"
                    },
                    "snapshot_id": {
                        "type": "string",
                        "description": "Save raw histogram with this name for later diffing"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="diff_class_histogram",
            description="Compare two saved histogram snapshots and show per-class memory growth.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "before_snapshot": {
                        "type": "string",
                        "description": "Name of the earlier snapshot"
                    },
                    "after_snapshot": {
                        "type": "string",
                        "description": "Name of the later snapshot"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max classes to return (default: 30)"
                    },
                    "before_run_id": {
                        "type": "string",
                        "description": "Run ID for the before snapshot (defaults to run_id)"
                    },
                    "after_run_id": {
                        "type": "string",
                        "description": "Run ID for the after snapshot (defaults to run_id)"
                    }
                },
                "required": ["run_id", "before_snapshot", "after_snapshot"]
            }
        ),
        Tool(
            name="start_jfr_recording",
            description="Start a Java Flight Recorder recording on the forked test JVM for allocation profiling.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "duration": {
                        "type": "string",
                        "description": "Recording duration (e.g., '60s', '5m'). Omit for continuous."
                    },
                    "settings": {
                        "type": "string",
                        "enum": ["default", "profile"],
                        "description": "JFR settings profile (default: 'default')"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="stop_jfr_recording",
            description="Dump and stop the active JFR recording on the forked test JVM.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_allocation_report",
            description="Aggregate allocation samples from a JFR recording. Shows top allocating classes and their stack traces.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max classes to return (default: 20)"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_thread_dump",
            description="Get a thread dump from the forked test JVM. Optionally filter by regex.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "filter": {
                        "type": "string",
                        "description": "Regex pattern to filter thread names or stack content"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_native_memory",
            description="Get Native Memory Tracking summary from jcmd VM.native_memory. Requires NMT to be enabled via JVM args.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="start_memory_monitor",
            description="Start background jstat sampling that writes heap metrics to a JSONL timeline for trend analysis.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "interval_seconds": {
                        "type": "integer",
                        "minimum": 1,
                        "description": "Seconds between samples (default: 5)"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_memory_timeline",
            description="Read memory timeline samples and compute trend analysis (growth rate, GC frequency, estimated OOM time).",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "Test run identifier"
                    },
                    "last_n": {
                        "type": "integer",
                        "description": "Return only the last N samples"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="attach_to_pid",
            description="Attach to an arbitrary JVM process by PID. Creates a synthetic run entry so all other tools can be used with the returned run_id.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pid": {
                        "type": "integer",
                        "description": "JVM process ID to attach to"
                    },
                    "label": {
                        "type": "string",
                        "description": "Optional label for this JVM (e.g., 'AudioSceneOptimizer')"
                    }
                },
                "required": ["pid"]
            }
        ),
    ]


# ---------------------------------------------------------------------------
# Tool handlers
# ---------------------------------------------------------------------------

@server.call_tool()
async def call_tool(name: str, arguments: dict):
    """Handle tool calls."""
    run_id = arguments.get("run_id", "")

    try:
        if name == "attach_to_run":
            pid = get_forked_pid(run_id)
            version_info = run_jcmd(pid, "VM.version")
            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "alive": True,
                "vm_version": version_info.strip()
            })

        elif name == "get_heap_summary":
            pid = get_forked_pid(run_id)
            gc = run_jstat(pid, "gc")

            # Compute aggregates (jstat values are in KB)
            s0u = gc.get("S0U", 0.0)
            s1u = gc.get("S1U", 0.0)
            eu = gc.get("EU", 0.0)
            ou = gc.get("OU", 0.0)
            mu = gc.get("MU", 0.0)

            s0c = gc.get("S0C", 0.0)
            s1c = gc.get("S1C", 0.0)
            ec = gc.get("EC", 0.0)
            oc = gc.get("OC", 0.0)
            mc = gc.get("MC", 0.0)

            heap_used = s0u + s1u + eu + ou
            heap_capacity = s0c + s1c + ec + oc

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "heap_used_mb": round(heap_used / 1024, 2),
                "heap_capacity_mb": round(heap_capacity / 1024, 2),
                "heap_utilization_pct": round(heap_used / heap_capacity * 100, 1) if heap_capacity > 0 else 0,
                "pools": {
                    "survivor_0": {"used_mb": round(s0u / 1024, 2), "capacity_mb": round(s0c / 1024, 2)},
                    "survivor_1": {"used_mb": round(s1u / 1024, 2), "capacity_mb": round(s1c / 1024, 2)},
                    "eden": {"used_mb": round(eu / 1024, 2), "capacity_mb": round(ec / 1024, 2)},
                    "old_gen": {"used_mb": round(ou / 1024, 2), "capacity_mb": round(oc / 1024, 2)},
                    "metaspace": {"used_mb": round(mu / 1024, 2), "capacity_mb": round(mc / 1024, 2)},
                },
                "gc": {
                    "young_gc_count": gc.get("YGC", 0),
                    "young_gc_time_s": gc.get("YGCT", 0.0),
                    "full_gc_count": gc.get("FGC", 0),
                    "full_gc_time_s": gc.get("FGCT", 0.0),
                    "total_gc_time_s": gc.get("GCT", 0.0),
                }
            })

        elif name == "get_gc_stats":
            pid = get_forked_pid(run_id)
            gc = run_jstat(pid, "gccause")

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "utilization": {
                    "survivor_0_pct": gc.get("S0", 0.0),
                    "survivor_1_pct": gc.get("S1", 0.0),
                    "eden_pct": gc.get("E", 0.0),
                    "old_gen_pct": gc.get("O", 0.0),
                    "metaspace_pct": gc.get("M", 0.0),
                    "compressed_class_pct": gc.get("CCS", 0.0),
                },
                "gc_counts": {
                    "young_gc": gc.get("YGC", 0),
                    "young_gc_time_s": gc.get("YGCT", 0.0),
                    "full_gc": gc.get("FGC", 0),
                    "full_gc_time_s": gc.get("FGCT", 0.0),
                    "total_gc_time_s": gc.get("GCT", 0.0),
                },
                "last_gc_cause": gc.get("LGCC", "unknown"),
                "current_gc_cause": gc.get("GCC", "No GC"),
            })

        elif name == "get_class_histogram":
            pid = get_forked_pid(run_id)
            raw_output = run_jcmd(pid, "GC.class_histogram")

            # Save snapshot if requested
            snapshot_id = arguments.get("snapshot_id")
            if snapshot_id:
                snapshot_dir = _jmx_dir(run_id) / "snapshots"
                snapshot_dir.mkdir(parents=True, exist_ok=True)
                snapshot_path = snapshot_dir / f"{snapshot_id}.txt"
                with open(snapshot_path, "w") as f:
                    f.write(raw_output)

            # Parse and filter
            classes = parse_histogram(raw_output)
            filtered = filter_histogram(
                classes,
                pattern=arguments.get("pattern"),
                sort_by=arguments.get("sort_by", "bytes"),
                limit=arguments.get("limit", 30)
            )

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "total_classes": len(classes),
                "returned_classes": len(filtered),
                "snapshot_id": snapshot_id,
                "classes": filtered
            })

        elif name == "diff_class_histogram":
            before_id = arguments["before_snapshot"]
            after_id = arguments["after_snapshot"]

            before_run = arguments.get("before_run_id", run_id)
            after_run = arguments.get("after_run_id", run_id)

            before_path = _jmx_dir(before_run) / "snapshots" / f"{before_id}.txt"
            after_path = _jmx_dir(after_run) / "snapshots" / f"{after_id}.txt"

            if not before_path.exists():
                return _error_response(
                    f"Snapshot '{before_id}' not found in run {before_run}", run_id
                )
            if not after_path.exists():
                return _error_response(
                    f"Snapshot '{after_id}' not found in run {after_run}", run_id
                )

            before_classes = parse_histogram(before_path.read_text())
            after_classes = parse_histogram(after_path.read_text())
            diffs = diff_histograms(before_classes, after_classes)

            limit = arguments.get("limit", 30)
            diffs = diffs[:limit]

            return _ok_response({
                "run_id": run_id,
                "before_run_id": before_run,
                "after_run_id": after_run,
                "before_snapshot": before_id,
                "after_snapshot": after_id,
                "cross_run": before_run != after_run,
                "classes_with_growth": len([d for d in diffs if d["byte_growth"] > 0]),
                "top_growth": diffs
            })

        elif name == "start_jfr_recording":
            pid = get_forked_pid(run_id)
            jfr_dir = _jmx_dir(run_id)
            output_path = str(jfr_dir / "jfr_recording.jfr")

            output = _start_jfr(
                pid,
                duration=arguments.get("duration"),
                settings=arguments.get("settings", "default"),
                output_path=output_path
            )

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "status": "recording_started",
                "output_path": output_path,
                "jcmd_output": output.strip()
            })

        elif name == "stop_jfr_recording":
            pid = get_forked_pid(run_id)
            jfr_dir = _jmx_dir(run_id)
            output_path = str(jfr_dir / "jfr_recording.jfr")

            output = _stop_jfr(pid, output_path)

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "status": "recording_stopped",
                "output_path": output_path,
                "jcmd_output": output.strip()
            })

        elif name == "get_allocation_report":
            jfr_path = _jmx_dir(run_id) / "jfr_recording.jfr"
            if not jfr_path.exists():
                return _error_response(
                    f"No JFR recording found for run {run_id}. "
                    "Use start_jfr_recording first.",
                    run_id
                )

            json_text = parse_jfr_json(
                str(jfr_path), "jdk.ObjectAllocationSample"
            )
            allocations = parse_allocation_events(json_text)

            limit = arguments.get("limit", 20)
            allocations = allocations[:limit]

            return _ok_response({
                "run_id": run_id,
                "total_classes": len(allocations),
                "allocations": allocations
            })

        elif name == "get_thread_dump":
            pid = get_forked_pid(run_id)
            raw_dump = run_jcmd(pid, "Thread.print")

            filter_pattern = arguments.get("filter")
            if filter_pattern:
                try:
                    regex = re.compile(filter_pattern, re.IGNORECASE)
                    # Split into thread blocks (separated by blank lines)
                    blocks = raw_dump.split("\n\n")
                    matched = [b for b in blocks if regex.search(b)]
                    filtered_dump = "\n\n".join(matched)
                except re.error:
                    filtered_dump = raw_dump
            else:
                filtered_dump = raw_dump

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "thread_dump": filtered_dump,
                "filtered": filter_pattern is not None
            })

        elif name == "get_native_memory":
            pid = get_forked_pid(run_id)
            output = run_jcmd(pid, "VM.native_memory summary")

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "native_memory": output.strip()
            })

        elif name == "start_memory_monitor":
            pid = get_forked_pid(run_id)

            # Check if already monitoring
            if run_id in active_monitors and active_monitors[run_id].is_running:
                monitor = active_monitors[run_id]
                return _ok_response({
                    "run_id": run_id,
                    "status": "already_running",
                    "sample_count": monitor.sample_count,
                    "interval_seconds": monitor.interval_seconds
                })

            interval = arguments.get("interval_seconds", 5)
            monitor = MemoryMonitor(pid, run_id, RUNS_DIR, interval)
            monitor.start()
            active_monitors[run_id] = monitor

            return _ok_response({
                "run_id": run_id,
                "forked_pid": pid,
                "status": "monitor_started",
                "interval_seconds": interval,
                "timeline_path": str(monitor.timeline_path)
            })

        elif name == "get_memory_timeline":
            timeline_path = RUNS_DIR / run_id / "jmx" / "timeline.jsonl"
            if not timeline_path.exists():
                return _error_response(
                    f"No timeline data for run {run_id}. "
                    "Use start_memory_monitor first.",
                    run_id
                )

            last_n = arguments.get("last_n")
            samples = read_timeline(timeline_path, last_n)
            trend = compute_trend(samples)

            # Check if monitor is still running
            monitor_status = "not_running"
            if run_id in active_monitors:
                monitor = active_monitors[run_id]
                monitor_status = "running" if monitor.is_running else "stopped"

            return _ok_response({
                "run_id": run_id,
                "monitor_status": monitor_status,
                "trend": trend,
                "samples": samples
            })

        elif name == "attach_to_pid":
            pid = arguments["pid"]
            label = arguments.get("label", "")

            if not is_process_alive(pid):
                return _error_response(f"Process {pid} is not running", "")

            # Verify it's a JVM
            version_info = run_jcmd(pid, "VM.version")

            # Create synthetic run
            synthetic_run_id = uuid.uuid4().hex[:8]
            run_dir = RUNS_DIR / synthetic_run_id
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "jmx").mkdir(exist_ok=True)
            (run_dir / "jmx" / "snapshots").mkdir(exist_ok=True)

            metadata = {
                "run_id": synthetic_run_id,
                "source": "manual_attach",
                "forked_pid": pid,
                "label": label,
                "jmx_monitoring": False,
                "status": "attached",
                "started_at": datetime.now().isoformat(),
                "config": {},
            }
            with open(run_dir / "metadata.json", "w") as f:
                json.dump(metadata, f, indent=2)

            return _ok_response({
                "run_id": synthetic_run_id,
                "pid": pid,
                "label": label,
                "vm_version": version_info.strip(),
                "note": "Use this run_id with all other ar-jmx tools. "
                        "JFR and NMT are not available unless the JVM was started with those args."
            })

        else:
            return _error_response(f"Unknown tool: {name}")

    except (ValueError, ProcessNotFoundError, JVMDiagnosticsError) as e:
        return _error_response(str(e), run_id)
    except Exception as e:
        return _error_response(f"Unexpected error: {str(e)}", run_id)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main():
    """Run the MCP server."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream, write_stream,
            server.create_initialization_options()
        )


if __name__ == "__main__":
    asyncio.run(main())
