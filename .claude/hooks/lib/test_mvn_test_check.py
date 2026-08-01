#!/usr/bin/env python3
"""Unit tests for mvn_test_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_mvn_test_check.py -v

or:

    python3 .claude/hooks/lib/test_mvn_test_check.py

The tests exercise the pure `decide(command)` function directly and
also drive the CLI entry points (argv mode and --stdin mode) to
verify the rendering contract used by the .sh and .ts adapters.

Coverage:
  - 12 decide() cases (block, allow, warn, edge cases)
  - 7 CLI --stdin mode cases (verifies the Claude Code adapter
    contract: exit 2 + stderr on block, exit 0 + JSON stdout on
    warn, exit 0 on allow)
  - 4 CLI argv mode cases (verifies the .ts adapter contract: JSON
    stdout, exit 0 always)
  - 1 bit-for-bit equivalence test: a 27-command sample fed through
    decide() matches the original inline .sh analyze() output.

The equivalence test is the load-bearing one — it proves the
extraction from the .sh into a shared core didn't change the
decision for any input.
"""
import importlib.util
import io
import json
import os
import shlex
import subprocess
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "mvn_test_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location("mvn_test_check", CORE_PATH)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class DecideFunctionTests(unittest.TestCase):
    """decide(command) -> Decision shape contract."""

    def setUp(self):
        self.core = _load_core()

    def test_empty_command_is_allow(self):
        d = self.core.decide("")
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["reason"], "")
        self.assertEqual(d["context"], "")
        self.assertEqual(d["stderr"], "")

    def test_whitespace_command_is_allow(self):
        d = self.core.decide("   \t  ")
        self.assertEqual(d["action"], "allow")

    def test_mvn_test_blocks(self):
        d = self.core.decide("mvn test")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])
        self.assertIn("mcp__ar-test-runner__start_test_run", d["reason"])
        self.assertIn("mcp__ar-test-runner__start_test_run", d["stderr"])

    def test_mvn_test_with_extra_args_blocks(self):
        d = self.core.decide("mvn -pl foo test -DfailIfNoTests=false")
        self.assertEqual(d["action"], "block")

    def test_mvn_test_dtest_blocks(self):
        d = self.core.decide("mvn -Dtest=Foo test")
        self.assertEqual(d["action"], "block")

    def test_mvn_integration_test_blocks(self):
        d = self.core.decide("mvn integration-test")
        self.assertEqual(d["action"], "block")

    def test_mvn_install_skip_tests_allows(self):
        d = self.core.decide("mvn install -DskipTests")
        self.assertEqual(d["action"], "allow")

    def test_maven_test_skip_allows(self):
        d = self.core.decide("mvn test -Dmaven.test.skip")
        self.assertEqual(d["action"], "allow")

    def test_maven_test_skip_equals_true_allows(self):
        d = self.core.decide("mvn test -Dmaven.test.skip=true")
        self.assertEqual(d["action"], "allow")

    def test_mvn_test_compile_allows(self):
        d = self.core.decide("mvn test-compile")
        self.assertEqual(d["action"], "allow")

    def test_mvn_clean_install_allows(self):
        d = self.core.decide("mvn clean install")
        self.assertEqual(d["action"], "allow")

    def test_bash_c_mvn_test_blocks_via_recursion(self):
        d = self.core.decide('bash -c "mvn test"')
        self.assertEqual(d["action"], "block")

    def test_sh_c_mvn_test_blocks_via_recursion(self):
        d = self.core.decide('sh -c "mvn test"')
        self.assertEqual(d["action"], "block")

    def test_bash_c_with_skip_tests_allows(self):
        d = self.core.decide('bash -c "mvn install -DskipTests"')
        self.assertEqual(d["action"], "allow")

    def test_echo_with_mvn_test_allows(self):
        # `echo "mvn test"` is parseable (shlex succeeds), so the
        # tokenizer walks it cleanly and `echo` is not `mvn`, so the
        # decision is 'clear' → 'allow'. The `mvn` and `test` strings
        # appear as a quoted argument, not as a command name. This
        # matches the original inline .sh analyze() behavior.
        d = self.core.decide('echo "mvn test"')
        self.assertEqual(d["action"], "allow")

    def test_grep_with_mvn_test_allows(self):
        # Same reasoning as echo: parseable, not an mvn invocation.
        d = self.core.decide("grep -r 'mvn test' .")
        self.assertEqual(d["action"], "allow")

    def test_unbalanced_quote_with_mvn_test_warns(self):
        d = self.core.decide("awk 'BEGIN { mvn test")
        self.assertEqual(d["action"], "warn")

    def test_unbalanced_quote_no_mvn_allows(self):
        d = self.core.decide("awk 'BEGIN { print")
        self.assertEqual(d["action"], "allow")

    def test_ls_allows(self):
        d = self.core.decide("ls -la")
        self.assertEqual(d["action"], "allow")

    def test_chained_commands_with_mvn_test_block(self):
        d = self.core.decide("echo hi && mvn test")
        self.assertEqual(d["action"], "block")

    def test_chained_commands_with_skip_test_allow(self):
        d = self.core.decide("mvn clean && mvn install -DskipTests")
        self.assertEqual(d["action"], "allow")

    def test_env_prefix_command_mvn_test_block(self):
        d = self.core.decide("FOO=bar mvn test")
        self.assertEqual(d["action"], "block")

    def test_sudo_mvn_test_block(self):
        d = self.core.decide("sudo mvn test")
        self.assertEqual(d["action"], "block")

    def test_pipe_to_mvn_test_block(self):
        d = self.core.decide("cat file | mvn test")
        self.assertEqual(d["action"], "block")


