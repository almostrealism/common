#!/usr/bin/env bash
# ─── Shared infrastructure-failure classification ────────────────────────
#
# A failed CI job is either the commit's fault (a compile error, a failing
# test, a timeout violation) or the infrastructure's (a plugin that would not
# download, a dropped connection, a lost runner). Only the first kind should
# ever reach a coding agent; the second is retried. Two paths make that call —
# the flaky-test retry gate (tools/ci/rerun-flaky-tests.sh) and the pull-request
# auto-review job (.github/workflows/analysis.yaml) — and they must make it the
# same way, so the signatures and the log fetch live here, sourced by both.
#
# What counts as an infrastructure failure is INFRA_SIGNATURES below: an
# extended regular expression, matched case-insensitively against a failed
# job's log. The set is deliberately narrow — a signature earns its place only
# when it cannot be produced by a defect in the commit:
#
#   - a dependency or plugin that would not resolve/download from a repository;
#   - a dropped, refused or timed-out network connection, or a DNS failure;
#   - a 5xx from a package, artifact or image host, or a registry rate limit —
#     an HTTP 429 in the forms the tools print it: curl's and git's "The
#     requested URL returned error: 429", Maven's "status code: 429" /
#     "Too Many Requests (429)", and "HTTP 429" / "HTTP/1.1 429" / "HTTP/2 429"
#     (only a 429 status, never a bare 4xx: a 401/403/404 can be the commit
#     naming a wrong repository, artifact or credential);
#   - a runner that shut down or lost contact with the server (a GitHub-hosted
#     runner being reclaimed, a self-hosted runner losing its connection);
#   - a full disk.
#
# Deliberately NOT signatures, because each can be the commit's own fault:
#
#   - a JVM fork dying ("The forked VM terminated without properly saying
#     goodbye", exit 134/137) — a real crash or OOM in the code under test;
#     test lanes already get flaky retries without reading their logs.
#   - a bare "The operation was canceled." — it is also what a job hitting its
#     own timeout-minutes, a manual cancel, or a concurrency cancel prints. A
#     cancellation that a runner shutdown caused is recognised by the shutdown
#     line GitHub emits alongside it ("received a shutdown signal", "lost
#     communication with the server"), which IS a signature below, so the
#     ambiguous cancellation line is not needed and is not matched.
#
# A job log is matched with its ANSI colour codes stripped. GitHub wraps the
# echoed step script in colour codes, and Maven and other tools colour their
# own output, so a code can fall inside the text a signature looks for; and
# `gh api` refuses outright to print a response containing escape sequences
# unless told it may (--allow-escape-sequences). Without that flag every log
# read fails, and a classifier that cannot read logs retries nothing.
#
# A log that cannot be read is NOT an infrastructure failure and NOT a
# deterministic one: it is unknown. ci_infra_job_matches reports that third
# state (exit 2) rather than guessing, so each caller can pick the safe
# fallback for its own path (the retry gate retries while attempts remain; the
# auto-review path dispatches, its normal action). Neither lets an unreadable
# log silently drop the failure.
#
# Required environment (for the log-reading helpers):
#   REPO      - owner/name of the repository holding the run
#   GH_TOKEN  - a token with actions:read on it
#
# Optional environment (log-fetch retry, tuned for tests):
#   CI_INFRA_LOG_ATTEMPTS     - how many times to try the logs endpoint
#                               (default 6). The endpoint can 404 briefly right
#                               after a run completes — the workflow_run
#                               `completed` event fires at the run's conclusion,
#                               before per-job logs are archived — and can
#                               return a transient redirect or 5xx, so a single
#                               try is not enough.
#   CI_INFRA_LOG_RETRY_DELAY  - seconds to wait after a failed try, doubling up
#                               to a 15s cap (default 2; tests set 0).

