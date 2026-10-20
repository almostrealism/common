"""Tests for the citation verifier.

The observed failures it exists for: a claim about one script attributed to a
chunk about another (E2), invented branch prefixes (E1), an invented trailer
format, and a present-tense claim resting on a stale note.
"""

import os
import sys
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from citation_verifier import (  # noqa: E402
    REASON_BRANCH_LOCAL, REASON_CONTRADICTED, REASON_FREE_TEXT, REASON_POLARITY,
    REASON_SUBJECT, REASON_TIER, REASON_UNCITED, REASON_UNKNOWN,
    REASON_UNSUPPORTED_UNCITED, REASON_VALUE, UNSUPPORTED_MARK,
    polarity_unsupported, salient_tokens, split_sentences, verify,
)
from consult_evidence import (  # noqa: E402
    TIER_BRANCH_LOCAL, TIER_CURRENT, TIER_HISTORICAL, EvidenceList,
)


def _evidence():
    ev = EvidenceList()
    ev.add_doc({"file": "flowtree/runtime/docs/claude-code-job.md", "start": 330,
                "end": 340, "section": "Validation: detect-test-hiding.sh",
                "context": "detect-test-hiding.sh exits 0 when clean and 2 "
                           "when a test was hidden."})
    ev.add_doc({"file": ".github/CLAUDE.md", "start": 874, "end": 886,
                "section": "Master Agent Dispatch",
                "context": "BRANCH_PREFIX (`qa/docs-`, `qa/coverage-`); "
                           "MAX_OPEN_PRS (7)"})
    return ev


def _reasons(report):
    return [r for s in report.sentences for r in s.reasons]


class TestSentenceChecks(unittest.TestCase):

    def test_subject_misattribution_is_flagged(self):
        # E2: the sentence is about validate-agent-commit.sh, the cited chunk
        # is about detect-test-hiding.sh.
        report = verify("validate-agent-commit.sh exits 2 [D1].", _evidence())
        self.assertIn(REASON_SUBJECT, _reasons(report))
        self.assertIn(UNSUPPORTED_MARK, report.annotated)

    def test_a_supported_sentence_passes_clean(self):
        report = verify("detect-test-hiding.sh exits 2 when a test was hidden [D1].",
                        _evidence())
        self.assertEqual(report.warnings, [])
        self.assertNotIn(UNSUPPORTED_MARK, report.annotated)

    def test_value_absent_from_the_cited_item_is_flagged(self):
        report = verify("The limit `MAX_OPEN_PRS` is 9 [D2].", _evidence())
        self.assertEqual(_reasons(report), [REASON_VALUE])

    def test_an_invented_prefix_is_not_matched_by_a_longer_real_one(self):
        # qa/coverage is a different prefix from qa/coverage-.
        report = verify("The prefix is `qa/coverage` [D2].", _evidence())
        self.assertIn(REASON_SUBJECT, _reasons(report))
        ok = verify("The prefix is `qa/coverage-` [D2].", _evidence())
        self.assertEqual(ok.warnings, [])

    def test_an_identifier_is_not_matched_inside_a_longer_one(self):
        ev = EvidenceList()
        ev.add_doc({"file": "a.md", "start": 1, "end": 2,
                    "context": "MAX_OPEN_PRS_LIMIT is 3"})
        report = verify("`MAX_OPEN_PRS` is 3 [D1].", ev)
        self.assertIn(REASON_SUBJECT, _reasons(report))

    def test_unknown_citation_is_flagged(self):
        report = verify("Something about `qa/docs-` [D9].", _evidence())
        self.assertIn(REASON_UNKNOWN, _reasons(report))

    def test_free_text_location_is_reported(self):
        report = verify("It exits 2 per claude-code-job.md:330 [D1].", _evidence())
        self.assertIn(REASON_FREE_TEXT, _reasons(report))


class TestUncited(unittest.TestCase):

    def test_uncited_specifics_found_nowhere_are_flagged(self):
        report = verify("Use `Sensitive-File-Bypass: signed-by-controller`.",
                        _evidence())
        self.assertEqual(_reasons(report), [REASON_UNSUPPORTED_UNCITED])
        self.assertEqual(report.uncited_claims, 1)
        # The mark stays inside the sentence it qualifies.
        self.assertTrue(report.annotated.endswith(UNSUPPORTED_MARK + "."))

    def test_uncited_specifics_present_in_evidence_are_counted_not_marked(self):
        report = verify("The limit is `MAX_OPEN_PRS`.", _evidence())
        self.assertEqual(_reasons(report), [REASON_UNCITED])
        self.assertEqual(report.warnings, [])
        self.assertEqual(report.uncited_claims, 1)
        self.assertNotIn(UNSUPPORTED_MARK, report.annotated)

    def test_an_abstention_sentence_is_not_a_claim(self):
        report = verify("The exit code of `validate-agent-commit.sh` is not documented.",
                        _evidence())
        self.assertEqual(report.sentences[0].reasons, [])

    def test_code_blocks_and_trailer_are_not_claims(self):
        spans = split_sentences("A [D1].\n```\nexit 99\n```\nBASIS: none")
        self.assertEqual(len(spans), 1)


