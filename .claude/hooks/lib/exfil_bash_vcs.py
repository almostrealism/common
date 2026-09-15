"""``git`` and ``gh`` handling for the exfiltration guard.

Both are the official channel to the project's origin — ``git push`` is
the only way the harness is supposed to publish — so the rule is
narrow: ``git push`` must go to ``origin``, ``origin`` must be this
project's origin, and the project must be an allowlisted git remote.
``gh`` is allowed for the read/write operations on issues, PRs, runs and
workflows, and ``gh api`` is limited to read requests; anything that
uploads a body (``--body-file``, ``-f``, ``-F``) is a block.

This module is the leaf of the bash dispatch tree — no other guard
module imports from it.
"""
import os
import re
import sys

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from exfil_bash_lex import GuardError, match_flag, owner_repo


# `gh` subcommands allowed at all, and the flags that turn an allowed one
# into an upload.
GH_ALLOWED_SUBCOMMANDS = frozenset({
    "pr", "issue", "run", "workflow", "search", "status", "browse", "label",
    "cache", "auth", "repo", "release", "api", "extension", "alias", "config",
    "help", "version", "--version", "--help",
})
GH_ALLOWED_SECOND = {
    "auth": {"status"},
    "repo": {"view", "list", "clone"},
    "release": {"list", "view", "download"},
    "extension": {"list"},
    "alias": {"list"},
    "config": {"get", "list"},
    "cache": {"list", "delete"},
}
GH_BODY_FILE_FLAGS = frozenset({"--body-file", "-F"})
GH_API_BODY_FLAGS = frozenset({"-f", "-F", "--field", "--raw-field", "--input"})
GH_API_METHOD_FLAGS = frozenset({"-X", "--method"})

GIT_BLOCKED_SUBCOMMANDS = frozenset({
    "send-email", "svn", "p4", "daemon", "instaweb", "credential",
    "credential-store", "credential-cache", "imap-send", "request-pull",
})
_GIT_REMOTE_MUTATORS = frozenset({"add", "set-url", "rename"})
_GIT_CONFIG_SENSITIVE = re.compile(
    r"^(remote\.|url\.|http\.|https\.|core\.sshcommand|credential\.|core\.gitproxy|"
    r"core\.askpass|gpg\.program|diff\.external|sendemail\.)", re.IGNORECASE)
_GIT_GLOBAL_OPTS_WITH_ARG = {
    "-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
}
_GIT_PUSH_BLOCKED_FLAGS = ("--repo", "--receive-pack", "--exec")


def _git_call(argv):
    """Return (subcommand, args, global_opts) for a git argv."""
    opts = []
    j = 1
    while j < len(argv):
        tok = argv[j]
        if tok in _GIT_GLOBAL_OPTS_WITH_ARG:
            opts.append((tok, argv[j + 1] if j + 1 < len(argv) else ""))
            j += 2
            continue
        if tok.startswith("-"):
            opts.append((tok.split("=", 1)[0], tok.split("=", 1)[1] if "=" in tok else ""))
            j += 1
            continue
        break
    if j >= len(argv):
        return "", [], opts
    return argv[j], argv[j + 1:], opts


