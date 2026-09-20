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
"""Reading the credentials the fleet services need, from files nobody else can read.

The collector carries a database credential (in its store URL) and the
poller a GitHub token. Neither may appear on a command line — ``ps`` shows
every account on the host every process's arguments — so both are read
from files, and a file that is group- or world-readable would hand the
credential to every account on the host, including the one that runs CI
jobs. The check is a hard failure, not a warning.

Known gap: :func:`read_secret_file` checks the POSIX mode bits only. On
macOS (the collector/poller's actual deployment target), a filesystem ACL
can grant another account read access while the mode bits still read
``0600`` — ``os.stat`` does not surface ACL entries, and inspecting them
portably from Python needs platform-specific tooling this module does not
have a way to exercise in CI (which runs on Linux). The primary defense
against that gap is procedural, not this check: the design requires the
collector and poller to run as an account that executes neither CI jobs nor
coding-agent jobs (see ``tools/fleet/README.md``), so an ACL grant would
have to specifically target that dedicated account to matter.
"""

from __future__ import annotations

import os
from typing import Optional


def reject_postgres_url_on_command_line(url: Optional[str], flag: str) -> None:
    """Refuse a Postgres URL supplied through a plain command-line *flag*.

    ``ps`` shows every account on the host every process's arguments (see
    the module docstring), so a Postgres DSN — which embeds the database
    password — must never reach a service through ``--store-url``/``--db``
    directly; only the corresponding ``--store-url-file``/``--db-url-file``
    (via :func:`read_secret_file`) may carry one. A sqlite path or URL is
    exempt: it carries no credential, so keeping it available directly on
    the command line costs nothing and keeps local/test usage simple.
    """
    if url is not None and url.startswith(("postgresql://", "postgres://")):
        raise ValueError(
            "%s must not be a Postgres URL: it would expose the database credential in "
            "this process's command line (visible to every account via `ps`); use the "
            "corresponding *-file flag to read it from a file instead" % flag
        )


def read_secret_file(path: str) -> str:
    """Return the stripped contents of a credential file, refusing one others can read.

    An empty (or whitespace-only) file is also refused: for a store URL, an
    empty string reaches :meth:`tools.fleet.store.FleetStore.from_url` and
    would otherwise be mistaken for "no URL configured" or silently open an
    unnamed temporary database, and for a GitHub token it would authenticate
    every request as anonymous instead of failing loudly.
    """
    mode = os.stat(path).st_mode & 0o777
    if mode & 0o077:
        raise PermissionError("%s is readable by others (mode %o); it must be mode 600" % (path, mode))
    with open(path, "r", encoding="utf-8") as handle:
        contents = handle.read().strip()
    if not contents:
        raise ValueError("%s is empty; it must contain a credential" % path)
    return contents
