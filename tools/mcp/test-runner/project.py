#!/usr/bin/env python3
"""The Maven project a test run targets.

The runner ships inside one repository but is not limited to it: any directory
holding a reactor ``pom.xml`` can be tested, which is what lets a downstream
consumer (a sibling checkout, a worktree) be exercised even though direct
``mvn test`` is blocked for agents. Everything about identifying and
interrogating that target lives here — resolving the root, and reading the
per-module CI test-group count from the project's own workflow definition.
"""

import re
from pathlib import Path
from typing import Optional

# The default project root, derived from this file's location
# (tools/mcp/test-runner/project.py -> project root). This is the repository the
# server itself lives in; a run may target a different Maven project, so
# PROJECT_ROOT is only the default, never an assumption.
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent.resolve()


def resolve_project_root(project: str = "") -> Path:
    """Resolve the Maven project a run targets.

    Args:
        project: Path to the project root. Empty selects :data:`PROJECT_ROOT`.
            A relative path is resolved against :data:`PROJECT_ROOT`, so
            ``../downstream`` names a sibling checkout the same way a shell
            in the repository would.

    Returns:
        The resolved absolute path.

    Raises:
        ValueError: If the path does not exist or holds no ``pom.xml`` — an
            unbuildable target is reported here rather than as an opaque
            Maven failure several steps later.
    """
    if not project:
        return PROJECT_ROOT

    root = Path(project).expanduser()
    if not root.is_absolute():
        root = PROJECT_ROOT / root

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


def ci_workflow_path(project_root: Path) -> Path:
    """Return the CI workflow definition for a project.

    The workflow is the single source of truth for the test-matrix group count
    (AR_TEST_GROUPS). The value is read from this file on demand rather than
    duplicated in tool descriptions, where it would rot.
    """
    return project_root / ".github" / "workflows" / "analysis.yaml"


def resolve_ci_test_groups(module: str, project_root: Optional[Path] = None) -> int:
    """Read the AR_TEST_GROUPS value the CI pipeline currently uses for a module.

    Different CI jobs partition their modules into different group counts
    (e.g. the media jobs use a different count than the main test jobs), so
    the value is resolved per module: each AR_TEST_GROUPS declaration in the
    workflow is associated with the ``-pl`` module of the mvn command it
    belongs to, and comment lines are ignored. Raises ValueError when the
    workflow cannot be read, never runs the module with test groups, or
    declares conflicting counts for it -- in which case the caller must
    supply test_groups explicitly. A project with no such workflow (any
    target other than this repository, typically) always takes that path.
    """
    workflow = ci_workflow_path(project_root or PROJECT_ROOT)

    try:
        lines = workflow.read_text().splitlines()
    except OSError as e:
        raise ValueError(
            f"Cannot read {workflow} to determine the CI group count; "
            f"pass test_groups explicitly ({e})")

    values = set()
    for i, line in enumerate(lines):
        if line.strip().startswith("#"):
            continue

        m = re.search(r"AR_TEST_GROUPS=(\d+)", line)
        if not m:
            continue

        # The -pl flag of the mvn command this declaration belongs to appears
        # on the same or an earlier continuation line of the command.
        for back in range(i, max(-1, i - 15), -1):
            pl = re.search(r"-pl\s+([\w/\-]+)", lines[back])
            if pl:
                if pl.group(1) == module:
                    values.add(int(m.group(1)))
                break

    if len(values) != 1:
        detail = f"never runs module {module} with AR_TEST_GROUPS" if not values else \
            f"declares conflicting AR_TEST_GROUPS values {sorted(values)} for module {module}"
        raise ValueError(
            f"{workflow} {detail}; pass test_groups explicitly")

    return values.pop()
