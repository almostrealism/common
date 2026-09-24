#!/usr/bin/env bash
# ─── Is the running agent the build that was merged? ─────────────────
#
# An agent host runs its own installed copy. Merging to master does not
# update it, and nothing in the pipeline previously compared the two. "It
# was merged and deployed and nothing changed" is what a stale install feels
# like from the outside — indistinguishable, without this check, from a fix
# that did not work.
#
# `install.sh` writes BUILD_PROVENANCE into the staging directory, so it is
# swapped into place by the same mv as the JARs it describes. This script
# reads it back and requires it to name the commit that was deployed.
#
# ── What this can and cannot establish ──────────────────────────────
#
# It proves the installer ran on a given commit, from a clean tree, and that
# it compiled rather than reusing whatever JARs were lying around. It does
# NOT prove the JARs were compiled from that commit; only a reproducible
# build proves that, and this is not one. The gap is real and is recorded
# here rather than papered over — what it catches is the realistic failure,
# an install from a stale or dirty checkout.
#
# ── Why the canary does not make this redundant ─────────────────────
#
# The post-deploy canary asks whether the fleet can publish a commit. A
# fleet running last week's build can do that perfectly well. The canary
# would pass, and the deploy would still not contain the change it was run
# for.
#
# Usage:
#   verify-deployed-build.sh verify     compare the install against a commit
#   verify-deployed-build.sh selftest   prove this check can both pass and
#                                       fail, against scratch fixtures
#
# Environment:
#   FLOWTREE_AGENT_HOME  install root (default ~/flowtree-agent)
#   PROVENANCE_FILE      override the file read (default $AGENT_HOME/lib/BUILD_PROVENANCE)
#   EXPECTED_SHA         commit the install must name (default: HEAD here)
#   ALLOW_DIRTY          set to "true" to tolerate clean=false (default: no)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

die() { echo "::error::$*" >&2; exit 1; }

provenance_value() {
    local file="$1" key="$2" line
    while IFS= read -r line; do
        case "$line" in
            "${key}="*) printf '%s' "${line#*=}"; return 0 ;;
        esac
    done < "$file"
    return 1
}

# Returns 0 when the install described by $1 is the build named by $2.
# Every rejection prints why; a check that fails without saying which
# property failed sends the reader to the wrong place.
verify_provenance() {
    local file="$1" expected="$2"
    local sha clean built

    if [ ! -f "$file" ]; then
        echo "  no BUILD_PROVENANCE at ${file}"
        echo "  Either the install predates provenance recording, or it did not"
        echo "  complete. Neither is a pass: what is running there is unknown."
        return 1
    fi

    sha="$(provenance_value "$file" sha)" || sha=""
    clean="$(provenance_value "$file" clean)" || clean=""
    built="$(provenance_value "$file" built)" || built=""

    if [ -z "$sha" ] || [ "$sha" = "unknown" ]; then
        echo "  BUILD_PROVENANCE names no commit (sha=${sha:-<absent>})."
        return 1
    fi

    if [ "$sha" != "$expected" ]; then
        echo "  STALE DEPLOY — the install names ${sha}"
        echo "  but the build under test is ${expected}."
        echo "  The fleet is running code that is not what was merged."
        return 1
    fi

    if [ "$built" != "true" ]; then
        echo "  the install ran with --no-build (built=${built:-<absent>})."
        echo "  Its JARs may predate ${sha} entirely, so the commit it names"
        echo "  does not describe the binary that is running."
        return 1
    fi

    if [ "$clean" != "true" ] && [ "${ALLOW_DIRTY:-}" != "true" ]; then
        echo "  the install was built from a dirty tree (clean=${clean:-<absent>})."
        echo "  The commit it names does not describe what was compiled."
        return 1
    fi

    return 0
}

case "${1:-verify}" in
verify)
    agent_home="${FLOWTREE_AGENT_HOME:-${HOME}/flowtree-agent}"
    file="${PROVENANCE_FILE:-${agent_home}/lib/BUILD_PROVENANCE}"
    expected="${EXPECTED_SHA:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)}"

    [ -n "$expected" ] || die "no EXPECTED_SHA and no git HEAD to compare against"

    echo "Checking the deployed build against ${expected}"
    if verify_provenance "$file" "$expected"; then
        echo "::notice::The installed agent was built from ${expected}, clean"
        exit 0
    fi
    die "The deployed agent does not match the build under test. Treat this deploy as not having happened."
    ;;

selftest)
    # Offline, no install required. Both directions, for the same reason the
    # fault catalogue has a control: a check that only ever rejects fails
    # every deploy, and one that only ever accepts certifies a stale fleet
    # forever. The second is the dangerous one and is checked first.
    echo "Deployed-build selftest: the check must accept a matching install and reject every other shape."

    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT
    good_sha="0123456789abcdef0123456789abcdef01234567"
    other_sha="fedcba9876543210fedcba9876543210fedcba98"

    write_provenance() {
        printf 'sha=%s\nbranch=master\nclean=%s\nbuilt=%s\ninstalled_at=x\ninstalled_by=y\nhost=z\n' \
            "$1" "$2" "$3" > "${scratch}/$4"
    }

    write_provenance "$good_sha"  true  true  matching
    write_provenance "$other_sha" true  true  stale
    write_provenance "$good_sha"  false true  dirty
    write_provenance "$good_sha"  true  false nobuild

    failures=0
    expect_accept() {
        if verify_provenance "${scratch}/$1" "$good_sha"; then
            echo "  accepts ${1}"
        else
            echo "  SELFTEST FAILED: rejected ${1}, which it must accept."
            echo "  A check in this state fails every deploy whether or not the fleet is stale."
            failures=$((failures + 1))
        fi
    }
    expect_reject() {
        if verify_provenance "${scratch}/$1" "$good_sha" >/dev/null; then
            echo "  SELFTEST FAILED: accepted ${1}, which it must reject."
            echo "  A check in this state certifies a bad deploy as a good one."
            failures=$((failures + 1))
        else
            echo "  rejects ${1}"
        fi
    }

    expect_accept matching
    expect_reject stale
    expect_reject dirty
    expect_reject nobuild
    expect_reject absent-file

    [ "$failures" -eq 0 ] || die "The deployed-build check does not discriminate; its verdicts mean nothing."
    echo "::notice::Deployed-build selftest passed"
    exit 0
    ;;

*)  die "Usage: $0 [verify|selftest]" ;;
esac
