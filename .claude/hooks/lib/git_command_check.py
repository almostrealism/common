#!/usr/bin/env python3
"""Decide whether a bash tool call violates one of the Class A git policies.

This module is the single source of truth for the three Class A
git policies: block-git-commit, block-git-worktree, warn-git-log. It
is invoked by:

  - .claude/hooks/block-git-commit.sh      (Claude Code, --stdin)
  - .claude/hooks/block-git-worktree.sh    (Claude Code, --stdin)
  - .claude/hooks/warn-git-log.sh          (Claude Code, --stdin)
  - .opencode/plugins/block-git-commit.ts        (opencode, argv)
  - .opencode/plugins/block-git-worktree.ts      (opencode, argv)
  - .opencode/plugins/warn-git-log.ts            (opencode, argv)
  - .opencode/plugins/_lib/command_pattern.ts    (helper, argv)

The decision logic is the simple pattern match that used to live in
each of the three .sh scripts, centralized here so all six call sites
agree. The matchers are the original `grep -E` patterns translated to
Python regex (POSIX [[:space:]] → Python \\s).

Two CLI entry points:

  python3 git_command_check.py <policy> <command>
      Used by the .ts adapter. Returns the Decision as a JSON
      object on stdout, exit 0 always. The .ts adapter does the
      harness-native rendering (throw on block, mutate output.output
      on warn).

  python3 git_command_check.py --stdin <policy>
      Used by the .sh adapters. Reads a Claude-Code-style hook
      payload from stdin (one JSON object: {"tool_input":
      {"command": "..."}}), computes the Decision, and renders
      natively (exit 2 + stderr on block, exit 0 on allow/warn, with
      the warn banner written to stderr).

The Decision shape (always JSON):

    {
      "action":  "block" | "allow" | "warn",
      "reason":  "str",   # shown to the model on block; printed to stderr
      "context": "str",   # injected into the model's next turn on warn
      "stderr":  "str",   # always printed to stderr for the human
    }

Unknown policies return "allow" with a non-empty `stderr` so a typo
in the adapter cannot accidentally block the agent.
"""
import json
import re
import sys


# Original shell patterns, translated to Python regex.
#
#   block-git-commit: 'git(\s+--[a-z-]+)*\s+commit'
#       matches `git commit`, `git --no-pager commit`, `git commit --amend`,
#       `git --no-pager commit --amend`, etc.
BLOCK_GIT_COMMIT = re.compile(r"git(?:\s+--[a-z-]+)*\s+commit")
#
#   block-git-worktree: 'worktree([[:space:]]+-[^[:space:]]+)*[[:space:]]+add([[:space:]]|$)'
#       matches `worktree add`, `worktree --track add`, `worktree -d add` (not
#       a real flag but the pattern doesn't care — `git worktree -d add` is
#       also blocked, which is the original behavior). Does NOT match
#       `worktree list`, `worktree remove`, `worktree prune`, etc. The
#       `worktree` token is not anchored on `git`, matching the original.
BLOCK_GIT_WORKTREE = re.compile(r"worktree(?:\s+-\S+)*\s+add(?:\s|$)")
#
#   warn-git-log: '(^|[[:space:]|;&])git([[:space:]]+--[a-z-]+)*[[:space:]]+log([[:space:]]|$)'
#       matches `git log`, `git --no-pager log`, `git log --oneline`, etc.
#       The `git` is anchored to start, whitespace, or shell separator so
#       `git/log` (path) and `git log.txt` (file) are not false positives.
WARN_GIT_LOG = re.compile(r"(?:^|[\s|;&])git(?:\s+--[a-z-]+)*\s+log(?:\s|$)")


BLOCK_GIT_COMMIT_REASON = (
    "BLOCKED: git commit is not permitted for agents.\n"
    "Stage changes with 'git add' only. The developer reviews and commits.\n"
    "This rule exists to prevent unauthorized commits. See CLAUDE.md.\n"
)

BLOCK_GIT_WORKTREE_REASON = (
    "BLOCKED: 'git worktree add' is not permitted for agents.\n"
    "All changes must remain in the single working tree the developer can see directly.\n"
    "Do not create separate worktrees or otherwise store work out of sight.\n"
    "Read-only 'git worktree list' is allowed. See CLAUDE.md.\n"
)

