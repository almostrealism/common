#!/usr/bin/env python3
"""Tests for the process questions shared by the MCP servers."""

import os
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import processes

# Above the PID limit on macOS (99998) and the Linux default (4194304 = 2**22).
NONEXISTENT_PID = 2 ** 22


class PidAliveTests(unittest.TestCase):
    """Which process ids count as naming a live process."""

    def test_own_process_is_alive(self):
        self.assertTrue(processes.pid_alive(os.getpid()))

    def test_nonexistent_pid_is_not_alive(self):
        self.assertFalse(processes.pid_alive(NONEXISTENT_PID))

    def test_exited_child_is_not_alive(self):
        child = subprocess.Popen([sys.executable, "-c", "pass"])
        child.wait()
        self.assertFalse(processes.pid_alive(child.pid))

    def test_missing_pid_is_not_alive(self):
        self.assertFalse(processes.pid_alive(None))

    def test_zero_is_not_alive(self):
        # kill(0, 0) addresses this process's own group and succeeds.
        self.assertFalse(processes.pid_alive(0))

    def test_negative_pid_is_not_alive(self):
        # kill(-1, 0) addresses every process this user may signal.
        self.assertFalse(processes.pid_alive(-1))

    def test_process_owned_by_another_user_is_alive(self):
        with patch.object(processes.os, "kill", side_effect=PermissionError):
            self.assertTrue(processes.pid_alive(12345))

    def test_other_signal_failure_is_not_alive(self):
        with patch.object(processes.os, "kill", side_effect=OSError):
            self.assertFalse(processes.pid_alive(12345))


class GetPpidTests(unittest.TestCase):
    """Parent lookup across /proc and ps."""

    def test_child_reports_this_process_as_parent(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            self.assertEqual(os.getpid(), processes.get_ppid(child.pid))
        finally:
            child.kill()
            child.wait()

    def test_nonexistent_pid_has_no_parent(self):
        self.assertIsNone(processes.get_ppid(NONEXISTENT_PID))


if __name__ == "__main__":
    unittest.main()
