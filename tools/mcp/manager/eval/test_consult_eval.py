"""Unit tests for the consult evaluation graders and case schema.

These run in CI (the manager Python-test job), unlike the model-backed runner.
They prove two things: the case file is a valid, loadable schema; and the
deterministic graders have teeth — they flag the 2026-10-17 observed failures
(E2's wrong exit code and misattributed citation, E3's stale-note guess) and
pass a hand-built correct answer. A grader that passed regardless of the
response would make the whole evaluation set worthless, so each teeth test
pairs a failing response with a passing one.
"""

import json
import os
import sys
import unittest

_EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
if _EVAL_DIR not in sys.path:
    sys.path.insert(0, _EVAL_DIR)

from graders import (  # noqa: E402
    grade, resolve_expect, response_outcome, named_absent_symbols,
    validate_case, KNOWN_OUTCOMES,
)
from run_consult_eval import (  # noqa: E402
    load_cases, observed_response, build_report, render_report,
)


class TestCaseSchema(unittest.TestCase):
    """The case file loads, validates, and its fixtures exist."""

    def test_cases_load_and_validate(self):
        cases = load_cases()
        self.assertEqual([c["id"] for c in cases], ["E1", "E2", "E3", "E4"])

    def test_every_expect_outcome_is_known(self):
        for case in load_cases():
            for label in ["expect", *case.get("milestones", {})]:
                expect = (case["expect"] if label == "expect"
                          else case["milestones"][label])
                outcome = expect.get("outcome")
                if outcome is not None:
                    self.assertIn(outcome, KNOWN_OUTCOMES)

    def test_fixture_files_exist_and_parse(self):
        for case in load_cases():
            for key in ("memories", "observed_response"):
                rel = case.get(key)
                if not rel:
                    continue
                path = os.path.join(_EVAL_DIR, rel)
                self.assertTrue(os.path.isfile(path), f"missing {path}")
                with open(path, encoding="utf-8") as handle:
                    json.load(handle)  # raises if malformed

    def test_validate_case_rejects_unknown_expect_key(self):
        bad = {"id": "X", "question": "q", "expect": {"must_glow": ["x"]}}
        errors = validate_case(bad)
        self.assertTrue(any("unknown keys" in e for e in errors))

    def test_validate_case_rejects_bad_outcome(self):
        bad = {"id": "X", "question": "q", "expect": {"outcome": "maybe"}}
        self.assertTrue(any("outcome" in e for e in validate_case(bad)))


class TestGraderTeeth(unittest.TestCase):
    """The graders must flag today's failures and pass a correct answer."""

    def _case(self, cid):
        return next(c for c in load_cases() if c["id"] == cid)

    def test_e2_observed_fails_on_exit_code_and_citation(self):
        case = self._case("E2")
        result = grade(observed_response(case), resolve_expect(case))
        self.assertFalse(result.passed)
        joined = " ".join(result.failures)
        self.assertIn("must_not_include", joined)  # "exit 2" present
        self.assertIn("must_not_cite", joined)     # claude-code-job.md:330

    def test_e2_correct_answer_passes(self):
        case = self._case("E2")
        good = {
            "ok": True,
            "answer": ("validate-agent-commit.sh rejects a change set whose "
                       "only content is edits to test files that exist on the "
                       "base branch, with no new test. It exits 3."),
            "sources": ["tools/ci/README.md"],
        }
        result = grade(good, resolve_expect(case))
        self.assertTrue(result.passed, result.failures)

    def test_e2_abstaining_on_exit_code_also_passes(self):
        # The expectation allows "the exit code is not documented" as an
        # alternative to citing exit 3 (must_include_any), as long as the
        # description is right and exit 2 is not asserted.
        case = self._case("E2")
        abstained = {
            "ok": True,
            "answer": ("validate-agent-commit.sh rejects a change set whose "
                       "only content is edits to test files that exist on the "
                       "base branch with no new test; the exit code is not "
                       "documented."),
            "sources": ["tools/ci/README.md"],
        }
        self.assertTrue(grade(abstained, resolve_expect(case)).passed)

    def test_e3_observed_surfaces_stale_guess(self):
        case = self._case("E3")
        result = grade(observed_response(case), resolve_expect(case))
        self.assertFalse(result.passed)  # runCorrectionSession is forbidden

    def test_e1_observed_is_a_false_abstention(self):
        case = self._case("E1")
        self.assertEqual(response_outcome(observed_response(case)), "abstain")
        # Baseline bar is "abstain", so the grader passes it even though it is
        # the wrong behaviour — which is why build_report counts it separately.
        self.assertTrue(grade(observed_response(case), resolve_expect(case)).passed)

    def test_e1_step2_documentation_answer_passes(self):
        case = self._case("E1")
        good = {
            "ok": True,
            "answer": ("Master Agent Dispatch runs doc-qa, defect-hunt, "
                       "coverage-qa, consolidation-qa, performance-qa, pdsl-qa "
                       "and qa-retrospective, with branch prefixes qa/docs-, "
                       "qa/defect-, qa/coverage-, qa/consolidate-, "
                       "qa/performance-, qa/pdsl- and qa/retro-. The open-PR "
                       "limit MAX_OPEN_PRS is 7."),
            "sources": [".github/CLAUDE.md"],
        }
        result = grade(good, resolve_expect(case, "step2"))
        self.assertTrue(result.passed, result.failures)

    def test_e1_step2_rejects_a_documentation_answer_missing_a_prefix(self):
        case = self._case("E1")
        incomplete = {
            "ok": True,
            "answer": ("Master Agent Dispatch runs doc-qa with prefix "
                       "qa/docs-. The open-PR limit is 7."),
            "sources": [".github/CLAUDE.md"],
        }
        self.assertFalse(grade(incomplete, resolve_expect(case, "step2")).passed)


