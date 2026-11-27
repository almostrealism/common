"""Tests for tools/ci/pr-status-labels.py and the workflow that runs it."""

import contextlib
import dataclasses
import datetime
import importlib.util
import io
import os
import tempfile
import unittest
from unittest import mock

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


def plan_pr(draft=True, base_ref="master", default_branch="master",
            head_full="owner/repo"):
    """A pull request detail object for the ready-to-launch conditions."""
    return {"number": 7, "draft": draft,
            "base": {"ref": base_ref, "repo": {"default_branch": default_branch}},
            "head": {"ref": "project/plan-x", "sha": "abc",
                     "repo": {"full_name": head_full}}}


def changed(filename, status="added"):
    """One entry of the pull request files API."""
    return {"filename": filename, "status": status}


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

    def test_a_validate_run_that_handed_off_on_a_retry_is_auto_review(self):
        """When the infra retry gate re-runs a failed validate run and a later
        attempt passes, auto-review now hands off on that attempt. The labeller
        reads it with no change: ``submitted`` looks for an auto-review-submit
        success in the run's jobs (``gather`` fetches them with ``filter=all``,
        i.e. across every attempt), so a handoff on attempt 2 reads as
        ``ci: auto-review`` just as one on attempt 1 does — not as the
        ``ci: needs attention`` the stranded run used to show."""
        state = self.derive(
            validate_run=run("pull_request", conclusion="success", attempt=2),
            validate_jobs=[job("auto-review-submit")])
        self.assertEqual(state[0], labels.AUTO_REVIEW)

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

    def test_ready_to_launch_is_added_beside_the_state(self):
        add, remove = labels.label_changes([labels.NEEDS_ATTENTION],
                                           labels.NEEDS_ATTENTION, False,
                                           ready=True)
        self.assertEqual((add, remove), ([labels.READY_TO_LAUNCH], []))

    def test_both_markers_are_added_beside_the_state(self):
        add, remove = labels.label_changes([labels.NEEDS_ATTENTION],
                                           labels.NEEDS_ATTENTION, True,
                                           ready=True)
        self.assertEqual(add,
                         sorted([labels.STALLED, labels.READY_TO_LAUNCH]))
        self.assertEqual(remove, [])

    def test_ready_to_launch_removes_a_present_ready_for_review(self):
        # The settled stage where the marker takes the label's place: a draft
        # plan currently ci: ready for review that is ready to launch shows the
        # marker alone, so ci: ready for review is removed as the marker is added.
        add, remove = labels.label_changes([labels.READY], labels.READY, False,
                                           ready=True)
        self.assertEqual((add, remove),
                         ([labels.READY_TO_LAUNCH], [labels.READY]))

    def test_ready_to_launch_is_removed_when_no_longer_ready(self):
        add, remove = labels.label_changes(
            [labels.FULL_TESTS, labels.READY_TO_LAUNCH], labels.FULL_TESTS,
            False, ready=False)
        self.assertEqual((add, remove), ([], [labels.READY_TO_LAUNCH]))

    def test_ready_to_launch_is_suppressed_while_auto_review_is_the_state(self):
        # The invariant: a plan an agent is still reviewing is not ready to
        # launch, so the marker is never added beside ci: auto-review.
        add, remove = labels.label_changes([labels.AUTO_REVIEW],
                                           labels.AUTO_REVIEW, False,
                                           ready=True)
        self.assertEqual((add, remove), ([], []))

    def test_starting_auto_review_removes_an_existing_ready_to_launch(self):
        # A review starting on a later push: the stage is ci: auto-review and
        # the marker left from the settled state is removed on this pass.
        add, remove = labels.label_changes(
            [labels.READY_TO_LAUNCH], labels.AUTO_REVIEW, False, ready=True)
        self.assertEqual((add, remove),
                         ([labels.AUTO_REVIEW], [labels.READY_TO_LAUNCH]))

    def test_ready_to_launch_is_suppressed_beside_every_in_progress_state(self):
        for state in (labels.VALIDATING, labels.AUTO_REVIEW,
                      labels.FULL_TESTS, labels.AUTO_FIX):
            add, remove = labels.label_changes([state], state, False,
                                               ready=True)
            self.assertNotIn(labels.READY_TO_LAUNCH, add, state)

    def test_ready_to_launch_replaces_ready_for_review(self):
        # On the settled ready-for-review stage the marker takes that label's
        # place: the wanted set is {READY_TO_LAUNCH}, never both green labels.
        add, remove = labels.label_changes([], labels.READY, False, ready=True)
        self.assertEqual((add, remove), ([labels.READY_TO_LAUNCH], []))

    def test_ready_for_review_stays_when_not_ready_to_launch(self):
        # A settled PR that is not a ready-to-launch draft plan keeps
        # ci: ready for review exactly as before.
        self.assertEqual(
            labels.label_changes([], labels.READY, False, ready=False),
            ([labels.READY], []))
        self.assertEqual(
            labels.label_changes([labels.READY], labels.READY, False,
                                 ready=False), ([], []))

    def test_a_pr_carrying_both_green_labels_loses_ready_for_review(self):
        # A pull request that already shows both gets ci: ready for review
        # removed so only ci: ready to launch remains.
        add, remove = labels.label_changes(
            [labels.READY, labels.READY_TO_LAUNCH], labels.READY, False,
            ready=True)
        self.assertEqual((add, remove), ([], [labels.READY]))

    def test_the_marker_sits_beside_review_limit(self):
        # ci: review limit is a settled stage the marker joins, as before.
        add, remove = labels.label_changes([labels.REVIEW_LIMIT],
                                           labels.REVIEW_LIMIT, False,
                                           ready=True)
        self.assertEqual((add, remove), ([labels.READY_TO_LAUNCH], []))

    def test_the_two_green_labels_are_never_both_wanted(self):
        # The owner's rule, across every stage and both markers: a pull request
        # is never wanted to carry ci: ready for review and ci: ready to launch
        # at once.
        for stage in labels.LABELS:
            for stalled in (False, True):
                for ready in (False, True):
                    wanted = labels.wanted_labels(stage, stalled, ready)
                    self.assertFalse(
                        labels.READY in wanted
                        and labels.READY_TO_LAUNCH in wanted,
                        (stage, stalled, ready))