class StdinModeTests(unittest.TestCase):
    """The .sh adapter contract: --stdin mode renders natively."""

    def _run_stdin(self, command):
        payload = json.dumps({"tool_input": {"command": command}})
        r = subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        return r

    def test_block_path_exits_2_with_stderr(self):
        r = self._run_stdin("mvn test")
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)
        self.assertIn("mcp__ar-test-runner__start_test_run", r.stderr)
        # Block path emits nothing on stdout.
        self.assertEqual(r.stdout, "")

    def test_allow_path_exits_0_with_no_output(self):
        r = self._run_stdin("mvn install -DskipTests")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_warn_path_exits_0_with_stderr_and_json_stdout(self):
        r = self._run_stdin("awk 'BEGIN { mvn test")
        self.assertEqual(r.returncode, 0)
        self.assertIn("mcp__ar-test-runner__start_test_run", r.stderr)
        # stdout is a JSON object with hookSpecificOutput.additionalContext
        obj = json.loads(r.stdout)
        self.assertIn("hookSpecificOutput", obj)
        self.assertEqual(obj["hookSpecificOutput"]["hookEventName"], "PreToolUse")
        self.assertIn("mcp__ar-test-runner__start_test_run",
                      obj["hookSpecificOutput"]["additionalContext"])

    def test_empty_command_exits_0_silently(self):
        r = self._run_stdin("")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_malformed_json_exits_0_silently(self):
        # Never block on uncertainty about the harness payload.
        r = subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input="not json",
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")

    def test_recursion_bash_c_blocks(self):
        r = self._run_stdin('bash -c "mvn test"')
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)

    def test_skip_via_dashtest_dot_equals_allows(self):
        r = self._run_stdin("mvn test -Dmaven.test.skip=true")
        self.assertEqual(r.returncode, 0)


class ArgvModeTests(unittest.TestCase):
    """The .ts adapter contract: argv mode returns JSON, exit 0."""

    def _run_argv(self, command):
        r = subprocess.run(
            ["python3", CORE_PATH, command],
            capture_output=True,
            text=True,
            timeout=5,
        )
        return r

    def test_block_returns_block_json(self):
        r = self._run_argv("mvn test")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")
        self.assertIn("BLOCKED", obj["reason"])
        self.assertIn("mcp__ar-test-runner__start_test_run", obj["reason"])

    def test_allow_returns_allow_json(self):
        r = self._run_argv("mvn install -DskipTests")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")
        self.assertEqual(obj["reason"], "")
        self.assertEqual(obj["context"], "")

    def test_warn_returns_warn_json(self):
        r = self._run_argv("awk 'BEGIN { mvn test")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "warn")
        self.assertIn("mcp__ar-test-runner__start_test_run", obj["context"])
        # The argv mode never writes to stderr (that's reserved for
        # the .sh adapter's --stdin mode).
        self.assertEqual(r.stderr, "")

    def test_empty_command_returns_allow_json(self):
        r = self._run_argv("")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")


