#!/usr/bin/env python3
"""Unit tests for branch_upstream_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_branch_upstream_check.py -v

Each test builds a real repository in a temporary directory, with a bare
repository as its ``origin``, so the check reads genuine git configuration
rather than a mock of it. The cases cover the state that must block (a
feature branch tracking origin/master or origin/main), every state that
must not (master itself, no upstream, an upstream of its own name, a local
upstream, detached HEAD, not a repository), the remedies and inspection
commands allowed while blocked, and both CLI contracts.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import branch_upstream_check as check  # noqa: E402

CORE_PATH = os.path.join(HERE, "branch_upstream_check.py")


def git(cwd, *args):
    """Run git in ``cwd`` with a fixed identity, failing the test on error."""
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.com",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.com")
    subprocess.run(["git", "-C", cwd, *args], check=True, capture_output=True, env=env)


class RepoTestCase(unittest.TestCase):
    """A working clone of a bare ``origin`` with one commit on ``master``."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        self.origin = os.path.join(root, "origin.git")
        self.work = os.path.join(root, "work")
        subprocess.run(["git", "init", "-q", "--bare", "-b", "master", self.origin], check=True)
        subprocess.run(["git", "init", "-q", "-b", "master", self.work], check=True)
        git(self.work, "commit", "-q", "--allow-empty", "-m", "base")
        git(self.work, "remote", "add", "origin", self.origin)
        git(self.work, "push", "-q", "-u", "origin", "master")

    def tearDown(self):
        self.tmp.cleanup()

    def mistrack(self, branch="feature/x", target="master"):
        """Check out ``branch`` tracking ``origin/<target>``, the footgun state."""
        if target != "master":
            git(self.work, "push", "-q", "origin", f"master:{target}")
            git(self.work, "fetch", "-q", "origin")
        git(self.work, "checkout", "-q", "-b", branch, f"origin/{target}")


class MistrackedStateTest(RepoTestCase):
    """When the branch must block, and every state in which it must not."""

    def test_feature_branch_tracking_master_blocks_every_tool(self):
        self.mistrack()
        for tool, command in (("Read", ""), ("Edit", ""), ("Bash", "ls"),
                              ("Bash", "git push"), ("mcp__x__y", "")):
            decision = check.decide(tool, command, self.work)
            self.assertEqual("block", decision["action"], (tool, command))
            self.assertIn("feature/x", decision["reason"])
            self.assertIn("origin/master", decision["reason"])

    def test_tracking_main_blocks(self):
        self.mistrack(target="main")
        self.assertEqual("block", check.decide("Read", "", self.work)["action"])

    def test_master_tracking_master_is_allowed(self):
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])

    def test_branch_without_upstream_is_allowed(self):
        git(self.work, "checkout", "-q", "-b", "feature/y", "--no-track", "origin/master")
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])

    def test_branch_tracking_its_own_name_is_allowed(self):
        git(self.work, "checkout", "-q", "-b", "feature/z")
        git(self.work, "push", "-q", "-u", "origin", "feature/z")
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])

    def test_local_upstream_is_allowed(self):
        git(self.work, "checkout", "-q", "--track", "-b", "feature/l", "master")
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])

    def test_detached_head_is_allowed(self):
        git(self.work, "checkout", "-q", "--detach")
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])

    def test_not_a_repository_is_allowed(self):
        self.assertEqual("allow", check.decide("Read", "", self.tmp.name)["action"])


