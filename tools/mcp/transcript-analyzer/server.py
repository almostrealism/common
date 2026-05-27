#!/usr/bin/env python3
"""
Transcript Analyzer MCP Server

Provides MCP tools for analyzing opencode session transcripts captured
during agent runs. Enables inspection of session metadata, tool usage,
timing, and event timelines.
"""

import json
import os
import sys
from pathlib import Path

from mcp.server.fastmcp import FastMCP

_SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_TRANSCRIPT_DIR = os.environ.get(
    "OPENCODE_TRANSCRIPT_DIR",
    "/tmp/opencode-transcripts"
)

mcp = FastMCP("ar-transcript-analyzer")

try:
    from transcript_parser import (
        parse_transcript,
        get_summary,
        get_events_in_timerange,
        get_timeline,
        list_transcript_files,
        Transcript,
    )
except ImportError:
    sys.path.insert(0, str(_SCRIPT_DIR))
    from transcript_parser import (
        parse_transcript,
        get_summary,
        get_events_in_timerange,
        get_timeline,
        list_transcript_files,
        Transcript,
    )


@mcp.tool()
def list_transcripts(
    directory: str = DEFAULT_TRANSCRIPT_DIR,
    pattern: str = "*.jsonl",
    job_id: str = "",
    session_id: str = "",
) -> dict:
    """List available transcript files in a directory.

    Args:
        directory: Directory to search for transcripts (default: /tmp/opencode-transcripts
            or OPENCODE_TRANSCRIPT_DIR env var). Use /agent-transcripts for
            persistent per-agent transcript storage.
        pattern: Glob pattern for matching files (default: *.jsonl).
        job_id: Optional filter - only show transcripts whose job_id contains
            this string (case-insensitive substring match).
        session_id: Optional filter - only show transcripts whose session_id
            contains this string (case-insensitive substring match).

    Returns:
        dict with 'transcripts' list (name, path, size_kb, modified_iso,
        and optionally job_id/session_id/model/phase when filtering) and
        'count' of matching files.
    """
    files = list_transcript_files(
        directory=directory,
        pattern=pattern,
        job_id=job_id if job_id else None,
        session_id=session_id if session_id else None,
    )
    return {
        "transcripts": files,
        "count": len(files),
        "search_dir": directory,
        "filter_job_id": job_id or None,
        "filter_session_id": session_id or None,
    }