# Log lines that mark a failed job as an infrastructure failure; see the header.
# Extended regular expression, matched case-insensitively.
INFRA_SIGNATURES='Could not (find|resolve|transfer) artifact|Failed to (transfer|read artifact descriptor)|Could not resolve dependencies|Could not resolve host|Connection (reset|refused|timed out)|(connect|read) timed out|ETIMEDOUT|ECONNRESET|Temporary failure in name resolution|TLS handshake timeout|Failed to connect to [^ ]+ port|unable to access [^ ]+: (Could not resolve|Failed to connect|The requested URL returned error: 5)|RPC failed|The remote end hung up unexpectedly|(HTTP(/[0-9.]+)?|status code:?) ?(5[0-9][0-9]|429([^0-9]|$))|The requested URL returned error: 429|(502 Bad Gateway|503 Service Unavailable|504 Gateway Time-?out)|too ?many ?requests|received a shutdown signal|lost communication with the server|No space left on device'

# ci_infra_fetch_job_log <job-id>
#   Print the job's log to stdout. Exit 0 when read, 1 when it could not be read
#   after CI_INFRA_LOG_ATTEMPTS tries. The log is captured whole before the
#   caller greps it: piping `gh` into `grep -q` lets grep exit on the first
#   match and, under `set -o pipefail`, reports the SIGPIPE'd `gh` as a failure.
#   A persistent fetch error is surfaced as a ::warning::, never discarded.
ci_infra_fetch_job_log() {
    local job_id="$1"
    [ -n "$job_id" ] || return 1

    local attempts="${CI_INFRA_LOG_ATTEMPTS:-6}"
    local delay="${CI_INFRA_LOG_RETRY_DELAY:-2}"
    local try log err_file err help allow=""
    err_file="$(mktemp)"
    # Older gh has neither the flag nor the refusal it lifts; see the header.
    help=$(gh api --help 2>/dev/null || true)
    case "$help" in
        *--allow-escape-sequences*) allow="--allow-escape-sequences" ;;
    esac

    for (( try = 1; try <= attempts; try++ )); do
        if log=$(gh api $allow "/repos/$REPO/actions/jobs/$job_id/logs" 2>"$err_file"); then
            rm -f "$err_file"
            printf '%s' "$log"
            return 0
        fi
        if [ "$try" -lt "$attempts" ]; then
            [ "$delay" -gt 0 ] 2>/dev/null && sleep "$delay"
            delay=$(( delay * 2 ))
            [ "$delay" -gt 15 ] && delay=15
        fi
    done

    err=$(tr '\n' ' ' < "$err_file")
    rm -f "$err_file"
    # To stderr: the only caller captures this function's stdout, so a warning
    # on stdout would be swallowed into that variable. GitHub renders ::warning::
    # on stderr too, and no caller captures stderr.
    echo "::warning::Could not read the log of job $job_id after $attempts attempt(s); its failure cannot be classified as infrastructure or deterministic: ${err:-no error output}" >&2
    return 1
}

# ci_infra_strip_ansi
#   Copy stdin to stdout without ANSI escape sequences (colour and other CSI
#   codes), so a signature matches text a colour code was placed inside.
ci_infra_strip_ansi() {
    local esc
    esc=$(printf '\033')
    LC_ALL=C sed -E "s/${esc}\[[0-9;?]*[A-Za-z]//g"
}

# ci_infra_log_matches
#   Exit 0 when the log on stdin shows an infrastructure failure, else 1. The
#   stripped log is captured before grep reads it, for the SIGPIPE reason given
#   at ci_infra_fetch_job_log.
ci_infra_log_matches() {
    local plain
    plain=$(ci_infra_strip_ansi)
    grep -Eiq "$INFRA_SIGNATURES" <<< "$plain"
}

# ci_infra_job_matches <job-id>
#   Exit 0 when the job's log shows an infrastructure failure, 1 when it is
#   readable and shows none (a deterministic failure), 2 when the log could not
#   be read (unknown — the caller decides the fallback).
ci_infra_job_matches() {
    local log
    log=$(ci_infra_fetch_job_log "$1") || return 2
    ci_infra_log_matches <<< "$log"
}

# When executed directly (not sourced), classify the log on stdin: print
# "infra" and exit 0 if it shows an infrastructure failure, else print
# "deterministic" and exit 1. tools/tests/test-infra-classification.sh drives
# this to check the signatures against real log samples without the GitHub API.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    if ci_infra_log_matches; then
        echo infra
    else
        echo deterministic
        exit 1
    fi
fi
