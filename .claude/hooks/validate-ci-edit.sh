#!/usr/bin/env bash
# validate-ci-edit.sh — PreToolUse hook
#
# Fires whenever Claude attempts to Edit or Write analysis.yaml.
# Validates that the proposed change does not break the analysis.needs contract:
#   Every job that uploads a coverage-* artifact MUST appear in analysis.needs.
#
# Validates against the POST-EDIT content so that new coverage-uploading jobs
# introduced by the current edit are caught before the edit is applied.
#
# Exit 0  → allow the edit
# Exit 2  → BLOCK the edit; Claude must fix the problem first

set -euo pipefail

INPUT="$(cat)"
TOOL_NAME="$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_name',''))" 2>/dev/null || true)"
FILE_PATH="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('file_path', inp.get('path', '')))
" 2>/dev/null || true)"

# Only fire for analysis.yaml
if ! echo "$FILE_PATH" | grep -q "analysis\.yaml"; then
    exit 0
fi

# Read the current on-disk file content
if [ ! -f "$FILE_PATH" ]; then
    exit 0
fi

CURRENT_CONTENT="$(cat "$FILE_PATH")"

# Extract the proposed change from the tool input
NEW_STRING="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('new_string', inp.get('content', '')))
" 2>/dev/null || true)"

OLD_STRING="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('old_string', ''))
" 2>/dev/null || true)"

# Construct the post-edit content to validate against:
#   Write → use the new content directly
#   Edit  → apply old_string → new_string replacement to current content
#   (fall back to current content if replacement cannot be applied)
VALIDATION_CONTENT="$(TOOL_NAME="$TOOL_NAME" \
    CURRENT_CONTENT="$CURRENT_CONTENT" \
    OLD_STRING="$OLD_STRING" \
    NEW_STRING="$NEW_STRING" \
    python3 - <<'PYEOF'
import os, sys

tool = os.environ.get("TOOL_NAME", "")
current = os.environ.get("CURRENT_CONTENT", "")
old_str = os.environ.get("OLD_STRING", "")
new_str = os.environ.get("NEW_STRING", "")

if tool == "Write":
    print(new_str, end="")
elif tool == "Edit" and old_str and old_str in current:
    print(current.replace(old_str, new_str, 1), end="")
else:
    print(current, end="")
PYEOF
)"

# Validate the post-edit content
VALIDATION_CONTENT="$VALIDATION_CONTENT" python3 - <<'PYEOF'
import os, sys, re

content = os.environ.get("VALIDATION_CONTENT", "")

# Parse top-level job names and their bodies from the YAML jobs section
job_pattern = re.compile(r'^  (\S[^:\n]+):\s*\n', re.MULTILINE)
jobs = {}
matches = list(job_pattern.finditer(content))
for i, m in enumerate(matches):
    job_name = m.group(1).strip()
    start = m.end()
    end = matches[i+1].start() if i+1 < len(matches) else len(content)
    jobs[job_name] = content[start:end]

# Find jobs that upload coverage-* artifacts (excluding coverage-report,
# which is the merged output produced by analysis itself, not an input to it).
coverage_uploaders = []
for job_name, body in jobs.items():
    if 'upload-artifact' in body and re.search(r'name:\s*coverage-(?!report)', body):
        coverage_uploaders.append(job_name)

# Find the analysis job's needs
analysis_needs = []
if 'analysis' in jobs:
    analysis_body = jobs['analysis']
    needs_match = re.search(r'needs:\s*\[([^\]]+)\]', analysis_body)
    if needs_match:
        analysis_needs = [n.strip() for n in needs_match.group(1).split(',')]
    else:
        needs_block = re.search(r'needs:\s*\n((?:        - \S+\n)+)', analysis_body)
        if needs_block:
            analysis_needs = re.findall(r'- (\S+)', needs_block.group(1))

missing = [j for j in coverage_uploaders if j not in analysis_needs]

if missing:
    print("VALIDATION FAILED (checked against post-edit content)")
    print("")
    print(f"Jobs that upload coverage-* artifacts: {coverage_uploaders}")
    print(f"Jobs in analysis.needs: {analysis_needs}")
    print("")
    print(f"MISSING from analysis.needs: {missing}")
    print("")
    print("Every job that uploads a coverage-* artifact MUST appear in analysis.needs.")
    print("The analysis job downloads ALL coverage-* artifacts before running Qodana.")
    print("If a coverage-generating job is missing from needs, analysis may run before")
    print("its artifacts are available, causing either stale results or a crash.")
    print("")
    print(f"Fix: Add {missing} to the analysis job's needs list, then retry.")
    sys.exit(2)
else:
    print("VALIDATION OK: All coverage-generating jobs are in analysis.needs.")
    print(f"  Coverage jobs: {coverage_uploaders}")
    print(f"  analysis.needs: {analysis_needs}")
    sys.exit(0)
PYEOF

STATUS=$?
exit $STATUS
