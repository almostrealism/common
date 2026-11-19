#!/usr/bin/env python3
"""Keep one CI status label on every open pull request.

A pull request moves through several stages that the checks list alone does not
tell apart: a validate run, an agent's review loop, the full test run with its
flaky-test retries, an agent fixing what the test run found, and finally a head
commit that has passed both runs. This script works out which stage each open
pull request of this repository is in and gives it exactly one ``ci: ...``
label for it, plus ``ci: stalled`` when it has been in that stage longer than
the stage should take.

The state is derived from scratch from the GitHub API on every call, never
from the labels already on the pull request or from the event that started the
call, so events that arrive late, out of order or not at all cannot leave a
wrong label behind: the next call corrects it.

What decides each state (``derive``), for the pull request's head commit:

- a test run (a ``deployment`` run of Build and Test) exists:
    - not finished: ``ci: full tests``
    - it succeeded, and the newest ``Test Checks`` and the validate run's
      ``All Checks`` passed:
      ``ci: ready for review`` (``ci: needs attention`` instead when the pull
      request has a merge conflict)
    - failed, and an agent was sent (``auto-resolve-python-submit`` succeeded,
      or ``auto-resolve`` staged a request in the latest attempt):
      ``ci: auto-fix``, or ``ci: review limit`` when the controller refused it
      because the pull request reached the per-PR remediation cap
    - failed on an attempt before the last, within a few minutes of finishing:
      ``ci: full tests``, since the retry gate may be about to re-run it
    - otherwise: ``ci: needs attention``
- otherwise a validate run (a ``pull_request`` run) exists:
    - not finished: ``ci: validating``
    - an agent was sent (``auto-review-submit`` or
      ``auto-resolve-python-submit`` succeeded, in any attempt):
      ``ci: auto-review``. The agent either pushes, which starts a new validate
      run, or finds nothing left to change, which starts the test run. When
      the controller refused it because the pull request reached the per-PR
      remediation cap: ``ci: review limit``.
    - otherwise: ``ci: needs attention``
- no run yet: ``ci: validating``

Beside that stage label, the way ``ci: stalled`` sits beside it, a draft plan
pull request can also carry ``ci: ready to launch``: a marker that an approver
marking it ready for review now would launch an implementation agent. It
mirrors the conditions of the ready-for-review chain (``ready-plan.yaml`` ->
``plan-ready.yaml`` -> ``verify-completion.yaml`` -> ``implement-plan.yaml``)
that the ready-for-review transition runs — see ``ready_to_launch``. The
chain's in-flight-workstream guard is not among them: the labeller holds no
controller credentials, so the marker can show while a job is already in
flight (in which case the switch would be refused).

The cap is read from the ``remediation-cap`` commit status that
``submit-agent-job.sh`` sets on the head commit when the controller refuses a
remediation pass for that reason. The submit step itself stays green, and the
Auto-Resolve Submit runs belong to the default branch rather than the pull
request, so the status is the record on the pull request that the loop
stopped. A new commit from a person resets the controller's count and carries
no such status, so the label clears by itself.

Usage:
    pr-status-labels.py [--branch BRANCH] [--dry-run]

Environment:
    GH_TOKEN  a token with pull-requests and issues write, actions, checks and
              statuses read
    REPO      owner/name
    GITHUB_STEP_SUMMARY  when set, a table of every pull request's state is
              appended to it
"""

import argparse
import datetime
import importlib.util
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "collect_observed_flakes", os.path.join(_HERE, "collect-observed-flakes.py"))
flakes = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(flakes)

WORKFLOW_FILE = "analysis.yaml"

# The retry gate in auto-resolve-submit.yaml re-runs a failed test run until
# this attempt; keep it equal to that workflow's MAX_ATTEMPTS.
MAX_ATTEMPTS = 3

# How long after a failed test run finishes the retry gate is given to re-run
# it before the failure is reported as needing attention.
RETRY_DECISION_MINUTES = 15

