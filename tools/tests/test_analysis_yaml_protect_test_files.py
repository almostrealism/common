"""Guard: every remediation request decides the harness's test lock on purpose.

``PROTECT_TEST_FILES`` turns on the harness's per-job test lock: every test
method that exists on the base branch stays exactly as it is for that job
(see ``flowtree/runtime/docs/file-staging.md``). It is for jobs whose premise
is that the existing tests are the reference — the ones sent to make failing
tests pass. An agent sent to fix a failing test has repeatedly loosened it
instead, which fails test-integrity-check, which dispatches another agent to
restore it, which fails the test again; the lock is what breaks that loop.

Every other remediation job (build failure, code policy, quality gates,
docs-only verify, general review, incomplete test execution) is held to
test-integrity-check alone, the rule every branch meets. The lock used to be
on for all of them, which contradicted that rule — a review could not even
improve an existing test.

Every "Stage submit request" step must therefore set the flag explicitly, to
the value its route calls for, so a step copied from another cannot silently
inherit the wrong one.
"""
import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

_STEP_NAME = re.compile(r"^- name: (Stage submit request .*)$")


def _stage_submit_steps():
    """Returns every "Stage submit request" step's (name, env) pair.

    Parsed with a regex scan over the raw YAML rather than ``yaml.safe_load``
    of the whole file: analysis.yaml uses reusable step lists and anchors
    that make step-level lookups by name awkward through the full document
    tree, while every step itself is a small, uniformly-indented YAML
    mapping under a `- name:` line that parses cleanly in isolation.
    """
    with open(_WORKFLOW) as f:
        lines = f.readlines()

    steps = []
    i = 0
    while i < len(lines):
        match = _STEP_NAME.match(lines[i].strip())
        if match:
            name = match.group(1)
            # A step's own line is "<indent>- name: ...": its field keys
            # (if:, env:, run:, ...) are indented two further, past the
            # "- " list marker.
            indent = len(lines[i]) - len(lines[i].lstrip(" "))
            field_indent = indent + 2
            block = [lines[i]]
            j = i + 1
            while j < len(lines):
                line = lines[j]
                if line.strip() == "":
                    block.append(line)
                    j += 1
                    continue
                line_indent = len(line) - len(line.lstrip(" "))
                if line_indent < field_indent:
                    break
                block.append(line)
                j += 1
            # Dedent every field line to column 0, and drop the leading
            # "- " list marker from the first line, so the result is a flat
            # mapping ("name: ...", "if: ...", "env: {...}", ...) that
            # yaml.safe_load can parse on its own.
            dedented = [block[0][field_indent:]]
            for line in block[1:]:
                dedented.append(line[field_indent:] if len(line) >= field_indent else "\n")
            step_yaml = "".join(dedented)
            step = yaml.safe_load(step_yaml)
            steps.append((name, step.get("env") or {}))
            i = j
        else:
            i += 1
    return steps


# The routes sent to make failing tests pass: the only ones that lock tests.
_TEST_FIXING_ROUTES = {
    "Stage submit request (test failures)",
    "Stage submit request (test job crash)",
    "Stage submit request (python test failures)",
}


class AnalysisYamlProtectTestFilesTest(unittest.TestCase):
    def test_every_stage_submit_request_step_exists(self):
        steps = _stage_submit_steps()
        self.assertGreaterEqual(len(steps), 8,
            "Expected at least 8 'Stage submit request' steps in analysis.yaml; "
            "found %d. If steps were renamed, update this test's expectations too."
            % len(steps))

    def test_every_stage_submit_request_step_protects_test_files(self):
        """Test files are protected exactly where a job is sent to fix tests."""
        steps = _stage_submit_steps()
        wrong = [name for name, env in steps
                 if env.get("PROTECT_TEST_FILES") != ("true" if name in _TEST_FIXING_ROUTES else "false")]
        self.assertEqual([], wrong,
            "These 'Stage submit request' steps do not set PROTECT_TEST_FILES "
            "explicitly to \"true\" (test-fixing routes) or \"false\" (all "
            "others): %s" % wrong)
        self.assertEqual(_TEST_FIXING_ROUTES,
                         {name for name, env in steps if env.get("PROTECT_TEST_FILES") == "true"})


if __name__ == "__main__":
    unittest.main()
