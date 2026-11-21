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
"""Pins the deploy-time wiring that keeps the controller stack's operator
settings across a deploy.

A deploy runs from a fresh checkout whose ``actions/checkout`` clean step
deletes ignored files, including the compose project's gitignored ``.env``.
Any operator setting kept only there (``AR_CONSULTANT_BACKEND`` was the one
that bit us) therefore reset to its compose default on every deploy. The fix
is an operator settings file outside the checkout, located through
``FLOWTREE_CONTROLLER_ENV`` and carried into the compose ``.env`` by
``rebuild.sh``; the workflow reports the effective non-secret settings in the
job summary so a reset is visible on the deploy that causes it. These tests
hold both ends of that wiring in place so a later edit to either file cannot
silently drop it.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_DEPLOY = os.path.join(_REPO_ROOT, ".github", "workflows", "deploy.yaml")
_REBUILD = os.path.join(_REPO_ROOT, "flowtree", "runtime", "rebuild.sh")


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


class RebuildScriptWiringTest(unittest.TestCase):
    """``rebuild.sh`` reads the operator settings file and carries it into the
    compose env file that compose actually interpolates."""

    def setUp(self):
        self.script = _read(_REBUILD)

    def test_controller_env_is_located_outside_the_checkout(self):
        # The default lives beside the deploy secrets, and the location is
        # overridable the same way the agent env file is.
        self.assertIn('CONTROLLER_ENV="${FLOWTREE_CONTROLLER_ENV:-${SECRETS_DIR}/controller.env}"',
                      self.script)

    def test_compose_env_is_derived_from_the_operator_settings_file(self):
        # The compose env file is rewritten from CONTROLLER_ENV, not from its
        # own prior contents (which a deploy wipes).
        self.assertIn("write_compose_env", self.script)
        self.assertIn('grep -v \'^FLEET_BIND_ADDR=\' "${CONTROLLER_ENV}"', self.script)
        # The superseded helper, which seeded the compose env from itself and so
        # lost every operator setting on a deploy, must be gone.
        self.assertNotIn("persist_fleet_bind_addr", self.script)

    def test_operator_value_wins_over_the_computed_fleet_bind_addr(self):
        # An operator FLEET_BIND_ADDR in the settings file is consulted before
        # detection, so a value the operator sets wins over the computed default.
        self.assertIn('operator_setting FLEET_BIND_ADDR', self.script)

    def test_help_text_documents_the_file_and_the_precedence(self):
        self.assertIn("FLOWTREE_CONTROLLER_ENV", self.script)
        # The precedence an operator relies on is stated in the usage text.
        self.assertIn("wins over the default", self.script)


class _DeployWorkflow(unittest.TestCase):
    """Shared access to the parsed ``deploy`` job and its steps."""

    @classmethod
    def setUpClass(cls):
        cls.text = _read(_DEPLOY)
        cls.doc = yaml.safe_load(cls.text)
        cls.deploy_steps = cls.doc["jobs"]["deploy"]["steps"]

    def _step(self, name):
        for step in self.deploy_steps:
            if step.get("name") == name:
                return step
        self.fail("no deploy step named %r" % name)


class DeployWorkflowWiringTest(_DeployWorkflow):

    def test_rebuild_step_passes_the_controller_env_location(self):
        step = self._step("Rebuild and restart the controller stack")
        env = step.get("env", {})
        self.assertEqual(env.get("FLOWTREE_CONTROLLER_ENV"),
                         "${{ vars.FLOWTREE_CONTROLLER_ENV }}")

    def test_the_summary_reports_the_consultant_backend(self):
        step = self._step("Report effective operator settings")
        run = step["run"]
        # The backend name is the setting whose reset started this; it must be
        # one of the reported settings, written to the job summary.
        self.assertIn("AR_CONSULTANT_BACKEND", run)
        self.assertIn("GITHUB_STEP_SUMMARY", run)
        # Effective settings are read from the running services, not from the
        # checkout or the env file, so the summary reflects what actually came up.
        self.assertIn("docker compose", run)
        self.assertIn("printenv", run)

    def test_the_summary_never_prints_a_secret(self):
        step = self._step("Report effective operator settings")
        run = step["run"]
        # A credential-named setting is redacted, by name, before any value is
        # read — the guard that keeps a secret out of the summary.
        for marker in ("KEY", "TOKEN", "SECRET", "PASSWORD"):
            self.assertIn(marker, run, "secret-name guard missing %r" % marker)
        self.assertIn("redacted", run)
        # The whole-container environment is never dumped: only named settings
        # are read (printenv NAME), so an unlisted secret cannot leak. A bare
        # `printenv` with no argument, or `env`-dumping the container, would.
        self.assertNotIn("printenv\n", run)
        self.assertNotIn("printenv |", run)

    def test_no_api_key_or_token_setting_is_listed_for_reporting(self):
        # Belt and braces: the explicit report() calls name only non-secret
        # settings, so even without the redaction guard nothing secret is read.
        step = self._step("Report effective operator settings")
        for line in step["run"].splitlines():
            stripped = line.strip()
            if not stripped.startswith("report "):
                continue
            name = stripped.split()[-1]
            for marker in ("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD"):
                self.assertNotIn(marker, name,
                                 "a credential-named setting is listed for reporting: %s" % name)


class DeploySafetyPropertiesPreservedTest(_DeployWorkflow):
    """The change must not weaken any existing deploy safety property."""

    def test_the_drain_and_reopen_bracket_is_intact(self):
        names = [s.get("name") for s in self.deploy_steps]
        self.assertIn("Stop accepting new agent jobs", names)
        self.assertIn("Wait for in-flight agent jobs to finish", names)
        self.assertIn("Resume accepting agent jobs", names)

    def test_the_deploy_still_ships_master(self):
        checkout = self._step("Checkout master")
        self.assertEqual(checkout["with"]["ref"], "master")


if __name__ == "__main__":
    unittest.main()
