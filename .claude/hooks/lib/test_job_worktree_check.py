#!/usr/bin/env python3
"""Tests for job_worktree_check.py and agent_session.py.

Run from the repo root:

    python3 -m unittest discover -s .claude/hooks/lib -p "test_job_worktree_check.py" -v

The worktree cases build a scratch repository with a real linked worktree, so
the hook's git queries run exactly as they do in a session.
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "job_worktree_check.py")
sys.path.insert(0, HERE)

import agent_session  # noqa: E402


def _load_core():
    spec = importlib.util.spec_from_file_location("job_worktree_check", CORE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _git(cwd, *args):
    subprocess.run(["git", "-C", cwd, *args], check=True, capture_output=True, text=True)


class AgentSessionTests(unittest.TestCase):
    """How a hook tells a FlowTree job from an interactive session."""

    def test_marker_marks_a_job(self):
        self.assertTrue(agent_session.is_flowtree_job({"AR_FLOWTREE_JOB": "1"}))

    def test_legacy_workstream_url_marks_a_job(self):
        self.assertTrue(agent_session.is_flowtree_job(
            {"AR_WORKSTREAM_URL": "http://controller/api/workstreams/w1"}))

    def test_interactive_session_is_not_a_job(self):
        self.assertFalse(agent_session.is_flowtree_job({"HOME": "/home/dev"}))
        self.assertFalse(agent_session.is_flowtree_job({"AR_FLOWTREE_JOB": "  "}))


class JobWorktreeTests(unittest.TestCase):
    """job_worktree_check.decide against a repository with a linked worktree."""

    @classmethod
    def setUpClass(cls):
        cls.core = _load_core()
        cls.root = os.path.realpath(tempfile.mkdtemp(prefix="job-worktree-"))
        cls.project = os.path.join(cls.root, "project")
        cls.tree = os.path.join(cls.root, "other-tree")
        cls.unrelated = os.path.join(cls.root, "unrelated")
        for repo in (cls.project, cls.unrelated):
            os.makedirs(repo)
            _git(repo, "init", "-q")
            _git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com",
                 "commit", "-q", "--allow-empty", "-m", "init")
        _git(cls.project, "worktree", "add", "-q", "-b", "side", cls.tree)
        cls.job = {"AR_FLOWTREE_JOB": "1", "CLAUDE_PROJECT_DIR": cls.project}
        cls.interactive = {"CLAUDE_PROJECT_DIR": cls.project}

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.root, ignore_errors=True)

    def _decide(self, tool, tool_input, cwd=None, env=None):
        payload = {"tool_name": tool, "tool_input": tool_input, "cwd": cwd or self.project}
        return self.core.decide(payload, env if env is not None else self.job)

    def assertBlocked(self, decision):
        self.assertEqual(decision["action"], "block", decision)
        self.assertIn("FlowTree job", decision["reason"])

    def assertAllowed(self, decision):
        self.assertEqual(decision["action"], "allow", decision)

    def test_enter_worktree_blocked_in_job(self):
        self.assertBlocked(self._decide("EnterWorktree", {}))

    def test_worktree_isolated_subagent_blocked_in_job(self):
        for tool in ("Agent", "Task"):
            self.assertBlocked(self._decide(tool, {"prompt": "x", "isolation": "worktree"}))

    def test_subagent_without_isolation_allowed_in_job(self):
        self.assertAllowed(self._decide("Agent", {"prompt": "x"}))

    def test_edit_in_other_worktree_blocked_in_job(self):
        for tool in ("Edit", "Write", "MultiEdit"):
            decision = self._decide(tool, {"file_path": os.path.join(self.tree, "a.txt")})
            self.assertBlocked(decision)
            self.assertIn(self.tree, decision["reason"])
        self.assertBlocked(self._decide(
            "NotebookEdit", {"notebook_path": os.path.join(self.tree, "n.ipynb")}))

    def test_edit_in_new_directory_of_other_worktree_blocked_in_job(self):
        path = os.path.join(self.tree, "not", "yet", "there.txt")
        self.assertBlocked(self._decide("Write", {"file_path": path}))

    def test_edit_in_own_tree_allowed_in_job(self):
        self.assertAllowed(self._decide("Edit", {"file_path": os.path.join(self.project, "a.txt")}))
        self.assertAllowed(self._decide("Write", {"file_path": "relative/b.txt"}))

    def test_edit_outside_the_repository_allowed_in_job(self):
        self.assertAllowed(self._decide("Write", {"file_path": os.path.join(self.unrelated, "c")}))
        self.assertAllowed(self._decide("Write", {"file_path": os.path.join(self.root, "d")}))

    def test_bash_from_other_worktree_blocked_in_job(self):
        self.assertBlocked(self._decide("Bash", {"command": "git status"}, cwd=self.tree))

    def test_bash_moving_into_other_worktree_blocked_in_job(self):
        for command in (f"cd {self.tree} && echo x > a.txt",
                        f"pushd {self.tree}; make",
                        f"git -C {self.tree} add a.txt",
                        "cd ../other-tree && git add -A"):
            self.assertBlocked(self._decide("Bash", {"command": command}))

    def test_bash_in_own_tree_allowed_in_job(self):
        for command in ("git status", f"cd {self.project}/sub && ls",
                        f"git -C {self.unrelated} status", "git worktree list"):
            self.assertAllowed(self._decide("Bash", {"command": command}))

    def test_interactive_session_unaffected(self):
        env = self.interactive
        self.assertAllowed(self._decide("EnterWorktree", {}, env=env))
        self.assertAllowed(self._decide("Agent", {"prompt": "x", "isolation": "worktree"}, env=env))
        self.assertAllowed(self._decide(
            "Edit", {"file_path": os.path.join(self.tree, "a.txt")}, env=env))
        self.assertAllowed(self._decide("Bash", {"command": "git status"}, cwd=self.tree, env=env))

    def test_stdin_adapter_exits_2_on_block(self):
        payload = json.dumps({"tool_name": "EnterWorktree", "tool_input": {}, "cwd": self.project})
        env = dict(os.environ, **self.job)
        result = subprocess.run([sys.executable, CORE_PATH, "--stdin"], input=payload,
                                capture_output=True, text=True, timeout=10, env=env)
        self.assertEqual(result.returncode, 2)
        self.assertIn("EnterWorktree", result.stderr)

    def test_stdin_adapter_exits_0_outside_a_job(self):
        payload = json.dumps({"tool_name": "EnterWorktree", "tool_input": {}, "cwd": self.project})
        env = {k: v for k, v in os.environ.items()
               if k not in ("AR_FLOWTREE_JOB", "AR_WORKSTREAM_URL")}
        result = subprocess.run([sys.executable, CORE_PATH, "--stdin"], input=payload,
                                capture_output=True, text=True, timeout=10, env=env)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
