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


def _split_heredocs(command):
    """The command with heredoc bodies removed, and each body with whether it expands.

    A body whose delimiter is unquoted (``<<EOF``) is subject to command
    substitution by the local shell before the program reading it ever
    sees it; a quoted delimiter (``<<'EOF'``, ``<<"EOF"``) makes the body
    literal text. The flag is what lets the caller tell the two apart.
    """
    if "<<" not in command:
        return command, []
    lines = command.split("\n")
    kept, bodies, i = [], [], 0
    quote = None
    while i < len(lines):
        line = lines[i]
        # Only a `<<` the shell itself reads starts a heredoc: one inside
        # quotes or a comment is text, and treating it as a marker would
        # hide the real commands after it as the "body".
        active, quote = _shell_active_positions(line, quote)
        match = next((m for m in _HEREDOC_START.finditer(line)
                      if m.start() in active and line[max(0, m.start() - 1):m.start()] != "<"),
                     None)
        if not match:
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
        bodies.append(("\n".join(body), not match.group("q")))
    return "\n".join(kept), bodies


def _shell_active_positions(line, quote):
    """Indices of ``line`` the shell reads as syntax, and the quote open at its end.

    ``quote`` is the quote open when the line starts (``None``, ``"'"``,
    ``'"'`` or ``"$'"``), so a string spanning lines is followed. Text inside
    quotes, after a backslash, or in a comment is not active. Where this is
    unsure it errs toward "not active", which only leaves a heredoc body to be
    analyzed as commands.
    """
    active = set()
    word_start = True
    i = 0
    while i < len(line):
        c = line[i]
        if quote == "'":
            quote = None if c == "'" else quote
            i += 1
            continue
        if quote is not None:
            if c == "\\":
                i += 2
                continue
            if c == ("'" if quote == "$'" else '"'):
                quote = None
            i += 1
            continue
        starts_word, word_start = word_start, False
        if c == "\\":
            i += 2
            continue
        if line.startswith("$'", i):
            quote = "$'"
            i += 2
            continue
        if c in ("'", '"'):
            quote = c
        elif c == "#" and starts_word:
            break
        else:
            active.add(i)
            word_start = c in _WORD_BREAKS
        i += 1
    return active, quote


def _strip_heredoc_bodies(command):
    stripped, bodies = _split_heredocs(command)
    return stripped, [body for body, _ in bodies]


# What an active command substitution is replaced with before tokenizing:
# a parameter expansion, so a command name made of one is still "computed at
# run time" and an argument made of one still reads as a value.
SUBSTITUTION_PLACEHOLDER = "$__guard_substitution__"

# Characters after which a ``#`` starts a comment rather than being part of
# a word.
_WORD_BREAKS = frozenset(" \t\n;&|()<>")

# shlex drops quotes before anyone sees the tokens, so `echo '('` and a real
# `(` arrive as the same token, and a quoted newline is joined with `;` like
# a real one. Inside quotes and after a backslash, operator characters and
# newlines are therefore swapped for private-use stand-ins while scanning,
# and swapped back only once _simple_commands has told separators from
# words: quoted text keeps its characters but can never be read as shell
# syntax.
_SHIELDED = "();<>|&\n"
_SHIELD = str.maketrans({c: chr(0xE000 + n) for n, c in enumerate(_SHIELDED)})
_UNSHIELD = str.maketrans({chr(0xE000 + n): c for n, c in enumerate(_SHIELDED)})


def split_substitutions(command):
    """What the local shell executes inside ``command``, separated from what it reads as text.

    Returns ``(masked, bodies)``: ``masked`` is the command with comments
    removed and every command substitution the shell will perform —
    ``$(…)``, backticks and ``<(…)``/``>(…)`` outside single quotes, the
    first two inside double quotes too — replaced by
    ``SUBSTITUTION_PLACEHOLDER``; ``bodies`` are those substitutions'
    commands, for the caller to analyze as commands in their own right.
    Text inside single quotes (and ``$'…'``) is left exactly as written:
    the local shell does not execute it, although a program that hands its
    arguments to another shell (``ssh``, ``bash -c``) may.

    Comments are removed here because the tokenizer never sees the newline
    that ends one — ``analyze_command`` joins lines with ``;`` first — so a
    comment left in place would swallow every command after it.

    Raises ``GuardError`` for quoting or a substitution that never closes.
    """
    bodies = []
    masked, _ = _scan_unquoted(command, 0, False, bodies)
    return masked, bodies


def heredoc_substitutions(body):
    """The commands an expanding (unquoted-delimiter) heredoc body substitutes.

    Quotes are literal in a heredoc body; only backslash escapes,
    ``$(…)`` and backticks are special.
    """
    bodies = []
    i = 0
    while i < len(body):
        if body[i] == "\\":
            i += 2
        elif body.startswith("$(", i) or body[i] == "`":
            i = _take_substitution(body, i, bodies)
        else:
            i += 1
    return bodies


