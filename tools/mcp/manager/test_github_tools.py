"""Tests for the GitHub tools.

Split from ``test_server.py``, which had grown past the file-length cap. The
tests are unchanged; shared fixtures live in ``manager_test_support``.
"""

import json
import os
import subprocess
import sys
import unittest
from unittest import mock
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from inference import Synthesis  # noqa: E402
from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _grant_scopes, _clear_scopes,
    _set_workspaces, _clear_workspaces, _reset_workspace_cache,
)


class TestGithubPrFindState(unittest.TestCase):
    """PR lookup across states.

    The default finding only open pull requests is what made a finished
    workstream look as though it never had one: the PR was merged, and merged
    is invisible to state=open.
    """

    def setUp(self):
        _grant_all_scopes()

    def _pr(self, number=7, state="closed", merged_at=None):
        return {"number": number, "title": "T", "html_url": "u",
                "state": state, "merged_at": merged_at}

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_default_state_is_open(self, mock_request, _):
        mock_request.return_value = [self._pr(state="open")]
        result = server.github_pr_find(branch="feature/x")
        self.assertIn("state=open", mock_request.call_args[0][1])
        self.assertEqual(result["searched_state"], "open")
        self.assertFalse(result["merged"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_merged_filters_closed_to_actually_merged(self, mock_request, _):
        # GitHub has no state=merged; it returns closed PRs and the merged
        # ones are those carrying merged_at.
        mock_request.return_value = [
            self._pr(number=1, merged_at=None),
            self._pr(number=2, merged_at="2026-08-01T00:00:00Z"),
        ]
        result = server.github_pr_find(branch="feature/x", state="merged")
        self.assertIn("state=closed", mock_request.call_args[0][1])
        self.assertEqual(result["number"], 2)
        self.assertTrue(result["merged"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_merged_reports_not_found_when_only_abandoned(
            self, mock_request, _):
        mock_request.return_value = [self._pr(number=1, merged_at=None)]
        result = server.github_pr_find(branch="feature/x", state="merged")
        self.assertTrue(result["ok"])
        self.assertFalse(result["found"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_all_searches_every_state(self, mock_request, _):
        mock_request.return_value = [self._pr(merged_at="2026-08-01T00:00:00Z")]
        result = server.github_pr_find(branch="feature/x", state="all")
        self.assertIn("state=all", mock_request.call_args[0][1])
        self.assertTrue(result["merged"])
        self.assertEqual(result["merged_at"], "2026-08-01T00:00:00Z")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_invalid_state_is_rejected(self, mock_request, _):
        result = server.github_pr_find(branch="feature/x", state="sideways")
        self.assertFalse(result["ok"])
        self.assertIn("sideways", result["error"])
        mock_request.assert_not_called()

    @patch.object(server, "_resolve_github_repo",
                  return_value=("org", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_only_merged_pages_deeply(self, mock_request, _):
        # The other states take the first result, so a one-item page suffices;
        # merged filters locally and would miss a merge behind newer closures.
        mock_request.return_value = []
        server.github_pr_find(branch="feature/x", state="all")
        self.assertIn("per_page=1", mock_request.call_args[0][1])
        server.github_pr_find(branch="feature/x", state="merged")
        self.assertIn("per_page=100", mock_request.call_args[0][1])

class TestGithubReadFile(unittest.TestCase):
    """Tests for github_read_file."""

    def setUp(self):
        _grant_all_scopes()

    def _make_contents_response(self, path, content_text, size=None):
        """Build a mock GitHub Contents API response for a text file."""
        import base64 as _b64
        encoded = _b64.b64encode(content_text.encode("utf-8")).decode("ascii")
        return {
            "path": path,
            "sha": "abc123",
            "size": size if size is not None else len(content_text.encode("utf-8")),
            "content": encoded,
            "encoding": "base64",
        }

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_success(self, mock_gh, mock_repo):
        mock_gh.return_value = self._make_contents_response(
            "docs/README.md", "# Hello World\n"
        )
        result = server.github_read_file(path="docs/README.md")
        self.assertTrue(result["ok"])
        self.assertEqual(result["content"], "# Hello World\n")
        self.assertEqual(result["path"], "docs/README.md")
        self.assertEqual(result["repo"], "owner/repo")
        self.assertIn("sha", result)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_file_not_found(self, mock_gh, mock_repo):
        mock_gh.return_value = {"ok": False, "error": "GitHub returned HTTP 404: Not Found"}
        result = server.github_read_file(path="missing/file.py")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_binary_file_rejected(self, mock_gh, mock_repo):
        # Craft a response whose base64 decodes to non-UTF-8 bytes.
        import base64 as _b64
        raw_binary = bytes([0x00, 0xFF, 0xFE, 0x80, 0x81])
        encoded = _b64.b64encode(raw_binary).decode("ascii")
        mock_gh.return_value = {
            "path": "image.png",
            "sha": "abc",
            "size": len(raw_binary),
            "content": encoded,
            "encoding": "base64",
        }
        result = server.github_read_file(path="image.png")
        self.assertFalse(result["ok"])
        self.assertIn("binary", result["error"].lower())

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_oversized_file_rejected(self, mock_gh, mock_repo):
        # Size field exceeds 1 MB limit — content should never be decoded.
        import base64 as _b64
        small_content = "x"
        encoded = _b64.b64encode(small_content.encode()).decode("ascii")
        mock_gh.return_value = {
            "path": "big.bin",
            "sha": "abc",
            "size": 1_100_000,  # > 1 MB
            "content": encoded,
            "encoding": "base64",
        }
        result = server.github_read_file(path="big.bin")
        self.assertFalse(result["ok"])
        self.assertIn("1 MB", result["error"])
        self.assertEqual(result["size"], 1_100_000)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_ref_used_in_request(self, mock_gh, mock_repo):
        mock_gh.return_value = self._make_contents_response(
            "src/main.py", "print('hello')\n"
        )
        server.github_read_file(path="src/main.py", ref="v1.2.3")
        call_args = mock_gh.call_args[0]
        self.assertIn("v1.2.3", call_args[1])

    @patch.object(server, "_github_request")
    def test_explicit_repo_url(self, mock_gh):
        mock_gh.return_value = self._make_contents_response(
            "README.md", "content\n"
        )
        result = server.github_read_file(
            path="README.md",
            repo_url="https://github.com/myorg/myrepo",
            branch="develop",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["repo"], "myorg/myrepo")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("develop", call_path)

    @patch.object(server, "_github_request")
    def test_invalid_repo_url_returns_error(self, mock_gh):
        result = server.github_read_file(
            path="README.md",
            repo_url="not-a-github-url",
        )
        self.assertFalse(result["ok"])
        self.assertIn("Cannot parse", result["error"])
        mock_gh.assert_not_called()

    def test_missing_path_returns_error(self):
        result = server.github_read_file(path="")
        self.assertFalse(result["ok"])
        self.assertIn("path is required", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_read_file(path="README.md")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "not found"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_read_file(path="README.md", workstream_id="bad")
        self.assertFalse(result["ok"])

class TestGithubPrCheckStatus(unittest.TestCase):
    """Tests for github_pr_check_status."""

    def setUp(self):
        _grant_all_scopes()

    def _make_pr(self, number, sha="abc123", ref="feature/x"):
        return {
            "number": number,
            "head": {"sha": sha, "ref": ref},
            "html_url": f"https://github.com/owner/repo/pull/{number}",
        }

    def _make_runs(self, head_sha, runs_data):
        """Build a mock workflow runs API response."""
        return {"workflow_runs": [
            {
                "id": r.get("id", 1),
                "name": r.get("name", "CI"),
                "status": r.get("status", "completed"),
                "conclusion": r.get("conclusion", "success"),
                "head_sha": r.get("head_sha", head_sha),
                "created_at": "2026-04-24T00:00:00Z",
                "updated_at": "2026-04-24T00:01:00Z",
                "html_url": "https://github.com/owner/repo/actions/runs/1",
            }
            for r in runs_data
        ]}

    def _make_checks(self, checks_data):
        return {"check_runs": [
            {
                "id": c.get("id", 1),
                "name": c.get("name", "test"),
                "status": c.get("status", "completed"),
                "conclusion": c.get("conclusion", "success"),
                "html_url": "https://github.com/owner/repo/runs/1",
                "started_at": "2026-04-24T00:00:00Z",
                "completed_at": "2026-04-24T00:01:00Z",
                "details_url": c.get("details_url", ""),
            }
            for c in checks_data
        ]}

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_all_success(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": "success"}]),
            self._make_checks([{"conclusion": "success"}, {"conclusion": "skipped"}]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "success")
        self.assertTrue(result["pipeline_current"])
        self.assertEqual(result["pr_number"], 42)
        self.assertEqual(result["head_sha"], "abc123")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_partial_failure(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": "failure"}]),
            self._make_checks([
                {"name": "build", "conclusion": "success"},
                {"name": "test", "conclusion": "failure", "details_url": "https://logs/123"},
            ]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "failure")
        failed_checks = [c for c in result["check_runs"] if c["conclusion"] == "failure"]
        self.assertEqual(len(failed_checks), 1)
        self.assertIn("details_url", failed_checks[0])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_no_workflow_runs(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            {"workflow_runs": []},
            {"check_runs": []},
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "no_runs")
        self.assertFalse(result["pipeline_current"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_stale_workflow_run(self, mock_gh, mock_repo):
        # Workflow run exists but targets an older commit SHA
        mock_gh.side_effect = [
            self._make_pr(42, sha="new_sha"),
            self._make_runs("new_sha", [{"head_sha": "old_sha", "conclusion": "success"}]),
            {"check_runs": []},
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "stale")
        self.assertFalse(result["pipeline_current"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_lookup_pr_by_branch(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            [self._make_pr(7, sha="sha7")],  # PR list
            self._make_runs("sha7", [{"head_sha": "sha7"}]),
            {"check_runs": []},
        ]
        result = server.github_pr_check_status()
        self.assertTrue(result["ok"])
        self.assertEqual(result["pr_number"], 7)
        self.assertEqual(result["head_sha"], "sha7")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request", return_value=[])
    def test_no_open_pr_for_branch(self, mock_gh, mock_repo):
        result = server.github_pr_check_status()
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "", None))
    def test_no_branch_and_no_pr_number(self, mock_repo):
        result = server.github_pr_check_status()
        self.assertFalse(result["ok"])
        self.assertIn("pr_number or branch", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "bad repo"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_pr_check_status(pr_number=1)
        self.assertFalse(result["ok"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_pr_check_status(pr_number=1)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_pending_checks(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": None,
                                        "status": "in_progress"}]),
            self._make_checks([{"status": "in_progress", "conclusion": None}]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "pending")

class TestGithubWorkflowRuns(unittest.TestCase):
    """Tests for the arbitrary workflow-run search and status tools."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "master", None))
    @patch.object(server.github_api, "_github_request")
    def test_list_runs_shapes_and_applies_filters(self, mock_gh, mock_repo):
        mock_gh.return_value = {
            "total_count": 7,
            "workflow_runs": [
                {"id": 101, "name": "Build and Test", "event": "push",
                 "status": "completed", "conclusion": "failure",
                 "head_branch": "master", "head_sha": "deadbeef",
                 "run_number": 12, "run_attempt": 2,
                 "html_url": "https://github.com/owner/repo/actions/runs/101"},
            ],
        }
        result = server.github_list_workflow_runs(
            branch="master", status="failure", event="push", limit=5)
        self.assertTrue(result["ok"])
        self.assertEqual(result["total_count"], 7)
        self.assertEqual(result["returned"], 1)
        run = result["workflow_runs"][0]
        self.assertEqual(run["run_id"], 101)
        self.assertEqual(run["conclusion"], "failure")
        self.assertEqual(run["run_attempt"], 2)
        # Filters and the repo-wide endpoint must reach the API path.
        path = mock_gh.call_args[0][1]
        self.assertIn("/repos/owner/repo/actions/runs?", path)
        self.assertIn("branch=master", path)
        self.assertIn("status=failure", path)
        self.assertIn("event=push", path)
        self.assertIn("per_page=5", path)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "master", None))
    @patch.object(server.github_api, "_github_request")
    def test_list_runs_workflow_scoped_endpoint(self, mock_gh, mock_repo):
        mock_gh.return_value = {"total_count": 0, "workflow_runs": []}
        result = server.github_list_workflow_runs(workflow="analysis.yaml")
        self.assertTrue(result["ok"])
        self.assertEqual(result["returned"], 0)
        path = mock_gh.call_args[0][1]
        self.assertIn(
            "/repos/owner/repo/actions/workflows/analysis.yaml/runs?", path)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "master", None))
    @patch.object(server.github_api, "_github_request")
    def test_run_status_reports_jobs_and_failed_steps(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            {"id": 101, "name": "Build and Test", "status": "completed",
             "conclusion": "failure", "head_branch": "master",
             "run_started_at": "2026-07-18T00:00:00Z",
             "html_url": "https://github.com/owner/repo/actions/runs/101"},
            {"jobs": [
                {"id": 1, "name": "build", "status": "completed",
                 "conclusion": "success", "steps": []},
                {"id": 2, "name": "test (0)", "status": "completed",
                 "conclusion": "failure", "steps": [
                     {"name": "Checkout", "number": 1, "conclusion": "success"},
                     {"name": "Run Tests", "number": 2, "conclusion": "failure"},
                 ]},
            ]},
        ]
        result = server.github_workflow_run_status(run_id=101)
        self.assertTrue(result["ok"])
        self.assertEqual(result["summary"]["total_jobs"], 2)
        self.assertEqual(result["summary"]["failed_jobs"], 1)
        self.assertEqual(result["run"]["run_started_at"], "2026-07-18T00:00:00Z")
        failed = [j for j in result["jobs"] if j["conclusion"] == "failure"]
        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0]["failed_steps"][0]["name"], "Run Tests")

    def test_run_status_requires_run_id(self):
        result = server.github_workflow_run_status(run_id=0)
        self.assertFalse(result["ok"])
        self.assertIn("run_id", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "master", None))
    @patch.object(server.github_api, "_github_request")
    def test_list_runs_propagates_api_error(self, mock_gh, mock_repo):
        mock_gh.return_value = {"ok": False, "error": "GitHub returned HTTP 404"}
        result = server.github_list_workflow_runs()
        self.assertFalse(result["ok"])

class TestGithubPrReviewComments(unittest.TestCase):
    """Tests for github_pr_review_comments (GraphQL-based, paginated)."""

    def setUp(self):
        _grant_all_scopes()

    def _make_graphql_response(self, threads, has_next=False, cursor="abc"):
        """Build a mock GraphQL response for reviewThreads."""
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "reviewThreads": {
                            "pageInfo": {
                                "hasNextPage": has_next,
                                "endCursor": cursor,
                            },
                            "nodes": threads,
                        }
                    }
                }
            }
        }

    def _make_thread(self, resolved, comments):
        """Build a reviewThread node."""
        return {
            "isResolved": resolved,
            "comments": {
                "nodes": [
                    {
                        "databaseId": c.get("id", 1),
                        "path": c.get("path", "file.py"),
                        "line": c.get("line", 10),
                        "originalLine": c.get("originalLine"),
                        "body": c.get("body", "fix this"),
                        "author": {"login": c.get("user", "reviewer")},
                        "createdAt": c.get("createdAt", "2026-01-01T00:00:00Z"),
                    }
                    for c in comments
                ]
            },
        }

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_returns_unresolved_comments(self, mock_gql, mock_repo):
        unresolved = self._make_thread(False, [
            {"id": 101, "body": "please fix", "user": "alice",
             "createdAt": "2026-03-01T10:00:00Z"},
        ])
        resolved = self._make_thread(True, [
            {"id": 102, "body": "looks good", "user": "bob",
             "createdAt": "2026-03-02T10:00:00Z"},
        ])
        mock_gql.return_value = self._make_graphql_response(
            [unresolved, resolved], has_next=False)

        result = server.github_pr_review_comments(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertEqual(result["comments"][0]["id"], 101)
        self.assertEqual(result["comments"][0]["body"], "please fix")
        self.assertEqual(result["comments"][0]["user"], "alice")
        self.assertIsNone(result["comments"][0]["in_reply_to_id"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_paginates_through_multiple_pages(self, mock_gql, mock_repo):
        page1_thread = self._make_thread(False, [
            {"id": 1, "body": "page1", "createdAt": "2026-01-01T00:00:00Z"},
        ])
        page2_thread = self._make_thread(False, [
            {"id": 2, "body": "page2", "createdAt": "2026-02-01T00:00:00Z"},
        ])
        mock_gql.side_effect = [
            self._make_graphql_response([page1_thread], has_next=True, cursor="c1"),
            self._make_graphql_response([page2_thread], has_next=False),
        ]

        result = server.github_pr_review_comments(pr_number=10)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)
        self.assertEqual(result["comments"][0]["id"], 2)
        self.assertEqual(result["comments"][1]["id"], 1)
        self.assertEqual(mock_gql.call_count, 2)
        first_vars = mock_gql.call_args_list[0][0][1]
        self.assertIsNone(first_vars["cursor"])
        second_vars = mock_gql.call_args_list[1][0][1]
        self.assertEqual(second_vars["cursor"], "c1")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_empty_when_all_resolved(self, mock_gql, mock_repo):
        resolved = self._make_thread(True, [{"id": 1, "body": "done"}])
        mock_gql.return_value = self._make_graphql_response(
            [resolved], has_next=False)

        result = server.github_pr_review_comments(pr_number=5)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 0)
        self.assertEqual(result["comments"], [])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_caps_at_50_comments(self, mock_gql, mock_repo):
        comments = [
            {"id": i, "body": f"comment {i}",
             "createdAt": f"2026-01-{i+1:02d}T00:00:00Z"}
            for i in range(60)
        ]
        thread = self._make_thread(False, comments)
        mock_gql.return_value = self._make_graphql_response(
            [thread], has_next=False)

        result = server.github_pr_review_comments(pr_number=1)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 50)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_graphql_error_returns_error(self, mock_gql, mock_repo):
        mock_gql.return_value = {
            "errors": [{"message": "Field 'foo' doesn't exist"}]
        }

        result = server.github_pr_review_comments(pr_number=99)
        self.assertFalse(result["ok"])
        self.assertIn("foo", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "no repo"}))
    def test_repo_resolution_error(self, mock_repo):
        result = server.github_pr_review_comments(pr_number=1)
        self.assertFalse(result["ok"])
        self.assertIn("no repo", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_pr_review_comments(pr_number=1)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_uses_line_fallback_to_originalLine(self, mock_gql, mock_repo):
        thread = self._make_thread(False, [
            {"id": 1, "line": None, "originalLine": 42, "body": "outdated"},
        ])
        mock_gql.return_value = self._make_graphql_response(
            [thread], has_next=False)

        result = server.github_pr_review_comments(pr_number=1)
        self.assertEqual(result["comments"][0]["line"], 42)

class TestGithubRequestCopilotReview(unittest.TestCase):
    """Tests for github_request_copilot_review and the _request_copilot_review helper."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "branch", None))
    @patch.object(server, "_request_copilot_review",
                  return_value={"ok": True})
    def test_requests_review_with_explicit_pr_number(self, mock_review, mock_repo):
        result = server.github_request_copilot_review(pr_number=42)
        self.assertTrue(result["ok"])
        mock_review.assert_called_once_with("owner", "repo", 42)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "my-branch", None))
    @patch.object(server, "_github_request")
    @patch.object(server, "_request_copilot_review",
                  return_value={"ok": True})
    def test_looks_up_pr_when_number_omitted(self, mock_review, mock_gh, mock_repo):
        mock_gh.return_value = [{"number": 99}]
        result = server.github_request_copilot_review()
        self.assertTrue(result["ok"])
        mock_review.assert_called_once_with("owner", "repo", 99)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "my-branch", None))
    @patch.object(server, "_github_request", return_value=[])
    def test_returns_error_when_no_open_pr(self, mock_gh, mock_repo):
        result = server.github_request_copilot_review()
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "bad repo"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_request_copilot_review(pr_number=1)
        self.assertFalse(result["ok"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_request_copilot_review(pr_number=1)

    # ------------------------------------------------------------------
    # Helpers for building GraphQL response payloads used by these tests.
    # ------------------------------------------------------------------

    @staticmethod
    def _lookup_response(pr_id="PR_kg1", bot_id="BOT_kg1",
                          bot_login="copilot-pull-request-reviewer",
                          include_copilot=True):
        """Build a CopilotLookup GraphQL response."""
        actors = []
        if include_copilot:
            actors.append({"__typename": "Bot", "login": bot_login, "id": bot_id})
        actors.append({"__typename": "User", "login": "alice", "id": "U_kg1"})
        return {"data": {"repository": {
            "pullRequest": {"id": pr_id} if pr_id else None,
            "suggestedActors": {"nodes": actors},
        }}}

    @staticmethod
    def _rest_response(reviewer_logins):
        """Build a realistic REST requested_reviewers PR response."""
        reviewers = [{"login": lg, "type": "Bot", "id": 12345}
                     for lg in reviewer_logins]
        return {"id": 1234567, "number": 10, "state": "open",
                "requested_reviewers": reviewers, "requested_teams": []}

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_success(self, mock_gql, mock_rest):
        # The implementation uses REST with the bot's login string, not
        # the GraphQL requestReviews mutation (which fails for Bot node IDs).
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1",
            bot_login="copilot-pull-request-reviewer")
        mock_rest.return_value = self._rest_response(["copilot-pull-request-reviewer"])
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        # Only one GraphQL call (the lookup) — no GraphQL mutation.
        self.assertEqual(mock_gql.call_count, 1)
        lookup_call_args = mock_gql.call_args_list[0][0]
        self.assertIn("CopilotLookup", lookup_call_args[0])
        self.assertEqual(lookup_call_args[1],
                         {"owner": "owner", "name": "repo", "number": 10})
        # REST called with the bot's login string, not its Bot node ID.
        mock_rest.assert_called_once_with(
            "owner", "repo", 10, "copilot-pull-request-reviewer"
        )

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_no_op_without_bot_is_failure(
            self, mock_gql, mock_rest):
        # Regression guard: the REST call returns 2xx but the bot is absent
        # from requested_reviewers (e.g. Copilot toggle disabled after the
        # lookup gate). Must NOT claim success.
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = self._rest_response([])
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("not added", result["error"].lower())
        self.assertIn("Copilot", result["error"])

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_recognises_all_observed_logins(
            self, mock_gql, mock_rest):
        # GitHub exposes Copilot under different login strings on different
        # endpoints (verified on live PRs against github.com):
        #   - ``copilot-pull-request-reviewer``        (suggestedActors Bot.login)
        #   - ``Copilot``                              (GET /pulls/N/comments user.login)
        #   - ``copilot-pull-request-reviewer[bot]``   (GET /pulls/N/reviews user.login)
        # The REST response's requested_reviewers must match all three.
        for login in ("copilot-pull-request-reviewer", "Copilot",
                      "copilot-pull-request-reviewer[bot]"):
            mock_gql.reset_mock()
            mock_rest.reset_mock()
            mock_gql.return_value = self._lookup_response(
                pr_id="PR_kg1", bot_id="BOT_kg1")
            mock_rest.return_value = self._rest_response([login])
            result = server._request_copilot_review("owner", "repo", 10)
            self.assertTrue(result["ok"],
                            f"Expected ok=True for login={login!r}, got {result}")

    def test_is_copilot_login_rejects_unrelated_users(self):
        # Sanity-check the helper directly: only logins whose lowercase form
        # contains "copilot" count. Non-Copilot users must not be recognised.
        self.assertFalse(server._is_copilot_login("ashesfall"))
        self.assertFalse(server._is_copilot_login("dependabot[bot]"))
        self.assertFalse(server._is_copilot_login(""))
        self.assertFalse(server._is_copilot_login(None))  # type: ignore[arg-type]
        self.assertTrue(server._is_copilot_login("copilot"))
        self.assertTrue(server._is_copilot_login("Copilot"))
        self.assertTrue(server._is_copilot_login("copilot-pull-request-reviewer"))
        self.assertTrue(server._is_copilot_login("copilot-pull-request-reviewer[bot]"))

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_pr_not_found(self, mock_gql):
        """When the lookup query returns no PR, surfaces a clear error."""
        mock_gql.return_value = self._lookup_response(pr_id=None)
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("PR #10", result["error"])

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_copilot_disabled(self, mock_gql):
        """When Copilot is absent from suggestedActors, returns a clear error
        instructing the user to enable Copilot code review for the repo."""
        mock_gql.return_value = self._lookup_response(include_copilot=False)
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Copilot is not available", result["error"])

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_lookup_transport_error(self, mock_gql):
        """When the lookup transport fails, the error propagates unchanged."""
        mock_gql.return_value = {"ok": False, "error": "controller proxy unreachable"}
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("controller proxy unreachable", result["error"])

    @patch.object(server, "_dismiss_copilot_review", return_value={"ok": True})
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_retries_after_dismiss(
            self, mock_gql, mock_rest, mock_dismiss):
        """When REST returns 'cannot be requested', dismisses the prior review
        and retries. The retry succeeds and the helper returns ok=True."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        # First REST call: GitHub 422 "already reviewed"
        mock_rest.side_effect = [
            {"ok": False,
             "error": "GitHub returned HTTP 422: Review cannot be requested at this time"},
            self._rest_response(["copilot-pull-request-reviewer"]),
        ]
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_dismiss.assert_called_once_with("owner", "repo", 10)
        self.assertEqual(mock_rest.call_count, 2)

    @patch.object(server, "_dismiss_copilot_review",
                  return_value={"ok": False, "error": "No dismissible reviews"})
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_error_when_dismiss_fails(
            self, mock_gql, mock_rest, mock_dismiss):
        """When REST fails with 'cannot be requested' and dismiss also fails,
        returns ok=False propagating the original error."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = {
            "ok": False,
            "error": "GitHub returned HTTP 422: Review cannot be requested at this time",
        }
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Review request failed", result["error"])
        self.assertIn("Review cannot be requested", result["error"])

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_rest_transport_error(
            self, mock_gql, mock_rest):
        """Surfaces a REST transport error that is not the 'already reviewed'
        shape without attempting a dismiss/retry."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = {
            "ok": False,
            "error": "GitHub returned HTTP 403: Resource not accessible by token",
        }
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Resource not accessible by token", result["error"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_success(self, mock_gh):
        """Dismisses the most recent CHANGES_REQUESTED Copilot review."""
        mock_gh.side_effect = [
            [
                {
                    "id": 42,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "CHANGES_REQUESTED",
                    "submitted_at": "2026-04-14T10:00:00Z",
                }
            ],
            {"id": 42, "state": "DISMISSED"},
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_gh.assert_any_call("GET", "/repos/owner/repo/pulls/10/reviews")
        mock_gh.assert_any_call(
            "PUT",
            "/repos/owner/repo/pulls/10/reviews/42/dismissals",
            {"message": "Dismissing prior Copilot review to allow re-review"},
        )

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_no_dismissible_reviews(self, mock_gh):
        """Returns ok=False when no dismissible Copilot reviews exist."""
        mock_gh.return_value = [
            {
                "id": 1,
                "user": {"login": "copilot-pull-request-reviewer[bot]"},
                "state": "COMMENTED",
                "submitted_at": "2026-04-14T09:00:00Z",
            }
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("No dismissible", result["error"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_list_error(self, mock_gh):
        """Returns ok=False when the reviews list request fails."""
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_picks_most_recent(self, mock_gh):
        """Dismisses the most recently submitted review when multiple exist."""
        mock_gh.side_effect = [
            [
                {
                    "id": 1,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "APPROVED",
                    "submitted_at": "2026-04-13T08:00:00Z",
                },
                {
                    "id": 2,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "CHANGES_REQUESTED",
                    "submitted_at": "2026-04-14T10:00:00Z",
                },
            ],
            {"id": 2, "state": "DISMISSED"},
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_gh.assert_any_call(
            "PUT",
            "/repos/owner/repo/pulls/10/reviews/2/dismissals",
            {"message": "Dismissing prior Copilot review to allow re-review"},
        )

class TestNoDirectGithubPath(unittest.TestCase):

    def test_server_module_has_no_github_token_symbol(self):
        # The legacy GITHUB_TOKEN / AR_MANAGER_GITHUB_TOKEN plumbing is gone;
        # nothing in the module should reference a local token anymore.
        self.assertFalse(hasattr(server, "GITHUB_TOKEN"))

    def test_github_api_module_has_no_direct_request(self):
        import github_api
        self.assertFalse(hasattr(github_api, "_github_direct_request"))
        self.assertFalse(hasattr(github_api, "_github_token"))

class TestGithubToolsDirectAddressing(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_with_explicit_org_repo_bypasses_workstream(
            self, mock_get, mock_gh):
        # The workstream list is empty; without direct addressing this would
        # fall through to the error path. With org+repo it should succeed.
        mock_get.return_value = []
        mock_gh.return_value = [
            {"number": 1, "title": "t", "html_url": "u", "state": "open"},
        ]
        result = server.github_pr_find(
            branch="feature/x", org="almostrealism", repo="common")
        self.assertTrue(result["ok"])
        self.assertTrue(result["found"])
        # The proxy call path should include almostrealism/common
        called_path = mock_gh.call_args.args[1]
        self.assertIn("/repos/almostrealism/common/pulls", called_path)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_rejects_half_addressing(self, mock_get, mock_gh):
        # Supplying only org (no repo) is ambiguous; the resolver must error
        # before any HTTP is attempted.
        mock_get.return_value = []
        result = server.github_pr_find(org="almostrealism")
        self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_scoped_token_rejects_out_of_scope_org(
            self, mock_get, mock_gh):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.github_pr_find(org="Plytrix", repo="plytrix-platform",
                                  branch="feature/x")
        mock_gh.assert_not_called()
