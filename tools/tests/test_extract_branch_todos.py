"""Guard: the branch-TODO extractor reports real markers, not prose.

``extract_branch_todos.py`` feeds the auto-review and auto-resolve prompts the
list of TODO markers a branch adds, so an agent treats each as a finding to fix
or file. The extractor therefore has to tell an actionable marker (``// TODO``,
``# TODO:``, ``TODO(review):``) apart from the word "TODO" written in prose —
documentation, prompt text, or a rule that describes the TODO convention
itself. Mistaking prose for a marker floods the findings with references to the
mechanism rather than real work; this branch's own diff, which centralises the
new-TODO rule into prompt prose, would otherwise contribute dozens of them.

These tests pin the classification (:func:`extract_added_todos` via the
``_MARKER`` rule) and the diff bookkeeping — added lines counted into the new
file, removed lines not, renames attributed to the new path.
"""

import os
import sys
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_REPO_ROOT, "tools", "ci"))

import extract_branch_todos as extractor  # noqa: E402


def _diff(path, added_lines, start=1):
    """Build a minimal unified diff that adds ``added_lines`` to ``path``.

    The hunk is framed as a pure addition at ``start`` in the new file, which is
    enough for :func:`extract_added_todos` to exercise its added-line counter.

    :param path: the new-file path to report the additions under
    :param added_lines: the content lines added (without the leading ``+``)
    :param start: the 1-based new-file line the hunk begins at
    :returns: unified-diff text
    """
    header = ("diff --git a/" + path + " b/" + path + "\n"
              "--- a/" + path + "\n"
              "+++ b/" + path + "\n"
              "@@ -0,0 +" + str(start) + "," + str(len(added_lines)) + " @@\n")
    return header + "".join("+" + line + "\n" for line in added_lines)


class MarkerClassificationTest(unittest.TestCase):
    """Only actionable markers are reported; prose mentioning TODO is not."""

    def _added_texts(self, lines):
        """Return the stripped text of each reported TODO in a one-file diff."""
        return [text for _, _, text in
                extractor.extract_added_todos(_diff("src/Foo.java", lines))]

    def test_comment_markers_without_a_colon_are_reported(self):
        """The dominant ``// TODO fix`` form — no colon — is still a finding."""
        self.assertEqual(
            ["// TODO fix this later", "# TODO drop the shim", "/* TODO revisit */"],
            self._added_texts([
                "\t\t// TODO fix this later",
                "    # TODO drop the shim",
                "    /* TODO revisit */",
            ]))

    def test_colon_and_owner_markers_are_reported(self):
        """``TODO:``, ``TODO(owner):`` and the ``TODO(review):`` note all match,
        including as a standalone marker in a file with no comment syntax."""
        self.assertEqual(
            ["// TODO: implement", "TODO(review): a note a review pass left",
             "# TODO(alice): wire this up"],
            self._added_texts([
                "\t\t// TODO: implement",
                "TODO(review): a note a review pass left",
                "  # TODO(alice): wire this up",
            ]))

    def test_leading_javadoc_star_marker_is_reported(self):
        """A javadoc-continuation ``* TODO`` is a marker; ``* fix every TODO``
        (prose after the star) is not."""
        self.assertEqual(
            ["* TODO finish the proof"],
            self._added_texts([
                "\t * TODO finish the proof",
                "\t * this still needs to handle every TODO the author left",
            ]))

    def test_prose_mentioning_todo_is_not_reported(self):
        """The word TODO in documentation/prompt prose — even inside a ``#``
        comment or after a comment opener but not adjacent to it — is ignored."""
        self.assertEqual([], self._added_texts([
            "every TODO the branch adds is a finding to fix or file",
            "Resolve every TODO in that list in exactly one of two ways:",
            "the `TODO(review)` notes a review pass leaves for itself: the next",
            "# The TODO lines this branch adds are findings every route forwards",
            "TODO someone already left in the code. The TODOs this branch adds",
            "- the **new-TODO findings rule** — every `TODO` this branch adds",
        ]))

    def test_prose_quoting_the_colon_marker_mid_line_is_not_reported(self):
        """A ``TODO:`` / ``TODO(review):`` quoted mid-sentence — as the extractor's
        own docstring and these fixtures do — is prose, not a standalone marker,
        so the line must begin with the marker before it counts."""
        self.assertEqual([], self._added_texts([
            "the token is written as an explicit TODO: / TODO(owner): marker,",
            "describe the TODO(review): note a review pass leaves",
            '    "TODO(review): a note a review pass left",',
        ]))

    def test_line_beginning_with_the_colon_marker_is_reported(self):
        """A line that genuinely begins with ``TODO:``/``TODO(review):`` — a
        standalone marker in a file with no comment syntax — is still a finding,
        allowing leading whitespace."""
        self.assertEqual(
            ["TODO: standalone marker", "TODO(review): a note a review pass left"],
            self._added_texts([
                "TODO: standalone marker",
                "\tTODO(review): a note a review pass left",
            ]))

    def test_plural_todos_token_is_not_reported(self):
        """``TODOs`` is a different word; the ``\\bTODO\\b`` boundary excludes it
        even right after a comment opener."""
        self.assertEqual([], self._added_texts([
            "## New TODOs are findings",
            "# New TODOs this branch adds",
        ]))

    def test_todo_substring_identifier_is_not_reported(self):
        """An identifier such as ``TODOList`` is not a marker."""
        self.assertEqual([], self._added_texts([
            "\t\tTODOList todos = new TODOList();",
            "// a TODOList holds the markers",
        ]))


