"""Guard: an enforcement step must not skip a branch that predates its script.

On a pull request the workflow comes from the merge with the base, but the
checkout is the PR head, which can predate a script the workflow runs. For an
ordinary step that absence is benign. For the CI file lock and the Python
test-hiding detector it is not: skipping them on such a branch would let a
branch based before the check existed change CI files, or weaken a Python
test, unchecked. Those steps instead extract a trusted copy of
``tools/ci/agent-protection/`` from ``origin/master`` and fail closed if even
that lacks the script. These tests pin that behaviour so a later edit cannot
restore the ``exit 0`` bypass.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

_STEPS = [
    ("changes", "Check the CI file lock", "check-ci-file-lock.sh"),
    ("test-integrity-check", "Check for Python test-hiding modifications",
     "detect-python-test-hiding.sh"),
]


def _run_script(job, name):
    with open(_WORKFLOW) as f:
        workflow = yaml.safe_load(f)
    for step in workflow["jobs"][job]["steps"]:
        if step.get("name") == name:
            return step["run"]
    raise AssertionError("step %r not found in job %r" % (name, job))


def _missing_script_branch(run):
    """The shell between the first 'if [ ! -f' test and its closing 'fi'."""
    match = re.search(r"if \[ ! -f [^\n]*\n(.*?)\n\s*fi\b", run, re.S)
    if match is None:
        raise AssertionError("no missing-script branch found")
    return match.group(1)


class EnforcementScriptFallbackTest(unittest.TestCase):

    def test_missing_script_does_not_exit_successfully(self):
        for job, name, script in _STEPS:
            with self.subTest(step=name):
                run = _run_script(job, name)
                self.assertIn(script, run)
                self.assertNotIn("exit 0", _missing_script_branch(run))

    def test_missing_script_uses_trusted_master_copy(self):
        for job, name, _ in _STEPS:
            with self.subTest(step=name):
                branch = _missing_script_branch(_run_script(job, name))
                self.assertIn("git archive origin/master tools/ci/agent-protection/", branch)

    def test_unavailable_trusted_copy_fails_closed(self):
        for job, name, _ in _STEPS:
            with self.subTest(step=name):
                run = _run_script(job, name)
                self.assertRegex(run, r"unavailable even from origin/master[^\n]*\n(?:[^\n]*\n)?\s*exit 1")


if __name__ == "__main__":
    unittest.main()
