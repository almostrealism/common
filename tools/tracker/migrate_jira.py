#!/usr/bin/env python3
"""Jira CSV to ar-tracker migration script.

Reads a Jira CSV export (Issues → Export → Export Excel CSV) and imports
all issues into the ar-tracker REST API as projects, releases, and tasks.

Usage:
    python migrate_jira.py \\
        --csv jira_export.csv \\
        --tracker-url http://localhost:8030 \\
        --tracker-token <token> \\
        [--project-name "My Project"] \\
        [--issue-type Story[,Task,Bug]] \\
        [--dry-run] \\
        [--workstream-map workstreams.json]

If --project-name is supplied, it is used for every row that does not
have a value in the CSV's "Project Name" column. Rows that already have
a Project Name are left alone.

If --issue-type is supplied, only rows whose "Issue Type" column matches
one of the given (comma-separated, case-insensitive) values are imported;
all others are skipped. Use this to drop Epics, Sub-tasks, or other
container issues that don't make sense as standalone tracker tasks.

Workstream map JSON format:
    {"PROJ-prefix": "ws-abc123", "summary-keyword": "ws-def456"}

The import is idempotent: task IDs are derived deterministically from
the Jira issue key using uuid.uuid5, so re-running the script is safe.
"""

import argparse
import csv
import json
import sys
import uuid
from datetime import datetime, timezone
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


# Jira status → tracker status
# The tracker is intentionally binary (open / closed). Anything that
# represents active or queued work maps to "open"; anything terminal
# maps to "closed". Unknown statuses fall back to "open" with a warning.
STATUS_MAP = {
    # Active / queued work
    "Open":                     "open",
    "To Do":                    "open",
    "Backlog":                  "open",
    "Selected for Development": "open",
    "Ready":                    "open",
    "Ready for Development":    "open",
    "Ready for Review":         "open",
    "In Progress":              "open",
    "In Review":                "open",
    "In Test":                  "open",
    "In QA":                    "open",
    "Testing":                  "open",
    "QA":                       "open",
    "Verified":                 "open",
    "Acceptance":               "open",
    "In Acceptance Testing":    "open",
    "Awaiting Approval":        "open",
    "Approved":                 "open",
    "Pending":                  "open",
    "Waiting":                  "open",
    "Blocked":                  "open",
    "On Hold":                  "open",
    "Reopened":                 "open",
    # Terminal / resolved
    "Done":      "closed",
    "Closed":    "closed",
    "Resolved":  "closed",
    "Released":  "closed",
    "Deployed":  "closed",
    "Won't Do":  "closed",
    "Won't Fix": "closed",
    "Cancelled": "closed",
    "Duplicate": "closed",
    "Invalid":   "closed",
    "Rejected":  "closed",
}

# Jira priority → tracker priority (signed integer in [-2, 2]).
# Modern Jira uses Highest..Lowest; older instances use Blocker..Trivial.
PRIORITY_MAP = {
    # Modern Jira labels
    "Highest":  2,
    "High":     1,
    "Medium":   0,
    "Low":     -1,
    "Lowest":  -2,
    # Legacy / pre-rename Jira labels
    "Blocker":  2,
    "Critical": 2,
    "Major":    1,
    "Minor":   -1,
    "Trivial": -2,
}
DEFAULT_PRIORITY = 0  # Medium

# UUID namespace for deterministic IDs derived from Jira issue keys
_JIRA_NS = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")  # UUID namespace DNS


def _jira_id_to_uuid(issue_key: str) -> str:
    """Derive a deterministic UUID from a Jira issue key."""
    return str(uuid.uuid5(_JIRA_NS, issue_key))


