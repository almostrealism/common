#!/usr/bin/env python3
"""
Full-corpus census of the ar-memory store.

Pulls every memory entry from the ar-memory HTTP service and computes a
health report covering the questions that matter for data quality:

  - Volume: totals by namespace, repository, and branch.
  - Cross-project contamination: how many repos share the pool, and how
    much of each namespace belongs to each repo.
  - Reformulation coverage: which entries went through the consultant's
    weak-model reformulation (``store_dual`` writes a JSON ``source``
    wrapper containing the ``original``) versus stored verbatim by jobs.
  - Size distribution: content-length percentiles and the largest blobs,
    which are the memories most at risk from lossy reformulation and the
    hardest for recall to rank usefully.
  - Age / staleness: creation-time distribution, oldest and newest.
  - Duplication: exact-duplicate content, and cheap near-duplicate
    blocking by normalized prefix so an operator can see redundancy
    without a full O(n^2) semantic pass.
  - Tag hygiene: how many entries are untagged and the tag vocabulary.

This is read-only. It never mutates the store. Run it on any host that
can reach the ar-memory service (inside the project container the host
shortcut ``host.docker.internal`` resolves to the machine running the
service).

Usage:
    python3 memory_census.py [--url URL] [--out report.json]
                             [--exclude-namespace messages ...]
                             [--top-largest N]

Example:
    python3 memory_census.py --exclude-namespace messages \
        --out /tmp/consultant-census.json
"""

import argparse
import json
import hashlib
import re
import sys
import urllib.request
import urllib.error
from collections import Counter, defaultdict
from datetime import datetime, timezone


DEFAULT_URL = "http://host.docker.internal:8020"
# Page size for the /api/memory/list endpoint. The service paginates by
# namespace with limit/offset; 500 keeps each response small enough to
# stream while limiting the number of round trips.
PAGE_SIZE = 500


def _http_get(url: str, timeout: int = 30) -> dict:
    """GET a JSON document, raising a clear error on failure."""
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_namespace_counts(base_url: str) -> dict:
    """Return the {namespace: count} map from the health endpoint."""
    health = _http_get(f"{base_url}/api/health")
    return health.get("namespace_counts", {})


def fetch_namespace_entries(base_url: str, namespace: str, count: int) -> list:
    """Page through every entry in a namespace via list + offset."""
    entries = []
    offset = 0
    while offset < count:
        url = (
            f"{base_url}/api/memory/list?namespace={namespace}"
            f"&limit={PAGE_SIZE}&offset={offset}"
        )
        page = _http_get(url).get("entries", [])
        if not page:
            break
        entries.extend(page)
        offset += len(page)
    return entries


def parse_created(ts: str):
    """Parse an ISO-8601 timestamp, tolerating a trailing Z."""
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


def is_reformulated(entry: dict) -> bool:
    """True when the entry carries the store_dual JSON source wrapper.

    ``store_dual`` writes ``source`` as ``{"original": ..., "user_source":
    ...}``. A verbatim job memory has ``source`` null or a plain string.
    """
    source = entry.get("source")
    if not source or not isinstance(source, str):
        return False
    try:
        parsed = json.loads(source)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(parsed, dict) and "original" in parsed


def normalized_prefix(content: str, width: int = 64) -> str:
    """A whitespace-normalized, lowercased prefix for near-dup blocking."""
    collapsed = re.sub(r"\s+", " ", content).strip().lower()
    return collapsed[:width]


def percentiles(values: list, points=(50, 90, 95, 99)) -> dict:
    """Return the requested percentiles of a numeric list."""
    if not values:
        return {f"p{p}": 0 for p in points}
    ordered = sorted(values)
    out = {}
    for p in points:
        idx = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
        out[f"p{p}"] = ordered[idx]
    return out


