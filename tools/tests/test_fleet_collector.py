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
"""Tests for ``tools.ci.fleet.collector.build_record``.

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

from tools.ci.fleet import collector

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


if __name__ == "__main__":
    unittest.main()
