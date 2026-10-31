"""Guard: CI follow-up jobs turn the object-oriented design review off.

The object-oriented design review (``ObjectOrientedDesignRule``) spends a
session on the changed production classes its risk gate flags and is on by
default at the controller. That is right for the job that creates a branch's work — a QA
round, a planning or task job, a hand-submitted job — but wrong for the
follow-up jobs CI submits in a loop: ``auto-review`` (and its code-policy and
quality-gate routes), ``auto-resolve-python`` and ``auto-resolve`` can each
fire many times on one branch, and the review should not ride along on every
pass.

Each of those jobs submits through a trusted "Submit agent job" step that
calls ``submit-staged-request.sh`` (which execs ``submit-agent-job.sh``). The
step's own environment — not the untrusted staged request — decides the
submission, so every such step must set ``OBJECT_ORIENTED_REVIEW_ENABLED`` to
``false``. ``submit-agent-job.sh`` sends the ``objectOrientedReviewEnabled``
field only when the knob is set, so every other submission path omits it and
gets the controller's default (on).
"""
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_ANALYSIS = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_AUTO_RESOLVE_SUBMIT = os.path.join(_REPO_ROOT, ".github", "workflows", "auto-resolve-submit.yaml")
_SUBMIT_AGENT_JOB = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")

_STEP_NAME = re.compile(r"^- name: (.*)$")


def _steps_named(workflow_path, step_name):
    """Returns the (name, env) pair of every step with the given name.

    Parsed with a regex scan over the raw YAML rather than ``yaml.safe_load``
    of the whole file: analysis.yaml uses reusable step lists and anchors that
    make step-level lookups by name awkward through the full document tree,
    while every step itself is a small, uniformly-indented YAML mapping under
    a ``- name:`` line that parses cleanly in isolation. Mirrors the parser in
    test_analysis_yaml_protect_test_files.py.
    """
    with open(workflow_path) as f:
        lines = f.readlines()

    steps = []
    i = 0
    while i < len(lines):
        match = _STEP_NAME.match(lines[i].strip())
        if match and match.group(1) == step_name:
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
            # Dedent every field line to column 0, and drop the leading "- "
            # list marker from the first line, so the result is a flat mapping
            # that yaml.safe_load can parse on its own.
            dedented = [block[0][field_indent:]]
            for line in block[1:]:
                dedented.append(line[field_indent:] if len(line) >= field_indent else "\n")
            step = yaml.safe_load("".join(dedented))
            steps.append((match.group(1), step.get("env") or {}))
            i = j
        else:
            i += 1
    return steps


