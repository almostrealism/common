"""Prompt linter for broad test-execution instructions.

Scans free-text task prompts for instructions that would have an agent run a
full/whole/entire suite, a module's tests, a CI shard, ``mvn``/pytest/unittest
without a bounded selector, or ``AR_TEST_GROUP``. Extracted from
``execution_limits`` (which still owns the command validator and the shared
``-Dtest`` narrowness rule) to keep both files focused and under the Python
line cap. The dependency is one-way: this module imports the shared helpers
from ``execution_limits``; nothing in ``execution_limits`` depends on it.

There is no exemption or bypass -- see the ``execution_limits`` module
docstring.
"""

import re

from execution_limits import (  # noqa: E402
    _MVN_LAUNCHER_NAMES,
    _MVN_LIFECYCLE_PHASES,
    _MVN_TEST_RUNNING_PHASES,
    _TRAILING_PROSE_PUNCTUATION,
    _classify_skip_value,
    _dtest_is_narrow,
)


_TEST_LINT_PATTERNS = [
    (re.compile(r"\b(full|whole|entire)\s+test\s+suite\b", re.IGNORECASE),
     '"full/whole/entire test suite" phrase'),
    (re.compile(r"\brun\s+(all|every)\s+(of\s+the\s+)?tests?\b", re.IGNORECASE),
     '"run all/every test(s)" phrase'),
    (re.compile(
        r"\b(?:run|execute|test)\s+(?:the\s+)?(?:relevant\s+)?[\w./-]*\s*module(?:'s)?\s+tests?\b",
        re.IGNORECASE),
     '"run the ... module tests" phrase (a whole module\'s test run)'),
    # The same request with the word order reversed ("run the tests for the
    # engine/utils module", "run tests in the module"). Plural "tests" only:
    # "run the test in module X" names a single test, not a suite.
    (re.compile(
        r"\b(?:run|execute)\s+(?:the\s+)?(?:relevant\s+)?tests\s+(?:for|in|of|from)\s+"
        r"(?:the\s+)?[\w./-]*\s*module\b",
        re.IGNORECASE),
     '"run the tests for/in the ... module" phrase (a whole module\'s test run)'),
    # The same request naming the module by its PATH instead of the literal
    # word "module": "run engine/utils tests" (forward) and "run the tests in
    # engine/utils" (reversed). The distinguishing mark of a whole-module run
    # is a module path -- a token containing a "/" -- between a run verb and the
    # plural word "tests". A single test is named as Class#method or
    # file.py::test and never takes this shape, so requiring the slash keeps
    # narrow instructions accepted. Plural "tests" only on the reversed form:
    # "run the test in engine/utils" names a single test, not a suite.
    (re.compile(
        r"\b(?:run|execute|test)\s+(?:the\s+)?(?:relevant\s+)?[\w.-]+/[\w./-]*\s+tests?\b",
        re.IGNORECASE),
     '"run <module path> tests" phrase (a whole module\'s test run)'),
    (re.compile(
        r"\b(?:run|execute)\s+(?:the\s+)?(?:relevant\s+)?tests\s+(?:for|in|of|from)\s+"
        r"(?:the\s+)?[\w.-]+/[\w./-]*",
        re.IGNORECASE),
     '"run the tests for/in <module path>" phrase (a whole module\'s test run)'),
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
        captured = match.group(1)
        if captured is not None:
            captured = _TRAILING_PROSE_PUNCTUATION.sub("", captured)
        value = _classify_skip_value(captured)
    return value


class _MvnTestSegmentMatcher:
    """Flags an ``mvn <test-running-phase>`` mention whose OWN chained-command
    fragment carries no bounding ``-Dtest`` selector, without being fooled by
    a selector that belongs to a different command earlier or later on the
    same line.

    A fragment is exempt only when it names exactly one ``-Dtest`` property
    whose value bounds the run (``_dtest_is_narrow`` -- a class, a few classes,
    or up to the method cap). More than one ``-Dtest`` property is NOT a
    bounding selector: Maven's last-value-wins silently discards all but the
    last, so an earlier narrow-looking value must not exempt the mention -- the
    same last-value-wins reasoning already applied to ``-DskipTests`` below.

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

    The launcher and the phase are matched as two INDEPENDENT patterns
    rather than one pattern requiring the phase immediately after the
    launcher: a prose fragment such as "run mvn -pl engine/utils test" or
    "run mvn clean test" has other words between the launcher and the
    phase, exactly like `` mavenSegmentViolation``'s argument-aware scan of
    every tokenized argument in
    ``flowtree/runtime/.../PostCompletionCommandValidator.java`` (it does
    not require the phase to be the first argument either). A single
    combined regex requiring adjacency missed both forms.
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _MVN_LAUNCHER_PATTERN = re.compile(
        r"\b(?:" + "|".join(re.escape(n) for n in sorted(_MVN_LAUNCHER_NAMES)) + r")\b",
        re.IGNORECASE)
    _MVN_TEST_PHASE_PATTERN = re.compile(
        r"\b(?:" + "|".join(re.escape(p) for p in sorted(_MVN_TEST_RUNNING_PHASES)) + r")\b",
        re.IGNORECASE)
    # Captures the assigned value token when present (a bare mention with no
    # "=value" means true) so `_effective_skip_value_in_text` can resolve
    # repeated mentions in the same fragment to the LAST one's value,
    # matching real Maven -D semantics -- a single regex that only checks
    # whether "=true" (or a bare mention) appears ANYWHERE in the fragment
    # would wrongly exempt "mvn verify -DskipTests=true -DskipTests=false",
    # even though Maven's last-value-wins semantics mean tests still run. The
    # value is captured as a non-space run (not just true|false) so a dynamic
    # value like "-DskipTests=$(printf false)" or "-DskipTests=$SKIP" is seen
    # as an occurrence and classified as non-skipping by _classify_skip_value
    # rather than leaving the bare "-DskipTests" prefix to read as true.
    # The no-value form ends at a property boundary (\b) so the bare
    # "-DskipTests" prefix is not read out of a longer, unrelated property
    # such as "-DskipTestsFoo" -- Maven would treat that as a distinct
    # property and still run tests, so "mvn verify -DskipTestsFoo" must not be
    # exempted as build-only. The "=value" branch still captures the whole
    # value (including a trailing "." that the prompt path tolerates).
    _SKIP_TESTS_TEXT_PATTERN = re.compile(r"-DskipTests(?:=(\S+)|\b)", re.IGNORECASE)
    _MAVEN_TEST_SKIP_TEXT_PATTERN = re.compile(
        r"-Dmaven\.test\.skip(?:=(\S+)|\b)", re.IGNORECASE)

    @staticmethod
    def _has_bounding_selector(fragment: str) -> bool:
        """Whether ``fragment`` names exactly one ``-Dtest`` property whose
        value bounds the run. Asks ``_dtest_is_narrow`` rather than looking for
        a ``#`` so a bare class or a bounded multi-class list exempts the
        mention, agreeing with ``_DTestBroadValueMatcher`` and the command
        validator. More than one ``-Dtest`` property is not bounding: Maven
        uses only the last, so the earlier values are dead."""
        values = _DTestBroadValueMatcher._VALUE_PATTERN.findall(fragment)
        return len(values) == 1 and _dtest_is_narrow(values[0])

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line):
            if not self._MVN_LAUNCHER_PATTERN.search(fragment) \
                    or not self._MVN_TEST_PHASE_PATTERN.search(fragment):
                continue
            if self._has_bounding_selector(fragment):
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

    # Quotation marks, closing brackets and sentence punctuation after the
    # value are prose, not selector; a trailing ``?``/``!`` is kept because
    # both are Surefire selector syntax.
    _VALUE_PATTERN = re.compile(r"-Dtest=(\S+?)[`'\".,;:)\]]*(?=\s|$)",
                                re.IGNORECASE)

    def search(self, line: str):
        matches = list(self._VALUE_PATTERN.finditer(line))
        if not matches:
            return None
        for match in matches:
            if _dtest_is_narrow(match.group(1)):
                continue
            return match
        return None


_TEST_LINT_PATTERNS.append(
    (_DTestBroadValueMatcher(), "-Dtest=<value> not naming exactly one Class#method entry"))


class _UnittestDiscoveryMatcher:
    """Flags a ``python -m unittest``/``python3 -m unittest`` mention that
    either uses ``discover`` or does not name EXACTLY ONE single dotted
    ``module.Class.method`` test id, mirroring
    ``_unittest_segment_violation``'s "exactly one positional" command-line
    check (see its docstring) for free-text prompt instructions. Without
    this, a prompt telling the agent to "run python3 -m unittest discover"
    -- the CI documentation's own example of a forbidden broad run -- passed
    ``lint_prompt_for_broad_test_instructions`` unflagged, even though the
    equivalent Maven/pytest instructions are caught by the patterns above.
    A bare ``search()`` that only checks whether a dotted id is present
    anywhere in the fragment would also accept a prompt naming two dotted
    ids (e.g. "run python -m unittest foo.Bar.test_a bar.Baz.test_b"),
    which still runs both tests in one invocation.

    Like ``_PytestNodeIdMatcher`` and the command-side
    ``_index_of_module_flag``, interpreter option flags are allowed
    between the interpreter and ``-m`` so that "python3 -O -m unittest
    discover" is still recognized rather than waved through because ``-O``
    breaks the literal ``python3 -m`` sequence.
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _UNITTEST_PATTERN = re.compile(
        r"\bpython(?:\d+(?:\.\d+)*)?(?:\s+-\S+)*\s+-m\s+unittest\b", re.IGNORECASE)
    _DISCOVER_PATTERN = re.compile(r"\bdiscover\b", re.IGNORECASE)
    _DOTTED_ID_PATTERN = re.compile(r"\b\w+(?:\.\w+){2,}\b")

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line):
            if not self._UNITTEST_PATTERN.search(fragment):
                continue
            dotted_id_count = len(self._DOTTED_ID_PATTERN.findall(fragment))
            if self._DISCOVER_PATTERN.search(fragment) or dotted_id_count != 1:
                return True
        return None


