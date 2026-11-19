"""Tests for collect-master-failures.py."""
import contextlib
import importlib.util
import io
import os
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "collect_master_failures", os.path.join(_CI_DIR, "collect-master-failures.py"))
collector = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collector)

_FAILURE_LINE = ("2026-11-15T01:02:03.0000000Z [ERROR] org.example.FooTest.computesSum"
                 "  Time elapsed: 0.2 s  <<< FAILURE!\n")


def _run(run_id=7, conclusion="failure", event="push", branch="master", status="completed",
         head_sha="headsha"):
    return {"id": run_id, "conclusion": conclusion, "event": event, "head_branch": branch,
            "status": status, "head_sha": head_sha, "run_attempt": 1,
            "html_url": "https://example.invalid/run/%d" % run_id}


class FakeClient:
    """Recorded GitHub payloads in place of GitHubClient."""

    def __init__(self, runs, jobs=(), logs=None, pulls=(), comparison="ahead"):
        self.runs = list(runs)
        self.jobs = list(jobs)
        self.logs = logs or {}
        self.pulls = list(pulls)
        self.comparison = comparison
        self.compared = []

    def list_workflow_runs(self, max_runs, branch=""):
        return [run for run in self.runs if not branch or run["head_branch"] == branch][:max_runs]

    def list_attempt_jobs(self, run_id, attempt):
        return self.jobs

    def job_log(self, job_id):
        return self.logs.get(job_id, "")

    def list_closed_pulls(self, base):
        return self.pulls

    def compare(self, base_sha, head_sha):
        self.compared.append((base_sha, head_sha))
        return {"status": self.comparison}


class TestLatestRun(unittest.TestCase):
    def test_the_newest_completed_push_run_is_chosen(self):
        runs = [_run(9, status="in_progress"), _run(8, event="deployment"), _run(7), _run(6)]
        self.assertEqual(7, collector.latest_completed_master_run(runs)["id"])

    def test_a_run_cancelled_by_a_later_merge_is_passed_over(self):
        runs = [_run(9, status="queued", conclusion=None), _run(8, conclusion="cancelled"),
                _run(7, conclusion="skipped"), _run(6), _run(5, conclusion="success")]
        self.assertEqual(6, collector.latest_completed_master_run(runs)["id"])

    def test_no_master_push_run_is_none(self):
        self.assertIsNone(collector.latest_completed_master_run([_run(5, branch="feature/x")]))


class TestFailingTests(unittest.TestCase):
    def test_failed_test_lanes_are_read_and_grouped(self):
        jobs = [{"id": 1, "name": "test-mac (2)", "conclusion": "failure"},
                {"id": 2, "name": "test", "conclusion": "failure"},
                {"id": 3, "name": "checkstyle", "conclusion": "failure"},
                {"id": 4, "name": "test-cl (1)", "conclusion": "success"}]
        logs = {1: _FAILURE_LINE, 2: _FAILURE_LINE, 3: _FAILURE_LINE, 4: _FAILURE_LINE}
        failures = collector.failing_tests(jobs, logs.get)
        self.assertEqual({"org.example.FooTest#computesSum": ["test", "test-mac"]}, failures)


class TestLatestFixMerge(unittest.TestCase):
    def test_the_most_recent_merged_round_is_chosen(self):
        pulls = [
            {"head": {"ref": "qa/master-fix-20261101-000000"}, "merged_at": "2026-11-01T00:00:00Z",
             "merge_commit_sha": "older"},
            {"head": {"ref": "qa/master-fix-20261110-000000"}, "merged_at": "2026-11-10T00:00:00Z",
             "merge_commit_sha": "newer"},
            {"head": {"ref": "qa/master-fix-20261112-000000"}, "merged_at": None,
             "merge_commit_sha": "closed-unmerged"},
            {"head": {"ref": "feature/x"}, "merged_at": "2026-11-12T00:00:00Z",
             "merge_commit_sha": "other"},
        ]
        self.assertEqual("newer", collector.latest_fix_merge(pulls))

    def test_no_round_merged_is_none(self):
        self.assertIsNone(collector.latest_fix_merge([]))


