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

# A synthetic repository modelled on the incident the duplicate-type block
# exists for: an arithmetic index sequence, the interface it implements, and
# unrelated neighbours that must never be reported as candidates.
SEQ = "mod/src/main/java/org/example/seq/"

FIXTURE_SOURCES = {
    SEQ + "IndexSequence.java": (
        "package org.example.seq;\n\n"
        "/**\n * A sequence of index values, addressed by position.\n */\n"
        "public interface IndexSequence {\n"
        "\tlong valueAt(int pos);\n"
        "\tint length();\n"
        "}\n"),
    SEQ + "ArithmeticIndexSequence.java": (
        "package org.example.seq;\n\n"
        "/**\n"
        " * An {@link IndexSequence} that represents arithmetic sequences of index\n"
        " * values: {@code offset + scale * pos}. Memory access in kernels often\n"
        " * follows such regular arithmetic progressions.\n"
        " */\n"
        "public class ArithmeticIndexSequence implements IndexSequence {\n"
        "\tprivate final long offset;\n"
        "\tprivate final long scale;\n"
        "\tprivate final int len;\n\n"
        "\tpublic ArithmeticIndexSequence(long offset, long scale, int len) {\n"
        "\t\tthis.offset = offset;\n\t\tthis.scale = scale;\n\t\tthis.len = len;\n"
        "\t}\n\n"
        "\tpublic long valueAt(int pos) { return offset + scale * pos; }\n\n"
        "\tpublic int length() { return len; }\n\n"
        "\tpublic ArithmeticIndexSequence scaled(long factor) {\n"
        "\t\treturn new ArithmeticIndexSequence(offset * factor, scale * factor, len);\n"
        "\t}\n"
        "\t// members\n"
        "}\n"),
    "mod/src/main/java/org/example/audio/WaveFilter.java": (
        "package org.example.audio;\n\n"
        "/** A low-pass filter applied to audio waveforms. */\n"
        "public class WaveFilter {\n"
        "\tpublic double cutoff() { return 0; }\n"
        "}\n"),
    "mod/src/main/java/org/example/text/TokenStream.java": (
        "package org.example.text;\n\n"
        "/** Tokens read from a body of text, one word at a time. */\n"
        "public class TokenStream {\n"
        "\tpublic String next() { return null; }\n"
        "}\n"),
}

# Unrelated neighbours, so that word rarity in the fixture behaves as it does
# in a real repository rather than in a four-type toy.
for _concept, _doc in (("ColorPalette", "A named collection of display colors."),
                       ("MeshVertex", "A point of a triangle mesh in model space."),
                       ("CurrencyAmount", "A sum of money in one currency."),
                       ("RetryPolicy", "When and how often a failed request is retried."),
                       ("ShaderProgram", "A compiled program run on the graphics card."),
                       ("UserAccount", "The login and profile of a person using the service."),
                       ("ChordVoicing", "The notes of a musical chord and their octaves."),
                       ("SensorReading", "One measurement reported by a hardware sensor."),
                       ("LogRotation", "When old log files are archived and removed."),
                       ("TaxBracket", "A range of income taxed at a single rate.")):
    FIXTURE_SOURCES["mod/src/main/java/org/example/misc/" + _concept + ".java"] = (
        "package org.example.misc;\n\n/** " + _doc + " */\npublic class " + _concept
        + " {\n\tpublic int id() { return 0; }\n}\n")

NAMESAKE = (
    "package org.example.seq;\n\n"
    "import java.util.AbstractSet;\n\n"
    "/**\n * An immutable set of the integers {@code start + k * step}.\n */\n"
    "public final class ArithmeticIndexSet extends AbstractSet<Integer> {\n"
    "\tpublic boolean contains(Object o) { return false; }\n"
    "}\n")

RENAMED = (
    "package org.example.seq;\n\n"
    "/**\n * An arithmetic progression of index values.\n */\n"
    "public final class IndexProgression {\n"
    "\tpublic long valueAt(int pos) { return 0; }\n"
    "}\n")

GENUINE = (
    "package org.example.net;\n\n"
    "/**\n * Routes network packets between peers over a mesh.\n */\n"
    "public final class PacketRouter {\n"
    "\tpublic void route(byte[] packet) { }\n"
    "}\n")

BUILDS_ON_CANDIDATE = NAMESAKE.replace(
    "extends AbstractSet<Integer>", "extends ArithmeticIndexSequence")

