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
"""Tests for ``tools.ci.fleet.store``.

Both ingest paths this store serves are at-least-once: a push transport
retries on timeout, and a GitHub poller re-reads the same run/job on its next
cycle. A schema with no primary/unique keys would duplicate rows and inflate
every rollup under either condition. These tests exercise exactly that: they
write the same logical row twice (once as a plain repeat, as a retried batch
or a repeated poll cycle would) and assert the store still holds exactly one
row, not two.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_store.py"
"""

import unittest

from tools.ci.fleet.store import FleetStore


class FleetStoreIdempotencyTests(unittest.TestCase):

    def setUp(self):
        self.store = FleetStore(":memory:")
        self.store.init_schema()

    def tearDown(self):
        self.store.close()

    def test_host_sample_upsert_is_idempotent_on_ts_host(self):
        self.store.upsert_host_sample("2026-09-18T00:00:00Z", "mac-studio", cpu_pct=10.0)
        self.store.upsert_host_sample("2026-09-18T00:00:00Z", "mac-studio", cpu_pct=10.0)
        count = self.store._conn.execute("SELECT COUNT(*) FROM host_sample").fetchone()[0]
        self.assertEqual(count, 1)

    def test_host_sample_upsert_replaces_the_value(self):
        self.store.upsert_host_sample("2026-09-18T00:00:00Z", "mac-studio", cpu_pct=10.0)
        self.store.upsert_host_sample("2026-09-18T00:00:00Z", "mac-studio", cpu_pct=99.0)
        row = self.store._conn.execute(
            "SELECT cpu_pct FROM host_sample WHERE ts = ? AND host = ?",
            ("2026-09-18T00:00:00Z", "mac-studio"),
        ).fetchone()
        self.assertEqual(row[0], 99.0)

    def test_class_sample_upsert_is_idempotent_on_ts_host_class(self):
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "mac-studio", "runner", 5.0, 100.0)
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "mac-studio", "runner", 5.0, 100.0)
        count = self.store._conn.execute("SELECT COUNT(*) FROM class_sample").fetchone()[0]
        self.assertEqual(count, 1)

    def test_job_event_upsert_is_idempotent_on_job_id_across_poll_cycles(self):
        """A GitHub poller re-reading the same run/job on its next cycle must
        not duplicate the job_event row."""
        for _ in range(3):
            self.store.upsert_job_event(
                job_id="job-42",
                run_id="run-7",
                created_at="2026-09-18T00:00:00Z",
                started_at="2026-09-18T00:05:00Z",
                pre_start_latency_seconds=300.0,
                is_entry_point=True,
                queue_wait_seconds=300.0,
            )
        self.assertEqual(self.store.job_event_count(), 1)

    def test_job_step_upsert_is_idempotent_on_job_id_and_number(self):
        for _ in range(2):
            self.store.upsert_job_step("job-42", 1, name="checkout")
            self.store.upsert_job_step("job-42", 2, name="test")
        self.assertEqual(self.store.job_step_count(), 2)

    def test_runner_state_upsert_is_idempotent_on_ts_host_runner(self):
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "mac-studio", "runner-1", state="busy")
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "mac-studio", "runner-1", state="busy")
        count = self.store._conn.execute("SELECT COUNT(*) FROM runner_state").fetchone()[0]
        self.assertEqual(count, 1)


class FleetStoreQueryTests(unittest.TestCase):

    def setUp(self):
        self.store = FleetStore(":memory:")
        self.store.init_schema()

    def tearDown(self):
        self.store.close()

    def test_latest_runner_states_picks_the_newest_sample_per_runner(self):
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "mac-studio", "runner-1", state="idle")
        self.store.upsert_runner_state("2026-09-18T00:05:00Z", "mac-studio", "runner-1", state="busy")
        rows = self.store.latest_runner_states()
        self.assertEqual(len(rows), 1)
        # (ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version)
        self.assertEqual(rows[0][4], "busy")

    def test_latest_runner_states_filters_by_host(self):
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "host-a", "runner-1", state="idle")
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "host-b", "runner-2", state="busy")
        rows = self.store.latest_runner_states(host="host-a")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][1], "host-a")

    def test_utilization_by_class_averages_across_samples(self):
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "mac-studio", "runner", 10.0, 100.0)
        self.store.upsert_class_sample("2026-09-18T00:01:00Z", "mac-studio", "runner", 30.0, 300.0)
        rows = self.store.utilization_by_class()
        self.assertEqual(len(rows), 1)
        host, cls, avg_cpu, avg_rss, count = rows[0]
        self.assertEqual(host, "mac-studio")
        self.assertEqual(cls, "runner")
        self.assertAlmostEqual(avg_cpu, 20.0)
        self.assertAlmostEqual(avg_rss, 200.0)
        self.assertEqual(count, 2)

    def test_pre_start_latency_by_label_only_counts_entry_point_jobs(self):
        """A non-entry-point job's `pre_start_latency` includes time blocked
        on its dependencies, so it must not be averaged into the queue-wait
        panel alongside jobs that have no dependency at all."""
        self.store.upsert_job_event(
            job_id="entry-1", labels="ar-ci", pre_start_latency_seconds=10.0, is_entry_point=True,
        )
        self.store.upsert_job_event(
            job_id="dependent-1", labels="ar-ci", pre_start_latency_seconds=9999.0, is_entry_point=False,
        )
        rows = self.store.pre_start_latency_by_label()
        self.assertEqual(len(rows), 1)
        labels, avg_latency, count = rows[0]
        self.assertEqual(labels, "ar-ci")
        self.assertEqual(count, 1)
        self.assertAlmostEqual(avg_latency, 10.0)


if __name__ == "__main__":
    unittest.main()
