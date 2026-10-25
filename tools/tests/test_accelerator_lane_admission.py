"""Guard: how the accelerator lanes in analysis.yaml are admitted and sized.

The CL lane (``test-cl`` -> ``test-media-cl``) runs alongside the macOS GPU
lane rather than after it: ``test-cl`` beside ``test-mac``, ``test-media-cl``
beside ``test-media-mac``. ``test-cl`` is admitted exactly as ``test-mac`` and
``test-cuda`` are, on ``build`` and the validation checks alone, so all three
accelerator first stages start together without waiting for the Linux ``test``
stage; ``test-media-cl`` still gates on both ``test`` and ``test-media``
directly, as the duplicate of ``test-media``. ``test-media-cuda`` likewise
still gates on both the CPU and the macOS lanes. The ``test`` job itself runs
as a single job (no matrix), so it is not among the sharded lanes this guard
sizes. A matrix whose size disagrees with the ``AR_TEST_GROUPS`` its steps
pass silently drops the groups that have no matrix entry, so the two are
checked together.
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
        # test-cl no longer waits for the CPU lane at all: it is admitted
        # alongside test-mac and test-cuda. test-media-cl, the duplicate of
        # test-media, still gates on the whole CPU lane directly, so it is where
        # the ROCm fleet's CPU-lane admission control now lives.
        self.assertNotIn("test", self.jobs["test-cl"]["needs"],
                         "test-cl must not wait for test")
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
        # test-cuda starts with test-mac, without waiting for `test`; it must
        # not wait for any stage of the CPU or macOS lanes. test-media-cuda
        # still gates on the whole CPU and macOS lanes directly.
        self.assertFalse(_gates_on(self.jobs["test-cuda"], "test"),
                         "test-cuda must not gate on test")
        for upstream in _CPU_LANE + _MAC_LANE:
            self.assertNotIn(upstream, self.jobs["test-cuda"]["needs"],
                             "test-cuda must not wait for %s" % upstream)
        for upstream in _CPU_LANE + _MAC_LANE:
            self.assertTrue(_gates_on(self.jobs["test-media-cuda"], upstream),
                            "test-media-cuda must gate on %s" % upstream)

    def test_cuda_starts_with_cl(self):
        # test-cl and test-cuda are now admitted on exactly the same set, so the
        # two accelerator first stages start together.
        self.assertEqual(sorted(self.jobs["test-cl"]["needs"]),
                         sorted(self.jobs["test-cuda"]["needs"]))

    def test_cuda_starts_with_mac(self):
        self.assertEqual(sorted(self.jobs["test-mac"]["needs"]),
                         sorted(self.jobs["test-cuda"]["needs"]))
        self.assertEqual(self.jobs["test-mac"]["if"].strip(),
                         self.jobs["test-cuda"]["if"].strip())

    def test_cl_matrix_matches_its_test_groups(self):
        name = "test-cl"
        job = self.jobs[name]
        groups = job["strategy"]["matrix"]["group"]
        self.assertEqual(3, len(groups), name)
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
        # Guard the discovery itself: every sharded test lane must be seen, so a
        # renamed or dropped lane fails here rather than going unchecked. `test`
        # runs as a single non-matrix job and is deliberately absent from this
        # set (test_cpu_test_job_is_not_sharded pins that); the sharded lanes are
        # test-media plus the three accelerator lanes.
        self.assertEqual(
            sorted(("test-media",) + _MAC_LANE + _CL_LANE + _CUDA_LANE),
            sorted(checked))

    def test_cpu_test_job_is_not_sharded(self):
        # The native `test` job runs as a single job rather than a matrix: it no
        # longer splits engine/utils and engine/ml across groups, so it neither
        # declares a `strategy` matrix nor passes AR_TEST_GROUPS.
        job = self.jobs["test"]
        self.assertNotIn("strategy", job,
                         "test must run as a single job, with no matrix")
        self.assertEqual(set(), _test_group_counts(job),
                         "test must not pass AR_TEST_GROUPS")

    def test_accelerator_jobs_run_at_least_three_groups_at_once(self):
        for name in _CL_LANE + _CUDA_LANE:
            self.assertGreaterEqual(self.jobs[name]["strategy"]["max-parallel"],
                                    _MIN_ACCELERATOR_PARALLELISM, name)

    def test_first_stage_skips_are_not_excused_by_another_lane(self):
        # auto-resolve's incomplete-execution check may excuse a skipped stage
        # only by a lane that stage actually waits for. test-cl and test-cuda
        # gate on no other test lane, so a CPU- or Metal-lane failure cannot
        # explain their skip; excusing it would hide an unexplained skip
        # whenever test or test-media failed.
        script = _incomplete_execution_script(self.jobs)
        for result, waits_for_lanes in (("CL_RESULT", False),
                                        ("CUDA_RESULT", False),
                                        ("MEDIA_CL_RESULT", True),
                                        ("MEDIA_CUDA_RESULT", True)):
            block = _result_block(script, result)
            excused = "CPU_LANE_FAILED" in block or "UPSTREAM_LANE_FAILED" in block
            self.assertEqual(waits_for_lanes, excused,
                             "%s skip %s be excused by an upstream lane's failure"
                             % (result, "must" if waits_for_lanes else "must not"))
        for name in ("test-cl", "test-cuda"):
            for upstream in _CPU_LANE + _MAC_LANE:
                self.assertNotIn(upstream, self.jobs[name]["needs"],
                                 "%s must not wait for %s" % (name, upstream))


def _incomplete_execution_script(jobs):
    """The ``run`` script of auto-resolve's incomplete-execution step."""
    for step in jobs["auto-resolve"]["steps"]:
        if step.get("name") == "Check for incomplete test execution":
            return step["run"]
    raise AssertionError("auto-resolve has no 'Check for incomplete test execution' step")


def _result_block(script, result):
    """The ``if`` block that handles a cancelled or skipped ``$<result>``."""
    opener = 'if [ "$%s" = "cancelled" ]' % result
    start = script.index(opener)
    end = script.find("\nfi\n", start)
    if end < 0:
        raise AssertionError("no closing fi for the %s block" % result)
    return script[start:end]


if __name__ == "__main__":
    unittest.main()
