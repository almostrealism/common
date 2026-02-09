"""
Parser for jcmd GC.class_histogram output.

Handles the tabular format produced by `jcmd <pid> GC.class_histogram`
and supports filtering, sorting, and diffing two histogram snapshots.
"""

import re
from typing import Optional


# Matches lines like: "   1:     50000   200000000  java.lang.String"
_HISTOGRAM_LINE = re.compile(
    r"^\s*(\d+):\s+(\d+)\s+(\d+)\s+(\S+)\s*$"
)


def parse_histogram(text: str) -> list[dict]:
    """Parse GC.class_histogram output into structured data.

    Args:
        text: Raw output from `jcmd <pid> GC.class_histogram`.

    Returns:
        List of dicts with keys: rank, instances, bytes, bytes_mb, class_name.
        Sorted by rank (original ordering, typically by bytes descending).
    """
    classes = []
    for line in text.split("\n"):
        match = _HISTOGRAM_LINE.match(line)
        if match:
            byte_count = int(match.group(3))
            classes.append({
                "rank": int(match.group(1)),
                "instances": int(match.group(2)),
                "bytes": byte_count,
                "bytes_mb": round(byte_count / (1024 * 1024), 2),
                "class_name": match.group(4)
            })
    return classes


def filter_histogram(classes: list[dict],
                     pattern: Optional[str] = None,
                     sort_by: str = "bytes",
                     limit: int = 30) -> list[dict]:
    """Filter and sort histogram entries.

    Args:
        classes: Parsed histogram entries from parse_histogram().
        pattern: Regex pattern to match against class_name. None for no filter.
        sort_by: Field to sort by ("bytes", "instances", "class_name").
        limit: Maximum number of entries to return.

    Returns:
        Filtered, sorted, and truncated list of histogram entries.
    """
    result = classes

    if pattern:
        try:
            regex = re.compile(pattern, re.IGNORECASE)
            result = [c for c in result if regex.search(c["class_name"])]
        except re.error:
            # Invalid regex, treat as substring match
            pattern_lower = pattern.lower()
            result = [c for c in result if pattern_lower in c["class_name"].lower()]

    reverse = sort_by != "class_name"
    result = sorted(result, key=lambda c: c.get(sort_by, 0), reverse=reverse)

    return result[:limit]


def diff_histograms(before: list[dict], after: list[dict]) -> list[dict]:
    """Compute per-class growth between two histogram snapshots.

    Args:
        before: Parsed histogram from the earlier snapshot.
        after: Parsed histogram from the later snapshot.

    Returns:
        List of dicts with keys: class_name, before_bytes, after_bytes,
        byte_growth, byte_growth_mb, before_instances, after_instances,
        instance_growth. Sorted by byte_growth descending.
    """
    before_map = {c["class_name"]: c for c in before}
    after_map = {c["class_name"]: c for c in after}

    all_classes = set(before_map.keys()) | set(after_map.keys())

    diffs = []
    for class_name in all_classes:
        b = before_map.get(class_name, {"bytes": 0, "instances": 0})
        a = after_map.get(class_name, {"bytes": 0, "instances": 0})

        byte_growth = a["bytes"] - b["bytes"]
        diffs.append({
            "class_name": class_name,
            "before_bytes": b["bytes"],
            "after_bytes": a["bytes"],
            "byte_growth": byte_growth,
            "byte_growth_mb": round(byte_growth / (1024 * 1024), 2),
            "before_instances": b["instances"],
            "after_instances": a["instances"],
            "instance_growth": a["instances"] - b["instances"]
        })

    diffs.sort(key=lambda d: d["byte_growth"], reverse=True)
    return diffs
