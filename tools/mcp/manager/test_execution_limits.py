"""
Enforcement of the "no broad test runs" rule for job submission.

On 2026-09-16 an operator-side assistant submitted a job whose
``post_completion_command`` ran a full ``mvn install`` followed by an entire
``engine/utils`` CI shard (``-DAR_TEST_GROUP``/``-DAR_TEST_GROUPS``), with a
3600s timeout and 2 retries. It burned over 3 hours on a macOS runner, and
the retry sessions then weakened pre-existing tests to force a pass. A
separate job's prompt told the agent in plain English to "run the relevant
flowtree module tests" -- broad in a different way, and just as costly.

Written guidance did not stop either failure mode, so this module enforces
the rule mechanically at every surface that can start a test run on behalf
of an agent or a job submitter:

  - ``post_completion_command`` (validate_post_completion_command) -- the
    shell command a submitted job runs after the agent declares done.
  - ``post_completion_timeout_seconds`` (validate_post_completion_timeout)
    -- the wall-clock budget for that command.
  - the free-text task ``prompt`` (lint_prompt_for_broad_test_instructions)
    -- instructions that tell the agent, in English, to run something broad.

Unlike the commit-sequencing linter in ``prompt_linting.py``, none of the
checks here have a bypass flag. The rule has no legitimate exception:
broad verification belongs to CI, never to an agent session or a
post-completion gate.
"""

import re
import shlex


# Maximum wall-clock budget for any test or build invocation an agent or
# job submitter can request. 2400s = 40 minutes.
POST_COMPLETION_MAX_TIMEOUT_SECONDS = 2400

_SHELL_OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}"}

# Matches a whole -DskipTests argument token, capturing an explicit true/false
# value when present (a bare flag with no "=value" means true). Used with
# re.match against a single token, not a substring search, so a plain
# occurrence never wrongly reads "-DskipTests=false" as bare-true.
_SKIP_TESTS_PROP_PATTERN = re.compile(r"^-DskipTests(?:=(true|false))?$", re.IGNORECASE)

# Same shape as _SKIP_TESTS_PROP_PATTERN for the maven.test.skip property.
_MAVEN_TEST_SKIP_PROP_PATTERN = re.compile(
    r"^-Dmaven\.test\.skip(?:=(true|false))?$", re.IGNORECASE)

# Maven lifecycle phases that execute tests unless skipped. "install" and
# "verify" are the two the incident used; "package" and "deploy" sit at or
# past "test" in the default lifecycle and carry the same risk.
_MVN_TEST_RUNNING_PHASES = {"test", "integration-test", "verify", "install", "package", "deploy"}

# Maven launcher executable names recognized by _maven_segment_violation: the
# plain "mvn" plus the Maven Wrapper scripts ("./mvnw", "./mvnw.cmd") and the
# standalone Windows batch launcher. Without these, "./mvnw.cmd test -pl
# engine/utils" would see a base name of "mvnw.cmd" (not "mvn") and be waved
# through as a custom command -- base-name extraction only strips leading
# path-directory components, never file extensions, so it never normalizes
# "mvnw.cmd" to "mvnw".
_MVN_LAUNCHER_NAMES = {"mvn", "mvnw", "mvn.cmd", "mvnw.cmd"}

# No leading word-boundary assertion: the real shard invocation shape is
# "-DAR_TEST_GROUP=2", where "AR_TEST_GROUP" is glued directly to the "-D"
# property prefix with no boundary between "D" and "A" (both word
# characters) -- a leading \b would never match that form and would leave
# the actual incident shape undetected.
_AR_TEST_GROUP_PATTERN = re.compile(r"AR_TEST_GROUPS?\b")

_ENV_ASSIGNMENT_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=.*$")

_SHELL_INTERPRETERS = {"sh", "bash", "zsh", "dash", "ksh"}

# `env` options that consume the following token as their own operand (unless
# given in glued `--opt=value` form) rather than being a bare flag -- e.g.
# `env -u FOO mvn test` unsets FOO before running `mvn`, so "FOO" must not be
# mistaken for the wrapped command's own first token. Excludes -S/
# --split-string: unlike every other entry here, that option's operand is not
# passed through unchanged as the wrapped command's argument -- env
# word-splits it into a brand-new command line and executes that, so
# `_unwrap_env` handles it separately via `_ENV_SPLIT_SCRIPT_SENTINEL`
# instead of treating it as an operand to skip.
_ENV_OPTIONS_WITH_OPERAND = {"-u", "--unset", "-C", "--chdir"}

_BACKTICK_SUBSTITUTION_PATTERN = re.compile(r"`([^`]*)`")

# Sentinel first token used by ``_line_segments`` to mark a segment that
# could not be tokenized, and recognized by ``_segment_violations`` to
# reject it explicitly rather than routing the raw text through ordinary
# command detection. Not a value any real shell token can equal, since a
# NUL byte cannot appear in a shell command line.
_UNPARSEABLE_SENTINEL = "\0unparseable\0"

# Sentinel first token used by ``_unwrap_env`` to signal that it found an
# ``env -S``/``--split-string`` script rather than an ordinary wrapped
# command, and recognized by ``_segment_violations`` to route the second
# element (the script text) to ``_text_violations`` instead of treating it as
# a command's own first token. Not a value any real shell token can equal.
_ENV_SPLIT_SCRIPT_SENTINEL = "\0envSplitScript\0"

# Matches a bare shell variable reference used as a whole token, in either the
# "$VAR" or "${VAR}" form, capturing the variable's name. Used by
# `_resolve_variable_command` to recognize "$cmd" in "cmd='mvn test -pl
# engine/utils'; $cmd" as a reference to a variable recorded by
# `_record_assignment_only_segment` earlier in the same command text.
_VARIABLE_REFERENCE_PATTERN = re.compile(r"^\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?$")

# python/python3 interpreter option flags that take no operand of their own,
# so they can precede -m without hiding it -- e.g. "python3 -O -m pytest
# tests/" must still be recognized as "-m pytest" by `_index_of_module_flag`.
# Not exhaustive of every real Python flag, only the ones that could
# plausibly appear before -m in an agent- or job-submitter-constructed
# command.
_PYTHON_NOARG_FLAGS = {
    "-O", "-OO", "-B", "-b", "-bb", "-d", "-E", "-h", "-i", "-I",
    "-q", "-s", "-S", "-t", "-tt", "-u", "-v", "-x", "-3", "-R",
}

