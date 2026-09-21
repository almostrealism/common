#!/usr/bin/env bash
set -euo pipefail

# ─── Self-hosted GitHub Actions Runner Entrypoint ────────────────────────
#
# Required environment variables:
#   GITHUB_OWNER    - GitHub org or user  (e.g. "almostrealism")
#   GITHUB_PAT      - Token with admin:org (org scope) or repo (repo scope)
#   GITHUB_REPO     - Repository name — required only when RUNNER_SCOPE=repo
#   RUNNER_PREFIX   - Name prefix for this machine (e.g. "mac-studio")
#
# Optional:
#   RUNNER_SCOPE    - "repo" or "org" (default: "repo")
#   RUNNER_NAME     - Explicit name (skips auto-indexing)
#   RUNNER_LABELS   - Comma-separated extra labels (always includes "self-hosted,linux")
#   RUNNER_GROUP    - Runner group (default: "Default")
#   RUNNER_WORKDIR  - Working directory for job execution (default: /home/runner/_work)

# ---------- Validation ----------
for var in GITHUB_OWNER GITHUB_PAT; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set."
        exit 1
    fi
done

RUNNER_SCOPE="${RUNNER_SCOPE:-repo}"
RUNNER_PREFIX="${RUNNER_PREFIX:-ar-runner}"
RUNNER_LABELS="${RUNNER_LABELS:-}"
RUNNER_GROUP="${RUNNER_GROUP:-Default}"
RUNNER_WORKDIR="${RUNNER_WORKDIR:-/home/runner/_work}"

# Registration scope: "repo" (default) registers against a single repository;
# "org" registers at the organization level so every repository in the org
# (subject to the runner group's access policy) can share the machine. Same
# contract as tools/ci/macos/runner.sh and tools/ci/rocm/entrypoint.sh.
case "${RUNNER_SCOPE}" in
    repo)
        if [ -z "${GITHUB_REPO:-}" ]; then
            echo "ERROR: GITHUB_REPO is required when RUNNER_SCOPE=repo."
            exit 1
        fi
        API_BASE="https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}"
        CONFIG_URL="https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}"
        SCOPE_LABEL="${GITHUB_OWNER}/${GITHUB_REPO}"
        ;;
    org)
        # Org-level registration needs a PAT with the admin:org scope (classic)
        # or the organization "Self-hosted runners" administration permission
        # (fine-grained). The runner group's repository-access policy (Org
        # Settings -> Actions -> Runner groups) decides which repos may use it —
        # without granting that access, jobs queue forever against a runner that
        # reports healthy.
        API_BASE="https://api.github.com/orgs/${GITHUB_OWNER}"
        CONFIG_URL="https://github.com/${GITHUB_OWNER}"
        SCOPE_LABEL="${GITHUB_OWNER} (org)"
        ;;
    *)
        echo "ERROR: RUNNER_SCOPE must be 'repo' or 'org' (got '${RUNNER_SCOPE}')."
        exit 1
        ;;
esac

BASE_LABELS="self-hosted,linux"
if [ -n "${RUNNER_LABELS}" ]; then
    ALL_LABELS="${BASE_LABELS},${RUNNER_LABELS}"
else
    ALL_LABELS="${BASE_LABELS}"
fi

mkdir -p "${RUNNER_WORKDIR}"

