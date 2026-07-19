#!/usr/bin/env python3
"""
Compute (and optionally apply) QC metadata for the ar-memory corpus.

This is the transition tool for SCHEMA_EVOLUTION_PROPOSAL.md. It derives
the values the proposed columns would hold for every existing entry:

    content_hash, origin, embedding_source, verification_status,
    superseded_by (exact-duplicate collapse)

It is **dry-run by default**: it fetches the corpus over HTTP, classifies
every row, and writes a report. It NEVER mutates the store unless run with
``--apply`` against a direct SQLite path (HTTP has no update endpoint yet,
by design — see the proposal's phased rollout).

Usage:
    # Dry run against the live service (read-only, recommended first):
    python3 backfill_qc_metadata.py --url http://host.docker.internal:8020 \
        --out /tmp/qc-backfill.json

    # Apply to a local SQLite copy AFTER the migration has added the columns:
    python3 backfill_qc_metadata.py --db /path/to/memory.db --apply

The classifier logic is the single source of truth for both the report and
the eventual apply, so what you review in dry-run is exactly what gets
written.
"""

import argparse
import hashlib
import json
import sqlite3
import sys
import urllib.request
from collections import Counter, defaultdict


DEFAULT_URL = "http://host.docker.internal:8020"

# Strings that mark a "memory" that is actually a backend-down passthrough
# dump or a leaked reformulation prompt rather than real knowledge.
GARBAGE_MARKERS = (
    "[Consultant model not available",
    "Returning raw context",
    "Reformulate the agent's note",
    "Return ONLY the reformulated note",
)


def content_hash(content: str) -> str:
    """SHA-256 of the content, matching the proposed content_hash column."""
    return hashlib.sha256((content or "").encode("utf-8")).hexdigest()


def is_garbage(content: str) -> bool:
    """True when content is a passthrough dump / leaked prompt.

    Anchored to the start of the content (first 200 chars) so that a
    meta-memory which merely *quotes* a banner — e.g. a QC audit note —
    is not mistaken for an actual backend-down dump, which always leads
    with the banner.
    """
    head = (content or "").lstrip()[:200]
    return any(marker in head for marker in GARBAGE_MARKERS)


def is_dual_wrapper(source) -> bool:
    """True when ``source`` is the store_dual {"original": ...} JSON wrapper."""
    if not source or not isinstance(source, str):
        return False
    try:
        parsed = json.loads(source)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(parsed, dict) and "original" in parsed


def classify(entry: dict) -> dict:
    """Derive the proposed QC-column values for a single entry.

    Returns a dict with the fields the migration would populate. This is
    the authoritative mapping used by both dry-run reporting and --apply.
    """
    content = entry.get("content") or ""
    garbage = is_garbage(content)
    dual = is_dual_wrapper(entry.get("source"))

    if garbage:
        origin = "passthrough-fallback"
        embedding_source = "content"
        verification_status = "refuted"
    elif dual:
        origin = "interactive-remember"
        embedding_source = "reformulated"
        verification_status = "unverified"
    else:
        origin = "job"
        embedding_source = "original"
        verification_status = "unverified"

    return {
        "id": entry.get("id"),
        "content_hash": content_hash(content),
        "origin": origin,
        "embedding_source": embedding_source,
        "verification_status": verification_status,
    }


def fetch_all_http(base_url: str) -> list:
    """Fetch every entry across every namespace over HTTP (read-only)."""
    def get(url):
        return json.loads(urllib.request.urlopen(url, timeout=30).read())

    counts = get(f"{base_url}/api/health").get("namespace_counts", {})
    entries = []
    for namespace, count in counts.items():
        offset = 0
        while offset < count:
            url = (
                f"{base_url}/api/memory/list?namespace={namespace}"
                f"&limit=500&offset={offset}"
            )
            page = get(url).get("entries", [])
            if not page:
                break
            entries.extend(page)
            offset += len(page)
    return entries


