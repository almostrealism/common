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
import signal
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field, asdict, replace
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
import preflight_runner  # noqa: E402
import reports  # noqa: E402
import run_store  # noqa: E402
import timing  # noqa: E402
import fork_discovery  # noqa: E402

# The target Maven project. Re-exported here because the MCP dispatch below
# resolves the caller's `project` argument, and because tests and other
# collaborators address these through the server module.
from project import resolve_ci_test_groups, resolve_project_root  # noqa: E402

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
# Output and stacktrace limits are owned by the collaborators that apply them;
# named here because the tool descriptions below quote them to callers.
DEFAULT_OUTPUT_LINES = run_store.DEFAULT_OUTPUT_LINES
DEFAULT_STACKTRACE_LINES = reports.DEFAULT_STACKTRACE_LINES
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
    project: str = ""
    module: str = DEFAULT_MODULE
    test_classes: list = field(default_factory=list)
    test_methods: list = field(default_factory=list)
    timeout_minutes: int = DEFAULT_TIMEOUT
    jvm_args: list = field(default_factory=list)
    profile: Optional[str] = None
    jmx_monitoring: bool = False
    jfr_settings: str = "default"
    repetitions: int = 1
    test_group: Optional[int] = None
    test_groups: Optional[int] = None

    def project_root(self) -> Path:
        """Return the resolved Maven project this run targets."""
        return resolve_project_root(self.project)

    def without_jmx(self) -> "RunConfig":
        """Return this configuration with JMX monitoring turned off.

        Used to retry a run whose forked JVM refused to start under the JMX
        arguments. Everything else is carried across, including the project the
        run targets — losing that would leave Maven running in one checkout
        while its generated output was directed at another. Copying the whole
        configuration rather than restating it field by field also means a
        field added later is carried without anyone having to remember to.
        """
        return replace(self, jmx_monitoring=False,
                       test_classes=list(self.test_classes),
                       test_methods=list(self.test_methods),
                       jvm_args=list(self.jvm_args))


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
        # Bound at construction so a test that redirects RUNS_DIR gets a runner
        # whose records land in the redirected directory.
        self.store = run_store.RunStore(RUNS_DIR)

    def generate_run_id(self) -> str:
        """Generate a short unique run ID."""
        return uuid.uuid4().hex[:8]

    def cleanup_old_runs(self):
        """Remove oldest runs if we exceed MAX_RUNS."""
        self.store.cleanup(MAX_RUNS)

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

        # Reproduce a CI test-matrix group. The CI `test` job runs the whole module
        # (no -Dtest filter) in a single surefire JVM with AR_TEST_GROUP/AR_TEST_GROUPS
        # set; TestDepthRule then skips every class whose name does not hash to the
        # group (Math.abs(className.hashCode()) % AR_TEST_GROUPS). Because the classes
        # that DO run share one JVM (surefire reuseForks=true, forkCount=1), static
        # state (interning tables, kernel/expression caches) accumulates across them
        # exactly as it does on CI -- which a single -Dtest=Class run can never
        # reproduce. Set test_group to run a group this way; test_groups defaults
        # to the AR_TEST_GROUPS value the CI workflow currently declares.
        if config.test_group is not None:
            cmd.append(f"-DAR_TEST_GROUP={config.test_group}")
            if config.test_groups is not None:
                cmd.append(f"-DAR_TEST_GROUPS={config.test_groups}")

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
                output_dir = str(config.project_root() / config.module
                                 / "results" / run_id)
                cmd.append(f"-DAR_INSTRUCTION_SET_OUTPUT_DIR={output_dir}")

        # Add test class/method filters. A -Dtest filter is mutually exclusive with a
        # group run: passing -Dtest restricts surefire to the named class(es), which
        # would defeat the whole point of test_group (running every class that hashes
        # to the group together in one JVM). When test_group is set, ignore class/method
        # filters so the full group runs.
        if config.test_group is None:
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
        preflight_result = preflight_runner.run(
            run_dir, config.module, config.project_root())
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
                cwd=config.project_root(),
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
        self._spawn_watcher_subprocess(run_id, process.pid, config.module, run_dir,
                                       config.project_root())

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
                                  run_dir: Path, project_root: Path) -> None:
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
                 str(reports_dst), str(project_root), module],
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
                          config: RunConfig, run_dir: Path):
        """Watch for process completion and update metadata.

        The config is required because finalising a run reads the project it
        targeted; a caller that omitted it would only fail at the very end,
        after the tests had already run.
        """
        start_time = datetime.now()
        exit_code = process.wait()
        elapsed_seconds = (datetime.now() - start_time).total_seconds()

        # Detect JMX-induced fork failure: early exit + non-zero + jmx enabled.
        # A retry is itself watched with jmx_monitoring off, so it cannot
        # re-enter this branch.
        if (config.jmx_monitoring
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
            self._copy_surefire_reports(run_id, module, config.project_root())

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
        # jmx_monitoring=False skips JFR/NMT in build_maven_command
        degraded_config = config.without_jmx()
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
                env=env, cwd=config.project_root(), preexec_fn=os.setsid)

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
        self._spawn_watcher_subprocess(run_id, new_process.pid, module, run_dir,
                                       config.project_root())

        # The degraded config is what the watcher needs: it names the project
        # whose reports are to be collected, and its jmx_monitoring is already
        # off, which is what stops this retry from retrying itself.
        threading.Thread(target=self._watch_completion,
                         args=(run_id, new_process, module,
                               degraded_config, run_dir),
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

    def _discover_forked_pid_background(self, maven_pid: int, run_id: str) -> None:
        """Run forked PID discovery in a daemon thread.

        Polls for as long as the run is active and the maven process is
        alive (see fork_discovery.discover_forked_pid). Writes forked_pid
        to the run metadata when found; sets forked_pid_discovery_failed
        when the run ends without the fork having been discovered.
        """
        def run_active() -> bool:
            metadata = self._load_metadata(run_id)
            return (metadata is not None
                    and metadata.get("status") in ("pending", "running")
                    and fork_discovery.pid_alive(maven_pid))

        pid = fork_discovery.discover_forked_pid(maven_pid, run_active)

        metadata = self._load_metadata(run_id)
        if not metadata:
            return
        if pid is not None:
            metadata["forked_pid"] = pid
        else:
            metadata["forked_pid_discovery_failed"] = True
        self._save_metadata_dict(run_id, metadata)

    def _copy_surefire_reports(self, run_id: str, module: str, project_root: Path):
        """Copy this run's surefire reports into its run directory.

        Reports older than the run's start belong to a previous run that left
        them in the module's target directory, so they are left behind.
        """
        metadata = self._load_metadata(run_id)
        if not metadata:
            return

        self._reports(run_id).collect_from(
            reports.module_output(project_root, module),
            modified_since=datetime.fromisoformat(metadata["started_at"]))

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

            # Wall-clock stamp taken before the process launches, so the report copy can
            # tell this invocation's reports from ones left by earlier runs. Distinct from
            # inv_start below, which is monotonic and only measures duration.
            inv_wall_start = datetime.now()

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
                    cwd=config.project_root(),
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
                degraded_config = config.without_jmx()
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
                        env=env, cwd=config.project_root(), preexec_fn=os.setsid)

                self.active_runs[run_id] = process
                inv_start = time.monotonic()
                exit_code = process.wait()
                inv_duration = time.monotonic() - inv_start

            # Copy surefire reports for this invocation
            self._copy_surefire_reports_to_invocation(
                    run_id, config.module, invocation_num,
                    config.project_root(), inv_wall_start)

            # Parse test counts from this invocation's reports
            inv_reports = self._reports(run_id).invocation(invocation_num)
            inv_counts = inv_reports.counts() if inv_reports.exists() else {}

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

    def _copy_surefire_reports_to_invocation(self, run_id: str, module: str,
                                             invocation_num: int, project_root: Path,
                                             invocation_start: datetime):
        """Copy this invocation's surefire reports to an invocation-specific subdirectory.

        Only reports written at or after ``invocation_start`` are copied. Maven overwrites
        the report for a class it actually runs, but leaves every other report in place, so
        ``target/surefire-reports`` accumulates results from earlier runs of other classes
        indefinitely. Copying the whole directory therefore counted those stale classes as
        part of this run: a 44-test class reported 58 tests because three unrelated classes
        (5, 4 and 5 tests) were still sitting in the directory. Single-invocation runs have
        always filtered by time; this is the same filter, per invocation.

        Args:
            run_id: the run identifier
            module: the Maven module under test
            invocation_num: 1-based invocation index
            project_root: the checkout the module's target directory lives under
            invocation_start: wall-clock time captured before this invocation launched
        """
        self._reports(run_id).invocation(invocation_num).collect_from(
            reports.module_output(project_root, module),
            modified_since=invocation_start)

    def get_run_timing(self, run_id: str) -> Optional[dict]:
        """Get timing analysis for a multi-invocation run.

        Returns per-invocation durations, aggregate stats, and per-test-method timing stats.
        """
        metadata = self._load_metadata(run_id)
        if not metadata:
            return None

        return timing.analyze(run_id, metadata, self._reports(run_id))

    def _save_metadata(self, run_id: str, metadata: RunMetadata):
        """Save run metadata."""
        self.store.save(run_id, asdict(metadata))

    def _save_metadata_dict(self, run_id: str, metadata: dict):
        """Save run metadata from dict."""
        self.store.save(run_id, metadata)

    def _load_metadata(self, run_id: str) -> Optional[dict]:
        """Load run metadata."""
        return self.store.load(run_id)

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
        collected = self._reports(run_id)
        repetitions = metadata.get("repetitions", 1)

        if collected.exists():
            metadata.update(collected.total_counts(repetitions))
            if repetitions > 1:
                metadata["invocations_completed"] = len(metadata.get("invocations", []))
                metadata["invocations_total"] = repetitions

        return metadata

    def _reports(self, run_id: str) -> reports.SurefireReports:
        """Return the surefire reports collected for a run."""
        return reports.SurefireReports(RUNS_DIR / run_id / "reports")

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
        return self.store.output(run_id, tail, filter_pattern, max_lines)

    def get_run_failures(self, run_id: str, include_all_tests: bool = False,
                         truncate_stacktraces: bool = True) -> Optional[dict]:
        """Get detailed failure information from a run.

        Args:
            run_id: The run identifier
            include_all_tests: Include all test results, not just failures (default: False)
            truncate_stacktraces: Truncate long stacktraces (default: True)
        """
        collected = self._reports(run_id)
        if not collected.exists():
            return reports.empty_failures(run_id)

        metadata = self._load_metadata(run_id)
        repetitions = metadata.get("repetitions", 1) if metadata else 1

        return {"run_id": run_id, **collected.collect_failures(
            include_all_tests, truncate_stacktraces, repetitions)}

    def abandon_running_runs(self) -> list[str]:
        """Mark every active run ``abandoned`` and return their IDs."""
        return self.store.abandon_running()

    def list_runs(self, limit: int = 10, status_filter: Optional[str] = None) -> list[dict]:
        """List recent runs."""
        runs = []

        for run_id in self.store.run_ids():
            metadata = self._load_metadata(run_id)
            if not metadata:
                continue

            # A run this process is still driving is running whatever the
            # stored record says, which may predate the process finishing.
            process = self.active_runs.get(run_id)
            if process is not None and process.poll() is None:
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
                    "project": {
                        "type": "string",
                        "description": (
                            "Root of the Maven project to test — the directory holding "
                            "the reactor pom.xml. Defaults to this repository. Use it to "
                            "test any other Maven project, such as a sibling checkout of "
                            "a downstream consumer: a relative path is resolved against "
                            "this repository, so \"../downstream\" names a sibling. "
                            "module is always relative to this root."
                        )
                    },
                    "module": {
                        "type": "string",
                        "description": (
                            f"Maven module to test, relative to the project root "
                            f"(default: {DEFAULT_MODULE}, which assumes the default project)"
                        )
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
                    },
                    "test_group": {
                        "type": "integer",
                        "minimum": 0,
                        "description": "Reproduce a CI test-matrix group: run the WHOLE module in one JVM with AR_TEST_GROUP set, so only classes hashing to this group run but they share JVM state exactly as on CI. Use this to reproduce failures that only appear when a test runs after others in the same JVM (static cache/intern-table pollution) -- a single test_classes run cannot reproduce these. Mutually exclusive with test_classes/test_methods (those are ignored when test_group is set). When test_groups is omitted, the group count is read from the CI workflow (AR_TEST_GROUPS in .github/workflows/analysis.yaml), so the partition always matches what CI actually runs. To fully mirror a CI job, also copy that job's hardware flags (AR_HARDWARE_DRIVER etc.) from the workflow into jvm_args."
                    },
                    "test_groups": {
                        "type": "integer",
                        "minimum": 1,
                        "description": "Total number of groups for test_group partitioning (AR_TEST_GROUPS). Defaults to the value the CI workflow currently uses, read from .github/workflows/analysis.yaml at request time. Pass explicitly only to explore a partitioning different from CI's. Only used when test_group is set."
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
                project=arguments.get("project", ""),
                module=arguments.get("module", DEFAULT_MODULE),
                test_classes=arguments.get("test_classes", []),
                test_methods=arguments.get("test_methods", []),
                timeout_minutes=arguments.get("timeout_minutes", DEFAULT_TIMEOUT),
                jvm_args=arguments.get("jvm_args", []),
                profile=arguments.get("profile"),
                jmx_monitoring=arguments.get("jmx_monitoring", False),
                jfr_settings=arguments.get("jfr_settings", "default"),
                repetitions=arguments.get("repetitions", 1),
                test_group=arguments.get("test_group"),
                test_groups=arguments.get("test_groups")
            )
            # Resolve eagerly so a bad path is reported as a tool error rather
            # than surfacing later as an opaque Maven failure inside a run.
            project_root = config.project_root()

            if config.test_group is not None and config.test_groups is None:
                config.test_groups = resolve_ci_test_groups(config.module, project_root)
            run_id, command = runner.start_run(config)
            response = {
                "run_id": run_id,
                "status": "started",
                "project": str(project_root),
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
