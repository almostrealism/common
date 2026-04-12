#!/usr/bin/env python3
"""
MCP Build Validator Server for Almost Realism

THIS SERVER IS NOT A GENERAL TEST RUNNER. It runs only static analysis and a
handful of tightly scoped policy-enforcement tests. It will NEVER trigger the
compute-intensive ML/audio/render test suites, even if asked. A runtime safety
guard in _run_subprocess enforces this invariant at the process level: any
attempt to run 'mvn test' outside the tools module or without a CodePolicy class
filter is refused with an error before a subprocess is ever created.

Available checks:
  checkstyle    - Style rules: no var, no @SuppressWarnings, file length, etc.
  code_policy   - Producer pattern, GPU memory model, Cell/Block/Features naming
  test_timeouts - All @Test annotations must include a timeout parameter
  duplicate_code- No blocks of 10+ identical lines across different files
  javadoc       - All non-private classes and methods have Javadoc documentation
  errorprone    - Java compiler ErrorProne checks (MissingOverride, UnusedVariable, etc.)

For running the actual test suite use mcp__ar-test-runner__start_test_run.

Usage:
  1. start_validation(checks=["checkstyle", "code_policy"])  → run_id
  2. get_validation_status(run_id)                           → per-check status
  3. get_validation_violations(run_id)                       → structured violations
"""

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

# Project root: tools/mcp/build-validator/server.py is 4 levels deep
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent.resolve()
RUNS_DIR = Path(__file__).parent / "runs"
MAX_RUNS = 30
DEFAULT_TIMEOUT_MINUTES = 25
DEFAULT_OUTPUT_LINES = 200

RUNS_DIR.mkdir(parents=True, exist_ok=True)

# These checks run Maven tests and need compiled artifacts.
# A full "mvn install -DskipTests" is run before them unless skip_build=True.
BUILD_REQUIRED_CHECKS = frozenset({"code_policy", "test_timeouts", "duplicate_code"})

# errorprone triggers compilation only (no test fork), so it is treated
# separately: if skip_build=False and a build step is NOT already running
# (because no BUILD_REQUIRED_CHECKS were requested), we still compile first.
COMPILE_REQUIRED_CHECKS = frozenset({"errorprone"})

DEFAULT_CHECKS = ["checkstyle", "code_policy", "test_timeouts", "duplicate_code"]

# Surefire report class for CodePolicyEnforcementTest
SUREFIRE_TEST_CLASS = (
    "org.almostrealism.util.test.CodePolicyEnforcementTest"
)

# Maximum violations to return in structured output (full output always available)
MAX_VIOLATIONS = 50

# ── Safety constants ──────────────────────────────────────────────────────────
# These two constants define the ONLY Maven test execution this server is ever
# allowed to perform. They are used both in _build_command (to construct the
# correct commands) and in _assert_safe_command (to verify them at run time).
# Changing either value requires a matching change in both places.
_SAFE_TEST_MODULE = "tools"          # the only -pl value permitted with 'mvn test'
_POLICY_TEST_CLASS = "CodePolicyEnforcementTest"  # the only -Dtest= class permitted


@dataclass
class ValidationConfig:
    """Configuration for a single validation run."""

    checks: list = field(default_factory=lambda: list(DEFAULT_CHECKS))
    skip_build: bool = False
    timeout_minutes: int = DEFAULT_TIMEOUT_MINUTES


@dataclass
class CheckResult:
    """Result of a single check within a validation run."""

    name: str
    status: str  # pending | running | passed | failed | skipped | error
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    duration_seconds: Optional[float] = None
    exit_code: Optional[int] = None
    violation_count: int = 0
    violations: list = field(default_factory=list)


@dataclass
class ValidationMetadata:
    """Metadata for a validation run."""

    run_id: str
    config: dict
    status: str  # running | completed | failed | timeout | cancelled
    started_at: str
    completed_at: Optional[str] = None
    pid: Optional[int] = None
    build_status: Optional[str] = None   # passed | failed | skipped
    build_duration_seconds: Optional[float] = None
    checks: list = field(default_factory=list)


