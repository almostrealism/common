"""Guard: every remediation route in ``analysis.yaml`` wires the branch TODOs.

``test_prompt_builders.py`` proves each builder *forwards* a TODO file it is
handed into the prompt. It cannot prove the workflow actually *hands it one*:
the builders run there in a throwaway directory, with no view of how
``analysis.yaml`` invokes them. So a future edit could drop
``${{ runner.temp }}/branch-todos.txt`` from one route step, or move it out of
the builder's final slot, and every prompt-builder test would still pass while
that route's CI prompt silently lost the branch's new-TODO findings.

This test closes that gap at the workflow level. It reads ``analysis.yaml``,
finds every ``build-*-prompt.sh`` invocation, and holds each to two
obligations:

* the invocation passes the branch-TODO file as its **last** argument — the
  slot ``append_branch_feedback`` reads it from (``build-resolve-prompt.sh``
  takes two optional classification files ahead of it, so "last", not "second");
* the job that invokes it also **computes** that file, by running
  ``extract_branch_todos.py`` in one of its steps.

It also pins the set of routes so a remediation path dropped from the workflow
entirely — not merely passing the file in the wrong place — fails here rather
than quietly shipping a prompt with no TODO handling.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

# The remediation-route builders analysis.yaml invokes. build-verify-prompt.sh
# is deliberately absent: it belongs to the trusted implement-plan reusable
# workflow, which checks out only framework scripts and so cannot diff the
# branch for TODOs. Every route listed here runs against a branch that may carry
# new TODOs, so each of its invocations must pass the computed list.
_ROUTE_BUILDERS = {
    "build-build-failure-prompt.sh",
    "build-docs-review-prompt.sh",
    "build-policy-violation-prompt.sh",
    "build-python-failure-prompt.sh",
    "build-quality-gate-prompt.sh",
    "build-resolve-prompt.sh",
    "build-review-prompt.sh",
    "build-vm-crash-prompt.sh",
}

# A route-builder invocation: the script run as a command, at the start of a
# logical line (after stripping indentation and an optional `bash `). Anchoring
# to the line start excludes the `-f` existence test and the `chmod +x` lines,
# which name the same path mid-line.
_INVOCATION = re.compile(
    r"^(?:bash\s+)?\./tools/ci/prompts/(build-[a-z-]+-prompt\.sh)\b(.*)$")

# The TODO file passed as the final argument: either the literal
# `.../branch-todos.txt` or the `$TODOS` variable the compute step assigns it
# to, optionally quoted, at the very end of the (continuation-joined) command.
_TRAILING_TODO = re.compile(r'(?:branch-todos\.txt|\$\{?TODOS\}?)"?\s*$')

# The shared computation step runs this, inline or in a "Compute branch TODOs"
# step; a job that invokes a route builder must also run it.
_COMPUTE = "extract_branch_todos.py"


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _run_scripts(job):
    """Every step's ``run`` text in a job, each with line continuations joined."""
    scripts = []
    for step in job.get("steps", []):
        run = step.get("run")
        if run:
            scripts.append(re.sub(r"\\\n\s*", " ", run))
    return scripts


def _route_invocations(job):
    """Returns ``(builder_name, command_line)`` for every route-builder call in a job."""
    invocations = []
    for script in _run_scripts(job):
        for line in script.splitlines():
            match = _INVOCATION.match(line.strip())
            if match:
                invocations.append((match.group(1), line.strip()))
    return invocations


class BranchTodosWorkflowWiringTest(unittest.TestCase):
    def setUp(self):
        self.workflow = _workflow()
        self.jobs = self.workflow["jobs"]

    def test_every_route_invocation_passes_the_todo_file_last(self):
        """A route step must forward the branch-TODO file in the builder's final slot.

        A dropped or mis-placed ``branch-todos.txt`` here is exactly the gap the
        isolated prompt-builder tests cannot see.
        """
        bad = []
        for job_name, job in self.jobs.items():
            for builder, command in _route_invocations(job):
                if not _TRAILING_TODO.search(command):
                    bad.append((job_name, builder, command))
        self.assertEqual([], bad,
                          "These route-builder invocations do not pass the branch-TODO "
                          "file as their last argument: %s" % bad)

    def test_every_job_invoking_a_route_also_computes_the_todo_file(self):
        """The file a route forwards must be computed in the same job."""
        missing = []
        for job_name, job in self.jobs.items():
            if not _route_invocations(job):
                continue
            if not any(_COMPUTE in script for script in _run_scripts(job)):
                missing.append(job_name)
        self.assertEqual([], missing,
                          "These jobs invoke a route builder but never run %s to "
                          "compute the branch TODOs: %s" % (_COMPUTE, missing))

    def test_every_expected_route_is_still_wired(self):
        """A remediation route dropped from the workflow fails here.

        Passing the file in the wrong slot is one failure mode; removing a
        route's invocation altogether is another, and the per-invocation checks
        above go vacuously green for a route that is simply gone.
        """
        invoked = {builder
                   for job in self.jobs.values()
                   for builder, _ in _route_invocations(job)}
        missing = _ROUTE_BUILDERS - invoked
        self.assertEqual(set(), missing,
                          "These remediation routes are no longer invoked in "
                          "analysis.yaml: %s" % missing)

    def test_at_least_one_route_invocation_is_discovered(self):
        """A discovery bug would make the checks above vacuously pass."""
        invoked = [inv
                   for job in self.jobs.values()
                   for inv in _route_invocations(job)]
        self.assertTrue(invoked)


if __name__ == "__main__":
    unittest.main()
