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

import unittest

from tools.fleet.github_poller import compute_job_metrics


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


if __name__ == "__main__":
    unittest.main()