class TestTiers(unittest.TestCase):

    def _note(self, tier, branch=""):
        ev = EvidenceList()
        ev.add_note({"id": "m", "content": "validate-agent-commit.sh exits 2",
                     "branch": branch}, tier)
        return ev

    def test_present_tense_claim_on_a_historical_note_fails_tier(self):
        report = verify("validate-agent-commit.sh exits 2 [N1].",
                        self._note(TIER_HISTORICAL))
        self.assertEqual(_reasons(report), [REASON_TIER])
        self.assertEqual(report.supporting_ids(), [])

    def test_historical_framing_on_a_historical_note_passes(self):
        report = verify("validate-agent-commit.sh previously exited 2 [N1].",
                        self._note(TIER_HISTORICAL))
        self.assertNotIn(REASON_TIER, _reasons(report))

    def test_a_current_note_may_support_present_fact(self):
        report = verify("validate-agent-commit.sh exits 2 [N1].",
                        self._note(TIER_CURRENT))
        self.assertEqual(report.warnings, [])
        self.assertEqual(report.supporting_ids(), ["N1"])

    def test_branch_local_claim_must_name_its_branch(self):
        ev = self._note(TIER_BRANCH_LOCAL, branch="feature/x")
        bare = verify("validate-agent-commit.sh exits 2 [N1].", ev)
        self.assertEqual(_reasons(bare), [REASON_BRANCH_LOCAL])
        self.assertEqual(bare.supporting_ids(), [])
        named = verify("On branch feature/x, validate-agent-commit.sh exits 2 [N1].", ev)
        self.assertEqual(named.warnings, [])

    def test_a_contradicted_value_is_flagged(self):
        report = verify("validate-agent-commit.sh exits 2 [N1].",
                        self._note(TIER_CURRENT), {"N1": {"2"}})
        self.assertEqual(_reasons(report), [REASON_CONTRADICTED])
        self.assertEqual(report.supporting_ids(), [])


def _deploy_evidence():
    ev = EvidenceList()
    ev.add_doc({"file": ".github/CLAUDE.md", "start": 737, "end": 749,
                "section": "What the Deploy Controller Stack workflow does",
                "context": "Lives in its own file, triggered by workflow_run on "
                           "a successful master run of \"Build and Test\", plus "
                           "workflow_dispatch for manual deploys.\nNever move a "
                           "deploy job into analysis.yaml."})
    ev.add_doc({"file": ".github/CLAUDE.md", "start": 909, "end": 921,
                "section": "Master Agent Dispatch",
                "context": "A planning round checks the backlog: it starts a "
                           "round unless more than MAX_OPEN_PRS are open. Files "
                           "under tools/ci/ change only on a branch named ci/."})
    ev.add_doc({"file": "flowtree/runtime/docs/coding-agent.md", "start": 204,
                "end": 216, "section": "Object-Oriented Design Review",
                "context": "ObjectOrientedDesignRule runs one dedicated session "
                           "per added or modified production Java class."})
    return ev