class DiffBookkeepingTest(unittest.TestCase):
    """Line numbering and rename/deletion handling around marker lines."""

    def test_added_marker_line_number_tracks_the_new_file(self):
        """A marker's reported line is its 1-based position in the new file,
        with context lines advancing the counter and removed lines not."""
        diff = (
            "diff --git a/src/Foo.java b/src/Foo.java\n"
            "--- a/src/Foo.java\n"
            "+++ b/src/Foo.java\n"
            "@@ -10,2 +10,3 @@\n"
            " context line stays\n"
            "-old line removed\n"
            "+\t// TODO handle the new case\n"
            "+\tint done = 1;\n")
        self.assertEqual(
            [("src/Foo.java", 11, "// TODO handle the new case")],
            extractor.extract_added_todos(diff))

    def test_rename_attributes_markers_to_the_new_path(self):
        """With ``-M`` the diff names the new path in ``+++``; markers follow it."""
        diff = (
            "diff --git a/old/Name.py b/new/Name.py\n"
            "similarity index 90%\n"
            "rename from old/Name.py\n"
            "rename to new/Name.py\n"
            "--- a/old/Name.py\n"
            "+++ b/new/Name.py\n"
            "@@ -1,0 +2,1 @@\n"
            "+    # TODO: port the helper\n")
        self.assertEqual(
            [("new/Name.py", 2, "# TODO: port the helper")],
            extractor.extract_added_todos(diff))

    def test_deleted_file_additions_are_not_attributed(self):
        """A ``+++ /dev/null`` section has no new path, so nothing is reported."""
        diff = (
            "diff --git a/src/Gone.java b/src/Gone.java\n"
            "--- a/src/Gone.java\n"
            "+++ /dev/null\n"
            "@@ -1,1 +0,0 @@\n"
            "-\t// TODO this line is being removed\n")
        self.assertEqual([], extractor.extract_added_todos(diff))


class RenderTest(unittest.TestCase):
    """The Markdown section reflects whether any markers were found."""

    def test_empty_list_renders_the_none_line(self):
        """No markers yields the explicit 'adds no TODO lines' section."""
        rendered = extractor.render([])
        self.assertIn("## New TODOs this branch adds", rendered)
        self.assertIn("None", rendered)

    def test_populated_list_renders_each_marker(self):
        """Each marker becomes a ``- `path:line` — text`` bullet."""
        rendered = extractor.render([("src/Foo.java", 12, "// TODO fix")])
        self.assertIn("- `src/Foo.java:12` — // TODO fix", rendered)


if __name__ == "__main__":
    unittest.main()
