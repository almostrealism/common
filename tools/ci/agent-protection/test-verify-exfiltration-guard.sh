#!/usr/bin/env bash
# ─── Regression tests for verify-exfiltration-guard.sh ─────────────
#
# Builds throwaway repositories that model master (guard present and
# registered) and PR branches that delete, unregister, narrow, or edit
# the guard, and asserts the exit code the verifier reports for each.
#
# Usage:
#   test-verify-exfiltration-guard.sh
#
# Exit codes:
#   0  - all tests passed
#   1  - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
VERIFY="$SCRIPT_DIR/verify-exfiltration-guard.sh"

PASS=0
FAIL=0
FAILED_TESTS=()

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

# Writes a settings.json registering the adapter with the given matcher
# (an empty matcher argument omits the guard entry entirely).
write_settings() {
    local repo="$1" matcher="$2"
    mkdir -p "$repo/.claude"
    if [ -z "$matcher" ]; then
        cat > "$repo/.claude/settings.json" <<'JSON'
{"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-git-commit.sh"}]}]}}
JSON
    else
        MATCHER="$matcher" python3 - "$repo/.claude/settings.json" <<'PY'
import json, os, sys
settings = {"hooks": {"PreToolUse": [
    {"matcher": "Bash", "hooks": [{"type": "command",
                                   "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-git-commit.sh"}]},
    {"matcher": os.environ["MATCHER"], "hooks": [{"type": "command",
                                                  "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-exfiltration.sh",
                                                  "description": "guard"}]},
]}}
with open(sys.argv[1], "w") as handle:
    json.dump(settings, handle, indent=2)
PY
    fi
}

# Creates a repository whose master has every guard file and a full
# registration, and leaves it checked out on a branch named "pr".
make_repo() {
    local repo
    repo=$(mktemp -d)
    git -C "$repo" init -q -b master
    git -C "$repo" config user.email guard@test
    git -C "$repo" config user.name guard
    git -C "$repo" config commit.gpgsign false
    for f in "${GUARD_FILES[@]}"; do
        mkdir -p "$repo/$(dirname "$f")"
        printf '# %s\n' "$f" > "$repo/$f"
    done
    write_settings "$repo" 'Artifact.*|SendUserFile|Bash'
    echo "prod" > "$repo/prod.txt"
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "master with guard"
    git -C "$repo" checkout -q -b pr
    echo "$repo"
}

commit_all() {
    git -C "$1" add -A
    git -C "$1" commit -q -m "$2"
}

# run_case NAME EXPECTED_EXIT REPO [BASE] [BRANCH_NAME]
run_case() {
    local name="$1" expected="$2" repo="$3" base="${4:-}" branch="${5:-}"
    local actual=0
    # An empty base must pass NO arguments: word-splitting an empty first
    # argument would slide the branch name into the base position, which
    # is the same mistake the workflow step had to be fixed for.
    if [ -n "$base" ]; then
        (cd "$repo" && bash "$VERIFY" "$base" "$branch" >/dev/null 2>&1) || actual=$?
    else
        (cd "$repo" && bash "$VERIFY" >/dev/null 2>&1) || actual=$?
    fi
    if [ "$actual" -eq "$expected" ]; then
        PASS=$((PASS + 1))
        echo "  PASS: $name (exit $actual)"
    else
        FAIL=$((FAIL + 1))
        FAILED_TESTS+=("$name")
        echo "  FAIL: $name (expected exit $expected, got $actual)"
    fi
    rm -rf "$repo"
}

echo "verify-exfiltration-guard.sh tests"

r=$(make_repo); echo "unrelated" >> "$r/prod.txt"; commit_all "$r" "prod change"
run_case "unrelated production change passes" 0 "$r" master

# ── The ci/... carve-out ────────────────────────────────────────────
#
# A branch named for the pipeline may change the files that enforce the
# pipeline — the same declaration validate-agent-commit.sh RULE 3 and the
# enforcement-tampering check already honour. Comparing its edits against
# the base polices nothing, since the same branch could delete the step
# that runs this script, and only blocks the work.
#
# The carve-out stops there. Presence and registration are not a claim
# about what this branch edited; they are what says the guard is in the
# tree an agent session will run with. Lifting those would let a branch
# name switch the runtime guard off for every session on the branch —
# the opposite of what the carve-out is for.

