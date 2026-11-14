"""Tests for tools/ci/start-pending-test-runs.sh.

The script is a catch-up a person runs with their own ``gh`` login: it starts
the test runs open pull requests are waiting for. It must never deploy a
fork's commit, never deploy a branch whose workflow has no deployment
trigger, never deploy a head that already has a test run, and change nothing
without ``--apply``. These tests run it against a fake ``gh`` that answers
from fixtures and records every call.
"""

import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "start-pending-test-runs.sh")
_REPO = "almostrealism/common"

#: A fake gh. ``api`` answers with the first fixture whose key is a substring
#: of the request path, filtered through --jq with the real jq; every call
#: (and any --input body) is appended to the log as one JSON line.
_FAKE_GH = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json, os, subprocess, sys
    args = sys.argv[1:]
    entry = {"args": args}
    if "--input" in args:
        entry["input"] = sys.stdin.read()
    with open(os.environ["GH_LOG"], "a") as f:
        f.write(json.dumps(entry) + "\\n")
    if args[:2] == ["auth", "status"] or args[:2] == ["run", "rerun"]:
        sys.exit(0)
    if args[0] != "api":
        sys.exit(1)
    path = next(a for a in args[1:] if a.startswith("repos/"))
    with open(os.environ["GH_FIXTURES"]) as f:
        fixtures = json.load(f)
    for key, value in fixtures:
        if key in path:
            break
    else:
        sys.exit(1)
    if value is None:
        sys.exit(1)
    if isinstance(value, str):
        sys.stdout.write(value)
        sys.exit(0)
    jq = args[args.index("--jq") + 1] if "--jq" in args else "."
    out = subprocess.run(["jq", "-r", jq], input=json.dumps(value),
                         capture_output=True, text=True)
    sys.stdout.write(out.stdout)
    sys.exit(out.returncode)
''')

_SPLIT_WORKFLOW = "jobs:\n  cancel-superseded-test-runs:\n"
_OLD_WORKFLOW = "jobs:\n  build:\n"


def _pr(number, branch, sha, head_repo=_REPO):
    return {"number": number, "head": {"ref": branch, "sha": sha,
                                       "repo": {"full_name": head_repo}}}


class StartPendingTestRunsTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        bindir = os.path.join(self.tmp.name, "bin")
        os.mkdir(bindir)
        gh = os.path.join(bindir, "gh")
        with open(gh, "w") as f:
            f.write(_FAKE_GH)
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)
        self.bindir = bindir
        self.log = os.path.join(self.tmp.name, "gh.log")
        self.fixtures = os.path.join(self.tmp.name, "fixtures.json")

    def _run(self, fixtures, *flags):
        with open(self.fixtures, "w") as f:
            json.dump(fixtures, f)
        env = dict(os.environ, PATH=self.bindir + os.pathsep + os.environ["PATH"],
                   GH_LOG=self.log, GH_FIXTURES=self.fixtures)
        result = subprocess.run(["bash", _SCRIPT, *flags], env=env,
                                capture_output=True, text=True, timeout=60)
        calls = []
        if os.path.exists(self.log):
            with open(self.log) as f:
                calls = [json.loads(line) for line in f]
        return result, calls

    @staticmethod
    def _fixtures(prs, run_conclusion="success", deployed_shas=(), old_shas=(),
                  required=("All Checks", "Test Checks")):
        fixtures = [["actions/workflows/analysis.yaml/runs",
                     {"workflow_runs": [{"id": 7, "status": "completed",
                                         "conclusion": run_conclusion,
                                         "head_sha": "a" * 40, "display_title": "Merge"}]}],
                    ["/pulls?", prs]]
        for pr in prs:
            sha = pr["head"]["sha"]
            fixtures.append(["deployments?sha=" + sha,
                             [{"id": 1}] if sha in deployed_shas else []])
            fixtures.append(["analysis.yaml?ref=" + sha,
                             _OLD_WORKFLOW if sha in old_shas else _SPLIT_WORKFLOW])
        # The deployment POST; after the per-SHA lookups, which match first.
        fixtures.append(["/deployments", {"id": 99}])
        fixtures.append(["required_status_checks",
                         {"contexts": list(required), "checks": []}])
        return fixtures

    @staticmethod
    def _deployments(calls):
        return [json.loads(c["input"]) for c in calls
                if "--input" in c["args"] and any(a.endswith("/deployments") for a in c["args"])]

    def test_report_mode_changes_nothing(self):
        prs = [_pr(1, "feature/a", "1" * 40)]
        result, calls = self._run(self._fixtures(prs, run_conclusion="cancelled"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self._deployments(calls))
        self.assertFalse(any(c["args"][:2] == ["run", "rerun"] for c in calls))
        self.assertIn("would POST", result.stdout)
        self.assertIn("would run: gh run rerun 7", result.stdout)

    def test_apply_deploys_the_branch_by_name(self):
        prs = [_pr(1, "feature/a", "1" * 40)]
        result, calls = self._run(self._fixtures(prs), "--apply")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        (body,) = self._deployments(calls)
        self.assertEqual("feature/a", body["ref"])
        self.assertEqual("test", body["environment"])
        self.assertFalse(body["auto_merge"])
        self.assertEqual([], body["required_contexts"])

    def test_a_fork_is_never_deployed(self):
        prs = [_pr(1, "feature/a", "1" * 40, head_repo="someone/common")]
        result, calls = self._run(self._fixtures(prs), "--apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self._deployments(calls))
        self.assertIn("fork", result.stdout)

    def test_a_head_with_a_test_deployment_is_skipped(self):
        prs = [_pr(1, "feature/a", "1" * 40), _pr(2, "feature/b", "2" * 40)]
        result, calls = self._run(self._fixtures(prs, deployed_shas={"1" * 40}), "--apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["feature/b"], [b["ref"] for b in self._deployments(calls)])

    def test_a_branch_without_the_split_must_merge_first(self):
        prs = [_pr(1, "feature/old", "1" * 40)]
        result, calls = self._run(self._fixtures(prs, old_shas={"1" * 40}), "--apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self._deployments(calls))
        self.assertIn("#1 feature/old", result.stdout)
        self.assertIn("must merge master", result.stdout)

    def test_a_cancelled_master_run_is_rerun(self):
        result, calls = self._run(self._fixtures([], run_conclusion="cancelled"), "--apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(["run", "rerun", "7", "--repo", _REPO],
                      [c["args"] for c in calls])

    def test_test_checks_is_added_only_when_asked(self):
        fixtures = self._fixtures([], required=("All Checks",))
        result, calls = self._run(fixtures, "--apply")
        self.assertIn("Test Checks: NOT required", result.stdout)
        self.assertFalse(any("--method" in c["args"] and any(
            a.endswith("/contexts") for a in c["args"]) for c in calls))

        os.remove(self.log)
        result, calls = self._run(fixtures, "--apply", "--require-test-checks")
        self.assertEqual(0, result.returncode, result.stderr)
        added = [c["args"] for c in calls if any(a.endswith("/contexts") for a in c["args"])]
        self.assertEqual(1, len(added))
        self.assertIn("contexts[]=Test Checks", added[0])


if __name__ == "__main__":
    unittest.main()
