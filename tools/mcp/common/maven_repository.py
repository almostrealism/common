"""Where Maven puts installed and downloaded artifacts in the local repository.

A FlowTree job runs every Maven invocation with a split local repository
(Maven 3.9), configured through the ``MAVEN_ARGS`` environment variable the
harness sets on the agent process -- see ``MavenInstallSpace`` in
``flowtree/runtime``::

    MAVEN_ARGS=-Daether.enhancedLocalRepository.split=true
               -Daether.enhancedLocalRepository.localPrefix=flowtree-jobs/<task id>

With the split on, ``mvn install`` writes to ``<repository>/<localPrefix>/``
(the job's own space) and downloads go to ``<repository>/<remotePrefix>/``
(``cached`` by default, shared by every job on the node). Without it, both
live directly under ``<repository>``.

Tools that look for an installed ``ar-*`` jar must look where Maven will
resolve it from, or they report a jar Maven will never use as present. This
module is the single reading of that layout; the test-runner preflight, the
JMX heap analyzer and the artifact-staleness hook
(``.claude/hooks/mvn-artifact-staleness.py``) read it through here.

The repository base is, in Maven's own order of precedence:

1. ``-Dmaven.repo.local=<dir>`` on the command line, then in ``MAVEN_ARGS``
   (prepended to the command line, so a later command-line value wins), then
   in ``MAVEN_OPTS`` (a JVM system property, which command-line ``-D``
   values override). CI's ``select-maven-repo`` action relocates the
   repository this way.
2. ``FLOWTREE_MAVEN_LOCAL_REPOSITORY``, which a job exports
   (``MavenInstallSpace.applyTo`` sets it to the resolved base, the same
   location the job's Java cleanup uses). Reading it here is what lets these
   tools follow the node's ``settings.xml`` when it relocates the local
   repository instead of seeding and inspecting the default.
3. ``~/.m2/repository``.

:func:`install_location` resolves all of it for one Maven invocation.
"""

from __future__ import annotations

import os
import shlex
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional, Sequence

SPLIT_PROPERTY = "aether.enhancedLocalRepository.split"
LOCAL_PREFIX_PROPERTY = "aether.enhancedLocalRepository.localPrefix"
REMOTE_PREFIX_PROPERTY = "aether.enhancedLocalRepository.remotePrefix"
REPO_LOCAL_PROPERTY = "maven.repo.local"

# Every property that decides where install_location() resolves, in the order
# a command re-emitting them should write them.
LOCATION_PROPERTIES = (REPO_LOCAL_PROPERTY, SPLIT_PROPERTY,
                       LOCAL_PREFIX_PROPERTY, REMOTE_PREFIX_PROPERTY)

# Environment variable a FlowTree job exports with the resolved local
# repository base (MavenInstallSpace.LOCAL_REPOSITORY_ENV). Unset outside a
# job, where the base is the user's default ``~/.m2/repository``.
LOCAL_REPOSITORY_ENV = "FLOWTREE_MAVEN_LOCAL_REPOSITORY"

# Resolver defaults for the two prefixes when the split is on.
DEFAULT_LOCAL_PREFIX = "installed"
DEFAULT_REMOTE_PREFIX = "cached"


@dataclass(frozen=True)
class InstallLocation:
    """Where one Maven invocation installs and resolves project artifacts.

    Attributes:
        repository: The local repository base.
        root: The directory ``mvn install`` writes to: ``repository``, or
            its local-prefix subdirectory when the split is enabled.
        origin: A short account of which setting chose ``root``, for
            messages that must say where they looked.
    """

    repository: Path
    root: Path
    origin: str


def default_repository() -> Path:
    """Return the local Maven repository base for the current environment.

    ``-Dmaven.repo.local`` in ``MAVEN_ARGS`` or ``MAVEN_OPTS`` wins, as it
    does for Maven. Otherwise a FlowTree job's exported
    :data:`LOCAL_REPOSITORY_ENV` is used, so a job whose ``settings.xml``
    relocates the local repository is inspected where Maven actually writes
    rather than at the default. Falls back to ``~/.m2/repository``.
    """
    return install_location().repository


def maven_properties(maven_args: Optional[str] = None) -> dict:
    """Return the ``-Dkey=value`` properties named in ``MAVEN_ARGS``.

    Args:
        maven_args: The arguments to read. ``None`` reads the
            ``MAVEN_ARGS`` environment variable.

    Returns:
        A dict of property name to value; a bare ``-Dkey`` maps to
        ``"true"``, as Maven treats it. A later definition wins.
    """
    if maven_args is None:
        maven_args = os.environ.get("MAVEN_ARGS", "")
    return command_properties(_split_arguments(maven_args))


def command_properties(tokens: Sequence[str]) -> dict:
    """Return the ``-D`` properties in an already-split argument list.

    Both ``-Dkey=value`` and Maven's separated ``-D key=value`` form are
    read; a bare ``-Dkey`` maps to ``"true"``. A later definition wins.
    """
    properties = {}
    pending = False
    for token in tokens:
        if pending:
            definition, pending = token, False
        elif token == "-D":
            pending = True
            continue
        elif token.startswith("-D"):
            definition = token[2:]
        else:
            continue
        key, sep, value = definition.partition("=")
        if key:
            properties[key] = value if sep else "true"
    return properties


