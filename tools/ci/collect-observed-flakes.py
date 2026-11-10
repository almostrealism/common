#!/usr/bin/env python3
"""Collect the flakes CI has already observed, so the flaky round starts from
evidence instead of guessing.

CI already knows which tests are flaky. ``rerun-flaky-tests.sh`` re-runs a failed
"Build and Test" run's failed test jobs (GitHub re-runs the failed jobs and their
dependents, producing a new *attempt* of the same run), so a test that failed in
one attempt and passed in a later attempt of the SAME run — the same commit — is
a confirmed flake, on a known lane, with its failure output recorded in that
attempt's job log. A test that fails in every attempt is a real failure, not a
flake, and is left out.

This script walks recent "Build and Test" runs (on master and on PR branches),
and for every run that was retried (``run_attempt`` >= 2) it reads each attempt's
test-lane jobs from the GitHub API. For a failing attempt it parses the test-lane
job log for the Surefire ``<<< FAILURE!`` / ``<<< ERROR!`` lines that name each
failing ``Class#method``; a passing attempt contributes an empty failed set. A
test that appears in one attempt's failed set and is absent from a later
attempt's failed set for the same lane flaked on that lane for that commit.

For each flaky ``(test, lane)`` it records how many commits it flaked on, when it
last did, the commits involved, and — so the agent can open the evidence with its
GitHub tools — the attempt job ids and URLs of the failing attempts. The list is
ranked by flake frequency, then recency, and rendered as a Markdown leads section
the flaky round's prompt puts ahead of its memory recall and tracker search.

Window and retention. The window is configurable (``--window-days`` /
``FLAKE_WINDOW_DAYS``, default 14; ``--max-runs`` / ``FLAKE_MAX_RUNS``, default
100). It cannot usefully reach past GitHub's log/artifact retention: once an
attempt's job logs are expired the per-attempt failed set can no longer be
recovered, so the default window stays well inside the default 90-day retention.

Usage:
    collect-observed-flakes.py [--window-days N] [--max-runs N] [--repo owner/name]

Reads ``GITHUB_TOKEN`` (and ``GITHUB_REPOSITORY`` when ``--repo`` is omitted) from
the environment. Prints the rendered leads section to stdout; the workflow
captures it and substitutes it into the round's prompt.

The network layer (``GitHubClient``) is a thin wrapper separated from the pure
detection and rendering functions, which take already-fetched payloads so they
can be unit-tested against recorded GitHub API data (see
``test_collect_observed_flakes.py``).
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request


# The "Build and Test" workflow whose retried runs record the flakes. Matched
# against each run's ``name`` so a differently-named workflow in the same
# repository is never mistaken for it. ``WORKFLOW_FILE`` is the workflow
# definition's file name, used to scope the runs listing to this one workflow so
# the run cap bounds Build-and-Test runs directly rather than being spent on the
# repository's other workflows.
WORKFLOW_NAME = "Build and Test"
WORKFLOW_FILE = "analysis.yaml"

# A bound on every GitHub API read, in seconds. Without it a stalled connection
# leaves ``urlopen`` blocked indefinitely, so the leads step hangs instead of
# reaching its best-effort fallback; a timed-out request raises a URLError the
# collector lets propagate, which the workflow reads as a failed leads-script and
# falls back to its memory and tracker leads.
REQUEST_TIMEOUT_SECONDS = 30

# The test-execution jobs whose failures are real test outcomes (a lane). Only
# these are read for failing tests; a failed setup/policy/build job is not a test
# result. A sharded lane's job name carries a matrix suffix — "test-media-cl (2)"
# — so a job name is reduced to its lane by stripping a trailing " (...)" before
# it is matched against this set.
TEST_LANES = frozenset((
    "test",
    "test-flowtree",
    "test-media",
    "test-mac",
    "test-media-mac",
    "test-cl",
    "test-media-cl",
    "test-cuda",
    "test-media-cuda",
))

# A friendly label for each lane, so the prompt can say "Metal" rather than make
# the agent decode the job name. A lane with no entry is shown by its job name.
LANE_LABELS = {
    "test": "CPU (native, linux)",
    "test-flowtree": "flowtree",
    "test-media": "CPU media (native, linux)",
    "test-mac": "Metal (GPU, macOS)",
    "test-media-mac": "Metal media (GPU, macOS)",
    "test-cl": "OpenCL (ROCm, linux)",
    "test-media-cl": "OpenCL media (ROCm, linux)",
    "test-cuda": "CUDA (NVIDIA, linux)",
    "test-media-cuda": "CUDA media (NVIDIA, linux)",
}

_SHARD_SUFFIX = re.compile(r"\s*\([^)]*\)\s*$")

# Surefire reports a failing test as a line naming the fully-qualified class and
# method followed by "<<< FAILURE!" or "<<< ERROR!", e.g.
#   [ERROR] org.example.FooTest.testBar  Time elapsed: 0.1 s  <<< FAILURE!
# The class is the dotted path up to the last segment; the method is the last
# segment. A parameterised method carries a "[0]"-style suffix, kept out of the
# name so repeats of one method collapse to one entry.
_SUREFIRE_FAILURE = re.compile(
    r"([\w.$]+)\.([\w$]+)(?:\[[^\]]*\])?\s+Time elapsed:.*<<<\s+(?:FAILURE|ERROR)!")


def lane_of(job_name):
    """The lane a job belongs to: its name with any matrix-shard suffix removed."""
    return _SHARD_SUFFIX.sub("", job_name).strip()


def is_test_lane(job_name):
    """True when a job is one of the pipeline's test-execution lanes."""
    return lane_of(job_name) in TEST_LANES