class ReadyToLaunchTest(unittest.TestCase):
    """Each condition of the ready-to-launch marker, on and off. It mirrors the
    ready-plan -> plan-ready -> verify-completion -> implement-plan chain."""

    REPO = "owner/repo"

    def ready(self, pr=None, files=None):
        return labels.ready_to_launch(
            {"pr": pr if pr is not None else plan_pr(),
             "files": files if files is not None
             else [changed("docs/plans/PLAN-x.md")]},
            self.REPO)

    def test_all_conditions_met_is_ready(self):
        self.assertTrue(self.ready())

    def test_a_non_draft_pr_is_not_ready(self):
        self.assertFalse(self.ready(pr=plan_pr(draft=False)))

    def test_a_pr_not_targeting_the_default_branch_is_not_ready(self):
        self.assertFalse(self.ready(pr=plan_pr(base_ref="release/1.0")))

    def test_a_fork_head_is_not_ready(self):
        self.assertFalse(self.ready(pr=plan_pr(head_full="fork/repo")))

    def test_a_change_outside_docs_is_not_ready(self):
        self.assertFalse(self.ready(files=[
            changed("docs/plans/PLAN-x.md"),
            changed("src/main/java/Foo.java", "modified")]))

    def test_no_new_plan_document_is_not_ready(self):
        # Docs-only, but nothing added under docs/plans.
        self.assertFalse(self.ready(files=[changed("docs/guide.md")]))

    def test_the_manager_log_does_not_count_as_a_plan(self):
        self.assertFalse(self.ready(files=[
            changed("docs/plans/MANAGER_LOG.md")]))

    def test_a_plan_whose_path_contains_the_manager_log_name_is_not_ready(self):
        # implement-plan.yaml's detect-plan drops every path matching
        # 'MANAGER_LOG.md', not only the exact basename, so approving this
        # pull request would find no plan.
        self.assertFalse(self.ready(files=[
            changed("docs/plans/PLAN-MANAGER_LOG.md")]))
        self.assertFalse(self.ready(files=[
            changed("docs/plans/MANAGER_LOG.md/PLAN-x.md")]))

    def test_a_modified_plan_is_not_a_new_plan(self):
        self.assertFalse(self.ready(files=[
            changed("docs/plans/PLAN-x.md", "modified")]))

    def test_no_changed_files_is_not_ready(self):
        self.assertFalse(self.ready(files=[]))

    def test_a_new_plan_beside_other_docs_is_ready(self):
        self.assertTrue(self.ready(files=[
            changed("docs/guide.md", "modified"),
            changed("docs/plans/sub/PLAN-y.md")]))

    def test_docs_dir_without_a_trailing_slash_is_a_directory_boundary(self):
        # A consumer passing "docs" (no slash) must not accept a sibling such
        # as "docs-other/": a change there is outside the docs directory and
        # blocks the marker, exactly as the normalized plans_dir does.
        config = dataclasses.replace(labels.DEFAULTS, docs_dir="docs")
        facts = {"pr": plan_pr(),
                 "files": [changed("docs/plans/PLAN-x.md"),
                           changed("docs-other/thing.py", "modified")]}
        self.assertFalse(labels.ready_to_launch(facts, self.REPO, config))

    def test_the_marker_is_the_same_green_as_ready_for_review(self):
        self.assertEqual(labels.LABELS[labels.READY_TO_LAUNCH][0],
                         labels.LABELS[labels.READY][0])

    def test_the_marker_never_stalls(self):
        self.assertFalse(labels.is_stalled(labels.READY_TO_LAUNCH,
                                           stamp(10 ** 5), NOW))

    def test_gather_reads_the_files_of_a_draft_plan_pr(self):
        item = {"number": 7, "head": {"sha": "abc", "ref": "project/plan-x",
                                      "repo": {"full_name": "owner/repo"}}}
        client = FakeClient([
            ("pulls/7/files", [changed("docs/plans/PLAN-x.md")]),
            ("pulls/7", plan_pr()),
            ("actions/workflows/", {"workflow_runs": []}),
            ("commits/abc/check-runs", {"check_runs": []}),
        ])
        facts = labels.gather(client, item)
        self.assertTrue(labels.ready_to_launch(facts, client.repo))

    def test_gather_skips_the_files_of_a_non_draft_pr(self):
        item = {"number": 7, "head": {"sha": "abc", "ref": "feature/x",
                                      "repo": {"full_name": "owner/repo"}}}
        client = FakeClient([
            ("pulls/7", plan_pr(draft=False)),
            ("actions/workflows/", {"workflow_runs": []}),
            ("commits/abc/check-runs", {"check_runs": []}),
        ])
        facts = labels.gather(client, item)
        self.assertNotIn("files", facts)
        self.assertFalse(labels.ready_to_launch(facts, client.repo))


