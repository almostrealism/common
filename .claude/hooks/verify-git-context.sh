#!/usr/bin/env bash
# PreToolUse — ar-manager memory/context tools: check the repo_url and branch
# arguments against live git before the call leaves this machine.
#
# ar-manager runs in a container and cannot see this working directory, so it
# trusts whatever repo_url/branch the caller sends. That trust is fine for
# authorisation (the token decides what is allowed) but not for accuracy: the
# model supplying those values routinely reads a stale branch name out of the
# harness-injected gitStatus snapshot, or out of a value cached near the top of
# a long context window, and names a branch that was switched many tool calls
# ago. See docs/plans/MANAGER_CONSULTANT_CONSOLIDATION.md §4.1.
#
# Calibrated by consequence:
#   memory_store          a write onto the wrong branch corrupts the corpus  → BLOCK
#   reads (recall, etc.)  wrong branch returns the wrong context             → WARN
#
# Reads are never blocked: asking about another repo or branch is legitimate
# (scope="all" exists for it). The hook stays silent when the argument is
# absent (the server's own resolution applies) or when this is not a git repo.
#
# Exit 0 → allow (stderr, if any, is shown as context)
# Exit 2 → BLOCK (stderr shown to the model as the reason)
set -euo pipefail

INPUT=$(cat)

LIVE_REMOTE=$(git remote get-url origin 2>/dev/null || true)
LIVE_BRANCH=$(git branch --show-current 2>/dev/null || true)

RESULT=$(FLOWTREE_HOOK_INPUT="$INPUT" \
         FLOWTREE_LIVE_REMOTE="$LIVE_REMOTE" \
         FLOWTREE_LIVE_BRANCH="$LIVE_BRANCH" python3 <<'PYEOF'
import os, json, re, sys

raw = os.environ.get("FLOWTREE_HOOK_INPUT", "")
try:
    data = json.loads(raw)
except Exception:
    print("ALLOW")
    sys.exit(0)

tool = data.get("tool_name", "") or ""
ti = data.get("tool_input", {}) or {}

# The connector prefix varies by how ar-manager is registered
# (mcp__ar-manager__… vs mcp__claude_ai_ar-manager__…), so match on the
# server-and-tool suffix rather than a fixed prefix.
m = re.match(r"^mcp__.*ar-manager__(\w+)$", tool)
if not m:
    print("ALLOW")
    sys.exit(0)

name = m.group(1)
WRITES = {"memory_store"}
READS = {"memory_recall", "workstream_context"}
if name not in WRITES and name not in READS:
    print("ALLOW")
    sys.exit(0)

live_remote = os.environ.get("FLOWTREE_LIVE_REMOTE", "").strip()
live_branch = os.environ.get("FLOWTREE_LIVE_BRANCH", "").strip()
if not live_remote and not live_branch:
    print("ALLOW")          # not a git working directory
    sys.exit(0)


def owner_repo(url):
    """Normalise a git remote to 'owner/repo' so the git@/https/.git
    spellings of the same repository compare equal."""
    if not url:
        return None
    m = re.search(r"[:/]([^/:]+)/([^/]+?)(?:\.git)?/?$", url.strip())
    return f"{m.group(1)}/{m.group(2)}".lower() if m else None


problems = []

supplied_repo = (ti.get("repo_url") or "").strip()
if supplied_repo:
    want, got = owner_repo(live_remote), owner_repo(supplied_repo)
    if want and got and want != got:
        problems.append(("repo_url", supplied_repo, live_remote))

supplied_branch = (ti.get("branch") or "").strip()
if supplied_branch and live_branch and supplied_branch != live_branch:
    problems.append(("branch", supplied_branch, live_branch))

if not problems:
    print("ALLOW")
    sys.exit(0)

# A caller that explicitly widened the search is not making a mistake.
if name == "memory_recall" and (ti.get("scope") or "") == "all":
    print("ALLOW")
    sys.exit(0)

print(json.dumps({
    "verdict": "BLOCK" if name in WRITES else "WARN",
    "tool": name,
    "problems": problems,
    "live_remote": live_remote,
    "live_branch": live_branch,
}))
PYEOF
)

[ "$RESULT" = "ALLOW" ] && exit 0

VERDICT=$(echo "$RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin)['verdict'])")
DETAIL=$(echo "$RESULT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for field, supplied, live in d['problems']:
    print(f'  {field}: you sent {supplied!r}, this working tree is {live!r}')
")
LIVE=$(echo "$RESULT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f\"  repo_url = {d['live_remote']}\")
print(f\"  branch   = {d['live_branch']}\")
")

if [ "$VERDICT" = "BLOCK" ]; then
    cat >&2 <<EOF
BLOCKED: the git context you supplied does not match this working tree.

${DETAIL}

ar-manager runs remotely and cannot check this — it stores exactly what you
send. Writing to the wrong branch puts the memory where nobody will find it.

The live values, read from git just now:
${LIVE}

Re-issue the call with those values. If you genuinely mean to write against a
different branch, say so explicitly in your message first so the intent is on
record.
EOF
    exit 2
fi

cat >&2 <<EOF
[ar-hooks/verify-git-context] The git context you supplied does not match this
working tree, so this read may return another branch's context.

${DETAIL}

Live values, read from git just now:
${LIVE}

Allowing the call — reading across repos/branches is sometimes intended (use
scope="all" to search everything). Re-issue with the live values if this was a
mistake.
EOF
exit 0
