"""Tests for collect-observed-flakes.py.

Loaded the same way ``test_classify_failures.py`` loads its subject: the script's
name has a hyphen, so it is imported by file location rather than by ``import``.

The flake detector is driven two ways: directly, with per-attempt records built
in the test, and end-to-end through ``collect`` with a fake GitHub client backed
by recorded-shaped API payloads (a runs list, per-attempt job lists, and job
logs). The required scenarios — a fail-then-pass test is a flake, a fail-every-
attempt test is not, two lanes are counted separately, and an empty window yields
an empty list — are covered both ways.
"""
import importlib.util
import os
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "collect-observed-flakes.py")

_spec = importlib.util.spec_from_file_location("collect_observed_flakes", _SCRIPT)
collect_observed_flakes = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collect_observed_flakes)


# A Surefire failure line as it appears in a test-lane job log, with the CI
# timestamp prefix the Actions log carries.
def _surefire_line(fqmethod):
    return ("2026-11-09T19:41:03.1234567Z [ERROR] %s  Time elapsed: 0.42 s  "
            "<<< FAILURE!" % fqmethod)


def _attempt(run_id, lane, attempt, failed, commit="cafe1234beef",
             created_at="2026-11-09T19:00:00Z", job_id=None):
    """One per-(run, lane, attempt) record for detect_flakes."""
    return {
        "run_id": run_id,
        "commit": commit,
        "branch": "master",
        "lane": lane,
        "attempt": attempt,
        "created_at": created_at,
        "failed": set(failed),
        "job_id": job_id if job_id is not None else run_id * 100 + attempt,
        "html_url": "https://github.com/o/r/actions/runs/%s/job/%s"
                    % (run_id, run_id * 100 + attempt),
    }


class SurefireParsingTests(unittest.TestCase):
    def test_failure_and_error_lines_name_the_class_and_method(self):
        log = "\n".join([
            _surefire_line("org.example.FooTest.computesSum"),
            "2026-11-09T19:41:04Z [ERROR] org.example.BarTest.reads  "
            "Time elapsed: 1.0 s  <<< ERROR!",
            "2026-11-09T19:41:05Z [INFO] BUILD FAILURE",
        ])
        self.assertEqual(
            collect_observed_flakes.surefire_failures(log),
            {"org.example.FooTest#computesSum", "org.example.BarTest#reads"})

    def test_parameterised_method_collapses_to_one_name(self):
        log = "\n".join([
            _surefire_line("org.example.FooTest.render[0]"),
            _surefire_line("org.example.FooTest.render[1]"),
        ])
        self.assertEqual(collect_observed_flakes.surefire_failures(log),
                         {"org.example.FooTest#render"})

    def test_a_log_with_no_failure_line_is_empty(self):
        self.assertEqual(
            collect_observed_flakes.surefire_failures("nothing to see here"), set())
        self.assertEqual(collect_observed_flakes.surefire_failures(""), set())


class LaneTests(unittest.TestCase):
    def test_a_sharded_job_reduces_to_its_lane(self):
        self.assertEqual(collect_observed_flakes.lane_of("test-media-cl (2)"),
                         "test-media-cl")
        self.assertEqual(collect_observed_flakes.lane_of("test"), "test")

    def test_only_test_lanes_are_recognised(self):
        self.assertTrue(collect_observed_flakes.is_test_lane("test-cuda (0)"))
        self.assertTrue(collect_observed_flakes.is_test_lane("test-mac"))
        self.assertFalse(collect_observed_flakes.is_test_lane("code-policy-check"))
        self.assertFalse(collect_observed_flakes.is_test_lane("build"))


