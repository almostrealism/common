#!/usr/bin/env python3
"""Classify a failed test job by *where in the job* it failed, so a setup-step
failure is never sent to the JVM-crash agent.

The auto-resolve "Check for incomplete test execution" step used to decide the
crash route from a job's overall result alone: any ``failure`` that produced no
parseable Surefire XML was treated as a crash inside the Maven/test step, built
the VM-crash prompt, and submitted it with ``ENFORCE_CHANGES``. But a job that
fails *before* its tests ever run — ``actions/setup-java`` timing out on the JDK
host, a checkout, a cache restore, or a build-artifact download failing — has no
crash for an agent to fix. The agent correctly finds nothing to change, and the
job ends DEGRADED.

This script looks at each failing job's *steps* (from the GitHub jobs API) and
anchors on the job's Maven/test step:

  environmental  every failed step comes before the job's first Maven/test step
                 — the failure is in checkout, ``setup-java``, cache restore,
                 artifact download or another setup step. Not agent-fixable;
                 reported, not assigned. Same spirit as ``classify-failures.py``'s
                 environmental bucket.
  crash          a step at or after the first Maven/test step failed — a real
                 failure inside test execution. This is the crash route's job.

The Maven/test step is found by *name* ("Run ... Tests", "Flowtree Tests"),
relative to which the setup steps are simply the steps before it — rather than
a hard-coded list of setup action names that goes stale as the setup preamble
changes. When a job's first Maven/test step cannot
be identified (an unrecognised name), or a job failed with no failed step to
point at, the job is treated as a crash: the conservative default that preserves
the pre-classification behaviour rather than silently dropping a real failure.

Input is the JSON the GitHub jobs API returns for a run attempt,
``/repos/{repo}/actions/runs/{run_id}/attempts/{attempt}/jobs``. ``gh api
--paginate`` concatenates one ``{"total_count": N, "jobs": [...]}`` object per
page, so the loader accepts a JSON stream of such objects (as well as a single
object or a bare jobs array).

Usage:
    classify-job-failures.py <jobs-json-file> <out-dir> [<required-job-id> ...]

Each ``<required-job-id>`` names a workflow job (``test``, ``test-media-cl``)
whose ``needs.<job>.result`` was ``failure``. A required job none of whose runs
appears among the payload's failed jobs — matched by name, ``<id>`` or a matrix
run ``<id> (...)`` — is counted as a crash: an empty or partial jobs response
must never turn a real failure into a zero crash count.

Writes ``<out-dir>/crash-jobs.txt`` and ``<out-dir>/environmental-jobs.txt``
(each a human-readable block per job, naming the step that failed), and prints
two counts to stdout, one per line:

    crash_count=<N>
    environmental_count=<K>

The CI step reads only these numeric ``*_count=<N>`` lines (it parses them, it
does not ``eval`` this output).
"""
import json
import os
import re
import sys


# A step whose GitHub conclusion is one of these failed. "timed_out" is a
# first-class failure here: a setup step that times out (the incident's
# setup-java ETIMEDOUT) must classify exactly as a setup step that errored.
FAILED_CONCLUSIONS = ("failure", "timed_out")

# The name of a Maven/test step. Every test lane's invocation step is named
# "Run <area> Tests" or "Flowtree Tests", so the word "test(s)" identifies it.
# Matched case-insensitively on word boundaries. "Maven"/"mvn" are deliberately
# NOT work markers: "Isolate Maven repository per runner" is a setup step that
# precedes "Download build artifacts" and "Restore build artifacts" in every
# macOS, CL and CUDA lane, and anchoring on it would route a failed artifact
# download in those lanes to the crash prompt. Likewise "build" is not a marker,
# so "Download build artifacts" is never taken for the work step.
_WORK_STEP_PATTERN = re.compile(r"\btests?\b", re.IGNORECASE)


def load_jobs(text):
    """Return the list of job objects in the jobs-API payload ``text``.

    Accepts a JSON *stream* — zero or more concatenated JSON values, as
    ``gh api --paginate`` emits one response object per page — as well as a
    single ``{"jobs": [...]}`` object or a bare list of jobs. Every value's
    ``jobs`` array is collected; a bare list is taken as jobs directly.
    """
    jobs = []
    decoder = json.JSONDecoder()
    index = 0
    length = len(text)
    while index < length:
        while index < length and text[index].isspace():
            index += 1
        if index >= length:
            break
        value, end = decoder.raw_decode(text, index)
        index = end
        if isinstance(value, dict) and "jobs" in value:
            jobs.extend(value["jobs"])
        elif isinstance(value, list):
            jobs.extend(value)
        elif isinstance(value, dict):
            jobs.append(value)
    return jobs