_TEST_LINT_PATTERNS.append(
    (_UnittestDiscoveryMatcher(),
     '"python -m unittest discover" (or a unittest invocation naming no '
     "single module.Class.method id)"))


class _PytestNodeIdMatcher:
    """Flags a ``pytest``/``py.test``/``python -m pytest`` invocation whose
    chained-command fragment does not name EXACTLY ONE ``file.py::test_name``
    node id, mirroring ``_pytest_segment_violation``'s rule for free-text
    prompt instructions. Without this, a prompt such as "Run pytest
    tools/mcp/manager" passed ``lint_prompt_for_broad_test_instructions``
    when the submission carried no command field for the command validator
    to inspect.

    ``python -m pytest`` is always an invocation. A bare ``pytest`` word is
    only treated as one when a run verb precedes it or an argument-shaped
    token (a flag, a path, a ``.py`` file or a node id) follows it, so prose
    that merely names the tool -- "add a pytest regression test", "explicit
    pytest node ids" -- is not mistaken for an instruction to run it.
    """

    _CHAIN_SPLIT_PATTERN = re.compile(r"&&|\|\||;|\|")
    _MODULE_INVOCATION_PATTERN = re.compile(
        r"\bpython(?:\d+(?:\.\d+)*)?(?:\s+-\S+)*\s+-m\s+pytest\b", re.IGNORECASE)
    _BARE_INVOCATION_PATTERN = re.compile(
        r"(?<![\w.-])(?:(?P<verb>run|execute)\s+)?py\.?test\b(?![.-]\w)",
        re.IGNORECASE)
    _ARGUMENT_SHAPE_PATTERN = re.compile(r"^-|/|\.py\b|::")

    def _arguments_after(self, remainder: str) -> list:
        """The contiguous run of argument-shaped tokens (flags, paths, ``.py``
        files or node ids) that immediately follows the invocation word --
        pytest's own positional/option arguments. Scanning stops at the first
        prose token so surrounding sentence text is not mistaken for an
        argument (e.g. "... test_foo.py::test_bar to verify the fix")."""
        args = []
        for tok in remainder.split():
            if tok.startswith("-") or self._ARGUMENT_SHAPE_PATTERN.search(tok):
                args.append(tok)
            else:
                break
        return args

    def _is_broad(self, args: list) -> bool:
        """Mirror ``_pytest_segment_violation``: broad unless the arguments
        name exactly one positional and that positional is a ``::`` node id.
        A bare positional (a whole file or directory) or more than one
        positional runs more than a single test."""
        positionals = [a for a in args if not a.startswith("-")]
        return not (len(positionals) == 1 and "::" in positionals[0])

    def search(self, line: str):
        for fragment in self._CHAIN_SPLIT_PATTERN.split(line.replace("`", " ")):
            for match in self._MODULE_INVOCATION_PATTERN.finditer(fragment):
                if self._is_broad(self._arguments_after(fragment[match.end():])):
                    return True
            for match in self._BARE_INVOCATION_PATTERN.finditer(fragment):
                args = self._arguments_after(fragment[match.end():])
                if not match.group("verb") and not args:
                    continue
                if self._is_broad(args):
                    return True
        return None


