#!/usr/bin/env bash
# PostToolUse — consult: record that it was called.
# This timestamp is checked by enforce-consultant-first.sh before Java file writes.
date +%s > "/tmp/.ar_consultant_last_${USER:-developer}.ts"
exit 0
