"""Guard: the recurring-QA cadence gate must actually bound the cadence.

The documentation review was supposed to run at most once a day. It did not.
The branch names it left behind record five separate days with two rounds on
each, and 26 abandoned workstreams had accumulated behind them by the time
anybody counted. The cap lived in a GitHub Actions cache marker, and every
cache miss silently authorised an extra run — a miss looks exactly like "no
run yet today", and nothing reports it.

``tools/ci/qa-cadence.sh`` replaces that with a decision derived from the
branches the job already creates. This exercises it against a real git remote,
because the parts most likely to be wrong are the date arithmetic and the
lexical "newest branch" assumption, and neither can be checked by reading.

The open-PR half of the gate needs the GitHub API and is not covered here;
these tests leave the token unset, which is the documented path that skips it.
"""

import os
import subprocess
import unittest
from datetime import datetime, timedelta, timezone

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "qa-cadence.sh")


# One clock for the whole module. Reading the wall clock per call would let
# two stamps in a single test straddle a UTC midnight, and the failure would
# surface once at 00:00 and never reproduce.
_NOW = datetime.now(timezone.utc)


def _stamp(days_ago):
    """Returns a branch-name date that many days before the fixed clock."""
    return (_NOW - timedelta(days=days_ago)).strftime("%Y%m%d")


class QaCadenceTests(unittest.TestCase):
    """End-to-end runs of the gate against a throwaway remote."""

    def setUp(self):
        import tempfile
        self.tmp = tempfile.mkdtemp(prefix="qa-cadence-test-")
        self.origin = os.path.join(self.tmp, "origin.git")
        self.work = os.path.join(self.tmp, "work")
        self._git("init", "-q", "--bare", self.origin, cwd=self.tmp)
        self._git("init", "-q", self.work, cwd=self.tmp)
        self._git("config", "user.email", "t@t.t")
        self._git("config", "user.name", "t")
        self._git("commit", "-q", "--allow-empty", "-m", "init")
        self._git("remote", "add", "origin", self.origin)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _git(self, *args, cwd=None):
        subprocess.run(["git"] + list(args), cwd=cwd or self.work,
                       check=True, capture_output=True)

    def _branch(self, name):
        """Publishes a branch on the fake remote."""
        self._git("branch", "-q", name)
        self._git("push", "-q", "origin", name)

    def _decide(self, prefix="qa/docs-", interval="7", force="false"):
        """Runs the gate and returns its ``(run, reason)`` outputs."""
        env = dict(os.environ)
        env.update({
            "BRANCH_PREFIX": prefix,
            "MIN_INTERVAL_DAYS": interval,
            "REMOTE": "origin",
            "FORCE": force,
            # Unset so the open-PR half is skipped; see the module docstring.
            "GITHUB_REPOSITORY": "",
            "GITHUB_TOKEN": "",
        })
        env.pop("GITHUB_OUTPUT", None)
        result = subprocess.run(["bash", _SCRIPT], cwd=self.work, env=env,
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        out = dict(
            line.split("=", 1) for line in result.stdout.splitlines()
            if line.startswith(("run=", "reason="))
        )
        return out.get("run"), out.get("reason")

    def test_first_run_is_allowed(self):
        self.assertEqual(("true", "first-run"), self._decide())

    def test_run_older_than_the_interval_is_due(self):
        self._branch("qa/docs-%s-010101" % _stamp(30))
        self.assertEqual(("true", "due"), self._decide())

    def test_run_inside_the_interval_is_blocked(self):
        self._branch("qa/docs-%s-010101" % _stamp(2))
        self.assertEqual(("false", "too-recent"), self._decide())

    def test_same_day_second_run_is_blocked(self):
        # The exact failure that produced two rounds on five separate days.
        self._branch("qa/docs-%s-001229" % _stamp(0))
        self.assertEqual(("false", "too-recent"), self._decide())

    def test_newest_branch_decides_not_the_oldest(self):
        # Both present: the recent one must win. Reading the oldest would
        # make the gate permanently open once any old branch exists.
        self._branch("qa/docs-%s-010101" % _stamp(30))
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("false", "too-recent"), self._decide())

    def test_boundary_day_is_due(self):
        self._branch("qa/docs-%s-010101" % _stamp(7))
        self.assertEqual(("true", "due"), self._decide())

    def test_prefixes_are_independent(self):
        # A docs round must not hold off a defect hunt, or the two jobs
        # would starve each other.
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("true", "first-run"), self._decide(prefix="qa/defect-"))

    def test_unrelated_branches_are_ignored(self):
        self._branch("feature/something")
        self._branch("master-ish")
        self.assertEqual(("true", "first-run"), self._decide())

    def test_force_overrides_a_recent_run(self):
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("true", "forced"), self._decide(force="true"))

    def test_unparseable_branch_date_does_not_block_forever(self):
        # Erring toward running is right here: a name the gate cannot read
        # would otherwise disable the job silently and permanently.
        self._branch("qa/docs-not-a-date")
        run, reason = self._decide()
        self.assertEqual("true", run)
        self.assertEqual("unparseable-branch-date", reason)

    def test_undated_branch_cannot_mask_a_recent_run(self):
        # Regression. Selecting the lexically-last branch name made a single
        # undated name shadow every real run: anything starting with a letter
        # sorts after "2026...", so it was read as "the most recent run",
        # failed to parse, and fell through to "treat as due". One such branch
        # held the gate permanently open — the exact behaviour this script
        # exists to prevent, and invisible because each run looked reasonable.
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self._branch("qa/docs-not-a-date")
        self.assertEqual(("false", "too-recent"), self._decide())

    def test_undated_branch_does_not_hide_an_old_run_either(self):
        # The same selection, in the direction that should run: the dated
        # branch is old, so the answer is "due" for that reason rather than
        # because the undated name was unreadable.
        self._branch("qa/docs-%s-010101" % _stamp(30))
        self._branch("qa/docs-zzz-placeholder")
        self.assertEqual(("true", "due"), self._decide())

    def test_dated_branch_is_chosen_regardless_of_sort_position(self):
        # Undated names on both sides of the digits, so the fix cannot be
        # passing by accident of where one of them happens to sort.
        self._branch("qa/docs-AAA-before")
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self._branch("qa/docs-zzz-after")
        self.assertEqual(("false", "too-recent"), self._decide())


if __name__ == "__main__":
    unittest.main()
