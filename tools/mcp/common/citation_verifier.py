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
- **polarity** — a claim-level check for sentences that carry no identifier,
  path or number to match. A clause that says what does *not* happen, or that
  something happens *only*, *always* or *never* — or that there is *one X per
  Y* — is a strong claim the token checks cannot see. It must be echoed by an
  evidence passage that itself makes a claim of that kind about the same
  things (see :func:`polarity_unsupported`). "It does not deploy automatically
  after every merge — only when explicitly triggered", cited to a passage
  saying the deploy is triggered by every green master run, fails here.

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
REASON_POLARITY = "polarity"

# Reasons whose sentence's citations do not count towards the answer basis.
_NON_SUPPORTING = frozenset({REASON_TIER, REASON_BRANCH_LOCAL, REASON_CONTRADICTED})

# Reasons that put the inline mark on the sentence.
MARKED_REASONS = frozenset({
    REASON_SUBJECT, REASON_VALUE, REASON_TIER, REASON_BRANCH_LOCAL,
    REASON_UNKNOWN, REASON_UNSUPPORTED_UNCITED, REASON_CONTRADICTED,
    REASON_POLARITY,
})

_EXTENSIONS = r"(?:md|sh|py|java|ya?ml|json|html|txt|xml|toml|properties|kt|js|ts)"

# A backticked span.
_BACKTICK = re.compile(r"`([^`\n]+)`")
# A double-quoted span (straight or curly quotes).
_QUOTED = re.compile(r"[\"“]([^\"”\n]{2,80})[\"”]")
# A path: something with a slash, or a file name with a known extension.
_PATH = re.compile(
    r"(?<![\w/.\-])((?:\.?[\w\-]+/)+[\w.\-*<>{}]*|[\w.\-]+\." + _EXTENSIONS
    + r")(?:\b|(?<=[/\-*>}]))")
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

# Wording that makes a clause a polarity or exclusivity claim.
_POLARITY_CLAIM = re.compile(
    r"\b(?:not|never|cannot|no longer|only|solely|exclusively|always)\b|n't\b",
    re.IGNORECASE)
# Wording that lets an evidence passage echo such a claim: the claim words,
# plus conditions, exceptions and refusals, which state the same limits in
# other words ("starts a round unless more than 7 are open" echoes "starts
# only while no more than 7 are open").
_POLARITY_ECHO = re.compile(
    r"\b(?:not|never|cannot|none|nothing|no|only|solely|exclusively|always|"
    r"unless|except|without|neither|nor|instead|rather than|refuses?d?|"
    r"rejects?|rejected|blocks?|blocked|forbids?|forbidden|prohibits?|"
    r"fails?|must)\b|n't\b", re.IGNORECASE)
# A cardinality claim: "one design-review session per changed class".
_ONE_PER = re.compile(r"\bone\s+((?:[\w\-]+\s+){0,3}?[\w\-]+)\s+per\s+"
                      r"((?:[\w\-]+\s*){1,6})", re.IGNORECASE)
# A clause about the evidence itself — what it lacks ("not present in the
# master snapshot", "does not reflect master"), what little it holds ("the
# only evidence is a branch-local note"), or the standing the tier labels gave
# it ("branch-local and not yet merged") — reports the search, like an
# abstention; it is not a claim about the code to echo.
_EVIDENCE_ABSENCE = re.compile(
    r"\b(?:not|no longer) (?:present|appears?|exists?|included|described|"
    r"mentioned|shown|found|reflected|stated|recorded) in (?:the |any )?(?:[\w\-]+ )?(?:snapshot|"
    r"documentation|docs|evidence|corpus|notes?)\b|"
    r"\bdoes not reflect\b|\bbranch-local\b|\bnot (?:yet )?merged\b|"
    r"\bthe question\b|"
    r"\bonly (?:evidence|mention|reference|source|note|item)s?\b",
    re.IGNORECASE)
# Where a sentence divides into separately checkable clauses.
_CLAUSE_BREAK = re.compile(
    r"\s*(?:[;:()\u2014\u2013]|,\s|\s-\s|\bbut\b|\bwhile\b|\bwhereas\b|"
    r"\bhowever\b)\s*", re.IGNORECASE)
_WORD = re.compile(r"[a-z][a-z0-9]+")
# Content stems a passage must share with a long polarity clause to echo it.
_ECHO_STEMS_MAX = 4
# A line that starts a block of its own rather than continuing wrapped prose.
_BLOCK_START = re.compile(r"(?:[|#>*+\-]|\d+[.)])\s")
_STOPWORDS = frozenset("""
    a an the and or of to in on at by for from with into onto over under
    after before about as is are was were be been being it its this that
    these those there their them they he she we you your our his her which
    who whom whose what when where why how than then so such any each all
    some more most less other another same can could may might must should
    will would shall do does did done has have had having if also just very
    via per both either one two three first case way thing things time
    not never cannot none nothing only solely exclusively always longer
""".split())


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
                                            REASON_UNSUPPORTED_UNCITED,
                                            REASON_POLARITY):
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
        if not abstaining and not check.unknown:
            # An uncited polarity claim is held to the whole evidence list.
            scope = ([evidence.get(i) for i in check.cited] if check.cited
                     else list(evidence.items()))
            unechoed = polarity_unsupported(sentence, [it.text for it in scope])
            if unechoed:
                check.reasons.append(REASON_POLARITY)
                check.missing = check.missing + unechoed
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


