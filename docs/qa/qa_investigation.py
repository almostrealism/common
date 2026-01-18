#!/usr/bin/env python3
"""
QA Investigation Helper for Almost Realism Documentation

This script helps track the investigation of Java classes against MCP documentation.
"""

import csv
import os
from datetime import datetime

CSV_PATH = '/workspace/project/common/docs/qa_tracking.csv'
REPORT_PATH = '/workspace/project/common/docs/qa_report.md'

# Status values
STATUS_PENDING = 'pending'
STATUS_INVESTIGATED = 'investigated'
STATUS_ISSUES_FOUND = 'issues_found'
STATUS_FIXED = 'fixed'
STATUS_SKIPPED = 'skipped'

def load_tracking():
    """Load the tracking CSV into a list of dicts."""
    with open(CSV_PATH, 'r') as f:
        return list(csv.DictReader(f))

def save_tracking(rows):
    """Save the tracking data back to CSV."""
    with open(CSV_PATH, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['module', 'path', 'class_name', 'status', 'notes'])
        writer.writeheader()
        writer.writerows(rows)

def update_status(class_name, module, status, notes=''):
    """Update the status of a specific class."""
    rows = load_tracking()
    for row in rows:
        if row['class_name'] == class_name and row['module'] == module:
            row['status'] = status
            if notes:
                row['notes'] = notes
            break
    save_tracking(rows)
    print(f"Updated {module}/{class_name} -> {status}")

def get_stats():
    """Get statistics about investigation progress."""
    rows = load_tracking()
    stats = {}
    for row in rows:
        module = row['module']
        status = row['status']
        if module not in stats:
            stats[module] = {'total': 0, 'pending': 0, 'investigated': 0, 'issues_found': 0, 'fixed': 0, 'skipped': 0}
        stats[module]['total'] += 1
        stats[module][status] = stats[module].get(status, 0) + 1
    return stats

def get_pending_by_module(module, limit=10):
    """Get pending classes for a specific module."""
    rows = load_tracking()
    return [r for r in rows if r['module'] == module and r['status'] == 'pending'][:limit]

def generate_report():
    """Generate a markdown report of findings."""
    rows = load_tracking()
    stats = get_stats()

    # Get issues
    issues = [r for r in rows if r['status'] == 'issues_found']
    fixed = [r for r in rows if r['status'] == 'fixed']

    report = f"""# Documentation QA Report
Generated: {datetime.now().isoformat()}

## Summary

| Module | Total | Pending | Investigated | Issues | Fixed | Skipped |
|--------|-------|---------|--------------|--------|-------|---------|
"""
    for module, s in sorted(stats.items()):
        report += f"| {module} | {s['total']} | {s.get('pending', 0)} | {s.get('investigated', 0)} | {s.get('issues_found', 0)} | {s.get('fixed', 0)} | {s.get('skipped', 0)} |\n"

    if issues:
        report += "\n## Open Issues\n\n"
        for issue in issues:
            report += f"- **{issue['module']}/{issue['class_name']}**: {issue['notes']}\n"

    if fixed:
        report += "\n## Fixed Issues\n\n"
        for fix in fixed:
            report += f"- **{fix['module']}/{fix['class_name']}**: {fix['notes']}\n"

    with open(REPORT_PATH, 'w') as f:
        f.write(report)
    print(f"Report written to {REPORT_PATH}")

if __name__ == '__main__':
    import sys
    if len(sys.argv) < 2:
        print("Usage: python qa_investigation.py <command> [args]")
        print("Commands: stats, pending <module>, update <class> <module> <status> [notes], report")
        sys.exit(1)

    cmd = sys.argv[1]
    if cmd == 'stats':
        stats = get_stats()
        for module, s in sorted(stats.items()):
            print(f"{module}: {s['total']} total, {s.get('pending', 0)} pending")
    elif cmd == 'pending':
        module = sys.argv[2] if len(sys.argv) > 2 else 'algebra'
        pending = get_pending_by_module(module)
        for p in pending:
            print(f"  {p['class_name']}: {p['path']}")
    elif cmd == 'update':
        class_name = sys.argv[2]
        module = sys.argv[3]
        status = sys.argv[4]
        notes = sys.argv[5] if len(sys.argv) > 5 else ''
        update_status(class_name, module, status, notes)
    elif cmd == 'report':
        generate_report()
