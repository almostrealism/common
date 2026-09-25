"""Tests for tools/mcp/manager/execution_limits.py.

Pure unit tests against the validation/lint functions themselves, without
going through the MCP tool layer. See test_workstream_submit.py for the
integration-level tests that prove workstream_submit_task actually wires
these checks in.
"""

import os
import sys
import unittest

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from execution_limits import (  # noqa: E402
    POST_COMPLETION_MAX_TIMEOUT_SECONDS,
    lint_prompt_for_broad_test_instructions,
    validate_post_completion_command,
    validate_post_completion_timeout,
)


class TestValidatePostCompletionCommandAccepted(unittest.TestCase):
    """Commands that must be accepted (no violations)."""

    def test_empty_command(self):
        self.assertEqual([], validate_post_completion_command(""))

    def test_single_method_maven_selector(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_mvnw_launcher_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "./mvnw -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_maven_install_skiptests_is_a_build_not_a_test_run(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn install -q -DskipTests -pl engine/utils -am"))

    def test_maven_verify_with_skiptests(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn verify -Dmaven.test.skip=true"))

    def test_maven_compile_no_test_phase(self):
        self.assertEqual([], validate_post_completion_command("mvn clean compile"))

    def test_pytest_single_node_id(self):
        self.assertEqual([], validate_post_completion_command(
            "cd tools/mcp/manager && pytest test_secrets.py::test_render"))

    def test_python_module_pytest_node_id(self):
        self.assertEqual([], validate_post_completion_command(
            "python3 -m pytest tools/mcp/manager/test_server.py::TestFoo::test_bar"))

    def test_custom_script(self):
        self.assertEqual([], validate_post_completion_command("bash scripts/verify-foo.sh"))


class TestValidatePostCompletionCommandRejected(unittest.TestCase):
    """Commands that must be rejected, with a clear reason."""

    def test_incident_command_is_rejected(self):
        # The exact command from the 2026-09-16 incident described in the
        # module docstring: a full install then a full CI shard.
        command = (
            "mvn install -q -DskipTests -pl engine/utils -am && "
            "mvn test -pl engine/utils -DAR_TEST_GROUP=2 -DAR_TEST_GROUPS=8"
        )
        violations = validate_post_completion_command(command)
        self.assertTrue(violations, "incident command must be rejected")
        joined = " ".join(violations)
        self.assertIn("AR_TEST_GROUP", joined)

    def test_mvn_test_with_no_selector_rejected(self):
        violations = validate_post_completion_command("mvn test -pl engine/utils")
        self.assertTrue(violations)
        self.assertIn("no -Dtest selector", " ".join(violations))

    def test_mvn_verify_with_no_selector_rejected(self):
        violations = validate_post_completion_command("mvn verify")
        self.assertTrue(violations)

    def test_mvn_install_with_no_skiptests_and_no_selector_rejected(self):
        violations = validate_post_completion_command("mvn install -pl engine/utils")
        self.assertTrue(violations)

    def test_bare_class_dtest_selector_rejected(self):
        violations = validate_post_completion_command(
            "mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest")
        self.assertTrue(violations, "a bare -Dtest=Class selector must be rejected")
        self.assertIn("Class#method", " ".join(violations))

    def test_mixed_narrow_and_broad_dtest_selector_rejected(self):
        violations = validate_post_completion_command(
            "mvn test -Dtest=FooTest#bar,BazTest")
        self.assertTrue(violations)

    def test_ar_test_group_alone_rejected_even_without_test_phase_keyword(self):
        violations = validate_post_completion_command(
            "mvn package -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4")
        self.assertTrue(violations)
        self.assertIn("AR_TEST_GROUP", " ".join(violations))

    def test_ar_test_groups_referenced_directly_as_env_assignment(self):
        violations = validate_post_completion_command(
            "AR_TEST_GROUP=2 AR_TEST_GROUPS=8 mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_pytest_on_directory_rejected(self):
        violations = validate_post_completion_command("pytest tools/mcp/manager")
        self.assertTrue(violations)
        self.assertIn("node id", " ".join(violations))

    def test_pytest_on_whole_file_rejected(self):
        violations = validate_post_completion_command("pytest tools/mcp/manager/test_server.py")
        self.assertTrue(violations)

    def test_pytest_with_no_args_rejected(self):
        violations = validate_post_completion_command("pytest")
        self.assertTrue(violations)

    def test_pytest_dash_k_keyword_selection_rejected(self):
        # -k selects by keyword pattern, not an explicit node id -- still broad.
        violations = validate_post_completion_command(
            "pytest tools/mcp/manager -k test_render")
        self.assertTrue(violations)

    def test_pytest_mixed_directory_and_node_id_rejected(self):
        # A bare directory alongside a real node id still runs the whole
        # directory; only checking that ANY positional has "::" let this
        # through.
        violations = validate_post_completion_command(
            "pytest tests/ test_foo.py::test_bar")
        self.assertTrue(violations, "a bare directory alongside a node id must still be rejected")
        self.assertIn("node id", " ".join(violations))

    def test_skip_tests_equals_false_is_not_treated_as_skip(self):
        violations = validate_post_completion_command(
            "mvn install -DskipTests=false -pl engine/utils")
        self.assertTrue(violations, "-DskipTests=false explicitly re-enables tests")

    def test_maven_test_skip_equals_false_is_not_treated_as_skip(self):
        violations = validate_post_completion_command(
            "mvn verify -Dmaven.test.skip=false")
        self.assertTrue(violations, "-Dmaven.test.skip=false explicitly re-enables tests")

    def test_env_wrapped_maven_test_rejected(self):
        # "env" as a wrapper must not hide the wrapped mvn invocation --
        # only the literal first token "mvn" was previously recognized.
        violations = validate_post_completion_command("env mvn test -pl engine/utils")
        self.assertTrue(violations, "env mvn test must be rejected like a direct mvn test")

    def test_env_with_assignment_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "env FOO=bar mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_sh_dash_c_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "sh -c 'mvn test -pl engine/utils'")
        self.assertTrue(violations, "sh -c 'mvn test' must be rejected like a direct mvn test")

    def test_bash_dash_c_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "bash -c 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'"))

    def test_sh_dash_c_wrapped_pytest_on_directory_rejected(self):
        violations = validate_post_completion_command("sh -c 'pytest tools/mcp/manager'")
        self.assertTrue(violations)

    def test_maven_test_glued_to_shell_operator_rejected(self):
        # shlex.split alone (no punctuation_chars) would swallow "&&" into
        # the "test" token as "test&&echo", hiding this from phase detection.
        violations = validate_post_completion_command("mvn test&&echo ok")
        self.assertTrue(violations, "mvn test glued to && must still be detected")

    def test_maven_test_glued_to_shell_operator_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn test -Dtest=Foo#bar&&echo ok"))

    def test_command_wrapped_maven_test_rejected(self):
        # "command" is a shell builtin that runs its argument as a normal
        # command, bypassing a shell function/alias of the same name -- it
        # must not hide the wrapped mvn invocation either.
        violations = validate_post_completion_command(
            "command mvn test -pl engine/utils")
        self.assertTrue(violations, "command mvn test must be rejected like a direct mvn test")

    def test_sudo_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command("sudo mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_command_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "command mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_sudo_env_wrapped_maven_test_rejected(self):
        # Prefixes chain: sudo wraps env, env wraps the real command.
        violations = validate_post_completion_command(
            "sudo env FOO=bar mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_bare_assignment_wrapped_maven_test_rejected(self):
        # A bare "VAR=value" prefix with no "env" token is exactly as valid
        # to the shell as one preceded by "env" -- it must be stripped the
        # same way, not waved through because the first token isn't
        # literally "mvn"/"env".
        violations = validate_post_completion_command(
            "FOO=bar mvn test -pl engine/utils")
        self.assertTrue(violations, "a bare VAR=value prefix must not bypass detection")

    def test_multiple_bare_assignments_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "FOO=bar BAZ=qux mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_bare_assignment_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "FOO=bar mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_sudo_bare_assignment_wrapped_maven_test_rejected(self):
        # Prefixes chain: sudo wraps a bare assignment, which wraps the real command.
        violations = validate_post_completion_command(
            "sudo FOO=bar mvn test -pl engine/utils")
        self.assertTrue(violations)

    def test_newline_separated_broad_mvn_after_narrow_one_rejected(self):
        # shlex.shlex always treats "\n" as whitespace -- a separator
        # consumed between tokens, never emitted as its own token -- so a
        # multi-line command would previously tokenize as ONE segment,
        # letting the narrow selector on the first line mask the second
        # line's broad invocation entirely.
        violations = validate_post_completion_command(
            "mvn test -Dtest=Foo#bar\nmvn test -pl engine/utils")
        self.assertTrue(violations, "the second line's broad mvn test must still be flagged")

    def test_newline_separated_narrow_commands_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn test -Dtest=Foo#bar\npytest tests/test_foo.py::test_bar"))

    def test_nice_with_operand_option_wrapped_maven_test_rejected(self):
        # "nice -n 10 mvn test" previously stripped only "nice", leaving
        # "-n" as the apparent command -- never reaching "mvn" at all.
        violations = validate_post_completion_command(
            "nice -n 10 mvn test -pl engine/utils")
        self.assertTrue(violations, "nice -n 10 mvn test must be rejected like a direct mvn test")

    def test_sudo_with_operand_option_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "sudo -u user mvn test -pl engine/utils")
        self.assertTrue(violations, "sudo -u user mvn test must be rejected like a direct mvn test")

    def test_nice_with_operand_option_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "nice -n 10 mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_sudo_with_operand_option_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "sudo -u user mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo"))

    def test_mvnw_launcher_with_no_selector_rejected(self):
        # "./mvnw test" must be rejected like a direct "mvn test" -- without
        # recognizing "mvnw" as a Maven launcher, it would be waved through
        # as an unrecognized custom command.
        violations = validate_post_completion_command("./mvnw test -pl engine/utils")
        self.assertTrue(violations, "./mvnw test must be rejected like a direct mvn test")

    def test_multiple_method_dtest_selector_rejected(self):
        # A -Dtest value naming more than one Class#method entry still runs
        # multiple tests in a single Maven invocation, contradicting the
        # "one test per invocation" rule -- even though every individual
        # entry is itself narrow.
        violations = validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=FooTest#bar,FooTest#baz")
        self.assertTrue(violations,
                         "a -Dtest value naming multiple methods must still be rejected")

    def test_pytest_multiple_node_ids_rejected(self):
        # Even when every positional is an explicit node id, pytest still
        # runs them together in a single invocation -- "one test per
        # invocation" means exactly one node id, not "every positional
        # happens to be narrow".
        violations = validate_post_completion_command(
            "pytest test_foo.py::test_bar test_baz.py::test_qux")
        self.assertTrue(violations, "multiple pytest node ids in one invocation must be rejected")

    def test_mvnw_cmd_launcher_with_no_selector_rejected(self):
        # The Windows Maven Wrapper batch launcher is "mvnw.cmd", not
        # "mvn.cmd" -- without recognizing it explicitly, base-name
        # extraction (which strips leading path components only, never
        # file extensions) would leave it unrecognized as Maven.
        violations = validate_post_completion_command("./mvnw.cmd test -pl engine/utils")
        self.assertTrue(violations, "./mvnw.cmd test must be rejected like a direct mvn test")

    def test_bash_dash_ec_combined_option_wrapped_maven_test_rejected(self):
        # "bash -ec" combines "-e" (errexit) and "-c" (inline script) in one
        # token -- only recognizing a literal "-c" token would let this
        # form's embedded broad Maven run slip through unchecked.
        violations = validate_post_completion_command(
            "bash -ec 'mvn test -pl engine/utils'")
        self.assertTrue(violations, "bash -ec 'mvn test' must be rejected like a direct mvn test")

    def test_bash_dash_e_dash_c_separated_option_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "bash -e -c 'mvn test -pl engine/utils'")
        self.assertTrue(violations, "bash -e -c 'mvn test' must be rejected like a direct mvn test")

    def test_bash_dash_ec_combined_option_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "bash -ec 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'"))

    def test_later_skip_tests_false_overrides_earlier_skip_tests_flag(self):
        # Maven system properties set via repeated -D take the LAST
        # occurrence's value: -DskipTests followed by -DskipTests=false
        # actually RUNS the tests, but an early-return on the first
        # skip-shaped flag would wrongly treat this as build-only.
        violations = validate_post_completion_command(
            "mvn test -DskipTests -DskipTests=false -pl engine/utils")
        self.assertTrue(violations,
                         "a later -DskipTests=false must override an earlier bare -DskipTests")

    def test_later_maven_test_skip_false_overrides_earlier_true(self):
        violations = validate_post_completion_command(
            "mvn test -Dmaven.test.skip=true -Dmaven.test.skip=false -pl engine/utils")
        self.assertTrue(violations,
                         "a later -Dmaven.test.skip=false must override an earlier true value")

    def test_later_skip_tests_true_overrides_earlier_false(self):
        # Same last-value-wins rule in the other direction: a later bare
        # -DskipTests (implicitly true) after an earlier "=false" must
        # still be treated as build-only.
        self.assertEqual([], validate_post_completion_command(
            "mvn install -DskipTests=false -DskipTests -pl engine/utils"))

    def test_unparseable_command_is_rejected(self):
        # An unbalanced quote fails _tokenize; the whole line must become a
        # violation in its own right rather than being routed through the
        # normal mvn/pytest first-token checks, where the entire raw text
        # (never equal to "mvn" or "pytest") would silently pass.
        violations = validate_post_completion_command(
            "mvn test -pl engine/utils '")
        self.assertTrue(violations, "an unparseable command must not be silently accepted")

    def test_env_dash_s_split_string_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "env -S 'mvn test -pl engine/utils'")
        self.assertTrue(violations, "env -S 'mvn test' must be rejected like a direct mvn test")

    def test_env_dash_s_glued_split_string_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "env -S'mvn test -pl engine/utils'")
        self.assertTrue(violations)

    def test_env_dash_dash_split_string_equals_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "env --split-string='mvn test -pl engine/utils'")
        self.assertTrue(violations)

    def test_env_dash_s_split_string_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "env -S 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'"))

    def test_if_then_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "if true; then mvn test -pl engine/utils; fi")
        self.assertTrue(violations, "if/then must not hide a broad mvn test")

    def test_for_do_wrapped_maven_test_rejected(self):
        violations = validate_post_completion_command(
            "for i in 1 2 3; do mvn test -pl engine/utils; done")
        self.assertTrue(violations, "for/do must not hide a broad mvn test")

    def test_if_then_wrapped_maven_test_with_selector_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "if true; then mvn -pl flowtree/runtime test "
            "-Dtest=NotifierRegistryTest#testFoo; fi"))

    def test_wildcard_method_dtest_selector_rejected(self):
        violations = validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=FooTest#test*")
        self.assertTrue(violations, "a wildcard method selector must be rejected")

    def test_wildcard_class_dtest_selector_rejected(self):
        violations = validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=Foo*#bar")
        self.assertTrue(violations, "a wildcard class selector must be rejected")

    def test_question_mark_wildcard_dtest_selector_rejected(self):
        violations = validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=FooTest#test?")
        self.assertTrue(violations)

    def test_unittest_discover_rejected(self):
        violations = validate_post_completion_command("python3 -m unittest discover")
        self.assertTrue(violations, "unittest discover must be rejected")

    def test_unittest_with_no_args_rejected(self):
        violations = validate_post_completion_command("python -m unittest")
        self.assertTrue(violations)

    def test_unittest_bare_module_rejected(self):
        violations = validate_post_completion_command("python3 -m unittest tests.test_foo")
        self.assertTrue(violations)

    def test_unittest_single_method_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "python3 -m unittest tests.test_foo.FooTest.test_bar"))

    def test_bare_sh_with_no_script_argument_rejected(self):
        violations = validate_post_completion_command(
            "printf 'mvn test -pl engine/utils' | sh")
        self.assertTrue(violations, "a bare sh reading a script from stdin must be rejected")

    def test_sh_with_script_file_argument_accepted(self):
        self.assertEqual([], validate_post_completion_command("bash scripts/verify-foo.sh"))

    def test_python_dash_o_flag_before_module_pytest_rejected(self):
        violations = validate_post_completion_command("python3 -O -m pytest tests/")
        self.assertTrue(violations, "an interpreter option before -m must not hide pytest")

    def test_python_dash_o_flag_before_module_pytest_with_node_id_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "python3 -O -m pytest tests/test_foo.py::test_bar"))

    def test_python_dash_b_flag_before_module_unittest_discover_rejected(self):
        violations = validate_post_completion_command("python3 -B -m unittest discover")
        self.assertTrue(violations, "an interpreter option before -m must not hide unittest discover")

    def test_dollar_paren_substitution_in_command_position_rejected(self):
        violations = validate_post_completion_command("$(printf mvn) test -pl engine/utils")
        self.assertTrue(violations)
        self.assertIn("command substitution", violations[0])

    def test_backtick_substitution_in_command_position_rejected(self):
        violations = validate_post_completion_command("`printf mvn` test -pl engine/utils")
        self.assertTrue(violations)

    def test_command_substitution_in_maven_phase_argument_rejected(self):
        # No literal "test" token is present for the phase check to see, but
        # the shell still substitutes it and runs the whole module suite.
        violations = validate_post_completion_command(
            "mvn $(printf test) -pl engine/utils")
        self.assertTrue(violations, "a substitution constructing the Maven phase must be rejected")

    def test_command_substitution_in_dtest_argument_rejected(self):
        violations = validate_post_completion_command(
            "mvn test -pl engine/utils -Dtest=$(printf FooTest#testBar)")
        self.assertTrue(violations)

    def test_command_substitution_in_pytest_argument_rejected(self):
        violations = validate_post_completion_command(
            "pytest $(printf test_foo.py::test_bar)")
        self.assertTrue(violations)

    def test_command_substitution_in_unittest_argument_rejected(self):
        violations = validate_post_completion_command(
            "python3 -m unittest $(printf pkg.FooTest.test_bar)")
        self.assertTrue(violations)

    def test_shell_variable_indirection_rejected(self):
        violations = validate_post_completion_command(
            "cmd='mvn test -pl engine/utils'; $cmd")
        self.assertTrue(violations, "a variable assigned a broad command and referenced must be resolved")

    def test_shell_variable_indirection_brace_form_rejected(self):
        violations = validate_post_completion_command(
            "cmd='mvn test -pl engine/utils'; ${cmd}")
        self.assertTrue(violations)

    def test_shell_variable_indirection_to_narrow_command_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "cmd='mvn test -pl engine/utils -Dtest=FooTest#testBar'; $cmd"))

    def test_unresolved_variable_reference_does_not_raise(self):
        self.assertEqual([], validate_post_completion_command("$undefined"))

    def test_surefire_plus_method_separator_dtest_selector_rejected(self):
        # Surefire treats "+" as a method-list separator, so
        # FooTest#first+second runs two methods in one invocation despite
        # naming one comma-free entry with a single "#".
        violations = validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=FooTest#first+second")
        self.assertTrue(violations, "a '+' method-list separator must be rejected")

    def test_shell_comment_hiding_broad_maven_command_rejected(self):
        # The commented-out -Dtest must not make the broad "mvn test" look
        # narrow: the shell runs only "mvn test".
        violations = validate_post_completion_command(
            "mvn test # -Dtest=FooTest#testBar")
        self.assertTrue(violations, "a commented-out selector must not exempt a broad mvn test")

    def test_quoted_hash_is_not_treated_as_a_comment(self):
        # A "#" inside a Class#method selector (mid-word, unquoted) is a real
        # selector, not a comment, so this narrow command is still accepted.
        self.assertEqual([], validate_post_completion_command(
            "mvn test -Dtest=FooTest#testBar"))

    def test_maven_phase_from_parameter_expansion_rejected(self):
        # $MAVEN_GOAL has no literal "test" token for the phase check, but the
        # shell expands it at run time and may run the whole module suite.
        violations = validate_post_completion_command(
            "mvn $MAVEN_GOAL -pl engine/utils")
        self.assertTrue(violations, "a Maven phase built from a variable expansion must be rejected")

    def test_dtest_selector_from_parameter_expansion_rejected(self):
        violations = validate_post_completion_command(
            "mvn test -pl engine/utils -Dtest=$CLASS#$METHOD")
        self.assertTrue(violations, "a -Dtest selector built from a variable expansion must be rejected")

    def test_parameter_expansion_in_non_test_property_value_accepted(self):
        # A variable expansion in an ordinary -D property value (not a phase
        # or a -Dtest selector) is benign and must not be rejected.
        self.assertEqual([], validate_post_completion_command(
            "mvn install -DskipTests -DAR_HARDWARE_LIBS=$TEMP/ar_libs"))


