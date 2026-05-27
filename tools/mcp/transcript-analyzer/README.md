# Transcript Analyzer MCP Server

An MCP (Model Context Protocol) server that provides tools for analyzing opencode session transcripts captured during agent runs.

## Overview

Opencode sessions are automatically recorded as JSONL transcript files containing:
- **Header line**: Session context (job ID, model, provider, timestamps)
- **Event stream**: Raw NDJSON events from opencode stdout (turns, tool calls, text)
- **Footer line**: Outcome metrics (exit code, duration, cost, turns)

This server provides composable tools for querying and analyzing these transcripts without writing ad-hoc scripts.

## Installation

```bash
cd tools/mcp/transcript-analyzer
pip install -r requirements.txt
```

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENCODE_TRANSCRIPT_DIR` | `/tmp/opencode-transcripts` | Default transcript directory |
| `AR_PROJECT_ROOT` | auto-detected | Path to project root |

## Running the Server

```bash
python server.py
```

Or with custom configuration:

```bash
OPENCODE_TRANSCRIPT_DIR=/path/to/transcripts python server.py
```

## Claude Desktop Configuration

```json
{
  "mcpServers": {
    "ar-transcript-analyzer": {
      "command": "python",
      "args": ["/path/to/tools/mcp/transcript-analyzer/server.py"],
      "env": {
        "OPENCODE_TRANSCRIPT_DIR": "/tmp/opencode-transcripts"
      }
    }
  }
}
```

## Transcript Format

Each transcript is a JSONL file with three sections:

### 1. Header (`type: transcript_header`)

```json
{
  "type": "transcript_header",
  "format_version": 1,
  "runner": "opencode",
  "job_id": "2bff9061-cb0d-4f1c-830c-5cf95dc28a8a",
  "workstream_id": "f5f8cc46-5158-4d34-8d44-1b6adcf42e67",
  "phase": "primary",
  "model": "local/qwen3",
  "provider": "local",
  "opencode_version": "1.0.0",
  "session_id": "sess-abc123",
  "start_epoch_ms": 1700000000000,
  "start_iso": "2026-05-27T12:00:00.000Z"
}
```

### 2. Event Stream

Raw NDJSON from opencode stdout including:
- `step_start` / `step_finish` - turn boundaries
- `text` with `part.text` - model output
- `tool_use` - tool invocation
- `tool_result` - tool output
- `error` - error events

### 3. Footer (`type: transcript_footer`)

```json
{
  "type": "transcript_footer",
  "exit_code": 0,
  "session_is_error": false,
  "num_turns": 5,
  "cost_usd": 0.05,
  "duration_ms": 30000,
  "end_epoch_ms": 1700000030000,
  "end_iso": "2026-05-27T12:00:30.000Z"
}
```

## Tools

### list_transcripts

List transcript files in a directory with optional filtering.

**Parameters:**
- `directory` (optional): Directory to search (default: /tmp/opencode-transcripts)
- `pattern` (optional): Glob pattern (default: *.jsonl)
- `job_id` (optional): Filter by job ID substring match
- `session_id` (optional): Filter by session ID substring match

**Returns:**
```json
{
  "transcripts": [
    {"name": "20260527-120000-job123-primary.jsonl", "path": "...", "size_kb": 12.5}
  ],
  "count": 1
}
```

### get_transcript_summary

Get a complete summary of a transcript including all metadata, timing, and counts.

**Parameters:**
- `path` (required): Path to transcript .jsonl file

**Returns:**
```json
{
  "ok": true,
  "job_id": "job123",
  "model": "local/qwen3",
  "duration_ms": 30000,
  "num_turns": 5,
  "unique_tools": ["Bash", "Read", "Edit"],
  "tool_counts": {"Read": 10, "Edit": 3, "Bash": 5},
  "error_count": 0
}
```

### get_transcript_metadata

Extract session context from the transcript header.

**Parameters:**
- `path` (required): Path to transcript .jsonl file

**Returns:** job_id, workstream_id, phase, model, provider, opencode_version, working_directory, prompt (truncated to 500 chars), session_id, start_iso.

### get_transcript_timing

Extract timing and outcome metrics from the transcript footer.

**Parameters:**
- `path` (required): Path to transcript .jsonl file

**Returns:** duration_ms, duration_seconds, num_turns, exit_code, stop_reason, session_is_error, killed_for_inactivity, cost_usd, denied_tool_names.

### get_tool_usage

Count and list tool invocations.

**Parameters:**
- `path` (required): Path to transcript .jsonl file
- `limit` (optional): Max tool events to return (default: 100)

**Returns:**
```json
{
  "ok": true,
  "total_tool_calls": 18,
  "unique_tool_count": 3,
  "tool_counts": {"Read": 10, "Edit": 3, "Bash": 5},
  "events": [{"tool": "Read", "input": {"filePath": "..."}}]
}
```

### get_events

Filter and list events from the event stream.

**Parameters:**
- `path` (required): Path to transcript .jsonl file
- `event_type` (optional): Filter by type (step_start, text, tool_use, error, ...)
- `tool_name` (optional): Filter tool_use events by tool name (case-insensitive substring)
- `limit` (optional): Max events to return (default: 50)

**Returns:**
```json
{
  "ok": true,
  "events": [{"type": "tool_use", "tool": "Read", "input": {"filePath": "..."}}],
  "total_matched": 18,
  "event_types": ["step_start", "text", "tool_use", "tool_result", "step_finish"]
}
```

### get_events_in_range

Get events within a time range (estimated).

**Parameters:**
- `path` (required): Path to transcript .jsonl file
- `start_epoch_ms` (required): Start of time range (epoch ms)
- `end_epoch_ms` (required): End of time range (epoch ms)

**Returns:** Events with estimated timestamps in the range.

### get_timeline_summary

Get a concise timeline of all events.

**Parameters:**
- `path` (required): Path to transcript .jsonl file
- `compact` (optional): If true, include only key fields (default: true)

**Returns:** Timeline with index, type, estimated timestamps, and type-specific details.

### get_errors

Get all error and corruption events.

**Parameters:**
- `path` (required): Path to transcript .jsonl file
- `include_corrupt` (optional): Include corrupted lines (default: true)

**Returns:**
```json
{
  "ok": true,
  "errors": [{"type": "error", "error_message": "..."}],
  "error_count": 1,
  "corrupt_count": 0
}
```

## Usage Examples

```python
# 1. List all transcripts
list_transcripts(directory="/agent-transcripts")

