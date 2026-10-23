"""Tests for plan_front_matter."""

import datetime
import unittest

from plan_front_matter import PlanFrontMatter, is_plan_path, with_header

TODAY = datetime.date(2026, 10, 23)


def _header(status="in-progress", created="2026-10-01", closed="", tracker="[]"):
    return "---\nstatus: %s\ncreated: %s\nclosed: %s\ntracker: %s\n---\n\n# Plan\n" % (
        status, created, closed, tracker)


class IsPlanPathTest(unittest.TestCase):

    def test_markdown_plan_is_a_plan(self):
        self.assertTrue(is_plan_path("docs/plans/PLAN-20261023-x.md"))
        self.assertTrue(is_plan_path("docs/plans/topic/NOTES.md"))

    def test_exempt_files_are_not_plans(self):
        self.assertFalse(is_plan_path("docs/plans/CLAUDE.md"))
        self.assertFalse(is_plan_path("docs/plans/MANAGER_LOG.md"))
        self.assertFalse(is_plan_path("docs/plans/0.76/PLAN.md"))
        self.assertFalse(is_plan_path("docs/plans/plan-x-workstream.yaml"))
        self.assertFalse(is_plan_path("docs/internals/x.md"))

    def test_exempt_names_count_only_at_the_top_level(self):
        self.assertTrue(is_plan_path("docs/plans/topic/CLAUDE.md"))


class ParseTest(unittest.TestCase):

    def test_valid_open_plan(self):
        header, problems = PlanFrontMatter.parse(_header(tracker="[a1, b2]"))
        self.assertEqual(problems, [])
        self.assertEqual(header.status, "in-progress")
        self.assertEqual(header.created, datetime.date(2026, 10, 1))
        self.assertIsNone(header.closed)
        self.assertEqual(header.tracker, ["a1", "b2"])

    def test_valid_closed_plan(self):
        _, problems = PlanFrontMatter.parse(_header(status="completed", closed="2026-10-20"))
        self.assertEqual(problems, [])

    def test_missing_block(self):
        header, problems = PlanFrontMatter.parse("# Plan\n")
        self.assertIsNone(header)
        self.assertIn("missing front-matter", problems[0])

    def test_unclosed_block(self):
        _, problems = PlanFrontMatter.parse("---\nstatus: proposed\n# Plan\n")
        self.assertIn("not closed", problems[0])

    def test_unknown_status(self):
        _, problems = PlanFrontMatter.parse(_header(status="done"))
        self.assertTrue(any("is not one of" in p for p in problems), problems)

    def test_closed_status_needs_closed_date(self):
        _, problems = PlanFrontMatter.parse(_header(status="abandoned"))
        self.assertIn("status 'abandoned' requires a closed date", problems)

    def test_open_status_must_not_have_closed_date(self):
        _, problems = PlanFrontMatter.parse(_header(closed="2026-10-20"))
        self.assertIn("status 'in-progress' must not have a closed date", problems)

    def test_bad_dates(self):
        _, problems = PlanFrontMatter.parse(_header(created="Oct 1"))
        self.assertTrue(any("not an ISO date" in p for p in problems), problems)
        _, problems = PlanFrontMatter.parse(_header(created=""))
        self.assertIn("created date is required", problems)

    def test_closed_before_created(self):
        _, problems = PlanFrontMatter.parse(
            _header(status="completed", created="2026-10-10", closed="2026-10-01"))
        self.assertIn("closed date precedes created date", problems)

    def test_tracker_must_be_a_list(self):
        _, problems = PlanFrontMatter.parse(_header(tracker="abc"))
        self.assertTrue(any("must be a list" in p for p in problems), problems)

    def test_unknown_field(self):
        _, problems = PlanFrontMatter.parse("---\nstatus: proposed\ncreated: 2026-10-01\n"
                                            "closed:\ntracker: []\nowner: me\n---\n")
        self.assertTrue(any("unrecognised" in p for p in problems), problems)


class RenderAndExpiryTest(unittest.TestCase):

    def test_new_header_round_trips(self):
        text = PlanFrontMatter.new(TODAY, ["t1"]).render() + "\n# Plan\n"
        header, problems = PlanFrontMatter.parse(text)
        self.assertEqual(problems, [])
        self.assertEqual((header.status, header.created, header.tracker), ("proposed", TODAY, ["t1"]))

    def test_expiry_is_strictly_after_the_retention_window(self):
        header = PlanFrontMatter("completed", datetime.date(2026, 9, 1), datetime.date(2026, 10, 9))
        self.assertFalse(header.is_expired(TODAY, 14))
        header.closed = datetime.date(2026, 10, 8)
        self.assertTrue(header.is_expired(TODAY, 14))

    def test_open_plan_never_expires(self):
        self.assertFalse(PlanFrontMatter.new(datetime.date(2020, 1, 1)).is_expired(TODAY, 14))


class WithHeaderTest(unittest.TestCase):

    def test_adds_header_when_missing(self):
        text, problems = with_header("# Plan\n", TODAY)
        self.assertEqual(problems, [])
        self.assertTrue(text.startswith(PlanFrontMatter.new(TODAY).render() + "\n# Plan"))

    def test_keeps_valid_header(self):
        original = _header()
        self.assertEqual(with_header(original, TODAY), (original, []))

    def test_reports_invalid_header(self):
        _, problems = with_header(_header(status="completed"), TODAY)
        self.assertTrue(problems)


if __name__ == "__main__":
    unittest.main()
