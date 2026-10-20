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
    REASON_BRANCH_LOCAL, REASON_CONTRADICTED, REASON_FREE_TEXT, REASON_SUBJECT,
    REASON_TIER, REASON_UNCITED, REASON_UNKNOWN, REASON_UNSUPPORTED_UNCITED,
    REASON_VALUE, UNSUPPORTED_MARK, salient_tokens, split_sentences, verify,
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
