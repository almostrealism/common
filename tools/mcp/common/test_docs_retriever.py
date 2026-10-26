"""Tests for the documentation index in ar-consultant's DocsRetriever.

The most important assertion this file makes: queries for phrases that
appear in standalone (non-Maven-layer) docs — flowtree/runtime/docs, tools/mcp,
docs/tutorials — return at least one result. A regression that drops
those roots from the index reproduces the failure mode this test exists
to prevent: agents asking the consultant about pushed tools, workspace
secrets, or HMAC token minting and getting "Not documented" answers even
though the docs are present on disk.
"""

import os
import sys
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from docs_retriever import (  # noqa: E402
    DocsRetriever,
    STANDALONE_HTML_ROOTS,
    STANDALONE_MD_ROOTS,
    _MAX_DOC_CHARS,
    _expand_roots,
    _read_file,
    is_indexed,
)


class TestStandaloneDocCoverage(unittest.TestCase):
    """Assert each standalone-doc query that previously returned
    'Not documented' now finds real documentation hits."""

    def setUp(self):
        self.retriever = DocsRetriever()

    def _assertHasHitIn(self, query, dir_prefix):
        hits = self.retriever.search(query, max_results=5)
        self.assertTrue(
            any(h["file"].startswith(dir_prefix) for h in hits),
            f"Expected at least one hit under {dir_prefix!r} for query "
            f"{query!r}; got {[h['file'] for h in hits]}",
        )

    def test_pushed_tools_topic_is_indexed(self):
        self._assertHasHitIn("registerPushedTools", "flowtree/runtime/docs/")

    def test_managed_tools_downloader_is_indexed(self):
        self._assertHasHitIn("ManagedToolsDownloader", "flowtree/runtime/docs/")

    def test_armt_tmp_token_is_indexed(self):
        self._assertHasHitIn("armt_tmp", "flowtree/runtime/docs/")

    def test_secret_render_file_is_indexed(self):
        # Hit may come from flowtree/runtime/docs or tools/mcp — either is fine.
        hits = self.retriever.search("secret_render_file", max_results=5)
        self.assertTrue(
            any(
                h["file"].startswith("flowtree/runtime/docs/")
                or h["file"].startswith("tools/mcp/")
                for h in hits
            ),
            f"Expected a hit in flowtree/runtime/docs/ or tools/mcp/; got "
            f"{[h['file'] for h in hits]}",
        )

    def test_workspace_secrets_security_rules_indexed(self):
        # tools/mcp/CLAUDE.md is the only place these are written down.
        self._assertHasHitIn("NEVER read the rendered file", "tools/mcp/")


class TestCiDocumentationCoverage(unittest.TestCase):
    """The corpus-coverage fix: the authoritative CI documents must be indexed.

    Before this fix, ``.github/CLAUDE.md`` (and the other CI docs) lived outside
    every Maven layer module and so were never read by ``DocsRetriever``. A
    consult asking which QA rounds Master Agent Dispatch runs, their branch
    prefixes, or the open-PR limit therefore abstained even though the answer is
    written down — the failure these tests guard against.
    """

    def setUp(self):
        self.retriever = DocsRetriever()

    def _assertHasHitIn(self, query, dir_prefix):
        hits = self.retriever.search(query, max_results=8)
        self.assertTrue(
            any(h["file"].startswith(dir_prefix) for h in hits),
            f"Expected at least one hit under {dir_prefix!r} for query "
            f"{query!r}; got {[h['file'] for h in hits]}",
        )

    def test_qa_branch_prefixes_are_indexed(self):
        # The branch prefixes live near char 51 619 of .github/CLAUDE.md.
        self._assertHasHitIn("qa/coverage-", ".github/")

    def test_open_pr_limit_is_indexed_past_the_old_read_cap(self):
        # MAX_OPEN_PRS sits at ~char 54 092 of .github/CLAUDE.md, past the
        # original 50 000-char read cap. A hit here proves both that the file is
        # in the index and that the raised read cap reaches it — lowering the
        # cap below that offset makes this fail.
        self._assertHasHitIn("MAX_OPEN_PRS", ".github/")

    def test_ci_architecture_is_indexed(self):
        self._assertHasHitIn("CI_ARCHITECTURE", ".github/")

    def test_ci_script_table_is_indexed(self):
        # tools/ci/README.md is the only doc describing each CI script.
        self._assertHasHitIn("validate-agent-commit", "tools/ci/")

    def test_computation_model_is_indexed(self):
        self._assertHasHitIn("computation graph model", "docs/COMPUTATION_MODEL.md")