class DetectFlakesTests(unittest.TestCase):
    def test_fail_then_pass_on_the_same_commit_is_a_flake(self):
        results = [
            _attempt(1, "test-mac", 1, {"org.example.FooTest#a"}),
            _attempt(1, "test-mac", 2, set()),  # re-run passed
        ]
        flakes = collect_observed_flakes.detect_flakes(results)
        self.assertEqual(len(flakes), 1)
        self.assertEqual(flakes[0]["test"], "org.example.FooTest#a")
        self.assertEqual(flakes[0]["lane"], "test-mac")
        self.assertEqual(flakes[0]["count"], 1)
        # The failing attempt's job is recorded as evidence to open.
        self.assertEqual([e["attempt"] for e in flakes[0]["evidence"]], [1])

    def test_fail_in_every_attempt_is_not_a_flake(self):
        results = [
            _attempt(2, "test-mac", 1, {"org.example.BarTest#b"}),
            _attempt(2, "test-mac", 2, {"org.example.BarTest#b"}),
            _attempt(2, "test-mac", 3, {"org.example.BarTest#b"}),
        ]
        self.assertEqual(collect_observed_flakes.detect_flakes(results), [])

    def test_two_lanes_are_counted_separately(self):
        results = [
            _attempt(3, "test-mac", 1, {"org.example.FooTest#a"}),
            _attempt(3, "test-mac", 2, set()),
            _attempt(3, "test-cuda", 1, {"org.example.FooTest#a"}),
            _attempt(3, "test-cuda", 2, set()),
        ]
        flakes = collect_observed_flakes.detect_flakes(results)
        self.assertEqual(len(flakes), 2)
        self.assertEqual({f["lane"] for f in flakes}, {"test-mac", "test-cuda"})
        for flake in flakes:
            self.assertEqual(flake["test"], "org.example.FooTest#a")
            self.assertEqual(flake["count"], 1)

    def test_an_empty_input_is_an_empty_list(self):
        self.assertEqual(collect_observed_flakes.detect_flakes([]), [])

    def test_ranked_by_frequency_then_recency(self):
        results = [
            # one lane, one flake, two commits -> count 2
            _attempt(10, "test-mac", 1, {"org.example.FooTest#a"},
                     commit="c1", created_at="2026-11-01T00:00:00Z"),
            _attempt(10, "test-mac", 2, set(), commit="c1",
                     created_at="2026-11-01T00:00:00Z"),
            _attempt(11, "test-mac", 1, {"org.example.FooTest#a"},
                     commit="c2", created_at="2026-11-02T00:00:00Z"),
            _attempt(11, "test-mac", 2, set(), commit="c2",
                     created_at="2026-11-02T00:00:00Z"),
            # a second flake, count 1
            _attempt(12, "test-cuda", 1, {"org.example.BarTest#b"},
                     commit="c3", created_at="2026-11-03T00:00:00Z"),
            _attempt(12, "test-cuda", 2, set(), commit="c3",
                     created_at="2026-11-03T00:00:00Z"),
        ]
        flakes = collect_observed_flakes.detect_flakes(results)
        self.assertEqual([f["test"] for f in flakes],
                         ["org.example.FooTest#a", "org.example.BarTest#b"])
        self.assertEqual(flakes[0]["count"], 2)
        self.assertEqual(flakes[0]["commits"], ["c1", "c2"])


class RenderTests(unittest.TestCase):
    def test_empty_renders_the_fallback_note(self):
        text = collect_observed_flakes.render([])
        self.assertIn("No confirmed flakes", text)
        self.assertIn("fall back", text.lower())

    def test_a_flake_renders_its_lane_count_and_evidence_link(self):
        results = [
            _attempt(5, "test-cuda", 1, {"org.example.FooTest#a"}, job_id=999),
            _attempt(5, "test-cuda", 2, set()),
        ]
        text = collect_observed_flakes.render(
            collect_observed_flakes.detect_flakes(results))
        self.assertIn("org.example.FooTest#a", text)
        self.assertIn("test-cuda", text)
        self.assertIn("CUDA", text)  # the friendly lane label
        self.assertIn("999", text)  # the attempt job id, for the agent to open


class _FakeClient:
    """A GitHub client backed by recorded-shaped payloads.

    ``runs`` is the workflow-runs list; ``attempt_jobs`` maps (run_id, attempt)
    to that attempt's jobs list; ``logs`` maps a job id to its log text."""

    def __init__(self, runs, attempt_jobs, logs):
        self._runs = runs
        self._attempt_jobs = attempt_jobs
        self._logs = logs

    def list_workflow_runs(self, max_runs):
        return self._runs[:max_runs]

    def list_attempt_jobs(self, run_id, attempt):
        return self._attempt_jobs.get((run_id, attempt), [])

    def job_log(self, job_id):
        return self._logs.get(job_id, "")


def _run(run_id, attempt, sha="cafe12340000beef", name="Build and Test",
         started="2026-11-09T19:00:00Z", branch="master"):
    return {
        "id": run_id,
        "name": name,
        "run_attempt": attempt,
        "head_sha": sha,
        "head_branch": branch,
        "run_started_at": started,
        "created_at": started,
    }


def _job(job_id, name, conclusion):
    return {
        "id": job_id,
        "name": name,
        "conclusion": conclusion,
        "html_url": "https://github.com/o/r/actions/runs/1/job/%s" % job_id,
    }


