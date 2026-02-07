"""
Request history logging for the AR Consultant MCP server.

Records every tool invocation with full context (inputs, doc/memory
retrieval results, prompts, LLM responses, latency) for:
  - Response quality evaluation
  - Documentation gap identification
  - Retrieval accuracy assessment
  - Fine-tuning dataset construction
  - Latency and backend performance tracking

Storage: SQLite with WAL mode at ``data/history.db`` (configurable via
``AR_CONSULTANT_HISTORY_DIR``).
"""

import contextvars
import functools
import json
import logging
import os
import sqlite3
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

log = logging.getLogger(__name__)

_SCRIPT_DIR = Path(__file__).parent
_DEFAULT_HISTORY_DIR = str(_SCRIPT_DIR / "data")

# Context variable holding the active RequestRecord for the current tool call.
_current_request: contextvars.ContextVar[Optional["RequestRecord"]] = (
    contextvars.ContextVar("_current_request", default=None)
)


# ---------------------------------------------------------------------------
# RequestRecord – mutable bag of data accumulated during a tool call
# ---------------------------------------------------------------------------

@dataclass
class RequestRecord:
    """Accumulates data for a single tool invocation."""

    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    tool_name: str = ""
    input_params: dict = field(default_factory=dict)

    # Documentation retrieval
    doc_query: Optional[str] = None
    doc_results: list[dict] = field(default_factory=list)

    # Memory retrieval
    memory_query: Optional[str] = None
    memory_results: list[dict] = field(default_factory=list)

    # LLM interaction
    prompt_text: Optional[str] = None
    llm_response: Optional[str] = None
    backend: Optional[str] = None
    llm_latency_ms: Optional[int] = None

    # Session
    session_id: Optional[str] = None

    # Outcome
    status: str = "success"
    error_message: Optional[str] = None
    result_summary: Optional[str] = None

    # Timing
    start_time: float = field(default_factory=time.monotonic)
    latency_ms: Optional[int] = None

    def add_doc_results(self, query: str, results: list[dict]) -> None:
        """Record documentation retrieval results."""
        self.doc_query = query
        self.doc_results = results

    def add_memory_results(self, query: str, results: list[dict]) -> None:
        """Record memory retrieval results."""
        self.memory_query = query
        self.memory_results = results

    def finish(self, result: Any) -> None:
        """Finalize timing and extract a result summary."""
        self.latency_ms = int((time.monotonic() - self.start_time) * 1000)
        if isinstance(result, dict):
            summary = result.get("answer") or result.get("summary") or result.get("response")
            if summary:
                self.result_summary = str(summary)[:500]


# ---------------------------------------------------------------------------
# HistoryStore – SQLite persistence
# ---------------------------------------------------------------------------

