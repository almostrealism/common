"""Collapse enforcement loops into compact summaries for ``workstream_context``.

When a branch goes round an enforcement loop — an agent edits, a rule rejects,
a remediation job runs, repeat — the raw ``workstream_context`` streams show it
as a long run of near-identical jobs and memories that a reader has to collapse
by hand. Two shapes recur:

* the same enforcement phase producing the same verdict across several
  consecutive jobs ("no-op review pass on the same commit", logged four or more
  times); and
* many near-identical memories written within a single job ("OOP review done …
  CLEAN", logged two dozen times).

This module detects both and replaces each run with one representative entry
carrying a ``loop`` annotation (count and time span). The individual entries
stay available when ``workstream_context`` is called with ``collapse_loops``
turned off, so nothing is lost — the default simply stops the loop from
drowning out the substantive notes around it.

The detection is intentionally textual and dependency-free: ``workstream_context``
runs in the manager, which has no embedder, so memory near-identity is judged
with :class:`difflib.SequenceMatcher` rather than embeddings. (Embedding-based
merging happens separately, at memory-store time, in the ar-memory server.)
"""

from datetime import datetime, timezone
from difflib import SequenceMatcher
from typing import Callable, Optional

# A run of fewer than this many consecutive entries is a retry, not a loop, and
# is left expanded. Three is the smallest run that reads as "this keeps
# happening" rather than "this happened twice"; it is also the size the task's
# synthetic-loop test exercises.
ENFORCEMENT_LOOP_MIN_RUN = 3

# Normalized-text similarity at or above which two consecutive memories are
# treated as near-identical. Loop memories are typically verbatim repeats
# (ratio ~1.0); distinct notes on a branch score far below this, so the floor
# is high enough that genuinely different memories are never collapsed.
MEMORY_NEAR_IDENTICAL_RATIO = 0.9


def _parse_timestamp(value: Optional[str]) -> Optional[datetime]:
    """Parse an ISO-8601 timestamp, tolerating a trailing ``Z``.

    Job timestamps arrive as ``…Z`` and memory timestamps as ``…+00:00``;
    Python 3.10's :func:`datetime.fromisoformat` rejects the former, so the
    ``Z`` is normalized first. Returns ``None`` for anything unparseable so a
    malformed timestamp degrades to "unknown span" instead of raising.
    """
    if not value or not isinstance(value, str):
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _normalize_text(text: Optional[str]) -> str:
    """Lowercase and collapse whitespace so trivial formatting differences
    between two otherwise-identical notes do not defeat the similarity check."""
    return " ".join((text or "").split()).lower()


def _group_runs(
    items: list,
    equivalent: Callable[[dict, dict], bool],
    min_run: int,
) -> list[tuple[int, int]]:
    """Return ``[start, end)`` index ranges of consecutive equivalent runs.

    A run is extended while each item is ``equivalent`` to the one before it,
    and reported only when it reaches ``min_run`` entries. Adjacency is judged
    in the order given; the caller's streams are newest-first, which is still
    consecutive-in-time, so the ranges are correct regardless of direction.
    """
    runs: list[tuple[int, int]] = []
    i = 0
    n = len(items)
    while i < n:
        j = i + 1
        while j < n and equivalent(items[j - 1], items[j]):
            j += 1
        if j - i >= min_run:
            runs.append((i, j))
        i = j
    return runs


def _span(items: list, start: int, end: int, ts_fn: Callable[[dict], Optional[str]]) -> dict:
    """Earliest and latest timestamp across ``items[start:end]``."""
    stamps = [ts_fn(it) for it in items[start:end]]
    parsed = [(_parse_timestamp(s), s) for s in stamps]
    present = [(p, s) for p, s in parsed if p is not None]
    if not present:
        return {"from": None, "to": None}
    earliest = min(present, key=lambda ps: ps[0])[1]
    latest = max(present, key=lambda ps: ps[0])[1]
    return {"from": earliest, "to": latest}


def _collapse(
    items: list,
    runs: list[tuple[int, int]],
    kind: str,
    ts_fn: Callable[[dict], Optional[str]],
    describe: Callable[[dict, int, dict], str],
) -> tuple[list, list]:
    """Replace each run with one annotated representative.

    Returns the collapsed list (runs replaced by their newest member, carrying
    a ``loop`` annotation) and the list of loop-summary dicts. Items outside a
    run pass through untouched.
    """
    run_starts = {start: end for start, end in runs}
    collapsed: list = []
    loops: list = []
    i = 0
    n = len(items)
    while i < n:
        if i in run_starts:
            end = run_starts[i]
            count = end - i
            span = _span(items, i, end, ts_fn)
            representative = dict(items[i])
            loop_meta = {
                "kind": kind,
                "count": count,
                "from": span["from"],
                "to": span["to"],
            }
            representative["loop"] = loop_meta
            collapsed.append(representative)
            summary = dict(loop_meta)
            summary["summary"] = describe(items[i], count, span)
            loops.append(summary)
            i = end
        else:
            collapsed.append(items[i])
            i += 1
    return collapsed, loops


def _normalized_job_key(job: dict) -> tuple[str, str]:
    """The (phase, verdict) a job's loop membership is keyed on.

    ``description`` names the enforcement phase and ``status`` is its verdict;
    normalizing the description keeps a run together across incidental
    whitespace or casing differences in how the phase was labelled.
    """
    return (_normalize_text(job.get("description")), str(job.get("status") or ""))


