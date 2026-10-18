"""Deterministic graders for the consult evaluation set.

A grader reads a ``consult`` response (the dict the MCP tool returns) and an
expectation, and reports which expectations held. The graders are pure and
model-independent: they read the structured response fields first and the
prose second, so the same grader works before and after the response-shape
changes the evidence-grounding work introduces. A missing field counts as a
failure only for an expectation that actually needs it.

The grader vocabulary (see ``validate_case``):

- ``outcome``            — ``answer`` | ``abstain`` | ``nonexistent``
- ``must_include``       — list of case-insensitive regexes; all must match
- ``must_not_include``   — list of case-insensitive regexes; none may match
- ``must_include_any``   — list of groups; at least one regex per group matches
- ``must_cite_any``      — list of files; at least one must be cited
- ``must_not_cite``      — list of ``{file, lines:[lo,hi], with?}`` forbidden
                           citations, optionally limited to sentences containing
                           the ``with`` token
- ``must_flag_stale``    — symbols that must appear in an evidence item's
                           ``named_symbols.absent`` list
- ``basis_in``           — allowed values of ``answer_basis``
- ``max_citation_warnings`` — ceiling on ``len(citation_warnings)``
"""

import os
import re
from typing import Optional

KNOWN_OUTCOMES = ("answer", "abstain", "nonexistent")

# Keys a case ``expect`` (or milestone override) may carry.
EXPECT_KEYS = frozenset({
    "outcome", "must_include", "must_not_include", "must_include_any",
    "must_cite_any", "must_not_cite", "must_flag_stale", "basis_in",
    "max_citation_warnings",
})

# Matches an inline ``path:line`` citation in answer prose, e.g.
# ``claude-code-job.md:330`` or ``flowtree/runtime/docs/claude-code-job.md:330``.
_INLINE_CITATION = re.compile(
    r"([\w./\-]+\.(?:md|sh|py|yaml|yml|java|html)):(\d+)"
)


class GradeResult:
    """The outcome of grading one response against one expectation."""

    def __init__(self) -> None:
        self.checks: list[tuple[str, bool, str]] = []

    def record(self, name: str, ok: bool, detail: str = "") -> None:
        self.checks.append((name, bool(ok), detail))

    @property
    def passed(self) -> bool:
        return all(ok for _, ok, _ in self.checks)

    @property
    def failures(self) -> list[str]:
        return [
            f"{name}: {detail}" if detail else name
            for name, ok, detail in self.checks
            if not ok
        ]


def prose(response: dict) -> str:
    """The human-facing text of a response — the answer, or the abstention
    note when there is no answer."""
    answer = response.get("answer")
    if isinstance(answer, str) and answer.strip():
        return answer
    note = response.get("note")
    return note if isinstance(note, str) else ""


def response_outcome(response: dict) -> str:
    """Classify a response as ``answer``, ``abstain`` or ``nonexistent``.

    ``nonexistent`` is a distinguished abstention the index-backed steps add;
    it is recognised through either a ``nonexistent`` boolean or an
    ``abstention_kind == "nonexistent"`` marker so a grader can assert it
    before the final field shape is settled.
    """
    if response.get("nonexistent") is True:
        return "nonexistent"
    if response.get("abstention_kind") == "nonexistent":
        return "nonexistent"
    if response.get("abstained") is True:
        return "abstain"
    answer = response.get("answer")
    if isinstance(answer, str) and answer.strip():
        return "answer"
    return "abstain"


def _file_matches(wanted: str, cited: str) -> bool:
    """Whether a citation path ``cited`` refers to the file ``wanted``.

    Lenient about relative-path depth: a bare basename citation
    (``claude-code-job.md``) matches a repo-relative ``wanted`` and vice
    versa. The leniency is deliberately confined to that case — when *both*
    sides are qualified paths they must agree on their relative path, so a
    citation to ``other/CLAUDE.md`` does not satisfy an expectation for
    ``.github/CLAUDE.md`` merely because the basenames coincide.
    """
    if wanted == cited:
        return True
    if cited.endswith("/" + wanted) or wanted.endswith("/" + cited):
        return True
    # Only fall back to basename equality when at least one side is a bare
    # basename (no directory component); two qualified paths must match above.
    one_is_bare = not os.path.dirname(wanted) or not os.path.dirname(cited)
    return one_is_bare and os.path.basename(wanted) == os.path.basename(cited)


