"""Tests for the in-process consult conversation store."""

import threading
import time
import unittest

from manager_test_support import server  # noqa: F401 - sets up sys.path

from consult_conversations import (
    ConversationError, ConversationStore, caller_identity,
)
from consult_evidence import KIND_DOC, KIND_NOTE, EvidenceItem


def _item(kind, n):
    return EvidenceItem(id=f"{'D' if kind == KIND_DOC else 'N'}{n}", kind=kind,
                        text="t", file="a.md" if kind == KIND_DOC else "",
                        start=n, end=n, memory_id=str(n))


class TestCallerIdentity(unittest.TestCase):

    def test_a_job_token_is_identified_by_its_workstream_and_job(self):
        self.assertEqual(caller_identity("ws", "job", "tmp:ws/job"), "job:ws/job")

    def test_a_static_token_is_identified_by_its_label(self):
        self.assertEqual(caller_identity("", "", "ops-slack"), "token:ops-slack")
        self.assertEqual(caller_identity("", "", ""), "token:anonymous")

    def test_a_label_spelled_like_a_job_is_another_identity(self):
        self.assertNotEqual(caller_identity("", "", "ws/job"),
                            caller_identity("ws", "job", "tmp:ws/job"))


class TestConversationStore(unittest.TestCase):

    def setUp(self):
        self.now = [0.0]
        self.store = ConversationStore(clock=lambda: self.now[0], idle_ttl=100,
                                       absolute_ttl=1000, max_turns=3,
                                       max_conversations=3)

    def _start(self, owner="ws/job", repo="r", branch=""):
        with self.store.turn("", owner, repo, branch) as conv:
            conv.record("q1", [], "a1", [], {})
            return conv.id

    def test_a_follow_up_continues_the_same_conversation(self):
        cid = self._start()
        with self.store.turn(cid, "ws/job", "r", "") as conv:
            self.assertEqual(conv.next_turn, 2)
            self.assertIn("Turn 1 question: q1", conv.history())

    def test_another_identity_cannot_continue_it(self):
        cid = self._start()
        with self.assertRaises(ConversationError) as ctx:
            with self.store.turn(cid, "ws/other-job", "r", ""):
                pass
        # Indistinguishable from an unknown id, so ids cannot be probed.
        self.assertEqual(ctx.exception.response["error"], "conversation expired")

    def test_an_unknown_id_is_expired_not_restarted(self):
        with self.assertRaises(ConversationError) as ctx:
            with self.store.turn("cv-nope", "ws/job", "r", ""):
                pass
        self.assertEqual(ctx.exception.response["error"], "conversation expired")
        self.assertEqual(len(self.store), 0)

    def test_scope_is_fixed_by_the_first_turn(self):
        cid = self._start(repo="r1")
        with self.assertRaises(ConversationError) as ctx:
            with self.store.turn(cid, "ws/job", "r2", ""):
                pass
        self.assertIn("scoped to repository 'r1'", ctx.exception.response["error"])

    def test_idle_and_absolute_expiry(self):
        cid = self._start()
        self.now[0] += 101
        with self.assertRaises(ConversationError):
            with self.store.turn(cid, "ws/job", "r", ""):
                pass
        cid = self._start()
        # Kept alive by turns inside the idle window, it still ends once the
        # absolute TTL (1000) has passed since it started.
        for _ in range(12):
            self.now[0] += 90
            try:
                with self.store.turn(cid, "ws/job", "r", ""):
                    pass
            except ConversationError as e:
                self.assertEqual(e.response["error"], "conversation expired")
                return
        self.fail("the absolute TTL never expired the conversation")

    def test_turn_limit(self):
        cid = self._start()
        for _ in range(2):
            with self.store.turn(cid, "ws/job", "r", "") as conv:
                conv.record("q", [], "a", [], {})
        with self.assertRaises(ConversationError) as ctx:
            with self.store.turn(cid, "ws/job", "r", ""):
                pass
        self.assertIn("turn limit", ctx.exception.response["error"])

    def test_least_recently_used_is_evicted_at_the_cap(self):
        first = self._start()
        self.now[0] += 1
        self._start()
        self._start()
        self._start()
        self.assertEqual(len(self.store), 3)
        with self.assertRaises(ConversationError):
            with self.store.turn(first, "ws/job", "r", ""):
                pass

    def test_carried_evidence_is_capped_per_kind(self):
        with self.store.turn("", "ws/job", "r", "") as conv:
            conv.record("q", [], "a", [_item(KIND_DOC, i) for i in range(1, 4)]
                        + [_item(KIND_NOTE, i) for i in range(1, 4)], {})
            carried = conv.carried()
        self.assertEqual([it.id for it in carried], ["D1", "D2", "N1", "N2"])

    def test_carried_terms_merge_every_turns_keywords_and_last_subjects(self):
        note = EvidenceItem(id="N1", kind=KIND_NOTE, text="t", memory_id="1",
                            named_symbols={"present": ["Foo.java"], "absent": []})
        with self.store.turn("", "ws/job", "r", "") as conv:
            conv.record("q1", ["Deploy", "drain-agent-jobs"], "a1",
                        [_item(KIND_DOC, 1)], {})
            conv.record("q2", ["second-kw"], "a2", [note], {})
            terms = conv.carried_terms()
        # Keywords from every turn, deduplicated in order.
        self.assertEqual(terms[:3], ["Deploy", "drain-agent-jobs", "second-kw"])
        # Subjects only of the previous (last) turn's cited evidence.
        self.assertIn("Foo.java", terms)
        self.assertNotIn("a.md", terms)

    def test_carried_terms_are_empty_before_any_turn(self):
        with self.store.turn("", "ws/job", "r", "") as conv:
            self.assertEqual(conv.carried_terms(), [])

    def test_history_is_held_to_its_budget(self):
        with self.store.turn("", "ws/job", "r", "") as conv:
            conv.record("q", [], "x" * 5000, [], {})
            self.assertLess(len(conv.history(budget=300)), 400)

    def test_turns_on_one_conversation_run_one_at_a_time(self):
        cid = self._start()
        order = []
        entered = threading.Event()

        def first():
            with self.store.turn(cid, "ws/job", "r", ""):
                entered.set()
                time.sleep(0.2)
                order.append("first-done")

        def second():
            entered.wait()
            with self.store.turn(cid, "ws/job", "r", ""):
                order.append("second-started")

        threads = [threading.Thread(target=first), threading.Thread(target=second)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(5)
        self.assertEqual(order, ["first-done", "second-started"])


if __name__ == "__main__":
    unittest.main()
