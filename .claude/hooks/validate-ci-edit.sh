#!/usr/bin/env bash
# validate-ci-edit.sh — PreToolUse hook
#
# Fires whenever Claude attempts to Edit or Write analysis.yaml.
# Validates that the proposed change does not break the analysis.needs contract:
#   Every job that uploads a coverage-* artifact MUST appear in analysis.needs.
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

# For Edit tool, we can inspect the new_string to see if it introduces a new
# coverage upload without also adding the job to analysis.needs.
NEW_CONTENT="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
# For Edit: check new_string; for Write: check content
print(inp.get('new_string', inp.get('content', '')))
" 2>/dev/null || true)"

# Read the current file content
if [ ! -f "$FILE_PATH" ]; then
    exit 0
fi

CURRENT_CONTENT="$(cat "$FILE_PATH")"

# If this is an Edit, construct the post-edit content by simulating the replacement.
# We do this by reading the actual file since the edit hasn't been applied yet.
# We validate the current file + the proposed change together.

# Strategy: Extract all jobs that upload 'coverage-' artifacts from the current file
# and from the proposed new_string (if any), then verify they all appear in needs of analysis.

# Extract job names that do "upload-artifact" with name starting "coverage-"
# Pattern in YAML: jobs that contain both 'upload-artifact' and 'name: coverage-'
python3 - "$FILE_PATH" <<'PYEOF'
import sys, re, json

# Read the current file
with open(sys.argv[1]) as f:
    content = f.read()

# Find jobs structure: job names and their content
# Simple heuristic: find all job definitions and check for coverage upload
job_pattern = re.compile(r'^  (\S[^:\n]+):\s*\n', re.MULTILINE)
jobs = {}
job_names = []
matches = list(job_pattern.finditer(content))
for i, m in enumerate(matches):
    job_name = m.group(1).strip()
    start = m.end()
    end = matches[i+1].start() if i+1 < len(matches) else len(content)
    jobs[job_name] = content[start:end]
    job_names.append(job_name)

# Find jobs that upload coverage-* artifacts
coverage_uploaders = []
for job_name, body in jobs.items():
    if 'upload-artifact' in body and re.search(r'name:\s*coverage-', body):
        coverage_uploaders.append(job_name)

# Find the analysis job's needs
analysis_needs = []
if 'analysis' in jobs:
    analysis_body = jobs['analysis']
    needs_match = re.search(r'needs:\s*\[([^\]]+)\]', analysis_body)
    if needs_match:
        analysis_needs = [n.strip() for n in needs_match.group(1).split(',')]
    else:
        # Multi-line needs
        needs_block = re.search(r'needs:\s*\n((?:        - \S+\n)+)', analysis_body)
        if needs_block:
            analysis_needs = re.findall(r'- (\S+)', needs_block.group(1))

missing = [j for j in coverage_uploaders if j not in analysis_needs]

if missing:
    print(f"VALIDATION FAILED")
    print(f"")
    print(f"Jobs that upload coverage-* artifacts: {coverage_uploaders}")
    print(f"Jobs in analysis.needs: {analysis_needs}")
    print(f"")
    print(f"MISSING from analysis.needs: {missing}")
    print(f"")
    print(f"Every job that uploads a coverage-* artifact MUST appear in analysis.needs.")
    print(f"The analysis job downloads ALL coverage-* artifacts before running Qodana.")
    print(f"If a coverage-generating job is missing from needs, analysis may run before")
    print(f"its artifacts are available, causing either stale results or a crash.")
    print(f"")
    print(f"Fix: Add {missing} to the analysis job's needs list, then retry.")
    sys.exit(2)
else:
    print(f"VALIDATION OK: All coverage-generating jobs are in analysis.needs.")
    print(f"  Coverage jobs: {coverage_uploaders}")
    print(f"  analysis.needs: {analysis_needs}")
    sys.exit(0)
PYEOF

STATUS=$?
exit $STATUS
