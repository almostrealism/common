#!/usr/bin/env python3
"""Exfiltration-guard tests for pushing a repository pinned in ``[git-remotes]``.

``git push origin`` from a clone of a repository other than the project's
own is allowed only when the allowlist pins that repository in
``[git-remotes]``, exactly (``host/owner/repo``) or by owner
(``host/owner/``). A bare host entry and a ``[lab-hosts]`` entry pin
nothing. Entries and remote URLs are compared case-insensitively.

Built on the shared :class:`GuardFixture` from test_exfiltration_guard_check.py.
CI runs this module via ``python3 -m unittest discover -p "test_exfiltration_guard_*.py"``.
"""
import os
import unittest

from test_exfiltration_guard_check import GuardFixture, _git


class PinnedSiblingPushTests(GuardFixture):
    """``git push origin`` from a clone of another repository."""

    def _commit_allowlist(self, git_remotes, lab_hosts=("localhost",)):
        """Commit an allowlist with the given sections; the guard reads the
        allowlist from HEAD."""
        text = ("[lab-hosts]\n" + "".join(e + "\n" for e in lab_hosts)
                + "[git-remotes]\n" + "".join(e + "\n" for e in git_remotes))
        self._write(".claude/hooks/exfil-allowlist.txt", text)
        _git(self.root, "add", ".claude/hooks/exfil-allowlist.txt")
        _git(self.root, "commit", "-q", "-m", "allowlist")

    def _sibling_clone(self, owner_repo):
        other = os.path.join(self.outside_dir, "sibling")
        os.makedirs(other)
        _git(other, "init", "-q", "-b", "main")
        _git(other, "remote", "add", "origin", f"git@github.com:{owner_repo}.git")
        return other

    def test_git_push_from_clone_pinned_by_owner_allows(self):
        self._commit_allowlist(["github.com/almostrealism/", "github.com/almostrealism/common"])
        other = self._sibling_clone("almostrealism/sibling")
        self.assertAllowed(self.bash(f"cd {other} && git push origin HEAD"))

    def test_git_push_from_clone_pinned_exactly_allows(self):
        self._commit_allowlist(["github.com/almostrealism/common", "github.com/almostrealism/sibling"])
        other = self._sibling_clone("almostrealism/sibling")
        self.assertAllowed(self.bash(f"cd {other} && git push origin HEAD"))

    def test_git_push_from_clone_pinned_in_other_case_allows(self):
        self._commit_allowlist(["github.com/AlmostRealism/", "github.com/almostrealism/common"])
        other = self._sibling_clone("almostRealism/Sibling")
        self.assertAllowed(self.bash(f"cd {other} && git push origin HEAD"))

    def test_git_push_from_clone_of_unpinned_owner_blocks(self):
        self._commit_allowlist(["github.com/almostrealism/", "github.com/almostrealism/common"])
        other = self._sibling_clone("someone-else/dump")
        self.assertBlocked(self.bash(f"cd {other} && git push origin HEAD"),
                           "pushing another repository is denied", "not pinned in [git-remotes]")

    def test_git_push_from_clone_with_only_bare_host_blocks(self):
        self._commit_allowlist(["github.com"])
        other = self._sibling_clone("almostrealism/sibling")
        self.assertBlocked(self.bash(f"cd {other} && git push origin HEAD"),
                           "pushing another repository is denied")

    def test_git_push_from_clone_on_lab_host_only_blocks(self):
        """A ``[lab-hosts]`` entry makes a host reachable but pins no
        repository, so it never permits pushing another repository."""
        self._commit_allowlist(["github.com/almostrealism/common"], lab_hosts=("localhost", "github.com"))
        other = self._sibling_clone("almostrealism/sibling")
        self.assertBlocked(self.bash(f"cd {other} && git push origin HEAD"),
                           "pushing another repository is denied", "not pinned in [git-remotes]")


if __name__ == "__main__":
    unittest.main()
