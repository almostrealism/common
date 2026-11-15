"""Tests for the manual-isolation hook (block-manual-isolation.py).

The hook blocks graph-building code that decides a kernel boundary by hand -
`Process.isolated(...)`, a `.isolate()` on another process, a `new
IsolatedProcess` - on the lines an edit ADDS to a file under `src/main/java`.
The tests here hold the two halves of that contract at once: that each hand-
isolation form is refused in ordinary production code, and that the shapes which
are legitimate - the optimizer's own package, the process types' delegation
overrides, `this`/`super` isolation, test sources, and existing unchanged lines
- are not.

The hook is a standalone script rather than a shared core, so it is loaded by
path and driven two ways: its decision functions directly, and end to end over
the PreToolUse stdin contract, since the exit code is what actually stops an
edit.
"""

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HOOK = os.path.join(HOOKS, "block-manual-isolation.py")

# .claude/hooks/lib -> .claude/hooks -> .claude -> the repository root.
# is_isolated_subclass() resolves type names with `git ls-files`, so the hook
# only identifies subclasses when it runs from here.
REPO = os.path.dirname(os.path.dirname(HOOKS))


def load_hook():
    """Imports the hook script as a module."""
    spec = importlib.util.spec_from_file_location("block_manual_isolation", HOOK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DecisionTest(unittest.TestCase):
    """The per-line decision, exercised directly."""

    def setUp(self):
        self.hook = load_hook()

    def blocked(self, line):
        return bool(self.hook.violations(line))

    def test_process_isolated_call_is_a_violation(self):
        self.assertTrue(self.blocked(
            "return (CollectionProducer) Process.isolated((Supplier) producer);"))

    def test_process_isolated_method_reference_is_a_violation(self):
        self.assertTrue(self.blocked(
            "inputs.stream().map(Process::isolated).collect(toList());"))

    def test_isolate_on_other_process_is_a_violation(self):
        self.assertTrue(self.blocked("op.add(wrapped.isolate());"))

    def test_isolate_on_this_is_allowed(self):
        self.assertFalse(self.blocked("return this.isolate();"))

    def test_isolate_on_super_is_allowed(self):
        self.assertFalse(self.blocked("return super.isolate();"))

    def test_bare_isolate_helper_with_argument_is_allowed(self):
        """`isolate(process)` is the permission-checked helper, not a hand call."""
        self.assertFalse(self.blocked("return isolate((Process) getInputs().get(1));"))

    def test_isolate_force_is_not_matched(self):
        """A longer name that merely starts with `isolate` is not `.isolate()`."""
        self.assertFalse(self.blocked("return c.isolateForce();"))

    def test_new_isolated_process_is_a_violation(self):
        self.assertTrue(self.blocked("return new IsolatedProcess(this);"))

    def test_new_qualified_isolated_process_is_a_violation(self):
        self.assertTrue(self.blocked(
            "return new CollectionProducerComputation.IsolatedProcess(this);"))

    def test_helper_method_returning_isolation_is_a_violation(self):
        """The shape of the removed isolated(producer) helper, on its body line."""
        self.assertTrue(self.blocked(
            "return (CollectionProducer) Process.isolated((Supplier) p);"))

    def test_unrelated_line_is_not_a_violation(self):
        self.assertFalse(self.blocked("return sigmoid(matmul(w, x).add(b));"))

    def test_comment_naming_isolation_is_ignored(self):
        self.assertTrue(self.hook.is_comment("// use Process.isolated(x) here"))
        self.assertTrue(self.hook.is_comment(" * calls Process.isolated(...)"))


class SubclassTest(unittest.TestCase):
    """The constructor-of-a-subclass branch, which resolves repo source."""

    def setUp(self):
        self.hook = load_hook()

    def test_subclass_of_isolated_process_is_detected(self):
        """A `new Type(` whose source extends ...IsolatedProcess is a violation."""
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "MyIso.java")
            with open(path, "w") as f:
                f.write("class MyIso extends Operation.IsolatedProcess {}\n")

            def fake_run(args, **kwargs):
                return subprocess.CompletedProcess(args, 0, stdout=path + "\n")

            original = self.hook.subprocess.run
            self.hook.subprocess.run = fake_run
            self.hook._subclass_cache.clear()
            try:
                self.assertTrue(self.hook.is_isolated_subclass("MyIso"))
                self.assertTrue(self.blocked_new("new MyIso(x)"))
            finally:
                self.hook.subprocess.run = original
                self.hook._subclass_cache.clear()

    def test_ordinary_class_is_not_a_subclass(self):
        self.assertFalse(self.hook.is_isolated_subclass("ReshapeProducer"))

    def blocked_new(self, line):
        return bool(self.hook.violations(line))


