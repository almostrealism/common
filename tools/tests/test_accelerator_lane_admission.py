"""Guard: how the accelerator lanes in analysis.yaml are admitted and sized.

The CL lane (``test-cl`` -> ``test-media-cl``) is admitted once the Linux CPU
lane (``test``, ``test-media``) is green, and runs alongside the macOS GPU
lane rather than after it: ``test-cl`` beside ``test-mac``, ``test-media-cl``
beside ``test-media-mac``. The CUDA lane is still admitted only after both the
CPU and the macOS lanes. A matrix whose size disagrees with the
``AR_TEST_GROUPS`` its steps pass silently drops the groups that have no
matrix entry, so the two are checked together.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

_CPU_LANE = ("test", "test-media")
_MAC_LANE = ("test-mac", "test-media-mac")
_CL_LANE = ("test-cl", "test-media-cl")
_CUDA_LANE = ("test-cuda", "test-media-cuda")

# The fewest groups any accelerator job may run at once.
_MIN_ACCELERATOR_PARALLELISM = 3


def _jobs():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)["jobs"]


def _gates_on(job, upstream):
    """Whether ``job`` both needs ``upstream`` and reads its result in ``if:``."""
    return upstream in job["needs"] and ("needs.%s.result" % upstream) in job["if"]


def _test_group_counts(job):
    """Every ``AR_TEST_GROUPS`` value the job's steps pass."""
    counts = set()
    for step in job["steps"]:
        counts.update(int(n) for n in re.findall(r"-DAR_TEST_GROUPS=(\d+)", step.get("run", "")))
    return counts


class AcceleratorLaneAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.jobs = _jobs()

    def test_cl_lane_gates_on_the_cpu_lane(self):
        for name in _CL_LANE:
            for upstream in _CPU_LANE:
                self.assertTrue(_gates_on(self.jobs[name], upstream),
                                "%s must gate on %s" % (name, upstream))

    def test_cl_lane_runs_alongside_the_mac_lane(self):
        for name in _CL_LANE:
            for upstream in _MAC_LANE:
                self.assertNotIn(upstream, self.jobs[name]["needs"],
                                 "%s must not wait for %s" % (name, upstream))

    def test_cuda_lane_gates_on_the_cpu_and_mac_lanes(self):
        for name in _CUDA_LANE:
            for upstream in _CPU_LANE + _MAC_LANE:
                self.assertTrue(_gates_on(self.jobs[name], upstream),
                                "%s must gate on %s" % (name, upstream))

    def test_cl_matrix_matches_its_test_groups(self):
        job = self.jobs["test-cl"]
        groups = job["strategy"]["matrix"]["group"]
        self.assertEqual(6, len(groups))
        self.assertEqual(list(range(len(groups))), groups)
        self.assertEqual({len(groups)}, _test_group_counts(job))

    def test_accelerator_jobs_run_at_least_three_groups_at_once(self):
        for name in _CL_LANE + _CUDA_LANE:
            self.assertGreaterEqual(self.jobs[name]["strategy"]["max-parallel"],
                                    _MIN_ACCELERATOR_PARALLELISM, name)


if __name__ == "__main__":
    unittest.main()