def summarize_job_loops(jobs: list, min_run: int = ENFORCEMENT_LOOP_MIN_RUN) -> tuple[list, list]:
    """Collapse consecutive same-phase, same-verdict jobs into loop entries.

    Args:
        jobs: The compact jobs timeline (each a dict with ``description``,
            ``status``, ``timestamp``).
        min_run: Minimum consecutive jobs to treat as a loop.

    Returns:
        ``(collapsed_jobs, loops)``.
    """
    if not jobs:
        return jobs, []

    def equivalent(a: dict, b: dict) -> bool:
        return _normalized_job_key(a) == _normalized_job_key(b)

    runs = _group_runs(jobs, equivalent, min_run)
    if not runs:
        return jobs, []

    def describe(job: dict, count: int, span: dict) -> str:
        phase = job.get("description") or "job"
        verdict = job.get("status") or "unknown"
        return (f"{phase!r} produced {verdict} across {count} consecutive jobs "
                f"({span['from']} … {span['to']})")

    return _collapse(jobs, runs, "jobs", lambda j: j.get("timestamp"), describe)


def _assign_job_buckets(memories: list, jobs: list) -> list[Optional[str]]:
    """Label each memory with the id of the job whose time window contains it.

    A memory belongs to the job that was running when it was written: the most
    recent job whose timestamp is at or before the memory's ``created_at``.
    This keeps the within-one-job rule honest — a near-identical run that
    straddles two jobs is two loops, not one. A memory whose owning job cannot
    be determined — no jobs were supplied, or the memory predates the oldest
    job in the (often limited) timeline — gets ``None``, which
    :func:`summarize_memory_loops` treats as an unsafe boundary that never
    collapses, rather than as one shared bucket that would collapse memories
    across unknown job boundaries.
    """
    job_times = []
    for job in jobs or []:
        parsed = _parse_timestamp(job.get("timestamp"))
        if parsed is not None:
            job_times.append((parsed, job.get("jobId")))
    job_times.sort(key=lambda pt: pt[0])

    buckets: list[Optional[str]] = []
    for mem in memories:
        created = _parse_timestamp(mem.get("created_at"))
        if created is None or not job_times:
            buckets.append(None)
            continue
        bucket = None
        for started, job_id in job_times:
            if started <= created:
                bucket = job_id
            else:
                break
        buckets.append(bucket)
    return buckets


def summarize_memory_loops(
    memories: list,
    jobs: list,
    min_run: int = ENFORCEMENT_LOOP_MIN_RUN,
) -> tuple[list, list]:
    """Collapse runs of near-identical memories written within one job.

    Two consecutive memories are part of the same loop when they fall in the
    same job's time window and their normalized text is at least
    :data:`MEMORY_NEAR_IDENTICAL_RATIO` similar.

    Args:
        memories: Branch memories, newest-first.
        jobs: The jobs timeline, used to bound runs to a single job.
        min_run: Minimum consecutive memories to treat as a loop.

    Returns:
        ``(collapsed_memories, loops)``.
    """
    if not memories:
        return memories, []

    buckets = _assign_job_buckets(memories, jobs)
    index_of = {id(mem): pos for pos, mem in enumerate(memories)}

    def equivalent(a: dict, b: dict) -> bool:
        bucket_a = buckets[index_of[id(a)]]
        bucket_b = buckets[index_of[id(b)]]
        # A memory with no determinable owning job (None) is an unsafe
        # boundary, not a bucket: collapsing across it would merge memories
        # from different (or unknown) jobs, violating the within-one-job rule.
        if bucket_a is None or bucket_b is None:
            return False
        if bucket_a != bucket_b:
            return False
        ratio = SequenceMatcher(
            None, _normalize_text(a.get("content")), _normalize_text(b.get("content"))
        ).ratio()
        return ratio >= MEMORY_NEAR_IDENTICAL_RATIO

    runs = _group_runs(memories, equivalent, min_run)
    if not runs:
        return memories, []

    def describe(mem: dict, count: int, span: dict) -> str:
        namespace = mem.get("namespace") or "memory"
        return (f"{count} near-identical {namespace} memories within one job "
                f"({span['from']} … {span['to']})")

    collapsed, loops = _collapse(
        memories, runs, "memories", lambda m: m.get("created_at"), describe)
    # Carry the owning job's id onto each memory-loop summary so a reader can
    # tie the collapsed run back to the job that produced it.
    for (start, _end), loop in zip(runs, loops):
        loop["job_id"] = buckets[start]
    return collapsed, loops


def summarize_loops(
    memories: list,
    jobs: list,
    collapse: bool = True,
    min_run: int = ENFORCEMENT_LOOP_MIN_RUN,
) -> dict:
    """Detect enforcement loops across a branch's memories and jobs.

    Args:
        memories: Branch memories, newest-first (already text-resolved).
        jobs: The compact jobs timeline, or an empty list.
        collapse: When true, the returned ``memories`` and ``jobs`` have each
            detected run replaced by one annotated representative. When false,
            both streams are returned untouched and only ``loops`` is computed,
            so a caller can surface that loops exist while still listing every
            entry.
        min_run: Minimum consecutive entries to treat as a loop.

    Returns:
        ``{"memories", "jobs", "loops"}``. ``loops`` is the combined list of
        job-loop and memory-loop summaries; it is empty when none are found.
    """
    mem_input = memories or []
    job_input = jobs or []

    collapsed_jobs, job_loops = summarize_job_loops(job_input, min_run)
    collapsed_memories, memory_loops = summarize_memory_loops(
        mem_input, job_input, min_run)

    return {
        "memories": collapsed_memories if collapse else mem_input,
        "jobs": collapsed_jobs if collapse else job_input,
        "loops": job_loops + memory_loops,
    }
