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
import subprocess
import tempfile
import unittest
from unittest import mock

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


class LaunchctlPrintParsingTests(unittest.TestCase):
    """`launchctl print <domain>/<label>` addresses a specific launchd
    domain explicitly - required when the collector runs under a separate
    identity from the agent (a plain `launchctl list` only ever sees the
    caller's own domain)."""

    def test_running_service_pid_is_found(self):
        text = "\n".join([
            "gui/501/com.almostrealism.flowtree-agent = {",
            "\tactive count = 1",
            "\tpid = 5678",
            "\tstate = running",
            "}",
        ])
        self.assertEqual(collector.parse_launchctl_print(text), 5678)

    def test_stopped_service_has_no_pid_line(self):
        text = "\n".join([
            "gui/501/com.almostrealism.flowtree-agent = {",
            "\tstate = not running",
            "}",
        ])
        self.assertIsNone(collector.parse_launchctl_print(text))

    def test_discover_macos_agent_pid_uses_print_when_domain_target_given(self):
        result = mock.Mock(stdout="\tpid = 42\n")
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=result) as run:
            pid = collector.discover_macos_agent_pid(domain_target="gui/501")
        self.assertEqual(pid, 42)
        args = run.call_args[0][0]
        self.assertEqual(args[:2], ["launchctl", "print"])
        self.assertEqual(args[2], "gui/501/com.almostrealism.flowtree-agent")

    def test_discover_macos_agent_pid_uses_list_when_no_domain_target(self):
        result = mock.Mock(stdout="99\t0\tcom.almostrealism.flowtree-agent\n")
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=result) as run:
            pid = collector.discover_macos_agent_pid()
        self.assertEqual(pid, 99)
        self.assertEqual(run.call_args[0][0], ["launchctl", "list"])

    def test_discover_macos_agent_pid_returns_none_when_launchctl_list_fails(self):
        """`launchctl` missing, or the caller lacking permission to query it,
        must be a best-effort None - not an exception - since PID discovery
        is an enrichment, not a requirement for sampling to work."""
        with mock.patch("tools.fleet.collector.subprocess.run", side_effect=FileNotFoundError()):
            self.assertIsNone(collector.discover_macos_agent_pid())

    def test_discover_macos_agent_pid_returns_none_when_launchctl_print_fails(self):
        """A `domain_target` naming a service that is not registered makes
        `launchctl print` exit non-zero - still a best-effort None, not a
        raised CalledProcessError."""
        error = subprocess.CalledProcessError(1, ["launchctl", "print"])
        with mock.patch("tools.fleet.collector.subprocess.run", side_effect=error):
            self.assertIsNone(collector.discover_macos_agent_pid(domain_target="gui/501"))


class RunPsAndUptimeTests(unittest.TestCase):
    """The thin subprocess wrappers around `ps`/`uptime` themselves - not
    just the pure text parsers they feed."""

    def test_run_ps_invokes_the_documented_column_set_and_returns_stdout(self):
        result = mock.Mock(stdout=PS_TEXT)
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=result) as run:
            output = collector._run_ps()
        self.assertEqual(output, PS_TEXT)
        self.assertEqual(run.call_args[0][0], ["ps", "-eo", "pid,ppid,user,pcpu,rss,comm"])

    def test_run_uptime_loads_parses_a_real_subprocess_result(self):
        result = mock.Mock(stdout="10:00  up 3 days, load average: 0.10, 0.05, 0.01\n")
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=result):
            self.assertEqual(collector._run_uptime_loads(), [0.10, 0.05, 0.01])

    def test_run_uptime_loads_returns_all_none_when_uptime_is_missing(self):
        """`uptime` not being on PATH (a minimal container image) must
        degrade to `[None, None, None]`, not raise out of the sampling loop."""
        with mock.patch("tools.fleet.collector.subprocess.run", side_effect=FileNotFoundError()):
            self.assertEqual(collector._run_uptime_loads(), [None, None, None])


