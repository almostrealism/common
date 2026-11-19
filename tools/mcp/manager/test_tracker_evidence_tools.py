"""Tests for adding evidence to an existing open task.

``tracker_append_task_evidence`` lets any caller — an agent job with or without
the ``planner`` role, or a person — add a section to the end of an open task's
description in its workspace. It is append-only: the existing text is kept
exactly, the new section is attributed to the calling job's token, and the
write sends the description and nothing else. It is how a round that finds a
problem an open task already tracks adds to that task instead of filing a
duplicate.
"""

import os
import re
import sys
import time
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server,
    _grant_all_scopes,
    _set_workspaces,
    _clear_workspaces,
    _reset_workspace_cache,
)

import tracker_tools  # noqa: E402

_EXISTING = "Flaky on the Metal lane.\n\nFirst seen in an earlier round."


def _task(status="open", workspace="W1", description=_EXISTING):
    """A stored task dict as the tracker returns it, filed by another job and
    linked to no workstream."""
    return {"id": "t1", "title": "DifferentialAttentionTest#lambdaReparameterization flakes",
            "description": description, "status": status, "stage": "backlog",
            "priority": 0, "workstream_id": None, "source": "follow-up:ws-9/job-9",
            "workspace_id": workspace}


class TestAppendTaskEvidence(unittest.TestCase):
    """An agent without the planner role appends to an open task it does not
    hold, within its own workspace, and changes nothing but the description."""

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")
        # ws-1 holds no tracker role.
        server._tracker_capability_cache["map"] = {}
        server._tracker_capability_cache["fetched"] = time.monotonic()
        controller = patch.object(server, "_controller_get")
        mock_controller = controller.start()
        self.addCleanup(controller.stop)
        # ws-1 lives in workspace W1 (the caller's), ws-2 in W2 (another).
        mock_controller.return_value = [
            {"workstreamId": "ws-1", "workspaceId": "W1"},
            {"workstreamId": "ws-2", "workspaceId": "W2"},
        ]
        _reset_workspace_cache()
        _set_workspaces("W1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._tracker_capability_cache["map"] = None
        _clear_workspaces()
        _reset_workspace_cache()

    def _append(self, task, evidence="Ran it ten times: 3 failures, 7 passes."):
        """Call the tool against *task*, returning the result and the PUT mock."""
        with patch.object(server, "_tracker_get") as mock_get, \
                patch.object(server, "_tracker_put") as mock_put:
            mock_get.return_value = {"ok": True, "task": task}
            mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
            result = server.tracker_append_task_evidence("t1", evidence)
        return result, mock_put

    def test_appending_keeps_the_old_text_and_adds_an_attributed_section(self):
        result, mock_put = self._append(_task())
        self.assertTrue(result["ok"])
        self.assertEqual("/v1/tasks/t1", mock_put.call_args.args[0])
        description = mock_put.call_args.args[1]["description"]
        self.assertTrue(description.startswith(_EXISTING + "\n\n"))
        section = description[len(_EXISTING):]
        self.assertRegex(section, r"### Evidence added by follow-up:ws-1/job-1 on "
                                  r"\d{4}-\d{2}-\d{2}\n\n")
        self.assertTrue(description.endswith("Ran it ten times: 3 failures, 7 passes."))

    def test_only_the_description_is_sent(self):
        _, mock_put = self._append(_task())
        self.assertEqual(["description"], list(mock_put.call_args.args[1].keys()))

    def test_a_closed_task_is_refused(self):
        result, mock_put = self._append(_task(status="closed"))
        self.assertFalse(result["ok"])
        self.assertIn("closed", result["error"])
        mock_put.assert_not_called()

    def test_a_task_in_another_workspace_is_refused(self):
        with self.assertRaises(PermissionError):
            self._append(_task(workspace="W2"))

    def test_a_description_over_the_limit_is_refused_without_truncation(self):
        long_text = "x" * (tracker_tools._DESCRIPTION_MAX - 10)
        result, mock_put = self._append(_task(description=long_text))
        self.assertFalse(result["ok"])
        self.assertIn(str(tracker_tools._DESCRIPTION_MAX), result["error"])
        mock_put.assert_not_called()

    def test_blank_evidence_is_refused(self):
        result, mock_put = self._append(_task(), evidence="  \n")
        self.assertFalse(result["ok"])
        mock_put.assert_not_called()

    def test_a_task_without_a_description_gets_only_the_section(self):
        _, mock_put = self._append(_task(description=None))
        description = mock_put.call_args.args[1]["description"]
        self.assertTrue(re.match(r"### Evidence added by follow-up:ws-1/job-1 on ", description))

    def test_a_person_is_attributed_as_a_person(self):
        server._set_token_context(workstream_id=None, job_id=None)
        _clear_workspaces()
        _, mock_put = self._append(_task())
        description = mock_put.call_args.args[1]["description"]
        self.assertIn("### Evidence added by person on ", description)

    def test_a_trailing_newline_in_the_existing_description_is_kept(self):
        existing = "Already noted.\n\n"
        _, mock_put = self._append(_task(description=existing))
        description = mock_put.call_args.args[1]["description"]
        self.assertEqual(existing, description[:len(existing)])
        self.assertIn("### Evidence added by follow-up:ws-1/job-1 on ", description)

    def test_a_workstream_without_a_job_is_refused(self):
        server._set_token_context(workstream_id="ws-1", job_id=None)
        result, mock_put = self._append(_task())
        self.assertFalse(result["ok"])
        self.assertIn("job", result["error"])
        mock_put.assert_not_called()

    def test_a_job_without_a_workstream_is_refused(self):
        server._set_token_context(workstream_id=None, job_id="job-1")
        _clear_workspaces()
        result, mock_put = self._append(_task())
        self.assertFalse(result["ok"])
        self.assertIn("workstream", result["error"])
        mock_put.assert_not_called()


if __name__ == "__main__":
    unittest.main()
