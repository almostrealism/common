#!/usr/bin/env bash
# ─── Prove the end-to-end tests can fail ────────────────────────────
#
# Applies each fault in faults/ to a scratch worktree, runs the test that
# fault names, and requires the outcome the fault declares. Normally that
# outcome is `fail`: the defect is reintroduced and the test that exists to
# catch it must catch it. A fault whose test passes means the test no longer
# covers what it claims to, and this script exits non-zero.
#
# The pipeline runs the suite normally (must pass) and this script (every
# fault must produce its declared outcome). Together they say: the tests are
# green, and they are green for a reason.
#
# Three rules this script follows about its own evidence, because it is the
# thing standing between a green pipeline and a fiction:
#
#   1. It never infers a result from an exit code. Maven exits 0 for a test
#      that does not exist, for a selector that matches nothing, and for a
#      run that was skipped. The first version of this script did exactly
#      that and reported a fault "undetected" when in truth no test had run
#      at all. A verification tool that passes when it verified nothing is
#      the defect it exists to find. So the verdict comes from the surefire
#      XML: the named test must be present, must have run, and must have
#      recorded the declared outcome.
#
#   2. It tests the working tree, not HEAD. Uncommitted work is the state a
#      change is actually in while it is being reviewed, and building the
#      worktree from HEAD silently excluded the very test being validated.
#
#   3. It is itself two-sided. A runner that reports "detected" no matter
#      what would keep this catalogue green forever while proving nothing —
#      the precise failure it exists to detect, one level up. So at least one
#      fault must declare `outcome=pass`: a patch that must NOT break its
#      test. A runner stuck on "detected" fails that entry; a runner stuck on
#      "passed" fails every other one. See faults/control-no-defect.
#
# Usage:
#   run-fault-catalogue.sh [fault-name ...]      (default: every fault)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
FAULTS_DIR="${REPO_ROOT}/tools/ci/agent-e2e/faults"
WORKTREE="${TMPDIR:-/tmp}/ar-fault-catalogue-$$"

cd "$REPO_ROOT"

selected=()
if [ "$#" -gt 0 ]; then
    for name in "$@"; do selected+=("$name"); done
else
    for dir in "$FAULTS_DIR"/*/; do
        [ -d "$dir" ] || continue
        selected+=("$(basename "$dir")")
    done
fi

if [ "${#selected[@]}" -eq 0 ]; then
    echo "No faults defined in ${FAULTS_DIR} — the end-to-end suite has no proof it can fail."
    echo "See faults/README.md."
    exit 1
fi

# ── The catalogue must be whole ─────────────────────────────────────
#
# Deleting a fault directory is a silent way to make this pass: fewer
# entries, all green, nothing in the output to say something used to be
# checked. It is the same move as deleting a failing test, and cheaper,
# because a fault has no obvious owner and its absence looks like it was
# never there. MANIFEST names every fault that must exist, so removing one
# takes an edit a reviewer sees.
#
# Only enforced on a full run; naming faults on the command line is a
# debugging aid.
check_manifest() {
    local manifest="${FAULTS_DIR}/MANIFEST" name missing=0 listed found
    if [ ! -f "$manifest" ]; then
        echo "No ${manifest}. Without it, a deleted fault is indistinguishable"
        echo "from one that never existed."
        return 1
    fi

    listed=""
    while IFS= read -r name; do
        name="$(printf '%s' "$name" | tr -d '[:space:]')"
        case "$name" in ''|'#'*) continue ;; esac
        listed="${listed} ${name}"
        if [ ! -d "${FAULTS_DIR}/${name}" ]; then
            echo "MISSING FAULT: ${name} is in MANIFEST but its directory is gone."
            echo "  If the defect is genuinely no longer expressible, remove it from"
            echo "  MANIFEST in the same change, so the loss is visible."
            missing=$((missing + 1))
        fi
    done < "$manifest"

    for dir in "$FAULTS_DIR"/*/; do
        [ -d "$dir" ] || continue
        found="$(basename "$dir")"
        case " ${listed} " in
            *" ${found} "*) ;;
            *)  echo "UNLISTED FAULT: ${found} exists but is not in MANIFEST."
                echo "  Add it, so the catalogue's contents and its manifest agree."
                missing=$((missing + 1)) ;;
        esac
    done

    [ "$missing" -eq 0 ]
}

