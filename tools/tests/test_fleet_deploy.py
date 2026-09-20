#!/usr/bin/env python3
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
"""Tests for the pieces that connect the fleet collector and poller to a store.

Covers the store's dialect handling and URL construction, the collector's
process filter and store write (with reconnect after a failed write), the
poller's scheduled loop, and the credential-file guard. Everything runs on
sqlite; the Postgres path is exercised only as far as SQL rendering, since
the test job has no database and no ``psycopg``.
"""

import os
import stat
import tempfile
import unittest
from unittest import mock

from tools.fleet import cli, collector, credentials, github_poller, schema
from tools.fleet.store import Dialect, FleetStore


class DialectTests(unittest.TestCase):

    def test_sqlite_dialect_keeps_question_marks_and_text_timestamps(self):
        d = Dialect(Dialect.SQLITE)
        self.assertEqual("?", d.placeholder)
        self.assertEqual("TEXT", d.timestamp_type)
        self.assertEqual("SELECT ? , ?", d.sql("SELECT ? , ?"))

    def test_postgres_dialect_rewrites_placeholders_and_uses_timestamptz(self):
        d = Dialect(Dialect.POSTGRES)
        self.assertEqual("%s", d.placeholder)
        self.assertEqual("TIMESTAMPTZ", d.timestamp_type)
        self.assertEqual("INSERT INTO t (a, b) VALUES (%s, %s)", d.sql("INSERT INTO t (a, b) VALUES (?, ?)"))

    def test_unknown_dialect_is_rejected(self):
        with self.assertRaises(ValueError):
            Dialect("mysql")

    def test_schema_statements_render_timestamp_type_and_include_indexes(self):
        sqlite_ddl = schema.statements("TEXT")
        pg_ddl = schema.statements("TIMESTAMPTZ")
        self.assertEqual(len(schema.TABLES) + len(schema.INDEXES), len(sqlite_ddl))
        self.assertIn("ts TEXT NOT NULL", sqlite_ddl[0])
        self.assertIn("ts TIMESTAMPTZ NOT NULL", pg_ddl[0])
        self.assertTrue(all("{ts}" not in s for s in pg_ddl))
        self.assertEqual(schema.ALL_STATEMENTS, sqlite_ddl)


class StoreUrlTests(unittest.TestCase):

    def test_sqlite_url_opens_a_file_store(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "fleet.db")
            with FleetStore.from_url("sqlite:///" + path) as store:
                store.init_schema()
                store.upsert_class_sample("2026-01-01T00:00:00Z", "h", "runner", 1.0, 2.0)
            self.assertTrue(os.path.exists(path))
            with FleetStore.from_url(path) as reopened:  # bare path is sqlite too
                self.assertEqual(1, len(reopened.utilization_by_class()))

    def test_memory_url(self):
        with FleetStore.from_url("sqlite:///:memory:") as store:
            self.assertEqual(Dialect.SQLITE, store.dialect.name)

    def test_unknown_scheme_is_rejected(self):
        with self.assertRaises(ValueError):
            FleetStore.from_url("mysql://x")

    def test_unknown_scheme_error_never_includes_the_credential(self):
        """A mistyped Postgres DSN is exactly the shape an unsupported-scheme
        URL takes; the error must name only the scheme, never echo the whole
        URL (and its embedded password) into a message a caller might log."""
        with self.assertRaises(ValueError) as ctx:
            FleetStore.from_url("mysql://user:hunter2@host/db")
        self.assertIn("mysql", str(ctx.exception))
        self.assertNotIn("hunter2", str(ctx.exception))
        self.assertNotIn("user:hunter2@host", str(ctx.exception))

    def test_empty_url_is_rejected_rather_than_opening_a_throwaway_sqlite_db(self):
        """`sqlite3.connect("")` silently opens an unnamed temporary database
        that vanishes on close - an empty URL (e.g. from a blank credential
        file) must fail loudly instead of looking like a working store."""
        with self.assertRaises(ValueError):
            FleetStore.from_url("")

    def test_whitespace_only_url_is_rejected(self):
        with self.assertRaises(ValueError):
            FleetStore.from_url("   ")

    def test_postgres_url_reports_missing_driver_clearly(self):
        with mock.patch.dict("sys.modules", {"psycopg": None}):
            with self.assertRaises(RuntimeError) as ctx:
                FleetStore.from_url("postgresql://u:p@h/db")
        self.assertIn("psycopg", str(ctx.exception))

    def test_postgres_store_issues_explicit_transactions_and_percent_placeholders(self):
        """The Postgres path is exercised against a recording fake connection."""
        executed = []

        class FakeConn:
            def execute(self, sql, params=()):
                executed.append((sql, tuple(params)))
                return iter(())

            def close(self):
                pass

        store = FleetStore(dialect=Dialect(Dialect.POSTGRES), connection=FakeConn())
        store.init_schema()
        with store.transaction():
            store.upsert_class_sample("2026-01-01T00:00:00Z", "h", "agent", 3.0, 4.0)
        statements = [sql for sql, _ in executed]
        self.assertTrue(any("TIMESTAMPTZ" in s for s in statements))
        self.assertIn("BEGIN", statements)
        self.assertIn("COMMIT", statements)
        upsert = [s for s in statements if s.startswith("INSERT INTO class_sample")][0]
        self.assertIn("%s", upsert)
        self.assertNotIn("?", upsert)
        self.assertIn("ON CONFLICT (ts, host, class) DO UPDATE SET cpu_pct = excluded.cpu_pct", upsert)

    def test_upsert_replaces_on_key_in_sqlite(self):
        with FleetStore() as store:
            store.init_schema()
            store.upsert_host_sample("2026-01-01T00:00:00Z", "h", cpu_pct=1.0)
            store.upsert_host_sample("2026-01-01T00:00:00Z", "h", cpu_pct=9.0)
            rows = store._rows("SELECT cpu_pct FROM host_sample")
            self.assertEqual([(9.0,)], rows)


