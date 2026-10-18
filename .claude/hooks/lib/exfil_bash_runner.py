"""Shell and interpreter execution checks for the exfiltration guard.

Three things live here:

1. The shells (bash/sh/zsh/…) and interpreters (python/node/ruby/…) that
   the guard follows: their option grammar (``_check_shell``,
   ``_check_interpreter``) and the network-capable modules that
   ``-m MODULE`` refuses.
2. The shell option grammar itself (``_shell_invocation``), which is
   what decides whether a ``bash -n`` is a syntax check (and so has
   nothing to police) or a real run.
3. The script files the guard reads when a program name turns out to be
   a file path (``_read_script``, ``_follow_cd``, ``_scan_preload``).

A program that runs as ``python3`` cannot be evaluated symbolically;
the guard does not try. It refuses what it cannot see (a piped program,
a file it cannot read) and scans what it can for network-capable
patterns. ``analyze_command`` is passed in by the caller because this
module is the leaf of the bash dispatch tree — re-importing the
dispatcher to reach it would form a cycle.
"""
import base64
import binascii
import os
import re
import sys
from urllib.parse import unquote, urlparse

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from exfil_bash_lex import SHELLS, GuardError, is_glob_or_brace, is_shell_expanded, match_flag
from exfil_bash_network import _scan_code, _scan_shell_script


# Script files handed to an interpreter or shell are read and scanned;
# anything larger than this is blocked rather than skipped.
MAX_SCRIPT_BYTES = 2 * 1024 * 1024

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

# The flags whose value is a PROGRAM, per family. The shared set above is
# the union over every interpreter, so for any one family most of its
# members are not inline-code flags at all: Python's -E and Perl's/Ruby's
# -p and -c take no argument, and matching them as code flags consumed the
# script that followed as their "code" (`python3 -E script.py` never read
# the script). A family listed here is matched against its own table only;
# an interpreter with no table keeps the shared set. These are matched
# with attached forms (`-e'code'`, `--eval=code`).
_INTERPRETER_INLINE_FLAGS = {
    "python": frozenset({"-c"}),
    "perl": frozenset({"-e", "-E"}),
    "ruby": frozenset({"-e"}),
    "node": frozenset({"--eval", "--print"}),
}

# Inline-code flags matched as whole tokens only. Node rejects an attached
# short value (`node -eCODE` is "bad option"), and `-pe` is its own alias
# for `--print --eval` whose program is the NEXT token, not `-p` with the
# attached code `e`.
_INTERPRETER_INLINE_EXACT_FLAGS = {
    "node": frozenset({"-e", "-p", "-pe"}),
}

# The Python short options that take a value. In a cluster (`-Ec'code'`,
# `-Emhttp.server`) the first of these consumes the rest of the token, or
# the next word when nothing follows it; every other letter is a switch.
_PYTHON_VALUE_LETTERS = "cmWX"

# Options that take their value from the NEXT token, so that token is not
# the program. The interpreter loop below otherwise reads the first word
# that does not start with a dash as the script to run, and
# `python3 -W error -m unittest …` was blocked because there is no file
# called `error`. A joined form (`-Werror`) needs nothing here — it is
# one dash-led token and is skipped as such. Keyed by interpreter family
# (see _interpreter_family); options that merely configure the run.
_INTERPRETER_VALUE_FLAGS = {
    "python": frozenset({"-W", "-X", "--check-hash-based-pycs"}),
    "node": frozenset({"--stack-size", "--title", "--input-type", "--conditions", "-C",
                       "--env-file", "--icu-data-dir", "--openssl-config"}),
    # Ruby's -C changes the working directory rather than merely configuring
    # the run, so it is excluded here and handled in _check_interpreter,
    # where it can update ctx for the flags that follow it.
    "ruby": frozenset({"-I", "-E", "-F"}),
    "perl": frozenset({"-I"}),
}

# Options whose next token names CODE the interpreter loads before the
# program: a preload is a program by another route. A value that is a
# readable file is scanned like a script; one that is not (a package
# name such as `ts-node/register`, resolved from a module path the guard
# does not model) passes, as a `require` inside a scanned script would.
_INTERPRETER_PRELOAD_FLAGS = {
    "node": frozenset({"-r", "--require", "--import", "--loader", "--experimental-loader"}),
    "ruby": frozenset({"-r"}),
}
_INTERPRETER_FAMILIES = {
    "python": ("python", "python2", "python3", "pypy", "pypy3"),
    "node": ("node", "nodejs"),
    "ruby": ("ruby",),
    "perl": ("perl",),
}
NETWORK_MODULES = frozenset({
    "http.server", "SimpleHTTPServer", "smtplib", "ftplib", "telnetlib",
    "webbrowser", "twine", "pyftpdlib", "uploadserver", "wsgiref.simple_server",
    "urllib.request", "http.client", "socket", "requests", "httpx",
})


