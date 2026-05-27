#!/usr/bin/env python3
"""
Tests for Transcript Analyzer MCP tools.

Uses representative transcript samples to verify parsing and analysis.
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from transcript_parser import (
    parse_transcript,
    get_summary,
    get_events_in_timerange,
    get_timeline,
    list_transcript_files,
    TranscriptHeader,
    TranscriptFooter,
    TranscriptEvent,
)

SAMPLE_HEADER = {
    "type": "transcript_header",
    "format_version": 1,
    "runner": "opencode",
    "job_id": "2bff9061-cb0d-4f1c-830c-5cf95dc28a8a",
    "workstream_id": "f5f8cc46-5158-4d34-8d44-1b6adcf42e67",
    "phase": "primary",
    "model": "local/qwen3",
    "provider": "local",
    "provider_url": "http://localhost:8080/v1",
    "opencode_version": "1.0.0",
    "working_directory": "/workspace/project/repo",
    "prompt": "Analyze the codebase and fix the bug",
    "prompt_length": 40,
    "session_id": "sess-abc123def456",
    "start_epoch_ms": 1700000000000,
    "start_iso": "2026-05-27T12:00:00.000Z"
}

SAMPLE_EVENTS = [
    '{"type":"step_start","sessionID":"sess-abc123def456"}',
    '{"type":"text","part":{"text":"I will analyze the codebase to find and fix the bug."}}',
    '{"type":"tool_use","tool":"Bash","input":{"command":"find . -name *.java | head -20"}}',
    '{"type":"tool_result","tool":"Bash","output":"File1.java\\nFile2.java"}',
    '{"type":"tool_use","tool":"Read","input":{"filePath":"src/Bug.java"}}',
    '{"type":"tool_result","tool":"Read","output":"Found the bug at line 42"}',
    '{"type":"text","part":{"text":"I found the bug. Let me fix it."}}',
    '{"type":"tool_use","tool":"Edit","input":{"filePath":"src/Bug.java","oldString":"// bug","newString":"// fixed"}}',
    '{"type":"tool_result","tool":"Edit","output":"Edit successful"}',
    '{"type":"step_finish","sessionID":"sess-abc123def456"}',
]

SAMPLE_FOOTER = {
    "type": "transcript_footer",
    "exit_code": 0,
    "killed_for_inactivity": False,
    "stop_reason": "success",
    "session_is_error": False,
    "num_turns": 1,
    "cost_usd": 0.03,
    "duration_ms": 15000,
    "session_id": "sess-abc123def456",
    "end_epoch_ms": 1700000015000,
    "end_iso": "2026-05-27T12:00:15.000Z",
}

ERROR_TRANSCRIPT_HEADER = {
    **SAMPLE_HEADER,
    "job_id": "job-with-error",
    "session_id": "sess-err001",
}

ERROR_TRANSCRIPT_EVENTS = [
    '{"type":"step_start","sessionID":"sess-err001"}',
    '{"type":"text","part":{"text":"Attempting a risky operation..."}}',
    '{"type":"tool_use","tool":"Bash","input":{"command":"rm -rf /important"}}',
    '{"type":"error","error":"Permission denied"}',
    '{"type":"text","part":{"text":"The operation failed."}}',
    '{"type":"step_finish","sessionID":"sess-err001"}',
    'CORRUPTED_LINE_NOT_JSON',
    '{"incomplete":',
]

ERROR_TRANSCRIPT_FOOTER = {
    **SAMPLE_FOOTER,
    "exit_code": 1,
    "session_is_error": True,
    "stop_reason": "error_unknown",
    "num_turns": 1,
    "duration_ms": 5000,
    "end_epoch_ms": 1700000005000,
    "end_iso": "2026-05-27T12:00:05.000Z",
}


def write_transcript(path: Path, header: dict, events: list, footer: dict):
    """Write a transcript file to disk."""
    lines = [json.dumps(header)] + events + [json.dumps(footer)]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class TestTranscriptParser(unittest.TestCase):
    """Tests for transcript parsing."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="transcript-test-")
        self.sample_transcript_path = Path(self.temp_dir) / "sample.jsonl"
        write_transcript(
            self.sample_transcript_path,
            SAMPLE_HEADER,
            SAMPLE_EVENTS,
            SAMPLE_FOOTER
        )
        self.error_transcript_path = Path(self.temp_dir) / "error.jsonl"
        write_transcript(
            self.error_transcript_path,
            ERROR_TRANSCRIPT_HEADER,
            ERROR_TRANSCRIPT_EVENTS,
            ERROR_TRANSCRIPT_FOOTER
        )

    def test_parse_valid_transcript(self):
        """A valid transcript is parsed correctly."""
        transcript = parse_transcript(str(self.sample_transcript_path))

        self.assertEqual(transcript.header.job_id, "2bff9061-cb0d-4f1c-830c-5cf95dc28a8a")
        self.assertEqual(transcript.header.model, "local/qwen3")
        self.assertEqual(transcript.header.provider, "local")
        self.assertEqual(transcript.header.start_epoch_ms, 1700000000000)

        self.assertEqual(transcript.footer.exit_code, 0)
        self.assertEqual(transcript.footer.duration_ms, 15000)
        self.assertEqual(transcript.footer.num_turns, 1)
        self.assertFalse(transcript.footer.session_is_error)

        self.assertEqual(len(transcript.events), len(SAMPLE_EVENTS))

    def test_parse_error_transcript(self):
        """Transcript with errors and corruption is parsed correctly."""
        transcript = parse_transcript(str(self.error_transcript_path))

        self.assertEqual(transcript.header.job_id, "job-with-error")
        self.assertEqual(transcript.footer.exit_code, 1)
        self.assertTrue(transcript.footer.session_is_error)

        corrupt_events = [e for e in transcript.events if e.is_corrupt]
        self.assertEqual(len(corrupt_events), 2)
        self.assertIn("CORRUPTED_LINE_NOT_JSON", corrupt_events[0].raw)

        error_events = [e for e in transcript.events if e.type == "error"]
        self.assertEqual(len(error_events), 1)
        self.assertEqual(error_events[0].error_message, "Permission denied")

    def test_parse_nonexistent_file(self):
        """FileNotFoundError for nonexistent paths."""
        with self.assertRaises(FileNotFoundError):
            parse_transcript("/nonexistent/path/transcript.jsonl")

    def test_parse_truncated_transcript(self):
        """ValueError for transcript with fewer than 2 lines."""
        short_path = Path(self.temp_dir) / "short.jsonl"
        short_path.write_text('{"type":"transcript_header"}\n', encoding="utf-8")

        with self.assertRaises(ValueError):
            parse_transcript(str(short_path))


