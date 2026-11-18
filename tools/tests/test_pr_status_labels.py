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


def check(name, conclusion, minutes_ago=5, started=True):
    """A check run; ids grow with creation, so a newer one has a larger id."""
    result = {"id": 10 ** 6 - minutes_ago, "name": name,
              "conclusion": conclusion,
              "status": "completed" if conclusion else "queued"}
    if started:
        result["started_at"] = stamp(minutes_ago)
    return result


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

    def test_a_queued_gate_hides_an_older_pass(self):
        state = self.derive(
            pr={"number": 7, "mergeable_state": "clean"},
            test_run=run("deployment"),
            check_runs=[check("All Checks", "success", minutes_ago=60),
                        check("All Checks", None, minutes_ago=1,
                              started=False),
                        check("Test Checks", "success")])
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_an_earlier_runs_passing_test_checks_does_not_make_a_failure_ready(
            self):
        checks = [check("All Checks", "success", minutes_ago=90),
                  check("Test Checks", "success", minutes_ago=60)]
        for conclusion in ("failure", "cancelled"):
            state = self.derive(
                pr={"number": 7, "mergeable_state": "clean"},
                test_run=run("deployment", conclusion=conclusion, attempt=3),
                check_runs=checks)
            self.assertEqual(state[0], labels.NEEDS_ATTENTION, conclusion)
        state = self.derive(
            pr={"number": 7, "mergeable_state": "clean"},
            test_run=run("deployment", conclusion="failure", attempt=3),
            check_runs=checks, staged=True)
        self.assertEqual(state[0], labels.AUTO_FIX)

    def test_a_check_run_without_an_id_is_ignored(self):
        self.assertIsNone(labels.check_conclusion(
            [{"name": "Test Checks", "conclusion": "success"}], "Test Checks"))
        self.assertIsNone(labels.check_conclusion([], "Test Checks"))

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

    def test_a_review_the_controller_refused_for_the_cap_is_the_review_limit(self):
        state = self.derive(
            validate_run=run("pull_request"),
            validate_jobs=[job("auto-review-submit")],
            capped=stamp(4))
        self.assertEqual(state[0], labels.REVIEW_LIMIT)
        self.assertEqual(state[1], stamp(4))

    def test_a_fix_the_controller_refused_for_the_cap_is_the_review_limit(self):
        state = self.derive(test_run=run("deployment", conclusion="failure",
                                         attempt=3),
                            staged=True, capped=stamp(1))
        self.assertEqual(state[0], labels.REVIEW_LIMIT)

    def test_the_cap_does_not_hide_a_running_or_passing_test_run(self):
        running = self.derive(test_run=run("deployment", status="in_progress"),
                              capped=stamp(30))
        self.assertEqual(running[0], labels.FULL_TESTS)
        passed = self.derive(
            pr={"number": 7, "mergeable_state": "clean"},
            test_run=run("deployment"), capped=stamp(30),
            check_runs=[check("All Checks", "success"),
                        check("Test Checks", "success")])
        self.assertEqual(passed[0], labels.READY)

    def test_the_review_limit_never_stalls(self):
        self.assertFalse(labels.is_stalled(labels.REVIEW_LIMIT, stamp(10 ** 5), NOW))


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
        self.requested = []

    def get(self, path):
        self.requested.append(path)
        for prefix, answer in self.answers:
            if path.startswith(prefix):
                return answer(path) if callable(answer) else answer
        # A head commit with no commit statuses, unless a test says otherwise.
        if "/statuses" in path:
            return []
        raise AssertionError("unexpected GET " + path)

    get_all = labels.StatusClient.get_all

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

    def test_ignores_a_deployment_run_of_another_branch(self):
        # A deployment to another environment (worker, production,
        # start-tests) is made from master, so its run is master's.
        master_deploy = dict(run("deployment", minutes_ago=1), id=13,
                             head_branch="master")
        validate = dict(run("pull_request"), id=14)
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/workflows/", {"workflow_runs": [master_deploy,
                                                      validate]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("actions/runs/14/jobs", {"jobs": []}),
        ])
        facts = labels.gather(client, PR)
        self.assertIsNone(facts["test_run"])
        self.assertEqual(facts["validate_run"]["id"], 14)

    def test_reads_every_page_of_check_runs_and_jobs(self):
        # 100 shard check runs on page 1; the newest gates are on page 2.
        def checks(path):
            if path.endswith("page=1"):
                return {"check_runs": [dict(check("shard", "success"), id=n)
                                       for n in range(100)]}
            return {"check_runs": [dict(check("All Checks", "success"),
                                        id=500),
                                   dict(check("Test Checks", "failure"),
                                        id=501)]}

        def jobs(path):
            if path.endswith("page=1"):
                return {"jobs": [job("shard %d" % n) for n in range(100)]}
            return {"jobs": [job("auto-resolve-python-submit")]}

        test_run = dict(run("deployment", conclusion="failure", attempt=3),
                        id=11)
        validate = dict(run("pull_request"), id=14)
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/workflows/", {"workflow_runs": [test_run, validate]}),
            ("commits/abc/check-runs", checks),
            ("actions/runs/14/jobs", jobs),
            ("actions/runs/11/attempts/3/jobs", jobs),
            ("actions/runs/11/artifacts", {"artifacts": []}),
        ])
        facts = labels.gather(client, PR)
        self.assertEqual(len(facts["check_runs"]), 102)
        self.assertEqual(labels.check_conclusion(facts["check_runs"],
                                                 "Test Checks"), "failure")
        self.assertEqual(len(facts["validate_jobs"]), 101)
        self.assertEqual(len(facts["test_jobs"]), 101)
        self.assertEqual(labels.derive(facts, NOW)[0], labels.AUTO_FIX)
        self.assertIn("commits/abc/check-runs?per_page=100&page=2",
                      client.requested)
        self.assertIn("actions/runs/14/jobs?filter=all&per_page=100&page=2",
                      client.requested)
        self.assertIn("actions/runs/11/attempts/3/jobs?per_page=100&page=2",
                      client.requested)

    def test_a_short_wrapped_page_ends_the_read(self):
        client = FakeClient([("commits/abc/check-runs", {})])
        self.assertEqual(client.get_all("commits/abc/check-runs",
                                        "check_runs"), [])
        self.assertEqual(client.requested,
                         ["commits/abc/check-runs?per_page=100&page=1"])

    def test_open_pulls_skips_forks(self):
        fork = {"number": 8, "head": {"ref": "x", "repo": {"full_name": "f/r"}}}
        client = FakeClient([("pulls?", [PR, fork])])
        self.assertEqual([p["number"] for p in labels.open_pulls(client)], [7])

    def test_open_pulls_reads_every_page(self):
        def page(path):
            number = int(path.rsplit("page=", 1)[1])
            if number == 1:
                return [dict(PR, number=n) for n in range(100)]
            return [dict(PR, number=100 + number)] if number == 2 else []
        client = FakeClient([("pulls?", page)])
        numbers = [p["number"] for p in labels.open_pulls(client)]
        self.assertEqual(len(numbers), 101)
        self.assertEqual(numbers[-1], 102)
        self.assertEqual(client.requested,
                         ["pulls?state=open&per_page=100&page=1",
                          "pulls?state=open&per_page=100&page=2"])

    def test_ensure_labels_sees_labels_on_a_later_page(self):
        def page(path):
            if path.endswith("page=1"):
                return [{"name": "other %d" % n} for n in range(100)]
            return [{"name": name} for name in labels.LABELS
                    if name != labels.STALLED]
        client = FakeClient([("labels?", page)])
        labels.ensure_labels(client)
        self.assertEqual([body["name"] for _, _, body in client.sent],
                         [labels.STALLED])
        self.assertEqual(client.requested[0], "labels?per_page=100&page=1")