# python/python3 interpreter option flags that consume the following token as
# their own operand, mirroring _PYTHON_NOARG_FLAGS for flags that are not bare.
_PYTHON_ARG_FLAGS = {"-W", "-X"}

# Sentinel prefix used by `_mask_command_substitutions` to stand in for a
# $(...)/backtick command substitution before shlex tokenization, and by
# `_unmask_token` to restore the original substitution text afterward.
_SUBST_SENTINEL_PATTERN = re.compile(r"\x00SUBST(\d+)\x00")

# Shell control-flow keywords that can precede a segment's real command after
# operator splitting -- e.g. "if true; then mvn test -pl engine/utils; fi"
# splits on ";" into a segment ["then", "mvn", "test", "-pl", "engine/utils"],
# whose first token is "then", not "mvn". Without stripping these,
# `_maven_segment_violation` and `_pytest_segment_violation` never see the
# wrapped command at all.
_SHELL_CONTROL_WORDS = {
    "if", "then", "elif", "else", "fi", "while", "until", "do", "done",
    "for", "case", "esac", "select", "function",
}

# Command-prefix wrappers that pass their remaining arguments through to the
# real command unchanged: a shell builtin/wrapper such as ``command mvn
# test`` or ``sudo mvn test`` must not be waved through just because its
# first token is not literally ``mvn``/``pytest``/``python``. Minus "env" --
# env is handled separately by ``_unwrap_env`` because it also strips its own
# VAR=value assignments and flags.
_CMD_PREFIXES = {"!", "time", "nohup", "sudo", "command", "exec", "builtin", "stdbuf", "nice", "ionice"}

# _CMD_PREFIXES wrapper option flags (keyed by the wrapper's base name) that
# consume the following token as their own operand, unless given in glued
# `--opt=value` form -- mirroring _ENV_OPTIONS_WITH_OPERAND for `env`.
# Without this, `nice -n 10 mvn test` would strip only "nice" and leave "-n"
# as the wrapped command's own first token, never reaching "mvn"; `sudo -u
# user mvn test` has the same problem with "-u". A wrapper absent from this
# map, or a flag absent from its set, is still stripped as a bare flag with
# no operand by `_unwrap_cmd_prefix_options` -- it fails toward stripping
# less, not toward absorbing an unrecognized flag's operand by mistake.
_CMD_PREFIX_OPTIONS_WITH_OPERAND = {
    "sudo": {"-u", "--user", "-g", "--group", "-h", "--host", "-p", "--prompt",
             "-C", "--close-from", "-R", "--chroot", "-T", "--command-timeout"},
    "nice": {"-n", "--adjustment"},
    "ionice": {"-c", "--class", "-n", "--classdata", "-p", "--pid"},
    "stdbuf": {"-i", "--input", "-o", "--output", "-e", "--error"},
    "time": {"-o", "--output", "-f", "--format"},
    "exec": {"-a", "--as"},
}


def _tokenize(command: str) -> list:
    """Split ``command`` into shell tokens, treating operators like ``&&``
    as tokens in their own right even when glued to a word with no
    whitespace (``mvn test&&echo ok``).

    Plain ``shlex.split`` only splits on whitespace and quoting, so
    punctuation attached directly to a word stays part of that word's
    token -- ``mvn test&&echo ok`` would yield a single ``test&&echo``
    token, hiding the ``mvn test`` invocation from every check that looks
    for an exact ``test`` phase token. ``shlex.shlex`` with
    ``punctuation_chars`` enabled splits ``();<>|&`` (and the multi-char
    operators built from them, e.g. ``&&``/``;;``/``|&``) into their own
    tokens regardless of surrounding whitespace, which is exactly the
    shell behaviour this validator needs to match.
    """
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    lexer.commenters = ""
    return list(lexer)


def _mask_command_substitutions(text: str):
    """Replaces every top-level ``$(...)``/backtick command substitution in
    ``text`` (outside single quotes, which suppress substitution entirely) with
    a NUL-delimited sentinel token, returning the masked text and the ordered
    list of original substitution spans (including their ``$()``/backtick
    delimiters).

    ``shlex``'s ``punctuation_chars`` splits on a bare ``(``/``)`` regardless
    of the ``$(`` substitution syntax that encloses them, which would
    otherwise scatter a substitution's own parens and interior words across
    unrelated segments and hide it from per-segment command-position
    analysis. Restoring the exact original text (not just the substitution's
    inner command) after tokenization via ``_unmask_token`` lets
    ``_segment_violations`` recognize when a segment's own first token is a
    substitution, since it can then match on the literal ``$(``/backtick
    marker.
    """
    spans = []
    result = []
    i = 0
    n = len(text)
    in_single = False
    while i < n:
        c = text[i]
        if in_single:
            result.append(c)
            if c == "'":
                in_single = False
            i += 1
            continue
        if c == "'":
            in_single = True
            result.append(c)
            i += 1
            continue
        if c == "$" and i + 1 < n and text[i + 1] == "(":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                j += 1
            if depth == 0:
                spans.append(text[i:j])
                result.append("\x00SUBST{}\x00".format(len(spans) - 1))
                i = j
                continue
        if c == "`":
            close = text.find("`", i + 1)
            end = close + 1 if close >= 0 else n
            spans.append(text[i:end])
            result.append("\x00SUBST{}\x00".format(len(spans) - 1))
            i = end
            continue
        result.append(c)
        i += 1
    return "".join(result), spans


def _unmask_token(token: str, spans: list) -> str:
    """Restores every sentinel ``_mask_command_substitutions`` embedded in
    ``token`` back to its original substitution text."""
    return _SUBST_SENTINEL_PATTERN.sub(lambda m: spans[int(m.group(1))], token)


