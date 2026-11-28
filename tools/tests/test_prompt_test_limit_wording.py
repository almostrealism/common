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

# Each phrasing asserts the superseded single-test-per-invocation limit, or the
# behaviour that followed from it (a bare class selector being rejected rather
# than accepted). The patterns are case-insensitive so a capitalised or
# reflowed restatement is caught too.
_STALE_PATTERNS = [
    re.compile(r"one[\s-]+test[\s-]+per[\s-]+invocation", re.IGNORECASE),
    re.compile(r"\bentry per invocation\b", re.IGNORECASE),
    re.compile(r"exactly one (?:node id|test) per invocation", re.IGNORECASE),
    re.compile(r"bare class selector is rejected", re.IGNORECASE),
]


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
                for lineno, line in enumerate(handle, start=1):
                    for pattern in _STALE_PATTERNS:
                        if pattern.search(line):
                            offenders.append(
                                "{}:{}: {}".format(relative, lineno, line.strip()))
        self.assertEqual(
            [], offenders,
            "Stale 'one test per invocation' wording found in CI prompts. The "
            "current limit is at most 5 test classes and 40 named methods (or "
            "5 files and 40 pytest node ids) per invocation:\n"
            + "\n".join(offenders))


if __name__ == "__main__":
    unittest.main()
