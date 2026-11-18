#!/usr/bin/env python3
"""Decide whether a staged change and its ``commit.txt`` may be committed.

Agents do not run ``git commit``: the Bash hook ``block-git-commit`` refuses it.
They commit through the ``ar-commit`` server instead, which runs the checks in
this module first and commits only when none of them finds a problem. The
checks are the commit-message rules of the root ``CLAUDE.md``:

  attribution       the message may not attribute authorship: no
                    ``Co-Authored-By:``-style trailer, no tool credit such as
                    "Generated with <assistant>", no line that is only an
                    assistant identity. The patterns are those of
                    ``io.flowtree.jobs.AuthorAttribution``, which applies the
                    same rule to the messages of FlowTree jobs; this module
                    refuses a message carrying any of them rather than
                    removing it, because the message is the agent's to rewrite.
  private link      the message may not carry a link only its author or the
                    project's own network can open, as decided by
                    ``.claude/hooks/lib/private_url_check.py``.
  temporary id      neither the message nor a line the change adds may carry a
                    temporary identifier, as decided by
                    ``scripts/check-temporary-identifiers.py``: a plan
                    document's handles anywhere, and the generic shapes (an id
                    beside a word such as task, job, run or commit, a
                    timestamped round branch name) on added lines.
  private product   neither the message nor a line the change adds may name a
                    private downstream product. The names are configuration of
                    the machine, not of this public repository: they are read
                    from ``AR_PRIVATE_PRODUCT_NAMES`` (comma-separated).

Besides the content checks, a commit needs a branch other than the default
branch, a staged change, and a message whose first line is not blank; and
``commit.txt`` must not be part of the change.
"""
import importlib.util
import os
import re
import sys
from pathlib import Path
from typing import Callable, Iterable, NamedTuple, Optional

REPOSITORY = Path(__file__).resolve().parents[3]

sys.path.insert(0, str(REPOSITORY / ".claude" / "hooks" / "lib"))
import private_url_check  # noqa: E402


