"""Guard: replicas of a runner fleet never register under the same name.

The shared runner entrypoint (``tools/ci/docker/entrypoint.sh``) originally
chose its ``<prefix>-N`` name by asking the GitHub API which names were
*online* and taking the lowest one that was not. A runner only shows online
after it has registered and started, so replicas started together by
``docker compose up --scale runner=N`` (``tools/ci/cuda/fleet.sh up N``) all saw
the same empty list and picked the same name. The registration's ``--replace``
then evicted whichever sibling registered first, possibly mid-job.

With ``RUNNER_SLOT_DIR`` set, the entrypoint instead claims the name by holding
an exclusive ``flock`` on ``<dir>/<prefix>-N.lock`` for its lifetime. These
tests run the real entrypoint with ``curl``, ``config.sh`` and ``run.sh``
stubbed out and check which name each instance registers under.

``flock(1)`` is part of util-linux, which the runner image has but macOS does
not; where it is missing, a stub that performs the same ``flock(2)`` call on
the inherited descriptor stands in for it.
"""

import fcntl
import os
import shutil
import stat
import subprocess
import tempfile
import time
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_ENTRYPOINT = os.path.join(_REPO_ROOT, "tools", "ci", "docker", "entrypoint.sh")
_COMPOSE = os.path.join(_REPO_ROOT, "tools", "ci", "cuda", "docker-compose.yml")

_PREFIX = "slot-test"

_CURL_STUB = """#!/usr/bin/env bash
printf '{"token":"stub-token"}\\n201'
"""

_CONFIG_STUB = """#!/usr/bin/env bash
if [ "${1:-}" = "remove" ]; then
    exit 0
fi
printf '%s\\n' "$@" > "${PWD}/config-args"
"""

_RUN_STUB = """#!/usr/bin/env bash
touch "${PWD}/running"
sleep "${STUB_RUN_SECONDS:-0}"
"""

_FLOCK_STUB = """#!/usr/bin/env python3
import fcntl
import sys

try:
    fcntl.flock(int(sys.argv[-1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(1)
"""


