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
# Four checks, in order:
#
# CHECK 0 (Applicability, PR branches only): the branch is one the guard
#   can be measured against at all. A branch cut before the guard existed
#   is not — it has nothing to measure — and exits 5.
#
# CHECK 1 (Presence): every guard file exists on HEAD — the adapter, the
#   shared core, its unit tests, the allowlist, and this script's own test.
#
# CHECK 2 (Registration): .claude/settings.json on HEAD registers the
#   adapter under hooks.PreToolUse with a matcher that covers each of the
#   tools Artifact, SendUserFile and Bash. A registration that is present
#   but no longer covers one of them counts as disabled.
#
# CHECK 3 (Integrity, PR branches only, and not on a ci/... branch): none
#   of the guard files, and not the guard's registration entry, changed
#   between the merge base with <base-branch> and HEAD — what this branch
#   did, not what has landed on the base branch since it diverged. Any
#   change — even a "strengthening" one — must be made by a human. The override the workflow
#   already offers for enforcement files (override_integrity_checks)
#   applies here the same way.
#
#   A ci/... branch is exempt from this comparison only. The pipeline is
#   the declared subject of the work there — the same declaration
#   validate-agent-commit.sh RULE 3 and the enforcement-tampering check
#   already accept — and a branch that may rewrite the workflow could
#   equally delete the step that runs this script, so comparing its edits
#   obstructs the work without policing it. CHECK 1 and CHECK 2 still
#   apply: they are what says the guard is present and registered in the
#   tree an agent session runs with, and no branch name lifts that.
#
# Usage:
#   verify-exfiltration-guard.sh [<base-branch> [<branch-name>]]
#
#   With no base branch only CHECK 1 and CHECK 2 run (what master should
#   satisfy at all times). With a base branch CHECK 3 runs as well.
#
#   <branch-name> is the branch under review, used only to recognise a
#   ci/... branch (see CHECK 3). CI must pass it explicitly: the workspace
#   is a detached checkout, where the branch name is not recoverable from
#   the repository. When omitted it is read from the checkout, which is
#   what a developer running this by hand wants.
#
# Exit codes:
#   0 - guard present, registered, and (if a base was given) unchanged
#   1 - invalid arguments / tooling missing
#   2 - BLOCKED: a guard file is missing from HEAD
#   3 - BLOCKED: the guard is not registered for every required tool
#   4 - BLOCKED: a guard file or its registration changed on this branch
#   5 - NOT APPLICABLE: the branch was cut before the guard existed
#
# Exit 5 is not a pass and not a block: there is nothing to verify. A
# branch whose merge base with the base branch predates the guard cannot
# be missing it, deleting it, or weakening it — the guard was simply not
# in the tree the branch was cut from, and every guard file is absent for
# the same benign reason an old branch lacks any other recent file. The
# distinction matters because the two states are otherwise identical on
# HEAD, and reporting "the guard is missing" for a branch that predates
# it accuses the branch of the one thing this script exists to catch.
# Merging the base branch (or rebasing onto it) brings the guard in and
# the checks apply again from that moment; agent branches are cut from a
# current base and so are never in this state to begin with.
#
# The reasoning behind the policy, the bypasses it was designed against,
# and what it knowingly does not cover live in
# docs/internals/exfiltration-guard.md.
#
# Outputs (to GITHUB_OUTPUT if available):
#   blocked=true|false
#   block_reason=<reason>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_BRANCH="${1:-}"
BRANCH_NAME="${2:-}"

if [ -z "$BRANCH_NAME" ]; then
    # "HEAD" on a detached checkout, which matches no policy and so
    # exempts nothing — the safe answer when the name is unknown.
    BRANCH_NAME=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")
fi

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
ADAPTER_PATH=".claude/hooks/block-exfiltration.sh"
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

# ── CHECK 0: is the guard established at the branch point? ──────────
#
# Asked before anything else, and only when a base branch was given (on
# the base branch itself the guard must always be present, so there is
# nothing to date it against). The adapter stands for the whole feature:
# every guard file landed in the same commit, so its absence at the merge
# base means the branch was cut before any of them existed.

