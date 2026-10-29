"""Tests for the tolerance-widening check (#5) of ``.claude/hooks/warn-test-deception.sh``.

The hook reads an Edit payload on stdin and exits 2 when an edit to a test
file widens an ``assertEquals``/``assertArrayEquals`` delta more than twice.
It once took the last number in *any* such call as the delta, so raising an
expected count (``assertEquals(2, x.size())`` -> ``assertEquals(7, x.size())``)
was refused as a widened tolerance. A delta is now the last of three or more
top-level arguments, and only when it is a numeric literal. These tests hold
both halves: the expected-value edits pass, and real widenings are still refused.
"""

import json
import os
import subprocess
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_HOOK = os.path.join(_REPO_ROOT, ".claude", "hooks", "warn-test-deception.sh")
_TEST_FILE = "/repo/engine/utils/src/test/java/org/example/FooTest.java"


def run_edit(old, new, path=_TEST_FILE):
    """Runs the hook on one Edit payload and returns the completed process."""
    payload = {
        "tool_name": "Edit",
        "tool_input": {"file_path": path, "old_string": old, "new_string": new},
    }
    return subprocess.run(["bash", _HOOK], input=json.dumps(payload),
                          capture_output=True, text=True, timeout=30)


class ToleranceWideningTest(unittest.TestCase):
    """Check #5: which assertEquals arguments count as a delta."""

    def assertAllowed(self, old, new):
        result = run_edit(old, new)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("tolerance", result.stderr)

    def assertBlocked(self, old, new):
        result = run_edit(old, new)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("tolerance widened > 2x", result.stderr)

    def test_two_argument_expected_count_raised_is_allowed(self):
        """The reported false positive: a two-argument call has no delta."""
        self.assertAllowed("assertEquals(2, keys.size());",
                           "assertEquals(7, keys.size());")

    def test_three_argument_expected_value_with_computed_delta_is_allowed(self):
        """A delta that is not a literal is not compared."""
        self.assertAllowed("assertEquals(2.0, x, tolerance());",
                           "assertEquals(70.0, x, tolerance());")

    def test_nested_call_arguments_are_not_top_level(self):
        """Commas inside a nested call do not split the outer arguments."""
        self.assertAllowed("assertEquals(1, f(a, b, 2));",
                           "assertEquals(1, f(a, b, 90));")

    def test_comma_inside_string_literal_is_not_an_argument_separator(self):
        """A message containing commas and parentheses is a single argument."""
        self.assertAllowed('assertEquals("a, (b), 3", 2);',
                           'assertEquals("a, (b), 3", 9);')

    def test_three_argument_delta_widened_is_blocked(self):
        self.assertBlocked("assertEquals(1.0, x, 0.001);",
                           "assertEquals(1.0, x, 0.1);")

    def test_four_argument_delta_widened_is_blocked(self):
        """The message form puts the delta last as well."""
        self.assertBlocked('assertEquals("value", 1.0, x, 1e-6);',
                           'assertEquals("value", 1.0, x, 1e-3);')

    def test_array_delta_with_type_suffix_widened_is_blocked(self):
        self.assertBlocked("assertArrayEquals(expected, actual, 0.001f);",
                           "assertArrayEquals(expected, actual, 0.5f);")

    def test_multiline_call_is_parsed(self):
        self.assertBlocked("assertEquals(expected(1, 2),\n    actual,\n    0.01);",
                           "assertEquals(expected(1, 2),\n    actual,\n    1.0);")

    def test_delta_tightened_is_allowed(self):
        self.assertAllowed("assertEquals(1.0, x, 0.1);",
                           "assertEquals(1.0, x, 0.001);")

    def test_small_increase_warns_without_blocking(self):
        """A widening within 2x is reported but not refused."""
        result = run_edit("assertEquals(1.0, x, 0.01);", "assertEquals(1.0, x, 0.015);")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("tolerance increased", result.stderr)

    def test_non_test_file_is_ignored(self):
        result = run_edit("assertEquals(1.0, x, 0.001);", "assertEquals(1.0, x, 0.1);",
                          path="/repo/engine/utils/src/main/java/org/example/Foo.java")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)


if __name__ == "__main__":
    unittest.main()
