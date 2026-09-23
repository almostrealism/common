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
        tokens = shlex.split(command, comments=False, posix=True)
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
        reason = _maven_segment_violation(segment)
        if reason:
            violations.append(reason)
            continue
        reason = _pytest_segment_violation(segment)
        if reason:
            violations.append(reason)
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
    (re.compile(r"\bmvn\s+test\b(?!.*-Dtest=\S+#\S+)", re.IGNORECASE),
     '"mvn test" without a Class#method -Dtest selector'),
]


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
