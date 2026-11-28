#!/usr/bin/env bash
#
# End-to-end smoke for a Linux host Server: one job routed to its native
# Node and one to its Docker Node, both through the controller.
#
# Operations only. It needs a running controller, a host Server installed
# by install.sh with a node list like nodes.properties.example, and a
# registered workstream to submit under; it is not part of any unit suite
# and nothing runs it automatically. Run it after installing or upgrading a
# host, from anywhere that can reach the controller's HTTP API.
#
# Each job is a shell-command job (no agent session, no credential spend)
# that checks where it ran: the native job fails if it finds itself in a
# container, the Docker job fails if it does not. So a SUCCESS for both
# proves the controller routed each by its required label to the right Node
# and that each Node kind executed it in the right place.
#
# Usage:
#   flowtree/runtime/agent/linux/smoke.sh --workstream <id> [options]
#
# Options:
#   --workstream ID       workstream to submit the jobs under (required)
#   --controller URL      controller HTTP API (default: http://localhost:7780)
#   --native-label K:V    required label that selects the native Node
#                         (default: runtime:native)
#   --docker-label K:V    required label that selects the Docker Node
#                         (default: runtime:docker)
#   --timeout SECONDS     how long to wait for both jobs (default: 900)
#
# Exit codes:
#   0 - both jobs reached SUCCESS
#   1 - a submission was refused, a job ended in any other state, or the
#       timeout passed first

set -euo pipefail

CONTROLLER="http://localhost:7780"
WORKSTREAM=""
NATIVE_LABEL="runtime:native"
DOCKER_LABEL="runtime:docker"
TIMEOUT_SECONDS=900
while [ "$#" -gt 0 ]; do
    case "$1" in
        --workstream)   WORKSTREAM="${2:?--workstream needs an id}"; shift ;;
        --controller)   CONTROLLER="${2:?--controller needs a URL}"; shift ;;
        --native-label) NATIVE_LABEL="${2:?--native-label needs key:value}"; shift ;;
        --docker-label) DOCKER_LABEL="${2:?--docker-label needs key:value}"; shift ;;
        --timeout)      TIMEOUT_SECONDS="${2:?--timeout needs seconds}"; shift ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument '$1'" >&2
            exit 1
            ;;
    esac
    shift
done

if [ -z "${WORKSTREAM}" ]; then
    echo "ERROR: --workstream is required." >&2
    exit 1
fi
for cmd in curl python3; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not on PATH." >&2
        exit 1
    fi
done

# Reads one top-level string field from the JSON document on stdin.
json_field() {
    python3 -I -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

# Builds the submission body for a shell job with one required label.
submission() {
    python3 -I -c '
import json, sys
workstream, label, description, command = sys.argv[1:5]
key, _, value = label.partition(":")
print(json.dumps({
    "workstreamId": workstream,
    "jobType": "shell",
    "command": command,
    "description": description,
    "requiredLabels": {key: value},
}))' "$@"
}

# Submits one job and prints its id; fails on a refused submission.
submit() {
    local label="$1" description="$2" command="$3" response job_id
    response="$(curl -fsS -X POST -H 'Content-Type: application/json' \
        --data "$(submission "${WORKSTREAM}" "${label}" "${description}" "${command}")" \
        "${CONTROLLER}/api/submit")"
    job_id="$(printf '%s' "${response}" | json_field jobId)"
    if [ -z "${job_id}" ]; then
        echo "ERROR: the controller did not accept the ${label} job: ${response}" >&2
        exit 1
    fi
    printf '%s' "${job_id}"
}

job_status() {
    curl -fsS "${CONTROLLER}/api/jobs/$1" 2>/dev/null | json_field status || true
}

IN_CONTAINER='[ -f /.dockerenv ] || grep -qE "docker|containerd|kubepods" /proc/1/cgroup'
NATIVE_JOB="$(submit "${NATIVE_LABEL}" "Linux host smoke: native Node" \
    "uname -a; if ${IN_CONTAINER}; then echo 'ran in a container' >&2; exit 1; fi")"
DOCKER_JOB="$(submit "${DOCKER_LABEL}" "Linux host smoke: Docker Node" \
    "uname -a; if ! { ${IN_CONTAINER}; }; then echo 'ran outside a container' >&2; exit 1; fi")"
echo "Submitted native job ${NATIVE_JOB} (${NATIVE_LABEL}) and Docker job ${DOCKER_JOB} (${DOCKER_LABEL})"

DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
NATIVE_STATUS=""
DOCKER_STATUS=""
finished() {
    case "$1" in
        SUCCESS|FAILED|CANCELLED|DEGRADED|BLOCKED) return 0 ;;
        *) return 1 ;;
    esac
}
while [ "$(date +%s)" -lt "${DEADLINE}" ]; do
    finished "${NATIVE_STATUS}" || NATIVE_STATUS="$(job_status "${NATIVE_JOB}")"
    finished "${DOCKER_STATUS}" || DOCKER_STATUS="$(job_status "${DOCKER_JOB}")"
    if finished "${NATIVE_STATUS}" && finished "${DOCKER_STATUS}"; then
        break
    fi
    sleep 10
done

echo "native: ${NATIVE_STATUS:-<no status>}  docker: ${DOCKER_STATUS:-<no status>}"
if [ "${NATIVE_STATUS}" = "SUCCESS" ] && [ "${DOCKER_STATUS}" = "SUCCESS" ]; then
    echo "Smoke passed: both Node kinds ran their job."
    exit 0
fi
echo "ERROR: smoke failed; see ${CONTROLLER}/api/jobs/${NATIVE_JOB} and ${CONTROLLER}/api/jobs/${DOCKER_JOB}." >&2
exit 1
