#!/usr/bin/env bash
# ─── Verify the exfiltration guard hook is present, registered, intact ──
#
# The exfiltration guard (.claude/hooks/block-exfiltration.sh and its
# shared core) is the only thing standing between an agent's tools and the
# outside world: it enforces that nothing leaves the machine except a
# repository-tracked file published as an artifact. A guard that an agent
# can delete, unregister, or quietly weaken is not a guard. This script
# mirrors how validate-agent-commit.sh protects CI files: the guard's
# files are read-only for agents, and their registration must survive.
#
# Three checks, in order:
#
# CHECK 1 (Presence): every guard file exists on HEAD — the adapter, the
#   shared core, its unit tests, the allowlist, and this script's own test.
#
# CHECK 2 (Registration): .claude/settings.json on HEAD registers the
#   adapter under hooks.PreToolUse with a matcher that covers each of the
#   tools Artifact, SendUserFile and Bash. A registration that is present
#   but no longer covers one of them counts as disabled.
#
# CHECK 3 (Integrity, PR branches only): none of the guard files, and not
#   the guard's registration entry, changed between <base-branch> and HEAD.
#   Any change — even a "strengthening" one — must be made and committed
#   by a human. The override the workflow already offers for enforcement
#   files (override_integrity_checks) applies here the same way.
#
# Usage:
#   verify-exfiltration-guard.sh [<base-branch>]
#
#   With no base branch only CHECK 1 and CHECK 2 run (what master should
#   satisfy at all times). With a base branch CHECK 3 runs as well.
#
# Exit codes:
#   0 - guard present, registered, and (if a base was given) unchanged
#   1 - invalid arguments / tooling missing
#   2 - BLOCKED: a guard file is missing from HEAD
#   3 - BLOCKED: the guard is not registered for every required tool
#   4 - BLOCKED: a guard file or its registration changed on this branch
#
# NOTE(review): four files in the guard feature that agents cannot edit —
# .claude/hooks/lib/exfiltration_guard_check.py, .claude/hooks/block-exfiltration.sh,
# .claude/hooks/exfil-allowlist.txt, .claude/settings.json — still name the design
# plan document for this feature in comments, docstrings, or block messages shown
# to the model/human. docs/plans/CLAUDE.md forbids durable code referencing a plan
# document (it is temporary and may be deleted or superseded once the feature
# lands, leaving a dangling pointer). This file, tools/bin/llama-service.sh, and
# tools/ci/README.md have had those references removed since a human can edit
# those four remaining files; the same cleanup is still owed there.
#
# Outputs (to GITHUB_OUTPUT if available):
#   blocked=true|false
#   block_reason=<reason>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_BRANCH="${1:-}"

GUARD_FILES=(
    ".claude/hooks/block-exfiltration.sh"
    ".claude/hooks/lib/exfiltration_guard_check.py"
    ".claude/hooks/lib/test_exfiltration_guard_check.py"
    ".claude/hooks/exfil-allowlist.txt"
    "tools/ci/agent-protection/verify-exfiltration-guard.sh"
    "tools/ci/agent-protection/test-verify-exfiltration-guard.sh"
    "tools/ci/agent-protection/exfil_guard_registration.py"
    "tools/ci/agent-protection/test_exfil_guard_registration.py"
)
SETTINGS_FILE=".claude/settings.json"
ADAPTER_NAME="block-exfiltration.sh"
REQUIRED_TOOLS=("Artifact" "SendUserFile" "Bash")

if ! command -v python3 >/dev/null 2>&1; then
    echo "verify-exfiltration-guard: python3 is required but was not found in PATH" >&2
    exit 1
fi

emit_output() {
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "blocked=$1" >> "$GITHUB_OUTPUT"
        echo "block_reason=$2" >> "$GITHUB_OUTPUT"
    fi
}

banner() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    printf "║  %-64s║\n" "$1"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║  The exfiltration guard is the only barrier between an agent's  ║"
    echo "║  tools and the outside world. It is read-only for agents: it   ║"
    echo "║  cannot be deleted, unregistered, or edited on a PR branch.    ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
}

# ── CHECK 1: presence on HEAD ───────────────────────────────────────

MISSING=""
for f in "${GUARD_FILES[@]}"; do
    if ! git cat-file -e "HEAD:${f}" 2>/dev/null; then
        MISSING="${MISSING}  - ${f}\n"
    fi
done

if [ -n "$MISSING" ]; then
    banner "BLOCKED: EXFILTRATION GUARD FILE MISSING FROM HEAD"
    echo "Missing on HEAD:"
    echo -e "$MISSING"
    emit_output true guard_file_missing
    exit 2
fi

# ── CHECK 2: registration on HEAD ───────────────────────────────────
#
# The registration is read from HEAD (git show), not the checkout, so a
# working-tree edit cannot satisfy it. The matcher is evaluated the way
# Claude Code evaluates it: as a regular expression against the tool
# name, with a plain string matching exactly.

HEAD_SETTINGS=$(git show "HEAD:${SETTINGS_FILE}" 2>/dev/null || true)