class SummaryRowTest(unittest.TestCase):
    """The step-summary row shows each marker beside the stage label."""

    PR = {"number": 7, "title": "Plan a | b"}

    def row(self, stalled, ready):
        return labels.summary_row(self.PR, labels.VALIDATING, "2026-01-01",
                                  "why", stalled, ready)

    def test_no_marker(self):
        self.assertEqual(self.row(False, False),
                         "| #7 | Plan a \\| b | %s | 2026-01-01 | why |"
                         % labels.VALIDATING)

    def test_stalled_marker(self):
        self.assertEqual(self.row(True, False),
                         "| #7 | Plan a \\| b | %s (stalled) | 2026-01-01 | why |"
                         % labels.VALIDATING)

    def test_ready_to_launch_marker(self):
        self.assertEqual(
            self.row(False, True),
            "| #7 | Plan a \\| b | %s (ready to launch) | 2026-01-01 | why |"
            % labels.VALIDATING)

    def test_both_markers_stalled_first(self):
        self.assertEqual(
            self.row(True, True),
            "| #7 | Plan a \\| b | %s (stalled) (ready to launch) "
            "| 2026-01-01 | why |" % labels.VALIDATING)

    def test_missing_since_is_blank(self):
        self.assertEqual(
            labels.summary_row(self.PR, labels.VALIDATING, None, "why",
                               False, True),
            "| #7 | Plan a \\| b | %s (ready to launch) |  | why |"
            % labels.VALIDATING)


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


