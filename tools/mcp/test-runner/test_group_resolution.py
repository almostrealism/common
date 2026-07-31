"""Tests for resolve_ci_test_groups, which reads AR_TEST_GROUPS from the CI workflow.

The group count must never be duplicated in tool descriptions or defaults,
where it rots; these tests pin the behavior of reading it from the workflow
definition itself, per module, since different CI jobs partition their
modules into different group counts.
"""

import pathlib
import tempfile
import unittest

import server

FIXTURE = """\
      - name: Run Utils Tests (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/utils \\
           -DAR_HARDWARE_DRIVER=native \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=8

      - name: Run Utils Tests Again (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/utils \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=8

      # This comment mentions AR_TEST_GROUPS=99 and must be ignored.
      - name: Run Media Tests (Group ${{ matrix.group }})
        run: |
          mvn test -Dcheckstyle.skip=true -pl engine/audio \\
           -DAR_TEST_GROUP=${{ matrix.group }} \\
           -DAR_TEST_GROUPS=4
"""

CONFLICTING = """\
          mvn test -pl engine/utils \\
           -DAR_TEST_GROUPS=8
          mvn test -pl engine/utils \\
           -DAR_TEST_GROUPS=7
"""


class GroupResolutionTest(unittest.TestCase):

    def _with_workflow(self, text):
        f = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False)
        f.write(text)
        f.close()
        return pathlib.Path(f.name)

    def _resolve(self, text, module):
        path = self._with_workflow(text)
        original = server.CI_WORKFLOW
        try:
            server.CI_WORKFLOW = path
            return server.resolve_ci_test_groups(module)
        finally:
            server.CI_WORKFLOW = original
            path.unlink()

    def test_resolves_per_module(self):
        """Each module resolves to the count of the job that runs it."""
        self.assertEqual(8, self._resolve(FIXTURE, "engine/utils"))
        self.assertEqual(4, self._resolve(FIXTURE, "engine/audio"))

    def test_comment_lines_are_ignored(self):
        """Counts mentioned in comments must not contaminate resolution."""
        self.assertEqual(4, self._resolve(FIXTURE, "engine/audio"))

    def test_unknown_module_is_rejected(self):
        """A module CI never runs with groups must be reported, not defaulted."""
        with self.assertRaises(ValueError):
            self._resolve(FIXTURE, "engine/render")

    def test_conflicting_values_are_rejected(self):
        """Conflicting counts for one module must be reported, not guessed between."""
        with self.assertRaises(ValueError):
            self._resolve(CONFLICTING, "engine/utils")

    def test_resolves_default_module_from_actual_workflow(self):
        """The real workflow resolves the default module to a usable count."""
        groups = server.resolve_ci_test_groups(server.DEFAULT_MODULE)
        self.assertGreaterEqual(groups, 1)


if __name__ == "__main__":
    unittest.main()