_TEST_LINT_PATTERNS.append(
    (_PytestNodeIdMatcher(),
     "pytest invocation not naming exactly one file.py::test_name node id"))


def lint_prompt_for_broad_test_instructions(prompt: str) -> list:
    """Scan ``prompt`` for instructions that would have the agent run a
    broad test set.

    Returns a list of ``(line_number, snippet, reason)`` tuples -- one per
    matched line (first matching pattern wins per line). A line whose
    command continues onto the next (see ``_continues_onto_next_line``) is
    also linted joined with its continuation lines, and a hit is reported at
    the line the command starts on. There is no
    bypass flag for this linter; a prompt legitimately quoting the phrase
    must be rewritten instead. Only an empty (or whitespace-only) prompt is
    exempt -- a short-but-unambiguous instruction such as ``"run all
    tests"`` or ``"mvn test"`` is well within every pattern's own minimum
    length and must still be scanned.
    """
    if not prompt or not prompt.strip():
        return []
    violations = []
    lines = prompt.splitlines()
    for index, line in enumerate(lines):
        hit = _first_lint_hit(line)
        if hit is None:
            joined = _joined_continuation(lines, index)
            if joined is not None:
                hit = _first_lint_hit(joined)
        if hit is not None:
            violations.append((index + 1, line.strip()[:120], hit))
    return violations


