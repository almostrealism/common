#!/usr/bin/env bash
# stop-guard.sh — Stop hook
#
# Fires when Claude attempts to stop (end the session / return control to user).
# Blocks stopping before 6:00 PM Pacific Time by exiting with code 2.
#
# Purpose: Enforce the user's standing instruction to keep working until 6pm PT.
#
# Exit 0  → allow Claude to stop
# Exit 2  → BLOCK stop; Claude must keep working

set -euo pipefail

# Get current hour in Pacific time (handles both PST UTC-8 and PDT UTC-7)
# Use 'TZ' env var with date command
PACIFIC_HOUR="$(TZ='America/Los_Angeles' date '+%H')"
PACIFIC_TIME="$(TZ='America/Los_Angeles' date '+%H:%M %Z')"
PACIFIC_DATE="$(TZ='America/Los_Angeles' date '+%Y-%m-%d')"

# Convert to integer
HOUR=$((10#$PACIFIC_HOUR))

if [ "$HOUR" -lt 18 ]; then
    cat <<BLOCKED
╔══════════════════════════════════════════════════════════════════════════════╗
║  STOP BLOCKED — It is $PACIFIC_TIME on $PACIFIC_DATE            ║
║  You have been instructed to keep working until 6:00 PM Pacific Time.     ║
╚══════════════════════════════════════════════════════════════════════════════╝

Current Pacific Time: $PACIFIC_TIME
Stop permitted after: 18:00 $( TZ='America/Los_Angeles' date '+%Z')

You must continue working. Outstanding tasks (check pending work):

1. Implement hook scripts and settings.json updates (hooks enforcement system)
2. Write .github/CI_ARCHITECTURE.md (~20kb)
3. Write docs/internals/module-dependency-architecture.md (~15kb)
4. Write docs/internals/standalone-modules.md (~5kb)
5. Write docs/internals/ci-investigation-protocol.md (~10kb)
6. Verify all documentation totals at least 50kb of new content
7. Verify hooks are properly wired in .claude/settings.json
8. Run mvn clean install -DskipTests to verify no build breakage

Pick up the next uncompleted task and continue working.
BLOCKED
    exit 2
fi

echo "Stop permitted: Current Pacific Time is $PACIFIC_TIME (after 6:00 PM)"
exit 0
