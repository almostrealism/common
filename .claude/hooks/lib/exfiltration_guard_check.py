#!/usr/bin/env python3
"""Decide whether a tool call could move session data off this machine.

This module is the single source of truth for the exfiltration policy
described in docs/internals/exfiltration-guard.md. It is invoked by

  - .claude/hooks/block-exfiltration.sh   (Claude Code PreToolUse, --stdin)

for the ``Artifact*``, ``SendUserFile`` and ``Bash`` tools.

The policy, in one sentence: the ONLY sanctioned way for anything to leave
this machine through the assistant's tools is publishing a file that is
already tracked in this repository, byte-identical to the committed (or
staged) version, so it can be viewed in the Claude app. Everything else is
blocked, and every decision is written to an audit log under the user's
home directory.

The guard fails CLOSED. Unparsable input, a missing ``git`` binary, a path
that cannot be resolved, a subprocess error, an unknown action, a
destination that cannot be determined, an audit log that cannot be
written — each of these is a block, never an allow. There is no
environment variable that disables it: an agent controls its environment
and must not be able to switch the guard off.

Two CLI entry points:

  python3 exfiltration_guard_check.py --stdin
      Reads a Claude-Code hook payload from stdin, renders natively
      (exit 2 + reason on stderr to block, exit 0 to allow).

  python3 exfiltration_guard_check.py <payload-json>
      Prints the Decision as JSON on stdout, exit 0 always. Used by
      tests and available for a future opencode adapter.

The Decision shape:

    {
      "action":  "block" | "allow",
      "reason":  "str",    # shown to the model on block
      "context": "str",    # reserved; empty in this policy
      "stderr":  "str",    # printed to stderr for the human
      "audit":   {...}     # the entry written to the audit log
    }

The bash analysis lives in the sibling modules under
``exfil_bash_lex``, ``exfil_bash_network``, ``exfil_bash_runner``,
``exfil_bash_vcs`` and ``exfil_bash_dispatch``. They are imported here
so the public API (``decide``, ``Allowlist``, ``_shell_invocation``)
still works for callers — most importantly the unit tests, which load
this module with ``importlib.util.spec_from_file_location`` and reach
into the symbols they expect to find. Splitting the file keeps each
one under the project's 1600-line cap.
"""
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
import time

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from exfil_bash_dispatch import (BashContext, analyze_command, bash_targets)
from exfil_bash_lex import (SHELLS, GuardError, normalize_host, owner_repo)
from exfil_bash_runner import _shell_invocation


ALLOWLIST_REL_PATH = ".claude/hooks/exfil-allowlist.txt"
AUDIT_LOG_PATH = os.path.join(os.path.expanduser("~"), ".ar-hooks", "exfiltration-guard.log")

# Free-text metadata the Artifact tool sends with a publish (title,
# description, label). The tool needs a title, so this channel is bounded
# rather than closed: a short caption cannot carry a file.
MAX_METADATA_CHARS = 300

# Appended to every block message. A guard that offers no way to
# disagree with it invites the other kind of disagreement — the quiet
# rephrasing that ends up teaching a model to route around its own
# safety rules. The rules here have been relaxed before when a block
# turned out to be wrong, so this is a real offer, not a formality.
RULE_CHANGE_INVITATION = (
    "If you think this rule is wrong — it is stopping ordinary work rather\n"
    "than an attempt to send data off this machine — say so and ask for it to\n"
    "be changed. Explain what you were doing and why the block does not fit\n"
    "it. That is a request with a real answer: these rules have been relaxed\n"
    "before, for exactly that reason. What is never acceptable is quietly\n"
    "working around a rule you disagree with — rewording the command, moving\n"
    "the same action to a tool that is not checked, or splitting it up to\n"
    "stay under the pattern. Ask, or stop and report that you are blocked.\n"
)

POLICY_STATEMENT = (
    "Policy: artifacts exist for ONE purpose — showing a file that is already\n"
    "tracked in this repository in the Claude app. The file must be inside the\n"
    "project's git work tree, tracked by git, and byte-identical to the version\n"
    "in the index or HEAD. Nothing else — scratchpad files, modified files,\n"
    "text authored in this session, database rows, comment replies — may leave\n"
    "this machine through the assistant's tools. If the content belongs in the\n"
    "repository, add it and stage it (git add) and leave it unmodified; then\n"
    "publish. If it does not belong in the repository, it does not get\n"
    "published. See docs/internals/exfiltration-guard.md.\n"
    "\n" + RULE_CHANGE_INVITATION
)

