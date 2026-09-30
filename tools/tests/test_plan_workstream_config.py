"""Tests for the plan workstream settings resolver.

A plan branch can declare workstream settings — for now, the node labels its
jobs need — in ``docs/plans/<prefix>-workstream.yaml``. Verify Completion
applies the file whose prefix best matches the branch when a person
dispatches the implementation. Picking the wrong file, or applying a file it
should have refused, sends an implementation to the wrong machine, so both
the matching and the validation are pinned here, along with the
registration script that carries the result to the controller.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_CI = os.path.join(_REPO_ROOT, "tools", "ci")
sys.path.insert(0, _CI)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import plan_workstream_config as config  # noqa: E402
from test_task_planning_gates import _ControllerStub, _run  # noqa: E402


class _PlansDirTest(unittest.TestCase):
    """A throwaway docs/plans directory."""

    def setUp(self):
        self.plans = tempfile.mkdtemp(prefix="plans-")

    def write(self, name, text):
        with open(os.path.join(self.plans, name), "w") as f:
            f.write(text)

    def chosen(self, branch):
        path = config.matching_file(branch, self.plans)
        return os.path.basename(path) if path else None


class MatchingTests(_PlansDirTest):

    def test_the_exact_branch_file_applies(self):
        self.write("plan-20260930-180401-workstream.yaml", "")
        self.assertEqual("plan-20260930-180401-workstream.yaml",
                         self.chosen("project/plan-20260930-180401"))

    def test_a_shorter_prefix_applies_as_a_default(self):
        self.write("plan-20260930-workstream.yaml", "")
        self.assertEqual("plan-20260930-workstream.yaml",
                         self.chosen("project/plan-20260930-180401"))

    def test_the_longest_matching_prefix_wins(self):
        self.write("plan-20260930-workstream.yaml", "")
        self.write("plan-20260930-180401-workstream.yaml", "")
        self.write("plan-workstream.yaml", "")
        self.assertEqual("plan-20260930-180401-workstream.yaml",
                         self.chosen("project/plan-20260930-180401"))
        self.assertEqual("plan-20260930-workstream.yaml",
                         self.chosen("project/plan-20260930-090000"))

    def test_a_prefix_matches_only_at_a_dash_boundary(self):
        self.write("plan-2026093-workstream.yaml", "")
        self.assertIsNone(self.chosen("project/plan-20260930-180401"))

    def test_a_titled_branch_still_matches(self):
        self.write("plan-20260930-180401-workstream.yaml", "")
        self.assertEqual("plan-20260930-180401-workstream.yaml",
                         self.chosen("project/plan-20260930-180401-tiny-lm"))

    def test_only_project_branches_declare_settings(self):
        self.write("plan-20260930-workstream.yaml", "")
        self.assertIsNone(self.chosen("feature/plan-20260930-180401"))
        self.assertIsNone(self.chosen("plan-20260930-180401"))

    def test_task_branches_match_their_own_files(self):
        self.write("task-20260930-workstream.yaml", "")
        self.write("plan-20260930-workstream.yaml", "")
        self.assertEqual("task-20260930-workstream.yaml",
                         self.chosen("project/task-20260930-120000"))

    def test_other_files_are_ignored(self):
        self.write("PLAN-20260930-self-hosted-tiny-lm.md", "")
        self.write("plan-20260930-workstream.yml", "")
        self.assertIsNone(self.chosen("project/plan-20260930-180401"))

    def test_a_missing_directory_has_no_settings(self):
        self.assertIsNone(config.matching_file("project/plan-1", "/nonexistent/plans"))


class ParsingTests(_PlansDirTest):

    def parse(self, text):
        self.write("plan-1-workstream.yaml", text)
        return config.parse(os.path.join(self.plans, "plan-1-workstream.yaml"))

    def test_required_labels_are_read(self):
        self.assertEqual({"requiredLabels": {"platform": "macos"}},
                         self.parse("requiredLabels:\n  platform: macos\n"))

    def test_an_empty_file_declares_nothing(self):
        self.assertEqual({}, self.parse(""))

    def test_an_unknown_setting_is_refused(self):
        with self.assertRaises(config.InvalidConfig):
            self.parse("requiredLabels:\n  platform: macos\ndispatchCapable: true\n")

    def test_unknown_keys_of_mixed_types_are_refused_by_name(self):
        # YAML keys need not be strings; listing a number beside a string
        # must still report the file rather than fail to sort them.
        with self.assertRaisesRegex(config.InvalidConfig, "unsupported setting"):
            self.parse("1: a\nfoo: b\n")

    def test_badly_shaped_labels_are_refused(self):
        for text in ("requiredLabels: macos\n",
                     "requiredLabels: {}\n",
                     "requiredLabels:\n  gpu: true\n",
                     "requiredLabels:\n  platform: ''\n",
                     "- platform\n",
                     "requiredLabels: [\n"):
            with self.subTest(text=text):
                with self.assertRaises(config.InvalidConfig):
                    self.parse(text)

    def test_a_repeated_key_is_refused(self):
        # A plain YAML load keeps the last of two equal keys, which would
        # route the implementation by a value the reader may not have seen.
        for text in ("requiredLabels:\n  platform: macos\n"
                     "requiredLabels:\n  platform: linux\n",
                     "requiredLabels:\n  platform: macos\n  platform: linux\n",
                     "requiredLabels: {platform: macos, platform: linux}\n"):
            with self.subTest(text=text):
                with self.assertRaisesRegex(config.InvalidConfig, "duplicate key 'platform'|"
                                            "duplicate key 'requiredLabels'"):
                    self.parse(text)

    def test_distinct_keys_in_separate_mappings_are_accepted(self):
        self.assertEqual({"requiredLabels": {"platform": "macos", "gpu": "metal"}},
                         self.parse("requiredLabels:\n  platform: macos\n  gpu: metal\n"))


class CommandLineTests(_PlansDirTest):

    def run_resolver(self, branch):
        return subprocess.run(
            [sys.executable, os.path.join(_CI, "plan_workstream_config.py"), branch, self.plans],
            capture_output=True, text=True, timeout=60)

    def test_the_applicable_settings_are_printed_with_their_file(self):
        self.write("plan-20260930-workstream.yaml", "requiredLabels:\n  platform: macos\n")
        result = self.run_resolver("project/plan-20260930-180401")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"file": "docs/plans/plan-20260930-workstream.yaml",
                          "requiredLabels": {"platform": "macos"}},
                         json.loads(result.stdout))

    def test_no_file_prints_an_empty_object(self):
        result = self.run_resolver("project/plan-20260930-180401")
        self.assertEqual((0, {}), (result.returncode, json.loads(result.stdout)))

    def test_an_invalid_file_fails_the_step(self):
        self.write("plan-20260930-workstream.yaml", "requiredLabels: macos\n")
        result = self.run_resolver("project/plan-20260930-180401")
        self.assertEqual(1, result.returncode)
        self.assertIn("::error::", result.stdout)


class RegistrationLabelTests(unittest.TestCase):
    """register-workstream.sh carries the resolved labels to the controller."""

    def _register(self, stub, labels_json):
        self.addCleanup(stub.close)
        return _run("register-workstream.sh", {
            "BRANCH": "project/plan-1", "BASE_BRANCH": "master",
            "CONTROLLER_URL": stub.url, "REQUIRED_LABELS_JSON": labels_json,
            "TRACKER_CAPABILITIES": "", "PLAN_FILE": ""})

    def test_labels_are_sent_with_the_registration(self):
        stub = _ControllerStub()
        code, _, log = self._register(stub, '{"platform":"macos"}')
        self.assertEqual(0, code, log)
        self.assertEqual({"platform": "macos"}, stub.seen[0][4]["requiredLabels"])

    def test_an_existing_workstream_is_updated_with_the_labels(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, '{"platform":"macos"}')
        self.assertEqual(0, code, log)
        self.assertEqual({"requiredLabels": {"platform": "macos"}}, stub.seen[1][4])

    def test_no_labels_sends_no_labels(self):
        stub = _ControllerStub()
        self._register(stub, "")
        self.assertNotIn("requiredLabels", stub.seen[0][4])

    def test_a_failed_label_update_fails_the_registration(self):
        # An implementation submitted without the labels the plan declared
        # runs on the wrong machine — one without the Metal the plan needs,
        # for instance — so a dropped label update must fail the step rather
        # than warn and exit 0, exactly as a dropped capability update does.
        stub = _ControllerStub(existing=True, update=(503, {"ok": False}))
        code, _, log = self._register(stub, '{"platform":"macos"}')
        self.assertEqual(1, code, log)
        self.assertEqual(2, len(stub.seen))


if __name__ == "__main__":
    unittest.main()
