#!/usr/bin/env python3
"""
check-temporary-identifiers.py — Fail the build when a temporary identifier
appears in a long-term file.

It applies two rules. The first concerns identifiers that plan documents
define; the second concerns the generic shapes of ephemeral handles, and
judges only the lines a change adds.

Plan-document identifiers
-------------------------

Plan documents (``docs/plans/`` and ``docs/qa/retrospectives/``) are
disposable: a plan is carried out and superseded, a retrospective is read once
and acted on. The handles they define — the document's own dated name
(``PLAN-<date>-<slug>``, ``RETRO-<date>``), the numbered items inside it
(``<FAMILY> P3``, ``<FAMILY>-<date> F6``) and the proposal slugs a retrospective
tracks in its ledger — mean nothing once the document is gone, so code,
workflows, prompts, tests and formal documentation must never cite them.
Describe the behavior itself instead.

The identifiers are not hard-coded. They are read from the plan documents
present in the checkout, so a new plan or retrospective is covered the moment
it lands:

- every plan document whose file name is ``<FAMILY>-<YYYYMMDD>[-<slug>]``
  contributes its full stem, and its ``<FAMILY>`` word;
- a ``<FAMILY>`` word (optionally with a date) followed by an item label such
  as ``P3`` or ``F6`` is an item reference;
- every ``ledger.tsv`` in a plan directory contributes the slugs listed in its
  ``proposal_ids`` column.

Generic identifier shapes
-------------------------

Agents also leak handles that no document defines: tracker task ids, job, run,
workstream and memory ids (as bare hexadecimal prefixes or full UUIDs), short
commit SHAs, and timestamped round branch or workstream names
(``<prefix>/<name>-YYYYMMDD-HHMMSS``). Hexadecimal strings are legitimate all
over the codebase — hashes, signatures, test vectors, colours — so this rule is
deliberately narrow:

- it judges only the lines the change adds, read from the unified diff given
  with ``--diff`` (``git diff --merge-base <base>`` compares the merge base
  with the working tree), so pre-existing code is never flagged; without
  ``--diff`` it does not run;
- a hexadecimal or UUID token is flagged only in an identifying context: a word
  such as task, job, run, workstream, memory, commit, SHA, tracker or id (in
  any spelling, ``taskId`` and ``job_id`` included) shortly before or after it,
  on its line or the few lines above, since a list of ids often sits just
  below the words that say what they are. A timestamped branch name is
  unambiguous and is flagged anywhere;
- a value plainly made up by hand — four equal digits in a row, an ascending
  run of four, hexspeak such as ``cafe`` — is a fixture, not a handle;
- the plan directories are exempt, as above, and generated or vendored files
  (by path, or by an ``@generated``/``DO NOT EDIT`` header) are skipped;
- a genuine case is suppressed on its own line with
  ``temporary-identifier-ok: <reason>``; the reason is required.

Durable anchors — PR numbers, class names, module names — never take these
shapes, so they stay allowed.

The files to consider are read from standard input as a NUL-separated list,
the form ``git ls-files -z`` prints, so only tracked files are scanned. The
plan documents are found in that same list; every other file in it is scanned.
The script runs no commands of its own: the caller supplies both lists.

Usage:
    git diff --merge-base origin/master -M > /tmp/change.diff
    git ls-files -z | check-temporary-identifiers.py [--root DIR] [--diff /tmp/change.diff]

Exit codes:
    0 — no temporary identifier found
    1 — one or more temporary identifiers found
    2 — the scan could not run (no file list, unreadable ledger or diff)
"""

import argparse
import csv
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple, Optional


PLAN_DIRECTORIES = ("docs/plans/", "docs/qa/retrospectives/")
"""Directories whose documents are plans; they define identifiers and are not scanned."""

DATED_DOCUMENT = re.compile(r"^(?P<family>[A-Z]+)-(?P<date>\d{8})(?:-[a-z0-9][a-z0-9-]*)?$")
"""The file-name stem of a dated plan document."""

ITEM_SEPARATOR = r"(?:[ \t]*\n[ \t]*(?:#|//|\*|--|\"\"\")?)?[ \t]+"
"""Space between a family word and an item label, which may wrap onto a comment line."""

LEDGER_NAME = "ledger.tsv"
LEDGER_SLUG_COLUMN = "proposal_ids"

BINARY_PROBE_BYTES = 8192

HEX_TOKEN = re.compile(
    r"(?<![0-9A-Za-z#])(?<!0x)(?<!0X)"
    r"(?P<token>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{7,40})"
    r"(?![0-9A-Za-z])")
"""A full UUID, or a lowercase hexadecimal run of 7 to 40 digits: an id prefix or a commit SHA.

A run preceded by ``#`` (a colour) or ``0x`` (a numeric literal) is not one."""