class CapStatusTest(unittest.TestCase):
    """The remediation-cap commit status, from the submitters to the label."""

    def test_only_the_newest_cap_status_counts(self):
        self.assertEqual(labels.capped_at([
            {"context": "ci/other", "state": "failure", "created_at": stamp(1)},
            {"context": labels.CAP_STATUS, "state": "failure",
             "created_at": stamp(5)},
            {"context": labels.CAP_STATUS, "state": "success",
             "created_at": stamp(9)}]), stamp(5))
        self.assertIsNone(labels.capped_at([
            {"context": labels.CAP_STATUS, "state": "success",
             "created_at": stamp(1)},
            {"context": labels.CAP_STATUS, "state": "failure",
             "created_at": stamp(5)}]))
        self.assertIsNone(labels.capped_at([]))

    def test_gather_reads_the_head_commits_statuses(self):
        validate = dict(run("pull_request"), id=14)
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/workflows/", {"workflow_runs": [validate]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("commits/abc/statuses", [{"context": labels.CAP_STATUS,
                                       "state": "failure",
                                       "created_at": stamp(2)}]),
            ("actions/runs/14/jobs", {"jobs": [job("auto-review-submit")]}),
        ])
        facts = labels.gather(client, PR)
        self.assertEqual(facts["capped"], stamp(2))
        self.assertEqual(labels.derive(facts, NOW)[0], labels.REVIEW_LIMIT)

    def test_the_status_name_matches_the_submit_script(self):
        with open(os.path.join(ROOT, "tools", "ci", "submit-agent-job.sh")) as handle:
            self.assertIn("context=%s" % labels.CAP_STATUS, handle.read())

    def test_the_submit_script_recognises_the_controllers_cap_reason(self):
        """submit-agent-job.sh matches "remediation cap" in the skip reason."""
        guard = os.path.join(ROOT, "flowtree", "runtime", "src", "main", "java",
                             "io", "flowtree", "controller",
                             "RemediationBudgetGuard.java")
        with open(guard) as handle:
            self.assertIn('"Per-PR remediation cap of "', handle.read())
        with open(os.path.join(ROOT, "tools", "ci", "submit-agent-job.sh")) as handle:
            self.assertIn('*"remediation cap"*)', handle.read())

    def test_every_remediation_submitter_can_mark_the_cap(self):
        """Each job that submits a remediation pass passes the head commit and
        may write commit statuses."""
        found = 0
        for name in ("analysis.yaml", "auto-resolve-submit.yaml"):
            with open(os.path.join(ROOT, ".github", "workflows", name)) as handle:
                workflow = yaml.safe_load(handle)
            for job_id, body in workflow["jobs"].items():
                for step in body.get("steps", []):
                    env = step.get("env", {})
                    if env.get("REMEDIATION_PASS") != "true":
                        continue
                    found += 1
                    self.assertIn("CAP_STATUS_SHA", env, job_id)
                    self.assertIn("GH_TOKEN", env, job_id)
                    self.assertEqual(body["permissions"].get("statuses"), "write",
                                     job_id)
        self.assertEqual(found, 3)


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
            "issues": "write", "pull-requests": "write", "statuses": "read"})

    def test_max_attempts_matches_the_retry_gate(self):
        with open(os.path.join(ROOT, ".github", "workflows",
                               "auto-resolve-submit.yaml")) as handle:
            text = handle.read()
        self.assertIn('MAX_ATTEMPTS: "%d"' % labels.MAX_ATTEMPTS, text)


if __name__ == "__main__":
    unittest.main()
