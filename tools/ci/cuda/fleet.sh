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

cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "No .env here. Copy .env.example to .env and set GITHUB_PAT." >&2
    exit 1
fi

compose() {
    docker compose --env-file .env "$@"
}

resolve_runner_version() {
    local pinned
    pinned=$(sed -n 's/^RUNNER_VERSION=//p' .env | tail -1)
    if [ -n "${pinned}" ]; then
        echo "${pinned}"
        return
    fi

    curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | jq -r '.tag_name // empty' | sed 's/^v//'
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
        export RUNNER_VERSION
        compose up -d --build --scale "runner=${COUNT}"
        ;;
    down)
        # The shared entrypoint deregisters on SIGTERM; give it time to.
        RUNNER_VERSION=unused compose down --timeout 60
        ;;
    status)
        RUNNER_VERSION=unused compose ps
        ;;
    logs)
        shift
        RUNNER_VERSION=unused compose logs "$@"
        ;;
    *)
        sed -n '/^# ─── Manage/,/^# "up" resolves/p' "$0" | sed 's/^# \{0,1\}//' | head -6
        exit 2
        ;;
esac
