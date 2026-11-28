"""Tests for the shared private-link detector.

Every literal that would match a pattern is assembled at run time from
fragments so this file never itself contains a real-looking private link that a
scanner (or a reviewer) would flag.
"""

import json
import os
import unittest

import private_link_detector as det


# Fragments, assembled so the source file carries no intact link.
_CLAUDE_HOST = "claude" + ".ai"
_SESSION = "session_" + "fakefakefake1234"
_CODE_SESSION_URL = "https://" + _CLAUDE_HOST + "/code/" + _SESSION
_SHARE_URL = "https://" + _CLAUDE_HOST + "/share/" + "abc123def456"
_CHAT_URL = "http://www." + _CLAUDE_HOST + "/chat/" + "xyz789ghi012"


class DetectorMatchTest(unittest.TestCase):

    def test_code_session_url_matches(self):
        self.assertTrue(det.contains_private_link(_CODE_SESSION_URL))

    def test_share_url_matches(self):
        self.assertTrue(det.contains_private_link(_SHARE_URL))

    def test_chat_url_matches(self):
        self.assertTrue(det.contains_private_link(_CHAT_URL))

    def test_bare_session_id_matches(self):
        self.assertTrue(det.contains_private_link("see " + _SESSION + " for context"))

    def test_claude_session_trailer_matches(self):
        trailer = "Claude" + "-Session" + ": " + _CODE_SESSION_URL
        self.assertTrue(det.contains_private_link(trailer))

    def test_coauthored_by_claude_matches(self):
        line = "Co-Authored" + "-By: Claude <noreply@" + "anthropic.com>"
        self.assertTrue(det.contains_private_link(line))

    def test_generated_with_claude_matches(self):
        line = "Generated with " + "Claude Code"
        self.assertTrue(det.contains_private_link(line))

    def test_clean_text_does_not_match(self):
        self.assertFalse(det.contains_private_link(
            "Fix the matmul kernel offset bug in LayerFeatures."))

    def test_unrelated_session_word_does_not_match(self):
        self.assertFalse(det.contains_private_link(
            "The training session improved accuracy."))

    def test_literal_session_underscore_before_word_does_not_match(self):
        # The collapsed variant must not join "session_" to the next prose word
        # and fabricate a bare-session-id match from ordinary text.
        self.assertFalse(det.contains_private_link(
            "The session_ identifier is documented here."))


class ObfuscationBypassTest(unittest.TestCase):
    """The detector must see through the obfuscations a motivated agent would try."""

    def test_url_encoded_slash(self):
        encoded = "https://" + _CLAUDE_HOST + "%2Fcode%2F" + _SESSION
        self.assertTrue(det.contains_private_link(encoded))

    def test_html_entity_encoded(self):
        entity = "https://" + _CLAUDE_HOST + "&#47;code&#47;" + _SESSION
        self.assertTrue(det.contains_private_link(entity))

    def test_zero_width_characters_inside_link(self):
        zw = "​"
        split = "https://" + _CLAUDE_HOST + "/co" + zw + "de/" + _SESSION
        self.assertTrue(det.contains_private_link(split))

    def test_link_split_across_lines(self):
        split = "https://" + _CLAUDE_HOST + "/code/\n" + _SESSION
        self.assertTrue(det.contains_private_link(split))

    def test_link_wrapped_in_code_fence(self):
        fenced = "```\n" + _CODE_SESSION_URL + "\n```"
        self.assertTrue(det.contains_private_link(fenced))

    def test_markdown_link_target(self):
        md = "[my session](" + _CODE_SESSION_URL + ")"
        self.assertTrue(det.contains_private_link(md))

    def test_json_escaped_solidus(self):
        # A serialised GitHub body escapes the solidus as \/.
        escaped = "https://" + _CLAUDE_HOST + "\\/code\\/" + _SESSION
        self.assertTrue(det.contains_private_link(escaped))

    def test_json_unicode_escape(self):
        escaped = "\\u0063laude.ai/code/" + _SESSION
        self.assertTrue(det.contains_private_link(escaped))

    def test_zero_width_revealed_by_decoding(self):
        # The zero-width is only present after percent-decoding, so it must be
        # re-stripped after the decode pass for the split link to be seen.
        split = "https://" + _CLAUDE_HOST + "/co%E2%80%8Bde/" + _SESSION
        self.assertTrue(det.contains_private_link(split))


class RedactionTest(unittest.TestCase):

    def test_redaction_removes_the_link(self):
        redacted = det.redact("start " + _CODE_SESSION_URL + " end")
        self.assertNotIn(_SESSION, redacted)
        self.assertNotIn("code/" + _SESSION, redacted)
        self.assertIn("<REDACTED-PRIVATE-LINK>", redacted)

    def test_redaction_keeps_surrounding_text(self):
        redacted = det.redact("prefix " + _SESSION + " suffix")
        self.assertIn("prefix", redacted)
        self.assertIn("suffix", redacted)

    def test_redaction_hides_whitespace_split_link(self):
        # A URL split by whitespace inside its path is detected only on the
        # collapsed variant and cannot be surgically removed, so redact() must
        # fall back to wholesale redaction rather than leak a fragment.
        redacted = det.redact("see https://" + _CLAUDE_HOST + "/co\nde/" + _SESSION)
        self.assertNotIn(_SESSION, redacted)
        self.assertNotIn(_CLAUDE_HOST + "/code", redacted)
        self.assertIn("<REDACTED-PRIVATE-LINK>", redacted)


class CheckAndBlockTest(unittest.TestCase):

    def test_block_result_shape(self):
        recorded = []
        result = det.check_and_block(
            "github_create_pr", "org/repo new PR",
            {"title": "ok", "body": _CODE_SESSION_URL},
            recorder=recorded.append)
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertEqual("private_link", result["blocked"])
        self.assertIn("body", result["target"])
        self.assertTrue(result["matched_patterns"])
        self.assertEqual(1, len(recorded))
        self.assertIn("matched_patterns", recorded[0])
        # The recorder never receives raw text — only metadata.
        self.assertNotIn(_SESSION, json.dumps(recorded[0]))

    def test_clean_fields_pass(self):
        result = det.check_and_block(
            "github_create_pr", "org/repo new PR",
            {"title": "Fix kernel", "body": "Adjusts the offset."})
        self.assertIsNone(result)

    def test_none_fields_skipped(self):
        result = det.check_and_block(
            "github_update_pr", "org/repo PR update",
            {"title": None, "body": "clean body"})
        self.assertIsNone(result)


class EmbeddedFallbackSyncTest(unittest.TestCase):
    """The embedded fallback must stay identical to the canonical JSON resource."""

    def test_embedded_matches_resource(self):
        path = det._locate_resource()
        self.assertIsNotNone(path, "canonical pattern resource must be locatable")
        with open(path, "r", encoding="utf-8") as handle:
            spec = json.load(handle)
        resource_regexes = [(p["name"], p["regex"]) for p in spec["patterns"]]
        embedded_regexes = [(p["name"], p["regex"]) for p in det._EMBEDDED_SPEC["patterns"]]
        self.assertEqual(resource_regexes, embedded_regexes)
        self.assertEqual(spec["block_message"], det._EMBEDDED_SPEC["block_message"])

    def test_block_message_states_the_required_points(self):
        message = det.BLOCK_MESSAGE.lower()
        self.assertIn("harmful", message)
        self.assertIn("public repositor", message)
        self.assertIn("malice", message)
        self.assertIn("reported", message)
        self.assertIn("remove", message)


if __name__ == "__main__":
    unittest.main()
