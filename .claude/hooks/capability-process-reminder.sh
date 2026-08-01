#!/usr/bin/env bash
# capability-process-reminder.sh — PreToolUse hook (Edit/Write/MultiEdit)
#
# Fires whenever a file implicated in the "add a job-submission capability /
# parameter" chain is about to be modified, and prints the ENTIRE end-to-end
# process so no link is silently dropped. Adding a parameter to one end of the
# chain without wiring the others produces a flag that looks plumbed but is a
# silent no-op (the exact failure that motivated this hook).
#
# Advisory only: prints to stdout and exits 0. It never blocks. The hard gates
# are the build-failing tests named in the checklist below
# (McpToolDiscoveryTest parity + signature guards).
set -euo pipefail

INPUT="$(cat)"
FILE_PATH="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('file_path', inp.get('path', '')))
" 2>/dev/null || true)"

[ -n "$FILE_PATH" ] || exit 0

# Decide whether this file is part of the capability-adding chain. server.py is
# matched by full path (the filename is too common); the Java/SPI files are
# matched by basename (unique enough across the repo).
BASE="$(basename "$FILE_PATH")"
IMPLICATED=0
case "$FILE_PATH" in
    */tools/mcp/manager/server.py) IMPLICATED=1 ;;
esac
case "$BASE" in
    FlowTreeApiEndpoint.java \
    | CodingAgentJobFactory.java \
    | CodingAgentJob.java \
    | CodingAgentJobCodec.java \
    | AgentRunRequest.java \
    | AgentRunner.java \
    | AgentCapabilities.java \
    | ClaudeCodeRunner.java \
    | OpencodeRunner.java \
    | McpConfigBuilder.java \
    | PhaseConfigResolver.java \
    | Workstream.java \
    | WorkstreamConfig.java \
    | McpToolDiscoveryTest.java \
    | McpToolDiscovery.java) IMPLICATED=1 ;;
esac

[ "$IMPLICATED" -eq 1 ] || exit 0

cat <<EOF

╔══════════════════════════════════════════════════════════════════════════════╗
║  CAPABILITY-CHAIN REMINDER — you are editing: ${BASE}
║  If you are adding/removing/renaming a job-submission capability or parameter,
║  EVERY link below must change together. A param wired at one end only is a
║  silent no-op. (Editing this file for an unrelated reason? Ignore this.)
╚══════════════════════════════════════════════════════════════════════════════╝

The chain, client → controller → job → runner:

 1. MCP tool — tools/mcp/manager/server.py (workstream_submit_task):
      • declare the param in the function SIGNATURE (type hint + default)
      • add an Args: docstring entry
      • emit it: payload["camelKey"] = ...
 2. Controller API — FlowTreeApiEndpoint.handleSubmit:
      • read it: extractJson*(body, "camelKey")
      • apply it: factory.setX(...)
 3. Factory — CodingAgentJobFactory:
      • private field + getter/setter (setter calls set("wireKey", ...))
      • propagate in nextJob(): job.setX(...)
      • decode in set()/setEnforcementFlag()
 4. Job — CodingAgentJob:
      • private field + getter/setter
      • consume in buildRunRequest(): .x(...) on the AgentRunRequest.Builder
 5. Wire codec — CodingAgentJobCodec:
      • appendEncoded(): emit ::wireKey:=value   • applySetting(): decode wireKey
 6. Runner request — AgentRunRequest (+ Builder), in flowtree/agents:
      • immutable field + getter + builder method
 7. Runner(s) — ClaudeCodeRunner / OpencodeRunner:
      • consume request.getX() / request.isX()
      • if it is a NEW capability (not just a param): update capabilities()/AgentCapabilities
 8. NEW TOOL only (not needed for a param on an existing tool):
      • classify the tool name in McpConfigBuilder (AR_MANAGER_TOOL_NAMES or
        EXCLUDED_AR_MANAGER_TOOLS) and add it to the expected sets in
        McpToolDiscoveryTest + tools/mcp/manager/test_server.py
 9. Build-failing guards — add/extend these so the chain cannot silently drift:
      • McpToolDiscoveryTest.submitTaskPayloadKeysAreAllConsumedByController
        (every tool payload key must be consumed by the controller)
      • McpToolDiscoveryTest.managerToolParametersAreProperlyDeclaredInSignatures
        (assert the new signature param)
      • CodingAgentJobControlDefaultsTest (factory→job propagation + wire round-trip)
      • tools/mcp/manager/test_server.py (payload emission)
10. Authoritative checklist: tools/mcp/CLAUDE.md ("Adding a New Tool to server.py").

EOF

exit 0
