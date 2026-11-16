"""Every branch's workstream must carry the settled-CI trigger.

A pull request cannot merge until a test run has passed on its head, and a
test run starts only when something deploys the branch to the ``test``
environment. The controller does that when a job on the branch's workstream
ends with nothing to commit, but only for a workstream whose
``settledCiTrigger`` is set. A workstream without it leaves its pull request
waiting for tests that never start.

These tests pin the three places that give a workstream the trigger:
``register-workstream.sh`` sends it on registration and re-applies it to a
workstream that already exists (one created some other way);
``submit-agent-job.sh`` sends it for a workstream its submission creates; and
``analysis.yaml`` sets it on the registration step and on both early submit
steps, which are what create the workstream of a docs-only branch. Every other
workflow that registers a workstream or submits a job (the QA rounds, task
planning, plan implementation, goal decomposition, plan review) sets it on
those steps too, so no branch an automated workflow starts waits for a test
run that nobody will start.

The submission half reaches the controller two ways: a step may run
``submit-agent-job.sh`` directly, or through the trusted
``submit-staged-request.sh`` wrapper (the staged auto-resolve/auto-review and
plan-implementation submit paths). The wrapper leaves ``CREATE_WORKSTREAM`` at
``submit-agent-job.sh``'s default of true and forwards ``SETTLED_CI_TRIGGER``
from its own environment, so both invocation forms can be the first to create a
workstream and ``EveryWorkflowWiringTests`` checks both.
"""

import glob
import os
import re
import shlex
import subprocess
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_task_planning_gates import _ControllerStub, _run  # noqa: E402

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_SUBMIT = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")
_WORKFLOWS = os.path.join(_REPO_ROOT, ".github", "workflows")
_TRIGGER = "deployment:test"
# The scripts whose invocation creates (or can create) a branch's workstream.
# submit-staged-request.sh is a trusted wrapper around submit-agent-job.sh: it
# forwards SETTLED_CI_TRIGGER from its own environment (not from the untrusted
# staged submit.env) and leaves CREATE_WORKSTREAM at submit-agent-job.sh's
# default of true, so a staged submission can be the first to create a branch's
# workstream and must carry the trigger just like a direct invocation.
_WORKSTREAM_SCRIPT = re.compile(
    r"tools/ci/(?:register-workstream|submit-agent-job|submit-staged-request)\.sh")
# Shell words that may precede the real command without changing what runs:
# keywords (``if``, ``then``, ...) and command wrappers (``timeout N``, ``env
# VAR=v``, ``nohup``, ...), whose own arguments are skipped with them.
_SHELL_KEYWORDS = frozenset((
    "if", "then", "else", "elif", "do", "while", "until", "!", "time",
    "nohup", "exec", "command", "sudo", "builtin"))
_COMMAND_WRAPPERS = frozenset(("timeout", "env", "xargs"))
# Programs that run the script named by their argument: a shell as a subprocess
# (``bash``, ``sh``) or the current shell sourcing it (``source``, ``.``). Each
# leaves the script path as the first non-flag word that follows.
_SHELLS = frozenset(("bash", "sh", "/bin/bash", "/bin/sh"))
_SCRIPT_RUNNERS = _SHELLS | frozenset(("source", "."))
_ASSIGNMENT = re.compile(r"\w+=")


def _inline_shell_script(command, shell):
    """Return the script a ``shell -c '<script>'`` command runs, or ``None``.

    ``command`` is tokenized the way the shell would (quotes removed, quoted
    text kept whole), and the ``-c`` argument of the first ``shell`` word is
    returned. A short flag cluster containing ``c`` (``-lc``, ``-ec``) counts as
    ``-c``. ``None`` means the shell is not given an inline script, or the
    command cannot be tokenized (an unbalanced quote).
    """
    try:
        tokens = shlex.split(command)
    except ValueError:
        return None
    if shell not in tokens:
        return None
    index = tokens.index(shell) + 1
    while index < len(tokens) and tokens[index].startswith("-") \
            and not tokens[index].startswith("--"):
        if "c" in tokens[index][1:]:
            return tokens[index + 1] if index + 1 < len(tokens) else None
        index += 1
    return None


