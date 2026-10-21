#!/usr/bin/env python3
"""
The snapshot index: what the documentation corpus's source tree contained.

The ar-manager image keeps only ``*.md``/``*.html`` of the tree it was built
from, so on its own consult cannot tell whether ``ClaudeCodeJob`` still exists
or what ``validate-agent-commit.sh`` exits with. The Dockerfile's ``docs``
stage has the whole tree in hand before it prunes it; this module runs there
(``build``), records a compact index of that tree, and ar-manager reads it
back at request time (:class:`SnapshotIndex`).

What the index holds:

- ``corpus`` — the commit the image was built from (the ``CORPUS_COMMIT``
  build argument, ``unknown`` when a build omits it) and when it was built.
  Everything consult calls "the snapshot" refers to this.
- ``paths`` — every file path in the indexed trees, build output excluded.
- ``types`` — declared type names (Java types, nested ones included, and
  Python classes) to the files declaring them; ``external_types`` — simple
  names imported by Java sources, so a JDK or library class a note mentions
  is not mistaken for a deleted project class.
- ``scripts`` — script basename to *every* path with that name: basenames
  such as ``entrypoint.sh`` repeat across directories.
- ``script_facts`` — for each script, its leading comment block, the literal
  exit codes it uses, and its ``signals`` (the named collection constants it
  defines — a status set, an allowlist — and the API paths it calls). These
  scripts are the documentation of their own behaviour, and a header comment
  only paraphrases the logic the signals state literally.
- ``constants`` — ``static final`` fields initialised with a single string or
  numeric literal, of any visibility, keyed by name, with their owner type.

Run as a script to build the index (see ``main``).
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from typing import Optional

INDEX_VERSION = 3

# Directory names never indexed: build output and caches.
_SKIP_DIRS = frozenset({
    ".git", "target", "__pycache__", ".pytest_cache", ".mypy_cache",
    "node_modules", "results", "runs", ".idea", ".vscode",
})

_SCRIPT_SUFFIXES = (".sh", ".py")
_SCRIPT_ROOTS = ("tools/", ".github/", "scripts/", ".claude/")

_HEADER_CHARS = 1500
_MAX_EXITS = 20
_MAX_VALUE_CHARS = 200
_MAX_SIGNAL_CHARS = 400

# A named collection constant a script defines on one line: an UPPER_SNAKE name
# bound to a ``{...}``/``[...]``/``(...)`` literal — a status set, an allowlist.
# ``ACTIVE = {"running", "in_progress", "started", "pending", "queued"}``.
_NAMED_COLLECTION = re.compile(
    r"^\s*([A-Z][A-Z0-9_]{2,})\s*=\s*([\[{(][^\[\]{}()\n]*[\]})])", re.MULTILINE)
# An API path a script calls, from a curl URL or a literal: ``/api/jobs``.
_API_PATH = re.compile(r"/api/[\w./-]+")

_JAVA_TYPE = re.compile(
    r"^\s*(?:(?:public|protected|private|abstract|static|final|sealed|"
    r"non-sealed|strictfp)\s+)*(?:class|interface|enum|record|@interface)\s+"
    r"([A-Z]\w*)", re.MULTILINE)
_JAVA_IMPORT = re.compile(r"^import\s+(?:static\s+)?[\w.]+\.([A-Z]\w*)\s*;",
                          re.MULTILINE)
_JAVA_CONSTANT = re.compile(
    r"^\s*((?:public|protected|private)\s+)?(?:static\s+final|final\s+static)\s+"
    r"(String|int|long|short|byte|double|float|boolean|char)\s+"
    r"([A-Z][A-Z0-9_]*)\s*=\s*(\"(?:[^\"\\]|\\.)*\"|-?\d[\d_]*[lL]?|"
    r"-?\d+\.\d+[fFdD]?|true|false)\s*;")
_PY_CLASS = re.compile(r"^\s*class\s+([A-Z]\w*)", re.MULTILINE)
_SH_EXIT = re.compile(r"^\s*(?:.*[;&|]\s*)?exit\s+(\d+)\b")
_PY_EXIT = re.compile(r"(?:sys\.exit|SystemExit)\(\s*(\d+)\s*\)")


def _walk(root: str, roots: Optional[list]) -> list[str]:
    """Repository-relative paths of every indexed file under ``root``."""
    starts = roots or ["."]
    found: list[str] = []
    for start in starts:
        abs_start = os.path.join(root, start)
        if os.path.isfile(abs_start):
            found.append(os.path.normpath(start))
            continue
        for dirpath, dirnames, filenames in os.walk(abs_start):
            dirnames[:] = sorted(d for d in dirnames if d not in _SKIP_DIRS)
            for name in filenames:
                if name.endswith((".class", ".pyc")):
                    continue
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                found.append(rel.replace(os.sep, "/"))
    return sorted(set(found))


def _read(path: str) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def script_header(text: str, suffix: str) -> str:
    """The leading comment block (shell) or module docstring (Python) of a
    script, decoration stripped, capped at :data:`_HEADER_CHARS`."""
    lines = text.split("\n")
    out: list[str] = []
    if suffix == ".py":
        match = re.match(r'\s*(?:#![^\n]*\n)?\s*(?:#[^\n]*\n\s*)*(?:"""|\'\'\')'
                         r'(.*?)(?:"""|\'\'\')', text, re.DOTALL)
        if match:
            out = [ln.rstrip() for ln in match.group(1).strip().split("\n")]
    if not out:
        for line in lines[1:] if lines and lines[0].startswith("#!") else lines:
            if not line.strip():
                if out:
                    out.append("")
                continue
            if not line.lstrip().startswith("#"):
                break
            out.append(line.lstrip()[1:].strip())
    cleaned = [ln for ln in out if not re.fullmatch(r"[\s─═\-=*#]*", ln) or not ln]
    header = "\n".join(cleaned).strip()
    header = re.sub(r"\n{3,}", "\n\n", header)
    return header[:_HEADER_CHARS]


