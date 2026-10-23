#!/usr/bin/env bash
# ─── Prove the end-to-end tests can fail ────────────────────────────
#
# Each fault in faults/ names a method of a fault-catalogue test class — today
# AgentJobFaultCatalogue — that runs one end-to-end scenario against a job
# carrying a deliberately broken collaborator. The fault declares the outcome
# that method must record. Normally that outcome is `fail`: the defect is
# installed and the scenario that exists to catch it must catch it. A fault
# whose method passes means the scenario no longer covers what it claims to,
# and this script exits non-zero.
#
# The defects live in test code, as subclasses of the production collaborators
# they break, installed through seams the job exposes. Nothing here edits a
# source file. A change to one of those collaborators that breaks its fault
# breaks compilation of the catalogue, in the same pull request, for the same
# author — which is why the faults are code and not patches.
#
# The pipeline runs the suite normally (must pass) and this script (every
# fault must produce its declared outcome). Together they say: the tests are
# green, and they are green for a reason.
#
# Rules this script follows about its own evidence, because it is the thing
# standing between a green pipeline and a fiction:
#
#   1. It never infers a result from an exit code. Maven exits 0 for a test
#      that does not exist, for a selector that matches nothing, and for a
#      run that was skipped — and here it exits non-zero on every healthy run,
#      because the faults are supposed to fail. The verdict comes from the
#      surefire XML: the named method must be present, must have run, and must
#      have recorded the declared outcome.
#
#   2. A failure counts as detection; an error does not. A fault is proven
#      when the scenario's own assertion fails (`<failure>`). An exception
#      (`<error>`) means the defect broke the scenario's machinery before any
#      assertion was reached — a double that throws, a timeout — which says
#      nothing about whether the assertion named for the defect would notice
#      it.
#
#   3. It is itself two-sided. A runner that reports "detected" no matter
#      what would keep this catalogue green forever while proving nothing.
#      So at least one fault must declare `outcome=pass`: a fault that must
#      NOT break its scenario. A runner stuck on "detected" fails that entry;
#      a runner stuck on "passed" fails every other one. See
#      faults/control-no-defect. `selftest` checks the verdict reader against
#      synthetic reports in both directions, offline.
#
#   4. The catalogue class and the fault directories must agree. Every
#      method the class ran must be claimed by a fault, and every fault's
#      method must have run, so neither side can gain or lose an entry
#      without the other.
#
# All faults that name the same module and class run in ONE Maven
# invocation: the module is built once and every scenario shares it.
#
# Usage:
#   run-fault-catalogue.sh [fault-name ...]   (default: every fault)
#   run-fault-catalogue.sh selftest           prove the verdict reader
#                                             discriminates (offline, no build)
#
# The modules the catalogue's module depends on must already be installed in
# the local Maven repository — in CI, `build`'s restored artifacts. Set
# MVN_OFFLINE= to let Maven reach the network; it defaults to offline.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
FAULTS_DIR="${FAULTS_DIR:-${REPO_ROOT}/tools/ci/agent-e2e/faults}"

# ── Verdict reader ──────────────────────────────────────────────────
#
# Prints, for each method named, one of: pass, failure, error, skipped,
# missing. Then prints "unclaimed <method>" for every testcase in the report
# that was not named. The report is the only evidence accepted.
read_verdicts() {
    local report="$1"; shift
    python3 - "$report" "$@" <<'PYEOF'
import re, sys
report, methods = sys.argv[1], sys.argv[2:]
xml = open(report, encoding="utf-8", errors="replace").read()
cases = {}
for m in re.finditer(r'<testcase\b([^>]*?)(/>|>(.*?)</testcase>)', xml, re.S):
    name = re.search(r'\bname="([^"]*)"', m.group(1))
    if not name:
        continue
    body = m.group(3) or ""
    if "<failure" in body:
        verdict = "failure"
    elif "<error" in body:
        verdict = "error"
    elif "<skipped" in body:
        verdict = "skipped"
    else:
        verdict = "pass"
    cases[name.group(1)] = verdict
for method in methods:
    print("%s %s" % (method, cases.get(method, "missing")))
for name in sorted(cases):
    if name not in methods:
        print("unclaimed %s" % name)
PYEOF
}

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

