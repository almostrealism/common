#!/usr/bin/env python3
"""Unit tests for steer_ls_grep_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_steer_ls_grep_check.py -v

or:

    python3 .claude/hooks/lib/test_steer_ls_grep_check.py

The tests exercise the pure `decide(command)` function directly and
also drive the CLI entry points (argv mode and --stdin mode) to
verify the rendering contract used by the .sh and .ts adapters.

Coverage:
  - simple `ls` cases (block): bare `ls`, `ls <path>`, `ls <path>` with
    hidden file, multi-path `ls`
  - allowed `ls` cases: `ls -l`, `ls -la`, `ls -lh`, `ls -R`, `ls -d`,
    `ls -F`, `ls -1` (NOT a format flag), piped `ls | head`
  - simple `grep` cases (block): `grep PATTERN file`, `grep -n PATTERN file`,
    `grep PATTERN dir/`, `rg PATTERN file`
  - allowed `grep` cases: `grep -P ...`, `grep ... | xargs ...`,
    `grep --include ...`, `grep -r ...`, `grep PATTERN file1 file2 file3`,
    `grep -c ...`, `grep -A 2 ...`, `grep ... | head`
  - CLI argv mode (verifies the .ts adapter contract: JSON stdout, exit 0)
  - CLI --stdin mode (verifies the .sh adapter contract: exit 2 + stderr)
  - empty / whitespace commands are allowed
  - compound commands with `&&`, `||`, `;`, `|` operators are allowed
    (the structured tools cannot express them)
"""
import importlib.util
import json
import os
import subprocess
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "steer_ls_grep_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location("steer_ls_grep_check", CORE_PATH)
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

    def test_whitespace_command_is_allow(self):
        d = self.core.decide("   \t  ")
        self.assertEqual(d["action"], "allow")

    def test_bare_ls_blocks(self):
        # `ls` with no args is a simple "list cwd" — substitutable by
        # `glob("*")` or `glob` with no pattern.
        d = self.core.decide("ls")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])
        self.assertIn("`glob`", d["reason"])

    def test_ls_with_path_blocks(self):
        d = self.core.decide("ls docs/")
        self.assertEqual(d["action"], "block")

    def test_ls_with_specific_file_blocks(self):
        d = self.core.decide("ls README.md")
        self.assertEqual(d["action"], "block")

    def test_ls_with_dot_path_blocks(self):
        d = self.core.decide("ls .")
        self.assertEqual(d["action"], "block")

    def test_ls_l_format_allows(self):
        d = self.core.decide("ls -l")
        self.assertEqual(d["action"], "allow")

    def test_ls_la_allows(self):
        d = self.core.decide("ls -la")
        self.assertEqual(d["action"], "allow")

    def test_ls_lh_allows(self):
        d = self.core.decide("ls -lh")
        self.assertEqual(d["action"], "allow")

    def test_ls_recursive_allows(self):
        d = self.core.decide("ls -R")
        self.assertEqual(d["action"], "allow")

    def test_ls_directory_only_allows(self):
        d = self.core.decide("ls -d")
        self.assertEqual(d["action"], "allow")

    def test_ls_classify_allows(self):
        d = self.core.decide("ls -F")
        self.assertEqual(d["action"], "allow")

    def test_ls_l_format_with_path_allows(self):
        d = self.core.decide("ls -l docs/")
        self.assertEqual(d["action"], "allow")

    def test_ls_lh_format_with_path_allows(self):
        d = self.core.decide("ls -lh /var/log")
        self.assertEqual(d["action"], "allow")

    def test_ls_piped_to_head_allows(self):
        # The structured `glob` tool cannot express a pipe to `head`.
        d = self.core.decide("ls | head -5")
        self.assertEqual(d["action"], "allow")

    def test_ls_with_glob_pattern_blocks(self):
        # `ls *.py` is a list-cwd-and-filter case — the structured
        # `glob` tool returns a list directly, so this is substitutable.
        d = self.core.decide("ls *.py")
        self.assertEqual(d["action"], "block")

    def test_ls_chained_with_and_allows(self):
        d = self.core.decide("ls docs/ && echo done")
        self.assertEqual(d["action"], "allow")

    def test_simple_grep_blocks(self):
        d = self.core.decide("grep pattern file.txt")
        self.assertEqual(d["action"], "block")
        self.assertIn("`grep`", d["reason"])

    def test_grep_with_line_number_blocks(self):
        d = self.core.decide("grep -n 'TODO' src/")
        self.assertEqual(d["action"], "block")

    def test_grep_recursive_form_blocks(self):
        # `grep -r PATTERN DIR/` is recursive search. The structured
        # `grep` tool recurses into directories when given a path,
        # so this is substitutable.
        d = self.core.decide("grep -r pattern src/")
        self.assertEqual(d["action"], "block")

    def test_grep_perl_regex_allows(self):
        d = self.core.decide("grep -P '\\b\\w+\\b' file.txt")
        self.assertEqual(d["action"], "allow")

    def test_grep_with_include_allows(self):
        d = self.core.decide("grep --include='*.java' -r pattern src/")
        self.assertEqual(d["action"], "allow")

    def test_grep_with_xargs_allows(self):
        d = self.core.decide("grep -l pattern src/ | xargs sed -i 's/x/y/'")
        self.assertEqual(d["action"], "allow")

    def test_grep_piped_to_head_allows(self):
        d = self.core.decide("grep pattern file.txt | head -20")
        self.assertEqual(d["action"], "allow")

    def test_grep_with_context_allows(self):
        d = self.core.decide("grep -A 2 -B 2 pattern file.txt")
        self.assertEqual(d["action"], "allow")

    def test_grep_count_only_allows(self):
        d = self.core.decide("grep -c pattern src/")
        self.assertEqual(d["action"], "allow")

    def test_grep_multifile_blocks(self):
        # Even with multiple files, the structured grep tool handles it
        # via a path glob — substitutable.
        d = self.core.decide("grep pattern file1.txt file2.txt file3.txt")
        self.assertEqual(d["action"], "block")

    def test_rg_simple_blocks(self):
        d = self.core.decide("rg pattern file.txt")
        self.assertEqual(d["action"], "block")
        self.assertIn("`grep`", d["reason"])

    def test_rg_with_line_number_blocks(self):
        d = self.core.decide("rg -n pattern src/")
        self.assertEqual(d["action"], "block")

    def test_rg_perl_allows(self):
        d = self.core.decide("rg -P '\\b\\w+\\b' file.txt")
        self.assertEqual(d["action"], "allow")

    def test_rg_files_with_matches_allows(self):
        d = self.core.decide("rg -l pattern src/")
        self.assertEqual(d["action"], "allow")

    def test_rg_with_pcre2_allows(self):
        d = self.core.decide("rg --pcre2 'foo|bar' file.txt")
        self.assertEqual(d["action"], "allow")

    def test_egrep_simple_blocks(self):
        d = self.core.decide("egrep 'foo|bar' file.txt")
        self.assertEqual(d["action"], "block")

    def test_fgrep_simple_blocks(self):
        d = self.core.decide("fgrep 'literal' file.txt")
        self.assertEqual(d["action"], "block")

    def test_unrelated_command_allows(self):
        d = self.core.decide("mvn install -DskipTests")
        self.assertEqual(d["action"], "allow")

    def test_git_command_allows(self):
        d = self.core.decide("git status")
        self.assertEqual(d["action"], "allow")

    def test_compound_command_with_or_allows(self):
        d = self.core.decide("ls docs/ || echo missing")
        self.assertEqual(d["action"], "allow")

    def test_compound_command_with_semicolon_allows(self):
        d = self.core.decide("ls docs/; echo done")
        self.assertEqual(d["action"], "allow")

    def test_unbalanced_quotes_allows(self):
        # Unparseable command — fall through to allow, do not block
        # legitimate work on a parser error.
        d = self.core.decide("ls 'unterminated")
        self.assertEqual(d["action"], "allow")

    def test_ls_with_full_path_blocks(self):
        # `/bin/ls` should be detected the same as `ls`.
        d = self.core.decide("/bin/ls docs/")
        self.assertEqual(d["action"], "block")

    def test_grep_with_full_path_blocks(self):
        d = self.core.decide("/usr/bin/grep pattern file.txt")
        self.assertEqual(d["action"], "block")