class TestReadCapCoversLongCiDoc(unittest.TestCase):
    """The per-file read cap must cover the longest indexed document whole."""

    def setUp(self):
        self.retriever = DocsRetriever()

    def test_read_cap_reaches_max_open_prs(self):
        path = self.retriever.common_dir / ".github" / "CLAUDE.md"
        self.assertTrue(path.is_file(), f"{path} is missing")
        content = _read_file(path)
        self.assertIn(
            "MAX_OPEN_PRS", content,
            "The read cap truncates .github/CLAUDE.md before MAX_OPEN_PRS; "
            f"_MAX_DOC_CHARS={_MAX_DOC_CHARS} is too small.",
        )

    def test_cap_is_large_enough_for_the_ci_doc(self):
        path = self.retriever.common_dir / ".github" / "CLAUDE.md"
        self.assertGreaterEqual(
            _MAX_DOC_CHARS, path.stat().st_size,
            "_MAX_DOC_CHARS must cover .github/CLAUDE.md whole.",
        )


class TestStandaloneRootExpansion(unittest.TestCase):
    """Lower-level test: every configured root resolves to at least one
    file on disk. Catches typos in STANDALONE_*_ROOTS that would
    otherwise silently produce empty results."""

    def setUp(self):
        self.retriever = DocsRetriever()
        self.common_dir = self.retriever.common_dir

    def test_every_md_root_resolves_to_something(self):
        for entry in STANDALONE_MD_ROOTS:
            files = _expand_roots(self.common_dir, [entry], ".md")
            self.assertTrue(
                files,
                f"STANDALONE_MD_ROOTS entry {entry!r} resolved to no files. "
                f"Either the path is wrong or the documentation was deleted "
                f"without updating this list.",
            )

    def test_every_html_root_resolves_to_something(self):
        for entry in STANDALONE_HTML_ROOTS:
            files = _expand_roots(self.common_dir, [entry], ".html")
            self.assertTrue(
                files,
                f"STANDALONE_HTML_ROOTS entry {entry!r} resolved to no files.",
            )


class TestFileLists(unittest.TestCase):
    """Sanity check that the three file-collection methods now include
    standalone roots when no module filter is supplied."""

    def setUp(self):
        self.retriever = DocsRetriever()

    def _file_set(self, paths):
        return {
            str(p.relative_to(self.retriever.common_dir))
            for p in paths
        }

    def test_markdown_files_includes_flowtree_docs(self):
        readmes, others = self.retriever._markdown_files()
        files = self._file_set(readmes) | self._file_set(others)
        self.assertIn("flowtree/runtime/docs/architecture.md", files)
        self.assertIn("tools/mcp/SECRETS.md", files)
        # README split rule: flowtree's README belongs in the readmes bucket.
        readme_files = self._file_set(readmes)
        self.assertIn("flowtree/runtime/README.md", readme_files)

    def test_html_files_includes_tutorials(self):
        files = self._file_set(self.retriever._html_files())
        self.assertIn("docs/tutorials/09-workspace-secrets.html", files)

    def test_all_doc_files_includes_standalone_roots(self):
        files = self._file_set(self.retriever._all_doc_files())
        self.assertIn("flowtree/runtime/docs/claude-code-job.md", files)
        self.assertIn("docs/tutorials/09-workspace-secrets.html", files)


class TestSkipDirIndexing(unittest.TestCase):
    """Guard: documents under a skipped runtime/build directory are never
    indexed, no matter how deep they are nested.

    ``.flowtree`` is the gitignored directory where FlowTree/oop-review
    sessions leave per-job agent state (e.g. ``oop-review.md``). Because it
    is a working-tree artifact absent from a clean checkout, the
    corpus-coverage guard only exercises it when such an artifact happens to
    be present; these assertions pin the skip behaviour deterministically so
    the ``_SKIP_DIRS`` entry cannot silently regress.
    """

    def test_flowtree_artifact_is_not_indexed(self):
        self.assertFalse(is_indexed(".flowtree/oop-review.md"))

    def test_nested_flowtree_artifact_is_not_indexed(self):
        self.assertFalse(is_indexed("tools/mcp/.flowtree/oop-review.md"))

    def test_other_skip_dirs_are_not_indexed(self):
        self.assertFalse(is_indexed("target/generated/notes.md"))
        self.assertFalse(is_indexed("results/run.md"))
        self.assertFalse(is_indexed("node_modules/pkg/README.md"))

    def test_ordinary_document_is_indexed(self):
        self.assertTrue(is_indexed("docs/QUICK_REFERENCE.md"))
        self.assertTrue(is_indexed("flowtree/runtime/docs/architecture.md"))

    def test_non_document_suffix_is_not_indexed(self):
        self.assertFalse(is_indexed(".flowtree/state.json"))


if __name__ == "__main__":
    unittest.main()