def _split_commands(script):
    """Yield each command in a shell ``script`` as a separate string.

    The script is cut at the separators that start a new command — a newline,
    ``;``, ``|``, ``&``, ``(`` or ``)`` — but only where they sit outside quotes,
    and the text of a ``#`` comment is dropped. A separator inside a quoted
    string is literal text, so a script path mentioned inside quotes
    (``echo "(bash ./tools/ci/x.sh)"``) stays part of the ``echo`` command and is
    never read as a command of its own. A backslash outside single quotes
    escapes the next character, so an escaped quote (``"it\\"s"``, ``it\\'s``)
    leaves the quoting state unchanged and a ``\\``-newline line continuation
    keeps one command whole.
    """
    commands = []
    current = []
    quote = None
    in_comment = False
    escaped = False
    prev = "\n"
    for ch in script:
        if in_comment:
            if ch == "\n":
                in_comment = False
            prev = ch
            continue
        if escaped:
            # A backslash-escaped character is literal: an escaped quote neither
            # opens nor closes a string, and an escaped newline continues the
            # current command rather than ending it.
            current.append(" " if ch == "\n" else "\\" + ch)
            escaped = False
            prev = ch
            continue
        if ch == "\\" and quote != "'":
            escaped = True
            prev = ch
            continue
        if quote is not None:
            current.append(ch)
            if ch == quote:
                quote = None
            prev = ch
            continue
        if ch in ("'", '"'):
            quote = ch
            current.append(ch)
        elif ch == "#" and prev in " \t\n;|&()":
            in_comment = True
            commands.append("".join(current))
            current = []
        elif ch in "\n;|&()":
            commands.append("".join(current))
            current = []
        else:
            current.append(ch)
        prev = ch
    commands.append("".join(current))
    return [command for command in commands if command.strip()]


def _command_runs_script(command):
    """Whether a single shell ``command`` runs a workstream-creating script.

    Leading shell keywords, command wrappers (with their own arguments) and
    ``VAR=value`` prefixes are skipped. The command runs the script when what
    remains is the script path itself, or a script runner (``bash`` with any
    flags, ``source``, ``.``) applied to it. A shell given an inline script
    (``bash -c '...'``) runs the script when that inline script does, judged by
    the same rules. A command that passes the path to a
    different program (``chmod``, ``git``, ``[``, ``echo``) does not run it.
    """
    words = command.split()
    index = 0
    while index < len(words):
        word = words[index]
        if word in _SHELL_KEYWORDS:
            index += 1
        elif word in _COMMAND_WRAPPERS:
            index += 1
            while index < len(words) and (words[index].startswith("-")
                                          or words[index].lstrip("-").isdigit()
                                          or _ASSIGNMENT.match(words[index])):
                index += 1
        elif _ASSIGNMENT.match(word):
            index += 1
        else:
            break
    if index >= len(words):
        return False
    word = words[index]
    if word in _SHELLS:
        inline = _inline_shell_script(command, word)
        if inline is not None:
            return _creates_workstream(inline)
    if word in _SCRIPT_RUNNERS:
        index += 1
        while index < len(words) and words[index].startswith("-"):
            index += 1
        if index >= len(words):
            return False
        word = words[index]
    return _WORKSTREAM_SCRIPT.search(word) is not None


def _creates_workstream(run):
    """Whether a workflow step's ``run`` script runs a workstream-creating script
    as a command, as opposed to only naming one as an argument, in a comment, or
    inside a quoted string.
    """
    return any(_command_runs_script(command) for command in _split_commands(run))