class MainTest(unittest.TestCase):
    """``main`` end to end against a ``FakeClient``: the reporting path that
    derives the stage shown and the launch marker from the wanted set, so a
    regression in that computation is caught, not only in ``wanted_labels`` and
    ``summary_row`` in isolation. Run with ``--dry-run`` so no label is written;
    the reporting still runs."""

    def run_main(self, client):
        """Run ``main --dry-run`` against ``client``, returning its stdout and
        the step-summary text it wrote."""
        handle = tempfile.NamedTemporaryFile("w+", suffix=".md", delete=False)
        handle.close()
        env = {"REPO": "owner/repo", "GH_TOKEN": "",
               "GITHUB_STEP_SUMMARY": handle.name}
        out = io.StringIO()
        try:
            with mock.patch.object(labels, "StatusClient",
                                   lambda *a, **k: client), \
                    mock.patch.dict(os.environ, env), \
                    contextlib.redirect_stdout(out):
                labels.main(["--dry-run"])
            with open(handle.name) as summary:
                written = summary.read()
        finally:
            os.unlink(handle.name)
        return out.getvalue(), written

    def test_a_ready_to_launch_draft_plan_reports_the_marker_as_the_stage(self):
        # A settled draft plan both ready for review and ready to launch,
        # already carrying ci: ready for review. Both the stdout line and the
        # step-summary row show ci: ready to launch as the stage, never beside
        # ci: ready for review and never with the separate marker suffix.
        item = {"number": 7,
                "head": {"sha": "abc", "ref": "project/plan-x",
                         "repo": {"full_name": "owner/repo"}},
                "labels": [{"name": labels.READY}]}
        client = FakeClient([
            ("pulls?state=open", [item]),
            ("pulls/7/files", [changed("docs/plans/PLAN-x.md")]),
            ("pulls/7", plan_pr()),
            ("actions/workflows/", {"workflow_runs": [
                dict(run("deployment"), head_branch="project/plan-x")]}),
            ("commits/abc/check-runs", {"check_runs": [
                check("All Checks", "success"),
                check("Test Checks", "success")]}),
        ])
        stdout, summary = self.run_main(client)
        self.assertIn("#7 " + labels.READY_TO_LAUNCH + ":", stdout)
        self.assertNotIn(" ready-to-launch", stdout)
        row = [line for line in summary.splitlines()
               if line.startswith("| #7")][0]
        self.assertIn("| %s |" % labels.READY_TO_LAUNCH, row)
        self.assertNotIn("(ready to launch)", row)
        self.assertNotIn(labels.READY, summary)

    def test_a_plain_ready_pull_request_keeps_ready_for_review_as_the_stage(self):
        # A settled pull request ready for review that is not a draft plan: the
        # marker does not apply, so ci: ready for review stays the stage and
        # ci: ready to launch appears nowhere.
        item = {"number": 9,
                "head": {"sha": "abc", "ref": "feature/x",
                         "repo": {"full_name": "owner/repo"}},
                "labels": []}
        client = FakeClient([
            ("pulls?state=open", [item]),
            ("pulls/9", {"number": 9, "draft": False,
                         "base": {"ref": "master",
                                  "repo": {"default_branch": "master"}},
                         "head": {"ref": "feature/x", "sha": "abc",
                                  "repo": {"full_name": "owner/repo"}},
                         "mergeable_state": "clean"}),
            ("actions/workflows/", {"workflow_runs": [
                dict(run("deployment"), head_branch="feature/x")]}),
            ("commits/abc/check-runs", {"check_runs": [
                check("All Checks", "success"),
                check("Test Checks", "success")]}),
        ])
        stdout, summary = self.run_main(client)
        self.assertIn("#9 " + labels.READY + ":", stdout)
        self.assertNotIn(labels.READY_TO_LAUNCH, stdout)
        self.assertIn(labels.READY, summary)
        self.assertNotIn(labels.READY_TO_LAUNCH, summary)


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
    """This repository's caller: it has only what it needs and reproduces this
    repository's own behaviour through the reusable workflow's inputs."""

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

    def test_calls_the_reusable_workflow(self):
        self.assertEqual(self.job["uses"],
                         "./.github/workflows/pr-status-labels-run.yaml")

    def test_checks_out_the_default_branch(self):
        self.assertEqual(self.job["with"]["framework-ref"],
                         "${{ github.event.repository.default_branch }}")

    def test_permissions(self):
        self.assertEqual(self.job["permissions"], {
            "actions": "read", "checks": "read", "contents": "read",
            "issues": "write", "pull-requests": "write", "statuses": "read"})

    def test_passes_this_repositorys_pipeline_as_inputs(self):
        with_ = self.job["with"]
        self.assertEqual(with_["workflow-file"], "analysis.yaml")
        self.assertEqual(with_["all-checks"], "All Checks")
        self.assertEqual(with_["test-checks"], "Test Checks")
        self.assertEqual(with_["validate-submitters"],
                         ",".join(labels.VALIDATE_SUBMITTERS))
        self.assertEqual(with_["test-submitters"],
                         ",".join(labels.TEST_SUBMITTERS))
        self.assertEqual(with_["staged-request"], labels.STAGED_REQUEST)
        self.assertEqual(with_["cap-status"], labels.CAP_STATUS)
        self.assertIs(with_["expect-test-run"], True)
        self.assertEqual(with_["plans-dir"], labels.PLANS_DIR)
        self.assertEqual(with_["plan-exclude-pattern"],
                         labels.PLAN_EXCLUDE_PATTERN)

    def test_max_attempts_matches_the_retry_gate(self):
        with open(os.path.join(ROOT, ".github", "workflows",
                               "auto-resolve-submit.yaml")) as handle:
            text = handle.read()
        self.assertIn('MAX_ATTEMPTS: "%d"' % labels.MAX_ATTEMPTS, text)


