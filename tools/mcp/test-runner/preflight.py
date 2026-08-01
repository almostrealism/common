"""Preflight seeding of upstream module artifacts for ar-test-runner.

When ar-test-runner is invoked for a module in a fresh worktree, the
upstream ``ar-*`` artifacts referenced by that module's ``pom.xml`` may
not yet be installed in ``~/.m2/repository/``. Maven invokes
``mvn test -pl <module>`` without ``-am``, so it will not build the
upstream chain — the test run fails immediately with an unresolvable
dependency, and the agent falls back to ``bash mvn install`` to seed
the local repo manually.

This module closes that gap. It exposes two functions that
``server.py`` calls before launching a test invocation:

* :func:`find_missing_upstream_artifacts` — pure inspection: parses the
  target module's ``pom.xml``, lists the direct ``org.almostrealism``
  dependencies, and returns the artifacts that are not present in the
  local Maven repository.

* :func:`seed_upstream_artifacts` — when any direct ``org.almostrealism``
  dependency is missing, runs
  ``mvn -pl <module> -am install -DskipTests -B`` from the project root
  so Maven builds and installs the entire upstream chain. When all
  direct dependencies are already present in ``~/.m2``, it is a no-op
  (idempotent).

The check is intentionally lightweight: it only verifies the direct
dependencies, not the full transitive closure. ``-am`` ensures that
once we seed for a module, Maven walks the full reactor — so the
direct-deps check is a sufficient "has this module's chain been
built?" heuristic.

The functions are pure with respect to the filesystem and the
``subprocess`` call (no global state, no caching). The caller is
responsible for serialising preflight calls if it cares about
avoiding redundant concurrent installs.
"""

from __future__ import annotations

import os
import subprocess
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Optional
from xml.etree import ElementTree

# All ``ar-*`` modules in this project live under this group id.
AR_GROUP_ID = "org.almostrealism"

# Default location of the user's local Maven repository.
DEFAULT_M2_REPOSITORY = Path.home() / ".m2" / "repository"


@dataclass
class MissingArtifact:
    """A direct ``org.almostrealism`` dependency that is absent from ``~/.m2``.

    Fields:
        artifact_id: The Maven ``artifactId`` (e.g. ``ar-utils``).
        version: The Maven ``version`` resolved from the dependency
            declaration.
        expected_path: The absolute path where the ``.jar`` would live
            if it had been installed. Useful for diagnostic logging.
    """

    artifact_id: str
    version: str
    expected_path: Path


@dataclass
class PreflightResult:
    """Outcome of a single :func:`seed_upstream_artifacts` invocation.

    Fields:
        action: One of ``"skipped"`` (all artifacts already present),
            ``"seeded"`` (mvn install ran and succeeded), or
            ``"failed"`` (mvn install ran and exited non-zero).
        missing: Artifacts found to be missing when the check ran.
            When ``action == "skipped"`` this is empty.
        command: The exact mvn command that was executed (or would
            have been), as a list of arguments. Empty list when no
            command was run.
        exit_code: The mvn process exit code, or ``None`` when no
            command was run.
        duration_seconds: Wall-clock time of the mvn invocation
            (``0.0`` when skipped).
        reason: A short human-readable summary; useful for logging.
    """

    action: str
    missing: list = field(default_factory=list)
    command: list = field(default_factory=list)
    exit_code: Optional[int] = None
    duration_seconds: float = 0.0
    reason: str = ""


# --------------------------------------------------------------------- #
# POM helpers
# --------------------------------------------------------------------- #


def _strip_ns(tag: str) -> str:
    """Drop the XML namespace prefix from an ElementTree tag."""
    return tag.split("}", 1)[-1] if "}" in tag else tag


def _child_text(element, name: str) -> Optional[str]:
    """Return the text of the first child element named ``name`` (namespace-agnostic)."""
    for child in element:
        if _strip_ns(child.tag) == name:
            return (child.text or "").strip() or None
    return None


