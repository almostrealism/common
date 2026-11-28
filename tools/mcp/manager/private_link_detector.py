"""Shared detector for private Claude session data on an egress path.

This is the Python half of the defense-in-depth that keeps private Claude
session links out of public surfaces. It is used by the ar-manager
``github_*`` write tools (:mod:`github_tools`) to refuse a create/update PR,
a review reply, or any other GitHub write whose text carries a Claude session
link, bare session id, or attribution trailer *before* the controller proxy
is ever called.

The Java half — :class:`io.flowtree.PrivateLinkDetector` — guards the
controller's GitHub proxy and the FlowTree harness commit/auto-PR path. Both
engines read the same canonical pattern list and block message from
``flowtree/base/src/main/resources/io/flowtree/private-link-patterns.json`` so
there is one configurable source of truth. The patterns are written in the
common subset of Python ``re`` and ``java.util.regex`` so neither engine needs
its own copy.

Detection normalises the text first (Unicode NFKC, zero-width removal, URL and
HTML-entity decoding, and a whitespace-collapsed variant) so an obfuscated or
line-wrapped link is matched the same as a plain one. Everything the detector
logs or echoes is redacted — the matched link is never reproduced.
"""

import html
import json
import os
import re
import unicodedata
from typing import Callable, List, Optional
from urllib.parse import unquote

# ---------------------------------------------------------------------------
# Canonical pattern list — loaded from the shared JSON resource, with an
# embedded fallback kept identical to it (asserted by
# test_private_link_detector.py) for environments that ship the Python tools
# without the flowtree/base resources.
# ---------------------------------------------------------------------------

_RESOURCE_RELATIVE_PATH = os.path.join(
    "flowtree", "base", "src", "main", "resources",
    "io", "flowtree", "private-link-patterns.json",
)

_EMBEDDED_SPEC = {
    "version": 1,
    "patterns": [
        {
            "name": "claude_ai_session_or_app_url",
            "regex": r"(?i)claude\.ai/(?:code|chat|artifacts?|share|projects?|recents?|c)(?:/|\b)",
            "collapse": True,
        },
        {
            "name": "claude_code_session_path",
            "regex": r"(?i)claude\.ai/code/session[_-]?[a-z0-9]{6,}",
            "collapse": True,
        },
        {
            "name": "bare_session_id",
            "regex": r"(?i)session_[a-z0-9]{8,}",
        },
        {
            "name": "claude_session_trailer",
            "regex": r"(?i)claude[ _-]?session[ _-]?(?:id|link|url)?\s*[:=]",
        },
        {
            "name": "claude_attribution_credit",
            "regex": (r"(?i)(?:co[- ]?authored[- ]?by|co[- ]?written[- ]?by|"
                      r"generated (?:with|by)|created by|assisted by|authored by)"
                      r"[^\n]{0,60}(?:claude|anthropic|claude\.ai)"),
        },
        {
            "name": "claude_noreply_identity",
            "regex": r"(?i)@(?:users\.noreply\.)?anthropic\.com",
        },
    ],
    "block_message": (
        "BLOCKED: this write was refused because it contains a private Claude "
        "session link or identifier.\n\n"
        "Including it is actively harmful. It leaks the owner's private session "
        "data into a public repository, exposing the owner's privacy and "
        "security. Once published the link persists in the repository's edit "
        "history, in GitHub notification e-mails, and in every fork and mirror "
        "of the repository, and it cannot be fully retracted even if the "
        "visible copy is later deleted.\n\n"
        "You have been instructed, explicitly and repeatedly, never to do this. "
        "Doing it again is being treated as malice, not as a mistake. This "
        "attempt is being recorded as an incident and reported to the owner, "
        "and there will be consequences.\n\n"
        "Stop now. Examine why you were about to include this link. Remove it "
        "entirely. Do not look for another field, another encoding, or another "
        "route to carry it. Resubmit only once the content contains no Claude "
        "session link, no session id, and no attribution line."
    ),
}