def ordered_steps(job):
    """The job's steps in execution order. The jobs API returns them ordered and
    numbered; sorting on ``number`` (stably, missing numbers last) makes the
    before/after reasoning independent of the payload's array order."""
    steps = job.get("steps") or []
    return sorted(
        steps,
        key=lambda step: step.get("number")
        if isinstance(step.get("number"), int) else len(steps))


def first_work_step_index(steps):
    """The index of the job's first Maven/test step, or ``None`` when no step
    name identifies one."""
    for index, step in enumerate(steps):
        if _WORK_STEP_PATTERN.search(step.get("name") or ""):
            return index
    return None


def failed_step_indices(steps):
    """The indices of the steps that failed or timed out."""
    return [index for index, step in enumerate(steps)
            if (step.get("conclusion") or "").lower() in FAILED_CONCLUSIONS]


def is_environmental(job):
    """True when every failed step of ``job`` comes before its first Maven/test
    step — a setup failure (checkout, setup-java, cache, artifact download).

    False — the conservative, crash-routed default — when a failed step is at or
    after the work step (a real in-test crash), when the work step cannot be
    identified, or when the job failed with no failed step to anchor on.
    """
    steps = ordered_steps(job)
    failed = failed_step_indices(steps)
    if not failed:
        return False
    work = first_work_step_index(steps)
    if work is None:
        return False
    return all(index < work for index in failed)


def failed_step_names(job):
    """The names of the job's failed steps, for the report."""
    steps = ordered_steps(job)
    return [steps[index].get("name") or "(unnamed step)"
            for index in failed_step_indices(steps)]


def classify(jobs):
    """Split the failed/timed-out jobs into (crashes, environmental) lists,
    preserving input order. Jobs that neither failed nor timed out are ignored.
    """
    crashes, environmental = [], []
    for job in jobs:
        if (job.get("conclusion") or "").lower() not in FAILED_CONCLUSIONS:
            continue
        if is_environmental(job):
            environmental.append(job)
        else:
            crashes.append(job)
    return crashes, environmental


def _block(job):
    """A human-readable block naming a job and the step(s) it failed at."""
    name = job.get("name") or "(unnamed job)"
    steps = failed_step_names(job)
    lines = ["- " + name]
    if steps:
        lines.append("  failed at: " + ", ".join(steps))
    return "\n".join(lines) + "\n"


def _write(path, jobs):
    with open(path, "w", encoding="utf-8") as handle:
        for job in jobs:
            handle.write(_block(job))


def is_run_of(job, job_id):
    """True when ``job`` (a jobs-API entry) is a run of the workflow job
    ``job_id``: its name is the id itself, or a matrix run ``<id> (...)``."""
    name = job.get("name") or ""
    return name == job_id or name.startswith(job_id + " (")


def unaccounted_job_ids(jobs, required_ids):
    """The ids in ``required_ids`` (failing workflow jobs) of which no run is
    among the failed/timed-out ``jobs``, in input order without duplicates."""
    failed = [job for job in jobs
              if (job.get("conclusion") or "").lower() in FAILED_CONCLUSIONS]
    missing = []
    for job_id in required_ids:
        if job_id in missing:
            continue
        if not any(is_run_of(job, job_id) for job in failed):
            missing.append(job_id)
    return missing


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) < 2:
        sys.stderr.write(
            "Usage: classify-job-failures.py <jobs-json-file> <out-dir>"
            " [<required-job-id> ...]\n")
        return 1

    jobs_file, out_dir, required_ids = argv[0], argv[1], argv[2:]

    with open(jobs_file, encoding="utf-8") as handle:
        jobs = load_jobs(handle.read())

    crashes, environmental = classify(jobs)
    missing = unaccounted_job_ids(jobs, required_ids)

    os.makedirs(out_dir, exist_ok=True)
    _write(os.path.join(out_dir, "crash-jobs.txt"), crashes)
    if missing:
        with open(os.path.join(out_dir, "crash-jobs.txt"), "a",
                  encoding="utf-8") as handle:
            for job_id in missing:
                handle.write("- " + job_id + "\n  not found among the run's"
                             " failed jobs; treated as a crash\n")
    _write(os.path.join(out_dir, "environmental-jobs.txt"), environmental)

    print(f"crash_count={len(crashes) + len(missing)}")
    print(f"environmental_count={len(environmental)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