BASH_POLICY_STATEMENT = (
    "Policy: no data leaves this machine through the assistant's tools except\n"
    "a repository-tracked file published as an artifact. Network commands are\n"
    "allowed only to hosts listed in .claude/hooks/exfil-allowlist.txt (as\n"
    "committed on HEAD); `git push` only to origin; `gh` only for pr/issue\n"
    "reads and writes with an inline body. If this command is legitimate, the\n"
    "developer can run it by hand — do not try to rephrase it past the guard.\n"
    "See docs/internals/exfiltration-guard.md.\n"
    "\n" + RULE_CHANGE_INVITATION
)

ARTIFACT_READ_ONLY_ACTIONS = frozenset({
    "read", "list", "comments", "status", "watch", "unwatch",
    "read_db", "list_assets", "read_asset",
})
# Actions that send nothing outward even though they mutate remote state.
ARTIFACT_INERT_ACTIONS = frozenset({"resolve", "delete_asset"})
ARTIFACT_FILE_ACTIONS = frozenset({"publish", "upload_asset"})
# Free text authored in this session would leave the machine.
ARTIFACT_TEXT_ACTIONS = frozenset({"reply", "resume_replies"})

SEND_FILE_PATH_KEYS = ("file_path", "filePath", "path", "file")

# Tools that cannot move anything off the machine, and so are allowed
# without inspection rather than denied as unrecognised. TaskStop ends a
# background task; TaskOutput reads one's output back into the session.
# Both move data inward or not at all, and whatever the task itself does
# was inspected when the tool that started it ran.
INERT_TOOLS = frozenset({"TaskStop", "TaskOutput"})


# ---------------------------------------------------------------------------
# Allowlist
# ---------------------------------------------------------------------------

class Allowlist:
    """Destinations that may receive data, parsed from the committed allowlist.

    ``lab_hosts`` may be reached by any transport; ``git_remotes`` only by
    ``git push origin`` and the sanctioned ``gh`` operations.
    """

    def __init__(self, lab_hosts=(), git_remotes=()):
        self.lab_hosts = list(lab_hosts)
        self.git_remotes = list(git_remotes)

    @classmethod
    def parse(cls, text):
        """Parse the allowlist file format (sections, comments, one entry per line)."""
        sections = {"lab-hosts": [], "git-remotes": []}
        current = None
        for raw in text.splitlines():
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            if line.startswith("[") and line.endswith("]"):
                current = line[1:-1].strip().lower()
                continue
            if current in sections:
                sections[current].append(line.lower())
        return cls(sections["lab-hosts"], sections["git-remotes"])

    @staticmethod
    def _is_cidr(entry):
        return "/" in entry and entry[0].isdigit()

    @staticmethod
    def _matches(entry, host):
        if entry.startswith("*."):
            return host.endswith(entry[1:]) or host == entry[2:]
        if Allowlist._is_cidr(entry):
            try:
                return ipaddress.ip_address(host) in ipaddress.ip_network(entry, strict=False)
            except ValueError:
                return False
        return host == entry

    def is_lab_host(self, host):
        """Whether ``host`` may receive data by any transport."""
        host = normalize_host(host)
        return bool(host) and any(self._matches(e, host) for e in self.lab_hosts)

    def is_git_remote(self, host, repo=None):
        """Whether ``host`` (and, when given, ``repo`` as owner/name) may be
        reached through git push / gh. A ``host/owner/name`` entry pins the
        repository; a bare ``host`` entry accepts any repository there."""
        host = normalize_host(host)
        if not host:
            return False
        if self.is_lab_host(host):
            return True
        for entry in self.git_remotes:
            if "/" in entry and not self._is_cidr(entry):
                entry_host, entry_repo = entry.split("/", 1)
                if entry_host == host and (repo is None or entry_repo == repo.lower()):
                    return True
            elif self._matches(entry, host):
                return True
        return False