# Short-circuits rather than folding into the final tally: if the catalogue
# is not the catalogue it claims to be, the results of running it do not mean
# what they would otherwise mean, and printing a row of "detected" underneath
# a missing entry invites reading past it.
if [ "$#" -eq 0 ] && ! check_manifest; then
    echo
    echo "The catalogue does not match its manifest. Nothing was run."
    exit 1
fi

expect_value() {
    local file="$1" key="$2"
    while IFS='=' read -r k v; do
        k="$(printf '%s' "$k" | tr -d '[:space:]')"
        if [ "$k" = "$key" ]; then
            printf '%s' "$v" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
            return 0
        fi
    done < "$file"
    return 1
}

cleanup() {
    cd "$REPO_ROOT"
    git worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ── The state under test is the working tree, not HEAD ──────────────
#
# A change is reviewed in the state it is in, which includes files not yet
# committed. `git stash create` covers tracked modifications but not new
# files, and a brand-new test is exactly what a new fault is usually paired
# with — building from HEAD silently excluded it and the run proved nothing.
# So the worktree starts at HEAD and every path git reports as changed,
# added or untracked is copied over it.
overlay_working_tree() {
    local dest="$1" status path
    while IFS= read -r status; do
        [ -z "$status" ] && continue
        path="${status:3}"
        # Renames report "old -> new"; the new path is what exists on disk.
        case "$path" in *" -> "*) path="${path##* -> }" ;; esac
        # Deletions have nothing to copy; the worktree already lacks them
        # only if they were never committed, so remove explicitly.
        if [ ! -e "$path" ]; then
            rm -f "${dest}/${path}" 2>/dev/null || true
            continue
        fi
        mkdir -p "${dest}/$(dirname "$path")" 2>/dev/null || true
        cp -R "$path" "${dest}/${path}" 2>/dev/null || true
    done < <(git status --porcelain -uall)
}

# One worktree, reused. Rebuilding it per fault meant a full compile of the
# module chain each time, which made every additional fault cost minutes —
# and a catalogue that is expensive to extend does not get extended. Between
# faults the tree is returned to the reviewed state: tracked files back to
# HEAD, untracked files removed, then the working tree overlaid again.
# `git clean -fd` without -x leaves ignored paths alone, so target/ survives
# and each subsequent build is incremental.
reset_worktree() {
    (cd "$WORKTREE" && git checkout -f --quiet -- . && git clean -qfd) || return 1
    overlay_working_tree "$WORKTREE"
}

if ! git worktree add --quiet --detach "$WORKTREE" HEAD; then
    echo "ERROR: could not create a scratch worktree"
    exit 1
fi
overlay_working_tree "$WORKTREE"

FAILURES=0
CHECKED=0
SAW_CONTROL=0

for name in "${selected[@]}"; do
    dir="${FAULTS_DIR}/${name}"
    patch="${dir}/fault.patch"
    expect="${dir}/expect.txt"

    if [ ! -f "$patch" ] || [ ! -f "$expect" ]; then
        echo "FAULT ${name}: missing fault.patch or expect.txt"
        FAILURES=$((FAILURES + 1))
        continue
    fi

    module="$(expect_value "$expect" module)" || module=""
    test_class="$(expect_value "$expect" test)" || test_class=""
    method="$(expect_value "$expect" method)" || method=""
    outcome="$(expect_value "$expect" outcome)" || outcome="fail"
    [ -n "$outcome" ] || outcome="fail"

    if [ -z "$module" ] || [ -z "$test_class" ] || [ -z "$method" ]; then
        echo "FAULT ${name}: expect.txt must set module, test and method."
        echo "  The method is required: a fault that breaks compilation, or breaks"
        echo "  some unrelated test, would otherwise satisfy 'something failed'."
        FAILURES=$((FAILURES + 1))
        continue
    fi

    case "$outcome" in
        fail) ;;
        pass) SAW_CONTROL=1 ;;
        *)  echo "FAULT ${name}: outcome must be 'fail' (the usual: the defect"
            echo "  must break its test) or 'pass' (a control: the patch must not"
            echo "  break it, proving this runner can report a negative)."
            FAILURES=$((FAILURES + 1))
            continue ;;
    esac

    echo "───────────────────────────────────────────────────────────"
    if [ "$outcome" = "fail" ]; then
        echo "FAULT ${name}: expecting ${test_class}#${method} to FAIL"
    else
        echo "CONTROL ${name}: expecting ${test_class}#${method} to PASS"
    fi

    if ! reset_worktree; then
        echo "  ERROR: could not return the scratch worktree to the reviewed state"
        FAILURES=$((FAILURES + 1))
        continue
    fi

    if ! (cd "$WORKTREE" && git apply "$patch" 2>&1); then
        echo "  ERROR: fault.patch no longer applies — the code it targets moved."
        echo "  Re-cut it against the current tree, or delete the fault if the"
        echo "  defect is no longer expressible."
        FAILURES=$((FAILURES + 1))
        continue
    fi

    CHECKED=$((CHECKED + 1))

    rm -rf "${WORKTREE}/${module}/target/surefire-reports" 2>/dev/null || true

    # Checkstyle is skipped deliberately. A fault patch is a deliberately
    # malformed piece of code; tripping a style rule would fail the build
    # before the test ran and be reported as NO EVIDENCE, which is true but
    # says nothing about the defect the fault models.
    (cd "$WORKTREE" && mvn -q ${MVN_OFFLINE--o} install -DskipTests \
        -Dcheckstyle.skip=true -pl "$module" -am) >/dev/null 2>&1
    (cd "$WORKTREE" && mvn ${MVN_OFFLINE--o} test -pl "$module" \
        -Dcheckstyle.skip=true -DAR_TEST_PROFILE=pipeline \
        -Dtest="${test_class}#${method}" -DfailIfNoTests=true) >/dev/null 2>&1

    # ── The verdict comes from the report, never the exit code ──────
    report="$(find "${WORKTREE}/${module}/target/surefire-reports" \
        -name "TEST-*${test_class}.xml" 2>/dev/null | head -1)"

    if [ -z "$report" ] || [ ! -f "$report" ]; then
        echo "  NO EVIDENCE — no surefire report for ${test_class}."
        echo "  The test did not run. That is not a pass and not a failure; it"
        echo "  means this fault is currently proving nothing."
        FAILURES=$((FAILURES + 1))
        continue
    fi

    # The failure/error element sits inside the named testcase element.
    python3 - "$report" "$method" <<'PYEOF'