WARN_GIT_LOG_BANNER = (
    "\n"
    "+--------------------------------------------------------------------------+\n"
    "|  REMINDER: `git log` is the wrong tool for catching up on a branch.  |\n"
    "+--------------------------------------------------------------------------+\n"
    "|                                                                          |\n"
    "|  `git log` shows commit TITLES. It does not show what other agents     |\n"
    "|  tried, found, decided, or abandoned. Those live in the branch         |\n"
    "|  memories, which is exactly what memory_branch_context returns:        |\n"
    "|                                                                          |\n"
    "|      mcp__ar-manager__memory_branch_context(                           |\n"
    "|          workstream_id=<from workstream_list>,                         |\n"
    "|          include_messages=false,                                       |\n"
    "|          limit=25,                                                     |\n"
    "|          job_limit=10)                                                 |\n"
    "|                                                                          |\n"
    "|  It returns memories (cross-namespace, newest-first), recent jobs,     |\n"
    "|  and a commit list relative to the base branch — in one call.          |\n"
    "|                                                                          |\n"
    "|  Keep using `git log` if you specifically need a commit SHA or the     |\n"
    "|  commit author of a particular change — that's what it's for. But      |\n"
    "|  if the goal is \"what's been happening on this branch\", stop and     |\n"
    "|  call memory_branch_context instead.                                   |\n"
    "|                                                                          |\n"
    "+--------------------------------------------------------------------------+\n"
    "\n"
)

WARN_GIT_LOG_CONTEXT = (
    "`git log` shows commit TITLES only. To catch up on a branch — what other "
    "agents tried, found, decided, or abandoned — call "
    "mcp__ar-manager__memory_branch_context instead. Keep `git log` for when "
    "you specifically need a commit SHA."
)

POLICIES = {
    "block-git-commit",
    "block-git-worktree",
    "warn-git-log",
}


def decide(command, policy):
    """Return a Decision dict for a (command, policy) pair.

    The result is harness-neutral; each adapter renders it in its own
    native way (exit code + stderr for Claude Code; throw / mutate
    output.output for opencode).
    """
    if not command or not command.strip():
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if policy not in POLICIES:
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": f"git_command_check: unknown policy {policy!r}",
        }

    if policy == "block-git-commit":
        if BLOCK_GIT_COMMIT.search(command):
            return {
                "action": "block",
                "reason": BLOCK_GIT_COMMIT_REASON,
                "context": "",
                "stderr": BLOCK_GIT_COMMIT_REASON,
            }
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if policy == "block-git-worktree":
        if BLOCK_GIT_WORKTREE.search(command):
            return {
                "action": "block",
                "reason": BLOCK_GIT_WORKTREE_REASON,
                "context": "",
                "stderr": BLOCK_GIT_WORKTREE_REASON,
            }
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if policy == "warn-git-log":
        if WARN_GIT_LOG.search(command):
            return {
                "action": "warn",
                "reason": "",
                "context": WARN_GIT_LOG_CONTEXT,
                "stderr": WARN_GIT_LOG_BANNER,
            }
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    return {"action": "allow", "reason": "", "context": "", "stderr": ""}


def _read_stdin_command():
    """Read a Claude-Code-style hook payload from stdin and return the command."""
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return ""
    return (payload.get("tool_input", {}) or {}).get("command", "") or ""


def _render_harness_native(decision):
    """Render a Decision in the Claude Code hook contract (exit code + stderr)."""
    action = decision.get("action")
    reason = decision.get("reason", "") or ""
    context = decision.get("context", "") or ""
    stderr_msg = decision.get("stderr", "") or ""

    if action == "block":
        sys.stderr.write(reason)
        sys.exit(2)

    if action == "warn":
        if stderr_msg:
            sys.stderr.write(stderr_msg)
        sys.exit(0)

    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        if len(argv) < 2:
            sys.stderr.write("usage: git_command_check.py --stdin <policy>\n")
            sys.exit(2)
        policy = argv[1]
        command = _read_stdin_command()
        _render_harness_native(decide(command, policy))
        return

    if len(argv) < 2:
        sys.stderr.write("usage: git_command_check.py <policy> <command>\n")
        sys.exit(2)

    policy = argv[0]
    command = argv[1]
    print(json.dumps(decide(command, policy)))
    sys.exit(0)


if __name__ == "__main__":
    main()