class BlockReasonTextTests(unittest.TestCase):
    """The block reason text must include the actionable anchors."""

    def setUp(self):
        self.core = _load_core()

    def test_ls_block_mentions_glob(self):
        d = self.core.decide("ls docs/")
        self.assertIn("`glob`", d["reason"])
        self.assertIn("`grep`", d["reason"])

    def test_grep_block_mentions_grep(self):
        d = self.core.decide("grep pattern file.txt")
        self.assertIn("`grep`", d["reason"])
        self.assertIn("structured", d["reason"])

    def test_block_mentions_command_for_auditability(self):
        # The block reason includes the offending command so the
        # agent can see what was blocked.
        d = self.core.decide("ls docs/")
        self.assertIn("ls docs/", d["reason"])

    def test_block_is_all_caps_lead(self):
        d = self.core.decide("ls docs/")
        # The lead with "BLOCKED:" makes the message stand out from
        # ordinary soft-inject guidance.
        self.assertTrue(d["reason"].startswith("BLOCKED:"))


class StdinModeTests(unittest.TestCase):
    """The .sh adapter contract: --stdin mode renders natively."""

    def setUp(self):
        self.core = _load_core()

    def test_stdin_block_exits_2_with_stderr(self):
        payload = json.dumps({"tool_input": {"command": "ls docs/"}})
        result = subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("BLOCKED", result.stderr)
        self.assertIn("`glob`", result.stderr)

    def test_stdin_allow_exits_0_silently(self):
        payload = json.dumps({"tool_input": {"command": "ls -la"}})
        result = subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)
        # Allow path: no stderr, no stdout payload.
        self.assertEqual(result.stderr.strip(), "")

    def test_stdin_grep_block(self):
        payload = json.dumps({"tool_input": {"command": "grep pattern file.txt"}})
        result = subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("`grep`", result.stderr)

    def test_stdin_grep_complex_allows(self):
        payload = json.dumps({"tool_input": {"command": "grep -P 'p' f"}})
        result = subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)

    def test_stdin_unparseable_payload_exits_0(self):
        # Malformed JSON should not crash; allow through.
        result = subprocess.run(
            [sys.executable, CORE_PATH, "--stdin"],
            input="not json at all",
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)


class ArgvModeTests(unittest.TestCase):
    """The .ts adapter contract: argv mode returns Decision JSON."""

    def test_argv_block(self):
        result = subprocess.run(
            [sys.executable, CORE_PATH, "ls docs/"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)
        d = json.loads(result.stdout)
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])

    def test_argv_allow(self):
        result = subprocess.run(
            [sys.executable, CORE_PATH, "ls -la"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)
        d = json.loads(result.stdout)
        self.assertEqual(d["action"], "allow")

    def test_argv_grep_block(self):
        result = subprocess.run(
            [sys.executable, CORE_PATH, "rg pattern f"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0)
        d = json.loads(result.stdout)
        self.assertEqual(d["action"], "block")

    def test_argv_no_args_exits_2(self):
        # usage error — exit 2 (which the .ts adapter would log
        # but treat as "allow" via the fail-safe).
        result = subprocess.run(
            [sys.executable, CORE_PATH],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
