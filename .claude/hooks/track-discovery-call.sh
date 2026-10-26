#!/usr/bin/env bash
# PostToolUse - Grep/Glob/Agent: record that discovery ran this session.
# warn-new-type-placement.py reads this marker (and the consult marker written
# by track-consultant-call.sh) to decide whether to escalate its new-type
# warning. Mirrors track-consultant-call.sh's /tmp timestamp convention.
date +%s > "/tmp/.ar_discovery_last_${USER:-developer}.ts"
exit 0
