"""
Prompt linting for the AR Manager MCP server.

Scans a submitted prompt for instructions that would have an agent split its
work across several commits. The project's agents commit once; a prompt that
tells one to do otherwise produces a branch nobody asked for.

Kept apart from ``server`` so the pattern set and its exemptions can be read
as one piece, and re-exported there because callers and tests reach it by that
name.
"""

import re


# Verbs that make a commit reference a READ rather than an instruction to
# produce one. "diff commit 123 against its parent" names an existing commit;
# "Commit 1: add the parser" plans a new one. The bare commit-number pattern
# below cannot tell them apart on its own, and rejecting the read case sent
# operators hunting for the allow_commit_language escape hatch.
# Kept deliberately narrow. Generic words that merely often appear near a
# commit reference — "before", "after", "since", "check" — would exempt
# instructions too ("commit this before running the tests"), turning a
# narrowed heuristic into a disabled one. Only verbs that make the commit the
# OBJECT OF AN INSPECTION belong here.
_COMMIT_READ_CONTEXT = re.compile(
    r"\b(?:diff|compare|revert|reverting|inspect|examine|review|reviewing|"
    r"cherry-?pick|analyse|analyze|look\s+at|refer\s+to|based\s+on)\b",
    re.IGNORECASE)

# Each entry is (pattern, reason, exemption). A line matching `pattern` is a
# violation unless `exemption` also matches it — which is how a read-only
# reference to an existing commit is distinguished from an instruction to
# create commits. Most patterns need no exemption because their wording is
# already imperative.
_COMMIT_SEQUENCING_PATTERNS = [
    (re.compile(r"\bcommit\s+\d+\b", re.IGNORECASE),
     "commit-number phrase (e.g. \"Commit 1\", \"commit 2\")",
     _COMMIT_READ_CONTEXT),
    (re.compile(r"\bfirst\s+commit\b", re.IGNORECASE),
     '"first commit" phrase', None),
    (re.compile(r"\bnext\s+commit\b", re.IGNORECASE),
     '"next commit" phrase', None),
    (re.compile(r"\bfinal\s+commit\b", re.IGNORECASE),
     '"final commit" phrase', None),
    (re.compile(r"\bas\s+(?:its\s+own|separate|individual)\s+commits?\b", re.IGNORECASE),
     '"as separate/individual commits" phrase', None),
    (re.compile(r"\b(?:in|across|over)\s+\d+\s+commits?\b", re.IGNORECASE),
     '"in/across/over N commits" phrase', None),
    (re.compile(
        r"\b(?:your|the)\s+commit\s+message\s+(?:should|will|must)\b", re.IGNORECASE),
     '"commit message should/will/must" phrase', None),
    (re.compile(
        r"\bcommit\s+(?:this|that|each|the)\s+(?:as|with|before)\b", re.IGNORECASE),
     '"commit this/that/each/the as/with/before" phrase', None),
    (re.compile(
        r"\bcommit\s+(?:between|after|before)\s+(?:each|every)\b", re.IGNORECASE),
     '"commit between/after/before each/every" phrase', None),
]

# Minimum prompt length below which the linter is skipped (false-positive
# ratio is too high on very short strings and they almost never contain the
# multi-word phrases we are looking for).
_COMMIT_LINTER_MIN_LEN = 50


def _lint_prompt_for_commit_sequencing(prompt: str) -> list:
    """Scan ``prompt`` for forbidden commit-sequencing phrases.

    Returns a list of ``(line_number, snippet, reason)`` tuples — one per
    matched line (first matching pattern wins per line).  Returns an empty
    list when the prompt is short (< 50 chars) or contains no matches.

    This function is pure (no I/O) and intentionally best-effort: it may
    produce false positives for prompts that quote commit messages or use
    the word "commit" in an unrelated context.  Callers that need to bypass
    the check should pass ``allow_commit_language=True`` to
    ``workstream_submit_task``.
    """
    if len(prompt) < _COMMIT_LINTER_MIN_LEN:
        return []
    violations = []
    for lineno, line in enumerate(prompt.splitlines(), 1):
        for pattern, reason, exemption in _COMMIT_SEQUENCING_PATTERNS:
            if pattern.search(line):
                if exemption is not None and exemption.search(line):
                    continue
                snippet = line.strip()[:120]
                violations.append((lineno, snippet, reason))
                break  # one violation entry per line, first pattern wins
    return violations