import re, sys
report, method = sys.argv[1], sys.argv[2]
xml = open(report, encoding="utf-8", errors="replace").read()
# Each testcase is either self-closing (passed) or has a body (failed/errored).
pattern = r'<testcase[^>]*\bname="%s"[^>]*(/>|>(.*?)</testcase>)' % re.escape(method)
m = re.search(pattern, xml, re.S)
if not m:
    sys.exit(2)
body = m.group(2) or ""
sys.exit(0 if ("<failure" in body or "<error" in body) else 1)
PYEOF
    verdict=$?

    case "${verdict}:${outcome}" in
        0:fail)
            echo "  detected" ;;
        1:pass)
            echo "  passed, as the control requires" ;;
        1:fail)
            echo "  NOT DETECTED — ${test_class}#${method} passed with the fault applied."
            echo "  That test does not cover the defect it is named for."
            sed 's/^/    /' "${dir}/why.md" 2>/dev/null
            FAILURES=$((FAILURES + 1)) ;;
        0:pass)
            echo "  CONTROL BROKEN — ${test_class}#${method} failed under a patch that"
            echo "  should not affect it. Either the patch does more than it claims,"
            echo "  or the test is flaky. Until this is resolved, a 'detected' verdict"
            echo "  from this runner is not trustworthy."
            sed 's/^/    /' "${dir}/why.md" 2>/dev/null
            FAILURES=$((FAILURES + 1)) ;;
        *)
            echo "  NO EVIDENCE — ${test_class} ran but recorded no verdict for"
            echo "  ${method}. The method was renamed or removed; this entry proves"
            echo "  nothing."
            FAILURES=$((FAILURES + 1)) ;;
    esac
done

echo "───────────────────────────────────────────────────────────"
echo "Faults checked: ${CHECKED}    unproven: ${FAILURES}"

# A catalogue of nothing but positives cannot distinguish a working runner
# from one that reports "detected" unconditionally. Only enforced on a full
# run, since naming a single fault on the command line is a debugging aid.
if [ "$#" -eq 0 ] && [ "$SAW_CONTROL" -eq 0 ]; then
    echo
    echo "No fault declares outcome=pass. Without a control, this runner has no"
    echo "evidence it can report a negative, and every 'detected' above is"
    echo "unfalsifiable. See faults/control-no-defect."
    FAILURES=$((FAILURES + 1))
fi

if [ "$FAILURES" -gt 0 ]; then
    echo
    echo "A fault is unproven: it did not produce its declared outcome, did not"
    echo "run, or its patch no longer applies. Each of those means the suite's"
    echo "green result claims more than it can support."
    exit 1
fi
