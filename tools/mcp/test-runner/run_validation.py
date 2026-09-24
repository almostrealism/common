"""
Validation for ``start_test_run`` arguments, enforcing the "no broad test
runs" rule at this MCP surface. See ``tools/mcp/manager/test_execution_limits.py``
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
    """Raises ValidationError when ``value`` contains a comma.

    ``build_maven_command`` joins a single ``test_classes``/``test_methods``
    entry's class/method text directly into Maven's ``-Dtest`` value with no
    further escaping. A comma embedded in that text (e.g. a ``test_classes``
    entry of ``"FooTest#first,BarTest#second"``, or a ``test_methods`` entry
    whose ``method`` field is ``"first,BarTest#second"``) survives into the
    rendered ``-Dtest`` value unchanged and is read by Maven as multiple
    test patterns in one invocation -- passing the earlier "at most one
    selector" length check while still running more than one test, exactly
    the bypass the one-test-per-invocation rule exists to prevent.
    """
    if "," in value:
        raise ValidationError(
            "{} \"{}\" contains a comma, which Maven reads as a list of "
            "multiple test patterns in a single -Dtest invocation -- "
            "exactly the multi-test bypass the one-test-per-invocation "
            "rule exists to prevent. Call start_test_run once per "
            "test instead.".format(field_description, value)
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
    if len(test_classes) + len(test_methods) > 1:
        raise ValidationError(
            "At most ONE test per invocation is permitted: "
            f"got {len(test_classes)} test_classes and "
            f"{len(test_methods)} test_methods. A -Dtest= value "
            "listing several classes/methods runs them together "
            "in one JVM, which agents and job submitters may "
            "never do. Call start_test_run once per test."
        )
    # A bare class selector (no #method) is only tolerated when JMX
    # monitoring is requested: that is the shape build-vm-crash-prompt.sh
    # generates to investigate a JVM crash, where no surefire report was
    # ever produced and there is no way to attribute the crash to one
    # method. Every other caller must narrow to Class#method, matching the
    # manager and controller validators' identical bare-class rejection.
    jmx_monitoring = arguments.get("jmx_monitoring", False)
    if test_classes:
        _reject_selector_delimiter(test_classes[0], "test_classes entry")
        _reject_wildcard(test_classes[0], "test_classes entry")
        if "#" not in test_classes[0] and not jmx_monitoring:
            raise ValidationError(
                f"test_classes entry \"{test_classes[0]}\" has no "
                "#method selector: build_maven_command emits "
                f"-Dtest={test_classes[0]} for it, which runs every "
                "method in that class -- the same bare-class breadth "
                "the manager and controller validators reject. Pass "
                "\"Class#method\" here, or use test_methods with an "
                "explicit {\"class\": ..., \"method\": ...} entry. (A bare "
                "class is tolerated only with jmx_monitoring:true, for "
                "reproducing a JVM crash that has no method attribution.)"
            )
        if "#" in test_classes[0]:
            class_part, _, method_part = test_classes[0].partition("#")
            if not class_part or not method_part:
                raise ValidationError(
                    f"test_classes entry \"{test_classes[0]}\" has an "
                    "empty class or method component around '#'. Both "
                    "must be non-empty exact names, e.g. "
                    "\"FooTest#testBar\"."
                )
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
    return {
        "timeout_minutes": timeout_minutes,
        "test_classes": test_classes,
        "test_methods": test_methods,
        "jvm_args": jvm_args,
    }
