"""
The curated concept map that consult retrieves against.

``docs/internals/consult-concept-map.md`` is a hand-maintained, machine-readable
map of the core abstractions in the codebase. Each ``##`` heading is one
concept; under it, bold-labelled list items give the concept statement, the
owning module, the canonical types that already represent it, how it is meant to
be extended, and the near-duplicates and anti-patterns that recur when an agent
reaches for a new type instead of the existing one.

This module parses that document into :class:`ConceptEntry` objects and scores
them against a consult question (and the files being touched), so the matching
entries can be presented as consult's own top evidence tier — ahead of document
fragments — telling the agent *the concept you are touching is X, its type is Y,
and this is how it is meant to be extended*. :meth:`ConceptMap.undeclared_types`
backs the upkeep check that fails when an entry names a type the source tree no
longer declares.

The map is parsed, never executed, and this module depends on nothing of the
project's, so it loads from a checkout and from the ar-manager image alike. The
document path is resolved the same way documentation is (``AR_DOCS_DIR`` when
set, otherwise relative to the repository root), so the two stay in step.
"""

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

# The map's location relative to the corpus root (the parent of the docs dir).
CONCEPT_MAP_RELATIVE = "docs/internals/consult-concept-map.md"

# Field labels, exactly as they appear in the document. ``canonical_types`` must
# be present and non-empty for a ``##`` section to count as a concept entry;
# that is what distinguishes a real entry from the "## Format" section, whose
# bold labels describe the fields rather than populate them.
_LABEL_CONCEPT = "Concept"
_LABEL_MODULE = "Module"
_LABEL_CANONICAL = "Canonical types"
_LABEL_EXTENSION = "Extension points"
_LABEL_ANTI = "Near-duplicates and anti-patterns"
_LABEL_KEYWORDS = "Keywords"

_HEADING = re.compile(r"^##\s+(.+?)\s*$")
_FIELD = re.compile(r"^\s*-\s*\*\*(?P<label>[^:*]+):\*\*\s*(?P<value>.*)$")
_BACKTICK_TYPE = re.compile(r"`([A-Za-z_][\w.]*)`")
_WORD = re.compile(r"[a-z0-9]{3,}")

# Scoring weights (see :meth:`ConceptMap.match`). A curated keyword phrase that
# the question contains is the strongest signal; a concept name or canonical
# type named as a whole word is next; shared vocabulary is a weak tie-breaker.
_SCORE_KEYWORD_PHRASE = 5.0
_SCORE_NAME = 4.0
_SCORE_TYPE = 4.0
_SCORE_WORD_OVERLAP = 1.0

# Common words that would match too many entries to narrow anything.
_STOP_WORDS = frozenset({
    "the", "and", "for", "with", "that", "this", "from", "into", "are", "was",
    "its", "has", "have", "not", "but", "can", "how", "what", "which", "when",
    "where", "does", "they", "all", "any", "new", "add", "use", "using", "type",
    "types", "class", "value", "values",
})


def _tokens(text: str) -> set:
    """The salient lowercase word tokens of ``text`` (length >= 3, stop words
    removed)."""
    return {w for w in _WORD.findall(text.lower()) if w not in _STOP_WORDS}


def _phrase_present(phrase: str, text: str) -> bool:
    """Whether ``phrase`` occurs in ``text`` on word boundaries.

    A raw substring test would let a short keyword surface an entry on an
    incidental fragment — ``cell`` inside ``cancellation`` — defeating the
    strong-signal requirement. Anchoring the (escaped) phrase on ``\\b`` keeps
    a genuine multi-word phrase matching while rejecting the fragment. Both
    arguments are expected lowercase."""
    if not phrase:
        return False
    return re.search(r"\b" + re.escape(phrase) + r"\b", text) is not None


@dataclass
class ConceptEntry:
    """One concept and the types that represent it.

    ``start``/``end`` are the 1-based inclusive line range the entry occupies in
    the document, so a citation names what the reader saw. ``order`` is the
    entry's position in the document, used only as a stable tie-breaker when two
    entries score equally.
    """

    name: str
    statement: str = ""
    module: str = ""
    canonical_types: list = field(default_factory=list)
    extension_points: str = ""
    anti_patterns: str = ""
    keywords: list = field(default_factory=list)
    start: int = 0
    end: int = 0
    order: int = 0

    def search_tokens(self) -> set:
        """Every word this entry can be matched on: its name, statement,
        keywords, module, extension points and canonical type names."""
        parts = [self.name, self.statement, self.module, self.extension_points,
                 " ".join(self.keywords), " ".join(self.canonical_types)]
        return _tokens(" ".join(parts))

    def render(self) -> str:
        """The entry as shown to the model: a compact, labelled block naming the
        concept, its module, its canonical types and how to extend it."""
        lines = [f"Concept: {self.name} — {self.statement}"]
        if self.module:
            lines.append(f"Module: {self.module}")
        if self.canonical_types:
            lines.append("Canonical types: "
                         + ", ".join(self.canonical_types))
        if self.extension_points:
            lines.append(f"Extension points: {self.extension_points}")
        if self.anti_patterns:
            lines.append(f"Near-duplicates and anti-patterns: {self.anti_patterns}")
        return "\n".join(lines)

    def label(self) -> str:
        """The short label a citation of this entry carries."""
        return f"concept {self.name}"


