"""
Deterministic checks that a consult answer's claims are supported by the
evidence they cite.

The model cites evidence by ID (``[D1]``); this module checks each sentence
against the items it cites, using nothing but token matching:

- **subject** — every identifier or path the sentence names must appear in
  at least one cited item. A sentence about ``validate-agent-commit.sh``
  that cites a chunk naming only ``detect-test-hiding.sh`` fails here.
- **value** — every number and quoted or backticked literal must appear
  verbatim in a cited item.
- **tier** — a present-tense sentence whose only support is historical or
  stale notes fails, even when its tokens match, because those notes may
  not supply present fact. A sentence resting only on a branch-local note
  must name the branch it is true on.
- **unknown citation** — a marker naming an ID that was never presented.
- **free-text location** — a ``file.md:123`` written by the model instead of
  an ID; the location cannot be checked and is often invented.
- **contradicted** — a sentence repeating a value from a note that a snapshot
  fact contradicts (a note's "exits 2" for a script whose literal exits are
  0, 1 and 3).

Uncited sentences are checked too, because a model can avoid the check by not
citing. An uncited sentence whose specifics appear in some presented item is
counted in ``uncited_claims``; one whose specifics appear in *no* item is a
claim with no evidence at all — the shape of every observed fabrication (an
invented branch prefix, an invented trailer format) — and is flagged.

Failing sentences are marked ``[unsupported]`` in the answer itself, so a
caller reading only ``answer`` still sees them. Token matching misses
paraphrase and so errs towards flagging, which is the right direction for a
tool whose callers are told to trust it.
"""

import re
from dataclasses import dataclass, field
from typing import Optional

from consult_evidence import (
    HISTORY_ONLY_TIERS, KIND_NOTE, MARKER, TIER_BRANCH_LOCAL, marker_ids,
)

UNSUPPORTED_MARK = "[unsupported]"

# Reasons a sentence can fail. Uncited sentences whose specifics are all
# somewhere in the evidence are recorded with REASON_UNCITED but not marked.
REASON_SUBJECT = "subject"
REASON_VALUE = "value"
REASON_TIER = "tier"
REASON_BRANCH_LOCAL = "branch-local"
REASON_UNKNOWN = "unknown citation"
REASON_FREE_TEXT = "free-text location"
REASON_UNCITED = "uncited"
REASON_UNSUPPORTED_UNCITED = "uncited, in no evidence item"
REASON_CONTRADICTED = "contradicted by snapshot"

# Reasons whose sentence's citations do not count towards the answer basis.
_NON_SUPPORTING = frozenset({REASON_TIER, REASON_BRANCH_LOCAL, REASON_CONTRADICTED})

# Reasons that put the inline mark on the sentence.
MARKED_REASONS = frozenset({
    REASON_SUBJECT, REASON_VALUE, REASON_TIER, REASON_BRANCH_LOCAL,
    REASON_UNKNOWN, REASON_UNSUPPORTED_UNCITED, REASON_CONTRADICTED,
})

_EXTENSIONS = r"(?:md|sh|py|java|ya?ml|json|html|txt|xml|toml|properties|kt|js|ts)"

# A backticked span.
_BACKTICK = re.compile(r"`([^`\n]+)`")
# A double-quoted span (straight or curly quotes).
_QUOTED = re.compile(r"[\"“]([^\"”\n]{2,80})[\"”]")
# A path: something with a slash, or a file name with a known extension.
_PATH = re.compile(
    r"(?<![\w/.\-])((?:\.?[\w\-]+/)+[\w.\-*<>{}]*|[\w.\-]+\." + _EXTENSIONS + r")\b/?")
# A model-written location citation such as ``claude-code-job.md:330``.
_FREE_TEXT_LOCATION = re.compile(r"[\w./\-]+\." + _EXTENSIONS + r":\d+")
# Identifiers: UpperCamel with two or more humps, SCREAMING_SNAKE, lowerCamel.
_UPPER_CAMEL = re.compile(r"\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\b")
_SCREAMING = re.compile(r"\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b")
_LOWER_CAMEL = re.compile(r"\b[a-z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\b")
_NUMBER = re.compile(r"(?<![\w.\-])\d+(?:\.\d+)?(?![\w\-])")
# A list-item ordinal at the start of a line ("1. ", "2) ").
_ORDINAL = re.compile(r"^\s*\d+[.)]\s")

