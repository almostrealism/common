"""
The evidence list a consult answer is grounded in.

Every item consult shows the model — a documentation chunk, a prior note, or a
fact extracted from the snapshot index — becomes an :class:`EvidenceItem` with
a short ID (``D1``, ``N2``, ``I1``) assigned before the prompt is built. The
model cites those IDs and never a file name or line number; the server expands
each cited ID to the location the item actually covers. That removes invented
line numbers outright, and it lets every later stage (the citation verifier,
the answer-basis computation, the response's ``evidence`` list) refer to
exactly the item the model saw.

Reliability belongs to each item, not to the kind it came from, so every item
carries a *tier* (see :mod:`consult_tiers`). The prompt orders items by tier
and labels each with its tier and provenance, so a small model applies a
label instead of doing date arithmetic or comparing branches.
"""

import re
from dataclasses import dataclass, field
from typing import Optional

# Tiers. A and B may state present fact; B' only qualified by its branch;
# C and C-stale only history (see consult_tiers.tier for how they are assigned).
TIER_DOC = "A"
TIER_CURRENT = "B"
TIER_BRANCH_LOCAL = "B'"
TIER_HISTORICAL = "C"
TIER_STALE = "C✗"
# A note shown without a computed tier (memory_recall's summary, which does
# not look branch state up).
TIER_UNTIERED = "N"

# Prompt order: authoritative first.
TIER_ORDER = (TIER_DOC, TIER_CURRENT, TIER_BRANCH_LOCAL, TIER_HISTORICAL,
              TIER_STALE, TIER_UNTIERED)

# Tiers whose items may never supply a present-tense fact.
HISTORY_ONLY_TIERS = frozenset({TIER_HISTORICAL, TIER_STALE})

# Note tiers that count towards an answer resting on notes.
NOTE_FACT_TIERS = frozenset({TIER_CURRENT, TIER_BRANCH_LOCAL})

KIND_DOC = "doc"
KIND_NOTE = "note"
KIND_INDEX = "index"

_PREFIX = {KIND_DOC: "D", KIND_NOTE: "N", KIND_INDEX: "I"}

# A citation marker as the model writes it: ``[D1]``, ``[N2]``, or several IDs
# in one bracket, ``[D1, N2]``.
MARKER = re.compile(r"\[((?:[DNI]\d+)(?:\s*[,;]\s*[DNI]\d+)*)\]")
_MARKER_ID = re.compile(r"[DNI]\d+")

# How much of a note's text reaches the prompt. Notes are agent prose and can
# run to pages; the documentation keeps its own budget (DocsRetriever).
NOTE_PROMPT_CHARS = 1200


def marker_ids(text: str) -> list[str]:
    """Every ID cited in ``text``, in order of appearance, repeats included."""
    ids: list[str] = []
    for match in MARKER.finditer(text):
        ids.extend(_MARKER_ID.findall(match.group(1)))
    return ids