class TestValidatePostCompletionTimeout(unittest.TestCase):

    def test_zero_uses_default_and_is_accepted(self):
        self.assertEqual("", validate_post_completion_timeout(0))

    def test_within_limit_accepted(self):
        self.assertEqual("", validate_post_completion_timeout(POST_COMPLETION_MAX_TIMEOUT_SECONDS))

    def test_exceeds_limit_rejected(self):
        err = validate_post_completion_timeout(3600)
        self.assertTrue(err)
        self.assertIn("2400", err)

    def test_incident_timeout_of_3600_is_rejected(self):
        err = validate_post_completion_timeout(3600)
        self.assertTrue(err, "the exact 3600s timeout from the incident must be rejected")


class TestLintPromptForBroadTestInstructions(unittest.TestCase):

    def test_empty_prompt_skipped(self):
        self.assertEqual([], lint_prompt_for_broad_test_instructions(""))

    def test_short_narrow_prompt_not_flagged(self):
        # Short, but scanned like any other prompt -- "fix it" contains no
        # broad-instruction phrase, so it is correctly not flagged.
        self.assertEqual([], lint_prompt_for_broad_test_instructions("fix it"))

    def test_short_broad_prompt_rejected(self):
        # A length fast-path previously let a short-but-unambiguous
        # instruction bypass every pattern below 20 characters -- both of
        # these are shorter than that and must still be flagged.
        self.assertTrue(lint_prompt_for_broad_test_instructions("run all tests"))
        self.assertTrue(lint_prompt_for_broad_test_instructions("mvn test"))

    def test_narrow_instruction_not_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Run mvn test -Dtest=FooTest#testBar and fix the failure you find.")
        self.assertEqual([], hits)

    def test_full_test_suite_phrase_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Before declaring done, run the full test suite to be sure.")
        self.assertTrue(hits)

    def test_whole_test_suite_phrase_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run the whole test suite before finishing up.")
        self.assertTrue(hits)

    def test_run_all_tests_phrase_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Once you are done, run all tests to confirm nothing broke.")
        self.assertTrue(hits)

    def test_incident_module_tests_phrase_rejected(self):
        # The exact phrasing from the second incident in the background:
        # "run the relevant flowtree module tests"
        hits = lint_prompt_for_broad_test_instructions(
            "When you finish, run the relevant flowtree module tests.")
        self.assertTrue(hits, "the incident's module-tests phrasing must be rejected")

    def test_shard_phrase_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Reproduce the failure by running the engine/utils CI shard.")
        self.assertTrue(hits)

    def test_ar_test_group_mention_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Set AR_TEST_GROUP=2 and AR_TEST_GROUPS=8 to reproduce this.")
        self.assertTrue(hits)

    def test_mvn_test_without_selector_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn test to check your change compiles and passes.")
        self.assertTrue(hits)

    def test_bare_dtest_class_mention_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Run it with -Dtest=NotifierRegistryTest to confirm the fix.")
        self.assertTrue(hits)

    def test_mixed_narrow_and_broad_dtest_mention_rejected(self):
        # -Dtest=Foo,Bar#baz -- Foo alone is broad even though Bar#baz is
        # narrow. A single-entry lookahead can find the later "#" and miss
        # this; every comma-separated entry must be checked individually.
        hits = lint_prompt_for_broad_test_instructions(
            "Run it with -Dtest=NotifierRegistryTest,OtherTest#testFoo to confirm the fix.")
        self.assertTrue(hits, "a mixed narrow/broad -Dtest value must still be flagged")

    def test_single_method_dtest_mention_not_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Run it with -Dtest=FooTest#bar to confirm the fix.")
        self.assertEqual([], hits)

    def test_multiple_method_dtest_mention_flagged(self):
        # Every individual entry names a method, but Maven still runs both
        # in the same invocation -- this is exactly as broad as a bare
        # class selector for the "one test per invocation" rule.
        hits = lint_prompt_for_broad_test_instructions(
            "Run it with -Dtest=FooTest#bar,BazTest#qux to confirm the fix.")
        self.assertTrue(hits, "a -Dtest value naming multiple methods must still be flagged")

    def test_surefire_plus_method_separator_dtest_mention_flagged(self):
        # "Class#m1+m2" runs both methods in one invocation via Surefire's
        # "+" separator, exactly as broad as the comma form above.
        hits = lint_prompt_for_broad_test_instructions(
            "Run it with -Dtest=FooTest#bar+qux to confirm the fix.")
        self.assertTrue(hits, "a -Dtest value using the '+' method separator must be flagged")

    def test_violation_includes_line_number(self):
        hits = lint_prompt_for_broad_test_instructions(
            "First read the code.\nThen run the full test suite.\n")
        self.assertTrue(hits)
        lineno, snippet, reason = hits[0]
        self.assertEqual(2, lineno)

    def test_chained_broad_mvn_test_before_narrow_one_rejected(self):
        # A whole-line lookahead is fooled by a selector belonging to a
        # LATER, unrelated chained command: "mvn test && mvn test
        # -Dtest=Foo#bar" must still flag the first (broad) "mvn test".
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn test && mvn test -Dtest=Foo#bar to double check this.")
        self.assertTrue(hits, "the broad first mvn test must be flagged")

    def test_chained_broad_mvn_test_after_narrow_one_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn test -Dtest=Foo#bar && mvn test to double check this.")
        self.assertTrue(hits, "the broad second mvn test must be flagged")

    def test_chained_narrow_mvn_test_commands_not_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn test -Dtest=Foo#bar && mvn test -Dtest=Baz#qux to confirm.")
        self.assertEqual([], hits)

    def test_mvn_verify_without_selector_rejected(self):
        # "verify" runs the full default lifecycle up to and including
        # tests unless -DskipTests is present -- exactly as broad as "test".
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn verify to check your change compiles and passes.")
        self.assertTrue(hits)

    def test_mvn_install_without_selector_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn install before declaring this task complete.")
        self.assertTrue(hits)

    def test_mvn_package_without_selector_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn package to confirm the artifact builds.")
        self.assertTrue(hits)

    def test_mvn_install_with_skip_tests_not_flagged(self):
        # A pure build-verification command that explicitly skips tests
        # must not be flagged as a broad test instruction -- this is the
        # exact phrase this repository's own CLAUDE.md recommends running
        # before declaring a task complete.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn clean install -DskipTests before declaring this done.")
        self.assertEqual([], hits)

    def test_mvn_verify_with_maven_test_skip_true_not_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn verify -Dmaven.test.skip=true to confirm it builds.")
        self.assertEqual([], hits)

    def test_mvn_verify_with_skip_tests_equals_false_still_flagged(self):
        # -DskipTests=false explicitly RE-ENABLES tests -- the skip matcher
        # previously matched on the "-DskipTests" prefix alone regardless of
        # what followed "=", so this was wrongly treated as a skip flag and
        # the broad "mvn verify" instruction slipped past the linter.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn verify -DskipTests=false to confirm it builds.")
        self.assertTrue(hits, "-DskipTests=false must not suppress the broad-run warning")

    def test_mvn_verify_with_maven_test_skip_equals_false_still_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn verify -Dmaven.test.skip=false to confirm it builds.")
        self.assertTrue(hits, "-Dmaven.test.skip=false must not suppress the broad-run warning")

    def test_mvnw_test_without_selector_rejected(self):
        # The bare "mvn" prefix does not match "mvnw" (no whitespace
        # between "mvn" and "w"), so the Maven Wrapper launcher needs its
        # own recognition here, mirroring the command validator's fix.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run ./mvnw test to check your change compiles and passes.")
        self.assertTrue(hits, "a prompt telling the agent to run ./mvnw test must be flagged")

    def test_mvn_test_with_module_flag_between_launcher_and_phase_rejected(self):
        # The old matcher required the phase immediately after the
        # launcher; "mvn -pl engine/utils test" has other tokens in
        # between and slipped through undetected.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn -pl engine/utils test to check your change compiles and passes.")
        self.assertTrue(hits, "mvn <flags> test must be flagged even with tokens between "
                              "the launcher and the phase")

    def test_mvn_clean_test_with_goal_between_launcher_and_phase_rejected(self):
        # Same adjacency gap for a preceding lifecycle goal: "mvn clean
        # test" is exactly as broad as "mvn test".
        hits = lint_prompt_for_broad_test_instructions(
            "Please run mvn clean test to check your change compiles and passes.")
        self.assertTrue(hits, "mvn clean test must be flagged even though \"test\" does not "
                              "immediately follow \"mvn\"")

    def test_unittest_discover_prompt_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run python3 -m unittest discover to check your change.")
        self.assertTrue(hits, "a prompt telling the agent to run unittest discover must be flagged")

    def test_unittest_bare_module_prompt_rejected(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run python -m unittest tests.test_foo to check your change.")
        self.assertTrue(hits)

    def test_unittest_single_method_prompt_not_flagged(self):
        hits = lint_prompt_for_broad_test_instructions(
            "Please run python3 -m unittest tests.test_foo.FooTest.test_bar to verify the fix.")
        self.assertFalse(hits, "an unambiguous single unittest method id must not be flagged")

    def test_unittest_two_dotted_ids_prompt_rejected(self):
        # A bare "a dotted id is present somewhere in the fragment" check
        # would miss this: naming two dotted ids still runs both tests
        # together in one invocation.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run python3 -m unittest tests.test_foo.FooTest.test_bar "
            "tests.test_baz.BazTest.test_qux to verify the fix.")
        self.assertTrue(hits, "naming two dotted unittest ids must still be flagged")

    def test_unittest_discover_with_interpreter_option_prompt_rejected(self):
        # An interpreter option before -m (here -O) breaks the literal
        # "python3 -m" sequence; the matcher must still recognize the
        # unittest discover run, matching the pytest matcher and the
        # command-side _index_of_module_flag.
        hits = lint_prompt_for_broad_test_instructions(
            "Please run python3 -O -m unittest discover to check your change.")
        self.assertTrue(
            hits,
            "an interpreter option before -m must not hide unittest discover in a prompt")

    def test_later_skip_tests_false_overrides_earlier_true_mention_rejected(self):
        # The mere PRESENCE of "-DskipTests=true" is not sufficient to
        # exempt the fragment: Maven's last-value-wins -D semantics mean a
        # later "-DskipTests=false" still runs the tests.
        hits = lint_prompt_for_broad_test_instructions(
            "Run mvn verify -DskipTests=true -DskipTests=false to confirm.")
        self.assertTrue(hits, "a later -DskipTests=false override must still be flagged")

    def test_later_broader_dtest_mention_rejected(self):
        # Only the FIRST -Dtest= occurrence being narrow is not sufficient:
        # Maven uses the later property value, so a later, broader mention
        # must still be flagged even though the first one alone is narrow.
        hits = lint_prompt_for_broad_test_instructions(
            "Run mvn test -Dtest=Foo#bar -Dtest=WholeClass to confirm.")
        self.assertTrue(hits, "a later, broader -Dtest= mention must still be flagged")


