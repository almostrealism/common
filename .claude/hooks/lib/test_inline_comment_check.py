#!/usr/bin/env python3
"""Tests for the inline-comment policy shared by the edit and shell guards."""

import unittest

import inline_comment_check as check


class GovernsTest(unittest.TestCase):

    def test_source_files_are_governed(self):
        self.assertTrue(check.governs("src/Foo.java"))
        self.assertTrue(check.governs("a/b/Thing.ts"))

    def test_other_files_are_not(self):
        self.assertFalse(check.governs("README.md"))
        self.assertFalse(check.governs("script.py"))


class CommentTextTest(unittest.TestCase):

    def test_reads_the_comment(self):
        self.assertEqual("hello", check.comment_text("int x; // hello"))

    def test_a_url_is_not_a_comment(self):
        self.assertIsNone(check.comment_text(' String s = "http://example.com";'))

    def test_a_line_without_one_reads_as_none(self):
        self.assertIsNone(check.comment_text("int x = 1;"))


class MeasureTest(unittest.TestCase):

    def test_counts_a_run_of_full_line_comments(self):
        text = "\n".join("// line %d" % i for i in range(6))
        self.assertEqual(6, check.measure([text])["worst_run"])

    def test_javadoc_is_exempt(self):
        text = "/**\n * design rationale\n * more of it\n */\nint x;"
        measured = check.measure([text])

        self.assertEqual(0, measured["worst_run"])
        self.assertEqual(0, measured["total"])

    def test_a_trailing_comment_breaks_the_run(self):
        text = "// one\n// two\nint x; // three\n// four"
        self.assertEqual(2, check.measure([text])["worst_run"])

    def test_total_accumulates_across_fragments(self):
        measured = check.measure(["// aaaa", "// bbbb"])
        self.assertEqual(8, measured["total"])


class DecideTest(unittest.TestCase):

    def test_a_short_comment_is_allowed(self):
        self.assertIsNone(check.decide("Foo.java", ["// brief nuance"]))

    def test_a_wall_of_comments_is_refused(self):
        text = "\n".join("// narrating what I did here" for _ in range(8))
        reason = check.decide("Foo.java", [text])

        self.assertIsNotNone(reason)
        self.assertIn("consecutive", reason)

    def test_one_very_long_comment_is_refused(self):
        reason = check.decide("Foo.java", ["// " + ("x" * 200)])

        self.assertIsNotNone(reason)
        self.assertIn("single // comment", reason)

    def test_splitting_the_same_text_does_not_evade(self):
        """Volume is measured in total, so breaking up the run changes nothing.

        Each run here is two lines, well inside the run limit, and no single
        comment is long. Only the total gives it away.
        """
        block = "// %s\n// %s\nint x;\n" % ("y" * 50, "y" * 50)
        text = block * 4

        reason = check.decide("Foo.java", [text])

        self.assertIsNotNone(reason)
        self.assertIn("in one edit", reason)

    def test_a_file_outside_the_policy_is_left_alone(self):
        text = "\n".join("// line %d" % i for i in range(20))
        self.assertIsNone(check.decide("notes.md", [text]))


class AddedTextTest(unittest.TestCase):

    def test_reads_every_edit_tool_shape(self):
        self.assertEqual(["w"], check.added_text({"content": "w"}))
        self.assertEqual(["e"], check.added_text({"new_string": "e"}))
        self.assertEqual(["m"], check.added_text({"edits": [{"new_string": "m"}]}))
        self.assertEqual(["n"], check.added_text({"new_source": "n"}))


if __name__ == "__main__":
    unittest.main()
