"""Tests for the project and planning-document tools.

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


class TestProjectCreateBranch(unittest.TestCase):

    @patch.object(server, "_github_request")
    def test_dispatch_default_repo(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"ok": True, "status": 204}
        result = server.project_create_branch(plan_title="my-feature")
        mock_gh.assert_called_once()
        call_path = mock_gh.call_args[0][1]
        self.assertIn("almostrealism/common", call_path)
        self.assertIn("master-agent-dispatch.yaml", call_path)
        self.assertEqual(
            "project-manager", mock_gh.call_args[0][2]["inputs"]["agent"])
        self.assertTrue(result["ok"])
        self.assertTrue(result["triggered"])

    @patch.object(server, "_github_request")
    def test_dispatch_explicit_repo(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"status": 204}
        result = server.project_create_branch(
            repo_url="https://github.com/myorg/myrepo")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("myorg/myrepo", call_path)
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_dispatch_from_workstream(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "baseBranch": "main",
        }
        mock_gh.return_value = {"status": 204}
        result = server.project_create_branch(workstream_id="ws-test")
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "main")
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_create_branch(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    @patch.object(server, "_github_request")
    def test_workflow_failure(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"ok": False, "error": "Not Found"}
        result = server.project_create_branch()
        self.assertIn("next_steps", result)

    def test_requires_pipeline_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.project_create_branch()

class TestProjectVerifyBranch(unittest.TestCase):

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_dispatch_verify(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "baseBranch": "master",
        }
        mock_gh.return_value = {"status": 204}
        result = server.project_verify_branch(workstream_id="ws-test")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("verify-completion.yaml", call_path)
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "feature/x")
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_custom_branch(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        mock_gh.return_value = {"status": 204}
        server.project_verify_branch(
            workstream_id="ws-test", branch="feature/custom")
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "feature/custom")

    @patch.object(server, "_find_workstream")
    def test_missing_repo_url(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {"defaultBranch": "feature/x"}
        result = server.project_verify_branch(workstream_id="ws-test")
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_verify_branch(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

class TestProjectCommitPlan(unittest.TestCase):

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_commit_plan(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        mock_gh.side_effect = [
            {"sha": "abc123"},  # GET existing file
            {"content": {"sha": "new"}, "commit": {"sha": "def456"}},  # PUT
        ]
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan\nDo stuff",
            path="docs/plans/PLAN.md",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["path"], "docs/plans/PLAN.md")
        self.assertEqual(result["commit_sha"], "def456")

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_auto_generates_path(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/my-plan",
        }
        mock_gh.side_effect = [
            {},  # GET existing (not found)
            {"content": {"sha": "new"}, "commit": {"sha": "abc"}},
        ]
        result = server.project_commit_plan(
            workstream_id="ws-test", content="# Plan")
        self.assertTrue(result["ok"])
        self.assertIn("PLAN-", result["path"])
        self.assertIn("feature-my-plan", result["path"])

    @patch.object(server, "_find_workstream")
    def test_path_traversal_blocked(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan",
            path="../../../etc/passwd",
        )
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_sensitive_path_blocked(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan",
            path=".github/workflows/evil.yaml",
        )
        self.assertFalse(result["ok"])

    def test_rejects_oversized_content(self):
        _grant_all_scopes()
        result = server.project_commit_plan(
            workstream_id="ws-test", content="x" * 100_001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])

class TestProjectReadPlan(unittest.TestCase):

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_read_plan(self, mock_find, mock_read_file):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# My Plan",
            "sha": "abc123",
            "ref": "feature/x",
            "repo": "org/repo",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        self.assertTrue(result["ok"])
        self.assertEqual(result["content"], "# My Plan")
        self.assertEqual(result["path"], "docs/plans/PLAN.md")

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_delegates_to_github_read_file(self, mock_find, mock_read_file):
        """project_read_plan must resolve the planningDocument and delegate to github_read_file."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# My Plan",
            "sha": "abc123",
            "repo": "org/repo",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        mock_read_file.assert_called_once_with(
            path="docs/plans/PLAN.md",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            workstream_id="ws-test",
        )
        self.assertTrue(result["ok"])
        next_steps = result.get("next_steps", [])
        self.assertTrue(
            any("project_commit_plan" in s for s in next_steps),
            "Expected project_commit_plan in next_steps",
        )

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_delegate_uses_explicit_branch(self, mock_find, mock_read_file):
        """An explicit branch parameter is forwarded to github_read_file."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "master",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# Plan",
            "sha": "def456",
            "repo": "org/repo",
        }
        server.project_read_plan(workstream_id="ws-test", branch="feature/x")
        mock_read_file.assert_called_once_with(
            path="docs/plans/PLAN.md",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            workstream_id="ws-test",
        )

    @patch.object(server, "_find_workstream")
    def test_no_planning_document(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("planning document", result["error"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_read_plan(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.project_read_plan(workstream_id="ws-test")