class TestMustFlagStale(unittest.TestCase):
    """must_flag_stale needs the evidence field and the named-absent list."""

    def test_missing_evidence_field_fails(self):
        expect = {"must_flag_stale": ["ClaudeCodeJob"]}
        self.assertFalse(grade({"ok": True, "answer": "x"}, expect).passed)

    def test_absent_symbol_flagged_passes(self):
        expect = {"must_flag_stale": ["ClaudeCodeJob"]}
        response = {
            "ok": True,
            "answer": "x",
            "evidence": [
                {"id": "N1", "named_symbols": {"present": [],
                                               "absent": ["ClaudeCodeJob"]}},
            ],
        }
        self.assertTrue(grade(response, expect).passed)

    def test_named_absent_symbols_returns_none_without_evidence(self):
        self.assertIsNone(named_absent_symbols({"ok": True}))


class TestMustNotCiteWithToken(unittest.TestCase):
    """The ``with`` qualifier limits a forbidden citation to the sentence
    that actually makes the misattributed claim."""

    def _spec(self):
        return [{"file": "flowtree/runtime/docs/claude-code-job.md",
                 "lines": [320, 345], "with": "validate-agent-commit"}]

    def test_citation_in_unrelated_sentence_is_allowed(self):
        response = {
            "ok": True,
            "answer": ("detect-test-hiding.sh is described in "
                       "claude-code-job.md:330. validate-agent-commit.sh is a "
                       "separate script."),
        }
        self.assertTrue(grade(response, {"must_not_cite": self._spec()}).passed)

    def test_citation_in_the_claim_sentence_is_forbidden(self):
        response = {
            "ok": True,
            "answer": ("validate-agent-commit.sh exits 2 per "
                       "claude-code-job.md:330."),
        }
        self.assertFalse(grade(response, {"must_not_cite": self._spec()}).passed)


class TestBaselineReport(unittest.TestCase):
    """The replay-mode baseline over the frozen responses is the documented
    starting point: E1 false-abstains, E2/E3 are false answers, E4 is a correct
    abstention."""

    def setUp(self):
        self.cases = load_cases()
        self.responses = {c["id"]: observed_response(c) for c in self.cases}
        self.summary = build_report(self.cases, self.responses, "baseline")

    def test_counts(self):
        self.assertEqual(self.summary["false_abstention"], 1)  # E1
        self.assertEqual(self.summary["false_answer"], 2)      # E2, E3

    def test_e4_is_a_correct_abstention(self):
        e4 = next(c for c in self.summary["cases"] if c["id"] == "E4")
        self.assertEqual(e4["outcome"], "abstain")
        self.assertFalse(e4["false_abstention"])
        self.assertTrue(e4["passed"])

    def test_report_renders(self):
        text = render_report(self.summary, {"mode": "replay"})
        self.assertIn("Consult evaluation baseline", text)
        self.assertIn("E1", text)


if __name__ == "__main__":
    unittest.main()