TIMESTAMPED_NAME = re.compile(
    r"(?<![\w.-])(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]*-\d{8}-(?P<time>\d{6})(?![\w-])")
"""A round branch or workstream name: ``<prefix>/<name>-YYYYMMDD-HHMMSS``."""

HEX_DIGITS = "0123456789abcdef"

PLACEHOLDER_WORDS = ("dead", "beef", "cafe", "babe", "face", "fade", "feed", "f00d", "c0de", "d00d")
"""Hexspeak that marks a value as made up by hand rather than generated."""

IDENTIFYING_WORDS = frozenset({
    "task", "tasks", "job", "jobs", "run", "runs", "workstream", "workstreams",
    "memory", "memories", "commit", "commits", "committed", "sha", "shas",
    "tracker", "id", "ids", "revert", "reverts", "reverted", "cherry",
})
"""Words that make a nearby hexadecimal token an identifier rather than data."""

CONTEXT_LINES = 3
"""How many neighbouring lines on each side of a token's line are read for context."""

CONTEXT_BEFORE = 240
"""How many characters before a token an identifying word may be."""

CONTEXT_AFTER = 40
"""How many characters after a token an identifying word may be."""

SUPPRESSION = re.compile(r"temporary-identifier-ok:(?P<reason>.*)$")
"""The inline suppression marker; the text after it is the required reason."""

GENERATED_PATH = re.compile(
    r"(?:^|/)(?:target|node_modules|vendor|vendored|third_party|generated|__pycache__)/"
    r"|\.min\.(?:js|css)$|(?:^|/)(?:package-lock\.json|yarn\.lock|poetry\.lock|Cargo\.lock)$")
"""Paths of generated or vendored files, which the generic rule skips."""

GENERATED_HEADER = re.compile(r"@generated|\bDO NOT EDIT\b|\bCode generated by\b")
"""A marker near the top of a file that says a tool wrote it."""

GENERATED_HEADER_LINES = 5
"""How many lines at the top of a file are searched for a generated-file marker."""

HUNK_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(?P<start>\d+)(?:,\d+)? @@")

HEX_SOURCE = ("a hexadecimal id in an identifying context (a task, job, run, workstream "
              "or memory id, or a commit SHA)")
NAME_SOURCE = "a timestamped round branch or workstream name"
UNREASONED_SOURCE = "suppressed without a reason; write 'temporary-identifier-ok: <reason>'"


class Identifier(NamedTuple):
    """A temporary identifier and the plan document that defines it."""

    pattern: "re.Pattern[str]"
    source: str


class Violation(NamedTuple):
    """One occurrence of a temporary identifier in a long-term file."""

    path: str
    line: int
    text: str
    source: str

    def describe(self) -> str:
        """Returns the human-readable report line for this violation."""
        return f"{self.path}:{self.line}: '{self.text}' is a temporary identifier: {self.source}"


def is_plan_path(path: str) -> bool:
    """Returns True when ``path`` lies inside a plan directory."""
    return path.startswith(PLAN_DIRECTORIES)


def parse_file_list(data: bytes) -> list[str]:
    """Returns the paths in a NUL-separated list such as ``git ls-files -z`` prints."""
    return [p for p in data.decode("utf-8", "replace").split("\0") if p]


def bounded(literal: str) -> "re.Pattern[str]":
    """Compiles ``literal`` so it matches only as a whole hyphenated token."""
    return re.compile(r"(?<![\w-])" + re.escape(literal) + r"(?![\w-])")


def ledger_slugs(text: str) -> list[str]:
    """Returns the proposal slugs listed in a retrospective ledger.

    Raises:
        ValueError: when the ledger has no ``proposal_ids`` column.
    """
    rows = list(csv.reader(text.splitlines(), delimiter="\t"))
    if not rows:
        return []
    header = rows[0]
    if LEDGER_SLUG_COLUMN not in header:
        raise ValueError(f"ledger has no '{LEDGER_SLUG_COLUMN}' column")
    column = header.index(LEDGER_SLUG_COLUMN)
    slugs = []
    for row in rows[1:]:
        if column < len(row):
            slugs.extend(s.strip() for s in row[column].split(",") if s.strip())
    return slugs


