#!/usr/bin/env python3
"""Classify auto-resolve test failures so a branch is only charged for what it
actually broke.

The auto-resolve path tells every branch "YOU BROKE THESE TESTS" for every
failing test in the run, even ones that fail on master too and ones that are
hardware/native hangs an agent cannot fix. Before dispatching, this script
sorts each failure into one of three buckets:

  regression    fails on this branch but not on its merge base. These are the
                branch's to fix, and the only ones sent to the branch's agent.
  pre-existing  fails on the base too (present in the master baseline). A
                master defect — route to the defect round / a tracker task,
                never charged to whatever branch surfaced it.
  environmental a hardware or native timeout, or a crash in native code
                (CUDA allocation hang, SIGSEGV, JVM fatal error, ...). Not
                agent-fixable — reported, not assigned. Same spirit as
                flowtree ``TransientGitException``'s infrastructure labelling.

Input is the failure list produced by ``parse-surefire-failures.sh`` (one block
per failure: a ``- Class#method`` line, then ``Exception:`` and stack-trace
lines). The master baseline, when available, is a file in that same format
listing the tests that fail on the most recent master CI result; see
``fetch-master-test-baseline.sh``. When no baseline exists, a non-environmental
failure is treated as a regression — the conservative default that preserves
today's "assign it to the branch" behaviour rather than silently dropping it.

Classification precedence is environmental, then pre-existing, then regression:
a native crash is not agent-fixable regardless of whether it also fails on base.

A regression is further *confirmed* when it cannot be a one-off: it failed on
two or more backends (the native CPU lanes, Metal, OpenCL, CUDA, the flowtree
JVM lane) in the same attempt, and its failure is not a timeout. A flaky test
fails on one host under one load; the same assertion failing on two different
accelerators is the code. A confirmed regression is sent to an agent at once
instead of being retried, which is what lets a red run skip the flaky-test
retries when retrying cannot change the outcome. A timeout is never confirmed,
because every lane slowing down together is load, not the code. The backend of
a failure comes from the name of the Surefire artifact its report was in, so
confirmation needs the reports directory, laid out as one sub-directory per
downloaded artifact; without it nothing is confirmed and every regression waits
for the retries as before.

Usage:
    classify-failures.py <failures-file> <out-dir> [baseline-file] [reports-dir]

Writes ``<out-dir>/regressions.txt``, ``<out-dir>/preexisting.txt``,
``<out-dir>/environmental.txt`` and ``<out-dir>/confirmed.txt`` (each in the
input's block format; ``confirmed.txt`` is a subset of ``regressions.txt``), and
prints the four counts to stdout, one per line:

    regression_count=<N>
    preexisting_count=<M>
    environmental_count=<K>
    confirmed_count=<C>

The CI step reads only these numeric ``*_count=<N>`` lines (it parses them, it
does not ``eval`` this output, which is branch-controlled).
"""
import os
import sys
import xml.etree.ElementTree as ElementTree


# Markers of a hardware/native failure, matched case-insensitively against a
# failure's exception and stack trace (never its test-name line, so a test
# merely named "...CudaBridgeTest" with an ordinary assertion failure is not
# mistaken for a hardware hang). A native CUDA allocation hang — the report's
# example, a 600s timeout inside CU.memAllocManaged — is caught by its frame
# appearing in the trace, so a plain deterministic timeout with no native frame
# stays a regression candidate.
ENVIRONMENTAL_MARKERS = (
    "memallocmanaged",
    "cumemalloc", "cumemfree", "cumemcpy",
    "cudaerror", "cuda_error", "curesult", "jcuda",
    "cl_out_of_resources", "cl_mem_object_allocation_failure",
    "hardwareexception",
    "sigsegv", "sigbus", "sigabrt", "sigill", "sigfpe",
    "fatal error has been detected",
    "exception_access_violation",
    "hs_err_pid",
)


# Markers of a timeout, matched case-insensitively against a failure's exception
# and stack trace. A timed-out test is never confirmed: lanes slowing down
# together is runner load, which a retry can clear.
TIMEOUT_MARKERS = (
    "testtimedoutexception",
    "timeoutexception",
    "timed out",
)

# The backend each Surefire artifact's tests ran on, by artifact name (the
# sub-directory the reports were downloaded into). Checked in order: the first
# matching fragment wins, so the accelerator lanes are tried before the native
# CPU lanes' plain names. An artifact matching none has no known backend and
# contributes nothing to confirmation.
BACKEND_ARTIFACTS = (
    ("-mac-group-", "metal"),
    ("-cl-group-", "opencl"),
    ("-cuda-group-", "cuda"),
    ("surefire-flowtree", "jvm"),
    ("surefire-utils", "native"),
    ("surefire-ml", "native"),
    ("surefire-media", "native"),
)


def artifact_backend(artifact_name):
    """The backend whose tests a Surefire artifact holds, or ``None``."""
    for fragment, backend in BACKEND_ARTIFACTS:
        if fragment in artifact_name:
            return backend
    return None


