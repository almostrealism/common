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