if [ "${1:-}" = "selftest" ]; then
    # Two-sided, like everything else here. A reader that called every
    # testcase a failure would certify every fault; one that called every
    # testcase a pass would certify the control. Both are checked, along with
    # the cases that must be neither: an error, a method that never ran, and
    # a method nobody claimed.
    echo "Fault-catalogue selftest: the verdict reader must tell every outcome apart."
    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT
    report="${scratch}/TEST-Synthetic.xml"
    cat > "$report" <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Synthetic" tests="5" failures="1" errors="1" skipped="1">
  <testcase name="passes" classname="Synthetic" time="0.1"/>
  <testcase name="fails" classname="Synthetic" time="0.1">
    <failure message="expected" type="java.lang.AssertionError">trace</failure>
  </testcase>
  <testcase name="throws" classname="Synthetic" time="0.1">
    <error message="boom" type="java.lang.IllegalStateException">trace</error>
  </testcase>
  <testcase name="skips" classname="Synthetic" time="0.0">
    <skipped/>
  </testcase>
  <testcase name="nobodyAsked" classname="Synthetic" time="0.1"/>
</testsuite>
XMLEOF
    got="$(read_verdicts "$report" passes fails throws skips neverRan)"
    want="passes pass
fails failure
throws error
skips skipped
neverRan missing
unclaimed nobodyAsked"
    if [ "$got" != "$want" ]; then
        echo "::error::SELFTEST FAILED: the verdict reader misread a synthetic report."
        echo "  expected:"; printf '%s\n' "$want" | sed 's/^/    /'
        echo "  got:";      printf '%s\n' "$got"  | sed 's/^/    /'
        exit 1
    fi
    echo "  reads pass, failure, error, skipped and missing as distinct verdicts"
    echo "  reports a testcase no fault claimed"
    echo "::notice::Fault-catalogue selftest passed"
    exit 0
fi

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
# what they would otherwise mean.
if [ "$#" -eq 0 ] && ! check_manifest; then
    echo
    echo "The catalogue does not match its manifest. Nothing was run."
    exit 1
fi

FAILURES=0
CHECKED=0
SAW_CONTROL=0

# fault name -> "module|class|method|outcome", for every well-formed entry.
entries=()
groups=""

for name in "${selected[@]}"; do
    dir="${FAULTS_DIR}/${name}"
    expect="${dir}/expect.txt"

    if [ ! -f "$expect" ] || [ ! -f "${dir}/why.md" ]; then
        echo "FAULT ${name}: missing expect.txt or why.md"
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
        FAILURES=$((FAILURES + 1))
        continue
    fi

    case "$outcome" in
        fail) ;;
        pass) SAW_CONTROL=1 ;;
        *)  echo "FAULT ${name}: outcome must be 'fail' (the usual: the defect"
            echo "  must break its scenario) or 'pass' (a control: it must not,"
            echo "  proving this runner can report a negative)."
            FAILURES=$((FAILURES + 1))
            continue ;;
    esac

    entries+=("${name}|${module}|${test_class}|${method}|${outcome}")
    case " ${groups} " in
        *" ${module}|${test_class} "*) ;;
        *) groups="${groups} ${module}|${test_class}" ;;
    esac
done