r=$(make_repo); echo "weakened" >> "$r/.claude/hooks/lib/exfiltration_guard_check.py"; commit_all "$r" "edit core on a ci branch"
run_case "edited core on a ci/ branch is allowed" 0 "$r" master ci/guard-work

r=$(make_repo); write_settings "$r" 'Artifact.*|SendUserFile|Bash|Read'; commit_all "$r" "reword registration on a ci branch"
run_case "reworded registration on a ci/ branch is allowed" 0 "$r" master ci/guard-work

r=$(make_repo); git -C "$r" rm -q .claude/hooks/block-exfiltration.sh; commit_all "$r" "delete adapter on a ci branch"
run_case "a ci/ branch still may not delete the adapter" 2 "$r" master ci/guard-work

r=$(make_repo); git -C "$r" rm -q .claude/hooks/lib/exfiltration_guard_check.py; commit_all "$r" "delete core on a ci branch"
run_case "a ci/ branch still may not delete the guard core" 2 "$r" master ci/guard-work

r=$(make_repo); write_settings "$r" ""; commit_all "$r" "unregister on a ci branch"
run_case "a ci/ branch still may not unregister the guard" 3 "$r" master ci/guard-work

r=$(make_repo); write_settings "$r" "Bash"; commit_all "$r" "narrow matcher on a ci branch"
run_case "a ci/ branch still may not narrow the matcher" 3 "$r" master ci/guard-work

# The exemption is the branch name, and nothing else grants it.
r=$(make_repo); echo "weakened" >> "$r/.claude/hooks/lib/exfiltration_guard_check.py"; commit_all "$r" "edit core"
run_case "the same edit on a feature branch is still blocked" 4 "$r" master feature/guard-work

r=$(make_repo); echo "weakened" >> "$r/.claude/hooks/lib/exfiltration_guard_check.py"; commit_all "$r" "edit core"
run_case "a branch merely containing ci/ in its name is not exempt" 4 "$r" master feature/ci/guard-work

# On the base branch itself there is no carve-out to apply: master must
# satisfy presence and registration whatever it is called.
r=$(make_repo); git -C "$r" rm -q .claude/hooks/block-exfiltration.sh; commit_all "$r" "delete adapter"
run_case "no base branch: the ci/ name grants nothing" 2 "$r" "" ci/guard-work

r=$(make_repo)
run_case "no base branch: presence and registration only" 0 "$r"

# ── What the BASE branch did is not the branch's doing ──────────────
#
# The file comparison uses a three-dot diff, which asks what this branch
# changed since it diverged. The registration comparison did not: it read
# the base branch's TIP, so a registration edit made on master after the
# branch point was reported as this branch modifying the guard. A branch
# that has touched nothing must pass no matter what master does behind it.

# Commits a change on master after the branch was cut, leaving pr untouched.
advance_master() {
    local repo="$1" what="$2"
    git -C "$repo" checkout -q master
    if [ "$what" = "registration" ]; then
        write_settings "$repo" 'Artifact.*|SendUserFile|Bash|Read'
    else
        echo "# tightened on master" >> "$repo/.claude/hooks/lib/exfiltration_guard_check.py"
    fi
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "change the guard on master"
    git -C "$repo" checkout -q pr
}

r=$(make_repo); advance_master "$r" registration
run_case "a registration change on master is not blamed on an untouched branch" 0 "$r" master

r=$(make_repo); advance_master "$r" file
run_case "a guard-file change on master is not blamed on an untouched branch" 0 "$r" master

r=$(make_repo); advance_master "$r" registration
echo "work" >> "$r/prod.txt"; commit_all "$r" "ordinary work on the branch"
run_case "nor when the branch has done unrelated work" 0 "$r" master

# The branch's own registration edit is still caught, master having moved
# or not — the comparison moves to the merge base, it does not go away.
r=$(make_repo); advance_master "$r" registration
write_settings "$r" "Bash"; commit_all "$r" "narrow the matcher on the branch"
run_case "the branch's own registration change is still blocked" 3 "$r" master

r=$(make_repo); advance_master "$r" file
write_settings "$r" 'Artifact.*|SendUserFile|Bash|Write'; commit_all "$r" "reword on the branch"
run_case "the branch's own reword is still blocked after master moved" 4 "$r" master

