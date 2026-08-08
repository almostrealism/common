"""
Dual-text handling for ar-memory entries.

The Consultant's ``remember`` tool rewrites an agent's note before storing
it ("reformulation"). The rewritten text becomes the entry ``content`` —
that is what gets embedded and ranked — while the text the agent actually
wrote is preserved in the entry ``source`` column as a JSON wrapper::

    {"original": "<what the agent wrote>", "user_source": "<caller source>"}

Reformulation is a **beta feature**: the rewrite runs on a small local
model and can drop detail, collapse specifics, or introduce claims the
author never made. Retrieval therefore presents the *original* text by
default, and exposes the reformulated text only when a caller explicitly
asks for it.

This module owns both halves of that encoding — writing the wrapper
(:func:`encode_dual_source`) and reading it back
(:func:`presented_entry`) — so every server, migration, and QC script
agrees on the format.
"""

import json
import os
from typing import Optional

# Keys inside the JSON ``source`` wrapper written by dual storage.
ORIGINAL_KEY = "original"
USER_SOURCE_KEY = "user_source"

# Environment variable that flips the default for every retrieval surface,
# including the ones that expose no per-call parameter.
PREFER_REFORMULATED_ENV = "AR_MEMORY_REFORMULATED"

# Warning attached to any response that carries reformulated text.
BETA_NOTICE = (
    "Reformulated memory text is a beta feature and its quality is still "
    "under development: the rewrite can drop detail, lose specifics, or "
    "state things the author did not. Verify against the original text "
    "(the default) before relying on it."
)

# Hint attached to responses whose entries have a reformulation available.
AVAILABILITY_HINT = (
    "Showing the original text authored by the agent. Pass "
    "reformulated=true to see the Consultant's rewrite instead (beta)."
)


def prefers_reformulated() -> bool:
    """Whether this environment opts in to reformulated text by default.

    A caller that asks for reformulated text explicitly always gets it;
    this is the fallback for callers that do not, and the only control
    available to retrieval surfaces that expose no parameter of their own
    (``consult``, ``start_consultation``, ``workstream_context``).

    Returns:
        True when ``AR_MEMORY_REFORMULATED`` is set to a truthy value.
    """
    value = os.environ.get(PREFER_REFORMULATED_ENV, "").strip().lower()
    return value in ("1", "true", "yes", "on")


def encode_dual_source(original: str, user_source: Optional[str] = None) -> str:
    """Build the JSON ``source`` wrapper that preserves the original text.

    Args:
        original: The text the agent wrote, before reformulation.
        user_source: The caller's own source identifier, if any.

    Returns:
        A JSON string suitable for the entry's ``source`` column.
    """
    return json.dumps({ORIGINAL_KEY: original, USER_SOURCE_KEY: user_source})


def decode_dual_source(source) -> Optional[dict]:
    """Parse the dual-text wrapper out of an entry's ``source`` value.

    Args:
        source: The raw ``source`` value from a memory entry. A verbatim
            memory carries ``None`` or a plain string here.

    Returns:
        The parsed wrapper dict, or ``None`` when ``source`` is not one.
    """
    if not source or not isinstance(source, str):
        return None
    try:
        parsed = json.loads(source)
    except (json.JSONDecodeError, TypeError):
        return None
    if isinstance(parsed, dict) and ORIGINAL_KEY in parsed:
        return parsed
    return None


def is_reformulated(entry: dict) -> bool:
    """Whether an entry's ``content`` is a reformulation of its original.

    Args:
        entry: A memory entry dict as returned by the ar-memory service.

    Returns:
        True when the entry carries the dual-text wrapper, meaning the
        stored ``content`` was rewritten and the author's text is
        recoverable.
    """
    return decode_dual_source(entry.get("source")) is not None


def original_text(entry: dict) -> str:
    """The text the memory's author actually wrote.

    Args:
        entry: A memory entry dict as returned by the ar-memory service.

    Returns:
        The unwrapped original for a reformulated entry, or the entry's
        ``content`` when it was stored verbatim.
    """
    wrapper = decode_dual_source(entry.get("source"))
    if wrapper is None:
        return entry.get("content") or ""
    return wrapper.get(ORIGINAL_KEY) or ""


