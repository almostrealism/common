#!/usr/bin/env python3
"""Tests for the guard against writing work products where only the session can read."""

import io
import json
import os
import tempfile
import unittest

import scratch_write_check as check


class LocationTest(unittest.TestCase):

    def setUp(self):
        self.project = os.path.realpath(tempfile.mkdtemp())
        self.addCleanup(os.rmdir, self.project)

    def test_a_file_in_the_working_tree_is_allowed(self):
        path = os.path.join(self.project, "tools", "report.md")
        self.assertIsNone(check.unreadable_location(path, self.project))

    def test_a_relative_path_is_read_against_the_current_directory(self):
        self.assertIsNone(check.unreadable_location("tools/report.md", os.getcwd()))

    def test_the_session_scratchpad_is_refused(self):
        path = "/private/tmp/claude-1/-Users-x-Projects-y/abc/scratchpad/census/census.py"
        self.assertEqual("the session scratchpad", check.unreadable_location(path, self.project))

    def test_a_scratchpad_outside_the_temporary_roots_is_refused(self):
        path = "/Users/x/.cache/session/scratchpad/notes.md"
        self.assertEqual("the session scratchpad", check.unreadable_location(path, self.project))

    def test_the_temporary_directory_is_refused(self):
        for path in ("/tmp/check.py", "/private/tmp/check.py", "/var/folders/ab/cd/T/check.py"):
            self.assertEqual("the system temporary directory",
                             check.unreadable_location(path, self.project), path)

    def test_a_path_that_climbs_out_of_the_project_into_tmp_is_refused(self):
        project = "/Users/x/Projects/y"
        self.assertIsNotNone(check.unreadable_location("/Users/x/Projects/y/../../../../tmp/x.py",
                                                       project))

    def test_a_project_checked_out_under_tmp_may_be_written(self):
        path = os.path.join(self.project, "src", "A.java")
        self.assertIsNone(check.unreadable_location(path, self.project))

    def test_a_file_elsewhere_in_the_home_directory_is_allowed(self):
        self.assertIsNone(check.unreadable_location("/Users/x/Projects/other/a.txt", self.project))

    def test_an_empty_path_is_allowed(self):
        self.assertIsNone(check.unreadable_location("", self.project))


class MainTest(unittest.TestCase):

    def run_main(self, payload, project="/Users/x/Projects/y"):
        err = io.StringIO()
        status = check.main(["--stdin"], io.StringIO(payload), err, project)
        return status, err.getvalue()

    def test_a_write_to_the_scratchpad_exits_two_with_the_reason(self):
        payload = json.dumps({"tool_input": {"file_path": "/tmp/s/scratchpad/a.py",
                                             "content": "x"}})
        status, said = self.run_main(payload)

        self.assertEqual(2, status)
        self.assertIn("delivered to nobody", said)
        self.assertIn("working tree", said)

    def test_an_edit_in_the_project_exits_zero(self):
        payload = json.dumps({"tool_input": {"file_path": "/Users/x/Projects/y/a.py"}})
        self.assertEqual((0, ""), self.run_main(payload))

    def test_an_opencode_payload_is_read(self):
        payload = json.dumps({"tool_input": {"filePath": "/tmp/a.py"}})
        self.assertEqual(2, self.run_main(payload)[0])

    def test_a_payload_without_a_path_exits_zero(self):
        self.assertEqual(0, self.run_main("{}")[0])
        self.assertEqual(0, self.run_main("not json")[0])


if __name__ == "__main__":
    unittest.main()
