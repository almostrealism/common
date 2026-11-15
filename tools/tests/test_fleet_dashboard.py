"""Pins the fleet capacity dashboard's queries against three past mistakes.

- Wait figures counted jobs that never had a runner. A skipped job carries
  its lane (the jobs API's labels are the requested ``runs-on`` set) and a
  wait of about zero seconds, and every validate run skips each test lane,
  so every lane's median wait read zero while its p90 read hours.
- Running-job counts treated a job with no ``completed_at`` as running until
  now. The poller lost track of jobs whose run left its window, so the count
  only ever grew. An open record now counts for at most the default job
  timeout.
- Host panels plotted one bucket per few 15-second samples, so a busy host's
  CPU line was a band as wide as its min-to-max range.
"""

import json
import os
import re
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_DASHBOARD = os.path.join(_REPO_ROOT, "flowtree", "runtime", "controller", "grafana",
                          "dashboards", "fleet-capacity.json")


def _panels():
    with open(_DASHBOARD) as f:
        return json.load(f)["panels"]


def _sql(panel):
    return "\n".join(t.get("rawSql", "") for t in panel.get("targets", []))


class FleetDashboardTest(unittest.TestCase):

    def setUp(self):
        self.panels = _panels()

    def test_wait_figures_count_only_jobs_that_had_a_runner(self):
        wait_panels = [p for p in self.panels if "queue_wait_seconds" in _sql(p)]
        self.assertGreaterEqual(len(wait_panels), 3, "the wait panels moved; update this test")
        for panel in wait_panels:
            self.assertIn("runner_name <> ''", _sql(panel), panel["title"])

    def test_no_open_job_counts_as_running_indefinitely(self):
        for panel in self.panels:
            sql = _sql(panel)
            self.assertIsNone(
                re.search(r"COALESCE\(\s*(?:j\.)?completed_at\s*,\s*NOW\(\)\s*\)", sql),
                "%s treats an unfinished job as running until now" % panel["title"])
            if "completed_at" in sql and "COALESCE" in sql:
                self.assertIn("INTERVAL '6 hours'", sql, panel["title"])

    def test_running_jobs_are_jobs_on_a_runner(self):
        running = [p for p in self.panels if "'running' AS metric" in _sql(p)]
        self.assertEqual(1, len(running))
        self.assertIn("j.runner_name <> ''", _sql(running[0]))

    def test_host_panels_average_many_samples_per_point(self):
        host_panels = [p for p in self.panels
                       if p.get("type") == "timeseries"
                       and re.search(r"FROM (host_sample|class_sample)", _sql(p))]
        self.assertEqual(4, len(host_panels), "the host panels moved; update this test")
        for panel in host_panels:
            self.assertEqual("5m", panel.get("interval"), panel["title"])
            self.assertLessEqual(panel.get("maxDataPoints") or 10 ** 9, 200, panel["title"])


if __name__ == "__main__":
    unittest.main()
