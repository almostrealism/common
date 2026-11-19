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
    def test_negative_pr_number_rejected(self, mock_gh, mock_repo):
        result = server.github_update_pr(pr_number=-3, title="New")
        self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_looks_up_open_pr_when_number_omitted(self, mock_gh, mock_repo):
        """With no pr_number, the open PR for the branch is looked up first."""
        mock_gh.side_effect = [
            [{"number": 7}],            # open-PR lookup for the branch
            self._pr(title="New"),      # PATCH response
        ]
        result = server.github_update_pr(title="New")
        self.assertTrue(result["ok"], msg=result.get("error"))
        # The PATCH targets the looked-up PR number.
        method, path, _ = mock_gh.call_args.args
        self.assertEqual("PATCH", method)
        self.assertEqual("/repos/org/repo/pulls/7", path)

    @patch.object(server, "_github_request", return_value=[])
    def test_no_open_pr_is_error(self, mock_gh, mock_repo):
        """When the branch has no open PR, the omitted-number path errors."""
        result = server.github_update_pr(title="New")
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_require_org_in_scope")
    @patch.object(server, "_github_request")
    def test_direct_org_repo_without_pr_or_branch_refused(
            self, mock_gh, mock_gate, mock_repo):
        """Direct org/repo addressing needs a branch when pr_number is omitted."""
        result = server.github_update_pr(title="New", org="org", repo="repo")
        self.assertFalse(result["ok"])
        self.assertIn("branch is required", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_whitespace_only_title_difference_is_success(self, mock_gh, mock_repo):
        """A title GitHub trims, and a body differing only in CRLF, are the
        edit we sent, so ok=True."""
        mock_gh.return_value = self._pr(title="New", body="Body text\n")
        result = server.github_update_pr(
            pr_number=7, title="  New  ", body="Body text\r\n")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual(["body", "title"], result["updated_fields"])

    @patch.object(server, "_github_request")
    def test_body_whitespace_difference_is_not_success(self, mock_gh, mock_repo):
        """A PATCH silently answered as a GET is caught on body whitespace.

        Requesting body ``" Body "`` when the stored body is ``"Body"`` is a
        real, unapplied change: the body comparison must not strip, so this
        reports ok=False instead of a false success.
        """
        mock_gh.return_value = self._pr(body="Body")
        result = server.github_update_pr(pr_number=7, body=" Body ")
        self.assertFalse(result["ok"])
        self.assertIn("body", result["error"])
        self.assertEqual({"body": "Body"}, result["returned"])

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

    @patch.object(server, "_github_request",
                  return_value={"ok": True, "status": 204})
    def test_response_without_pr_object_is_not_success(self, mock_gh, mock_repo):
        """A bodyless success (e.g. a 204) confirms no field, so it is a failure.

        ``_github_request`` reports an empty response as ``{"ok": True,
        "status": 204}``. That carries no PR object, so the edit cannot be
        verified; the tool must report ok=False rather than pass the bodyless
        success through as if the change had been applied.
        """
        result = server.github_update_pr(pr_number=7, title="New")
        self.assertFalse(result["ok"])
        self.assertIn("could not be confirmed", result["error"])
        self.assertEqual({"ok": True, "status": 204}, result["returned"])

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
