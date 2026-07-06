#!/usr/bin/env bash
# PreToolUse — Edit/MultiEdit/Write on test files: detect test-WEAKENING
# transitions by comparing the OLD vs NEW text of the change in real time.
#
# This is a per-edit, in-session mirror of
# tools/ci/agent-protection/detect-test-hiding.sh (which only sees committed
# diffs vs a base branch). The point is that a weakening becomes LOUD in the
# user-visible transcript — and is blocked for the unambiguous cases — BEFORE
# it is committed, so it cannot happen quietly under pressure.
#
# It complements, and does not duplicate, the existing
#   warn-defensive-guard.sh   (production-code tolerance patterns)
#   warn-assertion-free-test.sh (new @Test with no assertion)
#
# Severity:
#   exit 2 (BLOCK) — unambiguous deception: @TestDepth escalated across the
#                    CI depth (9), @Ignore/@Disabled added to existing code,
#                    assertEquals tolerance widened > 2x.
#   exit 0 (WARN)  — softer signals: assertion-count drop, skipLongTests
#                    added, smaller tolerance/timeout increases.
#
# Authored as an explicit self-binding protection (the agent will not be held
# to verbal promises; a hook fires automatically and disabling it is a
# visible act guarded by guard-enforcement-files.sh).

set -u

INPUT=$(cat)

HOOK_JSON="$INPUT" python3 <<'PY'
import os, json, re, sys

def w(x): print(x, file=sys.stderr)

try:
    data = json.loads(os.environ.get("HOOK_JSON", ""))
except Exception:
    sys.exit(0)

ti = data.get("tool_input", {}) or {}
tool = data.get("tool_name", "") or ""
path = ti.get("file_path", "") or ""

base = os.path.basename(path)
is_test = path.endswith(".java") and (
    re.search(r"(Test|Tests|IT)\.java$", base) or "/src/test/" in path
)
if not is_test:
    sys.exit(0)

# Collect (old, new) text. For Edit/MultiEdit the old_string is, by
# definition, existing code being modified. For Write the file on disk is
# still the pre-write content during PreToolUse.
pairs = []
existing_context = tool in ("Edit", "MultiEdit")
if tool == "Edit":
    pairs.append((ti.get("old_string", "") or "", ti.get("new_string", "") or ""))
elif tool == "MultiEdit":
    for e in ti.get("edits", []) or []:
        pairs.append((e.get("old_string", "") or "", e.get("new_string", "") or ""))
elif tool == "Write":
    new = ti.get("content", "") or ""
    old = ""
    try:
        with open(path) as f:
            old = f.read()
    except Exception:
        old = ""
    existing_context = bool(old.strip())  # Write over an existing file
    pairs.append((old, new))
else:
    sys.exit(0)

old = "\n".join(p[0] for p in pairs)
new = "\n".join(p[1] for p in pairs)

strong, soft = [], []

def ints(pat, s):
    return [int(x) for x in re.findall(pat, s)]

# 1. @TestDepth escalation
od, nd = ints(r"@TestDepth\(\s*(\d+)", old), ints(r"@TestDepth\(\s*(\d+)", new)
omax = max(od) if od else None
nmax = max(nd) if nd else None
if nmax is not None and omax is not None and nmax > omax:
    if nmax >= 9 and omax < 9:
        strong.append(f"@TestDepth escalated {omax} -> {nmax} (CI runs depth 9; this hides the test from CI)")
    else:
        soft.append(f"@TestDepth increased {omax} -> {nmax}")
elif nmax is not None and omax is None and nmax >= 9:
    soft.append(f"@TestDepth({nmax}) added (>= CI depth 9) — confirm this is a NEW test, not hiding an existing one")

# 2. @Ignore / @Disabled added
for ann in ("Ignore", "Disabled"):
    if new.count("@" + ann) > old.count("@" + ann):
        msg = f"added @{ann} (disables a test)"
        (strong if existing_context else soft).append(msg)

# 3. skipLongTests guard added
if new.count("skipLongTests") > old.count("skipLongTests"):
    soft.append("added skipLongTests guard (skips under default depth)")

# 4. assertion-count drop
def acount(s):
    return len(re.findall(r"\bassert[A-Za-z]*\s*\(|\bverify\s*\(|\bfail\s*\(", s))
oa, na = acount(old), acount(new)
if existing_context and na < oa:
    soft.append(f"assertion/verify count dropped {oa} -> {na} (weakened verification?)")

# 5. assertEquals/assertArrayEquals tolerance widening (3-arg delta form)
def tols(s):
    out = []
    for m in re.finditer(r"assert(?:Equals|ArrayEquals)\s*\((.*?)\)\s*;", s, re.S):
        nums = re.findall(r"\d+\.\d+(?:[eE][-+]?\d+)?|\d+(?:[eE][-+]?\d+)?", m.group(1))
        if nums:
            try:
                out.append(float(nums[-1]))
            except Exception:
                pass
    return out
ot, nt = tols(old), tols(new)
if ot and nt and max(nt) > max(ot) * 2:
    strong.append(f"assertEquals tolerance widened > 2x (max {max(ot)} -> {max(nt)})")
elif ot and nt and max(nt) > max(ot):
    soft.append(f"assertEquals tolerance increased (max {max(ot)} -> {max(nt)})")

# 6. @Test(timeout=) inflation
otmo, ntmo = ints(r"timeout\s*=\s*(\d+)", old), ints(r"timeout\s*=\s*(\d+)", new)
if otmo and ntmo and max(ntmo) > max(otmo) * 2:
    soft.append(f"@Test timeout inflated (max {max(otmo)} -> {max(ntmo)})")

if not strong and not soft:
    sys.exit(0)

w("")
w("==================== TEST-WEAKENING PATTERN DETECTED ====================")
w(f" File: {path}")
w(" These transitions match documented agent deception patterns")
w(" (CLAUDE.md 'Known Deception Patterns'; tools/ci/agent-protection/detect-test-hiding.sh).")
for x in strong:
    w("  [BLOCK] " + x)
for x in soft:
    w("  [warn]  " + x)
w("")
w(" Legitimate change (test split, real spec change)? Say so explicitly and proceed.")
w(" Making a failing test pass by weakening it? STOP — fix the production code.")
w("========================================================================")
w("")

sys.exit(2 if strong else 0)
PY
rc=$?
exit $rc
