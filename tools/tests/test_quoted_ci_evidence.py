"""Guard: a CI log quoted into an auto-resolve prompt can still be submitted.

The test-execution-limits lint (``tools/mcp/manager/prompt_test_lint.py``,
mirrored by the controller's ``PromptTestInstructionLinter``) rejects every
prompt that names the CI shard-selection variables. Auto-resolve prompts quote
the failing CI output, and that output names the variables whenever the failing
test, an assertion message or a printed environment does -- most visibly when
the failing test is the lint's own template check, whose assertion message
quotes the offending template line. Such a prompt was refused at submission, so
the failure could never be fixed automatically.

The prompt builders now pass every quoted excerpt through
``redact_ci_shard_variables`` (``tools/ci/prompts/prompt-render.sh``). These
tests feed a failure log of exactly that shape through the builders and the
submit-side lint, and check that the instruction text around the excerpt is
still held to the rule. The controller-side half is
``QuotedCiEvidenceSubmissionTest`` in ``flowtree/runtime``.
"""

import os
import subprocess
import sys
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_PROMPT_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "prompts")
_FIXTURE = os.path.join(
    _REPO_ROOT, "tools", "ci", "testdata", "python-tests-shard-variable-failure.txt")

sys.path.insert(0, os.path.join(_REPO_ROOT, "tools", "mcp", "manager"))
from prompt_test_lint import lint_prompt_for_broad_test_instructions  # noqa: E402

_SHARD_VARIABLES = ("AR_TEST_GROUP", "AR_TEST_GROUPS")
_PLACEHOLDER = "<redacted CI shard variable>"
_SHARD_INSTRUCTION = "Then reproduce it with AR_TEST_GROUP=2 exported, as CI does."


def _read(path):
    """Returns a file's text, closing it -- these tests run under -W error."""
    with open(path, encoding="utf-8") as f:
        return f.read()


class QuotedShardVariableEvidenceTests(unittest.TestCase):
    """Builds auto-resolve prompts from a log naming the shard variables."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="quoted-ci-evidence-")
        self.output = os.path.join(self.tmp, "agent-prompt.txt")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _build(self, builder, inputs, extra_env=None):
        """Runs ``builder`` with ``inputs`` ahead of the output path, returning
        the prompt it wrote."""
        env = dict(os.environ, BRANCH="ci/example", COMMIT_SHA="0" * 40)
        env.update(extra_env or {})
        result = subprocess.run(
            ["bash", os.path.join(_PROMPT_DIR, builder)] + inputs[:1] + [self.output] + inputs[1:],
            env=env, capture_output=True, text=True, cwd=self.tmp)
        self.assertEqual(0, result.returncode, result.stderr)
        return _read(self.output)

    def _todos_file(self):
        """A branch-TODO section whose quoted line names a shard variable."""
        path = os.path.join(self.tmp, "branch-todos.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write("## New TODOs this branch adds\n\n"
                    "- tools/ci/example.sh:3: # TODO honour AR_TEST_GROUPS here\n")
        return path

    def test_fixture_log_is_rejected_when_quoted_verbatim(self):
        """The fixture really exercises the defect: verbatim, the lint refuses it."""
        violations = lint_prompt_for_broad_test_instructions(_read(_FIXTURE))
        self.assertTrue(violations)
        self.assertTrue(all("reference" in reason for _, _, reason in violations))

    def test_python_failure_prompt_quoting_the_log_is_accepted(self):
        prompt = self._build("build-python-failure-prompt.sh", [_FIXTURE, self._todos_file()])
        self.assertEqual([], lint_prompt_for_broad_test_instructions(prompt))
        self.assertIn("TestCiPromptTemplatesLintClean", prompt)
        self.assertIn("'flaky-hunt.txt'", prompt)
        self.assertIn(_PLACEHOLDER, prompt)
        self.assertIn("TODO honour " + _PLACEHOLDER, prompt)
        for name in _SHARD_VARIABLES:
            self.assertNotIn(name, prompt)

    def test_quality_gate_prompt_quoting_the_log_is_accepted(self):
        prompt = self._build("build-quality-gate-prompt.sh", [_FIXTURE],
                             extra_env={"FAILURE_COUNT": "1"})
        self.assertEqual([], lint_prompt_for_broad_test_instructions(prompt))
        self.assertIn(_PLACEHOLDER, prompt)

    def test_redaction_leaves_text_without_the_names_unchanged(self):
        text = "FAIL: test_x (pkg.FooTest.test_x)\nRan 3 tests in 0.1s\n"
        result = subprocess.run(
            ["bash", "-c", 'source "$1" && redact_ci_shard_variables', "redact",
             os.path.join(_PROMPT_DIR, "prompt-render.sh")],
            input=text, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(text, result.stdout)

    def test_instruction_outside_the_quoted_log_is_still_rejected(self):
        """Redaction applies to quoted output only, so an instruction to use the
        variables, written anywhere in the prompt's own text, is still refused."""
        prompt = self._build("build-python-failure-prompt.sh", [_FIXTURE])
        instructed = prompt + "\n" + _SHARD_INSTRUCTION + "\n"
        violations = lint_prompt_for_broad_test_instructions(instructed)
        self.assertEqual(1, len(violations))
        self.assertEqual(len(instructed.splitlines()), violations[0][0])
        self.assertIn("AR_TEST_GROUP", violations[0][1])


if __name__ == "__main__":
    unittest.main()
