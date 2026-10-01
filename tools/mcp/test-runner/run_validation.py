"""
Validation for ``start_test_run`` arguments, enforcing the "no broad test
runs" rule at this MCP surface. See ``tools/mcp/manager/execution_limits.py``
for the sibling rule enforced at ar-manager job submission, and CLAUDE.md's
"Test Execution Limits" section for the full policy. There is no bypass for
any of these checks.
"""

import re

# Matches an AR_TEST_GROUP/AR_TEST_GROUPS reference anywhere in a jvm_args
# entry -- the same TestDepthRule shard properties test_group/test_groups
# are rejected for, reachable through jvm_args's raw Maven argLine instead.
# No leading word-boundary assertion: the real shard invocation shape is
# "-DAR_TEST_GROUP=2", where "AR_TEST_GROUP" is glued directly to the "-D"
# property prefix with no boundary between "D" and "A" (both word
# characters) -- a leading \b would never match that form.
AR_TEST_GROUP_PATTERN = re.compile(r"AR_TEST_GROUPS?\b")


class ValidationError(Exception):
    """Raised by validate_start_test_run_arguments with a ready-to-serialize,
    human-readable error message."""

    def __init__(self, error: str):
        super().__init__(error)
        self.error = error


_WILDCARD_CHARS = frozenset("*?")