def script_exits(text: str, suffix: str) -> list[list[int]]:
    """``[line, code]`` for every literal exit in a script, in file order."""
    pattern = _PY_EXIT if suffix == ".py" else _SH_EXIT
    exits: list[list[int]] = []
    for number, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        for match in pattern.finditer(line):
            exits.append([number, int(match.group(1))])
            if len(exits) >= _MAX_EXITS:
                return exits
    return exits


def script_signals(text: str) -> str:
    """The salient logic literals of an operations script: the named
    collection constants it defines (a status set, an allowlist) and the API
    paths it calls, capped at :data:`_MAX_SIGNAL_CHARS`.

    For a script such as ``drain-agent-jobs.sh`` these *are* its behaviour —
    which statuses count as active, which endpoint is polled — stated
    literally where the leading comment only paraphrases them.
    """
    collections: list[str] = []
    for match in _NAMED_COLLECTION.finditer(text):
        entry = f"{match.group(1)} = {match.group(2).strip()}"
        if entry not in collections:
            collections.append(entry)
    paths: list[str] = []
    for match in _API_PATH.finditer(text):
        if match.group(0) not in paths:
            paths.append(match.group(0))
    parts: list[str] = []
    if collections:
        parts.append("; ".join(collections))
    if paths:
        parts.append("API paths: " + ", ".join(paths))
    return " | ".join(parts)[:_MAX_SIGNAL_CHARS]