@dataclass
class EvidenceItem:
    """One piece of evidence, with the provenance the model and caller need.

    ``start``/``end`` are the 1-based inclusive lines a doc chunk covers (or
    the single line an index fact was extracted from). ``named_symbols`` is
    filled by mention extraction for notes: the checkable names a note uses,
    split by whether the snapshot still contains them.
    """

    id: str
    kind: str
    text: str
    tier: str = TIER_DOC
    file: str = ""
    start: int = 0
    end: int = 0
    section: str = ""
    label: str = ""
    memory_id: str = ""
    created_at: str = ""
    branch: str = ""
    namespace: str = ""
    branch_state: str = ""
    score: Optional[float] = None
    named_symbols: dict = field(
        default_factory=lambda: {"present": [], "absent": []})
    tier_reason: str = ""

    @property
    def key(self) -> str:
        """Stable identity across turns: what the item *is*, not its ID."""
        if self.kind == KIND_NOTE:
            return f"note:{self.memory_id or hash(self.text)}"
        return f"{self.kind}:{self.location()}:{self.label}"

    def location(self) -> str:
        """``file:start-end`` (or ``file:line``, or ``file``) for this item."""
        if not self.file:
            return ""
        if self.start and self.end and self.end != self.start:
            return f"{self.file}:{self.start}-{self.end}"
        if self.start:
            return f"{self.file}:{self.start}"
        return self.file

    def citation(self) -> str:
        """What an ``[ID]`` marker expands to in the answer."""
        if self.kind == KIND_NOTE:
            date = self.created_at[:10] if self.created_at else "undated"
            where = f", {self.branch}" if self.branch else ""
            return f"{self.id}: note {date}{where}"
        return f"{self.id}: {self.location()}"

    def tier_label(self, corpus: Optional[dict] = None) -> str:
        """The label the model applies its tier rule to."""
        if self.kind in (KIND_DOC, KIND_INDEX):
            commit = (corpus or {}).get("commit", "unknown")
            built = (corpus or {}).get("built_at", "")
            kind = "DOC" if self.kind == KIND_DOC else "INDEX"
            snap = f"snapshot {commit[:12]}" + (f", {built[:10]}" if built else "")
            return f"{kind} ({snap})"
        if self.tier == TIER_UNTIERED:
            return "NOTE"
        if self.tier == TIER_CURRENT:
            return "NOTE current"
        if self.tier == TIER_BRANCH_LOCAL:
            return f"NOTE branch-local ({self.branch or 'unknown branch'}, " \
                   f"{self.branch_state or 'unknown'})"
        if self.tier == TIER_STALE:
            gone = ", ".join(self.named_symbols.get("absent", [])) or "?"
            return f"NOTE stale — names absent from snapshot: {gone}"
        return f"NOTE historical ({self.created_at[:10] or 'undated'})"

    def provenance(self) -> str:
        """The second line of the item's prompt header."""
        if self.kind == KIND_NOTE:
            parts = [self.created_at[:10] or "undated"]
            if self.branch:
                parts.append(f"branch={self.branch}")
            if self.branch_state:
                parts.append(f"state={self.branch_state}")
            if self.namespace:
                parts.append(f"namespace={self.namespace}")
            if isinstance(self.score, (int, float)):
                parts.append(f"distance={self.score:.3f} (lower is closer)")
            return " ".join(parts)
        loc = self.location()
        if self.section:
            loc += f" § {self.section}"
        if self.label:
            loc += f" ({self.label})"
        return loc

    def haystack(self) -> str:
        """Everything a claim citing this item may be checked against: its
        text, location, heading trail and label."""
        return "\n".join(
            part for part in (self.text, self.file, self.section, self.label,
                              self.branch) if part)

    def to_response(self) -> dict:
        """The caller-facing record of this item."""
        record = {"id": self.id, "kind": self.kind, "tier": self.tier}
        if self.kind == KIND_NOTE:
            record.update({
                "memory_id": self.memory_id, "created_at": self.created_at,
                "branch": self.branch, "branch_state": self.branch_state,
                "namespace": self.namespace, "distance": self.score,
                "named_symbols": self.named_symbols,
            })
            if self.tier_reason:
                record["tier_reason"] = self.tier_reason
        else:
            record.update({"file": self.file, "range": [self.start, self.end],
                           "section": self.section})
            if self.label:
                record["label"] = self.label
            if self.kind == KIND_INDEX:
                record["value"] = self.text
        return record


class IdPool:
    """Allocates evidence IDs, keeping one ID per item identity.

    A conversation keeps one pool across its turns, so an item carried
    forward or retrieved again keeps the ID the caller already saw (``D1``
    stays ``D1``); a single-turn consult uses a fresh pool.
    """

    def __init__(self):
        self._ids: dict[str, str] = {}
        self._next = {prefix: 1 for prefix in _PREFIX.values()}

    def assign(self, kind: str, key: str) -> str:
        """The ID for the item identified by ``key``, allocating on first use."""
        if key in self._ids:
            return self._ids[key]
        prefix = _PREFIX[kind]
        new_id = f"{prefix}{self._next[prefix]}"
        self._next[prefix] += 1
        self._ids[key] = new_id
        return new_id