# ── One invocation per catalogue class ──────────────────────────────
for group in $groups; do
    module="${group%%|*}"
    test_class="${group#*|}"

    methods=()
    for entry in "${entries[@]}"; do
        IFS='|' read -r name e_module e_class method outcome <<< "$entry"
        [ "$e_module" = "$module" ] && [ "$e_class" = "$test_class" ] && methods+=("$method")
    done

    echo "───────────────────────────────────────────────────────────"
    echo "Running ${test_class} in ${module} (${#methods[@]} fault(s), one invocation)"

    reports="${REPO_ROOT}/${module}/target/surefire-reports"
    rm -f "${reports}/TEST-"*"${test_class}.xml" 2>/dev/null || true

    # The exit code is deliberately ignored: a healthy catalogue fails every
    # entry but the control. Checkstyle is skipped because it is not what is
    # being asked, and a style failure would surface as NO EVIDENCE below.
    (cd "$REPO_ROOT" && mvn ${MVN_OFFLINE--o} test -pl "$module" \
        -Dcheckstyle.skip=true -DAR_TEST_PROFILE=pipeline \
        -DAR_HARDWARE_NATIVE_DIRECT_BUFFERS=disabled \
        -Dtest="${test_class}" -Dsurefire.failIfNoSpecifiedTests=true) >"${TMPDIR:-/tmp}/fault-catalogue-mvn.log" 2>&1

    report="$(find "$reports" -name "TEST-*${test_class}.xml" 2>/dev/null | head -1)"
    if [ -z "$report" ] || [ ! -f "$report" ]; then
        echo "  NO EVIDENCE — no surefire report for ${test_class}."
        echo "  Nothing ran, so every fault in it is currently proving nothing."
        echo "  Last lines of the Maven log:"
        tail -30 "${TMPDIR:-/tmp}/fault-catalogue-mvn.log" | sed 's/^/    /'
        FAILURES=$((FAILURES + ${#methods[@]}))
        continue
    fi

    verdicts="$(read_verdicts "$report" "${methods[@]}")"

    # On a full run the class and the directories must agree. A catalogue
    # method nobody claims is a fault with no why.md and no MANIFEST entry —
    # a defect someone installed without saying what it models.
    if [ "$#" -eq 0 ]; then
        while read -r label unclaimed; do
            [ "$label" = "unclaimed" ] || continue
            echo "UNCLAIMED: ${test_class}#${unclaimed} ran, but no fault directory names it."
            echo "  Add faults/<name>/ with expect.txt and why.md, and list it in MANIFEST."
            FAILURES=$((FAILURES + 1))
        done <<< "$verdicts"
    fi

    for entry in "${entries[@]}"; do
        IFS='|' read -r name e_module e_class method outcome <<< "$entry"
        [ "$e_module" = "$module" ] && [ "$e_class" = "$test_class" ] || continue
        verdict="$(printf '%s\n' "$verdicts" | awk -v m="$method" '$1 == m {print $2; exit}')"
        CHECKED=$((CHECKED + 1))

        if [ "$outcome" = "fail" ]; then
            echo "FAULT ${name}: expecting ${test_class}#${method} to FAIL"
        else
            echo "CONTROL ${name}: expecting ${test_class}#${method} to PASS"
        fi

        case "${verdict}:${outcome}" in
            failure:fail)
                echo "  detected" ;;
            pass:pass)
                echo "  passed, as the control requires" ;;
            pass:fail)
                echo "  NOT DETECTED — the scenario passed with the fault installed."
                echo "  It does not cover the defect it is named for."
                sed 's/^/    /' "${FAULTS_DIR}/${name}/why.md" 2>/dev/null
                FAILURES=$((FAILURES + 1)) ;;
            error:*)
                echo "  ERRORED — the scenario threw rather than failing an assertion."
                echo "  The defect broke the machinery before anything was checked, so"
                echo "  this proves nothing about the assertion named for it."
                FAILURES=$((FAILURES + 1)) ;;
            failure:pass)
                echo "  CONTROL BROKEN — the scenario failed with only production"
                echo "  collaborators installed. Either a seam is not inert or the"
                echo "  scenario is flaky; until resolved, no 'detected' verdict from"
                echo "  this run is trustworthy."
                sed 's/^/    /' "${FAULTS_DIR}/${name}/why.md" 2>/dev/null
                FAILURES=$((FAILURES + 1)) ;;
            *)
                echo "  NO EVIDENCE — ${test_class} recorded ${verdict:-nothing} for ${method}."
                echo "  The method was renamed, removed or skipped; this entry proves nothing."
                FAILURES=$((FAILURES + 1)) ;;
        esac
    done
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
    echo "run, or the catalogue and its directories disagree. Each of those means"
    echo "the suite's green result claims more than it can support."
    exit 1
fi