def _is_substitution_executable(token: str) -> bool:
    """True when ``token`` contains a command substitution marker (``$(`` or
    a backtick) -- meaning the shell determines this token's actual text at
    run time from a subprocess's output, which this validator cannot resolve
    statically. Used by ``_segment_violations`` to reject a segment whose
    executable (first token) is determined this way, e.g. ``$(printf mvn)
    test -pl engine/utils``: the substitution's own inner command (``printf
    mvn``) is harmless in isolation, but its output becomes the broad ``mvn
    test`` command actually executed, which no first-token check can see
    without running the substitution."""
    return "$(" in token or "`" in token


def _shell_segments(command: str) -> list:
    """Best-effort split of a shell command string into simple-command token
    lists, one per ``&&``/``;``/``|``/newline-separated segment.

    Mirrors the tokenizer in ``.claude/hooks/lib/mvn_test_check.py`` so the
    ar-manager-side submission check and the agent-side Bash hook agree on
    what counts as a distinct command within a chained pipeline. The command
    is split on newlines before tokenization rather than relying on
    ``shlex`` to emit ``"\\n"`` as its own token: ``shlex.shlex`` always
    treats newline as whitespace (a separator consumed between tokens, never
    a token itself) regardless of ``punctuation_chars``, so a multi-line
    command such as ``"mvn test -Dtest=Foo#bar\\nmvn test -pl engine/utils"``
    would otherwise tokenize as one unbroken segment -- letting the narrow
    selector on the first line mask the second line's broad invocation.
    """
    segments = []
    for line in command.split("\n"):
        segments.extend(_line_segments(line))
    return segments


def _line_segments(line: str) -> list:
    """Splits a single (newline-free) line into simple-command token lists.

    Falls back to a sentinel segment recognized by ``_segment_violations``
    when the line cannot be tokenized (e.g. unbalanced quotes). Routing the
    raw, unparsed text through the ordinary ``mvn``/``pytest`` first-token
    checks would silently accept it -- the whole line becomes one token, so
    it can never equal ``"mvn"`` or ``"pytest"`` and none of the broad-run
    checks fire, exactly backwards from the "fail toward flagging it"
    intent. The sentinel makes an unparseable command a violation in its own
    right instead.
    """
    masked, spans = _mask_command_substitutions(line)
    try:
        tokens = [_unmask_token(t, spans) for t in _tokenize(masked)]
    except ValueError:
        return [[_UNPARSEABLE_SENTINEL, line]]
    segments = []
    current = []
    for tok in tokens:
        if tok in _SHELL_OPERATORS:
            if current:
                segments.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        segments.append(current)
    return segments


def _env_split_string_operand(tok: str, tokens: list, i: int):
    """Returns the script text of an ``env -S``/``--split-string`` flag at
    ``tokens[i]``, in any of its three forms (``-S <script>``, glued
    ``-S<script>``, or ``--split-string=<script>``), or ``None`` when
    ``tokens[i]`` is not that flag."""
    if tok in ("-S", "--split-string"):
        return tokens[i + 1] if i + 1 < len(tokens) else ""
    if tok.startswith("--split-string="):
        return tok[len("--split-string="):]
    if tok.startswith("-S") and len(tok) > 2:
        return tok[2:]
    return None


def _unwrap_env(tokens: list) -> list:
    """Strips a leading ``env`` invocation's ``VAR=value`` assignments and
    flags (e.g. ``-i``), returning the wrapped command's own tokens
    unchanged when ``tokens`` is not an ``env`` invocation.

    A flag in ``_ENV_OPTIONS_WITH_OPERAND`` (e.g. ``-u``) consumes the next
    token as its own operand unless given in glued ``--opt=value`` form --
    without this, ``env -u FOO mvn test`` would treat ``FOO`` as the wrapped
    command's own first token instead of skipping it, and never recognize
    ``mvn`` at all.

    ``-S``/``--split-string`` is different in kind, not just another
    operand-flag: ``env`` word-splits that operand and executes the result as
    a brand-new command line, the same way ``sh -c`` does -- it does not pass
    the operand through unchanged as an argument to the wrapped command.
    Skipping it like an ordinary operand would silently discard ``env -S
    'mvn test -pl engine/utils'``'s actual payload instead of validating it,
    so this returns the ``_ENV_SPLIT_SCRIPT_SENTINEL`` marker pair instead.
    """
    if not tokens or tokens[0].rsplit("/", 1)[-1] != "env":
        return tokens
    i = 1
    while i < len(tokens):
        tok = tokens[i]
        if _ENV_ASSIGNMENT_PATTERN.match(tok):
            i += 1
            continue
        if not tok.startswith("-"):
            break
        split_script = _env_split_string_operand(tok, tokens, i)
        if split_script is not None:
            return [_ENV_SPLIT_SCRIPT_SENTINEL, split_script]
        i += 1
        if tok in _ENV_OPTIONS_WITH_OPERAND and "=" not in tok and i < len(tokens):
            i += 1
    return tokens[i:]


def _unwrap_leading_assignments(tokens: list) -> list:
    """Strips one or more leading bare ``VAR=value`` assignment tokens (as
    the shell accepts directly in command position, with no ``env``
    keyword), returning ``tokens`` unchanged when it does not start with
    one."""
    i = 0
    while i < len(tokens) and _ENV_ASSIGNMENT_PATTERN.match(tokens[i]):
        i += 1
    return tokens[i:] if i else tokens


def _unwrap_cmd_prefix_options(wrapper_base: str, tokens: list) -> list:
    """Strips the option flags -- and, for a recognized wrapper/flag pair in
    ``_CMD_PREFIX_OPTIONS_WITH_OPERAND``, their operands -- that immediately
    follow a stripped ``_CMD_PREFIXES`` wrapper name, so the wrapped
    command's own first token (the thing actually executed) is what
    ``_maven_segment_violation``/``_pytest_segment_violation`` see. Without
    this, ``nice -n 10 mvn test`` or ``sudo -u user mvn test`` would leave
    ``-n``/``-u`` as the apparent command, never reaching ``mvn``. Stops at
    the first non-flag token, or after a bare ``--`` end-of-options marker.
    """
    operand_flags = _CMD_PREFIX_OPTIONS_WITH_OPERAND.get(wrapper_base, set())
    i = 0
    while i < len(tokens) and tokens[i].startswith("-") and tokens[i] != "--":
        flag = tokens[i]
        i += 1
        if flag in operand_flags and "=" not in flag and i < len(tokens):
            i += 1
    if i < len(tokens) and tokens[i] == "--":
        i += 1
    return tokens[i:]


