#!/usr/bin/env bash
# PostToolUse — mcp__ar-consultant__consult: record that the consultant was called.
# This timestamp is checked by enforce-consultant-first.sh before Java file writes.
date +%s > "/tmp/.ar_consultant_last_${USER:-developer}.ts"
exit 0
