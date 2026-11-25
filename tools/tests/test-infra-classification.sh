#!/usr/bin/env bash
# ─── Standalone test for infrastructure-failure classification ───────────
#
# Checks tools/ci/infra-failure.sh's INFRA_SIGNATURES against real log samples,
# without the GitHub API. The python-tests job runs it (as the "Run
# infrastructure classifier tests" step), gated on infra_classifier_changed so a
# change to the regex or the retry gate triggers it; run it directly too:
#
#   bash tools/tests/test-infra-classification.sh
#
# Broader verification (the retry gate and auto-review wiring) is in
# tools/tests/test_remediation_job_exclusivity.py, run by the same job.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLASSIFY="$REPO_ROOT/tools/ci/infra-failure.sh"

failures=0

# expect <infra|deterministic> <description> <<< "log text"
expect() {
    local want="$1" desc="$2" got rc
    rc=0
    got=$(bash "$CLASSIFY") || rc=$?
    if [ "$got" != "$want" ]; then
        echo "FAIL: $desc — classified as '$got', expected '$want'"
        failures=$(( failures + 1 ))
        return
    fi
    # "infra" exits 0, "deterministic" exits 1: the exit code must agree.
    if { [ "$want" = "infra" ] && [ "$rc" != "0" ]; } || \
       { [ "$want" = "deterministic" ] && [ "$rc" = "0" ]; }; then
        echo "FAIL: $desc — exit code $rc does not match '$want'"
        failures=$(( failures + 1 ))
        return
    fi
    echo "ok: $desc"
}

# The os-maven-plugin failure that left PR #1058 stuck (an infrastructure
# failure that was misclassified as deterministic).
expect infra "os-maven-plugin artifact descriptor could not be read" <<'LOG'
[ERROR] Error executing Maven.
[ERROR] 1 problem was encountered while building the effective settings
[ERROR] Unresolveable build extension: Plugin kr.motd.maven:os-maven-plugin:1.6.0 or one of its dependencies could not be resolved: Failed to read artifact descriptor for kr.motd.maven:os-maven-plugin:jar:1.6.0
LOG

# A genuine test-timeout-check violation: deterministic, the commit's fault.
expect deterministic "a real @Test-missing-timeout violation" <<'LOG'
[ERROR] CodePolicyEnforcementTest.enforceTestTimeouts:42 The following @Test methods are missing a timeout parameter:
[ERROR]   org.almostrealism.foo.FooTest.bar
[ERROR]   org.almostrealism.foo.FooTest.baz
LOG

# A dropped connection: infrastructure.
expect infra "a reset connection" <<'LOG'
Caused by: java.net.SocketException: Connection reset
LOG

# A lost self-hosted runner: infrastructure.
expect infra "a runner that lost communication" <<'LOG'
The self-hosted runner: ar-ci-1 lost communication with the server. Verify the machine is running and has a healthy network connection.
LOG

# A reclaimed GitHub-hosted runner: infrastructure.
expect infra "a runner shutdown signal" <<'LOG'
##[error]The runner has received a shutdown signal. This can happen when the runner service is stopped, or a manually started runner is canceled.
##[error]The operation was canceled.
LOG

# A registry rate limit (image pull): infrastructure.
expect infra "a Docker registry rate limit" <<'LOG'
toomanyrequests: You have reached your pull rate limit.
LOG

# A real compile error: deterministic, the commit's fault.
expect deterministic "a real compile error" <<'LOG'
[ERROR] /src/main/java/Foo.java:[10,5] cannot find symbol
[ERROR]   symbol:   method doesNotExist()
LOG

# A JVM fork death is deliberately NOT infrastructure (it can be a real crash).
expect deterministic "a forked VM termination (not treated as infra)" <<'LOG'
[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
LOG

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed"
    exit 1
fi
echo "All classification tests passed"