class HostCpuMemoryDispatchTests(unittest.TestCase):
    """`collect_host_cpu_pct`/`collect_host_memory_mb` dispatch by
    `platform.system()` - each branch must reach the right platform-specific
    implementation, and an unrecognised platform must degrade to `None`
    rather than guessing."""

    def test_collect_host_cpu_pct_dispatches_to_linux_on_linux(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Linux"), \
                mock.patch("tools.fleet.collector._linux_cpu_pct", return_value=42.0) as linux_impl, \
                mock.patch("tools.fleet.collector._macos_cpu_pct") as macos_impl:
            self.assertEqual(collector.collect_host_cpu_pct(), 42.0)
        linux_impl.assert_called_once()
        macos_impl.assert_not_called()

    def test_collect_host_cpu_pct_dispatches_to_macos_on_darwin(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Darwin"), \
                mock.patch("tools.fleet.collector._linux_cpu_pct") as linux_impl, \
                mock.patch("tools.fleet.collector._macos_cpu_pct", return_value=7.0) as macos_impl:
            self.assertEqual(collector.collect_host_cpu_pct(), 7.0)
        macos_impl.assert_called_once()
        linux_impl.assert_not_called()

    def test_collect_host_cpu_pct_is_none_on_an_unrecognised_platform(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Windows"):
            self.assertIsNone(collector.collect_host_cpu_pct())

    def test_collect_host_memory_mb_dispatches_to_linux_on_linux(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Linux"), \
                mock.patch("tools.fleet.collector._linux_memory_mb", return_value=(1.0, 2.0)) as linux_impl, \
                mock.patch("tools.fleet.collector._macos_memory_mb") as macos_impl:
            self.assertEqual(collector.collect_host_memory_mb(), (1.0, 2.0))
        linux_impl.assert_called_once()
        macos_impl.assert_not_called()

    def test_collect_host_memory_mb_dispatches_to_macos_on_darwin(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Darwin"), \
                mock.patch("tools.fleet.collector._linux_memory_mb") as linux_impl, \
                mock.patch("tools.fleet.collector._macos_memory_mb", return_value=(3.0, 4.0)) as macos_impl:
            self.assertEqual(collector.collect_host_memory_mb(), (3.0, 4.0))
        macos_impl.assert_called_once()
        linux_impl.assert_not_called()

    def test_collect_host_memory_mb_is_none_pair_on_an_unrecognised_platform(self):
        with mock.patch("tools.fleet.collector.platform.system", return_value="Windows"):
            self.assertEqual(collector.collect_host_memory_mb(), (None, None))


class LinuxCpuPctSamplingTests(unittest.TestCase):
    """`_linux_cpu_pct` takes two `/proc/stat` snapshots a sample interval
    apart; `_read_proc_stat_cpu` must degrade to `None` rather than raise
    when `/proc/stat` cannot be read (a non-Linux sandbox, a permission
    issue)."""

    def test_read_proc_stat_cpu_returns_none_on_oserror(self):
        with mock.patch("builtins.open", side_effect=OSError()):
            self.assertIsNone(collector._read_proc_stat_cpu())

    def test_linux_cpu_pct_takes_two_samples_a_sample_interval_apart(self):
        samples = [(100, 1000), (150, 1100)]
        with mock.patch("tools.fleet.collector._read_proc_stat_cpu", side_effect=samples), \
                mock.patch("tools.fleet.collector.time.sleep") as sleep_mock:
            pct = collector._linux_cpu_pct(sample_interval=0.25)
        self.assertAlmostEqual(pct, 50.0)
        sleep_mock.assert_called_once_with(0.25)

    def test_linux_cpu_pct_is_none_when_proc_stat_is_unreadable(self):
        with mock.patch("tools.fleet.collector._read_proc_stat_cpu", return_value=None), \
                mock.patch("tools.fleet.collector.time.sleep") as sleep_mock:
            self.assertIsNone(collector._linux_cpu_pct())
        sleep_mock.assert_not_called()


class MacosCpuPctSamplingTests(unittest.TestCase):
    """`_macos_cpu_pct` shells out to `top -l 1 -n 0`; any failure to run it
    (missing binary, timeout, non-zero exit) must yield `None`, not raise."""

    def test_macos_cpu_pct_parses_a_successful_run(self):
        result = mock.Mock(stdout="CPU usage: 10.00% user, 5.00% sys, 85.00% idle\n")
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=result):
            self.assertAlmostEqual(collector._macos_cpu_pct(), 15.0)

    def test_macos_cpu_pct_is_none_when_top_times_out(self):
        with mock.patch(
            "tools.fleet.collector.subprocess.run",
            side_effect=subprocess.TimeoutExpired(cmd="top", timeout=10),
        ):
            self.assertIsNone(collector._macos_cpu_pct())

    def test_macos_cpu_pct_is_none_when_top_is_missing(self):
        with mock.patch("tools.fleet.collector.subprocess.run", side_effect=OSError()):
            self.assertIsNone(collector._macos_cpu_pct())


class LinuxMemoryMbSamplingTests(unittest.TestCase):

    def test_linux_memory_mb_returns_none_pair_when_meminfo_is_unreadable(self):
        with mock.patch("builtins.open", side_effect=OSError()):
            self.assertEqual(collector._linux_memory_mb(), (None, None))

    def test_linux_memory_mb_parses_a_readable_file(self):
        text = "MemTotal:       16777216 kB\nMemAvailable:    8388608 kB\n"
        with mock.patch("builtins.open", mock.mock_open(read_data=text)):
            used_mb, total_mb = collector._linux_memory_mb()
        self.assertAlmostEqual(total_mb, 16384.0)
        self.assertAlmostEqual(used_mb, 8192.0)


class MacosMemoryMbSamplingTests(unittest.TestCase):
    """`_macos_memory_mb` shells out twice (`sysctl` then `vm_stat`); either
    call failing must yield `(None, None)` rather than a partial result that
    looks complete."""

    def test_macos_memory_mb_combines_sysctl_and_vm_stat(self):
        sysctl_result = mock.Mock(stdout="17179869184\n")  # 16 GiB
        vm_stat_result = mock.Mock(stdout="\n".join([
            "Mach Virtual Memory Statistics: (page size of 4096 bytes)",
            "Pages free:                               1000.",
        ]))
        with mock.patch(
            "tools.fleet.collector.subprocess.run", side_effect=[sysctl_result, vm_stat_result],
        ):
            used_mb, total_mb = collector._macos_memory_mb()
        self.assertAlmostEqual(total_mb, 16384.0)
        self.assertLess(used_mb, total_mb)

    def test_macos_memory_mb_is_none_pair_when_sysctl_fails(self):
        with mock.patch("tools.fleet.collector.subprocess.run", side_effect=OSError()):
            self.assertEqual(collector._macos_memory_mb(), (None, None))

    def test_macos_memory_mb_is_none_pair_when_sysctl_output_is_unparsable(self):
        sysctl_result = mock.Mock(stdout="not-a-number\n")
        with mock.patch("tools.fleet.collector.subprocess.run", return_value=sysctl_result):
            self.assertEqual(collector._macos_memory_mb(), (None, None))

    def test_macos_memory_mb_returns_total_only_when_vm_stat_is_unparsable(self):
        sysctl_result = mock.Mock(stdout="17179869184\n")
        vm_stat_result = mock.Mock(stdout="unexpected output\n")
        with mock.patch(
            "tools.fleet.collector.subprocess.run", side_effect=[sysctl_result, vm_stat_result],
        ):
            used_mb, total_mb = collector._macos_memory_mb()
        self.assertIsNone(used_mb)
        self.assertAlmostEqual(total_mb, 16384.0)


class ProcStatCpuParsingTests(unittest.TestCase):
    """Linux `/proc/stat` reports cumulative jiffies, not a percentage - two
    snapshots must be subtracted to get a rate."""

    def test_parses_busy_and_total_jiffies(self):
        text = "cpu  100 0 50 850 0 0 0 0 0 0\ncpu0 100 0 50 850 0 0 0 0 0 0\n"
        busy, total = collector.parse_proc_stat_cpu_line(text)
        self.assertEqual(total, 1000)
        self.assertEqual(busy, 150)

    def test_missing_cpu_line_yields_none(self):
        self.assertIsNone(collector.parse_proc_stat_cpu_line("nonsense\n"))

    def test_cpu_pct_from_two_samples(self):
        first = (150, 1000)
        second = (200, 1100)
        # busy delta 50 over total delta 100 = 50%.
        self.assertAlmostEqual(collector.cpu_pct_from_proc_stat_samples(first, second), 50.0)

    def test_cpu_pct_is_none_when_either_sample_missing(self):
        self.assertIsNone(collector.cpu_pct_from_proc_stat_samples(None, (200, 1100)))
        self.assertIsNone(collector.cpu_pct_from_proc_stat_samples((150, 1000), None))

    def test_cpu_pct_is_none_when_total_did_not_advance(self):
        self.assertIsNone(collector.cpu_pct_from_proc_stat_samples((150, 1000), (150, 1000)))

    def test_guest_and_guest_nice_are_not_double_counted_in_total(self):
        """`guest`/`guest_nice` (fields 8/9) are already folded into
        `user`/`nice` by the kernel; summing all ten fields would count guest
        time twice and understate `cpu_pct` on a host running VMs."""
        # user=100 nice=0 system=50 idle=850 iowait=0 irq=0 softirq=0 steal=0
        # guest=300 (already included in user) guest_nice=0
        text = "cpu  100 0 50 850 0 0 0 0 300 0\n"
        busy, total = collector.parse_proc_stat_cpu_line(text)
        self.assertEqual(total, 1000)
        self.assertEqual(busy, 150)


class MacosTopCpuParsingTests(unittest.TestCase):

    def test_parses_user_and_sys_percentages(self):
        text = "CPU usage: 12.34% user, 3.45% sys, 84.21% idle\n"
        self.assertAlmostEqual(collector.parse_macos_top_cpu_line(text), 12.34 + 3.45)

    def test_missing_line_yields_none(self):
        self.assertIsNone(collector.parse_macos_top_cpu_line("nothing here\n"))


class ProcMeminfoParsingTests(unittest.TestCase):

    def test_used_is_total_minus_available(self):
        text = "MemTotal:       16777216 kB\nMemAvailable:    8388608 kB\n"
        used_mb, total_mb = collector.parse_proc_meminfo(text)
        self.assertAlmostEqual(total_mb, 16384.0)
        self.assertAlmostEqual(used_mb, 8192.0)

    def test_missing_fields_yield_none(self):
        self.assertEqual(collector.parse_proc_meminfo("nothing here\n"), (None, None))


class MacosVmStatParsingTests(unittest.TestCase):

    def test_parses_page_size_and_pages_free(self):
        text = "\n".join([
            "Mach Virtual Memory Statistics: (page size of 4096 bytes)",
            "Pages free:                               1000.",
            "Pages active:                             2000.",
        ])
        self.assertEqual(collector.parse_macos_vm_stat(text), (4096, 1000))

    def test_missing_header_yields_none(self):
        text = "Pages free: 1000.\n"
        self.assertIsNone(collector.parse_macos_vm_stat(text))

    def test_missing_pages_free_yields_none(self):
        text = "Mach Virtual Memory Statistics: (page size of 4096 bytes)\n"
        self.assertIsNone(collector.parse_macos_vm_stat(text))


class DiskUsageTests(unittest.TestCase):

    def test_reports_used_and_total_for_an_existing_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            used_gb, total_gb = collector.collect_disk_usage(tmp)
        self.assertIsNotNone(used_gb)
        self.assertIsNotNone(total_gb)
        self.assertGreater(total_gb, 0)
        self.assertGreaterEqual(total_gb, used_gb)

    def test_missing_path_yields_none(self):
        used_gb, total_gb = collector.collect_disk_usage("/no/such/path/at/all")
        self.assertIsNone(used_gb)
        self.assertIsNone(total_gb)


class SampleAndWriteDiskPathTests(unittest.TestCase):
    """`sample_and_write` must sample the caller-supplied disk path (the
    runner work volume per the design), not silently default to the root
    filesystem on every host."""

    def test_disk_path_is_forwarded_to_collect_disk_usage(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl_path = os.path.join(tmp, "sample.jsonl")
            with mock.patch("tools.fleet.collector._run_ps", return_value=PS_TEXT), \
                 mock.patch("tools.fleet.collector._run_uptime_loads", return_value=[0.1, 0.2, 0.3]), \
                 mock.patch("tools.fleet.collector.discover_macos_agent_pid", return_value=None), \
                 mock.patch("tools.fleet.collector.collect_host_cpu_pct", return_value=1.0), \
                 mock.patch("tools.fleet.collector.collect_host_memory_mb", return_value=(1.0, 2.0)), \
                 mock.patch("tools.fleet.collector.collect_disk_usage", return_value=(3.0, 4.0)) as disk_usage:
                collector.sample_and_write("mac-studio", jsonl_path, disk_path="/mnt/work")
            disk_usage.assert_called_once_with("/mnt/work")

    def test_disk_path_defaults_to_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl_path = os.path.join(tmp, "sample.jsonl")
            with mock.patch("tools.fleet.collector._run_ps", return_value=PS_TEXT), \
                 mock.patch("tools.fleet.collector._run_uptime_loads", return_value=[0.1, 0.2, 0.3]), \
                 mock.patch("tools.fleet.collector.discover_macos_agent_pid", return_value=None), \
                 mock.patch("tools.fleet.collector.collect_host_cpu_pct", return_value=1.0), \
                 mock.patch("tools.fleet.collector.collect_host_memory_mb", return_value=(1.0, 2.0)), \
                 mock.patch("tools.fleet.collector.collect_disk_usage", return_value=(3.0, 4.0)) as disk_usage:
                collector.sample_and_write("mac-studio", jsonl_path)
            disk_usage.assert_called_once_with("/")


class BuildRecordHostMetricsTests(unittest.TestCase):
    """`build_record` carries the host_sample counters (§schema) collected
    independently of the ps-based process walk."""

    def test_host_metrics_are_carried_through_when_supplied(self):
        record = collector.build_record(
            "2026-09-18T00:00:00Z", "mac-studio", PS_TEXT,
            cpu_pct=12.5, mem_used_mb=1024.0, mem_total_mb=2048.0,
            disk_used_gb=10.0, disk_total_gb=100.0,
        )
        self.assertEqual(record["host_metrics"], {
            "cpu_pct": 12.5,
            "mem_used_mb": 1024.0,
            "mem_total_mb": 2048.0,
            "disk_used_gb": 10.0,
            "disk_total_gb": 100.0,
            "thermal_c": None,
            "throttled": None,
        })

    def test_host_metrics_default_to_none(self):
        record = collector.build_record("2026-09-18T00:00:00Z", "mac-studio", PS_TEXT)
        self.assertEqual(record["host_metrics"], {
            "cpu_pct": None,
            "mem_used_mb": None,
            "mem_total_mb": None,
            "disk_used_gb": None,
            "disk_total_gb": None,
            "thermal_c": None,
            "throttled": None,
        })


if __name__ == "__main__":
    unittest.main()
