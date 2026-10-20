"""
Tier assignment for consult evidence.

Code assigns the tier; the model applies it. A small model cannot reliably
work out how old a note is, compare branches, or tell whether a class still
exists — Python can, so each note's standing is computed here by one pure
function, :func:`tier`, and handed to the model as a label with a short rule.

Tiers (see ``consult_evidence``):

- **B**  current note: on the target branch, merged into it, or on the
  caller's own branch; inside the recency window; names nothing absent.
- **B'** branch-local note: on an open or unmerged branch other than the
  caller's — true of that branch, not established for the target.
- **C**  historical note: older than the window, on a branch closed without
  merging, or of unknown branch state.
- **C✗** stale note: names a type or path the snapshot no longer contains.

Merge state is looked up live while the corpus is a fixed snapshot, so a
note's branch can merge *after* the snapshot was built. A note written after
the snapshot can likewise name a symbol the snapshot predates. Neither is
demoted to C✗ on a snapshot absence alone: the absence is recorded, but only a
note written before the snapshot is judged stale by it.
"""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

from consult_evidence import (
    TIER_BRANCH_LOCAL, TIER_CURRENT, TIER_HISTORICAL, TIER_STALE,
)

# Notes older than this, measured back from the snapshot date, are history.
RECENCY_WINDOW_DAYS = 45

# Branch states, as the branch-state lookup reports them.
STATE_TARGET = "target"          # the note was written on the target branch
STATE_MERGED = "merged"          # its PR merged into the target branch
STATE_MERGED_ELSEWHERE = "merged-elsewhere"  # merged, but into another base
STATE_OPEN = "open"
STATE_CLOSED = "closed-unmerged"
STATE_UNKNOWN = "unknown"

# UpperCamel names that are products and protocols, not code: a note naming
# them must not be judged stale because no class carries the name.
PROPER_NOUNS = frozenset({
    "GitHub", "GitLab", "FlowTree", "OpenRouter", "OpenCode", "OpenAI",
    "FastMCP", "JavaScript", "TypeScript", "PyTorch", "TensorFlow", "MacOS",
    "CodeRabbit", "YouTube", "PostgreSQL", "MySQL", "SQLite", "LlamaCpp",
    "HuggingFace", "SentencePiece", "DeepSeek", "MiniMax", "OAuth", "JUnit",
    "IntelliJ", "VSCode", "JetBrains", "CloudFlare", "GraalVM", "OpenCL",
    "CircleCI", "DockerHub", "MkDocs", "JavaDoc", "AutoBackend", "GitOps",
})

_UPPER_CAMEL = re.compile(r"(?<![\w.])[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\b")
_BACKTICK = re.compile(r"`([A-Za-z_][\w.$/\-]*)`")
_PATHISH = re.compile(
    r"(?<![\w/:.@~-])((?:\.?[\w\-]+/)+[\w.\-]*|[\w\-]+\.(?:java|sh|py|md|ya?ml))"
    r"(?::\d+)?\b")


@dataclass(frozen=True)
class Caller:
    """Who is asking, and about which branch.

    ``target_branch`` is the branch the question is about: the caller's
    workstream ``baseBranch``, defaulting to ``master``. ``branch`` is the
    caller's own working branch, if known.
    """

    branch: str = ""
    target_branch: str = "master"


@dataclass(frozen=True)
class BranchState:
    """The objective state of a note's branch, from its most recent PR."""

    state: str = STATE_UNKNOWN
    merged_at: str = ""
    base: str = ""
    pr_number: int = 0

    def describe(self) -> str:
        """A short label: ``merged@2026-10-16`` (with base), ``open`` …"""
        if self.state == STATE_MERGED and self.merged_at:
            return f"merged@{self.merged_at[:10]}"
        if self.state == STATE_MERGED_ELSEWHERE and self.merged_at:
            return f"merged@{self.merged_at[:10]} into {self.base}"
        return self.state


def parse_time(value) -> Optional[datetime]:
    """An aware datetime from an ISO string (``Z`` accepted), or ``None``."""
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    if not value or not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.strptime(value.strip()[:10], "%Y-%m-%d")
        except ValueError:
            return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def extract_mentions(text: str) -> list[str]:
    """The checkable names in ``text``: UpperCamel identifiers with two or
    more humps, backticked identifiers, and path- or file-shaped tokens
    (``file:line`` included, the line stripped).

    Everything else is *unchecked* — never treated as absent — which keeps
    false stale flags low at the cost of missing some stale notes. Absolute
    paths, URLs and home-relative paths are skipped: they name things outside
    the snapshot by construction.
    """
    found: list[str] = []

    def _add(token: str) -> None:
        token = token.strip().rstrip(".,;:)")
        if token and token not in found:
            found.append(token)

    cleaned = re.sub(r"https?://\S+|(?<![\w.])[~/][\w./\-]*", " ", text)
    for match in _BACKTICK.finditer(cleaned):
        _add(match.group(1))
    for match in _PATHISH.finditer(cleaned):
        _add(match.group(1))
    for match in _UPPER_CAMEL.finditer(cleaned):
        _add(match.group(0))
    return found