VALIDATING = "ci: validating"
AUTO_REVIEW = "ci: auto-review"
FULL_TESTS = "ci: full tests"
AUTO_FIX = "ci: auto-fix"
READY = "ci: ready for review"
NEEDS_ATTENTION = "ci: needs attention"
REVIEW_LIMIT = "ci: review limit"
STALLED = "ci: stalled"
READY_TO_LAUNCH = "ci: ready to launch"

# The commit status submit-agent-job.sh sets on the head commit when the
# controller refuses a remediation pass because the per-PR cap is reached.
CAP_STATUS = "remediation-cap"

# label -> (colour, description, hours in the state before it is stalled;
# None never stalls).
LABELS = {
    VALIDATING: ("c5def5", "Validate run (build and static checks) in progress", 1),
    AUTO_REVIEW: ("d4c5f9", "An agent is reviewing the branch; the full test "
                            "run starts when it has nothing left to change", 4),
    FULL_TESTS: ("fbca04", "The full test run, with its flaky-test retries, "
                           "is in progress", 10),
    AUTO_FIX: ("f9a03f", "The full test run failed; an agent is fixing it", 4),
    READY: ("0e8a16", "Validate and full test runs passed on the head commit",
            None),
    NEEDS_ATTENTION: ("d93f0b", "CI stopped with no agent working on it", None),
    REVIEW_LIMIT: ("5319e7", "The auto-review/auto-fix pass limit was reached; "
                             "a person needs to look", None),
    STALLED: ("b60205", "In the same CI state for longer than it should take",
              None),
    READY_TO_LAUNCH: ("0e8a16", "Beside the stage label: approving this draft "
                                "plan now would launch an implementation agent",
                      None),
}

# The jobs whose success means a remediation agent was submitted.
VALIDATE_SUBMITTERS = ("auto-review-submit", "auto-resolve-python-submit")
TEST_SUBMITTERS = ("auto-resolve-python-submit",)
STAGED_REQUEST = "auto-resolve-request"


def parse_time(value):
    """A GitHub timestamp as an aware datetime, or None."""
    if not value:
        return None
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def newest(items, key="created_at"):
    """The item with the latest ``key`` timestamp, or None."""
    dated = [item for item in items if item.get(key)]
    return max(dated, key=lambda item: parse_time(item[key])) if dated else None


def submitted(jobs, names):
    """True when one of the named jobs succeeded."""
    return any(job.get("name") in names and job.get("conclusion") == "success"
               for job in jobs)


def check_conclusion(check_runs, name):
    """The conclusion of the newest check run with this name, or None.

    Newest is the highest id: check run ids increase with creation, and a
    queued check run has no ``started_at`` yet, so ordering by start time would
    pass over the queued gate and report an older run's result instead."""
    named = [run for run in check_runs
             if run.get("name") == name and run.get("id") is not None]
    latest = max(named, key=lambda run: run["id"]) if named else None
    return latest.get("conclusion") if latest else None


