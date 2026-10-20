"""Tests for the scripted evaluation mode and the graders it relies on.

``scripted`` runs the live consult pipeline with canned model answers, so the
pipeline's handling of known-wrong answers — flagging, demoting, refusing —
is measured without a model. These tests pin the mechanics (placeholder
resolution, the fixture memory client, cumulative milestones, the new grader
keys) and one end-to-end run over the committed cases.
"""

import os
import sys
import unittest

_EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
if _EVAL_DIR not in sys.path:
    sys.path.insert(0, _EVAL_DIR)

import run_consult_eval  # noqa: E402
from graders import grade, resolve_expect, validate_case  # noqa: E402
from run_consult_eval import (  # noqa: E402
    FixtureMemoryClient, ScriptedModel, build_report, load_cases,
    observed_response,
)

from consult_evidence import EvidenceList  # noqa: E402


def _case(cid):
    return next(c for c in load_cases() if c["id"] == cid)


class TestCumulativeMilestones(unittest.TestCase):

    def test_a_later_step_keeps_an_earlier_steps_bar(self):
        case = {"id": "X", "question": "q", "expect": {"outcome": "abstain"},
                "milestones": {"step2": {"outcome": "answer", "must_include": ["a"]},
                               "step7": {"max_citation_warnings": 0}}}
        self.assertEqual(resolve_expect(case, "step12"),
                         {"outcome": "answer", "must_include": ["a"],
                          "max_citation_warnings": 0})
        self.assertEqual(resolve_expect(case, "step5"),
                         {"outcome": "answer", "must_include": ["a"]})
        self.assertEqual(resolve_expect(case, "step1"), {"outcome": "abstain"})

    def test_a_later_override_replaces_an_earlier_one(self):
        case = {"id": "X", "question": "q", "expect": {"outcome": "abstain"},
                "milestones": {"step9": {"outcome": "nonexistent"},
                               "step12": {"outcome": "answer"}}}
        self.assertEqual(resolve_expect(case, "step12")["outcome"], "answer")

    def test_a_named_milestone_applies_only_itself(self):
        case = {"id": "X", "question": "q", "expect": {"outcome": "abstain"},
                "milestones": {"step2": {"outcome": "answer"},
                               "custom": {"must_include": ["z"]}}}
        self.assertEqual(resolve_expect(case, "custom"),
                         {"outcome": "abstain", "must_include": ["z"]})


class TestNewGraderKeys(unittest.TestCase):

    def test_must_mark_unsupported_reads_marks_and_warnings(self):
        expect = {"must_mark_unsupported": ["project/doc-"]}
        marked = {"ok": True, "answer": "Prefix `project/doc-*` [D3]. [unsupported]"}
        self.assertTrue(grade(marked, expect).passed)
        warned = {"ok": True, "answer": "x",
                  "citation_warnings": [{"sentence": "uses project/doc-*",
                                         "reason": "subject"}]}
        self.assertTrue(grade(warned, expect).passed)
        silent = {"ok": True, "answer": "Prefix `project/doc-*` [D3]."}
        self.assertFalse(grade(silent, expect).passed)

    def test_min_citation_warnings_and_min_conflicts(self):
        expect = {"min_citation_warnings": 1, "min_conflicts": 1}
        self.assertFalse(grade({"ok": True, "answer": "a"}, expect).passed)
        self.assertTrue(grade({"ok": True, "answer": "a",
                               "citation_warnings": [{"sentence": "s"}],
                               "conflicts": [{"items": ["N1", "I1"]}]},
                              expect).passed)

    def test_history_must_include(self):
        expect = {"history_must_include": ["other runners"]}
        self.assertTrue(grade({"ok": True, "history": "for other runners [N1]"},
                              expect).passed)
        self.assertFalse(grade({"ok": True, "answer": "for other runners"},
                               expect).passed)

    def test_new_keys_are_type_checked(self):
        bad = {"id": "X", "question": "q",
               "expect": {"min_conflicts": -1, "must_mark_unsupported": "x",
                          "history_must_include": ["("]},
               "scripted": "", "branch_states": []}
        errors = " ".join(validate_case(bad))
        for fragment in ("min_conflicts must be a non-negative integer",
                         "must_mark_unsupported must be a list of strings",
                         "history_must_include has invalid regex",
                         "'scripted' must be a non-empty string",
                         "'branch_states' must be an object"):
            self.assertIn(fragment, errors)


class TestScriptedModel(unittest.TestCase):

    def _evidence(self):
        ev = EvidenceList()
        ev.add_doc({"file": ".github/CLAUDE.md", "start": 849, "end": 861,
                    "context": "a"})
        ev.add_doc({"file": ".github/CLAUDE.md", "start": 909, "end": 921,
                    "context": "b"})
        ev.add_note({"id": "mem-1", "content": "n"})
        ev.add_index({"path": "tools/ci/x.sh", "line": 3, "label": "exit codes",
                      "value": "exit 3"})
        return ev

    def test_placeholders_resolve_to_presented_ids(self):
        model = ScriptedModel("A [D:.github/CLAUDE.md]. B [D:.github/CLAUDE.md@915]. "
                              "C [N:mem-1]. D [I:x.sh].")
        self.assertEqual(model.resolve(self._evidence()), "A [D1]. B [D2]. C [N1]. D [I1].")

    def test_unretrieved_evidence_becomes_an_unpresented_id(self):
        model = ScriptedModel("A [D:other.md]. B [N:missing].")
        self.assertEqual(model.resolve(self._evidence()), "A [D99]. B [N99].")

    def test_consult_records_the_prompt(self):
        model = ScriptedModel("x")
        model.consult("q", evidence=self._evidence())
        self.assertIn("[D1]", model.prompts[0])


class TestFixtureMemoryClient(unittest.TestCase):

    def test_search_honours_the_namespace(self):
        client = FixtureMemoryClient([{"id": "a", "namespace": "decisions"},
                                      {"id": "b"}])
        self.assertEqual([m["id"] for m in client.search("q", "default")], ["b"])
        self.assertEqual([m["id"] for m in client.search("q", "decisions")], ["a"])
        self.assertEqual([s["namespace"] for s in client.namespace_stats()],
                         ["decisions", "default"])


class TestObservedOnTheNineteenth(unittest.TestCase):
    """The deployed responses captured on 2026-10-19 are the failures the
    verifier exists to catch; replayed as observed, nothing flags them."""

    def test_replayed_responses_fail_their_flagging_expectation(self):
        cases = [_case(cid) for cid in ("E9", "E10", "E11")]
        summary = build_report(cases, {c["id"]: observed_response(c) for c in cases})
        self.assertEqual([c["passed"] for c in summary["cases"]], [False] * 3)
        self.assertEqual(summary["false_answer"], 3)

    def test_a_case_without_an_observed_response_has_none(self):
        self.assertIsNone(observed_response(_case("E12")))


class TestScriptedRun(unittest.TestCase):
    """One end-to-end scripted run over the committed cases at step 12."""

    @classmethod
    def setUpClass(cls):
        cls.cases = load_cases()
        cls.summary = build_report(cls.cases, run_consult_eval._run_scripted(cls.cases),
                                   "step12")

    def test_every_case_but_the_paraphrase_gap_passes(self):
        failing = {c["id"]: c["failures"] for c in self.summary["cases"]
                   if not c["passed"]}
        self.assertEqual(failing, {"E12": [
            "must_mark_unsupported: not flagged: 'one class per job'"]})

    def test_no_false_abstentions(self):
        self.assertEqual(self.summary["false_abstention"], 0)


if __name__ == "__main__":
    unittest.main()
