"""
Offline HPROF heap dump analyzer.

Invokes the HeapAnalyzer Java class from the ar-tools module as a subprocess
and parses the structured JSON output. Provides class histogram, dominator
tree, and summary views for offline OOM diagnosis.

The analyzer runs with generous heap settings (-Xmx8g) to handle large dumps.
"""

import glob
import json
import subprocess
from pathlib import Path


# Root of the common repo, relative to this module (tools/mcp/jmx/)
_COMMON_ROOT = Path(__file__).resolve().parent.parent.parent.parent

# ar-tools module jar and its Maven dependency cache
_TOOLS_TARGET = _COMMON_ROOT / "tools" / "target"
_MAIN_CLASS = "org.almostrealism.heap.HeapAnalyzer"


class HeapAnalyzerError(Exception):
    """Raised when the heap analyzer subprocess fails."""
    pass


def _build_classpath() -> str:
    """Build the Java classpath from the ar-tools module jar and its dependencies.

    Locates the ar-tools jar in the target directory and resolves the NetBeans
    Profiler and Jackson dependencies from the local Maven repository.

    Returns:
        Classpath string with all required jars separated by ':'.

    Raises:
        HeapAnalyzerError: If required jars are not found.
    """
    # Find the ar-tools jar
    tools_jars = list(_TOOLS_TARGET.glob("ar-tools-*.jar"))
    tools_jars = [j for j in tools_jars if "sources" not in j.name and "javadoc" not in j.name]
    if not tools_jars:
        raise HeapAnalyzerError(
            f"ar-tools jar not found in {_TOOLS_TARGET}. "
            "Build with: mvn install -pl tools -am -DskipTests"
        )
    tools_jar = tools_jars[0]

    # Resolve dependencies from Maven local repo
    m2 = Path.home() / ".m2" / "repository"
    dep_patterns = [
        "org/netbeans/modules/org-netbeans-lib-profiler/*/org-netbeans-lib-profiler-*.jar",
        "com/fasterxml/jackson/core/jackson-databind/*/jackson-databind-*.jar",
        "com/fasterxml/jackson/core/jackson-core/*/jackson-core-*.jar",
        "com/fasterxml/jackson/core/jackson-annotations/*/jackson-annotations-*.jar",
    ]

    classpath_entries = [str(tools_jar)]
    for pattern in dep_patterns:
        matches = sorted(glob.glob(str(m2 / pattern)))
        # Filter out sources/javadoc jars
        matches = [m for m in matches if not any(s in m for s in ["-sources", "-javadoc"])]
        if matches:
            classpath_entries.append(matches[-1])  # Use latest version

    return ":".join(classpath_entries)


def analyze_heap_dump(hprof_path: str,
                      mode: str = "summary",
                      top: int = 30,
                      timeout: int = 600) -> dict:
    """Analyze an HPROF heap dump file.

    Args:
        hprof_path: Absolute path to the .hprof file.
        mode: Analysis mode - "histogram", "dominators", or "summary".
        top: Maximum number of entries to return.
        timeout: Subprocess timeout in seconds (default 600 for large dumps).

    Returns:
        Parsed JSON result from the analyzer.

    Raises:
        HeapAnalyzerError: If the file is not found, dependencies are missing,
            Java is not available, or the analysis fails.
    """
    hprof = Path(hprof_path)
    if not hprof.exists():
        raise HeapAnalyzerError(f"HPROF file not found: {hprof_path}")

    if hprof.suffix != ".hprof":
        raise HeapAnalyzerError(
            f"Expected .hprof file, got: {hprof.name}"
        )

    if mode not in ("histogram", "dominators", "summary"):
        raise HeapAnalyzerError(
            f"Invalid mode '{mode}'. Use histogram, dominators, or summary."
        )

    classpath = _build_classpath()

    cmd = [
        "java",
        "-Xmx8g",
        "-cp", classpath,
        _MAIN_CLASS,
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
            "The dump may be very large; try 'histogram' mode first "
            "(it is single-pass and much faster than 'dominators')."
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
