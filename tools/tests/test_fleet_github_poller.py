# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Tests for ``tools.fleet.github_poller.compute_job_metrics``.

``started_at - created_at`` is not queue wait for a job gated behind
``needs:`` — it also includes time blocked on upstream jobs. These tests pin
the distinction: the raw interval is always available as
``pre_start_latency_seconds``, but ``queue_wait_seconds`` is only ever
populated for an entry-point job (no dependencies) or when a
dependency-completion timestamp is supplied — never guessed.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_github_poller.py"
"""

import email.message
import json
import unittest
import urllib.error
from unittest import mock

from tools.fleet.github_poller import (
    _get_json,
    compute_job_metrics,
    fetch_run_jobs,
    fetch_runs,
    poll_and_store,
)
from tools.fleet.store import FleetStore


def _http_error(code, headers=None):
    hdrs = email.message.Message()
    for key, value in (headers or {}).items():
        hdrs[key] = value
    return urllib.error.HTTPError("https://api.github.com/x", code, "error", hdrs, None)


class ComputeJobMetricsTests(unittest.TestCase):

    def test_pre_start_latency_is_always_computed_from_the_raw_interval(self):
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        metrics = compute_job_metrics(job)
        self.assertAlmostEqual(metrics["pre_start_latency_seconds"], 300.0)

    def test_unknown_dependency_graph_leaves_entry_point_and_queue_wait_unset(self):
        """`needs=None` means "the caller has not parsed the workflow graph" —
        the function must not guess is_entry_point in that case."""
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        metrics = compute_job_metrics(job, needs=None)
        self.assertIsNone(metrics["is_entry_point"])
        self.assertIsNone(metrics["queue_wait_seconds"])
        # pre_start_latency is still reported - it's just not labelled as queue wait.
        self.assertAlmostEqual(metrics["pre_start_latency_seconds"], 300.0)

    def test_entry_point_job_queue_wait_equals_pre_start_latency(self):
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        metrics = compute_job_metrics(job, needs=[])
        self.assertTrue(metrics["is_entry_point"])
        self.assertAlmostEqual(metrics["queue_wait_seconds"], 300.0)
        self.assertAlmostEqual(metrics["queue_wait_seconds"], metrics["pre_start_latency_seconds"])

    def test_dependent_job_without_dependency_timestamp_leaves_queue_wait_unset(self):
        """A job with `needs:` must not report the raw interval as queue
        wait — it also includes time blocked on those dependencies."""
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T01:00:00Z"}
        metrics = compute_job_metrics(job, needs=["build"])
        self.assertFalse(metrics["is_entry_point"])
        self.assertIsNone(metrics["queue_wait_seconds"])
        # The raw (misleading, if used alone) interval is still available under its
        # honest name.
        self.assertAlmostEqual(metrics["pre_start_latency_seconds"], 3600.0)

    def test_dependent_job_queue_wait_is_measured_from_dependency_completion(self):
        """The dependency-adjusted calculation: subtract the last
        dependency's completion time from the job's start time."""
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T01:00:00Z"}
        metrics = compute_job_metrics(
            job, needs=["build"], dependency_completed_at="2026-09-18T00:58:00Z",
        )
        self.assertFalse(metrics["is_entry_point"])
        self.assertAlmostEqual(metrics["queue_wait_seconds"], 120.0)
        # pre_start_latency (1 hour) and queue_wait (2 minutes) must differ -
        # that gap is exactly the time blocked on the dependency.
        self.assertNotAlmostEqual(metrics["queue_wait_seconds"], metrics["pre_start_latency_seconds"])

    def test_job_that_has_not_started_yet_has_no_latency(self):
        job = {"created_at": "2026-09-18T00:00:00Z", "started_at": None}
        metrics = compute_job_metrics(job, needs=[])
        self.assertIsNone(metrics["pre_start_latency_seconds"])
        self.assertIsNone(metrics["queue_wait_seconds"])


class FetchRunsPaginationTests(unittest.TestCase):
    """`fetch_runs`/`fetch_run_jobs` must fetch every page or raise - never
    silently return a truncated list once `max_pages` is reached."""

    def test_fetch_runs_collects_every_page_below_the_cap(self):
        pages = [
            {"workflow_runs": [{"id": 1}, {"id": 2}]},
            {"workflow_runs": [{"id": 3}]},
        ]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            runs = fetch_runs("acme/repo", "tok", per_page=2, max_pages=5)
        self.assertEqual([r["id"] for r in runs], [1, 2, 3])

    def test_fetch_runs_raises_when_max_pages_exhausted_with_a_full_page(self):
        """A run of full pages all the way to max_pages means there may be
        more data beyond the cap - returning silently would misrepresent a
        truncated list as complete."""
        pages = [{"workflow_runs": [{"id": 1}, {"id": 2}]} for _ in range(3)]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            with self.assertRaises(RuntimeError):
                fetch_runs("acme/repo", "tok", per_page=2, max_pages=3)

    def test_fetch_run_jobs_collects_every_page_below_the_cap(self):
        pages = [
            {"jobs": [{"id": 1}, {"id": 2}]},
            {"jobs": [{"id": 3}]},
        ]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            jobs = fetch_run_jobs("acme/repo", "42", "tok", per_page=2, max_pages=5)
        self.assertEqual([j["id"] for j in jobs], [1, 2, 3])

    def test_fetch_run_jobs_raises_when_max_pages_exhausted_with_a_full_page(self):
        pages = [{"jobs": [{"id": 1}, {"id": 2}]} for _ in range(2)]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            with self.assertRaises(RuntimeError):
                fetch_run_jobs("acme/repo", "42", "tok", per_page=2, max_pages=2)


class RateLimitRetryTests(unittest.TestCase):
    """A rate-limited (403/429) response must be retried with backoff, not
    surfaced to a periodic poller as an immediate, unrecoverable failure."""

    def _response(self, payload):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps(payload).encode("utf-8")
        return response

    def test_retries_on_429_with_retry_after_then_succeeds(self):
        error = _http_error(429, {"Retry-After": "0"})
        success = self._response({"ok": True})
        with mock.patch(
            "tools.fleet.github_poller.urllib.request.urlopen", side_effect=[error, success],
        ), mock.patch("tools.fleet.github_poller.time.sleep") as sleep_mock:
            result = _get_json("https://api.github.com/x", "tok")
        self.assertEqual(result, {"ok": True})
        sleep_mock.assert_called_once_with(0.0)

    def test_gives_up_after_max_retries_exhausted(self):
        errors = [_http_error(403, {"Retry-After": "0"}) for _ in range(3)]
        with mock.patch(
            "tools.fleet.github_poller.urllib.request.urlopen", side_effect=errors,
        ), mock.patch("tools.fleet.github_poller.time.sleep"):
            with self.assertRaises(urllib.error.HTTPError):
                _get_json("https://api.github.com/x", "tok", max_retries=2)

    def test_non_rate_limit_error_is_not_retried(self):
        error = _http_error(500)
        with mock.patch(
            "tools.fleet.github_poller.urllib.request.urlopen", side_effect=[error],
        ) as urlopen, mock.patch("tools.fleet.github_poller.time.sleep") as sleep_mock:
            with self.assertRaises(urllib.error.HTTPError):
                _get_json("https://api.github.com/x", "tok")
        sleep_mock.assert_not_called()
        self.assertEqual(urlopen.call_count, 1)


class PollAndStoreTests(unittest.TestCase):
    """`poll_and_store` is the poll-cycle entry point that actually persists
    fetched jobs/steps - without it, job_event/job_step never receive data
    from production code, only from tests calling the lower-level pieces
    directly."""

    def setUp(self):
        self.store = FleetStore(":memory:")
        self.store.init_schema()

    def tearDown(self):
        self.store.close()

    def test_poll_and_store_persists_job_event_and_job_step(self):
        run = {"id": 1}
        job = {
            "id": 42,
            "name": "test",
            "status": "completed",
            "conclusion": "success",
            "created_at": "2026-09-18T00:00:00Z",
            "started_at": "2026-09-18T00:05:00Z",
            "completed_at": "2026-09-18T00:10:00Z",
            "labels": ["self-hosted", "macos", "ar-ci"],
            "runner_name": "runner-1",
            "runner_group_name": "Default",
            "steps": [
                {
                    "number": 1, "name": "checkout",
                    "started_at": "2026-09-18T00:05:00Z",
                    "completed_at": "2026-09-18T00:06:00Z",
                    "conclusion": "success",
                },
            ],
        }
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job]):
            count = poll_and_store("acme/repo", "tok", self.store)
        self.assertEqual(count, 1)
        self.assertEqual(self.store.job_event_count(), 1)
        self.assertEqual(self.store.job_step_count(), 1)
        row = self.store._conn.execute(
            "SELECT run_id, repo, labels, pre_start_latency_seconds FROM job_event WHERE job_id = ?",
            ("42",),
        ).fetchone()
        self.assertEqual(row[0], "1")
        self.assertEqual(row[1], "acme/repo")
        self.assertEqual(row[2], "self-hosted,macos,ar-ci")
        self.assertAlmostEqual(row[3], 300.0)

    def test_poll_and_store_is_idempotent_across_poll_cycles(self):
        run = {"id": 1}
        job = {"id": 42, "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job]):
            poll_and_store("acme/repo", "tok", self.store)
            poll_and_store("acme/repo", "tok", self.store)
        self.assertEqual(self.store.job_event_count(), 1)


if __name__ == "__main__":
    unittest.main()
