#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Validate that FlowTree agent containers cannot share data via a volume.

The single hard invariant for the agent pool is that **no two agent
containers may exchange data through a mounted volume**. A shared writable
directory is both a source of cross-workstream git collisions (see
``FLOWTREE_COLLISIONS.md``) and a covert data-exfiltration channel between
unrelated workstreams.

This script parses the agent ``docker-compose.yml`` and fails (exit 1) when
either of the following is true:

1. **Cross-agent sharing.** Some host path / named volume is mounted
   *writable* by one agent and is *also reachable* (writable or read-only,
   via the same path or an ancestor/descendant path) by a different agent.
   Read-only mounts shared across agents are fine — a read-only mount cannot
   carry data *from* one agent *to* another — so only writable sources open a
   channel.

2. **Guard drift.** Some agent has a writable mount whose target is not the
   single sanctioned sink ``/agent-transcripts``. The in-container guard in
   ``entrypoint.sh`` refuses to start such a container; this check fails CI
   for the same condition so the misconfiguration is caught before deploy.

The companion in-container guard (``entrypoint.sh``) enforces the *local*
half of the invariant (an agent permits only ``/agent-transcripts`` writable);
it cannot see other containers' mounts. This script enforces the *cross-agent*
half that only the compose file can express.

Usage::

    python tools/ci/validate_agent_volume_isolation.py [COMPOSE_FILE ...]