def _record(procs):
    return {
        "ts": "2026-01-01T00:00:00Z",
        "host": "h",
        "load": [1.0, 2.0, 3.0],
        "host_metrics": {
            "cpu_pct": 10.0, "mem_used_mb": 1.0, "mem_total_mb": 2.0,
            "disk_used_gb": 3.0, "disk_total_gb": 4.0, "thermal_c": None, "throttled": None,
        },
        "class_totals": {
            "runner": {"cpu_pct": 5.0, "rss_mb": 100.0, "process_count": 1},
            "agent": {"cpu_pct": 0.0, "rss_mb": 50.0, "process_count": 1},
            "other": {"cpu_pct": 300.0, "rss_mb": 9000.0, "process_count": 3},
        },
        "procs": procs,
    }


class FilterProcsTests(unittest.TestCase):

    def test_keeps_classified_processes_and_busy_others_only(self):
        procs = [
            {"pid": 1, "class": "runner", "cpu": 0.0, "rss_mb": 1.0},
            {"pid": 2, "class": "agent", "cpu": 0.0, "rss_mb": 1.0},
            {"pid": 3, "class": "other", "cpu": 0.1, "rss_mb": 1.0},      # idle: dropped
            {"pid": 4, "class": "other", "cpu": 50.0, "rss_mb": 1.0},     # busy CPU: kept
            {"pid": 5, "class": "other", "cpu": 0.0, "rss_mb": 500.0},    # big RSS: kept
            {"pid": 6, "class": "other", "cpu": None, "rss_mb": None},    # unknown: dropped
        ]
        filtered = collector.filter_procs(_record(procs))
        self.assertEqual([1, 2, 4, 5], [p["pid"] for p in filtered["procs"]])

    def test_totals_are_untouched_by_the_filter(self):
        record = _record([{"pid": 3, "class": "other", "cpu": 0.1, "rss_mb": 1.0}])
        filtered = collector.filter_procs(record)
        self.assertEqual(record["class_totals"], filtered["class_totals"])
        self.assertEqual(record["host_metrics"], filtered["host_metrics"])
        self.assertEqual(1, len(record["procs"]))  # the original is not mutated

    def test_thresholds_are_configurable(self):
        procs = [{"pid": 3, "class": "other", "cpu": 2.0, "rss_mb": 10.0}]
        self.assertEqual(0, len(collector.filter_procs(_record(procs))["procs"]))
        self.assertEqual(1, len(collector.filter_procs(_record(procs), cpu_threshold=1.0)["procs"]))
        self.assertEqual(1, len(collector.filter_procs(_record(procs), rss_threshold_mb=5.0)["procs"]))


