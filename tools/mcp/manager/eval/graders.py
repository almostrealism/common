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
- ``min_citation_warnings`` — floor on ``len(citation_warnings)``: a case
                           replaying a known-wrong answer asserts it is flagged
- ``history_must_include`` — list of case-insensitive regexes that must match
                           the response's ``history`` field
- ``min_conflicts``      — floor on ``len(conflicts)``
- ``must_mark_unsupported`` — list of case-insensitive regexes; each must match
                           a sentence the citation verifier flagged (one marked
                           ``[unsupported]`` in the prose, or listed in
                           ``citation_warnings``)
"""

import os
import re
from typing import Optional

KNOWN_OUTCOMES = ("answer", "abstain", "nonexistent")

# Keys a case ``expect`` (or milestone override) may carry.
EXPECT_KEYS = frozenset({
    "outcome", "must_include", "must_not_include", "must_include_any",
    "must_cite_any", "must_not_cite", "must_flag_stale", "basis_in",
    "max_citation_warnings", "min_citation_warnings", "must_mark_unsupported",
    "history_must_include", "min_conflicts",
})

# Expectation keys whose value is a list of case-insensitive regexes.
_REGEX_LIST_KEYS = ("must_include", "must_not_include", "must_mark_unsupported",
                    "history_must_include")

# Expectation keys whose value is a non-negative integer bound.
_COUNT_KEYS = ("max_citation_warnings", "min_citation_warnings", "min_conflicts")

# The mark the citation verifier puts on a sentence it could not support.
UNSUPPORTED_MARK = "[unsupported]"

# Expectation keys whose value is a list of plain strings (files, symbols,
# ``answer_basis`` values), compared literally rather than as regexes.
_STRING_LIST_KEYS = ("must_cite_any", "must_flag_stale", "basis_in")


def _is_string_list(value) -> bool:
    """Whether ``value`` is a list whose every element is a string. A bare
    string is rejected: iterating it would yield single characters."""
    return isinstance(value, list) and all(isinstance(v, str) for v in value)


def _is_int(value) -> bool:
    """Whether ``value`` is an integer, excluding ``bool`` (a JSON ``true``
    would otherwise pass as ``1``)."""
    return isinstance(value, int) and not isinstance(value, bool)

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


def is_tool_failure(response: dict) -> bool:
    """Whether a response is a failed tool call (``ok`` explicitly ``False``).

    ``consult`` returns ``{"ok": False, "error": ...}`` when the backend or
    documentation corpus is unavailable; such a response carries no answer. It
    must never be graded as a pass, nor counted as a (false) abstention, since
    it reflects an environment failure rather than any behaviour of the tool.
    """
    return isinstance(response, dict) and response.get("ok") is False


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

    Structured ``evidence`` items carry no sentence context, so they cannot be
    tied to the claim they support. They are judged conservatively: a
    forbidden range in the evidence list is a violation whenever the prose
    mentions the ``with`` token at all (always, for an unqualified spec). A
    response could otherwise move the misattributed citation out of the
    sentence and into the evidence list and pass.
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

    if token and token.lower() not in text.lower():
        return None
    for item in response.get("evidence", []) or []:
        if not isinstance(item, dict):
            continue
        if not _file_matches(wanted, str(item.get("file", ""))):
            continue
        rng = item.get("range")
        if not isinstance(rng, (list, tuple)) or len(rng) != 2:
            continue
        try:
            start, end = int(rng[0]), int(rng[1])
        except (TypeError, ValueError):
            continue
        if start <= hi and end >= lo:
            return f"evidence cites {item.get('file')} range {rng}"
    return None


def grade(response: dict, expect: dict) -> GradeResult:
    """Grade one response against one resolved expectation.

    A failed tool response (``ok: False``) fails outright with a single ``ok``
    check, before any expectation is evaluated: it has no answer to grade, so
    letting the remaining negative checks pass vacuously would report a backend
    or corpus failure as a successful evaluation.
    """
    result = GradeResult()

    if is_tool_failure(response):
        result.record(
            "ok", False,
            str(response.get("error", "tool reported ok=False")),
        )
        return result

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

    if "min_citation_warnings" in expect:
        n = len(response.get("citation_warnings", []) or [])
        result.record(
            "min_citation_warnings", n >= expect["min_citation_warnings"],
            f"{n} warnings, fewer than {expect['min_citation_warnings']}",
        )

    history = response.get("history") if isinstance(response.get("history"), str) else ""
    for pat in expect.get("history_must_include", []):
        ok = re.search(pat, history, re.IGNORECASE) is not None
        result.record("history_must_include", ok, f"history lacks {pat!r}")

    if "min_conflicts" in expect:
        n = len(response.get("conflicts", []) or [])
        result.record("min_conflicts", n >= expect["min_conflicts"],
                      f"{n} conflicts reported, fewer than {expect['min_conflicts']}")

    flagged = flagged_sentences(response)
    for pat in expect.get("must_mark_unsupported", []):
        ok = any(re.search(pat, s, re.IGNORECASE) for s in flagged)
        result.record("must_mark_unsupported", ok, f"not flagged: {pat!r}")

    return result


def flagged_sentences(response: dict) -> list[str]:
    """Sentences the citation verifier flagged: those carrying the
    ``[unsupported]`` mark in the answer or ``history``, and every sentence
    named in ``citation_warnings``."""
    flagged: list[str] = []
    for field in ("answer", "history"):
        text = response.get(field)
        if not isinstance(text, str):
            continue
        sentences = _split_sentences(text)
        for i, sentence in enumerate(sentences):
            if UNSUPPORTED_MARK not in sentence:
                continue
            # A mark standing alone after a full stop qualifies the sentence
            # before it.
            if sentence.strip().strip(".") == UNSUPPORTED_MARK and i:
                sentence = sentences[i - 1] + " " + sentence
            flagged.append(sentence)
    for warning in response.get("citation_warnings", []) or []:
        if isinstance(warning, dict) and isinstance(warning.get("sentence"), str):
            flagged.append(warning["sentence"])
    return flagged


_STEP = re.compile(r"^step(\d+)$")


def resolve_expect(case: dict, milestone: str = "baseline") -> dict:
    """The expectation for a case at a milestone.

    Starts from the case's baseline ``expect`` and applies overrides. A
    ``stepN`` milestone is cumulative: every ``stepM`` override with
    ``M <= N`` applies, in step order, so the bar a case reached at step 2
    still holds at step 12 without being restated. Any other milestone name
    applies only its own entry. An override replaces a key outright (it is
    not merged element-wise), so a milestone states the whole expectation for
    each key it changes.
    """
    resolved = dict(case.get("expect", {}))
    if milestone == "baseline":
        return resolved
    milestones = case.get("milestones", {})
    target = _STEP.match(milestone)
    if target is None:
        resolved.update(milestones.get(milestone, {}))
        return resolved
    steps = sorted((int(m.group(1)), name) for name in milestones
                   for m in [_STEP.match(name)] if m)
    for number, name in steps:
        if number <= int(target.group(1)):
            resolved.update(milestones[name])
    return resolved


def validate_case(case: dict) -> list[str]:
    """Return a list of schema problems with a case, empty when it is valid.

    A case that is not an object (``null``, a string, a list) is itself a
    schema error, reported as such rather than crashing: ``load_cases`` calls
    this on every element of the case file and promises a controlled
    ``ValueError`` for malformed data, so a non-mapping entry must not raise an
    ``AttributeError`` on the first ``.get`` below.

    Every expectation key is type-checked, element by element, against the
    shape ``grade`` iterates over — regex lists must hold compilable strings,
    ``must_include_any`` must hold non-empty lists of them, and so on — so a
    malformed value is a schema error here rather than a crash (or a silent
    misreading, such as a bare string iterated as characters) during grading.
    """
    if not isinstance(case, dict):
        return [f"<case {type(case).__name__}>: case must be an object"]
    errors: list[str] = []
    cid = case.get("id", "<no id>")

    for key in ("id", "question", "expect"):
        if key not in case:
            errors.append(f"{cid}: missing required key {key!r}")
    # A present ``id`` is the key responses are looked up by, so it must be a
    # non-empty string; an explicit ``null`` is not the same as a missing key.
    if "id" in case and (not isinstance(case["id"], str) or not case["id"]):
        errors.append(f"{case['id']!r}: 'id' must be a non-empty string")

    def _check_expect(label: str, expect: dict) -> None:
        unknown = set(expect) - EXPECT_KEYS
        if unknown:
            errors.append(f"{cid}: {label} has unknown keys {sorted(unknown)}")
        outcome = expect.get("outcome")
        if outcome is not None and outcome not in KNOWN_OUTCOMES:
            errors.append(
                f"{cid}: {label} outcome {outcome!r} not in {KNOWN_OUTCOMES}")
        for key in _REGEX_LIST_KEYS:
            if key in expect:
                _check_regex_list(f"{label} {key}", expect[key])
        if "must_include_any" in expect:
            groups = expect["must_include_any"]
            if not isinstance(groups, list):
                errors.append(f"{cid}: {label} must_include_any must be a list")
            else:
                for group in groups:
                    if not isinstance(group, list) or not group:
                        errors.append(
                            f"{cid}: {label} must_include_any entries must "
                            f"be non-empty lists")
                    else:
                        _check_regex_list(f"{label} must_include_any", group)
        for key in _STRING_LIST_KEYS:
            if key in expect and not _is_string_list(expect[key]):
                errors.append(f"{cid}: {label} {key} must be a list of strings")
        for key in _COUNT_KEYS:
            if key in expect:
                bound = expect[key]
                if not _is_int(bound) or bound < 0:
                    errors.append(
                        f"{cid}: {label} {key} must be a non-negative integer")
        specs = expect.get("must_not_cite", [])
        if not isinstance(specs, list):
            errors.append(f"{cid}: {label} must_not_cite must be a list")
            specs = []
        for spec in specs:
            if not isinstance(spec, dict):
                errors.append(
                    f"{cid}: {label} must_not_cite entries must be objects")
            elif "file" not in spec or "lines" not in spec:
                errors.append(f"{cid}: {label} must_not_cite needs file+lines")
            elif not isinstance(spec["file"], str) or not spec["file"]:
                errors.append(
                    f"{cid}: {label} must_not_cite 'file' must be a "
                    f"non-empty string")
            elif (not isinstance(spec["lines"], list)
                  or len(spec["lines"]) != 2
                  or not all(_is_int(n) for n in spec["lines"])
                  or spec["lines"][0] > spec["lines"][1]):
                errors.append(
                    f"{cid}: {label} must_not_cite 'lines' must be [lo, hi]")
            elif "with" in spec and (not isinstance(spec["with"], str)
                                     or not spec["with"]):
                errors.append(
                    f"{cid}: {label} must_not_cite 'with' must be a "
                    f"non-empty string")

    def _check_regex_list(label: str, patterns) -> None:
        if not _is_string_list(patterns):
            errors.append(f"{cid}: {label} must be a list of strings")
            return
        for pat in patterns:
            try:
                re.compile(pat)
            except re.error as exc:
                errors.append(f"{cid}: {label} has invalid regex {pat!r}: {exc}")

    expect = case.get("expect")
    if "expect" in case and not isinstance(expect, dict):
        errors.append(f"{cid}: 'expect' must be an object")
    elif isinstance(expect, dict):
        # An empty expectation records no checks, and GradeResult.passed is
        # vacuously true over no checks — the case would pass any response.
        if not expect:
            errors.append(f"{cid}: 'expect' must state at least one check")
        _check_expect("expect", expect)
    # Use a default of {} only when the key is absent; a present-but-falsy
    # non-dict (``[]``, ``""``, ``null``) must reach the isinstance check and
    # be reported, because resolve_expect does ``milestones.get(...)`` and
    # would otherwise raise AttributeError on a non-baseline milestone.
    milestones = case.get("milestones", {})
    if not isinstance(milestones, dict):
        errors.append(f"{cid}: 'milestones' must be an object")
        milestones = {}
    for name, override in milestones.items():
        if isinstance(override, dict):
            _check_expect(f"milestones.{name}", override)
        else:
            errors.append(f"{cid}: milestones.{name} must be an object")

    if "answerable" in case and not isinstance(case["answerable"], bool):
        errors.append(f"{cid}: 'answerable' must be a boolean")
    for key in ("scripted", "observed_response", "memories", "now"):
        if key in case and (not isinstance(case[key], str) or not case[key]):
            errors.append(f"{cid}: {key!r} must be a non-empty string")
    for key in ("branch_states", "caller"):
        if key in case and not isinstance(case[key], dict):
            errors.append(f"{cid}: {key!r} must be an object")

    return errors
