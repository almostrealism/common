"""Tests for ``github_set_pr_state`` and ``github_start_test_run``.

These cover the branch-lifecycle GitHub tools an agent uses to finish its own
work: closing/reopening a pull request, and starting a branch's CI test run.
Shared fixtures live in ``manager_test_support``; no test contacts GitHub.
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
class TestGithubSetPrState(unittest.TestCase):
    """Only the open/closed state is changed, and the change is verified."""

    def setUp(self):
        _grant_all_scopes()

    def _pr(self, **fields):
        pr = {"number": 7, "html_url": "u", "state": "open", "merged": False}
        pr.update(fields)
        return pr

    @patch.object(server, "_github_request")
    def test_close_sends_state_closed(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(state="closed")
        result = server.github_set_pr_state(state="closed", pr_number=7)
        self.assertTrue(result["ok"], msg=result.get("error"))
        method, path, payload = mock_gh.call_args.args
        self.assertEqual(("PATCH", "/repos/org/repo/pulls/7", {"state": "closed"}),
                         (method, path, payload))
        self.assertEqual("closed", result["state"])
        self.assertEqual((7, "u"), (result["number"], result["url"]))

    @patch.object(server, "_github_request")
    def test_reopen_sends_state_open(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(state="open")
        result = server.github_set_pr_state(state="open", pr_number=7)
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual({"state": "open"}, mock_gh.call_args.args[2])

    @patch.object(server, "_github_request")
    def test_case_insensitive_state(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(state="closed")
        result = server.github_set_pr_state(state="CLOSED", pr_number=7)
        self.assertTrue(result["ok"], msg=result.get("error"))

    @patch.object(server, "_github_request")
    def test_invalid_state_rejected(self, mock_gh, mock_repo):
        result = server.github_set_pr_state(state="merged", pr_number=7)
        self.assertFalse(result["ok"])
        self.assertIn("open", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_negative_pr_number_rejected(self, mock_gh, mock_repo):
        result = server.github_set_pr_state(state="closed", pr_number=-1)
        self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_merged_pr_cannot_be_reopened(self, mock_gh, mock_repo):
        # GitHub answers a reopen of a merged PR with the PR still closed.
        mock_gh.return_value = self._pr(state="closed", merged=True)
        result = server.github_set_pr_state(state="open", pr_number=7)
        self.assertFalse(result["ok"])
        self.assertIn("merged", result["error"])
        self.assertEqual({"state": "closed", "merged": True}, result["returned"])

    @patch.object(server, "_github_request")
    def test_state_unchanged_is_not_success(self, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(state="open")
        result = server.github_set_pr_state(state="closed", pr_number=7)
        self.assertFalse(result["ok"])
        self.assertIn("still open", result["error"])

    @patch.object(server, "_github_request", return_value={"ok": True, "status": 204})
    def test_bodyless_response_is_not_success(self, mock_gh, mock_repo):
        result = server.github_set_pr_state(state="closed", pr_number=7)
        self.assertFalse(result["ok"])
        self.assertIn("could not be confirmed", result["error"])

    @patch.object(server, "_github_request",
                  return_value={"ok": False, "error": "GitHub returned HTTP 404"})
    def test_github_error_propagated(self, mock_gh, mock_repo):
        result = server.github_set_pr_state(state="closed", pr_number=7)
        self.assertEqual({"ok": False, "error": "GitHub returned HTTP 404"}, result)

    @patch.object(server, "_github_request")
    def test_close_looks_up_open_pr_when_number_omitted(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            [{"number": 7}],                 # open-PR lookup for the branch
            self._pr(state="closed"),        # PATCH response
        ]
        result = server.github_set_pr_state(state="closed")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual("PATCH", mock_gh.call_args.args[0])
        self.assertEqual("/repos/org/repo/pulls/7", mock_gh.call_args.args[1])

    @patch.object(server, "_find_recent_pr_by_branch",
                  return_value={"ok": True, "found": True, "pr": {"number": 9}})
    @patch.object(server, "_github_request")
    def test_reopen_uses_recent_pr_when_number_omitted(
            self, mock_gh, mock_recent, mock_repo):
        # The open-PR lookup would find nothing for a closed target, so reopen
        # resolves the branch's most recent PR instead.
        mock_gh.return_value = self._pr(number=9, state="open")
        result = server.github_set_pr_state(state="open")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_recent.assert_called_once_with("org", "repo", "feature/x")
        self.assertEqual("/repos/org/repo/pulls/9", mock_gh.call_args.args[1])

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope")
    def test_org_scope_gate_for_direct_addressing(
            self, mock_gate, mock_gh, mock_repo):
        mock_gh.return_value = self._pr(state="closed")
        result = server.github_set_pr_state(
            state="closed", pr_number=7, org="org", repo="repo")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_gate.assert_called_once_with("org")

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope",
                  side_effect=PermissionError("out of scope"))
    def test_out_of_scope_org_refused(self, mock_gate, mock_gh, mock_repo):
        with self.assertRaises(PermissionError):
            server.github_set_pr_state(
                state="closed", pr_number=7, org="other", repo="repo")
        mock_gh.assert_not_called()


@patch.object(server, "_resolve_github_repo",
              return_value=("org", "repo", "feature/x", None))
class TestGithubSetPrStateArchived(unittest.TestCase):
    """An archived round's pull request state may not be changed."""

    _ARCHIVED = {"workstreamId": "ws-arch", "archived": True,
                 "defaultBranch": "qa/retro-x"}

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_find_workstream", return_value=_ARCHIVED)
    @patch.object(server, "_github_request")
    def test_rejected_for_archived_workstream(self, mock_gh, mock_find, mock_repo):
        result = server.github_set_pr_state(
            state="closed", pr_number=7, workstream_id="ws-arch")
        self.assertFalse(result["ok"])
        self.assertIn("ws-arch", result["error"])
        mock_gh.assert_not_called()