class CollectEndToEndTests(unittest.TestCase):
    """``collect`` over recorded-shaped GitHub API payloads."""

    def test_fail_then_pass_is_a_flake_two_lanes_separate(self):
        runs = [_run(100, attempt=2)]
        attempt_jobs = {
            (100, 1): [_job(1001, "test-mac", "failure"),
                       _job(1002, "test-cuda", "failure"),
                       _job(1003, "build", "success")],
            (100, 2): [_job(2001, "test-mac", "success"),
                       _job(2002, "test-cuda", "success")],
        }
        logs = {
            1001: _surefire_line("org.example.FooTest.render"),
            1002: _surefire_line("org.example.FooTest.render"),
        }
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(len(flakes), 2)
        self.assertEqual({f["lane"] for f in flakes}, {"test-mac", "test-cuda"})
        for flake in flakes:
            self.assertEqual(flake["test"], "org.example.FooTest#render")

    def test_a_persistent_failure_is_not_collected(self):
        runs = [_run(200, attempt=3)]
        fail = _surefire_line("org.example.BarTest.b")
        attempt_jobs = {
            (200, n): [_job(2000 + n, "test", "failure")] for n in (1, 2, 3)
        }
        logs = {2000 + n: fail for n in (1, 2, 3)}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(flakes, [])

    def test_a_run_that_was_never_retried_contributes_nothing(self):
        runs = [_run(300, attempt=1)]
        attempt_jobs = {(300, 1): [_job(3001, "test", "failure")]}
        logs = {3001: _surefire_line("org.example.BazTest.c")}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(flakes, [])

    def test_a_differently_named_workflow_is_ignored(self):
        runs = [_run(400, attempt=2, name="Deploy Controller Stack")]
        attempt_jobs = {
            (400, 1): [_job(4001, "test", "failure")],
            (400, 2): [_job(4002, "test", "success")],
        }
        logs = {4001: _surefire_line("org.example.FooTest.a")}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(flakes, [])

    def test_an_empty_window_produces_an_empty_list_without_error(self):
        flakes = collect_observed_flakes.collect(
            _FakeClient([], {}, {}), window_days=14)
        self.assertEqual(flakes, [])
        self.assertIn("No confirmed flakes", collect_observed_flakes.render(flakes))

    def test_runs_outside_the_window_are_dropped(self):
        import datetime
        now = datetime.datetime(2026, 11, 10, tzinfo=datetime.timezone.utc)
        runs = [_run(500, attempt=2, started="2026-01-01T00:00:00Z")]
        attempt_jobs = {
            (500, 1): [_job(5001, "test", "failure")],
            (500, 2): [_job(5002, "test", "success")],
        }
        logs = {5001: _surefire_line("org.example.FooTest.a")}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=14, now=now)
        self.assertEqual(flakes, [])

    def test_a_failed_lane_with_no_surefire_output_is_not_a_false_flake(self):
        # Attempt 1 fails a real test; attempt 2's lane fails before any test
        # ran (a setup/build failure, no Surefire line). The attempt-2 failure
        # is not evidence the test passed, so no flake is reported — recording
        # it as an empty (all-passed) set would be a false confirmed flake.
        runs = [_run(600, attempt=2)]
        attempt_jobs = {
            (600, 1): [_job(6001, "test", "failure")],
            (600, 2): [_job(6002, "test", "failure")],
        }
        logs = {
            6001: _surefire_line("org.example.FooTest.a"),
            6002: "2026-11-09T19:41:03Z setup-java failed: could not download JDK",
        }
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(flakes, [])

    def test_a_persistent_failure_in_one_shard_is_not_a_false_flake(self):
        # A sharded lane: shard 3 fails the same test in every attempt while its
        # sibling shard 4 (a different test subset) passes. Collapsing shards to
        # the lane before comparing attempts would pair shard 3's failure with
        # shard 4's pass and report a false flake; comparing per shard does not.
        runs = [_run(700, attempt=2)]
        fail = _surefire_line("org.example.BarTest.b")
        attempt_jobs = {
            (700, 1): [_job(7001, "test-media-cl (3)", "failure"),
                       _job(7002, "test-media-cl (4)", "success")],
            (700, 2): [_job(7003, "test-media-cl (3)", "failure"),
                       _job(7004, "test-media-cl (4)", "success")],
        }
        logs = {7001: fail, 7003: fail}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(flakes, [])

    def test_a_flake_within_a_shard_is_detected_and_reported_by_lane(self):
        # The same shard fails then passes on re-run: a genuine flake. It is
        # still detected, and the output collapses the shard suffix to the lane.
        runs = [_run(800, attempt=2)]
        attempt_jobs = {
            (800, 1): [_job(8001, "test-media-cl (3)", "failure"),
                       _job(8002, "test-media-cl (4)", "success")],
            (800, 2): [_job(8003, "test-media-cl (3)", "success"),
                       _job(8004, "test-media-cl (4)", "success")],
        }
        logs = {8001: _surefire_line("org.example.BarTest.b")}
        flakes = collect_observed_flakes.collect(
            _FakeClient(runs, attempt_jobs, logs), window_days=None)
        self.assertEqual(len(flakes), 1)
        self.assertEqual(flakes[0]["test"], "org.example.BarTest#b")
        self.assertEqual(flakes[0]["lane"], "test-media-cl")  # shard collapsed


class WorkflowScopedListingTests(unittest.TestCase):
    """The run listing is scoped to the Build and Test workflow, not the whole
    repository, so the run cap bounds Build-and-Test runs directly."""

    def test_run_listing_requests_the_workflow_scoped_endpoint(self):
        captured = []

        class _CapturingClient(collect_observed_flakes.GitHubClient):
            def _request(self, path, raw=False):
                captured.append(path)
                return {"workflow_runs": []}

        client = _CapturingClient("owner/name", token="")
        client.list_workflow_runs(50)
        self.assertEqual(len(captured), 1)
        self.assertIn(
            "/actions/workflows/%s/runs" % collect_observed_flakes.WORKFLOW_FILE,
            captured[0])
        self.assertNotIn("/actions/runs?", captured[0])


if __name__ == "__main__":
    unittest.main()
