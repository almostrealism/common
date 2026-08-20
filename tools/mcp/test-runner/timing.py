#!/usr/bin/env python3
"""Timing analysis for a repeated run.

Running a test many times answers questions a single run cannot: how much its
duration varies, and whether a failure is deterministic or intermittent. This
module turns the per-invocation record kept in a run's metadata, plus the
per-test times read from its reports, into those statistics.

Only repeated runs have anything to analyse here — a single invocation has no
distribution, and its duration is already in the run status.
"""

import statistics as stats_module
from typing import Optional


def summarize(values: list) -> dict:
    """Return mean, median, spread, and coefficient of variation for a series.

    The coefficient of variation is the interesting one for repeated tests: it
    is scale-free, so a 10% spread reads the same whether the test takes a
    second or a minute.
    """
    if not values:
        return {"count": 0, "mean": 0, "median": 0, "std_dev": 0,
                "min": 0, "max": 0, "cv": 0}

    n = len(values)
    mean = stats_module.mean(values)
    median = stats_module.median(values)
    std_dev = stats_module.stdev(values) if n >= 2 else 0
    cv = (std_dev / mean * 100) if mean > 0 else 0

    return {
        "count": n,
        "mean": round(mean, 3),
        "median": round(median, 3),
        "std_dev": round(std_dev, 3),
        "min": round(min(values), 3),
        "max": round(max(values), 3),
        "cv": round(cv, 1)
    }


def per_test(test_times: dict) -> list:
    """Return per-test timing and pass-rate stats, slowest first.

    Args:
        test_times: ``{"class#method": [{"time", "status", "invocation"}, ...]}``
            as collected from a repeated run's reports.
    """
    result = []

    for key, entries in test_times.items():
        failure_count = sum(1 for e in entries if e["status"] in ("failed", "error"))
        pass_count = sum(1 for e in entries if e["status"] == "passed")
        total = len(entries)

        result.append({
            "test": key,
            "timing": summarize([e["time"] for e in entries]),
            "pass_rate": round(pass_count / total * 100, 1) if total > 0 else 0,
            "failure_count": failure_count,
            "invocation_count": total
        })

    result.sort(key=lambda x: x["timing"]["mean"], reverse=True)
    return result


def analyze(run_id: str, metadata: dict, reports) -> Optional[dict]:
    """Return the timing analysis for a run, or an error for a single-invocation run.

    Args:
        run_id: The run identifier.
        metadata: The run's stored metadata.
        reports: The run's :class:`reports.SurefireReports`.
    """
    repetitions = metadata.get("repetitions", 1)
    if repetitions <= 1:
        return {"error": "get_run_timing is only available for multi-invocation runs "
                         "(repetitions > 1). Use get_run_status for single-invocation timing."}

    invocations = metadata.get("invocations", [])
    durations = [inv["duration_seconds"] for inv in invocations
                 if "duration_seconds" in inv]

    return {
        "run_id": run_id,
        "repetitions": repetitions,
        "invocations_completed": len(invocations),
        "invocation_stats": summarize(durations),
        "invocations": invocations,
        "test_method_stats": per_test(reports.test_times())
    }
