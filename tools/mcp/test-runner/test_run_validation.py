"""Unit tests for run_validation.py's "no broad test runs" enforcement at the
ar-test-runner MCP surface.

Run from the repo root::

    python3 -m unittest tools/mcp/test-runner/test_run_validation.py -v
"""

import sys
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from run_validation import ValidationError, validate_start_test_run_arguments  # noqa: E402


def _validate(arguments, default_timeout=15, max_timeout_minutes=40):
    return validate_start_test_run_arguments(arguments, default_timeout, max_timeout_minutes)


class TestValidateStartTestRunArguments(unittest.TestCase):

    def test_single_method_test_classes_accepted(self):
        result = _validate({"test_classes": ["FooTest#testBar"]})
        self.assertEqual(["FooTest#testBar"], result["test_classes"])

    def test_single_method_test_methods_accepted(self):
        result = _validate({"test_methods": [{"class": "FooTest", "method": "testBar"}]})
        self.assertEqual([{"class": "FooTest", "method": "testBar"}], result["test_methods"])

    def test_wildcard_method_in_test_classes_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": ["FooTest#test*"]})
        self.assertIn("wildcard", ctx.exception.error)

    def test_wildcard_class_in_test_classes_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": ["Foo*#bar"]})
        self.assertIn("wildcard", ctx.exception.error)

    def test_question_mark_wildcard_in_test_classes_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest#test?"]})

    def test_wildcard_in_test_methods_class_field_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_methods": [{"class": "Foo*", "method": "testBar"}]})
        self.assertIn("wildcard", ctx.exception.error)

    def test_wildcard_in_test_methods_method_field_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_methods": [{"class": "FooTest", "method": "test*"}]})
        self.assertIn("wildcard", ctx.exception.error)

    def test_empty_class_component_around_hash_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["#testBar"]})

    def test_empty_method_component_around_hash_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest#"]})

    def test_bare_class_without_jmx_monitoring_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest"]})

    def test_bare_class_with_jmx_monitoring_still_rejected(self):
        # jmx_monitoring is caller-controlled and cannot authenticate a
        # JVM-crash reproduction request, so it must never exempt the
        # bare-class check -- otherwise any caller could widen a
        # single-test invocation into a whole-class run just by setting
        # jmx_monitoring:true.
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": ["FooTest"], "jmx_monitoring": True})
        self.assertIn("jmx_monitoring", ctx.exception.error)

    def test_comma_delimiter_in_test_classes_still_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest#first,BarTest#second"]})

    def test_neither_test_classes_nor_test_methods_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({})

    def test_both_test_classes_and_test_methods_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({
                "test_classes": ["FooTest#testBar"],
                "test_methods": [{"class": "BazTest", "method": "testQux"}],
            })

    def test_ar_test_group_in_jvm_args_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({
                "test_classes": ["FooTest#testBar"],
                "jvm_args": ["-DAR_TEST_GROUP=2"],
            })
        self.assertIn("AR_TEST_GROUP", ctx.exception.error)

    def test_test_group_argument_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_group": 2})

    def test_timeout_over_ceiling_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest#testBar"], "timeout_minutes": 41})

    def test_timeout_within_ceiling_accepted(self):
        result = _validate({"test_classes": ["FooTest#testBar"], "timeout_minutes": 40})
        self.assertEqual(40, result["timeout_minutes"])


if __name__ == "__main__":
    unittest.main()
