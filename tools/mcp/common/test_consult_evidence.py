"""Tests for the consult evidence list: IDs, ordering, rendering, expansion."""

import os
import sys
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from consult_evidence import (  # noqa: E402
    TIER_CURRENT, TIER_HISTORICAL, TIER_STALE, EvidenceList, IdPool,
    marker_ids,
)


def _doc(file="a.md", start=10, end=20, section="A › B", text="body"):
    return {"file": file, "line": start + 1, "start": start, "end": end,
            "section": section, "context": text}


class TestIds(unittest.TestCase):

    def test_ids_are_assigned_per_kind_in_order(self):
        ev = EvidenceList()
        d1 = ev.add_doc(_doc("a.md"))
        d2 = ev.add_doc(_doc("b.md"))
        n1 = ev.add_note({"id": "m1", "content": "x"})
        i1 = ev.add_index({"path": "s.sh", "line": 3, "label": "exit codes",
                           "value": "exit 3"})
        self.assertEqual([d1.id, d2.id, n1.id, i1.id], ["D1", "D2", "N1", "I1"])

    def test_the_same_item_added_twice_keeps_one_id(self):
        ev = EvidenceList()
        first = ev.add_doc(_doc("a.md"))
        again = ev.add_doc(_doc("a.md"))
        self.assertIs(first, again)
        self.assertEqual(len(ev), 1)

    def test_a_shared_pool_keeps_ids_stable_across_lists(self):
        # A conversation keeps one pool: an item seen on turn 1 keeps its ID
        # on turn 2 even when it is retrieved in a different order.
        pool = IdPool()
        turn1 = EvidenceList(pool=pool)
        turn1.add_doc(_doc("a.md"))
        turn1.add_doc(_doc("b.md"))
        turn2 = EvidenceList(pool=pool)
        b = turn2.add_doc(_doc("b.md"))
        c = turn2.add_doc(_doc("c.md"))
        self.assertEqual(b.id, "D2")
        self.assertEqual(c.id, "D3")

    def test_marker_ids_reads_single_and_grouped_markers(self):
        self.assertEqual(marker_ids("x [D1] y [N2, I3] z [D10]"),
                         ["D1", "N2", "I3", "D10"])


class TestRenderAndExpand(unittest.TestCase):

    def test_items_are_ordered_by_tier(self):
        ev = EvidenceList()
        ev.add_note({"id": "old", "content": "o"}, TIER_HISTORICAL)
        ev.add_note({"id": "new", "content": "n"}, TIER_CURRENT)
        ev.add_doc(_doc())
        self.assertEqual([it.id for it in ev.items()], ["D1", "N2", "N1"])

    def test_render_labels_tier_and_provenance(self):
        ev = EvidenceList({"commit": "abc123def4567", "built_at": "2026-10-19T00:00:00Z"})
        ev.add_doc(_doc("x/y.md", 5, 9, "Top › Sub", "the text"))
        ev.add_note({"id": "m", "content": "gone", "created_at": "2026-04-01",
                     "branch": "feature/z", "score": 0.4}, TIER_STALE,
                    named_symbols={"present": [], "absent": ["OldClass"]})
        text = ev.render()
        self.assertIn("[D1] DOC (snapshot abc123def456, 2026-10-19)", text)
        self.assertIn("x/y.md:5-9 § Top › Sub", text)
        self.assertIn("[N1] NOTE stale — names absent from snapshot: OldClass", text)
        self.assertIn("distance=0.400 (lower is closer)", text)

    def test_render_omits_empty_documentation_when_asked(self):
        ev = EvidenceList()
        ev.add_note({"id": "m", "content": "n"})
        self.assertIn("No documentation found", ev.render())
        ev.show_empty_documentation = False
        self.assertNotIn("Relevant Documentation", ev.render())

    def test_expand_replaces_known_ids_with_locations(self):
        ev = EvidenceList()
        ev.add_doc(_doc(".github/CLAUDE.md", 849, 861))
        ev.add_note({"id": "m", "content": "n", "created_at": "2026-10-15T00:00:00Z",
                     "branch": "qa/x"})
        self.assertEqual(
            ev.expand("A [D1]. B [N1]."),
            "A [D1: .github/CLAUDE.md:849-861]. B [N1: note 2026-10-15, qa/x].")

    def test_expand_leaves_an_unknown_id_as_written(self):
        # Expanding a fabricated ID would invent a location for it.
        ev = EvidenceList()
        ev.add_doc(_doc())
        self.assertEqual(ev.expand("x [D9] y [D1, D7]"), "x [D9] y [D1, D7]")

    def test_response_records_carry_ranges_and_note_provenance(self):
        ev = EvidenceList()
        ev.add_doc(_doc("a.md", 3, 7, "S"))
        ev.add_note({"id": "m", "content": "n", "branch": "b", "namespace": "decisions",
                     "score": 0.2}, TIER_CURRENT, branch_state="merged@2026-10-01")
        doc, note = ev.to_response()
        self.assertEqual(doc["range"], [3, 7])
        self.assertEqual(doc["section"], "S")
        self.assertEqual(note["namespace"], "decisions")
        self.assertEqual(note["branch_state"], "merged@2026-10-01")
        self.assertEqual(note["distance"], 0.2)


class TestSubjects(unittest.TestCase):

    def test_a_doc_or_index_item_contributes_its_file_name(self):
        ev = EvidenceList()
        doc = ev.add_doc(_doc("tools/ci/README.md"))
        index = ev.add_index({"path": "tools/ci/drain-agent-jobs.sh", "line": 1,
                              "label": "behavior", "value": "ACTIVE = {...}"})
        self.assertEqual(doc.subjects(), ["README.md"])
        self.assertEqual(index.subjects(), ["drain-agent-jobs.sh"])

    def test_a_note_contributes_its_present_named_symbols(self):
        ev = EvidenceList()
        note = ev.add_note(
            {"id": "m", "content": "n"}, TIER_CURRENT,
            named_symbols={"present": ["drain-agent-jobs.sh"], "absent": ["Gone"]})
        self.assertEqual(note.subjects(), ["drain-agent-jobs.sh"])


if __name__ == "__main__":
    unittest.main()
