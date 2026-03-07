#!/usr/bin/env python3
"""
Migrate memory stores from local SQLite databases to the centralized
ar-memory HTTP service.

Scans one or more directories for memory.db files (the SQLite databases
used by ar-memory's MemoryStore), reads all entries, and imports them
into the running ar-memory HTTP server. The server re-embeds each entry
on ingest, so only the metadata is transferred.

Usage:
    # Migrate from specific directories
    python migrate.py /path/to/dir1 /path/to/dir2

    # Scan recursively for memory.db files under a root
    python migrate.py --scan /home/user/.claude

    # Dry run (show what would be migrated without sending)
    python migrate.py --dry-run /path/to/dir

    # Target a specific ar-memory server
    python migrate.py --url http://localhost:8020 /path/to/dir

Each directory should contain a memory.db file (or the script will
search subdirectories for them). Duplicate entries are skipped based
on content + namespace + branch matching.
"""

import argparse
import json
import os
import sqlite3
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def find_memory_dbs(paths: list[str], scan: bool = False) -> list[str]:
    """Find memory.db files in the given paths.

    Args:
        paths: Directories or file paths to check.
        scan: If True, recursively search for memory.db files.

    Returns:
        List of absolute paths to memory.db files found.
    """
    dbs = []
    for path in paths:
        path = os.path.abspath(path)

        # Direct file reference
        if os.path.isfile(path) and path.endswith("memory.db"):
            dbs.append(path)
            continue

        # Check if directory contains memory.db directly
        candidate = os.path.join(path, "memory.db")
        if os.path.isfile(candidate):
            dbs.append(candidate)
            if not scan:
                continue

        # Recursive scan
        if scan and os.path.isdir(path):
            for root, dirs, files in os.walk(path):
                if "memory.db" in files:
                    dbs.append(os.path.join(root, "memory.db"))

    return sorted(set(dbs))


def read_entries(db_path: str) -> list[dict]:
    """Read all entries from a memory.db SQLite database.

    Args:
        db_path: Path to the SQLite database file.

    Returns:
        List of entry dicts with all fields.
    """
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        # Detect which columns exist (older databases lack repo_url/branch)
        col_info = conn.execute("PRAGMA table_info(entries)").fetchall()
        col_names = {row[1] for row in col_info}

        columns = ["id", "namespace", "content", "tags", "source", "created_at"]
        if "repo_url" in col_names:
            columns.append("repo_url")
        if "branch" in col_names:
            columns.append("branch")

        cursor = conn.execute(
            f"SELECT {', '.join(columns)} FROM entries ORDER BY created_at ASC"
        )
        entries = []
        for row in cursor:
            entry = dict(row)
            entry.setdefault("repo_url", None)
            entry.setdefault("branch", None)
            # Parse JSON tags
            if entry.get("tags"):
                try:
                    entry["tags"] = json.loads(entry["tags"])
                except (json.JSONDecodeError, TypeError):
                    entry["tags"] = None
            else:
                entry["tags"] = None
            entries.append(entry)
        return entries
    finally:
        conn.close()


def post_entry(base_url: str, entry: dict, auth_token: str = None) -> dict:
    """Store an entry via the ar-memory HTTP API.

    Args:
        base_url: ar-memory server base URL.
        entry: Entry dict to store.
        auth_token: Optional bearer token.

    Returns:
        Response dict from the server.
    """
    url = f"{base_url.rstrip('/')}/api/memory/store"
    payload = {
        "content": entry["content"],
        "namespace": entry.get("namespace", "default"),
        "repo_url": entry.get("repo_url", ""),
        "branch": entry.get("branch", ""),
    }
    if entry.get("tags"):
        payload["tags"] = entry["tags"]
    if entry.get("source"):
        payload["source"] = entry["source"]

    data = json.dumps(payload).encode("utf-8")
    req = Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if auth_token:
        req.add_header("Authorization", f"Bearer {auth_token}")

    with urlopen(req, timeout=30) as resp:
        body = resp.read().decode("utf-8")
        return json.loads(body) if body else {}


def check_server(base_url: str, auth_token: str = None) -> bool:
    """Check if the ar-memory server is reachable.

    Args:
        base_url: ar-memory server base URL.
        auth_token: Optional bearer token.

    Returns:
        True if the server is healthy.
    """
    url = f"{base_url.rstrip('/')}/api/health"
    req = Request(url)
    if auth_token:
        req.add_header("Authorization", f"Bearer {auth_token}")
    try:
        with urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("ok", False)
    except (URLError, HTTPError, Exception):
        return False


def _to_ssh_url(url: str) -> str:
    """Convert an HTTPS GitHub URL to SSH format.

    Examples:
        https://github.com/owner/repo       -> git@github.com:owner/repo.git
        https://github.com/owner/repo.git   -> git@github.com:owner/repo.git
        git@github.com:owner/repo.git       -> git@github.com:owner/repo.git  (unchanged)
        origin                              -> origin  (unchanged)
    """
    url = url.strip()
    if url.startswith("https://github.com/") or url.startswith("http://github.com/"):
        path = url.split("github.com/", 1)[1].rstrip("/").removesuffix(".git")
        return f"git@github.com:{path}.git"
    return url


