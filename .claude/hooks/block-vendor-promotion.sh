#!/usr/bin/env bash
# PreToolUse — Write/Edit/MultiEdit and the claude.ai document connector's writes:
# block an edit that adds vendor attribution or a claude.ai link to any file, and
# any write of a document to the vendor's claude.ai service.
#
# Thin wrapper; decision logic lives in .claude/hooks/lib/vendor_promotion_check.py.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/vendor_promotion_check.py" --stdin
