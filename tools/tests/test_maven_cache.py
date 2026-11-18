"""Guard: self-hosted CI lanes reuse a persistent, shared Maven cache.

The self-hosted Linux runners (ar-ci, ar-ci-cl, ar-ci-cuda) are ephemeral
containers that start with an empty local repository, so every job re-resolved
the whole plugin and dependency set from Maven Central and the fan-out tripped
its HTTP 429 rate limit. The fix points each lane's Maven ``local repository``
at a per-runner subdirectory of a persistent, shared cache a host mounts into
its runners (a named volume), falling back to the previous per-runner
repository when no such mount is present — so it is safe on today's runners and
gets fast once a host provides the volume.

These tests pin the three ends that have to agree:

* the shared ``select-maven-repo`` composite action every self-hosted lane runs,
  and its fallback behaviour (executed, not just grepped);
* each self-hosted lane routing through that one action rather than an inline
  per-runner ``run`` step that could drift;
* the runner images creating the mount point with runner ownership and setting a
  Maven transport retry/backoff, and the fleet definitions mounting a persistent
  named volume there.

Hosted (``ubuntu-latest``) jobs are exempt: they keep ``actions/setup-java``'s
``cache: maven`` (GitHub's cache), which is appropriate for GitHub-hosted jobs.
"""

import os
import re
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_SELECT_REPO_ACTION = "./.github/actions/select-maven-repo"
_ACTION = os.path.join(_REPO_ROOT, ".github", "actions", "select-maven-repo", "action.yml")
_MOUNT = "/opt/ar-maven-cache"

_DOCKER_DOCKERFILE = os.path.join(_REPO_ROOT, "tools", "ci", "docker", "Dockerfile")
_ROCM_DOCKERFILE = os.path.join(_REPO_ROOT, "tools", "ci", "rocm", "Dockerfile")
_DOCKER_COMPOSE = os.path.join(_REPO_ROOT, "tools", "ci", "docker", "docker-compose.yml")
_CUDA_COMPOSE = os.path.join(_REPO_ROOT, "tools", "ci", "cuda", "docker-compose.yml")
_ROCM_QUADLET = os.path.join(_REPO_ROOT, "tools", "ci", "rocm", "ar-ci-cl-runner@.container")
_MACOS_RUNNER = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "runner.sh")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _jobs():
    return _workflow()["jobs"]


def _runs_on(job):
    labels = job.get("runs-on", [])
    return [labels] if isinstance(labels, str) else list(labels)


def _is_self_hosted(job):
    return "self-hosted" in _runs_on(job)


def _read(path):
    with open(path) as f:
        return f.read()


class SelfHostedMavenRepoWiringTest(unittest.TestCase):
    def test_at_least_one_self_hosted_job_exists(self):
        """A smoke check so a refactor that renamed every lane cannot make the
        rules below pass vacuously."""
        self.assertTrue([n for n, j in _jobs().items() if _is_self_hosted(j)])

    def test_every_self_hosted_lane_selects_the_maven_repo_once(self):
        """Each self-hosted lane must route its Maven local repository through the
        one shared action, so the shared-cache preference and the per-runner
        fallback cannot drift between copies. A lane that re-inlined its own
        per-runner ``run`` step would bypass the shared cache, so this also fails
        on any self-hosted job that still sets ``maven.repo.local`` inline."""
        for name, job in _jobs().items():
            if not _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                uses = [s for s in job.get("steps", [])
                        if str(s.get("uses", "")).rstrip("/") == _SELECT_REPO_ACTION]
                self.assertEqual(1, len(uses),
                                 f"{name} must use {_SELECT_REPO_ACTION} exactly once")
                inline = [s for s in job.get("steps", [])
                          if "maven.repo.local" in str(s.get("run", ""))]
                self.assertEqual([], inline,
                                 f"{name} must not set maven.repo.local inline; use the shared action")

    def test_no_self_hosted_lane_keeps_the_old_isolate_step(self):
        """The previous inline step was named 'Isolate Maven repository per
        runner'. None must remain, or a lane would keep isolating without ever
        preferring the shared cache."""
        for name, job in _jobs().items():
            if not _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                names = [str(s.get("name", "")) for s in job.get("steps", [])]
                self.assertNotIn("Isolate Maven repository per runner", names,
                                 f"{name} still carries the old inline isolate step")

    def test_hosted_jobs_do_not_use_the_self_hosted_repo_action(self):
        """The shared cache is a self-hosted-host mount; a GitHub-hosted job has
        none and must keep setup-java's GitHub-backed Maven cache instead."""
        for name, job in _jobs().items():
            if _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                self.assertEqual([], [s for s in job.get("steps", [])
                                      if str(s.get("uses", "")).rstrip("/") == _SELECT_REPO_ACTION],
                                 f"{name} is hosted and must not use {_SELECT_REPO_ACTION}")

    def test_the_cpu_test_lane_falls_back_to_the_home_default_repo(self):
        """The ar-ci ``test`` lane warms $HOME/.m2/repository with actions/cache,
        so when no shared cache is mounted it must fall back to that exact path
        (fallback: home-default), not to a per-runner $HOME/.m2-ci repository the
        cache step never populated."""
        steps = _jobs()["test"]["steps"]
        uses = [s for s in steps if str(s.get("uses", "")).rstrip("/") == _SELECT_REPO_ACTION]
        self.assertEqual(1, len(uses), "the test lane must select the Maven repo once")
        self.assertEqual("home-default", str(uses[0].get("with", {}).get("fallback", "")),
                         "the ar-ci test lane must fall back to $HOME/.m2/repository")

    def test_accelerator_and_macos_lanes_fall_back_per_runner(self):
        """Every self-hosted lane other than the ar-ci ``test`` lane keeps a
        per-runner fallback (the default): the macOS fleet shares one $HOME, and
        the ephemeral CL/CUDA containers had no actions/cache to warm a shared
        home path."""
        for name, job in _jobs().items():
            if not _is_self_hosted(job) or name == "test":
                continue
            with self.subTest(job=name):
                uses = [s for s in job.get("steps", [])
                        if str(s.get("uses", "")).rstrip("/") == _SELECT_REPO_ACTION]
                fallback = str(uses[0].get("with", {}).get("fallback", "per-runner"))
                self.assertEqual("per-runner", fallback,
                                 f"{name} must keep the per-runner fallback")