def _parse_date(date_str: str) -> Optional[str]:
    """Convert a Jira date string to ISO 8601 UTC."""
    if not date_str:
        return None
    for fmt in ("%d/%b/%y %I:%M %p", "%Y-%m-%dT%H:%M:%S.%f%z",
                "%Y-%m-%d %H:%M:%S", "%d/%m/%Y %H:%M"):
        try:
            dt = datetime.strptime(date_str.strip(), fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            continue
    return None


def _read_csv(path: str) -> list:
    """Read the Jira CSV export, trying UTF-8 then latin-1 encoding."""
    for encoding in ("utf-8-sig", "utf-8", "latin-1"):
        try:
            with open(path, encoding=encoding, newline="") as f:
                rows = list(csv.DictReader(f))
            return rows
        except UnicodeDecodeError:
            continue
    raise ValueError(f"Cannot decode {path} as UTF-8 or latin-1")


def _post_json(url: str, payload: dict, token: Optional[str], dry_run: bool) -> dict:
    """POST JSON payload to url. Returns parsed response or dry-run stub."""
    if dry_run:
        print(f"  [dry-run] POST {url}", file=sys.stderr)
        return {"ok": True, "dry_run": True}
    headers = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=data, headers=headers)
    try:
        with urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:200]}"}
    except URLError as e:
        return {"ok": False, "error": f"Unreachable: {e.reason}"}


def _get_json(url: str, token: Optional[str]) -> dict:
    """GET JSON from url."""
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = Request(url, headers=headers)
    try:
        with urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (HTTPError, URLError, json.JSONDecodeError):
        return {}


def _resolve_or_create_project(
    name: str,
    existing_projects: dict,
    tracker_url: str,
    token: Optional[str],
    dry_run: bool,
) -> Optional[str]:
    """Return the UUID for a project by name, creating it if needed."""
    if name in existing_projects:
        return existing_projects[name]
    resp = _post_json(f"{tracker_url}/v1/projects", {"name": name}, token, dry_run)
    if dry_run:
        fake_id = str(uuid.uuid5(_JIRA_NS, f"proj:{name}"))
        existing_projects[name] = fake_id
        return fake_id
    if resp.get("ok") and resp.get("project"):
        project_id = resp["project"]["id"]
        existing_projects[name] = project_id
        return project_id
    print(f"  WARNING: Failed to create project '{name}': {resp.get('error')}", file=sys.stderr)
    return None


def _resolve_or_create_release(
    name: str,
    project_id: Optional[str],
    existing_releases: dict,
    tracker_url: str,
    token: Optional[str],
    dry_run: bool,
) -> Optional[str]:
    """Return the UUID for a release by name, creating it if needed."""
    key = (name, project_id)
    if key in existing_releases:
        return existing_releases[key]
    payload: dict = {"name": name}
    if project_id:
        payload["project_id"] = project_id
    resp = _post_json(f"{tracker_url}/v1/releases", payload, token, dry_run)
    if dry_run:
        fake_id = str(uuid.uuid5(_JIRA_NS, f"rel:{name}:{project_id}"))
        existing_releases[key] = fake_id
        return fake_id
    if resp.get("ok") and resp.get("release"):
        release_id = resp["release"]["id"]
        existing_releases[key] = release_id
        return release_id
    print(f"  WARNING: Failed to create release '{name}': {resp.get('error')}", file=sys.stderr)
    return None


