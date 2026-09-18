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
"""Tests for ``tools.fleet.collector.build_record``.

Exercises the pure, host-independent half of the metrics agent: given a
captured `ps` snapshot, does the record carry ppid/user per process (which
the current ``tools/ci/monitor/ar-host-monitor.sh`` does not) and the
runner/agent/other class split, without ever needing a live process tree.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_collector.py"
"""

import json
import os
import tempfile
import unittest

from tools.fleet import collector

PS_TEXT = "\n".join([
    "  PID  PPID USER     %CPU    RSS COMMAND",
    "     1     0 root      0.0   1024 launchd",
    "   100     1 runner-svc 5.0 204800 Runner.Listener",
    "   101   100 runner-svc 40.0 512000 Runner.Worker",
])


class BuildRecordTests(unittest.TestCase):

    def test_record_carries_ppid_and_user_per_process(self):
        record = collector.build_record("2026-09-18T00:00:00Z", "mac-studio", PS_TEXT)
        by_pid = {p["pid"]: p for p in record["procs"]}
        self.assertEqual(by_pid[101]["ppid"], 100)
        self.assertEqual(by_pid[101]["user"], "runner-svc")

    def test_record_never_carries_full_argv_only_basename(self):
        # ps -eo comm reports a path, never arguments; the parser must still
        # reduce it to a basename, never a token-bearing argv.
        line = "1 0 root 0.0 1024 /usr/local/bin/Runner.Worker"
        record = collector.build_record("2026-09-18T00:00:00Z", "host", line)
        self.assertEqual(record["procs"][0]["comm"], "Runner.Worker")

    def test_record_classifies_runner_subtree(self):
        record = collector.build_record("2026-09-18T00:00:00Z", "mac-studio", PS_TEXT)
        by_pid = {p["pid"]: p for p in record["procs"]}
        self.assertEqual(by_pid[100]["class"], "runner")
        self.assertEqual(by_pid[101]["class"], "runner")
        self.assertEqual(by_pid[1]["class"], "other")

    def test_class_totals_present_for_all_three_classes(self):
        record = collector.build_record("2026-09-18T00:00:00Z", "mac-studio", PS_TEXT)
        self.assertEqual(set(record["class_totals"].keys()), {"runner", "agent", "other"})
        self.assertAlmostEqual(record["class_totals"]["runner"]["cpu_pct"], 45.0)

    def test_write_jsonl_appends_one_line_per_record(self):
        record = collector.build_record("2026-09-18T00:00:00Z", "mac-studio", PS_TEXT)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sample.jsonl")
            collector.write_jsonl(record, path)
            collector.write_jsonl(record, path)
            with open(path, "r", encoding="utf-8") as handle:
                lines = handle.readlines()
            self.assertEqual(len(lines), 2)
            parsed = json.loads(lines[0])
            self.assertEqual(parsed["host"], "mac-studio")

    def test_record_honours_agent_root_pid_when_comm_is_generic(self):
        """Both launchers `exec java`, so name-based matching alone can never
        find the agent; a caller passing the discovered root PID must still
        get its subtree classified `agent`."""
        ps_text = "\n".join([
            "  PID  PPID USER     %CPU    RSS COMMAND",
            "   200     1 worker    2.0   4096 java",
            "   201   200 worker    8.0   1024 claude",
        ])
        record = collector.build_record(
            "2026-09-18T00:00:00Z", "mac-studio", ps_text, agent_root_pids={200},
        )
        by_pid = {p["pid"]: p for p in record["procs"]}
        self.assertEqual(by_pid[200]["class"], "agent")
        self.assertEqual(by_pid[201]["class"], "agent")


class UptimeLoadParsingTests(unittest.TestCase):

    def test_linux_comma_separated_loads_are_parsed(self):
        text = " 10:00:00 up 1 day,  2:14,  1 user,  load average: 0.10, 0.05, 0.01"
        self.assertEqual(collector.parse_uptime_loads(text), [0.10, 0.05, 0.01])

    def test_macos_space_separated_loads_are_parsed(self):
        text = "10:00  up 3 days,  2:14, 3 users, load averages: 1.23 1.10 0.95"
        self.assertEqual(collector.parse_uptime_loads(text), [1.23, 1.10, 0.95])

    def test_missing_marker_yields_all_none(self):
        self.assertEqual(collector.parse_uptime_loads("unexpected output"), [None, None, None])


class LaunchctlListParsingTests(unittest.TestCase):

    def test_running_service_pid_is_found(self):
        text = "1234\t0\tcom.almostrealism.flowtree-agent\n5\t-\tcom.apple.something\n"
        self.assertEqual(collector.parse_launchctl_list(text), 1234)

    def test_not_running_service_yields_none(self):
        text = "-\t0\tcom.almostrealism.flowtree-agent\n"
        self.assertIsNone(collector.parse_launchctl_list(text))

    def test_unregistered_label_yields_none(self):
        text = "1\t0\tcom.apple.something\n"
        self.assertIsNone(collector.parse_launchctl_list(text))


if __name__ == "__main__":
    unittest.main()
