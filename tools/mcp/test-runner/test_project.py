"""Tests for the project module: which Maven project a run targets.

Two things are pinned here. Root resolution decides where Maven is invoked, so
a wrong answer runs the wrong code or nothing at all — and since the runner can
target any project, the sibling-path and rejection cases matter as much as the
default. The group count must never be duplicated in tool descriptions or
defaults, where it rots, so it is read from the workflow definition itself, per
module, since different CI jobs partition their modules into different counts.
"""

import pathlib
import shutil
import tempfile
import unittest

import project
# Not `import server`: that name is claimed by several MCP server
# directories at once. See server_under_test for why.
from server_under_test import server

FIXTURE = """\
      - name: Run Utils Tests (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/utils \\
           -DAR_HARDWARE_DRIVER=native \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=8

      - name: Run Utils Tests Again (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/utils \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=8

      # This comment mentions AR_TEST_GROUPS=99 and must be ignored.
      - name: Run Media Tests (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/audio \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=4
"""

CONFLICTING = """\
          mvn test -pl engine/utils \\
           -DAR_TEST_GROUPS=8
          mvn test -pl engine/utils \\
           -DAR_TEST_GROUPS=7
"""


class GroupResolutionTest(unittest.TestCase):

    def _with_workflow(self, text):
        """Build a throwaway project root whose CI workflow holds ``text``.

        The workflow is located relative to a project root rather than through
        a module-level constant, so the fixture is a directory tree rather than
        a loose file.
        """
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        (root / "pom.xml").write_text("<project/>")

        workflow = project.ci_workflow_path(root)
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow.write_text(text)
        return root

    def _resolve(self, text, module):
        return project.resolve_ci_test_groups(module, self._with_workflow(text))

    def test_resolves_per_module(self):
        """Each module resolves to the count of the job that runs it."""
        self.assertEqual(8, self._resolve(FIXTURE, "engine/utils"))
        self.assertEqual(4, self._resolve(FIXTURE, "engine/audio"))

    def test_comment_lines_are_ignored(self):
        """Counts mentioned in comments must not contaminate resolution."""
        self.assertEqual(4, self._resolve(FIXTURE, "engine/audio"))

    def test_unknown_module_is_rejected(self):
        """A module CI never runs with groups must be reported, not defaulted."""
        with self.assertRaises(ValueError):
            self._resolve(FIXTURE, "engine/render")

    def test_conflicting_values_are_rejected(self):
        """Conflicting counts for one module must be reported, not guessed between."""
        with self.assertRaises(ValueError):
            self._resolve(CONFLICTING, "engine/utils")

    def test_resolves_default_module_from_actual_workflow(self):
        """The real workflow resolves the default module to a usable count."""
        groups = project.resolve_ci_test_groups(server.DEFAULT_MODULE)
        self.assertGreaterEqual(groups, 1)

    def test_project_without_workflow_is_rejected(self):
        """A project with no CI workflow must ask for an explicit count."""
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        (root / "pom.xml").write_text("<project/>")

        with self.assertRaises(ValueError):
            project.resolve_ci_test_groups("engine/utils", root)


class ProjectRootResolutionTest(unittest.TestCase):
    """The runner targets any Maven project, not only the repository it lives in."""

    def _project(self):
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        (root / "pom.xml").write_text("<project/>")
        return root

    def test_empty_selects_the_servers_own_repository(self):
        self.assertEqual(project.PROJECT_ROOT, project.resolve_project_root(""))

    def test_absolute_path_is_used_as_given(self):
        root = self._project()
        self.assertEqual(root.resolve(), project.resolve_project_root(str(root)))

    def test_relative_path_resolves_against_the_default_root(self):
        """A relative path names a sibling the way a shell in the repo would."""
        sibling = project.PROJECT_ROOT.parent / project.PROJECT_ROOT.name
        self.assertEqual(
            project.PROJECT_ROOT, project.resolve_project_root(f"../{sibling.name}"))

    def test_missing_directory_is_rejected(self):
        with self.assertRaises(ValueError):
            project.resolve_project_root("/nonexistent/project/root")

    def test_directory_without_pom_is_rejected(self):
        """An unbuildable target fails here, not as an opaque Maven error later."""
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)

        with self.assertRaises(ValueError) as caught:
            project.resolve_project_root(str(root))
        self.assertIn("pom.xml", str(caught.exception))

    def test_run_config_exposes_the_resolved_root(self):
        root = self._project()
        config = server.RunConfig(project=str(root), module="anything")
        self.assertEqual(root.resolve(), config.project_root())

    def test_run_config_defaults_to_the_servers_own_repository(self):
        self.assertEqual(project.PROJECT_ROOT, server.RunConfig().project_root())


if __name__ == "__main__":
    unittest.main()
