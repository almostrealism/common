"""Tests for classify-failures.py."""
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

# A kernel the native compiler rejected. The framework reports it through its
# general HardwareException, but it is a defect in generated code, not a
# hardware fault.
_COMPILE_FAILURE_BLOCK = """- org.example.ChainedDispatchTest#independentDispatch
  Exception: org.almostrealism.hardware.HardwareException: Native compiler failure (1) on org.almostrealism.generated.GeneratedOperation7
  Stack trace (truncated):
    at org.almostrealism.c.NativeCompiler.compile(NativeCompiler.java:10)
    at org.example.ChainedDispatchTest.independentDispatch(ChainedDispatchTest.java:20)
"""

# A device fault raised through the same exception class: the device's own
# error code is what marks it environmental.
_DEVICE_FAULT_BLOCK = """- org.example.KernelTest#runsOnDevice
  Exception: org.almostrealism.hardware.HardwareException: CL_OUT_OF_RESOURCES
  Stack trace (truncated):
    at org.example.KernelTest.runsOnDevice(KernelTest.java:8)
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

    def test_framework_exception_class_alone_is_not_environmental(self):
        # HardwareException is the hardware layer's general exception: a kernel
        # that does not compile is the code's fault and must reach an agent.
        self.assertFalse(
            classify_failures.is_environmental(_parse(_COMPILE_FAILURE_BLOCK)[0]))

    def test_device_fault_in_framework_exception_is_environmental(self):
        self.assertTrue(
            classify_failures.is_environmental(_parse(_DEVICE_FAULT_BLOCK)[0]))

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

    def test_compile_failure_is_a_regression(self):
        # The only failure of a run: classified environmental it would leave
        # regression_count at 0 and nothing would be staged for an agent.
        regressions, preexisting, environmental = classify_failures.classify(
            _parse(_COMPILE_FAILURE_BLOCK), set())
        self.assertEqual([f.name for f in regressions],
                         ["org.example.ChainedDispatchTest#independentDispatch"])
        self.assertEqual(preexisting, [])
        self.assertEqual(environmental, [])

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


# A test that timed out with no native frame: a regression candidate, but load
# can cause it on every lane at once, so it is never confirmed.
_TIMEOUT_BLOCK = """- org.example.SlowTest#rendersScene
  Exception: org.junit.runners.model.TestTimedOutException: test timed out after 300 seconds
  Stack trace (truncated):
    at org.example.SlowTest.rendersScene(SlowTest.java:12)
