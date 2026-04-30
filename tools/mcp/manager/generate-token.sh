#!/usr/bin/env bash
#
# Generate a bearer token for the ar-manager MCP server.
#
# Usage:
#   ./generate-token.sh                                  # full access, no workspace scope
#   ./generate-token.sh "My label"                       # full access with custom label
#   ./generate-token.sh "Dashboard" read                 # read-only token
#   ./generate-token.sh "Agent" read submit              # read+submit, no other scopes
#   ./generate-token.sh -w T0123ABC "Mobile"             # full access scoped to one workspace
#   ./generate-token.sh -w T0123 -w T9876 "Bot" read     # multi-workspace, read-only
#
# Flags (must precede positional arguments):
#   -w <workspace_id>   Restrict the token to the given Slack workspace ID.
#                       May be passed multiple times to allow several workspaces.
#                       Omit entirely for an unscoped (superadmin) token.
#
# Positional arguments (after flags):
#   $1                  Label for the token. Defaults to cli-YYYYMMDD.
#   $2..$N              Scopes. Defaults to all scopes when none are given.
#
# Available scopes:
#   read           list workstreams, get stats, get jobs, health check
#   write          register/update workstreams, update controller config,
#                  send messages
#   submit         submit a coding task prompt
#   pipeline       trigger GitHub workflows, commit plan files
#   github         read PR conversations/review comments, list/create PRs,
#                  reply on review threads, request Copilot review, read repo
#                  files, read planning documents
#   memory-read    recall memories, fetch workstream branch context
#   memory-write   store new memories
#
# The token is appended to the token file. If the file doesn't exist,
# it is created. The generated token value is printed to stdout.
#
set -euo pipefail

TOKEN_FILE="${AR_MANAGER_TOKEN_FILE:-${HOME}/.config/ar/manager-tokens.json}"

# Parse flags
WORKSPACES=()
while [ $# -gt 0 ]; do
    case "$1" in
        -w|--workspace)
            if [ $# -lt 2 ]; then
                echo "error: -w requires a workspace ID argument" >&2
                exit 1
            fi
            WORKSPACES+=("$2")
            shift 2
            ;;
        --)
            shift
            break
            ;;
        -*)
            echo "error: unknown flag: $1" >&2
            exit 1
            ;;
        *)
            break
            ;;
    esac
done

LABEL="${1:-cli-$(date +%Y%m%d)}"
shift 2>/dev/null || true

# Default scopes: full access
if [ $# -eq 0 ]; then
    SCOPES=("read" "write" "submit" "pipeline" "github" "memory-read" "memory-write")
else
    SCOPES=("$@")
fi

# Generate a random token (32 bytes, base64url-encoded, prefixed)
TOKEN_VALUE="armt_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"

# Build scopes JSON array
SCOPES_JSON="["
FIRST=true
for s in "${SCOPES[@]}"; do
    if [ "$FIRST" = true ]; then
        FIRST=false
    else
        SCOPES_JSON+=","
    fi
    SCOPES_JSON+="\"${s}\""
done
SCOPES_JSON+="]"

# Build workspace scopes JSON array (or "null" when unscoped)
if [ ${#WORKSPACES[@]} -eq 0 ]; then
    WORKSPACES_JSON="null"
else
    WORKSPACES_JSON="["
    FIRST=true
    for w in "${WORKSPACES[@]}"; do
        if [ "$FIRST" = true ]; then
            FIRST=false
        else
            WORKSPACES_JSON+=","
        fi
        WORKSPACES_JSON+="\"${w}\""
    done
    WORKSPACES_JSON+="]"
fi

# Ensure directory exists
mkdir -p "$(dirname "$TOKEN_FILE")"

# Create or update the token file — values passed via sys.argv to
# prevent shell injection through crafted label/token strings.
if [ -f "$TOKEN_FILE" ]; then
    python3 - "$TOKEN_FILE" "$TOKEN_VALUE" "$LABEL" "$SCOPES_JSON" "$WORKSPACES_JSON" <<'PYEOF'
import json, sys
token_file, token_value, label = sys.argv[1], sys.argv[2], sys.argv[3]
scopes = json.loads(sys.argv[4])
workspace_scopes = json.loads(sys.argv[5])
with open(token_file) as f:
    data = json.load(f)
entry = {
    'value': token_value,
    'scopes': scopes,
    'label': label,
}
if workspace_scopes is not None:
    entry['workspaceScopes'] = workspace_scopes
data.setdefault('tokens', []).append(entry)
with open(token_file, 'w') as f:
    json.dump(data, f, indent=2)
PYEOF
else
    python3 - "$TOKEN_FILE" "$TOKEN_VALUE" "$LABEL" "$SCOPES_JSON" "$WORKSPACES_JSON" <<'PYEOF'
import json, sys
token_file, token_value, label = sys.argv[1], sys.argv[2], sys.argv[3]
scopes = json.loads(sys.argv[4])
workspace_scopes = json.loads(sys.argv[5])
entry = {
    'value': token_value,
    'scopes': scopes,
    'label': label,
}
if workspace_scopes is not None:
    entry['workspaceScopes'] = workspace_scopes
data = {'tokens': [entry]}
with open(token_file, 'w') as f:
    json.dump(data, f, indent=2)
PYEOF
    chmod 600 "$TOKEN_FILE"
fi

echo ""
echo "Token generated:"
echo "  Value:      ${TOKEN_VALUE}"
echo "  Label:      ${LABEL}"
echo "  Scopes:     ${SCOPES[*]}"
if [ ${#WORKSPACES[@]} -eq 0 ]; then
    echo "  Workspaces: (unscoped — accessible from every workspace)"
else
    echo "  Workspaces: ${WORKSPACES[*]}"
fi
echo "  File:       ${TOKEN_FILE}"
echo ""
echo "Use this token as the Bearer value when configuring Claude mobile."