class TestPolarityClaims(unittest.TestCase):
    """The claim-level check: polarity and exclusivity claims carry no token
    the subject and value checks can match, so they are checked as claims."""

    def test_an_unechoed_negation_contradicting_its_citation_is_flagged(self):
        # Observed 2026-10-20: right about the trigger, then a confident
        # "does not ... only when" that nothing in the evidence says.
        report = verify(
            "The deploy is triggered by a successful master run of Build and "
            "Test [D1]. It does not deploy automatically after every merge — "
            "only when explicitly triggered [D1].", _deploy_evidence())
        first, second = report.sentences
        self.assertEqual(first.reasons, [])
        self.assertEqual(second.reasons, [REASON_POLARITY])
        self.assertEqual(second.missing,
                         ["It does not deploy automatically after every merge",
                          "only when explicitly triggered"])
        self.assertTrue(report.annotated.endswith("[D1] " + UNSUPPORTED_MARK + "."))
        self.assertEqual(report.warnings[0]["reason"], REASON_POLARITY)

    def test_an_uncited_polarity_claim_is_held_to_all_evidence(self):
        report = verify("It never deploys on its own.", _deploy_evidence())
        self.assertEqual(_reasons(report), [REASON_POLARITY])
        self.assertIn(UNSUPPORTED_MARK, report.annotated)

    def test_an_echoed_exclusivity_claim_passes(self):
        report = verify("Files under tools/ci/ may change only on a branch "
                        "named ci/ [D2].", _deploy_evidence())
        self.assertEqual(report.warnings, [])

    def test_a_condition_echoes_an_exclusivity_claim(self):
        # "only while no more than" restates "unless more than".
        report = verify("A planning round starts only while no more than "
                        "`MAX_OPEN_PRS` pull requests are open [D2].",
                        _deploy_evidence())
        self.assertEqual(report.warnings, [])

    def test_a_negation_echoed_by_its_citation_passes(self):
        self.assertEqual(polarity_unsupported(
            "Never move a deploy job into analysis.yaml [D1].",
            ["Never move a deploy job into analysis.yaml."]), [])

    def test_a_sentence_wrapped_across_lines_still_echoes(self):
        # Observed in a local run: a script header wraps its sentence, and a
        # clause checked against half of it was wrongly flagged.
        header = ("The tests on the base branch pass there. A change set whose "
                  "only content is edits to those\ntests — no production change, "
                  "no new test, nothing else — cannot fix\nanything.\n\n"
                  "| Flag | Meaning |\n| only | a separate row |")
        self.assertEqual(polarity_unsupported(
            "validate-agent-commit.sh blocks change sets whose only content is "
            "edits to test files that exist on the base branch [I1].", [header]),
            [])

    def test_polarity_wording_on_unrelated_content_does_not_echo(self):
        # The passage is negative, but about something else.
        self.assertEqual(polarity_unsupported(
            "The deploy does not rebuild the agent images.",
            ["Never move a deploy job into analysis.yaml."]),
            ["The deploy does not rebuild the agent images"])

    def test_a_cardinality_must_count_the_same_thing_per_the_same_unit(self):
        # E12: "one class per job" paraphrases "one session per changed
        # class" into a different claim; no token check can see that.
        report = verify("`ObjectOrientedDesignRule` runs one class per job [D3].",
                        _deploy_evidence())
        self.assertEqual(_reasons(report), [REASON_POLARITY])
        right = verify("`ObjectOrientedDesignRule` runs one session per "
                       "modified class [D3].", _deploy_evidence())
        self.assertEqual(right.warnings, [])

    def test_a_cardinality_unit_may_be_paraphrased(self):
        # Observed in a local run: "per changed production class" restates
        # "per added or modified production Java class".
        report = verify("The rule runs one dedicated session per changed "
                        "production class [D3].", _deploy_evidence())
        self.assertEqual(report.warnings, [])

    def test_an_unbackticked_prefix_keeps_its_dash(self):
        # Observed in a local run: "qa/docs-," was read as "qa/docs", which a
        # note naming "qa/docs-" does not contain as a whole name.
        subjects, _ = salient_tokens("Prefixes are qa/docs-, qa/defect- and tools/ci/.")
        self.assertEqual(subjects, ["qa/docs-", "qa/defect-", "tools/ci/"])
        report = verify("Prefixes are qa/docs-, qa/coverage- [D2].", _evidence())
        self.assertEqual(report.warnings, [])

    def test_polarity_words_inside_quoted_literals_are_not_the_claim(self):
        # Observed in a local run: the script's own message, quoted, carries
        # "only"; it is a literal the value check verifies, not a claim.
        self.assertEqual(polarity_unsupported(
            "It prints `BLOCKED: the change set only edits base-branch tests` "
            "and \"never retried\" [I1].", []), [])

    def test_a_clause_about_what_the_evidence_lacks_is_not_checked(self):
        # Observed in local runs: statements about the evidence, not the code.
        for sentence in (
                "This is branch-local and not present in the master snapshot.",
                "That branch is local and not reflected in the snapshot.",
                "The branch is open, so it does not reflect the state of master.",
                "The only evidence of streaming is a branch-local note [N1].",
                "The legacy ClaudeCodeJob name is no longer present in the snapshot [N1].",
                "The legacy ClaudeCodeJob name no longer appears in the snapshot [N1].",
                "This is branch-local and not yet merged to master [N1].",
                "Since this is not on master, it does not apply to the question."):
            self.assertEqual(polarity_unsupported(sentence, []), [], sentence)

    def test_an_abstention_is_not_a_polarity_claim(self):
        report = verify("The deploy schedule is not documented.", _deploy_evidence())
        self.assertEqual(report.sentences[0].reasons, [])

    def test_a_plain_affirmative_sentence_is_not_checked(self):
        self.assertEqual(polarity_unsupported(
            "It deploys after every merge.", []), [])


class TestSalientTokens(unittest.TestCase):

    def test_backticked_number_is_a_value(self):
        subjects, values = salient_tokens("It exits with code `2` [D1].")
        self.assertEqual(subjects, [])
        self.assertEqual(values, ["2"])

    def test_marker_digits_are_not_values(self):
        _, values = salient_tokens("Nothing numeric here [D12].")
        self.assertEqual(values, [])

    def test_identifiers_and_paths_are_subjects(self):
        subjects, _ = salient_tokens(
            "ObjectOrientedDesignRule writes .flowtree/oop-review.md via VERDICT_PATH.")
        self.assertIn("ObjectOrientedDesignRule", subjects)
        self.assertIn(".flowtree/oop-review.md", subjects)
        self.assertIn("VERDICT_PATH", subjects)


if __name__ == "__main__":
    unittest.main()
