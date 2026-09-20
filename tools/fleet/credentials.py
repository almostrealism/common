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
"""

from __future__ import annotations

import os


def read_secret_file(path: str) -> str:
    """Return the stripped contents of a credential file, refusing one others can read."""
    mode = os.stat(path).st_mode & 0o777
    if mode & 0o077:
        raise PermissionError("%s is readable by others (mode %o); it must be mode 600" % (path, mode))
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read().strip()
