#!/usr/bin/env python3
"""Unit tests for checkstyle_config_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_checkstyle_config_check.py -v

or:

    python3 .claude/hooks/lib/test_checkstyle_config_check.py

The tests exercise the pure `is_checkstyle_path` /
`bash_command_writes_checkstyle` / `decide` functions directly and
also drive the CLI entry points (argv mode and --stdin mode) to
verify the rendering contract used by the .sh and .ts adapters.

Coverage:
  - is_checkstyle_path()     : 20+ path cases (blocks + allows)
  - bash_command_writes_checkstyle() : 20+ command cases (bash bypass
    patterns from the task: redirection >> / >, sed -i, tee, cp/mv,
    install, cat >, python -c, etc.)
  - decide()                 : 12+ tool/tool_input cases across the
    five supported tools (bash, write, edit, multiedit, notebookedit)
  - --stdin mode             : the .sh adapter contract (exit 2 +
    stderr on block, exit 0 silent on allow)
  - argv mode                : the .ts adapter contract (JSON stdout,
    exit 0 always)

These are the load-bearing tests for the structural block — if any
of them fails, an agent that reaches for the cheat will be
let through.
"""
import importlib.util
import json
import os
import subprocess
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "checkstyle_config_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location(
        "checkstyle_config_check", CORE_PATH
    )
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


# ── Path matching ──────────────────────────────────────────────────────────


class IsCheckstylePathTests(unittest.TestCase):
    """is_checkstyle_path(path) is the core matcher."""

    def setUp(self):
        self.core = _load_core()

    # The two canonical basenames must always match.

    def test_root_checkstyle_xml_matches(self):
        self.assertTrue(self.core.is_checkstyle_path("checkstyle.xml"))

    def test_root_checkstyle_xml_with_dot_slash_matches(self):
        self.assertTrue(self.core.is_checkstyle_path("./checkstyle.xml"))

    def test_absolute_checkstyle_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path("/repo/checkstyle.xml")
        )

    def test_checkstyle_suppressions_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path("checkstyle-suppressions.xml")
        )

    def test_module_local_checkstyle_xml_matches(self):
        # Maven checkstyle plugin can be configured to use module-local
        # checkstyle.xml files (e.g. <configLocation> in a module's
        # pom.xml).  The matcher must cover those, not just the repo
        # root.
        self.assertTrue(
            self.core.is_checkstyle_path(
                "engine/ml/checkstyle.xml"
            )
        )

    def test_module_local_checkstyle_suppressions_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path(
                "engine/ml/checkstyle-suppressions.xml"
            )
        )

    # Files inside a /checkstyle/ directory.

    def test_checkstyle_directory_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path("config/checkstyle/extra.xml")
        )

    def test_checkstyle_directory_any_xml_matches(self):
        # Any .xml under a checkstyle/ directory is a checkstyle
        # config — even a file the agent has just created.
        self.assertTrue(
            self.core.is_checkstyle_path(
                "/tmp/checkstyle/new-rules.xml"
            )
        )

    # Filenames combining `checkstyle` and `suppress`.

    def test_module_prefixed_suppressions_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path(
                "engine-ml-checkstyle-suppressions.xml"
            )
        )

    def test_legacy_suppressions_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path(
                "checkstyle-suppressions-legacy.xml"
            )
        )

    # Case insensitivity.

    def test_checkstyle_uppercase_xml_matches(self):
        self.assertTrue(self.core.is_checkstyle_path("CHECKSTYLE.XML"))

    def test_mixed_case_suppressions_xml_matches(self):
        self.assertTrue(
            self.core.is_checkstyle_path("CheckStyle-Suppressions.xml")
        )

    # Non-matches (false-positive guards).

    def test_unrelated_xml_does_not_match(self):
        self.assertFalse(self.core.is_checkstyle_path("pom.xml"))

    def test_unrelated_java_does_not_match(self):
        self.assertFalse(self.core.is_checkstyle_path("Main.java"))

    def test_unrelated_md_does_not_match(self):
        self.assertFalse(self.core.is_checkstyle_path("README.md"))

    def test_substring_checkstyle_in_name_only_does_not_match(self):
        # `xcheckstyle.xml` is NOT under a /checkstyle/ directory and
        # the basename is not the canonical name, so it must not match.
        self.assertFalse(
            self.core.is_checkstyle_path("/tmp/xcheckstyle.xml")
        )

    def test_substring_suppress_in_name_only_does_not_match(self):
        # The matcher requires BOTH `checkstyle` and `suppress` for
        # the "filename contains both" rule.  A file called
        # `suppressions.xml` with no checkstyle in its name is not a
        # checkstyle config and must not be blocked.
        self.assertFalse(
            self.core.is_checkstyle_path("/tmp/suppressions.xml")
        )

    def test_empty_path_does_not_match(self):
        self.assertFalse(self.core.is_checkstyle_path(""))

    def test_none_path_does_not_match(self):
        self.assertFalse(self.core.is_checkstyle_path(None))

    def test_directory_only_path_does_not_match(self):
        # The matcher is for *file* paths.  A bare directory name
        # without an .xml file at the end must not match.
        self.assertFalse(self.core.is_checkstyle_path("checkstyle/"))

    def test_home_expansion_works(self):
        # Expanduser should not crash on a ~-prefixed path that
        # looks like a checkstyle config.
        self.assertTrue(
            self.core.is_checkstyle_path("~/checkstyle.xml")
        )


