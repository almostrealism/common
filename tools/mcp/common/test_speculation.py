"""Tests for speculation detection in the consultant QC scripts.

``scripts/evaluate_dataset.py``, ``scripts/validate_responses.py`` and
``qc/analyze_history.py`` each used to carry their own copy of the phrase
list and the check. The copies drifted: the two ``scripts/`` copies listed
"I can infer" with a capital letter while matching against lower-cased text,
so that phrase could never match; they lacked the "might be" / "could be"
hedges that SYSTEM_PROMPT forbids; and they raised on a missing answer. All
three now use ``has_speculation`` from ``tools/mcp/common/inference.py``, and
these tests pin that every script reports what the shared check reports.

This suite lives beside ``test_inference.py`` in ``tools/mcp/common`` because
that is the directory CI's ``python-tests`` job discovers; it reaches up into
``tools/mcp/consultant`` to load the scripts it checks.
"""

import importlib.util
import os
import sys
import types
import unittest
from unittest.mock import patch

_HERE = os.path.dirname(os.path.abspath(__file__))
_CONSULTANT = os.path.join(_HERE, "..", "consultant")
# The consultant scripts each self-configure sys.path at import time, but put
# the consultant and common directories on the path first so the plain
# ``from inference import ...`` / ``from docs_retriever import ...`` imports
# they perform resolve regardless of load order.
sys.path.insert(0, _CONSULTANT)
sys.path.insert(1, _HERE)


def _load(relative_path, name):
    """Load a module by path; the script directories are not packages."""
    spec = importlib.util.spec_from_file_location(name, os.path.join(_HERE, relative_path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_validate_responses():
    """Load ``validate_responses`` with the ``server`` import stubbed.

    ``validate_responses`` does ``from server import consult`` at import
    time. Importing the retired consultant ``server`` module runs live side
    effects at load: it instantiates ``DocsRetriever``/``HistoryStore``
    (opening the configured history database) and ``create_backend`` (which,
    under the default ``auto`` backend, probes the LLM endpoints over HTTP).
    The import there is guarded only against ``ImportError``, so if it gets
    far enough to run those constructors, an unwritable history directory or
    an unreachable endpoint raises an uncaught error that would abort
    discovery of the whole common suite. These string-check tests never call
    ``consult``, so a no-op stub stands in for the module while the script is
    loaded.

    The stub is installed only for the duration of this load and the previous
    ``sys.modules["server"]`` entry (if any) is restored afterward. Leaving
    the stub cached would break the ar-manager tests: both
    ``manager_test_support.py`` and ``test_dispatch_capable.py`` do
    ``import server`` and then call ``server._set_scopes(...)``, which the
    stub does not define. CI loads the manager and common suites in separate
    interpreters, but a single-interpreter discovery over ``tools/mcp`` must
    stay isolated regardless of load order.
    """
    stub = types.ModuleType("server")
    stub.consult = lambda *args, **kwargs: {}
    with patch.dict(sys.modules, {"server": stub}):
        return _load("../consultant/scripts/validate_responses.py", "validate_responses")


evaluate_dataset = _load("../consultant/scripts/evaluate_dataset.py", "evaluate_dataset")
validate_responses = _load_validate_responses()
analyze_history = _load("../consultant/qc/analyze_history.py", "analyze_history")
common_inference = _load("inference.py", "common_inference_under_test")

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


class EvaluateDatasetNullResponseTest(unittest.TestCase):
    """A null llm_response must not abort dataset evaluation.

    Exported history records can carry ``llm_response: null``. The evaluator
    functions take ``len(response)`` and slice it, so a ``None`` there raised
    ``TypeError`` and aborted the whole run. Both evaluator paths must treat a
    missing answer as the empty string. Empty keywords keep the retriever
    untouched, so these cases exercise the response handling in isolation.
    """

    def _null_record(self):
        return {
            "id": 1,
            "timestamp": "2026-01-01T00:00:00Z",
            "input_params": "{}",
            "llm_response": None,
        }

    def test_evaluate_record_handles_null_response(self):
        result = evaluate_dataset.evaluate_record(self._null_record(), retriever=None, keywords=[])
        self.assertEqual(0, result["goal3_other_failure"]["response_length"])
        self.assertFalse(result["goal3_other_failure"]["speculation_detected"])
        self.assertFalse(result["goal3_other_failure"]["is_verbose"])
        self.assertEqual("", result["response_preview"])

    def test_evaluate_with_augmented_handles_null_response(self):
        result = evaluate_dataset.evaluate_with_augmented(self._null_record(), aug={}, retriever=None)
        self.assertEqual(0, result["goal3_other_failure"]["response_length"])
        self.assertFalse(result["goal3_other_failure"]["speculation_detected"])
        self.assertFalse(result["goal3_other_failure"]["is_verbose"])
        self.assertEqual("", result["response_preview"])


if __name__ == "__main__":
    unittest.main()