class ReusableWorkflowTest(unittest.TestCase):
    """The reusable workflow: a trusted checkout of the framework, generic
    (non-FlowTree, non-this-repository) defaults, and only what it needs."""

    def setUp(self):
        with open(os.path.join(ROOT, ".github", "workflows",
                               "pr-status-labels-run.yaml")) as handle:
            self.workflow = yaml.safe_load(handle)
        self.job = self.workflow["jobs"]["label"]
        self.inputs = self.workflow[True]["workflow_call"]["inputs"]

    def test_triggered_only_by_workflow_call(self):
        self.assertEqual(list(self.workflow[True].keys()), ["workflow_call"])

    def test_checks_out_the_framework_explicitly(self):
        checkout = self.job["steps"][0]
        self.assertTrue(checkout["uses"].startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["repository"],
                         "${{ inputs.framework-repository }}")
        self.assertEqual(checkout["with"]["ref"], "${{ inputs.framework-ref }}")
        self.assertFalse(checkout["with"].get("persist-credentials", True))

    def test_declares_no_permissions_of_its_own(self):
        # A reusable workflow's token is the caller's grant. Declaring no
        # permissions block lets each caller ask for only what its own config
        # needs (statuses: read only with cap-status), as qa-round.yaml does.
        self.assertNotIn("permissions", self.job)

    def test_defaults_assume_no_particular_repositorys_pipeline(self):
        # A repository with no FlowTree workstream and no particular job or
        # check-run names must get no label that assumes any of them exist.
        for name in ("workflow-file", "all-checks", "test-checks",
                     "validate-submitters", "test-submitters",
                     "staged-request", "cap-status", "plans-dir",
                     "plan-exclude-pattern"):
            self.assertEqual(self.inputs[name]["default"], "", name)
        self.assertIs(self.inputs["expect-test-run"]["default"], False)

    def test_runs_the_script_with_every_input_as_an_environment_variable(self):
        run = self.job["steps"][1]["run"]
        env = self.job["steps"][1]["env"]
        for env_name, config_field in (
                ("WORKFLOW_FILE", "workflow_file"),
                ("VALIDATE_EVENT", "validate_event"),
                ("TEST_EVENT", "test_event"),
                ("ALL_CHECKS", "all_checks"),
                ("TEST_CHECKS", "test_checks"),
                ("VALIDATE_SUBMITTERS", "validate_submitters"),
                ("TEST_SUBMITTERS", "test_submitters"),
                ("STAGED_REQUEST", "staged_request"),
                ("CAP_STATUS", "cap_status"),
                ("MAX_ATTEMPTS", "max_attempts"),
                ("RETRY_DECISION_MINUTES", "retry_decision_minutes"),
                ("EXPECT_TEST_RUN", "expect_test_run"),
                ("PLANS_DIR", "plans_dir"),
                ("DOCS_DIR", "docs_dir"),
                ("PLAN_EXCLUDE_PATTERN", "plan_exclude_pattern")):
            self.assertIn(env_name, env, config_field)
            self.assertTrue(
                any(field.name == config_field
                    for field in dataclasses.fields(labels.Config)),
                config_field)
        self.assertIn("pr-status-labels.py", run)