# ---------------------------------------------------------------------------
# Git access
# ---------------------------------------------------------------------------

class Git:
    """Thin wrapper over the git binary that converts every failure to a block."""

    def __init__(self, cwd):
        self.cwd = cwd
        self.exe = shutil.which("git")
        if not self.exe:
            raise GuardError("git is not available on PATH; the guard cannot verify anything")

    def run(self, args, cwd=None, binary=False):
        """Run git; return (returncode, stdout). Any OS-level failure is a GuardError."""
        try:
            proc = subprocess.run(
                [self.exe] + list(args), cwd=cwd or self.cwd,
                capture_output=True, timeout=20)
        except (OSError, subprocess.SubprocessError) as exc:
            raise GuardError(f"git could not be run ({exc.__class__.__name__}: {exc})")
        out = proc.stdout if binary else proc.stdout.decode("utf-8", "replace")
        return proc.returncode, out

    def must(self, args, cwd=None, what="git"):
        """Run git and require success."""
        rc, out = self.run(args, cwd=cwd)
        if rc != 0:
            raise GuardError(f"{what} failed (git {' '.join(args)} exited {rc})")
        return out.strip()

    def toplevel(self, cwd=None):
        """Real path of the work tree containing ``cwd``."""
        return os.path.realpath(self.must(["rev-parse", "--show-toplevel"], cwd=cwd,
                                          what="locating the git work tree"))

    def allowlist(self, toplevel):
        """The allowlist as committed on HEAD — never the working-tree copy."""
        rc, out = self.run(["show", f"HEAD:{ALLOWLIST_REL_PATH}"], cwd=toplevel)
        if rc != 0:
            return Allowlist()
        return Allowlist.parse(out)


# ---------------------------------------------------------------------------
# File verification (Artifact / SendUserFile)
# ---------------------------------------------------------------------------

def verify_tracked_clean(path, git, project_toplevel):
    """Return the normalised path if ``path`` is a tracked, unmodified file
    inside the project's work tree; raise GuardError otherwise."""
    if not path or not isinstance(path, str):
        raise GuardError("no file_path was supplied, so there is nothing to verify")
    candidate = path if os.path.isabs(path) else os.path.join(project_toplevel, path)
    real = os.path.realpath(candidate)
    if not os.path.isfile(real):
        raise GuardError(f"{path!r} does not resolve to a regular file (resolved: {real!r})")
    if os.path.commonpath([real, project_toplevel]) != project_toplevel:
        raise GuardError(
            f"{path!r} resolves to {real!r}, which is outside the project work tree "
            f"{project_toplevel!r} (symlinks and '..' are fully resolved before checking)")
    file_toplevel = git.toplevel(cwd=os.path.dirname(real))
    if file_toplevel != project_toplevel:
        raise GuardError(f"{real!r} belongs to a different git repository ({file_toplevel!r})")
    rel = os.path.relpath(real, project_toplevel)

    rc, _ = git.run(["ls-files", "--error-unmatch", "--", rel], cwd=project_toplevel)
    if rc != 0:
        raise GuardError(f"{rel!r} is not tracked by git (untracked or ignored files never leave)")
    index_entry = git.must(["ls-files", "-s", "--", rel], cwd=project_toplevel,
                           what="reading the index entry")
    fields = index_entry.split()
    if len(fields) < 2:
        raise GuardError(f"could not read the index entry for {rel!r}")
    mode, index_blob = fields[0], fields[1]
    if mode == "120000":
        raise GuardError(f"{rel!r} is tracked as a symlink; only regular files may be published")
    worktree_blob = git.must(["hash-object", "--", real], cwd=project_toplevel,
                             what="hashing the working-tree file")
    if worktree_blob == index_blob:
        return real
    rc, head_blob = git.run(["rev-parse", "--verify", "--quiet", f"HEAD:{rel}"],
                            cwd=project_toplevel)
    if rc == 0 and head_blob.strip() == worktree_blob:
        return real
    raise GuardError(
        f"{rel!r} is tracked but its working-tree bytes differ from both the index and "
        f"HEAD; only the version-controlled bytes may be published (stage or revert it first)")