def derive(facts, now):
    """The state of one pull request: ``(label, since, reason)``.

    ``facts`` is what ``gather`` collects: the pull request, its head commit's
    newest validate and test runs, those runs' jobs, whether a request was
    staged, and the head commit's check runs. ``since`` is when the state
    began, which decides whether it has stalled."""
    pr = facts["pr"]
    test = facts.get("test_run")
    validate = facts.get("validate_run")
    checks = facts.get("check_runs", [])
    if test:
        started = test.get("run_started_at") or test.get("created_at")
        if test.get("status") != "completed":
            return FULL_TESTS, started, "test run attempt %s in progress" % (
                test.get("run_attempt", 1))
        finished = test.get("updated_at")
        # Check runs belong to the commit, not to a run, so an earlier test
        # run's passing Test Checks must not make a failed run look ready.
        if test.get("conclusion") == "success" and \
                check_conclusion(checks, "Test Checks") == "success":
            if check_conclusion(checks, "All Checks") != "success":
                return NEEDS_ATTENTION, finished, \
                    "Test Checks passed but All Checks has not"
            if pr.get("mergeable_state") == "dirty":
                return NEEDS_ATTENTION, finished, "merge conflict with the base"
            return READY, finished, "both runs passed"
        if test.get("conclusion") == "cancelled":
            return NEEDS_ATTENTION, finished, "test run cancelled"
        if facts.get("staged") or submitted(facts.get("test_jobs", []),
                                            TEST_SUBMITTERS):
            if facts.get("capped"):
                return REVIEW_LIMIT, facts["capped"], \
                    "the controller refused the fix: per-PR pass limit reached"
            return AUTO_FIX, finished, "agent sent after attempt %s" % (
                test.get("run_attempt", 1))
        if (test.get("run_attempt", 1) < MAX_ATTEMPTS and finished and
                now - parse_time(finished) <
                datetime.timedelta(minutes=RETRY_DECISION_MINUTES)):
            return FULL_TESTS, started, "waiting for the retry decision"
        return NEEDS_ATTENTION, finished, \
            "test run failed and no agent was sent"
    if validate:
        if validate.get("status") != "completed":
            return VALIDATING, validate.get("created_at"), \
                "validate run in progress"
        finished = validate.get("updated_at")
        if submitted(facts.get("validate_jobs", []), VALIDATE_SUBMITTERS):
            if facts.get("capped"):
                return REVIEW_LIMIT, facts["capped"], \
                    "the controller refused the review: per-PR pass limit reached"
            return AUTO_REVIEW, finished, "review agent sent"
        if validate.get("conclusion") == "cancelled":
            return NEEDS_ATTENTION, finished, "validate run cancelled"
        return NEEDS_ATTENTION, finished, \
            "validate run finished without sending an agent"
    return VALIDATING, pr.get("updated_at"), "no run for the head commit yet"


def is_stalled(label, since, now):
    """True when the pull request has been in ``label`` longer than allowed."""
    hours = LABELS[label][2]
    when = parse_time(since)
    if hours is None or when is None:
        return False
    return now - when > datetime.timedelta(hours=hours)


def draft_plan_pr(pr, repo):
    """Condition 1 of ``ready_to_launch``: an open draft pull request of this
    repository whose head is a branch here (not a fork) and whose base is the
    repository's default branch — the state a plan branch's pull request is in
    while it awaits approval.

    This is ``ready-plan.yaml``'s trigger (``pull_request_target`` of a draft
    marked ready, base the default branch) together with ``plan-ready.yaml``'s
    fork guard (the head is a branch in this repository). ``gather`` reads it to
    decide whether the changed files are worth fetching; ``ready_to_launch``
    rechecks it."""
    base = pr.get("base") or {}
    head = pr.get("head") or {}
    default_branch = (base.get("repo") or {}).get("default_branch")
    return bool(pr.get("draft")) and default_branch is not None \
        and base.get("ref") == default_branch \
        and (head.get("repo") or {}).get("full_name") == repo


def ready_to_launch(facts, repo):
    """Whether, if an approver switched this draft pull request to ready for
    review now, that switch would launch an implementation agent.

    It mirrors the conditions of the ``ready-plan.yaml`` -> ``plan-ready.yaml``
    -> ``verify-completion.yaml`` -> ``implement-plan.yaml`` chain a
    ready-for-review transition runs:

    1. the pull request is an open draft of this repository, targeting its
       default branch, with a head branch here and not a fork
       (``ready-plan.yaml``'s trigger and ``plan-ready.yaml``'s fork guard);
    2. every changed file is under ``docs/`` (``plan-ready.yaml``'s docs-only
       rule, read from the pull request's files, which GitHub computes as the
       merge-base diff against the base);
    3. the branch adds at least one new ``.md`` under ``docs/plans`` other than
       ``MANAGER_LOG.md`` (``implement-plan.yaml``'s ``detect-plan``: a file
       added relative to the default branch is ``status == "added"`` here).

    A fourth condition the chain enforces — that the branch's FlowTree
    workstream has no job pending or running (``implement-plan.yaml``'s
    in-flight guard, ``workstream-idle.sh``) — is NOT checked: the labeller
    holds no controller credentials, so the marker can show while a job is in
    flight, in which case the switch would be refused by that guard. The
    labeller also cannot know whether the person who switches it is in
    ``PLAN_APPROVERS``, which ``plan-ready.yaml`` requires."""
    if not draft_plan_pr(facts["pr"], repo):
        return False
    files = facts.get("files") or []
    names = [item.get("filename", "") for item in files]
    if not names or not all(name.startswith("docs/") for name in names):
        return False
    return any(item.get("status") == "added"
               and item.get("filename", "").startswith("docs/plans/")
               and item.get("filename", "").endswith(".md")
               and os.path.basename(item.get("filename", "")) != "MANAGER_LOG.md"
               for item in files)