def migrate(
    csv_path: str,
    tracker_url: str,
    token: Optional[str],
    dry_run: bool,
    workstream_map: Optional[dict],
    default_project_name: Optional[str] = None,
    issue_types: Optional[set] = None,
) -> None:
    """Run the Jira → tracker migration.

    Args:
        csv_path: Path to the Jira CSV export file.
        tracker_url: Base URL of the ar-tracker service.
        token: Bearer auth token (None for open APIs).
        dry_run: If True, print what would happen without making requests.
        workstream_map: Optional mapping of Jira prefixes/keywords → workstream IDs.
        default_project_name: Optional project name used for rows that do
            not have a value in the CSV's "Project Name" column. Per-row
            values from the CSV always take precedence.
        issue_types: Optional set of accepted Jira "Issue Type" values
            (case-insensitive). Rows with an Issue Type outside this set
            are skipped. None means accept everything.
    """
    print(f"Reading {csv_path} …", file=sys.stderr)
    rows = _read_csv(csv_path)
    print(f"  {len(rows)} rows loaded", file=sys.stderr)

    if not dry_run:
        health = _get_json(f"{tracker_url}/api/health", token)
        if not health.get("ok"):
            print(
                f"ERROR: Tracker at {tracker_url} is not healthy: {health}",
                file=sys.stderr,
            )
            sys.exit(1)

    # Pre-load existing projects and releases to avoid duplicate creation.
    existing_projects: dict = {}  # name → id
    existing_releases: dict = {}  # (name, project_id) → id

    if not dry_run:
        for p in (_get_json(f"{tracker_url}/v1/projects", token) or {}).get("projects", []):
            existing_projects[p["name"]] = p["id"]
        for r in (_get_json(f"{tracker_url}/v1/releases", token) or {}).get("releases", []):
            existing_releases[(r["name"], r.get("project_id"))] = r["id"]

    # Batch tasks for bulk import.
    task_batch: list = []
    skipped = 0
    skipped_by_issue_type: dict = {}
    workstream_warnings: list = []
    unknown_priorities: set = set()
    unknown_statuses: set = set()
    accepted_types_lower = (
        {t.strip().lower() for t in issue_types if t and t.strip()}
        if issue_types else None
    )

    for row in rows:
        issue_key = row.get("Issue Key") or row.get("Issue key") or ""
        summary = row.get("Summary") or row.get("summary") or ""
        if not issue_key or not summary:
            skipped += 1
            continue

        if accepted_types_lower is not None:
            row_type = (
                row.get("Issue Type")
                or row.get("Issue type")
                or row.get("issuetype")
                or ""
            ).strip()
            if row_type.lower() not in accepted_types_lower:
                skipped_by_issue_type[row_type or "(blank)"] = (
                    skipped_by_issue_type.get(row_type or "(blank)", 0) + 1
                )
                continue

        project_name = (row.get("Project Name") or row.get("Project name") or "").strip()
        if not project_name and default_project_name:
            project_name = default_project_name
        project_id = None
        if project_name:
            project_id = _resolve_or_create_project(
                project_name, existing_projects, tracker_url, token, dry_run
            )

        # Jira's Fix Version column appears under a few different headings
        # depending on the export format and Jira version: "Fix Version/s"
        # (classic all-fields), "Fix Versions" / "Fix versions" (newer Cloud),
        # "Fix Version" (singular), and "fixVersions" (REST-derived exports).
        fix_versions_raw = (
            row.get("Fix Version/s")
            or row.get("Fix Versions")
            or row.get("Fix versions")
            or row.get("Fix Version")
            or row.get("Fix version")
            or row.get("fixVersions")
            or ""
        )
        release_id = None
        for version_name in [v.strip() for v in fix_versions_raw.split(",") if v.strip()]:
            release_id = _resolve_or_create_release(
                version_name, project_id, existing_releases, tracker_url, token, dry_run
            )
            break  # Use first fix version as the release

        jira_status = (row.get("Status") or "").strip()
        tracker_status = STATUS_MAP.get(jira_status, "open")
        if jira_status and jira_status not in STATUS_MAP:
            if jira_status not in unknown_statuses:
                unknown_statuses.add(jira_status)
                print(
                    f"  WARNING: Unknown Jira status '{jira_status}' "
                    f"(first seen on {issue_key}), defaulting to 'open'. "
                    f"Same warning is suppressed for further occurrences.",
                    file=sys.stderr,
                )

        jira_priority = (row.get("Priority") or "").strip()
        tracker_priority = PRIORITY_MAP.get(jira_priority, DEFAULT_PRIORITY)
        if jira_priority and jira_priority not in PRIORITY_MAP:
            if jira_priority not in unknown_priorities:
                unknown_priorities.add(jira_priority)
                print(
                    f"  WARNING: Unknown Jira priority '{jira_priority}' "
                    f"(first seen on {issue_key}), defaulting to 0 (Medium). "
                    f"Same warning is suppressed for further occurrences.",
                    file=sys.stderr,
                )

        workstream_id = None
        if workstream_map:
            for prefix_or_key, ws_id in workstream_map.items():
                if issue_key.startswith(prefix_or_key) or prefix_or_key.lower() in summary.lower():
                    workstream_id = ws_id
                    break
            if not workstream_id:
                workstream_warnings.append(issue_key)

        created_at = _parse_date(row.get("Created") or "")
        updated_at = _parse_date(row.get("Updated") or "")

        task_batch.append({
            "id": _jira_id_to_uuid(issue_key),
            "title": summary[:500],
            "description": row.get("Description") or None,
            "status": tracker_status,
            "priority": tracker_priority,
            "project_id": project_id,
            "release_id": release_id,
            "workstream_id": workstream_id,
            "created_at": created_at,
            "updated_at": updated_at,
        })

    if workstream_warnings:
        print(
            f"  {len(workstream_warnings)} tasks have no workstream mapping "
            f"(e.g. {workstream_warnings[:3]}). "
            f"They will be imported with workstream_id=null.",
            file=sys.stderr,
        )

    # Post in batches of 100
    total_imported = 0
    batch_size = 100
    for i in range(0, len(task_batch), batch_size):
        batch = task_batch[i:i + batch_size]
        resp = _post_json(
            f"{tracker_url}/v1/import",
            {"projects": [], "releases": [], "tasks": batch},
            token,
            dry_run,
        )
        if not dry_run:
            if resp.get("ok"):
                created = resp.get("created", {}).get("tasks", 0)
                updated = resp.get("updated", {}).get("tasks", 0)
                total_imported += created + updated
                print(
                    f"  Batch {i // batch_size + 1}: {created} created, {updated} updated",
                    file=sys.stderr,
                )
            else:
                print(f"  ERROR in batch {i // batch_size + 1}: {resp.get('error')}", file=sys.stderr)
        else:
            total_imported += len(batch)

    print(f"\nMigration complete:", file=sys.stderr)
    print(f"  Projects created/resolved: {len(existing_projects)}", file=sys.stderr)
    print(f"  Releases created/resolved: {len(existing_releases)}", file=sys.stderr)
    print(f"  Tasks imported: {total_imported}", file=sys.stderr)
    print(f"  Skipped rows (no key/summary): {skipped}", file=sys.stderr)
    if skipped_by_issue_type:
        total_filtered = sum(skipped_by_issue_type.values())
        breakdown = ", ".join(
            f"{t}={n}" for t, n in sorted(skipped_by_issue_type.items())
        )
        print(
            f"  Skipped rows (filtered by --issue-type): "
            f"{total_filtered} ({breakdown})",
            file=sys.stderr,
        )