@patch.object(server, "_resolve_github_repo",
              return_value=("org", "repo", "feature/x", None))
class TestGithubStartTestRun(unittest.TestCase):
    """A test run deploys a resolved branch of the workspace repo."""

    def setUp(self):
        _grant_all_scopes()

    def _ref(self, branch="feature/x"):
        return {"ref": f"refs/heads/{branch}", "object": {"sha": "abc123"}}

    def _deployment(self):
        return {"id": 42, "sha": "abc123", "environment": "test",
                "ref": "feature/x"}

    @patch.object(server, "_github_request")
    def test_happy_path_creates_deployment(self, mock_gh, mock_repo):
        mock_gh.side_effect = [self._ref(), self._deployment()]
        result = server.github_start_test_run(branch="feature/x")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual(42, result["deployment_id"])
        self.assertEqual("test", result["environment"])
        self.assertEqual("feature/x", result["branch"])
        # The deployment call carries exactly the required fields.
        method, path, payload = mock_gh.call_args.args
        self.assertEqual(("POST", "/repos/org/repo/deployments"), (method, path))
        self.assertEqual(
            {"ref": "feature/x", "environment": "test", "auto_merge": False,
             "required_contexts": [], "transient_environment": True},
            payload)

    @patch.object(server, "_github_request",
                  return_value={"ok": False, "error": "GitHub returned HTTP 404"})
    def test_raw_sha_without_branch_refused_by_ref_lookup(self, mock_gh, mock_repo):
        # A raw commit SHA is no longer rejected by its name shape; it reaches
        # the exact refs/heads lookup, which 404s because no branch is named by
        # it. Built at runtime (a 40-character hex string) so no literal
        # commit-id appears in the source.
        result = server.github_start_test_run(branch="ab" * 20)
        self.assertFalse(result["ok"])
        self.assertIn("not found on refs/heads", result["error"])
        # The lookup ran (GET); the deployment POST did not.
        self.assertEqual(1, mock_gh.call_count)
        self.assertEqual("GET", mock_gh.call_args.args[0])

    @patch.object(server, "_github_request")
    def test_hex_branch_name_is_accepted_when_it_exists(self, mock_gh, mock_repo):
        # A branch whose name is all hex (a date-like "20261121") is a valid
        # refs/heads name and must not be refused as if it were a raw SHA.
        mock_gh.side_effect = [self._ref("20261121"), self._deployment()]
        result = server.github_start_test_run(branch="20261121")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual("20261121", result["branch"])
        # It reached the deployment POST rather than being refused by name.
        self.assertEqual("POST", mock_gh.call_args.args[0])

    @patch.object(server, "_github_request")
    def test_branch_with_pull_path_segment_is_accepted(self, mock_gh, mock_repo):
        # "feature/pull/fix" contains "pull" as a path segment but is an
        # ordinary branch, not a pull-request ref.
        mock_gh.side_effect = [self._ref("feature/pull/fix"), self._deployment()]
        result = server.github_start_test_run(branch="feature/pull/fix")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual("feature/pull/fix", result["branch"])

    @patch.object(server, "_github_request")
    def test_fork_head_refused(self, mock_gh, mock_repo):
        result = server.github_start_test_run(branch="someone:feature/x")
        self.assertFalse(result["ok"])
        self.assertIn("fork", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_pull_ref_refused(self, mock_gh, mock_repo):
        result = server.github_start_test_run(branch="refs/pull/12/head")
        self.assertFalse(result["ok"])
        self.assertIn("pull request ref", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_bare_pull_ref_refused(self, mock_gh, mock_repo):
        result = server.github_start_test_run(branch="pull/12/head")
        self.assertFalse(result["ok"])
        self.assertIn("pull request ref", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request",
                  return_value={"ok": False, "error": "GitHub returned HTTP 404"})
    def test_missing_branch_refused(self, mock_gh, mock_repo):
        result = server.github_start_test_run(branch="feature/x")
        self.assertFalse(result["ok"])
        self.assertIn("not found on refs/heads", result["error"])

    @patch.object(server, "_github_request")
    def test_ref_mismatch_refused(self, mock_gh, mock_repo):
        # A prefix match that resolves to a different ref is not the branch.
        mock_gh.return_value = {"ref": "refs/heads/feature/xyz"}
        result = server.github_start_test_run(branch="feature/x")
        self.assertFalse(result["ok"])
        self.assertIn("did not resolve", result["error"])

    @patch.object(server, "_github_request")
    def test_deployment_without_id_is_not_success(self, mock_gh, mock_repo):
        mock_gh.side_effect = [self._ref(), {"message": "Conflict"}]
        result = server.github_start_test_run(branch="feature/x")
        self.assertFalse(result["ok"])
        self.assertIn("could not be confirmed", result["error"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope")
    def test_org_scope_gate_for_direct_addressing(
            self, mock_gate, mock_gh, mock_repo):
        mock_gh.side_effect = [self._ref(), self._deployment()]
        result = server.github_start_test_run(
            branch="feature/x", org="org", repo="repo")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_gate.assert_called_once_with("org")

    @patch.object(server, "_require_org_in_scope",
                  side_effect=PermissionError("out of scope"))
    @patch.object(server, "_github_request")
    def test_out_of_scope_org_refused(self, mock_gh, mock_gate, mock_repo):
        with self.assertRaises(PermissionError):
            server.github_start_test_run(
                branch="feature/x", org="other", repo="repo")
        mock_gh.assert_not_called()


@patch.object(server, "_resolve_github_repo",
              return_value=("org", "repo", "feature/x", None))
class TestGithubRerunFailedJobs(unittest.TestCase):
    """Rerunning the failed jobs of an existing run, in place."""

    def setUp(self):
        _grant_all_scopes()

    def _run(self, status="completed", attempt=1):
        return {"id": 99, "status": status, "conclusion": "failure",
                "run_attempt": attempt, "html_url": "https://run/99"}

    def _jobs(self, *conclusions):
        return {"jobs": [{"conclusion": c} for c in conclusions]}

    @patch.object(server, "_github_request")
    def test_happy_path_reruns_failed_jobs(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._run(attempt=1),            # GET run
            self._jobs("success", "failure"),  # GET jobs
            {"ok": True, "status": 201},     # POST rerun-failed-jobs
            self._run(attempt=2),            # GET run (refreshed)
        ]
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual(99, result["run_id"])
        self.assertEqual("org/repo", result["repo"])
        self.assertEqual(2, result["run_attempt"])
        self.assertEqual("https://run/99", result["html_url"])
        # The rerun POST hit the rerun-failed-jobs endpoint with no payload.
        post_call = mock_gh.call_args_list[2]
        self.assertEqual("POST", post_call.args[0])
        self.assertEqual(
            "/repos/org/repo/actions/runs/99/rerun-failed-jobs",
            post_call.args[1])
        self.assertEqual(2, len(post_call.args))  # method and path, no payload

    @patch.object(server, "_github_request")
    def test_zero_run_id_rejected(self, mock_gh, mock_repo):
        result = server.github_rerun_failed_jobs(run_id=0)
        self.assertFalse(result["ok"])
        self.assertIn("run_id", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    def test_refused_while_run_in_progress(self, mock_gh, mock_repo):
        mock_gh.return_value = self._run(status="in_progress")
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertFalse(result["ok"])
        self.assertIn("in_progress", result["error"])
        # The run was read; no jobs listing and no rerun POST followed.
        self.assertEqual(1, mock_gh.call_count)

    @patch.object(server, "_github_request")
    def test_refused_when_nothing_failed(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._run(),
            self._jobs("success", "success"),
        ]
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertFalse(result["ok"])
        self.assertIn("no failed", result["error"])
        # Read the run and its jobs, but did not POST a rerun.
        self.assertEqual(2, mock_gh.call_count)

    @patch.object(server, "_github_request")
    def test_cancelled_job_is_rerunnable(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._run(),
            self._jobs("success", "cancelled"),
            {"ok": True, "status": 201},
            self._run(attempt=2),
        ]
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertTrue(result["ok"], msg=result.get("error"))

    @patch.object(server, "_github_request")
    def test_github_403_reports_actions_write(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._run(),
            self._jobs("failure"),
            {"ok": False, "error": "GitHub returned HTTP 403: Forbidden"},
        ]
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertFalse(result["ok"])
        self.assertIn("HTTP 403", result["error"])
        self.assertIn("Actions write", result["error"])
        self.assertIn("org/repo", result["error"])

    @patch.object(server, "_github_request",
                  return_value={"ok": False, "error": "GitHub returned HTTP 404"})
    def test_run_read_error_propagated(self, mock_gh, mock_repo):
        result = server.github_rerun_failed_jobs(run_id=99)
        self.assertEqual({"ok": False, "error": "GitHub returned HTTP 404"},
                         result)

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope")
    def test_org_scope_gate_for_direct_addressing(
            self, mock_gate, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._run(),
            self._jobs("failure"),
            {"ok": True, "status": 201},
            self._run(attempt=2),
        ]
        result = server.github_rerun_failed_jobs(
            run_id=99, org="org", repo="repo")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_gate.assert_called_once_with("org")

    @patch.object(server, "_github_request")
    @patch.object(server, "_require_org_in_scope",
                  side_effect=PermissionError("out of scope"))
    def test_out_of_scope_org_refused(self, mock_gate, mock_gh, mock_repo):
        with self.assertRaises(PermissionError):
            server.github_rerun_failed_jobs(
                run_id=99, org="other", repo="repo")
        mock_gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