# The halves of a single exact -Dtest selector: an (optionally
# package-qualified, optionally nested with ``$``) Java class name and a Java
# method name. Mirrors _DTEST_CLASS_NAME/_DTEST_METHOD_NAME in the manager's
# execution_limits.py and DTEST_CLASS_NAME/DTEST_METHOD_NAME in the
# controller's PostCompletionCommandValidator.java.
_CLASS_NAME = re.compile(
    r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*")
_METHOD_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")

# How much one invocation may select. Mirrors _MAX_TEST_CLASSES and
# _MAX_TEST_METHODS in the manager's execution_limits.py, and MAX_TEST_CLASSES
# and MAX_TEST_METHODS in the controller's PostCompletionCommandValidator.java.
# A class carries an unknown number of cases, so the method cap bounds only the
# selections that name a method; the class cap bounds the rest.
_MAX_TEST_CLASSES = 5
_MAX_TEST_METHODS = 40


def _reject_inexact_name(value: str, pattern, field_description: str) -> None:
    """Raises ValidationError unless ``value`` is an exact Java name matching
    ``pattern``.

    The wildcard and separator checks name specific Surefire constructs, but
    Surefire's selector syntax is wider than those: ``!FooTest#bar`` negates
    a pattern (running every other test) and ``%regex[Foo|Bar]#bar`` selects
    by regular expression. Requiring an exact identifier closes every such
    construct at once, including any the other checks do not enumerate.
    """
    if not pattern.fullmatch(value):
        raise ValidationError(
            "{} \"{}\" is not an exact Java name. Surefire reads other "
            "characters as selector syntax (e.g. '!' negation or a "
            "%regex[...] pattern) that can run more than one test in a "
            "single -Dtest invocation. Name exactly one test class and "
            "method.".format(field_description, value)
        )


def _reject_wildcard(value: str, field_description: str) -> None:
    """Raises ValidationError when ``value`` contains a Surefire wildcard.

    Surefire treats ``*`` and ``?`` in a ``-Dtest`` pattern as wildcards, so
    a selector such as ``FooTest#test*`` or ``Foo*#bar`` can match and run
    several methods/classes in a single invocation even though it passes
    the "exactly one selector" and "has a #" checks -- exactly the
    multi-test bypass the one-test-per-invocation rule exists to prevent.
    """
    if any(c in _WILDCARD_CHARS for c in value):
        raise ValidationError(
            "{} \"{}\" contains a Surefire wildcard character (* or ?), "
            "which can match multiple classes/methods in a single -Dtest "
            "invocation -- exactly the multi-test bypass the "
            "one-test-per-invocation rule exists to prevent. Call "
            "start_test_run once per test, naming it exactly.".format(
                field_description, value)
        )


def _reject_selector_delimiter(value: str, field_description: str) -> None:
    """Raises ValidationError when ``value`` contains a Surefire selector-list
    separator (a comma or a ``+``).

    ``build_maven_command`` joins a single ``test_classes``/``test_methods``
    entry's class/method text directly into Maven's ``-Dtest`` value with no
    further escaping. A comma embedded in that text (e.g. a ``test_classes``
    entry of ``"FooTest#first,BarTest#second"``, or a ``test_methods`` entry
    whose ``method`` field is ``"first,BarTest#second"``) survives into the
    rendered ``-Dtest`` value unchanged and is read by Maven as multiple
    test patterns in one invocation. Surefire also treats ``+`` as a
    method-list separator (``Class#method1+method2`` -- the form the
    repository's own CI uses in ``.github/workflows/analysis.yaml``), so a
    ``method`` field of ``"first+second"`` runs both methods in one
    invocation just the same. Either passes the earlier "at most one
    selector" length check while still running more than one test, exactly
    the bypass the one-test-per-invocation rule exists to prevent.
    """
    for separator, description in ((",", "a comma"), ("+", "a '+'")):
        if separator in value:
            raise ValidationError(
                "{} \"{}\" contains {}, which Maven/Surefire reads as a list "
                "of multiple test patterns in a single -Dtest invocation -- "
                "exactly the multi-test bypass the one-test-per-invocation "
                "rule exists to prevent. Call start_test_run once per "
                "test instead.".format(field_description, value, description)
            )


def validate_start_test_run_arguments(
        arguments: dict, default_timeout: int, max_timeout_minutes: int) -> dict:
    """Validates ``start_test_run``'s raw MCP arguments against the "no
    broad test runs" rule and normalizes them for ``RunConfig``.

    Args:
        arguments: The raw MCP tool arguments.
        default_timeout: Timeout in minutes to use when the caller omits
            ``timeout_minutes``.
        max_timeout_minutes: The hard ceiling on ``timeout_minutes``.

    Returns:
        A dict with normalized ``timeout_minutes``, ``test_classes``,
        ``test_methods``, and ``jvm_args`` values, ready to build a
        ``RunConfig``.

    Raises:
        ValidationError: with a human-readable ``error`` message describing
            the first violation found. There is no bypass for any check
            here -- see the module docstring.
    """
    if arguments.get("test_group") is not None or arguments.get("test_groups") is not None:
        raise ValidationError(
            "test_group/test_groups is not permitted: it reproduces a "
            "whole CI shard (every test class hashing to the group, run "
            "together in one JVM), which agents and job submitters "
            "may never run. Pass test_classes or test_methods to "
            "select the specific test(s) you need instead."
        )
    jvm_args = arguments.get("jvm_args", [])
    if any(AR_TEST_GROUP_PATTERN.search(str(arg)) for arg in jvm_args):
        raise ValidationError(
            "jvm_args references AR_TEST_GROUP/AR_TEST_GROUPS: this "
            "sets the same TestDepthRule shard properties test_group/"
            "test_groups are rejected for, just through Maven's "
            "argLine instead. CI-shard partitioning is reserved for "
            "the CI workflow matrix; agents and job submitters may "
            "never run a shard through any argument."
        )
    timeout_minutes = arguments.get("timeout_minutes", default_timeout)
    if timeout_minutes is None:
        timeout_minutes = default_timeout
    if timeout_minutes <= 0:
        raise ValidationError(
            f"timeout_minutes={timeout_minutes} must be positive. "
            "A zero or negative value arms no timer downstream, "
            "silently bypassing the 40-minute ceiling this check "
            "exists to enforce."
        )
    if timeout_minutes > max_timeout_minutes:
        raise ValidationError(
            f"timeout_minutes={timeout_minutes} exceeds the maximum "
            f"of {max_timeout_minutes} minutes (2400s). Broad "
            "verification belongs to CI; a test invocation here "
            "must be narrow and fast."
        )
    test_classes = arguments.get("test_classes", [])
    test_methods = arguments.get("test_methods", [])
    if not test_classes and not test_methods:
        raise ValidationError(
            "test_classes or test_methods is required. With "
            "neither set, this call falls through to "
            "'mvn test -pl <module>', running the module's whole "
            "test suite -- exactly the broad run agents and job "
            "submitters may never start. Pass test_classes or "
            "test_methods to select the specific test(s) you need."
        )
    # A named class is bounded by its own methods, and a handful of named
    # classes is still a bounded run, so both are allowed. What stays refused
    # is a selector with no ceiling: no selector at all (handled above), a
    # wildcard, or more classes/methods than the caps. Mirrors
    # _MAX_TEST_CLASSES/_MAX_TEST_METHODS in
    # tools/mcp/manager/execution_limits.py and MAX_TEST_CLASSES/
    # MAX_TEST_METHODS in PostCompletionCommandValidator.java.
    selected_classes = set()
    selected_methods = 0

    for entry in test_classes:
        _reject_selector_delimiter(entry, "test_classes entry")
        _reject_wildcard(entry, "test_classes entry")

        if "#" in entry:
            class_part, _, method_part = entry.partition("#")
            if not class_part or not method_part:
                raise ValidationError(
                    f"test_classes entry \"{entry}\" has an "
                    "empty class or method component around '#'. Both "
                    "must be non-empty exact names, e.g. "
                    "\"FooTest#testBar\"."
                )
            _reject_inexact_name(class_part, _CLASS_NAME, "test_classes class part")
            _reject_inexact_name(method_part, _METHOD_NAME, "test_classes method part")
            selected_methods += 1
        else:
            class_part = entry
            _reject_inexact_name(class_part, _CLASS_NAME, "test_classes entry")

        selected_classes.add(class_part)
    for entry in test_methods:
        if not isinstance(entry, dict) or not entry.get("class") or not entry.get("method"):
            raise ValidationError(
                f"test_methods entry {entry!r} must be an object "
                "with non-empty \"class\" and \"method\" fields, "
                "e.g. {\"class\": \"FooTest\", \"method\": \"bar\"}."
            )
        _reject_selector_delimiter(entry["class"], "test_methods class field")
        _reject_selector_delimiter(entry["method"], "test_methods method field")
        _reject_wildcard(entry["class"], "test_methods class field")
        _reject_wildcard(entry["method"], "test_methods method field")
        _reject_inexact_name(entry["class"], _CLASS_NAME, "test_methods class field")
        _reject_inexact_name(entry["method"], _METHOD_NAME, "test_methods method field")

        selected_classes.add(entry["class"])
        selected_methods += 1

    if len(selected_classes) > _MAX_TEST_CLASSES:
        raise ValidationError(
            f"{len(selected_classes)} test classes were selected, more than "
            f"the {_MAX_TEST_CLASSES} one invocation may run. Selecting a "
            "class, or a few related classes, is a bounded run; beyond that "
            "it approaches the module's whole suite, which is the pipeline's "
            "job. Split the selection across calls."
        )

    if selected_methods > _MAX_TEST_METHODS:
        raise ValidationError(
            f"{selected_methods} test methods were selected, more than the "
            f"{_MAX_TEST_METHODS} one invocation may run. Split the "
            "selection across calls, or name the classes instead of every "
            "method in them."
        )

    return {
        "timeout_minutes": timeout_minutes,
        "test_classes": test_classes,
        "test_methods": test_methods,
        "jvm_args": jvm_args,
    }