class HistoryStore:
    """Manages the ``history.db`` SQLite database."""

    def __init__(self, data_dir: Optional[str] = None):
        data_dir = data_dir or os.environ.get(
            "AR_CONSULTANT_HISTORY_DIR", _DEFAULT_HISTORY_DIR
        )
        self._dir = Path(data_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._db_path = self._dir / "history.db"
        self._init_db()

    # -- schema ---------------------------------------------------------------

    def _init_db(self) -> None:
        """Create tables and indices if they don't exist."""
        with self._connect() as conn:
            conn.executescript("""
                PRAGMA journal_mode = WAL;

                CREATE TABLE IF NOT EXISTS requests (
                    id              TEXT PRIMARY KEY,
                    timestamp       TEXT NOT NULL,
                    tool_name       TEXT NOT NULL,
                    input_params    TEXT,
                    doc_query       TEXT,
                    doc_result_count INTEGER,
                    memory_query    TEXT,
                    memory_result_count INTEGER,
                    prompt_text     TEXT,
                    llm_response    TEXT,
                    backend         TEXT,
                    latency_ms      INTEGER,
                    llm_latency_ms  INTEGER,
                    session_id      TEXT,
                    status          TEXT NOT NULL DEFAULT 'success',
                    error_message   TEXT,
                    result_summary  TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_requests_timestamp
                    ON requests(timestamp);
                CREATE INDEX IF NOT EXISTS idx_requests_tool_name
                    ON requests(tool_name);
                CREATE INDEX IF NOT EXISTS idx_requests_session_id
                    ON requests(session_id);
                CREATE INDEX IF NOT EXISTS idx_requests_status
                    ON requests(status);

                CREATE TABLE IF NOT EXISTS doc_chunks (
                    request_id  TEXT NOT NULL REFERENCES requests(id),
                    file        TEXT,
                    line        INTEGER,
                    context     TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_doc_chunks_request
                    ON doc_chunks(request_id);

                CREATE TABLE IF NOT EXISTS memory_hits (
                    request_id  TEXT NOT NULL REFERENCES requests(id),
                    memory_id   TEXT,
                    content     TEXT,
                    score       REAL,
                    namespace   TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_memory_hits_request
                    ON memory_hits(request_id);
            """)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self._db_path))
        conn.row_factory = sqlite3.Row
        return conn

    # -- write ----------------------------------------------------------------

    def save(self, record: RequestRecord) -> None:
        """Persist a completed RequestRecord (best-effort, never raises)."""
        try:
            self._save_impl(record)
        except Exception:
            log.exception("Failed to save request history record %s", record.id)

    def _save_impl(self, record: RequestRecord) -> None:
        from datetime import datetime, timezone

        ts = datetime.now(timezone.utc).isoformat()

        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO requests (
                    id, timestamp, tool_name, input_params,
                    doc_query, doc_result_count,
                    memory_query, memory_result_count,
                    prompt_text, llm_response, backend,
                    latency_ms, llm_latency_ms,
                    session_id, status, error_message, result_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    record.id,
                    ts,
                    record.tool_name,
                    json.dumps(record.input_params),
                    record.doc_query,
                    len(record.doc_results),
                    record.memory_query,
                    len(record.memory_results),
                    record.prompt_text,
                    record.llm_response,
                    record.backend,
                    record.latency_ms,
                    record.llm_latency_ms,
                    record.session_id,
                    record.status,
                    record.error_message,
                    record.result_summary,
                ),
            )

            for chunk in record.doc_results:
                conn.execute(
                    "INSERT INTO doc_chunks (request_id, file, line, context) "
                    "VALUES (?, ?, ?, ?)",
                    (record.id, chunk.get("file"), chunk.get("line"), chunk.get("context")),
                )

            for mem in record.memory_results:
                conn.execute(
                    "INSERT INTO memory_hits (request_id, memory_id, content, score, namespace) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (
                        record.id,
                        mem.get("id"),
                        mem.get("content"),
                        mem.get("score"),
                        mem.get("namespace"),
                    ),
                )

    # -- read -----------------------------------------------------------------

    def list_requests(
        self,
        limit: int = 20,
        tool_name: Optional[str] = None,
        status: Optional[str] = None,
        session_id: Optional[str] = None,
    ) -> list[dict]:
        """List recent requests with optional filters.

        Returns summarized records (no full prompt/response).
        """
        clauses = []
        params: list[Any] = []

        if tool_name:
            clauses.append("tool_name = ?")
            params.append(tool_name)
        if status:
            clauses.append("status = ?")
            params.append(status)
        if session_id:
            clauses.append("session_id = ?")
            params.append(session_id)

        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = (
            "SELECT id, timestamp, tool_name, doc_query, doc_result_count, "
            "memory_query, memory_result_count, backend, latency_ms, "
            "llm_latency_ms, session_id, status, error_message, result_summary "
            f"FROM requests{where} ORDER BY timestamp DESC LIMIT ?"
        )
        params.append(limit)

        with self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
            return [dict(row) for row in rows]

    def export_requests(
        self,
        since: Optional[str] = None,
        until: Optional[str] = None,
        tool_name: Optional[str] = None,
        include_prompts: bool = True,
        include_chunks: bool = True,
    ) -> list[dict]:
        """Export full request records for offline analysis.

        Args:
            since: ISO 8601 start timestamp (inclusive).
            until: ISO 8601 end timestamp (inclusive).
            tool_name: Filter by tool name.
            include_prompts: Include full prompt_text and llm_response.
            include_chunks: Include doc_chunks and memory_hits.

        Returns:
            List of complete request records.
        """
        clauses = []
        params: list[Any] = []

        if since:
            clauses.append("timestamp >= ?")
            params.append(since)
        if until:
            clauses.append("timestamp <= ?")
            params.append(until)
        if tool_name:
            clauses.append("tool_name = ?")
            params.append(tool_name)

        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM requests{where} ORDER BY timestamp DESC"

        with self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
            results = []
            for row in rows:
                rec = dict(row)

                if not include_prompts:
                    rec.pop("prompt_text", None)
                    rec.pop("llm_response", None)

                if include_chunks:
                    chunks = conn.execute(
                        "SELECT file, line, context FROM doc_chunks WHERE request_id = ?",
                        (rec["id"],),
                    ).fetchall()
                    rec["doc_chunks"] = [dict(c) for c in chunks]

                    mems = conn.execute(
                        "SELECT memory_id, content, score, namespace "
                        "FROM memory_hits WHERE request_id = ?",
                        (rec["id"],),
                    ).fetchall()
                    rec["memory_hits"] = [dict(m) for m in mems]

                results.append(rec)

            return results


# ---------------------------------------------------------------------------
# tracked_tool decorator
# ---------------------------------------------------------------------------

def tracked_tool(history: HistoryStore, tool_name: str) -> Callable:
    """Decorator that wraps an MCP tool function with history logging.

    Creates a ``RequestRecord``, stores it in ``_current_request``, runs
    the tool, finalises the record, and persists it.  Logging failures
    never propagate to the caller.

    Args:
        history: The HistoryStore instance to write to.
        tool_name: The name to record (e.g. ``"consult"``).
    """
    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            record = RequestRecord(tool_name=tool_name, input_params=kwargs)
            token = _current_request.set(record)
            try:
                result = fn(*args, **kwargs)
                record.finish(result)
                return result
            except Exception as exc:
                record.status = "error"
                record.error_message = str(exc)
                record.latency_ms = int(
                    (time.monotonic() - record.start_time) * 1000
                )
                raise
            finally:
                _current_request.reset(token)
                history.save(record)

        return wrapper
    return decorator


# ---------------------------------------------------------------------------
# Tracked LLM generation helper
# ---------------------------------------------------------------------------

def tracked_generate(
    llm: Any,
    prompt: str,
    system: Optional[str] = None,
    max_tokens: int = 1024,
    temperature: float = 0.3,
) -> str:
    """Call ``llm.generate()`` while recording prompt, response, and timing.

    If there is an active ``RequestRecord`` in ``_current_request``, the
    prompt text, LLM response, backend name, and LLM latency are written
    to it.  If there is no active record (e.g. called outside of a
    tracked tool), the function behaves identically to a direct
    ``llm.generate()`` call.
    """
    record = _current_request.get()

    t0 = time.monotonic()
    response = llm.generate(prompt, system=system, max_tokens=max_tokens, temperature=temperature)
    elapsed_ms = int((time.monotonic() - t0) * 1000)

    if record is not None:
        record.prompt_text = prompt
        record.llm_response = response
        record.backend = llm.name
        record.llm_latency_ms = elapsed_ms

    return response