def _read_script(path, ctx):
    base = ctx.require_cwd(f"cannot locate {path!r}") if not os.path.isabs(path) else None
    candidate = path if os.path.isabs(path) else os.path.join(base, path)
    try:
        size = os.path.getsize(candidate)
        if size > MAX_SCRIPT_BYTES:
            raise _GuardError(f"{path!r} is too large to scan ({size} bytes)")
        with open(candidate, "rb") as handle:
            data = handle.read()
    except OSError as exc:
        raise _GuardError(f"cannot read {path!r} to scan it ({exc.__class__.__name__}); "
                          f"a program the guard cannot read does not run")
    return data.decode("utf-8", "replace")


def _resolve_dir(target, cwd):
    """A directory argument resolved against ``cwd``, or None if it cannot be known.

    Shared by ``cd``/``pushd`` (``_follow_cd``) and any interpreter flag that
    changes the working directory before it runs, such as Ruby's ``-C``.
    """
    if _shell_can_expand(target):
        return None
    target = os.path.expanduser(target)
    if os.path.isabs(target):
        return os.path.normpath(target)
    if cwd is None:
        return None
    return os.path.normpath(os.path.join(cwd, target))


def _follow_cd(argv, cwd):
    """The working directory after ``cd``/``pushd``, or None if it cannot be known."""
    args = [a for a in argv[1:] if not a.startswith("-")]
    if not args:
        return os.path.expanduser("~")
    target = args[0]
    if target == "-":
        return None
    return _resolve_dir(target, cwd)


def _interpreter_family(prog):
    """The option table an interpreter reads from, or None for one with no table."""
    for family, names in _INTERPRETER_FAMILIES.items():
        if prog in names:
            return family
    return None


def _match_flag(tok, flags, allow_attached=True):
    """Wrap the shared ``match_flag`` so callers in this module can keep
    the leading underscore that signals "module-private"."""
    return match_flag(tok, flags, allow_attached)


def _match_inline_flag(tok, family):
    """The ``(flag, value)`` of an inline-code flag ``tok`` spells for ``family``.

    A family with an option table is matched against its own inline-code
    flags (``_INTERPRETER_INLINE_FLAGS`` and, as whole tokens,
    ``_INTERPRETER_INLINE_EXACT_FLAGS``). An interpreter with no table is
    matched against the shared ``_INTERPRETER_CODE_FLAGS`` set, exact
    tokens only, because its short-flag grammar is not known.
    """
    if family is None:
        return _match_flag(tok, _INTERPRETER_CODE_FLAGS, allow_attached=False)
    flag, value = _match_flag(tok, _INTERPRETER_INLINE_EXACT_FLAGS.get(family, frozenset()),
                              allow_attached=False)
    if flag:
        return flag, value
    return _match_flag(tok, _INTERPRETER_INLINE_FLAGS[family])


def _shell_can_expand(token):
    """Whether the shell may turn ``token`` into other words at run time."""
    return is_shell_expanded(token) or is_glob_or_brace(token)


def _refuse_if_expanded(prog, role, token):
    """Refuse ``token`` when the shell can expand it into words the guard cannot see.

    ``role`` names what the token is to the interpreter (a script, an
    argument after an inline program, an option's operand) for the message.
    """
    if token is not None and _shell_can_expand(token):
        raise _GuardError(f"{prog} {role} {token!r} is expanded by the shell, so the guard cannot "
                          f"read it to scan it; a program the guard cannot read does not run")


def _is_short_cluster(tok):
    """Whether ``tok`` is a single-dash token carrying more than one letter."""
    return tok.startswith("-") and not tok.startswith("--") and len(tok) > 2


def _python_cluster_option(tok):
    """The first value-taking letter of a Python short-option cluster and its attached value.

    Returns ``(letter, value)`` with ``value`` empty when the letter ends
    the token (its value is then the next word), or ``(None, None)`` when
    every letter is an argument-less switch (``-OO``, ``-IS``).
    """
    for position, letter in enumerate(tok[1:], start=2):
        if letter in _PYTHON_VALUE_LETTERS:
            return letter, tok[position:]
    return None, None