def _metadata_length(tool_input):
    total = 0
    for key in ("title", "description", "label", "text", "prompt"):
        value = tool_input.get(key)
        if isinstance(value, str):
            total += len(value)
    return total


def decide_artifact(tool_input, git, project_toplevel):
    """Apply the artifact policy; return (verdict, rule, target) or raise GuardError."""
    action = tool_input.get("action") or "publish"
    if not isinstance(action, str):
        raise GuardError("action is not a string")
    if action in ARTIFACT_READ_ONLY_ACTIONS or action in ARTIFACT_INERT_ACTIONS:
        return "allow", f"artifact:{action}:read-only", tool_input.get("url") or ""
    if action in ARTIFACT_TEXT_ACTIONS:
        raise GuardError(
            f"Artifact action {action!r} sends text authored in this session to a comment "
            f"thread; free text never leaves through the assistant's tools")
    if action in ARTIFACT_FILE_ACTIONS:
        if action == "publish" and _metadata_length(tool_input) > MAX_METADATA_CHARS:
            raise GuardError(
                f"title/description/label total more than {MAX_METADATA_CHARS} characters; "
                f"metadata is a caption, not a channel — shorten it")
        real = verify_tracked_clean(tool_input.get("file_path"), git, project_toplevel)
        return "allow", f"artifact:{action}:tracked-clean", real
    if action == "write_db":
        return _decide_write_db(tool_input, git, project_toplevel)
    raise GuardError(f"Artifact action {action!r} is not recognised by the guard; "
                     f"unknown actions are denied")


def _decide_write_db(tool_input, git, project_toplevel):
    op = tool_input.get("db_op")
    writes = []
    if op == "batch":
        entries = tool_input.get("writes")
        if not isinstance(entries, list) or not entries:
            raise GuardError("write_db batch carries no writes")
        writes = entries
    elif op in ("set", "update", "delete"):
        writes = [dict(tool_input, op=op)]
    else:
        raise GuardError(f"write_db with db_op {op!r} is not recognised; denied")
    targets = []
    for entry in writes:
        if not isinstance(entry, dict):
            raise GuardError("write_db entry is not an object")
        entry_op = entry.get("op")
        if entry_op == "delete":
            continue
        if entry_op not in ("set", "update"):
            raise GuardError(f"write_db entry op {entry_op!r} is not recognised; denied")
        if entry.get("data") is not None:
            raise GuardError(
                "write_db with inline `data` sends session-authored content to the artifact "
                "database; only a repository-tracked JSON file (file_path) may be written")
        targets.append(verify_tracked_clean(entry.get("file_path"), git, project_toplevel))
    return "allow", "artifact:write_db:tracked-clean", ", ".join(targets) or "delete-only"


def decide_send_user_file(tool_input, git, project_toplevel):
    """SendUserFile follows the artifact publish rule exactly."""
    candidates = [tool_input.get(k) for k in SEND_FILE_PATH_KEYS if tool_input.get(k)]
    if not candidates:
        raise GuardError("SendUserFile input carries no file path the guard recognises")
    targets = [verify_tracked_clean(c, git, project_toplevel) for c in candidates]
    return "allow", "send-user-file:tracked-clean", ", ".join(targets)


# ---------------------------------------------------------------------------
# Bash analysis: the public surface of the bash modules
# ---------------------------------------------------------------------------

# ``SHELLS`` is re-exported so callers that used to read it from the
# monolithic module still find it here. The runner imports its copy
# directly from ``exfil_bash_lex``; this binding exists only for
# backward compatibility.
__all__ = [
    "Allowlist", "Git", "GuardError", "ALLOWLIST_REL_PATH", "AUDIT_LOG_PATH",
    "BASH_POLICY_STATEMENT", "BashContext", "INERT_TOOLS", "MAX_METADATA_CHARS",
    "POLICY_STATEMENT", "RULE_CHANGE_INVITATION", "SHELLS", "analyze_command",
    "bash_targets", "decide", "decide_artifact", "decide_send_user_file",
    "main", "normalize_host", "owner_repo", "verify_tracked_clean",
]


# ---------------------------------------------------------------------------
# Decision + audit
# ---------------------------------------------------------------------------

