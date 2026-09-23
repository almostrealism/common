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
"""Tests for ``tools.fleet.store``.

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

import os
import tempfile
import unittest

from tools.fleet.attribution import ClassMetrics
from tools.fleet.store import Dialect, FleetStore


class _FakePostgresConnection:
    """Records every statement executed against it, and answers the
    ``information_schema`` primary-key lookup with a fixed column list.

    Exercises :class:`FleetStore`'s Postgres-only migration branches
    (``_add_column_if_missing``, ``_widen_runner_state_key``) without a real
    Postgres server: :meth:`FleetStore.__init__`'s two-argument form accepts
    any connection object carrying an ``execute`` method, and none of the
    statements these methods issue need a real result set beyond the
    canned primary-key row list.
    """

    def __init__(self, primary_key_columns, fail_on=None, primary_key_columns_after_fail=None):
        self.statements = []
        self._primary_key_columns = primary_key_columns
        self._fail_on = fail_on
        self._primary_key_columns_after_fail = primary_key_columns_after_fail
        self._failed = False

    def execute(self, sql, params=()):
        self.statements.append(sql)
        if self._fail_on is not None and self._fail_on in sql:
            self._failed = True
            raise RuntimeError("simulated Postgres failure for %r" % sql)
        if "information_schema.table_constraints" in sql:
            columns = (
                self._primary_key_columns_after_fail
                if self._failed and self._primary_key_columns_after_fail is not None
                else self._primary_key_columns
            )
            return [(column,) for column in columns]
        return []


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

    def test_job_event_upsert_persists_the_executing_runner_id(self):
        """`runner_id` is unique across GitHub registration scopes, unlike
        `runner_name` - a dashboard grouping utilization by it does not
        merge two same-named runners from different scopes together."""
        self.store.upsert_job_event(job_id="job-42", runner_name="runner-1", runner_id=987654)
        row = self.store._conn.execute("SELECT runner_id FROM job_event WHERE job_id = 'job-42'").fetchone()
        self.assertEqual((987654,), row)

    def test_job_event_upsert_leaves_runner_id_null_by_default(self):
        self.store.upsert_job_event(job_id="job-42")
        row = self.store._conn.execute("SELECT runner_id FROM job_event WHERE job_id = 'job-42'").fetchone()
        self.assertEqual((None,), row)

    def test_job_event_upsert_positional_arguments_preserve_prior_ordering(self):
        """`runner_id` was added after `pre_start_latency_seconds`,
        `is_entry_point`, `queue_wait_seconds`, `lane` and `platform` were
        already part of the signature, specifically so that a caller
        invoking those five positionally (as this test does) keeps binding
        them to the same parameters it always did, instead of silently
        shifting onto `runner_id`."""
        self.store.upsert_job_event(
            "job-1", "run-1", "acme/repo", "build", "[]",
            "2026-09-18T00:00:00Z", "2026-09-18T00:05:00Z", "2026-09-18T00:10:00Z",
            "completed", "success", "runner-1", "default",
            300.0, True, 300.0, "ar-ci", "macos",
        )
        row = self.store._conn.execute(
            "SELECT pre_start_latency_seconds, is_entry_point, queue_wait_seconds, lane, platform, runner_id "
            "FROM job_event WHERE job_id = 'job-1'"
        ).fetchone()
        self.assertEqual((300.0, 1, 300.0, "ar-ci", "macos", None), row)

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

    def test_runner_state_upsert_keeps_same_named_runners_from_different_registration_scopes(self):
        """A runner's name is unique only within its GitHub registration
        scope (a repository, or an organization) - `github_poller.fetch_runners`
        can return a repo-scoped and an org-scoped runner that happen to
        share a name, both stamped with the same poll-cycle `ts` and the
        same `host=''` (the runners API reports no host). Without `repo`
        (which carries the registration scope for these rows) in the key,
        the second upsert would silently overwrite the first."""
        self.store.upsert_runner_state(
            "2026-09-18T00:00:00Z", "", "shared-name", state="idle", repo="almostrealism/common",
        )
        self.store.upsert_runner_state(
            "2026-09-18T00:00:00Z", "", "shared-name", state="busy", repo="org:almostrealism",
        )
        rows = self.store._conn.execute(
            "SELECT repo, state FROM runner_state WHERE runner_name = 'shared-name' ORDER BY repo"
        ).fetchall()
        self.assertEqual([("almostrealism/common", "idle"), ("org:almostrealism", "busy")], rows)

    def test_class_samples_upsert_writes_one_row_per_class(self):
        metrics = {
            "runner": ClassMetrics(cpu_pct=12.5, rss_mb=256.0, process_count=2),
            "agent": ClassMetrics(cpu_pct=3.0, rss_mb=64.0, process_count=1),
        }
        self.store.upsert_class_samples("2026-09-18T00:00:00Z", "mac-studio", metrics)
        rows = dict(
            (cls, (cpu_pct, rss_mb))
            for cls, cpu_pct, rss_mb in self.store._conn.execute(
                "SELECT class, cpu_pct, rss_mb FROM class_sample WHERE ts = ? AND host = ?",
                ("2026-09-18T00:00:00Z", "mac-studio"),
            )
        )
        self.assertEqual(rows, {"runner": (12.5, 256.0), "agent": (3.0, 64.0)})


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

    def test_latest_runner_states_keeps_same_named_runners_from_different_registration_scopes(self):
        """Two runners that share a name but not a registration scope are
        distinct current runners (see `upsert_runner_state`) - grouping
        `latest_runner_states` on `(host, runner_name)` alone would let
        whichever one sampled later suppress the other from this list."""
        self.store.upsert_runner_state(
            "2026-09-18T00:00:00Z", "", "shared-name", state="idle", repo="almostrealism/common",
        )
        self.store.upsert_runner_state(
            "2026-09-18T00:05:00Z", "", "shared-name", state="busy", repo="org:almostrealism",
        )
        rows = self.store.latest_runner_states()
        # (ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version)
        by_repo = {row[5]: row[4] for row in rows}
        self.assertEqual({"almostrealism/common": "idle", "org:almostrealism": "busy"}, by_repo)

    def test_latest_runner_states_excludes_the_empty_inventory_heartbeat(self):
        """`github_poller.store_runner_states` writes a `runner_name=''`
        heartbeat row on a successful zero-runner poll cycle purely to
        advance `MAX(ts)` - it is not itself a runner, and must not appear
        in the listing `fleetctl list` renders."""
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "", "", labels="[]", state="", repo="")
        rows = self.store.latest_runner_states()
        self.assertEqual([], rows)

    def test_latest_runner_states_excludes_heartbeat_but_keeps_real_runners(self):
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "", "", labels="[]", state="", repo="")
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "", "runner-1", state="idle", repo="acme/repo")
        rows = self.store.latest_runner_states()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][2], "runner-1")

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

    def test_utilization_by_class_filters_by_host(self):
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "host-a", "runner", 10.0, 100.0)
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "host-b", "runner", 90.0, 900.0)
        rows = self.store.utilization_by_class(host="host-a")
        self.assertEqual(len(rows), 1)
        host, cls, avg_cpu, avg_rss, count = rows[0]
        self.assertEqual(host, "host-a")
        self.assertAlmostEqual(avg_cpu, 10.0)

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

    def test_pre_start_latency_by_label_count_excludes_unstarted_jobs(self):
        """A queued entry-point job has no `pre_start_latency_seconds` yet
        (it is NULL until the job starts). `COUNT(*)` would include that row
        even though `AVG` skips it, making the reported count describe a
        different set of rows than the average it sits beside."""
        self.store.upsert_job_event(
            job_id="entry-1", labels="ar-ci", pre_start_latency_seconds=10.0, is_entry_point=True,
        )
        self.store.upsert_job_event(
            job_id="entry-2", labels="ar-ci", pre_start_latency_seconds=None, is_entry_point=True,
        )
        rows = self.store.pre_start_latency_by_label()
        self.assertEqual(len(rows), 1)
        labels, avg_latency, count = rows[0]
        self.assertEqual(count, 1)
        self.assertAlmostEqual(avg_latency, 10.0)

    def test_pre_start_latency_by_label_filters_by_labels(self):
        self.store.upsert_job_event(
            job_id="entry-1", labels="ar-ci", pre_start_latency_seconds=10.0, is_entry_point=True,
        )
        self.store.upsert_job_event(
            job_id="entry-2", labels="ar-ci-cl", pre_start_latency_seconds=40.0, is_entry_point=True,
        )
        rows = self.store.pre_start_latency_by_label(labels="ar-ci-cl")
        self.assertEqual(len(rows), 1)
        labels, avg_latency, count = rows[0]
        self.assertEqual(labels, "ar-ci-cl")
        self.assertAlmostEqual(avg_latency, 40.0)


class FleetStoreContextManagerTests(unittest.TestCase):

    def test_context_manager_closes_the_connection_on_exit(self):
        with FleetStore(":memory:") as store:
            store.init_schema()
            store.upsert_host_sample("2026-09-18T00:00:00Z", "mac-studio", cpu_pct=1.0)
        with self.assertRaises(Exception):
            store._conn.execute("SELECT 1")


class FleetStoreTransactionBatchingTests(unittest.TestCase):
    """`transaction()` exists so a poll cycle performing many upserts (one
    `job_event` per job plus one `job_step` per step) commits once instead of
    once per row. These tests exercise the batching contract directly against
    a file-backed store, since a second connection to the same sqlite file is
    the only way to observe whether an intermediate write was actually
    committed rather than merely visible on the writer's own connection."""

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self._tmpdir.name, "fleet.db")
        self.store = FleetStore(self.db_path)
        self.store.init_schema()

    def tearDown(self):
        self.store.close()
        self._tmpdir.cleanup()

    def _count_via_second_connection(self, table):
        other = FleetStore(self.db_path)
        try:
            return other._conn.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0]
        finally:
            other.close()

    def test_writes_inside_a_transaction_are_not_visible_until_it_commits(self):
        with self.store.transaction():
            self.store.upsert_job_event(job_id="job-1")
            self.store.upsert_job_event(job_id="job-2")
            # Still inside the batch: nothing has been committed yet, so a
            # second connection to the same file must see zero rows.
            self.assertEqual(self._count_via_second_connection("job_event"), 0)
        # The block exited normally: exactly one commit for the whole batch.
        self.assertEqual(self._count_via_second_connection("job_event"), 2)

    def test_transaction_rolls_back_every_write_in_the_block_on_exception(self):
        """An error partway through a poll cycle must not leave a partially
        written cycle behind for a concurrent reader to observe."""
        with self.assertRaises(RuntimeError):
            with self.store.transaction():
                self.store.upsert_job_event(job_id="job-1")
                raise RuntimeError("simulated failure mid-cycle")
        self.assertEqual(self.store.job_event_count(), 0)
        self.assertEqual(self._count_via_second_connection("job_event"), 0)

    def test_nested_transactions_commit_once_at_the_outermost_exit(self):
        with self.store.transaction():
            with self.store.transaction():
                self.store.upsert_job_event(job_id="job-1")
            # Inner block exited but the outer one is still open.
            self.assertEqual(self._count_via_second_connection("job_event"), 0)
        self.assertEqual(self._count_via_second_connection("job_event"), 1)

    def test_upserts_outside_a_transaction_still_commit_immediately(self):
        """The default (no batch open) behaviour must be unchanged: a
        standalone upsert is visible to another connection right away."""
        self.store.upsert_job_event(job_id="job-1")
        self.assertEqual(self._count_via_second_connection("job_event"), 1)

    def test_nested_transaction_failure_caught_by_caller_does_not_silently_commit_partial_cycle(self):
        """A nested `transaction()` block is scoped to a SAVEPOINT: its
        rollback undoes only its own writes, not writes an enclosing block
        already made. If a caller catches the nested block's exception and
        keeps going, the outer block must still commit its own writes made
        both before and after the nested failure - a connection-wide
        rollback would have silently erased the earlier ones too."""
        with self.store.transaction():
            self.store.upsert_job_event(job_id="before")
            try:
                with self.store.transaction():
                    self.store.upsert_job_event(job_id="nested")
                    raise RuntimeError("simulated nested failure")
            except RuntimeError:
                pass
            self.store.upsert_job_event(job_id="after")
        ids = {row[0] for row in self.store._conn.execute("SELECT job_id FROM job_event")}
        self.assertEqual(ids, {"before", "after"})