@mcp.tool()
def get_transcript_summary(path: str) -> dict:
    """Get a summary of a transcript including session metadata, timing, and counts.

    Args:
        path: Path to the transcript .jsonl file.

    Returns:
        dict with session metadata (job_id, workstream_id, phase, model,
        provider, session_id, start/end timestamps), timing (duration_ms,
        duration_seconds, num_turns), counts (step_count, tool_use_count,
        unique_tools, tool_counts, error_count), and outcome (exit_code,
        session_is_error, stop_reason, cost_usd, denied_tools).
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        summary = get_summary(transcript)
        return {"ok": True, **summary}
    except json.JSONDecodeError as e:
        return {"error": f"Failed to parse transcript: {e}"}
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_transcript_metadata(path: str) -> dict:
    """Get session context metadata from a transcript header.

    Args:
        path: Path to the transcript .jsonl file.

    Returns:
        dict with header fields: job_id, workstream_id, phase, model, provider,
        provider_url, opencode_version, working_directory, prompt (truncated
        to 500 chars), prompt_length, session_id, start_epoch_ms, start_iso,
        format_version, runner.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        h = transcript.header
        return {
            "ok": True,
            "job_id": h.job_id,
            "workstream_id": h.workstream_id,
            "phase": h.phase,
            "model": h.model,
            "provider": h.provider,
            "provider_url": h.provider_url,
            "opencode_version": h.opencode_version,
            "working_directory": h.working_directory,
            "prompt": (h.prompt[:500] + "...") if h.prompt and len(h.prompt) > 500 else h.prompt,
            "prompt_length": h.prompt_length,
            "session_id": h.session_id,
            "start_epoch_ms": h.start_epoch_ms,
            "start_iso": h.start_iso,
            "format_version": h.format_version,
            "runner": h.runner,
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_transcript_timing(path: str) -> dict:
    """Get timing and outcome metrics from a transcript footer.

    Args:
        path: Path to the transcript .jsonl file.

    Returns:
        dict with timing: duration_ms, duration_seconds; turns: num_turns;
        outcome: exit_code, stop_reason, session_is_error, killed_for_inactivity;
        cost: cost_usd; denied tools: denied_tool_names list.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        f = transcript.footer
        return {
            "ok": True,
            "duration_ms": f.duration_ms,
            "duration_seconds": (f.duration_ms / 1000.0) if f.duration_ms else None,
            "num_turns": f.num_turns,
            "exit_code": f.exit_code,
            "stop_reason": f.stop_reason,
            "session_is_error": f.session_is_error,
            "killed_for_inactivity": f.killed_for_inactivity,
            "cost_usd": f.cost_usd,
            "denied_tool_names": f.denied_tool_names,
            "end_epoch_ms": f.end_epoch_ms,
            "end_iso": f.end_iso,
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_tool_usage(path: str, limit: int = 100) -> dict:
    """Count and list tool invocations from a transcript.

    Args:
        path: Path to the transcript .jsonl file.
        limit: Maximum number of tool events to return in the events list
            (default: 100). Use 0 to disable events listing.

    Returns:
        dict with counts: total_tool_calls, unique_tool_count; per-tool
            counts: tool_counts {tool_name: count}; denied_tools list;
            and events list of tool_use events (up to limit).
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        tool_events = [e for e in transcript.events if e.type == "tool_use"]

        tool_counts = {}
        for e in tool_events:
            if e.tool:
                tool_counts[e.tool] = tool_counts.get(e.tool, 0) + 1

        events_list = []
        if limit > 0:
            for e in tool_events[:limit]:
                events_list.append({
                    "tool": e.tool,
                    "input": e.tool_input,
                    "raw": e.raw[:300] if len(e.raw) > 300 else e.raw,
                })

        return {
            "ok": True,
            "total_tool_calls": len(tool_events),
            "unique_tool_count": len(tool_counts),
            "tool_counts": tool_counts,
            "denied_tools": transcript.footer.denied_tool_names,
            "events": events_list,
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_events(
    path: str,
    event_type: str = "",
    tool_name: str = "",
    limit: int = 50,
) -> dict:
    """Filter and list events from a transcript event stream.

    Args:
        path: Path to the transcript .jsonl file.
        event_type: Filter by event type (e.g. "step_start", "step_finish",
            "text", "tool_use", "tool_result", "error"). Empty string matches
            all types.
        tool_name: Filter to tool_use events for a specific tool (substring
            match, case-insensitive). Ignored if event_type is set to a
            non-tool type. Case-insensitive.
        limit: Maximum number of events to return (default: 50). Use 0 for
            unlimited.

    Returns:
        dict with events list (type, text, tool, error_message, is_corrupt,
            raw truncated to 500 chars), total_matched count, and event_types
            found in this transcript.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)

        events = transcript.events
        if event_type:
            events = [e for e in events if e.type == event_type]
        elif tool_name:
            events = [
                e for e in events
                if e.type == "tool_use" and
                e.tool and tool_name.lower() in e.tool.lower()
            ]

        all_types = sorted(set(e.type for e in transcript.events if e.type))

        limited = events[:limit] if limit > 0 else events
        events_list = []
        for e in limited:
            entry = {
                "type": e.type,
                "is_corrupt": e.is_corrupt,
                "raw": e.raw[:500] if len(e.raw) > 500 else e.raw,
            }
            if e.type == "text" and e.text:
                entry["text"] = e.text
            if e.tool:
                entry["tool"] = e.tool
            if e.tool_input:
                entry["input"] = e.tool_input
            if e.error_message:
                entry["error_message"] = e.error_message
            events_list.append(entry)

        return {
            "ok": True,
            "events": events_list,
            "total_matched": len(events),
            "returned": len(events_list),
            "event_types": all_types,
            "filter_event_type": event_type or None,
            "filter_tool_name": tool_name if not event_type else None,
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_events_in_range(
    path: str,
    start_epoch_ms: int,
    end_epoch_ms: int,
) -> dict:
    """Get events within a time range.

    Events are evenly distributed across the session duration since opencode
    does not emit per-event timestamps. This is an approximation.

    Args:
        path: Path to the transcript .jsonl file.
        start_epoch_ms: Start of time range in milliseconds since epoch.
        end_epoch_ms: End of time range in milliseconds since epoch.

    Returns:
        dict with events list (index, type, text, tool, error_message,
            is_corrupt, estimated_epoch_ms, raw), total_in_range count,
            and session timing info.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        events_in_range = get_events_in_timerange(transcript, start_epoch_ms, end_epoch_ms)

        return {
            "ok": True,
            "events": events_in_range,
            "total_in_range": len(events_in_range),
            "session_start_epoch_ms": transcript.header.start_epoch_ms,
            "session_end_epoch_ms": transcript.footer.end_epoch_ms,
            "total_events": len(transcript.events),
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_timeline_summary(
    path: str,
    compact: bool = True,
) -> dict:
    """Get a concise timeline of all events in a transcript.

    Args:
        path: Path to the transcript .jsonl file.
        compact: If True (default), include only type and key fields. If False,
            include full raw event text.

    Returns:
        dict with timeline list (index, type, estimated_epoch_ms, and type-specific
            fields: text preview, tool name, input_keys, error, corrupt flag),
            total_events, session duration, and turn count.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        timeline = get_timeline(transcript, compact=compact)
        footer = transcript.footer
        header = transcript.header

        return {
            "ok": True,
            "timeline": timeline,
            "total_events": len(timeline),
            "session_start_epoch_ms": header.start_epoch_ms,
            "session_end_epoch_ms": footer.end_epoch_ms,
            "duration_ms": footer.duration_ms,
            "num_turns": footer.num_turns,
        }
    except Exception as e:
        return {"error": str(e)}


@mcp.tool()
def get_errors(path: str, include_corrupt: bool = True) -> dict:
    """Get all error and corruption events from a transcript.

    Args:
        path: Path to the transcript .jsonl file.
        include_corrupt: If True (default), include corrupted/non-JSON lines.
            If False, only include events with type="error".

    Returns:
        dict with errors list (index, type, error_message, is_corrupt, raw),
            error_count, corrupt_count, total_issues.
    """
    if not os.path.exists(path):
        return {"error": f"Transcript not found: {path}"}

    try:
        transcript = parse_transcript(path)
        error_events = [e for e in transcript.events if e.type == "error"]
        corrupt_events = [e for e in transcript.events if e.is_corrupt]

        errors_list = []
        if include_corrupt:
            source = transcript.events
        else:
            # TODO(review): enumerate(source) yields positions within error_events,
            # not within transcript.events, so "index" is wrong for include_corrupt=False.
            # Fix: enumerate(transcript.events) and filter in the loop condition instead.
            source = error_events

        for i, e in enumerate(source):
            if e.type == "error" or (include_corrupt and e.is_corrupt):
                errors_list.append({
                    "index": i,
                    "type": e.type,
                    "error_message": e.error_message,
                    "is_corrupt": e.is_corrupt,
                    "raw": e.raw[:500] if len(e.raw) > 500 else e.raw,
                })

        return {
            "ok": True,
            "errors": errors_list,
            "error_count": len(error_events),
            "corrupt_count": len(corrupt_events),
            "total_issues": len(errors_list),
        }
    except Exception as e:
        return {"error": str(e)}


if __name__ == "__main__":
    mcp.run()