def citation_paths(response: dict) -> list[str]:
    """Every file a response cites: its ``sources``, any ``evidence`` item's
    file, and every inline ``path:line`` in the prose."""
    paths: list[str] = []
    for src in response.get("sources", []) or []:
        if isinstance(src, str):
            paths.append(src)
    for item in response.get("evidence", []) or []:
        f = item.get("file") if isinstance(item, dict) else None
        if isinstance(f, str):
            paths.append(f)
    for path, _line in _INLINE_CITATION.findall(prose(response)):
        paths.append(path)
    return paths


def named_absent_symbols(response: dict):
    """The union of every evidence item's ``named_symbols.absent`` entries,
    or ``None`` when the response carries no ``evidence`` field at all.

    Returning ``None`` (rather than an empty set) lets a grader distinguish
    "nothing was flagged" from "this response shape cannot answer the
    question yet", which a ``must_flag_stale`` expectation needs."""
    if "evidence" not in response:
        return None
    absent: set[str] = set()
    for item in response.get("evidence") or []:
        if not isinstance(item, dict):
            continue
        names = item.get("named_symbols") or {}
        for sym in names.get("absent", []) or []:
            if isinstance(sym, str):
                absent.add(sym)
    return absent


def _split_sentences(text: str) -> list[str]:
    """Split prose into rough sentences for the ``with``-qualified
    forbidden-citation check. Splits on sentence punctuation and newlines;
    the citation and its subject must land in the same fragment."""
    return [s for s in re.split(r"(?<=[.!?])\s+|\n+", text) if s.strip()]


def _violates_must_not_cite(response: dict, spec: dict) -> Optional[str]:
    """Return a description of a forbidden citation if the response makes one,
    else ``None``.

    A forbidden citation is one to ``spec['file']`` at a line within
    ``spec['lines']``. When ``spec`` carries ``with``, only a citation in a
    sentence that also mentions that token counts — this is how E2 is graded:
    citing ``claude-code-job.md`` in its 320–345 range is forbidden *only* for
    a sentence about ``validate-agent-commit``.
    """
    wanted = spec["file"]
    lo, hi = spec["lines"]
    token = spec.get("with")
    text = prose(response)

    sentences = _split_sentences(text) if token else [text]
    for sentence in sentences:
        if token and token.lower() not in sentence.lower():
            continue
        for path, line in _INLINE_CITATION.findall(sentence):
            if _file_matches(wanted, path) and lo <= int(line) <= hi:
                return f"cited {path}:{line} (forbidden range {lo}-{hi})"

    # Structured evidence ranges carry no sentence context, so they can only be
    # judged against an unqualified spec.
    if not token:
        for item in response.get("evidence", []) or []:
            if not isinstance(item, dict):
                continue
            if not _file_matches(wanted, str(item.get("file", ""))):
                continue
            rng = item.get("range")
            if isinstance(rng, (list, tuple)) and len(rng) == 2:
                if int(rng[0]) <= hi and int(rng[1]) >= lo:
                    return f"evidence cites {item.get('file')} range {rng}"
    return None


