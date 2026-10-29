#!/usr/bin/env python3
"""Unit tests for pytest_broad_run_check.py."""
import importlib.util
import json
import os
import subprocess
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "pytest_broad_run_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location("pytest_broad_run_check", CORE_PATH)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class DecideFunctionTests(unittest.TestCase):

    def setUp(self):
        self.core = _load_core()

    def assertAction(self, command, action):
        self.assertEqual(self.core.decide(command)["action"], action, command)

    def test_empty_command_is_allow(self):
        self.assertAction("", "allow")

    def test_node_id_allows(self):
        self.assertAction("pytest tests/test_foo.py::test_bar", "allow")

    def test_class_method_node_id_allows(self):
        self.assertAction("pytest tests/test_foo.py::TestFoo::test_bar", "allow")

    def test_whole_test_file_allows(self):
        # A file is the pytest counterpart of naming a class to Surefire.
        self.assertAction("python3 -m pytest -q tools/mcp/manager/test_server.py", "allow")

    def test_five_files_allow(self):
        files = " ".join("t/test_%d.py" % i for i in range(5))
        self.assertAction("pytest " + files, "allow")

    def test_six_files_block(self):
        files = " ".join("t/test_%d.py" % i for i in range(6))
        self.assertAction("pytest " + files, "block")

    def test_forty_node_ids_allow(self):
        ids = " ".join("t/test_a.py::test_%d" % i for i in range(40))
        self.assertAction("pytest " + ids, "allow")

    def test_forty_one_node_ids_block(self):
        ids = " ".join("t/test_a.py::test_%d" % i for i in range(41))
        self.assertAction("pytest " + ids, "block")

    def test_directory_blocks(self):
        d = self.core.decide("pytest tests/")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])

    def test_no_arguments_blocks(self):
        self.assertAction("pytest", "block")

    def test_options_only_blocks(self):
        self.assertAction("python3 -m pytest -q -x", "block")

    def test_keyword_filter_on_directory_blocks(self):
        self.assertAction("pytest tests/ -k test_bar", "block")

    def test_keyword_value_is_not_a_path(self):
        self.assertAction("pytest tests/test_foo.py -k test_bar", "allow")

    def test_mixed_directory_and_node_id_blocks(self):
        # A directory positional still runs the whole directory.
        self.assertAction("pytest tests/ tests/test_foo.py::test_bar", "block")

    def test_versioned_interpreter_with_flags_blocks(self):
        self.assertAction("python3.11 -O -m pytest tests/", "block")

    def test_python_module_pytest_without_path_blocks(self):
        self.assertAction("python3 -m pytest", "block")

    def test_non_pytest_command_allows(self):
        self.assertAction("ls -la tests/", "allow")

    def test_bash_c_recursion_blocks(self):
        self.assertAction('bash -c "pytest tests/"', "block")

    def test_bash_c_recursion_with_node_id_allows(self):
        self.assertAction('bash -c "pytest tests/test_foo.py::test_bar"', "allow")

    def test_chained_commands_block(self):
        self.assertAction("cd tools && pytest mcp/", "block")


class StdinModeTests(unittest.TestCase):

    def _run_stdin(self, command):
        payload = json.dumps({"tool_input": {"command": command}})
        return subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input=payload, capture_output=True, text=True, timeout=5,
        )

    def test_block_path_exits_2_with_stderr(self):
        r = self._run_stdin("pytest tests/")
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)

    def test_allow_path_exits_0_with_no_output(self):
        r = self._run_stdin("pytest tests/test_foo.py::test_bar")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")


class ArgvModeTests(unittest.TestCase):

    def _run_argv(self, command):
        return subprocess.run(
            [sys.executable, CORE_PATH, command],
            capture_output=True, text=True, timeout=5,
        )

    def test_block_returns_block_json(self):
        self.assertEqual(json.loads(self._run_argv("pytest tests/").stdout)["action"], "block")

    def test_allow_returns_allow_json(self):
        r = self._run_argv("pytest tests/test_foo.py::test_bar")
        self.assertEqual(json.loads(r.stdout)["action"], "allow")


if __name__ == "__main__":
    unittest.main(verbosity=2)
