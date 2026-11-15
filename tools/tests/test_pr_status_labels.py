"""Tests for tools/ci/pr-status-labels.py and the workflow that runs it."""

import datetime
import importlib.util
import os
import unittest

import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_spec = importlib.util.spec_from_file_location(
    "pr_status_labels", os.path.join(ROOT, "tools", "ci", "pr-status-labels.py"))
labels = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(labels)

NOW = datetime.datetime(2026, 11, 14, 12, 0, tzinfo=datetime.timezone.utc)


def stamp(minutes_ago):
    """A GitHub timestamp this many minutes before NOW."""
    return (NOW - datetime.timedelta(minutes=minutes_ago)).strftime(
        "%Y-%m-%dT%H:%M:%SZ")


def run(event, status="completed", conclusion="success", attempt=1,
        minutes_ago=5):
    return {"id": 1, "event": event, "status": status,
            "conclusion": conclusion if status == "completed" else None,
            "run_attempt": attempt, "created_at": stamp(minutes_ago + 30),
            "run_started_at": stamp(minutes_ago + 20),
            "updated_at": stamp(minutes_ago), "head_branch": "feature/x"}


def check(name, conclusion, minutes_ago=5):
    return {"name": name, "conclusion": conclusion,
            "started_at": stamp(minutes_ago)}


def job(name, conclusion="success"):
    return {"name": name, "conclusion": conclusion}


class DeriveTest(unittest.TestCase):
    """Which state each combination of runs and checks reports."""

    def derive(self, **facts):
        facts.setdefault("pr", {"number": 7, "updated_at": stamp(3)})
        return labels.derive(facts, NOW)

    def test_no_run_yet_is_validating(self):
        self.assertEqual(self.derive()[0], labels.VALIDATING)

    def test_a_running_validate_run_is_validating(self):
        state = self.derive(validate_run=run("pull_request", status="in_progress"))
        self.assertEqual(state[0], labels.VALIDATING)

    def test_a_finished_validate_run_that_sent_a_reviewer_is_auto_review(self):
        for conclusion in ("success", "failure"):
            state = self.derive(
                validate_run=run("pull_request", conclusion=conclusion),
                validate_jobs=[job("auto-review-submit")])
            self.assertEqual(state[0], labels.AUTO_REVIEW, conclusion)

    def test_a_python_fix_from_a_validate_run_is_auto_review(self):
        state = self.derive(
            validate_run=run("pull_request", conclusion="failure"),
            validate_jobs=[job("auto-review-submit", "skipped"),
                           job("auto-resolve-python-submit")])
        self.assertEqual(state[0], labels.AUTO_REVIEW)

    def test_a_validate_run_that_sent_nothing_needs_attention(self):
        state = self.derive(
            validate_run=run("pull_request", conclusion="failure"),
            validate_jobs=[job("auto-review-submit", "failure")])
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_a_running_test_run_is_full_tests_whatever_the_validate_run(self):
        state = self.derive(
            validate_run=run("pull_request"),
            validate_jobs=[job("auto-review-submit")],
            test_run=run("deployment", status="queued", attempt=2))
        self.assertEqual(state[0], labels.FULL_TESTS)

    def test_both_gates_passing_is_ready(self):
        state = self.derive(
            pr={"number": 7, "mergeable_state": "clean"},
            test_run=run("deployment"),
            check_runs=[check("All Checks", "success"),
                        check("Test Checks", "success")])
        self.assertEqual(state[0], labels.READY)

    def test_ready_reads_the_newest_all_checks(self):
        state = self.derive(
            test_run=run("deployment"),
            check_runs=[check("All Checks", "success", minutes_ago=60),
                        check("All Checks", "failure", minutes_ago=10),
                        check("Test Checks", "success")])
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_a_merge_conflict_is_not_ready(self):
        state = self.derive(
            pr={"number": 7, "mergeable_state": "dirty"},
            test_run=run("deployment"),
            check_runs=[check("All Checks", "success"),
                        check("Test Checks", "success")])
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_a_staged_request_is_auto_fix(self):
        state = self.derive(test_run=run("deployment", conclusion="failure",
                                         attempt=3),
                            staged=True)
        self.assertEqual(state[0], labels.AUTO_FIX)

    def test_a_python_fix_from_a_test_run_is_auto_fix(self):
        state = self.derive(test_run=run("deployment", conclusion="failure"),
                            test_jobs=[job("auto-resolve-python-submit")])
        self.assertEqual(state[0], labels.AUTO_FIX)

    def test_an_early_failure_awaits_the_retry_decision(self):
        state = self.derive(test_run=run("deployment", conclusion="failure",
                                         attempt=1, minutes_ago=2))
        self.assertEqual(state[0], labels.FULL_TESTS)

    def test_an_early_failure_nobody_retried_needs_attention(self):
        state = self.derive(test_run=run("deployment", conclusion="failure",
                                         attempt=1, minutes_ago=60))
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_a_last_attempt_failure_with_no_agent_needs_attention(self):
        state = self.derive(test_run=run("deployment", conclusion="failure",
                                         attempt=3, minutes_ago=2))
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_a_cancelled_test_run_needs_attention(self):
        state = self.derive(test_run=run("deployment", conclusion="cancelled"))
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)


