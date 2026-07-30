#!/usr/bin/env python3
"""
Analyze the consultant request history for answer-quality signals.

Runs directly against ``history.db`` (the SQLite log every consultant tool
call is written to; see ``history.py``). It needs no model and no network —
it reports the cheap, objective proxies that bound answer quality, so a
strong-model judge pass (``judge_answers.py``) can be aimed at the records
that matter:

  - Passthrough rate: consults answered while no inference model was
    reachable (backend down). These answers are raw doc dumps, not
    synthesis, and if they were also stored via ``remember`` they polluted
    the memory corpus.
  - Retrieval whiff rate: consults that retrieved ZERO documentation. The
    model had nothing grounded to answer from.
  - Memory-miss rate: consults where memory recall returned nothing.
  - Keyword-provided rate: how often callers supplied the ``keywords``
    argument (CLAUDE.md requires it; low adherence explains bad retrieval).
  - Speculation rate: answers containing hedging/guessing phrases.
  - Response length and latency distributions.
  - Error rate and month-by-month trend.

Usage:
    python3 analyze_history.py [--db PATH] [--out report.json]

Default --db is ``../data/history.db`` relative to this file.
"""

import argparse
import json
import os
import sqlite3
from collections import Counter, defaultdict


# Hedging phrases that indicate the model is guessing rather than grounding.
# Kept aligned with scripts/evaluate_dataset.py so the two agree on what
# "speculation" means.
SPECULATION_PHRASES = (
    "does not contain",
    "does not specifically",
    "not mentioned",
    "speculative",
    "hypothetical",
    "based on typical",
    "i can infer",
    "you may need to refer",
    "not covered in",
    "might be",
    "could be",
)

PASSTHROUGH_BANNER = "[Consultant model not available"

_DEFAULT_DB = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "data", "history.db"
)


def _percentiles(values, points=(50, 90, 99)):
    """Return requested percentiles of a numeric list."""
    if not values:
        return {f"p{p}": 0 for p in points}
    ordered = sorted(values)
    out = {}
    for p in points:
        idx = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
        out[f"p{p}"] = ordered[idx]
    return out


def _has_speculation(text):
    """True when an answer contains any hedging phrase."""
    low = (text or "").lower()
    return any(phrase in low for phrase in SPECULATION_PHRASES)


def _keywords_provided(input_params):
    """True when the consult call supplied a non-empty keywords argument."""
    try:
        params = json.loads(input_params or "{}")
    except json.JSONDecodeError:
        return False
    keywords = params.get("keywords")
    return bool(keywords)


def analyze(db_path):
    """Compute the answer-quality report from history.db."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    tool_counts = {
        row["tool_name"]: row["n"]
        for row in conn.execute(
            "SELECT tool_name, COUNT(*) AS n FROM requests GROUP BY tool_name"
        )
    }

    consults = conn.execute(
        "SELECT * FROM requests WHERE tool_name = 'consult'"
    ).fetchall()
    total = len(consults)

    passthrough = whiff = mem_miss = with_keywords = speculation = errors = 0
    lengths = []
    latencies = []
    llm_latencies = []
    by_month = defaultdict(lambda: {"consults": 0, "whiff": 0, "passthrough": 0})

    for row in consults:
        response = row["llm_response"] or ""
        backend = row["backend"] or ""
        is_pt = backend.startswith("passthrough") or response.lstrip().startswith(
            PASSTHROUGH_BANNER
        )

        if is_pt:
            passthrough += 1
        if (row["doc_result_count"] or 0) == 0:
            whiff += 1
        if (row["memory_result_count"] or 0) == 0:
            mem_miss += 1
        if _keywords_provided(row["input_params"]):
            with_keywords += 1
        if not is_pt and _has_speculation(response):
            speculation += 1
        if row["status"] == "error":
            errors += 1
        if response:
            lengths.append(len(response))
        if row["latency_ms"] is not None:
            latencies.append(row["latency_ms"])
        if row["llm_latency_ms"] is not None:
            llm_latencies.append(row["llm_latency_ms"])

        month = (row["timestamp"] or "")[:7]
        bucket = by_month[month]
        bucket["consults"] += 1
        if (row["doc_result_count"] or 0) == 0:
            bucket["whiff"] += 1
        if is_pt:
            bucket["passthrough"] += 1

    conn.close()

    def pct(n):
        return round(100 * n / total, 1) if total else 0

    return {
        "db": db_path,
        "tool_counts": tool_counts,
        "consults": {
            "total": total,
            "passthrough": {"count": passthrough, "pct": pct(passthrough)},
            "retrieval_whiff": {"count": whiff, "pct": pct(whiff)},
            "memory_miss": {"count": mem_miss, "pct": pct(mem_miss)},
            "keywords_provided": {"count": with_keywords, "pct": pct(with_keywords)},
            "speculation": {"count": speculation, "pct": pct(speculation)},
            "errors": {"count": errors, "pct": pct(errors)},
            "response_len_chars": _percentiles(lengths),
            "latency_ms": _percentiles(latencies),
            "llm_latency_ms": _percentiles(llm_latencies),
        },
        "by_month": {m: by_month[m] for m in sorted(by_month)},
    }


def print_summary(report):
    """Print a human-readable digest."""
    c = report["consults"]
    print("=" * 64)
    print("CONSULTANT ANSWER-QUALITY PROXIES (from history.db)")
    print("=" * 64)
    print(f"Tool calls: {report['tool_counts']}")
    print(f"\nConsults analyzed: {c['total']}")
    print(f"  Passthrough (backend down): {c['passthrough']['count']} ({c['passthrough']['pct']}%)")
    print(f"  Retrieval whiff (0 docs):   {c['retrieval_whiff']['count']} ({c['retrieval_whiff']['pct']}%)")
    print(f"  Memory miss (0 memories):   {c['memory_miss']['count']} ({c['memory_miss']['pct']}%)")
    print(f"  Keywords provided:          {c['keywords_provided']['count']} ({c['keywords_provided']['pct']}%)")
    print(f"  Speculation in answer:      {c['speculation']['count']} ({c['speculation']['pct']}%)")
    print(f"  Errors:                     {c['errors']['count']} ({c['errors']['pct']}%)")
    print(f"\n  Response length (chars): p50={c['response_len_chars']['p50']} "
          f"p90={c['response_len_chars']['p90']} p99={c['response_len_chars']['p99']}")
    print(f"  Latency (ms):            p50={c['latency_ms']['p50']} "
          f"p90={c['latency_ms']['p90']} p99={c['latency_ms']['p99']}")

    print("\n  Month     consults  whiff  passthrough")
    for month, bucket in report["by_month"].items():
        print(f"    {month}    {bucket['consults']:6d}  {bucket['whiff']:5d}  "
              f"{bucket['passthrough']:9d}")


def main():
    parser = argparse.ArgumentParser(description="Analyze consultant history.db")
    parser.add_argument("--db", default=_DEFAULT_DB,
                        help="Path to history.db (default: ../data/history.db)")
    parser.add_argument("--out", help="Write full JSON report to this path")
    args = parser.parse_args()

    report = analyze(args.db)
    print_summary(report)

    if args.out:
        with open(args.out, "w") as handle:
            json.dump(report, handle, indent=2)
        print(f"\nFull report written to: {args.out}")


if __name__ == "__main__":
    main()
