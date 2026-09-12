#!/usr/bin/env python3
"""Decide whether a tool call could move session data off this machine.

This module is the single source of truth for the exfiltration policy
described in docs/plans/EXFILTRATION_GUARD_HOOK.md. It is invoked by

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
"""
import ipaddress
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from urllib.parse import urlparse


ALLOWLIST_REL_PATH = ".claude/hooks/exfil-allowlist.txt"
AUDIT_LOG_PATH = os.path.join(os.path.expanduser("~"), ".ar-hooks", "exfiltration-guard.log")

# Free-text metadata the Artifact tool sends with a publish (title,
# description, label). The tool needs a title, so this channel is bounded
# rather than closed: a short caption cannot carry a file.
MAX_METADATA_CHARS = 300
# A GET is allowed to any host, but a URL long enough to carry a payload in
# its query string is not.
MAX_URL_LENGTH = 512
# Script files handed to an interpreter or shell are read and scanned;
# anything larger than this is blocked rather than skipped.
MAX_SCRIPT_BYTES = 2 * 1024 * 1024
MAX_NESTING_DEPTH = 3

POLICY_STATEMENT = (
    "Policy: artifacts exist for ONE purpose — showing a file that is already\n"
    "tracked in this repository in the Claude app. The file must be inside the\n"
    "project's git work tree, tracked by git, and byte-identical to the version\n"
    "in the index or HEAD. Nothing else — scratchpad files, modified files,\n"
    "text authored in this session, database rows, comment replies — may leave\n"
    "this machine through the assistant's tools. If the content belongs in the\n"
    "repository, add it and stage it (git add) and leave it unmodified; then\n"
    "publish. If it does not belong in the repository, it does not get\n"
    "published. See docs/plans/EXFILTRATION_GUARD_HOOK.md.\n"
)

