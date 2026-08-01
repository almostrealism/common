"""Focused tests for the github_request_copilot_review tool.

These tests use mocks — no live GitHub API is called.

NOTE: The only end-to-end verification of correctness is to manually invoke
the tool against a real PR and confirm Copilot appears in the
requested_reviewers list.  The pytest suite alone is not sufficient — a
prior implementation shipped broken because the mock did not reflect real
API behaviour (passing BOT_* node IDs to the GraphQL userIds field).
The tests below are written against realistic mock responses that match
what the GitHub API actually returns.
"""

import os
import sys
import unittest
from unittest.mock import patch

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_github_scope():
    server._set_scopes(
        ["read", "write", "submit", "pipeline", "github",
         "memory-read", "memory-write"],
        label="test",
    )


# ---------------------------------------------------------------------------
# Helpers for building realistic mock responses
# ---------------------------------------------------------------------------

def _lookup_response(pr_id="PR_kg1", bot_id="BOT_kgDOC9w8XQ",
                     bot_login="copilot-pull-request-reviewer",
                     include_copilot=True):
    """Build a realistic CopilotLookup GraphQL response."""
    actors = []
    if include_copilot:
        actors.append({"__typename": "Bot", "login": bot_login, "id": bot_id})
    actors.append({"__typename": "User", "login": "alice", "id": "U_kg1"})
    return {"data": {"repository": {
        "pullRequest": {"id": pr_id} if pr_id else None,
        "suggestedActors": {"nodes": actors},
    }}}


def _rest_pr_response(reviewer_logins):
    """Build a realistic REST requested_reviewers PR response.

    Matches the shape of the GitHub ``POST .../requested_reviewers``
    response: a PR object whose ``requested_reviewers`` field contains
    objects with ``login``, ``type``, and ``id``.
    """
    reviewers = [
        {"login": lg,
         "type": "Bot" if "copilot" in lg.lower() else "User",
         "id": 12345}
        for lg in reviewer_logins
    ]
    return {
        "id": 1234567,
        "number": 42,
        "state": "open",
        "requested_reviewers": reviewers,
        "requested_teams": [],
    }


def _graphql_user_node_error(bot_id):
    """The real GitHub GraphQL error when a Bot node ID goes into userIds.

    This is the exact error shape the real GitHub API returns when
    ``requestReviews`` is called with a Bot node ID in ``userIds``.
    """
    return {
        "data": {"requestReviews": None},
        "errors": [{
            "message": (
                f"Could not resolve to User node with the global id of '{bot_id}'."
            ),
            "locations": [{"line": 2, "column": 3}],
            "path": ["requestReviews"],
        }],
    }


# ---------------------------------------------------------------------------
# Failure mode: why GraphQL userIds does not work for Bot node IDs
# ---------------------------------------------------------------------------

class TestCopilotReviewApiShape(unittest.TestCase):
    """Tests that verify the correct API shape for requesting Copilot reviews.

    Includes documentary tests that encode the real GitHub API failure mode
    (passing a Bot node ID to GraphQL ``userIds``) so the reason for using
    the REST endpoint is visible in the test suite.
    """

    def setUp(self):
        _grant_github_scope()

    def test_graphql_userids_rejects_bot_node_id(self):
        """GraphQL requestReviews fails when a Bot node ID is put in userIds.

        BOT_kgDOC9w8XQ is a legitimate Copilot bot node ID returned by the
        suggestedActors query.  The GitHub GraphQL requestReviews mutation
        only accepts User-type node IDs in userIds — passing a Bot-type node
        ID raises the error encoded in this test.

        This test encodes the real API error response so future maintainers
        understand why the implementation uses the REST endpoint instead of
        the GraphQL mutation.
        """
        bot_id = "BOT_kgDOC9w8XQ"
        real_api_error = _graphql_user_node_error(bot_id)

        # Verify the error shape matches what the real API returns
        errors = real_api_error.get("errors") or []
        self.assertTrue(len(errors) > 0)
        self.assertIn("User node", errors[0]["message"])
        self.assertIn(bot_id, errors[0]["message"])

        # Verify that _is_already_reviewed_error does NOT fire for this error —
        # the "cannot resolve User node" error is a permanent failure, not a
        # transient "already reviewed" state that dismiss-and-retry can fix.
        self.assertFalse(
            server._is_already_reviewed_error(errors[0]["message"]),
            "Bot node ID rejection should not be confused with 'already reviewed'",
        )

    @patch.object(server, "_dismiss_copilot_review", return_value={"ok": True})
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_dismiss_and_retry_on_already_reviewed_error(
            self, mock_gql, mock_rest, mock_dismiss):
        """Dismiss-and-retry also fires for the 'already reviewed' wording."""
        mock_gql.return_value = _lookup_response()
        mock_rest.side_effect = [
            {"ok": False,
             "error": "GitHub returned HTTP 422: Already requested a review"},
            _rest_pr_response(["copilot-pull-request-reviewer"]),
        ]

        result = server._request_copilot_review("owner", "repo", 42)

        self.assertTrue(result["ok"])
        mock_dismiss.assert_called_once()


if __name__ == "__main__":
    unittest.main()