class TestTimeoutWrapper(unittest.TestCase):
    """``timeout DURATION <command>`` must not hide the wrapped command."""

    def test_timeout_wrapped_broad_maven_rejected(self):
        self.assertTrue(validate_post_completion_command("timeout 2400 mvn test -pl engine/utils"))

    def test_timeout_with_operand_option_rejected(self):
        self.assertTrue(validate_post_completion_command("timeout -k 5 2400 mvn test"))

    def test_timeout_with_glued_option_wrapped_pytest_directory_rejected(self):
        self.assertTrue(validate_post_completion_command("timeout --signal=KILL 60 pytest tools/"))

    def test_timeout_wrapped_skiptests_build_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "timeout 2400 mvn clean install -DskipTests"))

    def test_timeout_wrapped_single_node_id_accepted(self):
        self.assertEqual([], validate_post_completion_command(
            "timeout 60 pytest test_secrets.py::test_render"))


class TestPytestPromptLint(unittest.TestCase):
    """Prompt instructions to run pytest must name exactly one node id."""

    def test_bare_pytest_directory_flagged(self):
        self.assertTrue(lint_prompt_for_broad_test_instructions("Run pytest tools/mcp/manager"))

    def test_python_module_pytest_file_flagged(self):
        self.assertTrue(lint_prompt_for_broad_test_instructions(
            "run python3 -m pytest tools/mcp/manager/test_server.py"))

    def test_run_pytest_with_no_target_flagged(self):
        self.assertTrue(lint_prompt_for_broad_test_instructions("Run pytest"))

    def test_two_node_ids_flagged(self):
        self.assertTrue(lint_prompt_for_broad_test_instructions(
            "pytest -q a.py::test_one b.py::test_two"))

    def test_single_node_id_accepted(self):
        self.assertEqual([], lint_prompt_for_broad_test_instructions(
            "Then run python -m pytest tools/mcp/manager/test_server.py::TestFoo::test_bar"))

    def test_backquoted_single_node_id_accepted(self):
        self.assertEqual([], lint_prompt_for_broad_test_instructions(
            "Verify with `pytest test_secrets.py::test_render` before finishing."))

    def test_prose_naming_pytest_accepted(self):
        self.assertEqual([], lint_prompt_for_broad_test_instructions(
            "Add a pytest regression test and use explicit pytest node ids."))


if __name__ == "__main__":
    unittest.main()
