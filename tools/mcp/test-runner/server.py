#!/usr/bin/env python3
"""
MCP Test Runner Server for Almost Realism

Provides tools for running and managing test executions with:
- Parameterized test runs (depth, classes, methods)
- Async execution with run tracking
- Result retrieval from surefire reports
- Run history management
"""

import asyncio
import json
import os
import re
import shutil
import signal
import subprocess
import threading
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Optional

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# Configuration - derive project root from script location (tools/mcp/test-runner/server.py -> project root)
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent.resolve()
RUNS_DIR = Path(__file__).parent / "runs"
MAX_RUNS = 50
DEFAULT_MODULE = "utils"
DEFAULT_TIMEOUT = 30

# Ensure runs directory exists
RUNS_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class RunConfig:
    """Configuration for a test run."""
    depth: Optional[int] = None
    module: str = DEFAULT_MODULE
    test_classes: list = field(default_factory=list)
    test_methods: list = field(default_factory=list)
    timeout_minutes: int = DEFAULT_TIMEOUT
    jvm_args: list = field(default_factory=list)


@dataclass
class RunMetadata:
    """Metadata for a test run."""
    run_id: str
    config: dict
    status: str  # pending, running, completed, failed, timeout, cancelled
    started_at: str
    completed_at: Optional[str] = None
    exit_code: Optional[int] = None
    pid: Optional[int] = None
    command: str = ""


