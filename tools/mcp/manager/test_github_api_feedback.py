"""Tests for :func:`github_api.pr_outstanding_findings`.

The single source of truth for "is a reviewer finding still unaccounted for"
on a pull request. The incident on PR #866 was an automated pass skipped as
own-commit while a Copilot thread (and a review-body "previously missed"
finding) stood unanswered; these tests pin the four cases that decision turns
on.

``github_api`` is self-contained (it imports nothing from ``server``), so this
test imports it directly without standing up the MCP server.
"""

import os
import sys
import unittest

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

import github_api  # noqa: E402


def _thread(comments, resolved=False, url="https://gh/thread"):
    return {"isResolved": resolved, "url": url, "comments": comments}


def _comment(author, created_at, body="finding", url="https://gh/c"):
    return {"author": {"login": author}, "createdAt": created_at,
            "body": body, "url": url}


def _review(login, state="COMMENTED", body="", submitted_at="2026-11-07T06:50:00Z",
            url="https://gh/r"):
    return {"user": {"login": login}, "state": state, "body": body,
            "submitted_at": submitted_at, "html_url": url}


class TestThreadFindings(unittest.TestCase):
    """Review-thread findings — outstanding until replied to or resolved."""

    def test_own_commit_with_new_copilot_thread_is_outstanding(self):
        # PR #866: Copilot opened a thread on the pass's own commit and nobody
        # replied. The finding is outstanding, so the pass must be dispatched.
        threads = [_thread([_comment("copilot-pull-request-reviewer",
                                     "2026-11-07T06:50:00Z",
                                     body="LaneSum output store is a data race")])]
        findings = github_api.pr_outstanding_findings([], threads)
        self.assertEqual(1, len(findings))
        self.assertEqual("thread", findings[0]["kind"])
        self.assertIn("data race", findings[0]["summary"])

    def test_thread_with_a_reply_after_the_finding_is_accounted_for(self):
        threads = [_thread([
            _comment("copilot-pull-request-reviewer", "2026-11-07T06:50:00Z"),
            _comment("flowtree-agent", "2026-11-07T06:55:00Z",
                     body="Filed follow-up task; see description."),
        ])]
        self.assertEqual([], github_api.pr_outstanding_findings([], threads))

    def test_reviewer_reraising_after_a_reply_is_outstanding_again(self):
        threads = [_thread([
            _comment("copilot-pull-request-reviewer", "2026-11-07T06:50:00Z"),
            _comment("flowtree-agent", "2026-11-07T06:55:00Z", body="done"),
            _comment("copilot-pull-request-reviewer", "2026-11-07T07:10:00Z",
                     body="still a race"),
        ])]
        self.assertEqual(1, len(github_api.pr_outstanding_findings([], threads)))

    def test_resolved_thread_is_accounted_for(self):
        threads = [_thread(
            [_comment("copilot-pull-request-reviewer", "2026-11-07T06:50:00Z")],
            resolved=True)]
        self.assertEqual([], github_api.pr_outstanding_findings([], threads))

    def test_all_threads_answered_is_skippable(self):
        threads = [
            _thread([
                _comment("copilot-pull-request-reviewer", "2026-11-07T06:50:00Z"),
                _comment("flowtree-agent", "2026-11-07T06:55:00Z", body="fixed"),
            ]),
            _thread([_comment("human", "2026-11-07T06:40:00Z")], resolved=True),
        ]
        self.assertEqual([], github_api.pr_outstanding_findings([], threads))


class TestReviewBodyFindings(unittest.TestCase):
    """Findings that appear only in a review body (Copilot's overview)."""

    def test_review_body_only_finding_is_outstanding(self):
        # No thread at all — the finding lives only in Copilot's overview.
        reviews = [_review("copilot-pull-request-reviewer", state="COMMENTED",
                           body="## Previously missed\nCompiledKernelStructureContext "
                                "accepts laneCount < 1.")]
        findings = github_api.pr_outstanding_findings(reviews, [])
        self.assertEqual(1, len(findings))
        self.assertEqual("review", findings[0]["kind"])

    def test_changes_requested_body_is_outstanding(self):
        reviews = [_review("copilot-pull-request-reviewer",
                           state="CHANGES_REQUESTED", body="Please address the below.")]
        self.assertEqual(1, len(github_api.pr_outstanding_findings(reviews, [])))

    def test_latest_approved_review_supersedes_an_earlier_finding(self):
        reviews = [
            _review("copilot-pull-request-reviewer", state="COMMENTED",
                    body="## Previously missed\nx", submitted_at="2026-11-07T06:50:00Z"),
            _review("copilot-pull-request-reviewer", state="APPROVED",
                    body="Looks good now.", submitted_at="2026-11-07T07:20:00Z"),
        ]
        self.assertEqual([], github_api.pr_outstanding_findings(reviews, []))

    def test_quota_notice_is_not_a_finding(self):
        reviews = [_review("copilot-pull-request-reviewer", state="COMMENTED",
                           body="Copilot was unable to review this pull request "
                                "because the user who requested the review has "
                                "insufficient remaining quota.")]
        self.assertEqual([], github_api.pr_outstanding_findings(reviews, []))

    def test_plain_approval_is_accounted_for(self):
        reviews = [_review("copilot-pull-request-reviewer", state="APPROVED",
                           body="No issues found.")]
        self.assertEqual([], github_api.pr_outstanding_findings(reviews, []))


if __name__ == "__main__":
    unittest.main()
