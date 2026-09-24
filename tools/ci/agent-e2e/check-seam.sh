#!/usr/bin/env bash
# ─── Police the enumerated seam ──────────────────────────────────────
#
# The end-to-end agent tests are worth something only in proportion to how
# little they replace. Every substitution is a piece of production behaviour
# the suite stops covering, and the cheapest way to turn a red end-to-end
# test green is to stub one more thing — a change that looks, in a diff, like
# a test being fixed.
#
# So the substitutions are enumerated in seam.txt, and this refuses any it
# finds that is not declared there. Growing the seam is allowed; growing it
# silently is not. The diff has to say so, next to the test it excuses.
#
# ── What this is and is not ─────────────────────────────────────────
#
# A token scan, not a parser. It cannot prove the absence of a stub: a
# determined author can write an indirection it does not recognise, and no
# amount of pattern-matching fixes that. What it does is make the obvious
# ways loud, so that evading it requires deliberate effort that reads as
# deliberate in review. That is the honest claim, and it is stated here
# rather than left for someone to discover by getting a stub past it.
#
# Usage:
#   check-seam.sh            check the end-to-end sources
#   check-seam.sh selftest   prove it flags a planted stub and passes clean
#                            sources (offline, no build)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SEAM_FILE="${SEAM_FILE:-${REPO_ROOT}/tools/ci/agent-e2e/seam.txt}"

# Sources that make up the end-to-end suite. A new e2e test file belongs
# here; one that is not listed is not policed, so the list is part of the
# guarantee rather than a convenience.
DEFAULT_SOURCES="flowtree/runtime/src/test/java/io/flowtree/jobs/AgentJobEndToEndTest.java"

die() { echo "::error::$*" >&2; exit 1; }

# Substitution indicators. Each is a literal token; finding one in an
# end-to-end source means that source replaced something, and the token must
# appear in seam.txt or this fails.
#
# Mockito and friends are the obvious ones. `setDryRun(true)` is here because
# it is the single highest-value thing an author could add: the job runs, the
# log fills with what it "would" commit, every assertion about the job's own
# state still holds, and nothing is ever pushed. An end-to-end suite in dry
# run is a suite that has stopped testing the only thing it exists for.
SUBSTITUTION_TOKENS="
Mockito
mockito
EasyMock
PowerMock
 mock(
 spy(
setDryRun(true)
fake-agent.sh
"

declared() {
    local token="$1" line rest
    while IFS= read -r line; do
        case "$line" in ''|'#'*) continue ;; esac
        rest="${line%%|*}"
        [ "$rest" = "$token" ] && return 0
    done < "$SEAM_FILE"
    return 1
}

# Returns 0 when every substitution found in the given sources is declared.
check_sources() {
    local sources="$1" violations=0 src token

    [ -f "$SEAM_FILE" ] || { echo "  no seam manifest at ${SEAM_FILE}"; return 1; }

    for src in $sources; do
        if [ ! -f "$src" ]; then
            echo "  listed end-to-end source is missing: ${src}"
            echo "  A source that vanished is not a source that passed."
            violations=$((violations + 1))
            continue
        fi

        while IFS= read -r token; do
            [ -z "$token" ] && continue
            if grep -qF -- "$token" "$src" 2>/dev/null; then
                if ! declared "$token"; then
                    echo "  UNDECLARED SUBSTITUTION in ${src}: '${token}'"
                    echo "  Either remove it, or add it to seam.txt with a justification"
                    echo "  that says what production behaviour stops being covered."
                    violations=$((violations + 1))
                fi
            fi
        done <<< "$SUBSTITUTION_TOKENS"

        # The suite must still be asserting against a real remote. This is
        # not a substitution, it is the property the seam exists to protect,
        # and it is cheap to check here rather than invent another script.
        if ! grep -qF -- '"--bare"' "$src" 2>/dev/null; then
            echo "  ${src} no longer creates a bare repository."
            echo "  The oracle of this suite is a real git remote; without one its"
            echo "  assertions can only be about the job's account of itself."
            violations=$((violations + 1))
        fi
    done

    [ "$violations" -eq 0 ]
}

if [ "${1:-check}" = "selftest" ]; then
    # Two-sided, like every other check here: it must pass clean sources and
    # flag a planted stub. A linter that only ever says yes is indistinguishable
    # from no linter, and that is the failure this whole directory is about.
    echo "Seam selftest: the check must accept the real sources and flag a planted stub."

    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT

    cd "$REPO_ROOT"
    if ! check_sources "$DEFAULT_SOURCES"; then
        die "SELFTEST FAILED: the real end-to-end sources do not pass. Fix them or the manifest before trusting this check."
    fi
    echo "  accepts the real sources"

    clean="$(printf '%s' "$DEFAULT_SOURCES" | awk '{print $1}')"
    planted="${scratch}/Planted.java"
    cp "$clean" "$planted"
    printf '\n// planted by the seam selftest\n// import org.mockito.Mockito;\n' >> "$planted"

    if check_sources "$planted" >/dev/null 2>&1; then
        die "SELFTEST FAILED: a planted Mockito reference was not flagged. This check cannot see a stub being added, which is the only thing it is for."
    fi
    echo "  flags a planted stub"

    # The highest-value single line an author could add. Under dry run the
    # job runs, the log says what it "would" commit, assertions about the
    # job's own state still hold, and nothing is pushed anywhere.
    dry="${scratch}/Dry.java"
    cp "$clean" "$dry"
    printf '\n// planted: job.setDryRun(true);\n' >> "$dry"
    if check_sources "$dry" >/dev/null 2>&1; then
        die "SELFTEST FAILED: a planted setDryRun(true) was not flagged. That one line turns the whole suite into an assertion about what the job intended."
    fi
    echo "  flags a planted dry run"

    # Removing the bare repository leaves the tests asserting on the job's
    # own account of itself, which is what they exist not to do.
    noremote="${scratch}/NoRemote.java"
    sed 's/"--bare"/"--not-bare"/g' "$clean" > "$noremote"
    if check_sources "$noremote" >/dev/null 2>&1; then
        die "SELFTEST FAILED: a source with no bare remote was treated as passing."
    fi
    echo "  flags the loss of the bare remote"

    missing="${scratch}/DoesNotExist.java"
    if check_sources "$missing" >/dev/null 2>&1; then
        die "SELFTEST FAILED: a missing source was treated as passing."
    fi
    echo "  flags a source that is missing"

    echo "::notice::Seam selftest passed"
    exit 0
fi

cd "$REPO_ROOT"
echo "Checking the enumerated seam"
if check_sources "$DEFAULT_SOURCES"; then
    echo "::notice::Every substitution in the end-to-end sources is declared in seam.txt"
    exit 0
fi
die "The end-to-end suite replaces something it has not declared. See tools/ci/agent-e2e/seam.txt."
