# ci/test-execution-limits — hook changes a human must apply by hand

## Why this file exists

The coding-agent session working `ci/test-execution-limits` could not write to
`.claude/hooks/` or `.claude/settings.json` — every `Edit`/`Write` attempt
under either path was denied by the harness sandbox as "a sensitive file",
even for creating a brand-new file. Per the task's own instructions
("If your tools hard-block edits under .claude/hooks/, do not work around it.
Record the exact diff in memory and report that a human must apply it."),
this file holds the exact content that still needs to land, ready to
copy in by hand. A memory was also stored on this branch recording the
blocker (namespace `bugs`, tag `sensitive-file`).

Everything else in the "Agent runtime" requirement — capping and restricting
`ar-test-runner`'s `start_test_run` (rejecting `test_group`/`test_groups`,
capping `timeout_minutes` at 40) — was applied successfully in
`tools/mcp/test-runner/server.py` and is NOT part of this file; only the
`.claude/hooks/` and `.claude/settings.json` pieces are blocked.

There are four pieces below, in application order.

---

## 1. `.claude/hooks/lib/mvn_test_check.py` — broaden TEST_PHASES, add AR_TEST_GROUP block

Apply this diff (context lines included for exact placement):

```diff
@@
-SKIP = re.compile(r"-DskipTests|-Dmaven\.test\.skip(=true)?$|-Dmaven\.test\.skip\b")
+# Anchored to the whole argument token (the ^...$ pairs make runs_tests()'s
+# existing SKIP.search(a) call behave as a full-token match), not a
+# substring search -- the unanchored form matched "-DskipTests=false" and
+# "-Dmaven.test.skip=false" (both of which explicitly RE-ENABLE tests) as if
+# they were skip flags, letting a broad `mvn test -DskipTests=false` slip
+# past this hook uncaught. Matches the same whole-token, true-only pattern
+# already fixed in tools/mcp/manager/test_execution_limits.py's
+# _SKIP_TESTS_PATTERN and PostCompletionCommandValidator.java's SKIP_TESTS.
+SKIP = re.compile(r"^-DskipTests(=true)?$|^-Dmaven\.test\.skip(=true)?$")
 
-TEST_PHASES = {"test", "integration-test"}
+# Every default-lifecycle phase at or after "test" runs tests unless skipped.
+# Matches tools/mcp/manager/test_execution_limits.py's _MVN_TEST_RUNNING_PHASES
+# -- the same rule enforced at job submission is enforced here for a live
+# interactive Bash command.
+TEST_PHASES = {"test", "integration-test", "verify", "install", "package", "deploy"}
 
 DASH_C = re.compile(r"^-[a-z]*c$")
 
+# CI-shard partitioning. Referencing this anywhere in the command blocks it
+# outright, independent of which mvn phase (if any) is present -- there is
+# no legitimate agent use of a shard. See
+# tools/mcp/manager/test_execution_limits.py for the sibling rule enforced
+# at job submission.
+#
+# No leading word-boundary assertion: the real shard invocation shape is
+# "-DAR_TEST_GROUP=2", where "AR_TEST_GROUP" is glued directly to the "-D"
+# property prefix with no boundary between "D" and "A" (both word
+# characters) -- a leading \b would never match that form. Matches the same
+# no-leading-boundary pattern already fixed in
+# tools/mcp/manager/test_execution_limits.py's _AR_TEST_GROUP_PATTERN and
+# PostCompletionCommandValidator.java's AR_TEST_GROUP.
+AR_TEST_GROUP = re.compile(r"AR_TEST_GROUPS?\b")
+
 BLOCK_REASON = (
-    "BLOCKED: Direct 'mvn test' is not permitted for agents.\n\n"
+    "BLOCKED: Direct 'mvn test' (or verify/install/package/deploy, which "
+    "also run tests unless -DskipTests is set) is not permitted for agents.\n\n"
     "Use the MCP test runner:\n"
     "  mcp__ar-test-runner__start_test_run\n"
     "    module: \"<module>\"\n"
     "    test_classes: [\"MyTest\"]\n\n"
     "Testing a different Maven project (a sibling checkout, a downstream\n"
     "consumer, a worktree)? Pass its root as `project` — the runner is not\n"
     "limited to this repository:\n"
     "  mcp__ar-test-runner__start_test_run\n"
     "    project: \"../downstream\"\n"
     "    module: \"app\"\n"
     "    test_classes: [\"MyTest\"]\n\n"
     "Reason: Direct mvn test bypasses the controlled environment,\n"
     "runs at the wrong test depth, and produces unreliable results.\n"
 )
 
+AR_TEST_GROUP_BLOCK_REASON = (
+    "BLOCKED: This command references AR_TEST_GROUP/AR_TEST_GROUPS.\n\n"
+    "CI-shard partitioning reproduces a whole test-matrix group in one JVM\n"
+    "and is reserved for the CI workflow matrix. Agents must never run a\n"
+    "shard, with or without mvn. Use\n"
+    "  mcp__ar-test-runner__start_test_run\n"
+    "    test_classes: [\"MyTest\"]\n"
+    "to select the specific test(s) you need instead. There is no bypass\n"
+    "for this rule.\n"
+)
+
 WARN_NOTE = (
```

