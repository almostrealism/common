#!/usr/bin/env python3
"""Decide whether a bash tool call violates one of the Class A git policies.

This module is the single source of truth for the Class A git
policies: block-git-commit, block-git-worktree, warn-git-log, and
block-branch-track-master. It is invoked by:

  - .claude/hooks/block-git-commit.sh              (Claude Code, --stdin)
  - .claude/hooks/block-git-worktree.sh            (Claude Code, --stdin)
  - .claude/hooks/warn-git-log.sh                  (Claude Code, --stdin)
  - .claude/hooks/block-branch-track-master.sh     (Claude Code, --stdin)
  - .opencode/plugins/block-git-commit.ts          (opencode, argv)
  - .opencode/plugins/block-git-worktree.ts        (opencode, argv)
  - .opencode/plugins/warn-git-log.ts              (opencode, argv)
  - .opencode/plugins/block-branch-track-master.ts (opencode, argv)
  - .opencode/plugins/_lib/command_pattern.ts      (helper, argv)

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
import shlex
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


# ---------------------------------------------------------------------------
# block-branch-track-master
#
# The recurring footgun this policy prevents: creating a local branch FROM a
# remote-tracking ref (e.g. `git checkout -b feature origin/master`) makes git
# set the new branch's upstream to origin/master, so a later `git push` aims at
# MASTER instead of the feature branch. This policy refuses every command shape
# that would:
#   (a) create a non-master branch tracking a remote master/main start point
#       (allowed WITH --no-track, which does not configure the upstream),
#   (b) set a non-master branch's upstream to a remote master/main, or
#   (c) push over a remote master/main from a ref that is not master/main
#       (a NAME:master / HEAD:master / :master refspec, or a force-push).
#
# Unlike the three grep policies above, this one tokenizes the command
# (segments split on shell separators, then shlex per segment) so flag order
# and `git -C <path>` style global options do not fool it. It is still a pure
# function of the command STRING: a bare `git push` on an already-mistracked
# branch is not visible here and is meant to be covered by repo config
# (push.default=current) as a second layer.
# ---------------------------------------------------------------------------

PROTECTED_BRANCHES = ("master", "main")

# Matches a token naming master/main on a remote: origin/master, upstream/main,
# refs/remotes/origin/master. This is the kind of start point that makes
# `git checkout -b` configure a tracking upstream.
_PROTECTED_REMOTE_REF = re.compile(
    r"^(?:refs/remotes/)?[A-Za-z0-9][A-Za-z0-9._-]*/(?:master|main)$"
)

# git global options that consume the following token as their value, so the
# subcommand scanner can skip them to find the real subcommand.
_GIT_GLOBAL_OPTS_WITH_ARG = {
    "-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
}

BLOCK_BRANCH_TRACK_MASTER_REASON = (
    "BLOCKED: creating branch {name!r} from a remote master/main ref.\n"
    "\n"
    "Branching off a remote-tracking ref (e.g. origin/master) makes git set\n"
    "{name}'s upstream to master, so a later `git push` would target MASTER\n"
    "instead of your branch. This is the recurring footgun this guard stops.\n"
    "\n"
    "Do one of these instead (neither tracks master):\n"
    "  git fetch origin && git checkout -b {name} --no-track origin/master\n"
    "  git checkout master && git pull --ff-only && git checkout -b {name}\n"
    "Then publish under the branch's own name:  git push -u origin {name}\n"
)

SET_UPSTREAM_MASTER_REASON = (
    "BLOCKED: refusing to set a non-master branch's upstream to a remote "
    "master/main.\n"
    "That makes `git push` on this branch target master. Track master only on\n"
    "the master branch itself; publish feature branches with\n"
    "  git push -u origin <branch>\n"
)

PUSH_TO_MASTER_REASON = (
    "BLOCKED: this push would update remote {dst!r} from a ref that is not "
    "{dst!r}.\n"
    "Refusing to overwrite a protected branch. Push your branch under its own\n"
    "name instead:  git push -u origin <your-branch>\n"
)

FORCE_PUSH_TO_MASTER_REASON = (
    "BLOCKED: refusing a force-push to protected branch {dst!r}.\n"
    "Force-pushing master/main can destroy shared history.\n"
)


def _segments(command):
    """Split a command line into top-level segments on &&, ||, |, ;, &, newline."""
    return re.split(r"&&|\|\||[|;&\n]", command)


def _git_calls(segment):
    """Return a list of ``(subcommand, args)`` for each git invocation in a segment."""
    try:
        tokens = shlex.split(segment)
    except ValueError:
        tokens = segment.split()

    calls = []
    i = 0
    while i < len(tokens):
        if tokens[i].rsplit("/", 1)[-1] == "git":
            j = i + 1
            while j < len(tokens):
                token = tokens[j]
                if token in _GIT_GLOBAL_OPTS_WITH_ARG:
                    j += 2
                    continue
                if token.startswith("-"):
                    j += 1
                    continue
                break
            if j < len(tokens):
                calls.append((tokens[j], tokens[j + 1:]))
                i = j + 1
                continue
        i += 1
    return calls


def _next_positional(args, start):
    """Return the first non-option token at or after index ``start``, else None."""
    for k in range(start, len(args)):
        if not args[k].startswith("-"):
            return args[k]
    return None


def _ref_is_protected(ref):
    """True if ``ref`` denotes the local master/main branch."""
    if ref.startswith("+"):
        ref = ref[1:]
    if ref.startswith("refs/heads/"):
        ref = ref[len("refs/heads/"):]
    return ref in PROTECTED_BRANCHES


def _is_protected_remote_ref(token):
    """True if ``token`` names master/main on a remote (origin/master, etc.)."""
    return bool(_PROTECTED_REMOTE_REF.match(token))


def _branch_create_violation(sub, args):
    """Reason if this call creates a non-master branch tracking remote master/main."""
    name = None
    if sub == "checkout":
        for k, token in enumerate(args):
            if token in ("-b", "-B"):
                name = _next_positional(args, k + 1)
                break
    elif sub == "switch":
        for k, token in enumerate(args):
            if token in ("-c", "-C", "--create"):
                name = _next_positional(args, k + 1)
                break
    elif sub == "branch":
        # Only a plain `git branch NAME [start]` creates a branch; listing,
        # deletion, move, copy, and upstream forms are not branch creation.
        non_create = (
            "-d", "-D", "--delete", "-m", "-M", "--move", "-c", "-C", "--copy",
            "-l", "--list", "-a", "--all", "-r", "--remotes", "--show-current",
            "-u", "--set-upstream-to", "--unset-upstream", "--edit-description",
        )
        if not any(a in non_create for a in args) and not any(
                a.startswith("--set-upstream-to=") for a in args):
            name = _next_positional(args, 0)

    if name is None or _ref_is_protected(name) or "--no-track" in args:
        return ""

    if any(_is_protected_remote_ref(a) for a in args if a != name):
        return BLOCK_BRANCH_TRACK_MASTER_REASON.format(name=name)
    return ""


def _set_upstream_violation(sub, args):
    """Reason if this call sets a non-master branch's upstream to remote master/main."""
    if sub != "branch":
        return ""
    for k, token in enumerate(args):
        value = None
        rest = None
        if token in ("-u", "--set-upstream-to"):
            value = args[k + 1] if k + 1 < len(args) else None
            rest = k + 2
        elif token.startswith("--set-upstream-to="):
            value = token.split("=", 1)[1]
            rest = k + 1
        if value is not None and _is_protected_remote_ref(value):
            target = _next_positional(args, rest)
            if target is None or not _ref_is_protected(target):
                return SET_UPSTREAM_MASTER_REASON
    return ""


