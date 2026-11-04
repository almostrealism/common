#!/usr/bin/env python3
"""
check-temporary-identifiers.py — Fail the build when an identifier that belongs
to a plan document appears in a long-term file.

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

The files to consider are read from standard input as a NUL-separated list,
the form ``git ls-files -z`` prints, so only tracked files are scanned. The
plan documents are found in that same list; every other file in it is scanned.

Usage:
    git ls-files -z | check-temporary-identifiers.py [--root DIR]

Exit codes:
    0 — no temporary identifier found
    1 — one or more temporary identifiers found
    2 — the scan could not run (no file list, unreadable ledger)
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
        return (f"{self.path}:{self.line}: '{self.text}' is a temporary identifier "
                f"from {self.source}")


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
            identifiers.append(Identifier(bounded(stem), path))
            families.setdefault(dated.group("family"), path)
        elif name == LEDGER_NAME:
            text = (root / path).read_text(encoding="utf-8")
            for slug in ledger_slugs(text):
                if slug not in seen_slugs:
                    seen_slugs.add(slug)
                    identifiers.append(Identifier(bounded(slug), path))

    for family, example in sorted(families.items()):
        item = re.compile(r"(?<![\w-])" + re.escape(family) + r"(?:-\d{8})?"
                          + ITEM_SEPARATOR + r"[A-Z]\d+\b")
        identifiers.append(Identifier(item, f"the {family}-<date> plan documents (e.g. {example})"))
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


def check(root: Path, files: list[str]) -> list[Violation]:
    """Discovers the plan identifiers among ``files`` and scans every other one."""
    plans = [p for p in files if is_plan_path(p)]
    identifiers = discover_identifiers(root, plans)
    return scan(root, [p for p in files if not is_plan_path(p)], identifiers)


def main(argv: Optional[list[str]] = None) -> int:
    """Runs the check and prints any violation; returns the exit code."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=".", help="repository checkout to scan")
    args = parser.parse_args(argv)

    files = parse_file_list(sys.stdin.buffer.read())
    if not files:
        print("check-temporary-identifiers: no file list on standard input "
              "(pipe `git ls-files -z` into it)", file=sys.stderr)
        return 2

    try:
        violations = check(Path(args.root), files)
    except (OSError, ValueError) as error:
        print(f"check-temporary-identifiers: could not run: {error}", file=sys.stderr)
        return 2

    if not violations:
        print("No plan-document identifiers found outside the plan directories.")
        return 0

    for violation in violations:
        print(violation.describe())
    print()
    print(f"{len(violations)} temporary identifier(s) found. Identifiers defined by a plan "
          "document (its dated name, its numbered items, its proposal slugs) must not "
          "appear in code, workflows, tests or formal documentation: the plan is "
          "disposable and the reference goes dead with it. Describe the behavior itself.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