# 2. Get summary of a specific transcript
get_transcript_summary(path="/tmp/opencode-transcripts/20260527-job123-primary.jsonl")

# 3. Find all tool invocations
get_tool_usage(path="/tmp/transcript.jsonl")

# 4. Get errors and corruption
get_errors(path="/tmp/transcript.jsonl")

# 5. Get events in a time range
get_events_in_range(
    path="/tmp/transcript.jsonl",
    start_epoch_ms=1700000000000,
    end_epoch_ms=1700000010000
)

# 6. Get full timeline (compact)
get_timeline_summary(path="/tmp/transcript.jsonl", compact=True)
```

## Storage Locations

Transcripts are written to the first applicable location:

1. `OPENCODE_TRANSCRIPT_DIR` environment variable (explicit override)
2. `/agent-transcripts` (per-agent mount in dockerized agent pool)
3. `{output_capture_parent}/transcripts/` (next to job output)
4. `/tmp/opencode-transcripts` (default ephemeral)

## Security Notes

- Transcripts may contain sensitive information (file paths, command arguments, output content)
- No secrets are exposed through this server; it only reads metadata and event data
- Corrupted lines are preserved verbatim for forensic analysis

## See Also

- [OpenCode Operations Docs](../../flowtree/docs/operations/OPENCODE.md) - Full transcript format documentation
- `OpencodeTranscriptWriter.java` - Java writer that produces these transcripts
- `OpencodeTranscriptWriterTest.java` - Tests for transcript format