"""
Subprocess wrappers for JDK diagnostic tools (jcmd, jstat, jps, jfr).

Provides low-level access to JVM diagnostics for forked test processes.
All functions validate PID liveness before issuing commands and raise
structured exceptions on failure.
"""

import json
import os
import re
import subprocess
from pathlib import Path
from typing import Optional


class ProcessNotFoundError(Exception):
    """Raised when the target JVM process is no longer running."""
    pass


class JVMDiagnosticsError(Exception):
    """Raised when a JDK diagnostic tool returns an error."""
    pass


def is_process_alive(pid: int) -> bool:
    """Check whether a process is alive. Uses /proc on Linux, kill(0) on macOS."""
    # Try /proc first (Linux)
    try:
        stat_path = Path(f"/proc/{pid}/stat")
        if stat_path.exists():
            return True
    except (OSError, PermissionError):
        pass

    # Fallback: os.kill with signal 0 (works on macOS and all Unix)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # Process exists but we lack permission


def get_ppid(pid: int) -> Optional[int]:
    """Get parent PID. Uses /proc on Linux, ps on macOS."""
    # Try /proc first (Linux)
    try:
        stat_path = Path(f"/proc/{pid}/stat")
        text = stat_path.read_text()
        close_paren = text.rfind(")")
        if close_paren == -1:
            return None
        fields_after_comm = text[close_paren + 2:].split()
        if len(fields_after_comm) >= 2:
            return int(fields_after_comm[1])
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


def _require_alive(pid: int) -> None:
    """Raise ProcessNotFoundError if the process is not alive."""
    if not is_process_alive(pid):
        raise ProcessNotFoundError(f"Process {pid} is not running")


def run_jcmd(pid: int, command: str, timeout: int = 30) -> str:
    """Run a jcmd diagnostic command against a JVM process.

    Args:
        pid: Target JVM process ID.
        command: The jcmd command (e.g. "GC.class_histogram", "Thread.print").
        timeout: Subprocess timeout in seconds.

    Returns:
        The stdout output from jcmd.

    Raises:
        ProcessNotFoundError: If the target process is not running.
        JVMDiagnosticsError: If jcmd returns an error.
    """
    _require_alive(pid)
    try:
        result = subprocess.run(
            ["jcmd", str(pid), command],
            capture_output=True,
            text=True,
            timeout=timeout
        )
        if result.returncode != 0:
            stderr = result.stderr.strip()
            if "No such process" in stderr or "not found" in stderr.lower():
                raise ProcessNotFoundError(f"Process {pid} is not running")
            raise JVMDiagnosticsError(
                f"jcmd {pid} {command} failed (rc={result.returncode}): {stderr}"
            )
        return result.stdout
    except subprocess.TimeoutExpired:
        raise JVMDiagnosticsError(
            f"jcmd {pid} {command} timed out after {timeout}s"
        )
    except FileNotFoundError:
        raise JVMDiagnosticsError("jcmd not found on PATH")


