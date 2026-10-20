"""Tests for the interface-contract bypass hook (block-interface-bypass.py).

The hook blocks a real failure mode — a class adapting a concrete type behind an
interface, then handing that concrete type back out — and the tests here hold two
things at once: that the canonical incident is still refused, and that the shapes
which merely resemble it are not.

The hook is a standalone script rather than a shared core, so it is loaded by path
and driven two ways: its decision functions directly, and end to end over the
PreToolUse stdin contract, since the exit code is what actually stops an edit.
"""

import json
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HOOK = os.path.join(HOOKS, "block-interface-bypass.py")

# .claude/hooks/lib -> .claude/hooks -> .claude -> the repository root. type_kind()
# resolves type names with `git ls-files` against repo-relative pathspecs, so the
# hook only identifies types when it runs from here.
REPO = os.path.dirname(os.path.dirname(HOOKS))


def load_hook():
    """Imports the hook script as a module."""
    spec = importlib.util.spec_from_file_location("block_interface_bypass", HOOK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RepoDirectoryTest(unittest.TestCase):
    """Base for tests that need the hook's type resolution to work."""

    def setUp(self):
        self.hook = load_hook()
        self.cwd = os.getcwd()
        os.chdir(REPO)

    def tearDown(self):
        os.chdir(self.cwd)


class DecisionTest(RepoDirectoryTest):
    """The pieces the block decision is made of."""

    def adapted(self, source, class_name):
        return self.hook.constructor_adapted_types(source, class_name)

    def test_concrete_constructor_parameter_is_adapted(self):
        """A concrete type a constructor takes is a candidate for re-exposure."""
        source = "public class Router {\n\tpublic Router(WaveOutput out) {\n\t}\n}\n"
        self.assertIn("WaveOutput", self.adapted(source, "Router"))

    def test_own_type_is_not_adapted(self):
        """A copy constructor does not make the class adapt itself."""
        source = ("public class Ray {\n\tpublic Ray(Ray src) {\n\t}\n"
                  "\tpublic Ray(WaveOutput out) {\n\t}\n}\n")
        result = self.adapted(source, "Ray")
        self.assertNotIn("Ray", result)
        self.assertIn("WaveOutput", result, "other concrete types still count")

    def test_interface_constructor_parameter_is_not_adapted(self):
        """Taking the interface is the compliant shape, so nothing is adapted."""
        source = "public class Router {\n\tpublic Router(Receptor r) {\n\t}\n}\n"
        self.assertNotIn("Receptor", self.adapted(source, "Router"))

    def test_exposed_types_reads_public_members_only(self):
        source = ("public class Holder {\n"
                  "\tpublic TraversalPolicy getShape() {\n\t\treturn null;\n\t}\n"
                  "\tprivate WaveOutput hidden() {\n\t\treturn null;\n\t}\n}\n")
        exposed = self.hook.exposed_types(source)
        self.assertIn("TraversalPolicy", exposed)
        self.assertNotIn("WaveOutput", exposed)


class EndToEndTest(unittest.TestCase):
    """The verdict over the PreToolUse contract: exit 2 blocks, exit 0 allows."""

    # Members are inserted at this marker rather than before the closing brace: a
    # fixture body written `{ }` ends in "}\n" too, and splicing there puts the new
    # member mid-line where the declaration parser cannot see it, which makes an
    # "allowed" result meaningless. assertSeesMember fails if that happens again.
    MARKER = "\t// members\n"

    ROUTER = ("public class Router {\n"
              "\tpublic Router(java.util.List<WaveOutput> outputs) {\n\t}\n"
              "\tpublic Receptor getChannel(ChannelInfo info) {\n"
              "\t\treturn null;\n\t}\n"
              "\t// members\n"
              "}\n")

    def setUp(self):
        self.hook = load_hook()

    def assertSeesMember(self, before, after, name):
        """The hook must actually parse the inserted member as added."""
        cwd = os.getcwd()
        os.chdir(REPO)
        try:
            b, a = self.hook.methods(before), self.hook.methods(after)
            added = [entry for entry in a if a.count(entry) > b.count(entry)]
        finally:
            os.chdir(cwd)

        self.assertIn(name, [entry[0] for entry in added],
                      "the fixture did not present " + name + " as an added member")

    def run_hook(self, before, member, name, class_name="Router"):
        """Runs the hook for an edit inserting a member at the marker.

        @return (exit code, stderr)
        """
        self.assertIn(self.MARKER, before, "the fixture needs the insertion marker")
        self.assertSeesMember(before, before.replace(self.MARKER, member), name)

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, class_name + ".java")
            with open(path, "w") as f:
                f.write(before)

            payload = {
                "tool_name": "Edit",
                "tool_input": {
                    "file_path": path,
                    "old_string": self.MARKER,
                    "new_string": member,
                },
            }
            proc = subprocess.run(
                [sys.executable, HOOK], input=json.dumps(payload),
                capture_output=True, text=True, cwd=REPO, timeout=120)
            return proc.returncode, proc.stderr

    def test_re_exposing_an_adapted_type_is_blocked(self):
        """The incident this hook exists for: the router hands WaveOutput out."""
        code, stderr = self.run_hook(
            self.ROUTER,
            "\tpublic WaveOutput getStemOutput() {\n\t\treturn null;\n\t}\n",
            "getStemOutput")
        self.assertEqual(2, code, stderr)
        self.assertIn("WaveOutput", stderr)

    def test_a_new_member_cannot_exempt_itself(self):
        """The already-exposed exclusion reads the text BEFORE the edit.

        Were it to read the edited text, the member being judged would count as an
        existing exposure of its own return type and nothing would ever be blocked.
        """
        code, stderr = self.run_hook(
            self.ROUTER,
            "\tpublic WaveOutput first() {\n\t\treturn null;\n\t}\n"
            "\tpublic WaveOutput second() {\n\t\treturn null;\n\t}\n",
            "first")
        self.assertEqual(2, code, stderr)

    def test_returning_the_class_itself_is_allowed(self):
        """A static factory of the class is not a bypass of anything."""
        source = ("public class Ray {\n"
                  "\tpublic Ray(Ray src) {\n\t}\n"
                  "\tpublic Producer<Ray> producer() {\n\t\treturn null;\n\t}\n"
                  "\t// members\n"
                  "}\n")
        code, stderr = self.run_hook(
            source, "\tpublic static Ray of(double x) {\n\t\treturn null;\n\t}\n",
            "of", class_name="Ray")
        self.assertEqual(0, code, stderr)

    def test_returning_an_already_exposed_type_is_allowed(self):
        """The class already publishes the type its constructor takes."""
        source = ("public class Holder {\n"
                  "\tpublic Holder(TraversalPolicy shape) {\n\t}\n"
                  "\tpublic TraversalPolicy getShape() {\n\t\treturn null;\n\t}\n"
                  "\tpublic Producer<Holder> producer() {\n\t\treturn null;\n\t}\n"
                  "\t// members\n"
                  "}\n")
        code, stderr = self.run_hook(
            source,
            "\tpublic TraversalPolicy shapeFor(int axis) {\n\t\treturn null;\n\t}\n",
            "shapeFor", class_name="Holder")
        self.assertEqual(0, code, stderr)

    def test_private_member_is_not_blocked(self):
        code, stderr = self.run_hook(
            self.ROUTER,
            "\tprivate WaveOutput internal() {\n\t\treturn null;\n\t}\n",
            "internal")
        self.assertEqual(0, code, stderr)

    def test_returning_the_interface_is_allowed(self):
        code, stderr = self.run_hook(
            self.ROUTER, "\tpublic Receptor getStem() {\n\t\treturn null;\n\t}\n",
            "getStem")
        self.assertEqual(0, code, stderr)

    def test_non_java_file_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "notes.md")
            with open(path, "w") as f:
                f.write("WaveOutput\n")

            payload = {
                "tool_name": "Edit",
                "tool_input": {"file_path": path, "old_string": "WaveOutput",
                               "new_string": "WaveOutput getStemOutput()"},
            }
            proc = subprocess.run(
                [sys.executable, HOOK], input=json.dumps(payload),
                capture_output=True, text=True, cwd=REPO, timeout=120)
            self.assertEqual(0, proc.returncode, proc.stderr)


class RepositoryTest(RepoDirectoryTest):
    """Against a real class in the repository, which is where this was found."""

    PACKED = os.path.join(
        "compute", "algebra", "src", "main", "java", "org", "almostrealism",
        "collect", "PackedCollection.java")

    def test_packed_collection_admits_a_new_factory(self):
        """PackedCollection has a copy constructor, eight constructors and members
        returning interfaces. Before the exclusions, every new member returning the
        class or its shape type was refused — including the factories the class
        already has.
        """
        if not os.path.exists(self.PACKED):
            self.skipTest("PackedCollection is not in this checkout")

        with open(self.PACKED) as f:
            source = f.read()

        adapted = self.hook.constructor_adapted_types(source, "PackedCollection")
        adapted -= self.hook.exposed_types(source)

        self.assertNotIn("PackedCollection", adapted,
                         "a factory returning the class must be admissible")
        self.assertNotIn("TraversalPolicy", adapted,
                         "getShape() already publishes the shape type")


if __name__ == "__main__":
    unittest.main()