And in `decide(command)`, add the AR_TEST_GROUP check ahead of the existing
phase-based `analyze()` call so it fires regardless of mvn phase detection:

```diff
 def decide(command):
     """Return a Decision dict for a command string.
 
     The result is harness-neutral; each adapter renders it in its own
     native way (exit code + stderr for Claude Code; throw / mutate
     output.output for opencode).
     """
     if not command or not command.strip():
         return {"action": "allow", "reason": "", "context": "", "stderr": ""}
 
+    if AR_TEST_GROUP.search(command):
+        return {
+            "action": "block",
+            "reason": AR_TEST_GROUP_BLOCK_REASON,
+            "context": "",
+            "stderr": AR_TEST_GROUP_BLOCK_REASON,
+        }
+
     status = analyze(command)
```

### Consequence to be aware of

Broadening `TEST_PHASES` means `mvn clean install` (no `-DskipTests`) now
**blocks** — it did not before. This is intentional (that command runs the
whole reactor's tests) but it changes the existing
`test_mvn_clean_install_allows` test's expectation; see the test-file diff
below.

---

## 2. `.claude/hooks/lib/test_mvn_test_check.py` — keep tests in sync

Two edits:

### a. Fix `test_mvn_clean_install_allows`

`mvn clean install` with no `-DskipTests` must now be **block**, not allow.
Rename/adjust so the "allow" case is meaningfully tested with skip present,
and add a new explicit test for the newly-blocked case:

```diff
-    def test_mvn_clean_install_allows(self):
-        d = self.core.decide("mvn clean install")
-        self.assertEqual(d["action"], "allow")
+    def test_mvn_clean_install_skip_tests_allows(self):
+        d = self.core.decide("mvn clean install -DskipTests")
+        self.assertEqual(d["action"], "allow")
+
+    def test_mvn_clean_install_without_skip_blocks(self):
+        # install (like verify/package/deploy) runs tests unless skipped --
+        # this is the newly-widened half of TEST_PHASES.
+        d = self.core.decide("mvn clean install")
+        self.assertEqual(d["action"], "block")
+
+    def test_mvn_verify_without_skip_blocks(self):
+        d = self.core.decide("mvn verify")
+        self.assertEqual(d["action"], "block")
+
+    def test_mvn_package_without_skip_blocks(self):
+        d = self.core.decide("mvn package")
+        self.assertEqual(d["action"], "block")
+
+    def test_ar_test_group_blocks_even_without_mvn(self):
+        d = self.core.decide("AR_TEST_GROUP=2 AR_TEST_GROUPS=8 mvn test -pl engine/utils")
+        self.assertEqual(d["action"], "block")
+        self.assertIn("AR_TEST_GROUP", d["reason"])
+
+    def test_ar_test_group_blocks_on_non_test_phase(self):
+        # Even a build-only phase referencing the shard vars must block --
+        # there is no legitimate agent use of AR_TEST_GROUP at all.
+        d = self.core.decide("mvn package -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4 -DskipTests")
+        self.assertEqual(d["action"], "block")
+
+    def test_skip_tests_equals_false_is_not_treated_as_skip(self):
+        # The unanchored form of SKIP matched "-DskipTests=false" as a
+        # substring even though that flag explicitly RE-ENABLES tests --
+        # the anchored ^...$ pattern must not repeat that bug.
+        d = self.core.decide("mvn test -DskipTests=false")
+        self.assertEqual(d["action"], "block")
+
+    def test_maven_test_skip_equals_false_is_not_treated_as_skip(self):
+        d = self.core.decide("mvn install -Dmaven.test.skip=false")
+        self.assertEqual(d["action"], "block")
```

(Also update the chained-commands sample's other `mvn clean` usage if any
other test asserts `mvn clean install` is allowed elsewhere in the file —
search for `"mvn clean install"` before applying.)

### b. Keep `BitForBitEquivalenceTests` meaningful

That test's job is to prove the **tokenizer/dispatch** extraction is
equivalent to the pre-extraction `.sh` script — the `TEST_PHASES` literal is
incidental to that proof, and hard-coding it a second time means the test
silently goes stale every time the real set changes (as it just did). Make
the reference copy pull the current phase set from the module under test
instead of re-declaring it:

```diff
     def _inline_analyze(self, cmd):
         """The pre-extraction analyze() function, copied verbatim from
         the old .claude/hooks/block-mvn-test-direct.sh. The core's
         decide() must agree with this on every command in the
-        sample below."""
+        sample below. TEST_PHASES is read from the module under test
+        (self.core.TEST_PHASES) rather than duplicated here: this test's
+        job is to prove tokenizer/dispatch equivalence, not to pin an
+        independent copy of the phase set, which would go stale every
+        time TEST_PHASES intentionally changes (as it did to add
+        verify/install/package/deploy)."""
         OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
                      "\n", "then", "do", "else", "elif", "fi", "done"}
         CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                         "builtin", "stdbuf", "nice", "ionice"}
         ENV_ASSIGN = __import__("re").compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
-        SKIP = __import__("re").compile(r"-DskipTests|-Dmaven\.test\.skip(=true)?$|-Dmaven\.test\.skip\b")
-        TEST_PHASES = {"test", "integration-test"}
+        SKIP = self.core.SKIP
+        TEST_PHASES = self.core.TEST_PHASES
         DASH_C = __import__("re").compile(r"^-[a-z]*c$")
```

Also pulling `SKIP` from `self.core.SKIP` rather than re-declaring it here for the
same reason as `TEST_PHASES`: the equivalence test's job is proving the
tokenizer/dispatch extraction matches the pre-extraction script, not pinning an
independent copy of the skip-flag pattern that would go stale the next time
`SKIP` changes.

And update the sample list's two entries that change outcome under the wider
phase set (`mvn verify` and `mvn package` are already in the sample and will
now correctly resolve to `block` via `_expected_action`, no other code
change needed there — `_expected_action` calls `_inline_analyze`, which now
uses the live `TEST_PHASES`). No line changes needed in the sample list
itself; only the helper above.

---

## 3. New file `.claude/hooks/lib/pytest_broad_run_check.py`

Mirrors `mvn_test_check.py`'s structure exactly (tokenizer, CLI contract,
`decide()` shape) so both hooks are easy to read side by side.