class TestRunner:
    """Manages test run execution and tracking."""

    def __init__(self):
        self.active_runs: dict[str, subprocess.Popen] = {}
        self.timeout_timers: dict[str, threading.Timer] = {}

    def generate_run_id(self) -> str:
        """Generate a short unique run ID."""
        return uuid.uuid4().hex[:8]

    def cleanup_old_runs(self):
        """Remove oldest runs if we exceed MAX_RUNS."""
        if not RUNS_DIR.exists():
            return

        runs = []
        for run_dir in RUNS_DIR.iterdir():
            if run_dir.is_dir():
                metadata_file = run_dir / "metadata.json"
                if metadata_file.exists():
                    try:
                        with open(metadata_file) as f:
                            meta = json.load(f)
                            runs.append((run_dir, meta.get("started_at", "")))
                    except Exception:
                        runs.append((run_dir, ""))

        # Sort by started_at (oldest first)
        runs.sort(key=lambda x: x[1])

        # Remove oldest runs if we exceed MAX_RUNS
        while len(runs) >= MAX_RUNS:
            old_run_dir, _ = runs.pop(0)
            try:
                shutil.rmtree(old_run_dir)
            except Exception:
                pass

    def build_maven_command(self, config: RunConfig) -> list[str]:
        """Build the maven test command."""
        cmd = ["mvn", "test", "-pl", config.module]

        # Add JVM args if specified
        if config.jvm_args:
            jvm_arg_str = " ".join(config.jvm_args)
            cmd.append(f"-DargLine={jvm_arg_str}")

        # Add test depth
        if config.depth is not None:
            cmd.append(f"-DAR_TEST_DEPTH={config.depth}")

        # Add test class/method filters
        if config.test_classes:
            cmd.append(f"-Dtest={','.join(config.test_classes)}")
        elif config.test_methods:
            tests = [f"{m['class']}#{m['method']}" for m in config.test_methods]
            cmd.append(f"-Dtest={','.join(tests)}")

        return cmd

    def start_run(self, config: RunConfig) -> tuple[str, str]:
        """Start a new test run. Returns (run_id, command)."""
        self.cleanup_old_runs()

        run_id = self.generate_run_id()
        run_dir = RUNS_DIR / run_id
        run_dir.mkdir(parents=True)

        # Build command
        cmd = self.build_maven_command(config)
        cmd_str = " ".join(cmd)

        # Set environment
        env = os.environ.copy()
        env["AR_HARDWARE_LIBS"] = "/tmp/ar_libs/"
        env["AR_HARDWARE_DRIVER"] = "native"

        # Create metadata
        metadata = RunMetadata(
            run_id=run_id,
            config=asdict(config),
            status="running",
            started_at=datetime.now().isoformat(),
            command=cmd_str
        )

        # Start process
        output_file = run_dir / "output.txt"
        with open(output_file, "w") as f:
            process = subprocess.Popen(
                cmd,
                stdout=f,
                stderr=subprocess.STDOUT,
                env=env,
                cwd=PROJECT_ROOT,
                preexec_fn=os.setsid  # Create new process group for cleanup
            )

        metadata.pid = process.pid
        self.active_runs[run_id] = process

        # Save metadata
        self._save_metadata(run_id, metadata)

        # Start completion watcher
        watcher = threading.Thread(
            target=self._watch_completion,
            args=(run_id, process, config.module),
            daemon=True
        )
        watcher.start()

        # Start timeout timer
        if config.timeout_minutes:
            timer = threading.Timer(
                config.timeout_minutes * 60,
                self._timeout_run,
                [run_id]
            )
            timer.start()
            self.timeout_timers[run_id] = timer

        return run_id, cmd_str

    def _watch_completion(self, run_id: str, process: subprocess.Popen, module: str):
        """Watch for process completion and update metadata."""
        exit_code = process.wait()

        # Cancel timeout timer if it exists
        if run_id in self.timeout_timers:
            self.timeout_timers[run_id].cancel()
            del self.timeout_timers[run_id]

        # Remove from active runs
        if run_id in self.active_runs:
            del self.active_runs[run_id]

        # Update metadata
        metadata = self._load_metadata(run_id)
        if metadata and metadata.get("status") == "running":
            metadata["completed_at"] = datetime.now().isoformat()
            metadata["exit_code"] = exit_code
            metadata["status"] = "completed" if exit_code == 0 else "failed"
            self._save_metadata_dict(run_id, metadata)

            # Copy surefire reports
            self._copy_surefire_reports(run_id, module)

    def _timeout_run(self, run_id: str):
        """Handle run timeout."""
        if run_id in self.active_runs:
            process = self.active_runs[run_id]
            try:
                # Kill the entire process group
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            except Exception:
                pass

            # Update metadata
            metadata = self._load_metadata(run_id)
            if metadata:
                metadata["completed_at"] = datetime.now().isoformat()
                metadata["status"] = "timeout"
                self._save_metadata_dict(run_id, metadata)

            if run_id in self.active_runs:
                del self.active_runs[run_id]

    def cancel_run(self, run_id: str) -> bool:
        """Cancel a running test. Returns True if cancelled."""
        if run_id in self.active_runs:
            process = self.active_runs[run_id]
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            except Exception:
                pass

            # Cancel timeout timer
            if run_id in self.timeout_timers:
                self.timeout_timers[run_id].cancel()
                del self.timeout_timers[run_id]

            # Update metadata
            metadata = self._load_metadata(run_id)
            if metadata:
                metadata["completed_at"] = datetime.now().isoformat()
                metadata["status"] = "cancelled"
                self._save_metadata_dict(run_id, metadata)

            del self.active_runs[run_id]
            return True

        return False

    def _copy_surefire_reports(self, run_id: str, module: str):
        """Copy surefire reports to run directory, only those modified after run started."""
        reports_src = PROJECT_ROOT / module / "target" / "surefire-reports"
        reports_dst = RUNS_DIR / run_id / "reports"

        if not reports_src.exists():
            return

        # Get run start time
        metadata = self._load_metadata(run_id)
        if not metadata:
            return
        run_start = datetime.fromisoformat(metadata["started_at"])

        # Create destination directory
        reports_dst.mkdir(parents=True, exist_ok=True)

        # Copy only reports modified after run started
        for xml_file in reports_src.glob("TEST-*.xml"):
            try:
                file_mtime = datetime.fromtimestamp(xml_file.stat().st_mtime)
                if file_mtime >= run_start:
                    shutil.copy2(xml_file, reports_dst / xml_file.name)
            except Exception:
                pass

    def _save_metadata(self, run_id: str, metadata: RunMetadata):
        """Save run metadata."""
        metadata_file = RUNS_DIR / run_id / "metadata.json"
        with open(metadata_file, "w") as f:
            json.dump(asdict(metadata), f, indent=2)

    def _save_metadata_dict(self, run_id: str, metadata: dict):
        """Save run metadata from dict."""
        metadata_file = RUNS_DIR / run_id / "metadata.json"
        with open(metadata_file, "w") as f:
            json.dump(metadata, f, indent=2)

    def _load_metadata(self, run_id: str) -> Optional[dict]:
        """Load run metadata."""
        metadata_file = RUNS_DIR / run_id / "metadata.json"
        if metadata_file.exists():
            with open(metadata_file) as f:
                return json.load(f)
        return None

    def get_run_status(self, run_id: str) -> Optional[dict]:
        """Get status of a run including test counts from reports."""
        metadata = self._load_metadata(run_id)
        if not metadata:
            return None

        # Check if process is still running
        if run_id in self.active_runs:
            process = self.active_runs[run_id]
            if process.poll() is None:
                metadata["status"] = "running"

        # Calculate duration
        started = datetime.fromisoformat(metadata["started_at"])
        if metadata.get("completed_at"):
            completed = datetime.fromisoformat(metadata["completed_at"])
            metadata["duration_seconds"] = (completed - started).total_seconds()
        else:
            metadata["duration_seconds"] = (datetime.now() - started).total_seconds()

        # Parse surefire reports for test counts
        reports_dir = RUNS_DIR / run_id / "reports"
        if reports_dir.exists():
            counts = self._parse_test_counts(reports_dir)
            metadata.update(counts)

        return metadata

    def _parse_test_counts(self, reports_dir: Path) -> dict:
        """Parse surefire reports for test counts."""
        counts = {"tests_run": 0, "failures": 0, "errors": 0, "skipped": 0}

        for xml_file in reports_dir.glob("TEST-*.xml"):
            try:
                tree = ET.parse(xml_file)
                root = tree.getroot()
                counts["tests_run"] += int(root.get("tests", 0))
                counts["failures"] += int(root.get("failures", 0))
                counts["errors"] += int(root.get("errors", 0))
                counts["skipped"] += int(root.get("skipped", 0))
            except Exception:
                pass

        return counts

    def get_run_output(self, run_id: str, tail: Optional[int] = None,
                       filter_pattern: Optional[str] = None) -> Optional[dict]:
        """Get output from a run."""
        output_file = RUNS_DIR / run_id / "output.txt"
        if not output_file.exists():
            return None

        with open(output_file) as f:
            lines = f.readlines()

        # Apply filter if specified
        if filter_pattern:
            try:
                pattern = re.compile(filter_pattern)
                lines = [l for l in lines if pattern.search(l)]
            except re.error:
                pass

        # Apply tail if specified
        truncated = False
        if tail and len(lines) > tail:
            lines = lines[-tail:]
            truncated = True

        return {
            "run_id": run_id,
            "output": "".join(lines),
            "truncated": truncated,
            "total_lines": len(lines)
        }

    def get_run_failures(self, run_id: str) -> Optional[dict]:
        """Get detailed failure information from a run."""
        reports_dir = RUNS_DIR / run_id / "reports"
        if not reports_dir.exists():
            return {"run_id": run_id, "failures": [], "all_tests": []}

        failures = []
        all_tests = []

        for xml_file in reports_dir.glob("TEST-*.xml"):
            try:
                tree = ET.parse(xml_file)
                root = tree.getroot()

                for testcase in root.findall("testcase"):
                    classname = testcase.get("classname", "")
                    name = testcase.get("name", "")
                    time_sec = float(testcase.get("time", 0))

                    test_info = {
                        "class": classname,
                        "method": name,
                        "time_seconds": time_sec,
                        "status": "passed"
                    }

                    # Check for failure
                    failure = testcase.find("failure")
                    error = testcase.find("error")
                    skipped = testcase.find("skipped")

                    if failure is not None:
                        test_info["status"] = "failed"
                        failures.append({
                            "class": classname,
                            "method": name,
                            "time_seconds": time_sec,
                            "type": failure.get("type", ""),
                            "message": failure.get("message", ""),
                            "stacktrace": failure.text or ""
                        })
                    elif error is not None:
                        test_info["status"] = "error"
                        failures.append({
                            "class": classname,
                            "method": name,
                            "time_seconds": time_sec,
                            "type": error.get("type", ""),
                            "message": error.get("message", ""),
                            "stacktrace": error.text or ""
                        })
                    elif skipped is not None:
                        test_info["status"] = "skipped"

                    all_tests.append(test_info)
            except Exception:
                pass

        return {
            "run_id": run_id,
            "failures": failures,
            "all_tests": all_tests
        }

    def list_runs(self, limit: int = 10, status_filter: Optional[str] = None) -> list[dict]:
        """List recent runs."""
        runs = []

        for run_dir in RUNS_DIR.iterdir():
            if run_dir.is_dir():
                metadata = self._load_metadata(run_dir.name)
                if metadata:
                    # Check if process is still running
                    if run_dir.name in self.active_runs:
                        process = self.active_runs[run_dir.name]
                        if process.poll() is None:
                            metadata["status"] = "running"

                    if status_filter and metadata.get("status") != status_filter:
                        continue

                    runs.append({
                        "run_id": metadata["run_id"],
                        "status": metadata["status"],
                        "started_at": metadata["started_at"],
                        "config": metadata.get("config", {})
                    })

        # Sort by started_at (newest first)
        runs.sort(key=lambda x: x["started_at"], reverse=True)

        return runs[:limit]


