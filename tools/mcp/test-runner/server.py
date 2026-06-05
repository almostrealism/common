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
import atexit
import json
import os
import re
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Optional

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# Shared MCP helpers (tools/mcp/common); imported as top-level modules to avoid
# triggering the package __init__'s heavier dependencies.
_COMMON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)
from polling import block_until_terminal, resolve_block_timeout  # noqa: E402

# Preflight seeding of upstream module artifacts. Lives alongside server.py
# so the import is cheap and unambiguous regardless of where python is launched
# from. See preflight.py for the full rationale.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import preflight  # noqa: E402

# Configuration - derive project root from script location (tools/mcp/test-runner/server.py -> project root)
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent.resolve()
RUNS_DIR = Path(__file__).parent / "runs"
# External watcher process script — spawned in a detached session so it
# survives the python parent's death and can update metadata even when the
# in-process daemon thread cannot run (e.g., claude exits cleanly while a run
# is in progress and reaps its MCP stdio children).
WATCHER_SCRIPT = Path(__file__).parent / "watcher.py"
MAX_RUNS = 50
DEFAULT_MODULE = "engine/utils"
# Default test timeout in minutes. The harness inactivity monitor
# (ClaudeCodeJob.java:146) kills the agent process after 20 minutes of stdout
# silence, so the test-runner default is set 5 minutes below that ceiling so a
# legitimately long-running test fires this timer rather than the harness's
# inactivity kill (which is a confusing failure mode for the agent). Callers
# may pass a higher value, but values >20 are unsafe under the harness.
DEFAULT_TIMEOUT = 15
DEFAULT_OUTPUT_LINES = 200  # Default max lines for get_run_output
DEFAULT_STACKTRACE_LINES = 30  # Max lines per stacktrace
MAX_OUTPUT_BYTES = 50000  # ~50KB max response size
FORK_FAILURE_PATTERNS = [
    "Error occurred in starting fork",
    "ForkedBooter",
]
EARLY_EXIT_THRESHOLD_SECONDS = 15

# Run statuses that mean a test run has finished (used by the blocking
# get_run_status mode to decide when to stop waiting).
TERMINAL_RUN_STATES = frozenset({"completed", "failed", "timeout", "cancelled"})

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
    profile: Optional[str] = None
    jmx_monitoring: bool = False
    jfr_settings: str = "default"
    repetitions: int = 1


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
    jmx_monitoring: bool = False
    forked_pid: Optional[int] = None
    instruction_set_output_dir: Optional[str] = None
    repetitions: int = 1
    current_invocation: int = 0
    invocations: list = field(default_factory=list)