```python
#!/usr/bin/env python3
"""Decide whether a bash tool call is a broad (non-node-id) pytest invocation.

Companion to mvn_test_check.py: blocks `pytest`/`python -m pytest` /
`python3 -m pytest` commands that do not name an explicit node id
(`file.py::test_name`), which otherwise runs an entire file or directory.
See tools/mcp/manager/test_execution_limits.py for the same rule enforced
at job submission.

Same two CLI entry points as mvn_test_check.py:

  python3 pytest_broad_run_check.py <command>
      Returns the Decision as JSON on stdout, exit 0 always.

  python3 pytest_broad_run_check.py --stdin
      Reads a Claude-Code-style hook payload from stdin, computes the
      Decision, and renders natively (exit 2 + stderr on block, exit 0
      on allow -- this check never warns, only blocks or allows, since
      pytest invocations are much easier to parse unambiguously than
      shell-embedded mvn commands).
"""
import json
import re
import shlex
import sys


OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
             "\n", "then", "do", "else", "elif", "fi", "done"}

CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                "builtin", "stdbuf", "nice", "ionice"}

ENV_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")

DASH_C = re.compile(r"^-[a-z]*c$")

BLOCK_REASON = (
    "BLOCKED: pytest without an explicit node id is not permitted for agents.\n\n"
    "Run one test per invocation:\n"
    "  pytest path/to/test_file.py::test_name\n"
    "  pytest path/to/test_file.py::TestClass::test_name\n"
    "  python3 -m pytest path/to/test_file.py::test_name\n\n"
    "Reason: pytest against a directory or a whole file runs every test it\n"
    "finds there. Broad verification belongs to CI; a directory or\n"
    "whole-file invocation from an agent session is never appropriate.\n"
)


def _is_pytest_invocation(tokens):
    """Return the args after the pytest entry point, or None if tokens is
    not a pytest/python -m pytest/python3 -m pytest invocation."""
    if not tokens:
        return None
    base = tokens[0].rsplit("/", 1)[-1]
    if base in ("pytest", "py.test"):
        return tokens[1:]
    if base in ("python", "python3") and len(tokens) >= 3 \
            and tokens[1] == "-m" and tokens[2] == "pytest":
        return tokens[3:]
    return None


def _has_explicit_node_id(args):
    positionals = [a for a in args if not a.startswith("-")]
    # ALL positionals must be node ids, not just one -- "pytest tests/
    # tests/test_foo.py::test_bar" still runs the whole "tests/" directory
    # even though one of its two positionals names a single test.
    return bool(positionals) and all("::" in a for a in positionals)


def analyze(cmd):
    """Return 'block', 'uncertain', or 'clear' for a command string."""
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

        pytest_args = _is_pytest_invocation([tok] + args)
        if pytest_args is not None:
            if not _has_explicit_node_id(pytest_args):
                return "block"
        else:
            base = tok.rsplit("/", 1)[-1]
            if base in {"bash", "sh", "zsh", "dash", "ksh"}:
                for k, a in enumerate(args):
                    if DASH_C.match(a) and k + 1 < len(args):
                        inner = analyze(args[k + 1])
                        if inner == "block":
                            return "block"

        i = j
        in_cmd_position = True
    return "clear"


def decide(command):
    if not command or not command.strip():
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    status = analyze(command)
    if status == "block":
        return {
            "action": "block",
            "reason": BLOCK_REASON,
            "context": "",
            "stderr": BLOCK_REASON,
        }
    return {"action": "allow", "reason": "", "context": "", "stderr": ""}


def _read_stdin_command():
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return ""
    return (payload.get("tool_input", {}) or {}).get("command", "") or ""


def _render_harness_native(decision):
    action = decision.get("action")
    reason = decision.get("reason", "") or ""
    if action == "block":
        sys.stderr.write(reason)
        sys.exit(2)
    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        command = _read_stdin_command()
        _render_harness_native(decide(command))
        return

    if not argv:
        sys.stderr.write("usage: pytest_broad_run_check.py <command> | --stdin\n")
        sys.exit(2)

    command = argv[0]
    print(json.dumps(decide(command)))
    sys.exit(0)


if __name__ == "__main__":
    main()
```