def lane_label(lane):
    """A human label for a lane; its job name when none is defined."""
    return LANE_LABELS.get(lane, lane)


def surefire_failures(log_text):
    """The set of ``Class#method`` names a test-lane job log reports as failing.

    Reads the Surefire ``<<< FAILURE!`` / ``<<< ERROR!`` lines, which name the
    failing class and method directly, so a timestamped CI log line prefix does
    not interfere. Returns an empty set for a log with none; a job that failed
    before any test ran leaves no such line, and ``collect`` then treats that
    failure as non-evidence rather than as a clean pass."""
    names = set()
    for cls, method in _SUREFIRE_FAILURE.findall(log_text or ""):
        names.add("%s#%s" % (cls, method))
    return names


def detect_flakes(attempt_results):
    """Reduce per-attempt test-lane results to the confirmed flakes.

    ``attempt_results`` is a list of dicts, one per (run, lane, attempt) that
    actually ran, each with:
        run_id, commit, branch, lane, attempt (int), created_at,
        failed (set of Class#method), job_id, html_url, and optionally shard
        (the full job name, carrying the matrix-shard suffix when the lane is
        sharded; defaults to lane for an unsharded lane)

    Attempts are compared within the same (run, shard), not merely (run, lane),
    because each shard of a sharded lane runs a distinct subset of tests: a test
    absent from a SIBLING shard's failed set never ran there and is no evidence
    it passed. A test is flaky when it is in the failed set of one attempt and
    absent from the failed set of a LATER attempt of the same (run, shard) — it
    failed, then passed on re-run of the same commit. A test in every attempt's
    failed set never passed and is a real failure, not a flake. Confirmed flakes
    are then aggregated by (test, lane), collapsing shards.

    Returns a list of flake entries aggregated by (test, lane):
        {test, lane, lane_label, count, last_occurrence, commits (sorted list),
         evidence (list of {run_id, attempt, job_id, url})}
    ranked by flake count, then most-recent occurrence, both descending.
    """
    groups = {}
    for result in attempt_results:
        shard = result.get("shard", result["lane"])
        groups.setdefault((result["run_id"], shard), []).append(result)

    aggregate = {}
    for (run_id, shard), records in groups.items():
        lane = records[0]["lane"]
        records.sort(key=lambda record: record["attempt"])
        every_failed = set()
        for record in records:
            every_failed |= record["failed"]
        for test in every_failed:
            failing = [r for r in records if test in r["failed"]]
            first_fail = min(r["attempt"] for r in failing)
            passed_later = any(
                r["attempt"] > first_fail and test not in r["failed"]
                for r in records)
            if not passed_later:
                continue  # never passed on re-run — a real failure, not a flake

            key = (test, lane)
            entry = aggregate.setdefault(key, {
                "test": test,
                "lane": lane,
                "lane_label": lane_label(lane),
                "count": 0,
                "last_occurrence": None,
                "commits": set(),
                "evidence": [],
            })
            entry["count"] += 1
            when = records[0].get("created_at")
            if when and (entry["last_occurrence"] is None
                         or when > entry["last_occurrence"]):
                entry["last_occurrence"] = when
            commit = records[0].get("commit")
            if commit:
                entry["commits"].add(commit)
            for record in failing:
                if record.get("job_id") is not None:
                    entry["evidence"].append({
                        "run_id": run_id,
                        "attempt": record["attempt"],
                        "job_id": record["job_id"],
                        "url": record.get("html_url"),
                    })

    flakes = []
    for entry in aggregate.values():
        entry["commits"] = sorted(entry["commits"])
        flakes.append(entry)
    flakes.sort(
        key=lambda e: (e["count"], e["last_occurrence"] or ""),
        reverse=True)
    return flakes


