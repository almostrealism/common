"""Guard: every required test job is wired into auto-resolve's failure routing.

``test-flowtree`` shipped with no surefire upload, so a failure in it had no
parseable XML for auto-resolve to read, and the "job failed without
parseable surefire" check in the ``Check for incomplete test execution`` step
only looked at ``test``/``test-media``/``test-mac``/``test-media-mac`` — it
never considered ``needs.test-flowtree.result``. A ``test-flowtree`` failure
therefore fell all the way through to the quality-gate/general-review paths,
which told an agent "all tests are passing" while a real failure sat
unresolved on the branch.

The fix wires ``test-flowtree`` in by hand, which only prevents a recurrence
of exactly this bug. This test prevents the *next* one: it derives the set of
required test-execution jobs from ``analysis.needs`` (every job there other
than ``build`` — see ``.github/CLAUDE.md``, "What the `analysis` job does":
that list is deliberately kept in lockstep with what ``all-checks`` waits on)
and asserts each one is covered by both failure detection and the surefire
allowlist. A new test lane added to ``analysis.needs`` without also being
wired into ``auto-resolve`` fails this test until it is.

The CL lanes (``test-cl``, ``test-media-cl``) were once the documented
exclusion from this set: informational jobs outside the merge gate that
uploaded no surefire. The CUDA lanes (``test-cuda``, ``test-media-cuda``)
followed the same path. Both are now required like every other lane, so the
pipeline covers every accelerator backend it tests, and this test pins their
presence so they cannot quietly drop back out of the gate.
"""

import os
import re
import subprocess
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

# The OpenCL lanes, required like every other test lane.
_CL_LANES = {"test-cl", "test-media-cl"}

# The CUDA lanes, required like every other test lane.
_CUDA_LANES = {"test-cuda", "test-media-cuda"}

# Artifact names a future lane, not yet required, would upload. The allowlist
# must drop them; a glob broad enough to keep them (`surefire-media-*` once kept
# an informational lane's media artifact) fails the informational-jobs test.
_UNREQUIRED_LANE_ARTIFACTS = ["surefire-npu-group-0", "surefire-media-npu-group-0"]

# Matches a `${{ ... }}` GitHub Actions expression anywhere in a string.
_EXPRESSION = re.compile(r"\$\{\{[^}]*\}\}")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _required_test_jobs(workflow):
    """The job names `all-checks` and `auto-resolve` must treat as required.

    `analysis.needs` minus `build` is exactly this set: `analysis` waits for
    it "so that the input to analysis is not narrower than the input to
    all-checks" (see .github/CLAUDE.md), which ties it directly to the merge
    gate rather than to an incidental, hand-maintained list.
    """
    return set(workflow["jobs"]["analysis"]["needs"]) - {"build"}


def _step(job, name):
    for step in job["steps"]:
        if step.get("name") == name:
            return step
    raise AssertionError("step %r not found" % name)


def _surefire_artifact_names(job):
    """Every `surefire-*` artifact name a job uploads, expressions intact."""
    names = []
    for step in job["steps"]:
        if not str(step.get("uses", "")).startswith("actions/upload-artifact"):
            continue
        name = (step.get("with") or {}).get("name", "")
        if name.startswith("surefire"):
            names.append(name)
    return names


def _concrete_example(artifact_name):
    """A literal example of what `artifact_name` resolves to at runtime.

    Every `${{ ... }}` expression used in a surefire artifact name is a
    matrix index, so substituting a digit produces a name shaped exactly
    like what the download step actually sees.
    """
    return _EXPRESSION.sub("0", artifact_name)


def _allowlist_case_patterns():
    """The `case "$base" in <patterns>)` alternatives from the filter step."""
    workflow = _workflow()
    step = _step(workflow["jobs"]["auto-resolve"], "Filter to resolvable surefire reports")
    match = re.search(r'case "\$base" in\s*\n\s*(\S+)\)', step["run"])
    if not match:
        raise AssertionError("could not find the surefire allowlist case pattern")
    return match.group(1)


def _media_arm(patterns):
    """test-media's own alternative from the allowlist pattern."""
    for arm in patterns.split("|"):
        if arm.startswith("surefire-media-["):
            return arm
    raise AssertionError("could not find test-media's arm in %s" % patterns)


def _matches_allowlist(name, patterns):
    """Whether bash's own `case` would keep `name` under the allowlist."""
    script = (
        'case "$1" in\n'
        "  %s) exit 0 ;;\n"
        "  *) exit 1 ;;\n"
        "esac\n" % patterns
    )
    result = subprocess.run(["bash", "-c", script, "bash", name], check=False)
    return result.returncode == 0