def label_changes(current, label, stalled, ready=False):
    """The ``ci: `` labels to add and to remove, as two sorted lists.

    ``stalled`` and ``ready`` each add their marker beside the stage ``label``.
    Every ``ci: `` label this script manages other than the wanted ones is
    removed; labels outside that set are left alone."""
    wanted = {label}
    if stalled:
        wanted.add(STALLED)
    if ready:
        wanted.add(READY_TO_LAUNCH)
    present = set(current) & set(LABELS)
    return sorted(wanted - present), sorted(present - wanted)


class StatusClient(flakes.GitHubClient):
    """The REST calls this script makes, isolated so ``gather`` and ``apply``
    can be driven by a fake in the unit tests."""

    def get(self, path):
        """GET a repository-relative path."""
        return self._request("/repos/%s/%s" % (self.repo, path))

    def get_all(self, path, key=None):
        """Every item of a repository-relative list endpoint, following its
        pages 100 at a time.

        ``key`` names the field holding the list for an endpoint that wraps
        each page in an object (``check_runs``, ``jobs``); without it the
        endpoint must answer with a bare JSON array."""
        separator = "&" if "?" in path else "?"
        items = []
        page = 1
        while True:
            payload = self.get("%s%sper_page=100&page=%d"
                               % (path, separator, page))
            batch = payload.get(key, []) if key else payload
            items.extend(batch)
            if len(batch) < 100:
                return items
            page += 1

    def send(self, method, path, body=None):
        """A write request to a repository-relative path."""
        url = "%s/repos/%s/%s" % (self.api, self.repo, path)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/vnd.github+json")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
        request.add_header("Authorization", "Bearer %s" % self.token)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(
                request, timeout=flakes.REQUEST_TIMEOUT_SECONDS) as response:
            response.read()


def open_pulls(client, branch=None):
    """The repository's open pull requests from its own branches."""
    pulls = client.get_all("pulls?state=open")
    own = [pr for pr in pulls
           if (pr.get("head", {}).get("repo") or {}).get("full_name")
           == client.repo]
    if branch:
        own = [pr for pr in own if pr["head"]["ref"] == branch]
    return own


def capped_at(statuses):
    """When the head commit was marked with the remediation-cap status, or None.

    Statuses are listed newest first, and only the newest of a context counts,
    so a status later reset to success no longer marks the commit."""
    for status in statuses:
        if status.get("context") == CAP_STATUS:
            return status.get("created_at") if status.get("state") == "failure" \
                else None
    return None


