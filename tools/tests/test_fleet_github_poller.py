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
import time as _time
import unittest
import urllib.error
from unittest import mock

from tools.fleet.github_poller import (
    RATE_LIMIT_DEFAULT_RETRY_SECONDS,
    _get_json,
    _is_retryable_rate_limit,
    _rate_limit_delay_seconds,
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


def _http_error_no_headers(code):
    """An HTTPError constructed with `hdrs=None`, as `urllib.request.urlopen`
    can hand back when a response carries no headers at all — distinct from
    `_http_error`'s always-present (possibly empty) `Message` object, and the
    only way to exercise the ``headers is None`` guards below."""
    return urllib.error.HTTPError("https://api.github.com/x", code, "error", None, None)


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

    def test_fetch_runs_allow_partial_returns_instead_of_raising(self):
        """A caller that deliberately wants a bounded recent window (e.g.
        `poll_and_store`) sets `allow_partial=True` and must get back
        whatever was fetched, not a RuntimeError, when more data exists
        beyond the cap."""
        pages = [{"workflow_runs": [{"id": 1}, {"id": 2}]} for _ in range(3)]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            runs = fetch_runs("acme/repo", "tok", per_page=2, max_pages=3, allow_partial=True)
        self.assertEqual([r["id"] for r in runs], [1, 2, 1, 2, 1, 2])

    def test_fetch_run_jobs_allow_partial_returns_instead_of_raising(self):
        pages = [{"jobs": [{"id": 1}, {"id": 2}]} for _ in range(2)]
        with mock.patch("tools.fleet.github_poller._get_json", side_effect=pages):
            jobs = fetch_run_jobs("acme/repo", "42", "tok", per_page=2, max_pages=2, allow_partial=True)
        self.assertEqual([j["id"] for j in jobs], [1, 2, 1, 2])

    def test_fetch_runs_wraps_a_url_error_as_a_runtime_error(self):
        """A transport-level failure (DNS, connection refused, timeout) must
        surface as a RuntimeError naming the page it failed on, not the raw
        URLError - `poll_and_store` and any other caller should be able to
        catch one exception type for every fetch failure."""
        with mock.patch(
            "tools.fleet.github_poller._get_json", side_effect=urllib.error.URLError("connection refused"),
        ):
            with self.assertRaises(RuntimeError) as ctx:
                fetch_runs("acme/repo", "tok", per_page=2, max_pages=3)
        self.assertIn("page 1", str(ctx.exception))

    def test_fetch_run_jobs_wraps_a_url_error_as_a_runtime_error(self):
        with mock.patch(
            "tools.fleet.github_poller._get_json", side_effect=urllib.error.URLError("connection refused"),
        ):
            with self.assertRaises(RuntimeError) as ctx:
                fetch_run_jobs("acme/repo", "42", "tok", per_page=2, max_pages=3)
        self.assertIn("page 1", str(ctx.exception))

    def test_fetch_runs_and_fetch_run_jobs_request_distinct_endpoints(self):
        """The refactor sharing a pagination loop between `fetch_runs` and
        `fetch_run_jobs` must still address the two distinct GitHub API
        endpoints - a caller for one must never end up polling the other."""
        with mock.patch(
            "tools.fleet.github_poller._get_json", return_value={"workflow_runs": []},
        ) as get_json:
            fetch_runs("acme/repo", "tok", per_page=2, max_pages=1, allow_partial=True)
        self.assertEqual(get_json.call_args[0][0], "https://api.github.com/repos/acme/repo/actions/runs?per_page=2&page=1")

        with mock.patch(
            "tools.fleet.github_poller._get_json", return_value={"jobs": []},
        ) as get_json:
            fetch_run_jobs("acme/repo", "42", "tok", per_page=2, max_pages=1, allow_partial=True)
        self.assertEqual(
            get_json.call_args[0][0],
            "https://api.github.com/repos/acme/repo/actions/runs/42/jobs?per_page=2&page=1",
        )


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

    def test_permission_denied_403_is_not_retried(self):
        """A 403 with neither `Retry-After` nor `X-RateLimit-Remaining: 0` is
        a permission error (bad/expired token, wrong scope), not a rate
        limit — retrying it would only delay an unrecoverable failure."""
        error = _http_error(403)
        with mock.patch(
            "tools.fleet.github_poller.urllib.request.urlopen", side_effect=[error],
        ) as urlopen, mock.patch("tools.fleet.github_poller.time.sleep") as sleep_mock:
            with self.assertRaises(urllib.error.HTTPError):
                _get_json("https://api.github.com/x", "tok")
        sleep_mock.assert_not_called()
        self.assertEqual(urlopen.call_count, 1)

    def test_403_with_rate_limit_remaining_zero_is_retried(self):
        """The primary hourly quota exhausted is signalled by
        `X-RateLimit-Remaining: 0` on a 403 with no `Retry-After` header —
        this must still be retried, not mistaken for permission-denied."""
        error = _http_error(403, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": str(int(_time.time()))})
        success = self._response({"ok": True})
        with mock.patch(
            "tools.fleet.github_poller.urllib.request.urlopen", side_effect=[error, success],
        ), mock.patch("tools.fleet.github_poller.time.sleep") as sleep_mock:
            result = _get_json("https://api.github.com/x", "tok")
        self.assertEqual(result, {"ok": True})
        sleep_mock.assert_called_once()

    def test_is_retryable_rate_limit_is_false_when_headers_are_absent(self):
        """A 403 with no headers object at all (not even an empty one) must
        be treated the same as permission-denied — there is no
        `Retry-After`/`X-RateLimit-Remaining` to say otherwise."""
        self.assertFalse(_is_retryable_rate_limit(_http_error_no_headers(403)))

    def test_rate_limit_delay_prefers_retry_after_over_reset(self):
        error = _http_error(429, {"Retry-After": "5", "X-RateLimit-Reset": str(int(_time.time()) + 999)})
        self.assertEqual(_rate_limit_delay_seconds(error), 5.0)

    def test_rate_limit_delay_falls_back_to_reset_when_retry_after_is_malformed(self):
        reset_at = _time.time() + 30
        error = _http_error(429, {"Retry-After": "not-a-number", "X-RateLimit-Reset": str(reset_at)})
        self.assertAlmostEqual(_rate_limit_delay_seconds(error), 30.0, delta=1.0)

    def test_rate_limit_delay_falls_back_to_default_when_both_headers_are_malformed(self):
        error = _http_error(429, {"Retry-After": "nope", "X-RateLimit-Reset": "also-nope"})
        self.assertEqual(_rate_limit_delay_seconds(error), RATE_LIMIT_DEFAULT_RETRY_SECONDS)

    def test_rate_limit_delay_falls_back_to_default_when_headers_are_absent(self):
        self.assertEqual(
            _rate_limit_delay_seconds(_http_error_no_headers(429)), RATE_LIMIT_DEFAULT_RETRY_SECONDS,
        )


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
        self.assertEqual(row[2], json.dumps(["ar-ci", "macos", "self-hosted"]))
        self.assertAlmostEqual(row[3], 300.0)

    def test_poll_and_store_is_idempotent_across_poll_cycles(self):
        run = {"id": 1}
        job = {"id": 42, "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job]):
            poll_and_store("acme/repo", "tok", self.store)
            poll_and_store("acme/repo", "tok", self.store)
        self.assertEqual(self.store.job_event_count(), 1)

    def test_poll_and_store_canonicalizes_labels_regardless_of_api_order(self):
        """The same conceptual label set must serialize identically no
        matter what order the API happens to return it in on a given poll -
        `pre_start_latency_by_label` groups on this exact string, and an
        unsorted encoding would split one label set into separate buckets
        across polls."""
        run = {"id": 1}
        job_a = {"id": 42, "labels": ["macos", "ar-ci", "self-hosted"]}
        job_b = {"id": 43, "labels": ["self-hosted", "ar-ci", "macos"]}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job_a, job_b]):
            poll_and_store("acme/repo", "tok", self.store)
        rows = self.store._conn.execute("SELECT DISTINCT labels FROM job_event").fetchall()
        self.assertEqual([r[0] for r in rows], [json.dumps(["ar-ci", "macos", "self-hosted"])])

    def test_poll_and_store_encodes_labels_containing_a_comma_losslessly(self):
        """A comma-joined encoding would collapse `["a,b", "c"]` and
        `["a", "b,c"]` into the identical string `"a,b,c"`, silently merging
        two distinct label sets in `pre_start_latency_by_label`'s grouping.
        The JSON encoding must keep them distinct."""
        run = {"id": 1}
        job_a = {"id": 42, "labels": ["a,b", "c"]}
        job_b = {"id": 43, "labels": ["a", "b,c"]}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job_a, job_b]):
            poll_and_store("acme/repo", "tok", self.store)
        rows = self.store._conn.execute("SELECT DISTINCT labels FROM job_event").fetchall()
        self.assertEqual(
            sorted(r[0] for r in rows),
            sorted([json.dumps(sorted(["a,b", "c"])), json.dumps(sorted(["a", "b,c"]))]),
        )

    def test_poll_and_store_bounds_runs_fetched_per_cycle(self):
        """`max_runs` must cap how many of the fetched runs get their jobs
        polled this cycle - without a bound, a long-lived repository would
        force one jobs request per run on every poll."""
        runs = [{"id": i} for i in range(5)]
        job_calls = []

        def _fake_fetch_run_jobs(repo, run_id, token, **kwargs):
            job_calls.append(run_id)
            return []

        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=runs) as fetch_runs_mock, \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", side_effect=_fake_fetch_run_jobs):
            poll_and_store("acme/repo", "tok", self.store, max_runs=2)
        self.assertEqual(job_calls, ["0", "1"])
        # fetch_runs itself is still called with allow_partial=True, since a
        # periodic poller wants a bounded recent window, not a guarantee
        # that the full run history was returned.
        self.assertTrue(fetch_runs_mock.call_args.kwargs.get("allow_partial"))

    def test_poll_and_store_max_runs_none_processes_every_fetched_run(self):
        runs = [{"id": i} for i in range(3)]
        job_calls = []

        def _fake_fetch_run_jobs(repo, run_id, token, **kwargs):
            job_calls.append(run_id)
            return []

        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=runs), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", side_effect=_fake_fetch_run_jobs):
            poll_and_store("acme/repo", "tok", self.store, max_runs=None)
        self.assertEqual(job_calls, ["0", "1", "2"])

    def test_poll_and_store_without_resolver_leaves_entry_point_metrics_null(self):
        """The default (no `resolve_needs`) production path must stay
        honest: without a dependency graph, `is_entry_point`/
        `queue_wait_seconds` are NULL, never guessed."""
        run = {"id": 1}
        job = {"id": 42, "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job]):
            poll_and_store("acme/repo", "tok", self.store)
        row = self.store._conn.execute(
            "SELECT is_entry_point, queue_wait_seconds FROM job_event WHERE job_id = ?", ("42",),
        ).fetchone()
        self.assertIsNone(row[0])
        self.assertIsNone(row[1])

    def test_poll_and_store_uses_resolve_needs_to_populate_entry_point_metrics(self):
        """A caller that supplies a `resolve_needs` callback (e.g. one that
        parsed the run's workflow file) gets `is_entry_point`/
        `queue_wait_seconds` populated for the jobs it can resolve."""
        run = {"id": 1}
        entry_job = {"id": 42, "name": "build", "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        unknown_job = {"id": 43, "name": "mystery", "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}

        def _resolve_needs(run_obj, job_obj):
            self.assertIs(run_obj, run)
            return [] if job_obj["name"] == "build" else None

        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[entry_job, unknown_job]):
            poll_and_store("acme/repo", "tok", self.store, resolve_needs=_resolve_needs)

        entry_row = self.store._conn.execute(
            "SELECT is_entry_point, queue_wait_seconds FROM job_event WHERE job_id = ?", ("42",),
        ).fetchone()
        self.assertEqual(entry_row[0], 1)
        self.assertAlmostEqual(entry_row[1], 300.0)

        unknown_row = self.store._conn.execute(
            "SELECT is_entry_point, queue_wait_seconds FROM job_event WHERE job_id = ?", ("43",),
        ).fetchone()
        self.assertIsNone(unknown_row[0])
        self.assertIsNone(unknown_row[1])

    def test_poll_and_store_batches_the_whole_cycle_into_one_transaction(self):
        """A poll cycle upserts one job_event per job plus one job_step per
        step - potentially hundreds of statements. They must all land inside
        a single `FleetStore.transaction()` batch, not commit one at a time,
        so a failure partway through does not leave a partially written
        cycle for a concurrent reader to observe."""
        run = {"id": 1}
        job = {"id": 42, "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}
        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=[run]), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", return_value=[job]), \
                mock.patch.object(self.store, "transaction", wraps=self.store.transaction) as batch:
            poll_and_store("acme/repo", "tok", self.store)
        batch.assert_called_once()

    def test_poll_and_store_rolls_back_the_whole_cycle_on_a_mid_cycle_failure(self):
        """If fetching a later run's jobs fails, the job_event rows already
        upserted for an earlier run in this same cycle must not be left
        behind half-committed - the next poll cycle will simply re-fetch and
        re-upsert everything once the transient failure clears."""
        runs = [{"id": 1}, {"id": 2}]
        first_job = {"id": 42, "created_at": "2026-09-18T00:00:00Z", "started_at": "2026-09-18T00:05:00Z"}

        def _fake_fetch_run_jobs(repo, run_id, token, **kwargs):
            if run_id == "1":
                return [first_job]
            raise RuntimeError("simulated transient failure")

        with mock.patch("tools.fleet.github_poller.fetch_runs", return_value=runs), \
                mock.patch("tools.fleet.github_poller.fetch_run_jobs", side_effect=_fake_fetch_run_jobs):
            with self.assertRaises(RuntimeError):
                poll_and_store("acme/repo", "tok", self.store)
        self.assertEqual(self.store.job_event_count(), 0)


if __name__ == "__main__":
    unittest.main()
