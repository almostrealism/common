"""Tests for classify-failures.py (RETRO P2, autoresolve-attribution)."""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "classify-failures.py")

_spec = importlib.util.spec_from_file_location("classify_failures", _SCRIPT)
classify_failures = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(classify_failures)


# A failing test on the branch, with an ordinary assertion failure.
_REGRESSION_BLOCK = """- org.example.FooTest#computesSum
  Exception: java.lang.AssertionError: expected:<5> but was:<3>
  Stack trace (truncated):
    at org.example.FooTest.computesSum(FooTest.java:42)
"""

# A test that also fails on master (a KeyPosition deserialization defect, the
# report's pre-existing example).
_PREEXISTING_BLOCK = """- org.example.PatternFactoryTest#fixChoices
  Exception: com.fasterxml.jackson.databind.JsonMappingException: Cannot construct KeyPosition
  Stack trace (truncated):
    at org.example.PatternFactoryTest.fixChoices(PatternFactoryTest.java:10)
"""

# The report's environmental example: a 600s timeout inside the native CUDA
# call CU.memAllocManaged.
_CUDA_TIMEOUT_BLOCK = """- org.example.AudioSceneMultiGenomeTest#multiGenomePatternRender
  Exception: org.junit.runners.model.TestTimedOutException: test timed out after 600 seconds
  Stack trace (truncated):
    at jcuda.driver.JCudaDriver.cuMemAllocManaged(Native Method)
    at org.almostrealism.hardware.cuda.CUDAMemoryProvider.allocate(CUDAMemoryProvider.java:88)
"""


def _parse(block):
    return classify_failures.parse_failures(block)


class TestEnvironmentalDetection(unittest.TestCase):
    def test_cuda_memalloc_timeout_is_environmental(self):
        failure = _parse(_CUDA_TIMEOUT_BLOCK)[0]
        self.assertTrue(classify_failures.is_environmental(failure))

    def test_jvm_fatal_crash_is_environmental(self):
        block = ("- org.example.NativeTest#run\n"
                 "  Exception: A fatal error has been detected by the Java Runtime Environment\n"
                 "  Stack trace (truncated):\n"
                 "    at org.example.NativeTest.run(NativeTest.java:3)\n")
        self.assertTrue(classify_failures.is_environmental(_parse(block)[0]))

    def test_plain_assertion_is_not_environmental(self):
        self.assertFalse(
            classify_failures.is_environmental(_parse(_REGRESSION_BLOCK)[0]))

    def test_test_name_alone_does_not_trigger_environmental(self):
        # A test merely *named* for CUDA, with an ordinary assertion failure,
        # is a regression candidate — markers are matched on the detail, not
        # the "- Class#method" name line.
        block = ("- org.example.CudaBridgeTest#addsVectors\n"
                 "  Exception: java.lang.AssertionError: expected:<1.0> but was:<2.0>\n"
                 "  Stack trace (truncated):\n"
                 "    at org.example.CudaBridgeTest.addsVectors(CudaBridgeTest.java:5)\n")
        self.assertFalse(classify_failures.is_environmental(_parse(block)[0]))


class TestClassification(unittest.TestCase):
    def test_preexisting_on_base_is_not_a_regression(self):
        failures = _parse(_REGRESSION_BLOCK + _PREEXISTING_BLOCK)
        baseline = {"org.example.PatternFactoryTest#fixChoices"}
        regressions, preexisting, environmental = classify_failures.classify(
            failures, baseline)
        self.assertEqual([f.name for f in regressions],
                         ["org.example.FooTest#computesSum"])
        self.assertEqual([f.name for f in preexisting],
                         ["org.example.PatternFactoryTest#fixChoices"])
        self.assertEqual(environmental, [])

    def test_cuda_timeout_is_environmental_not_regression(self):
        failures = _parse(_REGRESSION_BLOCK + _CUDA_TIMEOUT_BLOCK)
        regressions, preexisting, environmental = classify_failures.classify(
            failures, set())
        self.assertEqual([f.name for f in regressions],
                         ["org.example.FooTest#computesSum"])
        self.assertEqual([f.name for f in environmental],
                         ["org.example.AudioSceneMultiGenomeTest#multiGenomePatternRender"])

    def test_no_baseline_treats_nonenvironmental_as_regression(self):
        failures = _parse(_REGRESSION_BLOCK + _PREEXISTING_BLOCK)
        regressions, preexisting, environmental = classify_failures.classify(
            failures, set())
        self.assertEqual(len(regressions), 2)
        self.assertEqual(preexisting, [])

    def test_environmental_takes_precedence_over_preexisting(self):
        # A native hang that is also in the baseline is still environmental
        # (not agent-fixable), never a pre-existing code-defect task.
        failures = _parse(_CUDA_TIMEOUT_BLOCK)
        baseline = {"org.example.AudioSceneMultiGenomeTest#multiGenomePatternRender"}
        regressions, preexisting, environmental = classify_failures.classify(
            failures, baseline)
        self.assertEqual(len(environmental), 1)
        self.assertEqual(preexisting, [])


class TestCli(unittest.TestCase):
    def test_cli_writes_three_files_and_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            failures_file = os.path.join(tmp, "failures.txt")
            baseline_file = os.path.join(tmp, "baseline.txt")
            out_dir = os.path.join(tmp, "out")
            with open(failures_file, "w", encoding="utf-8") as handle:
                handle.write(_REGRESSION_BLOCK + _PREEXISTING_BLOCK
                             + _CUDA_TIMEOUT_BLOCK)
            with open(baseline_file, "w", encoding="utf-8") as handle:
                handle.write(_PREEXISTING_BLOCK)

            proc = subprocess.run(
                [sys.executable, _SCRIPT, failures_file, out_dir, baseline_file],
                capture_output=True, text=True, timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stderr)

            out = proc.stdout
            self.assertIn("regression_count=1", out)
            self.assertIn("preexisting_count=1", out)
            self.assertIn("environmental_count=1", out)

            with open(os.path.join(out_dir, "regressions.txt"), encoding="utf-8") as handle:
                regressions = handle.read()
            self.assertIn("FooTest#computesSum", regressions)
            self.assertNotIn("PatternFactoryTest", regressions)
            self.assertNotIn("AudioSceneMultiGenomeTest", regressions)

    def test_cli_without_baseline_treats_all_nonenvironmental_as_regressions(self):
        with tempfile.TemporaryDirectory() as tmp:
            failures_file = os.path.join(tmp, "failures.txt")
            out_dir = os.path.join(tmp, "out")
            with open(failures_file, "w", encoding="utf-8") as handle:
                handle.write(_REGRESSION_BLOCK + _PREEXISTING_BLOCK)

            proc = subprocess.run(
                [sys.executable, _SCRIPT, failures_file, out_dir],
                capture_output=True, text=True, timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("regression_count=2", proc.stdout)
            self.assertIn("preexisting_count=0", proc.stdout)


if __name__ == "__main__":
    unittest.main()