class EndToEndTest(unittest.TestCase):
    """The verdict over the PreToolUse contract: exit 2 blocks, exit 0 allows."""

    def setUp(self):
        self.hook = load_hook()

    def run_write(self, rel_path, before, content):
        """Runs the hook for a Write of `content` over an on-disk `before`.

        `rel_path` is created inside a temp dir so its structure (the
        src/main/java segment, an allowed package, a basename) is what the hook
        keys on.

        @return (exit code, stderr)
        """
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, rel_path)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(before)

            payload = {
                "tool_name": "Write",
                "tool_input": {"file_path": path, "content": content},
            }
            proc = subprocess.run(
                [sys.executable, HOOK], input=json.dumps(payload),
                capture_output=True, text=True, cwd=REPO, timeout=120)
            return proc.returncode, proc.stderr

    MAIN = os.path.join(
        "domain", "graph", "src", "main", "java", "org", "almostrealism",
        "layers", "SpectralFeatures.java")

    def test_added_process_isolated_is_blocked(self):
        code, stderr = self.run_write(
            self.MAIN, "class SpectralFeatures {\n}\n",
            "class SpectralFeatures {\n"
            "\tProducer mean(Producer x) {\n"
            "\t\treturn (CollectionProducer) Process.isolated((Supplier) x);\n"
            "\t}\n}\n")
        self.assertEqual(2, code, stderr)
        self.assertIn("MANUAL PROCESS ISOLATION", stderr)

    def test_added_isolate_call_on_other_process_is_blocked(self):
        code, stderr = self.run_write(
            self.MAIN, "class SpectralFeatures {\n}\n",
            "class SpectralFeatures {\n"
            "\tvoid f(Process wrapped) {\n"
            "\t\top.add(wrapped.isolate());\n"
            "\t}\n}\n")
        self.assertEqual(2, code, stderr)

    def test_added_new_isolated_process_is_blocked(self):
        code, stderr = self.run_write(
            self.MAIN, "class SpectralFeatures {\n}\n",
            "class SpectralFeatures {\n"
            "\tProcess f() {\n"
            "\t\treturn new IsolatedProcess(this);\n"
            "\t}\n}\n")
        self.assertEqual(2, code, stderr)

    def test_added_isolation_helper_method_is_blocked(self):
        """A new method whose body returns the result of isolation."""
        code, stderr = self.run_write(
            self.MAIN, "class SpectralFeatures {\n}\n",
            "class SpectralFeatures {\n"
            "\tpublic static CollectionProducer isolated(Producer p) {\n"
            "\t\treturn (CollectionProducer) Process.isolated((Supplier) p);\n"
            "\t}\n}\n")
        self.assertEqual(2, code, stderr)

    def test_this_isolate_in_production_is_allowed(self):
        code, stderr = self.run_write(
            self.MAIN, "class SpectralFeatures {\n}\n",
            "class SpectralFeatures {\n"
            "\tProcess f() {\n"
            "\t\treturn this.isolate();\n"
            "\t}\n}\n")
        self.assertEqual(0, code, stderr)

    def test_relation_compute_package_is_allowed(self):
        """The optimizer's own package may construct and delegate isolation."""
        rel = os.path.join(
            "base", "relation", "src", "main", "java", "io", "almostrealism",
            "compute", "ParallelProcess.java")
        code, stderr = self.run_write(
            rel, "class ParallelProcess {\n}\n",
            "class ParallelProcess {\n"
            "\tProcess f() {\n"
            "\t\treturn process.isolate();\n"
            "\t}\n}\n")
        self.assertEqual(0, code, stderr)

    def test_delegation_class_is_allowed(self):
        """A process type's own delegation override may isolate the wrapped one."""
        rel = os.path.join(
            "compute", "algebra", "src", "main", "java", "org", "almostrealism",
            "collect", "computations", "ReshapeProducer.java")
        code, stderr = self.run_write(
            rel, "class ReshapeProducer {\n}\n",
            "class ReshapeProducer {\n"
            "\tProcess isolate() {\n"
            "\t\treturn new CollectionProducerComputation.IsolatedProcess(this);\n"
            "\t}\n}\n")
        self.assertEqual(0, code, stderr)

    def test_operation_with_info_delegation_is_allowed(self):
        rel = os.path.join(
            "base", "code", "src", "main", "java", "io", "almostrealism",
            "profile", "OperationWithInfo.java")
        code, stderr = self.run_write(
            rel, "class OperationWithInfo {\n}\n",
            "class OperationWithInfo {\n"
            "\tProcess isolate() {\n"
            "\t\treturn new OperationWithInfo(metadata, operation.isolate());\n"
            "\t}\n}\n")
        self.assertEqual(0, code, stderr)

    def test_test_source_is_allowed(self):
        """A hand-isolation in a test source is not the hook's concern."""
        rel = os.path.join(
            "domain", "graph", "src", "test", "java", "org", "almostrealism",
            "layers", "SpectralFeaturesTest.java")
        code, stderr = self.run_write(
            rel, "class SpectralFeaturesTest {\n}\n",
            "class SpectralFeaturesTest {\n"
            "\tvoid t() {\n"
            "\t\tProcess.isolated(reduction).get().evaluate();\n"
            "\t}\n}\n")
        self.assertEqual(0, code, stderr)

    def test_unchanged_existing_isolation_is_allowed(self):
        """An edit that leaves an existing Process.isolated line untouched."""
        before = (
            "class SpectralFeatures {\n"
            "\tProducer mean(Producer x) {\n"
            "\t\treturn (CollectionProducer) Process.isolated((Supplier) x);\n"
            "\t}\n}\n")
        after = (
            "class SpectralFeatures {\n"
            "\tProducer mean(Producer x) {\n"
            "\t\treturn (CollectionProducer) Process.isolated((Supplier) x);\n"
            "\t}\n"
            "\tProducer scaled(Producer x) {\n"
            "\t\treturn x.multiply(2.0);\n"
            "\t}\n}\n")
        code, stderr = self.run_write(self.MAIN, before, after)
        self.assertEqual(0, code, stderr)

    def test_non_java_file_is_ignored(self):
        code, stderr = self.run_write(
            os.path.join("notes", "readme.md"), "",
            "Process.isolated(x)\n")
        self.assertEqual(0, code, stderr)


if __name__ == "__main__":
    unittest.main()