def gather(client, pr):
    """Everything ``derive`` reads about one pull request."""
    sha = pr["head"]["sha"]
    branch = pr["head"]["ref"]
    detail = client.get("pulls/%d" % pr["number"])
    runs = client.get("actions/workflows/%s/runs?head_sha=%s&per_page=50"
                      % (WORKFLOW_FILE, sha)).get("workflow_runs", [])
    runs = [run for run in runs if run.get("head_branch") == branch]
    facts = {
        "pr": detail,
        "validate_run": newest([r for r in runs if r["event"] == "pull_request"]),
        "test_run": newest([r for r in runs if r["event"] == "deployment"]),
        # Every page: matrix shards over several attempts can put a commit
        # past 100 check runs, and the newest gate may be on a later page.
        "check_runs": client.get_all(
            "commits/%s/check-runs" % sha, "check_runs"),
        "capped": capped_at(client.get_all("commits/%s/statuses" % sha)),
    }
    validate = facts["validate_run"]
    if validate and validate.get("status") == "completed":
        facts["validate_jobs"] = client.get_all(
            "actions/runs/%d/jobs?filter=all" % validate["id"], "jobs")
    test = facts["test_run"]
    if test and test.get("status") == "completed" \
            and test.get("conclusion") != "success":
        facts["test_jobs"] = client.get_all(
            "actions/runs/%d/attempts/%d/jobs"
            % (test["id"], test.get("run_attempt", 1)), "jobs")
        artifacts = client.get(
            "actions/runs/%d/artifacts?name=%s"
            % (test["id"], STAGED_REQUEST)).get("artifacts", [])
        attempt_start = parse_time(test.get("run_started_at"))
        facts["staged"] = any(
            attempt_start is None or parse_time(a["created_at"]) >= attempt_start
            for a in artifacts)
    # Only a draft plan pull request can carry the ready-to-launch marker, and
    # only then are its changed files (conditions 2 and 3 of ready_to_launch)
    # worth a request.
    if draft_plan_pr(detail, client.repo):
        facts["files"] = client.get_all("pulls/%d/files" % pr["number"])
    return facts


def ensure_labels(client):
    """Create any ``ci: `` label the repository does not have yet."""
    existing = {label["name"] for label in client.get_all("labels")}
    for name, (colour, description, _) in LABELS.items():
        if name not in existing:
            client.send("POST", "labels", {
                "name": name, "color": colour, "description": description})


def apply(client, pr, add, remove):
    """Add and remove labels on one pull request."""
    if add:
        client.send("POST", "issues/%d/labels" % pr["number"], {"labels": add})
    for name in remove:
        client.send("DELETE", "issues/%d/labels/%s"
                    % (pr["number"], urllib.parse.quote(name)))


def summary_row(pr, label, since, reason, stalled, ready):
    """One markdown table row for the step summary."""
    markers = "".join(mark for mark, on in (
        (" (stalled)", stalled), (" (ready to launch)", ready)) if on)
    return "| #%d | %s | %s%s | %s | %s |" % (
        pr["number"], pr.get("title", "").replace("|", "\\|"), label,
        markers, since or "", reason)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--branch", help="only the pull request of this branch")
    parser.add_argument("--dry-run", action="store_true",
                        help="report the states without changing labels")
    args = parser.parse_args(argv)
    client = StatusClient(os.environ["REPO"], os.environ.get("GH_TOKEN", ""))
    now = datetime.datetime.now(datetime.timezone.utc)
    if not args.dry_run:
        ensure_labels(client)
    rows = []
    failures = 0
    for pr in open_pulls(client, args.branch):
        try:
            facts = gather(client, pr)
            label, since, reason = derive(facts, now)
            stalled = is_stalled(label, since, now)
            ready = ready_to_launch(facts, client.repo)
            add, remove = label_changes(
                [item["name"] for item in pr.get("labels", [])], label, stalled,
                ready)
            if not args.dry_run:
                apply(client, pr, add, remove)
            rows.append(summary_row(pr, label, since, reason, stalled, ready))
            print("#%d %s%s%s: %s (add %s, remove %s)" % (
                pr["number"], label, " stalled" if stalled else "",
                " ready-to-launch" if ready else "", reason, add, remove))
        except (urllib.error.URLError, KeyError, ValueError) as error:
            failures += 1
            print("::warning::#%d: could not update its status label: %s"
                  % (pr["number"], error))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and rows:
        with open(summary, "a") as handle:
            handle.write("| PR | Title | State | Since | Why |\n"
                         "|----|-------|-------|-------|-----|\n")
            handle.write("\n".join(rows) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
