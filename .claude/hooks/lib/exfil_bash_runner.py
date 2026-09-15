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
import os
import sys

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from exfil_bash_lex import SHELLS, GuardError
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
    "ruby": frozenset({"-I", "-E", "-C", "-F"}),
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


def _interpreter_family(prog):
    """The option table an interpreter reads from, or None for one with no table."""
    for family, names in _INTERPRETER_FAMILIES.items():
        if prog in names:
            return family
    return None


def _scan_preload(prog, value, ctx):
    """Scan a preloaded module that names a file; pass a package name.

    A value spelled as a path (``./x.js``, ``../x.rb``, ``~/x``, ``/x``) is
    held to the same rule as a script: it is read and scanned, and one that
    cannot be read blocks. Any other value is a package name unless a file
    of that name happens to sit in the working directory.
    """
    spelled_as_path = value.startswith((".", "~", "/"))
    if not spelled_as_path:
        if ctx.command_cwd is None or not os.path.isfile(os.path.join(ctx.command_cwd, value)):
            return
    _scan_code(_read_script(os.path.expanduser(value), ctx), f"{prog} preload {value!r}")


def _check_interpreter(prog, argv, piped, bodies, ctx, depth, analyze_command):
    args = argv[1:]
    family = _interpreter_family(prog)
    value_flags = _INTERPRETER_VALUE_FLAGS.get(family, frozenset())
    preload_flags = _INTERPRETER_PRELOAD_FLAGS.get(family, frozenset())
    code_seen = False
    i = 0
    while i < len(args):
        tok = args[i]
        # `-r` is inline code to php and a preload to node and ruby, so the
        # family's own tables are consulted before the shared code flags.
        if tok in value_flags:
            i += 2
            continue
        if tok in preload_flags:
            if i + 1 < len(args):
                _scan_preload(prog, args[i + 1], ctx)
            i += 2
            continue
        if tok in _INTERPRETER_CODE_FLAGS or (prog in ("perl",) and tok.startswith("-M")):
            code = tok[2:] if tok.startswith("-M") else (args[i + 1] if i + 1 < len(args) else "")
            _scan_code(code, f"{prog} inline program")
            code_seen = True
            i += 2 if not tok.startswith("-M") else 1
            continue
        if tok == _INTERPRETER_MODULE_FLAG and i + 1 < len(args):
            if args[i + 1] in NETWORK_MODULES:
                raise _GuardError(f"{prog} -m {args[i + 1]} is a network module; denied")
            return f"{prog}:-m {args[i + 1]}"
        if prog == "php" and tok == "-S":
            raise _GuardError("php -S serves files over the network; denied")
        if tok == "-":
            code_seen = True
            _check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)
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