class TestGetSummary(unittest.TestCase):
    """Tests for transcript summarization."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="summary-test-")
        self.sample_transcript_path = Path(self.temp_dir) / "sample.jsonl"
        write_transcript(
            self.sample_transcript_path,
            SAMPLE_HEADER,
            SAMPLE_EVENTS,
            SAMPLE_FOOTER
        )
        self.transcript = parse_transcript(str(self.sample_transcript_path))

    def test_summary_basic_fields(self):
        """Summary contains expected basic fields."""
        summary = get_summary(self.transcript)

        self.assertEqual(summary["job_id"], "2bff9061-cb0d-4f1c-830c-5cf95dc28a8a")
        self.assertEqual(summary["model"], "local/qwen3")
        self.assertEqual(summary["provider"], "local")
        self.assertEqual(summary["duration_ms"], 15000)
        self.assertEqual(summary["duration_seconds"], 15.0)
        self.assertEqual(summary["num_turns"], 1)
        self.assertEqual(summary["step_count"], 1)
        self.assertFalse(summary["session_is_error"])

    def test_summary_tool_counts(self):
        """Tool counts are computed correctly."""
        summary = get_summary(self.transcript)

        self.assertEqual(summary["tool_use_count"], 3)
        self.assertIn("Bash", summary["unique_tools"])
        self.assertIn("Read", summary["unique_tools"])
        self.assertIn("Edit", summary["unique_tools"])
        self.assertEqual(summary["tool_counts"]["Bash"], 1)
        self.assertEqual(summary["tool_counts"]["Read"], 1)
        self.assertEqual(summary["tool_counts"]["Edit"], 1)

    def test_summary_error_count(self):
        """Error and corrupt counts are computed correctly."""
        error_path = Path(self.temp_dir) / "error.jsonl"
        write_transcript(error_path, ERROR_TRANSCRIPT_HEADER, ERROR_TRANSCRIPT_EVENTS, ERROR_TRANSCRIPT_FOOTER)
        error_transcript = parse_transcript(str(error_path))
        summary = get_summary(error_transcript)

        self.assertEqual(summary["error_count"], 3)
        self.assertEqual(summary["corrupt_events"], 2)


class TestGetTimeline(unittest.TestCase):
    """Tests for timeline generation."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="timeline-test-")
        self.sample_transcript_path = Path(self.temp_dir) / "sample.jsonl"
        write_transcript(
            self.sample_transcript_path,
            SAMPLE_HEADER,
            SAMPLE_EVENTS,
            SAMPLE_FOOTER
        )
        self.transcript = parse_transcript(str(self.sample_transcript_path))

    def test_timeline_compact(self):
        """Compact timeline includes key fields only."""
        timeline = get_timeline(self.transcript, compact=True)

        self.assertEqual(len(timeline), len(SAMPLE_EVENTS))

        step_start = next(e for e in timeline if e["type"] == "step_start")
        self.assertIn("index", step_start)
        self.assertIn("estimated_epoch_ms", step_start)

        bash_tool = next(e for e in timeline if e.get("tool") == "Bash")
        self.assertIn("input_keys", bash_tool)
        self.assertEqual(bash_tool["input_keys"], ["command"])

        text_event = next(e for e in timeline if e.get("text"))
        self.assertIn("text", text_event)
        self.assertNotIn("raw", text_event)

    def test_timeline_full(self):
        """Full timeline includes raw events."""
        timeline = get_timeline(self.transcript, compact=False)

        self.assertIn("raw", timeline[0])
        self.assertTrue(len(timeline[0]["raw"]) > 0)


