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

_SHELL_OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}", "\n"}

_SKIP_TESTS_PATTERN = re.compile(
    r"^-DskipTests(=true)?$|^-Dmaven\.test\.skip(=true)?$", re.IGNORECASE)

# Maven lifecycle phases that execute tests unless skipped. "install" and
# "verify" are the two the incident used; "package" and "deploy" sit at or
# past "test" in the default lifecycle and carry the same risk.
_MVN_TEST_RUNNING_PHASES = {"test", "integration-test", "verify", "install", "package", "deploy"}

_AR_TEST_GROUP_PATTERN = re.compile(r"\bAR_TEST_GROUPS?\b")

_ENV_ASSIGNMENT_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=.*$")

_SHELL_INTERPRETERS = {"sh", "bash", "zsh", "dash", "ksh"}


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


def _shell_segments(command: str) -> list:
    """Best-effort split of a shell command string into simple-command token
    lists, one per ``&&``/``;``/``|``-separated segment.

    Mirrors the tokenizer in ``.claude/hooks/lib/mvn_test_check.py`` so the
    ar-manager-side submission check and the agent-side Bash hook agree on
    what counts as a distinct command within a chained pipeline. Falls back
    to treating the whole string as one segment when it cannot be
    tokenized (e.g. unbalanced quotes) -- fail toward flagging it for a
    human to look at, not toward silently passing it through.
    """
    try:
        tokens = _tokenize(command)
    except ValueError:
        return [[command]]
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


def _unwrap_env(tokens: list) -> list:
    """Strips a leading ``env`` invocation's ``VAR=value`` assignments and
    flags (e.g. ``-i``), returning the wrapped command's own tokens
    unchanged when ``tokens`` is not an ``env`` invocation."""
    if not tokens or tokens[0].rsplit("/", 1)[-1] != "env":
        return tokens
    i = 1
    while i < len(tokens) and (
            _ENV_ASSIGNMENT_PATTERN.match(tokens[i]) or tokens[i].startswith("-")):
        i += 1
    return tokens[i:]


def _shell_dash_c_script(tokens: list):
    """Returns the inline script text when ``tokens`` is a shell interpreter
    invoked as ``sh|bash|zsh|dash|ksh -c "<script>"``, or ``None`` when it
    is not that shape."""
    if len(tokens) < 3 or tokens[0].rsplit("/", 1)[-1] not in _SHELL_INTERPRETERS:
        return None
    if tokens[1] != "-c":
        return None
    return tokens[2]


def _segment_violations(tokens: list) -> list:
    """Return violation reasons for a single shell segment, first unwrapping
    a leading ``env VAR=val ...`` prefix and -- when the segment is a shell
    interpreter invoked as ``sh|bash|zsh|dash|ksh -c "<script>"`` -- recursing
    into the inline script's own segments instead of checking the
    interpreter invocation itself. Without this, ``env mvn test`` or
    ``sh -c 'mvn test'`` would see a first token other than ``mvn``/
    ``pytest`` and be waved through unchecked.
    """
    unwrapped = _unwrap_env(tokens)
    script = _shell_dash_c_script(unwrapped)
    if script is not None:
        violations = []
        for inner in _shell_segments(script):
            violations.extend(_segment_violations(inner))
        return violations
    reason = _maven_segment_violation(unwrapped)
    if reason:
        return [reason]
    reason = _pytest_segment_violation(unwrapped)
    if reason:
        return [reason]
    return []


def _dtest_values(args: list) -> list:
    return [a[len("-Dtest="):] for a in args if a.startswith("-Dtest=")]


def _dtest_is_narrow(value: str) -> bool:
    """True if every comma-separated -Dtest entry is a Class#method selector.

    A bare class name (no ``#``) still runs every test method in that
    class, which is exactly the "whole module's suite" shape this rule
    exists to reject -- see the HARD RULES for this rule: "a bare
    -Dtest=Class also counts as too broad".
    """
    entries = [e for e in value.split(",") if e]
    return bool(entries) and all("#" in e for e in entries)


def _maven_segment_violation(tokens: list) -> str:
    """Return a violation reason for a single ``mvn ...`` command segment,
    or ``""`` when the segment is not Maven, skips tests, or already
    selects an explicit Class#method test."""
    if not tokens:
        return ""
    base = tokens[0].rsplit("/", 1)[-1]
    if base != "mvn":
        return ""
    args = tokens[1:]
    if any(_SKIP_TESTS_PATTERN.match(a) for a in args):
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


