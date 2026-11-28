"""Tests for the curated concept map: parsing, retrieval, and the upkeep
check that flags entries naming a type the source tree no longer declares."""

import unittest

from concept_map import ConceptMap

# A small map in the real document's format, including a "## Format" section
# whose bold labels describe the fields rather than populate them, and a
# non-entry heading, so the parser's "an entry has >= 1 canonical type" rule is
# exercised directly.
_SAMPLE = """# Consult Concept Map

Intro prose.

## Format

- **Concept:** a plain statement of the idea.
- **Canonical types:** the interface and its implementations, in backticks.

---

## IndexSequence

- **Concept:** a finite sequence of the integer index values an index can take.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `IndexSequence`, `ArithmeticIndexSequence`, `ArrayIndexSequence`
- **Extension points:** implement `IndexSequence` for a new sequence shape.
- **Near-duplicates and anti-patterns:** do not write a new `Set<Integer>`.
- **Keywords:** integer membership; index options; set of integers

## Cell

- **Concept:** a node that receives and transmits data in the graph.
- **Module:** `domain/graph` (ar-graph).
- **Canonical types:** `Cell`, `Receptor`, `Transmitter`
- **Extension points:** implement `Cell`.
- **Near-duplicates and anti-patterns:** do not build a parallel node type.
- **Keywords:** cell; computation graph node; push-based data flow
"""


class TestParsing(unittest.TestCase):

    def setUp(self):
        self.map = ConceptMap.parse(_SAMPLE)

    def test_only_sections_with_canonical_types_are_entries(self):
        # "## Format" and the title are not entries; the two concepts are.
        self.assertEqual([e.name for e in self.map.entries],
                         ["IndexSequence", "Cell"])

    def test_fields_and_types_are_parsed(self):
        entry = self.map.entries[0]
        self.assertEqual(entry.canonical_types,
                         ["IndexSequence", "ArithmeticIndexSequence",
                          "ArrayIndexSequence"])
        self.assertEqual(entry.keywords,
                         ["integer membership", "index options",
                          "set of integers"])
        self.assertIn("finite sequence", entry.statement)
        self.assertIn("ar-code", entry.module)

    def test_line_range_covers_the_entry(self):
        entry = self.map.entries[0]
        self.assertGreater(entry.end, entry.start)
        lines = _SAMPLE.split("\n")
        self.assertEqual(lines[entry.start - 1].strip(), "## IndexSequence")

    def test_render_names_concept_types_and_extension(self):
        text = self.map.entries[0].render()
        self.assertIn("Concept: IndexSequence", text)
        self.assertIn("Canonical types: IndexSequence", text)
        self.assertIn("Extension points:", text)


class TestMatching(unittest.TestCase):

    def setUp(self):
        self.map = ConceptMap.parse(_SAMPLE)

    def test_integer_membership_matches_index_sequence_first(self):
        top = self.map.match("integer membership for index options")
        self.assertTrue(top)
        self.assertEqual(top[0].name, "IndexSequence")

    def test_a_touched_type_name_matches_its_concept(self):
        # The file being touched reaches the matcher as a token; the canonical
        # type it declares selects the concept.
        top = self.map.match("editing Transmitter.java")
        self.assertEqual(top[0].name, "Cell")

    def test_an_unrelated_query_matches_nothing(self):
        self.assertEqual(self.map.match("quarterly revenue forecast"), [])

    def test_incidental_word_overlap_alone_does_not_surface_an_entry(self):
        # "node" appears in the Cell statement, but with no keyword phrase,
        # concept name or canonical type matched it must not surface the entry.
        self.assertEqual(self.map.match("a node in the deployment pipeline"), [])


class TestUndeclaredTypes(unittest.TestCase):

    def setUp(self):
        self.map = ConceptMap.parse(_SAMPLE)

    def test_a_missing_type_flags_its_entry(self):
        present = {"IndexSequence", "ArithmeticIndexSequence",
                   "ArrayIndexSequence", "Cell", "Receptor"}  # Transmitter gone
        flagged = self.map.undeclared_types(lambda name: name in present)
        self.assertEqual([(e.name, missing) for e, missing in flagged],
                         [("Cell", ["Transmitter"])])

    def test_no_entry_is_flagged_when_every_type_exists(self):
        self.assertEqual(self.map.undeclared_types(lambda name: True), [])


class TestRealMap(unittest.TestCase):
    """The shipped map parses, covers the core concepts, and names only types
    that exist — the guarantee scripts/check-concept-map.py enforces."""

    def setUp(self):
        self.map = ConceptMap.load()
        self.assertIsNotNone(self.map, "docs/internals/consult-concept-map.md "
                             "must be present and loadable")

    def test_core_concepts_are_present(self):
        names = {e.name for e in self.map.entries}
        for concept in ("Producer", "Evaluable", "CollectionProducer",
                        "PackedCollection", "TraversalPolicy", "Expression",
                        "Index", "IndexSequence", "Scope", "Cell", "Block",
                        "Model", "Receptor", "MemoryProvider"):
            self.assertIn(concept, names)
        self.assertTrue(any("Submittable" in n and "Semaphore" in n
                            for n in names))

    def test_every_canonical_type_is_declared_in_the_source_tree(self):
        from docs_retriever import COMMON_DIR
        from snapshot_index import SnapshotIndex, build

        index = SnapshotIndex(build(str(COMMON_DIR)))
        flagged = self.map.undeclared_types(index.has_type)
        self.assertEqual(
            flagged, [],
            "concept-map entries name types absent from the source tree: "
            + "; ".join(f"{e.name}: {missing}" for e, missing in flagged))

    def test_integer_membership_matches_index_sequence_first(self):
        top = self.map.match("integer membership for index options")
        self.assertTrue(top)
        self.assertEqual(top[0].name, "IndexSequence")


if __name__ == "__main__":
    unittest.main()
