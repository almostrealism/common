#!/usr/bin/env python3
"""Tests for the asynchronous-submission bypass guard."""

import unittest

import async_bypass_check as check

SEMAPHORE = "/repo/base/relation/src/main/java/io/almostrealism/streams/Semaphore.java"
SUBMITTABLE = "/repo/base/code/src/main/java/io/almostrealism/concurrent/Submittable.java"
CALLER = "/repo/compute/time/src/main/java/org/almostrealism/time/SimulationRunner.java"
BACKEND = "/repo/base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java"
TEST = "/repo/engine/utils/src/test/java/org/almostrealism/time/test/SimulationRunnerTest.java"


def write(path, content):
    """Return the decision for a Write of `content` over an empty file."""
    return check.decide("Write", {"file_path": path, "content": content}, "")


def edit(path, before, old, new):
    """Return the decision for an Edit replacing `old` with `new` in `before`."""
    return check.decide("Edit", {"file_path": path, "old_string": old,
                                 "new_string": new}, before)


class ContractTest(unittest.TestCase):

    def test_backend_query_on_semaphore_blocks(self):
        code, message = write(SEMAPHORE, "public interface Semaphore {\n"
                                         "\tdefault boolean isOrderedOnDevice() { return false; }\n}")
        self.assertEqual(2, code)
        self.assertIn("isOrderedOnDevice", message)

    def test_submit_variant_on_submittable_blocks(self):
        code, message = write(SUBMITTABLE, "public interface Submittable {\n"
                                           "\tdefault Semaphore chainOnDevice(Semaphore d) { return submit(d); }\n}")
        self.assertEqual(2, code)
        self.assertIn("chainOnDevice", message)

    def test_an_ordinary_new_member_is_allowed(self):
        code, _ = write(SEMAPHORE, "public interface Semaphore {\n"
                                   "\tdefault void onSettled(Runnable r) { }\n}")
        self.assertEqual(0, code)

    def test_existing_members_are_not_reported_again(self):
        before = "public interface Semaphore {\n\tdefault boolean isOrderedOnDevice() { return false; }\n}"
        code, _ = edit(SEMAPHORE, before, "return false;", "return true;")
        self.assertEqual(0, code)


class CallerTest(unittest.TestCase):

    def test_calling_a_removed_member_blocks(self):
        code, _ = write(CALLER, "class R { void run() { p = s.chainOnDevice(p); } }")
        self.assertEqual(2, code)

    def test_semaphore_type_check_in_calling_code_blocks(self):
        code, _ = write(CALLER, "class R { void run() { if (p instanceof MetalSemaphore) p.waitFor(); } }")
        self.assertEqual(2, code)

    def test_semaphore_type_check_in_a_backend_is_allowed(self):
        code, _ = write(BACKEND, "class N { void a() { if (d instanceof LatchSemaphore) go(); } }")
        self.assertEqual(0, code)

    def test_in_flight_cap_blocks(self):
        code, _ = write(CALLER, "class R { int maxStepsInFlight = 256;\n"
                                "void run() { if (i % maxStepsInFlight == 0) pending.waitFor(); } }")
        self.assertEqual(2, code)

    def test_wait_then_null_submit_blocks(self):
        code, _ = write(BACKEND, "class N { void a() { d.waitFor(); op.submit(null); } }")
        self.assertEqual(2, code)

    def test_new_wait_in_submitting_code_warns(self):
        code, message = write(CALLER, "class R { void run() { p = op.submit(p); p.waitFor(); } }")
        self.assertEqual(0, code)
        self.assertIn("boundary operation", message)


class ReplayTest(unittest.TestCase):

    def test_a_replayed_file_is_measured_against_head(self):
        path = "compute/time/src/main/java/org/almostrealism/time/NotInHead.java"
        code, _ = check.decide(None, {"file_path": path},
                               "class R { void run() { p = s.chainOnDevice(p); } }")
        self.assertEqual(2, code)


class ScopeTest(unittest.TestCase):

    def test_tests_are_exempt(self):
        code, message = write(TEST, "class T { void t() { d.waitFor(); op.submit(null); } }")
        self.assertEqual(0, code)
        self.assertIsNone(message)

    def test_non_java_files_are_exempt(self):
        code, message = write("/repo/docs/x.md", "isOrderedOnDevice() chainOnDevice(")
        self.assertEqual(0, code)
        self.assertIsNone(message)


if __name__ == "__main__":
    unittest.main()
