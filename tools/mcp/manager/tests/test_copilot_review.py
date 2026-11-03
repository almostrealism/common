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
    import github_api


# The verbatim body GitHub posts as a "review" when Copilot is out of quota.
_QUOTA_BODY = (
    "Copilot was unable to review this pull request because the user who "
    "requested the review has insufficient remaining quota."
)


def _review(login, body, submitted_at, state="COMMENTED"):
    """Build a review object in the shape github_pr_reviews returns."""
    return {
        "id": hash((login, submitted_at)) & 0xFFFF,
        "user": login,
        "state": state,
        "submitted_at": submitted_at,
        "commit_id": "deadbeef",
        "body": body,
    }


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


class TestCopilotQuotaNoticeDetection(unittest.TestCase):
    """The out-of-quota refusal Copilot posts as a review is detected as such."""

    def test_is_copilot_quota_notice_matches_the_verbatim_body(self):
        self.assertTrue(github_api.is_copilot_quota_notice(_QUOTA_BODY))

    def test_is_copilot_quota_notice_matches_phrasing_variants(self):
        self.assertTrue(github_api.is_copilot_quota_notice(
            "Copilot was unable to review this pull request because the user "
            "who requested the review has reached their quota limit."))

    def test_is_copilot_quota_notice_rejects_a_real_review_body(self):
        self.assertFalse(github_api.is_copilot_quota_notice(
            "Changes recommended. Previously missed: a null check in Foo."))

    def test_is_copilot_quota_notice_rejects_unrelated_quota_text(self):
        # "quota" alone, without the refusal wording, is not a quota notice.
        self.assertFalse(github_api.is_copilot_quota_notice(
            "This change adds a per-PR quota limit to the controller."))

    def test_is_copilot_quota_notice_rejects_empty_and_non_string(self):
        self.assertFalse(github_api.is_copilot_quota_notice(""))
        self.assertFalse(github_api.is_copilot_quota_notice(None))

    def test_latest_copilot_quota_notice_returns_notice_when_latest(self):
        reviews = [
            _review("alice", "Looks good", "2026-10-26T00:00:00Z"),
            _review("copilot-pull-request-reviewer[bot]",
                    _QUOTA_BODY, "2026-10-26T02:29:00Z"),
        ]
        notice = github_api.latest_copilot_quota_notice(
            reviews, server._is_copilot_login)
        self.assertIsNotNone(notice)
        self.assertEqual(notice["submitted_at"], "2026-10-26T02:29:00Z")

    def test_latest_copilot_quota_notice_none_when_latest_is_real(self):
        # Copilot refused once, then a later real review ran: available again.
        reviews = [
            _review("copilot-pull-request-reviewer[bot]",
                    _QUOTA_BODY, "2026-10-20T00:00:00Z"),
            _review("copilot-pull-request-reviewer[bot]",
                    "Changes recommended.", "2026-10-26T00:00:00Z"),
        ]
        self.assertIsNone(github_api.latest_copilot_quota_notice(
            reviews, server._is_copilot_login))

    def test_latest_copilot_quota_notice_none_when_no_copilot_review(self):
        reviews = [_review("alice", _QUOTA_BODY, "2026-10-26T00:00:00Z")]
        self.assertIsNone(github_api.latest_copilot_quota_notice(
            reviews, server._is_copilot_login))

    def test_latest_copilot_quota_notice_accepts_nested_user_object(self):
        # The raw REST review object nests the login under user.login.
        reviews = [{
            "user": {"login": "copilot-pull-request-reviewer[bot]"},
            "submitted_at": "2026-10-26T02:29:00Z",
            "body": _QUOTA_BODY,
        }]
        self.assertIsNotNone(github_api.latest_copilot_quota_notice(
            reviews, server._is_copilot_login))


class TestCopilotReviewReportsQuotaRefusal(unittest.TestCase):
    """_request_copilot_review reports a quota refusal instead of success."""

    def setUp(self):
        _grant_github_scope()

    @patch.object(server, "_github_request")
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_quota_refusal_reported_after_request_accepted(
            self, mock_gql, mock_rest, mock_request):
        mock_gql.return_value = _lookup_response()
        # GitHub accepts the request: Copilot is in requested_reviewers.
        mock_rest.return_value = _rest_pr_response(
            ["copilot-pull-request-reviewer"])
        # But the PR's latest Copilot review is the out-of-quota refusal.
        mock_request.return_value = [
            _review("copilot-pull-request-reviewer[bot]",
                    _QUOTA_BODY, "2026-10-26T02:29:00Z"),
        ]

        result = server._request_copilot_review("owner", "repo", 42)

        self.assertFalse(result["ok"])
        self.assertTrue(result.get("quota_exceeded"))
        self.assertIn("quota", result["error"].lower())

    @patch.object(server, "_github_request")
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_success_when_no_quota_notice_present(
            self, mock_gql, mock_rest, mock_request):
        mock_gql.return_value = _lookup_response()
        mock_rest.return_value = _rest_pr_response(
            ["copilot-pull-request-reviewer"])
        # No reviews yet (Copilot will post a real one shortly).
        mock_request.return_value = []

        result = server._request_copilot_review("owner", "repo", 42)

        self.assertTrue(result["ok"])
        self.assertNotIn("quota_exceeded", result)

    @patch.object(server, "_github_request")
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_quota_refusal_on_a_later_page_is_reported(
            self, mock_gql, mock_rest, mock_request):
        mock_gql.return_value = _lookup_response()
        mock_rest.return_value = _rest_pr_response(
            ["copilot-pull-request-reviewer"])
        # A full first page of older real reviews; the refusal is on page 2.
        page1 = [_review("copilot-pull-request-reviewer[bot]", "Looks fine.",
                         "2026-10-01T00:00:%02dZ" % (i % 60)) for i in range(100)]
        page2 = [_review("copilot-pull-request-reviewer[bot]",
                         _QUOTA_BODY, "2026-10-26T02:29:00Z")]
        mock_request.side_effect = [page1, page2]

        result = server._request_copilot_review("owner", "repo", 42)

        self.assertFalse(result["ok"])
        self.assertTrue(result.get("quota_exceeded"))
        self.assertEqual(mock_request.call_count, 2)
        self.assertIn("page=2", mock_request.call_args_list[1][0][1])

    @patch.object(server, "_github_request")
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_review_listing_error_is_not_a_quota_refusal(
            self, mock_gql, mock_rest, mock_request):
        mock_gql.return_value = _lookup_response()
        mock_rest.return_value = _rest_pr_response(
            ["copilot-pull-request-reviewer"])
        mock_request.return_value = {"ok": False, "error": "boom"}

        result = server._request_copilot_review("owner", "repo", 42)

        self.assertTrue(result["ok"])
        self.assertNotIn("quota_exceeded", result)


if __name__ == "__main__":
    unittest.main()