def build(root: str, commit: str = "unknown", built_at: str = "",
          roots: Optional[list] = None) -> dict:
    """Index the tree under ``root`` (optionally only the ``roots`` beneath it).

    Args:
        root: The repository root (the ``docs`` stage's ``/src``).
        commit: The commit the tree was taken from; ``unknown`` when not known.
        built_at: ISO timestamp of the build; now when empty.
        roots: Repository-relative files or directories to index; the whole
            tree when omitted.

    Returns:
        The index as a JSON-serialisable dict.
    """
    paths = _walk(root, roots)
    types: dict[str, list[str]] = {}
    external: set[str] = set()
    scripts: dict[str, list[str]] = {}
    script_facts: dict[str, dict] = {}
    constants: dict[str, list[dict]] = {}

    for rel in paths:
        name = rel.rsplit("/", 1)[-1]
        abs_path = os.path.join(root, rel)
        if rel.endswith(".java"):
            text = _read(abs_path)
            for type_name in set(_JAVA_TYPE.findall(text)):
                types.setdefault(type_name, []).append(rel)
            external.update(_JAVA_IMPORT.findall(text))
            owner = name[:-5]
            for number, line in enumerate(text.split("\n"), 1):
                match = _JAVA_CONSTANT.match(line)
                if not match:
                    continue
                value = match.group(4)
                if value.startswith('"'):
                    value = bytes(value[1:-1], "utf-8").decode(
                        "unicode_escape", errors="replace")
                constants.setdefault(match.group(3), []).append({
                    "path": rel, "line": number, "owner": owner,
                    "type": match.group(2),
                    "visibility": (match.group(1) or "package-private").strip(),
                    "value": value[:_MAX_VALUE_CHARS],
                })
        elif rel.endswith(".py"):
            for type_name in set(_PY_CLASS.findall(_read(abs_path))):
                types.setdefault(type_name, []).append(rel)
        if name.endswith(_SCRIPT_SUFFIXES) and rel.startswith(_SCRIPT_ROOTS):
            scripts.setdefault(name, []).append(rel)
            text = _read(abs_path)
            suffix = os.path.splitext(name)[1]
            script_facts[rel] = {"header": script_header(text, suffix),
                                 "exits": script_exits(text, suffix),
                                 "signals": script_signals(text)}

    return {
        "version": INDEX_VERSION,
        "corpus": {
            "commit": commit or "unknown",
            "built_at": built_at or datetime.now(timezone.utc).strftime(
                "%Y-%m-%dT%H:%M:%SZ"),
        },
        "paths": paths,
        "types": {k: sorted(v) for k, v in sorted(types.items())},
        "external_types": sorted(external - set(types)),
        "scripts": {k: sorted(v) for k, v in sorted(scripts.items())},
        "script_facts": script_facts,
        "constants": dict(sorted(constants.items())),
    }


