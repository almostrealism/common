#!/usr/bin/env python3
"""
check-concept-map.py — Fail when the consult concept map names a type that no
longer exists.

The curated concept map (``docs/internals/consult-concept-map.md``) lists, for
each core abstraction, the canonical types that already represent it. Those
names are what consult points an agent at, so a name that has been renamed,
moved out of the source tree, or deleted turns the map into misdirection. This
check parses the map, indexes the type declarations in the source tree, and
reports every map entry that names a canonical type the tree no longer declares.

It is deliberately not wired into any CI workflow on this branch — the CI-file
lock keeps ``.github/workflows`` changes on a ``ci/...`` branch. Run it directly:

    python3 scripts/check-concept-map.py

Exit status is ``0`` when every canonical type exists, ``1`` when any is
missing, and ``2`` when the map document itself cannot be found.
"""

import os
import sys

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO_ROOT, "tools", "mcp", "common"))

from concept_map import ConceptMap  # noqa: E402
from snapshot_index import SnapshotIndex, build  # noqa: E402


def check(root: str) -> int:
    """Check the concept map under ``root`` and print any undeclared types.

    Args:
        root: The repository root to index and resolve the map against.

    Returns:
        The process exit status (0 ok, 1 undeclared types, 2 map missing).
    """
    path = os.path.join(root, "docs", "internals", "consult-concept-map.md")
    concept_map = ConceptMap.load(path)
    if concept_map is None:
        print(f"concept map not found at {path}", file=sys.stderr)
        return 2

    index = SnapshotIndex(build(root))
    flagged = concept_map.undeclared_types(index.declares_type)
    if not flagged:
        print(f"concept map OK: {len(concept_map.entries)} entries, "
              "all canonical types declared in the source tree")
        return 0

    for entry, missing in flagged:
        print(f"{path}:{entry.start}: concept '{entry.name}' names "
              f"{len(missing)} type(s) absent from the source tree: "
              + ", ".join(missing), file=sys.stderr)
    print(f"\n{len(flagged)} concept-map entr(y/ies) name a type that no longer "
          "exists. Update docs/internals/consult-concept-map.md to the current "
          "type name, or remove the stale name.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(check(_REPO_ROOT))