def main() -> None:
    """CLI entry point."""
    parser = argparse.ArgumentParser(
        description="Migrate Jira CSV export to ar-tracker",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--csv", required=True, help="Path to Jira CSV export file")
    parser.add_argument(
        "--tracker-url",
        default="http://localhost:8030",
        help="ar-tracker base URL (default: http://localhost:8030)",
    )
    parser.add_argument("--tracker-token", default="", help="Bearer token for ar-tracker auth")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would happen without making any requests",
    )
    parser.add_argument(
        "--workstream-map",
        help="JSON file mapping Jira issue prefixes/keywords to workstream IDs",
    )
    parser.add_argument(
        "--project-name",
        default="",
        help=(
            "Default project name for rows lacking a 'Project Name' column. "
            "Per-row CSV values always take precedence."
        ),
    )
    parser.add_argument(
        "--issue-type",
        default="",
        help=(
            "Comma-separated list of Jira 'Issue Type' values to import "
            "(case-insensitive). Rows of any other type are skipped. "
            "Example: --issue-type Story,Task,Bug. Omit to import every row."
        ),
    )
    args = parser.parse_args()

    workstream_map = None
    if args.workstream_map:
        try:
            with open(args.workstream_map) as f:
                workstream_map = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            print(f"ERROR: Cannot read workstream map: {e}", file=sys.stderr)
            sys.exit(1)

    issue_types = (
        {t.strip() for t in args.issue_type.split(",") if t.strip()}
        if args.issue_type.strip() else None
    )

    migrate(
        csv_path=args.csv,
        tracker_url=args.tracker_url.rstrip("/"),
        token=args.tracker_token.strip() or None,
        dry_run=args.dry_run,
        workstream_map=workstream_map,
        default_project_name=args.project_name.strip() or None,
        issue_types=issue_types,
    )


if __name__ == "__main__":
    main()
