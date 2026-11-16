#!/usr/bin/env bash
# PreToolUse — Bash, GitHub MCP writes, and Write/Edit/MultiEdit of commit.txt:
# block a commit message, pull request or comment that carries a private link
# (a claude.ai page, a session id, a URL on the project's own network).
#
# Thin wrapper; decision logic lives in .claude/hooks/lib/private_url_check.py.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/private_url_check.py" --stdin