def _parent_element(root):
    """Return the ``<parent>`` child of a ``<project>`` root, or ``None``."""
    for child in root:
        if _strip_ns(child.tag) == "parent":
            return child
    return None


def read_project_version(pom_path: Path) -> Optional[str]:
    """Resolve the project version declared in ``pom_path``.

    Handles parent inheritance: if ``<version>`` is missing on the
    module itself, looks at ``<parent><version>`` instead.

    Args:
        pom_path: Path to a ``pom.xml`` file.

    Returns:
        The version string, or ``None`` if neither the project nor
        its parent declares one.
    """
    try:
        root = ElementTree.parse(str(pom_path)).getroot()
    except (ElementTree.ParseError, OSError):
        return None
    version = _child_text(root, "version")
    if version:
        return version
    parent = _parent_element(root)
    if parent is not None:
        return _child_text(parent, "version")
    return None


def _dependency_elements(root) -> list:
    """Yield each ``<dependency>`` element under ``<dependencies>``.

    Only the direct module-level ``<dependencies>`` block is consulted
    — ``<dependencyManagement>`` is intentionally ignored because it
    declares versions, not actual dependencies of this module.
    """
    deps = []
    for child in root:
        if _strip_ns(child.tag) != "dependencies":
            continue
        for dep in child:
            if _strip_ns(dep.tag) == "dependency":
                deps.append(dep)
    return deps


def read_ar_dependencies(pom_path: Path,
                         fallback_version: Optional[str] = None) -> list:
    """Return ``(artifactId, version)`` for every direct ``org.almostrealism`` dep.

    Args:
        pom_path: Path to the module's ``pom.xml``.
        fallback_version: Version to assume when a ``<dependency>``
            omits ``<version>`` (Maven would normally resolve it via
            ``<dependencyManagement>``; we treat it as the project
            version because every internal ``ar-*`` module shares the
            same version in this repo).

    Returns:
        A list of ``(artifactId, version)`` tuples. The list is
        deduplicated while preserving the declaration order.
    """
    try:
        root = ElementTree.parse(str(pom_path)).getroot()
    except (ElementTree.ParseError, OSError):
        return []

    seen = set()
    result = []
    for dep in _dependency_elements(root):
        group_id = _child_text(dep, "groupId")
        if group_id != AR_GROUP_ID:
            continue
        artifact_id = _child_text(dep, "artifactId")
        if not artifact_id:
            continue
        version = _child_text(dep, "version") or fallback_version
        if not version:
            continue
        key = (artifact_id, version)
        if key in seen:
            continue
        seen.add(key)
        result.append(key)
    return result


# --------------------------------------------------------------------- #
# Local Maven repository inspection
# --------------------------------------------------------------------- #


def _resolve_repository(repository: Optional[Path]) -> Path:
    """Resolve a repository path, honoring the module-level default.

    Looking up :data:`DEFAULT_M2_REPOSITORY` at call time (rather than
    using it as a default argument) lets tests replace the module
    attribute without having to thread the override through every
    caller.
    """
    return DEFAULT_M2_REPOSITORY if repository is None else repository


def artifact_jar_path(repository: Path, group_id: str, artifact_id: str,
                       version: str) -> Path:
    """Return the absolute path where Maven would install the artifact's jar."""
    base = repository.joinpath(*group_id.split("."), artifact_id, version)
    return base / f"{artifact_id}-{version}.jar"


