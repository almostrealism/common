#!/usr/bin/env python3
"""Tests for maven_project: resolving the Maven project a run targets.

Root resolution decides where Maven is invoked, so the sibling-path and
rejection cases matter as much as the default. The resolver is shared by the
test runner and the build validator, so these also pin that an empty or
relative path resolves against the caller-supplied base rather than a base
baked in at import -- the property each server relies on to target a project
other than the one it ships in.
"""

import os
import pathlib
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import maven_project  # noqa: E402


class ResolveProjectRootTest(unittest.TestCase):
    """resolve_project_root resolves, and rejects, a run's target project."""

    def _project(self) -> pathlib.Path:
        """Return a throwaway directory that is a buildable Maven project."""
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        (root / "pom.xml").write_text("<project/>")
        return root

    def test_empty_selects_the_default_base(self):
        """An empty project is the repository the servers live in."""
        self.assertEqual(maven_project.REPO_ROOT, maven_project.resolve_project_root(""))

    def test_empty_selects_a_supplied_base(self):
        """A supplied base redirects the default away from common's own root."""
        base = self._project()
        self.assertEqual(base, maven_project.resolve_project_root("", base=base))

    def test_absolute_path_is_used_as_given(self):
        root = self._project()
        self.assertEqual(root.resolve(), maven_project.resolve_project_root(str(root)))

    def test_relative_path_resolves_against_the_supplied_base(self):
        """A relative path names a child of the base, not of common's root."""
        base = self._project()
        child = base / "module"
        child.mkdir()
        (child / "pom.xml").write_text("<project/>")
        self.assertEqual(
            child.resolve(),
            maven_project.resolve_project_root("module", base=base))

    def test_missing_directory_is_rejected(self):
        with self.assertRaises(ValueError):
            maven_project.resolve_project_root("/nonexistent/project/root")

    def test_directory_without_pom_is_rejected(self):
        """An unbuildable target fails here, not as an opaque Maven error later."""
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)

        with self.assertRaises(ValueError) as caught:
            maven_project.resolve_project_root(str(root))
        self.assertIn("pom.xml", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