class BuildValidator:
    """Manages build validation run execution and tracking."""

    def __init__(self):
        self._active_pids: dict[str, int] = {}
        self._cancel_flags: dict[str, bool] = {}
        self._timeout_timers: dict[str, threading.Timer] = {}

    # ──────────────────────────────────────────────────────────────────
    # Public API
    # ──────────────────────────────────────────────────────────────────

    def start_run(self, config: ValidationConfig) -> str:
        """Start a new validation run. Returns the run_id."""
        self._cleanup_old_runs()
        run_id = uuid.uuid4().hex[:8]
        run_dir = RUNS_DIR / run_id
        run_dir.mkdir(parents=True)

        checks = [CheckResult(name=c, status="pending") for c in config.checks]
        metadata = ValidationMetadata(
            run_id=run_id,
            config=asdict(config),
            status="running",
            started_at=datetime.now().isoformat(),
            checks=[asdict(c) for c in checks],
        )
        self._save_metadata(run_id, asdict(metadata))
        (run_dir / "output.txt").write_text("")

        if config.timeout_minutes:
            timer = threading.Timer(
                config.timeout_minutes * 60,
                self._handle_timeout,
                [run_id],
            )
            timer.daemon = True
            timer.start()
            self._timeout_timers[run_id] = timer

        self._cancel_flags[run_id] = False
        threading.Thread(
            target=self._run_validation,
            args=(run_id, config, run_dir),
            daemon=True,
        ).start()

        return run_id

    def get_status(self, run_id: str) -> Optional[dict]:
        """Return current metadata for a validation run."""
        metadata = self._load_metadata(run_id)
        if metadata is None:
            return None
        started = datetime.fromisoformat(metadata["started_at"])
        if metadata.get("completed_at"):
            elapsed = (datetime.fromisoformat(metadata["completed_at"]) - started).total_seconds()
        else:
            elapsed = (datetime.now() - started).total_seconds()
        metadata["duration_seconds"] = elapsed
        return metadata

    def get_output(
        self,
        run_id: str,
        tail: Optional[int] = None,
        filter_pattern: Optional[str] = None,
        max_lines: Optional[int] = None,
    ) -> Optional[dict]:
        """Return console output from a validation run."""
        output_file = RUNS_DIR / run_id / "output.txt"
        if not output_file.exists():
            return None

        with open(output_file, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()

        total = len(lines)
        if filter_pattern:
            try:
                pat = re.compile(filter_pattern)
                lines = [l for l in lines if pat.search(l)]
            except re.error:
                pass

        truncated = False
        if tail and len(lines) > tail:
            lines = lines[-tail:]
            truncated = True
        elif max_lines is None:
            max_lines = DEFAULT_OUTPUT_LINES
            if len(lines) > max_lines:
                head = max_lines // 2
                tail_n = max_lines - head
                omitted = len(lines) - max_lines
                lines = (
                    lines[:head]
                    + [f"\n... ({omitted} lines truncated) ...\n\n"]
                    + lines[-tail_n:]
                )
                truncated = True
        elif max_lines > 0 and len(lines) > max_lines:
            head = max_lines // 2
            tail_n = max_lines - head
            omitted = len(lines) - max_lines
            lines = (
                lines[:head]
                + [f"\n... ({omitted} lines truncated) ...\n\n"]
                + lines[-tail_n:]
            )
            truncated = True

        return {
            "run_id": run_id,
            "output": "".join(lines),
            "total_lines": total,
            "truncated": truncated,
        }

    def get_violations(self, run_id: str) -> Optional[dict]:
        """Return structured violation data per check."""
        metadata = self._load_metadata(run_id)
        if metadata is None:
            return None

        total = sum(c.get("violation_count", 0) for c in metadata.get("checks", []))
        return {
            "run_id": run_id,
            "status": metadata.get("status"),
            "total_violations": total,
            "build_status": metadata.get("build_status"),
            "checks": [
                {
                    "name": c["name"],
                    "status": c["status"],
                    "violation_count": c.get("violation_count", 0),
                    "violations": c.get("violations", []),
                    "duration_seconds": c.get("duration_seconds"),
                }
                for c in metadata.get("checks", [])
            ],
        }

    def cancel_run(self, run_id: str) -> bool:
        """Cancel a running validation. Returns True if cancelled."""
        self._cancel_flags[run_id] = True
        pid = self._active_pids.get(run_id)
        if pid:
            try:
                os.killpg(os.getpgid(pid), signal.SIGTERM)
            except Exception:
                pass
        metadata = self._load_metadata(run_id)
        if metadata and metadata.get("status") == "running":
            metadata["status"] = "cancelled"
            metadata["completed_at"] = datetime.now().isoformat()
            self._save_metadata(run_id, metadata)
            self._cancel_timer(run_id)
            return True
        return False

    def list_runs(self, limit: int = 10, status_filter: Optional[str] = None) -> list:
        """Return recent validation runs, newest first."""
        runs = []
        for run_dir in RUNS_DIR.iterdir():
            if not run_dir.is_dir():
                continue
            metadata = self._load_metadata(run_dir.name)
            if metadata is None:
                continue
            if status_filter and metadata.get("status") != status_filter:
                continue
            runs.append({
                "run_id": metadata["run_id"],
                "status": metadata["status"],
                "started_at": metadata["started_at"],
                "checks": [c["name"] for c in metadata.get("checks", [])],
                "build_status": metadata.get("build_status"),
            })
        runs.sort(key=lambda x: x["started_at"], reverse=True)
        return runs[:limit]

    # ──────────────────────────────────────────────────────────────────
    # Run orchestration (background thread)
    # ──────────────────────────────────────────────────────────────────

    def _run_validation(self, run_id: str, config: ValidationConfig, run_dir: Path):
        """Main validation thread: build (if needed) then run each check."""
        output_file = run_dir / "output.txt"
        metadata = self._load_metadata(run_id)

        needs_build = not config.skip_build and any(
            c in BUILD_REQUIRED_CHECKS | COMPILE_REQUIRED_CHECKS
            for c in config.checks
        )
        # If build-required checks are present we do a full install.
        # If only compile-required checks are present, just compile.
        needs_full_install = not config.skip_build and any(
            c in BUILD_REQUIRED_CHECKS for c in config.checks
        )

        try:
            # ── Step 1: Build ────────────────────────────────────────
            if needs_build:
                if self._is_cancelled(run_id):
                    self._finish_cancelled(run_id, metadata)
                    return

                if needs_full_install:
                    build_cmd = ["mvn", "install", "-DskipTests", "-B"]
                    label = "BUILD (mvn install -DskipTests)"
                else:
                    build_cmd = ["mvn", "compile", "-B"]
                    label = "COMPILE (mvn compile)"

                self._write_section(output_file, label)
                build_start = datetime.now()
                build_exit = self._run_subprocess(build_cmd, output_file, run_id)
                build_dur = (datetime.now() - build_start).total_seconds()
                metadata["build_status"] = "passed" if build_exit == 0 else "failed"
                metadata["build_duration_seconds"] = round(build_dur, 1)
                self._save_metadata(run_id, metadata)

                if build_exit != 0:
                    self._append(output_file,
                        "\nBuild step failed — checks requiring compiled artifacts will be skipped.\n")

            elif config.skip_build and any(
                c in BUILD_REQUIRED_CHECKS | COMPILE_REQUIRED_CHECKS
                for c in config.checks
            ):
                metadata["build_status"] = "skipped"
                self._save_metadata(run_id, metadata)

            # ── Step 2: Run checks ───────────────────────────────────
            for i, check_dict in enumerate(metadata["checks"]):
                check_name = check_dict["name"]

                if self._is_cancelled(run_id):
                    self._mark_remaining(metadata, i, "skipped")
                    self._finish_cancelled(run_id, metadata)
                    return

                # Skip build-required checks when the build failed
                requires_build = check_name in BUILD_REQUIRED_CHECKS
                build_failed = metadata.get("build_status") == "failed"
                if requires_build and build_failed and not config.skip_build:
                    metadata["checks"][i]["status"] = "skipped"
                    self._save_metadata(run_id, metadata)
                    continue

                metadata["checks"][i]["status"] = "running"
                metadata["checks"][i]["started_at"] = datetime.now().isoformat()
                self._save_metadata(run_id, metadata)

                self._write_section(output_file, f"CHECK: {check_name}")

                # Record output file size BEFORE the check so we can extract
                # only the bytes written during this check for violation parsing.
                try:
                    offset_before = output_file.stat().st_size
                except FileNotFoundError:
                    offset_before = 0

                cmd = self._build_command(check_name)
                check_start = datetime.now()
                exit_code = self._run_subprocess(cmd, output_file, run_id)
                duration = (datetime.now() - check_start).total_seconds()

                # Extract the output produced by this check only
                check_output = self._read_from_offset(output_file, offset_before)

                violations = self._parse_violations(check_name, check_output)
                metadata["checks"][i].update({
                    "status": "passed" if exit_code == 0 else "failed",
                    "completed_at": datetime.now().isoformat(),
                    "duration_seconds": round(duration, 1),
                    "exit_code": exit_code,
                    "violation_count": len(violations),
                    "violations": violations[:MAX_VIOLATIONS],
                })
                self._save_metadata(run_id, metadata)

            # ── Final status ─────────────────────────────────────────
            any_failed = any(c["status"] == "failed" for c in metadata["checks"])
            metadata["status"] = "failed" if any_failed else "completed"
            metadata["completed_at"] = datetime.now().isoformat()
            self._save_metadata(run_id, metadata)

        except Exception as exc:
            metadata["status"] = "failed"
            metadata["completed_at"] = datetime.now().isoformat()
            self._save_metadata(run_id, metadata)
            self._append(output_file, f"\nInternal error: {exc}\n")
        finally:
            self._cancel_timer(run_id)

    # ──────────────────────────────────────────────────────────────────
    # Command builders
    # ──────────────────────────────────────────────────────────────────

    def _build_command(self, check_name: str) -> list:
        """Return the Maven command for the given check."""
        if check_name == "checkstyle":
            return ["mvn", "checkstyle:check", "-B"]
        if check_name == "javadoc":
            return ["mvn", "checkstyle:check", "-Pjavadoc-check", "-B"]
        if check_name == "errorprone":
            # Compile the whole project; ErrorProne runs as an annotation processor.
            return ["mvn", "compile", "-B"]
        if check_name == "code_policy":
            return [
                "mvn", "test", "-pl", "tools", "-B",
                "-Dtest=CodePolicyEnforcementTest#enforceCodePolicies+testDetector*",
                "-DAR_HARDWARE_DRIVER=native",
            ]
        if check_name == "test_timeouts":
            return [
                "mvn", "test", "-pl", "tools", "-B",
                "-Dtest=CodePolicyEnforcementTest#enforceTestTimeouts",
                "-DAR_HARDWARE_DRIVER=native",
            ]
        if check_name == "duplicate_code":
            return [
                "mvn", "test", "-pl", "tools", "-B",
                "-Dtest=CodePolicyEnforcementTest#enforceNoDuplicateCode",
                "-DAR_HARDWARE_DRIVER=native",
            ]
        raise ValueError(f"Unknown check: {check_name!r}")

    # ──────────────────────────────────────────────────────────────────
    # Violation parsers
    # ──────────────────────────────────────────────────────────────────

    def _parse_violations(self, check_name: str, check_output: str) -> list:
        """Parse violations from the output produced by a single check."""
        if check_name in ("checkstyle", "javadoc"):
            return self._parse_checkstyle(check_output)
        if check_name == "errorprone":
            return self._parse_errorprone(check_output)
        if check_name in BUILD_REQUIRED_CHECKS:
            return self._parse_surefire(check_name)
        return []

    def _parse_checkstyle(self, text: str) -> list:
        """Extract checkstyle violations from Maven output.

        Matches lines like:
          [ERROR] /path/to/File.java:42:5: message [RuleName]
          [ERROR] /path/to/File.java:42: message. [RuleName]
        """
        pattern = re.compile(
            r"^\[ERROR\]\s+(.+?\.java):(\d+)(?::\d+)?:\s+(.+?)(?:\s+\[(\w+)\])?\s*$",
            re.MULTILINE,
        )
        violations = []
        for m in pattern.finditer(text):
            filepath, line, message, rule = m.group(1), m.group(2), m.group(3), m.group(4) or ""
            try:
                rel = str(Path(filepath).relative_to(PROJECT_ROOT))
            except ValueError:
                rel = filepath
            violations.append({
                "file": rel,
                "line": int(line),
                "message": message.strip(),
                "rule": rule,
            })
        return violations

    def _parse_errorprone(self, text: str) -> list:
        """Extract ErrorProne violations from Maven compiler output.

        Matches lines like:
          [ERROR] /path/to/File.java:[42,5] error: [RuleName] description
          [ERROR] /path/to/File.java:[42,5] error: [RuleName]
        """
        pattern = re.compile(
            r"^\[ERROR\]\s+(.+?\.java):\[(\d+),\d+\]\s+error:\s+\[(\w+)\]\s*(.*?)\s*$",
            re.MULTILINE,
        )
        violations = []
        for m in pattern.finditer(text):
            filepath, line, rule, message = m.group(1), m.group(2), m.group(3), m.group(4)
            try:
                rel = str(Path(filepath).relative_to(PROJECT_ROOT))
            except ValueError:
                rel = filepath
            violations.append({
                "file": rel,
                "line": int(line),
                "rule": rule,
                "message": message.strip(),
            })
        return violations

    def _parse_surefire(self, check_name: str) -> list:
        """Extract violations from the surefire report for a CodePolicyEnforcementTest method."""
        surefire_dir = (
            PROJECT_ROOT / "tools" / "target" / "surefire-reports"
        )
        report_file = surefire_dir / f"TEST-{SUREFIRE_TEST_CLASS}.xml"
        if not report_file.exists():
            return []

        method_map = {
            "code_policy": ["enforceCodePolicies", "testDetector"],
            "test_timeouts": ["enforceTestTimeouts"],
            "duplicate_code": ["enforceNoDuplicateCode"],
        }
        relevant = method_map.get(check_name, [])
        violations = []

        try:
            tree = ET.parse(report_file)
            root = tree.getroot()
            for testcase in root.findall("testcase"):
                method = testcase.get("name", "")
                if not any(m in method for m in relevant):
                    continue

                # Failure / error message from the assertion
                for elem in testcase.findall("failure") + testcase.findall("error"):
                    raw = elem.get("message", "").strip()
                    for line in raw.splitlines():
                        line = line.strip()
                        if line and len(line) > 5:
                            violations.append({"test": method, "message": line[:400]})

                # System-out may contain per-violation detail lines
                sysout = testcase.find("system-out")
                if sysout is not None and sysout.text:
                    for line in sysout.text.splitlines():
                        line = line.strip()
                        if (
                            line
                            and len(line) > 10
                            and not line.startswith("at ")
                            and any(
                                kw in line
                                for kw in ("VIOLATION", "violation", ".java", "Missing",
                                           "Found", "detected", "must", "should")
                            )
                        ):
                            violations.append({"test": method, "message": line[:400]})
        except Exception:
            pass

        return violations

    # ──────────────────────────────────────────────────────────────────
    # Subprocess helpers
    # ──────────────────────────────────────────────────────────────────

    @staticmethod
    def _assert_safe_command(cmd: list):
        """Raise RuntimeError if cmd could trigger broad test execution.

        This is a defence-in-depth guard called immediately before every
        subprocess.Popen. The commands built by _build_command already satisfy
        these invariants, but this check makes it impossible for future drift
        in that method (or any other caller) to silently trigger the
        compute-intensive ML/audio/render test suites.

        Permitted command shapes:
          mvn [clean] install ... -DskipTests ...
          mvn [clean] compile  ...
          mvn checkstyle:check ...
          mvn test -pl tools ... -Dtest=CodePolicyEnforcementTest#<method> ...

        Any other shape raises RuntimeError before a subprocess is created.
        """
        if not cmd or cmd[0] != "mvn":
            return

        # Maven lifecycle phases / goals are positional (don't start with '-').
        phases = [arg for arg in cmd[1:] if not arg.startswith("-")]

        if "test" not in phases:
            # No test phase: compile, checkstyle:check, etc. are all safe.
            # But if it's an install, make sure -DskipTests is present.
            if "install" in phases and "-DskipTests" not in cmd:
                raise RuntimeError(
                    "Build validator safety guard: 'mvn install' must include "
                    "-DskipTests. This server must never run the full test suite."
                )
            return

        # ── 'test' phase is present: enforce strict module + class restrictions ──
        cmd_str = " ".join(cmd)

        # -pl <module> must be exactly _SAFE_TEST_MODULE — not "tools,ml" or ":ar-tools"
        module_ok = False
        for i, arg in enumerate(cmd):
            if arg == "-pl" and i + 1 < len(cmd):
                if cmd[i + 1] == _SAFE_TEST_MODULE:
                    module_ok = True
                break   # only one -pl is expected; stop after the first

        if not module_ok:
            raise RuntimeError(
                f"Build validator safety guard: 'mvn test' must include "
                f"'-pl {_SAFE_TEST_MODULE}' (exactly) to prevent accidental "
                f"execution of compute-intensive tests in other modules. "
                f"Use mcp__ar-test-runner__start_test_run for general test "
                f"execution. Command was: {cmd_str}"
            )

        # -Dtest= must reference _POLICY_TEST_CLASS with an explicit method filter.
        # Bare "-Dtest=CodePolicyEnforcementTest" (no #method) is also rejected
        # to prevent accidentally running every method in the class.
        class_ok = any(
            arg.startswith(f"-Dtest={_POLICY_TEST_CLASS}#")
            for arg in cmd
        )
        if not class_ok:
            raise RuntimeError(
                f"Build validator safety guard: 'mvn test' must include "
                f"'-Dtest={_POLICY_TEST_CLASS}#<method>' to restrict execution "
                f"to specific policy enforcement methods. A bare class name "
                f"(without #method) is also rejected. "
                f"Use mcp__ar-test-runner__start_test_run for general test "
                f"execution. Command was: {cmd_str}"
            )

    def _run_subprocess(self, cmd: list, output_file: Path, run_id: str) -> int:
        """Run a command, appending stdout/stderr to output_file. Returns exit code.

        Calls _assert_safe_command before creating any subprocess. This is the
        single enforcement point: no Maven test command that hasn't passed the
        safety guard can ever be executed by this server.
        """
        self._assert_safe_command(cmd)

        env = os.environ.copy()
        env.pop("AR_HARDWARE_LIBS", None)  # must be auto-detected

        with open(output_file, "ab") as out:
            process = subprocess.Popen(
                cmd,
                stdout=out,
                stderr=subprocess.STDOUT,
                env=env,
                cwd=PROJECT_ROOT,
                preexec_fn=os.setsid,
            )
            self._active_pids[run_id] = process.pid
            exit_code = process.wait()
            self._active_pids.pop(run_id, None)
        return exit_code

    @staticmethod
    def _read_from_offset(output_file: Path, offset: int) -> str:
        """Read bytes written to output_file after the given byte offset."""
        try:
            with open(output_file, "rb") as f:
                f.seek(offset)
                return f.read().decode("utf-8", errors="replace")
        except Exception:
            return ""

    @staticmethod
    def _write_section(output_file: Path, label: str):
        """Append a clearly delimited section header to the output file."""
        header = f"\n{'=' * 60}\n=== {label} ===\n{'=' * 60}\n\n"
        with open(output_file, "a", encoding="utf-8") as f:
            f.write(header)

    @staticmethod
    def _append(output_file: Path, text: str):
        """Append text to the output file."""
        with open(output_file, "a", encoding="utf-8") as f:
            f.write(text)

    # ──────────────────────────────────────────────────────────────────
    # Metadata helpers
    # ──────────────────────────────────────────────────────────────────

    def _save_metadata(self, run_id: str, metadata: dict):
        meta_file = RUNS_DIR / run_id / "metadata.json"
        with open(meta_file, "w") as f:
            json.dump(metadata, f, indent=2)

    def _load_metadata(self, run_id: str) -> Optional[dict]:
        meta_file = RUNS_DIR / run_id / "metadata.json"
        if not meta_file.exists():
            return None
        with open(meta_file) as f:
            return json.load(f)

    # ──────────────────────────────────────────────────────────────────
    # Lifecycle helpers
    # ──────────────────────────────────────────────────────────────────

    def _is_cancelled(self, run_id: str) -> bool:
        return self._cancel_flags.get(run_id, False)

    @staticmethod
    def _mark_remaining(metadata: dict, from_index: int, status: str):
        for i in range(from_index, len(metadata["checks"])):
            if metadata["checks"][i]["status"] in ("pending", "running"):
                metadata["checks"][i]["status"] = status

    def _finish_cancelled(self, run_id: str, metadata: dict):
        metadata["status"] = "cancelled"
        metadata["completed_at"] = datetime.now().isoformat()
        self._save_metadata(run_id, metadata)
        self._cancel_timer(run_id)

    def _handle_timeout(self, run_id: str):
        self._cancel_flags[run_id] = True
        pid = self._active_pids.get(run_id)
        if pid:
            try:
                os.killpg(os.getpgid(pid), signal.SIGTERM)
            except Exception:
                pass
        metadata = self._load_metadata(run_id)
        if metadata:
            metadata["status"] = "timeout"
            metadata["completed_at"] = datetime.now().isoformat()
            self._save_metadata(run_id, metadata)

    def _cancel_timer(self, run_id: str):
        timer = self._timeout_timers.pop(run_id, None)
        if timer:
            timer.cancel()

    def _cleanup_old_runs(self):
        if not RUNS_DIR.exists():
            return
        runs = []
        for run_dir in RUNS_DIR.iterdir():
            if not run_dir.is_dir():
                continue
            meta = run_dir / "metadata.json"
            started_at = ""
            if meta.exists():
                try:
                    with open(meta) as f:
                        started_at = json.load(f).get("started_at", "")
                except Exception:
                    pass
            runs.append((run_dir, started_at))
        runs.sort(key=lambda x: x[1])
        while len(runs) >= MAX_RUNS:
            old_dir, _ = runs.pop(0)
            try:
                shutil.rmtree(old_dir)
            except Exception:
                pass


# ──────────────────────────────────────────────────────────────────────
# MCP server
# ──────────────────────────────────────────────────────────────────────

validator = BuildValidator()
server = Server("ar-build-validator")


@server.list_tools()
async def list_tools():
    """Advertise the tools provided by this server."""
    return [
        Tool(
            name="start_validation",
            description=(
                "Start a build validation run asynchronously. Runs static analysis checks "
                "without executing the full test suite. Returns a run_id for tracking. "
                "\n\nDefault checks: checkstyle, code_policy, test_timeouts, duplicate_code. "
                "Checks that need compiled artifacts (code_policy, test_timeouts, "
                "duplicate_code, errorprone) automatically run 'mvn install -DskipTests' "
                "first unless skip_build=true. "
                "\n\nFor a fast pre-commit check: use checks=['checkstyle'] — no build needed. "
                "For a full pre-push check: use the defaults (all four checks)."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "checks": {
                        "type": "array",
                        "items": {
                            "type": "string",
                            "enum": [
                                "checkstyle",
                                "code_policy",
                                "test_timeouts",
                                "duplicate_code",
                                "javadoc",
                                "errorprone",
                            ],
                        },
                        "description": (
                            "Checks to run. Defaults to: checkstyle, code_policy, "
                            "test_timeouts, duplicate_code.\n"
                            "  checkstyle     — style rules (no var, no @SuppressWarnings, etc.)\n"
                            "  code_policy    — Producer pattern, GPU memory model, naming conventions\n"
                            "  test_timeouts  — all @Test annotations must have a timeout parameter\n"
                            "  duplicate_code — no 10+ identical lines across different files\n"
                            "  javadoc        — all non-private types/methods have Javadoc\n"
                            "  errorprone     — Java compiler ErrorProne static analysis"
                        ),
                    },
                    "skip_build": {
                        "type": "boolean",
                        "description": (
                            "Skip the 'mvn install -DskipTests' step that normally runs before "
                            "checks requiring compiled artifacts. Set to true if the project is "
                            "already built and only source files changed (default: false)."
                        ),
                    },
                    "timeout_minutes": {
                        "type": "integer",
                        "minimum": 1,
                        "description": (
                            f"Maximum run time in minutes (default: {DEFAULT_TIMEOUT_MINUTES}). "
                            "The build step alone can take 5–10 minutes on a cold cache."
                        ),
                    },
                },
            },
        ),
        Tool(
            name="get_validation_status",
            description=(
                "Get the current status of a validation run. Returns the overall run status "
                "and the per-check breakdown (pending, running, passed, failed, skipped). "
                "Poll this until status is completed, failed, timeout, or cancelled."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {"type": "string", "description": "The run identifier returned by start_validation."},
                },
                "required": ["run_id"],
            },
        ),
        Tool(
            name="get_validation_output",
            description=(
                f"Get the raw console output from a validation run. Returns at most "
                f"{DEFAULT_OUTPUT_LINES} lines by default (head + tail). Each check is "
                "delimited by a === CHECK: <name> === banner. "
                "Use the filter parameter to focus on specific patterns like '[ERROR]'."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {"type": "string", "description": "The run identifier."},
                    "tail": {"type": "integer", "description": "Return only the last N lines."},
                    "filter": {
                        "type": "string",
                        "description": "Regex pattern to filter lines (e.g. '\\[ERROR\\]').",
                    },
                    "max_lines": {
                        "type": "integer",
                        "description": (
                            f"Max lines to return (default: {DEFAULT_OUTPUT_LINES}). "
                            "Set to 0 for unlimited (use sparingly)."
                        ),
                    },
                },
                "required": ["run_id"],
            },
        ),
        Tool(
            name="get_validation_violations",
            description=(
                "Get structured violation data for each check. Returns parsed violations "
                "with file paths, line numbers, rules, and messages — capped at "
                f"{MAX_VIOLATIONS} violations per check. For the full violation list use "
                "get_validation_output with filter='\\[ERROR\\]'."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {"type": "string", "description": "The run identifier."},
                },
                "required": ["run_id"],
            },
        ),
        Tool(
            name="list_validations",
            description="List recent validation runs, newest first.",
            inputSchema={
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of runs to return (default: 10).",
                    },
                    "status": {
                        "type": "string",
                        "enum": ["running", "completed", "failed", "timeout", "cancelled"],
                        "description": "Filter by status.",
                    },
                },
            },
        ),
        Tool(
            name="cancel_validation",
            description="Cancel a running validation.",
            inputSchema={
                "type": "object",
                "properties": {
                    "run_id": {"type": "string", "description": "The run identifier."},
                },
                "required": ["run_id"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict):
    """Dispatch tool calls."""
    try:
        if name == "start_validation":
            checks = arguments.get("checks", list(DEFAULT_CHECKS))
            valid = {"checkstyle", "code_policy", "test_timeouts",
                     "duplicate_code", "javadoc", "errorprone"}
            bad = [c for c in checks if c not in valid]
            if bad:
                return [TextContent(type="text",
                    text=json.dumps({"error": f"Unknown checks: {bad}. Valid: {sorted(valid)}"}))]

            config = ValidationConfig(
                checks=checks,
                skip_build=arguments.get("skip_build", False),
                timeout_minutes=arguments.get("timeout_minutes", DEFAULT_TIMEOUT_MINUTES),
            )
            run_id = validator.start_run(config)
            return [TextContent(type="text", text=json.dumps({
                "run_id": run_id,
                "status": "started",
                "checks": checks,
                "skip_build": config.skip_build,
                "note": (
                    "Poll get_validation_status until status is completed/failed. "
                    "Then call get_validation_violations for structured results."
                ),
            }, indent=2))]

        if name == "get_validation_status":
            run_id = arguments["run_id"]
            status = validator.get_status(run_id)
            if status is None:
                return [TextContent(type="text",
                    text=json.dumps({"error": f"Run {run_id!r} not found"}))]
            return [TextContent(type="text", text=json.dumps(status, indent=2))]

        if name == "get_validation_output":
            run_id = arguments["run_id"]
            output = validator.get_output(
                run_id,
                tail=arguments.get("tail"),
                filter_pattern=arguments.get("filter"),
                max_lines=arguments.get("max_lines"),
            )
            if output is None:
                return [TextContent(type="text",
                    text=json.dumps({"error": f"Run {run_id!r} not found"}))]
            return [TextContent(type="text", text=json.dumps(output, indent=2))]

        if name == "get_validation_violations":
            run_id = arguments["run_id"]
            violations = validator.get_violations(run_id)
            if violations is None:
                return [TextContent(type="text",
                    text=json.dumps({"error": f"Run {run_id!r} not found"}))]
            return [TextContent(type="text", text=json.dumps(violations, indent=2))]

        if name == "list_validations":
            runs = validator.list_runs(
                limit=arguments.get("limit", 10),
                status_filter=arguments.get("status"),
            )
            return [TextContent(type="text", text=json.dumps({"runs": runs}, indent=2))]

        if name == "cancel_validation":
            run_id = arguments["run_id"]
            cancelled = validator.cancel_run(run_id)
            return [TextContent(type="text", text=json.dumps({
                "run_id": run_id,
                "status": "cancelled" if cancelled else "not_running",
            }))]

        return [TextContent(type="text", text=json.dumps({"error": f"Unknown tool: {name!r}"}))]

    except Exception as exc:
        return [TextContent(type="text", text=json.dumps({"error": str(exc)}))]


async def main():
    """Run the MCP server over stdio."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream, write_stream, server.create_initialization_options()
        )


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
