#!/usr/bin/env python3
"""Tests for detecting runs that share a project's build tree."""

import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_tree


def write_run(directory: Path, run_id: str, **overrides) -> Path:
    """Write one run record, defaulting to a live-looking test run."""
    metadata = {
        "run_id": run_id,
        "status": "running",
        "started_at": datetime.now().isoformat(),
        "config": {"module": "engine/utils", "timeout_minutes": 15},
    }
    metadata.update(overrides)

    run_dir = directory / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "metadata.json").write_text(json.dumps(metadata))
    return run_dir


class InFlightTests(unittest.TestCase):
    """Which recorded runs count as still using the tree."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.tester = self.root / "test-runner" / "runs"
        self.validator = self.root / "build-validator" / "runs"
        self.tester.mkdir(parents=True)
        self.validator.mkdir(parents=True)
        self.directories = {
            "ar-test-runner": self.tester,
            "ar-build-validator": self.validator,
        }

    def tearDown(self):
        self.tmp.cleanup()

    def find(self, now=None):
        return build_tree.in_flight(run_directories=self.directories, now=now)

    def test_running_record_is_in_flight(self):
        write_run(self.tester, "aaa1")
        found = self.find()
        self.assertEqual(1, len(found))
        self.assertEqual("ar-test-runner", found[0].tool)
        self.assertEqual("aaa1", found[0].run_id)

    def test_completed_record_is_not_in_flight(self):
        write_run(self.tester, "aaa1", status="completed",
                  completed_at=datetime.now().isoformat())
        self.assertEqual([], self.find())

    def test_record_with_completion_time_is_not_in_flight(self):
        """A status left at running is not trusted over a recorded completion."""
        write_run(self.tester, "aaa1", completed_at=datetime.now().isoformat())
        self.assertEqual([], self.find())

    def test_stale_record_from_a_dead_parent_is_ignored(self):
        """A crash leaves running behind; it must not block every later run."""
        started = datetime.now() - timedelta(minutes=120)
        write_run(self.tester, "aaa1", started_at=started.isoformat())
        self.assertEqual([], self.find())

    def test_record_within_its_timeout_is_in_flight_without_a_process(self):
        """Between one check and the next a server holds no process."""
        started = datetime.now() - timedelta(minutes=2)
        write_run(self.validator, "bbb1", started_at=started.isoformat(),
                  config={"checks": ["checkstyle"], "timeout_minutes": 25})
        self.assertEqual(1, len(self.find()))

    def test_live_process_outlives_its_time_budget(self):
        """A run that is genuinely still going is not stale, however long it took."""
        started = datetime.now() - timedelta(minutes=600)
        write_run(self.tester, "aaa1", started_at=started.isoformat(),
                  pid=os.getpid())
        self.assertEqual(1, len(self.find()))

    def test_dead_pid_past_the_budget_is_ignored(self):
        started = datetime.now() - timedelta(minutes=600)
        write_run(self.tester, "aaa1", started_at=started.isoformat(),
                  pid=2 ** 22)
        self.assertEqual([], self.find())

    def test_unreadable_record_is_ignored(self):
        run_dir = self.tester / "aaa1"
        run_dir.mkdir(parents=True)
        (run_dir / "metadata.json").write_text("{not json")
        self.assertEqual([], self.find())

    def test_missing_directory_contributes_nothing(self):
        found = build_tree.in_flight(
            run_directories={"ar-test-runner": self.root / "absent"})
        self.assertEqual([], found)

    def test_runs_from_both_servers_are_reported_together(self):
        write_run(self.tester, "aaa1")
        write_run(self.validator, "bbb1", config={"checks": ["code_policy"]})
        self.assertEqual(2, len(self.find()))

    def test_another_project_does_not_conflict(self):
        """The test runner can build a sibling checkout, which shares no tree."""
        write_run(self.tester, "aaa1",
                  config={"module": "audio-desktop", "project": "../elsewhere"})
        self.assertEqual([], self.find())

    def test_summary_names_the_work(self):
        write_run(self.tester, "aaa1",
                  config={"module": "engine/utils",
                          "test_classes": ["PrecisionTest", "SoftmaxTests"]})
        self.assertIn("PrecisionTest", self.find()[0].summary)

    def test_summary_elides_a_long_class_list(self):
        write_run(self.tester, "aaa1",
                  config={"module": "engine/utils",
                          "test_classes": ["A", "B", "C", "D", "E"]})
        self.assertIn("+2 more", self.find()[0].summary)

    def test_summary_names_a_group_run(self):
        write_run(self.tester, "aaa1",
                  config={"module": "engine/utils", "test_group": 0})
        self.assertIn("group 0", self.find()[0].summary)


class ConflictMessageTests(unittest.TestCase):
    """What the refusal tells the caller."""

    def message(self):
        run = build_tree.MavenRun(
            tool="ar-test-runner", run_id="fd8e4023",
            started_at="2026-08-30T03:21:47", project_root="/repo",
            summary="engine/utils group 0")
        return build_tree.conflict_message([run], "ar-build-validator")

    def test_names_the_conflicting_run(self):
        self.assertIn("fd8e4023", self.message())

    def test_names_what_that_run_is_doing(self):
        self.assertIn("engine/utils group 0", self.message())

    def test_says_how_to_proceed(self):
        message = self.message()
        self.assertIn("get_run_status", message)
        self.assertIn("block=true", message)

    def test_warns_that_the_result_would_be_meaningless(self):
        self.assertIn("zero violations", self.message())


class RaceDiagnosisTests(unittest.TestCase):
    """Recognising an overlap that happened anyway."""

    def test_lost_file_under_target_is_recognised(self):
        output = ("[ERROR] CodePolicyEnforcementTest.enforceCodePolicies » UncheckedIO "
                  "java.nio.file.NoSuchFileException: "
                  "/workspace/project/common/engine/utils/target/surefire")
        self.assertIn("another Maven run", build_tree.race_diagnosis(output))

    def test_lost_sources_alone_is_not_a_race(self):
        """The policy detector logs this on runs that go on to pass."""
        output = ("[03:32.34] CodePolicyEnforcementTest: No source directories found. "
                  "Project root may be incorrect.")
        self.assertIsNone(build_tree.race_diagnosis(output))

    def test_an_ordinary_violation_is_not_a_race(self):
        output = ("[ERROR] Hardware.java:1: File length is 1,638 lines "
                  "(max allowed is 1,600). [FileLength]")
        self.assertIsNone(build_tree.race_diagnosis(output))

    def test_a_missing_file_outside_target_is_not_a_race(self):
        output = "java.nio.file.NoSuchFileException: /etc/nope"
        self.assertIsNone(build_tree.race_diagnosis(output))

    def test_a_target_path_elsewhere_in_the_output_is_not_a_race(self):
        """Every Maven run mentions target; only the failing line counts."""
        output = ("[INFO] Building jar: /repo/engine/utils/target/ar-utils.jar\n"
                  "java.nio.file.NoSuchFileException: /etc/nope\n")
        self.assertIsNone(build_tree.race_diagnosis(output))

    def test_empty_output_is_not_a_race(self):
        self.assertIsNone(build_tree.race_diagnosis(""))
        self.assertIsNone(build_tree.race_diagnosis(None))


if __name__ == "__main__":
    unittest.main()