class AutoResolveTestJobCoverageTest(unittest.TestCase):
    def test_cl_lanes_are_required(self):
        """The CL lanes are required test jobs."""
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        self.assertEqual(_CL_LANES, required & _CL_LANES,
                          "CL lanes must be in analysis.needs, and through it in the "
                          "merge gate and auto-resolve's failure routing")

    def test_cuda_lanes_are_required(self):
        """The CUDA lanes are required test jobs."""
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        self.assertEqual(_CUDA_LANES, required & _CUDA_LANES,
                          "CUDA lanes must be in analysis.needs, and through it in the "
                          "merge gate and auto-resolve's failure routing")

    def test_required_jobs_are_in_auto_resolve_needs(self):
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        needs = set(workflow["jobs"]["auto-resolve"]["needs"])
        missing = required - needs
        self.assertEqual(set(), missing,
                          "These required test jobs are missing from auto-resolve.needs: "
                          "%s" % missing)

    def test_required_jobs_are_checked_by_all_checks(self):
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        needs = set(workflow["jobs"]["all-checks"]["needs"])
        missing = required - needs
        self.assertEqual(set(), missing,
                          "These required test jobs are missing from all-checks.needs: "
                          "%s" % missing)

    def test_required_jobs_are_covered_by_failure_detection(self):
        """A required job's raw `failure` result must reach the routing script.

        `Check for incomplete test execution` is what decides whether a job
        failing without parseable surefire XML routes to the build-failure
        path instead of quality-gate/general-review. A job whose
        `needs.<job>.result` never appears there can fail silently while
        those paths still report "all tests passing".
        """
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        step = _step(workflow["jobs"]["auto-resolve"], "Check for incomplete test execution")
        run = step["run"]
        missing = [job for job in required if ("needs.%s.result" % job) not in run]
        self.assertEqual([], missing,
                          "These required test jobs are not read in the "
                          "'Check for incomplete test execution' step: %s" % missing)

    def test_required_jobs_are_covered_by_the_surefire_allowlist(self):
        """A required job's own surefire artifact must survive the filter step.

        A job that uploads surefire XML under a name the allowlist does not
        match has its reports silently deleted before parsing, so a real
        test failure in it is never described to the agent — only its bare
        job-failure status is, via the (much less specific) crash path.
        """
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        patterns = _allowlist_case_patterns()

        uncovered = []
        for job_name in sorted(required):
            job = workflow["jobs"][job_name]
            artifact_names = _surefire_artifact_names(job)
            examples = [_concrete_example(n) for n in artifact_names]
            if not any(_matches_allowlist(example, patterns) for example in examples):
                uncovered.append((job_name, examples))

        self.assertEqual([], uncovered,
                          "These required test jobs upload no surefire artifact the "
                          "allowlist keeps: %s (allowlist: %s)" % (uncovered, patterns))

    def test_informational_jobs_are_dropped_by_the_surefire_allowlist(self):
        """A job outside the required set must not have its surefire kept.

        An informational lane is deliberately absent from `analysis.needs`,
        so its failures must never reach an agent. A broad allowlist glob can
        still match its artifact name — the test-media arm was once
        `surefire-media-*`, which kept the then-informational CUDA lane's
        `surefire-media-cuda-group-*` — and hand its XML to the parser. Every
        lane is required today, so the check also runs against the artifact
        names a future, not-yet-required lane would upload.
        """
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        patterns = _allowlist_case_patterns()

        examples = [("(future lane)", name) for name in _UNREQUIRED_LANE_ARTIFACTS]
        for job_name in sorted(set(workflow["jobs"]) - required):
            for name in _surefire_artifact_names(workflow["jobs"][job_name]):
                examples.append((job_name, _concrete_example(name)))

        kept = []
        checked = 0
        for job_name, example in examples:
            checked += 1
            if _matches_allowlist(example, patterns):
                kept.append((job_name, example))

        self.assertGreater(checked, 0, "expected at least one informational surefire upload")
        self.assertEqual([], kept,
                          "These jobs are not required test jobs, but the allowlist "
                          "keeps their surefire artifacts: %s (allowlist: %s)" % (kept, patterns))

    def test_media_arm_does_not_match_other_lanes(self):
        """test-media's arm keeps only its own numbered groups.

        Checked on the arm alone: the other lanes' media artifacts are kept by
        their own arms, and this arm must not be what keeps them.
        """
        arm = _media_arm(_allowlist_case_patterns())
        self.assertTrue(_matches_allowlist("surefire-media-0", arm))
        self.assertTrue(_matches_allowlist("surefire-media-7", arm))
        self.assertFalse(_matches_allowlist("surefire-media-cuda-group-0", arm))
        self.assertFalse(_matches_allowlist("surefire-media-cl-group-0", arm))
        self.assertFalse(_matches_allowlist("surefire-media-", arm))


if __name__ == "__main__":
    unittest.main()
