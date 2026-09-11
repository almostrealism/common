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

# run_case NAME EXPECTED_EXIT REPO [BASE]
run_case() {
    local name="$1" expected="$2" repo="$3" base="${4:-}"
    local actual=0
    (cd "$repo" && bash "$VERIFY" $base >/dev/null 2>&1) || actual=$?
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

r=$(make_repo)
run_case "no base branch: presence and registration only" 0 "$r"

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