def _write_executable(path, content):
    with open(path, "w") as f:
        f.write(content)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class RunnerSlotClaimTest(unittest.TestCase):
    """Runs the shared entrypoint against stubs and checks the registered name."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="runner-slot-")
        self.slot_dir = os.path.join(self.tmp, "slots")
        os.mkdir(self.slot_dir)
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.mkdir(self.bin_dir)
        _write_executable(os.path.join(self.bin_dir, "curl"), _CURL_STUB)
        if shutil.which("flock") is None:
            _write_executable(os.path.join(self.bin_dir, "flock"), _FLOCK_STUB)
        self.held = []

    def tearDown(self):
        for f in self.held:
            f.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _runner_dir(self, label):
        """Creates a runner home holding the config.sh and run.sh stubs."""
        home = os.path.join(self.tmp, label)
        os.mkdir(home)
        _write_executable(os.path.join(home, "config.sh"), _CONFIG_STUB)
        _write_executable(os.path.join(home, "run.sh"), _RUN_STUB)
        return home

    def _start(self, home, run_seconds=0, **env_overrides):
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "HOME": home,
            "GITHUB_OWNER": "example-org",
            "GITHUB_PAT": "stub-pat",
            "RUNNER_SCOPE": "org",
            "RUNNER_PREFIX": _PREFIX,
            "RUNNER_WORKDIR": os.path.join(home, "_work"),
            "RUNNER_SLOT_DIR": self.slot_dir,
            "STUB_RUN_SECONDS": str(run_seconds),
        }
        env.update(env_overrides)
        env = {k: v for k, v in env.items() if v is not None}
        return subprocess.Popen(
            ["bash", _ENTRYPOINT], cwd=home, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def _run(self, label, **env_overrides):
        home = self._runner_dir(label)
        proc = self._start(home, **env_overrides)
        output, _ = proc.communicate(timeout=60)
        return proc.returncode, output, home

    @staticmethod
    def _registered_name(home):
        with open(os.path.join(home, "config-args")) as f:
            args = f.read().splitlines()
        return args[args.index("--name") + 1]

    def _hold(self, index):
        f = open(os.path.join(self.slot_dir, "%s-%d.lock" % (_PREFIX, index)), "w")
        fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.held.append(f)

    def test_first_runner_claims_slot_one(self):
        code, output, home = self._run("a")
        self.assertEqual(0, code, output)
        self.assertEqual(_PREFIX + "-1", self._registered_name(home))
        self.assertTrue(os.path.exists(os.path.join(self.slot_dir, _PREFIX + "-1.lock")))

    def test_held_slots_are_skipped(self):
        self._hold(1)
        self._hold(2)
        code, output, home = self._run("a")
        self.assertEqual(0, code, output)
        self.assertEqual(_PREFIX + "-3", self._registered_name(home))

    def test_gap_left_by_an_exited_runner_is_reused(self):
        self._hold(1)
        self._hold(3)
        code, output, home = self._run("a")
        self.assertEqual(0, code, output)
        self.assertEqual(_PREFIX + "-2", self._registered_name(home))

    def test_lock_is_released_when_the_runner_exits(self):
        first = self._run("a")
        second = self._run("b")
        self.assertEqual(0, first[0], first[1])
        self.assertEqual(0, second[0], second[1])
        self.assertEqual(_PREFIX + "-1", self._registered_name(first[2]))
        self.assertEqual(_PREFIX + "-1", self._registered_name(second[2]))

    def test_replicas_started_together_get_distinct_names(self):
        homes = [self._runner_dir("r%d" % i) for i in range(4)]
        procs = [self._start(home, run_seconds=3) for home in homes]
        outputs = [p.communicate(timeout=60)[0] for p in procs]
        for proc, output in zip(procs, outputs):
            self.assertEqual(0, proc.returncode, output)
        names = sorted(self._registered_name(home) for home in homes)
        self.assertEqual(["%s-%d" % (_PREFIX, i) for i in range(1, 5)], names)

    def test_lock_is_held_while_the_runner_runs(self):
        home = self._runner_dir("a")
        proc = self._start(home, run_seconds=5)
        try:
            running = os.path.join(home, "running")
            for _ in range(300):
                if os.path.exists(running) or proc.poll() is not None:
                    break
                time.sleep(0.1)
            self.assertTrue(os.path.exists(running), "run.sh never started")
            with open(os.path.join(self.slot_dir, _PREFIX + "-1.lock"), "w") as f:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            proc.communicate(timeout=60)

    def test_all_slots_held_fails_without_registering(self):
        for i in range(1, 101):
            self._hold(i)
        code, output, home = self._run("a")
        self.assertNotEqual(0, code)
        self.assertIn("Could not claim a runner slot", output)
        self.assertFalse(os.path.exists(os.path.join(home, "config-args")))

    def test_unwritable_slot_dir_fails_without_registering(self):
        code, output, home = self._run(
            "a", RUNNER_SLOT_DIR=os.path.join(self.tmp, "missing"))
        self.assertNotEqual(0, code)
        self.assertFalse(os.path.exists(os.path.join(home, "config-args")))

    def test_explicit_name_bypasses_the_slot_dir(self):
        code, output, home = self._run("a", RUNNER_NAME="pinned-name")
        self.assertEqual(0, code, output)
        self.assertEqual("pinned-name", self._registered_name(home))
        self.assertEqual([], os.listdir(self.slot_dir))

    def test_disable_update_flag_is_passed_only_when_requested(self):
        code, output, home = self._run("off")
        self.assertEqual(0, code, output)
        with open(os.path.join(home, "config-args")) as f:
            self.assertNotIn("--disableupdate", f.read().splitlines())

        code, output, home = self._run("on", RUNNER_DISABLE_UPDATE="true")
        self.assertEqual(0, code, output)
        with open(os.path.join(home, "config-args")) as f:
            self.assertIn("--disableupdate", f.read().splitlines())

    def test_cuda_fleet_enables_slot_locking_on_a_shared_volume(self):
        import yaml

        with open(_COMPOSE) as f:
            compose = yaml.safe_load(f)
        runner = compose["services"]["runner"]
        env = dict(item.split("=", 1) for item in runner["environment"])
        slot_dir = env.get("RUNNER_SLOT_DIR")
        self.assertTrue(slot_dir, "the CUDA fleet must set RUNNER_SLOT_DIR")
        mounts = [v for v in runner["volumes"]
                  if v.get("type") == "volume" and v.get("target") == slot_dir]
        self.assertEqual(1, len(mounts), "RUNNER_SLOT_DIR must be a shared named volume")
        volume = compose["volumes"][mounts[0]["source"]]
        self.assertIn("mode=1777", volume["driver_opts"]["o"],
                      "the non-root runner user must be able to create lock files")


if __name__ == "__main__":
    unittest.main()
