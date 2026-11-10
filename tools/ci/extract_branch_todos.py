#!/usr/bin/env python3
"""Extract the TODO lines a branch adds relative to its merge base.

CI puts this list in the auto-review and auto-resolve prompts the same way it
passes review feedback, so an agent treats each TODO the branch introduced —
including the ``TODO(review)`` notes a review pass leaves — as a finding to fix
and remove or to turn into a tracker task.

Only actionable TODO *markers* are listed — the ``// TODO`` / ``# TODO:`` /
``TODO(review):`` forms that annotate code — not the word "TODO" used in prose
(documentation, prompt text, or a rule that describes the TODO convention
itself), which would otherwise flood the findings with references to the
mechanism rather than real work. See :data:`_MARKER` for the exact rule.

The list is computed from ``git diff --merge-base origin/<base> -M`` so that:

* only lines the branch **adds** appear — a ``TODO`` already on the base branch
  is unchanged context in the diff and is never listed;
* a renamed file's added TODOs are attributed to its new path (``-M`` detects
  the rename, and the diff reports added lines under the new name).

The parsing is factored out of the git call (:func:`extract_added_todos`) so it
can be exercised on a literal diff without a repository.

Usage::

    extract_branch_todos.py <base-branch> <output-file>

Writes a Markdown section to ``<output-file>`` (a "none" line when the branch
adds no TODOs). Exits 0 whether or not any were found; a git failure is a
warning and an empty section, so a prompt build never fails on it.
"""

import re
import subprocess
import sys

# A hunk header: @@ -<old>[,<n>] +<new>[,<m>] @@ ; captures the new-file start.
_HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)")
# An actionable TODO *marker*, told apart from the word "TODO" written in prose
# (documentation, prompt text, a rule that describes the TODO convention). A
# line carries a marker when either:
#   * a comment opener (``//``, ``/*``, ``<!--``, ``#``, or a leading
#     javadoc-continuation ``*``) is immediately followed by the TODO token —
#     the ``// TODO`` / ``# TODO:`` / ``* TODO`` forms that actually annotate
#     code; or
#   * the token is written as an explicit ``TODO:`` / ``TODO(owner):`` marker,
#     so a standalone marker in a non-comment file is still caught.
# Prose that merely mentions TODO ("every TODO the branch adds", the
# ``TODO(review)`` convention) has an ordinary word before the token and no
# trailing colon, so it is left out instead of being reported as a finding.
_MARKER = re.compile(
    r"(?://|/\*|<!--|#)\s*TODO\b"   # a comment opener, then the TODO token
    r"|^\s*\*\s*TODO\b"             # a leading javadoc-continuation *, then TODO
    r"|\bTODO(?:\([^)]*\))?:")      # an explicit TODO: / TODO(owner): marker


def extract_added_todos(diff_text):
    """Return ``(path, line, text)`` for each added TODO line in a unified diff.

    ``line`` is the 1-based line number in the new file. Lines removed by the
    diff do not advance the new-file counter; added and context lines do.

    :param diff_text: the ``git diff`` output to parse
    :returns: a list of ``(path, line_number, stripped_text)`` tuples
    """
    todos = []
    path = None
    new_line = 0
    in_header = False
    for line in diff_text.split("\n"):
        if line.startswith("diff --git"):
            # A new file's section begins. Its "---"/"+++" file headers follow,
            # until the first "@@" hunk; only there are those markers headers.
            in_header = True
            path = None
            continue
        if in_header:
            # File-header region: "+++ " names the new file; the first hunk
            # header ends the region and every later "---"/"+++" is hunk body.
            if line.startswith("+++ "):
                # "+++ b/path" (or "+++ /dev/null" for a deletion).
                target = line[4:]
                if target.startswith("b/"):
                    target = target[2:]
                path = None if target == "/dev/null" else target
            elif line.startswith("@@"):
                match = _HUNK.match(line)
                if match:
                    new_line = int(match.group(1))
                in_header = False
            continue
        # Hunk body: classify each line by its first character alone, so an
        # added line whose content starts "++" and a removed line whose content
        # starts "--" are an addition and a removal, not misread file headers.
        if line.startswith("@@"):
            match = _HUNK.match(line)
            if match:
                new_line = int(match.group(1))
            continue
        if line.startswith("+"):
            content = line[1:]
            if path is not None and _MARKER.search(content):
                todos.append((path, new_line, content.strip()))
            new_line += 1
        elif line.startswith("-"):
            # A removed line: does not exist in the new file, so it does not
            # advance the new-file counter.
            continue
        elif not line.startswith("\\"):
            # Context line (or a blank diff line); advances the new-file counter.
            # "\ No newline at end of file" markers do not.
            new_line += 1
    return todos


def branch_diff(base_branch):
    """Return the unified diff of the branch against its merge base with ``base_branch``.

    Uses ``--unified=0`` (only changed lines, so the parser sees added lines and
    hunk headers) and ``-M`` (rename detection). A git failure returns ``None``.

    :param base_branch: the base branch to diff against (e.g. ``master``)
    :returns: the diff text, or ``None`` when git could not produce it
    """
    try:
        result = subprocess.run(
            ["git", "diff", "--merge-base", "origin/" + base_branch, "-M",
             "--unified=0", "--no-color", "--no-ext-diff"],
            capture_output=True, text=True, check=False)
    except OSError as error:
        sys.stderr.write("::warning::could not run git diff for TODO extraction: "
                         + str(error) + "\n")
        return None
    if result.returncode != 0:
        sys.stderr.write("::warning::git diff for TODO extraction exited "
                         + str(result.returncode) + ": " + result.stderr.strip() + "\n")
        return None
    return result.stdout


def render(todos):
    """Render the TODO list as the Markdown section embedded in the prompt.

    :param todos: the ``(path, line, text)`` tuples from :func:`extract_added_todos`
    :returns: the Markdown section text
    """
    lines = ["## New TODOs this branch adds", ""]
    if not todos:
        lines.append("None — the branch adds no TODO lines relative to its merge base.")
    else:
        lines.append("Each TODO below was added on this branch (it is not on the base "
                     "branch). Treat each as a finding, per the instructions above:")
        lines.append("")
        for path, line, text in todos:
            lines.append("- `" + path + ":" + str(line) + "` — " + text)
    lines.append("")
    return "\n".join(lines)


def main(argv):
    """Write the rendered TODO section for the branch to the output file.

    :param argv: ``[base_branch, output_file]``
    :returns: process exit code (always 0; a git failure is a warning)
    """
    if len(argv) != 2:
        sys.stderr.write("usage: extract_branch_todos.py <base-branch> <output-file>\n")
        return 2
    base_branch, output_file = argv
    diff_text = branch_diff(base_branch)
    todos = extract_added_todos(diff_text) if diff_text else []
    with open(output_file, "w", encoding="utf-8") as handle:
        handle.write(render(todos))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
