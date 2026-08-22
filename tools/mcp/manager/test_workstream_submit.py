"""Tests for workstream task submission, including the commit-sequencing linter.

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


class TestWorkstreamSubmitTask(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_submit_basic(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-test"}
        result = server.workstream_submit_task(
            prompt="Fix the bug", workstream_id="ws-test")
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["prompt"], "Fix the bug")
        self.assertEqual(payload["workstreamId"], "ws-test")
        self.assertTrue(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_submit_with_options(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-2"}
        server.workstream_submit_task(
            prompt="Task",
            target_branch="feature/x",
            description="test task",
            max_turns=10,
            max_budget_usd=5.0,
            protect_test_files=True,
            enforce_changes=True,
            started_after="1710000000000",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["targetBranch"], "feature/x")
        self.assertEqual(payload["description"], "test task")
        self.assertEqual(payload["maxTurns"], 10)
        self.assertEqual(payload["maxBudgetUsd"], 5.0)
        self.assertTrue(payload["protectTestFiles"])
        self.assertTrue(payload["enforceChanges"])
        self.assertEqual(payload["startedAfter"], "1710000000000")

    @patch.object(server, "_controller_post")
    def test_submit_omits_zero_defaults(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxTurns", payload)
        self.assertNotIn("maxBudgetUsd", payload)
        self.assertNotIn("protectTestFiles", payload)
        self.assertNotIn("enforceChanges", payload)
        self.assertNotIn("startedAfter", payload)

    @patch.object(server, "_controller_post")
    def test_submit_shell_job_type(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-s1", "jobType": "shell"}
        result = server.workstream_submit_task(
            job_type="shell", command="mvn -q test", workstream_id="ws-test")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["jobType"], "shell")
        self.assertEqual(payload["command"], "mvn -q test")
        self.assertNotIn("prompt", payload)
        self.assertTrue(result["ok"])

    @patch.object(server, "_controller_post")
    def test_submit_shell_implied_by_command(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-s2"}
        server.workstream_submit_task(command="ls -la", workstream_id="ws-test")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["jobType"], "shell")
        self.assertEqual(payload["command"], "ls -la")

    def test_submit_rejects_unknown_job_type(self):
        _grant_all_scopes()
        result = server.workstream_submit_task(
            job_type="bogus", command="ls", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("Unknown job_type", result["error"])

    def test_submit_shell_requires_command(self):
        _grant_all_scopes()
        result = server.workstream_submit_task(
            job_type="shell", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("command is required", result["error"])

    def test_submit_requires_prompt_or_command(self):
        _grant_all_scopes()
        result = server.workstream_submit_task(workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("prompt is required", result["error"])

    @patch.object(server, "_controller_post")
    def test_submit_error(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": False, "error": "No agents"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    def test_rejects_oversized_prompt(self):
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="x" * 50_001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])

    @patch.object(server, "_controller_post")
    def test_submit_repo_url_forwarded(self, mock_post):
        # Two workstreams that share a default branch but live on
        # different repos must be disambiguated by repo_url.  Verify the
        # tool forwards repo_url as repoUrl so the controller-side
        # findByBranchAndRepo lookup can pick the right workstream.
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-r1"}
        server.workstream_submit_task(
            prompt="Task",
            target_branch="feature/audio-prototypes",
            repo_url="git@github.com:almostrealism/common.git",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["targetBranch"], "feature/audio-prototypes")
        self.assertEqual(payload["repoUrl"],
                         "git@github.com:almostrealism/common.git")

    @patch.object(server, "_controller_post")
    def test_submit_repo_url_omitted_when_blank(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-r2"}
        server.workstream_submit_task(prompt="Task", target_branch="feature/x")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("repoUrl", payload)

    @patch.object(server, "_controller_post")
    def test_submit_create_workstream_if_missing(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-c1",
                                  "workstreamId": "ws-new",
                                  "workstreamCreated": True}
        result = server.workstream_submit_task(
            prompt="Task",
            target_branch="feature/unregistered",
            repo_url="git@github.com:almostrealism/common.git",
            create_workstream_if_missing=True,
        )
        payload = mock_post.call_args[0][1]
        self.assertTrue(payload["createWorkstreamIfMissing"])
        self.assertEqual(result["created_workstream"], "ws-new")

    @patch.object(server, "_controller_post")
    def test_submit_create_workstream_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-c2"}
        result = server.workstream_submit_task(
            prompt="Task", target_branch="feature/x")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("createWorkstreamIfMissing", payload)
        self.assertNotIn("created_workstream", result)

    @patch.object(server, "_controller_post")
    def test_submit_required_labels(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-3"}
        server.workstream_submit_task(
            prompt="Task", required_labels="platform:macos,gpu:true")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["requiredLabels"], {
            "platform": "macos", "gpu": "true"})

    @patch.object(server, "_controller_post")
    def test_submit_required_labels_json_object(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-3b"}
        server.workstream_submit_task(
            prompt="Task", required_labels='{"platform": "macos", "gpu": "true"}')
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["requiredLabels"], {
            "platform": "macos", "gpu": "true"})

    def test_parse_required_labels_csv(self):
        self.assertEqual(
            server._parse_required_labels("platform:macos,gpu:true"),
            {"platform": "macos", "gpu": "true"})

    def test_parse_required_labels_json_object_not_mangled(self):
        # Regression: a JSON object string must not be split on the first colon
        # into a mangled {'{"platform"': '"macos"}'} entry (which corrupted
        # workstreams.yaml). It must parse as a proper labels map.
        self.assertEqual(
            server._parse_required_labels('{"platform": "macos"}'),
            {"platform": "macos"})

    def test_parse_required_labels_malformed_json_returns_empty(self):
        # A leading "{" signals JSON intent; malformed JSON must NOT fall back to
        # the CSV splitter (which would re-create the mangled map this guards
        # against). It returns an empty map instead.
        self.assertEqual(
            server._parse_required_labels('{"platform": "macos"'), {})
        self.assertEqual(
            server._parse_required_labels('{platform: macos}'), {})

    def test_parse_required_labels_json_non_string_values_coerced(self):
        # Booleans coerce to lowercase JSON form so they match the CSV form
        # (gpu:true) and node-side label values.
        self.assertEqual(
            server._parse_required_labels('{"gpu": true, "count": 2}'),
            {"gpu": "true", "count": "2"})

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_local(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d1"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="local")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "local")

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_spawn(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d2"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="spawn")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "spawn")

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d3"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("deduplicationMode", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp1"}
        server.workstream_submit_task(prompt="Task", max_deduplication_passes=5)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxDeduplicationPasses"], 5)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxDeduplicationPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_one(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp3"}
        server.workstream_submit_task(prompt="Task", max_deduplication_passes=1)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxDeduplicationPasses"], 1)

    @patch.object(server, "_controller_post")
    def test_submit_max_review_passes_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp1"}
        server.workstream_submit_task(prompt="Task", max_review_passes=3)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxReviewPasses"], 3)

    @patch.object(server, "_controller_post")
    def test_submit_max_review_passes_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxReviewPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_review_enabled_omitted_when_true(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp3"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("reviewEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_review_enabled_false_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp4"}
        server.workstream_submit_task(prompt="Task", review_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["reviewEnabled"], False)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_omitted_by_default(self, mock_post):
        """retrospective_enabled is opt-in (default false), so the wire payload
        must omit retrospectiveEnabled when the caller did not pass it."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("retrospectiveEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_true_forwarded(self, mock_post):
        """retrospective_enabled=True must reach the controller as
        retrospectiveEnabled=True so the controller can opt the job into the
        retrospective phase."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt2"}
        server.workstream_submit_task(prompt="Task", retrospective_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["retrospectiveEnabled"], True)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_false_omitted(self, mock_post):
        """retrospective_enabled=False is the default; the wire payload must
        omit the key rather than forward an explicit false (matches the
        organizational_placement_enabled behaviour)."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt3"}
        server.workstream_submit_task(prompt="Task", retrospective_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("retrospectiveEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_falsification_enabled_omitted_by_default(self, mock_post):
        """falsification_enabled is opt-in (default false), so the wire payload
        must omit falsificationEnabled when the caller did not pass it."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-fal1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("falsificationEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_falsification_enabled_true_forwarded(self, mock_post):
        """falsification_enabled=True must reach the controller as
        falsificationEnabled=True so the controller can opt the job into the
        falsification phase."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-fal2"}
        server.workstream_submit_task(prompt="Task", falsification_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["falsificationEnabled"], True)

    @patch.object(server, "_controller_post")
    def test_submit_falsification_enabled_false_omitted(self, mock_post):
        """falsification_enabled=False is the default; the wire payload must
        omit the key rather than forward an explicit false (matches the
        retrospective_enabled behaviour)."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-fal3"}
        server.workstream_submit_task(prompt="Task", falsification_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("falsificationEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_use_tmux_omitted_by_default(self, mock_post):
        """use_tmux uses presence semantics (default None), so the wire payload
        must omit useTmux when the caller did not pass it. The job then inherits
        the workstream default and the AR_AGENT_USE_TMUX env var on the node."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-tmux1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("useTmux", payload)

    @patch.object(server, "_controller_post")
    def test_submit_use_tmux_true_forwarded(self, mock_post):
        """use_tmux=True must reach the controller as useTmux=True so the job
        launches its agent subprocess inside a tmux session."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-tmux2"}
        server.workstream_submit_task(prompt="Task", use_tmux=True)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["useTmux"], True)

    @patch.object(server, "_controller_post")
    def test_submit_use_tmux_false_forwarded(self, mock_post):
        """An explicit use_tmux=False must reach the controller as useTmux=False
        (presence semantics) so a job can opt out of a workstream that defaults
        tmux on. The controller distinguishes absent from false via hasField."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-tmux3"}
        server.workstream_submit_task(prompt="Task", use_tmux=False)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["useTmux"], False)

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_default_omitted(self, mock_post):
        """sensitive_file_protection_enabled defaults to TRUE (protections
        active), so the wire payload must omit the key when the caller
        did not opt out. Mirrors the inverted semantics of
        retrospective_enabled/organizational_placement_enabled."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_default_true_explicit_omitted(self, mock_post):
        """sensitive_file_protection_enabled=True is the default; the wire
        payload must omit the key rather than forward an explicit true."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp2"}
        server.workstream_submit_task(prompt="Task",
                                      sensitive_file_protection_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_disabled_forwarded(self, mock_post):
        """sensitive_file_protection_enabled=False must reach the controller
        as sensitiveFileProtectionEnabled=False so the controller can opt the
        job out of the per-job protections and compute a bypass signature."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp3"}
        server.workstream_submit_task(prompt="Task",
                                      sensitive_file_protection_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["sensitiveFileProtectionEnabled"], False)

    @patch.object(server, "_controller_post")
    def test_submit_preserves_job_id_in_next_steps(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "jobId": "job-42", "workstreamId": "ws-x"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-x")
        self.assertTrue(result["ok"])
        self.assertEqual(result["workstreamId"], "ws-x")
        # next_steps should mention the workstream
        self.assertTrue(any("ws-x" in s for s in result["next_steps"]))

    @patch.object(server, "_controller_post")
    def test_submit_controller_timeout(self, mock_post):
        """Simulate controller timeout — returns an error dict."""
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": False, "error": "Internal error contacting controller"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_submit_controller_returns_no_ok_field(self, mock_post):
        """Controller returns success-like response without explicit 'ok' key."""
        _grant_all_scopes()
        mock_post.return_value = {
            "jobId": "job-99", "workstreamId": "ws-test"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        # Without "ok" key, result.get("ok") is None/falsy, so error next_steps added
        self.assertIn("next_steps", result)

    def test_requires_submit_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.workstream_submit_task(prompt="Task")

    def test_submit_rejects_legacy_model(self):
        """The dropped `model` param is rejected with a 400-style error
        pointing callers at default_phase_config / phase_configs."""
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="Task", model="opus")
        self.assertFalse(result["ok"])
        self.assertIn("model", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["model"])

    def test_submit_rejects_legacy_effort(self):
        """The dropped `effort` param is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="Task", effort="high")
        self.assertFalse(result["ok"])
        self.assertIn("effort", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["effort"])

    @patch.object(server, "_controller_post")
    def test_submit_omits_model_and_effort_when_unset(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-me-2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("model", payload)
        self.assertNotIn("effort", payload)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_command_included_in_payload(self, mock_post):
        """post_completion_command is forwarded to the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pcc"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="mvn -pl flowtree/runtime test -Dtest=FooTest",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(
            payload["postCompletionCommand"],
            "mvn -pl flowtree/runtime test -Dtest=FooTest",
        )

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_command_omitted_by_default(self, mock_post):
        """post_completion_command must not appear in the payload when not set."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pcc-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("postCompletionCommand", payload)
        self.assertNotIn("postCompletionTimeoutSeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_timeout_included_when_set(self, mock_post):
        """A non-zero post_completion_timeout_seconds is forwarded."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pct"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            post_completion_timeout_seconds=600,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["postCompletionTimeoutSeconds"], 600)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_timeout_omitted_when_zero(self, mock_post):
        """Timeout=0 (the default) must not appear in the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pct-zero"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            post_completion_timeout_seconds=0,
        )
        payload = mock_post.call_args[0][1]
        self.assertNotIn("postCompletionTimeoutSeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_forwarded(self, mock_post):
        """max_post_completion_passes is forwarded to the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            max_post_completion_passes=5,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxPostCompletionPasses"], 5)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_omitted_by_default(self, mock_post):
        """max_post_completion_passes must not appear in the payload when not set."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxPostCompletionPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_one(self, mock_post):
        """max_post_completion_passes=1 is forwarded correctly."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp-one"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            max_post_completion_passes=1,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxPostCompletionPasses"], 1)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_forwarded(self, mock_post):
        """delay_seconds is forwarded to the controller payload as delaySeconds."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-delay"}
        server.workstream_submit_task(prompt="Task", delay_seconds=30)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["delaySeconds"], 30)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_omitted_by_default(self, mock_post):
        """delay_seconds must not appear in the payload when not specified."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-nodelay"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("delaySeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_zero_omitted(self, mock_post):
        """delay_seconds=0 must not appear in the payload (immediate dispatch)."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-nodelay-zero"}
        server.workstream_submit_task(prompt="Task", delay_seconds=0)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("delaySeconds", payload)

    def test_submit_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected with a 400-style error
        pointing callers at phase_configs / default_phase_config."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            runners='{"primary":"claude","deduplication":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["runners"])

    def test_submit_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task", default_runner="opencode")
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["default_runner"])

    @patch.object(server, "_controller_post")
    def test_submit_runners_omitted_by_default(self, mock_post):
        """No runners argument means no runners key in the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-norunners"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("runners", payload)

    @patch.object(server, "_controller_post")
    def test_submit_default_phase_config_forwarded(self, mock_post):
        """default_phase_config JSON is parsed and forwarded as a nested object."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-default-pc"}
        server.workstream_submit_task(
            prompt="Task",
            default_phase_config='{"runner":"claude","model":"opus","effort":"high"}',
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["defaultPhaseConfig"],
                         {"runner": "claude", "model": "opus", "effort": "high"})

    @patch.object(server, "_controller_post")
    def test_submit_phase_configs_forwarded(self, mock_post):
        """phase_configs JSON is parsed and forwarded as a nested object map."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pc"}
        server.workstream_submit_task(
            prompt="Task",
            phase_configs='{"review":{"model":"opus","effort":"high"},'
                          '"commit-message":{"runner":"opencode"}}',
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["phaseConfigs"], {
            "review": {"model": "opus", "effort": "high"},
            "commit-message": {"runner": "opencode"},
        })

    def test_submit_phase_configs_rejects_unknown_phase(self):
        """Unknown phase names in phase_configs are rejected client-side."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            phase_configs='{"future-phase":{"model":"opus"}}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("future-phase", result["error"])

    def test_submit_default_phase_config_rejects_unknown_key(self):
        """Unknown keys in default_phase_config are rejected client-side."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            default_phase_config='{"bogus":"value"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("bogus", result["error"])

    def test_submit_rejects_legacy_runners_even_with_phase_configs(self):
        """A legacy `runners` map is rejected up front even when the new
        phase_configs form is also supplied — no silent translation."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            runners='{"primary":"opencode"}',
            phase_configs='{"review":{"effort":"high"}}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])

    @patch.object(server, "_controller_post")
    def test_submit_phase_configs_omitted_by_default(self, mock_post):
        """No phase_configs argument means no defaultPhaseConfig/phaseConfigs keys."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-noPC"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("defaultPhaseConfig", payload)
        self.assertNotIn("phaseConfigs", payload)

class TestSubmitCommitLanguageLinter(unittest.TestCase):
    """Server-side linter rejects prompts that imply the agent controls
    git commits.  allow_commit_language=True bypasses the linter.
    """

    def setUp(self):
        _grant_all_scopes()

    def tearDown(self):
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes

    def _bad_prompt(self, text):
        """Submit a prompt that should be rejected; return the result."""
        return server.workstream_submit_task(prompt=text)

    def _good_prompt(self, text):
        """Submit a prompt with allow_commit_language=True and assert the
        linter did not fire. The controller HTTP call is mocked here so the
        test does not depend on CONTROLLER_URL reachability — without the
        mock urlopen would attempt a real network round-trip and pollute the
        observable error with a transport-level failure, making the linter
        assertion racy/slow on offline runners.
        """
        with mock.patch.object(server, "_controller_post",
                               return_value={"ok": True, "taskId": "test"}):
            result = server.workstream_submit_task(
                prompt=text, allow_commit_language=True)
        # Linter must not have fired — the mocked controller call is the
        # only thing that should have responded.
        error = result.get("error", "")
        self.assertNotIn("sequence of commits", error,
                         "allow_commit_language=True should bypass the linter")
        self.assertNotIn("commit-language", error,
                         "allow_commit_language=True should bypass the linter")

    # -- Forbidden patterns ---------------------------------------------------

    def test_rejects_commit_number_phrase(self):
        result = self._bad_prompt(
            "Do the following: Commit 1: set up the schema. "
            "Commit 2: add the service layer.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("commit-number phrase", result["error"])

    def test_rejects_first_commit_phrase(self):
        result = self._bad_prompt(
            "In the first commit add the migration file, "
            "then write the test.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("first commit", result["error"])

    def test_rejects_next_commit_phrase(self):
        result = self._bad_prompt(
            "After the setup is done, in the next commit "
            "wire up the controller.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("next commit", result["error"])

    def test_rejects_final_commit_phrase(self):
        result = self._bad_prompt(
            "The final commit should include the documentation update.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("final commit", result["error"])

    def test_rejects_as_separate_commits_phrase(self):
        result = self._bad_prompt(
            "Please land each layer as separate commits so reviewers "
            "can examine them independently.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("separate", result["error"])

    def test_rejects_in_n_commits_phrase(self):
        result = self._bad_prompt(
            "Please land the whole feature in 3 commits with clear boundaries.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_message_should_phrase(self):
        result = self._bad_prompt(
            "When you are done, your commit message should summarize "
            "the entire changeset.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_this_before_phrase(self):
        result = self._bad_prompt(
            "Commit this before starting on the second part of the task.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_between_each_phrase(self):
        result = self._bad_prompt(
            "Please commit between each major change so the diff is small.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    # -- Rejection message quality --------------------------------------------

    def test_rejection_message_names_the_phrase_and_line(self):
        result = self._bad_prompt(
            "Here is the plan:\n"
            "Commit 1: implement the parser.\n"
            "Commit 2: add tests.\n")
        self.assertFalse(result["ok"])
        error = result["error"]
        self.assertIn("Line 2", error)
        self.assertIn("commit-number phrase", error)

    # -- Escape hatch ---------------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_allow_commit_language_bypasses_linter(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j1"}
        result = server.workstream_submit_task(
            prompt="Commit 1: do X. Commit 2: do Y.",
            allow_commit_language=True,
        )
        self.assertTrue(result["ok"],
                        "allow_commit_language=True must bypass the linter")
        mock_post.assert_called_once()

    # -- Short-prompt exemption -----------------------------------------------

    @patch.object(server, "_controller_post")
    def test_short_prompt_skips_linter(self, mock_post):
        """Prompts shorter than 50 chars are too brief for the linter to run."""
        mock_post.return_value = {"ok": True, "jobId": "j2"}
        # "Commit 1: fix" is only 14 chars — well under the threshold.
        result = server.workstream_submit_task(prompt="Commit 1: fix")
        # The linter must not fire; the call goes to the controller.
        mock_post.assert_called_once()
        self.assertTrue(result["ok"])

    # -- False-positive guard -------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_does_not_reject_commit_message_convention_reference(self, mock_post):
        """A prompt that references the existing commit message convention
        (e.g. for documentation purposes) should not be flagged.
        """
        mock_post.return_value = {"ok": True, "jobId": "j3"}
        prompt = (
            "Update the CONTRIBUTING guide to explain that the existing "
            "commit message convention follows the Angular format: "
            "<type>(<scope>): <subject>.  Add examples of good and bad "
            "commit subject lines so contributors know what to write."
        )
        result = server.workstream_submit_task(prompt=prompt)
        self.assertTrue(result["ok"],
                        "Normal English references to commit conventions "
                        "should not be rejected by the linter")

class TestWorkstreamSubmitSelfCollision(unittest.TestCase):
    """workstream_submit_task must not let an agent submit work to its
    own workstream — concurrent commits on the same branch break the
    git lifecycle the controller relies on. The token-bound caller
    workstream comes from the temp token's payload (set during auth).
    """

    def setUp(self):
        _grant_all_scopes()
        server._set_workspace_scopes(["TAAA"])
        server._set_token_context(workstream_id="ws-self", job_id="job-self")
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_rejects_submission_to_calling_workstream(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Do something", workstream_id="ws-self")
        self.assertFalse(result["ok"])
        self.assertIn("calling workstream itself", result["error"])
        self.assertIn("ws-self", result["error"])
        self.assertIn("git collisions", result["error"])
        # Error should explain the user-confusion case clearly.
        self.assertIn("misunderstanding", result["error"])
        self.assertIn("directly", result["error"])
        # Must point at workstream_list as the discovery path.
        self.assertIn("workstream_list", result["error"])
        self.assertIn("next_steps", result)
        # Controller must NOT have been called.
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_rejects_missing_workstream_id_for_agent(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Task", target_branch="feature/somewhere")
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id is required", result["error"])
        self.assertIn("workstream_list", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_allows_submission_to_other_workstream_in_workspace(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-other"}
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-other")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["workstreamId"], "ws-other")

    @patch.object(server, "_controller_get")
    def test_rejects_submission_to_workstream_in_other_workspace(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-foreign", "slackWorkspaceId": "TBBB"},
        ]
        with self.assertRaises(PermissionError):
            server.workstream_submit_task(
                prompt="Task", workstream_id="ws-foreign")

class TestWorkstreamSubmitAgentSensitiveFileProtectionGuard(unittest.TestCase):
    """An in-flight coding agent (workstream-bound armt_tmp_ HMAC token)
    must never be allowed to forward ``sensitive_file_protection_enabled``
    =False to the controller. Doing so would cause the controller to
    compute a controller-signed bypass HMAC for the new job, and the
    resulting commit would be allowed to modify normally-protected
    files on the target workstream. The flag is operator-only.
    """

    def setUp(self):
        _grant_all_scopes()
        server._set_workspace_scopes(["TAAA"])
        server._set_token_context(workstream_id="ws-self", job_id="job-self")
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_rejects_agent_disabling_protection(self, mock_post, mock_get):
        """An agent with a workstream-bound token must be rejected when
        it asks for sensitive_file_protection_enabled=False. The call
        must be refused before the controller is contacted at all so
        the controller never mints a bypass signature on the agent's
        behalf."""
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            sensitive_file_protection_enabled=False,
        )
        self.assertFalse(result["ok"])
        # Error must explain why and reference the operator-only nature
        # of the flag.
        self.assertIn("sensitive_file_protection_enabled", result["error"])
        self.assertIn("operator", result["error"].lower())
        self.assertIn("next_steps", result)
        # Controller must NOT have been called — the rejection is
        # local so a bypass signature can never be minted.
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_allows_agent_to_leave_protection_default(self, mock_post, mock_get):
        """An agent must still be able to submit to other workstreams
        while leaving ``sensitive_file_protection_enabled`` at its
        default (True). The default is harmless: the controller sees
        no field at all, computes no bypass, and applies the standard
        protections on the new job."""
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-other"}
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-other")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        # The default (True) must NOT appear in the wire payload, mirroring
        # the existing test_submit_sensitive_file_protection_default_omitted
        # contract: the wire format only carries explicit opt-outs.
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)

class TestWorkstreamSubmitUnscopedCallerCanOptOut(unittest.TestCase):
    """Unscoped operator callers (no workstream-bound token) must still
    be able to opt out of sensitive-file protection — that is the
    legitimate production path for the bypass HMAC. The
    operator-only restriction is enforced by the presence of a
    workstream binding on the token, not by the opt-out itself.
    """

    def setUp(self):
        _grant_all_scopes()
        # No token context — this simulates a static admin bearer that
        # is not bound to any workstream.

    @patch.object(server, "_controller_post")
    def test_unscoped_operator_can_disable_protection(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "job-op1"}
        result = server.workstream_submit_task(
            prompt="Operator task",
            sensitive_file_protection_enabled=False,
        )
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        # The opt-out must be forwarded so the controller can compute
        # a bypass HMAC.
        self.assertIs(payload["sensitiveFileProtectionEnabled"], False)

class TestWorkstreamSubmitUnscopedCallerUnaffected(unittest.TestCase):
    """Unscoped operator callers (no workstream-bound token) must retain
    the prior behaviour: no self-collision check, target_branch alone is
    accepted, the controller resolves the workstream.
    """

    def setUp(self):
        _grant_all_scopes()  # no workspace scopes, no token context

    @patch.object(server, "_controller_post")
    def test_unscoped_target_branch_only(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "job-x"}
        result = server.workstream_submit_task(
            prompt="Task", target_branch="feature/x")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()

class TestWorkstreamSubmitStaticTokenIsolation(unittest.TestCase):
    """Regression: a static-token (Claude.ai web chat / third-party API)
    request that lands on a thread previously used to handle an in-cluster
    HMAC-temp-token request must not inherit that prior request's
    workstream binding via the thread-local fallback in
    ``_get_token_workstream_id``.

    Before the fix, the auth middleware's static-token path set request
    scopes but never reset the thread-local workstream_id. A subsequent
    static-token request handled on the same worker thread would find
    the stale value via ``_thread_local.workstream_id`` (since the
    contextvar was unset for the new request) and the self-collision
    check would refuse a perfectly legitimate cross-workstream
    submission.
    """

    def setUp(self):
        _grant_all_scopes()
        # Simulate a previous HMAC-temp-token request (an in-cluster
        # Claude Code agent running on workstream "ws-prior") having
        # left thread-local state behind on this worker thread.
        server._thread_local.workstream_id = "ws-prior"
        server._thread_local.job_id = "job-prior"
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def tearDown(self):
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def test_static_token_path_clears_stale_thread_local(self):
        # Mirror what AuthMiddleware does for a matched static token.
        server._set_scopes(["read", "write", "submit"], label="static")
        server._set_workspace_scopes(None)
        server._set_token_context("", "")
        # The self-collision check uses _get_token_workstream_id();
        # after the middleware fix, it must not see the leaked binding.
        self.assertFalse(bool(server._get_token_workstream_id()))

    @patch.object(server, "_controller_post")
    def test_static_token_can_submit_to_previously_bound_workstream(
            self, mock_post):
        # Mirror what AuthMiddleware does for a matched static token.
        server._set_scopes(["read", "write", "submit"], label="static")
        server._set_workspace_scopes(None)
        server._set_token_context("", "")
        mock_post.return_value = {"ok": True, "jobId": "job-1"}
        # A static-token caller submitting to the workstream that was
        # bound to a previous temp-token request on this thread must
        # succeed — the caller has no checkout and cannot collide.
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-prior")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()

class TestCommitLanguageReadContext(unittest.TestCase):
    """The commit-sequencing linter must not reject reading history.

    "diff commit 123 against its parent" names an existing commit; it is not
    an instruction to produce commits. Rejecting it sent operators looking for
    the allow_commit_language escape hatch to do something legitimate.
    """

    # Deliberately free of any word the read-context exemption matches; an
    # exemption word hiding in the padding would make every case pass.
    PAD = (" Additional prompt text of sufficient length that the linter's "
           "minimum-length guard does not short-circuit the whole scan.")

    def _flagged(self, text):
        return bool(server._lint_prompt_for_commit_sequencing(text + self.PAD))

    def test_read_only_references_are_allowed(self):
        for text in (
            "diff commit 123 against its parent to find the regression",
            "Look at commit 42 to understand the bug",
            "Revert commit 7 and re-apply the change by hand",
            "Compare commit 9 with master and report what differs",
            "Reviewing commit 15 first",
        ):
            self.assertFalse(self._flagged(text), text)

    def test_numbered_plans_are_still_rejected(self):
        # The reason the rule exists. An exemption broad enough to cover this
        # would have removed the safety net rather than narrowed it.
        self.assertTrue(self._flagged(
            "Commit 1: add the parser. Commit 2: wire it up."))

    def test_instructions_to_create_commits_are_still_rejected(self):
        for text in (
            "Make commit 1 the parser change and stop there",
            "Split the work across 3 commits please",
            "Land this as separate commits for reviewability",
            "Your commit message should mention the ticket",
        ):
            self.assertTrue(self._flagged(text), text)