def _is_env_split_string_result(unwrapped: list) -> bool:
    """True when ``unwrapped`` is the ``_ENV_SPLIT_SCRIPT_SENTINEL`` marker
    pair ``_unwrap_env`` returns for an ``env -S``/``--split-string``
    invocation."""
    return len(unwrapped) == 2 and unwrapped[0] == _ENV_SPLIT_SCRIPT_SENTINEL


def _unwrap_command_prefixes(tokens: list) -> list:
    """Strips a leading chain of command-prefix wrappers -- ``env``
    (with its own ``VAR=value`` assignments and flags), bare ``VAR=value``
    assignments with no leading ``env`` token (the shell accepts one or more
    of these directly in command position, e.g. ``FOO=bar mvn test``),
    simple wrappers in ``_CMD_PREFIXES`` (``sudo``, ``nohup``, ``time``,
    ``exec``, ``command``, ``builtin``, ``stdbuf``, ``nice``, ``ionice``,
    ``!``) along with any of that wrapper's own option flags and, for a
    recognized flag, its operand (see ``_unwrap_cmd_prefix_options``), and a
    leading ``_SHELL_CONTROL_WORDS`` keyword (e.g. ``then``, ``do``) left in
    front of a segment's real command by operator-splitting a chain like
    ``if true; then mvn test; fi`` -- so e.g. ``command mvn test``, ``sudo
    env FOO=bar mvn test``, ``nice -n 10 mvn test``, ``FOO=bar mvn test``, or
    ``then mvn test`` reach the real command. Returns ``tokens`` unchanged
    when it starts with none of these -- or, when ``tokens`` is an ``env
    -S``/``--split-string`` invocation, returns the
    ``_ENV_SPLIT_SCRIPT_SENTINEL`` marker pair from ``_unwrap_env`` unchanged,
    since that shape has no further tokens of its own left to unwrap.
    """
    while tokens:
        unwrapped = _unwrap_env(tokens)
        if _is_env_split_string_result(unwrapped):
            return unwrapped
        if unwrapped is not tokens:
            tokens = unwrapped
            continue
        unwrapped = _unwrap_leading_assignments(tokens)
        if unwrapped is not tokens:
            tokens = unwrapped
            continue
        base = tokens[0].rsplit("/", 1)[-1]
        if base in _CMD_PREFIXES:
            tokens = _unwrap_cmd_prefix_options(base, tokens[1:])
            continue
        if base in _SHELL_CONTROL_WORDS:
            tokens = tokens[1:]
            continue
        break
    return tokens


def _shell_dash_c_script(tokens: list):
    """Returns the inline script text when ``tokens`` is a shell interpreter
    invoked with a ``-c`` option -- whether as its own token (``sh -c
    "<script>"``) or combined with other short options in the same token
    (``bash -ec "<script>"``, ``bash -e -c "<script>"``) -- or ``None`` when
    it is not that shape.

    Walks the leading run of single-dash short-option tokens (stopping at
    the first token that is not one, a ``--`` form, or the end of the list)
    looking for one containing ``c``. Mirrors ``getopt``: any characters in
    that token after the ``c`` are its glued-on argument (``-cSCRIPT``);
    when none remain, the following whole token is the argument instead
    (``-ec "<script>"``). Without this, ``bash -ec 'mvn test -pl
    engine/utils'`` would see option token ``-ec``, not literally ``-c``,
    and be waved through as an unrecognized interpreter invocation instead
    of having its embedded script inspected.
    """
    if len(tokens) < 2 or tokens[0].rsplit("/", 1)[-1] not in _SHELL_INTERPRETERS:
        return None
    for i in range(1, len(tokens)):
        tok = tokens[i]
        if not tok.startswith("-") or tok.startswith("--"):
            return None
        c_index = tok.find("c", 1)
        if c_index < 0:
            continue
        remainder = tok[c_index + 1:]
        if remainder:
            return remainder
        return tokens[i + 1] if i + 1 < len(tokens) else None
    return None


def _eval_script(tokens: list):
    """Returns the inline script text when ``tokens`` is ``eval <words...>``,
    or ``None`` when it is not that shape. Mirrors the shell's own behaviour
    of concatenating eval's arguments with spaces and re-parsing the result
    as a new command line -- without this, ``eval mvn test -pl
    engine/utils`` would see a first token of ``eval`` (not ``mvn``) and be
    waved through unchecked."""
    if not tokens or tokens[0].rsplit("/", 1)[-1] != "eval" or len(tokens) < 2:
        return None
    return " ".join(tokens[1:])


def _dollar_paren_substitutions(text: str) -> list:
    """Extract the inner command text of every ``$(...)`` command
    substitution in ``text``, honoring balanced parentheses so a nested
    ``$( ... $(...) ... )`` is not truncated at the first closing paren."""
    results = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] == "$" and i + 1 < n and text[i + 1] == "(":
            depth = 1
            j = i + 2
            start = j
            while j < n and depth > 0:
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                j += 1
            if depth == 0:
                results.append(text[start:j - 1])
                i = j
                continue
        i += 1
    return results


def _command_substitutions(text: str) -> list:
    """Extract the inner command text of every backtick or ``$(...)``
    command substitution appearing anywhere in ``text``."""
    return _BACKTICK_SUBSTITUTION_PATTERN.findall(text) + _dollar_paren_substitutions(text)


