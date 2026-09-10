"""Guard: every agent prompt builder produces a fully substituted prompt.

The builders in ``tools/ci/prompts`` are the last step before a prompt reaches
a coding agent, and nothing downstream inspects what they wrote. A template
placeholder the builder forgot to substitute travels all the way into the
agent's instructions as the literal text ``${BRANCH}``, and the only symptom is
an agent that behaves slightly oddly for reasons nobody can reconstruct later.

These tests hold every builder to the contract its own header states: run it
with the variables it declares and it writes a prompt with no placeholder left
in it; leave one of them unset and it refuses rather than writing a prompt with
a hole in it.
"""

import os
import re
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_PROMPT_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "prompts")

# Every builder validates its inputs with the same loop, so the required set is
# read from the code rather than from the prose above it. The two are compared
# against each other by test_header_documents_every_required_variable.
_REQUIRED_LOOP = re.compile(r"^for var in ([A-Z_ ]+); do$", re.MULTILINE)
_HEADER_BLOCK = re.compile(
    r"^# Required environment variables:\n((?:^#.*\n)*?)^#\s*$", re.MULTILINE)
_HEADER_VAR = re.compile(r"^#   ([A-Z_]+)\s+-", re.MULTILINE)
_TEMPLATE_PATH = re.compile(r'^TEMPLATE="\$\{SCRIPT_DIR\}/([^"]+)"$', re.MULTILINE)
# Most builders take only an output path; a few take an input (a failures file,
# a crash-report directory) ahead of it. The header's usage line is what says so.
_USAGE = re.compile(r"^#   \S+\.sh((?: <[^>]+>)+)$", re.MULTILINE)
_USAGE_ARG = re.compile(r"<([^>]+)>")
_PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")

# A value distinctive enough to find in the output, and containing none of the
# characters the builders use as sed delimiters.
_VALUE = "placeholder-value"


def _read(path):
    """Returns a file's text, closing it — these tests run under -W error."""
    with open(path) as f:
        return f.read()


def _builders():
    """Returns the path of every prompt builder, sorted by name."""
    return [os.path.join(_PROMPT_DIR, name)
            for name in sorted(os.listdir(_PROMPT_DIR))
            if name.startswith("build-") and name.endswith(".sh")]


def _required_vars(builder):
    """Returns the variables a builder's validation loop insists on."""
    source = _read(builder)
    match = _REQUIRED_LOOP.search(source)
    return match.group(1).split() if match else []


def _usage_args(builder):
    """Returns the argument names from a builder's usage line, output last."""
    match = _USAGE.search(_read(builder))
    return _USAGE_ARG.findall(match.group(1)) if match else []


def _template_of(builder):
    """Returns the template a builder reads, or None when it inlines its prompt."""
    match = _TEMPLATE_PATH.search(_read(builder))
    return os.path.join(_PROMPT_DIR, match.group(1)) if match else None


class PromptBuilderTests(unittest.TestCase):
    """Runs each builder for real, in a throwaway directory."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prompt-builder-test-")
        self.output = os.path.join(self.tmp, "agent-prompt.txt")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _args(self, builder):
        """Builds the positional arguments a builder's usage line calls for.

        Each input ahead of the output path gets an empty fixture: what the
        builders do with a real one is their own business, and an empty one
        exercises the substitution path on every platform (the crash builder's
        parsing of a populated report needs GNU grep).
        """
        args = []
        for name in _usage_args(builder)[:-1]:
            if "dir" in name:
                args.append(tempfile.mkdtemp(dir=self.tmp))
            else:
                path = os.path.join(self.tmp, name)
                open(path, "w").close()
                args.append(path)
        return args + [self.output]

    def _run(self, builder, env_overrides=None, args=None):
        """Runs a builder with every required variable set, minus any removed."""
        env = dict(os.environ)
        for var in _required_vars(builder):
            env[var] = _VALUE
        for var, value in (env_overrides or {}).items():
            if value is None:
                env.pop(var, None)
            else:
                env[var] = value
        return subprocess.run(
            ["bash", builder] + (self._args(builder) if args is None else args),
            env=env, capture_output=True, text=True)

    def test_at_least_one_builder_is_discovered(self):
        """A discovery bug would make every other test here vacuously pass."""
        self.assertTrue(_builders())

    def test_every_builder_is_executable(self):
        """The workflow chmods these, but a builder should not depend on that."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(os.access(builder, os.X_OK))

    def test_every_builder_declares_its_required_variables(self):
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(_required_vars(builder))

    def test_header_documents_every_required_variable(self):
        """The prose above the script is the only reference a caller reads."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                block = _HEADER_BLOCK.search(_read(builder))
                self.assertIsNotNone(block, "no 'Required environment variables' block")
                documented = set(_HEADER_VAR.findall(block.group(1)))
                self.assertEqual(set(_required_vars(builder)), documented)

    def test_a_prompt_is_written_with_no_placeholder_left_in_it(self):
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder)
                self.assertEqual(0, result.returncode, result.stderr)
                prompt = _read(self.output)
                self.assertTrue(prompt.strip())
                self.assertNotIn("${", prompt)

    def test_every_template_placeholder_is_a_required_variable(self):
        """A placeholder outside the required set can never be substituted."""
        for builder in _builders():
            template = _template_of(builder)
            if template is None:
                continue
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(os.path.isfile(template))
                placeholders = set(_PLACEHOLDER.findall(_read(template)))
                self.assertTrue(placeholders, "template substitutes nothing")
                self.assertLessEqual(placeholders, set(_required_vars(builder)))

    def test_a_missing_variable_is_refused_rather_than_substituted_empty(self):
        for builder in _builders():
            for var in _required_vars(builder):
                with self.subTest(builder=os.path.basename(builder), var=var):
                    result = self._run(builder, {var: None})
                    self.assertEqual(1, result.returncode)
                    self.assertIn(var, result.stderr)
                    self.assertFalse(os.path.exists(self.output))

    def test_an_empty_variable_is_refused_too(self):
        """An unset variable and one set to "" are the same mistake."""
        for builder in _builders():
            var = _required_vars(builder)[0]
            with self.subTest(builder=os.path.basename(builder), var=var):
                result = self._run(builder, {var: ""})
                self.assertEqual(1, result.returncode)
                self.assertFalse(os.path.exists(self.output))

    def test_the_output_path_is_required(self):
        """Called with every argument but the last, a builder must refuse."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder, args=self._args(builder)[:-1])
                self.assertEqual(1, result.returncode)
                self.assertIn("Usage", result.stderr)

    def test_every_builder_documents_its_usage(self):
        """_args and test_the_output_path_is_required both read that line."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                args = _usage_args(builder)
                self.assertTrue(args)
                self.assertIn("output", args[-1])


if __name__ == "__main__":
    unittest.main()
