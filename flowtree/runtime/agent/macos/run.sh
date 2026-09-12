#!/usr/bin/env bash
#
# Launcher for a native (non-Docker) FlowTree agent on macOS.
#
# launchd executes this script from the agent's install directory
# (install.sh copies it to ${FLOWTREE_AGENT_HOME}/bin/run.sh). It loads the
# operator's env file, applies the same defaults the container entrypoint
# applies, and execs the Server. Everything that differs from
# ../entrypoint.sh is a consequence of not being in a container: there is no
# mount guard, the classpath and properties live under the install
# directory, and PATH has to be assembled by hand because launchd hands a
# service almost none of one.
#
# Required (from the env file):
#   FLOWTREE_ROOT_HOST       - Controller hostname
#   CLAUDE_CODE_OAUTH_TOKEN  - OAuth token from `claude setup-token`
#                              (or ANTHROPIC_API_KEY)
#
# Optional (from the env file):
#   FLOWTREE_ROOT_PORT       - Controller port (default: 7766)
#   FLOWTREE_NODE_ID         - Human-readable node identifier
#   FLOWTREE_NODE_LABELS     - Extra key:value labels, comma-separated
#   FLOWTREE_WORKING_DIR     - Workspace parent for repo checkouts
#                              (default: ${FLOWTREE_AGENT_HOME}/workspace)
#   AGENT_JVM_OPTS           - JVM options (default: -Xmx2048m)
#   GIT_USER_NAME            - Git author name for commits
#   GIT_USER_EMAIL           - Git author email for commits
#   FLOWTREE_AGENT_PATH      - Directories to put ahead of PATH; replaces the
#                              built-in Homebrew / npm-global list

set -euo pipefail

AGENT_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${FLOWTREE_AGENT_ENV:-${AGENT_HOME}/agent.env}"

if [ ! -f "${ENV_FILE}" ]; then
    echo "ERROR: agent env file not found at ${ENV_FILE}" >&2
    echo "  Copy agent.env.example there and fill in the values." >&2
    exit 1
fi

set -a
# shellcheck source=/dev/null
. "${ENV_FILE}"
set +a

# ── PATH ────────────────────────────────────────────────────────────
#
# A launchd service starts with /usr/bin:/bin:/usr/sbin:/sbin and nothing
# else, so the tools the agent shells out to (claude, node, git from
# Homebrew, mvn, java) must be put on PATH here. The default covers a
# Homebrew install on Apple Silicon plus a per-user npm prefix; an operator
# whose tools live elsewhere sets FLOWTREE_AGENT_PATH in the env file.
DEFAULT_TOOL_PATH="/opt/homebrew/bin:/opt/homebrew/opt/openjdk@17/bin:/usr/local/bin:${HOME}/.npm-global/bin"
export PATH="${FLOWTREE_AGENT_PATH:-${DEFAULT_TOOL_PATH}}:${PATH}"

# ── Defaults ────────────────────────────────────────────────────────

export FLOWTREE_ROOT_HOST="${FLOWTREE_ROOT_HOST:?FLOWTREE_ROOT_HOST is required}"
export FLOWTREE_ROOT_PORT="${FLOWTREE_ROOT_PORT:-7766}"
export FLOWTREE_WORKING_DIR="${FLOWTREE_WORKING_DIR:-${AGENT_HOME}/workspace}"
mkdir -p "${FLOWTREE_WORKING_DIR}"

# ── Auth check ──────────────────────────────────────────────────────

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "ERROR: Neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set in ${ENV_FILE}." >&2
    echo "  For Max plan: run 'claude setup-token' and set CLAUDE_CODE_OAUTH_TOKEN" >&2
    echo "  For API key:  set ANTHROPIC_API_KEY" >&2
    exit 1
fi

# ── Git identity (optional, but needed for push) ───────────────────

if [ -n "${GIT_USER_NAME:-}" ]; then
    git config --global user.name "${GIT_USER_NAME}"
fi
if [ -n "${GIT_USER_EMAIL:-}" ]; then
    git config --global user.email "${GIT_USER_EMAIL}"
fi

# ── JVM args ────────────────────────────────────────────────────────

JAVA_OPTS="${AGENT_JVM_OPTS:--Xmx2048m}"
JAVA_OPTS="${JAVA_OPTS} -Dflowtree.workingDirectory=${FLOWTREE_WORKING_DIR}"

# ── Node labels ─────────────────────────────────────────────────────
#
# platform:macos is detected by the Server itself (AutomaticLabel); only the
# per-node identity is added here, exactly as the container entrypoint does.

if [ -n "${FLOWTREE_NODE_ID:-}" ]; then
    export FLOWTREE_NODE_LABELS="${FLOWTREE_NODE_LABELS:-}${FLOWTREE_NODE_LABELS:+,}node-id:${FLOWTREE_NODE_ID}"
fi

# ── Launch ──────────────────────────────────────────────────────────

echo "FlowTree Agent starting (native macOS)"
echo "  Controller: ${FLOWTREE_ROOT_HOST}:${FLOWTREE_ROOT_PORT}"
echo "  Node ID:    ${FLOWTREE_NODE_ID:-<auto>}"
echo "  Workspace:  ${FLOWTREE_WORKING_DIR}"
echo "  Install:    ${AGENT_HOME}"
echo "  Java:       $(command -v java || echo 'NOT FOUND on PATH')"
echo "  Claude:     $(command -v claude || echo 'NOT FOUND on PATH')"

# shellcheck disable=SC2086
exec java ${JAVA_OPTS} \
    -cp "${AGENT_HOME}/lib/*" \
    io.flowtree.Server \
    "${AGENT_HOME}/conf/agent.properties"