class StoreRecordTests(unittest.TestCase):

    def test_writes_host_and_class_rows(self):
        with FleetStore() as store:
            store.init_schema()
            collector.store_record(store, _record([]))
            host_rows = store._rows("SELECT ts, host, cpu_pct, load1, load15 FROM host_sample")
            self.assertEqual([("2026-01-01T00:00:00Z", "h", 10.0, 1.0, 3.0)], host_rows)
            self.assertEqual(3, len(store.utilization_by_class("h")))

    def test_sample_and_write_writes_jsonl_before_store_and_filters_the_jsonl(self):
        procs = [
            {"pid": 1, "class": "runner", "cpu": 0.0, "rss_mb": 1.0},
            {"pid": 3, "class": "other", "cpu": 0.1, "rss_mb": 1.0},
        ]
        written = []
        with tempfile.TemporaryDirectory() as tmp, FleetStore() as store:
            store.init_schema()
            path = os.path.join(tmp, "x.jsonl")
            with mock.patch.object(collector, "_run_ps", return_value=""), \
                 mock.patch.object(collector, "_run_uptime_loads", return_value=[None, None, None]), \
                 mock.patch.object(collector, "discover_macos_agent_pid", return_value=None), \
                 mock.patch.object(collector, "collect_host_cpu_pct", return_value=None), \
                 mock.patch.object(collector, "collect_host_memory_mb", return_value=(None, None)), \
                 mock.patch.object(collector, "collect_disk_usage", return_value=(None, None)), \
                 mock.patch.object(collector, "build_record", return_value=_record(procs)), \
                 mock.patch.object(collector, "write_jsonl", side_effect=lambda r, p: written.append(r)):
                collector.sample_and_write("h", path, store=store)
            self.assertEqual([1], [p["pid"] for p in written[0]["procs"]])
            self.assertEqual(1, len(store._rows("SELECT * FROM host_sample")))