if [ -n "$BASE_BRANCH" ]; then
    MERGE_BASE=$(git merge-base "$BASE_BRANCH" HEAD 2>/dev/null || true)

    if [ -z "$MERGE_BASE" ]; then
        echo "verify-exfiltration-guard: no merge base between ${BASE_BRANCH} and HEAD;" >&2
        echo "cannot tell a branch that predates the guard from one that removed it." >&2
        exit 1
    fi

    # Only a branch that has no guard AND never had one is dated out. A
    # branch that carries the guard is verified in full whether it
    # inherited it or introduced it — the branch that first adds the
    # guard still has to add it correctly, and CHECK 3 already exempts
    # first-time files from the integrity comparison.
    #
    # The two endpoints alone are not enough to establish "never had
    # one": a branch cut before the guard could add the adapter and
    # delete it again, leaving both endpoints empty while the deletion
    # sits in the middle. So the branch's own history is consulted too,
    # and any commit that touched the adapter disqualifies the branch
    # from being treated as predating it — the checks below then report
    # the deletion as what it is.
    TOUCHED_ADAPTER=$(git log --format=%H "${MERGE_BASE}..HEAD" -- "${ADAPTER_PATH}" 2>/dev/null | head -1 || true)

    if [ -z "$TOUCHED_ADAPTER" ] \
            && ! git cat-file -e "HEAD:${ADAPTER_PATH}" 2>/dev/null \
            && ! git cat-file -e "${MERGE_BASE}:${ADAPTER_PATH}" 2>/dev/null; then
        echo "The exfiltration guard does not exist at this branch's merge base with"
        echo "${BASE_BRANCH} (${MERGE_BASE}) — the branch was cut before the guard"
        echo "landed, so there is nothing on it to verify. Merge ${BASE_BRANCH} to"
        echo "bring the guard in; the checks apply from that point on."
        emit_output false predates_guard
        exit 5
    fi
fi

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

# The ci/... carve-out applies to this comparison and to nothing above it.
# A branch named for the pipeline may change the files that enforce the
# pipeline — that is the declaration the name makes, and holding an edit
# against it would only obstruct work the check cannot police anyway,
# since the same branch could delete the step that runs this script.
#
# Presence and registration are a different claim, and they are NOT
# lifted: they say the guard is in the tree an agent session will run
# with. Exempting those would disable the guard at runtime for every
# session on the branch, which is the opposite of what the carve-out is
# for — the carve-out exists so pipeline work is not obstructed, not so
# the protection can be switched off by choosing a branch name.
if echo "$BRANCH_NAME" | grep -qE '^ci/'; then
    echo "Exfiltration guard present and registered on HEAD. ${BRANCH_NAME} is a CI"
    echo "branch — the pipeline is the declared subject of the change, so edits to the"
    echo "guard's files are not compared against ${BASE_BRANCH}."
    emit_output false ci_branch
    exit 0
fi

# Everything below asks one question: what did THIS branch do to the
# guard since it diverged? That is a comparison against the merge base,
# never against the base branch's tip — the tip includes whatever landed
# on the base branch afterwards, and blaming a branch for those is the
# same mistake as blaming it for a check that could not run. The file
# loop already had this right through the three-dot diff, whose left side
# is the merge base by definition; MERGE_BASE is now named explicitly so
# the file and registration comparisons visibly share one reference
# point, which is what they failed to do.
CHANGED=""
for f in "${GUARD_FILES[@]}"; do
    # Not present at the merge base at all: this is the guard's first-time
    # introduction, not a modification of an established file. Nothing is
    # hidden by it — the whole file is visible as new content in the PR
    # diff, the same as any other addition. Once merged, every subsequent
    # change to it is caught below as usual.
    if ! git cat-file -e "${MERGE_BASE}:${f}" 2>/dev/null; then
        continue
    fi
    if git diff --name-only "${MERGE_BASE}..HEAD" -- "$f" | grep -q .; then
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

if git cat-file -e "${MERGE_BASE}:${SETTINGS_FILE}" 2>/dev/null; then
    BASE_REGISTRATION=$(registration_json "$MERGE_BASE")
    HEAD_REGISTRATION=$(registration_json HEAD)
    # "[]" means the adapter had no registration entry at all where this
    # branch started — this is the registration's first-time introduction,
    # not a removal or weakening of one that already existed, so it is not
    # flagged. Any change starting from an existing, non-empty
    # registration still is.
    if [ "$BASE_REGISTRATION" != "$HEAD_REGISTRATION" ] && [ "$BASE_REGISTRATION" != "[]" ]; then
        CHANGED="${CHANGED}  - ${SETTINGS_FILE} (the ${ADAPTER_NAME} registration entry)\n"
    fi
fi
# When settings.json does not exist at the merge base at all, there is no
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