class ConfigTest(unittest.TestCase):
    """``Config.from_env`` reads the environment the reusable workflow sets,
    distinguishing unset (keep this repository's default) from set-but-empty
    (disable the feature)."""

    def test_defaults_match_this_repository(self):
        self.assertEqual(labels.DEFAULTS, labels.Config())
        self.assertEqual(labels.DEFAULTS.workflow_file, labels.WORKFLOW_FILE)
        self.assertEqual(labels.DEFAULTS.validate_submitters,
                         labels.VALIDATE_SUBMITTERS)
        self.assertTrue(labels.DEFAULTS.expect_test_run)
        self.assertEqual(labels.DEFAULTS.plans_dir, labels.PLANS_DIR)

    def test_an_unset_variable_keeps_the_default(self):
        self.assertEqual(labels.Config.from_env({}), labels.DEFAULTS)

    def test_an_empty_submitters_variable_means_no_such_job(self):
        config = labels.Config.from_env({"VALIDATE_SUBMITTERS": ""})
        self.assertEqual(config.validate_submitters, ())

    def test_a_submitters_variable_is_comma_separated(self):
        config = labels.Config.from_env(
            {"TEST_SUBMITTERS": "one-submit, two-submit"})
        self.assertEqual(config.test_submitters, ("one-submit", "two-submit"))

    def test_integers_and_booleans_are_parsed(self):
        config = labels.Config.from_env(
            {"MAX_ATTEMPTS": "5", "RETRY_DECISION_MINUTES": "30",
             "EXPECT_TEST_RUN": "False"})
        self.assertEqual(config.max_attempts, 5)
        self.assertEqual(config.retry_decision_minutes, 30)
        self.assertFalse(config.expect_test_run)

    def test_an_invalid_boolean_is_an_error(self):
        with self.assertRaises(ValueError):
            labels.Config.from_env({"EXPECT_TEST_RUN": "yes"})

    def test_an_empty_plans_dir_disables_the_marker(self):
        config = labels.Config.from_env({"PLANS_DIR": ""})
        self.assertEqual(config.plans_dir, "")


