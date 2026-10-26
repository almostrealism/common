"""Tests for section-aware chunks, keyword round-robin and the corpus walk."""

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from docs_retriever import (  # noqa: E402
    SECTION_GROW_LINES, DocsRetriever, chunk_label, is_indexed, make_chunk,
    walk_corpus, _headings,
)


class TestChunks(unittest.TestCase):

    def test_a_short_section_is_returned_whole_with_its_trail(self):
        lines = ["# Top", "intro", "## Validation: detect-test-hiding.sh",
                 "exits 0 or 2", "more", "## Next", "other"]
        chunk = make_chunk("f.md", lines, 3, 1, _headings(lines))
        self.assertEqual((chunk["start"], chunk["end"]), (3, 5))
        self.assertEqual(chunk["section"], "Top › Validation: detect-test-hiding.sh")
        self.assertNotIn("## Next", chunk["context"])

    def test_a_long_section_is_windowed_inside_its_bounds(self):
        body = [f"line {i}" for i in range(SECTION_GROW_LINES + 20)]
        lines = ["# Before", "x", "## Long"] + body + ["## After", "y"]
        chunk = make_chunk("f.md", lines, 3, 6, _headings(lines))
        # Window slides inside the section: it never shows "# Before".
        self.assertEqual(chunk["start"], 3)
        self.assertEqual(chunk["end"] - chunk["start"], 12)
        self.assertNotIn("x", chunk["context"].split("\n"))

    def test_headings_in_code_fences_are_ignored(self):
        lines = ["# Real", "```", "# not a heading", "```", "text"]
        self.assertEqual([t for _, _, t in _headings(lines)], ["Real"])

    def test_label_names_range_and_section(self):
        self.assertEqual(chunk_label({"file": "a.md", "line": 5, "start": 3,
                                      "end": 9, "section": "S"}), "a.md:3-9 § S")
        self.assertEqual(chunk_label({"file": "a.md", "line": 5}), "a.md:5")


class TestCorpus(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="docs-corpus-test-"))
        files = {
            "docs/internals/a.md": "# A\nalpha keyword\n",
            "docs/plans/PLAN.md": "# Plan\nalpha keyword\n",
            "tools/mcp/manager/eval/baselines/r.md": "# R\nalpha\n",
            "engine/ml/CLAUDE.md": "# ML rules\nbeta keyword\n",
            "base/target/x.md": "# built\n",
            "README.md": "# root\n",
            "docs/index.html": "<h1>Index</h1><p>gamma</p>",
        }
        for rel, text in files.items():
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_walk_indexes_everything_but_exclusions_and_build_output(self):
        found = {p.relative_to(self.root).as_posix() for p in walk_corpus(self.root)}
        self.assertEqual(found, {"docs/internals/a.md", "engine/ml/CLAUDE.md",
                                 "docs/index.html"})

    def test_is_indexed(self):
        self.assertTrue(is_indexed("engine/ml/CLAUDE.md"))
        self.assertFalse(is_indexed("docs/plans/X.md"))
        self.assertFalse(is_indexed("README.md"))
        self.assertFalse(is_indexed("a/.pytest_cache/README.md"))
        self.assertFalse(is_indexed("tools/x.py"))

    def test_module_claude_md_is_searchable(self):
        # Copied-but-never-indexed before the walk: module CLAUDE.md files.
        hits = DocsRetriever(self.root).search("beta keyword")
        self.assertEqual([h["file"] for h in hits], ["engine/ml/CLAUDE.md"])


class TestKeywordRoundRobin(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="docs-rr-test-"))
        for i in range(6):
            path = self.root / "docs" / "internals" / f"broad{i}.md"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"# Broad {i}\nworkflow name appears here\n")
        (self.root / "docs" / "internals" / "z-specific.md").write_text(
            "# Specific\nMAX_OPEN_PRS is 7\n")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_a_later_keyword_gets_a_slot(self):
        # The first keyword alone matches six files; before the round-robin
        # it took all five slots and the specific keyword's chunk was lost.
        result = DocsRetriever(self.root).get_context_for_keywords(
            ["workflow name", "MAX_OPEN_PRS"], max_chunks=5)
        files = [r["file"] for r in result["markdown_results"]]
        self.assertEqual(len(files), 5)
        self.assertIn("docs/internals/z-specific.md", files)


if __name__ == "__main__":
    unittest.main()
