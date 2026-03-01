#!/usr/bin/env bash
#
# Generate a bearer token for the ar-manager MCP server.
#
# Usage:
#   ./generate-token.sh                          # full access (read+write+pipeline)
#   ./generate-token.sh "My label"               # full access with custom label
#   ./generate-token.sh "Dashboard" read         # read-only token
#   ./generate-token.sh "Agent" read write       # read+write, no pipeline
#
# The token is appended to the token file. If the file doesn't exist,
# it is created. The generated token value is printed to stdout.
#
set -euo pipefail

TOKEN_FILE="${AR_MANAGER_TOKEN_FILE:-${HOME}/.config/ar/manager-tokens.json}"
LABEL="${1:-cli-$(date +%Y%m%d)}"
shift 2>/dev/null || true

# Default scopes: full access
if [ $# -eq 0 ]; then
    SCOPES=("read" "write" "pipeline")
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

# Ensure directory exists
mkdir -p "$(dirname "$TOKEN_FILE")"

# Create or update the token file
if [ -f "$TOKEN_FILE" ]; then
    # Append to existing tokens array using python (available everywhere)
    python3 -c "
import json, sys
with open('$TOKEN_FILE') as f:
    data = json.load(f)
data.setdefault('tokens', []).append({
    'value': '$TOKEN_VALUE',
    'scopes': $SCOPES_JSON,
    'label': '$LABEL'
})
with open('$TOKEN_FILE', 'w') as f:
    json.dump(data, f, indent=2)
"
else
    # Create new token file
    python3 -c "
import json
data = {
    'tokens': [{
        'value': '$TOKEN_VALUE',
        'scopes': $SCOPES_JSON,
        'label': '$LABEL'
    }]
}
with open('$TOKEN_FILE', 'w') as f:
    json.dump(data, f, indent=2)
"
    chmod 600 "$TOKEN_FILE"
fi

echo ""
echo "Token generated:"
echo "  Value:  ${TOKEN_VALUE}"
echo "  Label:  ${LABEL}"
echo "  Scopes: ${SCOPES[*]}"
echo "  File:   ${TOKEN_FILE}"
echo ""
echo "Use this token as the Bearer value when configuring Claude mobile."