class SamplingLoopStoreTests(unittest.TestCase):

    def _patch_sampling(self):
        return mock.patch.object(collector, "sample_and_write", autospec=True)

    def test_loop_opens_store_lazily_and_passes_it_to_each_sample(self):
        opened = []

        def open_store(url):
            opened.append(url)
            store = FleetStore()
            return store

        with tempfile.TemporaryDirectory() as tmp, self._patch_sampling() as sample:
            collector.run_sampling_loop(
                "h", tmp, interval_seconds=0, iterations=2, store_url="sqlite:///:memory:", open_store=open_store,
            )
        self.assertEqual(["sqlite:///:memory:"], opened)
        self.assertEqual(2, sample.call_count)
        self.assertIsNotNone(sample.call_args.kwargs["store"])

    def test_loop_survives_an_unavailable_store_and_keeps_sampling(self):
        def open_store(url):
            raise ConnectionError("db down")

        with tempfile.TemporaryDirectory() as tmp, self._patch_sampling() as sample:
            collector.run_sampling_loop(
                "h", tmp, interval_seconds=0, iterations=2, store_url="postgresql://x", open_store=open_store,
            )
        self.assertEqual(2, sample.call_count)
        self.assertIsNone(sample.call_args.kwargs["store"])

    def test_loop_reconnects_after_a_failed_write(self):
        stores = []

        def open_store(url):
            store = FleetStore()
            stores.append(store)
            return store

        calls = {"n": 0}

        def sample(*args, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:
                raise collector.StoreWriteError("connection lost")
            return {}

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(collector, "sample_and_write", side_effect=sample):
            collector.run_sampling_loop(
                "h", tmp, interval_seconds=0, iterations=2, store_url="sqlite:///:memory:", open_store=open_store,
            )
        self.assertEqual(2, len(stores))  # the first store was dropped, a second opened

    def test_a_failure_without_a_store_still_propagates(self):
        with tempfile.TemporaryDirectory() as tmp, \
             mock.patch.object(collector, "sample_and_write", side_effect=RuntimeError("ps exploded")):
            with self.assertRaises(RuntimeError):
                collector.run_sampling_loop("h", tmp, interval_seconds=0, iterations=1)

    def test_a_non_store_failure_propagates_and_does_not_trigger_reconnect(self):
        """Only `StoreWriteError` should be treated as "the store connection
        needs reopening" - a collection or JSONL bug (any other exception)
        must not be misreported as a store outage, even when a store is
        configured and open."""

        def open_store(url):
            return FleetStore()

        with tempfile.TemporaryDirectory() as tmp, \
             mock.patch.object(collector, "sample_and_write", side_effect=RuntimeError("collection bug")):
            with self.assertRaises(RuntimeError):
                collector.run_sampling_loop(
                    "h", tmp, interval_seconds=0, iterations=2,
                    store_url="sqlite:///:memory:", open_store=open_store,
                )

    def test_loop_closes_the_store_when_init_schema_fails_after_a_successful_open(self):
        """`open_store` succeeding but `init_schema` failing must not leak
        the just-opened connection - regression test for a leak where the
        exception handler discarded `store` without closing it first."""
        closed = []

        class FailingStore:
            def init_schema(self):
                raise RuntimeError("schema init failed")

            def close(self):
                closed.append(True)

        def open_store(url):
            return FailingStore()

        with tempfile.TemporaryDirectory() as tmp, self._patch_sampling() as sample:
            collector.run_sampling_loop(
                "h", tmp, interval_seconds=0, iterations=1, store_url="sqlite:///:memory:", open_store=open_store,
            )
        self.assertEqual([True], closed)
        self.assertIsNone(sample.call_args.kwargs["store"])


class SampleAndWriteStoreErrorTests(unittest.TestCase):
    """`sample_and_write` must translate a store-write failure into
    `StoreWriteError` (what `run_sampling_loop` catches for its reconnect
    logic) while still having already written the JSONL fallback line."""

    def test_store_write_failure_raises_store_write_error_after_jsonl_is_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "x.jsonl")
            broken_store = mock.Mock()
            broken_store.transaction.side_effect = RuntimeError("db gone")
            with mock.patch.object(collector, "_run_ps", return_value=""), \
                 mock.patch.object(collector, "_run_uptime_loads", return_value=[None, None, None]), \
                 mock.patch.object(collector, "discover_macos_agent_pid", return_value=None), \
                 mock.patch.object(collector, "collect_host_cpu_pct", return_value=None), \
                 mock.patch.object(collector, "collect_host_memory_mb", return_value=(None, None)), \
                 mock.patch.object(collector, "collect_disk_usage", return_value=(None, None)):
                with self.assertRaises(collector.StoreWriteError):
                    collector.sample_and_write("h", path, store=broken_store)
            with open(path, "r", encoding="utf-8") as handle:
                self.assertEqual(1, len(handle.readlines()))


class CredentialFileTests(unittest.TestCase):

    def test_reads_a_private_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            with open(path, "w") as handle:
                handle.write("secret\n")
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
            self.assertEqual("secret", credentials.read_secret_file(path))

    def test_refuses_a_file_others_can_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            with open(path, "w") as handle:
                handle.write("secret\n")
            os.chmod(path, 0o644)
            with self.assertRaises(PermissionError):
                credentials.read_secret_file(path)

    def test_refuses_an_empty_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            open(path, "w").close()
            os.chmod(path, 0o600)
            with self.assertRaises(ValueError):
                credentials.read_secret_file(path)

    def test_refuses_a_whitespace_only_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            with open(path, "w") as handle:
                handle.write("   \n\n")
            os.chmod(path, 0o600)
            with self.assertRaises(ValueError):
                credentials.read_secret_file(path)


class RejectPostgresUrlOnCommandLineTests(unittest.TestCase):

    def test_none_is_allowed(self):
        credentials.reject_postgres_url_on_command_line(None, "--store-url")  # must not raise

    def test_a_sqlite_url_is_allowed(self):
        credentials.reject_postgres_url_on_command_line("sqlite:///fleet.db", "--store-url")

    def test_a_bare_sqlite_path_is_allowed(self):
        credentials.reject_postgres_url_on_command_line("fleet.db", "--db")

    def test_a_postgresql_scheme_url_is_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            credentials.reject_postgres_url_on_command_line("postgresql://u:p@h/db", "--store-url")
        self.assertIn("--store-url", str(ctx.exception))

    def test_a_postgres_scheme_url_is_rejected(self):
        with self.assertRaises(ValueError):
            credentials.reject_postgres_url_on_command_line("postgres://u:p@h/db", "--db")


