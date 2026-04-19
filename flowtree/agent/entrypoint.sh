#!/usr/bin/env bash
#
# FlowTree Agent entrypoint.
#
# Starts a FlowTree Server that connects outbound to the controller
# and executes ClaudeCodeJob instances via Claude Code CLI.
#
# Required environment:
#   CLAUDE_CODE_OAUTH_TOKEN  - OAuth token from `claude setup-token`
#   FLOWTREE_ROOT_HOST       - Controller hostname
#
# Optional environment:
#   FLOWTREE_ROOT_PORT       - Controller port (default: 7766)
#   FLOWTREE_NODE_ID         - Human-readable node identifier
#   FLOWTREE_WORKING_DIR     - Workspace parent for repo checkouts
#   MAVEN_OPTS               - JVM options (default: -Xmx2048m)
#   GIT_USER_NAME            - Git author name for commits
#   GIT_USER_EMAIL           - Git author email for commits

set -euo pipefail

# ── Writable-volume guard (refuse to start if any volume is writable) ──
#
# Writable shared volumes caused the April 2026 cross-workstream collision
# documented in FLOWTREE_COLLISIONS.md: two agent containers mounting the
# same `flowtree_workspaces` volume wrote to the same git working tree
# concurrently, and the advisory file lock failed because git stash
# unlinked the lock file mid-job. Per the original flowtree spec,
# agents must run with NO writable volumes. Read-only bind mounts (ssh
# keys, models, samples) are fine.
#
# System mounts managed by Docker (overlay rootfs, proc, sys, dev, run,
# /etc/hostname, /etc/hosts, /etc/resolv.conf) are exempt — these are
# not operator-controlled and are unavoidably writable.
_flowtree_check_mount_writability() {
    local violations
    violations=$(awk '
        {
            mp = $5
            opts = $6
            # Skip kernel/pseudo filesystems and Docker-managed /etc files.
            if (mp == "/" \
                || mp == "/proc" || index(mp, "/proc/") == 1 \
                || mp == "/sys"  || index(mp, "/sys/")  == 1 \
                || mp == "/dev"  || index(mp, "/dev/")  == 1 \
                || mp == "/run"  || index(mp, "/run/")  == 1 \
                || mp == "/etc/hostname" \
                || mp == "/etc/hosts" \
                || mp == "/etc/resolv.conf") {
                next
            }
            # Mount options (field 6) begin with rw or ro.
            split(opts, flags, ",")
            if (flags[1] == "rw") print mp
        }
    ' /proc/self/mountinfo | sort -u)

    if [ -n "${violations}" ]; then
        cat >&2 <<EOF
ERROR: FlowTree Agent refuses to start -- writable volumes are attached.

Writable mounts detected:
$(printf '  %s\n' ${violations})

Per the original FlowTree spec, agents must run with NO writable
volumes. Read-only bind mounts (ssh keys, models, samples) are fine.

This guard exists because writable shared volumes caused the April 2026
cross-workstream collision documented in FLOWTREE_COLLISIONS.md: two
agent containers mounting the same volume at /workspace/project wrote
to the same git working tree simultaneously, and the in-repo advisory
file lock failed because 'git stash --include-untracked' unlinked the
lock file mid-job.

To fix the misconfiguration:
  * Remove every 'volumes: - <path>' and 'volumes: - <src>:<dst>'
    entry from your compose file (or '-v' flag from 'docker run')
    that is not marked ':ro'.
  * Each agent container must clone the repository onto its own
    overlay filesystem. Expect a cold 'git clone' on every start --
    this is deliberate: it is the only way to guarantee isolation.
  * Read-only reference mounts (ssh keys, models, samples) are fine
    and should continue to be passed as ':ro' binds.
EOF
        exit 1
    fi
}

_flowtree_check_mount_writability

# ── Defaults ────────────────────────────────────────────────────────

export FLOWTREE_ROOT_HOST="${FLOWTREE_ROOT_HOST:?FLOWTREE_ROOT_HOST is required}"
export FLOWTREE_ROOT_PORT="${FLOWTREE_ROOT_PORT:-7766}"

# ── Auth check ──────────────────────────────────────────────────────

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "ERROR: Neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set."
    echo "  For Max plan: run 'claude setup-token' and set CLAUDE_CODE_OAUTH_TOKEN"
    echo "  For API key:  set ANTHROPIC_API_KEY"
    exit 1
fi

# ── Git identity (optional, but needed for push) ───────────────────

if [ -n "${GIT_USER_NAME:-}" ]; then
    git config --global user.name "${GIT_USER_NAME}"
fi
if [ -n "${GIT_USER_EMAIL:-}" ]; then
    git config --global user.email "${GIT_USER_EMAIL}"
fi

# ── Build JVM args ──────────────────────────────────────────────────

JAVA_OPTS="${MAVEN_OPTS:--Xmx2048m}"

AGENT_PROPS="/app/conf/agent.properties"

# Inject the server-side working directory override if configured
if [ -n "${FLOWTREE_WORKING_DIR:-}" ]; then
    JAVA_OPTS="${JAVA_OPTS} -Dflowtree.workingDirectory=${FLOWTREE_WORKING_DIR}"
fi

# ── Node labels ─────────────────────────────────────────────────────

if [ -n "${FLOWTREE_NODE_ID:-}" ]; then
    export FLOWTREE_NODE_LABELS="${FLOWTREE_NODE_LABELS:-}${FLOWTREE_NODE_LABELS:+,}node-id:${FLOWTREE_NODE_ID}"
fi

# ── Launch ──────────────────────────────────────────────────────────

echo "FlowTree Agent starting"
echo "  Controller: ${FLOWTREE_ROOT_HOST}:${FLOWTREE_ROOT_PORT}"
echo "  Node ID:    ${FLOWTREE_NODE_ID:-<auto>}"
echo "  Workspace:  ${FLOWTREE_WORKING_DIR:-<default>}"

exec java ${JAVA_OPTS} \
    -cp "/app/lib/*" \
    io.flowtree.Server \
    "${AGENT_PROPS}"
