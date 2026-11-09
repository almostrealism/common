#!/usr/bin/env python3
"""Compare two instruction-cache benchmark reports.

The reports are the JSON files written by ``InstructionCacheBenchmark`` (engine/utils). Run the
benchmark with the same command, and so the same seed and population, on two branches, then:

    python3 scripts/compare-cache-benchmark.py before.json after.json

For every scenario and compute context present in both reports, the script prints each
deterministic metric that changed (hit rate, collisions, kernels per computation, arguments and
folded inputs per kernel, aggregate size, copies per dispatch, outcomes) and the ratio of the
timing metrics, followed by the instances whose kernels or outcome changed. A report pair whose
populations differ (different seed, population or generator) is refused, because its differences
would not be differences in behaviour.

Exit status: 0 when the reports were compared, 2 when they cannot be.
"""

import argparse
import json
import sys

# (label, path within a result) of every deterministic metric that is compared
DETERMINISTIC = [
    ("verified", ("outcomes", "VERIFIED")),
    ("mismatches", ("outcomes", "MISMATCH")),
    ("collisions", ("outcomes", "COLLISION")),
    ("failures", ("outcomes", "FAILURE")),
    ("kernel requests", ("cache", "requests")),
    ("hits", ("cache", "hits")),
    ("hit rate", ("cache", "hitRate")),
    ("compiled shared", ("cache", "compiledShared")),
    ("compiled unshared", ("cache", "compiledUnshared")),
    ("recompiled signatures", ("cache", "recompiledSignatures")),
    ("distinct signatures", ("cache", "distinctSignatures")),
    ("collision rate", ("cache", "collisionRate")),
    ("collision kinds", ("cache", "collisionKinds")),
    ("compiled over Metal limit", ("cache", "compiledOverMetalLimit")),
    ("problems", ("problems",)),
    ("kernels/computation mean", ("kernelsPerComputation", "mean")),
    ("kernels/computation max", ("kernelsPerComputation", "max")),
    ("arguments/kernel mean", ("argumentsPerKernel", "mean")),
    ("arguments/kernel max", ("argumentsPerKernel", "max")),
    ("kernels over Metal limit", ("argumentsPerKernel", "overMetalLimit")),
    ("folded/kernel mean", ("foldedPerCompiledKernel", "mean")),
    ("folded/kernel max", ("foldedPerCompiledKernel", "max")),
    ("aggregating kernels", ("aggregate", "kernels")),
    ("aggregate mean length", ("aggregate", "meanLength")),
    ("aggregate max length", ("aggregate", "maxLength")),
    ("copies in/aggregating dispatch", ("copies", "copiesInPerAggregatingDispatch")),
    ("copies back/aggregating dispatch", ("copies", "copiesBackPerAggregatingDispatch")),
]

# (label, path within a result) of every machine-dependent metric, compared as a ratio
TIMING = [
    ("native compilations", ("timing", "nativeCompilations")),
    ("native compile ms", ("timing", "nativeCompileMillis")),
    ("mean native compile ms", ("timing", "meanNativeCompileMillis")),
    ("first evaluation ms", ("timing", "firstEvaluationMillis")),
]


def load(path):
    """Read a report, refusing anything that is not an instruction-cache benchmark report."""
    with open(path, encoding="utf-8") as f:
        report = json.load(f)
    if report.get("benchmark") != "instruction-cache":
        raise ValueError(path + " is not an instruction-cache benchmark report")
    return report


def lookup(result, path):
    """Return the value at a path within a result, or None when it is absent."""
    value = result
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def fingerprints(report):
    """Return the population fingerprint of each scenario of a report."""
    return {s["name"]: s["fingerprint"] for s in report.get("scenarios", [])}


def results(report):
    """Return the results of a report keyed by (scenario, context)."""
    return {(r["scenario"], r["context"]): r for r in report.get("results", [])}


def compare_instances(before, after, limit):
    """Return a line for each instance whose kernels or outcome changed, at most ``limit`` of them."""
    lines = []
    old = before.get("instanceRecords", [])
    new = after.get("instanceRecords", [])
    for index, (a, b) in enumerate(zip(old, new)):
        if a.get("outcome") == b.get("outcome") and a.get("kernels") == b.get("kernels"):
            continue
        lines.append("    #%d %s %s %s: %s %s -> %s %s" % (
            index, a.get("template"), a.get("shape"), a.get("aliasing"),
            a.get("outcome"), a.get("kernels"), b.get("outcome"), b.get("kernels")))
    if len(lines) > limit:
        lines = lines[:limit] + ["    ... and %d more" % (len(lines) - limit)]
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("before", help="the report of the baseline branch")
    parser.add_argument("after", help="the report of the branch being measured")
    parser.add_argument("--instances", type=int, default=20,
                        help="the most changed instances listed per scenario and context (default 20)")
    args = parser.parse_args()

    try:
        before = load(args.before)
        after = load(args.after)
    except (OSError, ValueError) as e:
        print(e, file=sys.stderr)
        return 2

    old_prints = fingerprints(before)
    new_prints = fingerprints(after)
    common = [name for name in old_prints if name in new_prints]
    differing = [name for name in common if old_prints[name] != new_prints[name]]
    if differing:
        print("The populations differ for " + ", ".join(differing) +
              "; run both branches with the same command:\n  before: " + before.get("command", "?") +
              "\n  after:  " + after.get("command", "?"), file=sys.stderr)
        return 2

    old_results = results(before)
    new_results = results(after)
    changed = 0
    for key in old_results:
        if key not in new_results:
            print("%s / %s: only in %s" % (key[0], key[1], args.before))
            continue

        a = old_results[key]
        b = new_results[key]
        print("%s / %s" % key)

        for label, path in DETERMINISTIC:
            x = lookup(a, path)
            y = lookup(b, path)
            if x != y:
                changed += 1
                print("  %-34s %s -> %s" % (label, x, y))

        for label, path in TIMING:
            x = lookup(a, path)
            y = lookup(b, path)
            if isinstance(x, (int, float)) and isinstance(y, (int, float)) and x:
                print("  %-34s %s -> %s (x%.2f)" % (label, x, y, y / x))

        for line in compare_instances(a, b, args.instances):
            print(line)

    for key in new_results:
        if key not in old_results:
            print("%s / %s: only in %s" % (key[0], key[1], args.after))

    print("\n%d deterministic metric(s) changed" % changed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