def _check_module(prog, module):
    """Refuse ``-m MODULE`` for a network-capable module; label it otherwise."""
    if module in NETWORK_MODULES:
        raise _GuardError(f"{prog} -m {module} is a network module; denied")
    return f"{prog}:-m {module}"


def _scan_script_operand(prog, tok, ctx):
    """Read and scan ``tok`` as the script ``prog`` runs."""
    _refuse_if_expanded(prog, "script", tok)
    script = _read_script(tok, ctx)
    _scan_code(script, f"{prog} script {tok!r}")
    return f"{prog}:{tok}"


_URI_SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def _decode_data_uri(payload):
    """The code carried by a ``data:`` URI's payload (the part after the scheme)."""
    header, _, data = payload.partition(",")
    if header.endswith(";base64"):
        try:
            return base64.b64decode(data).decode("utf-8", "replace")
        except (binascii.Error, ValueError):
            raise _GuardError("data: preload has invalid base64 payload; denied")
    return unquote(data)


def _scan_preload_uri(prog, value, ctx):
    """Scan a preload value spelled as a URI: ``data:``, ``file:``, ``node:`` or other.

    ``--import``/``--loader`` accept ES module specifiers, not just paths and
    package names. A ``data:`` URI carries its code inline -- unlike a package
    name, that is not resolved through a path the guard declines to model, it
    is the program itself, so it is decoded and scanned exactly as an inline
    ``-e`` argument would be. A ``file:`` URI names a script the same way a
    plain path does. ``node:`` names one of Node's own builtin modules, which
    is not user-supplied code. Any other scheme (network schemes and the
    rest) fetches code the guard cannot see before it runs, so it is refused
    rather than silently passed.
    """
    scheme = urlparse(value).scheme.lower()
    if scheme == "node":
        return
    if scheme == "data":
        _scan_code(_decode_data_uri(value.split(":", 1)[1]), f"{prog} preload {value!r}")
        return
    if scheme == "file":
        parsed = urlparse(value)
        if parsed.netloc not in ("", "localhost"):
            raise _GuardError(f"{prog} preload {value!r} names a remote file: authority; denied")
        _scan_code(_read_script(unquote(parsed.path), ctx), f"{prog} preload {value!r}")
        return
    raise _GuardError(f"{prog} preload {value!r} loads code via {scheme!r}, which the guard "
                      f"cannot inspect; denied")


def _scan_preload(prog, value, ctx):
    """Scan a preloaded module that names a file; pass a package name.

    A value spelled as a path (``./x.js``, ``../x.rb``, ``~/x``, ``/x``) is
    held to the same rule as a script: it is read and scanned, and one that
    cannot be read blocks. A value spelled as a URI (``data:``, ``file:``,
    ``node:``, ...) is handled by ``_scan_preload_uri`` -- Node's ``--import``
    and ``--loader`` accept module specifiers in that form, and a bare
    package name never contains a colon. Any other value is a package name
    unless a file of that name happens to sit in the working directory.
    """
    if _URI_SCHEME.match(value):
        _scan_preload_uri(prog, value, ctx)
        return
    spelled_as_path = value.startswith((".", "~", "/"))
    if not spelled_as_path:
        if ctx.command_cwd is None or not os.path.isfile(os.path.join(ctx.command_cwd, value)):
            return
    _scan_code(_read_script(os.path.expanduser(value), ctx), f"{prog} preload {value!r}")