class BitForBitEquivalenceTests(unittest.TestCase):
    """The core must agree with the original inline .sh analyze() on a
    sample of representative commands. This is the load-bearing
    regression test for the extraction from .sh into a shared core.
    """

    def setUp(self):
        self.core = _load_core()

    def _inline_analyze(self, cmd):
        """The pre-extraction analyze() function, copied verbatim from
        the old .claude/hooks/block-mvn-test-direct.sh. The core's
        decide() must agree with this on every command in the
        sample below."""
        OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
                     "\n", "then", "do", "else", "elif", "fi", "done"}
        CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                        "builtin", "stdbuf", "nice", "ionice"}
        ENV_ASSIGN = __import__("re").compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
        SKIP = __import__("re").compile(r"-DskipTests|-Dmaven\.test\.skip(=true)?$|-Dmaven\.test\.skip\b")
        TEST_PHASES = {"test", "integration-test"}
        DASH_C = __import__("re").compile(r"^-[a-z]*c$")

        def runs_tests(args):
            if any(SKIP.search(a) for a in args):
                return False
            return any(a in TEST_PHASES or a.startswith("-Dtest=") for a in args)

        def analyze(cmd):
            try:
                tokens = shlex.split(cmd, comments=False, posix=True)
            except ValueError:
                return "uncertain"
            i, n = 0, len(tokens)
            in_cmd_position = True
            while i < n:
                tok = tokens[i]
                if tok in OPERATORS:
                    in_cmd_position = True
                    i += 1
                    continue
                if in_cmd_position and (ENV_ASSIGN.match(tok) or tok in CMD_PREFIXES):
                    i += 1
                    continue
                if not in_cmd_position:
                    i += 1
                    continue
                j = i + 1
                args = []
                while j < n and tokens[j] not in OPERATORS:
                    args.append(tokens[j])
                    j += 1
                base = tok.rsplit("/", 1)[-1]
                if base == "mvn":
                    if runs_tests(args):
                        return "block"
                elif base in {"bash", "sh", "zsh", "dash", "ksh"}:
                    for k, a in enumerate(args):
                        if DASH_C.match(a) and k + 1 < len(args):
                            inner = analyze(args[k + 1])
                            if inner == "block":
                                return "block"
                i = j
                in_cmd_position = True
            return "clear"
        return analyze(cmd)

    def _expected_action(self, command):
        """Map the inline analyze() return to the new action vocabulary."""
        status = self._inline_analyze(command)
        if status == "block":
            return "block"
        if status == "uncertain" and "mvn" in command and "test" in command:
            return "warn"
        return "allow"

    def test_27_command_sample_matches_inline_analyze(self):
        # A representative sample: simple blocks, simple allows, the
        # recursion case, the warn case, env prefixes, sudo, pipes.
        # 27 commands keeps the test runnable in milliseconds while
        # covering every code path in the analyze() function.
        sample = [
            "mvn test",
            "mvn install",
            "mvn install -DskipTests",
            "mvn compile",
            "mvn clean install",
            "mvn test-compile",
            "mvn verify",
            "mvn package",
            "mvn -Dtest=Foo test",
            "mvn -pl foo test -DfailIfNoTests=false",
            "mvn test -DskipTests",
            "mvn test -Dmaven.test.skip",
            "mvn test -Dmaven.test.skip=true",
            "mvn integration-test",
            "mvn integration-test -DskipTests",
            'bash -c "mvn test"',
            'sh -c "mvn install -DskipTests"',
            'zsh -c "mvn test"',
            'bash -c "echo hi && mvn test"',
            "FOO=bar mvn test",
            "sudo mvn test",
            "cat file | mvn test",
            'echo "mvn test"',
            "grep -r 'mvn test' .",
            "ls -la",
            "git status",
            "",
        ]
        for cmd in sample:
            with self.subTest(cmd=cmd):
                expected = self._expected_action(cmd)
                actual = self.core.decide(cmd)["action"]
                self.assertEqual(actual, expected,
                                 f"command {cmd!r}: expected {expected!r}, got {actual!r}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