"""


def _write_report(reports_dir, artifact, class_name, method, failing=True):
    """Write one Surefire report holding a single testcase into an artifact's
    sub-directory, the layout the auto-resolve download produces."""
    directory = os.path.join(reports_dir, artifact, "module", "target", "surefire-reports")
    os.makedirs(directory, exist_ok=True)
    outcome = '<failure type="java.lang.AssertionError" message="m">trace</failure>' if failing else ""
    with open(os.path.join(directory, f"TEST-{class_name}.xml"), "w", encoding="utf-8") as handle:
        handle.write(
            f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<testsuite name="{class_name}" tests="1">\n'
            f'  <testcase classname="{class_name}" name="{method}">{outcome}</testcase>\n'
            f'</testsuite>\n')


class TestBackends(unittest.TestCase):
    def test_artifacts_map_to_their_backends(self):
        self.assertEqual("metal", classify_failures.artifact_backend("surefire-media-mac-group-2"))
        self.assertEqual("opencl", classify_failures.artifact_backend("surefire-cl-group-1"))
        self.assertEqual("cuda", classify_failures.artifact_backend("surefire-media-cuda-group-3"))
        self.assertEqual("jvm", classify_failures.artifact_backend("surefire-flowtree"))
        self.assertEqual("native", classify_failures.artifact_backend("surefire-ml"))
        self.assertEqual("native", classify_failures.artifact_backend("surefire-media"))
        self.assertIsNone(classify_failures.artifact_backend("coverage-ml"))

    def test_failing_backends_collects_each_backend_a_test_failed_on(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_report(tmp, "surefire-ml", "org.example.FooTest", "computesSum")
            _write_report(tmp, "surefire-mac-group-1", "org.example.FooTest", "computesSum")
            _write_report(tmp, "surefire-cl-group-1", "org.example.BarTest", "passes", failing=False)
            backends = classify_failures.failing_backends(tmp)
        self.assertEqual({"native", "metal"}, backends["org.example.FooTest#computesSum"])
        self.assertNotIn("org.example.BarTest#passes", backends)

    def test_an_unreadable_report_is_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = os.path.join(tmp, "surefire-ml")
            os.makedirs(directory)
            with open(os.path.join(directory, "TEST-Broken.xml"), "w", encoding="utf-8") as handle:
                handle.write("<testsuite")
            self.assertEqual({}, classify_failures.failing_backends(tmp))


class TestConfirmation(unittest.TestCase):
    def test_a_regression_on_two_backends_is_confirmed(self):
        regressions = _parse(_REGRESSION_BLOCK + _REGRESSION_BLOCK)
        backends = {"org.example.FooTest#computesSum": {"native", "metal"}}
        result = classify_failures.confirmed(regressions, backends)
        self.assertEqual(["org.example.FooTest#computesSum"], [f.name for f in result])

    def test_a_regression_on_one_backend_is_not_confirmed(self):
        regressions = _parse(_REGRESSION_BLOCK)
        backends = {"org.example.FooTest#computesSum": {"metal"}}
        self.assertEqual([], classify_failures.confirmed(regressions, backends))

    def test_a_timeout_is_never_confirmed(self):
        regressions = _parse(_TIMEOUT_BLOCK)
        backends = {"org.example.SlowTest#rendersScene": {"native", "metal", "opencl"}}
        self.assertEqual([], classify_failures.confirmed(regressions, backends))

    def test_without_reports_nothing_is_confirmed(self):
        regressions = _parse(_REGRESSION_BLOCK)
        self.assertEqual([], classify_failures.confirmed(
            regressions, classify_failures.failing_backends("")))

    def test_a_timeout_block_vetoes_confirmation_of_a_mixed_test(self):
        # One lane reports an ordinary assertion failure for a test, another
        # lane times the same test out. A timeout in any lane means load, so
        # the test must not be confirmed even though the first block seen is an
        # assertion failure on two backends.
        mixed_timeout = _REGRESSION_BLOCK.replace(
            "- org.example.FooTest#computesSum\n"
            "  Exception: java.lang.AssertionError: expected:<5> but was:<3>",
            "- org.example.FooTest#computesSum\n"
            "  Exception: org.junit.runners.model.TestTimedOutException: "
            "test timed out after 600 seconds")
        regressions = _parse(_REGRESSION_BLOCK + mixed_timeout)
        backends = {"org.example.FooTest#computesSum": {"native", "metal"}}
        self.assertEqual([], classify_failures.confirmed(regressions, backends))


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
            self.assertIn("confirmed_count=0", out)

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

    def test_cli_confirms_a_regression_failing_on_two_backends(self):
        with tempfile.TemporaryDirectory() as tmp:
            failures_file = os.path.join(tmp, "failures.txt")
            reports_dir = os.path.join(tmp, "reports")
            out_dir = os.path.join(tmp, "out")
            with open(failures_file, "w", encoding="utf-8") as handle:
                handle.write(_REGRESSION_BLOCK + _REGRESSION_BLOCK + _TIMEOUT_BLOCK)
            _write_report(reports_dir, "surefire-ml", "org.example.FooTest", "computesSum")
            _write_report(reports_dir, "surefire-cl-group-2", "org.example.FooTest", "computesSum")
            _write_report(reports_dir, "surefire-ml", "org.example.SlowTest", "rendersScene")
            _write_report(reports_dir, "surefire-mac-group-1", "org.example.SlowTest", "rendersScene")

            proc = subprocess.run(
                [sys.executable, _SCRIPT, failures_file, out_dir, "", reports_dir],
                capture_output=True, text=True, timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("regression_count=3", proc.stdout)
            self.assertIn("confirmed_count=1", proc.stdout)
            with open(os.path.join(out_dir, "confirmed.txt"), encoding="utf-8") as handle:
                confirmed_text = handle.read()
            self.assertIn("FooTest#computesSum", confirmed_text)
            self.assertNotIn("SlowTest", confirmed_text)


if __name__ == "__main__":
    unittest.main()
