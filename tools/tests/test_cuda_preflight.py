"""Guard: a CUDA runner can read the sample library the media suites need.

``tools/ci/sync-music-samples.sh`` leaves the library readable by its group
only. The CUDA fleet's runner user belongs to no host group, so
``tools/ci/cuda/fleet.sh`` resolves the numeric gid that owns the library
(``AR_CI_SAMPLES_GID``) and ``docker-compose.yml`` adds it with ``group_add``.
``cuda-preflight.sh`` then refuses to register a runner that cannot read a
staged library, rather than letting every media job fail for a reason that
has nothing to do with the code under test.

These tests run the real preflight with ``nvidia-smi`` and the runner
entrypoint stubbed, and the real ``fleet.sh`` with ``docker`` stubbed.
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_CUDA_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "cuda")
_PREFLIGHT = os.path.join(_CUDA_DIR, "cuda-preflight.sh")
_FLEET = os.path.join(_CUDA_DIR, "fleet.sh")
_COMPOSE = os.path.join(_CUDA_DIR, "docker-compose.yml")

_NVIDIA_SMI_STUB = """#!/usr/bin/env bash
echo "GPU 0: Stub GPU (UUID: GPU-stub)"
"""

_ENTRYPOINT_STUB = """#!/usr/bin/env bash
touch "${STUB_MARKER}"
"""

_DOCKER_STUB = """#!/usr/bin/env bash
{
    echo "AR_CI_SAMPLES_GID=${AR_CI_SAMPLES_GID:-}"
    echo "ARGS=$*"
} >> "${STUB_DOCKER_LOG}"
"""


def _write_executable(path, content):
    with open(path, "w") as f:
        f.write(content)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class CudaPreflightSampleLibraryTest(unittest.TestCase):
    """Runs cuda-preflight.sh against a stub GPU and checks the library gate."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="cuda-preflight-")
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.mkdir(self.bin_dir)
        _write_executable(os.path.join(self.bin_dir, "nvidia-smi"), _NVIDIA_SMI_STUB)
        self.cuda_root = os.path.join(self.tmp, "cuda")
        os.makedirs(os.path.join(self.cuda_root, "lib64"))
        open(os.path.join(self.cuda_root, "lib64", "libnvrtc.so.12"), "w").close()
        self.samples = os.path.join(self.tmp, "samples")
        os.mkdir(self.samples)
        self.entrypoint = os.path.join(self.tmp, "entrypoint.sh")
        _write_executable(self.entrypoint, _ENTRYPOINT_STUB)
        self.marker = os.path.join(self.tmp, "registered")

    def tearDown(self):
        for root, dirs, _ in os.walk(self.tmp):
            for d in dirs:
                os.chmod(os.path.join(root, d), 0o755)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _stage(self):
        os.mkdir(os.path.join(self.samples, "Samples"))
        with open(os.path.join(self.samples, "pattern-factory.json"), "w") as f:
            f.write("{}")

    def _run(self):
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "PREFLIGHT_CUDA_ROOT": self.cuda_root,
            "PREFLIGHT_SAMPLES_ROOT": self.samples,
            "PREFLIGHT_RUNNER_ENTRYPOINT": self.entrypoint,
            "PREFLIGHT_FAIL_PAUSE_SECONDS": "0",
            "STUB_MARKER": self.marker,
        }
        proc = subprocess.run(["bash", _PREFLIGHT], env=env, capture_output=True,
                              text=True, timeout=60)
        return proc.returncode, proc.stdout + proc.stderr

    def test_readable_library_registers(self):
        self._stage()
        code, output = self._run()
        self.assertEqual(0, code, output)
        self.assertIn("Sample library: " + self.samples, output)
        self.assertNotIn("WARNING", output)
        self.assertTrue(os.path.exists(self.marker))

    def test_unstaged_library_warns_and_registers(self):
        code, output = self._run()
        self.assertEqual(0, code, output)
        self.assertIn("WARNING: no sample library is staged", output)
        self.assertTrue(os.path.exists(self.marker))

    def test_partially_staged_library_does_not_register(self):
        os.mkdir(os.path.join(self.samples, "Samples"))
        code, output = self._run()
        self.assertNotEqual(0, code)
        self.assertIn("is not readable", output)
        self.assertFalse(os.path.exists(self.marker))

    @unittest.skipIf(os.geteuid() == 0, "root reads the tree regardless of its mode")
    def test_unreadable_samples_dir_does_not_register(self):
        self._stage()
        os.chmod(os.path.join(self.samples, "Samples"), 0o000)
        code, output = self._run()
        self.assertNotEqual(0, code)
        self.assertIn("is not readable by uid %d" % os.getuid(), output)
        self.assertIn("AR_CI_SAMPLES_GID", output)
        self.assertFalse(os.path.exists(self.marker))

    @unittest.skipIf(os.geteuid() == 0, "root reads the tree regardless of its mode")
    def test_untraversable_library_root_does_not_register(self):
        self._stage()
        os.chmod(self.samples, 0o000)
        code, output = self._run()
        self.assertNotEqual(0, code)
        self.assertIn("is not readable", output)
        self.assertFalse(os.path.exists(self.marker))

    @unittest.skipIf(os.geteuid() == 0, "root reads the library regardless of its mode")
    def test_unreadable_nvrtc_does_not_register(self):
        self._stage()
        os.chmod(os.path.join(self.cuda_root, "lib64", "libnvrtc.so.12"), 0o000)
        code, output = self._run()
        self.assertNotEqual(0, code)
        self.assertIn("NVRTC under " + os.path.join(self.cuda_root, "lib64"), output)
        self.assertIn("is not readable", output)
        self.assertFalse(os.path.exists(self.marker))

    def test_missing_nvrtc_still_fails_first(self):
        self._stage()
        os.remove(os.path.join(self.cuda_root, "lib64", "libnvrtc.so.12"))
        code, output = self._run()
        self.assertNotEqual(0, code)
        self.assertIn("NVRTC was not found under " + self.cuda_root, output)
        self.assertFalse(os.path.exists(self.marker))


