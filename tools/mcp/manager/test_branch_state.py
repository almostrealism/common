"""Tests for the branch-state lookup behind note tiers.

The properties that matter: the lookup runs only for a caller holding the
``github`` scope whose workspace may see the note's organization, a skipped
or failed lookup degrades to ``unknown`` and never touches the cache, and the
cache holds only objective PR state.
"""

import unittest
from unittest.mock import patch

from manager_test_support import _grant_all_scopes, _grant_scopes, server

import branch_state
from branch_state import BranchStateResolver, FixedBranchStates, parse_repo_url
from consult_tiers import (
    STATE_CLOSED, STATE_MERGED, STATE_MERGED_ELSEWHERE, STATE_OPEN,
    STATE_TARGET, STATE_UNKNOWN, Caller,
)

REPO = "git@github.com:org/repo.git"
CALLER = Caller(branch="feature/me", target_branch="master")


def _pr(state="closed", merged_at="2026-10-16T00:00:00Z", base="master"):
    return {"ok": True, "found": True,
            "pr": {"state": state, "number": 7, "merged_at": merged_at,
                   "head": {"sha": "abc"}, "base": {"ref": base}}}


class TestBranchStateResolver(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        self.now = [1000.0]
        self.resolver = BranchStateResolver(clock=lambda: self.now[0])
        patcher = patch.object(server, "_resolve_github_repo",
                               return_value=("org", "repo", "", None))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_parse_repo_url(self):
        self.assertEqual(parse_repo_url(REPO), ("org", "repo"))
        self.assertEqual(parse_repo_url("https://github.com/org/repo"), ("org", "repo"))
        self.assertEqual(parse_repo_url("not a url"), ("", ""))

    def test_the_target_branch_needs_no_lookup(self):
        with patch.object(server, "_find_recent_pr_by_branch") as find:
            state, degraded = self.resolver.resolve(REPO, "master", CALLER)
        self.assertEqual(state.state, STATE_TARGET)
        self.assertFalse(degraded)
        find.assert_not_called()

    @patch.object(server, "_find_recent_pr_by_branch")
    def test_merged_open_and_closed_states(self, find):
        find.return_value = _pr()
        self.assertEqual(self.resolver.resolve(REPO, "a", CALLER)[0].state, STATE_MERGED)
        find.return_value = _pr(base="develop")
        self.assertEqual(self.resolver.resolve(REPO, "b", CALLER)[0].state,
                         STATE_MERGED_ELSEWHERE)
        find.return_value = _pr(state="open", merged_at=None)
        self.assertEqual(self.resolver.resolve(REPO, "c", CALLER)[0].state, STATE_OPEN)
        find.return_value = _pr(state="closed", merged_at=None)
        self.assertEqual(self.resolver.resolve(REPO, "d", CALLER)[0].state, STATE_CLOSED)

    @patch.object(server, "_find_recent_pr_by_branch")
    def test_without_the_github_scope_nothing_is_looked_up_or_cached(self, find):
        _grant_scopes("memory-read")
        state, degraded = self.resolver.resolve(REPO, "a", CALLER)
        self.assertEqual(state.state, STATE_UNKNOWN)
        self.assertTrue(degraded)
        find.assert_not_called()
        self.assertEqual(self.resolver._cache, {})

    @patch.object(server, "_find_recent_pr_by_branch")
    def test_the_org_gate_applies_before_the_cache(self, find):
        # A privileged entry must never be served to a caller that has not
        # passed the gate itself.
        find.return_value = _pr()
        self.resolver.resolve(REPO, "a", CALLER)
        with patch.object(server, "_require_org_in_scope",
                          side_effect=PermissionError("no")):
            state, degraded = self.resolver.resolve(REPO, "a", CALLER)
        self.assertEqual(state.state, STATE_UNKNOWN)
        self.assertTrue(degraded)

    @patch.object(server, "_find_recent_pr_by_branch")
    def test_a_failed_lookup_degrades_and_is_not_cached(self, find):
        find.return_value = {"ok": False, "error": "rate limited"}
        state, degraded = self.resolver.resolve(REPO, "a", CALLER)
        self.assertEqual((state.state, degraded), (STATE_UNKNOWN, True))
        self.assertEqual(self.resolver._cache, {})

    @patch.object(server, "_find_recent_pr_by_branch")
    def test_open_entries_expire_sooner_than_settled_ones(self, find):
        find.return_value = _pr(state="open", merged_at=None)
        self.resolver.resolve(REPO, "o", CALLER)
        find.return_value = _pr()
        self.resolver.resolve(REPO, "m", CALLER)
        self.assertEqual(find.call_count, 2)
        self.now[0] += branch_state.OPEN_TTL_SECONDS + 1
        self.resolver.resolve(REPO, "o", CALLER)
        self.resolver.resolve(REPO, "m", CALLER)
        self.assertEqual(find.call_count, 3)


class TestFixedBranchStates(unittest.TestCase):

    def test_answers_from_its_map(self):
        fixed = FixedBranchStates({"a": {"state": "merged", "merged_at": "2026-10-01"}})
        self.assertEqual(fixed.resolve(REPO, "a", CALLER)[0].state, STATE_MERGED)
        self.assertEqual(fixed.resolve(REPO, "master", CALLER)[0].state, STATE_TARGET)
        self.assertEqual(fixed.resolve(REPO, "zz", CALLER)[0].state, STATE_UNKNOWN)


if __name__ == "__main__":
    unittest.main()
