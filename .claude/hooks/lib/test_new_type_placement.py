"""Tests for the new-type placement hook (warn-new-type-placement.py).

The hook is the type-level counterpart of warn-method-placement.py: it warns
when an edit introduces a new top-level Java type, escalates the warning when no
discovery is on record, and blocks in the single mechanical case of a
Util/Helper/Exporter/Converter/Manager-named type. These tests hold all three
behaviours at once, and — like the interface-bypass suite this is modelled on —
guard against the two ways a hook test passes vacuously:

  1. REPO is resolved by counting the actual directory depth from this file, and
     a sanity test asserts it really is the repository root, so a miscount that
     silently pointed the run at nowhere would fail loudly here rather than make
     every downstream assertion pass against an empty tree.
  2. Every end-to-end fixture first asserts, in-process, that the hook's parser
     actually SEES the construct the case is about (the type is added, or the
     existing type is present in both before and after) — so an "allowed" or
     "blocked" verdict cannot pass merely because the parser found nothing.
"""

import json
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HOOK = os.path.join(HOOKS, "warn-new-type-placement.py")

# .claude/hooks/lib -> .claude/hooks -> .claude -> the repository root.
REPO = os.path.dirname(os.path.dirname(HOOKS))

# A user name unique to these tests so the /tmp discovery markers they create
# never collide with a real session's markers, and vice versa.
TEST_USER = "ar_newtype_hooktest"
DISCOVERY_MARKER = "/tmp/.ar_discovery_last_" + TEST_USER + ".ts"
CONSULT_MARKER = "/tmp/.ar_consultant_last_" + TEST_USER + ".ts"

CLASS_WITH_MARKER = (
    "package org.example;\n\n"
    "public class Foo {\n"
    "\tprivate int x;\n"
    "\t// members\n"
    "}\n"
)

HELPER_WITH_MARKER = (
    "package org.example;\n\n"
    "public class FooHelper {\n"
    "\tprivate int x;\n"
    "\t// members\n"
    "}\n"
)

NEW_CLASS = (
    "package org.example;\n\n"
    "public class Widget {\n"
    "\tprivate int x;\n"
    "}\n"
)