def _load_identifier_check():
    """Loads ``scripts/check-temporary-identifiers.py``, whose file name is not importable."""
    path = REPOSITORY / "scripts" / "check-temporary-identifiers.py"
    spec = importlib.util.spec_from_file_location("check_temporary_identifiers", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


identifier_check = _load_identifier_check()

MESSAGE_FILE = "commit.txt"
"""The file an agent writes its commit message to, at the top of the working tree."""

DEFAULT_BRANCHES = frozenset({"master", "main"})
"""Branches an agent never commits to directly."""

PRIVATE_NAMES_VARIABLE = "AR_PRIVATE_PRODUCT_NAMES"
"""The environment variable listing the private product names, comma-separated."""

ASSISTANTS = r"(?:claude|anthropic|copilot|chatgpt|openai|gpt-?[0-9]|codex|cursor|gemini)"

TRAILER_LINE = re.compile(r"(?i)^[\W\d_]*(?:co[- ]?)?(?:authored|written|assisted)[- ]?by[ \t]*:.*$")
"""A git trailer naming an author or co-author, e.g. ``Co-Authored-By: <name>``."""

TOOL_CREDIT_LINE = re.compile(
    r"(?i)^[\W\d_]*(?:co[- ]?)?(?:generated|created|authored|produced|written|assisted)"
    r"[- ]?(?:with|by)\b.*\b" + ASSISTANTS + r"\b.*$")
"""A line crediting a named assistant or vendor, e.g. ``Generated with <assistant>``."""

IDENTITY_LINE = re.compile(
    r"(?i)^[\W\d_]*(?:https?://)?"
    r"(?:noreply@anthropic\.com|claude\.com/claude-code|claude\.ai/code)[\W\d_]*$")
"""A line that is nothing but an assistant's e-mail address or product URL."""

INLINE_MARKER = re.compile(
    r"(?i)(?:co[- ]?)?(?:authored|written|assisted)[- ]?by[ \t]*:"
    r"|(?:co[- ]?)?(?:generated|created|produced|authored|written|assisted)"
    r"[- ]?(?:with|by)[ \t]+" + ASSISTANTS)
"""Attribution inside a line that also carries other text."""


class Problem(NamedTuple):
    """One reason a commit is refused."""

    check: str
    location: str
    text: str

    def describe(self) -> str:
        """Returns the report line for this problem."""
        return f"{self.check}: {self.location}: {self.text}"


def attribution_lines(message: str) -> list[tuple[int, str]]:
    """Returns the numbered lines of ``message`` that attribute authorship."""
    found = []
    for number, line in enumerate(message.splitlines(), start=1):
        if (TRAILER_LINE.match(line) or TOOL_CREDIT_LINE.match(line)
                or IDENTITY_LINE.match(line) or INLINE_MARKER.search(line)):
            found.append((number, line.strip()))
    return found


def private_product_names(value: Optional[str] = None) -> list[str]:
    """Returns the configured private product names; ``value`` overrides the environment."""
    raw = os.environ.get(PRIVATE_NAMES_VARIABLE, "") if value is None else value
    return [name.strip() for name in raw.split(",") if name.strip()]


def private_product_pattern(names: Iterable[str]) -> Optional["re.Pattern[str]"]:
    """Compiles the names into one whole-word pattern, or returns None when there are none.

    A name written in lower case matches in any case; a name with capitals
    matches only as written, so a product named like an ordinary word is not
    found in prose that uses the word.
    """
    parts = [re.escape(name) if name != name.lower() else "(?i:" + re.escape(name) + ")"
             for name in names]
    return re.compile(r"\b(?:" + "|".join(parts) + r")\b") if parts else None


def message_problems(message: str, root: Path, tracked: Iterable[str],
                     products: Optional["re.Pattern[str]"],
                     read_ledger: Optional[Callable[[str], str]] = None) -> list[Problem]:
    """Returns every problem with the text of a commit message.

    ``root`` is the working tree being committed and ``tracked`` its tracked
    files, from which the plan documents defining temporary identifiers are read.
    ``read_ledger`` supplies a ledger's text from the staged snapshot; see
    ``change_problems``.
    """
    problems = []
    lines = message.splitlines()
    if not lines or not lines[0].strip():
        problems.append(Problem("message", f"{MESSAGE_FILE}:1", "the first line (the subject) is blank"))
    for number, line in attribution_lines(message):
        problems.append(Problem("attribution", f"{MESSAGE_FILE}:{number}", line))
    for link in private_url_check.private_links(message, private_url_check.lab_hosts()):
        problems.append(Problem("private link", MESSAGE_FILE, link))
    plans = [path for path in tracked if identifier_check.is_plan_path(path)]
    for identifier in identifier_check.discover_identifiers(root, plans, read_ledger):
        for match in identifier.pattern.finditer(message):
            number = message.count("\n", 0, match.start()) + 1
            problems.append(Problem("temporary id", f"{MESSAGE_FILE}:{number}",
                                    f"'{match.group(0)}' {identifier.source}"))
    for index, line in enumerate(lines):
        preceding = "\n".join(lines[max(0, index - identifier_check.CONTEXT_LINES):index])
        following = "\n".join(lines[index + 1:index + 1 + identifier_check.CONTEXT_LINES])
        for violation in identifier_check.generic_identifiers(MESSAGE_FILE, index + 1, line,
                                                              preceding, following):
            problems.append(Problem("temporary id", f"{MESSAGE_FILE}:{index + 1}",
                                    f"'{violation.text}' is {violation.source}"))
        if products is not None:
            for match in products.finditer(line):
                problems.append(Problem("private product", f"{MESSAGE_FILE}:{index + 1}",
                                        f"names '{match.group(0)}'"))
    return problems


def change_problems(diff: str, root: Path, staged: Iterable[str], tracked: Iterable[str],
                    products: Optional["re.Pattern[str]"],
                    read_ledger: Optional[Callable[[str], str]] = None) -> list[Problem]:
    """Returns every problem with the lines a staged change adds.

    ``diff`` is the staged change as a unified diff, ``staged`` the paths it
    touches, and ``tracked`` the working tree's tracked files. ``read_ledger``
    supplies a plan ledger's text from the staged snapshot rather than the
    working tree, so an unstaged edit or deletion of a tracked ledger does not
    change which identifiers are enforced for this commit or abort the read.
    """
    staged = list(staged)
    problems = []
    if MESSAGE_FILE in staged:
        problems.append(Problem("message", MESSAGE_FILE, "is staged; it must not be part of the commit"))
    plans = [path for path in tracked if identifier_check.is_plan_path(path)]
    identifiers = identifier_check.discover_identifiers(root, plans, read_ledger)
    changed = identifier_check.parse_diff(diff)
    for path in staged:
        if path not in changed or identifier_check.is_plan_path(path):
            continue
        for number, line in sorted(changed[path].items()):
            if not line.added:
                continue
            for identifier in identifiers:
                for match in identifier.pattern.finditer(line.text):
                    problems.append(Problem("temporary id", f"{path}:{number}",
                                            f"'{match.group(0)}' {identifier.source}"))
            if products is not None:
                for match in products.finditer(line.text):
                    problems.append(Problem("private product", f"{path}:{number}",
                                            f"names '{match.group(0)}'"))
    for violation in identifier_check.scan_added(root, diff, staged):
        problems.append(Problem("temporary id", f"{violation.path}:{violation.line}",
                                f"'{violation.text}' is {violation.source}"))
    return problems


def branch_problems(branch: str) -> list[Problem]:
    """Returns the problems with committing on ``branch``."""
    if not branch:
        return [Problem("branch", "HEAD", "is detached; check out a branch first")]
    if branch in DEFAULT_BRANCHES:
        return [Problem("branch", branch, "is a default branch; commit on a feature branch")]
    return []