def _segment_violations(tokens: list) -> list:
    """Return violation reasons for a single shell segment, first unwrapping
    a leading chain of command-prefix wrappers (``env VAR=val ...``,
    ``sudo``, ``command``, ``exec``, ...) and -- when the segment is a shell
    interpreter invoked as ``sh|bash|zsh|dash|ksh -c "<script>"`` or ``eval
    <words...>`` -- recursing into the inline script's own text instead of
    checking the interpreter/eval invocation itself. Without this, ``env
    mvn test``, ``command mvn test``, ``sh -c 'mvn test'``, or ``eval mvn
    test`` would see a first token other than ``mvn``/``pytest`` and be
    waved through unchecked.

    A segment produced by ``_line_segments``' tokenization-failure fallback
    (first token ``_UNPARSEABLE_SENTINEL``) is rejected outright here rather
    than falling through to the ``mvn``/``pytest`` checks below, which could
    never fire against unparsed raw text anyway.
    """
    if tokens and tokens[0] == _UNPARSEABLE_SENTINEL:
        raw = tokens[1] if len(tokens) > 1 else ""
        return [
            "Command segment could not be parsed as a shell command "
            "(e.g. unbalanced quotes): \"{}\". Rewrite it so it "
            "tokenizes unambiguously; it cannot be validated as written.".format(raw)
        ]
    unwrapped = _unwrap_command_prefixes(tokens)
    if _is_env_split_string_result(unwrapped):
        return _text_violations(unwrapped[1])
    if unwrapped and _is_substitution_executable(unwrapped[0]):
        return [
            "Command position in \"{}\" is determined by a command "
            "substitution ($(...) or `...`), which this validator cannot "
            "resolve statically. Do not construct the executed command "
            "name via a substitution.".format(" ".join(tokens))
        ]
    script = _shell_dash_c_script(unwrapped)
    if script is None:
        script = _eval_script(unwrapped)
    if script is not None:
        return _text_violations(script)
    reason = _maven_segment_violation(unwrapped)
    if reason:
        return [reason]
    reason = _pytest_segment_violation(unwrapped)
    if reason:
        return [reason]
    reason = _unittest_segment_violation(unwrapped)
    if reason:
        return [reason]
    reason = _bare_shell_interpreter_violation(unwrapped)
    if reason:
        return [reason]
    return []


def _record_assignment_only_segment(segment: list, variables: dict) -> bool:
    """If ``segment`` consists entirely of ``VAR=value`` assignment tokens (as
    the shell accepts directly in command position, e.g. ``cmd='mvn test -pl
    engine/utils'``), records each into ``variables`` (later tokens override
    earlier ones, left to right, matching real shell assignment order) and
    returns True. Returns False, leaving ``variables`` untouched, when
    ``segment`` is not entirely assignments -- e.g. it also has its own
    command to run.
    """
    if not segment or _unwrap_leading_assignments(segment):
        return False
    for tok in segment:
        name, _, value = tok.partition("=")
        variables[name] = value
    return True


def _resolve_variable_command(tokens: list, variables: dict):
    """Returns ``tokens`` with a leading bare ``$VAR``/``${VAR}`` reference
    replaced by the recorded variable's own (re-tokenized) words, or
    ``None`` when ``tokens`` is empty or its first token is not a reference
    to a variable ``_record_assignment_only_segment`` already recorded
    earlier in the same command text.

    Without this, ``cmd='mvn test -pl engine/utils'; $cmd`` assigns the
    broad command to ``cmd`` in one segment and executes it by reference in
    the next -- the shell resolves ``$cmd`` to the assigned command line,
    but no first-token check (mvn/pytest/...) can see that without
    resolving the reference first.
    """
    if not tokens:
        return None
    match = _VARIABLE_REFERENCE_PATTERN.match(tokens[0])
    if not match or match.group(1) not in variables:
        return None
    return _tokenize(variables[match.group(1)]) + tokens[1:]


def _text_violations(text: str) -> list:
    """Validate a shell command string: its own simple-command segments plus
    the contents of any backtick/``$(...)`` command substitution appearing
    anywhere in it.

    A substitution executes even when the outer command's own first token
    (e.g. ``echo``) is not itself Maven or pytest -- ``echo \\`mvn test -pl
    engine/utils\\``` is tokenized as an ``echo`` segment, but the shell
    still runs the embedded ``mvn test`` to produce echo's argument. Scanning
    for substitutions is text-based rather than tied to one segment's tokens,
    so it also catches a substitution embedded inside a quoted argument that
    the tokenizer would otherwise treat as ordinary text.

    Segments are also scanned, in order, for bare ``VAR=value`` assignments
    and later ``$VAR``/``${VAR}`` references to them, so a broad command
    assigned to a variable and executed by reference is resolved to the
    command it actually runs (see ``_resolve_variable_command``).
    """
    violations = []
    variables = {}
    for segment in _shell_segments(text):
        if _record_assignment_only_segment(segment, variables):
            continue
        resolved = _resolve_variable_command(segment, variables)
        violations.extend(_segment_violations(resolved if resolved is not None else segment))
    for substitution in _command_substitutions(text):
        violations.extend(_text_violations(substitution))
    return violations


def _dtest_values(args: list) -> list:
    return [a[len("-Dtest="):] for a in args if a.startswith("-Dtest=")]


def _dtest_is_narrow(value: str) -> bool:
    """True if ``value`` is exactly one Class#method entry with non-empty,
    wildcard-free class and method names.

    A ``-Dtest`` value may name several comma-separated entries, but Maven
    runs all of them in a single invocation -- accepting more than one,
    even when each individually names a method, would still let one command
    run multiple tests, contradicting the "at most ONE test per invocation"
    rule this validator otherwise enforces. Only a single Class#method entry
    is narrow enough. A bare class name (no ``#``) also fails this check --
    see the HARD RULES for this rule: "a bare -Dtest=Class also counts as
    too broad". Surefire treats ``*`` and ``?`` in either half as
    wildcards, so e.g. ``FooTest#test*`` or ``Foo*#bar`` can still select
    and run several methods/classes in one invocation despite naming
    exactly one comma-separated entry with a ``#`` in it.
    """
    entries = [e for e in value.split(",") if e]
    if len(entries) != 1 or entries[0].count("#") != 1:
        return False
    class_name, _, method_name = entries[0].partition("#")
    if not class_name or not method_name:
        return False
    return not any(c in "*?" for c in class_name + method_name)


