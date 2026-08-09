"""Tests for the dual-text handling shared by ar-consultant and ar-manager.

Covers the two halves of the encoding — writing the ``source`` wrapper and
reading it back — plus the presentation rules that make the original text
the default and the Consultant's reformulation an explicit opt-in.
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from memory_text import (  # noqa: E402
    AVAILABILITY_HINT,
    BETA_NOTICE,
    PREFER_REFORMULATED_ENV,
    decode_dual_source,
    encode_dual_source,
    is_reformulated,
    original_text,
    prefers_reformulated,
    present,
    presented_entry,
    projected,
    reformulated_text,
    text_notice,
    user_source,
)


def _dual_entry(original="raw note", content="rewritten note", source=None):
    """A memory entry as ``store_dual`` writes it."""
    return {
        "id": "entry-1",
        "content": content,
        "source": encode_dual_source(original, source),
        "tags": ["bugs"],
    }


def _verbatim_entry(content="job note", source=None):
    """A memory entry stored without reformulation (the FlowTree job path)."""
    return {"id": "entry-2", "content": content, "source": source, "tags": None}


class TestDualSourceEncoding(unittest.TestCase):
    """The JSON wrapper that preserves the author's text."""

    def test_round_trip(self):
        encoded = encode_dual_source("raw", "notes.md")
        self.assertEqual(
            {"original": "raw", "user_source": "notes.md"}, json.loads(encoded))
        self.assertEqual({"original": "raw", "user_source": "notes.md"},
                         decode_dual_source(encoded))

    def test_plain_source_is_not_a_wrapper(self):
        self.assertIsNone(decode_dual_source("notes.md"))
        self.assertIsNone(decode_dual_source(None))
        self.assertIsNone(decode_dual_source(""))

    def test_unrelated_json_is_not_a_wrapper(self):
        self.assertIsNone(decode_dual_source(json.dumps({"user_source": "x"})))
        self.assertIsNone(decode_dual_source(json.dumps(["original"])))

    def test_malformed_json_is_not_a_wrapper(self):
        self.assertIsNone(decode_dual_source("{not json"))


class TestEntryText(unittest.TestCase):
    """Reading the two versions of a memory's text."""

    def test_reformulated_entry_exposes_both_versions(self):
        entry = _dual_entry()
        self.assertTrue(is_reformulated(entry))
        self.assertEqual("raw note", original_text(entry))
        self.assertEqual("rewritten note", reformulated_text(entry))

    def test_verbatim_entry_has_no_reformulation(self):
        entry = _verbatim_entry()
        self.assertFalse(is_reformulated(entry))
        self.assertEqual("job note", original_text(entry))
        self.assertIsNone(reformulated_text(entry))

    def test_user_source_is_unwrapped(self):
        self.assertEqual("notes.md", user_source(_dual_entry(source="notes.md")))
        self.assertIsNone(user_source(_dual_entry()))
        self.assertEqual("job", user_source(_verbatim_entry(source="job")))


class TestPresentation(unittest.TestCase):
    """What a retrieval tool returns to its caller."""

    def test_original_is_the_default(self):
        presented = presented_entry(_dual_entry())
        self.assertEqual("raw note", presented["content"])
        self.assertEqual("original", presented["text_source"])
        self.assertNotIn("original", presented)

    def test_reformulated_is_opt_in_and_carries_the_original(self):
        presented = presented_entry(_dual_entry(), reformulated=True)
        self.assertEqual("rewritten note", presented["content"])
        self.assertEqual("reformulated", presented["text_source"])
        self.assertEqual("raw note", presented["original"])

    def test_verbatim_entry_is_unchanged_by_the_opt_in(self):
        presented = presented_entry(_verbatim_entry(), reformulated=True)
        self.assertEqual("job note", presented["content"])
        self.assertEqual("original", presented["text_source"])

    def test_json_wrapper_never_reaches_the_caller(self):
        self.assertNotIn("source", presented_entry(_dual_entry()))
        self.assertEqual(
            "notes.md", presented_entry(_dual_entry(source="notes.md"))["source"])

    def test_present_returns_entries_and_notice(self):
        entries, notice = present([_dual_entry(), _verbatim_entry()])
        self.assertEqual(["raw note", "job note"], [e["content"] for e in entries])
        self.assertEqual(AVAILABILITY_HINT, notice)

    def test_beta_notice_accompanies_reformulated_text(self):
        _, notice = present([_dual_entry()], reformulated=True)
        self.assertEqual(BETA_NOTICE, notice)

    def test_no_notice_without_reformulated_entries(self):
        self.assertIsNone(text_notice([_verbatim_entry()]))
        self.assertIsNone(text_notice([_verbatim_entry()], reformulated=True))

    def test_projected_keeps_the_text_version_fields(self):
        presented = presented_entry(_dual_entry(), reformulated=True)
        fields = projected(presented, ("id", "content", "score"))
        self.assertEqual(
            {"id", "content", "score", "text_source", "original"}, set(fields))
        self.assertIsNone(fields["score"])

    def test_projected_omits_original_when_showing_originals(self):
        fields = projected(presented_entry(_dual_entry()), ("id", "content"))
        self.assertEqual({"id", "content", "text_source"}, set(fields))


class TestEnvironmentDefault(unittest.TestCase):
    """``AR_MEMORY_REFORMULATED`` opts a whole session in."""

    def setUp(self):
        self._saved = os.environ.pop(PREFER_REFORMULATED_ENV, None)

    def tearDown(self):
        os.environ.pop(PREFER_REFORMULATED_ENV, None)
        if self._saved is not None:
            os.environ[PREFER_REFORMULATED_ENV] = self._saved

    def test_unset_means_originals(self):
        self.assertFalse(prefers_reformulated())

    def test_truthy_values_opt_in(self):
        for value in ("1", "true", "TRUE", "yes", "on"):
            os.environ[PREFER_REFORMULATED_ENV] = value
            self.assertTrue(prefers_reformulated(), value)

    def test_other_values_do_not_opt_in(self):
        for value in ("0", "false", "no", ""):
            os.environ[PREFER_REFORMULATED_ENV] = value
            self.assertFalse(prefers_reformulated(), value)


if __name__ == "__main__":
    unittest.main()