def discover_identifiers(root: Path, plan_paths: Iterable[str]) -> list[Identifier]:
    """Reads the temporary identifiers the given plan documents define."""
    identifiers: list[Identifier] = []
    families: dict[str, str] = {}
    seen_slugs: set[str] = set()

    for path in sorted(plan_paths):
        name = Path(path).name
        stem = Path(path).stem
        dated = DATED_DOCUMENT.match(stem)
        if dated is not None and name.endswith(".md"):
            identifiers.append(Identifier(bounded(stem), f"defined by {path}"))
            families.setdefault(dated.group("family"), path)
        elif name == LEDGER_NAME:
            text = (root / path).read_text(encoding="utf-8")
            for slug in ledger_slugs(text):
                if slug not in seen_slugs:
                    seen_slugs.add(slug)
                    identifiers.append(Identifier(bounded(slug), f"defined by {path}"))

    for family, example in sorted(families.items()):
        item = re.compile(r"(?<![\w-])" + re.escape(family) + r"(?:-\d{8})?"
                          + ITEM_SEPARATOR + r"[A-Z]\d+\b")
        identifiers.append(Identifier(
            item, f"defined by the {family}-<date> plan documents (e.g. {example})"))
    return identifiers


def read_text(path: Path) -> Optional[str]:
    """Returns the file's text, or None for a binary or unreadable file."""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data[:BINARY_PROBE_BYTES]:
        return None
    return data.decode("utf-8", "replace")


def scan(root: Path, paths: Iterable[str], identifiers: list[Identifier]) -> list[Violation]:
    """Returns every occurrence of an identifier in the given long-term files."""
    violations = []
    for path in paths:
        text = read_text(root / path)
        if text is None:
            continue
        for identifier in identifiers:
            for match in identifier.pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                found = " ".join(match.group(0).split())
                violations.append(Violation(path, line, found, identifier.source))
    return sorted(violations)


def words(text: str) -> set[str]:
    """Returns the lower-cased words of ``text``, splitting camelCase and snake_case."""
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    return {w for w in re.split(r"[^0-9a-z]+", spaced.lower()) if w}


def is_identifier_token(token: str) -> bool:
    """Returns True when a hexadecimal token has the shape of a generated id.

    A UUID always does. A bare run needs both a digit and a letter, which
    leaves out numbers and dates (all digits) and words spelled from a-f.
    """
    if "-" in token:
        return True
    return any(c.isdigit() for c in token) and any(c.isalpha() for c in token)


# TODO(review): window test also exempts ~2% of real 40-digit SHAs/UUIDs and HH0000 timestamps; uppercase hex is never matched.
def is_placeholder(value: str) -> bool:
    """Returns True when a hexadecimal or numeric value is plainly made up by hand.

    A generated id or a real timestamp almost never holds four equal digits in
    a row (``0000``), an ascending run of four (``1234``, ``abcd``) or hexspeak
    (``cafe``), while a fixture written to stand for one usually does. Writing
    fixtures this way keeps them out of the check without a suppression.
    """
    digits = value.replace("-", "")
    for i in range(len(digits) - 3):
        window = digits[i:i + 4]
        if len(set(window)) == 1 or window in HEX_DIGITS:
            return True
    return any(word in digits for word in PLACEHOLDER_WORDS)


def in_identifying_context(before: str, after: str) -> bool:
    """Returns True when an identifying word ends the text before a token or starts the text after it."""
    nearby = words(before[-CONTEXT_BEFORE:]) | words(after[:CONTEXT_AFTER])
    return not IDENTIFYING_WORDS.isdisjoint(nearby)


def generic_identifiers(path: str, line: int, text: str,
                        preceding: str = "", following: str = "") -> list[Violation]:
    """Returns the generic temporary identifiers on one added line.

    ``preceding`` and ``following`` are the file's neighbouring lines, which
    count as context: a list of ids often sits a line or two below the words
    that say what they are.

    A line carrying ``temporary-identifier-ok: <reason>`` is suppressed; the
    marker without a reason is itself reported, in place of what it hides.
    """
    found = [Violation(path, line, m.group(0), NAME_SOURCE)
             for m in TIMESTAMPED_NAME.finditer(text) if not is_placeholder(m.group("time"))]
    for match in HEX_TOKEN.finditer(text):
        token = match.group("token")
        if is_identifier_token(token) and not is_placeholder(token) and in_identifying_context(
                preceding + "\n" + text[:match.start()], text[match.end():] + "\n" + following):
            found.append(Violation(path, line, token, HEX_SOURCE))
    if not found:
        return []
    suppression = SUPPRESSION.search(text)
    if suppression is None:
        return found
    if len(re.findall(r"[A-Za-z]{2,}", suppression.group("reason"))) >= 2:
        return []
    return [Violation(path, line, found[0].text, UNREASONED_SOURCE)]


