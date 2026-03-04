#!/usr/bin/env python3
"""
Profile Analyzer MCP Server

Provides tools for AI agents to explore and analyze performance profile data
from Almost Realism computations.

Uses the Java ProfileAnalyzerCLI for efficient parsing of large profile XML files.
"""

import json
import os
import subprocess
from glob import glob
from pathlib import Path
from typing import Optional

from mcp.server.fastmcp import FastMCP

# Configuration — derive PROJECT_ROOT from this file's location
# server.py lives at tools/mcp/profile-analyzer/server.py, so project root is 3 levels up
_SERVER_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = os.environ.get(
    "AR_PROJECT_ROOT",
    str(_SERVER_DIR.parent.parent.parent)
)
DEFAULT_PROFILE_DIR = os.environ.get("AR_PROFILE_DIR", "utils/results")
TOOLS_DIR = os.environ.get("AR_TOOLS_DIR", str(Path(PROJECT_ROOT) / "tools"))

# Initialize server
mcp = FastMCP("ar-profile-analyzer")


def run_java_cli(command: str, file_path: str, *args) -> dict:
    """Run the Java ProfileAnalyzerCLI and return parsed JSON result."""
    cmd = [
        "mvn", "-q", "exec:java",
        "-pl", "tools",
        f"-Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI",
        f"-Dexec.args={command} {file_path} {' '.join(str(a) for a in args)}"
    ]

    try:
        result = subprocess.run(
            cmd,
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=120
        )

        if result.returncode != 0:
            return {"error": f"Java CLI failed: {result.stderr}"}

        # Parse JSON output
        return json.loads(result.stdout)
    except subprocess.TimeoutExpired:
        return {"error": "Java CLI timed out after 120 seconds"}
    except json.JSONDecodeError as e:
        return {"error": f"Failed to parse CLI output: {e}", "output": result.stdout[:500]}
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def list_profiles(
    directory: str = DEFAULT_PROFILE_DIR,
    pattern: str = "*.xml"
) -> dict:
    """
    List available profile XML files in a directory.

    Args:
        directory: Directory to search (default: utils/results)
        pattern: Glob pattern for files (default: *.xml)

    Returns:
        Dictionary with list of profiles and count.
    """
    search_path = Path(directory)
    if not search_path.exists():
        return {"profiles": [], "count": 0, "error": f"Directory not found: {directory}"}

    profiles = []
    for xml_path in sorted(search_path.glob(pattern)):
        if xml_path.is_file():
            try:
                size_kb = xml_path.stat().st_size / 1024
                profiles.append({
                    "name": xml_path.name,
                    "path": str(xml_path.absolute()),
                    "size_kb": round(size_kb, 1)
                })
            except OSError:
                continue

    return {"profiles": profiles, "count": len(profiles)}


@mcp.tool()
def load_profile(path: str) -> dict:
    """
    Load a profile XML file and return its summary.

    Args:
        path: Path to the profile XML file.

    Returns:
        Dictionary with profile summary information.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    result = run_java_cli("summary", path)
    if "error" not in result:
        result["profile_path"] = path
    return result


@mcp.tool()
def find_slowest(
    path: str,
    limit: int = 10
) -> dict:
    """
    Find the N slowest operations in the profile.

    Args:
        path: Path to the profile XML file.
        limit: Maximum number of results.

    Returns:
        Dictionary with list of slowest operations.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    return run_java_cli("slowest", path, limit)


@mcp.tool()
def list_children(
    path: str,
    node_key: Optional[str] = None
) -> dict:
    """
    List children of a node with timing information.

    Args:
        path: Path to the profile XML file.
        node_key: Parent node key (uses root if omitted).

    Returns:
        Dictionary with list of children and their timing.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    if node_key:
        return run_java_cli("children", path, node_key)
    else:
        return run_java_cli("children", path)


@mcp.tool()
def search_operations(
    path: str,
    pattern: str
) -> dict:
    """
    Search for operations by name pattern.

    Args:
        path: Path to the profile XML file.
        pattern: Pattern to match operation names (case-insensitive substring match).

    Returns:
        Dictionary with matching operations.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    return run_java_cli("search", path, pattern)


@mcp.tool()
def get_timing_breakdown(
    path: str,
    node_key: str
) -> dict:
    """
    Get compile vs run time breakdown for an operation.

    Args:
        path: Path to the profile XML file.
        node_key: Node key to analyze.

    Returns:
        Dictionary with compile_time, run_time, compile_count, run_count,
        stage_details (if available), and percentages.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    return run_java_cli("breakdown", path, node_key)


@mcp.tool()
def find_slowest_by_category(
    path: str,
    category: str = "all",
    limit: int = 10
) -> dict:
    """
    Find slowest operations filtered by timing category.

    Args:
        path: Path to the profile XML file.
        category: "compile", "run", or "all" (default: "all").
        limit: Maximum number of results (default: 10).

    Returns:
        Dictionary with operations sorted by the specified category's duration.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    if category not in ("compile", "run", "all"):
        return {"error": f"Invalid category: {category}. Must be 'compile', 'run', or 'all'."}

    return run_java_cli("slowest", path, str(limit), f"--category={category}")


@mcp.tool()
def get_source(
    path: str,
    node_key: str,
    max_lines: int = 200
) -> dict:
    """
    Get generated source code for an operation node.

    Args:
        path: Path to the profile XML file.
        node_key: Node key to retrieve source for.
        max_lines: Maximum lines of source code to return (default: 200).

    Returns:
        Dictionary with source code, argument info, and line counts.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    return run_java_cli("source", path, node_key, str(max_lines))


@mcp.tool()
def get_source_summary(
    path: str,
    node_key: str
) -> dict:
    """
    Get a structural summary of generated source code for an operation node.

    Returns function counts by type, duplication analysis, loop counts,
    math operations, and total lines — without dumping the full source.

    Args:
        path: Path to the profile XML file.
        node_key: Node key to analyze.

    Returns:
        Dictionary with function types, duplication groups, control flow stats,
        and math operation counts.
    """
    if not os.path.exists(path):
        return {"error": f"File not found: {path}"}

    return run_java_cli("source-summary", path, node_key)


if __name__ == "__main__":
    mcp.run()