class TestDecide(unittest.TestCase):
    _FAILURES = {"org.example.FooTest#computesSum": ["test"]}

    def test_a_current_failure_starts_a_round(self):
        self.assertTrue(collector.decide(_run(), self._FAILURES, True)[0])

    def test_a_green_master_starts_none(self):
        self.assertFalse(collector.decide(_run(conclusion="success"), {}, True)[0])

    def test_a_failure_without_a_failing_test_starts_none(self):
        self.assertFalse(collector.decide(_run(), {}, True)[0])

    def test_a_run_older_than_the_last_fix_starts_none(self):
        self.assertFalse(collector.decide(_run(), self._FAILURES, False)[0])

    def test_no_run_starts_none(self):
        self.assertFalse(collector.decide(None, {}, True)[0])


class TestMain(unittest.TestCase):
    def _main(self, client, *args):
        original = collector.flakes.GitHubClient
        collector.flakes.GitHubClient = lambda repo, token: client
        self.addCleanup(setattr, collector.flakes, "GitHubClient", original)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, collector.main(["--repo", "o/r"] + list(args)))
        return out.getvalue()

    def _failing_client(self, **kwargs):
        return FakeClient([_run()], jobs=[{"id": 1, "name": "test-mac (1)", "conclusion": "failure"}],
                          logs={1: _FAILURE_LINE}, **kwargs)

    def test_the_gate_dispatches_a_current_failure(self):
        out = self._main(self._failing_client(), "--gate")
        self.assertIn("dispatch=true", out)
        self.assertIn("run_id=7", out)
        self.assertIn("failing_count=1", out)

    def test_the_gate_dispatches_a_failure_logged_by_the_current_plugin(self):
        # A test-lane job log as the pipeline writes it: name and timing
        # separated by " -- ", after the per-class summary line.
        log = ("2026-11-18T02:32:54.3387150Z [ERROR] Tests run: 2, Failures: 1, Errors: 0, "
               "Skipped: 0, Time elapsed: 0.018 s <<< FAILURE! -- in org.example.FooTest\n"
               "2026-11-18T02:32:54.3387960Z [ERROR] org.example.FooTest.computesSum "
               "-- Time elapsed: 0.003 s <<< FAILURE!\n")
        client = FakeClient(
            [_run()], jobs=[{"id": 1, "name": "test-cuda (2)", "conclusion": "failure"}],
            logs={1: log})
        out = self._main(client, "--gate")
        self.assertIn("dispatch=true", out)
        self.assertIn("failing_count=1", out)

    def test_the_gate_holds_off_a_run_that_predates_the_last_fix(self):
        pulls = [{"head": {"ref": "qa/master-fix-20261110-000000"},
                  "merged_at": "2026-11-10T00:00:00Z", "merge_commit_sha": "fix"}]
        client = self._failing_client(pulls=pulls, comparison="behind")
        out = self._main(client, "--gate")
        self.assertIn("dispatch=false", out)
        self.assertEqual([("fix", "headsha")], client.compared)

    def test_the_gate_holds_off_a_green_master(self):
        out = self._main(FakeClient([_run(conclusion="success")]), "--gate")
        self.assertIn("dispatch=false", out)
        self.assertIn("failing_count=0", out)

    def test_the_leads_name_each_failing_test_and_lane(self):
        out = self._main(self._failing_client())
        self.assertIn("`org.example.FooTest#computesSum`", out)
        self.assertIn("https://example.invalid/run/7", out)

    def test_the_leads_say_when_nothing_failed(self):
        out = self._main(FakeClient([_run(conclusion="success")]))
        self.assertIn("No failing test", out)


if __name__ == "__main__":
    unittest.main()