class ConceptMap:
    """The parsed concept map, with keyword/type retrieval over its entries."""

    def __init__(self, entries: list, path: str = ""):
        self.entries = entries
        self.path = path

    @classmethod
    def parse(cls, text: str, path: str = "") -> "ConceptMap":
        """Parse the map document ``text`` into its concept entries.

        A ``##`` section is a concept entry when it declares at least one
        canonical type; the format-describing section, whose bold labels carry
        no backticked type, is skipped.
        """
        entries: list = []
        lines = text.split("\n")
        heading_idx: Optional[int] = None
        name = ""
        fields: dict = {}

        def _flush(end_line: int) -> None:
            if heading_idx is None:
                return
            types = _BACKTICK_TYPE.findall(fields.get(_LABEL_CANONICAL, ""))
            if not types:
                return
            keywords = [k.strip() for k in fields.get(_LABEL_KEYWORDS, "").split(";")
                        if k.strip()]
            entries.append(ConceptEntry(
                name=name,
                statement=fields.get(_LABEL_CONCEPT, "").strip(),
                module=fields.get(_LABEL_MODULE, "").strip(),
                canonical_types=types,
                extension_points=fields.get(_LABEL_EXTENSION, "").strip(),
                anti_patterns=fields.get(_LABEL_ANTI, "").strip(),
                keywords=keywords,
                start=heading_idx + 1,
                end=end_line,
                order=len(entries),
            ))

        for i, line in enumerate(lines):
            match = _HEADING.match(line)
            if match:
                _flush(i)
                heading_idx = i
                name = match.group(1).strip()
                fields = {}
                continue
            field_match = _FIELD.match(line)
            if field_match and heading_idx is not None:
                fields[field_match.group("label").strip()] = field_match.group("value")
        _flush(len(lines))
        return cls(entries, path)

    @classmethod
    def load(cls, path: Optional[str] = None) -> Optional["ConceptMap"]:
        """The concept map at ``path`` (the default location when omitted), or
        ``None`` when the document is absent or unreadable."""
        resolved = Path(path) if path else default_path()
        try:
            text = resolved.read_text(encoding="utf-8")
        except OSError:
            return None
        return cls.parse(text, str(resolved))

    def match(self, query: str, limit: int = 3) -> list:
        """The concept entries ``query`` matches, most relevant first.

        ``query`` is the whole retrieval context — the question, its keywords
        and the names of any files being touched. An entry *qualifies* only on a
        strong signal: a curated keyword phrase the query contains, its concept
        name, or a canonical type named as a whole word (so a touched file such
        as ``IndexSequence.java`` matches the type it declares). Shared
        vocabulary alone never surfaces an entry — it would match unrelated
        questions on an incidental word — but it refines the ranking of entries
        that already qualified. Entries with no strong signal are dropped; ties
        keep document order.

        Args:
            query: The retrieval context to match against.
            limit: The most entries to return.

        Returns:
            The matching :class:`ConceptEntry` objects, highest score first.
        """
        tokens = _tokens(query)
        lowered = query.lower()
        scored: list = []
        for entry in self.entries:
            strong = 0.0
            for keyword in entry.keywords:
                if _phrase_present(keyword.lower().strip(), lowered):
                    strong += _SCORE_KEYWORD_PHRASE
            if entry.name.lower() in tokens:
                strong += _SCORE_NAME
            for type_name in entry.canonical_types:
                if type_name.lower() in tokens:
                    strong += _SCORE_TYPE
            if strong <= 0:
                continue
            weak = len(tokens & entry.search_tokens()) * _SCORE_WORD_OVERLAP
            scored.append((strong + weak, entry))
        scored.sort(key=lambda pair: (-pair[0], pair[1].order))
        return [entry for _, entry in scored[:limit]]

    def undeclared_types(self, type_exists: Callable[[str], bool]) -> list:
        """The entries naming a canonical type that ``type_exists`` rejects.

        Args:
            type_exists: Returns whether a type name is declared in the source
                tree (e.g. ``SnapshotIndex.declares_type``). A predicate that
                also accepts merely-imported names would let a deleted canonical
                type pass whenever another file still imports it.

        Returns:
            ``(entry, missing)`` pairs, one per entry that names at least one
            type the tree no longer declares, ``missing`` being those type
            names in document order.
        """
        flagged: list = []
        for entry in self.entries:
            missing = [t for t in entry.canonical_types if not type_exists(t)]
            if missing:
                flagged.append((entry, missing))
        return flagged


def default_path() -> Path:
    """The concept map's path, resolved like the documentation corpus so a
    checkout and the ar-manager image agree on it.

    ``AR_DOCS_DIR`` (the corpus baked into the ar-manager image) takes priority,
    exactly as :mod:`docs_retriever` resolves it; otherwise the path is taken
    relative to the repository root. Both bases are ``resolve()``-d so the
    result is correct even when this module was imported through a ``..`` entry
    on ``sys.path``."""
    env = os.environ.get("AR_DOCS_DIR", "").strip()
    common = (Path(env).resolve().parent if env
              else Path(__file__).resolve().parents[3])
    return common / CONCEPT_MAP_RELATIVE
