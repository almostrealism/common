"""Tests for extract_branch_todos.py — the branch-added-TODO extraction."""
import importlib.util
import os
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "extract_branch_todos.py")

_spec = importlib.util.spec_from_file_location("extract_branch_todos", _SCRIPT)
extract_branch_todos = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(extract_branch_todos)

extract_added_todos = extract_branch_todos.extract_added_todos
render = extract_branch_todos.render


class ExtractAddedTodosTest(unittest.TestCase):
    """The diff parser attributes each added TODO to its new-file path and line."""

    def test_finds_added_todo_with_line_number(self):
        """An added TODO line is reported at its new-file line number."""
        diff = (
            "diff --git a/src/Foo.java b/src/Foo.java\n"
            "--- a/src/Foo.java\n"
            "+++ b/src/Foo.java\n"
            "@@ -10,0 +11,2 @@ class Foo {\n"
            "+        // TODO(review): handle the null case\n"
            "+        return value;\n"
        )
        todos = extract_added_todos(diff)
        self.assertEqual([("src/Foo.java", 11, "// TODO(review): handle the null case")], todos)

    def test_ignores_todos_already_on_master(self):
        """A TODO present as unchanged context (on the base branch) is not listed."""
        diff = (
            "diff --git a/src/Bar.java b/src/Bar.java\n"
            "--- a/src/Bar.java\n"
            "+++ b/src/Bar.java\n"
            "@@ -5,3 +5,4 @@ class Bar {\n"
            " // TODO: this one is already on master\n"
            " int a = 1;\n"
            "+int b = 2;\n"
            " int c = 3;\n"
        )
        self.assertEqual([], extract_added_todos(diff))

    def test_added_todo_in_renamed_file_uses_new_path(self):
        """A rename with an added TODO attributes it to the file's new path."""
        diff = (
            "diff --git a/src/Old.java b/src/New.java\n"
            "similarity index 85%\n"
            "rename from src/Old.java\n"
            "rename to src/New.java\n"
            "--- a/src/Old.java\n"
            "+++ b/src/New.java\n"
            "@@ -20,0 +21,1 @@ class Renamed {\n"
            "+    // TODO: finish the rename cleanup\n"
        )
        self.assertEqual(
            [("src/New.java", 21, "// TODO: finish the rename cleanup")],
            extract_added_todos(diff))

    def test_multiple_hunks_track_line_numbers_independently(self):
        """Each hunk resets the new-file counter from its own header."""
        diff = (
            "diff --git a/src/Baz.java b/src/Baz.java\n"
            "--- a/src/Baz.java\n"
            "+++ b/src/Baz.java\n"
            "@@ -1,0 +2,1 @@\n"
            "+// TODO: first\n"
            "@@ -40,0 +50,1 @@\n"
            "+// TODO: second\n"
        )
        self.assertEqual(
            [("src/Baz.java", 2, "// TODO: first"),
             ("src/Baz.java", 50, "// TODO: second")],
            extract_added_todos(diff))

    def test_removed_todo_is_not_listed(self):
        """A TODO the branch removes is not an added TODO."""
        diff = (
            "diff --git a/src/Qux.java b/src/Qux.java\n"
            "--- a/src/Qux.java\n"
            "+++ b/src/Qux.java\n"
            "@@ -3,1 +3,0 @@\n"
            "-// TODO: deleted on this branch\n"
        )
        self.assertEqual([], extract_added_todos(diff))

    def test_identifier_containing_todo_is_not_a_marker(self):
        """A word that merely contains the letters TODO is not a TODO marker."""
        diff = (
            "diff --git a/src/List.java b/src/List.java\n"
            "--- a/src/List.java\n"
            "+++ b/src/List.java\n"
            "@@ -1,0 +1,1 @@\n"
            "+        TODOList items = new TODOList();\n"
        )
        self.assertEqual([], extract_added_todos(diff))

    def test_removed_line_whose_content_starts_dashes_does_not_shift_lines(self):
        """A removed body line shown as "----" does not advance the new-file counter."""
        # The removed line's own content is "---" (e.g. a Markdown rule or YAML
        # separator), so git renders it as "----". It is a removal, not a file
        # header, and must not push the following added TODO's line number up.
        diff = (
            "diff --git a/src/Doc.md b/src/Doc.md\n"
            "--- a/src/Doc.md\n"
            "+++ b/src/Doc.md\n"
            "@@ -3,1 +3,1 @@\n"
            "----\n"
            "+<!-- TODO: after a removed rule -->\n"
        )
        self.assertEqual(
            [("src/Doc.md", 3, "<!-- TODO: after a removed rule -->")],
            extract_added_todos(diff))

    def test_added_line_whose_content_starts_plus_is_examined(self):
        """An added body line shown as "+++..." is a real addition, not a file header."""
        # The added line's own content is "++i;" (a C/Java pre-increment at
        # column 0), so git renders it as "+++i;". It must be examined for a
        # TODO marker rather than skipped as a "+++ " file header.
        diff = (
            "diff --git a/src/Inc.c b/src/Inc.c\n"
            "--- a/src/Inc.c\n"
            "+++ b/src/Inc.c\n"
            "@@ -5,0 +5,2 @@\n"
            "+++i; // TODO: fix the increment\n"
            "+normal();\n"
        )
        self.assertEqual(
            [("src/Inc.c", 5, "++i; // TODO: fix the increment")],
            extract_added_todos(diff))

    def test_render_lists_each_todo_with_file_and_line(self):
        """The rendered section lists each TODO with its file:line."""
        section = render([("src/Foo.java", 11, "// TODO(review): x")])
        self.assertIn("New TODOs this branch adds", section)
        self.assertIn("`src/Foo.java:11` — // TODO(review): x", section)

    def test_render_says_none_when_empty(self):
        """With no TODOs the section still renders, saying there are none."""
        section = render([])
        self.assertIn("None", section)


if __name__ == "__main__":
    unittest.main()