def grade(response: dict, expect: dict) -> GradeResult:
    """Grade one response against one resolved expectation."""
    result = GradeResult()
    text = prose(response)

    if "outcome" in expect:
        actual = response_outcome(response)
        result.record(
            "outcome", actual == expect["outcome"],
            f"got {actual!r}, expected {expect['outcome']!r}",
        )

    for pat in expect.get("must_include", []):
        ok = re.search(pat, text, re.IGNORECASE) is not None
        result.record("must_include", ok, f"missing {pat!r}")

    for pat in expect.get("must_not_include", []):
        ok = re.search(pat, text, re.IGNORECASE) is None
        result.record("must_not_include", ok, f"present but forbidden: {pat!r}")

    for group in expect.get("must_include_any", []):
        ok = any(re.search(p, text, re.IGNORECASE) for p in group)
        result.record("must_include_any", ok, f"none of {group!r} present")

    if "must_cite_any" in expect:
        cited = citation_paths(response)
        ok = any(
            _file_matches(f, c) for f in expect["must_cite_any"] for c in cited
        )
        result.record(
            "must_cite_any", ok,
            f"none of {expect['must_cite_any']!r} cited (cited {cited!r})",
        )

    for spec in expect.get("must_not_cite", []):
        violation = _violates_must_not_cite(response, spec)
        result.record("must_not_cite", violation is None, violation or "")

    if "must_flag_stale" in expect:
        absent = named_absent_symbols(response)
        if absent is None:
            result.record(
                "must_flag_stale", False,
                "response carries no evidence field to flag stale symbols",
            )
        else:
            missing = [s for s in expect["must_flag_stale"] if s not in absent]
            result.record(
                "must_flag_stale", not missing,
                f"not flagged absent: {missing!r}",
            )

    if "basis_in" in expect:
        basis = response.get("answer_basis")
        result.record(
            "basis_in", basis in expect["basis_in"],
            f"answer_basis {basis!r} not in {expect['basis_in']!r}",
        )

    if "max_citation_warnings" in expect:
        n = len(response.get("citation_warnings", []) or [])
        result.record(
            "max_citation_warnings", n <= expect["max_citation_warnings"],
            f"{n} warnings exceed ceiling {expect['max_citation_warnings']}",
        )

    return result


def resolve_expect(case: dict, milestone: str = "baseline") -> dict:
    """The expectation for a case at a milestone.

    Starts from the case's baseline ``expect`` and, when ``milestone`` names an
    entry in the case's ``milestones`` map, overrides those keys. An override
    replaces a key outright (it is not merged element-wise), so a milestone
    states the whole expectation for each key it changes.
    """
    resolved = dict(case.get("expect", {}))
    if milestone != "baseline":
        resolved.update(case.get("milestones", {}).get(milestone, {}))
    return resolved


def validate_case(case: dict) -> list[str]:
    """Return a list of schema problems with a case, empty when it is valid."""
    errors: list[str] = []
    cid = case.get("id", "<no id>")

    for key in ("id", "question", "expect"):
        if key not in case:
            errors.append(f"{cid}: missing required key {key!r}")

    def _check_expect(label: str, expect: dict) -> None:
        unknown = set(expect) - EXPECT_KEYS
        if unknown:
            errors.append(f"{cid}: {label} has unknown keys {sorted(unknown)}")
        outcome = expect.get("outcome")
        if outcome is not None and outcome not in KNOWN_OUTCOMES:
            errors.append(
                f"{cid}: {label} outcome {outcome!r} not in {KNOWN_OUTCOMES}")
        for spec in expect.get("must_not_cite", []):
            if "file" not in spec or "lines" not in spec:
                errors.append(f"{cid}: {label} must_not_cite needs file+lines")
            elif (not isinstance(spec["lines"], list)
                  or len(spec["lines"]) != 2):
                errors.append(
                    f"{cid}: {label} must_not_cite 'lines' must be [lo, hi]")

    expect = case.get("expect")
    if "expect" in case and not isinstance(expect, dict):
        errors.append(f"{cid}: 'expect' must be an object")
    elif isinstance(expect, dict):
        # An empty expectation records no checks, and GradeResult.passed is
        # vacuously true over no checks — the case would pass any response.
        if not expect:
            errors.append(f"{cid}: 'expect' must state at least one check")
        _check_expect("expect", expect)
    for name, override in (case.get("milestones") or {}).items():
        if isinstance(override, dict):
            _check_expect(f"milestones.{name}", override)

    if "answerable" in case and not isinstance(case["answerable"], bool):
        errors.append(f"{cid}: 'answerable' must be a boolean")

    return errors