class RegisterWorkstreamTriggerTests(unittest.TestCase):
    """register-workstream.sh carries the trigger to new and existing workstreams."""

    def _register(self, stub, trigger):
        self.addCleanup(stub.close)
        return _run("register-workstream.sh", {
            "BRANCH": "feature/x", "BASE_BRANCH": "master",
            "CONTROLLER_URL": stub.url, "SETTLED_CI_TRIGGER": trigger,
            "REQUIRED_LABELS_JSON": "", "TRACKER_CAPABILITIES": "",
            "DEPENDENT_REPOS": "", "PLAN_FILE": ""})

    def test_the_trigger_is_sent_with_the_registration(self):
        stub = _ControllerStub()
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(0, code, log)
        self.assertEqual(_TRIGGER, stub.seen[0][4]["settledCiTrigger"])
        self.assertEqual(1, len(stub.seen), "a new workstream needs no update")

    def test_an_existing_workstream_is_updated_with_the_trigger(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(0, code, log)
        self.assertEqual(2, len(stub.seen), log)
        self.assertTrue(stub.seen[1][1].endswith("/ws-1/update"))
        self.assertEqual({"settledCiTrigger": _TRIGGER}, stub.seen[1][4])

    def test_a_failed_trigger_update_fails_the_registration(self):
        stub = _ControllerStub(existing=True, update=(503, {"ok": False}))
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(1, code, log)

    def test_no_trigger_sends_no_trigger(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, "")
        self.assertEqual(0, code, log)
        self.assertNotIn("settledCiTrigger", stub.seen[0][4])
        self.assertEqual(1, len(stub.seen), "nothing to update")


class SubmitAgentJobTriggerTests(unittest.TestCase):
    """submit-agent-job.sh sends the trigger for a workstream it creates."""

    def _submit(self, trigger):
        stub = _ControllerStub()
        self.addCleanup(stub.close)
        with tempfile.TemporaryDirectory() as tmp:
            prompt = os.path.join(tmp, "prompt.txt")
            with open(prompt, "w") as f:
                f.write("Do something")
            env = dict(os.environ)
            env.pop("GITHUB_OUTPUT", None)
            env.update({"BRANCH": "feature/x", "BASE_BRANCH": "master",
                        "CONTROLLER_URL": stub.url, "SETTLED_CI_TRIGGER": trigger,
                        "REPO_URL": "git@github.com:example/repo.git"})
            subprocess.run(["bash", _SUBMIT, prompt], env=env,
                           capture_output=True, text=True, timeout=60)
        submits = [s for s in stub.seen if s[1] == "/api/submit"]
        self.assertEqual(1, len(submits), stub.seen)
        return submits[0][4]

    def test_the_trigger_is_sent_with_the_submission(self):
        self.assertEqual(_TRIGGER, self._submit(_TRIGGER)["settledCiTrigger"])

    def test_no_trigger_sends_no_trigger(self):
        self.assertNotIn("settledCiTrigger", self._submit(""))


class AnalysisWiringTests(unittest.TestCase):
    """analysis.yaml gives the trigger to every step that can create a workstream."""

    @classmethod
    def setUpClass(cls):
        with open(_WORKFLOW) as f:
            cls.jobs = yaml.safe_load(f)["jobs"]

    def _env(self, job, step_name):
        for step in self.jobs[job]["steps"]:
            if step.get("name") == step_name:
                return step.get("env", {})
        self.fail("no step %r in job %r" % (step_name, job))

    def assertSetsTrigger(self, job, step_name):
        value = self._env(job, step_name).get("SETTLED_CI_TRIGGER", "")
        self.assertIn("'%s'" % _TRIGGER, value, (job, step_name))

    def test_early_registration_sets_the_trigger(self):
        self.assertSetsTrigger("register-workstream", "Register workstream")

    def test_the_early_submissions_set_the_trigger(self):
        self.assertSetsTrigger("auto-review-submit", "Submit agent job")
        self.assertSetsTrigger("auto-resolve-python-submit", "Submit agent job")


class EveryWorkflowWiringTests(unittest.TestCase):
    """Every workflow step that can create a workstream gives it the trigger."""

    def _creating_steps(self):
        """Return (file, job, step name, effective env) for each creating step."""
        found = []
        for path in sorted(glob.glob(os.path.join(_WORKFLOWS, "*.y*ml"))):
            with open(path) as f:
                workflow = yaml.safe_load(f) or {}
            workflow_env = workflow.get("env") or {}
            for job_name, job in (workflow.get("jobs") or {}).items():
                job_env = job.get("env") or {}
                for step in job.get("steps") or []:
                    if _creates_workstream(step.get("run") or ""):
                        env = dict(workflow_env)
                        env.update(job_env)
                        env.update(step.get("env") or {})
                        found.append((os.path.basename(path), job_name,
                                      step.get("name"), env))
        return found

    def test_the_known_creating_workflows_are_found(self):
        files = {step[0] for step in self._creating_steps()}
        for expected in ("analysis.yaml", "master-agent-dispatch.yaml", "qa-round.yaml",
                         "task-planning.yaml", "implement-plan.yaml",
                         "goal-decomposition.yaml", "plan-review.yaml",
                         "auto-resolve-submit.yaml"):
            self.assertIn(expected, files)

    def test_detector_counts_only_executed_invocations(self):
        executed = (
            "bash ./tools/ci/register-workstream.sh",
            "./tools/ci/submit-agent-job.sh prompt.txt",
            'bash "$FRAMEWORK/tools/ci/submit-agent-job.sh" prompt.txt',
            "set -e\n  bash ./tools/ci/submit-staged-request.sh staged-request",
            "./tools/ci/register-workstream.sh 2>&1 | sed 's/x//'",
            "true && ./tools/ci/register-workstream.sh",
            "OUT=$(bash ./tools/ci/submit-agent-job.sh p)",
            # led by a shell keyword or a command wrapper
            "if bash ./tools/ci/submit-agent-job.sh prompt.txt; then :; fi",
            "then ./tools/ci/register-workstream.sh",
            "timeout 60 bash ./tools/ci/submit-agent-job.sh p",
            # bash with flags, and a bare path given through a quoted variable
            "bash -x ./tools/ci/register-workstream.sh",
            '"$DIR"/tools/ci/register-workstream.sh',
            "FOO=bar ./tools/ci/register-workstream.sh",
            # sourced into the current shell rather than run as a subprocess
            "source ./tools/ci/submit-agent-job.sh prompt.txt",
            ". ./tools/ci/register-workstream.sh",
            # run by a nested shell given an inline script
            "bash -lc 'cd repo && ./tools/ci/submit-agent-job.sh p'",
            'sh -c "FOO=1 ./tools/ci/register-workstream.sh"',
            "timeout 60 bash -ec 'bash ./tools/ci/submit-staged-request.sh r'",
            # an escaped quote earlier in the step does not open a string that
            # would swallow the invocation after it
            'echo "it\\"s"\nbash ./tools/ci/submit-agent-job.sh p',
            "echo it\\'s\nbash ./tools/ci/submit-agent-job.sh p",
            # a backslash line continuation keeps the command whole
            "bash -x \\\n  ./tools/ci/register-workstream.sh",
            "timeout 60 \\\n  bash ./tools/ci/submit-agent-job.sh p",
        )
        named_only = (
            "chmod +x ./tools/ci/register-workstream.sh",
            "if [ ! -f ./tools/ci/submit-staged-request.sh ]; then exit 0; fi",
            "git diff --name-only -- tools/ci/register-workstream.sh",
            'echo "see tools/ci/submit-agent-job.sh"',
            "# bash ./tools/ci/register-workstream.sh",
            # the path sits inside a quoted string, so the separators around it
            # do not start a command of their own
            'echo "(bash ./tools/ci/register-workstream.sh)"',
            # a nested shell whose inline script only names the path
            "bash -c 'chmod +x ./tools/ci/register-workstream.sh'",
            'sh -c "echo tools/ci/submit-agent-job.sh"',
            # an escaped quote does not close the string around the path
            'echo "say \\"(bash ./tools/ci/register-workstream.sh)\\""',
            # a backslash inside single quotes is literal and still closes them
            "echo 'a\\' ; chmod +x ./tools/ci/register-workstream.sh",
        )
        for run in executed:
            with self.subTest(run=run):
                self.assertTrue(_creates_workstream(run))
        for run in named_only:
            with self.subTest(run=run):
                self.assertFalse(_creates_workstream(run))

    def test_every_creating_step_sets_the_trigger(self):
        for name, job, step, env in self._creating_steps():
            with self.subTest(workflow=name, job=job, step=step):
                self.assertIn("'%s'" % _TRIGGER, str(env.get("SETTLED_CI_TRIGGER", "")))


if __name__ == "__main__":
    unittest.main()