def fetch_all_sqlite(db_path: str) -> list:
    """Fetch every entry directly from a SQLite copy."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, namespace, content, source, created_at FROM entries"
    ).fetchall()
    conn.close()
    return [dict(row) for row in rows]


def compute_supersession(classified: list, entries_by_id: dict) -> dict:
    """Map duplicate entries to the id that should supersede them.

    Groups by content_hash; within each group the newest entry (by
    created_at) is kept and the rest are marked superseded_by that id.
    """
    groups = defaultdict(list)
    for record in classified:
        groups[record["content_hash"]].append(record["id"])

    superseded = {}
    for _hash, ids in groups.items():
        if len(ids) < 2:
            continue
        winner = max(
            ids, key=lambda i: entries_by_id[i].get("created_at") or ""
        )
        for entry_id in ids:
            if entry_id != winner:
                superseded[entry_id] = winner
    return superseded


def apply_to_sqlite(db_path: str, classified: list, superseded: dict) -> int:
    """Write derived metadata to a SQLite copy. Requires migrated columns."""
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.execute("PRAGMA table_info(entries)")
        columns = {row[1] for row in cursor.fetchall()}
        required = {"content_hash", "origin", "embedding_source",
                    "verification_status", "superseded_by"}
        missing = required - columns
        if missing:
            raise RuntimeError(
                f"columns missing (run the migration first): {sorted(missing)}"
            )
        updated = 0
        for record in classified:
            conn.execute(
                "UPDATE entries SET content_hash=?, origin=?, "
                "embedding_source=?, verification_status=?, superseded_by=? "
                "WHERE id=?",
                (
                    record["content_hash"],
                    record["origin"],
                    record["embedding_source"],
                    record["verification_status"],
                    superseded.get(record["id"]),
                    record["id"],
                ),
            )
            updated += 1
        conn.commit()
        return updated
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill ar-memory QC metadata")
    src = parser.add_mutually_exclusive_group()
    src.add_argument("--url", default=DEFAULT_URL,
                     help=f"ar-memory HTTP URL (read-only, default: {DEFAULT_URL})")
    src.add_argument("--db", help="Direct SQLite path (required for --apply)")
    parser.add_argument("--apply", action="store_true",
                        help="Write metadata to the --db copy (requires migration)")
    parser.add_argument("--out", help="Write the dry-run report JSON here")
    args = parser.parse_args()

    if args.apply and not args.db:
        print("ERROR: --apply requires --db (HTTP has no update endpoint)",
              file=sys.stderr)
        sys.exit(2)

    entries = fetch_all_sqlite(args.db) if args.db else fetch_all_http(args.url)
    entries_by_id = {e["id"]: e for e in entries}
    classified = [classify(e) for e in entries]
    superseded = compute_supersession(classified, entries_by_id)

    origin_counts = Counter(r["origin"] for r in classified)
    status_counts = Counter(r["verification_status"] for r in classified)

    report = {
        "total": len(classified),
        "origin": dict(origin_counts),
        "verification_status": dict(status_counts),
        "supersession": {
            "duplicate_entries_to_supersede": len(superseded),
        },
        "records": classified if args.out else None,
        "superseded_by": superseded if args.out else None,
    }

    print("=" * 60)
    print("QC METADATA BACKFILL — DRY RUN" if not args.apply else "QC BACKFILL — APPLY")
    print("=" * 60)
    print(f"Entries classified: {report['total']}")
    print(f"Origin:             {report['origin']}")
    print(f"Verification:       {report['verification_status']}")
    print(f"Dupes to supersede: {len(superseded)}")

    if args.out:
        with open(args.out, "w") as handle:
            json.dump(report, handle, indent=2)
        print(f"\nReport written to: {args.out}")

    if args.apply:
        updated = apply_to_sqlite(args.db, classified, superseded)
        print(f"\nApplied metadata to {updated} rows in {args.db}")


if __name__ == "__main__":
    main()
