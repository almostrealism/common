"""Tests for classify-job-failures.py.

The payloads mirror the GitHub jobs API
(``/repos/{repo}/actions/runs/{id}/attempts/{n}/jobs``): each job carries a
``name``, a ``conclusion`` and an ordered ``steps`` list, each step with a
``name`` and a ``conclusion``. Only the fields the classifier reads are kept.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "classify-job-failures.py")

_spec = importlib.util.spec_from_file_location("classify_job_failures", _SCRIPT)
classify_job_failures = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(classify_job_failures)


def _steps(*pairs):
    """Build an ordered step list from (name, conclusion) pairs."""
    return [{"name": name, "conclusion": conclusion} for name, conclusion in pairs]


# A test lane's setup preamble, then its first Maven/test step. The media lanes
# add "Verify curated sample library" and "Isolate Maven repository per runner"
# to the preamble; both are before the first "Run ... Tests" step.
def _media_cl_steps(setup_java, test_conclusion, verify="success"):
    return _steps(
        ("Set up job", "success"),
        ("Checkout Code", "success"),
        ("Set up JDK 17", setup_java),
        ("Verify curated sample library", verify),
        ("Isolate Maven repository per runner", "success"),
        ("Download build artifacts", "success"),
        ("Restore build artifacts", "success"),
        ("Run Audio Tests", test_conclusion),
        ("Run Music Tests", "skipped" if test_conclusion != "success" else "success"),
        ("Upload Surefire Reports", "success"),
    )


# ── the incident shape: only test-media-cl (3) and (4) failed at
# "Set up JDK 17"; actions/setup-java hit ETIMEDOUT so no JVM started and no
# tests ran. Every failed step is before the first "Run ... Tests" step.
_SETUP_JAVA_ONLY = {
    "jobs": [
        {"name": "test-media-cl (1)", "conclusion": "success",
         "steps": _media_cl_steps("success", "success")},
        {"name": "test-media-cl (2)", "conclusion": "success",
         "steps": _media_cl_steps("success", "success")},
        {"name": "test-media-cl (3)", "conclusion": "failure",
         "steps": _media_cl_steps("failure", "skipped")},
        {"name": "test-media-cl (4)", "conclusion": "failure",
         "steps": _media_cl_steps("failure", "skipped")},
        {"name": "test-media-cl (5)", "conclusion": "success",
         "steps": _media_cl_steps("success", "success")},
    ]
}

# ── a genuine in-test crash: setup succeeded, the Maven/test step itself failed
# (a JVM crash / native fault leaves no parseable Surefire XML).
_IN_TEST_CRASH = {
    "jobs": [
        {"name": "test", "conclusion": "failure",
         "steps": _steps(
             ("Set up job", "success"),
             ("Checkout Code", "success"),
             ("Set up JDK 17", "success"),
             ("Download build artifacts", "success"),
             ("Restore build artifacts", "success"),
             ("Run Hardware Tests", "success"),
             ("Run ML Tests", "failure"),
             ("Upload Surefire Reports", "success"),
         )},
    ]
}

# ── a mixed run: one lane crashed inside its tests, another failed in setup.
_MIXED = {
    "jobs": [
        {"name": "test", "conclusion": "failure",
         "steps": _steps(
             ("Set up job", "success"),
             ("Checkout Code", "success"),
             ("Set up JDK 17", "success"),
             ("Restore build artifacts", "success"),
             ("Run Hardware Tests", "failure"),
         )},
        {"name": "test-media-cl (3)", "conclusion": "failure",
         "steps": _media_cl_steps("failure", "skipped")},
        {"name": "test-media (2)", "conclusion": "success",
         "steps": _media_cl_steps("success", "success")},
    ]
}


class TestEnvironmentalDetection(unittest.TestCase):
    def test_setup_java_failure_is_environmental(self):
        job = _SETUP_JAVA_ONLY["jobs"][2]
        self.assertTrue(classify_job_failures.is_environmental(job))

    def test_checkout_failure_is_environmental(self):
        job = {"name": "test", "conclusion": "failure", "steps": _steps(
            ("Set up job", "success"),
            ("Checkout Code", "failure"),
            ("Run Hardware Tests", "skipped"),
        )}
        self.assertTrue(classify_job_failures.is_environmental(job))

    def test_artifact_download_failure_is_environmental(self):
        # "Download build artifacts" contains the word "build" but is a setup
        # step; it must not be mistaken for the Maven/test step.
        job = {"name": "test", "conclusion": "failure", "steps": _steps(
            ("Set up job", "success"),
            ("Set up JDK 17", "success"),
            ("Download build artifacts", "failure"),
            ("Run Hardware Tests", "skipped"),
        )}
        self.assertTrue(classify_job_failures.is_environmental(job))

    def test_in_test_step_failure_is_not_environmental(self):
        self.assertFalse(
            classify_job_failures.is_environmental(_IN_TEST_CRASH["jobs"][0]))

    def test_curated_library_verify_failure_is_environmental(self):
        # The sample-library verify step runs before the tests; its failure is
        # infrastructure, and it falls before the first "Run ... Tests" step.
        job = {"name": "test-media-cl (3)", "conclusion": "failure",
               "steps": _media_cl_steps("success", "skipped", verify="failure")}
        self.assertTrue(classify_job_failures.is_environmental(job))

    def test_unidentifiable_work_step_defaults_to_crash(self):
        # No step names a Maven/test step, so the anchor is unknown and the job
        # is routed as a crash rather than silently dropped.
        job = {"name": "test", "conclusion": "failure", "steps": _steps(
            ("Set up job", "success"),
            ("Mystery step", "failure"),
        )}
        self.assertFalse(classify_job_failures.is_environmental(job))

    def test_job_failed_with_no_failed_step_defaults_to_crash(self):
        job = {"name": "test", "conclusion": "failure", "steps": _steps(
            ("Set up job", "success"),
            ("Run Hardware Tests", "success"),
        )}
        self.assertFalse(classify_job_failures.is_environmental(job))

    def test_step_number_orders_classification_not_array_order(self):
        # The steps arrive out of array order; execution order comes from the
        # "number" field. The failed setup step (number 2) precedes the test
        # step (number 3), so the job is environmental despite the array order.
        job = {"name": "test", "conclusion": "failure", "steps": [
            {"name": "Run Hardware Tests", "number": 3, "conclusion": "skipped"},
            {"name": "Set up JDK 17", "number": 2, "conclusion": "failure"},
            {"name": "Set up job", "number": 1, "conclusion": "success"},
        ]}
        self.assertTrue(classify_job_failures.is_environmental(job))

    def test_setup_timeout_is_environmental(self):
        # A setup step that times out (the incident's setup-java ETIMEDOUT) is a
        # setup failure, not an in-test crash.
        job = {"name": "test-media-cl (3)", "conclusion": "timed_out",
               "steps": _media_cl_steps("timed_out", "skipped")}
        self.assertTrue(classify_job_failures.is_environmental(job))


class TestClassification(unittest.TestCase):
    def test_setup_only_run_dispatches_nothing(self):
        crashes, environmental = classify_job_failures.classify(
            _SETUP_JAVA_ONLY["jobs"])
        self.assertEqual(crashes, [])
        self.assertEqual([j["name"] for j in environmental],
                         ["test-media-cl (3)", "test-media-cl (4)"])

    def test_in_test_crash_takes_the_crash_route(self):
        crashes, environmental = classify_job_failures.classify(
            _IN_TEST_CRASH["jobs"])
        self.assertEqual([j["name"] for j in crashes], ["test"])
        self.assertEqual(environmental, [])

    def test_mixed_run_routes_only_the_genuine_failure(self):
        crashes, environmental = classify_job_failures.classify(_MIXED["jobs"])
        self.assertEqual([j["name"] for j in crashes], ["test"])
        self.assertEqual([j["name"] for j in environmental],
                         ["test-media-cl (3)"])

    def test_successful_and_skipped_jobs_are_ignored(self):
        jobs = [
            {"name": "build", "conclusion": "success", "steps": []},
            {"name": "test-cuda", "conclusion": "skipped", "steps": []},
        ]
        crashes, environmental = classify_job_failures.classify(jobs)
        self.assertEqual(crashes, [])
        self.assertEqual(environmental, [])


class TestLoadJobs(unittest.TestCase):
    def test_single_object(self):
        jobs = classify_job_failures.load_jobs(json.dumps(_SETUP_JAVA_ONLY))
        self.assertEqual(len(jobs), 5)

    def test_bare_list(self):
        jobs = classify_job_failures.load_jobs(
            json.dumps(_SETUP_JAVA_ONLY["jobs"]))
        self.assertEqual(len(jobs), 5)

    def test_paginated_stream_is_concatenated(self):
        # gh api --paginate emits one {"total_count", "jobs"} object per page,
        # concatenated with no array wrapper. The loader must collect all pages.
        page1 = json.dumps({"total_count": 4, "jobs": _MIXED["jobs"][:2]})
        page2 = json.dumps({"total_count": 4, "jobs": _MIXED["jobs"][2:]})
        jobs = classify_job_failures.load_jobs(page1 + "\n" + page2)
        self.assertEqual(len(jobs), 3)
        crashes, environmental = classify_job_failures.classify(jobs)
        self.assertEqual([j["name"] for j in crashes], ["test"])
        self.assertEqual([j["name"] for j in environmental],
                         ["test-media-cl (3)"])


class TestCli(unittest.TestCase):
    def _run(self, payload):
        with tempfile.TemporaryDirectory() as tmp:
            jobs_file = os.path.join(tmp, "jobs.json")
            out_dir = os.path.join(tmp, "out")
            with open(jobs_file, "w", encoding="utf-8") as handle:
                handle.write(json.dumps(payload))
            proc = subprocess.run(
                [sys.executable, _SCRIPT, jobs_file, out_dir],
                capture_output=True, text=True, timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            with open(os.path.join(out_dir, "crash-jobs.txt"),
                      encoding="utf-8") as handle:
                crash_text = handle.read()
            with open(os.path.join(out_dir, "environmental-jobs.txt"),
                      encoding="utf-8") as handle:
                env_text = handle.read()
            return proc.stdout, crash_text, env_text

    def test_setup_only_run_counts_and_files(self):
        out, crash_text, env_text = self._run(_SETUP_JAVA_ONLY)
        self.assertIn("crash_count=0", out)
        self.assertIn("environmental_count=2", out)
        self.assertEqual(crash_text, "")
        self.assertIn("test-media-cl (3)", env_text)
        self.assertIn("Set up JDK 17", env_text)

    def test_mixed_run_counts_and_files(self):
        out, crash_text, env_text = self._run(_MIXED)
        self.assertIn("crash_count=1", out)
        self.assertIn("environmental_count=1", out)
        self.assertIn("test", crash_text)
        self.assertIn("Run Hardware Tests", crash_text)
        self.assertIn("test-media-cl (3)", env_text)


if __name__ == "__main__":
    unittest.main()