def _check_interpreter(prog, argv, piped, bodies, ctx, depth, analyze_command):
    """Find and scan the program an interpreter runs; return a descriptive label.

    The walk stops as soon as the program is fixed. After ``-`` (a stdin
    program) and after an inline-code flag, the remaining words are the
    program's arguments — data, not a second program — so they are not
    read as scripts. A program fixed inline is treated like a script: it is
    scanned, its arguments are not. Its standard input still is, because an
    inline program can read and execute it.

    Python's ``-c`` makes the whole remainder ``sys.argv``, so the walk
    returns at once. Perl, Ruby and Node accept repeated inline-code flags,
    so their walk continues and ends at the first positional, refusing on
    the way any token the shell could expand into a further program and
    any short-option cluster it cannot classify. Interpreters with no
    option table get neither early return: for some (``lua -e 'x' s.lua``)
    the operand after inline code is still a program, so it is read.
    """
    args = argv[1:]
    family = _interpreter_family(prog)
    value_flags = _INTERPRETER_VALUE_FLAGS.get(family, frozenset())
    preload_flags = _INTERPRETER_PRELOAD_FLAGS.get(family, frozenset())
    # The generic module fallback below is shared by every interpreter,
    # including ones this module has no option grammar for (php, lua,
    # tclsh, Rscript, ...). Attached-form matching is only enabled for the
    # four families above, whose actual short-flag grammar is known; for
    # the rest it would risk misreading an unrelated option (a classpath
    # flag, say) as an attached flag.
    attached_ok = family is not None
    code_seen = False
    # Set only by an actual inline program, not by Perl's -M/-m, which
    # loads a module and leaves the following operand a script that runs.
    inline_seen = False

    def operand_of(value, index):
        return value if value is not None else (args[index + 1] if index + 1 < len(args) else None)

    def inline_label():
        _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
        return f"{prog}:inline"

    i = 0
    while i < len(args):
        tok = args[i]
        # `--` ends option parsing. Before an inline program the next word
        # is the script even when it is dash-shaped (`perl -- -e` runs the
        # FILE `-e`); after one, every later word is argv data.
        if tok == "--" and family is not None:
            if inline_seen:
                return inline_label()
            if i + 1 >= len(args):
                break
            if args[i + 1] == "-":
                _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
                return f"{prog}:stdin"
            return _scan_script_operand(prog, args[i + 1], ctx)
        # Ruby's -C changes the working directory before the script and any
        # -r preload run, so subsequent relative paths resolve against the
        # new directory rather than being skipped like an ordinary value
        # flag. Its argument can be attached (`-Cdir`) as well as separate.
        if family == "ruby":
            flag, value = _match_flag(tok, frozenset({"-C"}))
            if flag:
                target = operand_of(value, i)
                if inline_seen:
                    _refuse_if_expanded(prog, "operand", target)
                if target is not None:
                    ctx = ctx.at(_resolve_dir(target, ctx.command_cwd))
                i += 1 if value is not None else 2
                continue
        # `-r` is inline code to php and a preload to node and ruby, so the
        # family's own tables are consulted before the code flags. Once an
        # inline program is known, an operand the shell can expand is
        # refused rather than swallowed: it can word-split into a further
        # `-e` and its code.
        flag, value = _match_flag(tok, value_flags)
        if flag:
            if inline_seen:
                _refuse_if_expanded(prog, "operand", operand_of(value, i))
            i += 1 if value is not None else 2
            continue
        flag, value = _match_flag(tok, preload_flags)
        if flag:
            preload = operand_of(value, i)
            if inline_seen:
                _refuse_if_expanded(prog, "operand", preload)
            if preload is not None:
                _scan_preload(prog, preload, ctx)
            i += 1 if value is not None else 2
            continue
        flag, value = _match_inline_flag(tok, family)
        # Perl's -M and -m both load a module named by an attached value
        # and keep parsing, unlike Python's terminal `-m MODULE`.
        module_load = not flag and family == "perl" and tok.startswith(("-M", "-m"))
        if flag or module_load:
            if module_load:
                code = tok[2:]
                if inline_seen:
                    _refuse_if_expanded(prog, "operand", code)
                i += 1
            elif value is not None:
                code = value
                i += 1
            else:
                code = args[i + 1] if i + 1 < len(args) else ""
                i += 2
            _scan_code(code, f"{prog} inline program")
            code_seen = True
            if flag and family == "python":
                return inline_label()
            if flag and family is not None:
                inline_seen = True
            continue
        flag, value = _match_flag(tok, frozenset({_INTERPRETER_MODULE_FLAG}), allow_attached=attached_ok)
        if flag:
            module = operand_of(value, i)
            if module is not None:
                return _check_module(prog, module)
        if prog == "php" and tok == "-S":
            raise _GuardError("php -S serves files over the network; denied")
        # A Python cluster can bury a value-taking letter (`-Ec'code'`,
        # `-Emhttp.server`); the switches before it take no argument.
        if family == "python" and _is_short_cluster(tok):
            letter, value = _python_cluster_option(tok)
            if letter is not None:
                operand = value if value else (args[i + 1] if i + 1 < len(args) else "")
                if letter == "c":
                    _scan_code(operand, f"{prog} inline program")
                    return inline_label()
                if letter == "m":
                    return _check_module(prog, operand)
                i += 1 if value else 2
                continue
        if inline_seen:
            _refuse_if_expanded(prog, "argument", tok)
            if _is_short_cluster(tok):
                raise _GuardError(f"{prog} option cluster {tok!r} after an inline program is not "
                                  f"one the guard can read; it may carry a further program, so "
                                  f"spell its switches separately")
            if tok == "-" or not tok.startswith("-"):
                return inline_label()
        if tok == "-":
            _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
            return f"{prog}:stdin"
        if tok.startswith("-"):
            i += 1
            continue
        if prog in ("awk", "gawk", "mawk", "nawk"):
            if not code_seen:
                _scan_code(tok, "awk program")
                code_seen = True
            return f"{prog}:inline"
        return _scan_script_operand(prog, tok, ctx)
    if inline_seen or not code_seen:
        _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
    return f"{prog}:inline"