def find_missing_upstream_artifacts(
        project_root: Path,
        module: str,
        repository: Optional[Path] = None) -> list:
    """Return the direct ``org.almostrealism`` deps not installed in ``~/.m2``.

    Args:
        project_root: Absolute path to the project root (the directory
            containing the reactor ``pom.xml``).
        module: Maven module path relative to the project root
            (e.g. ``engine/utils``, ``flowtree/runtime``).
        repository: The local Maven repository to inspect. Defaults to
            ``~/.m2/repository``. Tests pass a sandbox directory. When
            ``None``, the value of :data:`DEFAULT_M2_REPOSITORY` is
            consulted at call time, so monkey-patching the attribute
            in tests works without threading an override through every
            caller.

    Returns:
        A possibly-empty list of :class:`MissingArtifact`. An empty
        list means every direct AR dependency is already installed,
        so a preflight ``mvn install`` is not needed.

        Returns an empty list when the module's pom cannot be read at
        all — the caller can detect that case by inspecting whether
        the path exists before calling this function. The intent is
        that "no parseable pom" is treated as "no preflight required";
        Maven itself will report any genuine module-resolution error
        with a clear message when the test launches.
    """
    repository = _resolve_repository(repository)
    module_pom = Path(project_root) / module / "pom.xml"
    if not module_pom.exists():
        return []

    root_pom = Path(project_root) / "pom.xml"
    project_version = (read_project_version(root_pom)
                       or read_project_version(module_pom))
    deps = read_ar_dependencies(module_pom, fallback_version=project_version)

    missing = []
    for artifact_id, version in deps:
        jar = artifact_jar_path(repository, AR_GROUP_ID, artifact_id, version)
        if not jar.is_file():
            missing.append(MissingArtifact(
                artifact_id=artifact_id,
                version=version,
                expected_path=jar,
            ))
    return missing


# --------------------------------------------------------------------- #
# Seeding
# --------------------------------------------------------------------- #


def build_seed_command(module: str) -> list:
    """Return the Maven command used to seed a module's upstream chain.

    The ``-am`` (also-make) flag tells Maven to build all reactor
    modules that the target depends on, transitively. ``-DskipTests``
    keeps the seed fast — we only need the jars on disk, not the
    test suite.
    """
    return ["mvn", "-pl", module, "-am", "install", "-DskipTests", "-B"]


def _run_seed_command(
        command: list,
        project_root: Path,
        output_writer: Optional[Callable[[str], None]] = None,
        runner: Optional[Callable[[list, Path, Optional[Callable[[str], None]]], int]] = None,
) -> tuple[int, float]:
    """Execute the seed command, capturing output through ``output_writer``.

    Args:
        command: Argument vector to launch.
        project_root: Working directory for the subprocess.
        output_writer: Optional callable invoked once per output chunk.
        runner: Optional override for the subprocess driver. Used by
            tests to avoid spawning a real Maven process.

    Returns:
        ``(exit_code, duration_seconds)``.
    """
    start = time.monotonic()
    if runner is not None:
        exit_code = runner(command, project_root, output_writer)
    else:
        exit_code = _default_runner(command, project_root, output_writer)
    duration = time.monotonic() - start
    return exit_code, duration


def _default_runner(
        command: list,
        project_root: Path,
        output_writer: Optional[Callable[[str], None]],
) -> int:
    """Real subprocess driver used in production.

    Streams stdout (with stderr merged) to ``output_writer`` line by
    line so a watcher reading the run's ``output.txt`` can follow the
    seed in real time.
    """
    env = os.environ.copy()
    # AR_HARDWARE_LIBS is auto-detected by the system; never inject it.
    env.pop("AR_HARDWARE_LIBS", None)

    process = subprocess.Popen(
        command,
        cwd=str(project_root),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
    )
    try:
        if process.stdout is not None:
            for raw in process.stdout:
                if output_writer is not None:
                    try:
                        output_writer(raw.decode("utf-8", errors="replace"))
                    except Exception:
                        # An output-writer failure must never break the seed.
                        pass
    finally:
        process.wait()
    return process.returncode