def build_census(base_url: str, exclude_namespaces: set, top_largest: int) -> dict:
    """Fetch the full corpus and compute the census report."""
    counts = fetch_namespace_counts(base_url)

    all_entries = []
    per_namespace = {}
    for namespace, count in counts.items():
        if namespace in exclude_namespaces:
            per_namespace[namespace] = {"count": count, "fetched": 0, "excluded": True}
            continue
        entries = fetch_namespace_entries(base_url, namespace, count)
        per_namespace[namespace] = {"count": count, "fetched": len(entries)}
        for entry in entries:
            entry["_namespace"] = namespace
        all_entries.extend(entries)

    total_scanned = len(all_entries)

    # -- repository / branch distribution -----------------------------------
    repo_counter = Counter()
    branch_counter = Counter()
    repo_namespace = defaultdict(Counter)
    for entry in all_entries:
        repo = entry.get("repo_url") or "(none)"
        branch = entry.get("branch") or "(none)"
        repo_counter[repo] += 1
        branch_counter[f"{repo}::{branch}"] += 1
        repo_namespace[entry["_namespace"]][repo] += 1

    # -- reformulation coverage ---------------------------------------------
    reformulated = sum(1 for e in all_entries if is_reformulated(e))

    # -- size distribution --------------------------------------------------
    lengths = [len(e.get("content") or "") for e in all_entries]
    largest = sorted(
        all_entries, key=lambda e: len(e.get("content") or ""), reverse=True
    )[:top_largest]
    largest_report = [
        {
            "id": e.get("id"),
            "namespace": e["_namespace"],
            "repo_url": e.get("repo_url"),
            "length": len(e.get("content") or ""),
            "preview": (e.get("content") or "")[:160],
        }
        for e in largest
    ]

    # -- age / staleness ----------------------------------------------------
    now = datetime.now(timezone.utc)
    ages_days = []
    oldest = newest = None
    for entry in all_entries:
        created = parse_created(entry.get("created_at"))
        if created is None:
            continue
        ages_days.append((now - created).days)
        if oldest is None or created < oldest:
            oldest = created
        if newest is None or created > newest:
            newest = created

    # -- duplication --------------------------------------------------------
    exact_hashes = Counter()
    prefix_blocks = defaultdict(list)
    for entry in all_entries:
        content = entry.get("content") or ""
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        exact_hashes[digest] += 1
        prefix_blocks[normalized_prefix(content)].append(entry.get("id"))
    exact_dupe_groups = {h: c for h, c in exact_hashes.items() if c > 1}
    exact_dupe_entries = sum(c for c in exact_dupe_groups.values())
    near_dupe_blocks = {
        prefix: ids for prefix, ids in prefix_blocks.items() if len(ids) > 1
    }
    near_dupe_entries = sum(len(ids) for ids in near_dupe_blocks.values())

    # -- tag hygiene --------------------------------------------------------
    untagged = 0
    tag_counter = Counter()
    for entry in all_entries:
        tags = entry.get("tags")
        if not tags:
            untagged += 1
            continue
        for tag in tags:
            tag_counter[tag] += 1

    return {
        "generated_at": now.isoformat(),
        "source_url": base_url,
        "totals": {
            "reported_by_health": sum(counts.values()),
            "scanned": total_scanned,
            "excluded_namespaces": sorted(exclude_namespaces),
        },
        "namespaces": per_namespace,
        "repositories": {
            "distinct_repos": len(repo_counter),
            "by_repo": dict(repo_counter.most_common()),
            "by_repo_branch_top": dict(branch_counter.most_common(20)),
            "namespace_repo_spread": {
                ns: dict(rc.most_common())
                for ns, rc in repo_namespace.items()
                if len(rc) > 1
            },
        },
        "reformulation": {
            "reformulated": reformulated,
            "verbatim": total_scanned - reformulated,
            "reformulated_pct": round(100 * reformulated / total_scanned, 1)
            if total_scanned else 0,
        },
        "size": {
            "chars": percentiles(lengths),
            "max_chars": max(lengths) if lengths else 0,
            "mean_chars": round(sum(lengths) / len(lengths)) if lengths else 0,
            "largest": largest_report,
        },
        "age_days": {
            **percentiles(ages_days),
            "oldest": oldest.isoformat() if oldest else None,
            "newest": newest.isoformat() if newest else None,
        },
        "duplication": {
            "exact_duplicate_groups": len(exact_dupe_groups),
            "exact_duplicate_entries": exact_dupe_entries,
            "near_duplicate_blocks": len(near_dupe_blocks),
            "near_duplicate_entries": near_dupe_entries,
        },
        "tags": {
            "untagged": untagged,
            "untagged_pct": round(100 * untagged / total_scanned, 1)
            if total_scanned else 0,
            "distinct_tags": len(tag_counter),
            "top_tags": dict(tag_counter.most_common(25)),
        },
    }


def print_summary(report: dict) -> None:
    """Print a human-readable digest of the census report."""
    t = report["totals"]
    print("=" * 64)
    print("AR-MEMORY CORPUS CENSUS")
    print("=" * 64)
    print(f"Source:   {report['source_url']}")
    print(f"Reported: {t['reported_by_health']} entries "
          f"({t['scanned']} scanned, excluded={t['excluded_namespaces']})")

    print("\nRepositories:")
    for repo, count in report["repositories"]["by_repo"].items():
        print(f"  {count:6d}  {repo}")

    ref = report["reformulation"]
    print(f"\nReformulation coverage: {ref['reformulated']} reformulated "
          f"/ {ref['verbatim']} verbatim ({ref['reformulated_pct']}% reformulated)")

    size = report["size"]
    print(f"\nContent size (chars): mean={size['mean_chars']} "
          f"p50={size['chars']['p50']} p90={size['chars']['p90']} "
          f"p99={size['chars']['p99']} max={size['max_chars']}")

    age = report["age_days"]
    print(f"\nAge (days): p50={age['p50']} p90={age['p90']} p99={age['p99']}")
    print(f"  oldest={age['oldest']}  newest={age['newest']}")

    dup = report["duplication"]
    print(f"\nDuplication: {dup['exact_duplicate_entries']} entries in "
          f"{dup['exact_duplicate_groups']} exact-dupe groups; "
          f"{dup['near_duplicate_entries']} entries in "
          f"{dup['near_duplicate_blocks']} near-dupe prefix blocks")

    tags = report["tags"]
    print(f"\nTags: {tags['untagged']} untagged ({tags['untagged_pct']}%), "
          f"{tags['distinct_tags']} distinct tags")

    print("\nLargest memories:")
    for item in report["size"]["largest"][:10]:
        print(f"  {item['length']:7d}  [{item['namespace']}] {item['preview'][:80]}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Census the ar-memory corpus")
    parser.add_argument("--url", default=DEFAULT_URL,
                        help=f"ar-memory base URL (default: {DEFAULT_URL})")
    parser.add_argument("--out", help="Write full JSON report to this path")
    parser.add_argument("--exclude-namespace", action="append", default=[],
                        help="Namespace to count but not scan (repeatable, "
                             "e.g. --exclude-namespace messages)")
    parser.add_argument("--top-largest", type=int, default=25,
                        help="How many largest memories to report")
    args = parser.parse_args()

    try:
        report = build_census(
            args.url, set(args.exclude_namespace), args.top_largest
        )
    except urllib.error.URLError as exc:
        print(f"ERROR: could not reach ar-memory at {args.url}: {exc}",
              file=sys.stderr)
        sys.exit(1)

    print_summary(report)

    if args.out:
        with open(args.out, "w") as handle:
            json.dump(report, handle, indent=2)
        print(f"\nFull report written to: {args.out}")


if __name__ == "__main__":
    main()
