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
module is the single reading of that layout; the test-runner preflight and
the JMX heap analyzer read it through here. The artifact-staleness hook
(``.claude/hooks/mvn-artifact-staleness.py``) still constructs paths under the
unsplit ``~/.m2`` layout and has yet to be routed through this module -- see
the ``TODO`` below.
"""

from __future__ import annotations

import os
import shlex
from pathlib import Path
from typing import Optional

SPLIT_PROPERTY = "aether.enhancedLocalRepository.split"
LOCAL_PREFIX_PROPERTY = "aether.enhancedLocalRepository.localPrefix"
REMOTE_PREFIX_PROPERTY = "aether.enhancedLocalRepository.remotePrefix"

# Resolver defaults for the two prefixes when the split is on.
DEFAULT_LOCAL_PREFIX = "installed"
DEFAULT_REMOTE_PREFIX = "cached"

# TODO(review): .claude/hooks/mvn-artifact-staleness.py still reads the unsplit ~/.m2 layout; route it through installed_root().


def default_repository() -> Path:
    """Return the default local Maven repository, ``~/.m2/repository``."""
    return Path.home() / ".m2" / "repository"


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
    try:
        tokens = shlex.split(maven_args)
    except ValueError:
        tokens = maven_args.split()
    properties = {}
    for token in tokens:
        if not token.startswith("-D") or len(token) <= 2:
            continue
        key, sep, value = token[2:].partition("=")
        properties[key] = value if sep else "true"
    return properties


def installed_root(repository: Optional[Path] = None,
                   maven_args: Optional[str] = None) -> Path:
    """Return the directory ``mvn install`` writes artifacts to.

    Args:
        repository: The local repository. ``None`` means
            :func:`default_repository`.
        maven_args: As for :func:`maven_properties`.

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
        maven_args: As for :func:`maven_properties`.

    Returns:
        ``repository`` itself, or ``repository/<remotePrefix>`` when the
        split local repository is enabled.
    """
    return _split_root(repository, maven_args,
                       REMOTE_PREFIX_PROPERTY, DEFAULT_REMOTE_PREFIX)


def _split_root(repository: Optional[Path], maven_args: Optional[str],
                prefix_property: str, default_prefix: str) -> Path:
    """Return ``repository``, or its ``prefix_property`` subdirectory when split."""
    repository = default_repository() if repository is None else Path(repository)
    properties = maven_properties(maven_args)
    if properties.get(SPLIT_PROPERTY, "false").lower() != "true":
        return repository
    return repository / properties.get(prefix_property, default_prefix)
