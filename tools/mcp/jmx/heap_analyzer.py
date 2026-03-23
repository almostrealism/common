"""
Offline HPROF heap dump analyzer.

Invokes the ar-heap-analyzer Java CLI as a subprocess and parses the
structured JSON output. Provides class histogram, dominator tree, and
summary views for offline OOM diagnosis.
"""

import json
import subprocess
from pathlib import Path
from typing import Optional


# Path to the shaded jar, relative to this module
_JAR_DIR = Path(__file__).resolve().parent.parent.parent / "ar-heap-analyzer" / "target"
_JAR_NAME = "ar-heap-analyzer.jar"


class HeapAnalyzerError(Exception):
    """Raised when the heap analyzer subprocess fails."""
    pass


def _find_jar() -> Path:
    """Locate the ar-heap-analyzer shaded jar.

    Returns:
        Path to the jar file.

    Raises:
        HeapAnalyzerError: If the jar is not found.
    """
    jar_path = _JAR_DIR / _JAR_NAME
    if jar_path.exists():
        return jar_path

    raise HeapAnalyzerError(
        f"ar-heap-analyzer.jar not found at {jar_path}. "
        "Build with: mvn package -pl tools/ar-heap-analyzer"
    )


def analyze_heap_dump(hprof_path: str,
                      mode: str = "summary",
                      top: int = 30,
                      timeout: int = 300) -> dict:
    """Analyze an HPROF heap dump file.

    Args:
        hprof_path: Absolute path to the .hprof file.
        mode: Analysis mode - "histogram", "dominators", or "summary".
        top: Maximum number of entries to return.
        timeout: Subprocess timeout in seconds.

    Returns:
        Parsed JSON result from the analyzer.

    Raises:
        HeapAnalyzerError: If the file is not found, the jar is missing,
            Java is not available, or the analysis fails.
    """
    hprof = Path(hprof_path)
    if not hprof.exists():
        raise HeapAnalyzerError(f"HPROF file not found: {hprof_path}")

    if not hprof.suffix == ".hprof":
        raise HeapAnalyzerError(
            f"Expected .hprof file, got: {hprof.name}"
        )

    if mode not in ("histogram", "dominators", "summary"):
        raise HeapAnalyzerError(
            f"Invalid mode '{mode}'. Use histogram, dominators, or summary."
        )

    jar_path = _find_jar()

    cmd = [
        "java", "-jar", str(jar_path),
        mode,
        "--top", str(top),
        str(hprof)
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout
        )
    except subprocess.TimeoutExpired:
        raise HeapAnalyzerError(
            f"Heap analysis timed out after {timeout}s. "
            "The dump may be very large; try histogram mode first."
        )
    except FileNotFoundError:
        raise HeapAnalyzerError(
            "Java not found on PATH. A JDK is required for heap analysis."
        )

    if result.returncode != 0:
        stderr = result.stderr.strip()
        raise HeapAnalyzerError(
            f"Heap analyzer failed (rc={result.returncode}): {stderr}"
        )

    stdout = result.stdout.strip()
    if not stdout:
        raise HeapAnalyzerError("Heap analyzer produced no output")

    try:
        parsed = json.loads(stdout)
    except json.JSONDecodeError as e:
        raise HeapAnalyzerError(
            f"Failed to parse analyzer output as JSON: {e}"
        )

    if "error" in parsed:
        raise HeapAnalyzerError(parsed["error"])

    return parsed