# Wording that frames a sentence as history rather than present fact.
_HISTORICAL = re.compile(
    r"\b(used to|historically|formerly|previously|no longer|was|were|had|"
    r"had been|removed|renamed|deleted|replaced|originally|in the past|"
    r"at the time|once|earlier)\b", re.IGNORECASE)

# Wording that states an absence rather than a claim.
_ABSTENTION = re.compile(
    r"(not documented|does not (?:state|say|cover|mention|specify|document)|"
    r"do not (?:state|say|cover|mention|specify)|no (?:item|document|evidence)|"
    r"is not (?:stated|covered|documented|specified)|not (?:stated|covered|"
    r"specified|mentioned)|unknown|not found|no file named)", re.IGNORECASE)

_TRAILER = re.compile(r"^\s*(BASIS|CONFLICTS)\s*:", re.IGNORECASE)


@dataclass
class SentenceCheck:
    """The verdict on one sentence."""

    text: str
    start: int
    end: int
    cited: list = field(default_factory=list)
    unknown: list = field(default_factory=list)
    reasons: list = field(default_factory=list)
    missing: list = field(default_factory=list)

    @property
    def marked(self) -> bool:
        return any(r in MARKED_REASONS for r in self.reasons)


@dataclass
class VerificationReport:
    """What the verifier found in one answer."""

    sentences: list = field(default_factory=list)
    annotated: str = ""

    @property
    def warnings(self) -> list[dict]:
        """``citation_warnings`` for the response: one entry per failing
        reason of each sentence, uncited-but-grounded sentences excluded."""
        out = []
        for s in self.sentences:
            for reason in s.reasons:
                if reason == REASON_UNCITED:
                    continue
                entry = {"sentence": s.text.strip(), "reason": reason,
                         "marker": ",".join(s.cited + s.unknown)}
                if s.missing and reason in (REASON_SUBJECT, REASON_VALUE,
                                            REASON_UNSUPPORTED_UNCITED):
                    entry["missing"] = list(s.missing)
                out.append(entry)
        return out

    @property
    def uncited_claims(self) -> int:
        """Factual sentences that cite nothing."""
        return sum(1 for s in self.sentences
                   if REASON_UNCITED in s.reasons
                   or REASON_UNSUPPORTED_UNCITED in s.reasons)

    def supporting_ids(self) -> list[str]:
        """IDs cited by sentences whose citations may raise the answer basis.

        A sentence failing ``tier`` rests on notes the tier rules forbid from
        supplying present fact; one failing ``branch-local`` asserts for the
        target branch what a note establishes only for its own; one failing
        ``contradicted`` repeats a value the snapshot disproves. None of their
        citations can raise the basis; every other resolved citation can.
        """
        ids: list[str] = []
        for s in self.sentences:
            if set(s.reasons) & _NON_SUPPORTING:
                continue
            for item_id in s.cited:
                if item_id not in ids:
                    ids.append(item_id)
        return ids

    def sentences_resting_on(self, tiers: frozenset) -> list[SentenceCheck]:
        """Sentences every one of whose citations is in ``tiers``."""
        return [s for s in self.sentences if s.cited
                and all(self._tier_of(i) in tiers for i in s.cited)]

    _tiers: dict = field(default_factory=dict)

    def _tier_of(self, item_id: str) -> str:
        return self._tiers.get(item_id, "")