class TestRunner:
    """Manages test run execution and tracking."""

    def __init__(self):
        self.active_runs: dict[str, subprocess.Popen] = {}
        self.timeout_timers: dict[str, threading.Timer] = {}

    def generate_run_id(self) -> str:
        """Generate a short unique run ID."""
        return uuid.uuid4().hex[:8]

    def _write_preflight_section(self, output_file: Path, header: str,
                                  body: str = "") -> None:
        """Write a clearly-delimited preflight section to the run's output file.

        The banner mirrors the section markers used by ar-build-validator
        so an agent scrolling through ``output.txt`` can immediately see
        which steps are preflight vs. test execution.
        """
        with open(output_file, "a") as handle:
            handle.write(f"\n{'=' * 60}\n")
            handle.write(f"[ar-test-runner] PREFLIGHT: {header}\n")
            handle.write(f"{'=' * 60}\n")
            if body:
                if not body.endswith("\n"):
                    body = body + "\n"
                handle.write(body)
            handle.write("\n")

    def _run_preflight(self, run_id: str, run_dir: Path,
                       module: str) -> "preflight.PreflightResult":
        """Run the upstream-artifact preflight and persist its output.

        Returns the :class:`preflight.PreflightResult` produced by
        :func:`preflight.seed_upstream_artifacts`. Output emitted by
        Maven during the seed is appended to ``run_dir/output.txt`` so
        ``get_run_output`` shows it alongside (and before) the test run
        output.

        Failures inside the preflight helper itself (for example, a
        ``pom.xml`` that fails to parse for a reason the helper does
        not already swallow) are reported as a synthetic ``"failed"``
        result so the caller can short-circuit cleanly without
        spawning Maven on a broken setup.
        """
        output_file = run_dir / "output.txt"

        try:
            missing = preflight.find_missing_upstream_artifacts(
                PROJECT_ROOT, module)
        except Exception as exc:  # noqa: BLE001
            # Inspection failures short-circuit the run (action="failed"), mirroring seed failures.
            # find_missing_upstream_artifacts already returns [] on expected filesystem errors, so this
            # branch should only fire on truly unexpected exceptions.
            # If that behavior is undesirable, consider letting the test invocation proceed instead.
            result = preflight.PreflightResult(
                action="failed",
                exit_code=1,
                reason=f"preflight inspection failed: {exc}",
            )
            self._write_preflight_section(
                output_file,
                "INSPECTION FAILED",
                f"Could not determine upstream dependencies: {exc}\n"
                "The test invocation is aborted due to this inspection error.",
            )
            return result

        if not missing:
            self._write_preflight_section(
                output_file,
                "skipped (upstream artifacts present)",
                f"All direct org.almostrealism dependencies of {module}\n"
                "are already installed in ~/.m2; no seed needed.",
            )
            return preflight.PreflightResult(
                action="skipped",
                reason="All direct org.almostrealism dependencies already installed",
            )

        missing_summary = ", ".join(
            f"{m.artifact_id}:{m.version}" for m in missing[:8])
        if len(missing) > 8:
            missing_summary += f", ... (+{len(missing) - 8} more)"
        self._write_preflight_section(
            output_file,
            f"seeding {len(missing)} upstream artifact(s)",
            f"Missing direct dependencies for {module}: {missing_summary}\n"
            f"Running: {' '.join(preflight.build_seed_command(module))}\n"
            "(This makes the FIRST ar-test-runner call in a fresh worktree "
            "self-sufficient — subsequent calls skip this step.)",
        )

        def _writer(chunk: str) -> None:
            try:
                with open(output_file, "a") as handle:
                    handle.write(chunk)
            except OSError:
                # An output-write failure must never break the preflight.
                pass

        result = preflight.seed_upstream_artifacts(
            PROJECT_ROOT, module, output_writer=_writer)

        if result.action == "seeded":
            self._write_preflight_section(
                output_file,
                f"seeded {len(result.missing)} artifact(s) in "
                f"{result.duration_seconds:.1f}s",
                "Test invocation will now proceed.",
            )
        elif result.action == "failed":
            self._write_preflight_section(
                output_file,
                f"FAILED (mvn install exited {result.exit_code})",
                "The upstream artifacts could not be installed. The test "
                "invocation is skipped because Maven would fail with the "
                "same dependency-resolution error.",
            )
        return result

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

    def build_maven_command(self, config: RunConfig,
                            run_dir: Optional[Path] = None,
                            run_id: Optional[str] = None) -> list[str]:
        """Build the maven test command.

        Args:
            config: Run configuration.
            run_dir: Run directory (reserved for per-run output paths).
            run_id: Run identifier, used to isolate instruction set output files.
        """
        cmd = ["mvn", "test", "-pl", config.module]

        # Build JVM args. Deliberately inject NOTHING when jmx_monitoring is on:
        # jmx_monitoring's only job is to enable forked-PID discovery (below) so
        # the ar-jmx tools can attach. Every useful ar-jmx diagnostic -- thread
        # dump, JFR recording, class histogram, GC stats, allocation report --
        # works by attaching to the already-running JVM via jcmd and needs no
        # startup flag.
        #
        # Two startup flags were tried here and removed because both break the
        # surefire-forked JVM that this project uses:
        #   * -XX:StartFlightRecording makes the JVM print "Started recording
        #     N..." straight to stdout (the C++ JFR initialiser bypasses -Xlog
        #     filters). Surefire treats any direct stdout write from a forked
        #     JVM as channel corruption and kills the fork before any test runs.
        #     Start JFR via the ar-jmx start_jfr_recording tool instead.
        #   * -XX:NativeMemoryTracking=summary aborts this project's forked JVM
        #     (SIGABRT, exit 134) when the JNI hardware native library loads.
        #     A caller that specifically wants NMT and accepts that risk can
        #     still pass it explicitly through jvm_args.
        jvm_args = list(config.jvm_args)

        # Add JVM args if specified
        if jvm_args:
            jvm_arg_str = " ".join(jvm_args)
            cmd.append(f"-DargLine={jvm_arg_str}")

        # Add test depth
        if config.depth is not None:
            cmd.append(f"-DAR_TEST_DEPTH={config.depth}")

        # Add test profile (e.g., "pipeline" to skip comparison tests)
        if config.profile:
            cmd.append(f"-DAR_TEST_PROFILE={config.profile}")

        # Auto-inject instruction set output directory to prevent file collisions
        # between concurrent or sequential test runs. Uses <module>/results/<run_id>/
        # so each run's generated C/MSL files are isolated. Always injected unless
        # the caller explicitly specified a directory (the Java code only writes files
        # when monitoring is enabled, so unused properties have no overhead).
        if run_id:
            has_output_dir = any("AR_INSTRUCTION_SET_OUTPUT_DIR" in arg
                                 for arg in config.jvm_args)
            if not has_output_dir:
                output_dir = str(PROJECT_ROOT / config.module / "results" / run_id)
                cmd.append(f"-DAR_INSTRUCTION_SET_OUTPUT_DIR={output_dir}")

        # Add test class/method filters
        if config.test_classes:
            cmd.append(f"-Dtest={','.join(config.test_classes)}")
        elif config.test_methods:
            tests = [f"{m['class']}#{m['method']}" for m in config.test_methods]
            cmd.append(f"-Dtest={','.join(tests)}")

        return cmd

    def start_run(self, config: RunConfig) -> tuple[str, str]:
        """Start a new test run. Returns (run_id, command).

        The first time this is invoked in a fresh worktree, the
        upstream ``ar-*`` modules referenced by ``config.module`` may
        not yet be installed in ``~/.m2/repository/``. To avoid the
        previous fail→install→retry cycle (which pushes agents toward
        bash ``mvn``), the run launches a preflight step that seeds
        any missing upstream artifacts via
        ``mvn -pl <module> -am install -DskipTests``. The preflight is
        a no-op when every direct ``org.almostrealism`` dependency is
        already present, so subsequent calls in the same worktree pay
        only an inspection cost (a few milliseconds).

        When the preflight install fails the run is short-circuited:
        the run is marked ``failed`` with the preflight banner left
        in ``output.txt``, and no Maven test process is launched. The
        agent sees a clear ``status == "failed"`` with the seed log
        attached, instead of a duplicate dependency-resolution error
        from the test invocation.
        """
        self.cleanup_old_runs()

        run_id = self.generate_run_id()
        run_dir = RUNS_DIR / run_id
        run_dir.mkdir(parents=True)

        # Create JMX subdirectories if monitoring is enabled
        if config.jmx_monitoring:
            (run_dir / "jmx").mkdir(parents=True, exist_ok=True)
            (run_dir / "jmx" / "snapshots").mkdir(parents=True, exist_ok=True)

        # Build command
        cmd = self.build_maven_command(config, run_dir, run_id)
        cmd_str = " ".join(cmd)

        # Extract instruction set output dir from command if injected
        iset_output_dir = None
        iset_prefix = "-DAR_INSTRUCTION_SET_OUTPUT_DIR="
        for part in cmd:
            if part.startswith(iset_prefix):
                iset_output_dir = part[len(iset_prefix):]
                break

        # Touch the output file so the preflight banner has somewhere to land.
        output_file = run_dir / "output.txt"
        output_file.write_text("")

        # Preflight: install missing upstream artifacts. Synchronous because
        # it must finish before the test process launches against the same
        # module. Skipped path is a few-millisecond pom scan; only the
        # genuinely-uninstalled case blocks for the duration of mvn install.
        preflight_result = self._run_preflight(run_id, run_dir, config.module)
        if preflight_result.action == "failed":
            # Short-circuit: mark the run failed and return early. The
            # preflight banner already explains the failure in output.txt.
            metadata = RunMetadata(
                run_id=run_id,
                config=asdict(config),
                status="failed",
                started_at=datetime.now().isoformat(),
                completed_at=datetime.now().isoformat(),
                exit_code=preflight_result.exit_code,
                command=cmd_str,
                jmx_monitoring=config.jmx_monitoring,
                instruction_set_output_dir=iset_output_dir,
                repetitions=config.repetitions,
            )
            self._save_metadata(run_id, metadata)
            return run_id, cmd_str

        # Multi-invocation path: delegate to _watch_repetitions thread
        if config.repetitions > 1:
            metadata = RunMetadata(
                run_id=run_id,
                config=asdict(config),
                status="running",
                started_at=datetime.now().isoformat(),
                command=cmd_str,
                jmx_monitoring=config.jmx_monitoring,
                instruction_set_output_dir=iset_output_dir,
                repetitions=config.repetitions,
                current_invocation=0,
                invocations=[]
            )
            self._save_metadata(run_id, metadata)

            # Start timeout timer (applies to entire run)
            if config.timeout_minutes:
                timer = threading.Timer(
                    config.timeout_minutes * 60,
                    self._timeout_run,
                    [run_id]
                )
                timer.start()
                self.timeout_timers[run_id] = timer

            # Launch repetition watcher thread
            threading.Thread(
                target=self._watch_repetitions,
                args=(run_id, config, run_dir),
                daemon=True
            ).start()

            return run_id, cmd_str

        # Single-invocation path (original behavior)
        env = os.environ.copy()
        env.pop("AR_HARDWARE_LIBS", None)  # Auto-detected by the system

        # Create metadata
        metadata = RunMetadata(
            run_id=run_id,
            config=asdict(config),
            status="running",
            started_at=datetime.now().isoformat(),
            command=cmd_str,
            jmx_monitoring=config.jmx_monitoring,
            instruction_set_output_dir=iset_output_dir
        )

        # Start process. The preflight banner is already in output.txt;
        # open in append mode so mvn output follows it instead of clobbering it.
        with open(output_file, "a") as f:
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

        # Spawn detached watcher subprocess. This is the durable backup to
        # the in-process daemon thread below: when the python parent dies
        # mid-run (e.g., claude exits cleanly and reaps its MCP children),
        # the daemon thread cannot run, but the watcher subprocess is in a
        # separate session and survives to write terminal metadata.
        self._spawn_watcher_subprocess(run_id, process.pid, config.module, run_dir)

        # Start completion watcher
        watcher = threading.Thread(
            target=self._watch_completion,
            args=(run_id, process, config.module, config, run_dir),
            daemon=True
        )
        watcher.start()

        # Start forked PID discovery if JMX monitoring is enabled
        if config.jmx_monitoring:
            pid_discovery = threading.Thread(
                target=self._discover_forked_pid_background,
                args=(process.pid, run_id),
                daemon=True
            )
            pid_discovery.start()

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

    def _spawn_watcher_subprocess(self, run_id: str, maven_pid: int, module: str,
                                  run_dir: Path) -> None:
        """Spawn the external watcher.py as a session-detached subprocess.

        The watcher polls the maven PID via `os.kill(pid, 0)`, then writes
        terminal metadata if the in-process daemon thread did not get the
        chance (because the python parent was killed mid-run).

        Failures to spawn the watcher are logged to stderr but do not abort
        the run: the in-process daemon thread remains the primary path.
        """
        if not WATCHER_SCRIPT.exists():
            sys.stderr.write(
                f"[ar-test-runner] watcher script not found at {WATCHER_SCRIPT}; "
                "skipping detached watcher\n")
            return
        metadata_path = RUNS_DIR / run_id / "metadata.json"
        output_path = RUNS_DIR / run_id / "output.txt"
        reports_dst = RUNS_DIR / run_id / "reports"
        try:
            subprocess.Popen(
                [sys.executable, str(WATCHER_SCRIPT),
                 str(maven_pid), str(metadata_path), str(output_path),
                 str(reports_dst), str(PROJECT_ROOT), module],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                stdin=subprocess.DEVNULL,
                start_new_session=True,
                close_fds=True,
            )
        except OSError as exc:
            sys.stderr.write(
                f"[ar-test-runner] failed to spawn watcher subprocess: {exc}\n")

    def _watch_completion(self, run_id: str, process: subprocess.Popen, module: str,
                          config: RunConfig = None, run_dir: Path = None):
        """Watch for process completion and update metadata."""
        start_time = datetime.now()
        exit_code = process.wait()
        elapsed_seconds = (datetime.now() - start_time).total_seconds()

        # Detect JMX-induced fork failure: early exit + non-zero + jmx enabled
        if (config is not None
                and config.jmx_monitoring
                and exit_code != 0
                and elapsed_seconds < EARLY_EXIT_THRESHOLD_SECONDS
                and self._is_fork_failure(run_id)):
            self._retry_without_jmx_args(run_id, config, run_dir, module)
            return  # Retry spawns its own watcher; this thread exits

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

    def _is_fork_failure(self, run_id: str) -> bool:
        """Check output.txt for Surefire fork failure patterns."""
        output_file = RUNS_DIR / run_id / "output.txt"
        if not output_file.exists():
            return False
        try:
            with open(output_file) as f:
                head = f.read(8192)  # Fork failures appear in first few KB
            return any(p in head for p in FORK_FAILURE_PATTERNS)
        except OSError:
            return False

    def _retry_without_jmx_args(self, run_id: str, config: RunConfig,
                                 run_dir: Path, module: str):
        """Retry a test run without JFR/NMT JVM arguments after a fork failure."""
        # Create degraded config (jmx_monitoring=False skips JFR/NMT in build_maven_command)
        degraded_config = RunConfig(
            depth=config.depth,
            module=config.module,
            test_classes=list(config.test_classes),
            test_methods=list(config.test_methods),
            timeout_minutes=config.timeout_minutes,
            jvm_args=list(config.jvm_args),
            profile=config.profile,
            jmx_monitoring=False,
        )
        cmd = self.build_maven_command(degraded_config, run_dir, run_id)

        # Log to output.txt
        output_file = run_dir / "output.txt"
        with open(output_file, "a") as f:
            f.write("\n[ar-test-runner] JMX monitoring: forked JVM failed to start with JFR/NMT arguments.\n")
            f.write("[ar-test-runner] Retrying without JFR/NMT. jstat-based monitoring will still be available.\n\n")

        # Start new process (append to output)
        env = os.environ.copy()
        env.pop("AR_HARDWARE_LIBS", None)  # Auto-detected by the system
        with open(output_file, "a") as f:
            new_process = subprocess.Popen(
                cmd, stdout=f, stderr=subprocess.STDOUT,
                env=env, cwd=PROJECT_ROOT, preexec_fn=os.setsid)

        self.active_runs[run_id] = new_process

        # Update metadata
        metadata = self._load_metadata(run_id)
        if metadata:
            metadata["pid"] = new_process.pid
            metadata["command"] = " ".join(cmd)
            metadata["jmx_monitoring_degraded"] = True
            metadata["jmx_retry_reason"] = "Fork failure with JFR/NMT arguments"
            self._save_metadata_dict(run_id, metadata)

        # Spawn detached watcher subprocess for the retry's maven PID.
        self._spawn_watcher_subprocess(run_id, new_process.pid, module, run_dir)

        # New watcher (config=None prevents infinite retry)
        threading.Thread(target=self._watch_completion,
                         args=(run_id, new_process, module),
                         daemon=True).start()

        # PID discovery for jstat-based monitoring
        threading.Thread(target=self._discover_forked_pid_background,
                         args=(new_process.pid, run_id),
                         daemon=True).start()

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

    def _get_ppid(self, pid: int) -> Optional[int]:
        """Get parent PID. Uses /proc on Linux, ps on macOS."""
        # Try /proc first (Linux)
        try:
            stat_path = Path(f"/proc/{pid}/stat")
            text = stat_path.read_text()
            close_paren = text.rfind(")")
            if close_paren == -1:
                return None
            fields = text[close_paren + 2:].split()
            if len(fields) >= 2:
                return int(fields[1])
        except (OSError, PermissionError, ValueError):
            pass

        # Fallback: ps (macOS / general Unix)
        try:
            result = subprocess.run(
                ["ps", "-o", "ppid=", "-p", str(pid)],
                capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0 and result.stdout.strip():
                return int(result.stdout.strip())
        except (subprocess.TimeoutExpired, FileNotFoundError, ValueError):
            pass

        return None

    def _is_descendant_of(self, pid: int, ancestor_pid: int) -> bool:
        """Check if pid is a descendant of ancestor_pid by walking the parent chain."""
        current = pid
        for _ in range(10):  # Max depth to prevent infinite loops
            ppid = self._get_ppid(current)
            if ppid is None or ppid <= 1:
                return False
            if ppid == ancestor_pid:
                return True
            current = ppid
        return False

    def _discover_forked_pid(self, maven_pid: int, run_id: str) -> Optional[int]:
        """Poll jps for a ForkedBooter process whose parent is the maven process.

        Polls every 1 second for up to 30 seconds. When found, writes the
        forked PID to the run metadata.

        Returns:
            The forked PID, or None if discovery timed out.
        """
        for _ in range(30):
            try:
                result = subprocess.run(
                    ["jps", "-l"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    for line in result.stdout.strip().split("\n"):
                        if "ForkedBooter" in line or "surefirebooter" in line:
                            parts = line.split(None, 1)
                            if parts:
                                try:
                                    candidate_pid = int(parts[0])
                                    # Verify this is a descendant of our maven process
                                    if self._is_descendant_of(candidate_pid, maven_pid):
                                        # Write to metadata
                                        metadata = self._load_metadata(run_id)
                                        if metadata:
                                            metadata["forked_pid"] = candidate_pid
                                            self._save_metadata_dict(run_id, metadata)
                                        return candidate_pid
                                except ValueError:
                                    pass
            except (subprocess.TimeoutExpired, FileNotFoundError):
                pass

            time.sleep(1)

        return None

    def _discover_forked_pid_background(self, maven_pid: int, run_id: str) -> None:
        """Run forked PID discovery in a daemon thread.

        On timeout, sets forked_pid_discovery_failed in metadata.
        """
        pid = self._discover_forked_pid(maven_pid, run_id)
        if pid is None:
            metadata = self._load_metadata(run_id)
            if metadata:
                metadata["forked_pid_discovery_failed"] = True
                self._save_metadata_dict(run_id, metadata)

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

    def _watch_repetitions(self, run_id: str, config: RunConfig, run_dir: Path):
        """Run the same test N times sequentially, collecting per-invocation results."""
        env = os.environ.copy()
        env.pop("AR_HARDWARE_LIBS", None)  # Auto-detected by the system

        cmd = self.build_maven_command(config, run_dir, run_id)
        output_file = run_dir / "output.txt"
        any_failed = False

        for invocation_num in range(1, config.repetitions + 1):
            # Check for cancellation or timeout
            metadata = self._load_metadata(run_id)
            if not metadata or metadata.get("status") in ("cancelled", "timeout"):
                return

            # Update current invocation
            metadata["current_invocation"] = invocation_num
            self._save_metadata_dict(run_id, metadata)

            # Write invocation marker to output
            with open(output_file, "a") as f:
                f.write(f"\n{'='*60}\n")
                f.write(f"[ar-test-runner] Invocation {invocation_num} of {config.repetitions}\n")
                f.write(f"{'='*60}\n\n")

            # Start subprocess (append to output)
            with open(output_file, "a") as f:
                process = subprocess.Popen(
                    cmd,
                    stdout=f,
                    stderr=subprocess.STDOUT,
                    env=env,
                    cwd=PROJECT_ROOT,
                    preexec_fn=os.setsid
                )

            # Track the current process so cancel_run and _timeout_run can kill it
            self.active_runs[run_id] = process

            # For the first invocation with JMX: handle fork failure retry
            if invocation_num == 1 and config.jmx_monitoring:
                threading.Thread(
                    target=self._discover_forked_pid_background,
                    args=(process.pid, run_id),
                    daemon=True
                ).start()

            # Wait for this invocation to complete
            inv_start = time.monotonic()
            exit_code = process.wait()
            inv_duration = time.monotonic() - inv_start

            # Detect JMX fork failure on first invocation
            if (invocation_num == 1
                    and config.jmx_monitoring
                    and exit_code != 0
                    and inv_duration < EARLY_EXIT_THRESHOLD_SECONDS
                    and self._is_fork_failure(run_id)):
                # Rebuild command without JMX args for remaining invocations
                degraded_config = RunConfig(
                    depth=config.depth,
                    module=config.module,
                    test_classes=list(config.test_classes),
                    test_methods=list(config.test_methods),
                    timeout_minutes=config.timeout_minutes,
                    jvm_args=list(config.jvm_args),
                    profile=config.profile,
                    jmx_monitoring=False,
                    repetitions=config.repetitions
                )
                cmd = self.build_maven_command(degraded_config, run_dir, run_id)

                with open(output_file, "a") as f:
                    f.write("\n[ar-test-runner] JMX monitoring: forked JVM failed. "
                            "Retrying invocation 1 without JFR/NMT.\n\n")

                metadata = self._load_metadata(run_id)
                if metadata:
                    metadata["jmx_monitoring_degraded"] = True
                    metadata["jmx_retry_reason"] = "Fork failure with JFR/NMT arguments"
                    self._save_metadata_dict(run_id, metadata)

                # Retry invocation 1
                with open(output_file, "a") as f:
                    f.write(f"\n{'='*60}\n")
                    f.write(f"[ar-test-runner] Invocation 1 of {config.repetitions} (retry)\n")
                    f.write(f"{'='*60}\n\n")

                with open(output_file, "a") as f:
                    process = subprocess.Popen(
                        cmd, stdout=f, stderr=subprocess.STDOUT,
                        env=env, cwd=PROJECT_ROOT, preexec_fn=os.setsid)

                self.active_runs[run_id] = process
                inv_start = time.monotonic()
                exit_code = process.wait()
                inv_duration = time.monotonic() - inv_start

            # Copy surefire reports for this invocation
            self._copy_surefire_reports_to_invocation(run_id, config.module, invocation_num)

            # Parse test counts from this invocation's reports
            inv_reports_dir = run_dir / "reports" / f"invocation_{invocation_num}"
            inv_counts = self._parse_test_counts(inv_reports_dir) if inv_reports_dir.exists() else {}

            # Record invocation result
            inv_status = "completed" if exit_code == 0 else "failed"
            if exit_code != 0:
                any_failed = True

            invocation_entry = {
                "invocation": invocation_num,
                "duration_seconds": round(inv_duration, 3),
                "exit_code": exit_code,
                "status": inv_status,
                **inv_counts
            }

            metadata = self._load_metadata(run_id)
            if metadata:
                metadata.setdefault("invocations", []).append(invocation_entry)
                self._save_metadata_dict(run_id, metadata)

        # All invocations complete
        if run_id in self.active_runs:
            del self.active_runs[run_id]

        # Cancel timeout timer
        if run_id in self.timeout_timers:
            self.timeout_timers[run_id].cancel()
            del self.timeout_timers[run_id]

        # Set overall status
        metadata = self._load_metadata(run_id)
        if metadata and metadata.get("status") == "running":
            metadata["completed_at"] = datetime.now().isoformat()
            metadata["status"] = "failed" if any_failed else "completed"
            self._save_metadata_dict(run_id, metadata)

    def _copy_surefire_reports_to_invocation(self, run_id: str, module: str, invocation_num: int):
        """Copy surefire reports to an invocation-specific subdirectory.

        Since invocations are sequential and Maven overwrites reports each time,
        no time filtering is needed.
        """
        reports_src = PROJECT_ROOT / module / "target" / "surefire-reports"
        reports_dst = RUNS_DIR / run_id / "reports" / f"invocation_{invocation_num}"

        if not reports_src.exists():
            return

        reports_dst.mkdir(parents=True, exist_ok=True)

        for xml_file in reports_src.glob("TEST-*.xml"):
            try:
                shutil.copy2(xml_file, reports_dst / xml_file.name)
            except Exception:
                pass

    @staticmethod
    def _compute_stats(values: list[float]) -> dict:
        """Compute statistical summary of a list of values.

        Returns dict with count, mean, median, std_dev, min, max, cv (coefficient of variation %).
        """
        if not values:
            return {"count": 0, "mean": 0, "median": 0, "std_dev": 0, "min": 0, "max": 0, "cv": 0}

        n = len(values)
        mean = statistics.mean(values)
        median = statistics.median(values)
        std_dev = statistics.stdev(values) if n >= 2 else 0
        cv = (std_dev / mean * 100) if mean > 0 else 0

        return {
            "count": n,
            "mean": round(mean, 3),
            "median": round(median, 3),
            "std_dev": round(std_dev, 3),
            "min": round(min(values), 3),
            "max": round(max(values), 3),
            "cv": round(cv, 1)
        }

    def get_run_timing(self, run_id: str) -> Optional[dict]:
        """Get timing analysis for a multi-invocation run.

        Returns per-invocation durations, aggregate stats, and per-test-method timing stats.
        """
        metadata = self._load_metadata(run_id)
        if not metadata:
            return None

        repetitions = metadata.get("repetitions", 1)
        if repetitions <= 1:
            return {"error": "get_run_timing is only available for multi-invocation runs (repetitions > 1). "
                             "Use get_run_status for single-invocation timing."}

        invocations = metadata.get("invocations", [])

        # Per-invocation summary
        inv_durations = [inv["duration_seconds"] for inv in invocations if "duration_seconds" in inv]
        invocation_stats = self._compute_stats(inv_durations)

        # Per-test-method stats across invocations
        reports_base = RUNS_DIR / run_id / "reports"
        test_method_times: dict[str, list[dict]] = {}  # key -> list of {time, status, invocation}

        for inv_dir in sorted(reports_base.glob("invocation_*")):
            inv_match = re.match(r"invocation_(\d+)", inv_dir.name)
            if not inv_match:
                continue
            inv_num = int(inv_match.group(1))

            for xml_file in inv_dir.glob("TEST-*.xml"):
                try:
                    tree = ET.parse(xml_file)
                    root = tree.getroot()
                    for testcase in root.findall("testcase"):
                        classname = testcase.get("classname", "")
                        method_name = testcase.get("name", "")
                        time_sec = float(testcase.get("time", 0))
                        key = f"{classname}#{method_name}"

                        status = "passed"
                        if testcase.find("failure") is not None:
                            status = "failed"
                        elif testcase.find("error") is not None:
                            status = "error"
                        elif testcase.find("skipped") is not None:
                            status = "skipped"

                        test_method_times.setdefault(key, []).append({
                            "time": time_sec,
                            "status": status,
                            "invocation": inv_num
                        })
                except Exception:
                    pass

        # Compute per-method stats
        test_method_stats = []
        for key, entries in test_method_times.items():
            times = [e["time"] for e in entries]
            failure_count = sum(1 for e in entries if e["status"] in ("failed", "error"))
            pass_count = sum(1 for e in entries if e["status"] == "passed")
            total = len(entries)
            pass_rate = round(pass_count / total * 100, 1) if total > 0 else 0

            test_method_stats.append({
                "test": key,
                "timing": self._compute_stats(times),
                "pass_rate": pass_rate,
                "failure_count": failure_count,
                "invocation_count": total
            })

        # Sort by mean time descending (slowest first)
        test_method_stats.sort(key=lambda x: x["timing"]["mean"], reverse=True)

        return {
            "run_id": run_id,
            "repetitions": repetitions,
            "invocations_completed": len(invocations),
            "invocation_stats": invocation_stats,
            "invocations": invocations,
            "test_method_stats": test_method_stats
        }

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
        repetitions = metadata.get("repetitions", 1)

        if repetitions > 1 and reports_dir.exists():
            # Aggregate counts across all invocation subdirectories
            counts = {"tests_run": 0, "failures": 0, "errors": 0, "skipped": 0}
            for inv_dir in sorted(reports_dir.glob("invocation_*")):
                inv_counts = self._parse_test_counts(inv_dir)
                for k in counts:
                    counts[k] += inv_counts.get(k, 0)
            metadata.update(counts)
            metadata["invocations_completed"] = len(metadata.get("invocations", []))
            metadata["invocations_total"] = repetitions
        elif reports_dir.exists():
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
                       filter_pattern: Optional[str] = None,
                       max_lines: Optional[int] = None) -> Optional[dict]:
        """Get output from a run.

        Args:
            run_id: The run identifier
            tail: Only return last N lines (overrides max_lines)
            filter_pattern: Regex pattern to filter lines
            max_lines: Max lines to return (default: DEFAULT_OUTPUT_LINES)
                       Set to 0 for unlimited (not recommended)
        """
        output_file = RUNS_DIR / run_id / "output.txt"
        if not output_file.exists():
            return None

        with open(output_file) as f:
            lines = f.readlines()

        total_lines = len(lines)

        # Apply filter if specified
        if filter_pattern:
            try:
                pattern = re.compile(filter_pattern)
                lines = [l for l in lines if pattern.search(l)]
            except re.error:
                pass

        filtered_lines = len(lines)
        truncated = False

        # Apply tail if specified (takes precedence)
        if tail and len(lines) > tail:
            lines = lines[-tail:]
            truncated = True
        elif max_lines is None:
            # Apply default limit - show head and tail
            max_lines = DEFAULT_OUTPUT_LINES
            if len(lines) > max_lines:
                head_lines = max_lines // 2
                tail_lines = max_lines - head_lines
                lines = (
                    lines[:head_lines] +
                    [f"\n... ({len(lines) - max_lines} lines truncated) ...\n\n"] +
                    lines[-tail_lines:]
                )
                truncated = True
        elif max_lines > 0 and len(lines) > max_lines:
            # Explicit limit requested
            head_lines = max_lines // 2
            tail_lines = max_lines - head_lines
            lines = (
                lines[:head_lines] +
                [f"\n... ({len(lines) - max_lines} lines truncated) ...\n\n"] +
                lines[-tail_lines:]
            )
            truncated = True

        return {
            "run_id": run_id,
            "output": "".join(lines),
            "truncated": truncated,
            "total_lines": total_lines,
            "filtered_lines": filtered_lines if filter_pattern else None
        }

    def _truncate_stacktrace(self, stacktrace: str, max_lines: int = DEFAULT_STACKTRACE_LINES) -> str:
        """Truncate a stacktrace to max_lines, keeping head and tail."""
        if not stacktrace:
            return ""
        lines = stacktrace.split('\n')
        if len(lines) <= max_lines:
            return stacktrace
        head = max_lines // 2
        tail = max_lines - head
        truncated_lines = (
            lines[:head] +
            [f"    ... ({len(lines) - max_lines} lines truncated) ..."] +
            lines[-tail:]
        )
        return '\n'.join(truncated_lines)

    def _parse_reports_dir(self, reports_dir: Path, include_all_tests: bool = False,
                           truncate_stacktraces: bool = True,
                           invocation: Optional[int] = None) -> tuple[list, list, dict]:
        """Parse surefire XML reports from a directory.

        Args:
            reports_dir: Directory containing TEST-*.xml files.
            include_all_tests: Whether to collect all test entries.
            truncate_stacktraces: Whether to truncate long stacktraces.
            invocation: If set, adds an 'invocation' field to each entry.

        Returns:
            Tuple of (failures, all_tests_or_empty, summary_dict).
        """
        failures = []
        all_tests = []
        summary = {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}

        for xml_file in reports_dir.glob("TEST-*.xml"):
            try:
                tree = ET.parse(xml_file)
                root = tree.getroot()

                for testcase in root.findall("testcase"):
                    classname = testcase.get("classname", "")
                    name = testcase.get("name", "")
                    time_sec = float(testcase.get("time", 0))
                    summary["total"] += 1

                    test_info = {
                        "class": classname,
                        "method": name,
                        "time_seconds": time_sec,
                        "status": "passed"
                    }
                    if invocation is not None:
                        test_info["invocation"] = invocation

                    # Check for failure
                    failure = testcase.find("failure")
                    error = testcase.find("error")
                    skipped = testcase.find("skipped")

                    if failure is not None:
                        test_info["status"] = "failed"
                        summary["failed"] += 1
                        stacktrace = failure.text or ""
                        if truncate_stacktraces:
                            stacktrace = self._truncate_stacktrace(stacktrace)
                        fail_entry = {
                            "class": classname,
                            "method": name,
                            "time_seconds": time_sec,
                            "type": failure.get("type", ""),
                            "message": failure.get("message", ""),
                            "stacktrace": stacktrace
                        }
                        if invocation is not None:
                            fail_entry["invocation"] = invocation
                        failures.append(fail_entry)
                    elif error is not None:
                        test_info["status"] = "error"
                        summary["error"] += 1
                        stacktrace = error.text or ""
                        if truncate_stacktraces:
                            stacktrace = self._truncate_stacktrace(stacktrace)
                        fail_entry = {
                            "class": classname,
                            "method": name,
                            "time_seconds": time_sec,
                            "type": error.get("type", ""),
                            "message": error.get("message", ""),
                            "stacktrace": stacktrace
                        }
                        if invocation is not None:
                            fail_entry["invocation"] = invocation
                        failures.append(fail_entry)
                    elif skipped is not None:
                        test_info["status"] = "skipped"
                        summary["skipped"] += 1
                    else:
                        summary["passed"] += 1

                    if include_all_tests:
                        all_tests.append(test_info)
            except Exception:
                pass

        return failures, all_tests, summary

    def get_run_failures(self, run_id: str, include_all_tests: bool = False,
                         truncate_stacktraces: bool = True) -> Optional[dict]:
        """Get detailed failure information from a run.

        Args:
            run_id: The run identifier
            include_all_tests: Include all test results, not just failures (default: False)
            truncate_stacktraces: Truncate long stacktraces (default: True)
        """
        reports_dir = RUNS_DIR / run_id / "reports"
        empty_result = {"run_id": run_id, "failures": [], "summary": {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}}

        if not reports_dir.exists():
            return empty_result

        metadata = self._load_metadata(run_id)
        repetitions = metadata.get("repetitions", 1) if metadata else 1

        if repetitions > 1:
            # Multi-invocation: iterate invocation subdirectories
            all_failures = []
            all_tests_list = []
            total_summary = {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}

            for inv_dir in sorted(reports_dir.glob("invocation_*")):
                inv_match = re.match(r"invocation_(\d+)", inv_dir.name)
                if not inv_match:
                    continue
                inv_num = int(inv_match.group(1))

                failures, tests, summary = self._parse_reports_dir(
                    inv_dir, include_all_tests, truncate_stacktraces, invocation=inv_num)
                all_failures.extend(failures)
                all_tests_list.extend(tests)
                for k in total_summary:
                    total_summary[k] += summary[k]

            result = {
                "run_id": run_id,
                "failures": all_failures,
                "summary": total_summary
            }
            if include_all_tests:
                result["all_tests"] = all_tests_list
            return result
        else:
            # Single-invocation: parse reports directly
            failures, all_tests_list, summary = self._parse_reports_dir(
                reports_dir, include_all_tests, truncate_stacktraces)

            result = {
                "run_id": run_id,
                "failures": failures,
                "summary": summary
            }
            if include_all_tests:
                result["all_tests"] = all_tests_list
            return result

    def abandon_running_runs(self) -> list[str]:
        """Mark every active run with ``status="abandoned"`` and return their IDs.

        Used as an atexit safety net: when the python parent (ar-test-runner)
        is shutting down, any run still in ``status="running"`` is marked
        ``abandoned`` so that the next inspector can distinguish "actually in
        progress" from "stranded by parent death". This is a complement to
        the detached watcher subprocess (which infers terminal status from
        output.txt); the watcher catches the case where maven completes after
        the parent dies, while this handler catches the inverse case where
        maven is still mid-run when the parent dies.
        """
        abandoned: list[str] = []
        if not RUNS_DIR.exists():
            return abandoned
        try:
            run_dirs = list(RUNS_DIR.iterdir())
        except OSError:
            return abandoned
        for run_dir in run_dirs:
            if not run_dir.is_dir():
                continue
            metadata_path = run_dir / "metadata.json"
            if not metadata_path.exists():
                continue
            try:
                with open(metadata_path) as f:
                    metadata = json.load(f)
            except (OSError, json.JSONDecodeError):
                continue
            if metadata.get("status") not in ("running", "pending"):
                continue
            metadata["status"] = "abandoned"
            metadata["abandoned_at"] = datetime.now().isoformat()
            metadata["abandoned_reason"] = (
                "ar-test-runner process exited while this run was still in progress")
            try:
                with open(metadata_path, "w") as f:
                    json.dump(metadata, f, indent=2)
                abandoned.append(run_dir.name)
            except OSError:
                pass
        return abandoned

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


def _on_shutdown_mark_abandoned():
    """atexit handler: mark any still-running runs as ``abandoned``.

    SIGKILL bypasses atexit, so this only fires on clean python exit
    (e.g., the MCP stdio loop ending because the parent claude process
    closed its end of the pipe). Combined with the detached watcher
    subprocesses spawned by ``start_run``, this ensures every metadata.json
    has a defined terminal state by the time a future agent inspects it.
    """
    try:
        abandoned = runner.abandon_running_runs()
        if abandoned:
            sys.stderr.write(
                "[ar-test-runner] atexit: marked "
                f"{len(abandoned)} run(s) as abandoned: {','.join(abandoned)}\n")
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write(f"[ar-test-runner] atexit handler failed: {exc}\n")


atexit.register(_on_shutdown_mark_abandoned)

# Create MCP server
server = Server("ar-test-runner")


@server.list_tools()
async def list_tools():
    """List available tools."""
    return [
        Tool(
            name="start_test_run",
            description=(
                "Start a new test run asynchronously. Returns a run_id for tracking.\n"
                "\n"
                "POLLING IS MANDATORY. This tool is asynchronous: it returns "
                "immediately with status=\"started\" while maven runs in the "
                "background. You MUST poll get_run_status(run_id) repeatedly "
                "until status is one of completed | failed | timeout | cancelled, "
                "then call get_run_failures(run_id) for the result, BEFORE "
                "ending your turn.\n"
                "\n"
                "If you end your turn while status==\"running\", the ar-test-runner "
                "subprocess will be killed by the harness — even if maven completes "
                "successfully — and your test result will be silently abandoned. "
                "The job will be marked DEGRADED and the next agent session will "
                "have to redo the work.\n"
                "\n"
                "Between polls, emit small productive tool calls (Read, Grep) "
                "every 30-60 seconds to keep the harness's inactivity clock alive. "
                "Do NOT use Bash sleep or ScheduleWakeup with delaySeconds>=300 "
                "to wait — both exceed the harness's 20-minute inactivity ceiling.\n"
                "\n"
                f"Default timeout_minutes is {DEFAULT_TIMEOUT}, set 5 minutes under the "
                "harness's 20-minute inactivity timeout. Values >20 are unsafe — "
                "the harness will kill the agent (and ar-test-runner) before the "
                "test-runner's own timer fires."
            ),
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
                        "description": (
                            f"Max run time in minutes (default: {DEFAULT_TIMEOUT}). "
                            "Values >20 are unsafe under the harness's "
                            "20-minute inactivity timeout."
                        )
                    },
                    "jvm_args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Additional JVM arguments (e.g., [\"-Xmx4g\"])"
                    },
                    "profile": {
                        "type": "string",
                        "description": "Test profile name (sets AR_TEST_PROFILE). Use 'pipeline' to skip comparison tests."
                    },
                    "jmx_monitoring": {
                        "type": "boolean",
                        "description": "Enable JMX monitoring: discovers the forked test JVM PID so ar-jmx tools can attach (default: false). Injects no JVM startup flags -- thread dumps, JFR, class histograms, GC stats and allocation reports all attach via jcmd at runtime. (JFR and NMT are NOT auto-injected: -XX:StartFlightRecording corrupts the surefire fork channel via stdout, and -XX:NativeMemoryTracking aborts this project's JVM when the JNI hardware library loads. Start JFR via ar-jmx start_jfr_recording; pass NMT explicitly through jvm_args only if you accept the crash risk.)"
                    },
                    "jfr_settings": {
                        "type": "string",
                        "enum": ["default", "profile"],
                        "description": "Accepted for backward compatibility but no longer wired to JVM startup; pass the chosen profile to ar-jmx start_jfr_recording when you call it (default: 'default')"
                    },
                    "repetitions": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 100,
                        "description": "Number of times to run the test (default: 1). When > 1, runs the test N times sequentially under one run_id. Use get_run_timing to get statistical analysis of results."
                    }
                }
            }
        ),
        Tool(
            name="get_run_status",
            description=(
                "Get the status of a test run including test counts and duration. "
                "Returns immediately by default; poll until status is completed, failed, "
                "timeout, or cancelled.\n\n"
                "Set block=true to have the server wait until the run reaches a terminal "
                "state before responding, so you can wait for completion with one call "
                "instead of polling. The wait is bounded by timeout_seconds; if it elapses "
                "the latest (still-running) status is returned. Use blocking when you have "
                "nothing else to do meanwhile; otherwise return and do other work."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    },
                    "block": {
                        "type": "boolean",
                        "description": (
                            "When true, wait server-side until the run finishes (or "
                            "timeout_seconds elapses) before responding. Default: false."
                        )
                    },
                    "timeout_seconds": {
                        "type": "integer",
                        "minimum": 1,
                        "description": (
                            "Maximum seconds to wait when block=true (default: 600, max: 3600). "
                            "Ignored when block is false."
                        )
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_run_output",
            description=f"Get the console output from a test run. By default, returns at most {DEFAULT_OUTPUT_LINES} lines (head + tail) to avoid large payloads.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    },
                    "tail": {
                        "type": "integer",
                        "description": "Only return last N lines (overrides max_lines)"
                    },
                    "filter": {
                        "type": "string",
                        "description": "Regex pattern to filter lines"
                    },
                    "max_lines": {
                        "type": "integer",
                        "description": f"Max lines to return (default: {DEFAULT_OUTPUT_LINES}). Set to 0 for unlimited."
                    }
                },
                "required": ["run_id"]
            }
        ),
        Tool(
            name="get_run_failures",
            description="Get failure information with truncated stacktraces. Returns summary counts and failure details only (not all tests by default).",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "The run identifier"
                    },
                    "include_all_tests": {
                        "type": "boolean",
                        "description": "Include all test results, not just failures (default: false)"
                    },
                    "full_stacktraces": {
                        "type": "boolean",
                        "description": "Return full stacktraces without truncation (default: false)"
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
        ),
        Tool(
            name="get_run_timing",
            description="Get timing analysis for a multi-invocation run. Returns per-invocation durations, aggregate stats (mean, median, std_dev, min, max, CV%), and per-test-method timing stats sorted by slowest first.",
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
                jvm_args=arguments.get("jvm_args", []),
                profile=arguments.get("profile"),
                jmx_monitoring=arguments.get("jmx_monitoring", False),
                jfr_settings=arguments.get("jfr_settings", "default"),
                repetitions=arguments.get("repetitions", 1)
            )
            run_id, command = runner.start_run(config)
            response = {
                "run_id": run_id,
                "status": "started",
                "command": command
            }
            # Include instruction set output directory if it was auto-injected
            output_dir_prefix = "-DAR_INSTRUCTION_SET_OUTPUT_DIR="
            for part in command.split():
                if part.startswith(output_dir_prefix):
                    response["instruction_set_output_dir"] = part[len(output_dir_prefix):]
                    break
            return [TextContent(
                type="text",
                text=json.dumps(response, indent=2)
            )]

        elif name == "get_run_status":
            run_id = arguments["run_id"]
            if arguments.get("block"):
                status = await block_until_terminal(
                    lambda: runner.get_run_status(run_id),
                    TERMINAL_RUN_STATES,
                    timeout_seconds=resolve_block_timeout(arguments.get("timeout_seconds")),
                )
            else:
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
                filter_pattern=arguments.get("filter"),
                max_lines=arguments.get("max_lines")
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
            failures = runner.get_run_failures(
                run_id,
                include_all_tests=arguments.get("include_all_tests", False),
                truncate_stacktraces=not arguments.get("full_stacktraces", False)
            )
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

        elif name == "get_run_timing":
            run_id = arguments["run_id"]
            timing = runner.get_run_timing(run_id)
            if timing is None:
                return [TextContent(
                    type="text",
                    text=json.dumps({"error": f"Run {run_id} not found"})
                )]
            return [TextContent(
                type="text",
                text=json.dumps(timing, indent=2)
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