With no arguments it validates ``flowtree/runtime/agent/docker-compose.yml``
relative to the repository root.
"""

from __future__ import annotations

import os
import re
import sys
from typing import Dict, List, Optional, Tuple

import yaml

# The one mount point agents are allowed to have writable. Kept in lockstep
# with entrypoint.sh and OpencodeTranscriptWriter.WELL_KNOWN_TRANSCRIPT_DIR.
SANCTIONED_WRITABLE_TARGET = "/agent-transcripts"

# Path to the agent Dockerfile, used to recognise agent services regardless
# of how they are named.
AGENT_DOCKERFILE_SUFFIX = "flowtree/runtime/agent/Dockerfile"

# Matches ${VAR} and ${VAR:-default} / ${VAR:default} interpolation.
_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::?-([^}]*))?\}")


class Volume:
    """A single parsed volume mount on a service."""

    def __init__(self, source: Optional[str], target: str, read_only: bool):
        self.source = source
        self.target = target
        self.read_only = read_only

    @property
    def is_anonymous(self) -> bool:
        """Anonymous volumes (target only, no source) are per-container."""
        return self.source is None

    @property
    def is_bind(self) -> bool:
        """True when the source denotes a host path rather than a named volume."""
        return self.source is not None and (
            self.source.startswith("/") or self.source.startswith(".")
        )


def resolve_env(value: str) -> str:
    """Substitute ``${VAR}`` / ``${VAR:-default}`` interpolation in *value*.

    A variable with a default uses that default. A bare variable with no
    default is replaced by a stable placeholder unique to the variable name
    (``<<VAR>>``) so that two services referencing the same undefined variable
    still compare equal — i.e. accidental sharing through an unset variable is
    detected rather than hidden.
    """

    def _sub(match: "re.Match[str]") -> str:
        name, default = match.group(1), match.group(2)
        if default is not None:
            return default
        env = os.environ.get(name)
        if env is not None and env != "":
            return env
        return "<<%s>>" % name

    return _ENV_PATTERN.sub(_sub, value)


def parse_volume(entry) -> Optional[Volume]:
    """Parse one compose ``volumes`` entry (short string or long mapping)."""
    if isinstance(entry, dict):
        target = entry.get("target")
        if target is None:
            return None
        source = entry.get("source")
        read_only = bool(entry.get("read_only", False))
        return Volume(
            resolve_env(source) if source is not None else None,
            resolve_env(target),
            read_only,
        )

    if isinstance(entry, str):
        text = resolve_env(entry)
        # Split into at most 3 fields: SOURCE:TARGET:MODE. Anonymous volumes
        # are a single field (the target).
        parts = text.split(":")
        if len(parts) == 1:
            return Volume(None, parts[0], False)
        if len(parts) == 2:
            return Volume(parts[0], parts[1], False)
        source, target, mode = parts[0], parts[1], parts[2]
        read_only = "ro" in mode.split(",")
        return Volume(source, target, read_only)

    return None


def agent_services(compose: dict) -> Dict[str, List[Volume]]:
    """Return ``{service_name: [Volume, ...]}`` for every agent service.

    A service is treated as an agent when its name starts with ``agent`` or
    when its build ``dockerfile`` points at the agent Dockerfile.
    """
    services = compose.get("services") or {}
    result: Dict[str, List[Volume]] = {}
    for name, spec in services.items():
        if not isinstance(spec, dict):
            continue
        if not _is_agent_service(name, spec):
            continue
        volumes: List[Volume] = []
        for entry in spec.get("volumes") or []:
            parsed = parse_volume(entry)
            if parsed is not None:
                volumes.append(parsed)
        result[name] = volumes
    return result


def _is_agent_service(name: str, spec: dict) -> bool:
    if name == "agent" or name.startswith("agent-"):
        return True
    build = spec.get("build")
    if isinstance(build, dict):
        dockerfile = str(build.get("dockerfile", ""))
        if dockerfile.endswith(AGENT_DOCKERFILE_SUFFIX):
            return True
    return False


def _normalize(source: str) -> str:
    """Normalise a bind source for comparison."""
    return os.path.normpath(source)


def paths_overlap(a: str, b: str) -> bool:
    """True when bind sources *a* and *b* are the same or nested.

    Either being an ancestor of the other constitutes overlap: if agent A can
    write ``/data`` and agent B mounts ``/data/sub``, A can deposit a file for
    B to read.
    """
    na, nb = _normalize(a), _normalize(b)
    if na == nb:
        return True
    pa = na.split(os.sep)
    pb = nb.split(os.sep)
    shorter, longer = (pa, pb) if len(pa) <= len(pb) else (pb, pa)
    return longer[: len(shorter)] == shorter


def _sources_overlap(va: Volume, vb: Volume) -> bool:
    """True when two volumes resolve to a shared backing store."""
    if va.source is None or vb.source is None:
        return False
    if va.is_bind and vb.is_bind:
        return paths_overlap(va.source, vb.source)
    # Named volumes (or a named volume vs. a bind): they share storage only
    # when the source identifiers are exactly equal.
    return va.source == vb.source


def find_violations(compose: dict) -> List[str]:
    """Return a list of human-readable violation messages (empty when clean)."""
    agents = agent_services(compose)
    violations: List[str] = []

    # Check 2 (guard drift): writable mounts must target the sanctioned sink.
    for name, volumes in sorted(agents.items()):
        for vol in volumes:
            if vol.read_only or vol.is_anonymous:
                continue
            if vol.target != SANCTIONED_WRITABLE_TARGET:
                violations.append(
                    "%s has a writable mount targeting %r; the only writable "
                    "mount an agent may have targets %r (entrypoint.sh would "
                    "refuse to start this container)."
                    % (name, vol.target, SANCTIONED_WRITABLE_TARGET)
                )

    # Check 1 (cross-agent sharing): a source writable by one agent must not be
    # reachable by any other agent.
    names = sorted(agents)
    for i, a_name in enumerate(names):
        for b_name in names[i + 1:]:
            for va in agents[a_name]:
                if va.read_only or va.is_anonymous:
                    continue
                for vb in agents[b_name]:
                    if vb.is_anonymous:
                        continue
                    if _sources_overlap(va, vb):
                        violations.append(
                            "%s (writable %r -> %s) and %s (%r -> %s) share a "
                            "volume source; one agent could pass data to the "
                            "other. Give each agent a DISTINCT source."
                            % (
                                a_name, va.source, va.target,
                                b_name, vb.source, vb.target,
                            )
                        )
    return violations


def load_compose(path: str) -> dict:
    """Load a compose file, returning the parsed mapping."""
    with open(path, "r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def validate_file(path: str) -> List[str]:
    """Validate a single compose file, returning violation messages."""
    compose = load_compose(path)
    if not isinstance(compose, dict):
        return ["%s: not a valid compose mapping" % path]
    agents = agent_services(compose)
    if not agents:
        return ["%s: no agent services found to validate" % path]
    return find_violations(compose)


def _default_compose_path() -> str:
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return os.path.join(repo_root, "flowtree", "runtime", "agent", "docker-compose.yml")


def main(argv: List[str]) -> int:
    paths = argv[1:] or [_default_compose_path()]
    failed = False
    for path in paths:
        try:
            violations = validate_file(path)
        except FileNotFoundError:
            print("ERROR: compose file not found: %s" % path, file=sys.stderr)
            failed = True
            continue
        if violations:
            failed = True
            print("FAIL: %s" % path, file=sys.stderr)
            for message in violations:
                print("  - %s" % message, file=sys.stderr)
        else:
            print("OK: %s — no cross-agent volume sharing detected" % path)
    if failed:
        print(
            "\nAgent volume isolation check FAILED. Two agents must never be "
            "able to share data through a volume. See the volume policy in "
            "flowtree/runtime/agent/docker-compose.yml.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