def _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command):
    if piped:
        raise _GuardError(f"{prog} reads its program from a pipe; a program the guard cannot see "
                          f"does not run")
    for body in bodies:
        if prog in SHELLS:
            reason = analyze_command(body, ctx, depth + 1)
            if reason:
                raise _GuardError(f"heredoc fed to {prog}: {reason}")
        else:
            _scan_code(body, f"{prog} heredoc program")


def _shell_invocation(args):
    """Parse a shell's invocation options once, for everything that reads them.

    Returns ``(noexec, command, operands)``: whether the shell was asked
    to parse without running anything, the ``-c`` string if one was
    given (``None`` otherwise), and the words left after the options —
    the script to run and its arguments.

    An option that takes a value swallows the rest of its token, so the
    letters are walked one at a time rather than searched. Missing that
    is not cosmetic: in ``-c'curl … /in'`` the whole command string is
    part of the ``-c`` token, so a token-wide search for ``n`` finds one
    in the URL and calls a live command a syntax check, and a scan for
    the first word that does not start with ``-`` picks an option's
    value as the script to inspect.

    ``bash -n script.sh`` is a syntax check: the shell reads the file,
    parses it, and exits. Nothing in it executes, so nothing in it can
    send anything anywhere — including a file the guard cannot read,
    which otherwise blocks on the reasoning that a program it cannot
    inspect must not run. Under ``-n`` no program runs at all, and that
    holds for ``-c`` too: the command string is parsed, not run.
    """
    # The invocation options that consume a value: the rest of the token
    # when something follows the letter, otherwise the next word.
    takes_argument = "co"

    noexec = False
    command = None
    index = 0
    while index < len(args):
        tok = args[index]
        if tok == "--":
            index += 1
            break
        if not tok.startswith(("-", "+")) or tok in ("-", "+"):
            break
        index += 1
        if tok.startswith("--"):
            if tok == "--command" and command is None and index < len(args):
                command = args[index]
                index += 1
            continue

        # A `+` cluster turns options OFF — `+o noexec` is the opposite
        # of a syntax check — so it can never establish noexec. Its
        # value is still consumed so it is not read as an operand.
        enabling = tok.startswith("-")
        for position, letter in enumerate(tok[1:]):
            if letter == "n" and enabling:
                noexec = True
                continue
            if letter in takes_argument:
                value = tok[position + 2:]
                if not value and index < len(args):
                    value = args[index]
                    index += 1
                if letter == "c" and command is None:
                    command = value
                elif letter == "o" and enabling and value == "noexec":
                    noexec = True
                break

    return noexec, command, args[index:]


def _check_shell(prog, argv, piped, bodies, ctx, depth, analyze_command):
    args = argv[1:]
    if prog == "eval":
        nested = " ".join(args)
        reason = analyze_command(nested, ctx, depth + 1)
        if reason:
            raise _GuardError(f"eval: {reason}")
        return "eval:inline"
    if prog in ("source", "."):
        if not args:
            raise _GuardError("source without a file")
        _scan_shell_script(_read_script(args[0], ctx), f"sourced script {args[0]!r}")
        return f"source:{args[0]}"
    noexec, command, operands = _shell_invocation(args)

    # Nothing executes under -n, so there is nothing here to police —
    # not the command string, not the script, not a script the guard
    # cannot even read.
    if noexec:
        return f"{prog}:-n"

    if command is not None:
        reason = analyze_command(command, ctx, depth + 1)
        if reason:
            raise _GuardError(f"{prog} -c: {reason}")
        return f"{prog}:-c"

    if operands:
        _scan_shell_script(_read_script(operands[0], ctx), f"{prog} script {operands[0]!r}")
        return f"{prog}:{operands[0]}"
    _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
    return f"{prog}:stdin"


# Backward-compatible alias: earlier callers raised ``_GuardError``
# because each module had its own stub. They now share ``GuardError``
# from ``exfil_bash_lex``; this alias keeps any code that named the
# local stub still working.
_GuardError = GuardError