def failing_backends(reports_dir):
    """Map each failing ``Class#method`` to the set of backends it failed on.

    ``reports_dir`` holds one sub-directory per downloaded Surefire artifact;
    every ``TEST-*.xml`` under one is read, and a ``testcase`` with a
    ``failure`` or ``error`` child counts as failing on that artifact's
    backend. The name is the testcase's ``classname`` (falling back to the
    suite's ``name``) and its ``name``, joined as ``parse-surefire-failures.sh``
    joins them. A report that cannot be parsed is skipped: confirmation only
    ever removes retries, so a report it cannot read must not add one.
    """
    backends = {}
    if not reports_dir or not os.path.isdir(reports_dir):
        return backends
    for artifact in sorted(os.listdir(reports_dir)):
        backend = artifact_backend(artifact)
        if backend is None:
            continue
        for root, _dirs, files in os.walk(os.path.join(reports_dir, artifact)):
            for file_name in files:
                if not (file_name.startswith("TEST-") and file_name.endswith(".xml")):
                    continue
                try:
                    suite = ElementTree.parse(os.path.join(root, file_name)).getroot()
                except (ElementTree.ParseError, OSError):
                    continue
                suite_class = suite.get("name", "")
                for case in suite.iter("testcase"):
                    if case.find("failure") is None and case.find("error") is None:
                        continue
                    name = f"{case.get('classname') or suite_class}#{case.get('name', '')}"
                    backends.setdefault(name, set()).add(backend)
    return backends


class Failure:
    """One parsed failure block: its test name and its verbatim text."""

    def __init__(self, name, lines):
        self.name = name
        self.lines = lines

    @property
    def block(self):
        return "\n".join(self.lines)

    def detail_text(self):
        """The exception and stack-trace lines, lowercased, without the name
        line — the text environmental markers are matched against."""
        return "\n".join(self.lines[1:]).lower()


def parse_failures(text):
    """Parse the failure-list format into a list of :class:`Failure`.

    A block starts at a line beginning with ``- `` and runs until the next such
    line (or end of input); the intervening exception, stack-trace and blank
    lines belong to it.
    """
    failures = []
    name = None
    lines = []
    for line in text.splitlines():
        if line.startswith("- "):
            if name is not None:
                failures.append(Failure(name, lines))
            name = line[2:].strip()
            lines = [line]
        elif name is not None:
            lines.append(line)
    if name is not None:
        failures.append(Failure(name, lines))
    return failures


def parse_baseline_names(text):
    """The set of ``Class#method`` names a baseline file lists as failing."""
    names = set()
    for line in text.splitlines():
        if line.startswith("- "):
            names.add(line[2:].strip())
    return names


def is_environmental(failure):
    """True when a failure's exception/trace names a hardware or native fault."""
    detail = failure.detail_text()
    return any(marker in detail for marker in ENVIRONMENTAL_MARKERS)


def is_timeout(failure):
    """True when a failure's exception/trace says the test timed out."""
    detail = failure.detail_text()
    return any(marker in detail for marker in TIMEOUT_MARKERS)


def confirmed(regressions, backends):
    """The regressions that failed on two or more backends and did not time out.

    The failure list holds one block per failing report, so a test that failed
    on several lanes appears several times; it is confirmed once, by its first
    block. ``backends`` is :func:`failing_backends`' map.
    """
    result = []
    seen = set()
    for failure in regressions:
        if failure.name in seen:
            continue
        seen.add(failure.name)
        if is_timeout(failure):
            continue
        if len(backends.get(failure.name, ())) >= 2:
            result.append(failure)
    return result


def classify(failures, baseline_names):
    """Split failures into (regressions, preexisting, environmental) lists,
    applying the environmental → pre-existing → regression precedence."""
    regressions, preexisting, environmental = [], [], []
    for failure in failures:
        if is_environmental(failure):
            environmental.append(failure)
        elif failure.name in baseline_names:
            preexisting.append(failure)
        else:
            regressions.append(failure)
    return regressions, preexisting, environmental


def _write(path, failures):
    with open(path, "w", encoding="utf-8") as handle:
        for failure in failures:
            handle.write(failure.block)
            handle.write("\n")
            if not failure.block.endswith("\n"):
                handle.write("\n")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) < 2:
        sys.stderr.write(
            "Usage: classify-failures.py <failures-file> <out-dir> "
            "[baseline-file] [reports-dir]\n")
        return 1

    failures_file, out_dir = argv[0], argv[1]
    baseline_file = argv[2] if len(argv) > 2 else ""
    reports_dir = argv[3] if len(argv) > 3 else ""

    with open(failures_file, encoding="utf-8") as handle:
        failures = parse_failures(handle.read())

    baseline_names = set()
    if baseline_file and os.path.isfile(baseline_file):
        with open(baseline_file, encoding="utf-8") as handle:
            baseline_names = parse_baseline_names(handle.read())

    regressions, preexisting, environmental = classify(failures, baseline_names)
    confirmed_regressions = confirmed(regressions, failing_backends(reports_dir))

    os.makedirs(out_dir, exist_ok=True)
    _write(os.path.join(out_dir, "regressions.txt"), regressions)
    _write(os.path.join(out_dir, "preexisting.txt"), preexisting)
    _write(os.path.join(out_dir, "environmental.txt"), environmental)
    _write(os.path.join(out_dir, "confirmed.txt"), confirmed_regressions)

    print(f"regression_count={len(regressions)}")
    print(f"preexisting_count={len(preexisting)}")
    print(f"environmental_count={len(environmental)}")
    print(f"confirmed_count={len(confirmed_regressions)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
