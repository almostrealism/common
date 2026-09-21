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
"""Regression tests for the ``--user`` validation in
``tools/fleet/systemd/install.sh``.

The installer needs root, ``useradd`` and ``systemd`` to run to completion,
none of which are available in the test sandbox, so the bulk of the script
cannot be exercised here. But the ``--user`` guards (reject ``root``,
reject anything that is not a plain system-account name) run *before* the
script even checks that it was invoked with sudo, so they can be exercised
as an ordinary user: a bad value must fail on the validation message, and
a good value must fail one check later, on the "run with sudo" message -
proof that it was not rejected by the new guard.

This is the installer's regression test for a security defect: ``--user``
used to be interpolated unvalidated into a root ``rm -rf`` path (so a value
like ``foo/../../etc`` could make the installer delete an arbitrary
root-owned directory) and ``--user root`` was accepted (defeating the
dedicated-account isolation the installer exists to provide).
"""

import os
import subprocess
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "fleet", "systemd", "install.sh")


def _run(*args):
    return subprocess.run(
        ["bash", _SCRIPT, *args], capture_output=True, text=True, timeout=30
    )


class InstallShUserValidationTests(unittest.TestCase):

    def test_rejects_user_root(self):
        result = _run("--user", "root")
        self.assertEqual(1, result.returncode)
        self.assertIn("must not be root", result.stderr)

    def test_rejects_a_path_traversal_username(self):
        """The value that used to reach `rm -rf "${APP_DIR}"` unvalidated -
        must be rejected before FLEET_HOME/APP_DIR are ever derived from it."""
        result = _run("--user", "foo/../../etc")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid system account name", result.stderr)

    def test_rejects_a_whitespace_only_username(self):
        result = _run("--user", " ")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid system account name", result.stderr)

    def test_a_wholly_empty_username_is_rejected_by_the_pre_existing_arg_parser(self):
        """`--user ""` never reaches the new validation at all - bash's
        `${2:?msg}` already treats an empty value as unset and rejects it
        with its own message. Documented here so the two guards' coverage
        is explicit rather than assumed."""
        result = _run("--user", "")
        self.assertEqual(1, result.returncode)
        self.assertIn("--user needs a name", result.stderr)

    def test_rejects_a_username_with_an_uppercase_letter(self):
        result = _run("--user", "Fleet")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid system account name", result.stderr)

    def test_rejects_a_username_starting_with_a_digit(self):
        result = _run("--user", "1fleet")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid system account name", result.stderr)

    def test_accepts_a_valid_username_and_fails_later_on_the_root_check(self):
        result = _run("--user", "fleet-test")
        self.assertEqual(1, result.returncode)
        self.assertIn("run with sudo", result.stderr)
        self.assertNotIn("must not be root", result.stderr)
        self.assertNotIn("valid system account name", result.stderr)

    def test_default_username_is_unaffected(self):
        result = _run()
        self.assertEqual(1, result.returncode)
        self.assertIn("run with sudo", result.stderr)


if __name__ == "__main__":
    unittest.main()
