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
import shutil
import sys
import tempfile
import types
import unittest
from unittest import mock

_EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
if _EVAL_DIR not in sys.path:
    sys.path.insert(0, _EVAL_DIR)

from graders import (  # noqa: E402
    grade, resolve_expect, response_outcome, named_absent_symbols,
    validate_case, _file_matches, is_tool_failure, KNOWN_OUTCOMES,
)
import run_consult_eval  # noqa: E402
from run_consult_eval import (  # noqa: E402
    load_cases, observed_response, build_report, render_report,
    _docs_stage_copies, _stage_corpus, report_passed,
)


class TestCaseSchema(unittest.TestCase):
    """The case file loads, validates, and its fixtures exist."""

    def test_cases_load_and_validate(self):
        cases = load_cases()
        self.assertEqual([c["id"] for c in cases],
                         ["E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8",
                          "E9", "E10", "E11", "E12", "E13", "E14"])

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

    def test_validate_case_rejects_empty_expect(self):
        # An empty expectation grades no checks, so it would pass any response.
        bad = {"id": "X", "question": "q", "expect": {}}
        errors = validate_case(bad)
        self.assertEqual(errors, ["X: 'expect' must state at least one check"])
        self.assertTrue(grade({"answer": "anything"}, {}).passed)

    def test_validate_case_rejects_non_object_expect(self):
        bad = {"id": "X", "question": "q", "expect": ["outcome"]}
        self.assertEqual(validate_case(bad), ["X: 'expect' must be an object"])

    def test_validate_case_rejects_non_object_case(self):
        # A non-mapping case entry used to raise AttributeError at the first
        # ``case.get`` instead of being reported as a schema error.
        self.assertEqual(validate_case(None),
                         ["<case NoneType>: case must be an object"])
        self.assertEqual(validate_case("X"),
                         ["<case str>: case must be an object"])
        self.assertEqual(validate_case(["id"]),
                         ["<case list>: case must be an object"])

    def test_validate_case_accepts_single_check_expect(self):
        ok = {"id": "X", "question": "q", "expect": {"outcome": "abstain"}}
        self.assertEqual(validate_case(ok), [])

    def test_validate_case_rejects_non_object_must_not_cite_entry(self):
        # A null entry used to raise TypeError at the membership check instead
        # of being reported as a schema error.
        bad = {"id": "X", "question": "q",
               "expect": {"must_not_cite": [None, "a.md"]}}
        self.assertEqual(validate_case(bad), [
            "X: expect must_not_cite entries must be objects",
            "X: expect must_not_cite entries must be objects",
        ])

    def test_validate_case_rejects_non_list_must_not_cite(self):
        bad = {"id": "X", "question": "q",
               "expect": {"must_not_cite": {"file": "a.md", "lines": [1, 2]}}}
        self.assertEqual(validate_case(bad),
                         ["X: expect must_not_cite must be a list"])

    def test_validate_case_checks_must_not_cite_in_milestones(self):
        bad = {"id": "X", "question": "q", "expect": {"outcome": "answer"},
               "milestones": {"step2": {"must_not_cite": [None]}}}
        self.assertEqual(validate_case(bad), [
            "X: milestones.step2 must_not_cite entries must be objects"])

    def test_validate_case_accepts_well_formed_must_not_cite(self):
        ok = {"id": "X", "question": "q", "expect": {
            "must_not_cite": [{"file": "a.md", "lines": [1, 2]}]}}
        self.assertEqual(validate_case(ok), [])

    def test_validate_case_rejects_null_regex_list(self):
        # ``must_include: null`` used to pass validation and then raise
        # TypeError while grade() iterated it.
        bad = {"id": "X", "question": "q", "expect": {"must_include": None}}
        self.assertEqual(validate_case(bad),
                         ["X: expect must_include must be a list of strings"])

    def test_validate_case_rejects_bare_string_regex_list(self):
        # A bare string would be iterated as single-character patterns.
        bad = {"id": "X", "question": "q",
               "expect": {"must_not_include": "exit 2"}}
        self.assertEqual(validate_case(bad), [
            "X: expect must_not_include must be a list of strings"])

    def test_validate_case_rejects_non_string_regex_element(self):
        bad = {"id": "X", "question": "q",
               "expect": {"must_include": ["ok", 3]}}
        self.assertEqual(validate_case(bad),
                         ["X: expect must_include must be a list of strings"])

    def test_validate_case_rejects_uncompilable_regex(self):
        bad = {"id": "X", "question": "q", "expect": {"must_include": ["("]}}
        errors = validate_case(bad)
        self.assertEqual(len(errors), 1)
        self.assertTrue(errors[0].startswith(
            "X: expect must_include has invalid regex '('"), errors)

    def test_validate_case_rejects_non_list_must_include_any(self):
        # A bare string used to be read as a list of one-character groups.
        bad = {"id": "X", "question": "q",
               "expect": {"must_include_any": "seven"}}
        self.assertEqual(validate_case(bad),
                         ["X: expect must_include_any must be a list"])

    def test_validate_case_rejects_flat_or_empty_must_include_any_group(self):
        bad = {"id": "X", "question": "q",
               "expect": {"must_include_any": ["seven", []]}}
        self.assertEqual(validate_case(bad), [
            "X: expect must_include_any entries must be non-empty lists",
            "X: expect must_include_any entries must be non-empty lists",
        ])

    def test_validate_case_rejects_bad_regex_inside_must_include_any(self):
        bad = {"id": "X", "question": "q",
               "expect": {"must_include_any": [["ok", None]]}}
        self.assertEqual(validate_case(bad), [
            "X: expect must_include_any must be a list of strings"])

    def test_validate_case_rejects_non_string_list_keys(self):
        bad = {"id": "X", "question": "q", "expect": {
            "must_cite_any": ".github/CLAUDE.md",
            "must_flag_stale": [None],
            "basis_in": None}}
        self.assertEqual(validate_case(bad), [
            "X: expect must_cite_any must be a list of strings",
            "X: expect must_flag_stale must be a list of strings",
            "X: expect basis_in must be a list of strings",
        ])

    def test_validate_case_rejects_bad_max_citation_warnings(self):
        for value in (-1, "2", True, 1.5, None):
            bad = {"id": "X", "question": "q",
                   "expect": {"max_citation_warnings": value}}
            self.assertEqual(validate_case(bad), [
                "X: expect max_citation_warnings must be a non-negative "
                "integer"], value)

    def test_validate_case_accepts_zero_max_citation_warnings(self):
        ok = {"id": "X", "question": "q",
              "expect": {"max_citation_warnings": 0}}
        self.assertEqual(validate_case(ok), [])

    def test_validate_case_rejects_malformed_must_not_cite_fields(self):
        cases = [
            ({"file": None, "lines": [1, 2]},
             "X: expect must_not_cite 'file' must be a non-empty string"),
            ({"file": "a.md", "lines": ["1", 2]},
             "X: expect must_not_cite 'lines' must be [lo, hi]"),
            ({"file": "a.md", "lines": [9, 2]},
             "X: expect must_not_cite 'lines' must be [lo, hi]"),
            ({"file": "a.md", "lines": [1, 2], "with": ""},
             "X: expect must_not_cite 'with' must be a non-empty string"),
        ]
        for spec, message in cases:
            bad = {"id": "X", "question": "q",
                   "expect": {"must_not_cite": [spec]}}
            self.assertEqual(validate_case(bad), [message], spec)

    def test_validate_case_type_checks_milestone_overrides(self):
        bad = {"id": "X", "question": "q", "expect": {"outcome": "answer"},
               "milestones": {"step2": {"must_include": None}}}
        self.assertEqual(validate_case(bad), [
            "X: milestones.step2 must_include must be a list of strings"])

    def test_validate_case_accepts_every_well_formed_key(self):
        ok = {"id": "X", "question": "q", "expect": {
            "outcome": "answer",
            "must_include": ["a\\.md"],
            "must_not_include": ["exits?\\s+2"],
            "must_include_any": [["\\b7\\b", "seven"]],
            "must_cite_any": [".github/CLAUDE.md"],
            "must_not_cite": [{"file": "a.md", "lines": [3, 3], "with": "x"}],
            "must_flag_stale": ["ClaudeCodeJob"],
            "basis_in": ["documentation"],
            "max_citation_warnings": 0,
        }}
        self.assertEqual(validate_case(ok), [])

    def test_load_cases_raises_on_null_must_include(self):
        data = {"cases": [{"id": "X", "question": "q",
                           "expect": {"must_include": None}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("must_include must be a list of strings",
                      str(ctx.exception))

    def test_validate_case_rejects_non_object_milestones(self):
        bad = {"id": "X", "question": "q", "expect": {"outcome": "answer"},
               "milestones": ["step2"]}
        self.assertEqual(validate_case(bad),
                         ["X: 'milestones' must be an object"])

    def test_validate_case_rejects_falsy_non_object_milestones(self):
        # A present-but-falsy non-dict (``[]``, ``""``) must be reported, not
        # coerced to {}: resolve_expect does milestones.get(...) and would
        # raise AttributeError on a non-baseline milestone otherwise.
        bad = {"id": "X", "question": "q", "expect": {"outcome": "answer"},
               "milestones": []}
        self.assertEqual(validate_case(bad),
                         ["X: 'milestones' must be an object"])

    def test_validate_case_rejects_non_object_milestone_override(self):
        bad = {"id": "X", "question": "q", "expect": {"outcome": "answer"},
               "milestones": {"step2": None}}
        self.assertEqual(validate_case(bad),
                         ["X: milestones.step2 must be an object"])

    def test_load_cases_raises_on_null_must_not_cite_entry(self):
        data = {"cases": [{"id": "X", "question": "q",
                           "expect": {"must_not_cite": [None]}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("must_not_cite entries must be objects",
                      str(ctx.exception))

    def test_load_cases_raises_on_empty_expect(self):
        data = {"cases": [{"id": "X", "question": "q", "expect": {}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("at least one check", str(ctx.exception))

    def test_load_cases_raises_on_duplicate_case_id(self):
        case = {"id": "X", "question": "q", "expect": {"outcome": "answer"}}
        data = {"cases": [case, dict(case)]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("X: duplicate case id", str(ctx.exception))

    def test_load_cases_raises_on_non_string_case_id(self):
        data = {"cases": [{"id": 7, "question": "q",
                           "expect": {"outcome": "answer"}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("7: 'id' must be a non-empty string",
                      str(ctx.exception))

    def test_load_cases_raises_on_non_object_case_entry(self):
        # A malformed element (``null``, a string) must surface as a controlled
        # ValueError from the schema validator, not an AttributeError crash.
        data = {"cases": [None,
                          {"id": "X", "question": "q",
                           "expect": {"outcome": "answer"}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("case must be an object", str(ctx.exception))

    def test_validate_case_rejects_explicit_null_id(self):
        # A present ``"id": null`` is not a missing id: it must be reported,
        # since responses are keyed by id.
        bad = {"id": None, "question": "q", "expect": {"outcome": "answer"}}
        self.assertEqual(validate_case(bad),
                         ["None: 'id' must be a non-empty string"])

    def test_validate_case_rejects_empty_string_id(self):
        bad = {"id": "", "question": "q", "expect": {"outcome": "answer"}}
        self.assertEqual(validate_case(bad),
                         ["'': 'id' must be a non-empty string"])

    def test_load_cases_raises_on_null_case_id(self):
        data = {"cases": [{"id": None, "question": "q",
                           "expect": {"outcome": "answer"}}]}
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value=data):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("None: 'id' must be a non-empty string",
                      str(ctx.exception))

    def test_load_cases_raises_on_missing_cases_key(self):
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value={"_comment": "no cases"}):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("'cases' must be a non-empty list", str(ctx.exception))

    def test_load_cases_raises_on_non_object_document(self):
        for document in ([], "cases", None):
            with mock.patch.object(run_consult_eval, "_load_json",
                                   return_value=document):
                with self.assertRaises(ValueError) as ctx:
                    load_cases()
            self.assertIn("'cases' must be a non-empty list",
                          str(ctx.exception))

    def test_load_cases_raises_on_empty_case_list(self):
        with mock.patch.object(run_consult_eval, "_load_json",
                               return_value={"cases": []}):
            with self.assertRaises(ValueError) as ctx:
                load_cases()
        self.assertIn("non-empty list", str(ctx.exception))


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

    def test_e3_baseline_rejects_any_hallucinated_answer(self):
        # E3 is unanswerable at baseline, so a confident answer that avoids the
        # one forbidden literal must still fail on outcome, and an abstention
        # must pass.
        case = self._case("E3")
        guess = {"ok": True,
                 "answer": "ObjectOrientedDesignRule writes its verdicts to /tmp."}
        result = grade(guess, resolve_expect(case))
        self.assertFalse(result.passed)
        self.assertEqual(result.failures,
                         ["outcome: got 'answer', expected 'abstain'"])
        abstained = {"ok": True, "answer": "", "note": "Not documented."}
        self.assertTrue(grade(abstained, resolve_expect(case)).passed)

    def test_e3_step12_still_permits_the_indexed_answer(self):
        case = self._case("E3")
        indexed = {
            "ok": True,
            "answer": "Verdicts are written to .flowtree/oop-review.md.",
            "evidence": [{"named_symbols": {"absent": ["ClaudeCodeJob"]}}],
        }
        result = grade(indexed, resolve_expect(case, "step12"))
        self.assertTrue(result.passed, result.failures)

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
                       "qa/docs-. The open-PR limit MAX_OPEN_PRS is 7."),
            "sources": [".github/CLAUDE.md"],
        }
        self.assertFalse(grade(incomplete, resolve_expect(case, "step2")).passed)

    def test_e1_step2_rejects_an_answer_that_omits_the_named_limit(self):
        # All seven prefixes and a bare "7" are not enough: the question asks
        # for the open-PR limit, so the named MAX_OPEN_PRS constant must appear
        # — otherwise an unrelated 7 would satisfy the check.
        case = self._case("E1")
        no_limit_name = {
            "ok": True,
            "answer": ("Master Agent Dispatch runs rounds with prefixes "
                       "qa/docs-, qa/defect-, qa/coverage-, qa/consolidate-, "
                       "qa/performance-, qa/pdsl- and qa/retro-. It uses 7 "
                       "workers."),
            "sources": [".github/CLAUDE.md"],
        }
        result = grade(no_limit_name, resolve_expect(case, "step2"))
        self.assertFalse(result.passed)
        self.assertIn("MAX_OPEN_PRS", " ".join(result.failures))


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

    def test_forbidden_evidence_range_fails_when_prose_mentions_token(self):
        # Moving the misattributed citation out of the sentence and into the
        # structured evidence list must not evade the check.
        response = {
            "ok": True,
            "answer": "validate-agent-commit.sh exits 3.",
            "evidence": [{"file": "flowtree/runtime/docs/claude-code-job.md",
                          "range": [328, 336]}],
        }
        result = grade(response, {"must_not_cite": self._spec()})
        self.assertFalse(result.passed)
        self.assertEqual(
            result.failures,
            ["must_not_cite: evidence cites "
             "flowtree/runtime/docs/claude-code-job.md range [328, 336]"])

    def test_forbidden_evidence_range_allowed_when_token_absent(self):
        response = {
            "ok": True,
            "answer": "detect-test-hiding.sh exits 2.",
            "evidence": [{"file": "flowtree/runtime/docs/claude-code-job.md",
                          "range": [328, 336]}],
        }
        self.assertTrue(grade(response, {"must_not_cite": self._spec()}).passed)

    def test_evidence_range_outside_forbidden_lines_is_allowed(self):
        response = {
            "ok": True,
            "answer": "validate-agent-commit.sh exits 3.",
            "evidence": [{"file": "flowtree/runtime/docs/claude-code-job.md",
                          "range": [346, 360]}],
        }
        self.assertTrue(grade(response, {"must_not_cite": self._spec()}).passed)

    def test_non_numeric_evidence_range_is_ignored_not_raised(self):
        response = {
            "ok": True,
            "answer": "validate-agent-commit.sh exits 3.",
            "evidence": [{"file": "flowtree/runtime/docs/claude-code-job.md",
                          "range": ["start", None]}],
        }
        self.assertTrue(grade(response, {"must_not_cite": self._spec()}).passed)


class TestFileMatches(unittest.TestCase):
    """Citation matching: bare-basename leniency, but no false positives
    between two qualified paths that merely share a basename."""

    def test_identical_paths_match(self):
        self.assertTrue(_file_matches(".github/CLAUDE.md", ".github/CLAUDE.md"))

    def test_bare_basename_matches_qualified(self):
        self.assertTrue(_file_matches(".github/CLAUDE.md", "CLAUDE.md"))
        self.assertTrue(_file_matches("CLAUDE.md", ".github/CLAUDE.md"))

    def test_suffix_match(self):
        self.assertTrue(_file_matches(
            "flowtree/runtime/docs/claude-code-job.md",
            "docs/claude-code-job.md"))

    def test_two_qualified_paths_with_same_basename_do_not_match(self):
        # The defect Copilot flagged: a citation to the wrong directory's
        # CLAUDE.md must not satisfy an expectation for .github/CLAUDE.md.
        self.assertFalse(_file_matches(".github/CLAUDE.md", "other/CLAUDE.md"))
        self.assertFalse(_file_matches("docs/a.md", "tools/a.md"))

    def test_grade_rejects_wrong_directory_citation(self):
        expect = {"must_cite_any": [".github/CLAUDE.md"]}
        wrong = {"ok": True, "answer": "x", "sources": ["other/CLAUDE.md"]}
        self.assertFalse(grade(wrong, expect).passed)


class TestBuildReportAggregation(unittest.TestCase):
    """Local mode grades every one of the k repetitions; a case passes only
    when all of them pass, and the pass fraction is reported."""

    def _case(self):
        return next(c for c in load_cases() if c["id"] == "E4")

    def _passing(self):
        return {"ok": True, "abstained": True,
                "note": "No such script is documented."}

    def _failing(self):
        return {"ok": True, "abstained": True,
                "note": "entropy is computed from the commit graph."}

    def test_single_response_still_accepted(self):
        summary = build_report([self._case()],
                               {"E4": self._passing()}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["runs"], 1)
        self.assertEqual(c["pass_fraction"], 1.0)
        self.assertTrue(c["passed"])

    def test_one_failing_run_fails_the_case(self):
        runs = [self._passing(), self._failing(), self._passing()]
        summary = build_report([self._case()], {"E4": runs}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["runs"], 3)
        self.assertEqual(c["passed_runs"], 2)
        self.assertFalse(c["passed"])  # not all passed
        self.assertEqual(summary["passed"], 0)

    def test_all_runs_passing_passes_the_case(self):
        runs = [self._passing(), self._passing()]
        summary = build_report([self._case()], {"E4": runs}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["passed_runs"], 2)
        self.assertTrue(c["passed"])
        self.assertEqual(summary["passed"], 1)

    def test_empty_run_list_is_no_response(self):
        summary = build_report([self._case()], {"E4": []}, "baseline")
        self.assertEqual(summary["cases"][0]["status"], "no-response")

    def test_failures_aggregate_across_repetitions(self):
        # The first run passes, a later run fails: the case must fail and the
        # reported failing checks must name the later run's failure rather than
        # being empty (taken only from the representative).
        runs = [self._passing(), self._failing()]
        summary = build_report([self._case()], {"E4": runs}, "baseline")
        c = summary["cases"][0]
        self.assertFalse(c["passed"])
        self.assertEqual(c["passed_runs"], 1)
        self.assertTrue(c["failures"], "failing run's checks must be reported")
        self.assertIn("must_not_include", " ".join(c["failures"]))


class TestToolFailureHandling(unittest.TestCase):
    """A failed tool call (``ok: False``) is a non-passing result, never a
    silently-passing (false) abstention."""

    def _case(self, cid, answerable):
        return {"id": cid, "question": "q", "answerable": answerable,
                "expect": {"outcome": "abstain",
                           "must_not_include": ["drift"]}}

    def _failure(self):
        return {"ok": False, "error": "ar-memory server unavailable"}

    def test_is_tool_failure_detects_ok_false(self):
        self.assertTrue(is_tool_failure({"ok": False, "error": "x"}))
        self.assertFalse(is_tool_failure({"ok": True, "answer": "x"}))
        self.assertFalse(is_tool_failure({"abstained": True}))

    def test_grade_fails_a_tool_failure_even_with_only_negative_checks(self):
        # E3-style expectation: only must_not_include. A backend failure has no
        # answer, so the negative check would pass vacuously without this guard.
        expect = {"must_not_include": ["runCorrectionSession"]}
        result = grade(self._failure(), expect)
        self.assertFalse(result.passed)
        self.assertIn("ar-memory server unavailable", " ".join(result.failures))

    def test_build_report_marks_failure_as_error_not_false_abstention(self):
        case = self._case("E1like", answerable=True)
        summary = build_report([case], {"E1like": self._failure()}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["outcome"], "error")
        self.assertFalse(c["passed"])
        self.assertFalse(c["false_abstention"])
        self.assertEqual(summary["false_abstention"], 0)
        self.assertEqual(summary["passed"], 0)

    def test_build_report_failure_does_not_count_as_correct_abstention(self):
        # An unanswerable case whose backend failed must not be scored as the
        # correct abstention it superficially resembles.
        case = self._case("E4like", answerable=False)
        summary = build_report([case], {"E4like": self._failure()}, "baseline")
        c = summary["cases"][0]
        self.assertFalse(c["passed"])
        self.assertEqual(c["outcome"], "error")

    def test_later_repetition_failure_marks_the_case_as_error(self):
        # Repetition 1 answers; repetition 2 is a backend failure. The case
        # used to be classified from repetition 1 alone (outcome "answer").
        case = {"id": "A", "question": "q", "answerable": True,
                "expect": {"outcome": "answer"}}
        runs = [{"ok": True, "answer": "It is documented."}, self._failure()]
        summary = build_report([case], {"A": runs}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["outcome"], "error")
        self.assertFalse(c["passed"])
        self.assertEqual(c["passed_runs"], 1)
        self.assertFalse(c["false_answer"])
        self.assertFalse(c["false_abstention"])
        self.assertIn("ar-memory server unavailable", " ".join(c["failures"]))

    def test_later_failure_suppresses_false_abstention_count(self):
        # Repetition 1 abstains on an answerable case, repetition 2 fails: the
        # environment failure must not be counted as a false abstention.
        case = self._case("B", answerable=True)
        runs = [{"ok": True, "abstained": True, "note": "Not documented."},
                self._failure()]
        summary = build_report([case], {"B": runs}, "baseline")
        self.assertEqual(summary["cases"][0]["outcome"], "error")
        self.assertEqual(summary["false_abstention"], 0)

    def test_all_successful_repetitions_keep_the_representative_outcome(self):
        case = self._case("C", answerable=True)
        runs = [{"ok": True, "abstained": True, "note": "Not documented."},
                {"ok": True, "abstained": True, "note": "Not documented."}]
        summary = build_report([case], {"C": runs}, "baseline")
        c = summary["cases"][0]
        self.assertEqual(c["outcome"], "abstain")
        self.assertTrue(c["false_abstention"])
        self.assertEqual(summary["false_abstention"], 1)


class TestCorpusStaging(unittest.TestCase):
    """Local mode stages the corpus from the Dockerfile's own COPY directives
    and prunes to markdown/HTML, so a coverage regression in the Dockerfile
    reaches local results."""

    def test_docs_stage_copies_reads_the_real_dockerfile(self):
        sources = _docs_stage_copies()
        # The CI documents this branch added must be reachable through one of
        # the staged roots.
        self.assertIn(".github", sources)
        self.assertIn("docs", sources)
        self.assertIn("CLAUDE.md", sources)
        # --from copies (the runtime stage) must not leak in.
        self.assertNotIn("/src", sources)
        # Nor may plain copies from the unnamed runtime stage that follows.
        self.assertNotIn("tools/mcp/common", sources)
        self.assertNotIn("tools/mcp/manager/*.py", sources)
        self.assertNotIn("tools/mcp/manager/requirements.txt", sources)

    def test_unnamed_from_ends_the_docs_stage(self):
        root = tempfile.mkdtemp(prefix="eval-fake-dockerfile-")
        dockerfile = os.path.join(root, "Dockerfile")
        try:
            with open(dockerfile, "w") as h:
                h.write(
                    "FROM python:3.11-slim AS builder\n"
                    "COPY setup/ ./setup/\n"
                    "FROM python:3.11-slim AS docs\n"
                    "COPY docs/ ./docs/\n"
                    "FROM python:3.11-slim\n"
                    "COPY .claude/ ./.claude/\n"
                    "COPY --from=docs /src /app/docs-root\n"
                    "FROM python:3.11-slim AS docs-extra\n"
                    "COPY extra/ ./extra/\n")
            with mock.patch.object(run_consult_eval, "_DOCKERFILE", dockerfile):
                # A COPY moved into the unnamed runtime stage is not corpus.
                self.assertEqual(_docs_stage_copies(), ["docs"])
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def _fake_repo(self):
        root = tempfile.mkdtemp(prefix="eval-fake-repo-")
        os.makedirs(os.path.join(root, "docs"))
        os.makedirs(os.path.join(root, ".github"))
        os.makedirs(os.path.join(root, "base"))
        with open(os.path.join(root, "CLAUDE.md"), "w") as h:
            h.write("# root")
        with open(os.path.join(root, "docs", "a.md"), "w") as h:
            h.write("# a")
        with open(os.path.join(root, "docs", "b.html"), "w") as h:
            h.write("<p>b</p>")
        with open(os.path.join(root, ".github", "CLAUDE.md"), "w") as h:
            h.write("# gh")
        # Non-doc files that the prune must drop.
        with open(os.path.join(root, "base", "Foo.java"), "w") as h:
            h.write("class Foo {}")
        with open(os.path.join(root, "docs", "notes.txt"), "w") as h:
            h.write("skip me")
        dockerfile = os.path.join(root, "Dockerfile")
        with open(dockerfile, "w") as h:
            h.write(
                "FROM python:3.11-slim AS docs\n"
                "WORKDIR /src\n"
                "COPY docs/ ./docs/\n"
                "COPY CLAUDE.md ./\n"
                "COPY .github/ ./.github/\n"
                "COPY base/ ./base/\n"
                "RUN find . -delete\n"
                "FROM python:3.11-slim\n"
                "COPY --from=docs /src /app/docs-root\n")
        return root, dockerfile

    def test_stage_corpus_prunes_to_markdown_and_html(self):
        root, dockerfile = self._fake_repo()
        staging = None
        try:
            with mock.patch.object(run_consult_eval, "_REPO_ROOT", root), \
                 mock.patch.object(run_consult_eval, "_DOCKERFILE", dockerfile):
                staging = _stage_corpus()
            staged = set()
            for base, _dirs, files in os.walk(staging):
                for name in files:
                    staged.add(os.path.relpath(
                        os.path.join(base, name), staging))
            self.assertIn("docs/a.md", staged)
            self.assertIn("docs/b.html", staged)
            self.assertIn("CLAUDE.md", staged)
            self.assertIn(os.path.join(".github", "CLAUDE.md"), staged)
            self.assertNotIn(os.path.join("base", "Foo.java"), staged)
            self.assertNotIn("docs/notes.txt", staged)
        finally:
            shutil.rmtree(root, ignore_errors=True)
            if staging:
                shutil.rmtree(staging, ignore_errors=True)


class TestLocalRunLifecycle(unittest.TestCase):
    """_run_local clears the memoised docs retriever and restores AR_DOCS_DIR,
    so a second local run in the same process restages against a fresh corpus
    rather than a retriever bound to the deleted first staging tree."""

    def _fake_server(self, seen_retriever):
        """A stand-in for the manager ``server`` module with a stale retriever
        cache already populated, so the reset is observable."""
        server = types.ModuleType("server")
        server._docs_retriever = "stale-retriever"
        server._docs_init_failed = True

        def consult(**kwargs):
            # The run must have cleared the stale cache before consulting, so
            # _get_docs would rebind against this run's staged corpus.
            seen_retriever.append(server._docs_retriever)
            return {"ok": True, "answer": "ok"}

        server._set_scopes = lambda scopes, label="": None
        server._get_memory_client = lambda: None
        server.consult = consult
        return server

    def test_run_local_resets_retriever_and_restores_env(self):
        cases = [{"id": "X", "question": "q", "keywords": ["k"]}]
        seen_retriever = []
        fake = self._fake_server(seen_retriever)
        staging = tempfile.mkdtemp(prefix="eval-fake-staging-")
        sentinel = os.path.join(staging, "sentinel-docs")
        prior = os.environ.get("AR_DOCS_DIR")
        os.environ["AR_DOCS_DIR"] = sentinel
        try:
            with mock.patch.dict(sys.modules, {"server": fake}), \
                 mock.patch.object(run_consult_eval, "_stage_corpus",
                                   return_value=staging):
                responses = run_consult_eval._run_local(cases, k=1)
            # Cleared before the first consult, so a stale tree is never read.
            self.assertEqual(seen_retriever, [None])
            # Reset again on the way out, so a later run restages cleanly.
            self.assertIsNone(fake._docs_retriever)
            self.assertFalse(fake._docs_init_failed)
            # AR_DOCS_DIR restored to its prior value, not left at the (now
            # deleted) staging path.
            self.assertEqual(os.environ.get("AR_DOCS_DIR"), sentinel)
            self.assertEqual(responses["X"][0]["answer"], "ok")
        finally:
            if prior is None:
                os.environ.pop("AR_DOCS_DIR", None)
            else:
                os.environ["AR_DOCS_DIR"] = prior
            shutil.rmtree(staging, ignore_errors=True)

    def test_run_local_removes_ar_docs_dir_when_unset_before(self):
        cases = [{"id": "X", "question": "q", "keywords": ["k"]}]
        fake = self._fake_server([])
        staging = tempfile.mkdtemp(prefix="eval-fake-staging-")
        prior = os.environ.pop("AR_DOCS_DIR", None)
        try:
            with mock.patch.dict(sys.modules, {"server": fake}), \
                 mock.patch.object(run_consult_eval, "_stage_corpus",
                                   return_value=staging):
                run_consult_eval._run_local(cases, k=1)
            # Absent before the run, AR_DOCS_DIR must not be left set after it.
            self.assertNotIn("AR_DOCS_DIR", os.environ)
        finally:
            if prior is not None:
                os.environ["AR_DOCS_DIR"] = prior
            shutil.rmtree(staging, ignore_errors=True)

    def _run_with_real_scopes(self, seen_scopes):
        """Run _run_local with a fake server whose _set_scopes is the real
        auth one, recording the scopes each consult sees."""
        import auth
        cases = [{"id": "X", "question": "q", "keywords": ["k"]}]
        fake = self._fake_server([])
        fake._set_scopes = auth._set_scopes

        def consult(**kwargs):
            seen_scopes.append((auth._get_scopes(), auth._get_token_label()))
            return {"ok": True, "answer": "ok"}

        fake.consult = consult
        staging = tempfile.mkdtemp(prefix="eval-fake-staging-")
        prior = os.environ.get("AR_DOCS_DIR")
        try:
            with mock.patch.dict(sys.modules, {"server": fake}), \
                 mock.patch.object(run_consult_eval, "_stage_corpus",
                                   return_value=staging):
                run_consult_eval._run_local(cases, k=2)
        finally:
            if prior is None:
                os.environ.pop("AR_DOCS_DIR", None)
            else:
                os.environ["AR_DOCS_DIR"] = prior
            shutil.rmtree(staging, ignore_errors=True)
        return auth

    def test_run_local_restores_prior_auth_context(self):
        import auth
        snapshot = auth._snapshot_scopes()
        auth._set_scopes(["read"], label="caller")
        try:
            seen = []
            self._run_with_real_scopes(seen)
            # Each repetition ran under the synthetic eval scope ...
            self.assertEqual(seen, [(["memory-read"], "eval")] * 2)
            # ... and the caller's own scope is back afterwards.
            self.assertEqual(auth._get_scopes(), ["read"])
            self.assertEqual(auth._get_token_label(), "caller")
            self.assertEqual(auth._thread_local.scopes, ["read"])
            self.assertEqual(auth._thread_local.token_label, "caller")
        finally:
            auth._restore_scopes(snapshot)

    def test_run_local_clears_auth_context_when_none_was_set(self):
        import auth
        snapshot = auth._snapshot_scopes()
        auth._restore_scopes((None, None, auth._UNSET, auth._UNSET))
        try:
            self._run_with_real_scopes([])
            # No synthetic memory-read authorization or eval label survives.
            self.assertIsNone(auth._get_scopes())
            self.assertEqual(auth._get_token_label(), "anonymous")
            self.assertFalse(hasattr(auth._thread_local, "scopes"))
            self.assertFalse(hasattr(auth._thread_local, "token_label"))
        finally:
            auth._restore_scopes(snapshot)


    def test_run_local_rebinds_imported_docs_retriever_module(self):
        """docs_retriever reads AR_DOCS_DIR at import time, so an imported
        module must be reloaded to see this run's staged corpus, and reloaded
        again afterwards to see the restored environment."""
        cases = [{"id": "X", "question": "q", "keywords": ["k"]}]
        module_dir = tempfile.mkdtemp(prefix="eval-fake-retriever-")
        with open(os.path.join(module_dir, "docs_retriever.py"), "w",
                  encoding="utf-8") as handle:
            handle.write("import os\n"
                         "COMMON_DIR = os.environ.get('AR_DOCS_DIR')\n")
        staging = tempfile.mkdtemp(prefix="eval-fake-staging-")
        seen_common_dir = []
        fake = self._fake_server([])

        def consult(**kwargs):
            seen_common_dir.append(sys.modules["docs_retriever"].COMMON_DIR)
            return {"ok": True, "answer": "ok"}

        fake.consult = consult
        prior = os.environ.get("AR_DOCS_DIR")
        os.environ["AR_DOCS_DIR"] = "/stale/first-run/docs"
        sys.path.insert(0, module_dir)
        try:
            with mock.patch.dict(sys.modules):
                sys.modules.pop("docs_retriever", None)
                import docs_retriever  # bound to the stale tree
                self.assertEqual(docs_retriever.COMMON_DIR,
                                 "/stale/first-run/docs")
                sys.modules["server"] = fake
                with mock.patch.object(run_consult_eval, "_stage_corpus",
                                       return_value=staging):
                    run_consult_eval._run_local(cases, k=1)
                self.assertEqual(seen_common_dir,
                                 [os.path.join(staging, "docs")])
                self.assertEqual(sys.modules["docs_retriever"].COMMON_DIR,
                                 "/stale/first-run/docs")
        finally:
            sys.path.remove(module_dir)
            if prior is None:
                os.environ.pop("AR_DOCS_DIR", None)
            else:
                os.environ["AR_DOCS_DIR"] = prior
            shutil.rmtree(staging, ignore_errors=True)
            shutil.rmtree(module_dir, ignore_errors=True)


class TestBaselineReport(unittest.TestCase):
    """The replay-mode baseline over the frozen responses is the documented
    starting point: E1 false-abstains, E2/E3 are false answers, E4 is a correct
    abstention. The counts are those of the cases observed on 2026-10-17 — the
    seed set the baseline was taken over; cases added later carry their own
    observation date."""

    def setUp(self):
        self.cases = [c for c in load_cases()
                      if c.get("observed", {}).get("date") == "2026-10-17"]
        self.responses = {c["id"]: observed_response(c) for c in self.cases}
        self.summary = build_report(self.cases, self.responses, "baseline")

    def test_the_seed_set_is_the_original_four_cases(self):
        self.assertEqual([c["id"] for c in self.cases], ["E1", "E2", "E3", "E4"])

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
        self.assertIn("# Consult evaluation report — milestone `baseline`", text)
        self.assertIn("E1", text)

    def test_report_heading_names_the_milestone(self):
        # A later-milestone report must be distinguishable from the baseline:
        # the heading carries the milestone and is not fixed to "baseline".
        summary = build_report(self.cases, self.responses, "step2")
        text = render_report(summary, {"mode": "replay"})
        self.assertIn("# Consult evaluation report — milestone `step2`", text)
        self.assertNotIn("milestone `baseline`", text)


class TestExitStatus(unittest.TestCase):
    """``main`` is non-gating by default and gates on ``--check``."""

    def _main(self, *argv):
        with mock.patch("builtins.print"):
            return run_consult_eval.main(["--mode", "replay", *argv])

    def test_report_passed_requires_every_case(self):
        self.assertTrue(report_passed({"cases": [
            {"id": "A", "passed": True}, {"id": "B", "passed": True}]}))
        self.assertFalse(report_passed({"cases": [
            {"id": "A", "passed": True}, {"id": "B", "passed": False}]}))

    def test_report_passed_treats_no_response_as_failure(self):
        self.assertFalse(report_passed({"cases": [
            {"id": "A", "passed": True}, {"id": "B", "status": "no-response"}]}))

    def test_report_passed_fails_on_empty_case_list(self):
        # all() is vacuously True for an empty list; the gate must not report
        # success for a run that graded nothing.
        self.assertFalse(report_passed({"cases": []}))

    def test_known_failing_baseline_exits_zero_without_check(self):
        self.assertEqual(self._main(), 0)

    def test_known_failing_baseline_exits_one_with_check(self):
        self.assertEqual(self._main("--check"), 1)

    def test_check_exits_zero_when_every_case_passes(self):
        passing = {"milestone": "baseline", "total": 1, "passed": 1,
                   "false_abstention": 0, "false_answer": 0,
                   "cases": [{"id": "E1", "outcome": "answer", "passed": True,
                              "runs": 1, "passed_runs": 1, "pass_fraction": 1.0,
                              "failures": [], "false_abstention": False,
                              "false_answer": False}]}
        with mock.patch.object(run_consult_eval, "build_report",
                               return_value=passing):
            self.assertEqual(self._main("--check"), 0)


if __name__ == "__main__":
    unittest.main()
