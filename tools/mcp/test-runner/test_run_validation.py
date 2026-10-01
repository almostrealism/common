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
        # A single bare class is now a bounded run and accepted (see
        # test_bare_class_accepted); what the caps still refuse is a selection
        # with no ceiling. Six bare classes exceed the 5-class cap, so the
        # selection is rejected.
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": [f"FooTest{i}" for i in range(6)]})
        self.assertIn("test classes", ctx.exception.error)

    def test_bare_class_with_jmx_monitoring_still_rejected(self):
        # jmx_monitoring is caller-controlled and never buys an exemption from
        # the caps -- an over-cap selection is rejected whether or not it is
        # set, so a caller cannot widen a run past the caps just by setting
        # jmx_monitoring:true.
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": [f"FooTest{i}" for i in range(6)],
                       "jmx_monitoring": True})
        self.assertIn("test classes", ctx.exception.error)

    def test_comma_delimiter_in_test_classes_still_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({"test_classes": ["FooTest#first,BarTest#second"]})

    def test_plus_method_separator_in_test_classes_rejected(self):
        # Surefire treats "+" as a method-list separator, so
        # "FooTest#first+second" packs two methods into a single entry,
        # evading the per-entry caps -- each must be its own entry.
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": ["FooTest#first+second"]})
        self.assertIn("+", ctx.exception.error)

    def test_surefire_negation_and_regex_in_test_classes_rejected(self):
        # "!" negates a selector (running every other test) and "%regex[...]"
        # selects by pattern; neither is an exact Java name.
        for selector in ("!FooTest#bar", "FooTest#!bar",
                         "%regex[Foo|Bar]#bar", "FooTest#%regex[b.r]",
                         "FooTest#bar[1]", "org/example/FooTest#bar"):
            with self.subTest(selector=selector):
                with self.assertRaises(ValidationError) as ctx:
                    _validate({"test_classes": [selector]})
                self.assertIn("not an exact Java name", ctx.exception.error)

    def test_surefire_negation_and_regex_in_test_methods_rejected(self):
        for entry in ({"class": "!FooTest", "method": "bar"},
                      {"class": "FooTest", "method": "!bar"},
                      {"class": "%regex[Foo|Bar]", "method": "bar"},
                      {"class": "FooTest", "method": "%regex[b.r]"}):
            with self.subTest(entry=entry):
                with self.assertRaises(ValidationError) as ctx:
                    _validate({"test_methods": [entry]})
                self.assertIn("not an exact Java name", ctx.exception.error)

    def test_package_qualified_selector_accepted(self):
        result = _validate({"test_classes": ["org.example.FooTest#testBar"]})
        self.assertEqual(["org.example.FooTest#testBar"], result["test_classes"])
        result = _validate({"test_methods": [
            {"class": "org.example.FooTest", "method": "testBar"}]})
        self.assertEqual("testBar", result["test_methods"][0]["method"])

    def test_plus_method_separator_in_test_methods_method_field_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_methods": [{"class": "FooTest", "method": "first+second"}]})
        self.assertIn("+", ctx.exception.error)

    def test_neither_test_classes_nor_test_methods_rejected(self):
        with self.assertRaises(ValidationError):
            _validate({})

    def test_both_test_classes_and_test_methods_rejected(self):
        # test_classes and test_methods are now honoured together (see
        # test_mixed_classes_and_methods_within_caps_accepted), but the
        # combined selection must still fit the caps: five classes plus a
        # method naming a sixth distinct class exceeds the 5-class cap.
        with self.assertRaises(ValidationError) as ctx:
            _validate({
                "test_classes": [f"FooTest{i}" for i in range(5)],
                "test_methods": [{"class": "BazTest", "method": "testQux"}],
            })
        self.assertIn("test classes", ctx.exception.error)

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

    def test_bare_class_accepted(self):
        # A single bare class names a bounded number of cases, so it is a
        # bounded run and accepted now that the one-test-per-invocation rule
        # has been relaxed to the class/method caps.
        result = _validate({"test_classes": ["FooTest"]})
        self.assertEqual(["FooTest"], result["test_classes"])

    def test_multiple_classes_at_cap_accepted(self):
        classes = [f"FooTest{i}" for i in range(5)]
        result = _validate({"test_classes": classes})
        self.assertEqual(classes, result["test_classes"])

    def test_classes_over_cap_rejected(self):
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_classes": [f"FooTest{i}" for i in range(6)]})
        self.assertIn("test classes", ctx.exception.error)

    def test_methods_at_cap_accepted(self):
        methods = [{"class": "FooTest", "method": f"testBar{i}"} for i in range(40)]
        result = _validate({"test_methods": methods})
        self.assertEqual(40, len(result["test_methods"]))

    def test_methods_over_cap_rejected(self):
        methods = [{"class": "FooTest", "method": f"testBar{i}"} for i in range(41)]
        with self.assertRaises(ValidationError) as ctx:
            _validate({"test_methods": methods})
        self.assertIn("test methods", ctx.exception.error)

    def test_mixed_classes_and_methods_within_caps_accepted(self):
        # The validator now honours test_classes and test_methods together, so
        # a bounded mix of the two is accepted (server.py merges both into one
        # -Dtest rather than silently dropping either).
        result = _validate({
            "test_classes": ["FooTest", "BarTest"],
            "test_methods": [{"class": "BazTest", "method": "testQux"}],
        })
        self.assertEqual(["FooTest", "BarTest"], result["test_classes"])
        self.assertEqual(1, len(result["test_methods"]))


if __name__ == "__main__":
    unittest.main()
