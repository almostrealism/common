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


if __name__ == "__main__":
    unittest.main()
