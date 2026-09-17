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

The CL lanes (``test-cl``, ``test-media-cl``) are the one documented
exclusion: they upload no coverage and no surefire by design (an OpenCL host
with known flakiness that is deliberately not part of the merge gate — see
"Three lanes" in ``.github/CLAUDE.md``), so they are absent from
``analysis.needs`` and from this test's required set. This test pins that
absence too, so the exclusion stays a fact about the workflow rather than a
comment that can silently drift from it.
"""

import os
import re
import subprocess
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

# Documented exclusion: CL lanes are intentionally not part of the merge gate.
_EXCLUDED_LANES = {"test-cl", "test-media-cl"}

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
    def test_cl_lanes_are_not_required(self):
        workflow = _workflow()
        required = _required_test_jobs(workflow)
        self.assertEqual(set(), required & _EXCLUDED_LANES,
                          "CL lanes must stay out of analysis.needs (they are the "
                          "documented exclusion, not a required test job)")

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


if __name__ == "__main__":
    unittest.main()