def _action():
    with open(_ACTION) as f:
        return yaml.safe_load(f)


def _action_run():
    steps = _action()["runs"]["steps"]
    return "\n".join(str(s.get("run", "")) for s in steps)


class SelectMavenRepoActionShapeTest(unittest.TestCase):
    def test_is_a_composite_action_with_the_expected_inputs(self):
        action = _action()
        self.assertEqual("composite", action["runs"]["using"])
        inputs = action.get("inputs", {})
        self.assertIn("shared-cache-root", inputs)
        self.assertIn("fallback", inputs)
        self.assertEqual(_MOUNT, inputs["shared-cache-root"].get("default"))
        self.assertEqual("per-runner", inputs["fallback"].get("default"))

    def test_exports_maven_repo_and_repo_local(self):
        run = _action_run()
        self.assertIn('MAVEN_REPO=', run)
        self.assertIn("-Dmaven.repo.local=", run)
        self.assertIn('>> "${GITHUB_ENV}"', run)


class SelectMavenRepoActionBehaviorTest(unittest.TestCase):
    """Execute the action's shell program against real directories and assert the
    repository it picks and exports, rather than only grepping its text."""

    def _run(self, shared_cache_root, fallback="per-runner", runner_name="ar-runner-1",
             home=None, maven_opts=None):
        with tempfile.TemporaryDirectory() as workdir:
            github_env = os.path.join(workdir, "github_env")
            open(github_env, "w").close()
            home = home or os.path.join(workdir, "home")
            os.makedirs(home, exist_ok=True)
            env = {
                "SHARED_CACHE_ROOT": shared_cache_root,
                "FALLBACK": fallback,
                "RUNNER_NAME": runner_name,
                "HOME": home,
                "GITHUB_ENV": github_env,
                "PATH": os.pathsep.join(["/usr/bin", "/bin"]),
            }
            if maven_opts is not None:
                env["MAVEN_OPTS"] = maven_opts
            result = subprocess.run(["/bin/bash", "-c", _action_run()],
                                    env=env, capture_output=True, text=True, timeout=60)
            with open(github_env) as f:
                env_out = f.read()
            return result, env_out, home

    def _repo(self, env_out):
        match = re.search(r"^MAVEN_REPO=(.*)$", env_out, re.M)
        return match.group(1) if match else None

    def test_uses_a_per_runner_subdir_of_the_shared_cache_when_writable(self):
        with tempfile.TemporaryDirectory() as cache:
            result, env_out, _ = self._run(cache, runner_name="ar-runner-2")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(os.path.join(cache, "ar-runner-2"), self._repo(env_out))
            self.assertIn("-Dmaven.repo.local=%s" % os.path.join(cache, "ar-runner-2"), env_out)
            self.assertTrue(os.path.isdir(os.path.join(cache, "ar-runner-2")))

    def test_sanitizes_spaces_in_the_runner_name(self):
        with tempfile.TemporaryDirectory() as cache:
            _, env_out, _ = self._run(cache, runner_name="mac studio 3")
            self.assertEqual(os.path.join(cache, "mac_studio_3"), self._repo(env_out))

    def test_falls_back_per_runner_when_the_cache_is_absent(self):
        missing = "/nonexistent/ar-maven-cache-xyz"
        result, env_out, home = self._run(missing, fallback="per-runner", runner_name="ar-runner-9")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(os.path.join(home, ".m2-ci", "ar-runner-9"), self._repo(env_out))

    def test_falls_back_home_default_for_the_cpu_lane(self):
        missing = "/nonexistent/ar-maven-cache-xyz"
        result, env_out, home = self._run(missing, fallback="home-default")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(os.path.join(home, ".m2", "repository"), self._repo(env_out))

    def test_rejects_an_unknown_fallback(self):
        missing = "/nonexistent/ar-maven-cache-xyz"
        result, _, _ = self._run(missing, fallback="bogus")
        self.assertNotEqual(0, result.returncode,
                            "an unknown fallback mode must fail rather than guess a repository")

    def test_preserves_existing_maven_opts(self):
        with tempfile.TemporaryDirectory() as cache:
            _, env_out, _ = self._run(cache, maven_opts="-Xmx2g")
            match = re.search(r"^MAVEN_OPTS=(.*)$", env_out, re.M)
            self.assertIsNotNone(match)
            self.assertIn("-Xmx2g", match.group(1))
            self.assertIn("-Dmaven.repo.local=", match.group(1))