# Global runner instance
runner = TestRunner()

# Create MCP server
server = Server("ar-test-runner")


@server.list_tools()
async def list_tools():
    """List available tools."""
    return [
        Tool(
            name="start_test_run",
            description="Start a new test run asynchronously. Returns a run_id for tracking.",
            inputSchema={
                "type": "object",
                "properties": {
                    "depth": {
                        "type": "integer",
                        "minimum": 0,
                        "maximum": 10,
                        "description": "AR_TEST_DEPTH value (0-10). Omit for no limit."
                    },
                    "module": {
                        "type": "string",
                        "description": f"Maven module to test (default: {DEFAULT_MODULE})"
                    },
                    "test_classes": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of test class names to run"
                    },
                    "test_methods": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "class": {"type": "string"},
                                "method": {"type": "string"}
                            },
                            "required": ["class", "method"]
                        },
                        "description": "List of specific test methods to run"
                    },
                    "timeout_minutes": {
                        "type": "integer",
                        "minimum": 1,
                        "description": f"Max run time in minutes (default: {DEFAULT_TIMEOUT})"
                    },
                    "jvm_args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Additional JVM arguments (e.g., [\"-Xmx4g\"])"
                    }
                }
            }
        ),
        Tool(
            name="get_run_status",
            description="Get the status of a test run including test counts and duration.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_run_output",
            description="Get the console output from a test run.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    },
                    "tail": {
                        "type": "integer",
                        "description": "Only return last N lines"
                    },
                    "filter": {
                        "type": "string",
                        "description": "Regex pattern to filter lines"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_run_failures",
            description="Get detailed failure information and all test results with timing.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="list_runs",
            description="List recent test runs.",
            inputSchema={
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Max runs to return (default: 10)"
                    },
                    "status": {
                        "type": "string",
                        "enum": ["running", "completed", "failed", "timeout", "cancelled"],
                        "description": "Filter by status"
                    }
                }
            }
        ),
        Tool(
            name="cancel_run",
            description="Cancel a running test.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    }
                },
                "required": ["run_id"]
            }
        )
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict):
    """Handle tool calls."""
    try:
        if name == "start_test_run":
            config = RunConfig(
                depth=arguments.get("depth"),
                module=arguments.get("module", DEFAULT_MODULE),
                test_classes=arguments.get("test_classes", []),
                test_methods=arguments.get("test_methods", []),
                timeout_minutes=arguments.get("timeout_minutes", DEFAULT_TIMEOUT),
                jvm_args=arguments.get("jvm_args", [])
            )
            run_id, command = runner.start_run(config)
            return [TextContent(
                type="text",
                text=json.dumps({
                    "run_id": run_id,
                    "status": "started",
                    "command": command
                }, indent=2)
            )]

        elif name == "get_run_status":
            run_id = arguments["run_id"]
            status = runner.get_run_status(run_id)
            if status is None:
                return [TextContent(
                    type="text",
                    text=json.dumps({"error": f"Run {run_id} not found"})
                )]
            return [TextContent(
                type="text",
                text=json.dumps(status, indent=2)
            )]

        elif name == "get_run_output":
            run_id = arguments["run_id"]
            output = runner.get_run_output(
                run_id,
                tail=arguments.get("tail"),
                filter_pattern=arguments.get("filter")
            )
            if output is None:
                return [TextContent(
                    type="text",
                    text=json.dumps({"error": f"Run {run_id} not found"})
                )]
            return [TextContent(
                type="text",
                text=json.dumps(output, indent=2)
            )]

        elif name == "get_run_failures":
            run_id = arguments["run_id"]
            failures = runner.get_run_failures(run_id)
            if failures is None:
                return [TextContent(
                    type="text",
                    text=json.dumps({"error": f"Run {run_id} not found"})
                )]
            return [TextContent(
                type="text",
                text=json.dumps(failures, indent=2)
            )]

        elif name == "list_runs":
            runs = runner.list_runs(
                limit=arguments.get("limit", 10),
                status_filter=arguments.get("status")
            )
            return [TextContent(
                type="text",
                text=json.dumps({"runs": runs}, indent=2)
            )]

        elif name == "cancel_run":
            run_id = arguments["run_id"]
            cancelled = runner.cancel_run(run_id)
            return [TextContent(
                type="text",
                text=json.dumps({
                    "run_id": run_id,
                    "status": "cancelled" if cancelled else "not_found"
                })
            )]

        else:
            return [TextContent(
                type="text",
                text=json.dumps({"error": f"Unknown tool: {name}"})
            )]

    except Exception as e:
        return [TextContent(
            type="text",
            text=json.dumps({"error": str(e)})
        )]


async def main():
    """Run the MCP server."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