# ── Bash command analysis ──────────────────────────────────────────────────


class BashWritesCheckstyleTests(unittest.TestCase):
    """bash_command_writes_checkstyle(command) catches the bash bypass
    patterns the task explicitly calls out: redirection, sed -i, tee,
    cp, mv, cat >, python -c, etc.
    """

    def setUp(self):
        self.core = _load_core()

    # The big cheat patterns — every one must block.

    def test_redirect_overwrite_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "echo '<module/>' > checkstyle.xml"
            )
        )

    def test_redirect_append_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "printf '<module/>' >> checkstyle.xml"
            )
        )

    def test_leading_redirect_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle("> checkstyle.xml")
        )

    def test_sed_in_place_blocks(self):
        # `sed -i` rewrites the file in place, so the path is the
        # target even with no `>` redirect in sight.
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "sed -i 's/foo/bar/' checkstyle.xml"
            )
        )

    def test_tee_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "cat input.xml | tee checkstyle.xml > /dev/null"
            )
        )

    def test_tee_with_suppressions_path_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "tee checkstyle-suppressions.xml"
            )
        )

    def test_cp_into_checkstyle_blocks(self):
        # cp's destination is the second argument, and the
        # destination matches — block.
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "cp my-config.xml checkstyle.xml"
            )
        )

    def test_mv_into_checkstyle_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "mv /tmp/foo.xml checkstyle.xml"
            )
        )

    def test_cat_into_redirect_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "cat > checkstyle.xml"
            )
        )

    def test_python_script_redirect_to_checkstyle_blocks(self):
        # `python3 -c '...'` followed by `>` to a checkstyle file
        # must be caught.  The classic pattern is: emit XML on
        # stdout via python, redirect to the config file.
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "python3 -c \"print('<module/>')\" > checkstyle.xml"
            )
        )

    def test_absolute_path_redirect_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "echo bad > /repo/checkstyle.xml"
            )
        )

    def test_heredoc_into_checkstyle_blocks(self):
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "cat > checkstyle.xml <<'EOF'\n<module/>\nEOF"
            )
        )

    def test_path_under_checkstyle_directory_blocks(self):
        # Files in a /checkstyle/ directory are configs too.
        self.assertTrue(
            self.core.bash_command_writes_checkstyle(
                "cat > /tmp/checkstyle/new-rules.xml"
            )
        )

    # Read-only commands must NOT be blocked (a `cat checkstyle.xml`
    # is fine; a `grep checkstyle.xml` is fine).

    def test_cat_checkstyle_does_not_block(self):
        self.assertFalse(
            self.core.bash_command_writes_checkstyle("cat checkstyle.xml")
        )

    def test_grep_checkstyle_does_not_block(self):
        self.assertFalse(
            self.core.bash_command_writes_checkstyle(
                "grep -r 'checkstyle' ."
            )
        )

    def test_ls_checkstyle_does_not_block(self):
        self.assertFalse(
            self.core.bash_command_writes_checkstyle("ls -la checkstyle.xml")
        )

    def test_vim_checkstyle_does_not_block(self):
        # `vim` is interactive and not a write pattern the matcher
        # recognises — the structured Write/Edit tools are how the
        # agent edits files in this project, so the bash bypass for
        # editing is largely hypothetical.  We just verify the
        # matcher is silent on read-style `vim` calls.
        self.assertFalse(
            self.core.bash_command_writes_checkstyle("vim checkstyle.xml")
        )

    # Edge cases.

    def test_empty_command_does_not_block(self):
        self.assertFalse(self.core.bash_command_writes_checkstyle(""))

    def test_whitespace_command_does_not_block(self):
        self.assertFalse(self.core.bash_command_writes_checkstyle("   "))

    def test_unrelated_write_does_not_block(self):
        # Writing to a non-checkstyle file must pass.
        self.assertFalse(
            self.core.bash_command_writes_checkstyle("echo hi > pom.xml")
        )


