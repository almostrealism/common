"""Tests for the snapshot index generator and reader."""

import json
import os
import shutil
import sys
import tempfile
import unittest

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from snapshot_index import (  # noqa: E402
    SnapshotIndex, build, main, script_exits, script_header, script_signals,
)

_JAVA = '''package x;
import java.util.concurrent.CompletableFuture;
public class OopRule {
    static final String VERDICT_PATH = ".flowtree/oop-review.md";
    public static final int MAX_CLASSES = 12;
    private static final String COMPUTED = prefix() + "x";
    static class Inner { }
}
'''

_SCRIPT = '''#!/usr/bin/env bash
# ─── Reject change sets ───
#
# Exit codes:
#   0 - fine
#   3 - BLOCKED
set -e
if [ -z "$1" ]; then
    exit 1
fi
exit 3
'''


_DRAIN = '''#!/usr/bin/env bash
# Wait until the controller reports no in-flight jobs.
set -euo pipefail
count_running() {
    curl -fsS "${CONTROLLER_URL}/api/jobs?limit=100" | python3 -c 'import json, sys
ACTIVE = {"running", "in_progress", "started", "pending", "queued"}
print(len(ACTIVE))'
}
exit 0
'''


class _Tree(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="snapshot-index-test-")
        self._write("flowtree/src/OopRule.java", _JAVA)
        self._write("tools/ci/agent-protection/validate.sh", _SCRIPT)
        self._write("tools/ci/drain-agent-jobs.sh", _DRAIN)
        self._write("tools/ci/docker/entrypoint.sh", "#!/bin/sh\nexit 0\n")
        self._write("tools/ci/rocm/entrypoint.sh", "#!/bin/sh\nexit 2\n")
        self._write("tools/mcp/common/store.py",
                    '"""Store module."""\nclass MemoryStore:\n    pass\n')
        self._write("flowtree/target/Generated.java", "class Generated {}\n")
        self._write("docs/a.md", "# A\n")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as handle:
            handle.write(text)


class TestBuild(_Tree):

    def test_records_paths_but_not_build_output(self):
        data = build(self.root, "abc")
        self.assertIn("docs/a.md", data["paths"])
        self.assertNotIn("flowtree/target/Generated.java", data["paths"])

    def test_corpus_identity_defaults_to_unknown(self):
        self.assertEqual(build(self.root, "")["corpus"]["commit"], "unknown")
        self.assertEqual(build(self.root, "abc", "2026-10-19T00:00:00Z")["corpus"],
                         {"commit": "abc", "built_at": "2026-10-19T00:00:00Z"})

    def test_types_include_nested_java_and_python_classes(self):
        types = build(self.root)["types"]
        self.assertIn("OopRule", types)
        self.assertIn("Inner", types)
        self.assertIn("MemoryStore", types)

    def test_imported_library_types_are_recorded(self):
        self.assertIn("CompletableFuture", build(self.root)["external_types"])

    def test_script_basenames_keep_every_path(self):
        scripts = build(self.root)["scripts"]
        self.assertEqual(scripts["entrypoint.sh"],
                         ["tools/ci/docker/entrypoint.sh",
                          "tools/ci/rocm/entrypoint.sh"])

    def test_literal_constants_of_any_visibility(self):
        constants = build(self.root)["constants"]
        verdict = constants["VERDICT_PATH"][0]
        self.assertEqual(verdict["value"], ".flowtree/oop-review.md")
        self.assertEqual(verdict["visibility"], "package-private")
        self.assertEqual(verdict["owner"], "OopRule")
        self.assertEqual(constants["MAX_CLASSES"][0]["value"], "12")
        self.assertNotIn("COMPUTED", constants)

    def test_script_facts_hold_header_and_exit_codes(self):
        facts = build(self.root)["script_facts"]["tools/ci/agent-protection/validate.sh"]
        self.assertIn("3 - BLOCKED", facts["header"])
        self.assertEqual([code for _, code in facts["exits"]], [1, 3])

    def test_script_facts_hold_signals(self):
        facts = build(self.root)["script_facts"]["tools/ci/drain-agent-jobs.sh"]
        self.assertIn('ACTIVE = {"running", "in_progress", "started", '
                      '"pending", "queued"}', facts["signals"])
        self.assertIn("/api/jobs", facts["signals"])

    def test_main_writes_json(self):
        out = os.path.join(self.root, "out", "index.json")
        self.assertEqual(main(["--root", self.root, "--out", out, "--commit", " "]), 0)
        with open(out) as handle:
            self.assertEqual(json.load(handle)["corpus"]["commit"], "unknown")


