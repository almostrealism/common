"""Tests for extract-crash-reports.sh.

The script runs after the test step of each CI job. A forked JVM that dies
natively leaves its cause in a surefire ``.dumpstream`` and an
``hs_err_pid*.log``; these tests check that the decisive lines of both reach
the job log (stdout), that the excerpt stays bounded, and that the files are
still collected into the crash-reports directory.
"""
import os
import shutil
import subprocess
import tempfile
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "extract-crash-reports.sh")
_FIXTURES = os.path.join(_CI_DIR, "testdata", "crash-reports")
_HS_ERR = "hs_err_pid4242.log"
# Stored under another name: the repository ignores hs_err_pid* files.
_HS_ERR_FIXTURE = os.path.join(_FIXTURES, "fatal-error-report.txt")
_DUMPSTREAM = "2026-11-25T10-00-00_000-jvmRun1.dumpstream"


class ExtractCrashReportsTest(unittest.TestCase):
    """Runs the script against a scratch module tree built from the fixtures."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="crash-tree-")
        self.out = tempfile.mkdtemp(prefix="crash-out-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.addCleanup(shutil.rmtree, self.out, True)
        self.reports = os.path.join(self.root, "engine", "target", "surefire-reports")
        os.makedirs(self.reports)
        self.log = os.path.join(self.root, "mvn-engine-test.log")
        with open(self.log, "w") as f:
            f.write("[ERROR] The forked VM terminated without properly saying goodbye.\n"
                    "[ERROR] Process Exit Code: 134\n"
                    "[ERROR] Crashed tests:\n")

    def place_dumpstream(self, hs_err_path="/nonexistent/hs_err_pid4242.log"):
        """Copy the dumpstream fixture into surefire-reports, naming the given hs_err path."""
        with open(os.path.join(_FIXTURES, _DUMPSTREAM)) as f:
            text = f.read().replace("HS_ERR_PATH", hs_err_path)
        with open(os.path.join(self.reports, _DUMPSTREAM), "w") as f:
            f.write(text)

    def run_script(self, **env_overrides):
        """Run the script from the scratch tree and return its stdout."""
        env = dict(os.environ)
        env["CRASH_REPORT_DIR"] = os.path.join(self.out, "crash-reports")
        env.update(env_overrides)
        result = subprocess.run(["bash", _SCRIPT, self.log], cwd=self.root, env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def test_hs_err_excerpt_shows_cause(self):
        shutil.copy(_HS_ERR_FIXTURE, os.path.join(self.root, "engine", _HS_ERR))
        self.place_dumpstream()
        out = self.run_script()
        self.assertIn("hs_err: hs_err_pid4242.log", out)
        self.assertIn("#  SIGABRT (0x6) at pc=0x00007f3a1c49eb2c, pid=4242, tid=4251", out)
        self.assertIn("# Problematic frame:\n# C  [libc.so.6+0x9eb2c]  pthread_kill+0x11c", out)
        self.assertIn('Current thread (0x00007f3a14029b60):  JavaThread "main"', out)
        self.assertIn("siginfo: si_signo: 6 (SIGABRT)", out)
        self.assertIn("C  [libc.so.6+0xadd9e]  free+0x6e", out)
        self.assertIn("C  [GeneratedOperation12.so+0x1a40]  apply+0x80", out)
        # The summary header ends at the first section rule; the rest of the
        # report (command line, registers) stays in the artifact.
        self.assertNotIn("Command Line:", out)
        self.assertNotIn("RAX=", out)
        collected = os.path.join(self.out, "crash-reports", "hs_err", _HS_ERR)
        self.assertTrue(os.path.isfile(collected))

    def test_dumpstream_excerpt_shows_glibc_message(self):
        self.place_dumpstream()
        out = self.run_script()
        self.assertIn("dumpstream: engine-" + _DUMPSTREAM, out)
        self.assertIn("Stream 'free(): invalid pointer'", out)
        self.assertIn("# A fatal error has been detected", out)
        self.assertIn("Stream '# C  [libc.so.6+0x9eb2c]  pthread_kill+0x11c'", out)
        self.assertIn("Stream 'Aborted (core dumped)", out)
        self.assertIn("Stream '# /nonexistent/hs_err_pid4242.log'", out)
        # Timestamp lines carry no cause and are left out of the excerpt.
        self.assertNotIn("# Created at", out)

    def test_hs_err_named_by_fatal_banner_is_collected(self):
        elsewhere = tempfile.mkdtemp(prefix="hs-err-elsewhere-")
        self.addCleanup(shutil.rmtree, elsewhere, True)
        hs_err_path = os.path.join(elsewhere, _HS_ERR)
        shutil.copy(_HS_ERR_FIXTURE, hs_err_path)
        self.place_dumpstream(hs_err_path)
        out = self.run_script()
        self.assertIn("hs_err: hs_err_pid4242.log", out)
        self.assertTrue(os.path.isfile(os.path.join(self.out, "crash-reports", "hs_err", _HS_ERR)))

    def test_native_frames_bounded(self):
        shutil.copy(_HS_ERR_FIXTURE, os.path.join(self.root, "engine", _HS_ERR))
        out = self.run_script(CRASH_LOG_NATIVE_FRAMES="5")
        self.assertIn("Native frames (first 5):", out)
        self.assertIn("C  [libc.so.6+0xa8fe5]  malloc_printerr+0x15", out)
        self.assertNotIn("_int_free_merge_chunk", out)
        self.assertNotIn("SyntheticFrame", out)

    def test_default_native_frame_bound(self):
        shutil.copy(_HS_ERR_FIXTURE, os.path.join(self.root, "engine", _HS_ERR))
        out = self.run_script()
        frames = [line for line in out.splitlines()
                  if line.startswith(("C  [", "j  ", "J "))]
        self.assertEqual(15, len(frames))

    def test_line_count_and_width_bounded(self):
        self.place_dumpstream()
        out = self.run_script(CRASH_LOG_MAX_LINES="3", CRASH_LOG_LINE_WIDTH="80")
        excerpt = out.split("dumpstream: ", 1)[1].splitlines()[1:]
        excerpt = [line for line in excerpt if line]
        self.assertEqual(3, len(excerpt))
        self.assertTrue(all(len(line) <= 80 for line in excerpt))
        # The 900-character tail of the abort line never reaches the log.
        self.assertNotIn("x" * 100, out)

    def test_file_count_bounded(self):
        for pid in range(1, 4):
            shutil.copy(_HS_ERR_FIXTURE,
                        os.path.join(self.root, "engine", "hs_err_pid%d.log" % pid))
        self.place_dumpstream()
        out = self.run_script(CRASH_LOG_MAX_FILES="2")
        self.assertEqual(2, out.count("──── "))
        self.assertIn("further crash files omitted", out)
        # Every file is still collected for the artifact.
        hs_err_dir = os.path.join(self.out, "crash-reports", "hs_err")
        self.assertEqual(3, len(os.listdir(hs_err_dir)))

    def test_no_crash_prints_nothing(self):
        with open(self.log, "w") as f:
            f.write("[INFO] BUILD SUCCESS\n")
        out = self.run_script()
        self.assertEqual("", out)
        self.assertFalse(os.path.exists(os.path.join(self.out, "crash-reports")))


if __name__ == "__main__":
    unittest.main()