def seed_upstream_artifacts(
        project_root: Path,
        module: str,
        output_writer: Optional[Callable[[str], None]] = None,
        repository: Optional[Path] = None,
        runner: Optional[Callable[[list, Path, Optional[Callable[[str], None]]], int]] = None,
) -> PreflightResult:
    """Install upstream ``ar-*`` artifacts for ``module`` when any are missing.

    The check-then-install sequence is:

    1. Parse the module's ``pom.xml`` for direct ``org.almostrealism``
       dependencies.
    2. For each, look for the corresponding ``.jar`` in ``repository``.
    3. If none are missing, return a ``"skipped"`` result without
       launching any subprocess.
    4. Otherwise, run ``mvn -pl <module> -am install -DskipTests -B``
       from ``project_root``. ``-am`` ensures the entire upstream
       chain is built and installed.

    Args:
        project_root: Project root (contains the reactor ``pom.xml``).
        module: Module path relative to the project root.
        output_writer: Optional callable invoked with each output
            chunk emitted by Maven, so a caller can persist the
            seed log alongside the test output.
        repository: Local Maven repository to consult. Defaults to
            :data:`DEFAULT_M2_REPOSITORY` (looked up at call time so
            tests can monkey-patch it).
        runner: Optional subprocess driver override; tests use this
            to avoid actually running Maven.

    Returns:
        A :class:`PreflightResult` describing what happened.
    """
    project_root = Path(project_root)
    repository = _resolve_repository(repository)
    missing = find_missing_upstream_artifacts(project_root, module, repository)

    if not missing:
        return PreflightResult(
            action="skipped",
            reason="All direct org.almostrealism dependencies already installed",
        )

    command = build_seed_command(module)
    exit_code, duration = _run_seed_command(
        command, project_root, output_writer=output_writer, runner=runner)

    action = "seeded" if exit_code == 0 else "failed"
    if exit_code == 0:
        reason = (f"Seeded {len(missing)} missing upstream artifact(s) "
                  f"in {duration:.1f}s")
    else:
        reason = (f"mvn install exited with code {exit_code} "
                  f"after {duration:.1f}s")
    return PreflightResult(
        action=action,
        missing=missing,
        command=command,
        exit_code=exit_code,
        duration_seconds=duration,
        reason=reason,
    )


# --------------------------------------------------------------------- #
# Installed-artifact age reporting
# --------------------------------------------------------------------- #
#
# ``mvn test -pl <module>`` (the command this server runs) compiles *only*
# the module under test. Every ``org.almostrealism`` dependency — direct or
# transitive — is resolved from whatever jar is already installed in
# ``~/.m2``. If the working tree for one of those dependencies was edited but
# not reinstalled, the test silently runs against stale bytecode. (This is
# not hypothetical: a base-layer ``ar-code`` change was a *transitive* dep of
# ``engine/ml`` and ran stale for a whole session before anyone noticed.)
#
# The report below mirrors the Claude PreToolUse staleness hook
# (``.claude/hooks/mvn-artifact-staleness.py``) but is emitted from inside
# ar-test-runner so the same age table is visible to every client, not just
# Claude's Bash channel. It lists every module artifact (not just direct
# dependencies) precisely because the dangerous case is usually a transitive
# one, and marks the single module this run recompiles.


def _parse_module_coords(pom_path: Path) -> Optional[tuple]:
    """Return ``(groupId, artifactId, version, packaging)`` for a ``pom.xml``.

    Resolves parent inheritance for ``groupId`` and ``version`` (internal
    ``ar-*`` modules omit both and inherit them from the reactor parent).
    ``packaging`` defaults to ``"jar"`` when unspecified.

    Args:
        pom_path: Path to the module's ``pom.xml``.

    Returns:
        The coordinate tuple, or ``None`` when the pom cannot be parsed
        or declares no ``artifactId``.
    """
    try:
        root = ElementTree.parse(str(pom_path)).getroot()
    except (ElementTree.ParseError, OSError):
        return None
    artifact_id = _child_text(root, "artifactId")
    if not artifact_id:
        return None
    group_id = _child_text(root, "groupId")
    version = _child_text(root, "version")
    packaging = _child_text(root, "packaging") or "jar"
    parent = _parent_element(root)
    if parent is not None:
        if not group_id:
            group_id = _child_text(parent, "groupId")
        if not version:
            version = _child_text(parent, "version")
    return (group_id, artifact_id, version, packaging)


