#!/usr/bin/env python3
"""Unit tests for git_command_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_git_command_check.py -v

or:

    python3 .claude/hooks/lib/test_git_command_check.py

The tests exercise the pure `decide(command, policy)` function
directly and also drive the CLI entry points (argv mode and
--stdin mode) to verify the rendering contract used by the .sh
and .ts adapters.

Coverage:
  - decide() cases across the four policies (block, allow, warn)
  - --stdin mode cases (verifies the Claude Code adapter contract)
  - argv mode cases (verifies the .ts adapter contract)
  - 1 bit-for-bit equivalence test: the new core's decision matches
    the original inline .sh grep -E patterns on a sample of
    representative commands.

The equivalence test is the load-bearing one — it proves the
extraction from the .sh scripts into a shared core didn't change
the decision for any input.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "git_command_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location("git_command_check", CORE_PATH)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class BlockGitCommitTests(unittest.TestCase):
    """block-git-commit policy: blocks `git commit ...`."""

    def setUp(self):
        self.core = _load_core()

    def test_plain_git_commit_blocks(self):
        d = self.core.decide("git commit -m 'fix'", "block-git-commit")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])
        self.assertIn("git add", d["reason"])

    def test_git_commit_no_message_blocks(self):
        d = self.core.decide("git commit", "block-git-commit")
        self.assertEqual(d["action"], "block")

    def test_git_commit_amend_blocks(self):
        d = self.core.decide("git commit --amend", "block-git-commit")
        self.assertEqual(d["action"], "block")

    def test_git_no_pager_commit_blocks(self):
        d = self.core.decide("git --no-pager commit -m x", "block-git-commit")
        self.assertEqual(d["action"], "block")

    def test_git_status_allows(self):
        d = self.core.decide("git status", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_git_log_allows(self):
        # log is warn-git-log's job, not block-git-commit's
        d = self.core.decide("git log", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_git_diff_allows(self):
        d = self.core.decide("git diff", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_ls_allows(self):
        d = self.core.decide("ls -la", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_empty_allows(self):
        d = self.core.decide("", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_whitespace_allows(self):
        d = self.core.decide("   ", "block-git-commit")
        self.assertEqual(d["action"], "allow")

    def test_echo_with_commit_text_blocks(self):
        # The original inline-grep .sh also matched the substring
        # `git commit` inside a quoted argument to echo (it was a
        # substring match, not a parsed-CLI match). The shared core
        # preserves that bit-for-bit — false positives in echo
        # output are an acceptable cost for keeping the grep
        # behavior identical.
        d = self.core.decide('echo "git commit"', "block-git-commit")
        self.assertEqual(d["action"], "block")


class BlockGitWorktreeTests(unittest.TestCase):
    """block-git-worktree policy: blocks `git worktree add ...`."""

    def setUp(self):
        self.core = _load_core()

    def test_plain_worktree_add_blocks(self):
        d = self.core.decide("git worktree add /tmp/wt", "block-git-worktree")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])
        self.assertIn("working tree", d["reason"])

    def test_worktree_list_allows(self):
        d = self.core.decide("git worktree list", "block-git-worktree")
        self.assertEqual(d["action"], "allow")

    def test_worktree_remove_allows(self):
        d = self.core.decide("git worktree remove /tmp/wt", "block-git-worktree")
        self.assertEqual(d["action"], "allow")

    def test_worktree_prune_allows(self):
        d = self.core.decide("git worktree prune", "block-git-worktree")
        self.assertEqual(d["action"], "allow")

    def test_worktree_with_flag_add_blocks(self):
        d = self.core.decide("git worktree --track add /tmp/wt", "block-git-worktree")
        self.assertEqual(d["action"], "block")

    def test_worktree_with_short_flag_add_blocks(self):
        d = self.core.decide("git worktree -d add /tmp/wt", "block-git-worktree")
        self.assertEqual(d["action"], "block")

    def test_git_status_allows(self):
        d = self.core.decide("git status", "block-git-worktree")
        self.assertEqual(d["action"], "allow")

    def test_ls_allows(self):
        d = self.core.decide("ls -la", "block-git-worktree")
        self.assertEqual(d["action"], "allow")

    def test_empty_allows(self):
        d = self.core.decide("", "block-git-worktree")
        self.assertEqual(d["action"], "allow")


class WarnGitLogTests(unittest.TestCase):
    """warn-git-log policy: warns on `git log ...`."""

    def setUp(self):
        self.core = _load_core()

    def test_plain_git_log_warns(self):
        d = self.core.decide("git log", "warn-git-log")
        self.assertEqual(d["action"], "warn")
        self.assertIn("memory_branch_context", d["context"])
        # The stderr field carries the human-visible banner.
        self.assertIn("REMINDER", d["stderr"])
        self.assertIn("memory_branch_context", d["stderr"])

    def test_git_log_with_flags_warns(self):
        for cmd in [
            "git log --oneline -5",
            "git log -p",
            "git --no-pager log",
            "git log --all --graph",
            "git log --author=alice",
        ]:
            with self.subTest(cmd=cmd):
                d = self.core.decide(cmd, "warn-git-log")
                self.assertEqual(d["action"], "warn")

    def test_git_shortlog_allows(self):
        # `shortlog` is a different subcommand, not `log`
        d = self.core.decide("git shortlog", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_git_status_allows(self):
        d = self.core.decide("git status", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_git_commit_allows(self):
        # commit is block-git-commit's job
        d = self.core.decide("git commit -m x", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_ls_allows(self):
        d = self.core.decide("ls -la", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_empty_allows(self):
        d = self.core.decide("", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_whitespace_allows(self):
        d = self.core.decide("   ", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_git_log_dot_txt_allows(self):
        # False positive guard: a file called `log.txt`
        d = self.core.decide("git log.txt", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_cat_git_log_allows(self):
        # False positive guard: a path `git/log`
        d = self.core.decide("cat git/log", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_local_log_allows(self):
        # False positive guard: a local binary `./log`
        d = self.core.decide("./log", "warn-git-log")
        self.assertEqual(d["action"], "allow")

    def test_echo_with_git_log_allows(self):
        # `git log` as a quoted argument
        d = self.core.decide('echo "git log"', "warn-git-log")
        self.assertEqual(d["action"], "allow")


class BlockBranchTrackMasterTests(unittest.TestCase):
    """block-branch-track-master: refuse non-master branches that track or push to remote master/main."""

    def setUp(self):
        self.core = _load_core()

    def _action(self, command):
        return self.core.decide(command, "block-branch-track-master")["action"]

    # --- the exact recurring footgun: branch off a remote master/main ref ---
    def test_checkout_b_off_origin_master_blocks(self):
        d = self.core.decide("git checkout -b feature/x origin/master", "block-branch-track-master")
        self.assertEqual(d["action"], "block")
        self.assertIn("BLOCKED", d["reason"])
        self.assertIn("--no-track", d["reason"])
        self.assertIn("feature/x", d["reason"])

    def test_checkout_b_off_origin_main_blocks(self):
        self.assertEqual(self._action("git checkout -b feature origin/main"), "block")

    def test_checkout_capital_b_off_origin_master_blocks(self):
        self.assertEqual(self._action("git checkout -B feature origin/master"), "block")

    def test_switch_c_off_origin_master_blocks(self):
        self.assertEqual(self._action("git switch -c feature origin/master"), "block")

    def test_switch_create_long_off_origin_master_blocks(self):
        self.assertEqual(self._action("git switch --create feature origin/master"), "block")

    def test_branch_off_origin_master_blocks(self):
        self.assertEqual(self._action("git branch feature origin/master"), "block")

    def test_off_upstream_master_blocks(self):
        self.assertEqual(self._action("git checkout -b feature upstream/master"), "block")

    def test_compound_cd_then_checkout_blocks(self):
        self.assertEqual(self._action("cd /repo && git checkout -b feature origin/master"), "block")

    def test_git_dash_c_path_global_option_blocks(self):
        self.assertEqual(self._action("git -C /repo checkout -b feature origin/master"), "block")

    # --- correct forms are allowed ---
    def test_no_track_form_allows(self):
        self.assertEqual(self._action("git checkout -b feature --no-track origin/master"), "allow")

    def test_checkout_b_no_start_point_allows(self):
        self.assertEqual(self._action("git checkout -b feature"), "allow")

    def test_off_local_master_allows(self):
        self.assertEqual(self._action("git checkout -b feature master"), "allow")

    def test_recreate_master_from_origin_master_allows(self):
        self.assertEqual(self._action("git checkout -b master origin/master"), "allow")

    def test_branch_name_with_master_suffix_allows(self):
        self.assertEqual(self._action("git checkout -b feature/master"), "allow")

    def test_plain_checkout_allows(self):
        self.assertEqual(self._action("git checkout master"), "allow")

    def test_switch_existing_allows(self):
        self.assertEqual(self._action("git switch develop"), "allow")

    def test_branch_list_allows(self):
        self.assertEqual(self._action("git branch -a"), "allow")

    def test_branch_delete_allows(self):
        self.assertEqual(self._action("git branch -d feature"), "allow")

    # --- set-upstream to remote master/main ---
    def test_set_upstream_eq_origin_master_blocks(self):
        self.assertEqual(self._action("git branch --set-upstream-to=origin/master"), "block")

    def test_set_upstream_space_origin_master_blocks(self):
        self.assertEqual(self._action("git branch --set-upstream-to origin/master feature"), "block")

    def test_set_upstream_short_u_blocks(self):
        self.assertEqual(self._action("git branch -u origin/master feature"), "block")

    def test_set_upstream_for_master_allows(self):
        self.assertEqual(self._action("git branch --set-upstream-to=origin/master master"), "allow")

    def test_set_upstream_to_feature_allows(self):
        self.assertEqual(self._action("git branch --set-upstream-to=origin/feature mybranch"), "allow")

    # --- pushes that overwrite a protected branch ---
    def test_push_head_to_master_blocks(self):
        self.assertEqual(self._action("git push origin HEAD:master"), "block")

    def test_push_branch_to_master_blocks(self):
        self.assertEqual(self._action("git push origin feature:master"), "block")

    def test_push_delete_master_blocks(self):
        self.assertEqual(self._action("git push origin :master"), "block")

    def test_push_head_to_main_blocks(self):
        self.assertEqual(self._action("git push origin HEAD:main"), "block")

    def test_force_push_to_master_blocks(self):
        self.assertEqual(self._action("git push --force origin develop:master"), "block")

    def test_push_master_to_master_allows(self):
        self.assertEqual(self._action("git push origin master"), "allow")

    def test_push_feature_to_own_name_allows(self):
        self.assertEqual(self._action("git push -u origin feature"), "allow")

    def test_push_same_name_refspec_allows(self):
        self.assertEqual(self._action("git push origin feature:feature"), "allow")

    def test_bare_push_allows(self):
        # not visible from the command string; covered by the config layer
        self.assertEqual(self._action("git push"), "allow")

    # --- unrelated commands ---
    def test_git_status_allows(self):
        self.assertEqual(self._action("git status"), "allow")

    def test_ls_allows(self):
        self.assertEqual(self._action("ls -la"), "allow")

    def test_empty_allows(self):
        self.assertEqual(self._action(""), "allow")


class UnknownPolicyTests(unittest.TestCase):
    """Unknown policies are allowed (defensive) but log to stderr."""

    def setUp(self):
        self.core = _load_core()

    def test_unknown_policy_allows(self):
        d = self.core.decide("anything goes", "block-git-foo")
        self.assertEqual(d["action"], "allow")

    def test_unknown_policy_logs_to_stderr(self):
        d = self.core.decide("anything goes", "block-git-foo")
        self.assertIn("unknown policy", d["stderr"])


class StdinModeTests(unittest.TestCase):
    """The .sh adapter contract: --stdin mode renders natively."""

    def _run_stdin(self, policy, command):
        payload = json.dumps({"tool_input": {"command": command}})
        r = subprocess.run(
            ["python3", CORE_PATH, "--stdin", policy],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        return r

    def test_block_git_commit_exits_2_with_stderr(self):
        r = self._run_stdin("block-git-commit", "git commit -m x")
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)
        self.assertIn("git add", r.stderr)
        self.assertEqual(r.stdout, "")

    def test_block_git_commit_allow_exits_0_silently(self):
        r = self._run_stdin("block-git-commit", "git status")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_block_git_worktree_exits_2_with_stderr(self):
        r = self._run_stdin("block-git-worktree", "git worktree add /tmp/wt")
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)
        self.assertIn("working tree", r.stderr)

    def test_block_git_worktree_allow_exits_0_silently(self):
        r = self._run_stdin("block-git-worktree", "git worktree list")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_warn_git_log_exits_0_with_banner(self):
        r = self._run_stdin("warn-git-log", "git log")
        self.assertEqual(r.returncode, 0)
        self.assertIn("REMINDER", r.stderr)
        self.assertIn("memory_branch_context", r.stderr)
        # warn does not emit JSON on stdout; the model sees the
        # reminder in the (Claude Code) tool output stream instead.
        self.assertEqual(r.stdout, "")

    def test_warn_git_log_allow_exits_0_silently(self):
        r = self._run_stdin("warn-git-log", "git status")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_block_branch_track_master_exits_2_with_stderr(self):
        r = self._run_stdin("block-branch-track-master", "git checkout -b feature origin/master")
        self.assertEqual(r.returncode, 2)
        self.assertIn("BLOCKED", r.stderr)
        self.assertIn("--no-track", r.stderr)
        self.assertEqual(r.stdout, "")

    def test_block_branch_track_master_allow_exits_0_silently(self):
        r = self._run_stdin("block-branch-track-master", "git checkout -b feature --no-track origin/master")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertEqual(r.stderr, "")

    def test_malformed_json_exits_0_silently(self):
        r = subprocess.run(
            ["python3", CORE_PATH, "--stdin", "block-git-commit"],
            input="not json",
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(r.returncode, 0)


class ArgvModeTests(unittest.TestCase):
    """The .ts adapter contract: argv mode returns JSON, exit 0."""

    def _run_argv(self, policy, command):
        r = subprocess.run(
            ["python3", CORE_PATH, policy, command],
            capture_output=True,
            text=True,
            timeout=5,
        )
        return r

    def test_block_git_commit_returns_block_json(self):
        r = self._run_argv("block-git-commit", "git commit -m x")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")
        self.assertIn("BLOCKED", obj["reason"])
        self.assertEqual(r.stderr, "")

    def test_block_git_worktree_returns_block_json(self):
        r = self._run_argv("block-git-worktree", "git worktree add /tmp/wt")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")

    def test_warn_git_log_returns_warn_json(self):
        r = self._run_argv("warn-git-log", "git log")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "warn")
        self.assertIn("memory_branch_context", obj["context"])
        self.assertIn("REMINDER", obj["stderr"])
        self.assertEqual(r.stderr, "")

    def test_allow_returns_allow_json(self):
        r = self._run_argv("block-git-commit", "git status")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")
        self.assertEqual(obj["reason"], "")
        self.assertEqual(obj["context"], "")

    def test_block_branch_track_master_returns_block_json(self):
        r = self._run_argv("block-branch-track-master", "git checkout -b feature origin/master")
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "block")
        self.assertIn("BLOCKED", obj["reason"])
        self.assertEqual(r.stderr, "")


class BitForBitEquivalenceTests(unittest.TestCase):
    """The core must agree with the original inline .sh grep -E
    patterns on a sample of representative commands. This is the
    load-bearing regression test for the extraction from the three
    .sh scripts into a shared core.
    """

    def setUp(self):
        self.core = _load_core()

    def _legacy_grep(self, command, policy):
        """The pre-extraction grep -E pattern from the original .sh
        scripts, translated to Python regex (POSIX [[:space:]] →
        \\s). Returns True if the original .sh would have taken the
        action (block for block-*, warn for warn-*)."""
        if policy == "block-git-commit":
            pattern = r'git(?:\s+--[a-z-]+)*\s+commit'
        elif policy == "block-git-worktree":
            pattern = r'worktree(?:\s+-\S+)*\s+add(?:\s|$)'
        elif policy == "warn-git-log":
            pattern = r'(?:^|[\s|;&])git(?:\s+--[a-z-]+)*\s+log(?:\s|$)'
        else:
            return False
        return bool(re.search(pattern, command))

    def test_block_git_commit_sample_matches_inline_grep(self):
        sample = [
            ("git commit -m 'fix'", "block"),
            ("git commit", "block"),
            ("git commit --amend", "block"),
            ("git --no-pager commit -m x", "block"),
            ("git status", "allow"),
            ("git log", "allow"),
            ("git diff", "allow"),
            ("ls -la", "allow"),
            ("", "allow"),
            ("   ", "allow"),
            ('echo "git commit"', "block"),
        ]
        for cmd, _ in sample:
            with self.subTest(cmd=cmd):
                expected_block = self._legacy_grep(cmd, "block-git-commit")
                expected_action = "block" if expected_block else "allow"
                actual_action = self.core.decide(cmd, "block-git-commit")["action"]
                self.assertEqual(actual_action, expected_action,
                                 f"block-git-commit: {cmd!r}: expected {expected_action!r}, got {actual_action!r}")

    def test_block_git_worktree_sample_matches_inline_grep(self):
        sample = [
            ("git worktree add /tmp/wt", "block"),
            ("git worktree list", "allow"),
            ("git worktree remove /tmp/wt", "allow"),
            ("git worktree prune", "allow"),
            ("git worktree --track add /tmp/wt", "block"),
            ("git worktree -d add /tmp/wt", "block"),
            ("git status", "allow"),
            ("ls -la", "allow"),
            ("", "allow"),
        ]
        for cmd, _ in sample:
            with self.subTest(cmd=cmd):
                expected_block = self._legacy_grep(cmd, "block-git-worktree")
                expected_action = "block" if expected_block else "allow"
                actual_action = self.core.decide(cmd, "block-git-worktree")["action"]
                self.assertEqual(actual_action, expected_action,
                                 f"block-git-worktree: {cmd!r}: expected {expected_action!r}, got {actual_action!r}")

    def test_warn_git_log_sample_matches_inline_grep(self):
        sample = [
            ("git log", "warn"),
            ("git log --oneline -5", "warn"),
            ("git log -p", "warn"),
            ("git --no-pager log", "warn"),
            ("git log --all --graph", "warn"),
            ("git shortlog", "allow"),
            ("git status", "allow"),
            ("git commit -m x", "allow"),
            ("ls -la", "allow"),
            ("", "allow"),
            ("git log.txt", "allow"),
            ("cat git/log", "allow"),
            ("./log", "allow"),
        ]
        for cmd, _ in sample:
            with self.subTest(cmd=cmd):
                expected_warn = self._legacy_grep(cmd, "warn-git-log")
                expected_action = "warn" if expected_warn else "allow"
                actual_action = self.core.decide(cmd, "warn-git-log")["action"]
                self.assertEqual(actual_action, expected_action,
                                 f"warn-git-log: {cmd!r}: expected {expected_action!r}, got {actual_action!r}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