# ── decide() top-level dispatch ────────────────────────────────────────────


class DecideTests(unittest.TestCase):
    """decide(tool, tool_input) routes to the right matcher per tool."""

    def setUp(self):
        self.core = _load_core()

    def test_write_to_checkstyle_xml_blocks(self):
        d = self.core.decide(
            "Write", {"file_path": "checkstyle.xml", "content": "x"}
        )
        self.assertEqual(d["action"], "block")
        self.assertIn("FORBIDDEN", d["reason"])
        self.assertIn("ABANDON", d["reason"])
        self.assertIn("checkstyle.xml", d["reason"])

    def test_edit_checkstyle_xml_blocks(self):
        d = self.core.decide(
            "Edit",
            {
                "file_path": "/repo/checkstyle.xml",
                "old_string": "a",
                "new_string": "b",
            },
        )
        self.assertEqual(d["action"], "block")

    def test_edit_suppressions_file_blocks(self):
        d = self.core.decide(
            "Edit",
            {"file_path": "checkstyle-suppressions.xml"},
        )
        self.assertEqual(d["action"], "block")

    def test_multiedit_checkstyle_blocks(self):
        d = self.core.decide(
            "MultiEdit", {"file_path": "engine/checkstyle/rules.xml"}
        )
        self.assertEqual(d["action"], "block")

    def test_notebookedit_checkstyle_blocks(self):
        d = self.core.decide(
            "NotebookEdit", {"file_path": "checkstyle.xml"}
        )
        self.assertEqual(d["action"], "block")

    def test_bash_redirect_to_checkstyle_blocks(self):
        d = self.core.decide(
            "Bash", {"command": "echo x > checkstyle.xml"}
        )
        self.assertEqual(d["action"], "block")

    def test_write_to_unrelated_file_allows(self):
        d = self.core.decide(
            "Write", {"file_path": "README.md", "content": "x"}
        )
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["reason"], "")

    def test_edit_unrelated_file_allows(self):
        d = self.core.decide(
            "Edit", {"file_path": "src/Foo.java", "old_string": "a", "new_string": "b"}
        )
        self.assertEqual(d["action"], "allow")

    def test_bash_read_of_checkstyle_allows(self):
        # Reading checkstyle config is fine.
        d = self.core.decide(
            "Bash", {"command": "cat checkstyle.xml"}
        )
        self.assertEqual(d["action"], "allow")

    def test_bash_grep_checkstyle_allows(self):
        d = self.core.decide(
            "Bash", {"command": "grep -r 'checkstyle' ."}
        )
        self.assertEqual(d["action"], "allow")

    def test_unknown_tool_allows(self):
        # An unknown tool name must never accidentally block work.
        d = self.core.decide("SomeNewTool", {"file_path": "checkstyle.xml"})
        self.assertEqual(d["action"], "allow")
        self.assertIn("unknown", d["stderr"])

    def test_none_tool_allows(self):
        d = self.core.decide(None, {"command": "echo x > checkstyle.xml"})
        self.assertEqual(d["action"], "allow")

    def test_none_tool_input_allows(self):
        d = self.core.decide("Write", None)
        self.assertEqual(d["action"], "allow")

    def test_empty_file_path_allows(self):
        # An empty file_path is a no-op (the harness supplies a real
        # path on every real Write/Edit call), not a reason to block.
        d = self.core.decide("Write", {"file_path": ""})
        self.assertEqual(d["action"], "allow")

    def test_block_message_is_all_caps_section(self):
        # The block reason must contain the same forceful, ALL-CAPS
        # phrasing called out in the task description.
        d = self.core.decide(
            "Write", {"file_path": "checkstyle.xml"}
        )
        self.assertIn("MODIFYING CHECKSTYLE CONFIGURATION IS FORBIDDEN", d["reason"])
        self.assertIn("NEVER ACCEPTABLE", d["reason"])
        self.assertIn("ABANDON THE TASK", d["reason"])
        self.assertIn("DECLARING FAILURE", d["reason"])
        self.assertIn("FIX THE CODE", d["reason"])

    def test_block_message_includes_target(self):
        d = self.core.decide(
            "Write", {"file_path": "/path/to/checkstyle.xml"}
        )
        # The target path must appear in the reason so the agent
        # knows what it was about to write.
        self.assertIn("/path/to/checkstyle.xml", d["reason"])

    def test_block_message_includes_tool(self):
        d = self.core.decide(
            "Edit", {"file_path": "checkstyle.xml"}
        )
        self.assertIn("Tool:", d["reason"])
        self.assertIn("edit", d["reason"].lower())

    def test_opencode_style_filePath_key_blocks(self):
        # The opencode adapter uses camelCase `filePath` rather than
        # Claude Code's snake_case `file_path`.  The matcher must
        # accept both.
        d = self.core.decide(
            "write", {"filePath": "checkstyle.xml", "content": "x"}
        )
        self.assertEqual(d["action"], "block")


