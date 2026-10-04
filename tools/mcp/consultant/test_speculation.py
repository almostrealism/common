"""Tests for speculation detection in the consultant QC scripts.

``scripts/evaluate_dataset.py``, ``scripts/validate_responses.py`` and
``qc/analyze_history.py`` each used to carry their own copy of the phrase
list and the check. The copies drifted: the two ``scripts/`` copies listed
"I can infer" with a capital letter while matching against lower-cased text,
so that phrase could never match; they lacked the "might be" / "could be"
hedges that SYSTEM_PROMPT forbids; and they raised on a missing answer. All
three now use ``has_speculation`` from ``tools/mcp/common/inference.py``, and
these tests pin that every script reports what the shared check reports.
"""

import importlib.util
import os
import sys
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_COMMON = os.path.join(_HERE, "..", "common")
sys.path.insert(0, _HERE)
sys.path.insert(1, _COMMON)


def _load(relative_path, name):
    """Load a consultant script by path; the script directories are not packages."""
    spec = importlib.util.spec_from_file_location(name, os.path.join(_HERE, relative_path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evaluate_dataset = _load("scripts/evaluate_dataset.py", "evaluate_dataset")
validate_responses = _load("scripts/validate_responses.py", "validate_responses")
analyze_history = _load("qc/analyze_history.py", "analyze_history")
common_inference = _load("../common/inference.py", "common_inference_under_test")

SCRIPTS = {
    "evaluate_dataset": evaluate_dataset.has_speculation,
    "validate_responses": validate_responses.has_speculation,
    "analyze_history": analyze_history.has_speculation,
}

ANSWERS = {
    "The documentation DOES NOT CONTAIN that.": True,
    "This is hypothetical.": True,
    "I can infer it works.": True,
    "It might be cached.": True,
    "It could be cached.": True,
    "Use cp() per collection.md:4.": False,
    "": False,
    None: False,
}


class SpeculationDetectionTest(unittest.TestCase):
    """Every QC script flags exactly the answers the shared check flags."""

    def test_shared_check(self):
        for answer, expected in ANSWERS.items():
            with self.subTest(answer=answer):
                self.assertEqual(expected, common_inference.has_speculation(answer))

    def test_scripts_agree_with_shared_check(self):
        for name, check in SCRIPTS.items():
            for answer, expected in ANSWERS.items():
                with self.subTest(script=name, answer=answer):
                    self.assertEqual(expected, check(answer))

    def test_phrases_are_lower_case(self):
        for phrase in common_inference.SPECULATION_PHRASES:
            with self.subTest(phrase=phrase):
                self.assertEqual(phrase.lower(), phrase)
                self.assertTrue(common_inference.has_speculation(phrase.upper()))


if __name__ == "__main__":
    unittest.main()