def migrate(
    db_paths: list[str],
    base_url: str,
    auth_token: str = None,
    dry_run: bool = False,
    default_repo_url: str = "",
    default_branch: str = "",
    ssh_urls: bool = False,
) -> dict:
    """Migrate entries from local databases to the HTTP server.

    Args:
        db_paths: Paths to memory.db files.
        base_url: ar-memory server URL.
        auth_token: Optional bearer token.
        dry_run: If True, don't actually send entries.
        default_repo_url: Fallback repo_url for entries missing one.
        default_branch: Fallback branch for entries missing one.
        ssh_urls: If True, convert HTTPS GitHub URLs to SSH format.

    Returns:
        Summary dict with counts.
    """
    total = 0
    imported = 0
    had_context = 0
    used_default = 0
    skipped_no_context = 0
    skipped_empty = 0
    errors = []
    seen_repo_urls = set()
    seen_branches = set()

    for db_path in db_paths:
        print(f"\n--- {db_path} ---")
        entries = read_entries(db_path)
        print(f"  Found {len(entries)} entries")
        total += len(entries)

        for entry in entries:
            content = entry.get("content", "").strip()
            if not content:
                skipped_empty += 1
                continue

            # Track whether this entry had its own context or needed defaults
            orig_repo = entry.get("repo_url") or ""
            orig_branch = entry.get("branch") or ""
            repo_url = orig_repo or default_repo_url
            branch = orig_branch or default_branch

            if ssh_urls and repo_url:
                repo_url = _to_ssh_url(repo_url)

            if not repo_url or not branch:
                skipped_no_context += 1
                ns = entry.get("namespace", "default")
                preview = content[:60].replace("\n", " ")
                print(f"  SKIP (no repo/branch): [{ns}] {preview}...")
                continue

            if orig_repo and orig_branch:
                had_context += 1
            else:
                used_default += 1

            if orig_repo:
                seen_repo_urls.add(orig_repo)
            if orig_branch:
                seen_branches.add(orig_branch)

            entry["repo_url"] = repo_url
            entry["branch"] = branch

            ns = entry.get("namespace", "default")
            preview = content[:60].replace("\n", " ")

            if dry_run:
                print(f"  DRY: [{ns}] {preview}...")
                imported += 1
                continue

            try:
                result = post_entry(base_url, entry, auth_token)
                entry_id = result.get("id", "?")
                print(f"  OK:  [{ns}] {preview}... -> {entry_id}")
                imported += 1
            except (HTTPError, URLError) as e:
                err_msg = str(e)
                if hasattr(e, "read"):
                    err_msg = e.read().decode("utf-8", errors="replace")[:200]
                print(f"  ERR: [{ns}] {preview}... -> {err_msg}")
                errors.append({"db": db_path, "id": entry.get("id"), "error": err_msg})

    return {
        "total": total,
        "imported": imported,
        "had_context": had_context,
        "used_default": used_default,
        "skipped_no_context": skipped_no_context,
        "skipped_empty": skipped_empty,
        "errors": len(errors),
        "error_details": errors,
        "seen_repo_urls": sorted(seen_repo_urls),
        "seen_branches": sorted(seen_branches),
    }


def main():
    parser = argparse.ArgumentParser(
        description="Migrate local memory stores to the ar-memory HTTP service.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "paths",
        nargs="+",
        help="Directories containing memory.db, or paths to memory.db files directly",
    )
    parser.add_argument(
        "--scan",
        action="store_true",
        help="Recursively scan paths for memory.db files",
    )
    parser.add_argument(
        "--url",
        default=os.environ.get("AR_MEMORY_URL", "http://localhost:8020"),
        help="ar-memory HTTP server URL (default: $AR_MEMORY_URL or localhost:8020)",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("AR_MEMORY_AUTH_TOKEN", ""),
        help="Bearer auth token (default: $AR_MEMORY_AUTH_TOKEN)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be migrated without sending",
    )
    parser.add_argument(
        "--default-repo-url",
        default="",
        help="Fallback repo_url for entries that don't have one",
    )
    parser.add_argument(
        "--default-branch",
        default="",
        help="Fallback branch for entries that don't have one",
    )
    parser.add_argument(
        "--ssh-urls",
        action="store_true",
        help="Convert HTTPS GitHub URLs to SSH format (git@github.com:owner/repo.git)",
    )

    args = parser.parse_args()

    # Find databases
    db_paths = find_memory_dbs(args.paths, scan=args.scan)
    if not db_paths:
        print("No memory.db files found in the specified paths.", file=sys.stderr)
        sys.exit(1)

    print(f"Found {len(db_paths)} database(s):")
    for db in db_paths:
        print(f"  {db}")

    # Check server connectivity (unless dry run)
    token = args.token.strip() or None
    if not args.dry_run:
        print(f"\nConnecting to {args.url}...")
        if not check_server(args.url, token):
            print(f"ERROR: Cannot reach ar-memory at {args.url}", file=sys.stderr)
            print("Start the server or use --url to specify the correct address.",
                  file=sys.stderr)
            sys.exit(1)
        print("Server is healthy.")

    # Run migration
    summary = migrate(
        db_paths=db_paths,
        base_url=args.url,
        auth_token=token,
        dry_run=args.dry_run,
        default_repo_url=args.default_repo_url,
        default_branch=args.default_branch,
        ssh_urls=args.ssh_urls,
    )

    # Print summary
    print(f"\n{'=== DRY RUN SUMMARY ===' if args.dry_run else '=== MIGRATION SUMMARY ==='}")
    print(f"  Total entries found:     {summary['total']}")
    print(f"  Imported:                {summary['imported']}")
    print(f"    Had repo/branch:       {summary['had_context']}")
    print(f"    Used default:          {summary['used_default']}")
    print(f"  Skipped (no context):    {summary['skipped_no_context']}")
    print(f"  Skipped (empty):         {summary['skipped_empty']}")
    print(f"  Errors:                  {summary['errors']}")

    if summary["seen_repo_urls"]:
        print(f"\n  Repo URLs found in entries:")
        for url in summary["seen_repo_urls"]:
            print(f"    {url}")

    if summary["seen_branches"]:
        print(f"\n  Branches found in entries:")
        for b in summary["seen_branches"]:
            print(f"    {b}")

    if summary["errors"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
