"""Unit tests for exfil_guard_registration.invokes_adapter().

verify-exfiltration-guard.sh's CHECK 2 and CHECK 3 both call this function
from an embedded Python heredoc (see test-verify-exfiltration-guard.sh for
the black-box coverage of the shell script itself); this module tests the
shared helper directly.
"""
import unittest

from exfil_guard_registration import invokes_adapter

ADAPTER = "block-exfiltration.sh"


class InvokesAdapterTest(unittest.TestCase):

    def test_direct_invocation_matches(self):
        self.assertTrue(invokes_adapter(
            "$CLAUDE_PROJECT_DIR/.claude/hooks/block-exfiltration.sh", ADAPTER))

    def test_bash_wrapped_invocation_matches(self):
        self.assertTrue(invokes_adapter(
            "bash .claude/hooks/block-exfiltration.sh", ADAPTER))

    def test_sh_wrapped_invocation_with_flag_matches(self):
        self.assertTrue(invokes_adapter(
            "sh -e .claude/hooks/block-exfiltration.sh", ADAPTER))

    def test_env_wrapped_invocation_matches(self):
        self.assertTrue(invokes_adapter(
            "env .claude/hooks/block-exfiltration.sh", ADAPTER))

    def test_decoy_echo_does_not_match(self):
        self.assertFalse(invokes_adapter("echo block-exfiltration.sh", ADAPTER))

    def test_decoy_comment_does_not_match(self):
        self.assertFalse(invokes_adapter(
            "# runs block-exfiltration.sh", ADAPTER))

    def test_different_adapter_does_not_match(self):
        self.assertFalse(invokes_adapter(
            ".claude/hooks/block-git-commit.sh", ADAPTER))

    def test_empty_command_does_not_match(self):
        self.assertFalse(invokes_adapter("", ADAPTER))

    def test_wrapper_shell_with_no_program_does_not_match(self):
        self.assertFalse(invokes_adapter("bash -e", ADAPTER))

    def test_unclosed_quote_does_not_match(self):
        self.assertFalse(invokes_adapter(
            "'.claude/hooks/block-exfiltration.sh", ADAPTER))

    def test_nested_wrapper_only_strips_one_layer(self):
        self.assertFalse(invokes_adapter(
            "bash sh .claude/hooks/block-exfiltration.sh", ADAPTER))


if __name__ == "__main__":
    unittest.main()
