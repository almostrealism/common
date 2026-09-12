#!/usr/bin/env bash
# PreToolUse — Artifact*, SendUserFile, Bash: block anything that could move
# session data off this machine, with ONE sanctioned exception — publishing a
# file that is already tracked in this repository, byte-identical to the
# committed (or staged) version, so it can be viewed in the Claude app.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/exfiltration_guard_check.py — the single source of
# truth for the policy (docs/internals/exfiltration-guard.md).
#
# The guard fails CLOSED: unparsable input, a missing git binary, an
# unresolvable path, a subprocess failure, an unknown action, an
# undeterminable destination, or an unwritable audit log all block. There
# is deliberately no environment variable that disables it.
#
# Exit 0 → allow
# Exit 2 → BLOCK (reason on stderr, shown to the model)
#
# Why exec: the wrapper forwards the harness's stdin JSON to the core and
# nothing else. If python3 itself is missing, exec fails and the non-zero
# exit is the block — the guard never degrades to "allow".
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/exfiltration_guard_check.py" --stdin
