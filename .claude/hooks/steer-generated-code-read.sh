#!/usr/bin/env bash
# PreToolUse — Bash: block raw shell reads of the native compiler's
# GENERATED OUTPUT (instruction-set dumps, *.metal/*.cl kernels,
# OperationProfile XML) and steer them to the ar-profile-analyzer MCP tool.
#
# Agents keep reaching for `find -name '*.metal'` / `cat
# results/<id>/*instruction_set*.c` / `grep ... test-profiles/*.xml` to
# inspect generated kernels, even though the ar-profile-analyzer tool
# (list_profiles -> search_operations -> get_source/get_source_summary)
# maps each operation node to its generated source with timing and
# structural context. A soft warning does not change the behavior, so this
# is a hard block on the substitutable case (a read/search/list command
# whose target is a generated artifact). Build/run commands and source
# reads under src/ pass through.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/steer_generated_code_check.py — the single source of
# truth for this policy across both Claude Code (this script) and opencode
# (.opencode/plugins/steer-generated-code-read.ts).
#
# Exit 0  → allow
# Exit 2  → BLOCK (the multi-line BLOCKED message is on stderr,
#           shown to the model as the block reason)
#
# See docs/plans/OPENCODE_HOOKS.md for the architecture.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/steer_generated_code_check.py" --stdin
