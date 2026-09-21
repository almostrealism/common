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
"""Wires ``flowtree/runtime/test-rebuild.sh`` into a job the CI pipeline
already runs.

``test-rebuild.sh`` is ``rebuild.sh``'s own regression suite for the fleet
setup guards (secret generation, data directories, ``FLEET_BIND_ADDR``
detection): it mocks ``docker``/``mvn`` and redirects ``SECRETS_DIR`` /
``FLEET_DB_DATA_DIR`` / ``FLEET_GRAFANA_DATA_DIR`` at temporary directories,
so it never touches a real deployment. Nothing invoked it from CI — the
``python-tests`` job discovers only ``test_*.py`` under ``tools/tests``, and
no other job referenced the script by name — so those guards could regress
while the pipeline stayed green. This test is the wiring: it runs the shell
suite as a subprocess, so ``python-tests`` fails whenever
``test-rebuild.sh`` does.
"""

import os
import subprocess
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "flowtree", "runtime", "test-rebuild.sh")


class RebuildShRegressionTests(unittest.TestCase):

    def test_rebuild_sh_fleet_setup_guards_pass(self):
        result = subprocess.run(
            ["bash", _SCRIPT], capture_output=True, text=True, timeout=120
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