def run_jstat(pid: int, option: str = "gc") -> dict:
    """Run jstat and parse the header/value output into a dict.

    Args:
        pid: Target JVM process ID.
        option: The jstat option (e.g. "gc", "gccause", "gcutil").

    Returns:
        Dict mapping column names to float values (or strings for
        known text columns like LGCC and GCC).

    Raises:
        ProcessNotFoundError: If the target process is not running.
        JVMDiagnosticsError: If jstat returns an error or unparseable output.
    """
    _require_alive(pid)

    # Known string columns that should not be parsed as float
    string_columns = {"LGCC", "GCC"}

    try:
        result = subprocess.run(
            ["jstat", f"-{option}", str(pid)],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode != 0:
            stderr = result.stderr.strip()
            if "No such process" in stderr or "not found" in stderr.lower():
                raise ProcessNotFoundError(f"Process {pid} is not running")
            raise JVMDiagnosticsError(
                f"jstat -{option} {pid} failed (rc={result.returncode}): {stderr}"
            )

        lines = result.stdout.strip().split("\n")
        if len(lines) < 2:
            raise JVMDiagnosticsError(
                f"jstat -{option} {pid}: unexpected output format"
            )

        headers = lines[0].split()
        values = lines[1].split()

        if len(headers) != len(values):
            # For gccause, the GCC column may contain spaces like "No GC"
            # Re-parse: fixed columns up to GCC, then rest is GCC value
            if option == "gccause" and len(values) < len(headers):
                # The last column (GCC) may be multi-word
                raw_line = lines[1]
                # Split only up to len(headers)-1 fields
                parts = raw_line.split(None, len(headers) - 1)
                values = parts

        parsed = {}
        for i, header in enumerate(headers):
            if i >= len(values):
                parsed[header] = ""
                continue
            if header in string_columns:
                parsed[header] = values[i]
            else:
                try:
                    parsed[header] = float(values[i])
                except ValueError:
                    parsed[header] = values[i]

        return parsed

    except subprocess.TimeoutExpired:
        raise JVMDiagnosticsError(f"jstat -{option} {pid} timed out")
    except FileNotFoundError:
        raise JVMDiagnosticsError("jstat not found on PATH")


def find_forked_booter_pids() -> list[dict]:
    """Scan jps output for Maven Surefire ForkedBooter processes.

    Returns:
        List of dicts with 'pid' (int) and 'command' (str) for each
        ForkedBooter process found.
    """
    try:
        result = subprocess.run(
            ["jps", "-l"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode != 0:
            return []

        pids = []
        for line in result.stdout.strip().split("\n"):
            if "ForkedBooter" in line:
                parts = line.split(None, 1)
                if parts:
                    try:
                        pids.append({
                            "pid": int(parts[0]),
                            "command": parts[1] if len(parts) > 1 else ""
                        })
                    except ValueError:
                        pass
        return pids

    except (subprocess.TimeoutExpired, FileNotFoundError):
        return []


def start_jfr_recording(pid: int, duration: Optional[str] = None,
                        settings: str = "default",
                        output_path: Optional[str] = None) -> str:
    """Start a JFR recording on the target JVM.

    Args:
        pid: Target JVM process ID.
        duration: Recording duration (e.g. "60s", "5m"). None for continuous.
        settings: JFR settings profile ("default" or "profile").
        output_path: File path for the recording output.

    Returns:
        The jcmd output (contains recording ID).

    Raises:
        ProcessNotFoundError: If the target process is not running.
        JVMDiagnosticsError: If the command fails.
    """
    cmd_parts = ["JFR.start", f"settings={settings}"]
    if duration:
        cmd_parts.append(f"duration={duration}")
    if output_path:
        cmd_parts.append(f"filename={output_path}")
    cmd_parts.append("name=ar_jmx")

    return run_jcmd(pid, " ".join(cmd_parts))


def stop_jfr_recording(pid: int, output_path: str,
                       recording_name: str = "ar_jmx") -> str:
    """Dump and stop a JFR recording.

    Args:
        pid: Target JVM process ID.
        output_path: File path to dump the recording to.
        recording_name: Name of the recording to stop.

    Returns:
        The jcmd output.

    Raises:
        ProcessNotFoundError: If the target process is not running.
        JVMDiagnosticsError: If the command fails.
    """
    # Dump first, then stop
    dump_output = run_jcmd(pid, f"JFR.dump name={recording_name} filename={output_path}")
    stop_output = run_jcmd(pid, f"JFR.stop name={recording_name}")
    return f"{dump_output}\n{stop_output}"


def parse_jfr_json(jfr_file: str, event_type: str = "jdk.ObjectAllocationSample") -> str:
    """Parse a JFR recording file to JSON for a specific event type.

    Uses `jfr print --json --events <type> <file>` to extract events.

    Args:
        jfr_file: Path to the .jfr file.
        event_type: Fully qualified JFR event type name.

    Returns:
        Raw JSON string from jfr print.

    Raises:
        JVMDiagnosticsError: If jfr command fails or file not found.
    """
    if not Path(jfr_file).exists():
        raise JVMDiagnosticsError(f"JFR file not found: {jfr_file}")

    try:
        result = subprocess.run(
            ["jfr", "print", "--json", "--events", event_type, jfr_file],
            capture_output=True,
            text=True,
            timeout=60
        )
        if result.returncode != 0:
            raise JVMDiagnosticsError(
                f"jfr print failed (rc={result.returncode}): {result.stderr.strip()}"
            )
        return result.stdout
    except subprocess.TimeoutExpired:
        raise JVMDiagnosticsError(f"jfr print timed out for {jfr_file}")
    except FileNotFoundError:
        raise JVMDiagnosticsError("jfr command not found on PATH")