def unquote_path(path: str) -> str:
    """Strips the quotes git puts around a diff path containing unusual characters."""
    if len(path) >= 2 and path[0] == path[-1] == '"':
        return path[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    return path


class DiffLine(NamedTuple):
    """One line of a file's new side, as a diff shows it."""

    text: str
    added: bool


def parse_diff(diff: str) -> dict[str, dict[int, DiffLine]]:
    """Returns the new-side lines a unified diff shows, by path and line number.

    Added lines are marked; context lines are kept so that an added line's
    neighbours can be read without the file. Removed lines are not on the new
    side and are dropped.
    """
    files: dict[str, dict[int, DiffLine]] = {}
    lines: Optional[dict[int, DiffLine]] = None
    number = 0
    for row in diff.split("\n"):
        if row.startswith("diff "):
            lines = None
        elif row.startswith("+++ ") and lines is None:
            target = unquote_path(row[4:].rstrip("\t"))
            lines = files.setdefault(target[2:], {}) if target.startswith("b/") else None
        elif row.startswith("@@"):
            header = HUNK_HEADER.match(row)
            number = int(header.group("start")) if header else 0
        elif lines is not None and row[:1] in ("+", " "):
            lines[number] = DiffLine(row[1:], row[0] == "+")
            number += 1
    return files


def diff_identifiers(path: str, lines: dict[int, DiffLine]) -> list[Violation]:
    """Returns the generic temporary identifiers on the added lines of one file's diff.

    Up to ``CONTEXT_LINES`` neighbours of each added line, added or context,
    are read from the diff as its context.
    """
    violations = []
    for number, line in sorted(lines.items()):
        if line.added:
            preceding = "\n".join(lines[n].text for n in range(number - CONTEXT_LINES, number)
                                  if n in lines)
            following = "\n".join(lines[n].text for n in range(number + 1, number + 1 + CONTEXT_LINES)
                                  if n in lines)
            violations.extend(generic_identifiers(path, number, line.text, preceding, following))
    return violations


def is_generated(root: Path, path: str) -> bool:
    """Returns True when ``path`` is a generated or vendored file, by its path or its header."""
    if GENERATED_PATH.search(path):
        return True
    text = read_text(root / path)
    head = "\n".join(text.split("\n")[:GENERATED_HEADER_LINES]) if text is not None else ""
    return GENERATED_HEADER.search(head) is not None


def scan_added(root: Path, diff: str, files: Iterable[str]) -> list[Violation]:
    """Returns the generic temporary identifiers on the lines ``diff`` adds to long-term files."""
    changed = parse_diff(diff)
    violations = []
    for path in files:
        if path in changed and not is_plan_path(path) and not is_generated(root, path):
            violations.extend(diff_identifiers(path, changed[path]))
    return sorted(violations)


def check(root: Path, files: list[str], diff: Optional[str] = None) -> list[Violation]:
    """Discovers the plan identifiers among ``files`` and scans every other one.

    With ``diff`` — the change under review, as a unified diff — the lines it
    adds are also checked for the generic identifier shapes.
    """
    plans = [p for p in files if is_plan_path(p)]
    identifiers = discover_identifiers(root, plans)
    violations = scan(root, [p for p in files if not is_plan_path(p)], identifiers)
    if diff is not None:
        violations = sorted(violations + scan_added(root, diff, files))
    return violations


def main(argv: Optional[list[str]] = None) -> int:
    """Runs the check and prints any violation; returns the exit code."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=".", help="repository checkout to scan")
    parser.add_argument("--diff", help="a unified diff of the change; the lines it adds are "
                        "also judged for generic identifier shapes")
    args = parser.parse_args(argv)

    files = parse_file_list(sys.stdin.buffer.read())
    if not files:
        print("check-temporary-identifiers: no file list on standard input "
              "(pipe `git ls-files -z` into it)", file=sys.stderr)
        return 2

    try:
        diff = None
        if args.diff is not None:
            diff = Path(args.diff).read_text(encoding="utf-8", errors="replace")
        violations = check(Path(args.root), files, diff)
    except (OSError, ValueError) as error:
        print(f"check-temporary-identifiers: could not run: {error}", file=sys.stderr)
        return 2

    if not violations:
        print("No plan-document identifiers found outside the plan directories.")
        if diff is not None:
            print(f"No generic temporary identifiers on the lines {args.diff} adds.")
        return 0

    for violation in violations:
        print(violation.describe())
    print()
    print(f"{len(violations)} temporary identifier(s) found. Identifiers defined by a plan "
          "document (its dated name, its numbered items, its proposal slugs) must not "
          "appear in code, workflows, tests or formal documentation: the plan is "
          "disposable and the reference goes dead with it. Describe the behavior itself.")
    print("The same holds for tracker task, job, run, workstream and memory ids, commit "
          "SHAs and timestamped round branch names: name a durable thing (a class, a "
          "module, a PR number) or look the current value up at run time. A genuine case "
          "is suppressed on its own line with 'temporary-identifier-ok: <reason>'.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
