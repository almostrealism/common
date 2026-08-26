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


class ReplayedGuardTest(RepositoryTestCase):
    """The whole-file guards must say the same thing on either route."""

    def test_a_line_number_reference_is_caught(self):
        """warn-line-number-refs, reached by a script rather than an edit."""
        self.write("Foo.java", "class Foo {\n\t// see Bar.java:123\n}\n")

        found = check.violations(self.root)

        self.assertTrue(any("Foo.java" in report for report in found),
                        "expected a report naming the file: %r" % (found,))

    def test_a_clean_file_says_nothing(self):
        self.write("Foo.java", "class Foo { int x; }\n")
        self.assertEqual([], check.violations(self.root))

    def test_each_guard_is_replayed_with_the_edit_payload(self):
        """The guards are the edit path's own scripts, not a second copy."""
        self.write("Foo.java", "class Foo {\n\t// see Bar.java:123\n}\n")

        said = check.guard_reports(self.root, "Foo.java")

        self.assertTrue(said)
        self.assertTrue(any("Bar.java:123" in report for report in said))

    def test_a_test_without_an_assertion_is_caught(self):
        """warn-assertion-free-test reports on stderr rather than stdout.

        One case per replayed guard, so a guard that goes quiet through this
        route - by writing to a stream the replay does not read, or by
        rejecting the payload - fails a test rather than passing silently.
        """
        self.write("FooTest.java",
                   "class FooTest {\n"
                   "\t@Test(timeout = 1000)\n"
                   "\tpublic void doesNothing() {\n"
                   "\t\tint x = 1;\n"
                   "\t}\n"
                   "}\n")

        said = check.guard_reports(self.root, "FooTest.java")

        self.assertTrue(any("doesNothing" in report for report in said),
                        "expected the method to be named: %r" % (said,))

    def test_a_producer_pattern_violation_is_caught(self):
        """scan-producer-violations only governs computation-layer paths."""
        governed = os.path.join("studio", "music", "src", "main", "java")
        os.makedirs(os.path.join(self.root, governed))
        self.write(os.path.join(governed, "Thing.java"),
                   "class Thing {\n\tdouble v = p.toDouble(0);\n}\n")

        said = check.guard_reports(self.root,
                                   os.path.join(governed, "Thing.java"))

        self.assertTrue(any("toDouble" in report for report in said),
                        "expected the violation to be named: %r" % (said,))

    def test_a_missing_guard_is_survivable(self):
        original = check.REPLAYED_GUARDS
        check.REPLAYED_GUARDS = ("no-such-guard.sh",)
        self.addCleanup(setattr, check, "REPLAYED_GUARDS", original)

        self.write("Foo.java", "class Foo {\n\t// see Bar.java:123\n}\n")

        self.assertEqual([], check.guard_reports(self.root, "Foo.java"))


class TruncationTest(RepositoryTestCase):

    def test_files_beyond_the_limit_are_reported_as_skipped(self):
        """A bounded scan says what it left out rather than implying coverage."""
        original = check.MAX_FILES
        check.MAX_FILES = 2
        self.addCleanup(setattr, check, "MAX_FILES", original)

        for i in range(5):
            self.write("File%d.java" % i, "class File%d {}\n" % i)

        found = check.violations(self.root)

        self.assertTrue(any("not examined" in report for report in found),
                        "expected the skipped files to be named: %r" % (found,))


if __name__ == "__main__":
    unittest.main()
