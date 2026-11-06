"""Guard: only the remediation submitters mark a submission as a remediation pass.

The controller's per-PR remediation loop skips an ``auto-review`` whose only new
commit is the previous pass's own, and caps how many no-progress passes a PR may
draw. Both decisions must act on genuine remediation passes
(``auto-review`` / ``auto-resolve``) only — not on every automated job. Every CI
submission sets ``automated`` (it is how the intake gate works), so a QA round, a
planning job or a plan implementation is automated too, yet each is the primary
work a review reviews, not a remediation pass. Inferring "remediation pass" from
``automated`` made the controller record those jobs' primary-work commits as a
review's own and skip the first real auto-review of nearly every PR.

So the three trusted submission paths that *are* remediation passes set
``REMEDIATION_PASS=true`` explicitly, and every other submission path leaves it
unset (``submit-agent-job.sh`` defaults the ``remediationPass`` field to
``false``). This test pins that wiring at both ends.
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

    A regex scan over the raw YAML, dedenting each matched step into a flat
    mapping ``yaml.safe_load`` can parse on its own — the same parser as
    test_oop_review_follow_up_disabled.py and
    test_analysis_yaml_protect_test_files.py, because analysis.yaml's reusable
    step lists make step-level lookups through the full document tree awkward.
    """
    with open(workflow_path) as f:
        lines = f.readlines()

    steps = []
    i = 0
    while i < len(lines):
        match = _STEP_NAME.match(lines[i].strip())
        if match and match.group(1) == step_name:
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
            dedented = [block[0][field_indent:]]
            for line in block[1:]:
                dedented.append(line[field_indent:] if len(line) >= field_indent else "\n")
            step = yaml.safe_load("".join(dedented))
            steps.append((match.group(1), step.get("env") or {}))
            i = j
        else:
            i += 1
    return steps


class RemediationPassMarkingTest(unittest.TestCase):
    def test_every_remediation_submitter_marks_the_pass(self):
        """The auto-review, auto-resolve-python and auto-resolve paths set it true."""
        steps = (_steps_named(_ANALYSIS, "Submit agent job")
                 + _steps_named(_AUTO_RESOLVE_SUBMIT, "Submit agent job"))
        self.assertEqual(3, len(steps),
            "Expected exactly 3 'Submit agent job' steps across analysis.yaml and "
            "auto-resolve-submit.yaml (the auto-review, auto-resolve-python and "
            "auto-resolve submission paths); found %d. If a remediation submission "
            "path was added or renamed, set REMEDIATION_PASS on it and update this "
            "test." % len(steps))
        wrong = [name for name, env in steps
                 if env.get("REMEDIATION_PASS") != "true"]
        self.assertEqual([], wrong,
            "These remediation 'Submit agent job' steps do not set "
            "REMEDIATION_PASS to \"true\": %s" % wrong)

    def test_initial_work_submitters_do_not_mark_a_pass(self):
        """Workflows that submit a branch's primary work never set the flag.

        Their commits are what a review reviews; marking them as remediation
        passes would record their commits as a review's own and skip the first
        auto-review of the work.
        """
        workflows = os.path.join(_REPO_ROOT, ".github", "workflows")
        for name in ("implement-plan.yaml", "verify-completion.yaml",
                     "goal-decomposition.yaml", "master-agent-dispatch.yaml",
                     "qa-round.yaml", "plan-review.yaml"):
            with self.subTest(workflow=name):
                with open(os.path.join(workflows, name)) as f:
                    self.assertNotIn("REMEDIATION_PASS", f.read(),
                        "%s submits primary work to be reviewed, so it must leave "
                        "REMEDIATION_PASS unset (the default false)." % name)

    def test_submit_agent_job_sends_the_field_defaulting_false(self):
        """submit-agent-job.sh always sends remediationPass, defaulting to false."""
        with open(_SUBMIT_AGENT_JOB) as f:
            script = f.read()
        self.assertIn("REMEDIATION_PASS", script,
            "submit-agent-job.sh must read the REMEDIATION_PASS knob.")
        self.assertRegex(script, r'remediationPass:\s*\$remediation',
            "submit-agent-job.sh must send the remediationPass field.")
        self.assertRegex(script, r'\$\{REMEDIATION_PASS:-false\}',
            "submit-agent-job.sh must default REMEDIATION_PASS to false.")


@unittest.skipUnless(shutil.which("jq"), "submit-agent-job.sh builds its payload with jq")
class SubmitAgentJobRemediationPayloadTest(unittest.TestCase):
    """Runs submit-agent-job.sh against a stub ``curl`` and reads the payload it sends."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="remediation-pass-submit-test-")
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

    def _run(self, value):
        env = {k: v for k, v in os.environ.items() if k != "REMEDIATION_PASS"}
        env.update(PATH=self.tmp + os.pathsep + env.get("PATH", ""),
                   BRANCH="feature/x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid")
        if value is not None:
            env["REMEDIATION_PASS"] = value
        return subprocess.run(["bash", _SUBMIT_AGENT_JOB, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def _sent(self):
        with open(self.payload) as f:
            return json.load(f)

    def test_unset_defaults_to_false(self):
        result = self._run(None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(False, self._sent()["remediationPass"])

    def test_true_is_sent_as_a_json_boolean(self):
        result = self._run("true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(True, self._sent()["remediationPass"])

    def test_false_is_sent_as_a_json_boolean(self):
        result = self._run("false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(False, self._sent()["remediationPass"])


if __name__ == "__main__":
    unittest.main()