def _push_violation(args):
    """Reason if this push would overwrite a remote master/main from another ref."""
    force = any(a in ("-f", "--force", "--force-with-lease") for a in args) or any(
        a.startswith("--force-with-lease=") or a.startswith("--force=") for a in args)
    delete = any(a in ("-d", "--delete") for a in args)
    positionals = [a for a in args if not a.startswith("-")]

    if delete:
        targets = positionals[1:] if len(positionals) >= 2 else positionals
        for t in targets:
            t_name = t[len("refs/heads/"):] if t.startswith("refs/heads/") else t
            if t_name in PROTECTED_BRANCHES:
                return PUSH_TO_MASTER_REASON.format(dst=t_name)
        return ""

    if len(positionals) >= 2:
        refspecs = positionals[1:]
    elif len(positionals) == 1 and (":" in positionals[0] or positionals[0].startswith("+")):
        refspecs = positionals
    else:
        refspecs = []

    for spec in refspecs:
        body = spec[1:] if spec.startswith("+") else spec
        if ":" in body:
            src, dst = body.split(":", 1)
        else:
            src, dst = body, body
        dst_name = dst[len("refs/heads/"):] if dst.startswith("refs/heads/") else dst
        src_name = src[1:] if src.startswith("+") else src
        if src_name.startswith("refs/heads/"):
            src_name = src_name[len("refs/heads/"):]
        if dst_name in PROTECTED_BRANCHES and src_name != dst_name:
            return PUSH_TO_MASTER_REASON.format(dst=dst_name)
        if dst_name in PROTECTED_BRANCHES and force:
            return FORCE_PUSH_TO_MASTER_REASON.format(dst=dst_name)
    return ""


def _violation_for_call(sub, args):
    """Reason if a single git (subcommand, args) call violates the policy, else ''."""
    if sub in ("checkout", "switch", "branch"):
        return _branch_create_violation(sub, args) or _set_upstream_violation(sub, args)
    if sub == "push":
        return _push_violation(args)
    return ""


def _branch_track_master_violation(command):
    """Reason for the first block-branch-track-master violation in ``command``, else ''."""
    for segment in _segments(command):
        for sub, args in _git_calls(segment):
            reason = _violation_for_call(sub, args)
            if reason:
                return reason
    return ""


POLICIES = {
    "block-git-commit",
    "block-git-worktree",
    "warn-git-log",
    "block-branch-track-master",
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

    if policy == "block-branch-track-master":
        reason = _branch_track_master_violation(command)
        if reason:
            return {"action": "block", "reason": reason, "context": "", "stderr": reason}
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
