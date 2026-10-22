#!/usr/bin/env python3
"""PreToolUse: keep a FlowTree job's work in the job's own working tree.

The FlowTree harness stages, commits and pushes the working tree it started the
job in, and nothing else. A job that does its work in another git worktree
leaves that tree untouched: the work is lost when the job ends and the job
still reports success. In an interactive session there is no harness to lose
anything, so worktrees are allowed there; this hook does nothing outside a job
(see ``agent_session.is_flowtree_job``).

In a job it blocks every route into another worktree that a tool call exposes:

  - ``EnterWorktree``, which creates a worktree and moves the session into it;
  - ``Agent`` / ``Task`` with ``isolation: "worktree"``, which runs a subagent
    in a fresh worktree whose changes never reach the job's tree;
  - ``Edit`` / ``Write`` / ``MultiEdit`` / ``NotebookEdit`` on a file inside
    another worktree of the same repository;
  - ``Bash`` run from inside another worktree, or that moves into one with
    ``cd`` / ``pushd`` or points git at one with ``git -C``.

``git worktree add`` itself is the job-only ``block-git-worktree`` policy in
``git_command_check.py``. "Another worktree" means a working tree whose
top level differs from the project's while sharing its git common directory,
so unrelated repositories and plain directories are not this hook's concern.

Usage (Claude Code adapter): ``python3 job_worktree_check.py --stdin``, with
the hook payload on stdin; exits 2 with the reason on stderr to block.
"""

import json
import os
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_session import is_flowtree_job  # noqa: E402

EDIT_TOOLS = frozenset({"Edit", "Write", "MultiEdit", "NotebookEdit"})
SUBAGENT_TOOLS = frozenset({"Agent", "Task"})
DIRECTORY_CHANGERS = frozenset({"cd", "pushd"})

REASON_PREAMBLE = (
    "BLOCKED: this FlowTree job may not work in another git worktree.\n"
    "The harness stages, commits and pushes only the job's own working tree ({project});\n"
    "work done anywhere else is lost when the job ends, while the job still reports success.\n"
)
REASON_ENTER = "EnterWorktree would move this session into a new worktree."
REASON_SUBAGENT = ("A subagent with isolation \"worktree\" works in a separate worktree. "
                   "Run it without isolation, in this tree.")
REASON_EDIT = "{path} is in the worktree {tree}. Make the change in this tree instead."
REASON_BASH_CWD = ("This command would run in the worktree {tree}. "
                   "Return to {project} first.")
REASON_BASH_TARGET = "'{target}' is in the worktree {tree}. Work in {project} instead."


def _git_paths(directory):
    """(toplevel, common_dir) of the work tree containing ``directory``, or None."""
    try:
        result = subprocess.run(
            ["git", "-C", directory, "rev-parse", "--show-toplevel", "--git-common-dir"],
            capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    lines = result.stdout.splitlines()
    if result.returncode != 0 or len(lines) < 2:
        return None
    toplevel = os.path.realpath(lines[0])
    common = lines[1]
    if not os.path.isabs(common):
        common = os.path.join(directory, common)
    return toplevel, os.path.realpath(common)


def _existing_directory(path):
    """The nearest existing directory at or above ``path``."""
    current = os.path.abspath(path)
    while not os.path.isdir(current):
        parent = os.path.dirname(current)
        if parent == current:
            return None
        current = parent
    return current


def other_worktree(path, project):
    """Top level of the other worktree of ``project``'s repository holding ``path``, else None."""
    if not path or not project:
        return None
    directory = _existing_directory(path if os.path.isabs(path) else os.path.join(project, path))
    if directory is None:
        return None
    own = _git_paths(project)
    there = _git_paths(directory)
    if own is None or there is None:
        return None
    if there[0] != own[0] and there[1] == own[1]:
        return there[0]
    return None


def _bash_targets(command):
    """Directories a command moves into (cd/pushd) or points git at (-C)."""
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        tokens = list(lexer)
    except ValueError:
        return []
    targets = []
    for i, token in enumerate(tokens[:-1]):
        following = tokens[i + 1]
        if token in DIRECTORY_CHANGERS and not following.startswith("-"):
            targets.append(following)
        elif token == "-C" and i > 0 and os.path.basename(tokens[i - 1]) == "git":
            targets.append(following)
    return targets


def _block(message, project):
    return {"action": "block", "reason": REASON_PREAMBLE.format(project=project) + message + "\n"}


def decide(payload, environ=None):
    """Return ``{"action": "allow"|"block", "reason": str}`` for a hook payload."""
    env = os.environ if environ is None else environ
    if not is_flowtree_job(env):
        return {"action": "allow", "reason": ""}
    tool = payload.get("tool_name") or ""
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd") if isinstance(payload.get("cwd"), str) else ""
    project = env.get("CLAUDE_PROJECT_DIR") or cwd

    if tool == "EnterWorktree":
        return _block(REASON_ENTER, project)
    if tool in SUBAGENT_TOOLS and tool_input.get("isolation") == "worktree":
        return _block(REASON_SUBAGENT, project)
    if tool in EDIT_TOOLS:
        path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        tree = other_worktree(path, project)
        if tree:
            return _block(REASON_EDIT.format(path=path, tree=tree), project)
    if tool == "Bash":
        tree = other_worktree(cwd, project)
        if tree:
            return _block(REASON_BASH_CWD.format(tree=tree, project=project), project)
        base = cwd or project
        for target in _bash_targets(tool_input.get("command") or ""):
            resolved = target if os.path.isabs(target) else os.path.join(base, target)
            tree = other_worktree(resolved, project)
            if tree:
                return _block(REASON_BASH_TARGET.format(target=target, tree=tree,
                                                        project=project), project)
    return {"action": "allow", "reason": ""}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv != ["--stdin"]:
        sys.stderr.write("usage: job_worktree_check.py --stdin\n")
        sys.exit(2)
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except ValueError:
        sys.exit(0)
    decision = decide(payload if isinstance(payload, dict) else {})
    if decision["action"] == "block":
        sys.stderr.write(decision["reason"])
        sys.exit(2)
    sys.exit(0)


if __name__ == "__main__":
    main()
