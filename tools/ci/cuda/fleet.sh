#!/usr/bin/env bash
set -euo pipefail

# ─── Manage the ar-ci-cuda runner fleet ──────────────────────────────────
#
#   ./fleet.sh up [N]      Build the image and start N runners (default 1)
#   ./fleet.sh down        Stop all runners, deregistering them
#   ./fleet.sh status      Show the runner containers
#   ./fleet.sh logs [-f]   Show the runner logs
#
# "up" resolves the current actions/runner release (unless RUNNER_VERSION
# is pinned in .env) and passes it to the build. The runners register with
# --disableupdate, so the image is the only source of agent updates: re-run
# "up" every few weeks, or whenever GitHub ships a new agent release.
#
# "up" also resolves AR_CI_SAMPLES_GID, the numeric group that owns the
# sample library (unless pinned in .env). sync-music-samples.sh leaves that
# tree readable by its group only, and the image's runner user is not in any
# host group, so the compose file adds this gid to the runner with group_add.

cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "No .env here. Copy .env.example to .env and set GITHUB_PAT." >&2
    exit 1
fi

compose() {
    docker compose --env-file .env "$@"
}

env_value() {
    sed -n "s/^$1=//p" .env | tail -1
}

resolve_runner_version() {
    local pinned
    pinned=$(env_value RUNNER_VERSION)
    if [ -n "${pinned}" ]; then
        echo "${pinned}"
        return
    fi

    curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | jq -r '.tag_name // empty' | sed 's/^v//'
}

resolve_samples_gid() {
    local pinned dir
    pinned=$(env_value AR_CI_SAMPLES_GID)
    if [ -n "${pinned}" ]; then
        echo "${pinned}"
        return
    fi

    dir=$(env_value AR_CI_SAMPLES_DIR)
    dir="${dir:-/srv/ar-ci/music}"
    if [ -d "${dir}" ]; then
        ls -nd "${dir}" | awk '{print $4}'
    fi
}

case "${1:-}" in
    up)
        COUNT="${2:-1}"
        RUNNER_VERSION=$(resolve_runner_version)
        if [ -z "${RUNNER_VERSION}" ]; then
            echo "Could not determine the actions/runner release." >&2
            echo "Set RUNNER_VERSION in .env, or check access to api.github.com." >&2
            exit 1
        fi
        echo "Runner release: ${RUNNER_VERSION}"
        AR_CI_SAMPLES_GID=$(resolve_samples_gid)
        if [ -z "${AR_CI_SAMPLES_GID}" ]; then
            echo "The sample library directory does not exist on this host." >&2
            echo "Stage it first (see README.md, Test Data), or set AR_CI_SAMPLES_DIR in .env." >&2
            exit 1
        fi
        if [ "${AR_CI_SAMPLES_GID}" = "0" ] && [ -z "$(env_value AR_CI_SAMPLES_GID)" ]; then
            echo "The sample library directory is owned by the root group (gid 0)." >&2
            echo "Stage it with sync-music-samples.sh --group first, or pin AR_CI_SAMPLES_GID in .env." >&2
            exit 1
        fi
        echo "Sample library group: ${AR_CI_SAMPLES_GID}"
        export RUNNER_VERSION AR_CI_SAMPLES_GID
        compose up -d --build --scale "runner=${COUNT}"
        ;;
    down)
        # The shared entrypoint deregisters on SIGTERM; give it time to.
        RUNNER_VERSION=unused AR_CI_SAMPLES_GID=0 compose down --timeout 60
        ;;
    status)
        RUNNER_VERSION=unused AR_CI_SAMPLES_GID=0 compose ps
        ;;
    logs)
        shift
        RUNNER_VERSION=unused AR_CI_SAMPLES_GID=0 compose logs "$@"
        ;;
    *)
        sed -n '/^# ─── Manage/,/^# "up" resolves/p' "$0" | sed 's/^# \{0,1\}//' | head -6
        exit 2
        ;;
esac