class RemedyTest(RepoTestCase):
    """What a blocked session may still run, and what it may not."""

    def setUp(self):
        super().setUp()
        self.mistrack()

    def assertAllowed(self, command):
        self.assertEqual("allow", check.decide("Bash", command, self.work)["action"], command)

    def assertBlocked(self, command):
        self.assertEqual("block", check.decide("Bash", command, self.work)["action"], command)

    def test_fixes_are_allowed(self):
        self.assertAllowed("git branch --unset-upstream")
        self.assertAllowed("cd /repo && git branch --unset-upstream")
        self.assertAllowed("git push -u origin feature/x")
        self.assertAllowed("git push --set-upstream origin HEAD")
        self.assertAllowed("git branch -u origin/feature/x")
        self.assertAllowed("git branch --set-upstream-to=origin/feature/x")

    def test_inspection_is_allowed(self):
        self.assertAllowed("git status")
        self.assertAllowed("git branch -vv")
        self.assertAllowed("git branch --show-current")
        self.assertAllowed("git rev-parse --abbrev-ref @{u}")
        self.assertAllowed("git config --get branch.feature/x.merge")
        self.assertAllowed("git remote -v")
        self.assertAllowed("git status && git branch --unset-upstream && git branch -vv")

    def test_anything_else_is_blocked(self):
        self.assertBlocked("git push")
        self.assertBlocked("git push origin")
        self.assertBlocked("git push -u origin master")
        self.assertBlocked("git push -u origin feature/x:master")
        self.assertBlocked("git branch -u origin/master")
        self.assertBlocked("git branch --set-upstream-to=origin/main")
        self.assertBlocked("git commit -m x")
        self.assertBlocked("git status && rm -rf build")
        self.assertBlocked("git remote add other /tmp/x")
        self.assertBlocked("git config branch.feature/x.merge refs/heads/x")
        self.assertBlocked("git branch new-branch")
        self.assertBlocked("cd /repo")
        self.assertBlocked("")

    def test_substitution_and_redirection_are_blocked(self):
        """A remedy word list says nothing about what the shell runs or writes
        around it: a substitution runs a command of its own, and a redirection
        writes or reads a file. No remedy needs either."""
        self.assertBlocked("git status $(touch /tmp/escaped)")
        self.assertBlocked("git status ${ touch /tmp/escaped; git branch --unset-upstream }")
        self.assertBlocked("git status ${| touch /tmp/escaped; }")
        self.assertBlocked("git status `touch /tmp/escaped`")
        self.assertBlocked("git config --get x <(cat /tmp/secret)")
        self.assertBlocked("git status >(sh)")
        self.assertBlocked("git status > /tmp/escaped")
        self.assertBlocked("git status >> /tmp/escaped")
        self.assertBlocked("git status < /tmp/input")
        self.assertBlocked("git branch -vv 2>/tmp/escaped")
        self.assertBlocked("git branch --unset-upstream <<EOF\nx\nEOF")

    def test_git_options_that_run_commands_are_blocked(self):
        """Configuration given on the command line can name a command for git
        to run, so a read-only subcommand is no longer read-only with it."""
        self.assertBlocked("git -c core.fsmonitor='touch /tmp/escaped' status")
        self.assertBlocked("git -c core.pager=sh branch -vv")
        self.assertBlocked("git -C /repo -c core.fsmonitor=sh status")
        self.assertBlocked("git --config-env=core.fsmonitor=EVIL status")
        self.assertBlocked("git --exec-path=/tmp/evil branch --unset-upstream")
        self.assertBlocked("git status && git -c alias.x=!sh branch --unset-upstream")
        self.assertAllowed("git -C /repo status")
        self.assertAllowed("git -C /repo branch --unset-upstream")
        self.assertAllowed("git branch --color -vv")

    def test_help_option_launches_a_viewer_and_is_blocked(self):
        """``--help`` on any subcommand makes git launch the external
        man/help viewer, so a command carrying it is not read-only inspection
        however safe the subcommand itself would be."""
        self.assertBlocked("git status --help")
        self.assertBlocked("git branch --help")
        self.assertBlocked("git --help")
        self.assertBlocked("git -C /repo status --help")
        self.assertBlocked("git status && git branch --help")
        self.assertAllowed("git status")
        self.assertAllowed("git branch -vv")

    def test_push_options_that_run_commands_are_blocked(self):
        """``git push`` options such as ``--receive-pack``/``--exec`` name a
        command for git to run, so a set-upstream push carrying one is not a
        remedy; only ``-u``/``--set-upstream`` may ride along."""
        self.assertBlocked("git push -u --receive-pack=/tmp/evil origin feature/x")
        self.assertBlocked("git push -u --exec=/tmp/evil origin feature/x")
        self.assertBlocked("git push --receive-pack=/tmp/evil -u origin feature/x")
        self.assertBlocked("git push --set-upstream --receive-pack sh origin feature/x")
        self.assertAllowed("git push -u origin feature/x")
        self.assertAllowed("git push --set-upstream origin feature/x")

    def test_a_second_line_is_held_to_the_same_rule(self):
        self.assertBlocked("git status\nrm -rf build")
        self.assertAllowed("git status\ngit branch --unset-upstream")

    def test_unsetting_the_upstream_clears_the_block(self):
        git(self.work, "branch", "--unset-upstream")
        self.assertEqual("allow", check.decide("Read", "", self.work)["action"])


class CliContractTest(RepoTestCase):
    """The --stdin (Claude Code) and argv (opencode) entry points."""

    def setUp(self):
        super().setUp()
        self.mistrack()

    def run_stdin(self, payload):
        return subprocess.run([sys.executable, CORE_PATH, "--stdin"],
                              input=json.dumps(payload), capture_output=True, text=True)

    def test_stdin_blocks_with_exit_2(self):
        result = self.run_stdin({"tool_name": "Read", "tool_input": {}, "cwd": self.work})
        self.assertEqual(2, result.returncode)
        self.assertIn("BLOCKED", result.stderr)

    def test_stdin_allows_a_remedy(self):
        result = self.run_stdin({"tool_name": "Bash", "cwd": self.work,
                                 "tool_input": {"command": "git branch --unset-upstream"}})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_stdin_fails_open_on_bad_payload(self):
        result = subprocess.run([sys.executable, CORE_PATH, "--stdin"],
                                input="not json", capture_output=True, text=True)
        self.assertEqual(0, result.returncode)

    def test_registered_wrapper_blocks(self):
        wrapper = os.path.join(os.path.dirname(HERE), "require-own-upstream.sh")
        result = subprocess.run([wrapper], capture_output=True, text=True, input=json.dumps(
            {"tool_name": "Read", "tool_input": {}, "cwd": self.work}))
        self.assertEqual(2, result.returncode)
        self.assertIn("feature/x", result.stderr)

    def test_argv_prints_decision_json(self):
        result = subprocess.run([sys.executable, CORE_PATH, "read", "", self.work],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode)
        self.assertEqual("block", json.loads(result.stdout)["action"])


if __name__ == "__main__":
    unittest.main()