def find_repo_module_artifacts(
        project_root: Path,
        repository: Optional[Path] = None) -> list:
    """Return the installed-artifact age of every ``org.almostrealism`` module.

    Walks the project tree for ``pom.xml`` files, and for each
    ``org.almostrealism`` module looks up the corresponding jar in the local
    Maven repository.

    Args:
        project_root: Absolute path to the project root.
        repository: Local Maven repository to inspect. Defaults to
            ``~/.m2/repository`` (resolved at call time so tests can
            monkey-patch :data:`DEFAULT_M2_REPOSITORY`).

    Returns:
        A list of ``(rel_path, artifact_id, version, mtime)`` tuples, where
        ``rel_path`` is the module directory relative to ``project_root`` and
        ``mtime`` is the jar's last-modified time in epoch seconds, or
        ``None`` when the artifact is not installed. Order follows the
        filesystem walk; callers sort as needed.
    """
    repository = _resolve_repository(repository)
    project_root = Path(project_root)
    skip = {"target", ".git", "node_modules", ".idea"}
    rows = []
    for dirpath, dirnames, filenames in os.walk(str(project_root)):
        dirnames[:] = [d for d in dirnames if d not in skip]
        if "pom.xml" not in filenames:
            continue
        coords = _parse_module_coords(Path(dirpath) / "pom.xml")
        if not coords:
            continue
        group_id, artifact_id, version, packaging = coords
        # Aggregator/parent poms (packaging=pom) install no jar and are never
        # test dependencies, so they would only add misleading NOT-INSTALLED
        # rows. Restrict the report to installable ar-* jar artifacts.
        if group_id != AR_GROUP_ID or not version or packaging == "pom":
            continue
        rel = os.path.relpath(dirpath, str(project_root))
        jar = artifact_jar_path(repository, group_id, artifact_id, version)
        mtime = jar.stat().st_mtime if jar.is_file() else None
        rows.append((rel, artifact_id, version, mtime))
    return rows


def format_artifact_age_report(
        project_root: Path,
        module: str,
        repository: Optional[Path] = None) -> str:
    """Render the installed-artifact staleness banner for a test run.

    Explains that ``mvn test -pl <module>`` recompiles only ``module`` and
    resolves all dependencies from ``~/.m2``, then tabulates every module
    artifact's installed age (oldest first) so a stale dependency is obvious.
    The module recompiled by this run is flagged with ``*``.

    Args:
        project_root: Absolute path to the project root.
        module: Module path under test, relative to the project root
            (e.g. ``engine/ml``).
        repository: Local Maven repository to inspect. Defaults to
            ``~/.m2/repository``.

    Returns:
        A multi-line string suitable for writing into the run's output as a
        preflight section body.
    """
    module_norm = module.rstrip("/")
    rows = find_repo_module_artifacts(project_root, repository)
    # Oldest installed first; NOT-INSTALLED artifacts float to the very top.
    rows.sort(key=lambda r: (r[3] is not None, r[3] if r[3] else 0.0))

    lines = [
        f"`mvn test -pl {module_norm}` (no -am) recompiles ONLY {module_norm}.",
        "Every org.almostrealism dependency below — direct or transitive — is",
        "resolved from its already-installed ~/.m2 jar and is NOT rebuilt by",
        "this run. If you edited a dependency's sources, its jar is stale until",
        "you reinstall it, e.g. `mvn install -DskipTests -pl <module> -am`",
        "(or `mvn clean install -DskipTests` from the root to refresh all).",
        "",
    ]
    if not rows:
        lines.append("No org.almostrealism module artifacts were found to report.")
        return "\n".join(lines)

    name_w = max(len(r[1]) for r in rows)
    lines.append("Installed module artifacts in ~/.m2 (oldest first; "
                 "* = recompiled by this run):")
    for rel, artifact_id, version, mtime in rows:
        when = (datetime.utcfromtimestamp(mtime).strftime("%Y-%m-%d %H:%M:%S")
                if mtime else "NOT INSTALLED      ")
        marker = "*" if rel == module_norm else " "
        lines.append(f"  {marker} {when}  {artifact_id.ljust(name_w)}  {rel}")
    return "\n".join(lines)
