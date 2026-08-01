#!/usr/bin/env python3
"""Unit tests for mvn_build_check.py.

Covers:
  - decide() returns warn for every artifact-producing goal we care about
  - decide() returns warn for a goal still surfaces when -DskipTests
    is present (the steer is informational, the agent may still be
    seeding deps legitimately)
  - decide() returns allow for non-Maven commands and for Maven
    invocations that don't trigger an artifact goal
  - chained commands attribute goals to the correct segment
  - the CLI argv mode emits a Decision JSON on stdout

Run from the repo root::

    python3 -m unittest .claude/hooks/lib/test_mvn_build_check.py -v
"""
import importlib.util
import json
import os
import subprocess
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "mvn_build_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location("mvn_build_check", CORE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DecideFunctionTests(unittest.TestCase):
    """``decide(command)`` shape contract."""

    def setUp(self):
        self.core = _load_core()

    def test_empty_command_is_allow(self):
        d = self.core.decide("")
        self.assertEqual("allow", d["action"])
        self.assertEqual("", d["context"])

    def test_whitespace_command_is_allow(self):
        d = self.core.decide("   \t  ")
        self.assertEqual("allow", d["action"])

    def test_mvn_install_warns(self):
        d = self.core.decide("mvn install")
        self.assertEqual("warn", d["action"])
        self.assertIn("ar-build-validator", d["context"])
        self.assertIn("install", d["context"])

    def test_mvn_compile_warns(self):
        d = self.core.decide("mvn compile")
        self.assertEqual("warn", d["action"])
        self.assertIn("compile", d["context"])

    def test_mvn_package_warns(self):
        d = self.core.decide("mvn package")
        self.assertEqual("warn", d["action"])
        self.assertIn("package", d["context"])

    def test_mvn_install_with_skip_tests_still_warns(self):
        # We DO warn here, but the steer text explicitly says this is
        # informational and does not block — the agent is allowed to
        # proceed when seeding deps is the intent.
        d = self.core.decide("mvn install -DskipTests")
        self.assertEqual("warn", d["action"])
        self.assertIn("ar-build-validator", d["context"])
        self.assertIn("does not block", d["context"])

    def test_mvn_install_with_pl_and_am_warns(self):
        # The seed pattern emitted by ar-test-runner's preflight is
        # `mvn -pl <m> -am install -DskipTests -B`. We warn on it too,
        # but the agent's preflight runs out-of-band (it's the MCP
        # tool's own subprocess, not a bash tool call), so this only
        # affects bash invocations the agent issues directly.
        d = self.core.decide(
            "mvn -pl engine/utils -am install -DskipTests -B")
        self.assertEqual("warn", d["action"])

    def test_mvn_test_alone_is_allow(self):
        # `test` is NOT in ARTIFACT_GOALS — it's handled by the
        # block-mvn-test-direct hook, not this one. Avoid double-warn.
        d = self.core.decide("mvn test")
        self.assertEqual("allow", d["action"])

    def test_mvn_version_is_allow(self):
        d = self.core.decide("mvn -version")
        self.assertEqual("allow", d["action"])

    def test_mvn_help_is_allow(self):
        d = self.core.decide("mvn help:effective-pom")
        self.assertEqual("allow", d["action"])

    def test_non_mvn_command_is_allow(self):
        d = self.core.decide("ls -la")
        self.assertEqual("allow", d["action"])

    def test_echo_with_mvn_install_is_allow(self):
        # `echo "mvn install"` does not invoke mvn.
        d = self.core.decide('echo "mvn install"')
        self.assertEqual("allow", d["action"])

    def test_chained_mvn_install_after_npm_install_attributes_to_mvn(self):
        # `npm install` should NOT trigger the warn (no mvn in that
        # segment). The second segment has `mvn install`, which does.
        d = self.core.decide("npm install && mvn install -DskipTests")
        self.assertEqual("warn", d["action"])

    def test_chained_npm_install_alone_is_allow(self):
        d = self.core.decide("npm install && echo done")
        self.assertEqual("allow", d["action"])

    def test_mvnw_install_warns(self):
        # The wrapper script counts as mvn for steering purposes.
        d = self.core.decide("./mvnw install -DskipTests")
        self.assertEqual("warn", d["action"])

    def test_unparseable_quoting_falls_back_to_whitespace_split(self):
        # An unbalanced quote forces shlex to raise; the fallback
        # splitter should still detect a real mvn install.
        d = self.core.decide('mvn install "unterminated')
        self.assertEqual("warn", d["action"])


class DetectGoalsTests(unittest.TestCase):
    """``detect_artifact_goals(command)`` returns the right goals."""

    def setUp(self):
        self.core = _load_core()

    def test_returns_empty_for_non_mvn(self):
        self.assertEqual(set(), self.core.detect_artifact_goals("ls"))

    def test_returns_install_goal(self):
        self.assertEqual({"install"},
                         self.core.detect_artifact_goals("mvn install"))

    def test_returns_compile_and_package(self):
        self.assertEqual(
            {"compile", "package"},
            self.core.detect_artifact_goals("mvn compile package -X"),
        )

    def test_clean_alone_is_not_artifact_producing(self):
        # `clean` removes artifacts; it doesn't produce them. The
        # steer does NOT fire for a pure clean invocation.
        self.assertEqual(set(),
                         self.core.detect_artifact_goals("mvn clean"))

    def test_clean_install_returns_install(self):
        self.assertEqual({"install"},
                         self.core.detect_artifact_goals("mvn clean install"))


class CliEntryPointTests(unittest.TestCase):
    """``mvn_build_check.py <command>`` writes the Decision JSON to stdout."""

    def setUp(self):
        self.core = _load_core()

    def _run_argv(self, command):
        result = subprocess.run(
            [sys.executable, CORE_PATH, command],
            capture_output=True, text=True, check=False)
        return result

    def test_argv_warn_emits_json(self):
        result = self._run_argv("mvn install")
        self.assertEqual(0, result.returncode)
        d = json.loads(result.stdout)
        self.assertEqual("warn", d["action"])
        self.assertIn("ar-build-validator", d["context"])

    def test_argv_allow_emits_json(self):
        result = self._run_argv("ls")
        self.assertEqual(0, result.returncode)
        d = json.loads(result.stdout)
        self.assertEqual("allow", d["action"])
        self.assertEqual("", d["context"])

    def test_argv_handles_complex_quoting(self):
        result = self._run_argv("mvn install -DskipTests -Dxx='hi'")
        self.assertEqual(0, result.returncode)
        d = json.loads(result.stdout)
        self.assertEqual("warn", d["action"])

    def test_no_argv_exits_with_error(self):
        result = subprocess.run(
            [sys.executable, CORE_PATH], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
