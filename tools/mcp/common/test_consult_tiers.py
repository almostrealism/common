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

    def test_runtime_dot_directory_paths_are_unchecked(self):
        # A path rooted in a dot-directory the snapshot lacks (a runtime
        # output never committed) must not be tiered stale: it is absent by
        # construction. A dot-directory the tree does contain stays checked.
        result = classify_mentions(
            [".flowtree/oop-review.md", ".github/CLAUDE.md", ".github/gone.md"],
            self.index)
        self.assertNotIn(".flowtree/oop-review.md",
                         result["present"] + result["absent"])
        self.assertIn(".github/CLAUDE.md", result["present"])
        self.assertIn(".github/gone.md", result["absent"])

    def test_tree_external_slashed_paths_are_unchecked(self):
        # A slash-bearing path whose first segment is not a top-level
        # directory of the snapshot — another repository's path, or a
        # module-relative fragment the tree does not root — must be left
        # unchecked, never tiered stale. A trailing fragment the snapshot
        # does contain still resolves as present; a missing file under a
        # real top-level directory is still absent.
        result = classify_mentions(
            ["ringsdesktop/docs/planning/feature.md", "other-repo/config.yaml",
             "ci/check.sh", "tools/ci/gone.sh"], self.index)
        self.assertNotIn("ringsdesktop/docs/planning/feature.md",
                         result["present"] + result["absent"])
        self.assertNotIn("other-repo/config.yaml",
                         result["present"] + result["absent"])
        # A trailing fragment of a real path resolves as present.
        self.assertIn("ci/check.sh", result["present"])
        # A missing file under a real top-level directory is genuinely absent.
        self.assertIn("tools/ci/gone.sh", result["absent"])

    def test_a_placeholder_path_is_not_split_into_bogus_names(self):
        # Observed 2026-10-20: a correct note naming the prompt-builder family
        # was tiered C✗ because the template was cut at <x> into
        # "tools/ci/prompts/build" and "-prompt.sh", neither of which exists.
        note = ("Each round's prompt comes from tools/ci/prompts/build-<x>-prompt.sh "
                "(`tools/ci/prompts/build-<x>-prompt.sh`), see tools/ci/check.sh.")
        mentions = extract_mentions(note)
        self.assertFalse(any("prompts/build" in m or m.startswith("-")
                             for m in mentions), mentions)
        self.assertIn("tools/ci/check.sh", mentions)
        self.assertEqual(classify_mentions(mentions, self.index)["absent"], [])

    def test_glob_and_brace_templates_are_unchecked(self):
        mentions = extract_mentions(
            "Matches tools/ci/*.sh, docs/**/*.md, {module}/pom.xml and "
            "project/doc-* but **CodingAgentJob** is a name.")
        self.assertEqual(mentions, ["CodingAgentJob"])
        # Handed a template directly, classification leaves it unchecked.
        result = classify_mentions(
            ["tools/ci/build-<x>-prompt.sh", "tools/*/gone.sh", "{a}/b.md"],
            self.index)
        self.assertEqual(result, {"present": [], "absent": []})

    def test_a_lock_pattern_prefix_is_not_an_absent_directory(self):
        # Observed 2026-10-20: ".github/actions/" is a lock pattern in
        # check-ci-file-lock.sh, not a directory the tree has. A prefix with
        # nothing under it is unchecked; one that matches is present.
        mentions = extract_mentions(
            "Locked: .github/workflows/, .github/actions/ and tools/ci/.")
        self.assertIn(".github/actions/", mentions)
        result = classify_mentions(mentions + ["tools/ci/prompts/build-"],
                                   self.index)
        self.assertEqual(result["absent"], [])
        self.assertIn("tools/ci/", result["present"])
        self.assertNotIn(".github/actions/", result["present"])

    def test_without_an_index_nothing_is_absent(self):
        self.assertEqual(classify_mentions(["ClaudeCodeJob"], None),
                         {"present": [], "absent": []})


if __name__ == "__main__":
    unittest.main()
