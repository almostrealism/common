#!/usr/bin/env python3
"""
Transcript parser for opencode session transcripts.

Parses JSONL transcript files produced by OpencodeTranscriptWriter.
Each transcript contains three sections:
1. Header line (type=transcript_header) - session context
2. Event stream - raw NDJSON from opencode stdout
3. Footer line (type=transcript_footer) - outcome metrics
"""

import json
import os
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional


@dataclass
class TranscriptHeader:
    """Session context from the transcript header line."""
    format_version: int
    runner: str
    start_epoch_ms: int
    start_iso: str
    job_id: Optional[str] = None
    workstream_id: Optional[str] = None
    phase: Optional[str] = None
    model: Optional[str] = None
    provider: Optional[str] = None
    provider_url: Optional[str] = None
    opencode_version: Optional[str] = None
    working_directory: Optional[str] = None
    prompt: Optional[str] = None
    prompt_length: Optional[int] = None
    session_id: Optional[str] = None


@dataclass
class TranscriptFooter:
    """Outcome metrics from the transcript footer line."""
    end_epoch_ms: int
    end_iso: str
    exit_code: Optional[int] = None
    killed_for_inactivity: bool = False
    stop_reason: Optional[str] = None
    session_is_error: bool = False
    num_turns: Optional[int] = None
    cost_usd: Optional[float] = None
    duration_ms: Optional[int] = None
    session_id: Optional[str] = None
    denied_tool_names: list = field(default_factory=list)


@dataclass
class TranscriptEvent:
    """A single event from the transcript event stream."""
    raw: str
    type: Optional[str] = None
    session_id: Optional[str] = None
    text: Optional[str] = None
    tool: Optional[str] = None
    tool_input: Optional[dict] = None
    error_message: Optional[str] = None
    is_corrupt: bool = False


@dataclass
class Transcript:
    """A parsed transcript with header, events, and footer."""
    path: str
    header: TranscriptHeader
    events: list
    footer: TranscriptFooter
    raw_lines: list = field(default_factory=list)


