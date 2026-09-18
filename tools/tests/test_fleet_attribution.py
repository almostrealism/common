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
"""Tests for ``tools.ci.fleet.attribution``.

Covers two properties that matter for making sense of shared-host CPU/memory
numbers: computing ``other`` as a residual (``total - runner - agent``) is
ill-defined and can go negative, and every process must end up classified
into exactly one bucket. These tests build a small synthetic process tree and
check the classifier directly, so they fail if attribution reverts to a
subtraction-based ``other`` or drops the one-class-per-pid guarantee.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_attribution.py"
"""

import unittest

from tools.ci.fleet import attribution


class ParsePsLineTests(unittest.TestCase):

    def test_parses_a_well_formed_line(self):
        sample = attribution.parse_ps_line("  123   1 worker  12.5  20480 java")
        self.assertEqual(sample.pid, 123)
        self.assertEqual(sample.ppid, 1)
        self.assertEqual(sample.user, "worker")
        self.assertAlmostEqual(sample.cpu_pct, 12.5)
        self.assertAlmostEqual(sample.rss_mb, 20.0)
        self.assertEqual(sample.comm, "java")

    def test_strips_full_path_to_basename(self):
        sample = attribution.parse_ps_line("1 0 root 0.0 1024 /usr/bin/Runner.Worker")
        self.assertEqual(sample.comm, "Runner.Worker")

    def test_header_line_is_ignored(self):
        self.assertIsNone(attribution.parse_ps_line("  PID  PPID USER %CPU   RSS COMMAND"))

    def test_blank_line_is_ignored(self):
        self.assertIsNone(attribution.parse_ps_line("   "))

    def test_malformed_line_is_ignored_not_raised(self):
        self.assertIsNone(attribution.parse_ps_line("not-a-number 1 user 0.0 100 comm"))


class ClassifyProcessesTests(unittest.TestCase):
    """A synthetic tree: one runner subtree, one agent subtree, one bystander."""

    def setUp(self):
        self.samples = [
            attribution.ProcessSample(1, 0, "root", 0.0, 10.0, "launchd"),
            # Runner subtree: Runner.Listener (100) -> Runner.Worker (101) -> mvn (102) -> java (103)
            attribution.ProcessSample(100, 1, "runner-svc", 0.5, 50.0, "Runner.Listener"),
            attribution.ProcessSample(101, 100, "runner-svc", 5.0, 200.0, "Runner.Worker"),
            attribution.ProcessSample(102, 101, "runner-svc", 40.0, 512.0, "mvn"),
            attribution.ProcessSample(103, 102, "runner-svc", 30.0, 1024.0, "java"),
            # Agent subtree: flowtree-agent (200) -> claude (201) -> node (202)
            attribution.ProcessSample(200, 1, "worker", 0.1, 30.0, "flowtree-agent"),
            attribution.ProcessSample(201, 200, "worker", 10.0, 300.0, "claude"),
            attribution.ProcessSample(202, 201, "worker", 5.0, 150.0, "node"),
            # A bystander, unrelated to either subtree.
            attribution.ProcessSample(300, 1, "worker", 1.0, 40.0, "bash"),
        ]

    def test_every_process_gets_exactly_one_class(self):
        classes = attribution.classify_processes(self.samples)
        self.assertEqual(len(classes), len(self.samples))
        for sample in self.samples:
            self.assertIn(classes[sample.pid], (attribution.RUNNER, attribution.AGENT, attribution.OTHER))

    def test_full_runner_subtree_is_tagged_runner(self):
        classes = attribution.classify_processes(self.samples)
        for pid in (100, 101, 102, 103):
            self.assertEqual(classes[pid], attribution.RUNNER, "pid %d" % pid)

    def test_full_agent_subtree_is_tagged_agent(self):
        classes = attribution.classify_processes(self.samples)
        for pid in (200, 201, 202):
            self.assertEqual(classes[pid], attribution.AGENT, "pid %d" % pid)

    def test_unrelated_processes_are_other(self):
        classes = attribution.classify_processes(self.samples)
        self.assertEqual(classes[1], attribution.OTHER)
        self.assertEqual(classes[300], attribution.OTHER)

    def test_class_metrics_are_not_a_residual(self):
        """`other` must be the direct sum of its own processes, not
        total - runner - agent: recompute the OTHER bucket by hand and
        require it to match the direct sum, not some subtraction of
        independently-scaled totals.
        """
        classes = attribution.classify_processes(self.samples)
        metrics = attribution.class_metrics(self.samples, classes)

        other_samples = [s for s in self.samples if classes[s.pid] == attribution.OTHER]
        expected_other_cpu = sum(s.cpu_pct for s in other_samples)
        expected_other_rss = sum(s.rss_mb for s in other_samples)

        self.assertAlmostEqual(metrics[attribution.OTHER].cpu_pct, expected_other_cpu)
        self.assertAlmostEqual(metrics[attribution.OTHER].rss_mb, expected_other_rss)
        self.assertEqual(metrics[attribution.OTHER].process_count, len(other_samples))

    def test_class_metrics_sum_to_classified_total(self):
        classes = attribution.classify_processes(self.samples)
        metrics = attribution.class_metrics(self.samples, classes)
        total_cpu = sum(s.cpu_pct for s in self.samples)
        self.assertAlmostEqual(attribution.classified_cpu_total(metrics), total_cpu)

    def test_every_class_key_present_even_when_empty(self):
        classes = attribution.classify_processes([])
        metrics = attribution.class_metrics([], classes)
        self.assertEqual(set(metrics.keys()), {attribution.RUNNER, attribution.AGENT, attribution.OTHER})
        self.assertEqual(metrics[attribution.RUNNER].process_count, 0)

    def test_custom_agent_root_is_honoured(self):
        """A caller may name a different agent-root comm (e.g. a
        container's PID 1 name in the Docker pool)."""
        samples = [
            attribution.ProcessSample(1, 0, "root", 0.0, 0.0, "init"),
            attribution.ProcessSample(2, 1, "agent", 3.0, 60.0, "custom-agent-root"),
            attribution.ProcessSample(3, 2, "agent", 7.0, 90.0, "claude"),
        ]
        classes = attribution.classify_processes(samples, agent_root_comms={"custom-agent-root"})
        self.assertEqual(classes[2], attribution.AGENT)
        self.assertEqual(classes[3], attribution.AGENT)


if __name__ == "__main__":
    unittest.main()