r=$(make_repo); git -C "$r" rm -q .claude/hooks/block-exfiltration.sh; commit_all "$r" "delete adapter"
run_case "deleted adapter is blocked" 2 "$r" master

r=$(make_repo); git -C "$r" rm -q .claude/hooks/exfil-allowlist.txt; commit_all "$r" "delete allowlist"
run_case "deleted allowlist is blocked" 2 "$r" master

r=$(make_repo); git -C "$r" rm -q .claude/hooks/lib/test_exfiltration_guard_check.py; commit_all "$r" "delete tests"
run_case "deleted hook tests are blocked" 2 "$r" master

r=$(make_repo); git -C "$r" rm -q tools/ci/agent-protection/exfil_guard_registration.py; commit_all "$r" "delete registration helper"
run_case "deleted registration helper is blocked" 2 "$r" master

r=$(make_repo); echo "x" >> "$r/tools/ci/agent-protection/exfil_guard_registration.py"; commit_all "$r" "edit registration helper"
run_case "edited registration helper is blocked" 4 "$r" master

r=$(make_repo); write_settings "$r" ""; commit_all "$r" "unregister"
run_case "removed registration is blocked" 3 "$r" master

r=$(make_repo); write_settings "$r" "Bash"; commit_all "$r" "narrow matcher"
run_case "matcher narrowed to Bash only is blocked" 3 "$r" master

r=$(make_repo); write_settings "$r" "Artifact|Bash"; commit_all "$r" "drop SendUserFile"
run_case "matcher without SendUserFile is blocked" 3 "$r" master

r=$(make_repo); echo "weakened" >> "$r/.claude/hooks/lib/exfiltration_guard_check.py"; commit_all "$r" "edit core"
run_case "edited core is blocked" 4 "$r" master

r=$(make_repo); echo "evil.example" >> "$r/.claude/hooks/exfil-allowlist.txt"; commit_all "$r" "edit allowlist"
run_case "edited allowlist is blocked" 4 "$r" master

r=$(make_repo); echo "x" >> "$r/tools/ci/agent-protection/verify-exfiltration-guard.sh"; commit_all "$r" "edit verifier"
run_case "edited verifier is blocked" 4 "$r" master

r=$(make_repo); write_settings "$r" 'Artifact.*|SendUserFile|Bash|Read'; commit_all "$r" "reword registration"
run_case "reworded registration entry is blocked" 4 "$r" master

r=$(make_repo); echo "weakened" >> "$r/.claude/hooks/lib/exfiltration_guard_check.py"
run_case "working-tree-only edit does not count (HEAD is what ships)" 0 "$r" master

r=$(make_repo)
python3 - "$r/.claude/settings.json" <<'PY'
import json, sys
path = sys.argv[1]
settings = json.load(open(path))
settings["hooks"]["PostToolUse"] = [{"matcher": "Read", "hooks": [{"type": "command", "command": "x.sh"}]}]
json.dump(settings, open(path, "w"), indent=2)
PY
commit_all "$r" "unrelated settings change"
run_case "unrelated settings.json change passes" 0 "$r" master

r=$(make_repo)
MATCHER='Artifact.*|SendUserFile|Bash' python3 - "$r/.claude/settings.json" <<'PY'
import json, os, sys
settings = {"hooks": {"PreToolUse": [
    {"matcher": "Bash", "hooks": [{"type": "command",
                                   "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-git-commit.sh"}]},
    {"matcher": os.environ["MATCHER"], "hooks": [{"type": "command",
                                                  "command": "echo block-exfiltration.sh",
                                                  "description": "decoy"}]},
]}}
with open(sys.argv[1], "w") as handle:
    json.dump(settings, handle, indent=2)
PY
commit_all "$r" "decoy registration"
run_case "command that only mentions the adapter name is not a real registration" 3 "$r" master

r=$(mktemp -d)
git -C "$r" init -q -b master
git -C "$r" config user.email guard@test
git -C "$r" config user.name guard
git -C "$r" config commit.gpgsign false
echo "prod" > "$r/prod.txt"
git -C "$r" add -A
git -C "$r" commit -q -m "master without the guard"
git -C "$r" checkout -q -b pr
for f in "${GUARD_FILES[@]}"; do
    mkdir -p "$r/$(dirname "$f")"
    printf '# %s\n' "$f" > "$r/$f"