def parse_transcript(path: str) -> Transcript:
    """Parse a transcript JSONL file and return structured data.

    Args:
        path: Path to the transcript .jsonl file

    Returns:
        Transcript object with header, events, and footer

    Raises:
        FileNotFoundError: If the transcript file does not exist
        ValueError: If the transcript format is invalid (missing header/footer)
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"Transcript not found: {path}")

    with open(path, "r", encoding="utf-8") as f:
        lines = [line.rstrip("\n") for line in f if line.strip()]

    if len(lines) < 2:
        raise ValueError(f"Transcript too short (need header + footer): {path}")

    header = _parse_header(lines[0])
    footer = _parse_footer(lines[-1])
    events = [_parse_event(line) for line in lines[1:-1]]

    return Transcript(
        path=path,
        header=header,
        events=events,
        footer=footer,
        raw_lines=lines
    )


def _parse_header(line: str) -> TranscriptHeader:
    """Parse the header line into a TranscriptHeader."""
    node = json.loads(line)
    return TranscriptHeader(
        format_version=node.get("format_version", 1),
        runner=node.get("runner", ""),
        start_epoch_ms=node.get("start_epoch_ms", 0),
        start_iso=node.get("start_iso", ""),
        job_id=node.get("job_id"),
        workstream_id=node.get("workstream_id"),
        phase=node.get("phase"),
        model=node.get("model"),
        provider=node.get("provider"),
        provider_url=node.get("provider_url"),
        opencode_version=node.get("opencode_version"),
        working_directory=node.get("working_directory"),
        prompt=node.get("prompt"),
        prompt_length=node.get("prompt_length"),
        session_id=node.get("session_id"),
    )


def _parse_footer(line: str) -> TranscriptFooter:
    """Parse the footer line into a TranscriptFooter."""
    node = json.loads(line)
    denied = node.get("denied_tool_names", [])
    if isinstance(denied, str):
        denied = [denied]
    return TranscriptFooter(
        end_epoch_ms=node.get("end_epoch_ms", 0),
        end_iso=node.get("end_iso", ""),
        exit_code=node.get("exit_code"),
        killed_for_inactivity=bool(node.get("killed_for_inactivity", False)),
        stop_reason=node.get("stop_reason"),
        session_is_error=bool(node.get("session_is_error", False)),
        num_turns=node.get("num_turns"),
        cost_usd=node.get("cost_usd"),
        duration_ms=node.get("duration_ms"),
        session_id=node.get("session_id"),
        denied_tool_names=denied or [],
    )


def _parse_event(line: str) -> TranscriptEvent:
    """Parse a single event line, handling corruption gracefully."""
    try:
        node = json.loads(line)
        event_type = node.get("type")
        text = None
        error_message = None
        if event_type == "text":
            part = node.get("part", {})
            text = part.get("text") if isinstance(part, dict) else None
        elif event_type == "error":
            part = node.get("part", {})
            error_message = part.get("message") if isinstance(part, dict) else node.get("error")
            text = error_message

        tool = None
        tool_input = None
        if event_type == "tool_use":
            tool = node.get("tool")
            tool_input = node.get("input")

        return TranscriptEvent(
            raw=line,
            type=event_type,
            session_id=node.get("sessionID") or node.get("session_id"),
            text=text,
            tool=tool,
            tool_input=tool_input,
            error_message=error_message,
            is_corrupt=False,
        )
    except json.JSONDecodeError:
        return TranscriptEvent(
            raw=line,
            is_corrupt=True,
        )


def get_summary(transcript: Transcript) -> dict:
    """Extract a concise summary from a transcript."""
    header = transcript.header
    footer = transcript.footer
    events = transcript.events

    step_starts = sum(1 for e in events if e.type == "step_start")
    step_finishes = sum(1 for e in events if e.type == "step_finish")
    tool_uses = [e for e in events if e.type == "tool_use"]
    tool_results = [e for e in events if e.type == "tool_result"]
    errors = [e for e in events if e.type == "error" or e.is_corrupt]
    texts = [e for e in events if e.type == "text" and e.text]

    unique_tools = sorted(set(e.tool for e in tool_uses if e.tool))

    tool_counts = {}
    for e in tool_uses:
        if e.tool:
            tool_counts[e.tool] = tool_counts.get(e.tool, 0) + 1

    return {
        "path": transcript.path,
        "job_id": header.job_id,
        "workstream_id": header.workstream_id,
        "phase": header.phase,
        "model": header.model,
        "provider": header.provider,
        "session_id": header.session_id or footer.session_id,
        "start_iso": header.start_iso,
        "end_iso": footer.end_iso,
        "duration_ms": footer.duration_ms,
        "duration_seconds": (footer.duration_ms / 1000.0) if footer.duration_ms else None,
        "num_turns": footer.num_turns or step_starts,
        "step_count": step_starts,
        "step_finish_count": step_finishes,
        "tool_use_count": len(tool_uses),
        "tool_result_count": len(tool_results),
        "unique_tools": unique_tools,
        "tool_counts": tool_counts,
        "error_count": len(errors),
        "text_event_count": len(texts),
        "exit_code": footer.exit_code,
        "session_is_error": footer.session_is_error,
        "stop_reason": footer.stop_reason,
        "killed_for_inactivity": footer.killed_for_inactivity,
        "cost_usd": footer.cost_usd,
        "denied_tools": footer.denied_tool_names,
        "total_events": len(events),
        "corrupt_events": sum(1 for e in events if e.is_corrupt),
    }


def get_events_in_timerange(
    transcript: Transcript,
    start_epoch_ms: int,
    end_epoch_ms: int,
) -> list:
    """Get events within a time range based on estimated timestamps.

    Since opencode doesn't emit per-event timestamps, we estimate positions
    by assuming events are evenly distributed across the session duration.

    Args:
        transcript: The parsed transcript
        start_epoch_ms: Start of time range (epoch milliseconds)
        end_epoch_ms: End of time range (epoch milliseconds)

    Returns:
        List of events within the time range with estimated timestamps
    """
    if not transcript.events:
        return []

    header = transcript.header
    footer = transcript.footer
    duration = (footer.end_epoch_ms - header.start_epoch_ms) or 1
    total_events = len(transcript.events)

    events_in_range = []
    for i, event in enumerate(transcript.events):
        event_start = header.start_epoch_ms + int((i / total_events) * duration)
        event_end = header.start_epoch_ms + int(((i + 1) / total_events) * duration)

        if start_epoch_ms <= event_start < end_epoch_ms or \
           start_epoch_ms <= event_end < end_epoch_ms or \
           (event_start < start_epoch_ms and event_end > end_epoch_ms):
            events_in_range.append({
                "index": i,
                "type": event.type,
                "text": event.text,
                "tool": event.tool,
                "error_message": event.error_message,
                "is_corrupt": event.is_corrupt,
                "estimated_epoch_ms": event_start,
                "raw": event.raw[:500] if len(event.raw) > 500 else event.raw,
            })

    return events_in_range


def get_timeline(transcript: Transcript, compact: bool = True) -> list:
    """Build a concise timeline of events.

    Args:
        transcript: The parsed transcript
        compact: If True, include only type and key fields; if False, include raw

    Returns:
        List of timeline entries
    """
    header = transcript.header
    footer = transcript.footer
    events = transcript.events

    if not events:
        return []

    duration = (footer.end_epoch_ms - header.start_epoch_ms) or 1
    total_events = len(events)

    timeline = []
    for i, event in enumerate(events):
        estimated_time = header.start_epoch_ms + int((i / total_events) * duration)
        entry = {
            "index": i,
            "type": event.type or "unknown",
            "estimated_epoch_ms": estimated_time,
        }

        if event.type == "text" and event.text:
            entry["text"] = event.text[:200] + "..." if len(event.text) > 200 else event.text
        elif event.type == "tool_use":
            entry["tool"] = event.tool
            if event.tool_input:
                entry["input_keys"] = list(event.tool_input.keys()) if isinstance(event.tool_input, dict) else "?"
        elif event.type == "error":
            entry["error"] = (event.error_message or event.raw)[:200]
        elif event.is_corrupt:
            entry["corrupt"] = True
            entry["preview"] = event.raw[:100]

        if not compact:
            entry["raw"] = event.raw

        timeline.append(entry)

    return timeline


def list_transcript_files(
    directory: str,
    pattern: str = "*.jsonl",
    job_id: Optional[str] = None,
    session_id: Optional[str] = None,
) -> list:
    """List transcript files in a directory with optional filtering.

    Args:
        directory: Directory to search
        pattern: Glob pattern (default: *.jsonl)
        job_id: Optional filter by job ID (substring match)
        session_id: Optional filter by session ID (substring match)

    Returns:
        List of transcript file info dicts sorted by modification time (newest first)
    """
    search_path = Path(directory)
    if not search_path.exists():
        return []

    files = []
    for jsonl_path in sorted(search_path.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True):
        if not jsonl_path.is_file():
            continue

        try:
            size_kb = jsonl_path.stat().st_size / 1024
            entry = {
                "name": jsonl_path.name,
                "path": str(jsonl_path.absolute()),
                "size_kb": round(size_kb, 1),
                "modified_iso": datetime.fromtimestamp(jsonl_path.stat().st_mtime).isoformat(),
            }

            if job_id or session_id:
                header = {}
                try:
                    with open(jsonl_path, "r", encoding="utf-8") as f:
                        header_line = f.readline()
                    if header_line.strip():
                        header = json.loads(header_line)
                        header_job_id = (header.get("job_id") or "").lower()
                        header_session_id = (header.get("session_id") or "").lower()
                        if job_id and job_id.lower() not in header_job_id:
                            continue
                        if session_id and session_id.lower() not in header_session_id:
                            continue
                    entry["job_id"] = header.get("job_id")
                    entry["session_id"] = header.get("session_id")
                    entry["model"] = header.get("model")
                    entry["phase"] = header.get("phase")
                except (json.JSONDecodeError, OSError):
                    pass

            files.append(entry)
        except OSError:
            continue

    return files