REASONS = {
    "cannot_extend": "the candidate is final in a sibling library we do not own",
    "cannot_change_signature": "the signature is fixed by a published external SPI",
    "interface_would_not_work": "the SPI requires a concrete class, not an interface",
}


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

    def test_assess_added_skips_non_main_source(self):
        """A type outside src/main/java is never scored against the index: a
        generated or examples tree is not blocked, matching the main-source gate.
        """
        added = [("class", "IndexProgression")]
        self.assertEqual(([], []),
                         self.hook.assess_added("/x/src/generated/java/p/IndexProgression.java",
                                                RENAMED, added))
        self.assertEqual(([], []),
                         self.hook.assess_added("/x/src/test/java/p/IndexProgression.java",
                                                RENAMED, added))


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

    # -- Duplicate-type block, against a synthetic repository -------------

    def make_repo(self, tmp):
        """Lays FIXTURE_SOURCES out as a repository rooted at ``tmp``."""
        os.mkdir(os.path.join(tmp, ".git"))
        for rel, text in FIXTURE_SOURCES.items():
            full = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w") as f:
                f.write(text)
        return tmp

    def run_in_repo(self, tmp, payload):
        """Runs the hook against the synthetic repository at ``tmp``, with
        the index cached inside it so no real cache is touched."""
        env = dict(os.environ, USER=TEST_USER,
                   AR_TYPE_INDEX_CACHE=os.path.join(tmp, "index.json"))
        env.pop("AR_TYPE_INDEX_ROOT", None)
        with open(DISCOVERY_MARKER, "w") as f:
            f.write("1")
        return subprocess.run(
            [sys.executable, HOOK], input=json.dumps(payload),
            capture_output=True, text=True, cwd=tmp, env=env, timeout=120)

    def candidates_for(self, tmp, rel, source, name):
        """The candidates the scorer reports in-process: the fixture check
        that keeps a block or pass from being vacuous."""
        lib = os.path.join(HOOKS, "lib")
        if lib not in sys.path:
            sys.path.insert(0, lib)
        import type_equivalence
        os.environ["AR_TYPE_INDEX_CACHE"] = os.path.join(tmp, "probe.json")
        try:
            index = type_equivalence.TypeIndex.load(tmp)
        finally:
            del os.environ["AR_TYPE_INDEX_CACHE"]
        result = type_equivalence.assess(index, source, rel, name, [])
        return [c["fqn"] for c in result["candidates"]]

    def write_ledger(self, tmp, fqn, candidates):
        path = os.path.join(tmp, ".claude", "new-type-ledger.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump({"types": [{
                "type": fqn,
                "test": "ArithmeticIndexSetTest#distinguishesFromSequence",
                "candidates": {c: REASONS for c in candidates}}]}, f)

    def test_namesake_type_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "ArithmeticIndexSet.java"
            self.assertAdds("", NAMESAKE, "ArithmeticIndexSet")
            self.assertIn("org.example.seq.ArithmeticIndexSequence",
                          self.candidates_for(tmp, rel, NAMESAKE, "ArithmeticIndexSet"))
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), NAMESAKE))
        self.assertEqual(2, proc.returncode, proc.stdout)
        self.assertIn("org.example.seq.ArithmeticIndexSequence", proc.stderr)
        self.assertIn(SEQ + "ArithmeticIndexSequence.java", proc.stderr)
        self.assertNotIn("WaveFilter", proc.stderr)
        self.assertNotIn("TokenStream", proc.stderr)

    def test_block_message_orders_remedies_and_rejects_excuses(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "ArithmeticIndexSet.java"
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), NAMESAKE))
        self.assertEqual(2, proc.returncode, proc.stdout)
        text = proc.stderr
        order = [text.find("1. USE"), text.find("2. CHANGE THE SIGNATURE"),
                 text.find("3. EXTEND OR IMPROVE"), text.find("4. Only then")]
        self.assertTrue(all(i >= 0 for i in order), text)
        self.assertEqual(sorted(order), order, "remedies must appear in order")
        self.assertIn("the existing type does not implement interface X", text)
        self.assertIn("the method\nsignature requires Y", text)
        self.assertIn("Renaming\nthe new type is NOT a remedy", text)

    def test_renamed_equivalent_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "IndexProgression.java"
            self.assertAdds("", RENAMED, "IndexProgression")
            self.assertIn("org.example.seq.ArithmeticIndexSequence",
                          self.candidates_for(tmp, rel, RENAMED, "IndexProgression"))
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), RENAMED))
        self.assertEqual(2, proc.returncode, proc.stdout)
        self.assertIn("org.example.seq.ArithmeticIndexSequence", proc.stderr)

    def test_genuinely_new_concept_keeps_warning(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = "mod/src/main/java/org/example/net/PacketRouter.java"
            self.assertAdds("", GENUINE, "PacketRouter")
            self.assertEqual([], self.candidates_for(tmp, rel, GENUINE, "PacketRouter"))
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), GENUINE))
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("PacketRouter", context)
        self.assertIn("generality ladder", context)

    def test_type_extending_candidate_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "ArithmeticIndexSet.java"
            self.assertIn("org.example.seq.ArithmeticIndexSequence",
                          self.candidates_for(tmp, rel, BUILDS_ON_CANDIDATE,
                                              "ArithmeticIndexSet"))
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), BUILDS_ON_CANDIDATE))
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("extends or implements ArithmeticIndexSequence", context)

    def test_type_delegating_to_candidate_passes(self):
        delegating = RENAMED.replace(
            "public final class IndexProgression {\n",
            "public final class IndexProgression {\n"
            "\tprivate final ArithmeticIndexSequence sequence = null;\n")
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "IndexProgression.java"
            self.assertIn("org.example.seq.ArithmeticIndexSequence",
                          self.candidates_for(tmp, rel, delegating, "IndexProgression"))
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), delegating))
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("delegates to ArithmeticIndexSequence", context)

    def test_ledger_covering_every_candidate_lifts_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "IndexProgression.java"
            found = self.candidates_for(tmp, rel, RENAMED, "IndexProgression")
            self.assertGreaterEqual(len(found), 2,
                                    "the fixture must list several candidates")
            self.write_ledger(tmp, "org.example.seq.IndexProgression", found)
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), RENAMED))
        self.assertEqual(0, proc.returncode, proc.stderr)
        context = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("covers every candidate", context)

    def test_ledger_missing_a_candidate_still_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "IndexProgression.java"
            found = self.candidates_for(tmp, rel, RENAMED, "IndexProgression")
            self.assertGreaterEqual(len(found), 2,
                                    "the fixture must list several candidates")
            self.write_ledger(tmp, "org.example.seq.IndexProgression", found[:-1])
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), RENAMED))
        self.assertEqual(2, proc.returncode, proc.stdout)
        self.assertIn("candidate " + found[-1] + " is not justified", proc.stderr)

    def test_ledger_without_test_still_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "IndexProgression.java"
            found = self.candidates_for(tmp, rel, RENAMED, "IndexProgression")
            self.write_ledger(tmp, "org.example.seq.IndexProgression", found)
            path = os.path.join(tmp, ".claude", "new-type-ledger.json")
            with open(path) as f:
                data = json.load(f)
            del data["types"][0]["test"]
            with open(path, "w") as f:
                json.dump(data, f)
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), RENAMED))
        self.assertEqual(2, proc.returncode, proc.stdout)
        self.assertIn("no test named", proc.stderr)

    def test_edit_to_existing_type_in_repo_never_fires(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = SEQ + "ArithmeticIndexSequence.java"
            before = FIXTURE_SOURCES[rel]
            new_method = "\tpublic long first() {\n\t\treturn offset;\n\t}\n"
            after = before.replace("\t// members\n", new_method)
            self.assertPresentUnchanged(before, after, "ArithmeticIndexSequence")
            proc = self.run_in_repo(tmp, self.edit_payload(
                os.path.join(tmp, rel), "\t// members\n", new_method))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("", proc.stdout.strip(), "an ordinary edit must emit nothing")

    def test_new_test_class_is_not_checked_for_equivalents(self):
        test_source = NAMESAKE.replace("ArithmeticIndexSet", "ArithmeticIndexSequenceTest")
        with tempfile.TemporaryDirectory() as tmp:
            self.make_repo(tmp)
            rel = "mod/src/test/java/org/example/seq/ArithmeticIndexSequenceTest.java"
            self.assertAdds("", test_source, "ArithmeticIndexSequenceTest")
            proc = self.run_in_repo(tmp, self.write_payload(
                os.path.join(tmp, rel), test_source))
        self.assertEqual(0, proc.returncode, proc.stderr)



class ScanTest(unittest.TestCase):
    """The harness-facing ``scan`` and the candidate exclusions it relies on."""

    def setUp(self):
        lib = os.path.join(HOOKS, "lib")
        if lib not in sys.path:
            sys.path.insert(0, lib)
        import type_equivalence
        self.te = type_equivalence

    def index(self, tmp):
        """Loads the index of ``tmp`` with its cache kept inside ``tmp``."""
        os.environ["AR_TYPE_INDEX_CACHE"] = os.path.join(tmp, "scan-index.json")
        try:
            return self.te.TypeIndex.load(tmp)
        finally:
            del os.environ["AR_TYPE_INDEX_CACHE"]

    def write(self, tmp, rel, text):
        full = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    def test_equivalent_in_the_same_file_is_still_a_candidate(self):
        rel = SEQ + "ArithmeticIndexSequence.java"
        sibling = RENAMED.replace("package org.example.seq;\n\n", "").replace(
            "public final class", "final class")
        combined = FIXTURE_SOURCES[rel] + "\n" + sibling
        with tempfile.TemporaryDirectory() as tmp:
            EndToEndTest.make_repo(self, tmp)
            self.write(tmp, rel, combined)
            result = self.te.assess(self.index(tmp), combined, rel, "IndexProgression", [])
        self.assertEqual("org.example.seq.IndexProgression", result["fqn"])
        self.assertIn("org.example.seq.ArithmeticIndexSequence",
                      [c["fqn"] for c in result["candidates"]],
                      "a type declared beside the new one must not be hidden from the search")

    def test_scan_reports_every_added_type_and_excludes_them_as_candidates(self):
        progression = SEQ + "IndexProgression.java"
        cursor_source = RENAMED.replace("IndexProgression", "IndexProgressionCursor")
        cursor = SEQ + "IndexProgressionCursor.java"
        router = "mod/src/main/java/org/example/net/PacketRouter.java"
        with tempfile.TemporaryDirectory() as tmp:
            EndToEndTest.make_repo(self, tmp)
            self.write(tmp, progression, RENAMED)
            self.write(tmp, cursor, cursor_source)
            self.write(tmp, router, GENUINE)
            index = self.index(tmp)
            unexcluded = self.te.assess(index, cursor_source, cursor, "IndexProgressionCursor", [])
            os.environ["AR_TYPE_INDEX_CACHE"] = os.path.join(tmp, "scan-index.json")
            try:
                assessed, added = self.te.scan(tmp, [progression, cursor, router])
            finally:
                del os.environ["AR_TYPE_INDEX_CACHE"]
        new_types = {"org.example.seq.IndexProgression", "org.example.seq.IndexProgressionCursor",
                     "org.example.net.PacketRouter"}
        self.assertEqual(new_types, set(added), "every added type is reported, candidates or not")
        self.assertIn("org.example.seq.IndexProgression",
                      [c["fqn"] for c in unexcluded["candidates"]],
                      "the fixture must make one new type resemble the other")
        by_fqn = {a["fqn"]: a for a in assessed}
        self.assertIn("org.example.seq.IndexProgressionCursor", by_fqn)
        self.assertNotIn("org.example.net.PacketRouter", by_fqn)
        for a in assessed:
            found = {c["fqn"] for c in a["candidates"]}
            self.assertFalse(found & new_types, a["fqn"] + " lists a branch-new type: " + str(found))
            self.assertIn("org.example.seq.ArithmeticIndexSequence", found)

    def test_ledger_entry_matches_fully_qualified_name_only(self):
        new = {"fqn": "org.example.other.IndexProgression", "name": "IndexProgression"}
        by_simple = [{"type": "IndexProgression"}]
        by_other_package = [{"type": "org.example.seq.IndexProgression"}]
        exact = [{"type": "org.example.other.IndexProgression"}]
        self.assertIsNone(self.te.ledger_entry(by_simple, new))
        self.assertIsNone(self.te.ledger_entry(by_other_package, new))
        self.assertEqual(exact[0], self.te.ledger_entry(exact, new))

    def test_justification_matches_candidate_keys_by_fqn_only(self):
        candidates = [{"fqn": "a.Foo", "name": "Foo"}, {"fqn": "b.Foo", "name": "Foo"}]
        reasons = {"cannot_extend": "the candidate is final elsewhere",
                   "cannot_change_signature": "the signature is fixed by an SPI",
                   "interface_would_not_work": "the SPI requires a concrete class"}
        by_simple = {"test": "T#m", "candidates": {"Foo": reasons}}
        gaps = self.te.justification_gaps(by_simple, candidates)
        self.assertTrue(any("a.Foo" in g for g in gaps),
                        "a simple-name key must not justify a.Foo: " + str(gaps))
        self.assertTrue(any("b.Foo" in g for g in gaps),
                        "a simple-name key must not justify b.Foo: " + str(gaps))
        by_fqn = {"test": "T#m", "candidates": {"a.Foo": reasons, "b.Foo": reasons}}
        self.assertEqual([], self.te.justification_gaps(by_fqn, candidates))


if __name__ == "__main__":
    unittest.main()