done
write_settings "$r" 'Artifact.*|SendUserFile|Bash'
commit_all "$r" "introduce the guard for the first time"
run_case "first-time introduction of the guard on a PR branch is not flagged as tampering" 0 "$r" master

# ── Branches cut before the guard existed ───────────────────────────
#
# The guard's absence on HEAD has two possible causes, and they are
# opposite: the branch removed it, or the branch is older than it. Both
# look identical on HEAD, so the merge base decides. Reporting the second
# as the first accuses an untouched branch of disabling the guard — and
# that accusation is what dispatches an agent to "fix" it.

make_predating_repo() {
    local repo
    repo=$(mktemp -d)
    git -C "$repo" init -q -b master
    git -C "$repo" config user.email guard@test
    git -C "$repo" config user.name guard
    git -C "$repo" config commit.gpgsign false
    echo "prod" > "$repo/prod.txt"
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "master before the guard existed"
    # The branch is cut here, from a tree that has no guard in it.
    git -C "$repo" checkout -q -b pr
    git -C "$repo" checkout -q master
    for f in "${GUARD_FILES[@]}"; do
        mkdir -p "$repo/$(dirname "$f")"
        printf '# %s\n' "$f" > "$repo/$f"
    done
    write_settings "$repo" 'Artifact.*|SendUserFile|Bash'
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "the guard lands on master"
    git -C "$repo" checkout -q pr
    echo "$repo"
}

r=$(make_predating_repo)
run_case "branch cut before the guard existed is not applicable, not a violation" 5 "$r" master

r=$(make_predating_repo); echo "work" >> "$r/prod.txt"; commit_all "$r" "ordinary work on the old branch"
run_case "an old branch doing ordinary work is still not applicable" 5 "$r" master

# Merging the base branch brings the guard in, and from that commit on the
# branch is held to it like any other.
r=$(make_predating_repo); git -C "$r" merge -q --no-edit master
run_case "once the base is merged the guard is verified again" 0 "$r" master

r=$(make_predating_repo); git -C "$r" merge -q --no-edit master
git -C "$r" rm -q .claude/hooks/block-exfiltration.sh; commit_all "$r" "delete the adapter after merging"
run_case "deleting the guard after merging it in is still blocked" 2 "$r" master

# Both endpoints can be empty while a deletion sits between them: an old
# branch that introduces the guard and then removes it again looks, at
# HEAD and at the merge base, exactly like one that never had it. The
# branch's own history is what separates them.
r=$(make_predating_repo)
for f in "${GUARD_FILES[@]}"; do
    mkdir -p "$r/$(dirname "$f")"
    printf '# %s\n' "$f" > "$r/$f"
done
write_settings "$r" 'Artifact.*|SendUserFile|Bash'
commit_all "$r" "introduce the guard on the old branch"
git -C "$r" rm -q .claude/hooks/block-exfiltration.sh
commit_all "$r" "and delete it again"
run_case "introducing the guard then deleting it is blocked, not treated as predating" 2 "$r" master

# The real repository must satisfy CHECK 1 and CHECK 2 on HEAD once the
# guard is committed; before that first commit the files are only staged
# and HEAD legitimately lacks them, so this case is informational.
if git -C "$REPO_ROOT" cat-file -e "HEAD:.claude/hooks/block-exfiltration.sh" 2>/dev/null; then
    actual=0
    (cd "$REPO_ROOT" && bash "$VERIFY" >/dev/null 2>&1) || actual=$?
    if [ "$actual" -eq 0 ]; then
        PASS=$((PASS + 1)); echo "  PASS: real repository HEAD passes presence + registration"
    else
        FAIL=$((FAIL + 1)); FAILED_TESTS+=("real repository HEAD"); echo "  FAIL: real repository HEAD (exit $actual)"
    fi
else
    echo "  SKIP: real repository HEAD does not carry the guard yet (not committed)"
fi

echo ""
echo "Passed: $PASS  Failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf '  failed: %s\n' "${FAILED_TESTS[@]}"
    exit 1
fi
exit 0