def _write_audit(entry, log_path):
    try:
        directory = os.path.dirname(log_path)
        os.makedirs(directory, mode=0o700, exist_ok=True)
        with open(log_path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")
        os.chmod(log_path, 0o600)
        return True
    except OSError:
        return False


def _block(reason, policy, audit):
    audit["verdict"] = "block"
    audit["reason"] = reason
    text = f"BLOCKED by the exfiltration guard: {reason}\n\n{policy}"
    return {"action": "block", "reason": text, "context": "", "stderr": text, "audit": audit}


def decide(payload, hook_cwd=None, log_path=AUDIT_LOG_PATH):
    """Compute the Decision for a hook payload.

    :param payload: the parsed hook JSON (tool_name, tool_input, cwd, session_id)
    :param hook_cwd: the project directory the hook runs in (defaults to os.getcwd())
    :param log_path: the audit log file
    """
    hook_cwd = hook_cwd or os.getcwd()
    audit = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "cwd": hook_cwd}
    if not isinstance(payload, dict):
        return _block("hook input is not a JSON object", POLICY_STATEMENT, audit)
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input")
    audit["session_id"] = payload.get("session_id") or ""
    audit["tool"] = tool if isinstance(tool, str) else repr(tool)
    if not isinstance(tool, str) or not isinstance(tool_input, dict):
        return _block("hook input has no usable tool_name/tool_input", POLICY_STATEMENT, audit)
    policy = BASH_POLICY_STATEMENT if tool == "Bash" else POLICY_STATEMENT
    if tool == "Bash":
        command_value = tool_input.get("command")
        audit["command"] = command_value[:2000] if isinstance(command_value, str) else repr(command_value)
    else:
        audit["action"] = tool_input.get("action") or ("publish" if tool.startswith("Artifact") else "")

    try:
        git = Git(hook_cwd)
        project_toplevel = git.toplevel()
        if tool in INERT_TOOLS:
            verdict, rule, target = "allow", f"inert-tool:{tool}", ""
        elif tool.startswith("Artifact"):
            verdict, rule, target = decide_artifact(tool_input, git, project_toplevel)
        elif tool == "SendUserFile":
            verdict, rule, target = decide_send_user_file(tool_input, git, project_toplevel)
        elif tool == "Bash":
            command = tool_input.get("command")
            if not isinstance(command, str):
                raise GuardError("Bash input has no command string")
            command_cwd = payload.get("cwd") if isinstance(payload.get("cwd"), str) else hook_cwd
            ctx = BashContext(git.allowlist(project_toplevel), git, project_toplevel, command_cwd)
            reason = analyze_command(command, ctx)
            if reason:
                raise GuardError(reason)
            verdict, rule, target = "allow", "bash:clean", bash_targets(command, ctx)
        else:
            raise GuardError(f"tool {tool!r} is not one the guard understands; denied")
    except GuardError as exc:
        audit["target"] = ""
        decision = _block(str(exc), policy, audit)
        _write_audit(audit, log_path)
        return decision
    except Exception as exc:  # any internal failure is a block, never an allow
        audit["target"] = ""
        decision = _block(f"internal guard error {exc.__class__.__name__}: {exc}", policy, audit)
        _write_audit(audit, log_path)
        return decision

    audit.update({"verdict": "allow", "rule": rule, "target": target})
    if not _write_audit(audit, log_path):
        return _block(f"the audit log {log_path!r} could not be written; an unaudited action "
                      f"does not run", policy, audit)
    return {"action": "allow", "reason": "", "context": "", "stderr": "", "audit": audit}


def _render_harness_native(decision):
    if decision.get("action") == "block":
        sys.stderr.write(decision.get("reason", "") or "BLOCKED by the exfiltration guard\n")
        sys.exit(2)
    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        try:
            payload = json.loads(sys.stdin.read())
        except Exception:
            payload = None
        _render_harness_native(decide(payload))
        return
    if not argv:
        sys.stderr.write("usage: exfiltration_guard_check.py --stdin | <payload-json>\n")
        sys.exit(2)
    try:
        payload = json.loads(argv[0])
    except Exception:
        payload = None
    print(json.dumps(decide(payload)))
    sys.exit(0)


if __name__ == "__main__":
    main()