#!/usr/bin/env python3
"""Sweep docs/plans: remove closed plans and report the ones needing attention.

Every plan carries the lifecycle header read by
``tools/mcp/common/plan_front_matter.py`` (rules in ``docs/plans/CLAUDE.md``).
This script reads those headers and does not need to read any plan's prose:

  expired   ``completed`` or ``abandoned`` with a ``closed`` date more than
            ``--retention-days`` ago. Removed with ``git rm`` under
            ``--delete``; listed otherwise.
  invalid   no header, or one that does not parse. Always listed; the run
            exits 1 when there are any, so CI can use ``--check``.
  stale     open, with no commit touching it for ``--stale-days``.
  tracked   open, with tracker tasks. The caller checks whether every listed
            task is closed; if so the plan is finished or abandoned and
            should be closed.

Usage:
  tools/ci/sweep-plans.py [--root DIR] [--today YYYY-MM-DD]
                          [--retention-days 14] [--stale-days 60]
                          [--delete | --check]
"""

import argparse
import datetime
import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, "..", "mcp", "common"))

from plan_front_matter import PLANS_DIR, PlanFrontMatter, is_plan_path  # noqa: E402


def plan_paths(root):
    """Repository-relative paths of every plan under docs/plans."""
    paths = []
    for directory, _, files in os.walk(os.path.join(root, PLANS_DIR)):
        for name in files:
            rel = os.path.relpath(os.path.join(directory, name), root).replace(os.sep, "/")
            if is_plan_path(rel):
                paths.append(rel)
    return sorted(paths)


def last_commit_date(root, path):
    """The committer date of the last commit touching path, or None if untracked."""
    out = subprocess.run(["git", "-C", root, "log", "-1", "--format=%cs", "--", path],
                         capture_output=True, text=True, check=False).stdout.strip()
    return datetime.date.fromisoformat(out) if out else None


def sweep(root, today, retention_days, stale_days):
    """Classify every plan. Returns a dict of the four lists described above."""
    report = {"expired": [], "invalid": [], "stale": [], "tracked": []}
    for path in plan_paths(root):
        with open(os.path.join(root, path), encoding="utf-8") as f:
            header, problems = PlanFrontMatter.parse(f.read())
        if problems:
            report["invalid"].append((path, problems))
        elif header.is_expired(today, retention_days):
            report["expired"].append((path, header.closed.isoformat()))
        elif header.status not in ("completed", "abandoned"):
            touched = last_commit_date(root, path)
            if touched is not None and (today - touched).days > stale_days:
                report["stale"].append((path, touched.isoformat()))
            if header.tracker:
                report["tracked"].append((path, header.tracker))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".")
    parser.add_argument("--today", type=datetime.date.fromisoformat, default=datetime.date.today())
    parser.add_argument("--retention-days", type=int, default=14)
    parser.add_argument("--stale-days", type=int, default=60)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--delete", action="store_true", help="git rm the expired plans")
    mode.add_argument("--check", action="store_true", help="report only invalid headers")
    args = parser.parse_args(argv)

    report = sweep(args.root, args.today, args.retention_days, args.stale_days)

    for path, problems in report["invalid"]:
        print("invalid  %s: %s" % (path, "; ".join(problems)))
    if not args.check:
        for path, closed in report["expired"]:
            print("expired  %s (closed %s)%s" % (path, closed, ", removed" if args.delete else ""))
            if args.delete:
                subprocess.run(["git", "-C", args.root, "rm", "-q", "--", path], check=True)
        for path, touched in report["stale"]:
            print("stale    %s (last commit %s)" % (path, touched))
        for path, tracker in report["tracked"]:
            print("tracked  %s: %s" % (path, ", ".join(tracker)))
    return 1 if report["invalid"] else 0


if __name__ == "__main__":
    sys.exit(main())
