"""Tests for maven_repository: reading the split local repository from MAVEN_ARGS."""

import os
import sys
import unittest
from pathlib import Path
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import maven_repository  # noqa: E402

# What the FlowTree harness sets on a job's agent (MavenInstallSpace.mavenArgs).
JOB_ARGS = ("-Daether.enhancedLocalRepository.split=true "
            "-Daether.enhancedLocalRepository.localPrefix=flowtree-jobs/task-1")


class MavenPropertiesTest(unittest.TestCase):
    """Parsing -D properties out of MAVEN_ARGS."""

    def test_reads_properties_and_ignores_other_arguments(self):
        properties = maven_repository.maven_properties("-B -Dfoo=bar -Dflag -o")
        self.assertEqual({"foo": "bar", "flag": "true"}, properties)

    def test_reads_environment_when_no_arguments_given(self):
        with mock.patch.dict(os.environ, {"MAVEN_ARGS": JOB_ARGS}):
            properties = maven_repository.maven_properties()
        self.assertEqual("flowtree-jobs/task-1",
                         properties[maven_repository.LOCAL_PREFIX_PROPERTY])


class SplitRootsTest(unittest.TestCase):
    """Where installed and downloaded artifacts live."""

    def setUp(self):
        self.repository = Path("/tmp/m2/repository")

    def test_unsplit_repository_holds_everything_at_its_root(self):
        self.assertEqual(self.repository,
                         maven_repository.installed_root(self.repository, "-B"))
        self.assertEqual(self.repository,
                         maven_repository.downloaded_root(self.repository, ""))

    def test_job_installs_go_to_the_job_space(self):
        self.assertEqual(self.repository / "flowtree-jobs" / "task-1",
                         maven_repository.installed_root(self.repository, JOB_ARGS))

    def test_job_downloads_go_to_the_shared_cache(self):
        self.assertEqual(self.repository / "cached",
                         maven_repository.downloaded_root(self.repository, JOB_ARGS))

    def test_split_without_prefix_uses_resolver_default(self):
        args = "-Daether.enhancedLocalRepository.split=true"
        self.assertEqual(self.repository / "installed",
                         maven_repository.installed_root(self.repository, args))

    def test_split_from_maven_opts_reaches_the_split_roots(self):
        """A split enabled on MAVEN_OPTS must move the roots just as MAVEN_ARGS does.

        install_location() layers MAVEN_OPTS, so installed_root/downloaded_root
        — the readers test-runner preflight and the JMX heap analyzer use — must
        agree with it, or the shared resolver disagrees about where Maven writes.
        """
        opts = ("-Daether.enhancedLocalRepository.split=true "
                "-Daether.enhancedLocalRepository.localPrefix=flowtree-jobs/task-1")
        with mock.patch.dict(os.environ, {"MAVEN_OPTS": opts}, clear=False):
            os.environ.pop("MAVEN_ARGS", None)
            self.assertEqual(self.repository / "flowtree-jobs" / "task-1",
                             maven_repository.installed_root(self.repository))
            self.assertEqual(self.repository / "cached",
                             maven_repository.downloaded_root(self.repository))

    def test_unset_split_environment_leaves_roots_unsplit(self):
        """With no split set in the environment, the roots stay at the repository.

        installed_root/downloaded_root with no explicit maven_args resolve the
        split from the ambient environment alone (MAVEN_OPTS, MAVEN_ARGS). When
        neither carries a split, both roots remain the repository itself.
        """
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MAVEN_OPTS", None)
            os.environ.pop("MAVEN_ARGS", None)
            self.assertEqual(self.repository,
                             maven_repository.installed_root(self.repository))
            self.assertEqual(self.repository,
                             maven_repository.downloaded_root(self.repository))

    def test_default_repository_is_user_m2(self):
        with mock.patch.dict(os.environ, {"MAVEN_ARGS": JOB_ARGS},
                             clear=False):
            os.environ.pop(maven_repository.LOCAL_REPOSITORY_ENV, None)
            root = maven_repository.installed_root()
        self.assertEqual(Path.home() / ".m2" / "repository" / "flowtree-jobs" / "task-1", root)


class DefaultRepositoryTest(unittest.TestCase):
    """The repository base follows the job's exported relocation."""

    def test_falls_back_to_user_m2_when_unset(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(maven_repository.LOCAL_REPOSITORY_ENV, None)
            self.assertEqual(Path.home() / ".m2" / "repository",
                             maven_repository.default_repository())

    def test_honours_exported_relocation(self):
        with mock.patch.dict(
                os.environ,
                {maven_repository.LOCAL_REPOSITORY_ENV: "/srv/m2/repository"}):
            self.assertEqual(Path("/srv/m2/repository"),
                             maven_repository.default_repository())

    def test_relocated_base_feeds_the_split_roots(self):
        with mock.patch.dict(
                os.environ,
                {maven_repository.LOCAL_REPOSITORY_ENV: "/srv/m2/repository",
                 "MAVEN_ARGS": JOB_ARGS}):
            self.assertEqual(
                Path("/srv/m2/repository") / "flowtree-jobs" / "task-1",
                maven_repository.installed_root())


class InstallLocationPrecedenceTest(unittest.TestCase):
    """How install_location layers maven.repo.local across the Maven inputs."""

    def test_maven_args_repo_local_overrides_maven_opts(self):
        """MAVEN_ARGS is prepended after MAVEN_OPTS, so its repo.local wins.

        install_location layers MAVEN_OPTS, then MAVEN_ARGS, then the command
        line, each overriding the one before. This pins the MAVEN_ARGS over
        MAVEN_OPTS order so a regression that swapped the two layers is caught.
        """
        with mock.patch.dict(
                os.environ,
                {"MAVEN_OPTS": "-Dmaven.repo.local=/from/opts",
                 "MAVEN_ARGS": "-Dmaven.repo.local=/from/args"},
                clear=False):
            location = maven_repository.install_location()
        self.assertEqual(Path("/from/args"), location.repository)
        self.assertIn("MAVEN_ARGS", location.origin)

    def test_command_line_repo_local_overrides_both_environments(self):
        """A command-line -Dmaven.repo.local wins over MAVEN_ARGS and MAVEN_OPTS."""
        with mock.patch.dict(
                os.environ,
                {"MAVEN_OPTS": "-Dmaven.repo.local=/from/opts",
                 "MAVEN_ARGS": "-Dmaven.repo.local=/from/args"},
                clear=False):
            location = maven_repository.install_location(
                ["-Dmaven.repo.local=/from/cli"])
        self.assertEqual(Path("/from/cli"), location.repository)
        self.assertIn("the command line", location.origin)


if __name__ == "__main__":
    unittest.main()
