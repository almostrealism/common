"""Tests for the ar-docs MCP server's documentation index.

Mirrors the assertions in ar-consultant's test_docs_retriever — both
servers index the same standalone (non-Maven-layer) doc roots and both
need a guard that catches accidental removal of those roots. The
specific failure mode this prevents: a future refactor of MODULES drops
flowtree, and queries about pushed tools / workspace secrets / HMAC
token minting silently return empty results despite the docs being
present on disk.
"""

import importlib.util
import os
import sys
import types
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))


def _stub_mcp_imports():
    """The ar-docs server.py imports `mcp.server`, `mcp.types`, and
    `mcp.server.stdio` eagerly at module load. None of those are needed
    to test the indexing logic; stub them so the import succeeds in a
    plain Python environment."""
    mcp = types.ModuleType("mcp")
    mcp_server = types.ModuleType("mcp.server")

    class _Server:
        def __init__(self, *a, **kw):
            pass

        def list_tools(self):
            return lambda f: f

        def call_tool(self):
            return lambda f: f

    mcp_server.Server = _Server
    mcp_types = types.ModuleType("mcp.types")

    class _TextContent:
        def __init__(self, **kw):
            self.__dict__.update(kw)

    class _Tool:
        pass

    mcp_types.TextContent = _TextContent
    mcp_types.Tool = _Tool
    mcp_stdio = types.ModuleType("mcp.server.stdio")
    mcp_stdio.stdio_server = lambda: None
    sys.modules.setdefault("mcp", mcp)
    sys.modules.setdefault("mcp.server", mcp_server)
    sys.modules.setdefault("mcp.types", mcp_types)
    sys.modules.setdefault("mcp.server.stdio", mcp_stdio)


_stub_mcp_imports()
_spec = importlib.util.spec_from_file_location("ar_docs_server", os.path.join(_DIR, "server.py"))
server = importlib.util.module_from_spec(_spec)
sys.modules["ar_docs_server"] = server
_spec.loader.exec_module(server)


class TestStandaloneRootExpansion(unittest.TestCase):

    def test_every_md_root_resolves_to_something(self):
        for entry in server.STANDALONE_MD_ROOTS:
            files = server._expand_standalone_roots([entry], ".md")
            self.assertTrue(
                files,
                f"STANDALONE_MD_ROOTS entry {entry!r} resolved to no files.",
            )

    def test_every_html_root_resolves_to_something(self):
        for entry in server.STANDALONE_HTML_ROOTS:
            files = server._expand_standalone_roots([entry], ".html")
            self.assertTrue(
                files,
                f"STANDALONE_HTML_ROOTS entry {entry!r} resolved to no files.",
            )


class TestModulesRegistry(unittest.TestCase):

    def test_flowtree_module_is_listed(self):
        # Without this, read_ar_module('flowtree') rejects with
        # "Unknown module", even though flowtree/README.md and
        # flowtree/docs/ both exist on disk.
        self.assertIn("flowtree", server.MODULES)

    def test_flowtree_module_has_docs_on_disk(self):
        # The read_ar_module fallback chain looks for
        # COMMON_DIR/{module}/README.md and COMMON_DIR/{module}/docs/.
        # Without those files this test would pass while the tool fails.
        self.assertTrue((server.COMMON_DIR / "flowtree" / "README.md").is_file())
        self.assertTrue((server.COMMON_DIR / "flowtree" / "docs").is_dir())


if __name__ == "__main__":
    unittest.main()