class TestGetEventsInTimerange(unittest.TestCase):
    """Tests for time-range event filtering."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="timerange-test-")
        self.sample_transcript_path = Path(self.temp_dir) / "sample.jsonl"
        write_transcript(
            self.sample_transcript_path,
            SAMPLE_HEADER,
            SAMPLE_EVENTS,
            SAMPLE_FOOTER
        )
        self.transcript = parse_transcript(str(self.sample_transcript_path))

    def test_events_in_range(self):
        """Events within time range are returned."""
        events = get_events_in_timerange(
            self.transcript,
            start_epoch_ms=1700000000000,
            end_epoch_ms=1700000005000
        )

        self.assertGreater(len(events), 0)
        for e in events:
            self.assertIn("index", e)
            self.assertIn("type", e)
            self.assertIn("estimated_epoch_ms", e)

    def test_events_outside_range(self):
        """No events when range is after session end."""
        events = get_events_in_timerange(
            self.transcript,
            start_epoch_ms=1700001000000,
            end_epoch_ms=1700002000000
        )

        self.assertEqual(len(events), 0)


class TestListTranscripts(unittest.TestCase):
    """Tests for transcript file listing."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="list-test-")
        self.sample_transcript_path = Path(self.temp_dir) / "20260527-job123-primary.jsonl"
        write_transcript(
            self.sample_transcript_path,
            SAMPLE_HEADER,
            SAMPLE_EVENTS,
            SAMPLE_FOOTER
        )

    def test_list_transcripts_basic(self):
        """All transcripts in directory are listed."""
        files = list_transcript_files(self.temp_dir)

        self.assertEqual(len(files), 1)
        self.assertEqual(files[0]["name"], "20260527-job123-primary.jsonl")
        self.assertIn("size_kb", files[0])
        self.assertIn("modified_iso", files[0])

    def test_list_transcripts_filter_by_job_id(self):
        """Job ID filter works correctly."""
        files = list_transcript_files(self.temp_dir, job_id="2bff9061")

        self.assertEqual(len(files), 1)
        self.assertEqual(files[0]["job_id"], "2bff9061-cb0d-4f1c-830c-5cf95dc28a8a")

        no_match = list_transcript_files(self.temp_dir, job_id="nonexistent")
        self.assertEqual(len(no_match), 0)

    def test_list_transcripts_nonexistent_dir(self):
        """Empty list for nonexistent directory."""
        files = list_transcript_files("/nonexistent/path")
        self.assertEqual(len(files), 0)


