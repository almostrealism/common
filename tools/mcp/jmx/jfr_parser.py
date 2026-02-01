"""
Parser for JFR (Java Flight Recorder) JSON output.

Processes the JSON produced by `jfr print --json` and aggregates
allocation events by class for leak detection and allocation profiling.
"""

import json
from typing import Optional


def parse_allocation_events(json_text: str) -> list[dict]:
    """Parse JFR allocation sample events and aggregate by class.

    Processes `jdk.ObjectAllocationSample` events from `jfr print --json`
    output. Aggregates allocation weight by object class and collects
    the top stack frames for each class.

    Args:
        json_text: Raw JSON string from `jfr print --json --events
                   jdk.ObjectAllocationSample <file>`.

    Returns:
        List of dicts sorted by total_bytes descending, each containing:
        - class_name: The allocated object class
        - total_bytes: Sum of allocation weights for this class
        - total_bytes_mb: total_bytes in megabytes (rounded to 2 decimal places)
        - sample_count: Number of allocation samples for this class
        - top_frames: Top 3 most common stack frames (list of strings)
    """
    try:
        data = json.loads(json_text)
    except json.JSONDecodeError:
        return []

    # JFR JSON structure: {"recording": {"events": [...]}}
    events = []
    if isinstance(data, dict):
        recording = data.get("recording", data)
        if isinstance(recording, dict):
            events = recording.get("events", [])
        elif isinstance(data, list):
            events = data
    elif isinstance(data, list):
        events = data

    # Aggregate by class
    class_stats: dict[str, dict] = {}
    frame_counts: dict[str, dict[str, int]] = {}

    for event in events:
        if not isinstance(event, dict):
            continue

        # Extract class name from objectClass field
        object_class = event.get("objectClass")
        if isinstance(object_class, dict):
            class_name = object_class.get("name", "unknown")
        elif isinstance(object_class, str):
            class_name = object_class
        else:
            continue

        # Extract allocation weight
        weight = event.get("weight", 0)
        if isinstance(weight, str):
            try:
                weight = int(weight)
            except ValueError:
                weight = 0

        if class_name not in class_stats:
            class_stats[class_name] = {"total_bytes": 0, "sample_count": 0}
            frame_counts[class_name] = {}

        class_stats[class_name]["total_bytes"] += weight
        class_stats[class_name]["sample_count"] += 1

        # Collect stack frames
        stack_trace = event.get("stackTrace")
        if isinstance(stack_trace, dict):
            frames = stack_trace.get("frames", [])
        elif isinstance(stack_trace, list):
            frames = stack_trace
        else:
            frames = []

        for frame in frames[:3]:
            if isinstance(frame, dict):
                method = frame.get("method", {})
                if isinstance(method, dict):
                    frame_str = (
                        f"{method.get('type', {}).get('name', '?')}."
                        f"{method.get('name', '?')}"
                    )
                else:
                    frame_str = str(method)
                line = frame.get("lineNumber", "?")
                frame_key = f"{frame_str}:{line}"
            elif isinstance(frame, str):
                frame_key = frame
            else:
                continue

            frame_counts[class_name][frame_key] = (
                frame_counts[class_name].get(frame_key, 0) + 1
            )

    # Build result sorted by total_bytes descending
    result = []
    for class_name, stats in class_stats.items():
        # Get top 3 frames by frequency
        sorted_frames = sorted(
            frame_counts.get(class_name, {}).items(),
            key=lambda x: x[1],
            reverse=True
        )
        top_frames = [f for f, _ in sorted_frames[:3]]

        total_bytes = stats["total_bytes"]
        result.append({
            "class_name": class_name,
            "total_bytes": total_bytes,
            "total_bytes_mb": round(total_bytes / (1024 * 1024), 2),
            "sample_count": stats["sample_count"],
            "top_frames": top_frames
        })

    result.sort(key=lambda x: x["total_bytes"], reverse=True)
    return result
