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
"""Tests for the read-only fleet CLI verbs (``tools.fleet.cli``).

This CLI has exactly two verbs — ``list`` and ``status`` — and both are
read-only against the store. These tests populate an in-memory store and
drive the same functions ``main()`` calls, so a broken query or a broken
formatter fails a test here rather than only being caught by a human staring
at CLI output.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_cli.py"
"""

import contextlib
import io
import os
import tempfile
import unittest

from tools.fleet import cli
from tools.fleet.store import FleetStore


class FleetCliTests(unittest.TestCase):

    def setUp(self):
        self.store = FleetStore(":memory:")
        self.store.init_schema()

    def tearDown(self):
        self.store.close()

    def test_list_reports_no_data_message_when_empty(self):
        self.assertIn("No runner state recorded", cli.run_list(self.store, None))

    def test_list_shows_the_latest_state_and_current_job(self):
        self.store.upsert_runner_state(
            "2026-09-18T00:00:00Z", "mac-studio", "runner-1",
            labels="ar-ci,macos", state="busy", job_id="job-99", repo="almostrealism/common",
        )
        output = cli.run_list(self.store, None)
        self.assertIn("mac-studio", output)
        self.assertIn("runner-1", output)
        self.assertIn("busy", output)
        self.assertIn("job-99", output)

    def test_list_shows_workflow_and_agent_version(self):
        """The Phase A CLI contract promises the runner listing includes the
        current job *and version* — both fields FleetStore.latest_runner_states
        already returns must actually reach the rendered output."""
        self.store.upsert_runner_state(
            "2026-09-18T00:00:00Z", "mac-studio", "runner-1",
            state="busy", job_id="job-99", workflow="analysis.yaml", agent_version="2.320.0",
        )
        output = cli.run_list(self.store, None)
        self.assertIn("analysis.yaml", output)
        self.assertIn("2.320.0", output)
        self.assertIn("WORKFLOW", output)
        self.assertIn("VERSION", output)

    def test_list_host_filter_excludes_other_hosts(self):
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "host-a", "runner-1", state="idle")
        self.store.upsert_runner_state("2026-09-18T00:00:00Z", "host-b", "runner-2", state="idle")
        output = cli.run_list(self.store, "host-a")
        self.assertIn("host-a", output)
        self.assertNotIn("host-b", output)

    def test_status_reports_no_data_message_when_empty(self):
        self.assertIn("No utilization data recorded", cli.run_status(self.store, None))

    def test_status_shows_per_class_averages(self):
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "mac-studio", "runner", 40.0, 500.0)
        self.store.upsert_class_sample("2026-09-18T00:00:00Z", "mac-studio", "agent", 20.0, 300.0)
        output = cli.run_status(self.store, None)
        self.assertIn("runner", output)
        self.assertIn("agent", output)
        self.assertIn("40.00", output)

    def test_parser_requires_a_subcommand(self):
        parser = cli.build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args([])

    def test_parser_accepts_list_with_host(self):
        parser = cli.build_parser()
        args = parser.parse_args(["list", "--host", "mac-studio"])
        self.assertEqual(args.command, "list")
        self.assertEqual(args.host, "mac-studio")


class MainEntryPointTests(unittest.TestCase):
    """Drive ``cli.main`` itself (not just the ``run_*``/formatting helpers
    the other tests exercise), so the argument parsing, file-backed store
    lifecycle, and stdout wiring that only ``main`` performs are covered
    too."""

    def test_main_runs_list_command_against_a_file_backed_store(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = os.path.join(tmp, "fleet.db")
            store = FleetStore(db_path)
            store.init_schema()
            store.upsert_runner_state(
                "2026-09-18T00:00:00Z", "mac-studio", "runner-1",
                state="busy", job_id="job-1",
            )
            store.close()

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                exit_code = cli.main(["--db", db_path, "list"])
            self.assertEqual(exit_code, 0)
            self.assertIn("runner-1", buf.getvalue())
            self.assertIn("busy", buf.getvalue())

    def test_main_runs_status_command_and_initializes_schema_on_a_fresh_db(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = os.path.join(tmp, "fleet.db")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                exit_code = cli.main(["--db", db_path, "status"])
            self.assertEqual(exit_code, 0)
            self.assertIn("No utilization data recorded", buf.getvalue())
            self.assertTrue(os.path.exists(db_path))


if __name__ == "__main__":
    unittest.main()
