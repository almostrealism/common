"""Unit tests for enforcement-loop summarization (``loop_summary``).

Covers the two loop shapes ``workstream_context`` collapses: a run of
consecutive jobs where the same enforcement phase produced the same verdict,
and a run of near-identical memories written within one job. Distinct jobs and
distinct memories must pass through untouched.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import loop_summary


def _job(job_id, ts, status, description):
    return {"jobId": job_id, "timestamp": ts, "status": status,
            "description": description}


def _mem(mem_id, created_at, content, namespace="review"):
    return {"id": mem_id, "created_at": created_at, "content": content,
            "namespace": namespace}


class JobLoopTests(unittest.TestCase):

    def test_three_job_loop_is_one_entry(self):
        jobs = [
            _job("j3", "2026-04-20T12:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j2", "2026-04-20T11:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "no-op review pass"),
        ]
        collapsed, loops = loop_summary.summarize_job_loops(jobs)
        self.assertEqual(len(collapsed), 1)
        self.assertEqual(len(loops), 1)
        loop = loops[0]
        self.assertEqual(loop["kind"], "jobs")
        self.assertEqual(loop["count"], 3)
        self.assertEqual(loop["from"], "2026-04-20T10:00:00Z")
        self.assertEqual(loop["to"], "2026-04-20T12:00:00Z")
        # The surviving job carries the loop annotation.
        self.assertEqual(collapsed[0]["loop"]["count"], 3)

    def test_distinct_jobs_are_not_collapsed(self):
        jobs = [
            _job("j3", "2026-04-20T12:00:00Z", "SUCCESS", "merge PR"),
            _job("j2", "2026-04-20T11:00:00Z", "FAILURE", "no-op review pass"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "no-op review pass"),
        ]
        collapsed, loops = loop_summary.summarize_job_loops(jobs)
        self.assertEqual(len(collapsed), 3)
        self.assertEqual(loops, [])

    def test_two_job_repeat_is_not_a_loop(self):
        jobs = [
            _job("j2", "2026-04-20T11:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "no-op review pass"),
        ]
        collapsed, loops = loop_summary.summarize_job_loops(jobs)
        self.assertEqual(len(collapsed), 2)
        self.assertEqual(loops, [])

    def test_same_verdict_different_phase_is_not_merged(self):
        jobs = [
            _job("j3", "2026-04-20T12:00:00Z", "SUCCESS", "placement review"),
            _job("j2", "2026-04-20T11:00:00Z", "SUCCESS", "oop review"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "oop review"),
        ]
        collapsed, loops = loop_summary.summarize_job_loops(jobs)
        self.assertEqual(len(collapsed), 3)
        self.assertEqual(loops, [])


class MemoryLoopTests(unittest.TestCase):

    def _one_job(self):
        return [_job("job-1", "2026-04-20T10:00:00Z", "RUNNING", "primary")]

    def test_near_identical_memories_within_one_job_are_merged(self):
        jobs = self._one_job()
        memories = [
            _mem("m3", "2026-04-20T10:30:00+00:00", "OOP review done on Foo: CLEAN"),
            _mem("m2", "2026-04-20T10:20:00+00:00", "OOP review done on Foo: CLEAN"),
            _mem("m1", "2026-04-20T10:10:00+00:00", "OOP review done on Foo: CLEAN"),
        ]
        collapsed, loops = loop_summary.summarize_memory_loops(memories, jobs)
        self.assertEqual(len(collapsed), 1)
        self.assertEqual(len(loops), 1)
        self.assertEqual(loops[0]["kind"], "memories")
        self.assertEqual(loops[0]["count"], 3)
        self.assertEqual(loops[0]["job_id"], "job-1")
        self.assertEqual(collapsed[0]["id"], "m3")  # newest kept

    def test_distinct_memories_are_kept(self):
        jobs = self._one_job()
        memories = [
            _mem("m3", "2026-04-20T10:30:00+00:00", "Fixed the kernel compile bug"),
            _mem("m2", "2026-04-20T10:20:00+00:00", "Investigated memory copy overlap"),
            _mem("m1", "2026-04-20T10:10:00+00:00", "Added a regression test for FFT"),
        ]
        collapsed, loops = loop_summary.summarize_memory_loops(memories, jobs)
        self.assertEqual(len(collapsed), 3)
        self.assertEqual(loops, [])

    def test_run_does_not_cross_a_job_boundary(self):
        # Three identical notes, but the middle one was written under a second
        # job — so there is no run of three within any single job.
        jobs = [
            _job("job-1", "2026-04-20T10:00:00Z", "RUNNING", "primary"),
            _job("job-2", "2026-04-20T10:15:00Z", "RUNNING", "primary"),
        ]
        memories = [
            _mem("m3", "2026-04-20T10:30:00+00:00", "rule X failed again"),
            _mem("m2", "2026-04-20T10:20:00+00:00", "rule X failed again"),
            _mem("m1", "2026-04-20T10:05:00+00:00", "rule X failed again"),
        ]
        collapsed, loops = loop_summary.summarize_memory_loops(memories, jobs)
        # m3+m2 are in job-2 (a run of 2, below the floor); m1 is in job-1.
        self.assertEqual(len(collapsed), 3)
        self.assertEqual(loops, [])


class CombinedTests(unittest.TestCase):

    def test_collapse_false_keeps_individual_entries(self):
        jobs = [
            _job("j3", "2026-04-20T12:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j2", "2026-04-20T11:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "no-op review pass"),
        ]
        result = loop_summary.summarize_loops([], jobs, collapse=False)
        # Loops are still reported, but every job remains listed.
        self.assertEqual(len(result["jobs"]), 3)
        self.assertEqual(len(result["loops"]), 1)

    def test_collapse_true_collapses_both_streams(self):
        jobs = [
            _job("j3", "2026-04-20T12:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j2", "2026-04-20T11:00:00Z", "SUCCESS", "no-op review pass"),
            _job("j1", "2026-04-20T10:00:00Z", "SUCCESS", "no-op review pass"),
        ]
        result = loop_summary.summarize_loops([], jobs, collapse=True)
        self.assertEqual(len(result["jobs"]), 1)
        self.assertEqual(len(result["loops"]), 1)

    def test_timestamp_with_trailing_z_parses(self):
        self.assertIsNotNone(loop_summary._parse_timestamp("2026-04-20T10:00:00Z"))
        self.assertIsNotNone(
            loop_summary._parse_timestamp("2026-04-20T10:00:00+00:00"))
        self.assertIsNone(loop_summary._parse_timestamp("not-a-date"))
        self.assertIsNone(loop_summary._parse_timestamp(None))


if __name__ == "__main__":
    unittest.main()
