"""Tests for tools/ci/sweep-plans.py and the plan headers it reads.

``RepositoryPlansTest`` holds every plan in this repository to the header
rules in ``docs/plans/CLAUDE.md``, so a plan that would be invisible to the
sweep fails CI when it is added rather than lingering for good.
"""

import datetime
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "sweep-plans.py")

_spec = importlib.util.spec_from_file_location("sweep_plans", _SCRIPT)
sweep_plans = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sweep_plans)

TODAY = datetime.date(2026, 10, 23)


def _plan(status, created="2026-08-01", closed="", tracker="[]"):
    return "---\nstatus: %s\ncreated: %s\nclosed: %s\ntracker: %s\n---\n\n# Plan\n" % (
        status, created, closed, tracker)


class RepositoryPlansTest(unittest.TestCase):

    def test_every_plan_has_a_valid_header(self):
        report = sweep_plans.sweep(_REPO_ROOT, TODAY, 14, 60)
        self.assertEqual(report["invalid"], [], "fix the front matter as described in docs/plans/CLAUDE.md")


class SweepTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self._git("init", "-q")
        self._git("config", "user.email", "test@example.invalid")
        self._git("config", "user.name", "test")

    def tearDown(self):
        shutil.rmtree(self.root)

    def _git(self, *args, date=None):
        env = dict(os.environ)
        if date:
            env["GIT_COMMITTER_DATE"] = env["GIT_AUTHOR_DATE"] = date + "T12:00:00"
        subprocess.run(["git", "-C", self.root] + list(args), check=True,
                       capture_output=True, env=env)

    def _write(self, name, text, commit_date="2026-10-20"):
        path = os.path.join(self.root, "docs", "plans", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
        self._git("add", "-A")
        self._git("commit", "-q", "-m", name, date=commit_date)

    def test_classifies_plans(self):
        self._write("OLD_DONE.md", _plan("completed", closed="2026-10-01"))
        self._write("RECENT_DONE.md", _plan("abandoned", closed="2026-10-15"))
        self._write("QUIET.md", _plan("in-progress"), commit_date="2026-07-01")
        self._write("TRACKED.md", _plan("approved", tracker="[t1, t2]"))
        self._write("BROKEN.md", "# No header\n")
        self._write("CLAUDE.md", "# Exempt\n")
        self._write("0.76/PLAN.md", "# Exempt\n")

        report = sweep_plans.sweep(self.root, TODAY, 14, 60)

        self.assertEqual(report["expired"], [("docs/plans/OLD_DONE.md", "2026-10-01")])
        self.assertEqual([p for p, _ in report["invalid"]], ["docs/plans/BROKEN.md"])
        self.assertEqual(report["stale"], [("docs/plans/QUIET.md", "2026-07-01")])
        self.assertEqual(report["tracked"], [("docs/plans/TRACKED.md", ["t1", "t2"])])

    def test_delete_removes_only_expired_plans(self):
        self._write("OLD_DONE.md", _plan("completed", closed="2026-10-01"))
        self._write("OPEN.md", _plan("proposed"))

        status = sweep_plans.main(["--root", self.root, "--today", TODAY.isoformat(), "--delete"])

        self.assertEqual(status, 0)
        self.assertFalse(os.path.exists(os.path.join(self.root, "docs/plans/OLD_DONE.md")))
        self.assertTrue(os.path.exists(os.path.join(self.root, "docs/plans/OPEN.md")))

    def test_check_fails_on_invalid_header(self):
        self._write("BROKEN.md", _plan("completed"))
        self.assertEqual(sweep_plans.main(["--root", self.root, "--check"]), 1)


if __name__ == "__main__":
    unittest.main()
