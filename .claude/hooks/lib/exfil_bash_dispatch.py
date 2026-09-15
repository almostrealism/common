"""Top-level Bash command dispatch for the exfiltration guard.

This is where ``analyze_command`` lives: it tokenises the command,
splits it into the simple commands that compose it, and routes each one
to the check that knows the program (``curl`` → ``_check_upload_tool``,
``bash -c`` → ``_check_shell``, …). The dispatch table is the only place
that names every program class the guard recognises.

Program files found by path (rather than by name lookup) are scanned
here too, with the same network patterns the runner applies to inline
programs. A ``cp`` into a cloud-synced folder is also caught here:
``cp`` is a Bash primitive, not a network tool, but its target can be.
"""
import os
import re
import shutil
import sys

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from exfil_bash_lex import (_DEFINITION, _DEV_TCP, _NETWORK_WORD, _SUBSTITUTION,
                             _find_exec, _simple_commands, _strip_heredoc_bodies,
                             _tokenize, _unwrap, GuardError, owner_repo)
from exfil_bash_network import (_scan_code, _scan_shell_script,
                                 PROBE_TOOLS, RAW_SOCKET_TOOLS, SSH_FAMILY,
                                 UPLOAD_TOOLS, _check_probe_tool,
                                 _check_raw_socket_tool, _check_ssh_family,
                                 _check_upload_tool)
from exfil_bash_runner import (INTERPRETERS, MAX_SCRIPT_BYTES, SHELLS,
                                _check_interpreter, _check_shell,
                                _check_stdin_program, _follow_cd,
                                _shell_invocation)
from exfil_bash_vcs import (_check_gh, _check_git)


# Commands past this depth cannot be followed by ``analyze_command`` —
# the recursion bottoms out as a block. Three is enough to cover any
# legitimate use (a shell wrapper around a shell wrapper around an
# actual command); going deeper is the kind of indirection that exists
# to hide what a command does.
MAX_NESTING_DEPTH = 3

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

# Folders that sync to a cloud provider: a plain `cp` into one is an upload.
SYNCED_FOLDER_PATTERNS = re.compile(
    r"Mobile Documents|/Dropbox/|/Google Drive/|/OneDrive/|/CloudStorage/|"
    r"/Box Sync/|/Box/|/iCloud", re.IGNORECASE)
COPY_TOOLS = frozenset({"cp", "mv", "rsync", "tee", "dd", "tar", "zip", "ln",
                        "install", "ditto", "cat", "unzip", "gzip", "bzip2", "xz"})
SYSTEM_PREFIXES = ("/bin/", "/sbin/", "/usr/", "/opt/homebrew/", "/opt/local/",
                   "/Library/", "/System/", "/Applications/", "/nix/")


def _check_program_file(prog_token, ctx, depth):
    """Scripts invoked by path (or found on PATH outside a system prefix) are scanned."""
    if "/" in prog_token:
        if os.path.isabs(prog_token):
            path = prog_token
        else:
            path = os.path.join(ctx.require_cwd(f"cannot locate program {prog_token!r}"), prog_token)
    else:
        path = shutil_which(prog_token)
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


def shutil_which(prog_token):
    return shutil.which(prog_token)


def _check_simple_command(argv, piped, heredoc, bodies, ctx, depth, has_substitution,
                          analyze_command):
    """Return an audit target string for one simple command, or raise GuardError."""
    raw_first = argv[0]
    if raw_first.startswith("$") or _SUBSTITUTION.search(raw_first):
        raise _GuardError(f"command name {raw_first!r} is computed at run time; the guard cannot "
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
        raise _GuardError(f"command name {argv[0]!r} is computed at run time; denied")

    sensitive = (prog in UPLOAD_TOOLS or prog in SSH_FAMILY or prog in RAW_SOCKET_TOOLS
                 or prog in PROBE_TOOLS or prog in INTERPRETERS or prog in SHELLS
                 or prog in ("git", "gh") or prog in BLOCKED_SUBCOMMANDS)
    if sensitive and has_substitution:
        raise _GuardError(f"{prog} appears in a command line that uses command substitution "
                          f"($(...), backticks or process substitution) — even inside a quoted "
                          f"remote command or argument; what it expands to cannot be verified, so "
                          f"write the value out literally")
    if prog in ALWAYS_BLOCKED:
        raise _GuardError(f"{prog} exists to move data elsewhere (cloud CLI, mail, tunnel, file "
                          f"server, or launcher); denied outright")
    if prog in BLOCKED_SUBCOMMANDS and any(a in BLOCKED_SUBCOMMANDS[prog] for a in argv[1:]):
        raise _GuardError(f"{prog} {' '.join(a for a in argv[1:] if a in BLOCKED_SUBCOMMANDS[prog])} "
                          f"publishes or uploads; denied")
    if prog in COPY_TOOLS and any(SYNCED_FOLDER_PATTERNS.search(a) or a.startswith("/Volumes/")
                                  for a in argv[1:]):
        raise _GuardError(f"{prog} touches a cloud-synced folder or mounted volume; copying data "
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
        return _check_shell(prog, argv, piped, bodies if heredoc or bodies else [],
                            ctx, depth, analyze_command)
    if prog in INTERPRETERS:
        return _check_interpreter(prog, argv, piped, bodies if heredoc or bodies else [],
                                   ctx, depth, analyze_command)
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
                                  has_substitution, analyze_command)
    except _GuardError as exc:
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


# Backward-compatible alias: earlier callers raised ``_GuardError``
# because each module had its own stub. They now share ``GuardError``
# from ``exfil_bash_lex``; this alias keeps any code that named the
# local stub still working.
_GuardError = GuardError


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