class TestReader(_Tree):

    def setUp(self):
        super().setUp()
        self.index = SnapshotIndex(build(self.root, "abc"))

    def test_path_lookups(self):
        self.assertTrue(self.index.has_path("tools/ci/agent-protection/validate.sh"))
        self.assertTrue(self.index.has_path("agent-protection/validate.sh"))
        self.assertTrue(self.index.has_path("validate.sh"))
        self.assertTrue(self.index.has_path("tools/ci/"))
        self.assertTrue(self.index.has_path("tools/ci/**"))
        self.assertTrue(self.index.has_path("tools/ci/agent-protection/validate.sh:10"))
        self.assertFalse(self.index.has_path("check-branch-entropy.sh"))

    def test_tree_paths_are_rooted_in_a_top_level_directory(self):
        self.assertTrue(self.index.is_tree_path("tools/x/y"))
        self.assertFalse(self.index.is_tree_path("qa/docs-"))

    def test_type_lookups(self):
        self.assertTrue(self.index.has_type("OopRule"))
        self.assertTrue(self.index.has_type("CompletableFuture"))
        self.assertFalse(self.index.has_type("ClaudeCodeJob"))

    def test_facts_for_a_script_and_a_type(self):
        facts = self.index.facts_for(["validate.sh", "OopRule"], limit=10)
        labels = [f["label"] for f in facts]
        self.assertIn("exit codes", labels)
        self.assertIn("header comment", labels)
        self.assertIn("constant OopRule.VERDICT_PATH", labels)
        exits = next(f for f in facts if f["label"] == "exit codes")
        self.assertIn("exit 3", exits["value"])
        self.assertEqual(exits["line"], 11)

    def test_facts_for_a_constant_name(self):
        facts = self.index.facts_for(["MAX_CLASSES"])
        self.assertEqual(len(facts), 1)
        self.assertIn("OopRule.MAX_CLASSES = 12", facts[0]["value"])

    def test_a_script_resolves_by_its_stem(self):
        # A keyword naming a script without its extension still reaches it.
        self.assertEqual(self.index.script_paths("drain-agent-jobs"),
                         ["tools/ci/drain-agent-jobs.sh"])
        facts = self.index.facts_for(["drain-agent-jobs"], limit=10)
        behavior = next(f for f in facts if f["label"] == "behavior")
        self.assertIn("ACTIVE =", behavior["value"])
        self.assertIn("/api/jobs", behavior["value"])

    def test_an_exact_basename_still_wins_over_a_stem(self):
        self.assertEqual(self.index.script_paths("validate.sh"),
                         ["tools/ci/agent-protection/validate.sh"])

    def test_facts_respect_the_limit(self):
        self.assertEqual(len(self.index.facts_for(["validate.sh", "OopRule"], 2)), 2)

    def test_load_of_a_missing_file_is_none(self):
        self.assertIsNone(SnapshotIndex.load(os.path.join(self.root, "none.json")))
        self.assertIsNone(SnapshotIndex.load(""))


class TestScriptParsing(unittest.TestCase):

    def test_python_header_is_its_docstring(self):
        self.assertEqual(script_header('#!/usr/bin/env python3\n"""Does X.\n\nMore."""\n',
                                       ".py"), "Does X.\n\nMore.")

    def test_signals_capture_named_collections_and_api_paths(self):
        signals = script_signals(
            'ACTIVE = {"running", "pending"}\n'
            'curl "${URL}/api/jobs?limit=100"\n')
        self.assertIn('ACTIVE = {"running", "pending"}', signals)
        self.assertIn("API paths: /api/jobs", signals)

    def test_signals_are_empty_for_a_script_without_them(self):
        self.assertEqual(script_signals("set -e\nexit 0\n"), "")

    def test_commented_exits_are_ignored(self):
        self.assertEqual(script_exits("# exit 9\nexit 4\n", ".sh"), [[2, 4]])

    def test_python_exits(self):
        self.assertEqual(script_exits("sys.exit(2)\nraise SystemExit(5)\n", ".py"),
                         [[1, 2], [2, 5]])


if __name__ == "__main__":
    unittest.main()