class SnapshotIndex:
    """Read access to a built index."""

    def __init__(self, data: dict):
        self.data = data
        self._paths = set(data.get("paths", []))
        self._dirs: set[str] = set()
        self._basenames: set[str] = set()
        for path in self._paths:
            parts = path.split("/")
            self._basenames.add(parts[-1])
            for i in range(1, len(parts)):
                self._dirs.add("/".join(parts[:i]) + "/")
        self._types = data.get("types", {})
        self._external = set(data.get("external_types", []))

    @classmethod
    def load(cls, path: str) -> Optional["SnapshotIndex"]:
        """The index at ``path``, or ``None`` when it is absent or unreadable."""
        if not path or not os.path.isfile(path):
            return None
        try:
            with open(path, encoding="utf-8") as handle:
                return cls(json.load(handle))
        except (OSError, ValueError):
            return None

    @property
    def corpus(self) -> dict:
        """``{commit, built_at}`` of the snapshot."""
        corpus = self.data.get("corpus") or {}
        return {"commit": corpus.get("commit") or "unknown",
                "built_at": corpus.get("built_at") or ""}

    def has_path(self, token: str) -> bool:
        """Whether a path-shaped mention names something in the snapshot.

        A full path, a directory (with or without the trailing slash), a
        trailing fragment of a path (``agent-protection/validate-agent-commit.sh``)
        or a bare file name all count; glob decoration is ignored.
        """
        token = token.strip().split(":", 1)[0]
        token = re.sub(r"(\*+|<[^>]*>|\{[^}]*\})+$", "", token)
        if token.startswith("./"):
            token = token[2:]
        if not token:
            return True
        if token in self._paths or token in self._dirs or token + "/" in self._dirs:
            return True
        if "/" not in token.rstrip("/"):
            return token.rstrip("/") in self._basenames or token in self._dirs
        suffix = "/" + token.lstrip("/")
        return any(p.endswith(suffix) for p in self._paths) or any(
            d.endswith(suffix if suffix.endswith("/") else suffix + "/")
            for d in self._dirs)

    def is_tree_path(self, token: str) -> bool:
        """Whether a slash-bearing token is shaped like a path in this tree:
        its first segment is a top-level directory of the snapshot.

        Branch names (``qa/docs-``, ``ci/test-protection-consistency``,
        ``origin/master``) and other slash-separated words share the shape of
        a relative path; only one rooted in the tree is a checkable mention.
        """
        first = token.strip().removeprefix("./").split("/", 1)[0]
        return bool(first) and first + "/" in self._dirs

    def has_type(self, name: str) -> bool:
        """Whether a type name is declared in, or imported by, the snapshot."""
        return name in self._types or name in self._external

    def type_paths(self, name: str) -> list[str]:
        """Files declaring the type ``name``."""
        return list(self._types.get(name, []))

    def script_paths(self, basename: str) -> list[str]:
        """Every path of a script with this file name.

        A bare stem (no extension) resolves to a script named by adding a
        script suffix, so a note or keyword that names ``drain-agent-jobs``
        reaches ``drain-agent-jobs.sh``; an exact basename is matched first.
        """
        scripts = self.data.get("scripts", {})
        if basename in scripts:
            return list(scripts[basename])
        if "." not in basename:
            for suffix in _SCRIPT_SUFFIXES:
                if basename + suffix in scripts:
                    return list(scripts[basename + suffix])
        return []

    def facts_for(self, mentions: list[str], limit: int = 4) -> list[dict]:
        """Index-derived facts about the scripts, types and constants that
        ``mentions`` name, as ``{path, line, label, value}`` records.

        A script contributes its header comment and its exit codes; a type
        contributes its literal constants; a constant name contributes its
        value(s). At most ``limit`` facts are returned, in mention order.
        """
        facts: list[dict] = []
        seen: set[tuple] = set()

        def _add(fact: dict) -> None:
            key = (fact["path"], fact["label"])
            if key not in seen and len(facts) < limit:
                seen.add(key)
                facts.append(fact)

        script_facts = self.data.get("script_facts", {})
        constants = self.data.get("constants", {})
        for mention in mentions:
            base = mention.rstrip("/").rsplit("/", 1)[-1].split(":", 1)[0]
            for path in self.script_paths(base):
                if "/" in mention and not path.endswith(mention.lstrip("./")):
                    continue
                fact = script_facts.get(path, {})
                exits = fact.get("exits", [])
                if exits:
                    codes = sorted({code for _, code in exits})
                    _add({"path": path, "line": exits[-1][0],
                          "label": "exit codes",
                          "value": "literal exit codes: " + ", ".join(
                              f"exit {c}" for c in codes)
                          + "; lines: " + ", ".join(
                              f"{line}: exit {code}" for line, code in exits)})
                if fact.get("header"):
                    _add({"path": path, "line": 1, "label": "header comment",
                          "value": fact["header"]})
                if fact.get("signals"):
                    _add({"path": path, "line": 1, "label": "behavior",
                          "value": fact["signals"]})
            for entry in constants.get(mention, []):
                _add(_constant_fact(mention, entry))
            for type_path in self.type_paths(mention):
                owner = type_path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
                for name, entries in constants.items():
                    for entry in entries:
                        if entry["path"] == type_path and entry["owner"] == owner:
                            _add(_constant_fact(name, entry))
        return facts


def _constant_fact(name: str, entry: dict) -> dict:
    """The fact record for one extracted constant."""
    value = entry["value"]
    shown = f'"{value}"' if entry.get("type") == "String" else value
    return {"path": entry["path"], "line": entry["line"],
            "label": f"constant {entry['owner']}.{name}",
            "value": f"{entry['visibility']} static final {entry.get('type', '')} "
                     f"{entry['owner']}.{name} = {shown}"}


def main(argv=None) -> int:
    """Build the index from a tree and write it as JSON."""
    parser = argparse.ArgumentParser(description="Build the snapshot index.")
    parser.add_argument("--root", required=True, help="tree to index")
    parser.add_argument("--out", required=True, help="output JSON path")
    parser.add_argument("--commit", default="unknown",
                        help="commit the tree was taken from")
    parser.add_argument("--built-at", default="", help="ISO build timestamp")
    parser.add_argument("roots", nargs="*",
                        help="relative files/directories to index (default: all)")
    args = parser.parse_args(argv)
    index = build(args.root, args.commit.strip() or "unknown", args.built_at,
                  args.roots or None)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(index, handle, separators=(",", ":"))
    print(f"indexed {len(index['paths'])} paths, {len(index['types'])} types, "
          f"{len(index['script_facts'])} scripts, {len(index['constants'])} "
          f"constant names; corpus {index['corpus']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