def split_sentences(text: str) -> list[tuple[int, int]]:
    """Character spans of the claim-bearing sentences of ``text``.

    Lines are split first (list items and table rows are one claim each),
    then each line on sentence-ending punctuation followed by a capital,
    backtick or bracket. Fenced code blocks, headings, blank lines and the
    ``BASIS:``/``CONFLICTS:`` trailer are skipped: code examples are quoted
    material, not claims.
    """
    spans: list[tuple[int, int]] = []
    in_fence = False
    offset = 0
    for line in text.split("\n"):
        line_start = offset
        offset += len(line) + 1
        stripped = line.strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence or not stripped or stripped.startswith("#") \
                or _TRAILER.match(line):
            continue
        cursor = 0
        for boundary in re.finditer(r"(?<=[.!?])\s+(?=[A-Z`\[(*])", line):
            spans.append((line_start + cursor, line_start + boundary.start()))
            cursor = boundary.end()
        spans.append((line_start + cursor, line_start + len(line)))
    return [(a, b) for a, b in spans if text[a:b].strip()]


def _normalise_path(token: str) -> str:
    """Strip glob and placeholder decoration so a path is matched by its
    literal stem: ``project/doc-*`` → ``project/doc-``, ``.github/actions/**``
    → ``.github/actions/``."""
    return re.sub(r"(\*+|<[^>]*>|\{[^}]*\})+$", "", token.rstrip(".,;:")).rstrip("*")


def salient_tokens(sentence: str) -> tuple[list[str], list[str]]:
    """The checkable specifics of a sentence: ``(subjects, values)``.

    Subjects are identifiers and paths; values are numbers and quoted or
    backticked literals that are not themselves identifiers or paths.
    Citation markers and free-text locations are removed first so an ID's
    digits are not mistaken for a claimed number.
    """
    body = MARKER.sub(" ", sentence)
    body = _FREE_TEXT_LOCATION.sub(" ", body)
    body = _ORDINAL.sub(" ", body)
    subjects: list[str] = []
    values: list[str] = []

    def _add(target: list, token: str) -> None:
        token = token.strip()
        if token and token not in target:
            target.append(token)

    for match in _BACKTICK.finditer(body):
        span = match.group(1).strip()
        if re.fullmatch(r"-?\d+(?:\.\d+)?", span):
            _add(values, span)
        elif "/" in span and " " not in span or re.fullmatch(r"[\w.$#\-]+", span):
            _add(subjects, span)
        else:
            _add(values, span)
    body = _BACKTICK.sub(" ", body)
    for match in _QUOTED.finditer(body):
        _add(values, match.group(1))
    body = _QUOTED.sub(" ", body)
    for match in _PATH.finditer(body):
        _add(subjects, match.group(1))
    body = _PATH.sub(" ", body)
    for pattern in (_UPPER_CAMEL, _SCREAMING, _LOWER_CAMEL):
        for match in pattern.finditer(body):
            _add(subjects, match.group(0))
    for match in _NUMBER.finditer(body):
        _add(values, match.group(0))
    return subjects, values


def _found(token: str, haystack: str, is_path: bool) -> bool:
    """Whether ``token`` appears in ``haystack`` as a whole (case-insensitive).

    "As a whole" matters: ``qa/coverage`` is a different branch prefix from
    ``qa/coverage-``, and ``MAX_OPEN_PRS`` a different name from
    ``MAX_OPEN_PRS_LIMIT``, so a match must not continue into a longer name.
    A token that ends in ``/`` or ``-`` is itself a prefix and may. A path
    also matches on its file name alone, since a chunk names a script by its
    basename more often than by its full path.
    """
    low = haystack.lower()
    if is_path:
        norm = _normalise_path(token).lower()
        if not norm:
            return True
        if _whole(norm, low):
            return True
        base = norm.rstrip("/").rsplit("/", 1)[-1]
        return bool(base) and "." in base and _whole(base, low)
    if re.fullmatch(r"\d+(?:\.\d+)?", token):
        return re.search(rf"(?<![\d.]){re.escape(token)}(?![\d])", low) is not None
    return _whole(token.lower(), low)


def _whole(needle: str, low: str) -> bool:
    """``needle`` occurs in ``low`` not embedded in a longer name."""
    tail = "" if needle.endswith(("/", "-")) else r"(?![\w\-])"
    head = "" if needle.startswith(("/", ".", "-")) else r"(?<![\w\-])"
    return re.search(head + re.escape(needle) + tail, low) is not None


def _is_path(token: str) -> bool:
    return "/" in token or re.search(r"\." + _EXTENSIONS + r"$", token) is not None


