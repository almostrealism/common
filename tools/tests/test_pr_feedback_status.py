"""Tests for ``tools/ci/pr-feedback-status.py``.

The script turns a PR's reviews and review threads into the single line a CI
step reads: ``feedback_outstanding=true|false|unknown``. ``false`` is the only
result that lets an own-commit pass be skipped, so the script must report
``unknown`` — never ``false`` — whenever the data it was given is incomplete or
invalid. These tests pin that the explicit false, the found-finding true, and
each incomplete/invalid case (malformed reviews, malformed threads, a truncated
thread page, a truncated comment page) land on the right status.
"""

import importlib.util
import io
import json
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "ci" / "pr-feedback-status.py"


def _load():
    spec = importlib.util.spec_from_file_location("pr_feedback_status", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


STATUS = _load()


def _review(login="copilot-pull-request-reviewer", state="CHANGES_REQUESTED",
            body="Please address the below."):
    return {"user": {"login": login}, "state": state, "body": body,
            "submitted_at": "2026-11-07T06:50:00Z", "html_url": "https://gh/r"}


def _threads_document(nodes, threads_next=False):
    return json.dumps({"data": {"repository": {"pullRequest": {
        "reviewThreads": {"pageInfo": {"hasNextPage": threads_next},
                          "nodes": nodes}}}}})


def _thread_node(resolved=False, comments_next=False, comments=None):
    return {"isResolved": resolved,
            "comments": {"pageInfo": {"hasNextPage": comments_next},
                         "nodes": comments or []}}


class ReviewsWellFormedTest(unittest.TestCase):
    """``reviews_well_formed`` distinguishes valid-but-empty from malformed."""

    def test_empty_is_well_formed(self):
        self.assertTrue(STATUS.reviews_well_formed(""))

    def test_empty_array_is_well_formed(self):
        self.assertTrue(STATUS.reviews_well_formed("[]"))

    def test_concatenated_pages_are_well_formed(self):
        self.assertTrue(STATUS.reviews_well_formed('[{"id":1}]\n[{"id":2}]'))

    def test_truncated_json_is_malformed(self):
        self.assertFalse(STATUS.reviews_well_formed('[{"id":1}'))

    def test_error_object_is_malformed(self):
        # gh can exit 0 with a JSON error object rather than an array.
        self.assertFalse(STATUS.reviews_well_formed('{"message":"Not Found"}'))


class NormalizeThreadsTest(unittest.TestCase):
    """``normalize_threads`` reports truncation and malformed documents."""

    def test_clean_document_is_ok(self):
        raw = _threads_document([_thread_node()])
        threads, ok = STATUS.normalize_threads(raw)
        self.assertTrue(ok)
        self.assertEqual(1, len(threads))

    def test_malformed_document_is_not_ok(self):
        threads, ok = STATUS.normalize_threads("{not json")
        self.assertFalse(ok)
        self.assertEqual([], threads)

    def test_truncated_thread_page_is_not_ok(self):
        raw = _threads_document([_thread_node()], threads_next=True)
        _threads, ok = STATUS.normalize_threads(raw)
        self.assertFalse(ok)

    def test_truncated_comment_page_is_not_ok(self):
        raw = _threads_document([_thread_node(comments_next=True)])
        _threads, ok = STATUS.normalize_threads(raw)
        self.assertFalse(ok)


class MainStatusTest(unittest.TestCase):
    """End-to-end status line for each kind of input."""

    def _run(self, reviews_raw, threads_raw, tmp):
        reviews_file = tmp / "reviews.json"
        threads_file = tmp / "threads.json"
        reviews_file.write_text(reviews_raw, encoding="utf-8")
        threads_file.write_text(threads_raw, encoding="utf-8")
        import sys
        argv = sys.argv
        sys.argv = ["pr-feedback-status.py", str(reviews_file), str(threads_file)]
        buffer = io.StringIO()
        try:
            with redirect_stdout(buffer):
                STATUS.main()
        finally:
            sys.argv = argv
        return buffer.getvalue().strip()

    def setUp(self):
        import tempfile
        self._dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self._dir.name)

    def tearDown(self):
        self._dir.cleanup()

    def test_no_feedback_is_false(self):
        out = self._run("[]", _threads_document([]), self.tmp)
        self.assertEqual("feedback_outstanding=false", out)

    def test_a_finding_is_true(self):
        out = self._run(json.dumps([_review()]), _threads_document([]), self.tmp)
        self.assertEqual("feedback_outstanding=true", out)

    def test_malformed_reviews_without_a_finding_is_unknown(self):
        out = self._run('{"message":"Not Found"}', _threads_document([]), self.tmp)
        self.assertEqual("feedback_outstanding=unknown", out)

    def test_malformed_threads_without_a_finding_is_unknown(self):
        out = self._run("[]", "{not json", self.tmp)
        self.assertEqual("feedback_outstanding=unknown", out)

    def test_truncated_threads_without_a_finding_is_unknown(self):
        out = self._run("[]", _threads_document([], threads_next=True), self.tmp)
        self.assertEqual("feedback_outstanding=unknown", out)

    def test_a_finding_overrides_incomplete_data(self):
        # When a finding is already visible the answer is definitely "true"
        # regardless of truncation elsewhere.
        out = self._run(json.dumps([_review()]),
                        _threads_document([], threads_next=True), self.tmp)
        self.assertEqual("feedback_outstanding=true", out)


if __name__ == "__main__":
    unittest.main()
