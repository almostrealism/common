"""Tests that the ar-manager github_* write tools refuse private Claude links.

These exercise the enforcement layer end to end at the tool boundary: a write
that carries a session link, bare session id, or attribution trailer must be
refused with the owner's block message and must never reach the controller
proxy (``server._github_request``). Link literals are assembled from fragments
so this file contains no intact private link.
"""

import os
import sys
import tempfile
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402

_CLAUDE_HOST = "claude" + ".ai"
_SESSION = "session_" + "fakefakefake1234"
_CODE_SESSION_URL = "https://" + _CLAUDE_HOST + "/code/" + _SESSION


@patch.object(server, "_controller_post", return_value={"ok": True})
@patch.object(server, "_resolve_github_repo",
              return_value=("org", "repo", "feature/x", None))
@patch.object(server, "_resolve_effective_pr",
              return_value=("org", "repo", 7, None))
class PrivateLinkBlockTest(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        self._tmp = tempfile.NamedTemporaryFile(
            suffix=".jsonl", delete=False, mode="w")
        self._tmp.close()
        os.environ["AR_PRIVATE_LINK_INCIDENT_LOG"] = self._tmp.name

    def tearDown(self):
        os.environ.pop("AR_PRIVATE_LINK_INCIDENT_LOG", None)
        os.unlink(self._tmp.name)

    def _assert_blocked(self, result):
        self.assertFalse(result["ok"])
        self.assertEqual("private_link", result.get("blocked"))
        self.assertIn("malice", result["error"].lower())
        self.assertIn("reported", result["error"].lower())

    @patch.object(server, "_github_request")
    def test_create_pr_blocks_link_in_body(self, mock_gh, *_):
        result = server.github_create_pr(
            title="Fix kernel", body="Done. " + _CODE_SESSION_URL,
            base="master", head="feature/x", org="org", repo="repo")
        self._assert_blocked(result)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_create_pr_blocks_link_in_title(self, mock_gh, *_):
        result = server.github_create_pr(
            title="See " + _SESSION, body="clean body",
            base="master", head="feature/x", org="org", repo="repo")
        self._assert_blocked(result)
        self.assertIn("title", result["target"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_update_pr_blocks_link_in_body(self, mock_gh, *_):
        result = server.github_update_pr(
            pr_number=7, body="Updated. " + _CODE_SESSION_URL,
            org="org", repo="repo")
        self._assert_blocked(result)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_pr_reply_blocks_link_in_body(self, mock_gh, *_):
        result = server.github_pr_reply(
            comment_id=1, body="Fixed, see " + _CODE_SESSION_URL,
            pr_number=7, org="org", repo="repo")
        self._assert_blocked(result)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_clean_create_pr_proceeds(self, mock_gh, *_):
        mock_gh.return_value = {"number": 7, "html_url": "u", "title": "Fix kernel"}
        result = server.github_create_pr(
            title="Fix kernel", body="Adjusts the offset.",
            base="master", head="feature/x", org="org", repo="repo")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_gh.assert_called_once()

    @patch.object(server, "_github_request")
    def test_block_records_incident(self, mock_gh, *_):
        import private_link_incident
        server.github_create_pr(
            title="x", body=_CODE_SESSION_URL,
            base="master", head="feature/x", org="org", repo="repo")
        incidents = private_link_incident.query_incidents()
        self.assertTrue(incidents)
        self.assertEqual("private_link_block", incidents[0]["type"])
        self.assertEqual("github_create_pr", incidents[0]["route"])
        # The incident log never contains the raw link.
        with open(self._tmp.name, "r", encoding="utf-8") as handle:
            self.assertNotIn(_SESSION, handle.read())


if __name__ == "__main__":
    unittest.main()