def _check_git(argv, ctx):
    sub, args, opts = _git_call(argv)
    for opt, value in opts:
        if opt in ("-C", "--git-dir", "--work-tree"):
            raise _GuardError(f"git {opt} points at another repository; only this project's "
                              f"origin may be pushed to")
        if opt == "-c" and _GIT_CONFIG_SENSITIVE.match(value):
            raise _GuardError(f"git -c {value!r} rewrites remote/transport configuration; denied")
    if sub in GIT_BLOCKED_SUBCOMMANDS:
        raise _GuardError(f"git {sub} sends data or credentials somewhere else; denied")
    if sub == "remote" and args and args[0] in _GIT_REMOTE_MUTATORS:
        raise _GuardError(f"git remote {args[0]} changes where pushes go; a human edits remotes")
    if sub == "config":
        keys = [a for a in args if not a.startswith("-")]
        if any(_GIT_CONFIG_SENSITIVE.match(k) for k in keys) and not any(
                a in ("--get", "--get-all", "--get-regexp", "-l", "--list") for a in args):
            raise _GuardError("git config write to remote/url/http/credential settings; denied")
        return "git:config"
    if sub != "push":
        return f"git:{sub or 'none'}"
    for a in args:
        if a.split("=", 1)[0] in _GIT_PUSH_BLOCKED_FLAGS:
            raise _GuardError(f"git push {a} overrides the remote; denied")
    ctx.require_cwd("git push")
    positionals = [a for a in args if not a.startswith("-")]
    remote = positionals[0] if positionals else None
    if remote is None:
        rc, branch = ctx.git.run(["branch", "--show-current"], cwd=ctx.command_cwd)
        branch = branch.strip()
        if rc != 0 or not branch:
            raise _GuardError("git push with no remote on a detached HEAD; denied")
        rc, configured = ctx.git.run(["config", "--get", f"branch.{branch}.remote"],
                                     cwd=ctx.command_cwd)
        if rc != 0 or not configured.strip():
            raise _GuardError(f"git push with no remote and no configured remote for "
                              f"{branch!r}; name the remote explicitly (origin)")
        remote = configured.strip()
    if remote != "origin":
        raise _GuardError(f"git push to {remote!r}; only `origin` may be pushed to")
    rc, url = ctx.git.run(["remote", "get-url", "origin"], cwd=ctx.command_cwd)
    if rc != 0:
        raise _GuardError("origin has no URL in the directory this command runs in; denied")
    target = _owner_repo(url)
    project = ctx.project_origin()
    if target is None or project is None:
        raise _GuardError(f"origin URL {url.strip()!r} could not be compared with the project's origin")
    if target != project:
        raise _GuardError(f"origin here is {target[0]}:{target[1]}, not the project's "
                          f"{project[0]}:{project[1]}; pushing another repository is denied")
    if not ctx.allowlist.is_git_remote(target[0], target[1]):
        raise _GuardError(f"origin {target[0]}/{target[1]} is not an allowlisted git remote")
    return f"git:push:{target[0]}/{target[1]}"


def _check_gh(argv, ctx):
    args = argv[1:]
    hostname = None
    i = 0
    while i < len(args) and args[i].startswith("-"):
        if args[i] in ("--hostname", "-h") and i + 1 < len(args):
            hostname = args[i + 1]
            i += 2
            continue
        if args[i].startswith("--hostname="):
            hostname = args[i].split("=", 1)[1]
        i += 1
    if hostname and not ctx.allowlist.is_git_remote(hostname):
        raise _GuardError(f"gh --hostname {hostname!r} is not an allowlisted git remote")
    rest = args[i:]
    if not rest:
        return "gh:none"
    sub = rest[0]
    if sub not in GH_ALLOWED_SUBCOMMANDS:
        raise _GuardError(f"gh {sub} is not a sanctioned operation (only pr/issue/run/workflow "
                          f"reads and writes with inline bodies are allowed)")
    if sub in GH_ALLOWED_SECOND:
        second = next((a for a in rest[1:] if not a.startswith("-")), "")
        if second not in GH_ALLOWED_SECOND[sub]:
            raise _GuardError(f"gh {sub} {second} is not a sanctioned operation")
    if sub in ("pr", "issue"):
        for a in rest[1:]:
            flag, _ = _match_flag(a, GH_BODY_FILE_FLAGS)
            if flag:
                raise _GuardError(f"gh {sub} with {a}: a body read from a file cannot be reviewed "
                                  f"in the transcript; pass --body with the text inline")
    if sub == "api":
        for k, a in enumerate(rest[1:], 1):
            flag, value = _match_flag(a, GH_API_BODY_FLAGS)
            if flag:
                raise _GuardError(f"gh api with {flag}: request bodies are denied")
            flag, value = _match_flag(a, GH_API_METHOD_FLAGS)
            if flag:
                if value is None:
                    value = rest[k + 1] if k + 1 < len(rest) else ""
                elif value.startswith("="):
                    value = value[1:]
                if value.upper() not in ("GET", "HEAD"):
                    raise _GuardError(f"gh api with method {value}: mutating requests are denied")
    return f"gh:{sub}"


# Backward-compatible alias: earlier callers raised ``_GuardError``
# because each module had its own stub. They now share ``GuardError``
# from ``exfil_bash_lex``; this alias keeps any code that named the
# local stub still working.
_GuardError = GuardError


def _match_flag(tok, flags):
    """Wrap the shared ``match_flag`` so callers in this module can keep
    the leading underscore that signals "module-private"."""
    return match_flag(tok, flags)


def _owner_repo(url):
    """Wrap the shared ``owner_repo`` so callers in this module can keep
    the leading underscore that signals "module-private"."""
    return owner_repo(url)