def _scan_unquoted(text, i, nested, bodies):
    """Scan unquoted shell text from ``i``; stop at the ``)`` closing a
    substitution when ``nested``. Returns ``(masked, end)``."""
    out = []
    depth = 0
    # A `#` starts a comment only at the start of a word: after an unescaped
    # blank or operator character, never after an escaped one (`a\ #` is one
    # word, and whatever follows the `#` still runs).
    word_start = True
    while i < len(text):
        c = text[i]
        if nested and c == ")" and depth == 0:
            return "".join(out), i
        starts_word, word_start = word_start, False
        if text.startswith("\\\n", i):
            # A line continuation is removed outright, as the shell removes
            # it: the word it interrupts carries on (`safe\<newline>#` is the
            # one word `safe#`, not a word and a comment).
            word_start = starts_word
            i += 2
        elif c == "\\":
            out.append(text[i:i + 2].translate(_SHIELD))
            i += 2
        elif c == "'":
            end = text.find("'", i + 1)
            if end < 0:
                raise GuardError("the command's quoting cannot be parsed (a single quote is never closed); an unreadable command does not run")
            out.append(text[i:end + 1].translate(_SHIELD))
            i = end + 1
        elif text.startswith("$'", i):
            end = _ansi_c_end(text, i + 2)
            out.append(text[i:end].translate(_SHIELD))
            i = end
        elif c == '"':
            piece, end = _scan_double_quoted(text, i + 1, bodies)
            out.append('"' + piece + '"')
            i = end + 1
        elif c == "#" and starts_word:
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text.startswith(("$(", "<(", ">("), i) or c == "`":
            i = _take_substitution(text, i, bodies)
            out.append(SUBSTITUTION_PLACEHOLDER)
        else:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            out.append(c)
            word_start = c in _WORD_BREAKS
            i += 1
    if nested:
        raise GuardError("the command's quoting cannot be parsed (a command substitution is never closed); an unreadable command does not run")
    return "".join(out), i


def _scan_double_quoted(text, i, bodies):
    """Scan double-quoted text from ``i``. Returns ``(masked, index of the closing quote)``."""
    out = []
    while i < len(text):
        c = text[i]
        if c == '"':
            return "".join(out), i
        if text.startswith("\\\n", i):
            i += 2
        elif c == "\\":
            out.append(text[i:i + 2].translate(_SHIELD))
            i += 2
        elif text.startswith("$(", i) or c == "`":
            i = _take_substitution(text, i, bodies)
            out.append(SUBSTITUTION_PLACEHOLDER)
        else:
            out.append(c.translate(_SHIELD))
            i += 1
    raise GuardError("the command's quoting cannot be parsed (a double quote is never closed); an unreadable command does not run")


def _ansi_c_end(text, i):
    """The index just past the ``'`` closing a ``$'…'`` string whose body starts at ``i``."""
    while i < len(text):
        if text[i] == "\\":
            i += 2
        elif text[i] == "'":
            return i + 1
        else:
            i += 1
    raise GuardError("the command's quoting cannot be parsed (a $'...' string is never closed); an unreadable command does not run")


def _take_substitution(text, i, bodies):
    """Record the substitution starting at ``i``; return the index just past it."""
    if text[i] == "`":
        body = []
        j = i + 1
        while j < len(text) and text[j] != "`":
            if text[j] == "\\" and j + 1 < len(text) and text[j + 1] in "`$\\":
                body.append(text[j + 1])
                j += 2
            else:
                body.append(text[j])
                j += 1
        if j >= len(text):
            raise GuardError("the command's quoting cannot be parsed (a backtick substitution is never closed); an unreadable command does not run")
        bodies.append("".join(body))
        return j + 1
    # The scan only finds where this substitution ends. What it records is the
    # substitution's raw text, nested substitutions included, not the masked
    # scan output: analyze_command re-scans that text and so analyzes every
    # nested substitution in turn, which is why the scan's own list is dropped.
    _, end = _scan_unquoted(text, i + 2, True, [])
    bodies.append(text[i + 2:end])
    return end + 1


def _tokenize(command):
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        # Comments are removed by split_substitutions, which knows where a
        # line ends. Left to shlex, a ``#`` would consume the rest of the
        # command, newline-joined commands included.
        lexer.commenters = ""
        tokens = list(lexer)
    except ValueError:
        return None
    return [part for tok in tokens for part in _split_operators(tok)]


# shlex's punctuation_chars joins any run of these characters into a single
# token, so `(true); curl …` yields `);`. Left joined, it matches no
# separator and every word after it is read as an argument of the command
# before it — a command hidden in plain sight.
_OPERATOR_CHARS = frozenset("();<>|&")
_OPERATORS = sorted({";;", "&&", "||", "|&", ">>", "<<", "<<<", "&>", "&>>", ">&", "<&",
                     "<>", ">|", ";", "|", "&", "(", ")", "<", ">"}, key=len, reverse=True)


def _split_operators(tok):
    """A token of operator characters, split into the operators it is made of."""
    if len(tok) < 2 or not set(tok) <= _OPERATOR_CHARS:
        return [tok]
    parts = []
    i = 0
    while i < len(tok):
        # Every operator character is itself an operator, so a match always exists.
        op = next(o for o in _OPERATORS if tok.startswith(o, i))
        parts.append(op)
        i += len(op)
    return parts


def _simple_commands(tokens):
    """Split a token stream into (argv, piped_in, has_heredoc, subshells) simple commands.

    ``subshells`` identifies the ``( … )`` groups a command sits inside, outermost
    first, each by a number unique within the command line: a ``cd`` in a
    subshell ends with it, and two sibling groups at the same depth are still
    two different subshells, which the caller needs in order to know where
    each command runs.
    """
    commands, current, piped, heredoc = [], [], False, False
    at_start = True
    skip_words = False
    subshells = []
    opened = 0
    i = 0

    def flush(next_piped):
        nonlocal current, piped, heredoc, at_start, skip_words
        if current:
            commands.append((current, piped, heredoc, tuple(subshells)))
        current, piped, heredoc, at_start, skip_words = [], next_piped, False, True, False

    while i < len(tokens):
        tok = tokens[i]
        if tok in _SEPARATOR_TOKENS:
            flush(tok in _PIPE_TOKENS)
            if tok == "(":
                opened += 1
                subshells.append(opened)
            elif tok == ")" and subshells:
                subshells.pop()
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
        current.append(tok.translate(_UNSHIELD))
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
