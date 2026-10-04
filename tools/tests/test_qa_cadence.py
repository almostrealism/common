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

Most tests leave the token unset, which is the documented path that skips
the GitHub API. The awaiting-PR condition cannot be exercised that way, so its
tests point ``GITHUB_API_URL`` at a local stub of the pulls endpoint.
"""

import json
import os
import subprocess
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "qa-cadence.sh")


# One clock for the whole module. Reading the wall clock per call would let
# two stamps in a single test straddle a UTC midnight, and the failure would
# surface once at 00:00 and never reproduce.
_NOW = datetime.now(timezone.utc)


def _stamp(days_ago):
    """Returns a branch-name date that many days before the fixed clock."""
    return (_NOW - timedelta(days=days_ago)).strftime("%Y%m%d")


def _full_stamp(hours_ago):
    """Returns a branch-name "YYYYMMDD-HHMMSS" that many hours before the fixed clock."""
    return (_NOW - timedelta(hours=hours_ago)).strftime("%Y%m%d-%H%M%S")


class _PullsStub:
    """A local stand-in for the GitHub pulls endpoint.

    ``open_heads`` are the head refs listed as open PRs; ``heads_with_prs``
    are the head refs that have a PR in any state. Every query is recorded
    so a test can tell which lookups the gate made.
    """

    def __init__(self, open_heads=(), heads_with_prs=()):
        self.open_heads = list(open_heads)
        self.heads_with_prs = set(heads_with_prs) | set(open_heads)
        self.queries = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                query = parse_qs(urlparse(self.path).query)
                stub.queries.append(query)
                if query.get("state") == ["open"]:
                    body = [{"number": n, "head": {"ref": ref}}
                            for n, ref in enumerate(stub.open_heads, start=1)]
                else:
                    ref = query["head"][0].split(":", 1)[1]
                    body = [{"number": 1}] if ref in stub.heads_with_prs else []
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class _GateTestBase(unittest.TestCase):
    """A throwaway remote and a runner for the gate against it."""

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

    def _decide(self, prefix="qa/docs-", interval="7", force="false",
                grace="0", api=None, ignore_interval="false"):
        """Runs the gate and returns its ``(run, reason)`` outputs.

        With ``api`` (a :class:`_PullsStub`) the GitHub queries go to the
        stub; without it the token is unset and they are skipped.
        """
        env = dict(os.environ)
        env.update({
            "BRANCH_PREFIX": prefix,
            "MIN_INTERVAL_DAYS": interval,
            "PR_GRACE_HOURS": grace,
            "REMOTE": "origin",
            "FORCE": force,
            "IGNORE_INTERVAL": ignore_interval,
            # Unset so the open-PR half is skipped; see the module docstring.
            "GITHUB_REPOSITORY": "",
            "GITHUB_TOKEN": "",
        })
        if api is not None:
            env.update({
                "GITHUB_REPOSITORY": "owner/repo",
                "GITHUB_TOKEN": "token",
                "GITHUB_API_URL": api.url,
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


class QaCadenceTests(_GateTestBase):
    """End-to-end runs of the gate against a throwaway remote."""

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

    def test_interval_override_allows_a_recent_run(self):
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("true", "interval-ignored"),
                         self._decide(ignore_interval="true"))

    def test_interval_override_does_not_bypass_an_open_pr(self):
        # Unlike force, the override must never stack a round on an open one.
        api = _PullsStub(open_heads=["qa/docs-%s-010101" % _stamp(1)])
        self.addCleanup(api.close)
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("false", "pr-open"),
                         self._decide(ignore_interval="true", api=api))

    def test_unset_interval_override_keeps_the_interval(self):
        # A push to master passes the dispatch input through as "".
        self._branch("qa/docs-%s-010101" % _stamp(1))
        self.assertEqual(("false", "too-recent"),
                         self._decide(ignore_interval=""))

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


class AwaitingPrTests(_GateTestBase):
    """The awaiting-PR condition, configured as the planning job uses it.

    The planning job has no interval (``MIN_INTERVAL_DAYS=0``): one round
    may be open at a time, and the next may start as soon as it closes.
    Its agent opens the round's PR only when it finishes, so without this
    condition a merge landing while the agent works starts a second round
    beside the first — how project/plan-20260926-172935 and
    project/plan-20260926-174202 came to exist twelve minutes apart.
    """

    PREFIX = "project/plan-"

    def _plan(self, api, grace="24", force="false"):
        self.addCleanup(api.close)
        return self._decide(prefix=self.PREFIX, interval="0", grace=grace,
                            force=force, api=api)

    def test_no_planning_branch_starts_a_round(self):
        self.assertEqual(("true", "first-run"), self._plan(_PullsStub()))

    def test_a_new_branch_without_a_pr_holds_off_a_second_round(self):
        # The regression: the first round had no commits and no PR yet.
        self._branch(self.PREFIX + _full_stamp(0.2))
        self.assertEqual(("false", "awaiting-pr"), self._plan(_PullsStub()))

    def test_a_titled_branch_is_aged_by_its_stamp(self):
        self._branch(self.PREFIX + _full_stamp(1) + "-some-title")
        self.assertEqual(("false", "awaiting-pr"), self._plan(_PullsStub()))

    def test_an_open_plan_pr_holds_off_a_second_round(self):
        branch = self.PREFIX + _full_stamp(72)
        self._branch(branch)
        self.assertEqual(("false", "pr-open"),
                         self._plan(_PullsStub(open_heads=[branch])))

    def test_a_merged_or_closed_round_does_not_hold_anything_off(self):
        # A branch left behind after its PR was merged or closed is
        # history, not a round in progress, however recent it is.
        branch = self.PREFIX + _full_stamp(1)
        self._branch(branch)
        self.assertEqual(("true", "due"),
                         self._plan(_PullsStub(heads_with_prs=[branch])))

    def test_a_branch_that_never_opened_a_pr_stops_blocking_after_the_window(self):
        # An agent that failed without opening a PR must not stop
        # planning for good.
        self._branch(self.PREFIX + _full_stamp(30))
        self.assertEqual(("true", "due"), self._plan(_PullsStub()))

    def test_a_branch_without_a_time_is_not_treated_as_in_progress(self):
        self._branch(self.PREFIX + _stamp(0) + "-runner-fleet-monitoring")
        self.assertEqual(("true", "due"), self._plan(_PullsStub()))

    def test_open_prs_under_other_prefixes_do_not_count(self):
        self.assertEqual(("true", "first-run"),
                         self._plan(_PullsStub(open_heads=["qa/docs-20260926-172839"])))

    def test_the_condition_is_off_unless_a_window_is_set(self):
        # The QA jobs do not set PR_GRACE_HOURS; their interval covers the
        # same window, and their behaviour must not change.
        api = _PullsStub()
        self._branch(self.PREFIX + _full_stamp(0.2))
        self.assertEqual(("true", "due"), self._plan(api, grace="0"))
        self.assertTrue(all(q.get("state") == ["open"] for q in api.queries))

    def test_force_overrides_a_round_in_progress(self):
        self._branch(self.PREFIX + _full_stamp(0.2))
        self.assertEqual(("true", "forced"), self._plan(_PullsStub(), force="true"))


if __name__ == "__main__":
    unittest.main()