def classify_mentions(mentions: list[str], index) -> dict:
    """Split mentions into ``present``/``absent`` against the snapshot index.

    A mention that is neither a checkable path nor a checkable type (a
    proper noun, a branch name such as ``qa/docs-``, a member reference such
    as ``Foo.bar``'s ``bar``) is left out of both lists. A slash-bearing
    mention counts as absent only when it is rooted in a top-level directory
    the snapshot has yet names no file there; one the snapshot cannot speak to
    (another repository's path, a runtime output under a dot-directory the
    tree lacks) is left unchecked so a correct note is never tiered stale.
    Without an index every mention is unchecked.
    """
    present: list[str] = []
    absent: list[str] = []
    if index is None:
        return {"present": present, "absent": absent}
    for mention in mentions:
        if "/" in mention:
            # A slash-bearing mention is a path only when the tree can speak
            # to it. It is present when the snapshot contains it (including as
            # a trailing path fragment, per has_path), and absent only when it
            # is rooted in a top-level directory the snapshot does have, so a
            # missing file there is genuinely gone rather than merely external.
            # Everything else — a branch name (qa/docs-, origin/master), another
            # repository's path (ringsdesktop/docs/plan.md), a never-committed
            # runtime output under a dot-directory the tree lacks
            # (.flowtree/oop-review.md) — is left unchecked, so a correct note
            # that names it is never tiered stale.
            if index.has_path(mention):
                present.append(mention)
            elif index.is_tree_path(mention):
                absent.append(mention)
            continue
        if re.search(r"\.(java|sh|py|md|ya?ml)$", mention):
            (present if index.has_path(mention) else absent).append(mention)
            continue
        head = mention.split(".", 1)[0]
        if not re.fullmatch(r"[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+", head):
            continue
        if head in PROPER_NOUNS:
            continue
        target = present if index.has_type(head) else absent
        if head not in target:
            target.append(head)
    return {"present": present, "absent": absent}


def tier(note: dict, branch_state: BranchState, caller: Caller,
         snapshot_built_at: str, absent_names: list[str],
         now: Optional[datetime] = None) -> tuple[str, str]:
    """The tier of one note, and the reason it was given.

    Args:
        note: The memory record (``created_at``, ``branch`` are read).
        branch_state: The state of the note's branch.
        caller: The caller and the target branch of the question.
        snapshot_built_at: When the corpus snapshot was built (ISO); the
            recency window is measured back from it, falling back to ``now``.
        absent_names: Checkable names the note uses that the snapshot lacks.
        now: The current time; injected so the evaluation can freeze it.

    Returns:
        ``(tier, reason)``.
    """
    created = parse_time(note.get("created_at"))
    snapshot = parse_time(snapshot_built_at) or now or datetime.now(timezone.utc)
    branch = str(note.get("branch") or "")
    written_before_snapshot = created is None or created < snapshot

    if absent_names and written_before_snapshot:
        return TIER_STALE, "names absent from snapshot: " + ", ".join(absent_names)

    on_target = (branch == caller.target_branch
                 or branch_state.state in (STATE_TARGET, STATE_MERGED))
    own_branch = bool(branch) and branch == caller.branch
    recent = created is not None and created >= snapshot - timedelta(
        days=RECENCY_WINDOW_DAYS)

    if branch_state.state == STATE_CLOSED:
        return TIER_HISTORICAL, "branch closed without merging"
    if on_target or own_branch:
        if recent:
            where = "caller's branch" if own_branch and not on_target else \
                f"on {caller.target_branch}" if branch == caller.target_branch \
                else f"merged into {caller.target_branch}"
            return TIER_CURRENT, where
        return TIER_HISTORICAL, f"older than {RECENCY_WINDOW_DAYS} days"
    if branch_state.state in (STATE_OPEN, STATE_MERGED_ELSEWHERE):
        if recent:
            return TIER_BRANCH_LOCAL, f"branch {branch} is {branch_state.describe()}"
        return TIER_HISTORICAL, f"older than {RECENCY_WINDOW_DAYS} days"
    return TIER_HISTORICAL, "branch state unknown"
