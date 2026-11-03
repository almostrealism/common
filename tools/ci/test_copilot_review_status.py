"""Tests for copilot-review-status.py — the CI-side Copilot quota detector.

Runs the script as a subprocess (its real contract: reviews JSON on stdin,
``copilot_available=...`` on stdout), the way the auto-review job invokes it.
"""
import json
import os
import subprocess
import sys
import unittest

_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(_CI_DIR, "copilot-review-status.py")

_QUOTA_BODY = (
    "Copilot was unable to review this pull request because the user who "
    "requested the review has insufficient remaining quota."
)


def _run(reviews_json):
    proc = subprocess.run(
        [sys.executable, _SCRIPT],
        input=reviews_json,
        capture_output=True,
        text=True,
        timeout=60,
    )
    return proc.stdout.strip(), proc.returncode


class TestCopilotReviewStatus(unittest.TestCase):
    def test_quota_notice_latest_reports_unavailable(self):
        reviews = [
            {"user": {"login": "alice"}, "submitted_at": "2026-10-26T00:00:00Z",
             "body": "LGTM"},
            {"user": {"login": "copilot-pull-request-reviewer[bot]"},
             "submitted_at": "2026-10-26T02:29:00Z", "body": _QUOTA_BODY},
        ]
        out, code = _run(json.dumps(reviews))
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=false")

    def test_real_latest_review_reports_available(self):
        reviews = [
            {"user": {"login": "copilot-pull-request-reviewer[bot]"},
             "submitted_at": "2026-10-20T00:00:00Z", "body": _QUOTA_BODY},
            {"user": {"login": "copilot-pull-request-reviewer[bot]"},
             "submitted_at": "2026-10-26T00:00:00Z", "body": "Changes recommended."},
        ]
        out, code = _run(json.dumps(reviews))
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_no_reviews_reports_available(self):
        out, code = _run("[]")
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_empty_stdin_reports_available(self):
        out, code = _run("")
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_malformed_json_reports_available(self):
        out, code = _run("not json at all")
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_error_object_not_a_list_reports_available(self):
        out, code = _run(json.dumps({"message": "Not Found"}))
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_quota_notice_on_a_later_page_reports_unavailable(self):
        # gh api --paginate (no --slurp) writes one array per page, back to back.
        first_page = [{"user": {"login": "copilot-pull-request-reviewer[bot]"},
                       "submitted_at": "2026-10-20T00:00:00Z", "body": "Looks fine."}]
        second_page = [{"user": {"login": "copilot-pull-request-reviewer[bot]"},
                        "submitted_at": "2026-10-26T00:00:00Z", "body": _QUOTA_BODY}]
        out, code = _run(json.dumps(first_page) + "\n" + json.dumps(second_page))
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=false")

    def test_real_review_on_a_later_page_reports_available(self):
        first_page = [{"user": {"login": "copilot-pull-request-reviewer[bot]"},
                       "submitted_at": "2026-10-20T00:00:00Z", "body": _QUOTA_BODY}]
        second_page = [{"user": {"login": "copilot-pull-request-reviewer[bot]"},
                        "submitted_at": "2026-10-26T00:00:00Z", "body": "Changes recommended."}]
        out, code = _run(json.dumps(first_page) + json.dumps(second_page))
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")

    def test_truncated_later_page_reports_available(self):
        page = [{"user": {"login": "copilot-pull-request-reviewer[bot]"},
                 "submitted_at": "2026-10-26T00:00:00Z", "body": _QUOTA_BODY}]
        out, code = _run(json.dumps(page) + '[{"user": ')
        self.assertEqual(code, 0)
        self.assertEqual(out, "copilot_available=true")


if __name__ == "__main__":
    unittest.main()
