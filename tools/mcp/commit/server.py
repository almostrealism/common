#!/usr/bin/env python3
"""
AR Commit MCP Server

Commits an agent's staged change with the message it wrote to ``commit.txt``,
after checking both against the commit-message rules of the root ``CLAUDE.md``.

Agents may not run ``git commit`` themselves (the ``block-git-commit`` Bash
hook refuses it), because nothing would check what they commit. This server is
the checked route: ``commit`` reads ``commit.txt`` at the top of the working
tree, refuses the commit when ``commit_check`` finds any problem (author
attribution, a private link, a temporary identifier, a private product name, a
default or detached branch, nothing staged), and otherwise commits exactly the
text it checked, with the configured git identity and the repository's own git
hooks. A successful commit deletes ``commit.txt`` so that its message cannot be
reused for a later commit.

``check_commit`` runs the same checks without committing.

Configuration via environment variables:
    AR_PRIVATE_PRODUCT_NAMES  Comma-separated names of private products that a
                              commit must not mention (see commit_check.py).
                              When unset, that check is skipped and every
                              result says so.
"""

import os
import subprocess
import sys
from pathlib import Path

from mcp.server.fastmcp import FastMCP

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import commit_check  # noqa: E402

GIT_TIMEOUT_SECONDS = 120
"""The longest a single git command, the repository's commit hooks included, may run."""

mcp = FastMCP("ar-commit")


def _git(root: Path, *args: str, stdin: str = None) -> subprocess.CompletedProcess:
    """Runs git in ``root`` and returns the completed process, without raising on failure."""
    return subprocess.run(["git", "-C", str(root), *args], input=stdin, capture_output=True,
                          text=True, timeout=GIT_TIMEOUT_SECONDS)


def _working_tree(repo_path: str) -> Path:
    """Returns the top of the working tree containing ``repo_path`` (the server's directory when empty)."""
    start = Path(repo_path).expanduser() if repo_path else Path.cwd()
    result = _git(start, "rev-parse", "--show-toplevel")
    if result.returncode != 0:
        raise ValueError(f"{start} is not inside a git working tree: {result.stderr.strip()}")
    return Path(result.stdout.strip())


def _review(repo_path: str) -> dict:
    """Runs every check on the working tree and returns the findings, with what the commit needs."""
    root = _working_tree(repo_path)
    branch = _git(root, "branch", "--show-current").stdout.strip()
    staged = [p for p in _git(root, "diff", "--cached", "--name-only", "-z").stdout.split("\0") if p]
    tracked = [p for p in _git(root, "ls-files", "-z").stdout.split("\0") if p]
    diff = _git(root, "diff", "--cached", "-M", "--unified=3", "--no-color").stdout

    message_path = root / commit_check.MESSAGE_FILE
    names = commit_check.private_product_names()
    products = commit_check.private_product_pattern(names)

    problems = commit_check.branch_problems(branch)
    if not staged:
        problems.append(commit_check.Problem("change", str(root), "nothing is staged"))
    message = None
    if message_path.is_file():
        message = message_path.read_text(encoding="utf-8").replace("\r\n", "\n").rstrip() + "\n"
        problems.extend(commit_check.message_problems(message, root, tracked, products))
    else:
        problems.append(commit_check.Problem("message", str(message_path), "does not exist"))
    problems.extend(commit_check.change_problems(diff, root, staged, tracked, products))

    warnings = []
    if products is None:
        warnings.append(f"{commit_check.PRIVATE_NAMES_VARIABLE} is not set, so private product "
                        "names were not checked")
    return {
        "root": root,
        "branch": branch,
        "staged": staged,
        "message": message,
        "message_path": message_path,
        "problems": problems,
        "warnings": warnings,
    }


def _report(review: dict) -> dict:
    """Returns the parts of a review a caller sees."""
    return {
        "repository": str(review["root"]),
        "branch": review["branch"],
        "staged_files": len(review["staged"]),
        "problems": [p.describe() for p in review["problems"]],
        "warnings": review["warnings"],
    }


@mcp.tool()
def check_commit(repo_path: str = "") -> dict:
    """Check commit.txt and the staged change without committing.

    Runs the same checks as `commit`: the message in commit.txt (at the top of
    the working tree) must have a subject line and carry no author attribution,
    private link, temporary identifier or private product name; the lines the
    staged change adds must carry no temporary identifier or private product
    name; something must be staged, commit.txt must not be, and the branch must
    be neither detached nor a default branch.

    Args:
        repo_path: Any path inside the working tree to check, such as a
            worktree directory. Defaults to the server's working directory.

    Returns:
        ok (true when there are no problems), repository, branch,
        staged_files, problems (one line each) and warnings.
    """
    try:
        review = _review(repo_path)
    except ValueError as e:
        return {"ok": False, "error": str(e)}
    return {"ok": not review["problems"], **_report(review)}


@mcp.tool()
def commit(repo_path: str = "") -> dict:
    """Commit the staged change with the message in commit.txt, after checking both.

    Write the commit message to commit.txt at the top of the working tree and
    stage the change with git add, then call this tool. It runs every check of
    `check_commit` and commits only when none finds a problem; otherwise
    nothing is committed and the problems are returned, to be fixed before
    calling it again. The commit uses the configured git identity and runs the
    repository's git hooks. On success commit.txt is deleted, so write a new
    one for the next commit.

    Args:
        repo_path: Any path inside the working tree to commit, such as a
            worktree directory. Defaults to the server's working directory.

    Returns:
        ok, repository, branch, staged_files, problems and warnings; on
        success also commit (the new commit's hash) and subject.
    """
    try:
        review = _review(repo_path)
    except ValueError as e:
        return {"ok": False, "error": str(e)}
    report = _report(review)
    if review["problems"]:
        return {"ok": False, "committed": False, **report}

    root = review["root"]
    result = _git(root, "commit", "--cleanup=whitespace", "-F", "-", stdin=review["message"])
    if result.returncode != 0:
        return {"ok": False, "committed": False, **report,
                "error": (result.stderr or result.stdout).strip()}

    review["message_path"].unlink()
    head = _git(root, "log", "-1", "--format=%H%n%s").stdout.split("\n")
    return {"ok": True, "committed": True, **report, "commit": head[0], "subject": head[1]}


if __name__ == "__main__":
    mcp.run()
