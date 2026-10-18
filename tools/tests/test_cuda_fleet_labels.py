"""Guard: the CUDA fleet's labels and per-runner memory limit.

``tools/ci/cuda/docker-compose.yml`` registers every runner with the
``ar-ci-cuda`` capability label, followed by whatever ``RUNNER_EXTRA_LABELS``
lists (``ar-ci`` lets one GPU host also serve the CPU lane). The CUDA jobs
must only ever land on this fleet, so ``ar-ci-cuda`` must come first and must
never be replaced, and an empty ``RUNNER_EXTRA_LABELS`` must not leave a
trailing comma that registers an empty label.

The memory limit applies to every replica, so the compose default must fit the
largest fleet the documentation suggests (three runners on a 128 GB host).
"""

import os
import re
import subprocess
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_CUDA_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "cuda")
_COMPOSE = os.path.join(_CUDA_DIR, "docker-compose.yml")
_ENV_EXAMPLE = os.path.join(_CUDA_DIR, ".env.example")

_HOST_MEMORY_GB = 128
_MAX_DOCUMENTED_RUNNERS = 3


def _runner():
    import yaml

    with open(_COMPOSE) as f:
        return yaml.safe_load(f)["services"]["runner"]


def _env_example_value(key):
    with open(_ENV_EXAMPLE) as f:
        for line in f:
            if line.startswith(key + "="):
                return line.strip().split("=", 1)[1]
    return None


def _gigabytes(limit):
    match = re.fullmatch(r"(\d+)g", limit)
    if match is None:
        raise AssertionError("memory limit %r is not expressed in whole gigabytes" % limit)
    return int(match.group(1))


class CudaFleetLabelsTest(unittest.TestCase):
    """Evaluates the compose file's RUNNER_LABELS expression with bash, whose
    ``${VAR:+...}`` expansion compose's interpolation follows."""

    def _labels_expression(self):
        entries = [e for e in _runner()["environment"] if e.startswith("RUNNER_LABELS=")]
        self.assertEqual(1, len(entries), "the runner must set RUNNER_LABELS exactly once")
        return entries[0].split("=", 1)[1]

    def _render(self, extra=None):
        env = {"PATH": os.environ.get("PATH", "")}
        if extra is not None:
            env["RUNNER_EXTRA_LABELS"] = extra
        proc = subprocess.run(["bash", "-c", 'printf "%s" "' + self._labels_expression() + '"'],
                              env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return proc.stdout

    def test_unset_extra_labels_is_cuda_only(self):
        self.assertEqual("ar-ci-cuda", self._render())

    def test_empty_extra_labels_leaves_no_trailing_comma(self):
        self.assertEqual("ar-ci-cuda", self._render(""))

    def test_cpu_lane_label_is_appended_after_the_capability(self):
        self.assertEqual("ar-ci-cuda,ar-ci", self._render("ar-ci"))

    def test_multiple_extra_labels_keep_the_capability_first(self):
        labels = self._render("ar-ci,extra").split(",")
        self.assertEqual(["ar-ci-cuda", "ar-ci", "extra"], labels)

    def test_env_example_opts_into_the_cpu_lane(self):
        self.assertEqual("ar-ci", _env_example_value("RUNNER_EXTRA_LABELS"))


class CudaFleetMemoryTest(unittest.TestCase):
    """The compose default memory limit must fit the documented maximum runner count."""

    def _compose_default(self):
        limit = _runner()["deploy"]["resources"]["limits"]["memory"]
        match = re.fullmatch(r"\$\{RUNNER_MEMORY_LIMIT:-([^}]+)\}", limit)
        self.assertIsNotNone(match, "memory limit must default RUNNER_MEMORY_LIMIT: %r" % limit)
        return match.group(1)

    def test_compose_default_fits_the_documented_fleet(self):
        total = _gigabytes(self._compose_default()) * _MAX_DOCUMENTED_RUNNERS
        self.assertLess(total, _HOST_MEMORY_GB,
                        "%d runners at the compose default exceed a %d GB host"
                        % (_MAX_DOCUMENTED_RUNNERS, _HOST_MEMORY_GB))


if __name__ == "__main__":
    unittest.main()
