"""Guard: how the accelerator lanes in analysis.yaml are admitted and sized.

The CL lane (``test-cl`` -> ``test-media-cl``) runs alongside the macOS GPU
lane rather than after it: ``test-cl`` beside ``test-mac``, ``test-media-cl``
beside ``test-media-mac``. ``test-cl`` is admitted as soon as the Linux
``test`` stage is green (it no longer waits for ``test-media``), since it is
the OpenCL duplicate of the same modules; ``test-media-cl`` still gates on both
``test`` and ``test-media`` directly, as the duplicate of ``test-media``.
``test-cuda`` is admitted exactly as ``test-cl`` is, so the two start together;
``test-media-cuda`` still gates on both the CPU and the macOS lanes. A
matrix whose size disagrees with the ``AR_TEST_GROUPS`` its steps pass silently
drops the groups that have no matrix entry, so the two are checked together.
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
        # test-cl is admitted as soon as `test` is green; it must not wait for
        # test-media. test-media-cl, the duplicate of test-media, still gates on
        # the whole CPU lane directly.
        self.assertTrue(_gates_on(self.jobs["test-cl"], "test"),
                        "test-cl must gate on test")
        self.assertNotIn("test-media", self.jobs["test-cl"]["needs"],
                         "test-cl must not wait for test-media")
        for upstream in _CPU_LANE:
            self.assertTrue(_gates_on(self.jobs["test-media-cl"], upstream),
                            "test-media-cl must gate on %s" % upstream)

    def test_cl_lane_runs_alongside_the_mac_lane(self):
        for name in _CL_LANE:
            for upstream in _MAC_LANE:
                self.assertNotIn(upstream, self.jobs[name]["needs"],
                                 "%s must not wait for %s" % (name, upstream))

    def test_cuda_lane_gates_on_the_cpu_and_mac_lanes(self):
        # test-cuda starts with test-cl, as soon as `test` is green; it must not
        # wait for test-media or the macOS lane. test-media-cuda still gates on
        # the whole CPU and macOS lanes directly.
        self.assertTrue(_gates_on(self.jobs["test-cuda"], "test"),
                        "test-cuda must gate on test")
        for upstream in ("test-media",) + _MAC_LANE:
            self.assertNotIn(upstream, self.jobs["test-cuda"]["needs"],
                             "test-cuda must not wait for %s" % upstream)
        for upstream in _CPU_LANE + _MAC_LANE:
            self.assertTrue(_gates_on(self.jobs["test-media-cuda"], upstream),
                            "test-media-cuda must gate on %s" % upstream)

    def test_cuda_starts_with_cl(self):
        self.assertEqual(sorted(self.jobs["test-cl"]["needs"]),
                         sorted(self.jobs["test-cuda"]["needs"]))

    def test_cl_matrix_matches_its_test_groups(self):
        for name in ("test-cl", "test-cuda"):
            job = self.jobs[name]
            groups = job["strategy"]["matrix"]["group"]
            self.assertEqual(4, len(groups), name)
            self.assertEqual(list(range(len(groups))), groups, name)
            self.assertEqual({len(groups)}, _test_group_counts(job), name)

    def test_every_matrix_job_matches_its_test_groups(self):
        """Every job that both shards a matrix over ``group`` and passes
        ``AR_TEST_GROUPS`` must keep the two in agreement: a contiguous
        ``[0..n-1]`` matrix and a single ``AR_TEST_GROUPS == n`` across all of
        its steps. A disagreement silently drops the groups with no matrix
        entry, so this guards every lane's group count, not only ``test-cl``."""
        checked = []
        for name, job in self.jobs.items():
            group = job.get("strategy", {}).get("matrix", {}).get("group")
            counts = _test_group_counts(job)
            if group is None or not counts:
                continue
            checked.append(name)
            self.assertEqual(list(range(len(group))), group,
                             "%s matrix groups must be a contiguous [0..n-1] range, got %r"
                             % (name, group))
            self.assertEqual({len(group)}, counts,
                             "%s passes AR_TEST_GROUPS %r but its matrix has %d groups"
                             % (name, sorted(counts), len(group)))
        # Guard the discovery itself: all eight sharded test lanes must be seen,
        # so a renamed or dropped lane fails here rather than going unchecked.
        self.assertEqual(
            sorted(_CPU_LANE + _MAC_LANE + _CL_LANE + _CUDA_LANE),
            sorted(checked))

    def test_accelerator_jobs_run_at_least_three_groups_at_once(self):
        for name in _CL_LANE + _CUDA_LANE:
            self.assertGreaterEqual(self.jobs[name]["strategy"]["max-parallel"],
                                    _MIN_ACCELERATOR_PARALLELISM, name)


if __name__ == "__main__":
    unittest.main()
