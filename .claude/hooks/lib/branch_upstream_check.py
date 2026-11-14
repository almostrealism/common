#!/usr/bin/env python3
"""Refuse every tool call while a non-master branch tracks a remote master/main.

``block-branch-track-master`` (git_command_check.py) refuses the commands that
would make a feature branch track master, but it only sees command text. The
state can still arise where no hook looks: a person runs
``git checkout -b feature origin/master`` in their own terminal, a script does
it, or a branch was created before the guard existed. On such a branch a bare
``git push`` goes to master. This check reads the repository state itself,
before every tool call, so the condition is caught however it came about.

While the working tree's branch is not master/main and its configured upstream
(``branch.<name>.merge`` on a real remote) is master/main, every tool call is
blocked except a Bash command made only of:

  - the fixes: ``git branch --unset-upstream``, ``git branch -u|--set-upstream-to
    <remote>/<not master>``, or ``git push -u|--set-upstream <remote> <branch>``;
  - read-only inspection: ``git status``, ``git branch`` listing forms,
    ``git rev-parse``, ``git config --get``, ``git remote -v``;
  - ``cd``.

So the session fixes the branch before it does anything else. A detached HEAD,
a branch with no upstream, a local (``.``) upstream, and a directory that is not
a git repository are all allowed: the check fails open on anything it cannot
read, because a hook that cannot tell must not stop all work.

Two CLI entry points, matching git_command_check.py:

  python3 branch_upstream_check.py --stdin
      Claude Code adapter. Reads the hook payload ({"tool_name", "tool_input",
      "cwd"}) from stdin; exit 2 with the reason on stderr to block.

  python3 branch_upstream_check.py <tool> <command> <cwd>
      opencode adapter. Prints the Decision as JSON; exit 0 always.
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from git_command_check import (  # noqa: E402
    PROTECTED_BRANCHES,
    _git_calls,
    _segments,
)

REASON = (
    "BLOCKED: branch {branch!r} tracks {upstream!r}.\n"
    "\n"
    "A plain `git push` from this branch would update {target!r} on the remote.\n"
    "Every tool call is refused until the branch stops tracking it. Fix it first:\n"
    "\n"
    "  git branch --unset-upstream                  (then `git push -u {remote} {branch}`\n"
    "                                                when you are ready to publish)\n"
    "  git push -u {remote} {branch}                (publishes and tracks {remote}/{branch})\n"
    "\n"
    "The usual cause is creating the branch from a remote ref without --no-track,\n"
    "e.g. `git checkout -b {branch} origin/master`. Create branches with\n"
    "`git checkout -b <name> --no-track origin/master` instead.\n"
)

# git subcommands that only read state; allowed while the branch is mistracked
# so the session can inspect what it is about to fix.
_READ_ONLY_SUBCOMMANDS = frozenset({"status", "rev-parse", "remote"})

# `git branch` arguments that make it list rather than change anything.
_BRANCH_LIST_ARGS = frozenset({
    "-v", "-vv", "--verbose", "-a", "--all", "-r", "--remotes", "-l", "--list",
    "--show-current", "--no-color", "--color",
})

_ALLOW = {"action": "allow", "reason": "", "context": "", "stderr": ""}


def _git(cwd, *args):
    """Run a read-only git command in ``cwd``; return stripped stdout or None."""
    try:
        result = subprocess.run(
            ["git", "-C", cwd, *args], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def mistracked_upstream(cwd):
    """Describe a non-master branch that tracks a remote master/main, else None.

    :param cwd: a directory inside the working tree to inspect
    :return: ``{"branch", "remote", "target", "upstream"}`` when the current
        branch is not master/main and its upstream is master/main on a real
        remote; ``None`` otherwise, including when git cannot be read
    """
    branch = _git(cwd, "symbolic-ref", "--quiet", "--short", "HEAD")
    if not branch or branch in PROTECTED_BRANCHES:
        return None
    remote = _git(cwd, "config", "--get", f"branch.{branch}.remote")
    merge = _git(cwd, "config", "--get", f"branch.{branch}.merge")
    if not remote or remote == "." or not merge:
        return None
    target = merge[len("refs/heads/"):] if merge.startswith("refs/heads/") else merge
    if target not in PROTECTED_BRANCHES:
        return None
    return {"branch": branch, "remote": remote, "target": target,
            "upstream": f"{remote}/{target}"}


def _is_fix_or_inspection(sub, args, state):
    """Whether one git call fixes the mistracked upstream or only reads state."""
    if sub in _READ_ONLY_SUBCOMMANDS:
        return sub != "remote" or all(a in ("-v", "--verbose") for a in args)
    if sub == "config":
        return bool(args) and args[0] in ("--get", "--get-all", "--list", "-l")
    if sub == "branch":
        if "--unset-upstream" in args:
            return True
        for k, token in enumerate(args):
            value = None
            if token in ("-u", "--set-upstream-to") and k + 1 < len(args):
                value = args[k + 1]
            elif token.startswith("--set-upstream-to="):
                value = token.split("=", 1)[1]
            if value is not None:
                return value.rsplit("/", 1)[-1] not in PROTECTED_BRANCHES
        return all(a in _BRANCH_LIST_ARGS for a in args)
    if sub == "push":
        if not any(a in ("-u", "--set-upstream") for a in args):
            return False
        positionals = [a for a in args if not a.startswith("-")]
        if len(positionals) != 2:
            return False
        dst = positionals[1].split(":", 1)[-1].lstrip("+")
        dst = dst[len("refs/heads/"):] if dst.startswith("refs/heads/") else dst
        return dst in (state["branch"], "HEAD")
    return False


def command_is_remedy(command, state):
    """Whether a Bash command consists only of fixes, inspection and ``cd``."""
    if not command or not command.strip():
        return False
    saw_git = False
    for segment in _segments(command):
        words = segment.split()
        if not words:
            continue
        if words[0] == "cd":
            continue
        calls = _git_calls(segment)
        if not calls or words[0].rsplit("/", 1)[-1] != "git":
            return False
        for sub, args in calls:
            if not _is_fix_or_inspection(sub, args, state):
                return False
        saw_git = True
    return saw_git


def decide(tool, command, cwd):
    """Return a Decision for one tool call made with working directory ``cwd``."""
    state = mistracked_upstream(cwd) if cwd else None
    if state is None:
        return dict(_ALLOW)
    if tool == "Bash" or tool == "bash":
        if command_is_remedy(command, state):
            return dict(_ALLOW)
    reason = REASON.format(**state)
    return {"action": "block", "reason": reason, "context": "", "stderr": reason}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        try:
            payload = json.loads(sys.stdin.read())
        except Exception:
            sys.exit(0)
        tool = payload.get("tool_name", "") or ""
        command = (payload.get("tool_input", {}) or {}).get("command", "") or ""
        cwd = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
        decision = decide(tool, command, cwd)
        if decision["action"] == "block":
            sys.stderr.write(decision["reason"])
            sys.exit(2)
        sys.exit(0)

    if len(argv) < 3:
        sys.stderr.write("usage: branch_upstream_check.py <tool> <command> <cwd>\n")
        sys.exit(2)
    print(json.dumps(decide(argv[0], argv[1], argv[2])))
    sys.exit(0)


if __name__ == "__main__":
    main()