def _effective_skip_value(args: list, pattern) -> bool:
    """Returns the effective boolean value of a Maven skip property (e.g.
    skipTests) given every matching token in ``args``, or ``None`` when the
    property never appears.

    Maven system properties set via repeated ``-D`` take the LAST
    occurrence's value, so a command such as ``-DskipTests
    -DskipTests=false`` does NOT skip tests even though an earlier flag says
    otherwise -- returning as soon as any matching flag is seen, regardless
    of order, would wrongly accept that command as build-only. A bare flag
    with no ``=value`` means true.
    """
    value = None
    for arg in args:
        match = pattern.match(arg)
        if match:
            explicit = match.group(1)
            value = explicit is None or explicit.lower() == "true"
    return value


def _maven_segment_violation(tokens: list) -> str:
    """Return a violation reason for a single ``mvn ...`` command segment,
    or ``""`` when the segment is not Maven, skips tests, or already
    selects an explicit Class#method test."""
    if not tokens:
        return ""
    base = tokens[0].rsplit("/", 1)[-1]
    if base not in _MVN_LAUNCHER_NAMES:
        return ""
    args = tokens[1:]
    if _effective_skip_value(args, _SKIP_TESTS_PROP_PATTERN) is True \
            or _effective_skip_value(args, _MAVEN_TEST_SKIP_PROP_PATTERN) is True:
        return ""
    phases_present = sorted(a for a in args if a in _MVN_TEST_RUNNING_PHASES)
    dtest_values = _dtest_values(args)
    if not phases_present and not dtest_values:
        return ""
    rendered = " ".join(tokens)
    if not dtest_values:
        return (
            "Maven command runs a test-executing phase ({}) with no -Dtest "
            "selector: \"{}\". This runs the module's whole test suite. Pass "
            "-Dtest=Class#method for each test, or add -DskipTests if this "
            "command is only meant to build.".format(
                ", ".join(phases_present) or "install/verify/package/deploy", rendered))
    for value in dtest_values:
        if not _dtest_is_narrow(value):
            return (
                "Maven -Dtest={} in \"{}\" does not select explicit "
                "Class#method tests. A bare class selector (or none) runs "
                "every test in that class or module. Use Class#method for "
                "each test, one per invocation.".format(value, rendered))
    return ""


def _index_of_module_flag(tokens: list):
    """Returns the index of the ``-m`` flag among ``tokens``, skipping any
    leading ``_PYTHON_NOARG_FLAGS``/``_PYTHON_ARG_FLAGS`` interpreter option
    flags that precede it -- e.g. ``python3 -O -m pytest tests/`` must still
    be recognized as ``-m pytest``, not waved through because ``-O``
    occupies the position ``-m`` is checked at. Returns ``None`` when
    ``-m`` is not reachable that way: an unrecognized flag stops the walk
    rather than guessing past it, so a genuinely unrecognized interpreter
    invocation shape is left to whatever check runs next instead of being
    silently unwrapped.
    """
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if tok == "-m":
            return i
        if tok in _PYTHON_NOARG_FLAGS:
            i += 1
            continue
        if tok in _PYTHON_ARG_FLAGS:
            i += 2
            continue
        return None
    return None


def _pytest_segment_violation(tokens: list) -> str:
    """Return a violation reason for a single ``pytest``/``python -m pytest``
    command segment, or ``""`` when it already names explicit node ids."""
    if not tokens:
        return ""
    base = tokens[0].rsplit("/", 1)[-1]
    rest = tokens[1:]
    if base in ("python", "python3"):
        m_index = _index_of_module_flag(rest)
        if m_index is None or m_index + 1 >= len(rest) or rest[m_index + 1] != "pytest":
            return ""
        rest = rest[m_index + 2:]
    elif base in ("pytest", "py.test"):
        pass
    else:
        return ""
    positionals = [a for a in rest if not a.startswith("-")]
    if len(positionals) == 1 and "::" in positionals[0]:
        return ""
    if len(positionals) > 1:
        return (
            "pytest command names {} positional arguments in \"{}\". Even "
            "when each one is an explicit node id, pytest runs them "
            "together in a single invocation, which agents and job "
            "submitters may never do. Pass exactly one node id per "
            "invocation.".format(len(positionals), " ".join(tokens)))
    return (
        "pytest command has no explicit node id (file.py::test_name): "
        "\"{}\". This runs an entire file or directory. Pass explicit "
        "node ids, one test per invocation.".format(" ".join(tokens)))


def _unittest_segment_violation(tokens: list) -> str:
    """Return a violation reason for a ``python -m unittest`` command
    segment, or ``""`` when it already names a single dotted test id.

    Unlike pytest's ``file.py::test_name`` node id, ``unittest`` addresses a
    single test with a dotted ``module.Class.method`` path (two or more
    dots) -- a bare module or ``module.Class`` still runs every test in it,
    and ``discover`` explicitly walks and runs a whole test tree. Without
    this check, ``python3 -m unittest discover`` (the CI documentation's own
    example of a forbidden broad run) passed through both the Maven and
    pytest checks unrecognized, and was accepted.
    """
    if not tokens:
        return ""
    base = tokens[0].rsplit("/", 1)[-1]
    if base not in ("python", "python3"):
        return ""
    rest = tokens[1:]
    m_index = _index_of_module_flag(rest)
    if m_index is None or m_index + 1 >= len(rest) or rest[m_index + 1] != "unittest":
        return ""
    args = rest[m_index + 2:]
    positionals = [a for a in args if not a.startswith("-")]
    if len(positionals) == 1 and positionals[0] != "discover" \
            and positionals[0].count(".") >= 2:
        return ""
    return (
        "python -m unittest command in \"{}\" does not name a single "
        "dotted module.Class.method test id. \"discover\", a bare module, "
        "or a module.Class runs many tests at once, which agents and job "
        "submitters may never do. Pass exactly one module.Class.method id "
        "per invocation.".format(" ".join(tokens)))


