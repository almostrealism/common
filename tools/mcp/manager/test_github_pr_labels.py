"""Tests for the github_pr_labels tool.

Follows the patterns in ``test_github_tools.py`` — shared fixtures live in
``manager_test_support`` and the server helpers are patched by name.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server, _grant_scopes, _grant_all_scopes,
)


class TestGithubPrLabels(unittest.TestCase):
    """Reading a PR's labels together with its draft status."""

    def setUp(self):
        _grant_all_scopes()

    def _make_pr(self, number=42, draft=False, state="open", labels=None,
                 sha="abc123", ref="feature/x"):
        return {
            "number": number,
            "draft": draft,
            "state": state,
            "labels": labels if labels is not None else [],
            "head": {"sha": sha, "ref": ref},
            "html_url": f"https://github.com/owner/repo/pull/{number}",
        }

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_labels_parsed_with_detail(self, mock_gh, _):
        mock_gh.return_value = self._make_pr(labels=[
            {"name": "ci: ready for review", "color": "0e8a16",
             "description": "Ready"},
            {"name": "bug", "color": "d73a4a", "description": None},
        ])
        result = server.github_pr_labels(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["pr_number"], 42)
        self.assertEqual(result["repo"], "owner/repo")
        self.assertEqual(result["state"], "open")
        self.assertEqual(result["labels"], ["ci: ready for review", "bug"])
        self.assertEqual(result["label_details"][0]["color"], "0e8a16")
        self.assertEqual(result["label_details"][0]["description"], "Ready")
        self.assertEqual(result["head_branch"], "feature/x")
        self.assertEqual(result["head_sha"], "abc123")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_draft_true(self, mock_gh, _):
        mock_gh.return_value = self._make_pr(draft=True)
        result = server.github_pr_labels(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertTrue(result["draft"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_draft_false(self, mock_gh, _):
        mock_gh.return_value = self._make_pr(draft=False)
        result = server.github_pr_labels(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertFalse(result["draft"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_empty_labels(self, mock_gh, _):
        mock_gh.return_value = self._make_pr(labels=[])
        result = server.github_pr_labels(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["labels"], [])
        self.assertEqual(result["label_details"], [])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_lookup_pr_by_branch_when_number_omitted(self, mock_gh, _):
        # When pr_number is omitted the open PR is found from the branch via
        # the list endpoint, whose PR objects also carry labels and draft.
        mock_gh.return_value = [self._make_pr(number=7, draft=True, labels=[
            {"name": "wip", "color": "fbca04", "description": None},
        ])]
        result = server.github_pr_labels()
        self.assertTrue(result["ok"])
        self.assertEqual(result["pr_number"], 7)
        self.assertTrue(result["draft"])
        self.assertEqual(result["labels"], ["wip"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request", return_value=[])
    def test_no_open_pr_for_branch(self, mock_gh, _):
        result = server.github_pr_labels()
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "", None))
    def test_no_branch_and_no_pr_number(self, _):
        result = server.github_pr_labels()
        self.assertFalse(result["ok"])
        self.assertIn("pr_number or branch", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_github_error_passthrough(self, mock_gh, _):
        mock_gh.return_value = {"ok": False,
                                "error": "GitHub returned HTTP 404: Not Found"}
        result = server.github_pr_labels(pr_number=999)
        self.assertFalse(result["ok"])
        self.assertIn("404", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "bad repo"}))
    def test_repo_resolution_error_propagates(self, _):
        result = server.github_pr_labels(pr_number=1)
        self.assertFalse(result["ok"])
        self.assertIn("bad repo", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_pr_labels(pr_number=1)


if __name__ == "__main__":
    unittest.main()