def verify(answer: str, evidence,
           contradicted: Optional[dict] = None) -> VerificationReport:
    """Check every sentence of ``answer`` against ``evidence``.

    Args:
        answer: The answer body, citation markers unexpanded and the trailer
            already removed.
        evidence: The ``EvidenceList`` the answer was synthesised from.
        contradicted: Note ID to the values in it that a snapshot fact
            contradicts; a sentence citing that note and stating one of
            those values fails.

    Returns:
        A :class:`VerificationReport`, including the answer with failing
        sentences marked :data:`UNSUPPORTED_MARK`.
    """
    report = VerificationReport()
    report._tiers = {it.id: it.tier for it in evidence.items()}
    all_text = "\n".join(it.haystack() for it in evidence.items())

    for start, end in split_sentences(answer):
        sentence = answer[start:end]
        check = SentenceCheck(text=sentence, start=start, end=end)
        for item_id in marker_ids(sentence):
            if evidence.get(item_id) is None:
                if item_id not in check.unknown:
                    check.unknown.append(item_id)
            elif item_id not in check.cited:
                check.cited.append(item_id)
        if check.unknown:
            check.reasons.append(REASON_UNKNOWN)
        if _FREE_TEXT_LOCATION.search(MARKER.sub(" ", sentence)):
            check.reasons.append(REASON_FREE_TEXT)

        subjects, values = salient_tokens(sentence)
        abstaining = _ABSTENTION.search(sentence) is not None
        if check.cited:
            _check_cited(check, evidence, subjects, values)
            bad = {v for i in check.cited for v in (contradicted or {}).get(i, ())}
            if bad & set(values):
                check.reasons.append(REASON_CONTRADICTED)
        elif (subjects or values) and not abstaining and not check.unknown:
            missing = [t for t in subjects + values
                       if not _found(t, all_text, _is_path(t))]
            if missing:
                check.missing = missing
                check.reasons.append(REASON_UNSUPPORTED_UNCITED)
            else:
                check.reasons.append(REASON_UNCITED)
        report.sentences.append(check)

    report.annotated = _annotate(answer, report.sentences)
    return report


def _check_cited(check: SentenceCheck, evidence, subjects: list,
                 values: list) -> None:
    """Run the subject, value and tier checks for a sentence that cites."""
    items = [evidence.get(i) for i in check.cited]
    haystack = "\n".join(it.haystack() for it in items)
    missing_subjects = [t for t in subjects if not _found(t, haystack, _is_path(t))]
    missing_values = [t for t in values if not _found(t, haystack, False)]
    if missing_subjects:
        check.reasons.append(REASON_SUBJECT)
    if missing_values:
        check.reasons.append(REASON_VALUE)
    check.missing = missing_subjects + missing_values

    tiers = {it.tier for it in items}
    if items and all(it.kind == KIND_NOTE for it in items):
        if tiers <= HISTORY_ONLY_TIERS and not _HISTORICAL.search(check.text):
            check.reasons.append(REASON_TIER)
        elif tiers == {TIER_BRANCH_LOCAL} and not _names_branch(check.text, items):
            check.reasons.append(REASON_BRANCH_LOCAL)


def _names_branch(sentence: str, items: list) -> bool:
    """Whether a sentence qualifies its claim by the branch it holds on."""
    low = sentence.lower()
    if "on branch" in low or "unmerged" in low:
        return True
    return any(it.branch and it.branch.lower() in low for it in items)


def _annotate(answer: str, sentences: list[SentenceCheck]) -> str:
    """Insert :data:`UNSUPPORTED_MARK` at the end of every marked sentence,
    before its closing punctuation, so the mark stays part of the sentence it
    qualifies: ``... [D3] [unsupported].``"""
    out = answer
    for s in sorted(sentences, key=lambda s: s.end, reverse=True):
        if not s.marked:
            continue
        end = s.start + len(answer[s.start:s.end].rstrip())
        if end > s.start and answer[end - 1] in ".!?":
            end -= 1
        out = out[:end].rstrip() + " " + UNSUPPORTED_MARK + out[end:]
    return out

