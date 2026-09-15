"""Shell command tokenization and wrapper unwrapping for the exfiltration guard.

The guard breaks a Bash command into the simple commands that compose it,
then drops the wrappers (env, sudo, xargs, …) so the real program and its
arguments are visible. None of this module makes a decision — the callers
above it (``analyze_command`` in the dispatch module, the per-program
checks) decide whether what they found is allowed.

Tokenization treats the command as a POSIX shell would: shlex with
``posix=True`` and ``punctuation_chars=True``. Heredoc bodies are stripped
out first so their contents do not become tokens that look like commands;
``\n`` is rewritten to ``;`` because shlex reads it as whitespace, but a
shell runs it as a separator.
"""
import os
import re
import shlex
import sys

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)


SHELLS = frozenset({"bash", "sh", "zsh", "ksh", "dash", "fish", "eval", "source", "."})


class GuardError(Exception):
    """Raised for any condition the guard must treat as a block.

    Every other guard module imports this from here so that one
    ``except GuardError`` in the dispatcher catches errors raised from
    anywhere in the call tree. The class is also re-exported as
    ``GuardError`` by the entry file for backward compatibility with
    callers that imported the name from the original monolithic module.
    """


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


def match_flag(tok, flags, allow_attached=True):
    """The ``(flag, value)`` from ``flags`` that ``tok`` spells, or ``(None, None)``.

    Shared by every program-specific check in this guard that reads a
    POSIX-ish option grammar: a flag's value can arrive three ways — as
    the next token (``-r x``, so ``value`` is ``None`` and the caller
    reads the next token itself), attached to a single-letter short flag
    with no separator (``-rx``), or joined to a long flag with ``=``
    (``--require=x``). Matching only the bare token misses the second and
    third forms entirely: a token like ``-r./evil.js`` or
    ``--require=./evil.js`` never equals ``-r`` or ``--require``, so it
    falls through to whatever a caller does with an unrecognised token —
    typically skip it — and the value inside it is never inspected. That
    gap has been real, not theoretical: it let a Ruby ``-C`` (working
    directory), a Node preload flag, and a ``gh`` short flag for reading a
    request body from a file all bypass the checks built specifically to
    catch what they carry, simply by dropping the space.

    ``allow_attached`` lets a caller with an option table it does not
    fully trust — one shared across several programs whose short-flag
    grammar it has not individually verified — fall back to exact-token
    matching only, so an unrelated option is not misread as an attached
    form of something else.
    """
    if tok in flags:
        return tok, None
    if not allow_attached:
        return None, None
    for flag in flags:
        if flag.startswith("--"):
            if tok.startswith(flag + "="):
                return flag, tok[len(flag) + 1:]
        elif len(flag) == 2 and tok.startswith(flag) and len(tok) > 2:
            return flag, tok[2:]
    return None, None


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

_SEPARATOR_TOKENS = frozenset({";", ";;", "&&", "||", "|", "|&", "&", "\n", "(", ")"})
_PIPE_TOKENS = frozenset({"|", "|&"})
_KEYWORD_TOKENS = frozenset({
    "if", "then", "else", "elif", "fi", "while", "until", "do", "done", "{", "}",
    "!", "time", "esac",
})
_REDIRECT_TOKENS = frozenset({">", ">>", "<", ">&", "<&", "<>", ">|", "&>", "&>>", "<<<"})
_HEREDOC_START = re.compile(r"<<-?\s*(?P<q>['\"]?)(?P<word>[A-Za-z_][A-Za-z0-9_]*)(?P=q)")
_DEV_TCP = re.compile(r"/dev/(?:tcp|udp)/([^/\s'\"]+)/")
_SUBSTITUTION = re.compile(r"\$\(|`|<\(|>\(")
_NETWORK_WORD = re.compile(
    r"(?<![\w./-])(?:curl|wget|scp|sftp|rsync|ssh|nc|ncat|netcat|socat|telnet|ftp|lftp|tftp)"
    r"(?![\w.-])")
_DEFINITION = re.compile(r"(^|[\s;&|])(?:alias|function)\s|^[A-Za-z_][A-Za-z0-9_]*\s*\(\)")


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
