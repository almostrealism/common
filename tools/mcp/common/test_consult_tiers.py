"""Tests for consult tier assignment and mention extraction."""

import os
import sys
import unittest
from datetime import datetime, timezone

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from consult_evidence import (  # noqa: E402
    TIER_BRANCH_LOCAL, TIER_CURRENT, TIER_HISTORICAL, TIER_STALE,
)
from consult_tiers import (  # noqa: E402
    STATE_CLOSED, STATE_MERGED, STATE_MERGED_ELSEWHERE, STATE_OPEN,
    STATE_TARGET, BranchState, Caller, classify_mentions, extract_mentions,
    tier,
)
from snapshot_index import SnapshotIndex  # noqa: E402

SNAPSHOT = "2026-10-19T00:00:00Z"
NOW = datetime(2026, 10, 19, tzinfo=timezone.utc)


def _tier(created, branch, state=BranchState(), absent=(), caller=Caller()):
    return tier({"created_at": created, "branch": branch}, state, caller,
                SNAPSHOT, list(absent), now=NOW)[0]


class TestTier(unittest.TestCase):

    def test_recent_note_on_the_target_branch_is_current(self):
        self.assertEqual(_tier("2026-10-10", "master",
                               BranchState(STATE_TARGET)), TIER_CURRENT)

    def test_recent_note_merged_into_the_target_is_current(self):
        self.assertEqual(_tier("2026-10-10", "feature/x",
                               BranchState(STATE_MERGED, "2026-10-12")),
                         TIER_CURRENT)

    def test_recent_note_on_the_callers_own_branch_is_current(self):
        self.assertEqual(_tier("2026-10-10", "feature/mine", BranchState(STATE_OPEN),
                               caller=Caller(branch="feature/mine")),
                         TIER_CURRENT)

    def test_open_branch_of_someone_else_is_branch_local(self):
        self.assertEqual(_tier("2026-10-10", "feature/x", BranchState(STATE_OPEN)),
                         TIER_BRANCH_LOCAL)

    def test_merged_into_another_base_is_branch_local(self):
        # Merged somewhere is not merged into the branch being asked about.
        self.assertEqual(_tier("2026-10-10", "feature/x",
                               BranchState(STATE_MERGED_ELSEWHERE, "2026-10-12",
                                           "develop")),
                         TIER_BRANCH_LOCAL)

    def test_closed_unmerged_is_historical(self):
        self.assertEqual(_tier("2026-10-10", "feature/x", BranchState(STATE_CLOSED)),
                         TIER_HISTORICAL)

    def test_old_note_is_historical(self):
        self.assertEqual(_tier("2026-07-01", "master", BranchState(STATE_TARGET)),
                         TIER_HISTORICAL)

    def test_unknown_branch_state_is_historical(self):
        self.assertEqual(_tier("2026-10-10", "feature/x"), TIER_HISTORICAL)

    def test_absent_name_makes_an_older_note_stale(self):
        self.assertEqual(_tier("2026-10-10", "master", BranchState(STATE_TARGET),
                               absent=["ClaudeCodeJob"]), TIER_STALE)

    def test_a_note_written_after_the_snapshot_is_not_stale_on_absence(self):
        # The symbol may simply be newer than the snapshot.
        self.assertEqual(_tier("2026-10-20", "feature/x",
                               BranchState(STATE_MERGED, "2026-10-20"),
                               absent=["NewClass"]), TIER_CURRENT)

    def test_the_target_branch_comes_from_the_caller(self):
        caller = Caller(target_branch="develop")
        self.assertEqual(_tier("2026-10-10", "develop", caller=caller), TIER_CURRENT)
        self.assertEqual(_tier("2026-10-10", "master", caller=caller), TIER_HISTORICAL)


class TestMentions(unittest.TestCase):

    def setUp(self):
        self.index = SnapshotIndex({
            "paths": ["tools/ci/check.sh", "flowtree/X.java", ".github/CLAUDE.md"],
            "types": {"CodingAgentJob": ["flowtree/X.java"]},
            "external_types": ["CompletableFuture"],
        })

    def test_extracts_camel_case_backticks_and_paths(self):
        found = extract_mentions(
            "ClaudeCodeJob used `runCorrectionSession` in tools/ci/check.sh:12 "
            "and validate.py; see https://x.io/a/b and /Users/me/file.sh")
        self.assertIn("ClaudeCodeJob", found)
        self.assertIn("runCorrectionSession", found)
        self.assertIn("tools/ci/check.sh", found)
        self.assertIn("validate.py", found)
        self.assertFalse(any("x.io" in m or "Users" in m for m in found))

    def test_classifies_present_and_absent_names(self):
        result = classify_mentions(
            ["ClaudeCodeJob", "CodingAgentJob", "CompletableFuture",
             "tools/ci/check.sh", "tools/ci/gone.sh", "GitHub"], self.index)
        self.assertEqual(result["absent"], ["ClaudeCodeJob", "tools/ci/gone.sh"])
        self.assertIn("CodingAgentJob", result["present"])
        self.assertIn("CompletableFuture", result["present"])
        self.assertNotIn("GitHub", result["present"] + result["absent"])

    def test_branch_names_are_not_checked_as_paths(self):
        # qa/docs- and origin/master share a path's shape but are not rooted
        # in the tree; treating them as paths made correct notes stale.
        result = classify_mentions(["qa/docs-", "origin/master",
                                    "ci/test-protection-consistency"], self.index)
        self.assertEqual(result, {"present": [], "absent": []})

    def test_without_an_index_nothing_is_absent(self):
        self.assertEqual(classify_mentions(["ClaudeCodeJob"], None),
                         {"present": [], "absent": []})


if __name__ == "__main__":
    unittest.main()