# ---------- Token helpers ----------
# Requests a runner token (runners/registration-token or runners/remove-token)
# for the configured scope. Echoes the token on stdout on success; on failure
# logs the HTTP status and the API error to stderr and returns non-zero, so a
# permission/scope problem is reported rather than failing with an empty token.
request_runner_token() {
    local endpoint="$1"
    local response http_status body token message

    response=$(curl -sS -w $'\n%{http_code}' -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github+json" \
        "${API_BASE}/actions/${endpoint}" || true)

    http_status=$(printf '%s\n' "${response}" | tail -n1)
    body=$(printf '%s\n' "${response}" | sed '$d')
    token=$(printf '%s' "${body}" | jq -r '.token // empty')

    if [ -n "${token}" ]; then
        printf '%s' "${token}"
        return 0
    fi

    message=$(printf '%s' "${body}" | jq -r '.message // empty')
    echo "  GitHub API request to ${endpoint} failed (HTTP ${http_status:-no response})." >&2
    if [ -n "${message}" ]; then
        echo "  GitHub says: ${message}" >&2
    elif [ -n "${body}" ]; then
        echo "  Response body: ${body}" >&2
    fi
    if [ "${RUNNER_SCOPE}" = "org" ]; then
        echo "  Org scope needs a PAT with admin:org (classic) or the organization" >&2
        echo "  'Self-hosted runners' read/write permission (fine-grained), with" >&2
        echo "  ${GITHUB_OWNER} as the token's resource owner." >&2
    else
        echo "  Repo scope needs a PAT with the repo scope (classic) or the" >&2
        echo "  repository 'Administration' read/write permission (fine-grained)." >&2
    fi
    return 1
}

list_runners() {
    curl -s \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github+json" \
        "${API_BASE}/actions/runners?per_page=100"
}

# ---------- Clean up local config from previous run ----------
if [ -f .runner ]; then
    echo "Removing leftover local .runner config..."
    if REMOVE_TOKEN=$(request_runner_token "runners/remove-token"); then
        ./config.sh remove --token "${REMOVE_TOKEN}" 2>/dev/null || true
    fi
    rm -f .runner .credentials .credentials_rsaparams 2>/dev/null || true
fi

# ---------- Claim a sequential name ----------
# If RUNNER_NAME is explicitly set, use it. Otherwise find the lowest
# available <prefix>-N by querying the GitHub runners API.
if [ -z "${RUNNER_NAME:-}" ]; then
    # Small random delay to reduce races when many containers start together
    sleep $(( RANDOM % 3 ))

    echo "Finding available runner slot for prefix '${RUNNER_PREFIX}'..."
    REGISTERED=$(list_runners \
        | jq -r --arg pfx "${RUNNER_PREFIX}-" \
            '[.runners[] | select(.name | startswith($pfx)) | select(.status == "online") | .name] | sort | .[]')

    # Find the first unused index starting from 1
    for i in $(seq 1 100); do
        CANDIDATE="${RUNNER_PREFIX}-${i}"
        if ! echo "${REGISTERED}" | grep -qx "${CANDIDATE}"; then
            RUNNER_NAME="${CANDIDATE}"
            break
        fi
    done

    if [ -z "${RUNNER_NAME:-}" ]; then
        echo "ERROR: Could not find an available runner slot (tried 1-100)."
        exit 1
    fi

    # If a stale (offline) runner exists with this name, remove it
    STALE_ID=$(list_runners \
        | jq -r --arg name "${RUNNER_NAME}" \
            '.runners[] | select(.name == $name) | .id // empty')

    if [ -n "${STALE_ID}" ]; then
        echo "Removing stale runner '${RUNNER_NAME}' (id=${STALE_ID})..."
        curl -s -X DELETE \
            -H "Authorization: token ${GITHUB_PAT}" \
            -H "Accept: application/vnd.github+json" \
            "${API_BASE}/actions/runners/${STALE_ID}" || true
    fi
fi

echo "Claiming runner name: ${RUNNER_NAME}"

# ---------- Obtain registration token ----------
if ! REG_TOKEN=$(request_runner_token "runners/registration-token"); then
    echo "ERROR: Failed to obtain registration token."
    exit 1
fi

# ---------- Configure the runner ----------
echo "Registering with ${SCOPE_LABEL} as '${RUNNER_NAME}' [${ALL_LABELS}]..."
./config.sh \
    --url "${CONFIG_URL}" \
    --token "${REG_TOKEN}" \
    --name "${RUNNER_NAME}" \
    --labels "${ALL_LABELS}" \
    --runnergroup "${RUNNER_GROUP}" \
    --work "${RUNNER_WORKDIR}" \
    --replace \
    --unattended \
    --ephemeral

# ---------- Graceful shutdown ----------
cleanup() {
    echo "Caught signal, removing runner '${RUNNER_NAME}'..."
    if REMOVE_TOKEN=$(request_runner_token "runners/remove-token"); then
        ./config.sh remove --token "${REMOVE_TOKEN}" || true
    fi
}
trap cleanup SIGTERM SIGINT

# ---------- Run ----------
echo "Starting runner '${RUNNER_NAME}'..."
./run.sh &
wait $!
