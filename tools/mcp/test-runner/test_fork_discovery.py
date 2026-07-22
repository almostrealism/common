"""Tests for fork_discovery: parent-chain walking, jps parsing, and polling."""

import os
import subprocess
import sys
import time

import pytest

import fork_discovery


class TestGetPpid:
    def test_child_process_reports_this_process_as_parent(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            assert fork_discovery.get_ppid(child.pid) == os.getpid()
        finally:
            child.kill()
            child.wait()

    def test_nonexistent_pid_returns_none(self):
        # PID max on macOS is 99998; 2**22 is safely out of range everywhere.
        assert fork_discovery.get_ppid(2 ** 22) is None


class TestIsDescendantOf:
    def test_direct_child_is_descendant(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            assert fork_discovery.is_descendant_of(child.pid, os.getpid())
        finally:
            child.kill()
            child.wait()

    def test_grandchild_is_descendant(self):
        # Parent python spawns a grandchild python and prints its PID.
        parent = subprocess.Popen(
            [sys.executable, "-c",
             "import subprocess, sys; "
             "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); "
             "print(p.pid, flush=True); p.wait()"],
            stdout=subprocess.PIPE, text=True)
        try:
            grandchild_pid = int(parent.stdout.readline().strip())
            assert fork_discovery.is_descendant_of(grandchild_pid, os.getpid())
        finally:
            parent.kill()
            parent.wait()

    def test_unrelated_process_is_not_descendant(self):
        # PID 1 (init/launchd) is never a descendant of this process.
        assert not fork_discovery.is_descendant_of(1, os.getpid())


class TestPidAlive:
    def test_own_process_is_alive(self):
        assert fork_discovery.pid_alive(os.getpid())

    def test_nonexistent_pid_is_not_alive(self):
        assert not fork_discovery.pid_alive(2 ** 22)


class TestFindForkedJvm:
    def _jps_result(self, stdout, returncode=0):
        return subprocess.CompletedProcess(
            args=["jps", "-l"], returncode=returncode, stdout=stdout, stderr="")

    def test_matches_surefirebooter_jar_line(self, monkeypatch):
        out = ("123 org.codehaus.plexus.classworlds.launcher.Launcher\n"
               "456 /repo/module/target/surefire/surefirebooter-abc123.jar\n"
               "789 jdk.jcmd/sun.tools.jps.Jps\n")
        monkeypatch.setattr(fork_discovery.subprocess, "run",
                            lambda *a, **k: self._jps_result(out))
        monkeypatch.setattr(fork_discovery, "is_descendant_of",
                            lambda pid, ancestor: pid == 456)
        assert fork_discovery.find_forked_jvm(123) == 456

    def test_matches_forkedbooter_class_line(self, monkeypatch):
        out = "456 org.apache.maven.plugin.surefire.booterclient.ForkedBooter\n"
        monkeypatch.setattr(fork_discovery.subprocess, "run",
                            lambda *a, **k: self._jps_result(out))
        monkeypatch.setattr(fork_discovery, "is_descendant_of",
                            lambda pid, ancestor: True)
        assert fork_discovery.find_forked_jvm(123) == 456

    def test_rejects_fork_from_another_maven(self, monkeypatch):
        out = "456 /repo/target/surefire/surefirebooter-abc.jar\n"
        monkeypatch.setattr(fork_discovery.subprocess, "run",
                            lambda *a, **k: self._jps_result(out))
        monkeypatch.setattr(fork_discovery, "is_descendant_of",
                            lambda pid, ancestor: False)
        assert fork_discovery.find_forked_jvm(123) is None

    def test_no_match_returns_none(self, monkeypatch):
        out = "123 org.codehaus.plexus.classworlds.launcher.Launcher\n"
        monkeypatch.setattr(fork_discovery.subprocess, "run",
                            lambda *a, **k: self._jps_result(out))
        assert fork_discovery.find_forked_jvm(123) is None

    def test_jps_failure_returns_none(self, monkeypatch):
        monkeypatch.setattr(fork_discovery.subprocess, "run",
                            lambda *a, **k: self._jps_result("", returncode=1))
        assert fork_discovery.find_forked_jvm(123) is None


class TestDiscoverForkedPid:
    def test_stops_immediately_when_run_not_active(self, monkeypatch):
        scans = []
        monkeypatch.setattr(fork_discovery, "find_forked_jvm",
                            lambda pid: scans.append(1) or None)
        assert fork_discovery.discover_forked_pid(123, lambda: False) is None
        assert scans == []

    def test_finds_fork_that_appears_after_many_polls(self, monkeypatch):
        # The fork appears on the 40th poll — past the old fixed 30-poll
        # window whose expiry caused discovery to fail on every module with
        # a non-trivial compile phase.
        calls = {"n": 0}

        def late_fork(pid):
            calls["n"] += 1
            return 456 if calls["n"] >= 40 else None

        monkeypatch.setattr(fork_discovery, "find_forked_jvm", late_fork)
        pid = fork_discovery.discover_forked_pid(
            123, lambda: True, poll_interval=0.001)
        assert pid == 456
        assert calls["n"] == 40

    def test_returns_none_when_run_ends_before_fork_appears(self, monkeypatch):
        deadline = time.time() + 0.05
        monkeypatch.setattr(fork_discovery, "find_forked_jvm", lambda pid: None)
        pid = fork_discovery.discover_forked_pid(
            123, lambda: time.time() < deadline, poll_interval=0.001)
        assert pid is None

    def test_missing_jps_aborts_discovery(self, monkeypatch):
        def no_jps(pid):
            raise FileNotFoundError("jps")

        monkeypatch.setattr(fork_discovery, "find_forked_jvm", no_jps)
        assert fork_discovery.discover_forked_pid(123, lambda: True) is None

    def test_transient_jps_timeout_keeps_polling(self, monkeypatch):
        calls = {"n": 0}

        def flaky(pid):
            calls["n"] += 1
            if calls["n"] == 1:
                raise subprocess.TimeoutExpired(cmd="jps", timeout=5)
            return 456

        monkeypatch.setattr(fork_discovery, "find_forked_jvm", flaky)
        pid = fork_discovery.discover_forked_pid(
            123, lambda: True, poll_interval=0.001)
        assert pid == 456


@pytest.mark.skipif(subprocess.run(["which", "jps"], capture_output=True).returncode != 0,
                    reason="jps not available")
class TestLiveJps:
    def test_find_forked_jvm_scans_real_jps_without_error(self):
        # No surefire fork is running under this test process; the scan
        # must complete and report none rather than raising.
        assert fork_discovery.find_forked_jvm(os.getpid()) is None