class StallTest(unittest.TestCase):

    def test_a_long_running_stage_stalls(self):
        self.assertTrue(labels.is_stalled(labels.AUTO_REVIEW, stamp(5 * 60), NOW))
        self.assertFalse(labels.is_stalled(labels.AUTO_REVIEW, stamp(60), NOW))

    def test_ready_and_needs_attention_never_stall(self):
        for label in (labels.READY, labels.NEEDS_ATTENTION):
            self.assertFalse(labels.is_stalled(label, stamp(10 ** 5), NOW))

    def test_every_label_has_a_colour_and_description(self):
        for name, (colour, description, _) in labels.LABELS.items():
            self.assertTrue(name.startswith("ci: "), name)
            self.assertRegex(colour, r"^[0-9a-f]{6}$")
            self.assertLessEqual(len(description), 100, name)


class LabelChangesTest(unittest.TestCase):

    def test_moves_from_one_state_to_the_next(self):
        add, remove = labels.label_changes(
            [labels.AUTO_REVIEW, labels.STALLED, "documentation"],
            labels.FULL_TESTS, False)
        self.assertEqual(add, [labels.FULL_TESTS])
        self.assertEqual(remove, sorted([labels.AUTO_REVIEW, labels.STALLED]))

    def test_nothing_changes_when_already_labelled(self):
        self.assertEqual(labels.label_changes([labels.READY], labels.READY,
                                              False), ([], []))

    def test_stalled_is_added_beside_the_state(self):
        add, remove = labels.label_changes([labels.FULL_TESTS],
                                           labels.FULL_TESTS, True)
        self.assertEqual((add, remove), ([labels.STALLED], []))


class FakeClient:
    """Answers the GETs ``gather`` makes from a path-prefix table."""

    repo = "owner/repo"

    def __init__(self, answers):
        self.answers = answers
        self.sent = []

    def get(self, path):
        for prefix, answer in self.answers:
            if path.startswith(prefix):
                return answer
        raise AssertionError("unexpected GET " + path)

    def send(self, method, path, body=None):
        self.sent.append((method, path, body))


PR = {"number": 7, "head": {"sha": "abc", "ref": "feature/x",
                            "repo": {"full_name": "owner/repo"}}}


class GatherTest(unittest.TestCase):

    def test_reads_only_this_branch_and_the_latest_attempts_request(self):
        test_run = dict(run("deployment", conclusion="failure", attempt=3),
                        id=11)
        other = dict(run("pull_request"), id=12, head_branch="other")
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/workflows/", {"workflow_runs": [test_run, other]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("actions/runs/11/attempts/3/jobs", {"jobs": []}),
            ("actions/runs/11/artifacts", {"artifacts": [
                {"created_at": stamp(60)}]}),
        ])
        facts = labels.gather(client, PR)
        self.assertIsNone(facts["validate_run"])
        self.assertEqual(facts["test_run"]["id"], 11)
        # Staged before the latest attempt started: an earlier attempt's.
        self.assertFalse(facts["staged"])

    def test_apply_adds_then_removes(self):
        client = FakeClient([])
        labels.apply(client, PR, [labels.READY], [labels.FULL_TESTS])
        self.assertEqual(client.sent[0],
                         ("POST", "issues/7/labels", {"labels": [labels.READY]}))
        self.assertEqual(client.sent[1],
                         ("DELETE", "issues/7/labels/ci%3A%20full%20tests", None))

    def test_open_pulls_skips_forks(self):
        fork = {"number": 8, "head": {"ref": "x", "repo": {"full_name": "f/r"}}}
        client = FakeClient([("pulls?", [PR, fork])])
        self.assertEqual([p["number"] for p in labels.open_pulls(client)], [7])


class WorkflowTest(unittest.TestCase):
    """The workflow runs no pull request code and has only what it needs."""

    def setUp(self):
        with open(os.path.join(ROOT, ".github", "workflows",
                               "pr-status-labels.yaml")) as handle:
            self.workflow = yaml.safe_load(handle)
        self.job = self.workflow["jobs"]["label"]

    def test_triggers(self):
        triggers = self.workflow[True]  # YAML reads the bare key `on` as True
        self.assertEqual(triggers["workflow_run"]["workflows"],
                         ["Build and Test", "Auto-Resolve Submit"])
        self.assertIn("schedule", triggers)

    def test_checks_out_the_default_branch(self):
        checkout = self.job["steps"][0]
        self.assertTrue(checkout["uses"].startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["ref"],
                         "${{ github.event.repository.default_branch }}")

    def test_permissions(self):
        self.assertEqual(self.job["permissions"], {
            "actions": "read", "checks": "read", "contents": "read",
            "issues": "write", "pull-requests": "write"})

    def test_max_attempts_matches_the_retry_gate(self):
        with open(os.path.join(ROOT, ".github", "workflows",
                               "auto-resolve-submit.yaml")) as handle:
            text = handle.read()
        self.assertIn('MAX_ATTEMPTS: "%d"' % labels.MAX_ATTEMPTS, text)


if __name__ == "__main__":
    unittest.main()