def _stems(text: str) -> list[str]:
    """Content-word stems of ``text``, in order: function words and the
    polarity words themselves dropped, the rest cut to five letters so
    "deploys", "deployment" and "deploy" agree."""
    out = []
    for word in _WORD.findall(text.lower()):
        if word in _STOPWORDS:
            continue
        if len(word) >= 5:
            word = word[:5]
        elif len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
            word = word[:-1]
        out.append(word)
    return out


def _passages(texts: list[str]) -> list[str]:
    """The evidence split into sentences.

    Wrapped prose is rejoined first — a script header or a markdown
    paragraph breaks its sentences across lines, and a clause checked against
    half a sentence would be wrongly unechoed — while a table row, list item
    or heading stays a block of its own.
    """
    out = []
    for text in texts:
        blocks: list[str] = []
        joinable = False
        for line in (text or "").split("\n"):
            stripped = line.strip()
            if not stripped:
                joinable = False
                continue
            if joinable and not _BLOCK_START.match(stripped):
                blocks[-1] += " " + stripped
            else:
                blocks.append(stripped)
            joinable = not stripped.startswith(("|", "#"))
        for block in blocks:
            out.extend(p for p in re.split(r"(?<=[.!?])\s+", block) if p.strip())
    return out


def _one_per_echoed(claim: re.Match, passages: list[str]) -> bool:
    """Whether some passage states the cardinality ``claim`` states: one of
    the same thing (the last content word before "per") per a unit described
    in shared terms (a content word in common after "per")."""
    counted, unit = _stems(claim.group(1)), set(_stems(claim.group(2)))
    if not counted or not unit:
        return True
    for passage in passages:
        for m in _ONE_PER.finditer(passage):
            if counted[-1] in _stems(m.group(1)) and unit & set(_stems(m.group(2))):
                return True
    return False


def _polarity_echoed(clause: str, passages: list[str]) -> bool:
    """Whether some passage makes a polarity claim about the clause's
    subject matter: it carries polarity wording and shares at least two of
    the clause's content stems, or half of them for a longer clause, up to
    :data:`_ECHO_STEMS_MAX` — a long clause restating a passage in its own
    words shares its key terms, not all of them."""
    wanted = set(_stems(clause))
    if not wanted:
        return True
    need = min(len(wanted),
               max(2, min(_ECHO_STEMS_MAX, (len(wanted) + 1) // 2)))
    for passage in passages:
        if _POLARITY_ECHO.search(passage) and \
                len(wanted & set(_stems(passage))) >= need:
            return True
    return False


def polarity_unsupported(sentence: str, texts: list[str]) -> list[str]:
    """The polarity and exclusivity clauses of ``sentence`` that no passage
    of ``texts`` echoes.

    This is the claim-level check behind :data:`REASON_POLARITY`. A clause is
    checked when it says what does not happen or that something happens
    only, always or never, or when it states a cardinality ("one X per Y").
    A polarity clause is echoed by a passage that also carries polarity
    wording (:data:`_POLARITY_ECHO`) and shares enough of its content words;
    a cardinality is echoed only by a passage stating one of the same thing
    per the same unit, so "one class per job" is not echoed by "one session
    per changed class". Like the token checks this errs towards flagging: a
    true limit the evidence states in other words is flagged, a wrong one is
    not passed.

    Returns:
        The unechoed clauses, as written; empty when every one is echoed.
    """
    # Quoted and backticked literals are checked verbatim by the value check;
    # a polarity word inside one ("BLOCKED: the change set only edits ...")
    # is quoted material, not the sentence's claim. A backticked name stays.
    body = _BACKTICK.sub(
        lambda m: m.group(1) if re.fullmatch(r"[\w.$#/\-]+", m.group(1)) else " ",
        MARKER.sub(" ", sentence))
    body = _QUOTED.sub(" ", _FREE_TEXT_LOCATION.sub(" ", body))
    if _EVIDENCE_ABSENCE.search(body):
        return []
    passages = _passages(texts)
    failed = []
    for clause in _CLAUSE_BREAK.split(body):
        clause = clause.strip(" .!?*")
        if not clause or _ABSTENTION.search(clause):
            continue
        cardinality = _ONE_PER.search(clause)
        if cardinality:
            if not _one_per_echoed(cardinality, passages):
                failed.append(clause)
        elif _POLARITY_CLAIM.search(clause) and \
                not _polarity_echoed(clause, passages):
            failed.append(clause)
    return failed


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