def _first_lint_hit(text: str):
    """Returns the reason of the first ``_TEST_LINT_PATTERNS`` entry that
    matches ``text``, or ``None`` when none does."""
    for pattern, reason in _TEST_LINT_PATTERNS:
        if pattern.search(text):
            return reason
    return None


def _opens_with_maven_argument(line: str) -> bool:
    """Whether ``line`` opens with an argument-shaped token -- a flag (``-pl``,
    ``-DskipTests``) or a Maven lifecycle phase as its first word (``clean``,
    ``install``). A command split across lines continues with one of these; an
    ordinary prose sentence following a line that merely mentions ``mvn``
    continues with a word like ``to``/``and``/``then`` and must NOT be joined."""
    stripped = line.strip()
    if not stripped:
        return False
    first = stripped.split()[0]
    return first.startswith("-") or first.lower() in _MVN_LIFECYCLE_PHASES


def _continues_onto_next_line(text: str, next_line: str) -> bool:
    """Whether a command in ``text`` continues onto ``next_line``: the text
    ends with a shell ``\\`` continuation, or its last chained fragment names a
    Maven launcher but no lifecycle phase yet AND ``next_line`` opens with an
    argument-shaped token (``Run mvn`` followed by ``clean install -pl
    engine/utils``). The ``next_line`` guard keeps prose that merely mentions a
    launcher without a phase -- ``We build with mvn.`` followed by ``Then verify
    the fix.`` -- from being joined into a fabricated ``mvn ... verify`` command
    and falsely flagged. pytest and unittest need no joining -- an invocation
    left with no target on its own line is already flagged as broad."""
    if text.rstrip().endswith("\\"):
        return True
    fragment = _MvnTestSegmentMatcher._CHAIN_SPLIT_PATTERN.split(text)[-1]
    if not _MvnTestSegmentMatcher._MVN_LAUNCHER_PATTERN.search(fragment) \
            or _MvnTestSegmentMatcher._MVN_TEST_PHASE_PATTERN.search(fragment):
        return False
    return _opens_with_maven_argument(next_line)


def _joined_continuation(lines: list, index: int):
    """Joins ``lines[index]`` with the lines its command continues onto (see
    ``_continues_onto_next_line``), stopping at a blank line or the end of
    the prompt, so a command split across lines is linted as one. Returns
    ``None`` when the line does not continue."""
    text = lines[index]
    nxt = index + 1
    while nxt < len(lines) and lines[nxt].strip() \
            and _continues_onto_next_line(text, lines[nxt]):
        text = text.rstrip().rstrip("\\") + " " + lines[nxt].strip()
        nxt += 1
    return text if nxt > index + 1 else None