def _bare_shell_interpreter_violation(tokens: list) -> str:
    """Return a violation reason when ``tokens`` invokes a
    ``_SHELL_INTERPRETERS`` interpreter with no script-file positional
    argument -- meaning it reads its script from standard input, as in
    ``printf 'mvn test -pl engine/utils' | sh`` or a heredoc. That shape
    cannot be validated as written: the script text is not present anywhere
    in the command line for this validator to inspect (unlike ``sh -c
    "<script>"``, which ``_shell_dash_c_script`` already recurses into).

    Returns ``""`` when the interpreter has a positional argument -- a
    script file path, e.g. ``bash scripts/verify-foo.sh`` -- since that is a
    deliberately supported, trusted use of a post-completion command (see
    ``PostCompletionCommandRule``'s javadoc) that this validator cannot and
    does not attempt to inspect the contents of.
    """
    if not tokens or tokens[0].rsplit("/", 1)[-1] not in _SHELL_INTERPRETERS:
        return ""
    for tok in tokens[1:]:
        if not tok.startswith("-"):
            return ""
    return (
        "Shell interpreter invoked with no script file or -c argument in "
        "\"{}\" reads its script from standard input (e.g. via a pipe or "
        "heredoc), which cannot be validated as written. Run mvn/pytest "
        "directly, or invoke an explicit script file instead of piping one "
        "into the interpreter.".format(" ".join(tokens)))


def validate_post_completion_command(command: str) -> list:
    """Return a list of human-readable violation strings for ``command``.

    An empty list means the command is acceptable. There is no bypass for
    this check -- see the module docstring.
    """
    if not command or not command.strip():
        return []
    violations = []
    if _AR_TEST_GROUP_PATTERN.search(command):
        violations.append(
            "Command references AR_TEST_GROUP/AR_TEST_GROUPS: \"{}\". "
            "CI-shard partitioning is reserved for the CI workflow matrix; "
            "agents and job submitters must never run a shard.".format(
                command.strip()[:200]))
    violations.extend(_text_violations(command))
    return violations


def validate_post_completion_timeout(seconds: int) -> str:
    """Return an error string if ``seconds`` exceeds the maximum, else ``""``."""
    if seconds and seconds > POST_COMPLETION_MAX_TIMEOUT_SECONDS:
        return (
            "post_completion_timeout_seconds={} exceeds the maximum of {} "
            "seconds (40 minutes). Broad verification belongs to CI; a "
            "post-completion gate must be a narrow, fast check.".format(
                seconds, POST_COMPLETION_MAX_TIMEOUT_SECONDS))
    return ""


# ---------------------------------------------------------------------------
# Prompt linter -- scans free-text task prompts for instructions to run a
# full/whole/entire suite, a module's tests, a shard, mvn test without a
# single-method selector, or AR_TEST_GROUP. Mirrors the structure of
# prompt_linting._lint_prompt_for_commit_sequencing but has no exemption or
# bypass: see module docstring.
# ---------------------------------------------------------------------------

_TEST_LINT_PATTERNS = [
    (re.compile(r"\b(full|whole|entire)\s+test\s+suite\b", re.IGNORECASE),
     '"full/whole/entire test suite" phrase'),
    (re.compile(r"\brun\s+(all|every)\s+(of\s+the\s+)?tests?\b", re.IGNORECASE),
     '"run all/every test(s)" phrase'),
    (re.compile(
        r"\b(?:run|execute|test)\s+(?:the\s+)?(?:relevant\s+)?[\w./-]*\s*module(?:'s)?\s+tests?\b",
        re.IGNORECASE),
     '"run the ... module tests" phrase (a whole module\'s test run)'),
    (re.compile(r"\brun(?:ning)?\s+(?:the\s+)?[\w./-]*\s*(?:CI\s+)?shard\b", re.IGNORECASE),
     '"run(ning) ... shard" phrase'),
    (re.compile(r"AR_TEST_GROUPS?\b"),
     "AR_TEST_GROUP/AR_TEST_GROUPS reference"),
]


def _effective_skip_value_in_text(fragment: str, pattern) -> bool:
    """Returns the effective boolean value of a Maven skip-property mention
    in free English/prose text, taking the LAST occurrence of ``pattern`` in
    ``fragment`` -- mirroring ``_effective_skip_value``'s last-``-D``-wins
    Maven semantics for an already-tokenized args list, but scanned across
    prose text instead. Returns ``None`` when the property is never
    mentioned. A bare mention with no ``=value`` means true.
    """
    value = None
    for match in pattern.finditer(fragment):
        explicit = match.group(1)
        value = explicit is None or explicit.lower() == "true"
    return value