def render(flakes):
    """Render the ranked flakes as the Markdown leads section the prompt reads.

    An empty list renders the honest "nothing observed" note, so the prompt's
    ${...} placeholder is always filled and the round falls back to memory and
    the tracker rather than seeing a hole."""
    if not flakes:
        return (
            "No confirmed flakes were collected from recent Build and Test runs "
            "in the window. Fall back to the memory recall and tracker search "
            "below to choose a lead.")

    lines = [
        "These tests were observed flaking in CI: each failed in one attempt of "
        "a Build and Test run and passed in a later attempt of the same commit, "
        "so the flake is confirmed on the named lane. They are ranked by how "
        "often they flaked and how recently. Start here.",
        "",
    ]
    for index, flake in enumerate(flakes, start=1):
        times = "time" if flake["count"] == 1 else "times"
        head = "%d. `%s` — lane %s (%s) — flaked %d %s" % (
            index, flake["test"], flake["lane"], flake["lane_label"],
            flake["count"], times)
        if flake["last_occurrence"]:
            head += ", last %s" % flake["last_occurrence"]
        lines.append(head)
        if flake["commits"]:
            lines.append("   - commits: %s" % ", ".join(flake["commits"]))
        for item in flake["evidence"]:
            link = item["url"] or ("job %s" % item["job_id"])
            lines.append(
                "   - attempt %d failing job: %s (run %s, job id %s)" % (
                    item["attempt"], link, item["run_id"], item["job_id"]))
    return "\n".join(lines)


