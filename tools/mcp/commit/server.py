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
                              result says so. The check is also skipped when the
                              repository being committed is itself one of those
                              products -- its own name naturally appears in its
                              own commits -- as decided from the ``origin``
                              remote URL; the result says so as a warning.
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
    """Runs git in ``root`` and returns the completed process, without raising on failure.

    A command that outlives ``GIT_TIMEOUT_SECONDS`` (a repository hook included)
    is returned as a failed process rather than raised: ``_git_output`` then
    refuses the review and ``commit`` reports that nothing was committed, instead
    of an unstructured ``TimeoutExpired`` escaping the MCP tool.
    """
    argv = ["git", "-C", str(root), *args]
    try:
        return subprocess.run(argv, input=stdin, capture_output=True,
                              text=True, timeout=GIT_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as e:
        return subprocess.CompletedProcess(argv, 124, _as_text(e.stdout),
                                           _as_text(e.stderr) + f"git {' '.join(args)} "
                                           f"timed out after {GIT_TIMEOUT_SECONDS}s")


def _as_text(stream) -> str:
    """Decodes a captured git stream to text.

    ``TimeoutExpired`` carries whatever output the command produced before it was
    killed, and on POSIX that is the raw ``bytes`` buffer even when ``text=True``
    was requested, because the timeout aborts before the decode step. Normalizing
    it here keeps the timeout branch from concatenating ``str`` and ``bytes``.
    """
    if isinstance(stream, bytes):
        return stream.decode("utf-8", "replace")
    return stream or ""


def _git_output(root: Path, *args: str) -> str:
    """Runs a git command that inspects the repository and returns its output.

    Raises ``ValueError`` when git fails, so the review is refused rather than
    read from an empty output: an empty staged diff from a failed ``git diff``
    would otherwise leave the staged lines unchecked while the commit goes ahead.
    """
    result = _git(root, *args)
    if result.returncode != 0:
        raise ValueError(f"git {' '.join(args)} failed in {root}: "
                         f"{(result.stderr or result.stdout).strip()}")
    return result.stdout


def _default_branches(root: Path) -> set[str]:
    """Returns the branches treated as default: ``master``, ``main`` and every remote's HEAD branch.

    A repository whose default is another name (``develop``, say) records it as
    the target of ``refs/remotes/<remote>/HEAD`` when cloned, so that name is
    refused as well.
    """
    branches = set(commit_check.DEFAULT_BRANCHES)
    heads = _git_output(root, "for-each-ref", "--format=%(refname)%00%(symref)", "refs/remotes/*/HEAD")
    for line in heads.splitlines():
        ref, _, target = line.partition("\0")
        prefix = ref[:-len("HEAD")]
        if target.startswith(prefix):
            branches.add(target[len(prefix):])
    return branches


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
    branch = _git_output(root, "branch", "--show-current").strip()
    staged = [p for p in _git_output(root, "diff", "--cached", "--name-only", "-z").split("\0") if p]
    tracked = [p for p in _git_output(root, "ls-files", "-z").split("\0") if p]
    diff = _git_output(root, "diff", "--cached", "-M", "--unified=3", "--no-color", "--no-ext-diff", "--no-textconv")

    message_path = root / commit_check.MESSAGE_FILE
    names = commit_check.private_product_names()
    products = commit_check.private_product_pattern(names)

    warnings = []
    origin = _git(root, "config", "--get", "remote.origin.url")
    repository = commit_check.repository_name(origin.stdout) if origin.returncode == 0 else None
    if products is None:
        warnings.append(f"{commit_check.PRIVATE_NAMES_VARIABLE} is not set, so private product "
                        "names were not checked")
    elif commit_check.names_own_repository(repository, names):
        warnings.append(f"the private product name check was skipped because this repository "
                        f"('{repository}') is itself a configured private product")
        products = None

    def read_ledger(path: str) -> str:
        """Returns a tracked plan ledger's text from the staged index, not the working tree.

        The checks describe the snapshot being committed, so an unstaged edit or
        deletion of a ledger must not change which identifiers are enforced.
        """
        return _git_output(root, "show", f":{path}")

    problems = commit_check.branch_problems(branch, _default_branches(root))
    if not staged:
        problems.append(commit_check.Problem("change", str(root), "nothing is staged"))
    message = None
    if message_path.is_file():
        message = message_path.read_text(encoding="utf-8").replace("\r\n", "\n").rstrip() + "\n"
        problems.extend(commit_check.message_problems(message, root, tracked, products, read_ledger))
    else:
        problems.append(commit_check.Problem("message", str(message_path), "does not exist"))
    problems.extend(commit_check.change_problems(diff, root, staged, tracked, products, read_ledger))

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