class _MvnTestSegmentMatcher:
    """Flags an ``mvn <test-running-phase>`` mention whose OWN chained-command
    fragment has no Class#method ``-Dtest`` selector, without being fooled by
    a selector that belongs to a different command earlier or later on the
    same line.

    A single regex with a negative lookahead for ``-Dtest=\\S+#\\S+`` cannot
    express this correctly: the lookahead scans the rest of the whole line,
    so ``mvn test && mvn test -Dtest=Foo#bar`` wrongly exempts the FIRST
    (actually broad) ``mvn test`` because a selector exists later on the
    line, for an unrelated chained command. Splits the line on the chain
    operators (``&&``/``||``/``;``/``|``) by plain text rather than full
    shell tokenization -- prompt lines are English prose, not shell syntax,
    and commonly contain unescaped apostrophes (``don't``, ``module's``)
    that would make a quote-aware tokenizer raise on perfectly ordinary
    text -- and checks each ``mvn <phase>`` mention against only its own
    fragment. Exposes the same ``search(line)`` interface as a compiled
    pattern so it drops into ``_TEST_LINT_PATTERNS`` unchanged.

    Matches every phase in ``_MVN_TEST_RUNNING_PHASES``
    (test/integration-test/verify/install/package/deploy), not just
    ``test``: ``mvn verify`` and ``mvn install`` run the full default
    lifecycle up to and including tests unless ``-DskipTests`` is present,
    so a prompt telling the agent to "run mvn verify" is exactly as broad
    as "run mvn test". Also matches the Maven Wrapper launcher names in
    ``_MVN_LAUNCHER_NAMES`` (``mvnw``, ``mvn.cmd``), not just plain
    ``mvn``: a prompt telling the agent to "run ./mvnw test" is exactly as
    broad, and the bare ``mvn`` prefix does not match ``mvnw`` (no
    whitespace between ``mvn`` and ``w``).
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _MVN_TEST_PATTERN = re.compile(
        r"\b(?:" + "|".join(re.escape(n) for n in sorted(_MVN_LAUNCHER_NAMES)) + r")\s+(?:"
        + "|".join(re.escape(p) for p in sorted(_MVN_TEST_RUNNING_PHASES)) + r")\b",
        re.IGNORECASE)
    _SELECTOR_PATTERN = re.compile(r"-Dtest=\S+#\S+", re.IGNORECASE)
    # Captures an explicit true/false value when present (a bare mention
    # with no "=value" means true) so `_effective_skip_value_in_text` can
    # resolve repeated mentions in the same fragment to the LAST one's
    # value, matching real Maven -D semantics -- a single regex that only
    # checks whether "=true" (or a bare mention) appears ANYWHERE in the
    # fragment would wrongly exempt "mvn verify -DskipTests=true
    # -DskipTests=false", even though Maven's last-value-wins semantics
    # mean tests still run.
    _SKIP_TESTS_TEXT_PATTERN = re.compile(r"-DskipTests(?:=(true|false)\b)?", re.IGNORECASE)
    _MAVEN_TEST_SKIP_TEXT_PATTERN = re.compile(
        r"-Dmaven\.test\.skip(?:=(true|false)\b)?", re.IGNORECASE)

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line):
            if not self._MVN_TEST_PATTERN.search(fragment):
                continue
            if self._SELECTOR_PATTERN.search(fragment):
                continue
            if _effective_skip_value_in_text(fragment, self._SKIP_TESTS_TEXT_PATTERN) is True \
                    or _effective_skip_value_in_text(
                        fragment, self._MAVEN_TEST_SKIP_TEXT_PATTERN) is True:
                continue
            return True
        return None


_TEST_LINT_PATTERNS.append(
    (_MvnTestSegmentMatcher(),
     '"mvn test/verify/install/package/deploy" without a Class#method -Dtest selector'))


class _DTestBroadValueMatcher:
    """Flags a ``-Dtest=<value>`` mention that does not name exactly one
    ``Class#method`` entry.

    A single regex with a negative lookahead for ``#`` cannot express this: a
    mixed value like ``-Dtest=Foo,Bar#baz`` (where ``Foo`` alone is broad)
    satisfies a lookahead that only checks whether a ``#`` appears somewhere
    later in the string, because it finds the one in ``Bar#baz``. Requiring
    exactly one entry also catches ``-Dtest=Foo#bar,Baz#qux``, where every
    individual entry names a method but Maven still runs both in the same
    invocation -- matching ``_dtest_is_narrow``'s "at most ONE test per
    invocation" rule in the command validator. Also matches
    ``_dtest_is_narrow`` in requiring non-empty, wildcard-free class and
    method names: Surefire treats ``*``/``?`` as wildcards, so
    ``-Dtest=FooTest#test*`` still runs several methods despite naming one
    entry with a ``#`` in it. Exposes the same ``search(line)`` interface as
    a compiled pattern so it drops into ``_TEST_LINT_PATTERNS`` unchanged.
    """

    _VALUE_PATTERN = re.compile(r"-Dtest=(\S+)", re.IGNORECASE)

    def search(self, line: str):
        matches = list(self._VALUE_PATTERN.finditer(line))
        if not matches:
            return None
        for match in matches:
            entries = [e for e in match.group(1).split(",") if e]
            if len(entries) == 1 and entries[0].count("#") == 1:
                class_name, _, method_name = entries[0].partition("#")
                if class_name and method_name and not any(
                        c in "*?" for c in class_name + method_name):
                    continue
            return match
        return None


_TEST_LINT_PATTERNS.append(
    (_DTestBroadValueMatcher(), "-Dtest=<value> not naming exactly one Class#method entry"))


class _UnittestDiscoveryMatcher:
    """Flags a ``python -m unittest``/``python3 -m unittest`` mention that
    either uses ``discover`` or names no single dotted ``module.Class.method``
    test id, mirroring ``_unittest_segment_violation``'s command-line check
    (see its docstring) for free-text prompt instructions. Without this, a
    prompt telling the agent to "run python3 -m unittest discover" -- the CI
    documentation's own example of a forbidden broad run -- passed
    ``lint_prompt_for_broad_test_instructions`` unflagged, even though the
    equivalent Maven/pytest instructions are caught by the patterns above.
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _UNITTEST_PATTERN = re.compile(r"\bpython3?\s+-m\s+unittest\b", re.IGNORECASE)
    _DISCOVER_PATTERN = re.compile(r"\bdiscover\b", re.IGNORECASE)
    _DOTTED_ID_PATTERN = re.compile(r"\b\w+(?:\.\w+){2,}\b")

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line):
            if self._UNITTEST_PATTERN.search(fragment) and (
                    self._DISCOVER_PATTERN.search(fragment)
                    or not self._DOTTED_ID_PATTERN.search(fragment)):
                return True
        return None


_TEST_LINT_PATTERNS.append(
    (_UnittestDiscoveryMatcher(),
     '"python -m unittest discover" (or a unittest invocation naming no '
     "single module.Class.method id)"))


def lint_prompt_for_broad_test_instructions(prompt: str) -> list:
    """Scan ``prompt`` for instructions that would have the agent run a
    broad test set.

    Returns a list of ``(line_number, snippet, reason)`` tuples -- one per
    matched line (first matching pattern wins per line). There is no
    bypass flag for this linter; a prompt legitimately quoting the phrase
    must be rewritten instead. Only an empty (or whitespace-only) prompt is
    exempt -- a short-but-unambiguous instruction such as ``"run all
    tests"`` or ``"mvn test"`` is well within every pattern's own minimum
    length and must still be scanned.
    """
    if not prompt or not prompt.strip():
        return []
    violations = []
    for lineno, line in enumerate(prompt.splitlines(), 1):
        for pattern, reason in _TEST_LINT_PATTERNS:
            if pattern.search(line):
                snippet = line.strip()[:120]
                violations.append((lineno, snippet, reason))
                break
    return violations