BASH_POLICY_STATEMENT = (
    "Policy: no data leaves this machine through the assistant's tools except\n"
    "a repository-tracked file published as an artifact. Network commands are\n"
    "allowed only to hosts listed in .claude/hooks/exfil-allowlist.txt (as\n"
    "committed on HEAD); `git push` only to origin; `gh` only for pr/issue\n"
    "reads and writes with an inline body. If this command is legitimate, the\n"
    "developer can run it by hand — do not try to rephrase it past the guard.\n"
    "See docs/plans/EXFILTRATION_GUARD_HOOK.md.\n"
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

# ---------------------------------------------------------------------------
# Bash: program classes
# ---------------------------------------------------------------------------

SHELLS = frozenset({"bash", "sh", "zsh", "ksh", "dash", "fish", "eval", "source", "."})

# Tools that cannot move anything off the machine, and so are allowed
# without inspection rather than denied as unrecognised. TaskStop ends a
# background task; TaskOutput reads one's output back into the session.
# Both move data inward or not at all, and whatever the task itself does
# was inspected when the tool that started it ran.
INERT_TOOLS = frozenset({"TaskStop", "TaskOutput"})

# Programs whose leading tokens are dropped to find the real command.
WRAPPERS = frozenset({
    "env", "sudo", "doas", "nohup", "time", "nice", "ionice", "timeout",
    "command", "exec", "builtin", "caffeinate", "stdbuf", "unbuffer",
    "chronic", "xargs", "parallel", "watch",
})
_WRAPPER_FLAGS_WITH_ARG = {
    "sudo": {"-u", "-g", "-C", "-D", "-h", "-p", "-r", "-t", "-T", "-U"},
    "doas": {"-u", "-C"},
    "nice": {"-n"},
    "ionice": {"-c", "-n", "-p"},
    "xargs": {"-I", "-n", "-P", "-L", "-s", "-d", "-a", "-E", "-R", "-S"},
    "parallel": {"-j", "-a", "-N", "-L", "-I"},
    "watch": {"-n", "-d"},
    "stdbuf": {"-i", "-o", "-e"},
    "timeout": {"-s", "-k", "--signal", "--kill-after"},
}
_WRAPPER_POSITIONALS = {"timeout": 1}

INTERPRETERS = frozenset({
    "python", "python2", "python3", "pypy", "pypy3", "node", "nodejs", "deno",
    "bun", "ruby", "perl", "php", "lua", "luajit", "Rscript", "julia",
    "groovy", "jshell", "tclsh", "powershell", "pwsh", "awk", "gawk", "mawk",
    "nawk",
})
_INTERPRETER_CODE_FLAGS = frozenset({
    "-c", "-e", "-E", "-p", "-r", "-C", "--eval", "--print", "-Command",
    "-command", "-EncodedCommand", "-enc", "-ec", "eval",
})
_INTERPRETER_MODULE_FLAG = "-m"
NETWORK_MODULES = frozenset({
    "http.server", "SimpleHTTPServer", "smtplib", "ftplib", "telnetlib",
    "webbrowser", "twine", "pyftpdlib", "uploadserver", "wsgiref.simple_server",
    "urllib.request", "http.client", "socket", "requests", "httpx",
})

# Patterns that mark an inline program (or a script file) as network-capable.
NETWORK_CODE_PATTERNS = [re.compile(p) for p in (
    r"\brequests\.", r"\burllib\b", r"\bhttp\.client\b", r"\bhttplib\b",
    r"\bhttpx\b", r"\baiohttp\b", r"\bsocket\b", r"\bsmtplib\b", r"\bftplib\b",
    r"\btelnetlib\b", r"\bparamiko\b", r"\bboto3\b", r"\bbotocore\b",
    r"\bpycurl\b", r"\bwebsocket", r"\bfetch\s*\(", r"\bXMLHttpRequest\b",
    r"\bhttps?\.request\b", r"\bnet\.connect\b", r"\bnet\.createConnection\b",
    r"\bdgram\b", r"\bWebSocket\b", r"\baxios\b", r"\bgot\s*\(",
    r"\bnode-fetch\b", r"\bNet::", r"\bLWP\b", r"\bIO::Socket\b",
    r"\bHTTP::Tiny\b", r"\bcurl_", r"\bfsockopen\b", r"\bstream_socket_client\b",
    r"\bInvoke-WebRequest\b", r"\bInvoke-RestMethod\b", r"\bNet\.WebClient\b",
    r"\bSystem\.Net\b", r"\bHttpClient\b", r"\bURLConnection\b", r"\bjava\.net\b",
    r"\bnew\s+URL\s*\(", r"\bSocket\s*\(", r"\bdo shell script\b",
    r"https?://", r"\bsystem\s*\(", r"\bsubprocess\b", r"\bos\.system\b",
    r"\bchild_process\b", r"\bexec\s*\(", r"\bpopen\b", r"/inet/",
    r"\|\s*getline\b", r"\bopen\s*\(\s*['\"]https?:",
)]

# Shell script FILES are scanned by pattern rather than parsed as command
# lines: array literals, case arms and multi-line strings defeat a
# tokenizer that was built for one command. Comment lines are dropped
# first; anything else that names a network tool, a push, or a raw socket
# counts. Prose in an echo still trips it — that is the accepted cost.
SHELL_SCRIPT_PATTERNS = [re.compile(p) for p in (
    r"(?<![\w./-])(?:curl|wget|scp|sftp|rsync|ssh|nc|ncat|netcat|socat|telnet|ftp|lftp|tftp)"
    r"(?![\w.-])",
    r"/dev/(?:tcp|udp)/",
    r"\bgit\s+push\b",
    r"\bgh\s+(?:gist|release|api|secret|variable|repo\s+create)\b",
    r"(?<![\w./-])(?:aws|gsutil|gcloud|az|rclone|s3cmd|mail|mailx|sendmail|osascript|ngrok|"
    r"cloudflared|docker\s+push|npm\s+publish|twine)(?![\w.-])",
    r"\bopenssl\s+s_client\b",
)]

# Programs that take a destination host and are allowed only to lab hosts.
UPLOAD_TOOLS = frozenset({"curl", "wget"})
SSH_FAMILY = frozenset({"ssh", "scp", "sftp", "rsync"})
RAW_SOCKET_TOOLS = frozenset({"nc", "ncat", "netcat", "socat", "telnet", "ftp",
                              "lftp", "tftp", "openssl"})
PROBE_TOOLS = frozenset({"ping", "ping6", "dig", "nslookup", "host", "traceroute",
                         "mtr", "nmap", "whois"})

# Programs blocked outright: cloud/upload CLIs, mail, tunnels, file servers,
# anything whose purpose is to move data somewhere else.
ALWAYS_BLOCKED = frozenset({
    "aws", "gsutil", "gcloud", "az", "rclone", "s3cmd", "b2", "mc", "doctl",
    "flyctl", "fly", "heroku", "vercel", "netlify", "wrangler", "firebase",
    "twine", "kubectl", "helm", "terraform", "ansible", "ansible-playbook",
    "mail", "mailx", "sendmail", "mutt", "msmtp", "swaks", "osascript",
    "automator", "shortcuts", "pastebinit", "transfer", "croc", "wormhole",
    "magic-wormhole", "ngrok", "cloudflared", "bore", "localtunnel", "lt",
    "http-server", "serve", "caddy", "nginx", "miniserve", "darkhttpd",
    "open", "xdg-open", "skopeo", "s3", "gdrive", "dropbox_uploader.sh",
    "onedrive", "megacmd", "mega-put", "ftp-upload", "curlftpfs",
})

# Subcommands of otherwise-allowed programs that publish or upload.
BLOCKED_SUBCOMMANDS = {
    "docker": {"push", "login"},
    "podman": {"push", "login"},
    "npm": {"publish"},
    "yarn": {"publish"},
    "pnpm": {"publish"},
    "cargo": {"publish"},
    "mvn": {"deploy", "deploy:deploy", "nexus-staging:deploy", "release:perform"},
    "gradle": {"publish", "publishToMavenLocal", "uploadArchives", "bintrayUpload"},
    "tailscale": {"file", "serve", "funnel"},
    "php": {"-S"},
    "gem": {"push"},
    "pip": {"upload"},
    "hg": {"push"},
    "svn": {"commit", "ci", "import"},
}

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

# Folders that sync to a cloud provider: a plain `cp` into one is an upload.
SYNCED_FOLDER_PATTERNS = re.compile(
    r"Mobile Documents|/Dropbox/|/Google Drive/|/OneDrive/|/CloudStorage/|"
    r"/Box Sync/|/Box/|/iCloud", re.IGNORECASE)
COPY_TOOLS = frozenset({"cp", "mv", "rsync", "tee", "dd", "tar", "zip", "ln",
                        "install", "ditto", "cat", "unzip", "gzip", "bzip2", "xz"})
_DEV_TCP = re.compile(r"/dev/(?:tcp|udp)/([^/\s'\"]+)/")

_SEPARATOR_TOKENS = frozenset({";", ";;", "&&", "||", "|", "|&", "&", "\n", "(", ")"})
_PIPE_TOKENS = frozenset({"|", "|&"})
_KEYWORD_TOKENS = frozenset({
    "if", "then", "else", "elif", "fi", "while", "until", "do", "done", "{", "}",
    "!", "time", "esac",
})
_REDIRECT_TOKENS = frozenset({">", ">>", "<", ">&", "<&", "<>", ">|", "&>", "&>>", "<<<"})
_HEREDOC_START = re.compile(r"<<-?\s*(?P<q>['\"]?)(?P<word>[A-Za-z_][A-Za-z0-9_]*)(?P=q)")
_SUBSTITUTION = re.compile(r"\$\(|`|<\(|>\(")
_HOSTISH = re.compile(r"^(?:[A-Za-z0-9_.-]+@)?\[?[A-Za-z0-9.:_-]+\]?(?::\d+)?$")
_REMOTE_SPEC = re.compile(r"^(?:[A-Za-z0-9_.-]+@)?(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9][A-Za-z0-9.-]*)::?(?!//)")
_NETWORK_WORD = re.compile(
    r"(?<![\w./-])(?:curl|wget|scp|sftp|rsync|ssh|nc|ncat|netcat|socat|telnet|ftp|lftp|tftp)"
    r"(?![\w.-])")
_DEFINITION = re.compile(r"(^|[\s;&|])(?:alias|function)\s|^[A-Za-z_][A-Za-z0-9_]*\s*\(\)")
SYSTEM_PREFIXES = ("/bin/", "/sbin/", "/usr/", "/opt/homebrew/", "/opt/local/",
                   "/Library/", "/System/", "/Applications/", "/nix/")


class GuardError(Exception):
    """Raised for any condition the guard must treat as a block."""


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


def normalize_host(host):
    """Strip user@, :port and IPv6 brackets; lower-case the result."""
    if not host:
        return ""
    host = host.strip()
    if "@" in host:
        host = host.rsplit("@", 1)[1]
    if host.startswith("[") and "]" in host:
        host = host[1:host.index("]")]
    elif host.count(":") == 1:
        host = host.split(":", 1)[0]
    return host.strip().rstrip(".").lower()


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
# Bash analysis
# ---------------------------------------------------------------------------

class BashContext:
    """What the Bash analysis needs from the outside world."""

    def __init__(self, allowlist, git, project_toplevel, command_cwd):
        self.allowlist = allowlist
        self.git = git
        self.project_toplevel = project_toplevel
        self.command_cwd = command_cwd

    def at(self, cwd):
        """The same context with a different effective working directory
        (``None`` when a ``cd`` made it undeterminable)."""
        return BashContext(self.allowlist, self.git, self.project_toplevel, cwd)

    def require_cwd(self, what):
        """The effective working directory, or a block when it is unknown."""
        if self.command_cwd is None:
            raise GuardError(f"{what}: the working directory could not be determined after a "
                             f"`cd` whose target is computed at run time; run the command "
                             f"from a literal directory")
        return self.command_cwd

    def project_origin(self):
        """owner/repo of this project's origin, or None."""
        rc, url = self.git.run(["remote", "get-url", "origin"], cwd=self.project_toplevel)
        return owner_repo(url) if rc == 0 else None


def owner_repo(url):
    """Normalise a git remote URL to (host, owner/repo) so git@/https spellings compare equal."""
    if not url:
        return None
    url = url.strip()
    m = re.match(r"^(?:ssh://)?(?:[A-Za-z0-9_.-]+@)?([A-Za-z0-9.-]+)[:/]+([^/:]+)/([^/]+?)(?:\.git)?/?$", url)
    if not m:
        m = re.match(r"^https?://([A-Za-z0-9.-]+)(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?/?$", url)
    if not m:
        return None
    return m.group(1).lower(), f"{m.group(2)}/{m.group(3)}".lower()


def _strip_heredoc_bodies(command):
    if "<<" not in command:
        return command, []
    lines = command.split("\n")
    kept, bodies, i = [], [], 0
    while i < len(lines):
        line = lines[i]
        match = _HEREDOC_START.search(line)
        if not match or line[max(0, match.start() - 1):match.start()] == "<":
            kept.append(line)
            i += 1
            continue
        kept.append(line[:match.start()] + " <<HEREDOC " + line[match.end():])
        delimiter = match.group("word")
        body = []
        i += 1
        while i < len(lines) and lines[i].strip() != delimiter:
            body.append(lines[i])
            i += 1
        i += 1
        bodies.append("\n".join(body))
    return "\n".join(kept), bodies


def _tokenize(command):
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        return list(lexer)
    except ValueError:
        return None


def _simple_commands(tokens):
    """Split a token stream into (argv, piped_in, has_heredoc) simple commands."""
    commands, current, piped, heredoc = [], [], False, False
    at_start = True
    skip_words = False
    i = 0

    def flush(next_piped):
        nonlocal current, piped, heredoc, at_start, skip_words
        if current:
            commands.append((current, piped, heredoc))
        current, piped, heredoc, at_start, skip_words = [], next_piped, False, True, False

    while i < len(tokens):
        tok = tokens[i]
        if tok in _SEPARATOR_TOKENS:
            flush(tok in _PIPE_TOKENS)
            i += 1
            continue
        if tok == "HEREDOC" and i > 0 and tokens[i - 1] == "<<":
            heredoc = True
            i += 1
            continue
        if tok == "<<":
            i += 1
            continue
        if tok in _REDIRECT_TOKENS:
            if current and current[-1].isdigit():
                current.pop()
            i += 2
            continue
        if at_start and tok in _KEYWORD_TOKENS:
            i += 1
            continue
        if at_start and tok in ("for", "case", "select"):
            skip_words = True
            i += 1
            continue
        if skip_words:
            i += 1
            continue
        current.append(tok)
        at_start = False
        i += 1
    flush(False)
    return commands


def _unwrap(argv):
    """Drop env assignments and wrapper programs; return the real argv."""
    argv = list(argv)
    for _ in range(8):
        while argv and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", argv[0]):
            argv.pop(0)
        if not argv:
            return argv
        prog = os.path.basename(argv[0])
        if prog not in WRAPPERS:
            return argv
        if prog == "command" and len(argv) > 1 and argv[1] in ("-v", "-V"):
            return []
        with_arg = _WRAPPER_FLAGS_WITH_ARG.get(prog, set())
        rest = argv[1:]
        j = 0
        while j < len(rest) and rest[j].startswith("-"):
            j += 2 if rest[j] in with_arg else 1
        if prog == "env":
            while j < len(rest) and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", rest[j]):
                j += 1
        j += _WRAPPER_POSITIONALS.get(prog, 0)
        argv = rest[j:]
    return argv


def _find_exec(argv):
    """Extract the command run by `find ... -exec CMD ... ;`."""
    for flag in ("-exec", "-execdir", "-ok", "-okdir"):
        if flag in argv:
            start = argv.index(flag) + 1
            end = start
            while end < len(argv) and argv[end] not in (";", "+"):
                end += 1
            return argv[start:end]
    return []


def _extract_hosts_from_url(token):
    if "://" not in token:
        return None
    parsed = urlparse(token)
    if parsed.scheme in ("file", ""):
        return ""
    return parsed.hostname or ""


_CURL_VALUE_CLUSTER = "oHAbceuUxmwKTdFXryYzCEQtPD"
_WGET_VALUE_CLUSTER = "OoaPUTtwQBeiFl"


def _url_positionals(argv, value_flags, cluster_chars):
    """Tokens that name a destination for curl/wget-style programs."""
    urls = []
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok == "--url" and i + 1 < len(argv):
            urls.append(argv[i + 1])
            i += 2
            continue
        if tok.startswith("--url="):
            urls.append(tok.split("=", 1)[1])
        elif tok.startswith("-") and tok != "-":
            if tok in value_flags or (len(tok) == 2 and tok in value_flags):
                i += 1
            elif not tok.startswith("--") and len(tok) > 2 and tok[-1] in cluster_chars:
                i += 1
        else:
            urls.append(tok)
        i += 1
    return urls


_CURL_VALUE_FLAGS = frozenset({
    "-o", "-H", "-A", "-b", "-c", "-e", "-u", "-U", "-x", "-m", "-w", "-K", "-T",
    "-d", "-F", "-X", "-r", "-y", "-Y", "-z", "-C", "-E", "-Q", "-t", "--output",
    "--header", "--user-agent", "--cookie", "--cookie-jar", "--referer", "--user",
    "--proxy-user", "--proxy", "--max-time", "--write-out", "--config",
    "--upload-file", "--data", "--data-ascii", "--data-binary", "--data-raw",
    "--data-urlencode", "--form", "--form-string", "--request", "--connect-timeout",
    "--retry", "--cacert", "--cert", "--key", "--resolve", "--interface",
    "--dns-servers", "--unix-socket", "--abstract-unix-socket", "--range",
    "--json", "--max-filesize", "--limit-rate", "--speed-limit", "--speed-time",
    "--stderr", "--trace", "--trace-ascii", "--dump-header", "--output-dir",
    "--create-file-mode", "--aws-sigv4", "--oauth2-bearer", "--proto",
    "--proto-redir", "--tls-max", "--tlsuser", "--tlspassword", "--variable",
    "--expect100-timeout", "--happy-eyeballs-timeout-ms", "--keepalive-time",
    "--local-port", "--max-redirs", "--noproxy", "--pinnedpubkey", "--retry-delay",
    "--retry-max-time", "--socks5", "--socks4", "--socks4a", "--socks5-hostname",
    "--time-cond", "--tcp-fastopen", "--url-query", "--service-name", "--sasl-authzid",
})
_CURL_UPLOAD_LONG = frozenset({
    "--data", "--data-ascii", "--data-binary", "--data-raw", "--data-urlencode",
    "--form", "--form-string", "--upload-file", "--json", "--url-query",
})
_CURL_REDIRECTING = frozenset({"--proxy", "-x", "--resolve", "--dns-servers", "--unix-socket",
                               "--abstract-unix-socket", "--socks5", "--socks4", "--socks4a",
                               "--socks5-hostname", "--connect-to", "--interface", "-K", "--config",
                               "--aws-sigv4"})


def _curl_uploads(argv):
    """Whether this curl invocation sends a body, and whether it redirects its destination."""
    uploads, redirects = False, False
    i = 1
    while i < len(argv):
        tok = argv[i]
        base = tok.split("=", 1)[0]
        if base in _CURL_UPLOAD_LONG:
            uploads = True
        if base in _CURL_REDIRECTING:
            redirects = True
        if base in ("-X", "--request"):
            value = tok.split("=", 1)[1] if "=" in tok else (argv[i + 1] if i + 1 < len(argv) else "")
            if value.upper() not in ("GET", "HEAD"):
                uploads = True
        if tok.startswith("-") and not tok.startswith("--") and len(tok) > 1:
            for ch in tok[1:]:
                if ch in "dFTKX":
                    if ch == "X":
                        idx = tok.index("X") + 1
                        value = tok[idx:] or (argv[i + 1] if i + 1 < len(argv) else "")
                        if value.upper() not in ("GET", "HEAD"):
                            uploads = True
                    elif ch == "K":
                        redirects = True
                    else:
                        uploads = True
                    break
        i += 1
    return uploads, redirects


_WGET_UPLOAD = ("--post-data", "--post-file", "--body-data", "--body-file")
_WGET_VALUE_FLAGS = frozenset({
    "-O", "-o", "-a", "-P", "-U", "-T", "-t", "-w", "-Q", "-B", "-e", "-i", "-F",
    "--output-document", "--output-file", "--append-output", "--directory-prefix",
    "--user-agent", "--timeout", "--tries", "--wait", "--quota", "--base", "--execute",
    "--input-file", "--header", "--user", "--password", "--http-user", "--http-password",
    "--referer", "--method", "--limit-rate", "--bind-address", "--ca-certificate",
    "--certificate", "--private-key", "--load-cookies", "--save-cookies", "--level", "-l",
})


def _wget_uploads(argv):
    uploads, redirects = False, False
    for i, tok in enumerate(argv[1:], 1):
        base = tok.split("=", 1)[0]
        if base in _WGET_UPLOAD:
            uploads = True
        if base in ("-e", "--execute", "--bind-address"):
            redirects = True
        if base == "--method":
            value = tok.split("=", 1)[1] if "=" in tok else (argv[i + 1] if i + 1 < len(argv) else "")
            if value.upper() not in ("GET", "HEAD"):
                uploads = True
    return uploads, redirects


def _check_upload_tool(prog, argv, piped, ctx):
    uploads, redirects = (_curl_uploads if prog == "curl" else _wget_uploads)(argv)
    if piped and prog == "curl":
        uploads = True
    if redirects:
        raise GuardError(f"{prog} is given a proxy/resolve/config option that changes where "
                         f"the request actually goes; the destination cannot be verified")
    if prog == "curl":
        urls = _url_positionals(argv, _CURL_VALUE_FLAGS, _CURL_VALUE_CLUSTER)
    else:
        urls = _url_positionals(argv, _WGET_VALUE_FLAGS, _WGET_VALUE_CLUSTER)
    if not urls:
        raise GuardError(f"{prog} destination could not be determined from the command")
    for url in urls:
        if url.startswith("$") or url.startswith("@"):
            raise GuardError(f"{prog} destination {url!r} is not a literal; cannot be verified")
        host = _extract_hosts_from_url(url)
        if host is None:
            host = normalize_host(url.split("/", 1)[0])
        if host == "":
            continue
        if ctx.allowlist.is_lab_host(host):
            continue
        if uploads:
            raise GuardError(f"{prog} would send data to {host!r}, which is not an allowlisted "
                             f"lab host (upload flag, mutating method, or piped stdin present)")
        if len(url) > MAX_URL_LENGTH:
            raise GuardError(f"{prog} URL to {host!r} is {len(url)} characters long; a URL that "
                             f"long is a data channel, not a fetch")
    return f"{prog}:" + ",".join(urls)


_SSH_VALUE_FLAGS = frozenset({"-p", "-l", "-i", "-o", "-F", "-J", "-L", "-R", "-D", "-W",
                              "-b", "-c", "-e", "-m", "-O", "-Q", "-S", "-E", "-B", "-I", "-P"})


def _ssh_positionals(argv, value_flags):
    """Positional arguments and the option values that matter for routing."""
    positionals, options = [], []
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok in value_flags and i + 1 < len(argv):
            options.append((tok, argv[i + 1]))
            i += 2
            continue
        if tok.startswith("-") and len(tok) > 2 and tok[:2] in value_flags:
            options.append((tok[:2], tok[2:]))
        elif not tok.startswith("-"):
            positionals.append(tok)
        i += 1
    return positionals, options


def _require_lab(host, prog, ctx):
    if not host or host.startswith("$"):
        raise GuardError(f"{prog} destination could not be determined ({host!r})")
    if not ctx.allowlist.is_lab_host(host):
        raise GuardError(f"{prog} destination {normalize_host(host)!r} is not an allowlisted "
                         f"lab host (see .claude/hooks/exfil-allowlist.txt)")


def _check_ssh_family(prog, argv, ctx):
    positionals, options = _ssh_positionals(argv, _SSH_VALUE_FLAGS)
    for flag, value in options:
        if flag == "-o" and re.match(r"(?i)^\s*(ProxyCommand|ProxyJump|LocalCommand|PermitLocalCommand)",
                                    value):
            raise GuardError(f"{prog} -o {value!r} routes the session through another command; denied")
        if flag == "-J":
            for hop in value.split(","):
                _require_lab(hop, f"{prog} jump host", ctx)
        if flag == "-S" and prog == "scp":
            raise GuardError("scp -S substitutes the transport program; denied")
        if flag in ("-e", "--rsh") and prog == "rsync" and re.search(r"-J|ProxyCommand|ProxyJump", value):
            raise GuardError("rsync -e with a jump/proxy option; denied")
    hosts = []
    if prog == "ssh":
        if not positionals:
            raise GuardError("ssh has no host argument")
        hosts.append(positionals[0])
    else:
        for tok in positionals:
            if "://" in tok:
                hosts.append(_extract_hosts_from_url(tok) or "")
            elif _REMOTE_SPEC.match(tok) and not re.match(r"^[A-Za-z]:[\\/]", tok):
                hosts.append(_REMOTE_SPEC.match(tok).group(1))
            elif prog == "sftp":
                hosts.append(tok)
        if prog == "sftp" and not hosts:
            raise GuardError("sftp has no host argument")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(normalize_host(h) for h in hosts)


def _check_raw_socket_tool(prog, argv, ctx):
    args = argv[1:]
    if prog in ("nc", "ncat", "netcat") and any(a.startswith("-") and "l" in a[1:] for a in args):
        raise GuardError(f"{prog} in listen mode exposes this machine to inbound connections; denied")
    if prog == "openssl":
        if "s_client" not in args:
            return "openssl:local"
        if "-connect" in args:
            _require_lab(args[args.index("-connect") + 1], "openssl s_client", ctx)
            return "openssl:" + args[args.index("-connect") + 1]
        raise GuardError("openssl s_client without -connect; destination undetermined")
    hosts = []
    if prog == "socat":
        for a in args:
            if re.match(r"(?i)^(tcp|udp|sctp|openssl|ssl|socks4|socks4a|socks5|proxy)[46]?-listen:", a):
                raise GuardError("socat listen address exposes this machine; denied")
            m = re.match(r"(?i)^(?:tcp|udp|sctp|openssl|ssl)[46]?:([^:,]+)", a)
            if m:
                hosts.append(m.group(1))
            elif re.match(r"(?i)^(exec|system|shell):", a):
                raise GuardError("socat exec/system address runs an arbitrary command; denied")
            elif re.match(r"(?i)^(socks|proxy)", a):
                raise GuardError("socat via a proxy; destination undetermined")
    else:
        for a in args:
            if a.startswith("-"):
                continue
            if "://" in a:
                hosts.append(_extract_hosts_from_url(a) or "")
            elif _HOSTISH.match(a) and not a.isdigit():
                hosts.append(a)
                if prog != "lftp":
                    break
    if not hosts:
        raise GuardError(f"{prog} destination could not be determined")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(normalize_host(h) for h in hosts)


def _check_probe_tool(prog, argv, ctx):
    hosts = [a.lstrip("@") for a in argv[1:] if not a.startswith("-") and not a.isdigit()]
    if not hosts:
        raise GuardError(f"{prog} has no host argument")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(hosts)


def _scan_code(code, label):
    for pattern in NETWORK_CODE_PATTERNS:
        if pattern.search(code):
            raise GuardError(f"{label} contains network-capable code ({pattern.pattern}); "
                             f"inline programs that can open a connection are denied")


def _scan_shell_script(text, label):
    """Block when a shell script file names a network tool outside comments."""
    code = "\n".join(line for line in text.split("\n") if not line.lstrip().startswith("#"))
    for pattern in SHELL_SCRIPT_PATTERNS:
        match = pattern.search(code)
        if match:
            raise GuardError(f"{label} invokes {match.group(0).strip()!r}; a script that can reach "
                             f"the network does not run through the guard")


def _follow_cd(argv, cwd):
    """The working directory after ``cd``/``pushd``, or None if it cannot be known."""
    args = [a for a in argv[1:] if not a.startswith("-")]
    if not args:
        return os.path.expanduser("~")
    target = args[0]
    if target == "-" or "$" in target or "`" in target:
        return None
    target = os.path.expanduser(target)
    if os.path.isabs(target):
        return os.path.normpath(target)
    if cwd is None:
        return None
    return os.path.normpath(os.path.join(cwd, target))


def _read_script(path, ctx):
    base = ctx.require_cwd(f"cannot locate {path!r}") if not os.path.isabs(path) else None
    candidate = path if os.path.isabs(path) else os.path.join(base, path)
    try:
        size = os.path.getsize(candidate)
        if size > MAX_SCRIPT_BYTES:
            raise GuardError(f"{path!r} is too large to scan ({size} bytes)")
        with open(candidate, "rb") as handle:
            data = handle.read()
    except OSError as exc:
        raise GuardError(f"cannot read {path!r} to scan it ({exc.__class__.__name__}); "
                         f"a program the guard cannot read does not run")
    return data.decode("utf-8", "replace")


def _check_interpreter(prog, argv, piped, bodies, ctx, depth):
    args = argv[1:]
    code_seen = False
    i = 0
    while i < len(args):
        tok = args[i]
        if tok in _INTERPRETER_CODE_FLAGS or (prog in ("perl",) and tok.startswith("-M")):
            code = tok[2:] if tok.startswith("-M") else (args[i + 1] if i + 1 < len(args) else "")
            _scan_code(code, f"{prog} inline program")
            code_seen = True
            i += 2 if not tok.startswith("-M") else 1
            continue
        if tok == _INTERPRETER_MODULE_FLAG and i + 1 < len(args):
            if args[i + 1] in NETWORK_MODULES:
                raise GuardError(f"{prog} -m {args[i + 1]} is a network module; denied")
            return f"{prog}:-m {args[i + 1]}"
        if prog == "php" and tok == "-S":
            raise GuardError("php -S serves files over the network; denied")
        if tok == "-":
            code_seen = True
            _check_stdin_program(prog, piped, bodies, ctx, depth)
            i += 1
            continue
        if tok.startswith("-"):
            i += 1
            continue
        if prog in ("awk", "gawk", "mawk", "nawk"):
            if not code_seen:
                _scan_code(tok, "awk program")
                code_seen = True
            return f"{prog}:inline"
        script = _read_script(tok, ctx)
        _scan_code(script, f"{prog} script {tok!r}")
        return f"{prog}:{tok}"
    if not code_seen:
        _check_stdin_program(prog, piped, bodies, ctx, depth)
    return f"{prog}:inline"


def _check_stdin_program(prog, piped, bodies, ctx, depth):
    if piped:
        raise GuardError(f"{prog} reads its program from a pipe; a program the guard cannot see "
                         f"does not run")
    for body in bodies:
        if prog in SHELLS:
            reason = analyze_command(body, ctx, depth + 1)
            if reason:
                raise GuardError(f"heredoc fed to {prog}: {reason}")
        else:
            _scan_code(body, f"{prog} heredoc program")


def _shell_is_noexec(args):
    """True when the shell was told to parse its input without running it.

    ``bash -n script.sh`` is a syntax check: the shell reads the file,
    parses it, and exits. Nothing in it executes, so nothing in it can
    send anything anywhere — including a file the guard cannot read,
    which otherwise blocks on the reasoning that a program it cannot
    inspect must not run. Under ``-n`` no program runs at all.

    Only the option cluster is examined, since a shell stops treating
    words as options at ``--`` or at the first operand. ``-c`` alongside
    ``-n`` is still inert: the command string is parsed, not run.
    """
    index = 0
    while index < len(args):
        tok = args[index]
        index += 1
        if tok == "--" or not tok.startswith(("-", "+")) or tok in ("-", "+"):
            return False
        # `-o noexec` is the long spelling of -n; `+o noexec` turns it
        # off again, and a `+` cluster never turns an option on, so
        # neither of those may be read as a syntax check.
        if tok in ("-o", "+o"):
            value = args[index] if index < len(args) else ""
            index += 1
            if tok == "-o" and value == "noexec":
                return True
            continue
        if tok.startswith("--") or tok.startswith("+"):
            continue
        if "n" in tok[1:]:
            return True
    return False


def _check_shell(prog, argv, piped, bodies, ctx, depth):
    args = argv[1:]
    if prog not in ("eval", "source", ".") and _shell_is_noexec(args):
        return f"{prog}:-n"
    if prog == "eval":
        nested = " ".join(args)
        reason = analyze_command(nested, ctx, depth + 1)
        if reason:
            raise GuardError(f"eval: {reason}")
        return "eval:inline"
    if prog in ("source", "."):
        if not args:
            raise GuardError("source without a file")
        _scan_shell_script(_read_script(args[0], ctx), f"sourced script {args[0]!r}")
        return f"source:{args[0]}"
    for i, tok in enumerate(args):
        if tok in ("-c", "--command"):
            nested = args[i + 1] if i + 1 < len(args) else ""
            reason = analyze_command(nested, ctx, depth + 1)
            if reason:
                raise GuardError(f"{prog} -c: {reason}")
            return f"{prog}:-c"
        if tok.startswith("-") and len(tok) > 1 and "c" in tok[1:] and not tok.startswith("--"):
            nested = args[i + 1] if i + 1 < len(args) else ""
            reason = analyze_command(nested, ctx, depth + 1)
            if reason:
                raise GuardError(f"{prog} -c: {reason}")
            return f"{prog}:-c"
    positionals = [a for a in args if not a.startswith("-")]
    if positionals:
        _scan_shell_script(_read_script(positionals[0], ctx), f"{prog} script {positionals[0]!r}")
        return f"{prog}:{positionals[0]}"
    _check_stdin_program(prog, piped, bodies, ctx, depth)
    return f"{prog}:stdin"


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
            raise GuardError(f"git {opt} points at another repository; only this project's "
                             f"origin may be pushed to")
        if opt == "-c" and _GIT_CONFIG_SENSITIVE.match(value):
            raise GuardError(f"git -c {value!r} rewrites remote/transport configuration; denied")
    if sub in GIT_BLOCKED_SUBCOMMANDS:
        raise GuardError(f"git {sub} sends data or credentials somewhere else; denied")
    if sub == "remote" and args and args[0] in _GIT_REMOTE_MUTATORS:
        raise GuardError(f"git remote {args[0]} changes where pushes go; a human edits remotes")
    if sub == "config":
        keys = [a for a in args if not a.startswith("-")]
        if any(_GIT_CONFIG_SENSITIVE.match(k) for k in keys) and not any(
                a in ("--get", "--get-all", "--get-regexp", "-l", "--list") for a in args):
            raise GuardError("git config write to remote/url/http/credential settings; denied")
        return "git:config"
    if sub != "push":
        return f"git:{sub or 'none'}"
    for a in args:
        if a.split("=", 1)[0] in _GIT_PUSH_BLOCKED_FLAGS:
            raise GuardError(f"git push {a} overrides the remote; denied")
    ctx.require_cwd("git push")
    positionals = [a for a in args if not a.startswith("-")]
    remote = positionals[0] if positionals else None
    if remote is None:
        rc, branch = ctx.git.run(["branch", "--show-current"], cwd=ctx.command_cwd)
        branch = branch.strip()
        if rc != 0 or not branch:
            raise GuardError("git push with no remote on a detached HEAD; denied")
        rc, configured = ctx.git.run(["config", "--get", f"branch.{branch}.remote"],
                                     cwd=ctx.command_cwd)
        if rc != 0 or not configured.strip():
            raise GuardError(f"git push with no remote and no configured remote for "
                             f"{branch!r}; name the remote explicitly (origin)")
        remote = configured.strip()
    if remote != "origin":
        raise GuardError(f"git push to {remote!r}; only `origin` may be pushed to")
    rc, url = ctx.git.run(["remote", "get-url", "origin"], cwd=ctx.command_cwd)
    if rc != 0:
        raise GuardError("origin has no URL in the directory this command runs in; denied")
    target = owner_repo(url)
    project = ctx.project_origin()
    if target is None or project is None:
        raise GuardError(f"origin URL {url.strip()!r} could not be compared with the project's origin")
    if target != project:
        raise GuardError(f"origin here is {target[0]}:{target[1]}, not the project's "
                         f"{project[0]}:{project[1]}; pushing another repository is denied")
    if not ctx.allowlist.is_git_remote(target[0], target[1]):
        raise GuardError(f"origin {target[0]}/{target[1]} is not an allowlisted git remote")
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
        raise GuardError(f"gh --hostname {hostname!r} is not an allowlisted git remote")
    rest = args[i:]
    if not rest:
        return "gh:none"
    sub = rest[0]
    if sub not in GH_ALLOWED_SUBCOMMANDS:
        raise GuardError(f"gh {sub} is not a sanctioned operation (only pr/issue/run/workflow "
                         f"reads and writes with inline bodies are allowed)")
    if sub in GH_ALLOWED_SECOND:
        second = next((a for a in rest[1:] if not a.startswith("-")), "")
        if second not in GH_ALLOWED_SECOND[sub]:
            raise GuardError(f"gh {sub} {second} is not a sanctioned operation")
    if sub in ("pr", "issue"):
        for a in rest[1:]:
            if a.split("=", 1)[0] in GH_BODY_FILE_FLAGS:
                raise GuardError(f"gh {sub} with {a}: a body read from a file cannot be reviewed "
                                 f"in the transcript; pass --body with the text inline")
    if sub == "api":
        for k, a in enumerate(rest[1:], 1):
            base = a.split("=", 1)[0]
            if base in GH_API_BODY_FLAGS:
                raise GuardError(f"gh api with {base}: request bodies are denied")
            if base in ("-X", "--method"):
                value = a.split("=", 1)[1] if "=" in a else (rest[k + 1] if k + 1 < len(rest) else "")
                if value.upper() not in ("GET", "HEAD"):
                    raise GuardError(f"gh api with method {value}: mutating requests are denied")
    return f"gh:{sub}"


def _check_program_file(prog_token, ctx, depth):
    """Scripts invoked by path (or found on PATH outside a system prefix) are scanned."""
    if "/" in prog_token:
        if os.path.isabs(prog_token):
            path = prog_token
        else:
            path = os.path.join(ctx.require_cwd(f"cannot locate program {prog_token!r}"), prog_token)
    else:
        path = shutil.which(prog_token)
        if path is None:
            return None
    real = os.path.realpath(path)
    if real.startswith(SYSTEM_PREFIXES):
        return None
    try:
        with open(real, "rb") as handle:
            head = handle.read(MAX_SCRIPT_BYTES + 1)
    except OSError as exc:
        raise GuardError(f"cannot read program {prog_token!r} ({exc.__class__.__name__}); "
                         f"a program the guard cannot inspect does not run")
    if b"\x00" in head[:8192]:
        # A compiled program cannot be inspected. One that lives where an
        # agent builds things (the work tree, a temp directory) is denied;
        # a user-installed toolchain elsewhere (~/.sdkman, a venv) is the
        # documented gap, not something the guard pretends to cover.
        untrusted = (ctx.project_toplevel + os.sep, "/tmp/", "/private/tmp/", "/var/tmp/",
                     "/private/var/tmp/", "/dev/shm/")
        if real.startswith(untrusted):
            raise GuardError(f"program {prog_token!r} is a compiled binary in the work tree or a "
                             f"temp directory; the guard cannot inspect what it does")
        return None
    if len(head) > MAX_SCRIPT_BYTES:
        raise GuardError(f"program {prog_token!r} is too large to scan")
    text = head.decode("utf-8", "replace")
    first = text.split("\n", 1)[0]
    if re.search(r"python|node|ruby|perl|php|lua|julia|groovy", first):
        _scan_code(text, f"script {prog_token!r}")
    else:
        _scan_shell_script(text, f"script {prog_token!r}")
    return f"script:{prog_token}"


def _check_simple_command(argv, piped, heredoc, bodies, ctx, depth, has_substitution):
    """Return an audit target string for one simple command, or raise GuardError."""
    raw_first = argv[0]
    if raw_first.startswith("$") or _SUBSTITUTION.search(raw_first):
        raise GuardError(f"command name {raw_first!r} is computed at run time; the guard cannot "
                         f"tell what it runs")
    argv = _unwrap(argv)
    if not argv:
        return ""
    if os.path.basename(argv[0]) == "find":
        argv = _unwrap(_find_exec(argv))
        if not argv:
            return "find"
    prog = os.path.basename(argv[0])
    if prog.startswith("$") or "$" in argv[0] or "`" in argv[0]:
        raise GuardError(f"command name {argv[0]!r} is computed at run time; denied")

    sensitive = (prog in UPLOAD_TOOLS or prog in SSH_FAMILY or prog in RAW_SOCKET_TOOLS
                 or prog in PROBE_TOOLS or prog in INTERPRETERS or prog in SHELLS
                 or prog in ("git", "gh") or prog in BLOCKED_SUBCOMMANDS)
    if sensitive and has_substitution:
        raise GuardError(f"{prog} appears in a command line that uses command substitution "
                         f"($(...), backticks or process substitution) — even inside a quoted "
                         f"remote command or argument; what it expands to cannot be verified, so "
                         f"write the value out literally")
    if prog in ALWAYS_BLOCKED:
        raise GuardError(f"{prog} exists to move data elsewhere (cloud CLI, mail, tunnel, file "
                         f"server, or launcher); denied outright")
    if prog in BLOCKED_SUBCOMMANDS and any(a in BLOCKED_SUBCOMMANDS[prog] for a in argv[1:]):
        raise GuardError(f"{prog} {' '.join(a for a in argv[1:] if a in BLOCKED_SUBCOMMANDS[prog])} "
                         f"publishes or uploads; denied")
    if prog in COPY_TOOLS and any(SYNCED_FOLDER_PATTERNS.search(a) or a.startswith("/Volumes/")
                                  for a in argv[1:]):
        raise GuardError(f"{prog} touches a cloud-synced folder or mounted volume; copying data "
                         f"there is an upload")
    if prog in UPLOAD_TOOLS:
        return _check_upload_tool(prog, argv, piped, ctx)
    if prog in SSH_FAMILY:
        return _check_ssh_family(prog, argv, ctx)
    if prog in RAW_SOCKET_TOOLS:
        return _check_raw_socket_tool(prog, argv, ctx)
    if prog in PROBE_TOOLS:
        return _check_probe_tool(prog, argv, ctx)
    if prog in SHELLS:
        return _check_shell(prog, argv, piped, bodies if heredoc or bodies else [], ctx, depth)
    if prog in INTERPRETERS:
        return _check_interpreter(prog, argv, piped, bodies if heredoc or bodies else [], ctx, depth)
    if prog == "git":
        return _check_git(argv, ctx)
    if prog == "gh":
        return _check_gh(argv, ctx)
    scanned = _check_program_file(argv[0], ctx, depth)
    return scanned or ""


def analyze_command(command, ctx, depth=0):
    """Return a block reason for ``command``, or '' when it may run."""
    if not command or not command.strip():
        return ""
    if depth > MAX_NESTING_DEPTH:
        return "command nesting deeper than the guard follows; denied"
    for m in _DEV_TCP.finditer(command):
        if not ctx.allowlist.is_lab_host(m.group(1)):
            return f"/dev/tcp redirection to {m.group(1)!r}, which is not an allowlisted lab host"
    stripped, bodies = _strip_heredoc_bodies(command)
    # shlex treats a newline as whitespace, but in a shell it ends the
    # command. A backslash-newline continuation is joined first so a
    # command split across lines stays one command.
    stripped = re.sub(r"\\\n", " ", stripped).replace("\n", " ; ")
    tokens = _tokenize(stripped)
    if tokens is None:
        return "the command's quoting cannot be parsed; an unreadable command does not run"
    if _DEFINITION.search(stripped) and (_NETWORK_WORD.search(stripped) or any(
            os.path.basename(t) in ALWAYS_BLOCKED for t in tokens)):
        return "alias/function definition alongside a network tool; indirection is denied"
    commands = _simple_commands(tokens)
    has_substitution = bool(_SUBSTITUTION.search(stripped))
    # A `cd` earlier in the same command line changes where relative paths
    # resolve for everything after it, so the effective directory is
    # tracked through the list. A target that cannot be known statically
    # leaves it None, and anything that then needs it fails closed.
    cwd = ctx.command_cwd
    try:
        for argv, piped, heredoc in commands:
            if argv and os.path.basename(argv[0]) in ("cd", "pushd"):
                cwd = _follow_cd(argv, cwd)
                continue
            _check_simple_command(argv, piped, heredoc, bodies, ctx.at(cwd), depth,
                                  has_substitution)
    except GuardError as exc:
        return str(exc)
    return ""


def bash_targets(command, ctx):
    """Audit summary of the network-relevant programs in ``command``."""
    stripped, _ = _strip_heredoc_bodies(command)
    tokens = _tokenize(stripped) or []
    names = []
    for argv, _, _ in _simple_commands(tokens):
        unwrapped = _unwrap(argv)
        if unwrapped:
            names.append(os.path.basename(unwrapped[0]))
    return " ".join(names)[:200]


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
