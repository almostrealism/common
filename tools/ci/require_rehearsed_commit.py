#!/usr/bin/env python3
"""Refuse to deploy a commit whose deploy rehearsal did not pass.

The Deploy Controller Stack workflow runs this before it changes anything. It
looks up the "Build and Test" push runs of the commit being deployed and
succeeds only when the latest attempt of one of them has a ``Deploy rehearsal``
job that concluded ``success``. That job runs ``flowtree/runtime/rebuild.sh
--rehearse`` -- the deploy's own build and preflight -- so a commit reaches the
deploy only after the deploy has already been performed once, short of
replacing the running stack, on exactly that commit.

Usage:
    require_rehearsed_commit.py SHA

Environment:
    GITHUB_TOKEN       token with ``actions: read`` on the repository
    GITHUB_REPOSITORY  ``owner/name``
    GITHUB_API_URL     API root (default ``https://api.github.com``)

Exit codes:
    0  the commit's rehearsal passed
    1  no passing rehearsal: not run yet, still running, failed, or unknown
"""

import json
import os
import sys
import urllib.request

#: The ``name:`` of the rehearsal job in ``.github/workflows/analysis.yaml``.
REHEARSAL_JOB_NAME = "Deploy rehearsal"

#: The workflow file whose push runs carry the rehearsal.
WORKFLOW_FILE = "analysis.yaml"


def rehearsal_verdict(runs, jobs_of):
    """Decide whether a commit's rehearsal passed.

    Args:
        runs: the ``workflow_runs`` list of the commit's push runs, any order.
        jobs_of: a callable taking a run id and returning the ``jobs`` list of
            that run's latest attempt.

    Returns:
        ``(passed, reason)``: ``passed`` is True only when some run's latest
        attempt has a rehearsal job that concluded ``success``; ``reason``
        explains the decision either way.
    """
    if not runs:
        return False, "no Build and Test push run exists for this commit yet"

    seen = []
    for run in sorted(runs, key=lambda r: r.get("run_number", 0), reverse=True):
        for job in jobs_of(run["id"]):
            if job.get("name") != REHEARSAL_JOB_NAME:
                continue
            state = job.get("conclusion") or job.get("status") or "unknown"
            if state == "success":
                return True, "rehearsal passed in run %s" % run["id"]
            seen.append("run %s: %s" % (run["id"], state))

    if not seen:
        return False, "no %r job in any push run of this commit" % REHEARSAL_JOB_NAME
    return False, "the rehearsal has not passed (%s)" % "; ".join(seen)


def _get(url, token):
    """Returns the decoded JSON body of a GitHub API GET."""
    request = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def main(argv):
    """Checks the commit named on the command line and reports the verdict."""
    if len(argv) != 2:
        print("usage: require_rehearsed_commit.py SHA", file=sys.stderr)
        return 1
    sha = argv[1]
    token = os.environ["GITHUB_TOKEN"]
    repo = os.environ["GITHUB_REPOSITORY"]
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    base = "%s/repos/%s/actions" % (api, repo)

    runs = _get("%s/workflows/%s/runs?event=push&head_sha=%s&per_page=20"
                % (base, WORKFLOW_FILE, sha), token).get("workflow_runs", [])

    def jobs_of(run_id):
        return _get("%s/runs/%s/jobs?filter=latest&per_page=100" % (base, run_id),
                    token).get("jobs", [])

    passed, reason = rehearsal_verdict(runs, jobs_of)
    if passed:
        print("Commit %s: %s." % (sha, reason))
        return 0
    print("::error::Refusing to deploy %s: %s. A deploy ships only a commit whose "
          "master push run passed the deploy rehearsal; wait for that run, or fix "
          "what the rehearsal reports." % (sha, reason))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
