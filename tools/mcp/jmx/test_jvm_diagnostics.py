#!/usr/bin/env python3
"""Tests for the liveness guard in front of the JDK diagnostic tools."""

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_diagnostics


class RequireAliveTests(unittest.TestCase):
    """A JDK tool is only ever pointed at one live process."""

    def test_pid_zero_never_reaches_jcmd(self):
        # "jcmd 0 <command>" runs the command against every JVM on the host,
        # and kill(0, 0) succeeds, so pid 0 must be refused before jcmd runs.
        with patch.object(jvm_diagnostics.subprocess, "run") as run:
            with self.assertRaises(jvm_diagnostics.ProcessNotFoundError):
                jvm_diagnostics.run_jcmd(0, "VM.version")
        run.assert_not_called()

    def test_negative_pid_never_reaches_jcmd(self):
        with patch.object(jvm_diagnostics.subprocess, "run") as run:
            with self.assertRaises(jvm_diagnostics.ProcessNotFoundError):
                jvm_diagnostics.run_jcmd(-1, "VM.version")
        run.assert_not_called()

    def test_live_pid_reaches_jcmd(self):
        with patch.object(jvm_diagnostics.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = "OpenJDK 64-Bit Server VM"
            output = jvm_diagnostics.run_jcmd(os.getpid(), "VM.version")
        self.assertEqual("OpenJDK 64-Bit Server VM", output)
        self.assertEqual(["jcmd", str(os.getpid()), "VM.version"], run.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
