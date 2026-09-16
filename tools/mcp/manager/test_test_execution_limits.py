"""Tests for tools/mcp/manager/test_execution_limits.py.

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

from test_execution_limits import (  # noqa: E402
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

    def test_multiple_single_method_maven_selectors(self):
        self.assertEqual([], validate_post_completion_command(
            "mvn -pl engine/utils test -Dtest=FooTest#bar,FooTest#baz"))

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

    def test_short_prompt_skipped(self):
        self.assertEqual([], lint_prompt_for_broad_test_instructions("fix it"))

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

    def test_violation_includes_line_number(self):
        hits = lint_prompt_for_broad_test_instructions(
            "First read the code.\nThen run the full test suite.\n")
        self.assertTrue(hits)
        lineno, snippet, reason = hits[0]
        self.assertEqual(2, lineno)


if __name__ == "__main__":
    unittest.main()