def load_hook():
    """Imports the hook script as a module."""
    spec = importlib.util.spec_from_file_location("warn_new_type_placement", HOOK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RepoLayoutTest(unittest.TestCase):
    """The depth counting that resolves REPO must land on the real repo root.

    If it did not, git-backed or file-backed lookups would silently resolve
    nothing and the rest of the suite would pass against emptiness. This is the
    trap the interface-bypass suite warns about, asserted directly.
    """

    def test_hook_script_exists(self):
        self.assertTrue(os.path.isfile(HOOK), HOOK)

    def test_repo_root_is_the_repository(self):
        self.assertTrue(os.path.isfile(os.path.join(REPO, "CLAUDE.md")),
                        "REPO did not resolve to the repository root: " + REPO)


class ParserTest(unittest.TestCase):
    """The pieces the verdict is built from, tested in-process."""

    def setUp(self):
        self.hook = load_hook()

    def test_top_level_type_is_seen(self):
        seen = self.hook.top_level_types(NEW_CLASS)
        self.assertIn(("class", "Widget"), seen)

    def test_nested_type_is_excluded(self):
        source = ("public class Outer {\n"
                  "\tpublic class Inner {\n\t}\n"
                  "\tenum Mode { A, B }\n"
                  "}\n")
        seen = self.hook.top_level_types(source)
        self.assertIn(("class", "Outer"), seen,
                      "the top-level type must be seen")
        self.assertNotIn(("class", "Inner"), seen,
                         "a nested type is not a new top-level concept")
        self.assertNotIn(("enum", "Mode"), seen)

    def test_interface_enum_record_kinds_are_seen(self):
        source = ("interface Alpha {}\n"
                  "enum Beta { X }\n"
                  "record Gamma(int v) {}\n")
        seen = self.hook.top_level_types(source)
        self.assertIn(("interface", "Alpha"), seen)
        self.assertIn(("enum", "Beta"), seen)
        self.assertIn(("record", "Gamma"), seen)

    def test_added_types_ignores_pre_existing(self):
        added = self.hook.added_types(NEW_CLASS, NEW_CLASS)
        self.assertEqual([], added, "an unchanged type is not an addition")

    def test_blocked_matches_declared_suffix(self):
        added = [("class", "FooHelper"), ("class", "Widget"),
                 ("class", "StringUtils"), ("interface", "TaskManager")]
        blocked = self.hook.blocked(added)
        blocked_names = {name for _, name in blocked}
        self.assertEqual({"FooHelper", "StringUtils", "TaskManager"},
                         blocked_names)


class EndToEndTest(unittest.TestCase):
    """The verdict over the PreToolUse contract: exit 2 blocks, exit 0 allows."""

    def setUp(self):
        self.hook = load_hook()
        self._clear_markers()

    def tearDown(self):
        self._clear_markers()

    def _clear_markers(self):
        for marker in (DISCOVERY_MARKER, CONSULT_MARKER):
            try:
                os.remove(marker)
            except OSError:
                pass

    def assertAdds(self, before, after, name):
        """The hook must actually parse `name` as an added top-level type."""
        added = self.hook.added_types(before, after)
        self.assertIn(name, [n for _, n in added],
                      "the fixture did not present " + name + " as an added type")

    def assertPresentUnchanged(self, before, after, name):
        """`name` exists in both texts and is NOT counted as added.

        Proves an "allowed" verdict on an existing-class edit is meaningful:
        the class really is there, the edit really did not add it.
        """
        self.assertIn(name, [n for _, n in self.hook.top_level_types(before)],
                      name + " must exist before the edit")
        self.assertIn(name, [n for _, n in self.hook.top_level_types(after)],
                      name + " must still exist after the edit")
        self.assertEqual([], self.hook.added_types(before, after),
                         "the edit must not add a top-level type")

    def run_payload(self, payload, discovery=False, consult=False):
        """Runs the hook end to end under a controlled USER and marker state."""
        self._clear_markers()
        if discovery:
            with open(DISCOVERY_MARKER, "w") as f:
                f.write("1")
        if consult:
            with open(CONSULT_MARKER, "w") as f:
                f.write("1")
        env = dict(os.environ, USER=TEST_USER)
        proc = subprocess.run(
            [sys.executable, HOOK], input=json.dumps(payload),
            capture_output=True, text=True, cwd=REPO, env=env, timeout=120)
        return proc

    def write_payload(self, file_path, content):
        return {"tool_name": "Write",
                "tool_input": {"file_path": file_path, "content": content}}

    def edit_payload(self, file_path, old, new):
        return {"tool_name": "Edit",
                "tool_input": {"file_path": file_path, "old_string": old,
                               "new_string": new}}

    def test_new_plain_class_warns(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "Widget.java")
            self.assertAdds("", NEW_CLASS, "Widget")
            proc = self.run_payload(self.write_payload(path, NEW_CLASS),
                                    discovery=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        out = json.loads(proc.stdout)
        self.assertIn("Widget",
                      out["hookSpecificOutput"]["additionalContext"])

    def test_edit_to_existing_class_does_not_fire(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "Foo.java")
            with open(path, "w") as f:
                f.write(CLASS_WITH_MARKER)
            new_method = "\tpublic int getX() {\n\t\treturn x;\n\t}\n"
            after = CLASS_WITH_MARKER.replace("\t// members\n", new_method)
            self.assertPresentUnchanged(CLASS_WITH_MARKER, after, "Foo")
            proc = self.run_payload(
                self.edit_payload(path, "\t// members\n", new_method))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("", proc.stdout.strip(),
                         "an ordinary edit must emit nothing")

    def test_new_helper_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "FooHelper.java")
            helper = NEW_CLASS.replace("Widget", "FooHelper")
            self.assertAdds("", helper, "FooHelper")
            proc = self.run_payload(self.write_payload(path, helper),
                                    discovery=True)
        self.assertEqual(2, proc.returncode, proc.stdout)
        self.assertIn("FooHelper", proc.stderr)

    def test_block_matches_declared_name_not_file_name(self):
        """A helper declared in a non-helper-named file is still blocked;
        a plain class in a helper-named file is not."""
        with tempfile.TemporaryDirectory() as tmp:
            declared_helper = NEW_CLASS.replace("Widget", "BarHelper")
            path = os.path.join(tmp, "Widget.java")
            self.assertAdds("", declared_helper, "BarHelper")
            proc = self.run_payload(self.write_payload(path, declared_helper),
                                    discovery=True)
            self.assertEqual(2, proc.returncode, proc.stdout)

            plain = NEW_CLASS.replace("Widget", "Gadget")
            helper_path = os.path.join(tmp, "FooHelper.java")
            self.assertAdds("", plain, "Gadget")
            proc = self.run_payload(self.write_payload(helper_path, plain),
                                    discovery=True)
            self.assertEqual(0, proc.returncode, proc.stderr)

    def test_existing_helper_being_edited_does_not_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "FooHelper.java")
            with open(path, "w") as f:
                f.write(HELPER_WITH_MARKER)
            new_method = "\tpublic int getX() {\n\t\treturn x;\n\t}\n"
            after = HELPER_WITH_MARKER.replace("\t// members\n", new_method)
            self.assertPresentUnchanged(HELPER_WITH_MARKER, after, "FooHelper")
            proc = self.run_payload(
                self.edit_payload(path, "\t// members\n", new_method))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("", proc.stdout.strip())

    def test_escalated_wording_when_no_discovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "Widget.java")
            self.assertAdds("", NEW_CLASS, "Widget")
            proc = self.run_payload(self.write_payload(path, NEW_CLASS),
                                    discovery=False, consult=False)
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("No discovery is on record", context)

    def test_no_escalation_when_discovery_on_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "Widget.java")
            self.assertAdds("", NEW_CLASS, "Widget")
            proc = self.run_payload(self.write_payload(path, NEW_CLASS),
                                    discovery=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertNotIn("No discovery is on record", context)

    def test_consult_marker_alone_counts_as_discovery(self):
        """The consult marker (track-consultant-call.sh) satisfies the signal."""
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "Widget.java")
            proc = self.run_payload(self.write_payload(path, NEW_CLASS),
                                    discovery=False, consult=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertNotIn("No discovery is on record", context)

    def test_non_java_file_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "notes.md")
            proc = self.run_payload(
                self.write_payload(path, "class FooHelper {}\n"))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("", proc.stdout.strip())


if __name__ == "__main__":
    unittest.main()