# ── --stdin mode (the .sh adapter contract) ──────────────────────────────


class StdinModeTests(unittest.TestCase):
    """The .sh adapter contract: --stdin mode renders natively."""

    def _run_stdin(self, payload):
        return subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_block_path_exits_2_with_stderr(self):
        r = self._run_stdin({
            "tool_name": "Write",
            "tool_input": {"file_path": "checkstyle.xml", "content": "x"},
        })
        self.assertEqual(r.returncode, 2)
        self.assertIn("FORBIDDEN", r.stderr)
        self.assertIn("ABANDON", r.stderr)
        # Block path emits nothing on stdout.
        self.assertEqual(r.stdout, "")

    def test_block_bash_redirect_exits_2(self):
        r = self._run_stdin({
            "tool_name": "Bash",
            "tool_input": {"command": "echo x > checkstyle.xml"},
        })
        self.assertEqual(r.returncode, 2)
        self.assertIn("FORBIDDEN", r.stderr)

    def test_allow_path_exits_0_silently(self):
        r = self._run_stdin({
            "tool_name": "Write",
            "tool_input": {"file_path": "README.md", "content": "x"},
        })
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_allow_bash_exits_0_silently(self):
        r = self._run_stdin({
            "tool_name": "Bash",
            "tool_input": {"command": "ls -la"},
        })
        self.assertEqual(r.returncode, 0)

    def test_empty_input_exits_0_silently(self):
        r = self._run_stdin({})
        self.assertEqual(r.returncode, 0)

    def test_malformed_json_exits_0_silently(self):
        r = subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input="not json",
            capture_output=True,
            text=True,
            timeout=5,
        )
        # Never block on uncertainty about the harness payload —
        # the structured Write/Edit/Bash calls always have JSON.
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")

    def test_block_message_is_forceful(self):
        r = self._run_stdin({
            "tool_name": "Edit",
            "tool_input": {"file_path": "checkstyle.xml"},
        })
        self.assertEqual(r.returncode, 2)
        # Five phrases from the task description.
        for phrase in (
            "FORBIDDEN",
            "NEVER ACCEPTABLE",
            "ABANDON",
            "ALWAYS PREFERABLE",
            "FIX THE CODE",
        ):
            self.assertIn(phrase, r.stderr, msg=f"missing phrase: {phrase}")


# ── argv mode (the .ts adapter contract) ──────────────────────────────────


class ArgvModeTests(unittest.TestCase):
    """The .ts adapter contract: argv mode returns JSON, exit 0."""

    def _run_argv(self, payload):
        return subprocess.run(
            ["python3", CORE_PATH, json.dumps(payload)],
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_block_returns_block_json(self):
        r = self._run_argv({
            "tool": "write",
            "filePath": "checkstyle.xml",
        })
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")
        self.assertIn("FORBIDDEN", obj["reason"])

    def test_bash_block_returns_block_json(self):
        r = self._run_argv({
            "tool": "bash",
            "command": "echo x > checkstyle.xml",
        })
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")

    def test_allow_returns_allow_json(self):
        r = self._run_argv({
            "tool": "write",
            "filePath": "README.md",
        })
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")
        self.assertEqual(obj["reason"], "")

    def test_unknown_tool_returns_allow_json(self):
        r = self._run_argv({"tool": "some_new_tool"})
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")

    def test_invalid_json_exits_2(self):
        r = subprocess.run(
            ["python3", CORE_PATH, "not json"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(r.returncode, 2)

    def test_no_argv_exits_2(self):
        r = subprocess.run(
            ["python3", CORE_PATH],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
