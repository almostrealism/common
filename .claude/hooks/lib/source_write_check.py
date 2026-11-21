#!/usr/bin/env python3
"""Apply the edit-time guards to source files a shell command modified.

The Write/Edit matchers cannot see a file written by a script. A command that
redirects into a source file, runs `sed -i` over one, copies one into place, or
drives an interpreter that writes one, changes the tree exactly as an edit does
and reaches no edit-scoped hook at all. This module closes that route by asking
git what actually changed, which is true regardless of how it changed.

Two kinds of check run over what it finds. The inline-comment policy judges each
file as it now stands and charges the change for every comment it wrote into.
The guards that inspect a whole file are not reimplemented: they are the same
scripts the edit path runs, handed the same payload for each file that changed,
so the two routes cannot enforce different things.

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

#: The edit-time guards that inspect a whole file, replayed here per file.
#: They filter by path themselves, so this list does not repeat their rules.
REPLAYED_GUARDS = ("scan-producer-violations.sh",
                   "warn-assertion-free-test.sh",
                   "warn-line-number-refs.sh",
                   "block-async-bypass.sh")

#: Most files to examine in one pass. A command that rewrites half the tree is
#: not what this exists to catch, and running every guard over all of it would
#: cost more than it finds. Truncation is reported rather than silent.
MAX_FILES = 40


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


def comment_findings(repo_root, path):
    """Return ``(text, findings)`` for the inline commentary a file has gained.

    The file is judged as it now stands, and a finding is charged when the
    change relative to HEAD wrote any line of the comment it sits in, so a
    line appended to a comment that was already there is answerable for the
    whole of it. A file git does not track yet is judged in full.

    A file that is no longer there, because the change deleted it, has no
    commentary to judge and yields no findings. Any other failure to read it
    propagates, so the caller reports the file as unchecked instead of
    passing it as clean.
    """
    try:
        with open(os.path.join(repo_root, path), encoding="utf-8",
                  errors="replace", newline="") as handle:
            text = handle.read()
    except FileNotFoundError:
        return "", []

    diff = _git(repo_root, "diff", "-U0", "HEAD", "--", path)
    tracked = _git(repo_root, "ls-files", "--", path)

    if diff is None or not (tracked or "").strip():
        added = None
    else:
        added = inline_comment_check.diff_added(diff).get(path, set())
        if not added:
            return text, []

    return text, inline_comment_check.violations(path, text, added)


def guard_reports(repo_root, path):
    """Return what the whole-file edit-time guards say about one file.

    Each is handed the payload it receives on the edit path, so whatever it
    would have said about a written file it says about a changed one. Both
    streams are read: a PostToolUse hook's own output reaches the model either
    way, so the guards do not agree on which one to use.
    """
    hooks_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    payload = json.dumps({"tool_input": {"file_path": path}})
    said = []

    for guard in REPLAYED_GUARDS:
        script = os.path.join(hooks_dir, guard)
        if not os.path.exists(script):
            continue

        try:
            done = subprocess.run(("bash", script), cwd=repo_root,
                                  input=payload, capture_output=True,
                                  text=True, timeout=30)
        except Exception:
            continue

        spoken = (done.stdout + done.stderr).strip()
        if spoken:
            said.append(spoken)

    return said


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


def _fresh(seen, kind, path, subject):
    """Return whether this finding is new, remembering it when it is."""
    mark = "%s:%s:%s" % (kind, path,
                         hashlib.sha1(subject.encode()).hexdigest()[:16])
    if mark in seen:
        return False

    seen.add(mark)
    return True


def unreadable_reason(path, error):
    """Return the report for a changed source file that could not be read.

    The policy was not applied to it, and saying nothing would read as a pass,
    so the file is named along with the error that stopped the check.
    """
    return ("%s changed but could not be read (%s), so the inline-comment "
            "policy was not checked against it. The edit, commit and CI gates "
            "refuse a file they cannot read; make it readable and check it."
            % (path, error))


def violations(repo_root):
    """Return what the guards say about each source file that changed."""
    if operation_in_progress(repo_root):
        return []

    seen = _reported(repo_root)
    found = []

    changed = changed_source_files(repo_root)
    examined = changed[:MAX_FILES]

    for path in examined:
        try:
            text, findings = comment_findings(repo_root, path)
        except OSError as error:
            if _fresh(seen, "unreadable", path, str(error)):
                found.append(unreadable_reason(path, error))
            continue

        if findings and _fresh(seen, "comments", path, text):
            found.append(inline_comment_check.block_reason(path, text, findings))

        for report in guard_reports(repo_root, path):
            if _fresh(seen, "guard", path, report):
                found.append(report)

    if len(changed) > len(examined):
        found.append(
            "%d further changed source files were not examined (limit %d): %s"
            % (len(changed) - len(examined), MAX_FILES,
               ", ".join(changed[len(examined):len(examined) + 5]) + " ..."))

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
                         "a source guard has findings to report.",
    }))

    return 0


if __name__ == "__main__":
    sys.exit(main())
