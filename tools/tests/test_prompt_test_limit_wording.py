"""Guard: CI agent prompts state the current test-execution limit.

The root ``CLAUDE.md`` "Test Execution Limits" rule is a bounded selection per
invocation -- at most 5 test classes and 40 named methods for Java, and a
bounded list of explicit ``.py`` files or ``file.py::test_name`` node ids
(at most 5 files and 40 node ids) for pytest. An earlier revision of the rule
capped every invocation at a single test, and that older "one test per
invocation" wording survived in some ``tools/ci/prompts`` builders after the
rule was relaxed, telling coding agents something the limiter
(``tools/mcp/manager/execution_limits.py``) no longer enforces.

This test fails if any of that stale wording reappears in a prompt. It does not
object to a builder asking an agent to reproduce a specific failure "one at a
time" -- that is a diagnostic methodology, not a claim about the per-invocation
limit, and the JVM-crash builder legitimately isolates one candidate method per
invocation for JMX monitoring. It only forbids phrasings that assert the limit
itself is a single test, or that a bare class selector is rejected.
"""

import os
import re
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_PROMPT_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "prompts")

# Word separator: whitespace (including a line wrap), a hyphen, or the "#"
# comment leader a shell builder repeats on each wrapped comment line.
_SEP = r"(?:[\s#-])+"

# Each phrasing asserts the superseded single-test-per-invocation limit, or the
# behaviour that followed from it (a bare class selector being rejected rather
# than accepted). The patterns are matched against the whole file text and are
# case-insensitive, so a capitalised or reflowed restatement is caught too.
_STALE_PATTERNS = [
    re.compile(_SEP.join(["one", "test", "per", "invocation"]), re.IGNORECASE),
    re.compile(r"\b" + _SEP.join(["entry", "per", "invocation"]) + r"\b", re.IGNORECASE),
    re.compile(_SEP.join(["exactly", "one",
                          "(?:explicit" + _SEP + ")?(?:node" + _SEP + "id|test)",
                          "per", "invocation"]),
               re.IGNORECASE),
    re.compile(_SEP.join(["bare", "class", "selector", "is", "rejected"]), re.IGNORECASE),
]


def _stale_wording(text):
    """Every stale phrasing in ``text``, as (line number, matched phrase) for
    the line the match starts on, with a wrapped match collapsed to one line."""
    found = []
    for pattern in _STALE_PATTERNS:
        for match in pattern.finditer(text):
            lineno = text.count("\n", 0, match.start()) + 1
            found.append((lineno, " ".join(match.group(0).split())))
    return sorted(found)


def _prompt_files():
    """Every regular file under ``tools/ci/prompts`` (prompt templates and the
    shell builders that render them), as (relative path, absolute path)."""
    for dirpath, _dirnames, filenames in os.walk(_PROMPT_DIR):
        for name in sorted(filenames):
            absolute = os.path.join(dirpath, name)
            yield os.path.relpath(absolute, _REPO_ROOT), absolute


class TestPromptTestLimitWording(unittest.TestCase):
    """The prompts must not reintroduce the superseded one-test-per-invocation
    wording for the test-execution limit."""

    def test_prompt_dir_is_present(self):
        self.assertTrue(
            os.path.isdir(_PROMPT_DIR),
            "tools/ci/prompts must exist for this guard to be meaningful")
        self.assertTrue(
            list(_prompt_files()), "expected prompt files under tools/ci/prompts")

    def test_no_stale_one_test_per_invocation_wording(self):
        offenders = []
        for relative, absolute in _prompt_files():
            with open(absolute, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            for lineno, phrase in _stale_wording(text):
                offenders.append("{}:{}: {}".format(relative, lineno, phrase))
        self.assertEqual(
            [], offenders,
            "Stale 'one test per invocation' wording found in CI prompts. The "
            "current limit is at most 5 test classes and 40 named methods (or "
            "5 files and 40 pytest node ids) per invocation:\n"
            + "\n".join(offenders))

    def test_wrapped_phrase_is_detected(self):
        # A phrase split across a line wrap, including a wrapped shell
        # comment, is still stale wording and must be reported on the line
        # it starts on.
        text = "intro\n# the one test per\n# invocation rule\n"
        self.assertEqual([(2, "one test per # invocation")], _stale_wording(text))
        self.assertEqual(
            [(1, "bare class selector is rejected")],
            _stale_wording("a bare class\nselector is rejected here"))

    def test_explicit_node_id_phrase_is_detected(self):
        # The most direct stale restatement -- "exactly one explicit node id
        # per invocation" -- must be caught even with "explicit" between "one"
        # and "node id", and whether or not "explicit" is present.
        self.assertEqual(
            [(1, "exactly one explicit node id per invocation")],
            _stale_wording("exactly one explicit node id per invocation"))
        self.assertEqual(
            [(1, "exactly one node id per invocation")],
            _stale_wording("exactly one node id per invocation"))

    def test_current_wording_is_not_flagged(self):
        # The current limit's wording, and a diagnostic "one at a time", are
        # not stale.
        text = ("Run at most 5 test classes and 40 named methods per\n"
                "invocation; reproduce the failure one at a time.\n"
                "A bare class selector is accepted.\n")
        self.assertEqual([], _stale_wording(text))


if __name__ == "__main__":
    unittest.main()
