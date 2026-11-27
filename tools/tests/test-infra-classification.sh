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

ESC=$(printf '\033')

# The Merge Coverage Data failure that left PR #1099 un-retried: Maven
# Central rate-limited the jacoco-cli download (verbatim from the job log).
expect infra "curl rate-limited by Maven Central (HTTP 429)" <<'LOG'
2026-11-26T20:23:34.7027945Z jacoco-cli 0.8.11 not in the Maven cache; downloading from Maven Central
2026-11-26T20:23:34.7259296Z curl: (22) The requested URL returned error: 429
2026-11-26T20:23:39.7361160Z curl: (22) The requested URL returned error: 429
2026-11-26T20:23:59.7838635Z ##[error]Process completed with exit code 22.
LOG

# The same job's echoed step script, colour-wrapped as GitHub prints it. It
# mentions a 429 in prose only, so on its own it must stay deterministic.
expect deterministic "the coloured step-script echo alone" <<LOG
2026-11-26T20:23:34.6841760Z ${ESC}[36;1m  # retries transient ones with backoff, so a 429 or a rate-limit page${ESC}[0m
2026-11-26T20:23:59.7838635Z ##[error]Process completed with exit code 22.
LOG

# The rate-limited download with the echoed script ahead of it, as the whole
# log reads.
expect infra "coloured step-script echo followed by the curl 429" <<LOG
2026-11-26T20:23:34.6843901Z ${ESC}[36;1m    echo "jacoco-cli \${JACOCO_VERSION} not in the Maven cache; downloading from Maven Central"${ESC}[0m
2026-11-26T20:23:34.7259296Z curl: (22) The requested URL returned error: 429
LOG

# Maven's form of a 429 from a repository.
expect infra "Maven transfer rejected with status code 429" <<'LOG'
[ERROR] Failed to execute goal on project ar-io: Could not collect dependencies: status code: 429, reason phrase: Too Many Requests (429)
LOG

# The HTTP status-line forms.
expect infra "an HTTP/2 429 status line" <<'LOG'
< HTTP/2 429
LOG
expect infra "an HTTP/1.1 429 status line" <<'LOG'
< HTTP/1.1 429 Too Many Requests
LOG
expect infra "an HTTP 429 response" <<'LOG'
Error: Request failed: HTTP 429
LOG

# A colour code inside a signature: Maven colouring part of the line.
expect infra "a colour code inside the Maven 429 signature" <<LOG
[${ESC}[1;31mERROR${ESC}[m] Transfer failed: status code: ${ESC}[1m429${ESC}[m
LOG
expect infra "a colour code inside a connection-reset signature" <<LOG
Caused by: SocketException: Connection ${ESC}[1mreset${ESC}[0m
LOG

# Other 4xx statuses are NOT infrastructure: a 404 or 403 can be the commit
# naming a wrong artifact, repository or credential.
expect deterministic "curl HTTP 404 (not a rate limit)" <<'LOG'
curl: (22) The requested URL returned error: 404
LOG
expect deterministic "an HTTP 403 response" <<'LOG'
< HTTP/1.1 403 Forbidden
LOG
expect deterministic "a 4290 that is not a 429 status" <<'LOG'
Error: Request failed: HTTP 4290
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