def _layered_properties(environ: Mapping[str, str],
                        command_args: Sequence[str] = ()) -> tuple:
    """Merge the ``-D`` properties as Maven layers them for one invocation.

    ``MAVEN_OPTS``, then ``MAVEN_ARGS``, then the command line, each
    overriding the one before. Both this resolution and the split-prefix
    helpers read through here, so every tool that asks where Maven installs
    gets the same answer whichever layer the split was configured on.

    Returns:
        A ``(merged, sources)`` pair: the winning value of each property, and
        the layer name its winning definition came from.
    """
    merged = {}
    sources = {}
    for name, properties in (
            ("MAVEN_OPTS", maven_properties(environ.get("MAVEN_OPTS", ""))),
            ("MAVEN_ARGS", maven_properties(environ.get("MAVEN_ARGS", ""))),
            ("the command line", command_properties(command_args))):
        merged.update(properties)
        sources.update(dict.fromkeys(properties, name))
    return merged, sources


def install_location(command_args: Sequence[str] = (),
                     environ: Optional[Mapping[str, str]] = None) -> InstallLocation:
    """Resolve where a Maven invocation installs project artifacts.

    Args:
        command_args: The arguments given to ``mvn`` on its command line.
            Empty for a process that inherits the environment and adds no
            repository flag of its own (the ar-test-runner's Maven runs).
        environ: The environment Maven runs with; ``None`` means
            ``os.environ``.

    Returns:
        The repository base, the install root and how they were chosen.
        Properties are layered ``MAVEN_OPTS``, then ``MAVEN_ARGS``, then the
        command line, each overriding the one before, as in Maven.
    """
    if environ is None:
        environ = os.environ
    merged, sources = _layered_properties(environ, command_args)

    if merged.get(REPO_LOCAL_PROPERTY):
        repository = Path(os.path.expanduser(merged[REPO_LOCAL_PROPERTY]))
        origin = f"{REPO_LOCAL_PROPERTY} from {sources[REPO_LOCAL_PROPERTY]}"
    elif environ.get(LOCAL_REPOSITORY_ENV):
        repository = Path(environ[LOCAL_REPOSITORY_ENV])
        origin = LOCAL_REPOSITORY_ENV
    else:
        repository = Path.home() / ".m2" / "repository"
        origin = "the default local repository"

    root = _prefixed(repository, merged, LOCAL_PREFIX_PROPERTY, DEFAULT_LOCAL_PREFIX)
    if root != repository:
        origin += f", split local repository from {sources[SPLIT_PROPERTY]}"
    return InstallLocation(repository=repository, root=root, origin=origin)


def installed_root(repository: Optional[Path] = None,
                   maven_args: Optional[str] = None) -> Path:
    """Return the directory ``mvn install`` writes artifacts to.

    Args:
        repository: The local repository. ``None`` means
            :func:`default_repository`.
        maven_args: ``None`` resolves the split through the layered Maven
            environment, as :func:`install_location` does; an explicit string
            reads only that string, as for :func:`maven_properties`.

    Returns:
        ``repository`` itself, or ``repository/<localPrefix>`` when the
        split local repository is enabled.
    """
    return _split_root(repository, maven_args,
                       LOCAL_PREFIX_PROPERTY, DEFAULT_LOCAL_PREFIX)


def downloaded_root(repository: Optional[Path] = None,
                    maven_args: Optional[str] = None) -> Path:
    """Return the directory artifacts downloaded from remote repositories go to.

    Args:
        repository: The local repository. ``None`` means
            :func:`default_repository`.
        maven_args: ``None`` resolves the split through the layered Maven
            environment, as :func:`install_location` does; an explicit string
            reads only that string, as for :func:`maven_properties`.

    Returns:
        ``repository`` itself, or ``repository/<remotePrefix>`` when the
        split local repository is enabled.
    """
    return _split_root(repository, maven_args,
                       REMOTE_PREFIX_PROPERTY, DEFAULT_REMOTE_PREFIX)


def _split_root(repository: Optional[Path], maven_args: Optional[str],
                prefix_property: str, default_prefix: str) -> Path:
    """Return ``repository``, or its ``prefix_property`` subdirectory when split.

    With ``maven_args`` ``None`` the split properties are resolved through the
    same layered environment (``MAVEN_OPTS``, ``MAVEN_ARGS``, in that order) as
    :func:`install_location`, so a split enabled on ``MAVEN_OPTS`` is honoured
    here too and the shared resolver does not disagree with itself about where
    Maven installs. An explicit ``maven_args`` string reads only that string.
    """
    repository = default_repository() if repository is None else Path(repository)
    if maven_args is None:
        properties, _ = _layered_properties(os.environ)
    else:
        properties = maven_properties(maven_args)
    return _prefixed(repository, properties, prefix_property, default_prefix)


def _prefixed(repository: Path, properties: Mapping[str, str],
              prefix_property: str, default_prefix: str) -> Path:
    """Return ``repository``, or its prefix subdirectory when ``properties`` split it."""
    if properties.get(SPLIT_PROPERTY, "false").lower() != "true":
        return repository
    return repository / properties.get(prefix_property, default_prefix)


def _split_arguments(arguments: str) -> list:
    """Split a shell-quoted argument string, falling back to whitespace."""
    try:
        return shlex.split(arguments)
    except ValueError:
        return arguments.split()