def _pytest_segment_violation(tokens: list) -> str:
    """Return a violation reason for a single ``pytest``/``python -m pytest``
    command segment, or ``""`` when it already names explicit node ids."""
    if not tokens:
        return ""
    base = tokens[0].rsplit("/", 1)[-1]
    rest = tokens[1:]
    if base in ("python", "python3") and len(rest) >= 2 and rest[0] == "-m" and rest[1] == "pytest":
        rest = rest[2:]
    elif base in ("pytest", "py.test"):
        pass
    else:
        return ""
    positionals = [a for a in rest if not a.startswith("-")]
    node_ids = [a for a in positionals if "::" in a]
    if positionals and len(node_ids) == len(positionals):
        return ""
    return (
        "pytest command has no explicit node id (file.py::test_name): "
        "\"{}\". This runs an entire file or directory. Pass explicit "
        "node ids, one test per invocation.".format(" ".join(tokens)))


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
    for segment in _shell_segments(command):
        violations.extend(_segment_violations(segment))
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

_TEST_LINT_MIN_LEN = 20

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
    (re.compile(r"\bAR_TEST_GROUPS?\b"),
     "AR_TEST_GROUP/AR_TEST_GROUPS reference"),
]


class _MvnTestSegmentMatcher:
    """Flags an ``mvn test`` mention whose OWN chained-command fragment has no
    Class#method ``-Dtest`` selector, without being fooled by a selector that
    belongs to a different command earlier or later on the same line.

    A single regex with a negative lookahead for ``-Dtest=\\S+#\\S+`` cannot
    express this correctly: the lookahead scans the rest of the whole line,
    so ``mvn test && mvn test -Dtest=Foo#bar`` wrongly exempts the FIRST
    (actually broad) ``mvn test`` because a selector exists later on the
    line, for an unrelated chained command. Splits the line on the chain
    operators (``&&``/``||``/``;``/``|``) by plain text rather than full
    shell tokenization -- prompt lines are English prose, not shell syntax,
    and commonly contain unescaped apostrophes (``don't``, ``module's``)
    that would make a quote-aware tokenizer raise on perfectly ordinary
    text -- and checks each ``mvn test`` mention against only its own
    fragment. Exposes the same ``search(line)`` interface as a compiled
    pattern so it drops into ``_TEST_LINT_PATTERNS`` unchanged.
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _MVN_TEST_PATTERN = re.compile(r"\bmvn\s+test\b", re.IGNORECASE)
    _SELECTOR_PATTERN = re.compile(r"-Dtest=\S+#\S+", re.IGNORECASE)

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line):
            if self._MVN_TEST_PATTERN.search(fragment) \
                    and not self._SELECTOR_PATTERN.search(fragment):
                return True
        return None


_TEST_LINT_PATTERNS.append(
    (_MvnTestSegmentMatcher(), '"mvn test" without a Class#method -Dtest selector'))


class _DTestBroadValueMatcher:
    """Flags a ``-Dtest=<value>`` mention whose comma-separated entries are
    not ALL narrowed to ``Class#method``.

    A single regex with a negative lookahead for ``#`` cannot express this: a
    mixed value like ``-Dtest=Foo,Bar#baz`` (where ``Foo`` alone is broad)
    satisfies a lookahead that only checks whether a ``#`` appears somewhere
    later in the string, because it finds the one in ``Bar#baz``. Exposes the
    same ``search(line)`` interface as a compiled pattern so it drops into
    ``_TEST_LINT_PATTERNS`` unchanged.
    """

    _VALUE_PATTERN = re.compile(r"-Dtest=(\S+)", re.IGNORECASE)

    def search(self, line: str):
        match = self._VALUE_PATTERN.search(line)
        if not match:
            return None
        entries = [e for e in match.group(1).split(",") if e]
        if entries and all("#" in e for e in entries):
            return None
        return match


_TEST_LINT_PATTERNS.append(
    (_DTestBroadValueMatcher(), "-Dtest=<Class> selector without #method"))


def lint_prompt_for_broad_test_instructions(prompt: str) -> list:
    """Scan ``prompt`` for instructions that would have the agent run a
    broad test set.

    Returns a list of ``(line_number, snippet, reason)`` tuples -- one per
    matched line (first matching pattern wins per line). There is no
    bypass flag for this linter; a prompt legitimately quoting the phrase
    must be rewritten instead.
    """
    if len(prompt) < _TEST_LINT_MIN_LEN:
        return []
    violations = []
    for lineno, line in enumerate(prompt.splitlines(), 1):
        for pattern, reason in _TEST_LINT_PATTERNS:
            if pattern.search(line):
                snippet = line.strip()[:120]
                violations.append((lineno, snippet, reason))
                break
    return violations