class GitHubClient:
    """The GitHub REST calls the collector makes, isolated from the pure logic.

    Only the methods the collector needs; each returns parsed JSON or log text.
    Separated so ``collect`` can be unit-tested with a recorded-payload fake in
    its place."""

    def __init__(self, repo, token, api="https://api.github.com"):
        self.repo = repo
        self.token = token
        self.api = api.rstrip("/")

    def _request(self, path, raw=False):
        url = path if path.startswith("http") else "%s%s" % (self.api, path)
        request = urllib.request.Request(url)
        request.add_header("Accept",
                           "application/vnd.github+json" if not raw else "*/*")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self.token:
            request.add_header("Authorization", "Bearer %s" % self.token)
        with urllib.request.urlopen(
                request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            data = response.read()
        return data if raw else json.loads(data.decode("utf-8"))

    def list_workflow_runs(self, max_runs, workflow_file=WORKFLOW_FILE):
        """The most recent Build and Test runs, newest first, across every branch.

        Scoped to the ``workflow_file`` workflow so ``max_runs`` bounds that
        workflow's runs directly; listing ``/actions/runs`` for the whole
        repository would let the repository's other workflows crowd the cap and
        discard recent Build-and-Test retries before they are seen."""
        runs = []
        page = 1
        while len(runs) < max_runs:
            per_page = min(100, max_runs - len(runs))
            payload = self._request(
                "/repos/%s/actions/workflows/%s/runs?per_page=%d&page=%d"
                % (self.repo, workflow_file, per_page, page))
            batch = payload.get("workflow_runs", [])
            if not batch:
                break
            runs.extend(batch)
            if len(batch) < per_page:
                break
            page += 1
        return runs[:max_runs]

    def list_attempt_jobs(self, run_id, attempt):
        """The jobs of one attempt of a run."""
        jobs = []
        page = 1
        while True:
            payload = self._request(
                "/repos/%s/actions/runs/%s/attempts/%d/jobs?per_page=100&page=%d"
                % (self.repo, run_id, attempt, page))
            batch = payload.get("jobs", [])
            jobs.extend(batch)
            if len(batch) < 100:
                break
            page += 1
        return jobs

    def job_log(self, job_id):
        """One job's plain-text log (the attempt-scoped job id, so the right
        attempt's output)."""
        try:
            return self._request(
                "/repos/%s/actions/jobs/%s/logs" % (self.repo, job_id),
                raw=True).decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            if error.code in (404, 410):  # log expired or not retained
                return ""
            raise


def _within_window(run, window_days, now):
    """True when a run started within the window. A run with no parseable start
    is kept, so a payload shape the collector does not recognise is never
    silently dropped."""
    if window_days is None or now is None:
        return True
    started = run.get("run_started_at") or run.get("created_at")
    if not started:
        return True
    try:
        import datetime
        when = datetime.datetime.fromisoformat(started.replace("Z", "+00:00"))
    except ValueError:
        return True
    return (now - when).total_seconds() <= window_days * 86400


def collect(client, workflow_name=WORKFLOW_NAME, window_days=14, max_runs=100,
            now=None):
    """Walk the recent retried runs and return the detected flakes.

    ``client`` provides ``list_workflow_runs``, ``list_attempt_jobs`` and
    ``job_log``; a fake with those three methods drives the unit tests. ``now``
    is the moment the window is measured back from (a ``datetime``); omitted, no
    time window is applied and ``max_runs`` alone bounds the work."""
    attempt_results = []
    for run in client.list_workflow_runs(max_runs):
        if run.get("name") != workflow_name:
            continue
        if not _within_window(run, window_days, now):
            continue
        attempts = int(run.get("run_attempt", 1) or 1)
        if attempts < 2:
            continue  # never retried, so no attempt can disagree with another
        run_id = run.get("id")
        commit = (run.get("head_sha") or "")[:12]
        branch = run.get("head_branch")
        created_at = run.get("run_started_at") or run.get("created_at")
        for attempt in range(1, attempts + 1):
            for job in client.list_attempt_jobs(run_id, attempt):
                name = job.get("name", "")
                if not is_test_lane(name):
                    continue
                conclusion = job.get("conclusion")
                if conclusion == "failure":
                    failed = surefire_failures(client.job_log(job.get("id")))
                    if not failed:
                        # A failure with no Surefire line failed before any test
                        # produced a result — a setup step, a build/compile
                        # error, a JVM crash, or an expired log. It is not
                        # attributable to a test outcome, so it is no evidence
                        # that any test passed or failed. Skip it, the same as a
                        # skipped or cancelled lane; recording it as an empty
                        # (all-passed) set would make a real failure in an
                        # earlier attempt look like it "passed on re-run" and
                        # report a false flake.
                        continue
                elif conclusion == "success":
                    failed = set()
                else:
                    # skipped, cancelled or still running: the lane did not
                    # produce a clean pass/fail, so it is not evidence either way.
                    continue
                attempt_results.append({
                    "run_id": run_id,
                    "commit": commit,
                    "branch": branch,
                    "lane": lane_of(name),
                    "shard": name,
                    "attempt": attempt,
                    "created_at": created_at,
                    "failed": failed,
                    "job_id": job.get("id"),
                    "html_url": job.get("html_url"),
                })
    return detect_flakes(attempt_results)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--window-days", type=int,
                        default=int(os.environ.get("FLAKE_WINDOW_DAYS", "14")))
    parser.add_argument("--max-runs", type=int,
                        default=int(os.environ.get("FLAKE_MAX_RUNS", "100")))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    if not args.repo:
        sys.stderr.write("ERROR: --repo or GITHUB_REPOSITORY is required.\n")
        return 1

    import datetime
    now = datetime.datetime.now(datetime.timezone.utc)
    client = GitHubClient(args.repo, os.environ.get("GITHUB_TOKEN", ""))
    flakes = collect(client, window_days=args.window_days,
                     max_runs=args.max_runs, now=now)
    sys.stdout.write(render(flakes))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
