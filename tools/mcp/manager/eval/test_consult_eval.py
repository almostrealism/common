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
import unittest
from unittest import mock

_EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
if _EVAL_DIR not in sys.path:
    sys.path.insert(0, _EVAL_DIR)

from graders import (  # noqa: E402
    grade, resolve_expect, response_outcome, named_absent_symbols,
    validate_case, _file_matches, KNOWN_OUTCOMES,
)
import run_consult_eval  # noqa: E402
from run_consult_eval import (  # noqa: E402
    load_cases, observed_response, build_report, render_report,
    _docs_stage_copies, _stage_corpus,
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
