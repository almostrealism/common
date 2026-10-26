"""Tests for the evidence-list consult prompt and its system prompts."""

import os
import sys
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from consult_evidence import TIER_CURRENT, EvidenceList  # noqa: E402
from inference import (  # noqa: E402
    REFORMULATE_SYSTEM_PROMPT, SYSTEM_PROMPT, InferenceBackend,
    build_consult_prompt,
)


class _Recorder(InferenceBackend):
    """A backend that records what it was asked and answers fixed text."""

    def __init__(self, reply="ok"):
        super().__init__()
        self.reply = reply
        self.calls = []

    @property
    def name(self):
        return "recorder"

    def generate(self, prompt, system=None, max_tokens=1024, temperature=0.3):
        self.calls.append((prompt, system))
        return self.reply


class TestBuildConsultPrompt(unittest.TestCase):

    def _evidence(self):
        ev = EvidenceList()
        ev.add_doc({"file": "a.md", "start": 1, "end": 4, "context": "DOC TEXT"})
        ev.add_note({"id": "m", "content": "NOTE TEXT"}, TIER_CURRENT)
        return ev

    def test_question_comes_first_then_the_evidence(self):
        prompt = build_consult_prompt("What is X?", self._evidence())
        self.assertTrue(prompt.startswith("## Question\n\nWhat is X?"))
        self.assertLess(prompt.index("[D1]"), prompt.index("[N1] NOTE current"))
        self.assertIn("DOC TEXT", prompt)
        self.assertIn("BASIS:", prompt)

    def test_a_rendered_string_is_accepted(self):
        self.assertIn("RENDERED", build_consult_prompt("q", "RENDERED"))

    def test_history_and_context_sections(self):
        prompt = build_consult_prompt("q", "E", extra_context="snippet",
                                      history="Turn 1 question: p")
        self.assertIn("## Earlier In This Conversation", prompt)
        self.assertIn("Turn 1 question: p", prompt)
        self.assertIn("## Additional Context from Agent\n\nsnippet", prompt)

    def test_instructions_can_be_replaced(self):
        prompt = build_consult_prompt("q", "E", instructions="## Task\n\nSummarize.")
        self.assertTrue(prompt.endswith("## Task\n\nSummarize."))
        self.assertNotIn("## Instructions", prompt)


class TestSystemPrompts(unittest.TestCase):

    def test_consult_prompt_states_the_tier_and_citation_contract(self):
        for phrase in ("NOTE current", "NOTE branch-local", "NOTE stale",
                       "[D1]", "BASIS:", "CONFLICTS:"):
            self.assertIn(phrase, SYSTEM_PROMPT)

    def test_reformulation_does_not_use_the_answering_contract(self):
        # The BASIS/CONFLICTS trailer would otherwise leak into stored notes.
        backend = _Recorder("rewritten")
        self.assertEqual(backend.reformulate("a note").text, "rewritten")
        _, system = backend.calls[0]
        self.assertEqual(system, REFORMULATE_SYSTEM_PROMPT)
        self.assertNotIn("BASIS", system)

    def test_consult_passes_evidence_and_history_through(self):
        backend = _Recorder("answer [D1]")
        backend.consult("q", evidence="EVIDENCE BLOCK", history="Turn 1")
        prompt, system = backend.calls[0]
        self.assertIn("EVIDENCE BLOCK", prompt)
        self.assertIn("Turn 1", prompt)
        self.assertEqual(system, SYSTEM_PROMPT)


if __name__ == "__main__":
    unittest.main()
