"""Guard: the Dockerfile must package every module server.py imports.

The ar-manager server was refactored to import sibling modules (config.py,
auth.py, controller_client.py, tracker_client.py, workspace_map.py, …). The
Dockerfile copied an explicit, hand-maintained file list that silently omitted
the new modules, so the built image started with:

    ModuleNotFoundError: No module named 'config'

The Python test suite did not catch it because it runs in the source tree,
where every sibling is present — the gap only exists in the packaged ``/app``
layout. This test closes that gap: it parses ``server.py``'s imports and the
Dockerfile's COPY/RUN instructions and fails if any locally-imported module
would be absent from the image. It needs no Docker daemon, so it runs in the
existing ``python-tests`` CI job.
"""

import ast
import fnmatch
import os
import re
import unittest

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
_DOCKERFILE = os.path.join(_MANAGER_DIR, "Dockerfile")


def _local_module_files():
    """All Python module files in the manager dir (basenames)."""
    return {
        name for name in os.listdir(_MANAGER_DIR)
        if name.endswith(".py")
    }


def _imported_sibling_modules():
    """Module names ``server.py`` imports that are local sibling .py files.

    Walks every import (including deferred imports inside functions, which
    still need their module present at call time), keeping only those whose
    ``<name>.py`` exists next to server.py.
    """
    local = {name[:-3] for name in _local_module_files()}
    with open(os.path.join(_MANAGER_DIR, "server.py")) as handle:
        tree = ast.parse(handle.read())

    imported = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".")[0]
                if root in local:
                    imported.add(root)
        elif isinstance(node, ast.ImportFrom):
            # Only absolute imports of a sibling module (level 0, e.g.
            # "from config import X"). Relative imports (level > 0) are not
            # used here and would resolve differently.
            if node.level == 0 and node.module:
                root = node.module.split(".")[0]
                if root in local:
                    imported.add(root)
    return imported


def _packaged_root_files():
    """Basenames of manager files that land at /app root per the Dockerfile.

    Expands ``COPY manager/*.py ./`` globs against the real directory and
    applies any ``RUN rm -f /app/test_*.py`` removals, so the result matches
    what the image actually contains.
    """
    with open(_DOCKERFILE) as handle:
        lines = handle.read().splitlines()

    all_files = _local_module_files()
    packaged = set()
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("COPY "):
            tokens = stripped[len("COPY "):].split()
            if len(tokens) < 2:
                continue
            sources, dest = tokens[:-1], tokens[-1]
            # Only COPYs into the app root (./ or /app or /app/) contribute
            # root-level modules; e.g. "COPY common/ /app/common/" does not.
            if dest not in ("./", ".", "/app", "/app/"):
                continue
            for src in sources:
                if not src.startswith("manager/"):
                    continue
                pattern = src[len("manager/"):]
                for name in all_files:
                    if fnmatch.fnmatch(name, pattern):
                        packaged.add(name)
        elif stripped.startswith("RUN ") and "rm " in stripped:
            # Apply "rm -f /app/test_*.py"-style removals.
            for match in re.findall(r"/app/([^\s]+)", stripped):
                for name in list(packaged):
                    if fnmatch.fnmatch(name, match):
                        packaged.discard(name)
    return packaged


class DockerfilePackagingTests(unittest.TestCase):
    """Verify the image packages everything server.py needs to import."""

    def test_server_module_is_packaged(self):
        self.assertIn("server.py", _packaged_root_files())

    def test_all_imported_siblings_are_packaged(self):
        packaged = _packaged_root_files()
        missing = {
            f"{module}.py"
            for module in _imported_sibling_modules()
            if f"{module}.py" not in packaged
        }
        self.assertEqual(
            missing, set(),
            f"server.py imports these sibling modules but the Dockerfile does "
            f"not package them into /app: {sorted(missing)}. Update the COPY "
            f"instruction in tools/mcp/manager/Dockerfile.",
        )

    def test_runtime_modules_do_not_include_tests(self):
        # Test files should not be shipped in the runtime image.
        packaged = _packaged_root_files()
        shipped_tests = {name for name in packaged if name.startswith("test_")}
        self.assertEqual(shipped_tests, set())


if __name__ == "__main__":
    unittest.main()