def reformulated_text(entry: dict) -> Optional[str]:
    """The Consultant's rewrite of the memory, when one exists.

    Args:
        entry: A memory entry dict as returned by the ar-memory service.

    Returns:
        The reformulated text, or ``None`` for a verbatim entry.
    """
    if not is_reformulated(entry):
        return None
    return entry.get("content") or ""


def user_source(entry: dict):
    """The caller-supplied source identifier of a memory entry.

    Args:
        entry: A memory entry dict as returned by the ar-memory service.

    Returns:
        The plain source identifier, unwrapped from the dual-text JSON
        when the entry carries it. ``None`` when there is none.
    """
    raw = entry.get("source")
    wrapper = decode_dual_source(raw)
    if wrapper is None:
        return raw
    return wrapper.get(USER_SOURCE_KEY)


def presented_entry(entry: dict, reformulated: bool = False) -> dict:
    """Rewrite an entry so ``content`` holds the requested version of the text.

    The returned dict is a shallow copy with ``content`` resolved,
    ``source`` unwrapped to the caller's own identifier, and a
    ``text_source`` field recording which version is being shown
    (``"original"`` or ``"reformulated"``). When the reformulated text is
    requested, the original is included alongside it as ``original`` so
    the two can be compared.

    Args:
        entry: A memory entry dict as returned by the ar-memory service.
        reformulated: When true, present the Consultant's rewrite instead
            of the author's text. Entries stored verbatim are unaffected.

    Returns:
        A new entry dict ready to be returned to a tool caller.
    """
    presented = dict(entry)
    plain_source = user_source(entry)
    if plain_source is None:
        presented.pop("source", None)
    else:
        presented["source"] = plain_source

    rewrite = reformulated_text(entry)
    if rewrite is not None and reformulated:
        presented["content"] = rewrite
        presented["original"] = original_text(entry)
        presented["text_source"] = "reformulated"
    else:
        presented["content"] = original_text(entry)
        presented["text_source"] = "original"

    return presented


def presented_entries(entries: list, reformulated: bool = False) -> list:
    """Apply :func:`presented_entry` to every entry in a result list.

    Args:
        entries: Memory entries as returned by the ar-memory service.
        reformulated: When true, present the Consultant's rewrites.

    Returns:
        A new list of presented entry dicts, in the original order.
    """
    return [presented_entry(e, reformulated=reformulated) for e in entries]


def projected(entry: dict, keys) -> dict:
    """Select response fields from a presented entry.

    Tools return different subsets of a memory's fields, but every one of
    them must carry the text-version fields added by
    :func:`presented_entry` so a caller can tell which version it is
    reading. This selects the caller's keys and appends those.

    Args:
        entry: A presented entry (see :func:`presented_entry`).
        keys: The field names this tool returns.

    Returns:
        A new dict with the requested keys plus ``text_source``, and
        ``original`` when the reformulated text is being shown.
    """
    out = {key: entry.get(key) for key in keys}
    for field in ("text_source", "original"):
        if field in entry:
            out[field] = entry[field]
    return out


def present(entries: list, reformulated: bool = False) -> tuple:
    """Apply the text preference to a search result and derive its notice.

    This is the entry point retrieval tools use: it resolves which text
    each entry shows and produces the one notice that describes the
    choice, so every tool presents reformulation the same way.

    Args:
        entries: Raw memory entries as returned by the ar-memory service.
        reformulated: When true, present the Consultant's rewrites
            instead of the authors' text.

    Returns:
        A ``(entries, notice)`` tuple, where ``notice`` is ``None`` when
        no entry in the result has a reformulation.
    """
    return (
        presented_entries(entries, reformulated=reformulated),
        text_notice(entries, reformulated=reformulated),
    )


def text_notice(entries: list, reformulated: bool = False) -> Optional[str]:
    """The notice to attach to a response carrying these entries.

    Args:
        entries: The raw entries as returned by the ar-memory service —
            :func:`presented_entry` unwraps the ``source`` column, so a
            presented entry no longer carries the evidence this needs.
        reformulated: Whether reformulated text was requested.

    Returns:
        :data:`BETA_NOTICE` when reformulated text is being shown,
        :data:`AVAILABILITY_HINT` when originals are shown but at least
        one entry has a rewrite available, or ``None`` when neither
        applies.
    """
    if not any(is_reformulated(e) for e in entries):
        return None
    return BETA_NOTICE if reformulated else AVAILABILITY_HINT