class EvidenceList:
    """The evidence for one consult turn, keyed by ID."""

    def __init__(self, corpus: Optional[dict] = None,
                 pool: Optional[IdPool] = None):
        self.corpus = corpus or {"commit": "unknown", "built_at": ""}
        self.pool = pool or IdPool()
        self._items: dict[str, EvidenceItem] = {}
        # Whether render() states "no documentation found" when there is
        # none, or leaves the section out (a caller with no corpus at all).
        self.show_empty_documentation = True

    def add(self, item: EvidenceItem) -> EvidenceItem:
        """Assign ``item`` its ID and add it; an item already present (same
        identity) is returned unchanged rather than added twice."""
        item.id = self.pool.assign(item.kind, item.key)
        existing = self._items.get(item.id)
        if existing is not None:
            return existing
        self._items[item.id] = item
        return item

    def add_doc(self, result: dict) -> EvidenceItem:
        """Add a documentation chunk (a ``DocsRetriever`` result)."""
        line = result.get("line", 0)
        return self.add(EvidenceItem(
            id="", kind=KIND_DOC, text=result.get("context", ""),
            file=result.get("file", ""), start=result.get("start", line),
            end=result.get("end", line), section=result.get("section", ""),
        ))

    def add_note(self, memory: dict, tier: str = TIER_HISTORICAL,
                 branch_state: str = "", named_symbols: Optional[dict] = None,
                 tier_reason: str = "") -> EvidenceItem:
        """Add a prior note with the tier computed for it."""
        return self.add(EvidenceItem(
            id="", kind=KIND_NOTE, text=memory.get("content", ""), tier=tier,
            memory_id=str(memory.get("id", "")),
            created_at=str(memory.get("created_at", "") or ""),
            branch=str(memory.get("branch", "") or ""),
            namespace=str(memory.get("namespace", "") or ""),
            branch_state=branch_state, score=memory.get("score"),
            named_symbols=named_symbols or {"present": [], "absent": []},
            tier_reason=tier_reason,
        ))

    def add_index(self, fact: dict) -> EvidenceItem:
        """Add a fact extracted from the snapshot index (tier A)."""
        line = fact.get("line", 0)
        return self.add(EvidenceItem(
            id="", kind=KIND_INDEX, text=fact.get("value", ""),
            file=fact.get("path", ""), start=line, end=line,
            label=fact.get("label", ""),
        ))

    def get(self, item_id: str) -> Optional[EvidenceItem]:
        """The item with ``item_id``, or ``None`` when no such item was shown."""
        return self._items.get(item_id)

    def items(self) -> list[EvidenceItem]:
        """Items in prompt order: by tier, then documentation before index
        facts before notes, then by ID number."""
        kind_rank = {KIND_DOC: 0, KIND_INDEX: 1, KIND_NOTE: 2}
        return sorted(self._items.values(), key=lambda it: (
            TIER_ORDER.index(it.tier) if it.tier in TIER_ORDER else 9,
            kind_rank.get(it.kind, 9), int(it.id[1:])))

    def of_kind(self, kind: str) -> list[EvidenceItem]:
        """Items of one kind, in prompt order."""
        return [it for it in self.items() if it.kind == kind]

    def __len__(self) -> int:
        return len(self._items)

    def render(self) -> str:
        """The evidence section of the prompt.

        Documentation and index facts come first under ``Relevant
        Documentation``, then notes under ``Notes From Prior Sessions``,
        each item headed by ``[ID] <tier label>`` and its provenance.
        """
        docs = [it for it in self.items() if it.kind != KIND_NOTE]
        notes = self.of_kind(KIND_NOTE)
        parts = []
        if docs:
            parts.append("### Relevant Documentation\n\n" + "\n\n".join(
                self._render_item(it) for it in docs))
        elif self.show_empty_documentation:
            parts.append("### Relevant Documentation\n\n"
                         "(No documentation found for this query)")
        if notes:
            parts.append("### Notes From Prior Sessions\n\n" + "\n\n".join(
                self._render_item(it) for it in notes))
        return "\n\n".join(parts)

    def _render_item(self, item: EvidenceItem) -> str:
        text = item.text
        if item.kind == KIND_NOTE and len(text) > NOTE_PROMPT_CHARS:
            text = text[:NOTE_PROMPT_CHARS] + " …"
        return (f"[{item.id}] {item.tier_label(self.corpus)}\n"
                f"{item.provenance()}\n{text}")

    def expand(self, text: str) -> str:
        """Expand every resolvable ``[ID]`` marker in ``text`` to the location
        it stands for, e.g. ``[D1]`` → ``[D1: .github/CLAUDE.md:849-861]``.

        A marker naming an ID that was never presented is left as written:
        the verifier reports it, and expanding it would invent a location.
        """
        def _expand(match: re.Match) -> str:
            ids = _MARKER_ID.findall(match.group(1))
            if not all(self.get(i) is not None for i in ids):
                return match.group(0)
            return "[" + "; ".join(self.get(i).citation() for i in ids) + "]"

        return MARKER.sub(_expand, text)

    def to_response(self) -> list[dict]:
        """The ``evidence`` field of the consult response."""
        return [it.to_response() for it in self.items()]