_RETRY_PROP = "aether.connector.http.retryHandler.count"


class RunnerImageMavenCacheTest(unittest.TestCase):
    """The runner images must create the shared-cache mount point owned by the
    runner user (so a fresh named volume mounted over it is writable by the
    non-root runner) and set a Maven transport retry/backoff as the backstop."""

    def _assert_image(self, path):
        text = _read(path)
        self.assertRegex(text, r"mkdir -p %s" % re.escape(_MOUNT),
                         "%s must create the shared-cache mount point" % path)
        self.assertRegex(text, r"chown -R runner:runner %s" % re.escape(_MOUNT),
                         "%s must own the mount point as the runner user" % path)
        self.assertIn(_RETRY_PROP, text,
                      "%s must set a Maven transport retry via MAVEN_ARGS" % path)
        self.assertRegex(text, r'ENV MAVEN_ARGS=',
                         "%s must export MAVEN_ARGS so every mvn inherits the retry" % path)

    def test_docker_image(self):
        self._assert_image(_DOCKER_DOCKERFILE)

    def test_rocm_image(self):
        self._assert_image(_ROCM_DOCKERFILE)

    def test_macos_runner_sets_the_retry_backstop(self):
        text = _read(_MACOS_RUNNER)
        self.assertIn(_RETRY_PROP, text,
                      "the macOS runner must set the same Maven transport retry")
        self.assertIn("MAVEN_ARGS", text)


class FleetVolumeTest(unittest.TestCase):
    """Every self-hosted Linux fleet mounts a persistent named volume at the
    shared-cache path, so the dependency cache survives the ephemeral runner."""

    def _compose_mounts_named_volume(self, path):
        compose = yaml.safe_load(_read(path))
        runner = compose["services"]["runner"]
        targets = []
        for v in runner.get("volumes", []):
            if isinstance(v, str):
                # short syntax "name:/target"
                parts = v.split(":")
                if len(parts) >= 2:
                    targets.append((parts[0], parts[1]))
            elif isinstance(v, dict):
                targets.append((v.get("source"), v.get("target")))
        mount = [(src, tgt) for src, tgt in targets if tgt == _MOUNT]
        self.assertEqual(1, len(mount), "%s must mount one volume at %s" % (path, _MOUNT))
        source = mount[0][0]
        self.assertIn(source, (compose.get("volumes") or {}),
                      "%s must declare the %s volume as a named volume" % (path, source))

    def test_docker_compose(self):
        self._compose_mounts_named_volume(_DOCKER_COMPOSE)

    def test_cuda_compose(self):
        self._compose_mounts_named_volume(_CUDA_COMPOSE)

    def test_rocm_quadlet_mounts_a_named_volume(self):
        """The ROCm fleet is podman-Quadlet, not compose: assert a Volume= line
        mounts a named volume (not a host bind) at the shared-cache path."""
        text = _read(_ROCM_QUADLET)
        lines = [l.strip() for l in text.splitlines()
                 if l.strip().startswith("Volume=") and l.strip().endswith(_MOUNT)]
        self.assertEqual(1, len(lines),
                         "the quadlet must mount exactly one volume at %s" % _MOUNT)
        source = lines[0][len("Volume="):].split(":")[0]
        self.assertNotIn("/", source,
                         "the shared cache must be a named volume, not a host bind mount")


if __name__ == "__main__":
    unittest.main()
