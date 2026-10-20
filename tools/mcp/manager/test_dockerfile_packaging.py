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
import glob
import os
import re
import sys
import unittest

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
_DOCKERFILE = os.path.join(_MANAGER_DIR, "Dockerfile")
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(_MANAGER_DIR)))

_COMMON_DIR = os.path.join(os.path.dirname(_MANAGER_DIR), "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

import docs_retriever  # noqa: E402

# COPY sources are relative to the build context, which is the repo root.
_MANAGER_COPY_PREFIX = "tools/mcp/manager/"


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
                # The build context is the repo root (the image bakes in the
                # documentation corpus, which lives outside tools/mcp), so
                # manager sources are addressed by their full repo path.
                if not src.startswith(_MANAGER_COPY_PREFIX):
                    continue
                pattern = src[len(_MANAGER_COPY_PREFIX):]
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


def _dockerfile_text():
    with open(_DOCKERFILE) as handle:
        return handle.read()


def _docs_stage_text():
    """The Dockerfile instructions belonging to the ``AS docs`` stage.

    A multi-stage Dockerfile has several ``FROM`` blocks; the corpus is
    assembled in the ``docs`` stage and only the markdown/HTML it stages
    reaches the image. Assertions about what the *corpus* contains must look
    at that stage alone: a regression that moved a ``COPY .claude/`` into the
    runtime stage would still match a whole-file search while leaving the
    staged corpus without those documents. Returns the text between
    ``FROM ... AS docs`` and the next ``FROM``.
    """
    lines = _dockerfile_text().splitlines()
    collected = []
    in_docs_stage = False
    for line in lines:
        from_match = re.match(r"(?i)^\s*FROM\s+.*\bAS\s+(\S+)", line)
        if from_match:
            in_docs_stage = from_match.group(1).lower() == "docs"
            continue
        if re.match(r"(?i)^\s*FROM\s+", line):
            in_docs_stage = False
            continue
        if in_docs_stage:
            collected.append(line)
    return "\n".join(collected)


class DocsCorpusPackagingTests(unittest.TestCase):
    """Guard: the documentation corpus must reach the image.

    ar-manager grounds memory answers in the docs (``memory_recall`` blends
    them into its summary). A container has no checkout, so a corpus that is
    not baked in leaves the feature silently disabled — ``_get_docs`` degrades
    rather than failing, which is right at runtime and invisible in CI.
    """

    def test_ar_docs_dir_is_set(self):
        self.assertIn("ENV AR_DOCS_DIR=", _dockerfile_text())

    def test_ar_docs_dir_names_a_docs_directory(self):
        # DocsRetriever treats the PARENT of AR_DOCS_DIR as the repo root, so
        # the variable must point at the "docs" directory itself.
        match = re.search(r"ENV AR_DOCS_DIR=(\S+)", _dockerfile_text())
        self.assertIsNotNone(match)
        self.assertTrue(
            match.group(1).endswith("/docs"),
            f"AR_DOCS_DIR must end in /docs, got {match.group(1)!r}",
        )

    def test_corpus_is_copied_into_the_image(self):
        text = _dockerfile_text()
        match = re.search(r"ENV AR_DOCS_DIR=(\S+)", text)
        corpus_root = os.path.dirname(match.group(1))
        self.assertIn(f"COPY --from=docs /src {corpus_root}", text)

    def test_every_module_with_docs_is_staged(self):
        """The docs stage must copy every top-level directory that holds
        documentation DocsRetriever indexes. A new top-level module whose
        README is not staged would be silently missing from the corpus."""
        text = _docs_stage_text()
        staged = set(re.findall(r"^COPY (\S+)/ \./\S+/$", text, re.MULTILINE))
        expected = {
            name for name in os.listdir(_REPO_ROOT)
            if os.path.isdir(os.path.join(_REPO_ROOT, name))
            and not name.startswith(".")
            and glob.glob(os.path.join(_REPO_ROOT, name, "**", "*.md"),
                          recursive=True)
        }
        missing = expected - staged
        self.assertEqual(
            missing, set(),
            f"these top-level directories contain markdown the corpus should "
            f"index but are not staged in the docs stage of "
            f"tools/mcp/manager/Dockerfile: {sorted(missing)}",
        )

    def test_only_docs_survive_the_prune(self):
        # The prune is what keeps the Java source tree out of the image.
        self.assertIn("! -name '*.md' ! -name '*.html' -delete",
                      _dockerfile_text())

    def test_docs_stage_text_excludes_runtime_stage(self):
        """The docs-stage extraction must stop at the runtime ``FROM``.

        If it bled into the runtime stage, a corpus assertion could be
        satisfied by a COPY that never reaches the staged documentation. The
        docs-stage text must contain the corpus COPYs and the prune, and must
        not contain runtime-only instructions (the ENV AR_DOCS_DIR, the
        requirements install, or the COPY --from=docs that imports the
        already-built corpus)."""
        docs_text = _docs_stage_text()
        self.assertIn("COPY docs/ ./docs/", docs_text)
        self.assertIn("! -name '*.md' ! -name '*.html' -delete", docs_text)
        self.assertNotIn("ENV AR_DOCS_DIR=", docs_text)
        self.assertNotIn("COPY --from=docs", docs_text)
        self.assertNotIn("requirements.txt", docs_text)

    def test_github_and_claude_docs_are_staged(self):
        """The CI and hook/agent documentation live under dot-directories that
        the layer-module COPYs do not reach. The corpus-coverage fix copies them
        explicitly so .github/CLAUDE.md (and the other CI docs) can be indexed;
        a regression that drops either COPY makes those answers disappear.

        Restricted to the ``docs`` stage: a COPY that moved to the runtime
        stage would leave the staged corpus without these documents yet still
        satisfy a whole-file search."""
        text = _docs_stage_text()
        for directory in (".github", ".claude"):
            self.assertIn(
                f"COPY {directory}/ ./{directory}/", text,
                f"the docs stage of tools/mcp/manager/Dockerfile must copy "
                f"{directory}/ so its markdown reaches the corpus",
            )


def _docs_stage_sources():
    """Repo-relative sources the docs stage copies (``COPY <src> <dest>``)."""
    return [m.rstrip("/") for m in re.findall(
        r"^COPY (?!--)(\S+) \S+$", _docs_stage_text(), re.MULTILINE)]


def _repository_documents():
    """Every ``*.md`` and ``*.html`` in the checkout, repo-relative, outside
    build output and caches (the directories the corpus walk never enters,
    and which ``.dockerignore`` keeps out of the build context)."""
    found = []
    for dirpath, dirnames, filenames in os.walk(_REPO_ROOT):
        dirnames[:] = [d for d in dirnames
                       if d not in docs_retriever._SKIP_DIRS]
        for name in filenames:
            if name.endswith((".md", ".html")):
                rel = os.path.relpath(os.path.join(dirpath, name), _REPO_ROOT)
                found.append(rel.replace(os.sep, "/"))
    return sorted(found)


def _excluded(path):
    """Whether ``path`` is named by ``INDEX_EXCLUSIONS``."""
    return any(path.startswith(e) if e.endswith("/") else path == e
               for e in docs_retriever.INDEX_EXCLUSIONS)


class CorpusCoverageTests(unittest.TestCase):
    """Guard: every document is either searchable or excluded on purpose.

    The index used to be a hand-kept allowlist beside the Dockerfile's
    hand-kept ``COPY`` list, and the two drifted: documents were copied into
    the image that the retriever never read. The retriever now walks the
    copied tree, minus ``INDEX_EXCLUSIONS``. This closes the loop the other
    way: a document that is neither copied-and-indexed nor named in the
    exclusion list fails here, so coverage fails closed.
    """

    def test_every_document_is_indexed_or_excluded(self):
        sources = _docs_stage_sources()

        def staged(path):
            return any(path == src or path.startswith(src + "/")
                       for src in sources)

        missing = [path for path in _repository_documents()
                   if not _excluded(path)
                   and not (staged(path) and docs_retriever.is_indexed(path))]
        self.assertEqual(
            missing, [],
            "these documents are neither indexed by the consult corpus nor "
            "listed in INDEX_EXCLUSIONS (tools/mcp/common/docs_retriever.py); "
            "copy their directory in the docs stage of "
            "tools/mcp/manager/Dockerfile or exclude them on purpose: "
            f"{missing}")

    def test_every_exclusion_matches_a_document(self):
        # A stale exclusion is harmless today and a hole tomorrow: it would
        # silently hide whatever document next takes that path.
        documents = _repository_documents()
        for entry in docs_retriever.INDEX_EXCLUSIONS:
            self.assertTrue(
                any(_excluded(p) and (p.startswith(entry) or p == entry)
                    for p in documents),
                f"INDEX_EXCLUSIONS entry {entry!r} matches no document")


class SnapshotIndexPackagingTests(unittest.TestCase):
    """Guard: the snapshot index is built from the full tree and ships.

    The index must be computed before the docs stage prunes everything but
    markdown (the tree it describes is gone afterwards) and written outside
    the pruned tree, or the prune deletes it.
    """

    def test_index_is_built_before_the_prune(self):
        text = _docs_stage_text()
        build = text.find("snapshot_index.py")
        prune = text.find("! -name '*.md' ! -name '*.html' -delete")
        self.assertGreater(build, -1, "the docs stage must build the index")
        self.assertLess(build, prune,
                        "the index must be built before the prune")

    def test_index_is_written_outside_the_pruned_tree(self):
        match = re.search(r"--out\s+(\S+)", _docs_stage_text())
        self.assertIsNotNone(match)
        self.assertFalse(match.group(1).startswith("/src"),
                         f"the index at {match.group(1)} would be pruned")

    def test_corpus_commit_defaults_to_unknown(self):
        # A build that omits the argument must record an explicit sentinel,
        # never an empty string that reads like a commit.
        self.assertIn("ARG CORPUS_COMMIT=unknown", _docs_stage_text())
        self.assertIn('--commit "${CORPUS_COMMIT}"', _docs_stage_text())

    def test_index_ships_and_is_named_by_the_environment(self):
        text = _dockerfile_text()
        out = re.search(r"--out\s+(\S+)", _docs_stage_text()).group(1)
        out_dir = os.path.dirname(out)
        copy = re.search(rf"COPY --from=docs {re.escape(out_dir)} (\S+)", text)
        self.assertIsNotNone(copy, f"{out_dir} is not copied into the image")
        env = re.search(r"ENV AR_SNAPSHOT_INDEX=(\S+)", text)
        self.assertIsNotNone(env)
        self.assertEqual(env.group(1),
                         copy.group(1).rstrip("/") + "/" + os.path.basename(out))

    def test_scripts_are_staged_for_the_index(self):
        self.assertIn("COPY scripts/ ./scripts/", _docs_stage_text())

    def test_compose_passes_the_corpus_commit(self):
        compose = os.path.join(_REPO_ROOT, "flowtree", "runtime", "controller",
                               "docker-compose.yml")
        with open(compose) as handle:
            text = handle.read()
        self.assertIn("CORPUS_COMMIT: ${CORPUS_COMMIT:-unknown}", text)


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