class TestTranscriptHeaderClass(unittest.TestCase):
    """Tests for TranscriptHeader dataclass."""

    def test_header_fields(self):
        """All header fields are accessible."""
        header = TranscriptHeader(
            format_version=1,
            runner="opencode",
            start_epoch_ms=1700000000000,
            start_iso="2026-05-27T12:00:00.000Z",
            job_id="job123",
            model="local/qwen3",
        )

        self.assertEqual(header.format_version, 1)
        self.assertEqual(header.runner, "opencode")
        self.assertEqual(header.job_id, "job123")
        self.assertEqual(header.model, "local/qwen3")
        self.assertIsNone(header.workstream_id)


class TestTranscriptFooterClass(unittest.TestCase):
    """Tests for TranscriptFooter dataclass."""

    def test_footer_fields(self):
        """All footer fields are accessible."""
        footer = TranscriptFooter(
            end_epoch_ms=1700000015000,
            end_iso="2026-05-27T12:00:15.000Z",
            exit_code=0,
            duration_ms=15000,
            num_turns=3,
            cost_usd=0.05,
        )

        self.assertEqual(footer.exit_code, 0)
        self.assertEqual(footer.duration_ms, 15000)
        self.assertEqual(footer.num_turns, 3)
        self.assertEqual(footer.cost_usd, 0.05)
        self.assertFalse(footer.session_is_error)
        self.assertEqual(footer.denied_tool_names, [])

    def test_footer_default_values(self):
        """Footer default values are set correctly."""
        footer = TranscriptFooter(end_epoch_ms=1700000015000, end_iso="2026-05-27T12:00:15.000Z")

        self.assertIsNone(footer.exit_code)
        self.assertIsNone(footer.duration_ms)
        self.assertFalse(footer.session_is_error)
        self.assertFalse(footer.killed_for_inactivity)
        self.assertEqual(footer.denied_tool_names, [])


class TestTranscriptEventClass(unittest.TestCase):
    """Tests for TranscriptEvent dataclass."""

    def test_event_fields(self):
        """All event fields are accessible."""
        event = TranscriptEvent(
            raw='{"type":"tool_use","tool":"Read"}',
            type="tool_use",
            tool="Read",
            tool_input={"filePath": "/src/File.java"},
        )

        self.assertEqual(event.type, "tool_use")
        self.assertEqual(event.tool, "Read")
        self.assertEqual(event.tool_input, {"filePath": "/src/File.java"})
        self.assertFalse(event.is_corrupt)

    def test_corrupt_event(self):
        """Corrupt events are marked correctly."""
        event = TranscriptEvent(
            raw="NOT_VALID_JSON",
            is_corrupt=True,
        )

        self.assertTrue(event.is_corrupt)
        self.assertIsNone(event.type)
        self.assertEqual(event.raw, "NOT_VALID_JSON")


if __name__ == "__main__":
    unittest.main()