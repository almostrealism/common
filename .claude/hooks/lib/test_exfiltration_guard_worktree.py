#!/usr/bin/env python3
"""Exfiltration-guard tests for ``git -C`` into a linked worktree.

A linked worktree (``git worktree add``) shares the project's git common
directory, and with it the configuration and remotes a push is checked
against, so ``git -C`` into one is followed wherever the worktree lives. A
separate repository, nested in the work tree or outside it, is still refused.

Built on the shared :class:`GuardFixture` from test_exfiltration_guard_check.py.
CI runs this module via ``python3 -m unittest discover -p "test_exfiltration_guard_*.py"``.
"""
import os
import shutil
import tempfile

from test_exfiltration_guard_check import GuardFixture, _git


class LinkedWorktreeTests(GuardFixture):
    """``git -C`` into a worktree of the project's own repository."""

    def _worktree(self, path, branch):
        _git(self.root, "worktree", "add", "-q", "-b", branch, path)
        return path

    def test_git_directory_option_into_outside_worktree_allows(self):
        parent = os.path.realpath(tempfile.mkdtemp(prefix="exfil-worktree-"))
        self.addCleanup(shutil.rmtree, parent, True)
        tree = self._worktree(os.path.join(parent, "tree"), "side")
        self.assertAllowed(self.bash(f"git -C {tree} status"))
        self.assertAllowed(self.bash(f"git -C {tree} push origin side"))

    def test_git_directory_option_into_inside_worktree_allows(self):
        tree = self._worktree(os.path.join(self.root, ".claude", "worktrees", "wt"), "inner")
        self.assertAllowed(self.bash("git -C .claude/worktrees/wt status"))
        self.assertAllowed(self.bash(f"git -C {tree} push origin inner"))

    def test_worktree_push_still_checks_origin(self):
        """The worktree is followed, not trusted: a push from it is held to the
        same remote rules as one from the main tree."""
        parent = os.path.realpath(tempfile.mkdtemp(prefix="exfil-worktree-"))
        self.addCleanup(shutil.rmtree, parent, True)
        tree = self._worktree(os.path.join(parent, "tree"), "side")
        self.assertBlocked(self.bash(f"git -C {tree} push mirror side"), "only `origin`")

    def test_separate_clone_outside_tree_still_blocks(self):
        clone = os.path.join(self.outside_dir, "clone")
        _git(self.outside_dir, "init", "-q", "clone")
        self.assertBlocked(self.bash(f"git -C {clone} status"), "points at another repository")
