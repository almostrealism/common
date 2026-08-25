#!/usr/bin/env python3
"""Tests for the guard over source files changed by a shell command."""

import os
import shutil
import subprocess
import tempfile
import unittest

import source_write_check as check


WALL = "\n".join("// narrating what I was doing at the time" for _ in range(8))


class RepositoryTestCase(unittest.TestCase):
    """A real repository, since the guard's whole point is asking git."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, True)
        self.addCleanup(shutil.rmtree, check.STATE_DIR, True)

        self._run("git", "init", "-q")
        self._run("git", "config", "user.email", "t@example.com")
        self._run("git", "config", "user.name", "Test")
        self.write("Foo.java", "class Foo {}\n")
        self._run("git", "add", "-A")
        self._run("git", "commit", "-q", "-m", "initial")

    def _run(self, *args):
        subprocess.run(args, cwd=self.root, capture_output=True, check=True)

    def write(self, name, text):
        with open(os.path.join(self.root, name), "w") as handle:
            handle.write(text)


class ChangedFilesTest(RepositoryTestCase):

    def test_a_modified_file_is_seen(self):
        self.write("Foo.java", "class Foo { int x; }\n")
        self.assertIn("Foo.java", check.changed_source_files(self.root))

    def test_an_untracked_file_is_seen(self):
        self.write("Bar.java", "class Bar {}\n")
        self.assertIn("Bar.java", check.changed_source_files(self.root))

    def test_an_unchanged_repository_has_nothing(self):
        self.assertEqual([], check.changed_source_files(self.root))

    def test_files_outside_the_policy_are_ignored(self):
        self.write("notes.md", "# notes\n")
        self.assertEqual([], check.changed_source_files(self.root))


class ViolationTest(RepositoryTestCase):

    def test_a_wall_written_by_a_script_is_reported(self):
        """The route the edit-time guard cannot see."""
        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")

        found = check.violations(self.root)

        self.assertEqual(1, len(found))
        self.assertIn("Foo.java", found[0])

    def test_an_acceptable_change_is_not_reported(self):
        self.write("Foo.java", "class Foo {\n// brief nuance\n}\n")
        self.assertEqual([], check.violations(self.root))

    def test_only_added_lines_count(self):
        """Committed commentary is somebody else's problem, not this change's."""
        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")
        self._run("git", "add", "-A")
        self._run("git", "commit", "-q", "-m", "with comments")

        self.write("Foo.java", "class Foo {\n" + WALL + "\nint x;\n}\n")

        self.assertEqual([], check.violations(self.root))

    def test_a_standing_violation_is_reported_once(self):
        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")

        self.assertEqual(1, len(check.violations(self.root)))
        self.assertEqual([], check.violations(self.root))

    def test_a_further_violation_is_reported_again(self):
        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")
        self.assertEqual(1, len(check.violations(self.root)))

        self.write("Foo.java", "class Foo {\n" + WALL + "\n// and more of it\n}\n")
        self.assertEqual(1, len(check.violations(self.root)))

    def test_a_directory_that_is_not_a_repository_is_survivable(self):
        plain = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, plain, True)

        self.assertEqual([], check.violations(plain))

    def test_nothing_is_reported_while_a_merge_is_under_way(self):
        """A merge's difference from HEAD is everything it is bringing in.

        Measuring that would attribute whatever the incoming branch happens to
        contain to whoever is resolving the merge.
        """
        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")
        self.write(os.path.join(".git", "MERGE_HEAD"), "0" * 40 + "\n")

        self.assertTrue(check.operation_in_progress(self.root))
        self.assertEqual([], check.violations(self.root))

    def test_reporting_resumes_once_the_merge_is_finished(self):
        self.write(os.path.join(".git", "MERGE_HEAD"), "0" * 40 + "\n")
        os.remove(os.path.join(self.root, ".git", "MERGE_HEAD"))

        self.write("Foo.java", "class Foo {\n" + WALL + "\n}\n")

        self.assertFalse(check.operation_in_progress(self.root))
        self.assertEqual(1, len(check.violations(self.root)))


if __name__ == "__main__":
    unittest.main()