class OopReviewFollowUpDisabledTest(unittest.TestCase):
    def test_every_submit_agent_job_step_disables_the_review(self):
        """Every trusted submission path for a follow-up job sends false."""
        steps = (_steps_named(_ANALYSIS, "Submit agent job")
                 + _steps_named(_AUTO_RESOLVE_SUBMIT, "Submit agent job"))
        # auto-review-submit and auto-resolve-python-submit in analysis.yaml,
        # plus the submit job in auto-resolve-submit.yaml.
        self.assertEqual(3, len(steps),
            "Expected exactly 3 'Submit agent job' steps across analysis.yaml "
            "and auto-resolve-submit.yaml (the auto-review, auto-resolve-python "
            "and auto-resolve submission paths); found %d. If a submission path "
            "was added or renamed, wire OBJECT_ORIENTED_REVIEW_ENABLED into it "
            "and update this test." % len(steps))
        wrong = [name for name, env in steps
                 if env.get("OBJECT_ORIENTED_REVIEW_ENABLED") != "false"]
        self.assertEqual([], wrong,
            "These 'Submit agent job' steps do not set "
            "OBJECT_ORIENTED_REVIEW_ENABLED to \"false\": %s" % wrong)

    def test_initial_work_submitters_keep_the_review_on(self):
        """Workflows that submit a branch's initial work never set the knob."""
        workflows = os.path.join(_REPO_ROOT, ".github", "workflows")
        for name in ("verify-completion.yaml", "goal-decomposition.yaml",
                     "master-agent-dispatch.yaml", "qa-round.yaml"):
            with self.subTest(workflow=name):
                with open(os.path.join(workflows, name)) as f:
                    self.assertNotIn("OBJECT_ORIENTED_REVIEW_ENABLED", f.read(),
                        "%s submits initial work, so it must leave "
                        "OBJECT_ORIENTED_REVIEW_ENABLED unset and keep the "
                        "controller default (on)." % name)

    def test_submit_agent_job_sends_the_field_only_when_set(self):
        """The knob is optional: unset means the controller default applies."""
        with open(_SUBMIT_AGENT_JOB) as f:
            script = f.read()
        self.assertIn("OBJECT_ORIENTED_REVIEW_ENABLED", script,
            "submit-agent-job.sh must read the OBJECT_ORIENTED_REVIEW_ENABLED knob.")
        self.assertIn("objectOrientedReviewEnabled", script,
            "submit-agent-job.sh must send the objectOrientedReviewEnabled field.")
        # The field is appended inside a guard on the env var being non-empty,
        # so an unset knob omits it and the controller's on-by-default applies.
        self.assertRegex(script,
            r"if \[ -n \"\$\{OBJECT_ORIENTED_REVIEW_ENABLED:-\}\" \];[\s\S]*objectOrientedReviewEnabled: \$o",
            "submit-agent-job.sh must send objectOrientedReviewEnabled only when "
            "OBJECT_ORIENTED_REVIEW_ENABLED is set, so every other submission "
            "path keeps the controller default (on).")


class SubmitAgentJobPayloadTest(unittest.TestCase):
    """Runs submit-agent-job.sh against a stub ``curl`` and reads the payload it sends."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="oop-review-submit-test-")
        self.payload = os.path.join(self.tmp, "payload.json")
        self.prompt = os.path.join(self.tmp, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("prompt")
        curl = os.path.join(self.tmp, "curl")
        with open(curl, "w") as f:
            f.write("""#!/usr/bin/env bash
while [ $# -gt 0 ]; do
  if [ "$1" = "-d" ]; then printf '%%s' "$2" > "%s"; shift; fi
  shift
done
printf '{"ok":true,"jobId":"j1"}\\n200'
""" % self.payload)
        os.chmod(curl, os.stat(curl).st_mode | stat.S_IXUSR)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, knob):
        env = {k: v for k, v in os.environ.items()
               if k != "OBJECT_ORIENTED_REVIEW_ENABLED"}
        env.update(PATH=self.tmp + os.pathsep + env.get("PATH", ""),
                   BRANCH="feature/x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid")
        if knob is not None:
            env["OBJECT_ORIENTED_REVIEW_ENABLED"] = knob
        return subprocess.run(["bash", _SUBMIT_AGENT_JOB, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def _sent(self):
        with open(self.payload) as f:
            return json.load(f)

    def test_unset_knob_omits_the_field(self):
        result = self._run(None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("objectOrientedReviewEnabled", self._sent())

    def test_empty_knob_omits_the_field(self):
        result = self._run("")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("objectOrientedReviewEnabled", self._sent())

    def test_false_is_sent_as_a_json_boolean(self):
        result = self._run("false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(False, self._sent()["objectOrientedReviewEnabled"])

    def test_true_is_sent_as_a_json_boolean(self):
        result = self._run("true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(True, self._sent()["objectOrientedReviewEnabled"])

    def test_an_invalid_value_submits_nothing(self):
        for value in ("False", "0", "no", "\"false\""):
            with self.subTest(value=value):
                result = self._run(value)
                self.assertEqual(1, result.returncode)
                self.assertIn("OBJECT_ORIENTED_REVIEW_ENABLED must be true or false",
                              result.stderr)
                self.assertFalse(os.path.exists(self.payload))


if __name__ == "__main__":
    unittest.main()