class FleetStoreSchemaMigrationTests(unittest.TestCase):
    """`init_schema` must bring a store created before a column existed up to
    the current writers - `CREATE TABLE IF NOT EXISTS` alone never would."""

    def _store_with_old_job_event_and_runner_state(self):
        store = FleetStore(":memory:")
        store._conn.execute(
            "CREATE TABLE job_event (job_id TEXT PRIMARY KEY, run_id TEXT, repo TEXT, name TEXT, labels TEXT, "
            "created_at TEXT, started_at TEXT, completed_at TEXT, status TEXT, conclusion TEXT, "
            "runner_name TEXT, runner_group TEXT, pre_start_latency_seconds REAL, is_entry_point INTEGER, "
            "queue_wait_seconds REAL)"
        )
        store._conn.execute(
            "CREATE TABLE runner_state (ts TEXT NOT NULL, host TEXT NOT NULL, runner_name TEXT NOT NULL, "
            "labels TEXT, state TEXT, repo TEXT, workflow TEXT, job_id TEXT, agent_version TEXT, "
            "PRIMARY KEY (ts, host, runner_name))"
        )
        store._conn.execute("INSERT INTO job_event (job_id, name) VALUES ('1', 'old')")
        return store

    def test_columns_lists_a_tables_columns_in_order(self):
        store = FleetStore(":memory:")
        store.init_schema()
        self.assertEqual(["ts", "host", "class", "cpu_pct", "rss_mb"], store.columns("class_sample"))
        self.assertEqual([], store.columns("no_such_table"))

    def test_init_schema_adds_the_missing_columns_to_an_existing_table(self):
        store = self._store_with_old_job_event_and_runner_state()
        self.assertNotIn("lane", store.columns("job_event"))
        store.init_schema()
        self.assertIn("lane", store.columns("job_event"))
        self.assertIn("platform", store.columns("job_event"))
        self.assertIn("lane", store.columns("runner_state"))
        self.assertIn("platform", store.columns("runner_state"))
        row = store._conn.execute("SELECT name, lane FROM job_event WHERE job_id = '1'").fetchone()
        self.assertEqual(("old", None), row)

    def test_init_schema_adds_the_runner_id_column_to_an_existing_job_event_table(self):
        store = self._store_with_old_job_event_and_runner_state()
        self.assertNotIn("runner_id", store.columns("job_event"))
        store.init_schema()
        self.assertIn("runner_id", store.columns("job_event"))
        store.upsert_job_event(job_id="3", runner_id=42)
        row = store._conn.execute("SELECT runner_id FROM job_event WHERE job_id = '3'").fetchone()
        self.assertEqual((42,), row)

    def test_a_migrated_store_accepts_the_current_writers(self):
        store = self._store_with_old_job_event_and_runner_state()
        store.init_schema()
        store.upsert_job_event(job_id="2", labels='["ar-ci"]', lane="ar-ci", platform="macos")
        store.upsert_runner_state(ts="2026-09-21T00:00:00Z", host="", runner_name="r", lane="ar-ci", platform="linux")
        self.assertEqual(
            ("ar-ci", "macos"),
            store._conn.execute("SELECT lane, platform FROM job_event WHERE job_id = '2'").fetchone(),
        )
        self.assertEqual(
            ("ar-ci", "linux"),
            store._conn.execute("SELECT lane, platform FROM runner_state WHERE runner_name = 'r'").fetchone(),
        )

    def test_init_schema_is_idempotent_after_migrating(self):
        store = self._store_with_old_job_event_and_runner_state()
        store.init_schema()
        before = store.columns("job_event")
        store.init_schema()
        self.assertEqual(before, store.columns("job_event"))

    def test_a_fresh_store_and_a_migrated_store_have_the_same_columns(self):
        fresh = FleetStore(":memory:")
        fresh.init_schema()
        migrated = self._store_with_old_job_event_and_runner_state()
        migrated.init_schema()
        for table in ("job_event", "runner_state"):
            self.assertEqual(sorted(fresh.columns(table)), sorted(migrated.columns(table)), table)

    def test_a_fresh_store_already_has_the_widened_runner_state_key(self):
        store = FleetStore(":memory:")
        store.init_schema()
        self.assertEqual(["ts", "host", "runner_name", "repo"], store._primary_key_columns("runner_state"))

    def test_init_schema_widens_an_old_runner_state_primary_key(self):
        store = self._store_with_old_job_event_and_runner_state()
        self.assertEqual(["ts", "host", "runner_name"], store._primary_key_columns("runner_state"))
        store.init_schema()
        self.assertEqual(["ts", "host", "runner_name", "repo"], store._primary_key_columns("runner_state"))

    def test_widening_the_runner_state_key_preserves_existing_rows(self):
        store = self._store_with_old_job_event_and_runner_state()
        store._conn.execute(
            "INSERT INTO runner_state (ts, host, runner_name, state, repo) "
            "VALUES ('2026-09-21T00:00:00Z', '', 'r1', 'idle', 'almostrealism/common')"
        )
        store.init_schema()
        row = store._conn.execute(
            "SELECT runner_name, state, repo FROM runner_state WHERE runner_name = 'r1'"
        ).fetchone()
        self.assertEqual(("r1", "idle", "almostrealism/common"), row)

    def test_widening_the_runner_state_key_coalesces_a_legacy_null_repo(self):
        """The legacy table's `repo` column had no `NOT NULL` constraint,
        but the widened table's does (`repo` is now part of the primary
        key). A legacy row with `repo IS NULL` must not make the migration's
        copy step fail - it is coalesced to `''`, the same default a fresh
        write without a `repo` uses."""
        store = self._store_with_old_job_event_and_runner_state()
        store._conn.execute(
            "INSERT INTO runner_state (ts, host, runner_name, state, repo) "
            "VALUES ('2026-09-21T00:00:00Z', '', 'r-legacy', 'idle', NULL)"
        )
        store.init_schema()
        row = store._conn.execute(
            "SELECT runner_name, state, repo FROM runner_state WHERE runner_name = 'r-legacy'"
        ).fetchone()
        self.assertEqual(("r-legacy", "idle", ""), row)

    def test_widening_the_runner_state_key_recreates_its_indexes(self):
        """sqlite drops an index along with the table it is on; the
        migration renames the old `runner_state` away (taking
        `runner_state_host_ts`/`runner_state_ts` with it) and drops it once
        the data is copied, so the two indexes must be recreated against the
        new table rather than silently lost."""
        store = self._store_with_old_job_event_and_runner_state()
        store.init_schema()
        index_names = {
            row[0] for row in store._conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'runner_state'"
            )
        }
        self.assertIn("runner_state_host_ts", index_names)
        self.assertIn("runner_state_ts", index_names)

    def test_init_schema_is_idempotent_after_widening_the_runner_state_key(self):
        store = self._store_with_old_job_event_and_runner_state()
        store.init_schema()
        before = store._primary_key_columns("runner_state")
        store.init_schema()
        self.assertEqual(before, store._primary_key_columns("runner_state"))

    def test_add_column_if_missing_uses_atomic_syntax_on_postgres(self):
        """The Postgres branch must be a single `ADD COLUMN IF NOT EXISTS`
        statement, not a check-then-`ALTER` - see `_add_column_if_missing`'s
        docstring for why a check-then-`ALTER` races when the collector and
        the poller both call `init_schema()` against the same store."""
        connection = _FakePostgresConnection(primary_key_columns=["ts", "host", "runner_name", "repo"])
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._add_column_if_missing("job_event", "lane", "TEXT")
        self.assertIn("ALTER TABLE job_event ADD COLUMN IF NOT EXISTS lane TEXT", connection.statements)

    def test_widen_runner_state_key_migrates_an_old_postgres_primary_key(self):
        connection = _FakePostgresConnection(primary_key_columns=["ts", "host", "runner_name"])
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._widen_runner_state_key()
        self.assertIn("ALTER TABLE runner_state DROP CONSTRAINT IF EXISTS runner_state_pkey", connection.statements)
        self.assertIn(
            "ALTER TABLE runner_state ADD PRIMARY KEY (ts, host, runner_name, repo)", connection.statements
        )

    def test_widen_runner_state_key_is_a_noop_on_postgres_when_already_widened(self):
        connection = _FakePostgresConnection(primary_key_columns=["ts", "host", "runner_name", "repo"])
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._widen_runner_state_key()
        self.assertFalse(any("ALTER TABLE runner_state" in sql for sql in connection.statements))

    def test_widen_runner_state_key_reraises_a_postgres_failure_that_left_the_key_unwidened(self):
        """A real failure (bad permissions, invalid legacy data) must not be
        swallowed alongside the benign concurrent-migration race - the old
        broad `except: pass` hid both identically, which could leave
        `runner_state` on the old key (or no key at all, since the
        connection is autocommit and `DROP CONSTRAINT` can already have
        committed) while writers upsert against a key it does not have."""
        connection = _FakePostgresConnection(
            primary_key_columns=["ts", "host", "runner_name"], fail_on="ADD PRIMARY KEY",
        )
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        with self.assertRaises(RuntimeError):
            store._widen_runner_state_key()

    def test_widen_runner_state_key_swallows_a_postgres_failure_once_a_concurrent_migration_already_won(self):
        """The one case a failed `ADD PRIMARY KEY` should be swallowed: a
        concurrent process already widened the key by the time this one's
        attempt failed, so the table is already on the target key despite
        the local error."""
        connection = _FakePostgresConnection(
            primary_key_columns=["ts", "host", "runner_name"], fail_on="ADD PRIMARY KEY",
            primary_key_columns_after_fail=["ts", "host", "runner_name", "repo"],
        )
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._widen_runner_state_key()

    def test_widen_runner_state_key_backfills_null_repo_before_widening_on_postgres(self):
        """The legacy Postgres `repo` column predates `NOT NULL DEFAULT ''`
        and can hold real NULLs; `ADD PRIMARY KEY` rejects a key column that
        contains one, so the backfill must run - and run before the ADD -
        the same way the sqlite branch coalesces during its copy."""
        connection = _FakePostgresConnection(primary_key_columns=["ts", "host", "runner_name"])
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._widen_runner_state_key()
        backfill = "UPDATE runner_state SET repo = '' WHERE repo IS NULL"
        add_key = "ALTER TABLE runner_state ADD PRIMARY KEY (ts, host, runner_name, repo)"
        self.assertIn(backfill, connection.statements)
        self.assertLess(connection.statements.index(backfill), connection.statements.index(add_key))

    def test_widen_runner_state_key_wraps_the_postgres_statements_in_one_transaction(self):
        """Autocommit would let `DROP CONSTRAINT` land on its own, leaving
        `runner_state` with no primary key at all until `ADD PRIMARY KEY`
        runs next; wrapping both in one explicit transaction closes that
        window and lets a concurrent migration serialize on the resulting
        table-level lock instead of racing it."""
        connection = _FakePostgresConnection(primary_key_columns=["ts", "host", "runner_name"])
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        store._widen_runner_state_key()
        self.assertEqual("COMMIT", connection.statements[-1])
        self.assertLess(connection.statements.index("BEGIN"), connection.statements.index("COMMIT"))
        self.assertNotIn("ROLLBACK", connection.statements)

    def test_widen_runner_state_key_rolls_back_the_transaction_on_a_real_postgres_failure(self):
        connection = _FakePostgresConnection(
            primary_key_columns=["ts", "host", "runner_name"], fail_on="ADD PRIMARY KEY",
        )
        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=connection)
        with self.assertRaises(RuntimeError):
            store._widen_runner_state_key()
        self.assertIn("ROLLBACK", connection.statements)
        self.assertNotIn("COMMIT", connection.statements)


if __name__ == "__main__":
    unittest.main()
