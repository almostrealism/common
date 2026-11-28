#!/usr/bin/env python3
"""The Maven project an MCP server run targets.

A server ships inside one repository but is not limited to it: any directory
holding a reactor ``pom.xml`` can be the target of a run, which is what lets a
downstream consumer -- a sibling checkout, a worktree -- be exercised even
though a direct ``mvn`` invocation is blocked for agents. Resolving that target
from a caller-supplied path is the one concern here, shared so the test runner
and the build validator resolve it identically rather than each keeping its own
copy.

The default base a relative or empty path resolves against is the repository
the servers live in, but a caller may pass its own base. Each server passes the
module-level default root it already exposes, so that root stays patchable in
the server's own tests -- the resolver reads whatever base it is handed rather
than binding one at import.
"""

from pathlib import Path
from typing import Optional

# tools/mcp/common/maven_project.py -> the repository the servers live in. The
# default base an empty or relative project path is resolved against; the same
# path ``common/build_tree.REPO_ROOT`` points at.
REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent


def resolve_project_root(project: str = "", base: Optional[Path] = None) -> Path:
    """Resolve the Maven project a run targets.

    Args:
        project: Path to the project root. Empty selects ``base`` (the
            repository the server lives in). A relative path is resolved
            against ``base``, so ``../downstream`` names a sibling checkout the
            same way a shell in the repository would.
        base: The directory an empty or relative ``project`` is resolved
            against. ``None`` selects :data:`REPO_ROOT`. A server passes the
            module-level default root it already exposes so that root stays
            patchable in its own tests.

    Returns:
        The resolved absolute path.

    Raises:
        ValueError: If the path does not exist or holds no ``pom.xml`` -- an
            unbuildable target is reported here rather than as an opaque Maven
            failure several steps later.
    """
    base = REPO_ROOT if base is None else Path(base)
    if not project:
        return base

    root = Path(project).expanduser()
    if not root.is_absolute():
        root = base / root

    try:
        root = root.resolve()
    except OSError as e:
        raise ValueError(f"Cannot resolve project path {project!r}: {e}")

    if not root.is_dir():
        raise ValueError(f"Project root {root} does not exist")
    if not (root / "pom.xml").is_file():
        raise ValueError(
            f"Project root {root} has no pom.xml; "
            "the path must name the directory holding the reactor pom")

    return root
