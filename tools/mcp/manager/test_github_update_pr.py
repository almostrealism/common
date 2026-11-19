"""Tests for ``github_update_pr``.

Split from ``test_github_tools.py``, which had grown against the file-length
cap. The tests are unchanged apart from the empty-body-clears coverage added
alongside the move; shared fixtures live in ``manager_test_support``.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402


@patch.object(server, "_resolve_github_repo",
              return_value=("org", "repo", "feature/x", None))
class TestGithubUpdatePr(unittest.TestCase):
    """``github_update_pr`` sends only title/body in a PATCH to the PR."""

    def setUp(self):
        _grant_all_scopes()

    def _pr(self, **fields):
        pr = {"number": 7, "html_url": "u", "title": "Old", "body": "Old body"}
        pr.update(fields)
        return pr

    def _sent(self, mock_gh):
        method, path, payload = mock_gh.call_args.args
        self.assertEqual("PATCH", method)
        self.assertEqual("/repos/org/repo/pulls/7", path)
        self.assertTrue(set(payload) <= {"title", "body"}, payload)
        return payload

    @patch.object(server, "_github_request")
    def test_title_only(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(title="New")
        result = server.github_update_pr(pr_number=7, title="New")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual({"title": "New"}, self._sent(mock_gh))
        self.assertEqual(["title"], result["updated_fields"])
        self.assertEqual((7, "u", "New"),
                         (result["number"], result["url"], result["title"]))

    @patch.object(server, "_github_request")
    def test_body_only(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(body="New body")
        result = server.github_update_pr(pr_number=7, body="New body")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual({"body": "New body"}, self._sent(mock_gh))
        self.assertEqual(["body"], result["updated_fields"])

    @patch.object(server, "_github_request")
    def test_empty_body_clears_description(self, mock_gh, mock_repo):
        """``body=""`` sends an empty body so a description can be cleared."""
        mock_gh.return_value = self._pr(body="")
        result = server.github_update_pr(pr_number=7, body="")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual({"body": ""}, self._sent(mock_gh))
        self.assertEqual(["body"], result["updated_fields"])

    @patch.object(server, "_github_request")
    def test_title_and_body(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(title="New", body="New body")
        result = server.github_update_pr(pr_number=7, title="New", body="New body")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual({"title": "New", "body": "New body"}, self._sent(mock_gh))
        self.assertEqual(["body", "title"], result["updated_fields"])

    @patch.object(server, "_github_request")
    def test_neither_rejected_without_request(self, mock_gh, mock_repo):
        result = server.github_update_pr(pr_number=7)
        self.assertFalse(result["ok"])
        self.assertIn("error", result)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_empty_or_whitespace_title_rejected_without_request(self, mock_gh, mock_repo):
        for bad in ("   ", ""):
            result = server.github_update_pr(pr_number=7, title=bad, body="B")
            self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_non_positive_pr_number_rejected(self, mock_gh, mock_repo):
        for bad in (0, -3):
            result = server.github_update_pr(pr_number=bad, title="New")
            self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_whitespace_only_difference_is_success(self, mock_gh, mock_repo):
        """A title GitHub trims is still the edit we sent, so ok=True."""
        mock_gh.return_value = self._pr(title="New", body="Body text")
        result = server.github_update_pr(
            pr_number=7, title="  New  ", body="Body text\r\n")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual(["body", "title"], result["updated_fields"])

    @patch.object(server, "_github_request")
    def test_unchanged_response_is_not_success(self, mock_gh, mock_repo):
        """A proxy that answers the PATCH as a GET returns the old PR."""
        mock_gh.return_value = self._pr()
        result = server.github_update_pr(pr_number=7, title="New")
        self.assertFalse(result["ok"])
        self.assertIn("title", result["error"])
        self.assertEqual({"title": "Old"}, result["returned"])

    @patch.object(server, "_github_request",
                  return_value={"ok": False, "error": "GitHub returned HTTP 422: bad"})
    def test_github_error_returned(self, mock_gh, mock_repo):
        result = server.github_update_pr(pr_number=7, title="New")
        self.assertEqual({"ok": False, "error": "GitHub returned HTTP 422: bad"}, result)

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope")
    def test_org_scope_gate_applied_for_direct_addressing(
            self, mock_gate, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(title="New")
        result = server.github_update_pr(
            pr_number=7, title="New", org="org", repo="repo")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_gate.assert_called_once_with("org")
        self.assertEqual("org", mock_repo.call_args.kwargs["owner"])
        self.assertEqual("repo", mock_repo.call_args.kwargs["repo"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope",
                  side_effect=PermissionError("out of scope"))
    def test_out_of_scope_org_refused_without_request(
            self, mock_gate, mock_gh, mock_repo):
        with self.assertRaises(PermissionError):
            server.github_update_pr(
                pr_number=7, title="New", org="other", repo="repo")
        mock_gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
