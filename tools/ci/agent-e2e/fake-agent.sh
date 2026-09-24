#!/usr/bin/env bash
# ─── A stand-in for the `claude` binary, for end-to-end job tests ────
#
# Emits a real `stream-json` transcript on stdout and performs real side
# effects in the working directory, so everything above the process
# boundary — ClaudeCodeRunner's command line and NDJSON parsing, the MCP
# init-event handling, CodingAgentJob's phase dispatch, GitCommitHandler's
# staging and push — runs exactly as it does in production. Only the model
# is absent.
#
# That seam is deliberate. The outage this harness exists to prevent was a
# policy decision about an init event (a required MCP server reported as
# not connected), and no test that stubs out the runner can see it.
#
# Behaviour is scripted through the environment so one script covers every
# scenario:
#
#   AR_FAKE_AGENT_MCP          Semicolon-separated "name=status" pairs for the
#                              init event's mcp_servers array.
#                              Default: "ar-manager=connected".
#                              Example: "ar-manager=failed;ar-docs=connected"
#   AR_FAKE_AGENT_WRITE        Semicolon-separated "path=content" pairs written
#                              relative to the working directory. Empty writes
#                              nothing, which is how a no-op session is scripted.
#                              Single-line content only: `;` separates pairs, so
#                              anything with a semicolon in it — Java source, for
#                              one — needs AR_FAKE_AGENT_COPY instead.
#   AR_FAKE_AGENT_COPY         Semicolon-separated "destpath=srcpath" pairs. The
#                              source is read from disk and copied to the
#                              destination relative to the working directory.
#                              For content that cannot survive being squeezed
#                              through an environment variable; the test writes
#                              the file it wants and names it here.
#   AR_FAKE_AGENT_COMMIT_MSG   Written to commit.txt. Empty writes no commit.txt.
#   AR_FAKE_AGENT_EXIT         Process exit code. Default 0.
#   AR_FAKE_AGENT_SUBTYPE      `subtype` on the result event. Default "success".
#   AR_FAKE_AGENT_GIT_COMMIT   When "true", the agent makes its own git commit
#                              on every invocation, which is the tampering
#                              case. When a number, it commits only on that
#                              invocation — "1" tampers once and behaves on
#                              every restart, which is how the recovery path
#                              is scripted. Needs AR_FAKE_AGENT_STATE.
#   AR_FAKE_AGENT_STATE        Path to a counter file, OUTSIDE the repository,
#                              incremented once per invocation. A marker kept
#                              inside the working tree would itself become a
#                              change the job tries to stage, so the scenario
#                              would be measuring the marker.
#
# The prompt and flags the harness passed are echoed into the transcript so
# a failing test can show what the runner actually invoked.
set -uo pipefail

emit() { printf '%s\n' "$1"; }

# ── init event ──────────────────────────────────────────────────────
mcp_spec="${AR_FAKE_AGENT_MCP-ar-manager=connected}"
servers=""
if [ -n "$mcp_spec" ]; then
    IFS=';' read -ra pairs <<< "$mcp_spec"
    for pair in "${pairs[@]}"; do
        [ -z "$pair" ] && continue
        name="${pair%%=*}"
        status="${pair#*=}"
        [ -n "$servers" ] && servers="${servers},"
        servers="${servers}{\"name\":\"${name}\",\"status\":\"${status}\",\"source\":\"dynamic\"}"
    done
fi
emit "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"fake-session\",\"mcp_servers\":[${servers}]}"

# ── the work ────────────────────────────────────────────────────────
emit '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Write"}]}}'

writes="${AR_FAKE_AGENT_WRITE-}"
if [ -n "$writes" ]; then
    IFS=';' read -ra entries <<< "$writes"
    for entry in "${entries[@]}"; do
        [ -z "$entry" ] && continue
        path="${entry%%=*}"
        content="${entry#*=}"
        mkdir -p "$(dirname "$path")" 2>/dev/null || true
        printf '%s\n' "$content" > "$path"
    done
fi

copies="${AR_FAKE_AGENT_COPY-}"
if [ -n "$copies" ]; then
    IFS=';' read -ra entries <<< "$copies"
    for entry in "${entries[@]}"; do
        [ -z "$entry" ] && continue
        dest="${entry%%=*}"
        src="${entry#*=}"
        mkdir -p "$(dirname "$dest")" 2>/dev/null || true
        cp "$src" "$dest"
    done
fi

commit_msg="${AR_FAKE_AGENT_COMMIT_MSG-}"
if [ -n "$commit_msg" ]; then
    printf '%s\n' "$commit_msg" > commit.txt
fi

invocation=0
if [ -n "${AR_FAKE_AGENT_STATE-}" ]; then
    invocation="$(cat "${AR_FAKE_AGENT_STATE}" 2>/dev/null || echo 0)"
    case "$invocation" in ''|*[!0-9]*) invocation=0 ;; esac
    invocation=$((invocation + 1))
    printf '%s\n' "$invocation" > "${AR_FAKE_AGENT_STATE}"
fi

should_commit=false
case "${AR_FAKE_AGENT_GIT_COMMIT-}" in
    '')     ;;
    true)   should_commit=true ;;
    *)      [ "${AR_FAKE_AGENT_GIT_COMMIT}" = "$invocation" ] && should_commit=true ;;
esac

if [ "$should_commit" = true ]; then
    git add -A >/dev/null 2>&1 || true
    git -c user.name=fake -c user.email=fake@example.com \
        commit -m "agent commit" >/dev/null 2>&1 || true
fi

emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1"}]}}'

# ── result event ────────────────────────────────────────────────────
subtype="${AR_FAKE_AGENT_SUBTYPE-success}"
emit "{\"type\":\"result\",\"subtype\":\"${subtype}\",\"session_id\":\"fake-session\",\"duration_ms\":1234,\"duration_api_ms\":1000,\"num_turns\":1,\"total_cost_usd\":0.01,\"is_error\":false,\"result\":\"done\"}"

exit "${AR_FAKE_AGENT_EXIT-0}"
