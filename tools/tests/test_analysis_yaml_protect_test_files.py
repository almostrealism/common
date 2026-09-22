"""Guard: every auto-resolve submission in analysis.yaml protects test files.

PR #492 was stuck for most of a day because the "general review" auto-resolve
path — the one that fires when the whole pipeline is green — submitted its
job without ``PROTECT_TEST_FILES``. The agent added new test methods to an
existing base-branch file, one of them was broken, and the harness's
guardrail (at the time, whole-file) silently discarded the entire file,
including the fix.

Test-file protection is now method-level (see
``flowtree/runtime/docs/file-staging.md``): an agent may still add new test
methods or edit ones it introduced on the branch, so there is no longer any
tradeoff between enabling protection and letting an agent write tests. Every
"Stage submit request" step in analysis.yaml should therefore set
``PROTECT_TEST_FILES: "true"`` — this test pins that so a future step copied
from an older one before this fix cannot silently reintroduce the gap.
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


class AnalysisYamlProtectTestFilesTest(unittest.TestCase):
    def test_every_stage_submit_request_step_exists(self):
        steps = _stage_submit_steps()
        self.assertGreaterEqual(len(steps), 8,
            "Expected at least 8 'Stage submit request' steps in analysis.yaml; "
            "found %d. If steps were renamed, update this test's expectations too."
            % len(steps))

    def test_every_stage_submit_request_step_protects_test_files(self):
        steps = _stage_submit_steps()
        missing = [name for name, env in steps if env.get("PROTECT_TEST_FILES") != "true"]
        self.assertEqual([], missing,
            "These 'Stage submit request' steps do not set "
            "PROTECT_TEST_FILES: \"true\": %s" % missing)


if __name__ == "__main__":
    unittest.main()