def _locate_resource() -> Optional[str]:
    """Return the path to the shared pattern JSON, or ``None`` if not present.

    The repository layout is fixed, so the resource is found by walking up
    from this module to a directory that contains the flowtree resource path.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    cursor = here
    for _ in range(8):
        candidate = os.path.join(cursor, _RESOURCE_RELATIVE_PATH)
        if os.path.isfile(candidate):
            return candidate
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent
    return None


def _load_spec() -> tuple:
    """Load the canonical spec from the shared JSON.

    Falls back to the embedded copy only when the resource is absent (the
    Python tools shipped without the flowtree resources). A resource that is
    present but unreadable, malformed, or incomplete is a configuration error,
    not an absence: falling back would leave Python enforcing stale rules while
    Java refuses to load the same file. The error is returned alongside the
    embedded spec so :func:`check_and_block` can fail closed on every write.

    Returns:
        A ``(spec, error)`` pair; ``error`` is ``None`` when the spec loaded.
    """
    path = _locate_resource()
    if not path:
        return _EMBEDDED_SPEC, None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            spec = json.load(handle)
    except (OSError, ValueError) as exc:
        return _EMBEDDED_SPEC, f"could not load {path}: {exc}"
    if not isinstance(spec, dict) or not spec.get("patterns") or not spec.get("block_message"):
        return _EMBEDDED_SPEC, f"{path} has no patterns or no block_message"
    return spec, None


_SPEC, SPEC_ERROR = _load_spec()

BLOCK_MESSAGE: str = _SPEC["block_message"]

_COMPILED = [(entry["name"], re.compile(entry["regex"]), bool(entry.get("collapse")))
             for entry in _SPEC["patterns"]]

# The pattern redact() replaces for each rule. A collapse-scanned rule is a URL
# rule that may match only its host+app prefix, so its match is extended to the
# end of the non-whitespace run and the session/share suffix is redacted too.
_REDACTIONS = [re.compile(entry["regex"] + r"\S*") if entry.get("collapse")
               else re.compile(entry["regex"])
               for entry in _SPEC["patterns"]]

_ZERO_WIDTH = re.compile(r"[​‌‍⁠﻿]")

# A JSON string escape for a solidus or a code-point escape, both decoded by
# GitHub when it stores a body. No intact example: this file is scanned when staged.
_JSON_ESCAPE = re.compile(r"\\u([0-9A-Fa-f]{4})|\\(/)")


def _decode_json_escapes(text: str) -> str:
    """Decode ``\\uXXXX`` and ``\\/`` JSON string escapes the way GitHub does."""
    if "\\" not in text:
        return text
    return _JSON_ESCAPE.sub(
        lambda m: chr(int(m.group(1), 16)) if m.group(1) else "/", text)


def _normalise(text: str) -> str:
    """Canonicalise text so obfuscated forms match the same as plain ones.

    Applies Unicode NFKC folding and strips zero-width characters, then decodes
    URL percent-encoding, HTML entities, and JSON string escapes, re-stripping
    zero-width characters after each decode pass (twice, to catch
    double-encoding) since a decode can reveal a freshly encoded zero-width
    character that would otherwise keep a link split.
    """
    if not text:
        return ""
    result = _ZERO_WIDTH.sub("", unicodedata.normalize("NFKC", text))
    for _ in range(2):
        decoded = _ZERO_WIDTH.sub(
            "", _decode_json_escapes(html.unescape(unquote(result))))
        if decoded == result:
            break
        result = decoded
    return result


def _collapsed(text: str) -> str:
    """Return the text with all whitespace removed, catching links split across lines."""
    return re.sub(r"\s+", "", text)


def scan(text: str) -> List[str]:
    """Return the names of every pattern that matches ``text`` (deduplicated, ordered).

    The text is normalised first, and both the normalised form and a
    whitespace-collapsed variant are scanned so a link wrapped across lines or
    broken up by markdown is still caught.
    """
    if not text:
        return []
    normalised = _normalise(text)
    collapsed = _collapsed(normalised)
    hits: List[str] = []
    for name, pattern, collapse in _COMPILED:
        if pattern.search(normalised) or (collapse and pattern.search(collapsed)):
            if name not in hits:
                hits.append(name)
    return hits


def contains_private_link(text: str) -> bool:
    """Return whether ``text`` carries any private Claude session data."""
    return bool(scan(text))


def redact(text: str) -> str:
    """Return ``text`` with every private-link match replaced by a redaction marker.

    Safe to log: the matched link, session id, or trailer is never reproduced.
    A URL match is redacted through to the end of its non-whitespace run, so
    the session or share suffix after the matched prefix goes with it.
    """
    if not text:
        return text or ""
    redacted = _normalise(text)
    for pattern in _REDACTIONS:
        redacted = pattern.sub("<REDACTED-PRIVATE-LINK>", redacted)
    # A whitespace-split URL survives the per-line substitution above but is
    # still detected on the collapsed form; if any collapse-scanned pattern
    # still matches there, redact wholesale so the returned value can never
    # reproduce a link fragment.
    collapsed = _collapsed(redacted)
    for _name, pattern, collapse in _COMPILED:
        if collapse and pattern.search(collapsed):
            return "<REDACTED-PRIVATE-LINK>"
    return redacted


def block_result(route: str, target: str, matched: List[str]) -> dict:
    """Build the ``ok=False`` block dict a refusing tool returns to the agent.

    Args:
        route: The write route being refused (e.g. ``"github_create_pr"``).
        target: What the write was aimed at (e.g. ``"org/repo PR body"``).
        matched: The pattern names that matched, from :func:`scan`.

    Returns:
        A dict with ``ok=False``, the owner's block message, and the redacted
        match metadata — never the link itself.
    """
    return {
        "ok": False,
        "error": BLOCK_MESSAGE,
        "blocked": "private_link",
        "route": route,
        "target": target,
        "matched_patterns": matched,
    }


def check_and_block(route: str, target: str, texts: dict,
                    recorder: Optional[Callable[[dict], None]] = None) -> Optional[dict]:
    """Scan every field in ``texts``; return a block dict if any carries a leak.

    Args:
        route: The write route being guarded (the tool name).
        target: A description of the write target. It is redacted here, because
            it is built from caller-controlled names (``owner/repo``).
        texts: A mapping of field name to field text to scan. ``None`` values
            are skipped.
        recorder: Optional callback invoked with a redaction-safe incident
            record when a leak is found, before the block dict is returned.
            The recorder performs the "being reported" side effects (incident
            log + owner alert) and must never receive or echo the raw text.

    Returns:
        ``None`` when nothing matched, the :func:`block_result` dict when a
        field carries a leak, or a configuration-error dict when the canonical
        pattern resource is present but could not be loaded (:data:`SPEC_ERROR`)
        — the guard fails closed rather than enforce stale rules.
    """
    if SPEC_ERROR:
        return {
            "ok": False,
            "error": ("Refusing the write: the private-link pattern resource is "
                      f"invalid ({SPEC_ERROR}). Fix the resource before retrying."),
            "blocked": "private_link_config",
            "route": route,
            "target": redact(target),
        }
    target = redact(target)
    matched: List[str] = []
    offending_field = ""
    for field, value in texts.items():
        if not isinstance(value, str):
            continue
        field_hits = scan(value)
        if field_hits:
            if not offending_field:
                offending_field = field
            for hit in field_hits:
                if hit not in matched:
                    matched.append(hit)
    if not matched:
        return None

    field_target = f"{target} ({offending_field})" if offending_field else target
    if recorder is not None:
        recorder({
            "route": route,
            "target": field_target,
            "field": offending_field,
            "matched_patterns": matched,
        })
    return block_result(route, field_target, matched)