class CudaFleetSamplesGroupTest(unittest.TestCase):
    """Runs fleet.sh against a stub docker and checks the gid it passes on."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="cuda-fleet-")
        self.fleet_dir = os.path.join(self.tmp, "cuda")
        os.mkdir(self.fleet_dir)
        shutil.copy(_FLEET, self.fleet_dir)
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.mkdir(self.bin_dir)
        _write_executable(os.path.join(self.bin_dir, "docker"), _DOCKER_STUB)
        self.docker_log = os.path.join(self.tmp, "docker.log")
        self.samples = os.path.join(self.tmp, "music")
        os.mkdir(self.samples)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_env(self, **values):
        defaults = {"GITHUB_PAT": "stub", "RUNNER_VERSION": "9.9.9",
                    "AR_CI_SAMPLES_DIR": self.samples, "AR_CI_SAMPLES_GID": ""}
        defaults.update(values)
        with open(os.path.join(self.fleet_dir, ".env"), "w") as f:
            for key, value in defaults.items():
                f.write("%s=%s\n" % (key, value))

    def _run(self, *args):
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "STUB_DOCKER_LOG": self.docker_log,
        }
        proc = subprocess.run(["bash", os.path.join(self.fleet_dir, "fleet.sh")] + list(args),
                              env=env, capture_output=True, text=True, timeout=60)
        return proc.returncode, proc.stdout + proc.stderr

    def _docker_calls(self):
        if not os.path.exists(self.docker_log):
            return []
        with open(self.docker_log) as f:
            return f.read().splitlines()

    def test_up_passes_the_library_directory_group(self):
        self._write_env()
        code, output = self._run("up")
        self.assertEqual(0, code, output)
        expected = "AR_CI_SAMPLES_GID=%d" % os.stat(self.samples).st_gid
        self.assertIn(expected, self._docker_calls())
        self.assertIn("ARGS=compose --env-file .env up -d --build --scale runner=1",
                      self._docker_calls())

    def test_up_prefers_a_gid_pinned_in_env(self):
        self._write_env(AR_CI_SAMPLES_GID="4321")
        code, output = self._run("up", "2")
        self.assertEqual(0, code, output)
        self.assertIn("AR_CI_SAMPLES_GID=4321", self._docker_calls())
        self.assertIn("ARGS=compose --env-file .env up -d --build --scale runner=2",
                      self._docker_calls())

    def test_up_refuses_when_the_library_directory_is_missing(self):
        self._write_env(AR_CI_SAMPLES_DIR=os.path.join(self.tmp, "absent"))
        code, output = self._run("up")
        self.assertNotEqual(0, code)
        self.assertIn("does not exist", output)
        self.assertEqual([], self._docker_calls())

    def test_up_resolves_the_group_when_env_omits_the_setting(self):
        self._write_env()
        env_path = os.path.join(self.fleet_dir, ".env")
        with open(env_path) as f:
            lines = [line for line in f if not line.startswith("AR_CI_SAMPLES_GID=")]
        with open(env_path, "w") as f:
            f.writelines(lines)
        code, output = self._run("up")
        self.assertEqual(0, code, output)
        expected = "AR_CI_SAMPLES_GID=%d" % os.stat(self.samples).st_gid
        self.assertIn(expected, self._docker_calls())

    def test_up_refuses_a_library_owned_by_the_root_group(self):
        if os.stat("/").st_gid != 0:
            self.skipTest("the filesystem root is not owned by gid 0 on this host")
        self._write_env(AR_CI_SAMPLES_DIR="/")
        code, output = self._run("up")
        self.assertNotEqual(0, code)
        self.assertIn("root group (gid 0)", output)
        self.assertEqual([], self._docker_calls())

    def test_up_accepts_a_pinned_root_gid(self):
        self._write_env(AR_CI_SAMPLES_GID="0")
        code, output = self._run("up")
        self.assertEqual(0, code, output)
        self.assertIn("AR_CI_SAMPLES_GID=0", self._docker_calls())

    def test_down_does_not_need_the_library(self):
        self._write_env(AR_CI_SAMPLES_DIR=os.path.join(self.tmp, "absent"))
        code, output = self._run("down")
        self.assertEqual(0, code, output)
        self.assertIn("AR_CI_SAMPLES_GID=0", self._docker_calls())
        self.assertIn("ARGS=compose --env-file .env down --timeout 60", self._docker_calls())

    def test_compose_adds_the_resolved_gid_to_the_runner(self):
        import yaml

        with open(_COMPOSE) as f:
            compose = yaml.safe_load(f)
        runner = compose["services"]["runner"]
        group_add = runner.get("group_add", [])
        self.assertEqual(1, len(group_add), "the runner must join the sample library's group")
        self.assertTrue(group_add[0].startswith("${AR_CI_SAMPLES_GID:?"),
                        "group_add must require AR_CI_SAMPLES_GID rather than default it")
        mounts = [v for v in runner["volumes"] if v.get("target") == "/opt/ar-samples"]
        self.assertEqual(1, len(mounts))
        self.assertTrue(mounts[0].get("read_only"))


if __name__ == "__main__":
    unittest.main()
