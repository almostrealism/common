#!/usr/bin/env python3
"""Find the tests failing on master, for the round that fixes them.

A master run is never retried and no branch's agent is charged with it, so
before this round a failure on master was seen only when someone happened to
look. "Master Failure Dispatch" fires when a master "Build and Test" run fails,
asks this script whether a fix round should start, and, when one starts, uses
the same script as the round's leads-script so the agent begins from the
failures themselves.

The failures are read from the most recent *completed* master push run that
was not cancelled (a later merge cancels a run before its tests finish): newer
evidence supersedes older, and a master that has since gone green has nothing
left to fix. For that run, every failed test-lane job's log is read for the
Surefire ``<<< FAILURE!`` / ``<<< ERROR!`` lines, giving each failing
``Class#method`` and the lanes it failed on. The lane, log parsing and GitHub
client come from ``collect-observed-flakes.py``, which reads the same runs for
the flaky round.

A round starts only when all of these hold (``--gate``):

  - the most recent completed master run failed, and at least one test in a
    test lane failed in it. A failure with no failing test (a setup step, a
    runner lost mid-job) is the fleet's, not something a coding agent can fix;
  - that run's commit contains the merge of the most recent master-fix pull
    request, if any. Master runs take hours, so after a fix merges the newest
    completed run is often still one that started before it; dispatching from
    it would send a second agent after a failure that is already fixed.

How many rounds may be open at once is not decided here: the round's
cadence gate (``qa-cadence.sh``, one round per branch prefix) holds every
further round off while one is in progress. Together these are the cap: at
most one master-fix branch at a time, started only for a failure that is
current.

Usage:
    collect-master-failures.py [--gate] [--repo owner/name] [--max-runs N]

Reads ``GITHUB_TOKEN`` (and ``GITHUB_REPOSITORY`` when ``--repo`` is omitted)
from the environment. Without ``--gate`` it prints a Markdown leads section.
With ``--gate`` it prints ``dispatch=true|false``, ``run_id=<id>`` and
``failing_count=<N>``, one per line, for the workflow to append to
``$GITHUB_OUTPUT``, and a ``::notice::`` line saying why.
"""
import argparse
import importlib.util
import os
import sys


_CI_DIR = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "collect_observed_flakes", os.path.join(_CI_DIR, "collect-observed-flakes.py"))
flakes = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(flakes)

# The branch whose failures this round fixes, and the prefix of the round's
# branches (the branch-prefix "Master Failure Dispatch" passes to qa-round).
MASTER = "master"
ROUND_BRANCH_PREFIX = "qa/master-fix-"
# Conclusions of a completed run that did not test the merged commit.
NOT_A_RESULT = ("cancelled", "skipped")


def latest_completed_master_run(runs):
    """The newest completed push run on master in ``runs`` (newest first), or
    ``None``. A pull-request or deployment run on master's head is not a master
    result: only a push run tests what was merged. Nor is a cancelled or
    skipped run: one is cancelled when a later merge supersedes it, before its
    tests finish, so it says nothing about master. Counting it would let every
    merge that lands while a failing run is still going hide that failure."""
    for run in runs:
        if (run.get("head_branch") == MASTER and run.get("event") == "push"
                and run.get("status") == "completed"
                and run.get("conclusion") not in NOT_A_RESULT):
            return run
    return None


def failing_tests(jobs, job_log):
    """Map each failing ``Class#method`` to the sorted lanes it failed on.

    ``jobs`` are one attempt's jobs; ``job_log`` returns a job's log text. Only
    a failed test-lane job is read."""
    lanes = {}
    for job in jobs:
        if job.get("conclusion") != "failure" or not flakes.is_test_lane(job.get("name", "")):
            continue
        lane = flakes.lane_of(job["name"])
        for name in flakes.surefire_failures(job_log(job["id"])):
            lanes.setdefault(name, set()).add(lane)
    return {name: sorted(found) for name, found in lanes.items()}


def latest_fix_merge(pulls):
    """The merge commit of the most recently merged master-fix pull request in
    ``pulls``, or ``None``."""
    merged = [pull for pull in pulls
              if pull.get("merged_at")
              and str((pull.get("head") or {}).get("ref", "")).startswith(ROUND_BRANCH_PREFIX)]
    if not merged:
        return None
    return max(merged, key=lambda pull: pull["merged_at"]).get("merge_commit_sha")


def decide(run, failures, contains_fix):
    """Whether a fix round should start, and why: ``(dispatch, reason)``.

    ``contains_fix`` is whether the run's commit contains the latest fix merge
    (``True`` when there has been none)."""
    if run is None:
        return False, "no completed master push run was found"
    if run.get("conclusion") != "failure":
        return False, "the most recent completed master run (%s) concluded %s" % (
            run.get("id"), run.get("conclusion"))
    if not failures:
        return False, "master run %s failed without a failing test in a test lane" % run.get("id")
    if not contains_fix:
        return False, ("master run %s is for a commit that predates the latest master-fix "
                       "merge; its failures may already be fixed" % run.get("id"))
    return True, "master run %s has %d failing test(s)" % (run.get("id"), len(failures))


def render(run, failures):
    """The Markdown leads section for the round's prompt."""
    if run is None or not failures:
        return "_No failing test was found on the most recent completed master run._\n"
    lines = [
        "Master run [%s](%s) (commit `%s`, attempt %s) failed these tests:"
        % (run.get("id"), run.get("html_url", ""), str(run.get("head_sha", ""))[:12],
           run.get("run_attempt", 1)),
        "",
    ]
    for name in sorted(failures):
        lanes = ", ".join(flakes.lane_label(lane) for lane in failures[name])
        lines.append("- `%s` on %s" % (name, lanes))
    lines.append("")
    return "\n".join(lines)


def _contains(client, base_sha, head_sha):
    """Whether ``head_sha`` contains ``base_sha``. A failed comparison counts as
    containing it: the check only exists to avoid a duplicate round, so it must
    never be what stops a round for a real failure."""
    if not base_sha:
        return True
    try:
        comparison = client.compare(base_sha, head_sha)
    except (OSError, ValueError) as error:
        # URLError and HTTPError are OSErrors; a malformed body is a ValueError.
        sys.stderr.write("compare %s...%s failed: %s\n" % (base_sha, head_sha, error))
        return True
    return comparison.get("status") in ("ahead", "identical")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gate", action="store_true")
    parser.add_argument("--max-runs", type=int, default=30)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    if not args.repo:
        sys.stderr.write("ERROR: --repo or GITHUB_REPOSITORY is required.\n")
        return 1

    client = flakes.GitHubClient(args.repo, os.environ.get("GITHUB_TOKEN", ""))
    run = latest_completed_master_run(
        client.list_workflow_runs(args.max_runs, branch=MASTER))
    failures = {}
    if run is not None and run.get("conclusion") == "failure":
        failures = failing_tests(
            client.list_attempt_jobs(run["id"], int(run.get("run_attempt", 1))),
            client.job_log)

    if not args.gate:
        sys.stdout.write(render(run, failures))
        return 0

    contains_fix = True
    if failures:
        contains_fix = _contains(client, latest_fix_merge(client.list_closed_pulls(MASTER)),
                                 run.get("head_sha", ""))
    dispatch, reason = decide(run, failures, contains_fix)
    print("::notice::%s — %s" % ("starting a master-fix round" if dispatch else "no round", reason))
    print("dispatch=%s" % ("true" if dispatch else "false"))
    print("run_id=%s" % ((run or {}).get("id", "")))
    print("failing_count=%d" % len(failures))
    return 0


if __name__ == "__main__":
    sys.exit(main())