# The settings text travels through an environment variable rather than a
# pipe: the python program itself arrives on stdin (heredoc), so stdin is
# not available for data. PYTHONPATH points at this script's own directory
# so the heredoc can import the invokes_adapter() helper shared with the
# registration_json() heredoc below, instead of duplicating it.
REGISTRATION_STATUS=$(SETTINGS_JSON="$HEAD_SETTINGS" ADAPTER_NAME="$ADAPTER_NAME" \
    REQUIRED_TOOLS="${REQUIRED_TOOLS[*]}" PYTHONPATH="$SCRIPT_DIR" \
    python3 - <<'PY' || echo "unreadable"
import json
import os
import re
import sys

sys.path.insert(0, os.environ["PYTHONPATH"])
from exfil_guard_registration import invokes_adapter

try:
    settings = json.loads(os.environ["SETTINGS_JSON"])
except Exception:
    print("unreadable")
    sys.exit(0)

adapter = os.environ["ADAPTER_NAME"]
required = os.environ["REQUIRED_TOOLS"].split()

groups = (settings.get("hooks") or {}).get("PreToolUse") or []
covered = set()
for group in groups:
    if not isinstance(group, dict):
        continue
    commands = [h.get("command", "") for h in group.get("hooks") or [] if isinstance(h, dict)]
    if not any(invokes_adapter(c, adapter) for c in commands):
        continue
    matcher = group.get("matcher")
    if matcher is None:
        matcher = ""
    for tool in required:
        if matcher in ("", "*", tool):
            covered.add(tool)
            continue
        try:
            if re.fullmatch(matcher, tool) or re.search(matcher, tool):
                covered.add(tool)
        except re.error:
            pass
missing = [t for t in required if t not in covered]
print("ok" if not missing else "missing:" + ",".join(missing))
PY
)

if [ "$REGISTRATION_STATUS" != "ok" ]; then
    banner "BLOCKED: EXFILTRATION GUARD IS NOT REGISTERED"
    echo "hooks.PreToolUse in ${SETTINGS_FILE} on HEAD must run ${ADAPTER_NAME} for"
    echo "every one of: ${REQUIRED_TOOLS[*]}"
    echo "Status: ${REGISTRATION_STATUS}"
    emit_output true guard_not_registered
    exit 3
fi

# ── CHECK 3: integrity against the base branch ──────────────────────

if [ -z "$BASE_BRANCH" ]; then
    echo "Exfiltration guard present and registered on HEAD (no base branch given; integrity check skipped)."
    emit_output false none
    exit 0
fi

CHANGED=""
for f in "${GUARD_FILES[@]}"; do
    # Not present on the base branch at all: this is the guard's first-time
    # introduction, not a modification of an established file. Nothing is
    # hidden by it — the whole file is visible as new content in the PR
    # diff, the same as any other addition. Once merged, every subsequent
    # change to it is caught below as usual.
    if ! git cat-file -e "${BASE_BRANCH}:${f}" 2>/dev/null; then
        continue
    fi
    if git diff --name-only "${BASE_BRANCH}...HEAD" -- "$f" | grep -q .; then
        CHANGED="${CHANGED}  - ${f}\n"
    fi
done

# The registration entry is compared as the normalised JSON of the group
# that runs the adapter, so an unrelated settings.json edit does not trip
# the check but removing, rewording, or narrowing the guard's entry does.
registration_json() {
    local text
    text=$(git show "$1:${SETTINGS_FILE}" 2>/dev/null || true)
    SETTINGS_JSON="$text" ADAPTER_NAME="$ADAPTER_NAME" PYTHONPATH="$SCRIPT_DIR" \
        python3 - <<'PY' || echo "unreadable"
import json
import os
import sys

sys.path.insert(0, os.environ["PYTHONPATH"])
from exfil_guard_registration import invokes_adapter

try:
    settings = json.loads(os.environ["SETTINGS_JSON"])
except Exception:
    print("unreadable")
    sys.exit(0)
adapter = os.environ["ADAPTER_NAME"]

groups = (settings.get("hooks") or {}).get("PreToolUse") or []
entries = [g for g in groups if isinstance(g, dict)
           and any(invokes_adapter(h.get("command", "") if isinstance(h, dict) else "", adapter)
                   for h in g.get("hooks") or [])]
print(json.dumps(entries, sort_keys=True))
PY
}

if git cat-file -e "${BASE_BRANCH}:${SETTINGS_FILE}" 2>/dev/null; then
    BASE_REGISTRATION=$(registration_json "$BASE_BRANCH")
    HEAD_REGISTRATION=$(registration_json HEAD)
    # "[]" means the adapter had no registration entry at all on the base
    # branch — this is the registration's first-time introduction, not a
    # removal or weakening of one that already existed, so it is not
    # flagged. Any change starting from an existing, non-empty
    # registration still is.
    if [ "$BASE_REGISTRATION" != "$HEAD_REGISTRATION" ] && [ "$BASE_REGISTRATION" != "[]" ]; then
        CHANGED="${CHANGED}  - ${SETTINGS_FILE} (the ${ADAPTER_NAME} registration entry)\n"
    fi
fi
# When settings.json does not exist on the base branch at all, there is no
# prior registration to weaken — whatever HEAD introduces is by definition
# new, not a modification, so the comparison above is skipped entirely.

if [ -n "$CHANGED" ]; then
    banner "BLOCKED: EXFILTRATION GUARD MODIFIED ON THIS BRANCH"
    echo "Changed relative to ${BASE_BRANCH}:"
    echo -e "$CHANGED"
    echo "Changes to the guard are made and committed by a human, never by an agent."
    emit_output true guard_modified
    exit 4
fi

echo "Exfiltration guard present, registered, and unchanged relative to ${BASE_BRANCH}."
emit_output false none
exit 0