---

## 4. New file `.claude/hooks/lib/test_pytest_broad_run_check.py`

```python
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

    def test_empty_command_is_allow(self):
        d = self.core.decide("")
        self.assertEqual(d["action"], "allow")

    def test_pytest_with_node_id_allows(self):
        d = self.core.decide("pytest tests/test_foo.py::test_bar")
        self.assertEqual(d["action"], "allow")

    def test_pytest_class_method_node_id_allows(self):
        d = self.core.decide("pytest tests/test_foo.py::TestFoo::test_bar")
        self.assertEqual(d["action"], "allow")

    def test_pytest_on_directory_blocks(self):
        d = self.core.decide("pytest tests/")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])

    def test_pytest_on_whole_file_blocks(self):
        d = self.core.decide("pytest tests/test_foo.py")
        self.assertEqual(d["action"], "block")

    def test_pytest_with_no_args_blocks(self):
        d = self.core.decide("pytest")
        self.assertEqual(d["action"], "block")

    def test_pytest_dash_k_keyword_blocks(self):
        d = self.core.decide("pytest tests/ -k test_bar")
        self.assertEqual(d["action"], "block")

    def test_pytest_mixed_directory_and_node_id_blocks(self):
        # A bare directory positional still runs the whole directory even
        # when a second positional names one specific test -- _has_explicit_
        # node_id must require ALL positionals to be node ids, not just one.
        d = self.core.decide("pytest tests/ tests/test_foo.py::test_bar")
        self.assertEqual(d["action"], "block")

    def test_python_module_pytest_with_node_id_allows(self):
        d = self.core.decide("python3 -m pytest tests/test_foo.py::test_bar")
        self.assertEqual(d["action"], "allow")

    def test_python_module_pytest_without_node_id_blocks(self):
        d = self.core.decide("python3 -m pytest tests/")
        self.assertEqual(d["action"], "block")

    def test_non_pytest_command_allows(self):
        d = self.core.decide("ls -la")
        self.assertEqual(d["action"], "allow")

    def test_bash_c_recursion_blocks(self):
        d = self.core.decide('bash -c "pytest tests/"')
        self.assertEqual(d["action"], "block")

    def test_bash_c_recursion_with_node_id_allows(self):
        d = self.core.decide('bash -c "pytest tests/test_foo.py::test_bar"')
        self.assertEqual(d["action"], "allow")

    def test_chained_commands_block(self):
        d = self.core.decide("echo hi && pytest tests/")
        self.assertEqual(d["action"], "block")


class StdinModeTests(unittest.TestCase):

    def _run_stdin(self, command):
        payload = json.dumps({"tool_input": {"command": command}})
        return subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
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
            ["python3", CORE_PATH, command],
            capture_output=True, text=True, timeout=5,
        )

    def test_block_returns_block_json(self):
        r = self._run_argv("pytest tests/")
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")

    def test_allow_returns_allow_json(self):
        r = self._run_argv("pytest tests/test_foo.py::test_bar")
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")


if __name__ == "__main__":
    unittest.main(verbosity=2)
```