class PollLoopTests(unittest.TestCase):

    def test_polls_each_cycle_with_one_store(self):
        opened = []

        def open_store(url):
            opened.append(url)
            return FleetStore()

        polled = []

        def poll(repo, token, store, max_runs=None):
            polled.append((repo, token, max_runs))
            return 7

        stored = github_poller.run_poll_loop(
            "o/r", "tok", "sqlite:///:memory:", interval_seconds=0, iterations=3,
            max_runs=5, open_store=open_store, poll=poll,
        )
        self.assertEqual(3, stored)
        self.assertEqual(1, len(opened))
        self.assertEqual([("o/r", "tok", 5)] * 3, polled)

    def test_a_failed_cycle_reconnects_and_the_loop_continues(self):
        opened = []

        def open_store(url):
            opened.append(url)
            return FleetStore()

        outcomes = iter([RuntimeError("github 500"), 3])

        def poll(repo, token, store, max_runs=None):
            outcome = next(outcomes)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        stored = github_poller.run_poll_loop(
            "o/r", "tok", "sqlite:///:memory:", interval_seconds=0, iterations=2, open_store=open_store, poll=poll,
        )
        self.assertEqual(1, stored)
        self.assertEqual(2, len(opened))

    def test_closes_the_store_when_init_schema_fails_after_a_successful_open(self):
        """`open_store` succeeding but `init_schema` failing must not leak
        the just-opened connection - regression test for a leak where the
        exception handler discarded `store` without closing it first."""
        closed = []

        class FailingStore:
            def init_schema(self):
                raise RuntimeError("schema init failed")

            def close(self):
                closed.append(True)

        def open_store(url):
            return FailingStore()

        def poll(repo, token, store, max_runs=None):
            self.fail("poll must not run when the store failed to initialize")

        stored = github_poller.run_poll_loop(
            "o/r", "tok", "sqlite:///:memory:", interval_seconds=0, iterations=1, open_store=open_store, poll=poll,
        )
        self.assertEqual(0, stored)
        self.assertEqual([True], closed)

    def test_main_requires_a_store_and_a_private_token_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            token = os.path.join(tmp, "token")
            with open(token, "w") as handle:
                handle.write("tok")
            os.chmod(token, 0o600)
            with self.assertRaises(SystemExit):
                github_poller.main(["--repo", "o/r", "--token-file", token])
            with mock.patch.object(github_poller, "run_poll_loop", return_value=1) as loop:
                rc = github_poller.main(["--repo", "o/r", "--token-file", token, "--store-url", "sqlite:///:memory:", "--once"])
            self.assertEqual(0, rc)
            self.assertEqual(("o/r", "tok", "sqlite:///:memory:"), loop.call_args.args)
            self.assertEqual(1, loop.call_args.kwargs["iterations"])

    def test_main_rejects_a_postgres_store_url_given_directly(self):
        """A Postgres credential must only reach the poller through
        --store-url-file - --store-url itself is rejected for a
        postgresql://... value, since it would otherwise appear in this
        process's command line (visible to every account via `ps`)."""
        with tempfile.TemporaryDirectory() as tmp:
            token = os.path.join(tmp, "token")
            with open(token, "w") as handle:
                handle.write("tok")
            os.chmod(token, 0o600)
            with self.assertRaises(ValueError):
                github_poller.main([
                    "--repo", "o/r", "--token-file", token,
                    "--store-url", "postgresql://u:p@h/db", "--once",
                ])


class CollectorMainCredentialGuardTests(unittest.TestCase):

    def test_main_rejects_a_postgres_store_url_given_directly(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_dir = os.path.join(tmp, "logs")
            with self.assertRaises(ValueError):
                collector.main(["--log-dir", log_dir, "--store-url", "postgresql://u:p@h/db", "--once"])


class CliMainCredentialGuardTests(unittest.TestCase):

    def test_main_rejects_a_postgres_db_argument_given_directly(self):
        with self.assertRaises(ValueError):
            cli.main(["--db", "postgresql://u:p@h/db", "status"])


if __name__ == "__main__":
    unittest.main()
