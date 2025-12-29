#!/usr/bin/env python3
"""
Profile Analyzer MCP Server

Provides tools for AI agents to explore and analyze performance profile data
from Almost Realism computations.
"""

import os
import re
from glob import glob
from pathlib import Path
from typing import Optional

from mcp.server.fastmcp import FastMCP

from cache import ProfileCache, CachedProfile
from profile_parser import ProfileNode

# Configuration
DEFAULT_PROFILE_DIR = os.environ.get("AR_PROFILE_DIR", "utils/results")
CACHE_SIZE = int(os.environ.get("AR_PROFILE_CACHE_SIZE", "10"))

# Initialize server and cache
mcp = FastMCP("ar-profile-analyzer")
cache = ProfileCache(max_size=CACHE_SIZE)


def format_duration(seconds: float) -> str:
    """Format duration for display."""
    if seconds < 0.001:
        return f"{seconds * 1000000:.1f}us"
    elif seconds < 1:
        return f"{seconds * 1000:.2f}ms"
    else:
        return f"{seconds:.3f}s"


def get_profile_or_error(profile_id: str) -> CachedProfile:
    """Get a cached profile or raise an error."""
    profile = cache.get(profile_id)
    if profile is None:
        raise ValueError(
            f"Profile '{profile_id}' not found in cache. "
            f"Use load_profile first to load the XML file."
        )
    return profile


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
        Dictionary with profile_id and summary information.
    """
    try:
        cached = cache.load(path)
    except Exception as e:
        return {"error": str(e), "suggestion": "Check that the file exists and is valid XML"}

    # Compute top operations
    all_nodes = cached.root.all_nodes()
    nodes_with_duration = [
        (n, n.self_duration) for n in all_nodes if n.self_duration > 0
    ]
    nodes_with_duration.sort(key=lambda x: x[1], reverse=True)

    total_duration = cached.root.total_duration
    top_operations = []
    for node, duration in nodes_with_duration[:5]:
        percentage = (duration / total_duration * 100) if total_duration > 0 else 0
        top_operations.append({
            "key": node.key,
            "name": node.name,
            "duration": round(duration, 4),
            "duration_formatted": format_duration(duration),
            "percentage": round(percentage, 1)
        })

    # Count operations with source code
    compiled_operations = sum(1 for n in all_nodes if n.has_source)

    return {
        "profile_id": cached.profile_id,
        "name": cached.name,
        "total_duration_seconds": round(total_duration, 4),
        "total_duration_formatted": format_duration(total_duration),
        "node_count": cached.node_count,
        "compiled_operations": compiled_operations,
        "top_operations": top_operations
    }


@mcp.tool()
def get_node_summary(
    profile_id: str,
    node_key: Optional[str] = None
) -> dict:
    """
    Get timing summary for a specific node.

    Args:
        profile_id: Profile ID from load_profile.
        node_key: Node key (uses root if omitted).

    Returns:
        Dictionary with detailed timing information for the node.
    """
    profile = get_profile_or_error(profile_id)

    # Find the node
    if node_key is None:
        node = profile.root
    else:
        node = profile.root.find_by_key(node_key)
        if node is None:
            return {"error": f"Node with key '{node_key}' not found"}

    # Calculate percentage of parent
    parent_duration = profile.root.total_duration
    percentage = (node.total_duration / parent_duration * 100) if parent_duration > 0 else 0

    # Build response
    result = {
        "key": node.key,
        "name": node.name,
        "timing": {
            "total_seconds": round(node.total_duration, 6),
            "total_formatted": format_duration(node.total_duration),
            "self_seconds": round(node.self_duration, 6),
            "self_formatted": format_duration(node.self_duration),
            "children_seconds": round(node.children_duration, 6),
            "measured_seconds": round(node.measured_duration, 6),
            "percentage_of_root": round(percentage, 1)
        },
        "children_count": len(node.children),
        "has_source": node.has_source
    }

    # Add invocation counts if available
    if node.metric and node.metric.counts:
        result["invocations"] = dict(node.metric.counts)

    # Add stage breakdown if available
    if node.stage_detail_time:
        result["stage_breakdown"] = {
            k: round(v, 6) for k, v in node.stage_detail_time.entries.items()
        }

    # Add metadata if available
    if node.metadata:
        result["metadata"] = node.metadata

    return result


@mcp.tool()
def list_children(
    profile_id: str,
    node_key: Optional[str] = None,
    sort_by: str = "duration",
    limit: int = 20
) -> dict:
    """
    List children of a node with timing information.

    Args:
        profile_id: Profile ID from load_profile.
        node_key: Parent node key (uses root if omitted).
        sort_by: Sort order: 'duration', 'name', or 'invocations'.
        limit: Maximum number of children to return.

    Returns:
        Dictionary with list of children and their timing.
    """
    profile = get_profile_or_error(profile_id)

    # Find the parent node
    if node_key is None:
        parent = profile.root
    else:
        parent = profile.root.find_by_key(node_key)
        if parent is None:
            return {"error": f"Node with key '{node_key}' not found"}

    # Sort children
    children = list(parent.children)
    if sort_by == "duration":
        children.sort(key=lambda n: n.total_duration, reverse=True)
    elif sort_by == "name":
        children.sort(key=lambda n: n.name)
    elif sort_by == "invocations":
        def get_invocations(n):
            if n.metric and n.metric.counts:
                return sum(n.metric.counts.values())
            return 0
        children.sort(key=get_invocations, reverse=True)

    # Build response
    parent_duration = parent.total_duration
    child_list = []
    for child in children[:limit]:
        percentage = (child.total_duration / parent_duration * 100) if parent_duration > 0 else 0
        child_list.append({
            "key": child.key,
            "name": child.name,
            "duration": round(child.total_duration, 6),
            "duration_formatted": format_duration(child.total_duration),
            "percentage": round(percentage, 1),
            "has_children": len(child.children) > 0,
            "has_source": child.has_source
        })

    return {
        "parent_key": parent.key,
        "parent_name": parent.name,
        "children": child_list,
        "total_children": len(parent.children),
        "showing": len(child_list)
    }


@mcp.tool()
def get_source(
    profile_id: str,
    node_key: str,
    format: str = "full"
) -> dict:
    """
    Get generated source code for an operation.

    Args:
        profile_id: Profile ID from load_profile.
        node_key: Node key to get source for.
        format: 'full' for complete source, 'summary' for first 50 lines.

    Returns:
        Dictionary with source code and argument information.
    """
    profile = get_profile_or_error(profile_id)

    node = profile.root.find_by_key(node_key)
    if node is None:
        return {"error": f"Node with key '{node_key}' not found"}

    source_obj = node.get_source()
    if source_obj is None:
        return {
            "key": node.key,
            "name": node.name,
            "has_source": False,
            "message": "No source code available for this operation"
        }

    source_code = source_obj.source
    lines = source_code.split('\n')
    line_count = len(lines)

    # Detect language from source content
    language = "c"  # Default
    if "kernel void" in source_code or "__kernel" in source_code:
        language = "opencl"
    elif "#include <metal_stdlib>" in source_code:
        language = "metal"

    # Apply format
    if format == "summary" and line_count > 50:
        source_code = '\n'.join(lines[:50]) + f"\n... ({line_count - 50} more lines)"

    # Build argument info
    arguments = []
    for i, key in enumerate(source_obj.argument_keys):
        name = source_obj.argument_names[i] if i < len(source_obj.argument_names) else f"arg{i}"
        # Try to find the argument node for description
        arg_node = profile.root.find_by_key(key)
        description = arg_node.name if arg_node else key
        arguments.append({
            "name": name,
            "key": key,
            "description": description
        })

    return {
        "key": node.key,
        "name": node.name,
        "language": language,
        "arguments": arguments,
        "source": source_code,
        "line_count": line_count
    }


@mcp.tool()
def find_slowest(
    profile_id: str,
    limit: int = 10,
    min_duration: float = 0.0,
    include_children: bool = False
) -> dict:
    """
    Find the N slowest operations in the profile.

    Args:
        profile_id: Profile ID from load_profile.
        limit: Maximum number of results.
        min_duration: Minimum duration in seconds to include.
        include_children: If True, use total duration; if False, use self duration.

    Returns:
        Dictionary with list of slowest operations.
    """
    profile = get_profile_or_error(profile_id)

    all_nodes = profile.root.all_nodes()

    # Calculate duration based on include_children flag
    def get_duration(node: ProfileNode) -> float:
        return node.total_duration if include_children else node.self_duration

    # Filter and sort
    nodes_with_duration = [
        (n, get_duration(n)) for n in all_nodes
        if get_duration(n) >= min_duration and n.key  # Skip nodes without keys
    ]
    nodes_with_duration.sort(key=lambda x: x[1], reverse=True)

    total_profile_duration = profile.root.total_duration
    slowest = []
    for node, duration in nodes_with_duration[:limit]:
        percentage = (duration / total_profile_duration * 100) if total_profile_duration > 0 else 0

        # Get invocation count
        invocations = 0
        if node.metric and node.metric.counts:
            invocations = sum(node.metric.counts.values())

        # Get path
        path = profile.root.get_path(node.key) or node.name

        slowest.append({
            "key": node.key,
            "name": node.name,
            "path": path,
            "duration": round(duration, 6),
            "duration_formatted": format_duration(duration),
            "percentage": round(percentage, 1),
            "invocations": invocations,
            "avg_duration": round(duration / invocations, 6) if invocations > 0 else duration,
            "has_source": node.has_source
        })

    return {
        "slowest": slowest,
        "total_profile_duration": round(total_profile_duration, 4),
        "duration_type": "total (including children)" if include_children else "self (excluding children)"
    }


@mcp.tool()
def search_operations(
    profile_id: str,
    pattern: str,
    limit: int = 20
) -> dict:
    """
    Search for operations by name pattern.

    Args:
        profile_id: Profile ID from load_profile.
        pattern: Regex pattern to match operation names.
        limit: Maximum number of results.

    Returns:
        Dictionary with matching operations.
    """
    profile = get_profile_or_error(profile_id)

    try:
        regex = re.compile(pattern, re.IGNORECASE)
    except re.error as e:
        return {"error": f"Invalid regex pattern: {e}"}

    all_nodes = profile.root.all_nodes()
    matches = []

    for node in all_nodes:
        if regex.search(node.name):
            path = profile.root.get_path(node.key) or node.name
            matches.append({
                "key": node.key,
                "name": node.name,
                "path": path,
                "duration": round(node.total_duration, 6),
                "duration_formatted": format_duration(node.total_duration),
                "has_source": node.has_source
            })

    # Sort by duration descending
    matches.sort(key=lambda x: x["duration"], reverse=True)

    return {
        "matches": matches[:limit],
        "match_count": len(matches),
        "showing": min(len(matches), limit)
    }


@mcp.tool()
def compare_profiles(
    profile_id_a: str,
    profile_id_b: str,
    threshold: float = 10.0
) -> dict:
    """
    Compare timing between two profiles.

    Args:
        profile_id_a: First profile ID.
        profile_id_b: Second profile ID.
        threshold: Minimum percentage change to report (default: 10%).

    Returns:
        Dictionary with comparison results.
    """
    profile_a = get_profile_or_error(profile_id_a)
    profile_b = get_profile_or_error(profile_id_b)

    total_a = profile_a.root.total_duration
    total_b = profile_b.root.total_duration
    overall_change = ((total_b - total_a) / total_a * 100) if total_a > 0 else 0

    # Build maps of node key -> node for comparison
    nodes_a = {n.key: n for n in profile_a.root.all_nodes() if n.key}
    nodes_b = {n.key: n for n in profile_b.root.all_nodes() if n.key}

    # Find significant changes
    significant_changes = []

    all_keys = set(nodes_a.keys()) | set(nodes_b.keys())
    for key in all_keys:
        node_a = nodes_a.get(key)
        node_b = nodes_b.get(key)

        duration_a = node_a.self_duration if node_a else 0
        duration_b = node_b.self_duration if node_b else 0

        if duration_a == 0 and duration_b == 0:
            continue

        if duration_a > 0:
            change_percent = (duration_b - duration_a) / duration_a * 100
        elif duration_b > 0:
            change_percent = 100  # New operation
        else:
            continue

        if abs(change_percent) >= threshold:
            name = (node_a or node_b).name
            significant_changes.append({
                "key": key,
                "name": name,
                "duration_a": round(duration_a, 6),
                "duration_b": round(duration_b, 6),
                "duration_a_formatted": format_duration(duration_a),
                "duration_b_formatted": format_duration(duration_b),
                "change_percent": round(change_percent, 1),
                "status": "improved" if change_percent < 0 else ("regressed" if change_percent > 0 else "unchanged")
            })

    # Sort by absolute change
    significant_changes.sort(key=lambda x: abs(x["change_percent"]), reverse=True)

    return {
        "profile_a": {
            "name": profile_a.name,
            "total": round(total_a, 4),
            "total_formatted": format_duration(total_a)
        },
        "profile_b": {
            "name": profile_b.name,
            "total": round(total_b, 4),
            "total_formatted": format_duration(total_b)
        },
        "overall_change_percent": round(overall_change, 1),
        "overall_status": "improved" if overall_change < -threshold else ("regressed" if overall_change > threshold else "unchanged"),
        "significant_changes": significant_changes[:20],
        "threshold_percent": threshold
    }


if __name__ == "__main__":
    mcp.run()
