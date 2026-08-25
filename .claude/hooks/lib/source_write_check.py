#!/usr/bin/env python3
"""Apply the inline-comment policy to source files a shell command modified.

The Write/Edit matchers cannot see a file written by a script. A command that
redirects into a source file, runs `sed -i` over one, copies one into place, or
drives an interpreter that writes one, changes the tree exactly as an edit does
and reaches no edit-scoped hook at all. This module closes that route by asking
git what actually changed, which is true regardless of how it changed.

It reports each offending file once per distinct state, so a violation left
standing does not repeat after every subsequent command, while a new one is
always reported.

Usage:

    python3 source_write_check.py [repo_root]

Prints a report for each newly offending file and exits 0 either way: this runs
after the fact, so it informs rather than blocks.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile

import inline_comment_check


#: Where the already-reported states are remembered between calls.
STATE_DIR = os.path.join(tempfile.gettempdir(), "ar-source-write-check")


def _git(repo_root, *args):
    """Run a git command in the repository, returning stdout or None."""
    try:
        done = subprocess.run(("git",) + args, cwd=repo_root,
                              capture_output=True, text=True, timeout=20)
    except Exception:
        return None

    if done.returncode != 0:
        return None

    return done.stdout


#: Files whose presence means an operation in progress owns the working tree.
IN_PROGRESS_MARKERS = ("MERGE_HEAD", "REVERT_HEAD", "CHERRY_PICK_HEAD",
                       "rebase-merge", "rebase-apply")


def operation_in_progress(repo_root):
    """Return whether a merge, rebase, revert or cherry-pick is under way.

    While one is, the difference from HEAD is the whole incoming changeset
    rather than anything written here, so measuring it would report other
    people's committed work as though this session had just produced it.
    """
    git_dir = _git(repo_root, "rev-parse", "--git-dir")
    if git_dir is None:
        return False

    git_dir = os.path.join(repo_root, git_dir.strip())
    return any(os.path.exists(os.path.join(git_dir, marker))
               for marker in IN_PROGRESS_MARKERS)


def changed_source_files(repo_root):
    """Return the source files that differ from HEAD, tracked or not."""
    listed = _git(repo_root, "diff", "--name-only", "HEAD")
    if listed is None:
        return []

    untracked = _git(repo_root, "ls-files", "--others",
                     "--exclude-standard") or ""

    paths = set(listed.split("\n")) | set(untracked.split("\n"))
    return sorted(p for p in paths if p and inline_comment_check.governs(p))


def added_lines(repo_root, path):
    """Return the text a file has gained relative to HEAD."""
    diff = _git(repo_root, "diff", "-U0", "HEAD", "--", path)

    if diff is None:
        try:
            with open(os.path.join(repo_root, path)) as handle:
                return handle.read()
        except OSError:
            return ""

    added = [line[1:] for line in diff.split("\n")
             if line.startswith("+") and not line.startswith("+++")]
    return "\n".join(added)


def _state_path(repo_root):
    """Return the file remembering what has already been reported."""
    key = hashlib.sha1(os.path.abspath(repo_root).encode()).hexdigest()[:16]
    return os.path.join(STATE_DIR, key)


def _reported(repo_root):
    """Return the set of file states already reported."""
    try:
        with open(_state_path(repo_root)) as handle:
            return set(handle.read().split("\n"))
    except OSError:
        return set()


def _remember(repo_root, seen):
    """Record the file states that have now been reported."""
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(_state_path(repo_root), "w") as handle:
            handle.write("\n".join(sorted(seen)))
    except OSError:
        pass


def violations(repo_root):
    """Return a report for each source file newly in breach of the policy."""
    if operation_in_progress(repo_root):
        return []

    seen = _reported(repo_root)
    found = []

    for path in changed_source_files(repo_root):
        text = added_lines(repo_root, path)
        reason = inline_comment_check.decide(path, [text])
        if reason is None:
            continue

        mark = path + ":" + hashlib.sha1(text.encode()).hexdigest()[:16]
        if mark in seen:
            continue

        seen.add(mark)
        found.append(reason)

    if found:
        _remember(repo_root, seen)

    return found


#: Appended to every report, naming the route that reached the file.
ROUTE_NOTE = (
    "This was reached by a shell command rather than an edit, so the guard on "
    "the edit tools did not see it. Use Edit or Write for source changes; a "
    "script that writes them opts out of every check the project has.\n")


def main(argv=None):
    """Report any source file a command left in breach of the policy.

    The report is emitted as the harness-native JSON a hook uses to add
    context, because plain stdout from a PostToolUse hook does not reach the
    model — a hook that runs but says nothing is no better than no hook.
    """
    argv = list(sys.argv[1:] if argv is None else argv)
    repo_root = argv[0] if argv else os.getcwd()

    found = violations(repo_root)
    if not found:
        return 0

    report = "\n".join(reason + "\n" + ROUTE_NOTE for reason in found)
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": report,
        },
        "systemMessage": "Source file modified outside the edit tools; "
                         "inline-comment policy exceeded.",
    }))

    return 0


if __name__ == "__main__":
    sys.exit(main())