class GeneralizationTest(unittest.TestCase):
    """The behaviour each generalization switch turns on or off, for a
    repository whose pipeline does not match this one's."""

    GENERIC = labels.Config(
        workflow_file="", validate_submitters=(), test_submitters=(),
        staged_request="", cap_status="", all_checks="", test_checks="",
        expect_test_run=False, plans_dir="")

    def test_empty_submitters_never_report_an_agent_sent(self):
        state = labels.derive(
            {"pr": {"number": 7},
             "validate_run": run("pull_request", conclusion="failure")},
            NOW, self.GENERIC)
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_expect_test_run_false_makes_a_passing_validate_run_ready(self):
        state = labels.derive(
            {"pr": {"number": 7, "mergeable_state": "clean"},
             "validate_run": run("pull_request", conclusion="success")},
            NOW, self.GENERIC)
        self.assertEqual(state[0], labels.READY)

    def test_expect_test_run_false_still_reports_a_merge_conflict(self):
        state = labels.derive(
            {"pr": {"number": 7, "mergeable_state": "dirty"},
             "validate_run": run("pull_request", conclusion="success")},
            NOW, self.GENERIC)
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_expect_test_run_true_awaits_a_test_run_instead(self):
        state = labels.derive(
            {"pr": {"number": 7},
             "validate_run": run("pull_request", conclusion="success")},
            NOW, labels.DEFAULTS)
        self.assertEqual(state[0], labels.NEEDS_ATTENTION)

    def test_empty_checks_skip_the_gate(self):
        state = labels.derive(
            {"pr": {"number": 7, "mergeable_state": "clean"},
             "test_run": run("deployment")},
            NOW, self.GENERIC)
        self.assertEqual(state[0], labels.READY)

    def test_an_empty_cap_status_skips_the_statuses_request(self):
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/runs?", {"workflow_runs": [dict(run("pull_request"),
                                                      id=21)]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("actions/runs/21/jobs", {"jobs": []}),
        ])
        facts = labels.gather(client, PR, self.GENERIC)
        self.assertIsNone(facts["capped"])
        self.assertNotIn("commits/abc/statuses?per_page=100&page=1",
                         client.requested)

    def test_an_empty_cap_status_is_never_matched(self):
        self.assertIsNone(labels.capped_at(
            [{"context": "remediation-cap", "state": "failure",
              "created_at": stamp(1)}], self.GENERIC))

    def test_an_empty_plans_dir_disables_ready_to_launch(self):
        self.assertFalse(labels.ready_to_launch(
            {"pr": plan_pr(), "files": [changed("docs/plans/PLAN-x.md")]},
            "owner/repo", self.GENERIC))

    def test_gather_skips_the_files_request_when_plans_dir_is_empty(self):
        item = {"number": 7, "head": {"sha": "abc", "ref": "project/plan-x",
                                      "repo": {"full_name": "owner/repo"}}}
        client = FakeClient([
            ("pulls/7", plan_pr()),
            ("actions/runs?", {"workflow_runs": []}),
            ("commits/abc/check-runs", {"check_runs": []}),
        ])
        facts = labels.gather(client, item, self.GENERIC)
        self.assertNotIn("files", facts)

    def test_an_empty_workflow_file_reads_every_workflows_runs(self):
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/runs?", {"workflow_runs": [dict(run("pull_request"),
                                                      id=14)]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("actions/runs/14/jobs", {"jobs": []}),
        ])
        facts = labels.gather(client, PR, self.GENERIC)
        self.assertEqual(facts["validate_run"]["id"], 14)
        self.assertIn("actions/runs?head_sha=abc&per_page=50",
                      client.requested)

    def test_an_empty_staged_request_skips_the_artifact_request(self):
        test_run = dict(run("deployment", conclusion="failure", attempt=3),
                        id=11)
        client = FakeClient([
            ("pulls/7", {"number": 7}),
            ("actions/runs?", {"workflow_runs": [test_run]}),
            ("commits/abc/check-runs", {"check_runs": []}),
            ("actions/runs/11/attempts/3/jobs", {"jobs": []}),
        ])
        facts = labels.gather(client, PR, self.GENERIC)
        self.assertFalse(facts["staged"])
        self.assertNotIn("actions/runs/11/artifacts", client.requested[-1])


if __name__ == "__main__":
    unittest.main()
