"""Guards the module names under ``tools/mcp`` against collisions.

These directories are not packages, so everything here lands in one flat
module namespace, and two files that claim the same name break any run
spanning both. Two separate collisions did exactly that, and both are
asserted here because each is invisible until someone runs the
directories together:

1. **Test files.** For a directory with no ``__init__.py``, pytest and
   ``unittest discover`` name the module after the basename alone. Three
   separate ``test_server.py`` files (ar-manager, ar-secrets,
   ar-test-runner) therefore claimed one name, and collection aborted
   with "import file mismatch" — not a skip, an error that took the whole
   run with it.

2. **The modules under test.** Nine directories define a top-level
   ``server.py``, and their tests imported it as bare ``server``. That
   resolves by ``sys.path`` order, so once collection spanned several
   directories the losing ones exercised the wrong module — surfacing as
   ``AttributeError: module 'server' has no attribute 'runner'`` rather
   than as a name clash. Fixed by loading ``server.py`` from an explicit
   path under a directory-specific name (see
   ``test-runner/server_under_test.py``).

A shared basename is fine when a package disambiguates it, so the first
check compares the module names collection actually derives, not
filenames.
"""

import os
import re
import unittest

_MCP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SKIP_DIRS = {"__pycache__", ".pytest_cache", "node_modules", "target", ".git"}


def _module_name(path: str) -> str:
    """The module name collection will import ``path`` as.

    Walks up while each directory is a package, mirroring how pytest
    derives a module name: the basename, prefixed by every ancestor
    package directory, stopping at the first one without ``__init__.py``.
    """
    parts = [os.path.splitext(os.path.basename(path))[0]]
    directory = os.path.dirname(os.path.abspath(path))
    while os.path.exists(os.path.join(directory, "__init__.py")):
        parts.insert(0, os.path.basename(directory))
        directory = os.path.dirname(directory)
    return ".".join(parts)


def _test_files() -> list:
    """Every ``test_*.py`` under ``tools/mcp``."""
    found = []
    for root, dirs, files in os.walk(_MCP_DIR):
        dirs[:] = [d for d in dirs if d not in _SKIP_DIRS]
        for name in files:
            if name.startswith("test_") and name.endswith(".py"):
                found.append(os.path.join(root, name))
    return found


class TestModuleNamesAreUnique(unittest.TestCase):
    """Two test modules must never claim the same import name."""

    def test_no_two_test_files_share_a_module_name(self):
        by_name = {}
        for path in _test_files():
            by_name.setdefault(_module_name(path), []).append(
                os.path.relpath(path, _MCP_DIR)
            )

        collisions = {n: p for n, p in by_name.items() if len(p) > 1}

        self.assertEqual(
            {}, collisions,
            "these test files resolve to the same module name, so any run "
            "covering both aborts during collection with 'import file "
            "mismatch'. Rename one after the server it tests (as "
            "test_secrets_server.py and test_runner_server.py were), or add "
            "an __init__.py so the package disambiguates them: "
            f"{collisions}",
        )

    def test_the_walk_actually_found_the_test_files(self):
        """A guard that silently matches nothing would assert nothing."""
        self.assertGreater(
            len(_test_files()), 10,
            f"expected to find the tools/mcp test suite under {_MCP_DIR}; "
            "finding almost nothing means the walk is misrooted and the "
            "collision check above is vacuous",
        )


class TestBareServerImportIsClaimedOnce(unittest.TestCase):
    """At most one directory may import its ``server.py`` by bare name."""

    _BARE_IMPORT = re.compile(r"^\s*(?:import\s+server\b|from\s+server\s+import)", re.M)

    def test_only_one_directory_imports_bare_server(self):
        claimants = set()
        for path in _test_files():
            with open(path, errors="ignore") as handle:
                if self._BARE_IMPORT.search(handle.read()):
                    claimants.add(os.path.relpath(os.path.dirname(path), _MCP_DIR))

        # ar-manager keeps the bare name; every other directory loads its
        # own server.py by path. A second claimant means whichever imports
        # second silently gets ar-manager's module.
        allowed = {"manager", os.path.join("manager", "tests")}

        self.assertEqual(
            set(), claimants - allowed,
            "these directories' tests import the bare module name `server`, "
            "which only ar-manager may claim. In a run covering both, one of "
            "them exercises ar-manager's server.py instead of its own. Load "
            "this directory's server.py by explicit path under its own "
            "module name — test-runner/server_under_test.py is the pattern.",
        )
