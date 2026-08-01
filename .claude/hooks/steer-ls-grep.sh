#!/usr/bin/env bash
# PreToolUse — Bash: block simple `ls` and `grep`/`rg` calls that
# should use the structured `glob` / `grep` tools.
#
# Agents keep reaching for `bash ls <path>` and `bash grep <pattern>
# <file>` even though the `glob` and `grep` tools return richer
# structured output. A soft warning does not change the behavior
# (per multiple workstream retrospectives), so this hook is a hard
# block on the simple substitutable cases only. Compound shell
# features (pipes, xargs, perl-regex, output-format flags) are
# allowed through — the structured tools cannot express them.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/steer_ls_grep_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/steer-ls-grep.ts).
#
# Exit 0  → allow
# Exit 2  → BLOCK (the multi-line BLOCKED message is on stderr,
#           shown to the model as the block reason)
#
# See docs/plans/OPENCODE_HOOKS.md for the architecture.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/steer_ls_grep_check.py" --stdin