---

## 5. New file `.claude/hooks/block-pytest-broad-run.sh`

```bash
#!/usr/bin/env bash
# PreToolUse — Bash: block a pytest/python -m pytest invocation with no
# explicit ::node_id (runs a whole file or directory).
#
# Thin wrapper; decision logic lives in
# .claude/hooks/lib/pytest_broad_run_check.py — mirrors
# block-mvn-test-direct.sh / mvn_test_check.py.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/pytest_broad_run_check.py" --stdin
```

Make it executable: `chmod +x .claude/hooks/block-pytest-broad-run.sh`.

---

## 6. `.claude/settings.json` — register the new hook

In the `hooks.PreToolUse` array, find the entry with `"matcher": "Bash"` (the
one whose `hooks` list starts with `block-git-commit.sh`), and add a new
entry to that list right after `block-mvn-test-direct.sh`:

```diff
           {
             "type": "command",
             "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-mvn-test-direct.sh",
             "description": "Block direct mvn test — must use mcp__ar-test-runner__start_test_run"
           },
+          {
+            "type": "command",
+            "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-pytest-broad-run.sh",
+            "description": "Block direct pytest/python -m pytest without an explicit ::node_id, and any AR_TEST_GROUP reference — agents and job submitters may never run a full/whole test suite, a module's suite, or a CI shard (ci/test-execution-limits)"
+          },
           {
             "type": "command",
             "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-checkstyle-edit.sh",
             "description": "Block bash manipulation of checkstyle configuration (checkstyle.xml, checkstyle-suppressions.xml, any /checkstyle/ dir, etc.) — abandon the task rather than tamper with enforcement"
           },
```

---

## Verification once applied

Run each new/changed test one node id at a time (never the whole file):

```bash
python3 -m pytest .claude/hooks/lib/test_pytest_broad_run_check.py::DecideFunctionTests::test_pytest_on_directory_blocks -q
python3 -m pytest .claude/hooks/lib/test_mvn_test_check.py::DecideFunctionTests::test_ar_test_group_blocks_even_without_mvn -q
python3 -m pytest .claude/hooks/lib/test_mvn_test_check.py::BitForBitEquivalenceTests::test_27_command_sample_matches_inline_analyze -q
```
